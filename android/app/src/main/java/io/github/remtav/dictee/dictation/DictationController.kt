package io.github.remtav.dictee.dictation

import io.github.remtav.dictee.audio.AudioSource
import io.github.remtav.dictee.audio.AudioSourceException
import io.github.remtav.dictee.audio.SpeechChunk
import io.github.remtav.dictee.audio.VoiceActivityDetector
import io.github.remtav.dictee.stt.AudioBlock
import io.github.remtav.dictee.stt.BlockDictation
import io.github.remtav.dictee.stt.DictationResult
import io.github.remtav.dictee.stt.ModelSpec
import io.github.remtav.dictee.stt.Transcription
import io.github.remtav.dictee.stt.WaitControl
import io.github.remtav.dictee.stt.monotonicMillis
import io.github.remtav.dictee.text.Piece
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.log10
import kotlin.math.sqrt

enum class DictationState { IDLE, LISTENING, FINISHING, ERROR }

/** Everything a dictation session needs, snapshotted when it starts. */
class DictationSession(
    val spec: ModelSpec,
    val audio: AudioSource,
    val vad: VoiceActivityDetector,
    val pipeline: TextPipeline,
    /** Transcribes one chunk with the session's model; blocks while the model loads. */
    val transcribe: (FloatArray) -> Transcription,
    val partials: Boolean = true,
    /**
     * Whole-dictation mode (remote server, issue #13): when set, nothing is
     * transcribed while the user speaks. The whole audio (and the VAD segments,
     * kept for a local fallback) goes to this function once the dictation ends.
     */
    val whole: ((audio: FloatArray, segments: List<FloatArray>, control: WaitControl) -> DictationResult)? = null,
    /**
     * Block mode (remote server, issue #35): when set, the audio is cut into blocks at pauses while the user
     * speaks, and each block goes to the server at once (see [BlockDictation]); at the end only the last block
     * and the polish remain. [whole] stays the way for a server that cannot do blocks (no `POST /polish`).
     */
    val blocks: ((control: WaitControl) -> BlockDictation)? = null,
    /** Block size: closed at the first pause past [minBlockSeconds], at [maxBlockSeconds] at the latest. */
    val minBlockSeconds: Float = 15f,
    val maxBlockSeconds: Float = 30f,
    /** Longest whole (or block) dictation; recording stops there and what was said is sent. */
    val maxWholeSeconds: Int = 300,
    /** False when no local model can take over: "Annuler" would only drop the dictation, so it is hidden. */
    val canFallBack: Boolean = true,
    /**
     * Local dictation (issue #53, « Regrouper les énoncés (Whisper) »): the VAD's utterances are grouped before being
     * transcribed, see [LocalGrouping]. Null (the default): one transcription per utterance, as before.
     */
    val localGrouping: LocalGrouping? = null,
)

interface DictationListener {
    fun onState(state: DictationState, message: String? = null) {}
    /** Input level, 0..1, a few times per second while listening. */
    fun onLevel(level: Float) {}
    fun onSpeech(active: Boolean) {}
    /** A final segment was transcribed and inserted. */
    fun onSegment(transcription: Transcription, inserted: String) {}
    /** Whole-dictation mode: waiting for the server (true), or the wait is over (false). */
    fun onServerWait(waiting: Boolean) {}
    /** Information that is not an error (maximum duration reached). */
    fun onInfo(message: String) {}
    /** Whole-dictation mode: the result was inserted ([inserted] is what was typed). */
    fun onDictationResult(result: DictationResult, inserted: String) {}
    /** Block mode: [done] of the [total] blocks known so far are transcribed. */
    fun onBlocks(done: Int, total: Int) {}
}

/**
 * Tap-to-start / tap-to-stop dictation: audio → VAD → one transcription per
 * pause → text inserted through a [TextSink].
 *
 * Threads: a capture thread owns the audio source and the VAD; a decode thread
 * owns all model calls (finals in order, provisional decodes only when idle);
 * every [TextSink] call and listener callback goes through [mainExecutor].
 *
 * A session number guards against stale results: [cancel] (focus moved to
 * another field) bumps it, and anything decoded for an older session is
 * dropped instead of being typed into the wrong field.
 */
class DictationController(
    private val sinkProvider: () -> TextSink?,
    private val mainExecutor: Executor,
    private val listener: DictationListener,
    private val partialIntervalMillis: Long = 700,
) {
    @Volatile var state: DictationState = DictationState.IDLE
        private set

    private val sessionId = AtomicInteger(0)

    private class PendingText(
        val session: Int, val pipeline: TextPipeline, val pieces: List<Piece>, val t: Transcription,
        val result: DictationResult? = null,
    )

    /** Finals decoded while no field was available. Main thread only. */
    private val pending = ArrayList<PendingText>()

    /** Inserts text held back while the field was away. Call on the main thread when it is back. */
    fun deliverPending() {
        val sink = sinkProvider() ?: return
        val items = pending.filter { it.session == sessionId.get() }
        pending.clear()
        for (p in items) {
            val inserted = p.pipeline.apply(p.pieces, sink)
            p.result?.let { listener.onDictationResult(it, inserted) }
            listener.onSegment(p.t, inserted)
        }
    }
    @Volatile private var run: Run? = null

    private sealed interface Job {
        val session: Int
        class Final(override val session: Int, val chunk: SpeechChunk) : Job
        class Partial(override val session: Int, val samples: FloatArray) : Job
        class End(override val session: Int, val error: String?) : Job
        class Whole(override val session: Int, val audio: FloatArray, val segments: List<FloatArray>, val error: String?) : Job
        /** Block mode: one block, complete while the user still speaks. */
        class Block(override val session: Int, val block: AudioBlock) : Job
        /**
         * Block mode, end of the dictation: the [last] block, and the whole audio with every VAD segment while
         * the server's support for blocks was not known yet (to go whole for an older server).
         */
        class Finish(
            override val session: Int, val last: AudioBlock?, val whole: FloatArray?, val segments: List<FloatArray>,
            val error: String?,
        ) : Job
    }

    /** Block mode: whether this dictation goes by blocks or whole (older server), decided on the decode thread. */
    private enum class Decision { UNKNOWN, BLOCKS, WHOLE }

    val isActive: Boolean get() = state == DictationState.LISTENING || state == DictationState.FINISHING

    @Synchronized
    fun start(session: DictationSession) {
        if (isActive) return
        val id = sessionId.incrementAndGet()
        try {
            session.audio.start()
        } catch (e: AudioSourceException) {
            setState(DictationState.ERROR, e.message)
            return
        } catch (e: RuntimeException) {
            setState(DictationState.ERROR, "Micro indisponible : ${e.message}")
            return
        }
        setState(DictationState.LISTENING)
        run = Run(id, session).also { it.launch() }
    }

    /** Session whose whole dictation waits for the server, 0 for none. */
    @Volatile private var waitingFor = 0

    /** True while the current whole dictation waits for the server (the keyboard shows "Annuler"). */
    val waitingServer: Boolean get() = waitingFor != 0 && waitingFor == sessionId.get()

    /** Whether "Annuler" can hand the dictation to a local model. */
    val canCancelServerWait: Boolean get() = run?.s?.canFallBack == true

    /**
     * "Annuler" while waiting for the server: the server job is cancelled and the
     * kept audio is transcribed by the local fallback model.
     */
    fun cancelServerWait() {
        run?.control?.toLocal()
    }

    /** Stops listening; speech already captured is still transcribed and inserted. */
    fun stop() {
        if (state != DictationState.LISTENING) return
        run?.stopRequested = true
        setState(DictationState.FINISHING)
    }

    /** Stops at once and drops everything not yet inserted (field changed, IME closed). */
    @Synchronized
    fun cancel() {
        if (!isActive) return
        sessionId.incrementAndGet()
        mainExecutor.execute { pending.clear() }
        run?.abort()
        run = null
        mainExecutor.execute { sinkProvider()?.clearComposing() }
        setState(DictationState.IDLE)
    }

    fun toggle(sessionFactory: () -> DictationSession?) {
        when (state) {
            DictationState.LISTENING -> stop()
            DictationState.FINISHING -> {}
            else -> sessionFactory()?.let { start(it) }
        }
    }

    /** One dictation session: its own job queue and threads. */
    private inner class Run(val id: Int, val s: DictationSession) {
        private val jobs = LinkedBlockingDeque<Job>()
        @Volatile var finalsPending = 0
        @Volatile var partialInFlight = false
        @Volatile var stopRequested = false
        val control = WaitControl()
        private val blockMode = s.blocks != null
        /** No transcription while the user speaks (the server gets the whole dictation, or blocks). */
        private val wholeMode = s.whole != null || blockMode
        @Volatile private var decision = if (blockMode) Decision.UNKNOWN else Decision.WHOLE
        /** Keep the whole audio and every segment: whole mode, or blocks not confirmed yet. */
        private fun keepWhole() = s.whole != null && decision != Decision.BLOCKS
        private fun cutBlocks() = blockMode && decision != Decision.WHOLE
        private val blocksQueued = AtomicInteger()
        private val blocksDone = AtomicInteger()
        /** Audio of the blocks queued and not transcribed yet. */
        private val pendingSamples = AtomicLong()
        /** End of the capture (monotonic ms, 0 while capturing), and the audio still to transcribe then. */
        @Volatile private var stoppedAt = 0L
        @Volatile private var pendingAtStop = 0L
        /** The decode thread has ended (also after an error): nobody will end a wait started now. */
        @Volatile private var decodeEnded = false
        private val capture = Thread({ captureLoop() }, "dictation-capture").apply { priority = Thread.MAX_PRIORITY }
        private val decode = Thread({ decodeLoop() }, "dictation-decode")

        fun launch() {
            decode.start()
            capture.start()
        }

        fun abort() {
            stopRequested = true
            control.abandon()
            jobs.clear()
            capture.interrupt()
            decode.interrupt()
        }

        // ------------------------------------------------------------------ capture

        private fun captureLoop() {
            val chunk = FloatArray(CHUNK)
            val ring = Ring(RING_SECONDS * RATE)
            var totalSamples = 0L
            var speechStart = -1L
            var lastPartialAt = 0L
            var lastLevelAt = 0L
            var wasSpeech = false
            var error: String? = null
            // Whole-dictation mode: all the audio, and the VAD segments for a local fallback.
            val maxWhole = s.maxWholeSeconds.toLong() * RATE
            var whole = if (keepWhole()) FloatArray(RATE * 30) else FloatArray(0)
            val cutter = if (!blockMode) null else BlockCutter(
                (s.minBlockSeconds * RATE).toInt(),
                (s.maxBlockSeconds * RATE).toInt() - CHUNK,
                { n -> ring.last(n) },
            ) { block -> enqueueBlock(block) }
            // Issue #53: groups of utterances, each transcribed and inserted like one utterance today.
            val grouper = if (wholeMode) null else s.localGrouping?.cutter({ n -> ring.last(n) }) { block ->
                enqueueFinal(SpeechChunk(block.startSample, block.audio))
            }
            var wholeLen = 0
            var firstSpeech = -1L
            val segments = ArrayList<FloatArray>()
            try {
                while (!stopRequested && sessionId.get() == id) {
                    val n = s.audio.read(chunk)
                    if (n < 0) break
                    if (n == 0) { Thread.sleep(5); continue }
                    if (s.audio.silenced) { error = "Micro coupé par le système (appel ?)"; break }
                    val samples = if (n == chunk.size) chunk else chunk.copyOf(n)
                    if (keepWhole()) {
                        if (wholeLen + n > whole.size) whole = whole.copyOf(minOf(maxOf(whole.size * 2, wholeLen + n), maxWhole.toInt()))
                        System.arraycopy(samples, 0, whole, wholeLen, n)
                        wholeLen += n
                    } else if (whole.isNotEmpty()) {
                        // The server takes blocks: the whole-dictation buffer is not needed any more.
                        whole = FloatArray(0)
                        wholeLen = 0
                        segments.clear()
                    }
                    ring.write(samples)
                    totalSamples += n
                    s.vad.accept(samples.copyOf())

                    val now = System.currentTimeMillis()
                    if (now - lastLevelAt >= LEVEL_INTERVAL_MS) {
                        lastLevelAt = now
                        val level = level(samples)
                        post { listener.onLevel(level) }
                    }

                    val speech = s.vad.isSpeech()
                    var onset = -1L
                    if (speech && !wasSpeech) {
                        speechStart = (totalSamples - LOOKBACK).coerceAtLeast(0)
                        onset = speechStart
                        if (firstSpeech < 0) firstSpeech = speechStart
                        post { listener.onSpeech(true) }
                    } else if (!speech && wasSpeech) {
                        post { listener.onSpeech(false) }
                    }
                    wasSpeech = speech
                    if (cutBlocks()) cutter?.onAudio(samples, n, totalSamples, onset)
                    grouper?.onAudio(samples, n, totalSamples, onset)

                    var popped = false
                    while (true) {
                        val seg = s.vad.poll() ?: break
                        acceptSegment(seg, segments, cutter, grouper)
                        popped = true
                    }
                    if (cutBlocks()) cutter?.afterChunk(totalSamples, speech, popped)
                    grouper?.afterChunk(totalSamples, speech, popped)
                    if (wholeMode && totalSamples + CHUNK > maxWhole) {
                        val info = MAX_DURATION_MESSAGE.format(s.maxWholeSeconds / 60)
                        post { listener.onInfo(info) }
                        break
                    }
                    if (popped) {
                        // Next provisional text starts after what was just finalized.
                        speechStart = if (s.vad.isSpeech()) totalSamples - LOOKBACK / 2 else -1
                        if (finalsPending > 0) post { sinkProvider()?.clearComposing() }
                    }

                    if (s.partials && !wholeMode && speech && speechStart >= 0 && finalsPending == 0 && !partialInFlight &&
                        now - lastPartialAt >= partialIntervalMillis
                    ) {
                        val len = (totalSamples - speechStart).coerceAtMost(ring.capacity.toLong()).toInt()
                        if (len >= MIN_PARTIAL_SAMPLES) {
                            lastPartialAt = now
                            partialInFlight = true
                            jobs.offer(Job.Partial(id, ring.last(len)))
                        }
                    }
                }
            } catch (_: InterruptedException) {
                // cancel()
            } catch (e: Throwable) {
                // Also an OutOfMemoryError while growing the whole-dictation buffer: keep what we have.
                error = "Erreur audio : ${e.message ?: e.javaClass.simpleName}"
            } finally {
                try { s.audio.stop() } catch (_: Exception) {}
            }
            if (sessionId.get() == id) {
                // Whatever was being said when the user tapped stop still counts.
                try {
                    s.vad.flush()
                    while (true) {
                        val seg = s.vad.poll() ?: break
                        acceptSegment(seg, segments, cutter, grouper)
                    }
                    // The group in progress when the user tapped stop (never after cancel: the session changed).
                    grouper?.finish()?.let { enqueueFinal(SpeechChunk(it.startSample, it.audio)) }
                } catch (_: Exception) {}
                if (error != null) setState(DictationState.FINISHING, error)
                else if (wholeMode && state == DictationState.LISTENING) setState(DictationState.FINISHING) // source ended by itself
                if (blockMode) {
                    val last = if (cutBlocks()) cutter?.finish() else null
                    val keep = keepWhole()
                    val said = segments.isNotEmpty() || firstSpeech >= 0
                    val from = firstSpeech.coerceIn(0, wholeLen.toLong()).toInt()
                    val audio = if (!keep || !said) null else try {
                        whole.copyOfRange(from, wholeLen)
                    } catch (_: OutOfMemoryError) {
                        FloatArray(0) // the local fallback still has the segments
                    }
                    whole = FloatArray(0)
                    last?.let { pendingSamples.addAndGet(it.audio.size.toLong()) }
                    pendingAtStop = pendingSamples.get()
                    stoppedAt = monotonicMillis()
                    // The user waits from now on: "Transcription et polissage…" and "Annuler".
                    if (last != null || blocksQueued.get() > 0 || audio != null) {
                        setWaiting(id, true)
                        // The decode thread stopped on an error meanwhile: it already ended the wait, do it again.
                        if (decodeEnded) setWaiting(id, false)
                    }
                    jobs.offer(Job.Finish(id, last, audio, if (keep) ArrayList(segments) else emptyList(), error))
                } else if (wholeMode) {
                    stoppedAt = monotonicMillis()
                    // Nothing was said: nothing to send. Otherwise drop the leading silence only.
                    val said = segments.isNotEmpty() || firstSpeech >= 0
                    val from = firstSpeech.coerceIn(0, wholeLen.toLong()).toInt()
                    val audio = if (!said) FloatArray(0) else try {
                        if (from == 0 && wholeLen == whole.size) whole else whole.copyOfRange(from, wholeLen)
                    } catch (_: OutOfMemoryError) {
                        FloatArray(0) // the local fallback still has the segments
                    }
                    whole = FloatArray(0)
                    jobs.offer(Job.Whole(id, audio, segments, error))
                } else {
                    jobs.offer(Job.End(id, error))
                }
            }
            // The VAD belongs to this session: free its native memory now, not at GC.
            try { s.vad.release() } catch (_: Throwable) {}
            post { listener.onSpeech(false); listener.onLevel(0f) }
        }

        /**
         * A VAD segment: kept for a whole dictation, given to the block cutter, or transcribed (local models), alone or
         * in a group ([grouper], issue #53).
         */
        private fun acceptSegment(seg: SpeechChunk, segments: MutableList<FloatArray>, cutter: BlockCutter?, grouper: BlockCutter?) {
            if (!wholeMode) return if (grouper != null) grouper.onSegment(seg) else enqueueFinal(seg)
            if (keepWhole()) segments += seg.samples
            if (cutBlocks()) cutter?.onSegment(seg)
        }

        private fun enqueueBlock(block: AudioBlock) {
            blocksQueued.incrementAndGet()
            pendingSamples.addAndGet(block.audio.size.toLong())
            jobs.offer(Job.Block(id, block))
        }

        private fun enqueueFinal(seg: SpeechChunk) {
            synchronized(jobs) { finalsPending++ }
            // Finals jump ahead of a queued provisional decode, which they make obsolete.
            jobs.removeIf { it is Job.Partial }
            jobs.offer(Job.Final(id, seg))
        }

        // ------------------------------------------------------------------- decode

        private fun decodeLoop() {
            var error: String? = null
            try {
                val blocks = s.blocks?.let { startBlocks(it) }
                while (sessionId.get() == id) {
                    val job = jobs.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    if (job.session != id) continue
                    when (job) {
                        is Job.Partial -> {
                            try {
                                if (finalsPending == 0) {
                                    val t = s.transcribe(job.samples)
                                    if (finalsPending == 0 && sessionId.get() == id && state == DictationState.LISTENING) {
                                        post(id) { sinkProvider()?.let { s.pipeline.preview(t.text, it) } }
                                    }
                                }
                            } finally {
                                partialInFlight = false
                            }
                        }
                        is Job.Final -> {
                            val t = try {
                                s.transcribe(job.chunk.samples)
                            } finally {
                                synchronized(jobs) { finalsPending-- }
                            }
                            val pieces: List<Piece> = s.pipeline.prepare(t.text, s.spec.family)
                            post(id) {
                                val sink = sinkProvider()
                                if (sink != null && pending.isEmpty()) {
                                    listener.onSegment(t, s.pipeline.apply(pieces, sink))
                                } else {
                                    // No field right now (activity being recreated): keep the text
                                    // until the same field is back, see deliverPending().
                                    pending += PendingText(id, s.pipeline, pieces, t)
                                    if (sink != null) deliverPending()
                                }
                            }
                        }
                        is Job.End -> {
                            error = job.error
                            break
                        }
                        is Job.Whole -> {
                            error = job.error
                            if (job.audio.isNotEmpty() || job.segments.isNotEmpty()) transcribeWhole(job)
                            break
                        }
                        is Job.Block -> {
                            // Queued before the server turned out to take no blocks: the whole audio covers it.
                            if (blocks != null && decision == Decision.BLOCKS) transcribeBlock(blocks, job.block, last = false)
                        }
                        is Job.Finish -> {
                            error = job.error
                            if (blocks == null || decision == Decision.WHOLE) {
                                val audio = job.whole ?: FloatArray(0)
                                if (audio.isNotEmpty() || job.segments.isNotEmpty()) transcribeWhole(Job.Whole(id, audio, job.segments, job.error))
                            } else {
                                job.last?.let { transcribeBlock(blocks, it, last = true) }
                                if (blocks.blocks > 0) finishBlocks(blocks)
                            }
                            break
                        }
                    }
                }
            } catch (_: InterruptedException) {
                setWaiting(id, false)
                return
            } catch (e: Throwable) {
                val detail = if (e is io.github.remtav.dictee.stt.RemoteException) e.message else e.cause?.message ?: e.message
                error = "Erreur de transcription : ${detail ?: e.javaClass.simpleName}"
                stopRequested = true
            }
            decodeEnded = true
            setWaiting(id, false)
            if (sessionId.get() == id) {
                post(id) {
                    synchronized(this@DictationController) {
                        sinkProvider()?.clearComposing()
                        if (sessionId.get() == id) {
                            setStateNow(if (error != null) DictationState.ERROR else DictationState.IDLE, error)
                        }
                    }
                }
            }
        }

        /**
         * Block mode: the dictation's [BlockDictation], and whether the server takes blocks (asked once, before
         * the first block; the capture keeps the whole audio until then).
         */
        private fun startBlocks(factory: (WaitControl) -> BlockDictation): BlockDictation? {
            // The phone taking over after the end of the dictation ends the wait ("Annuler" disappears).
            control.onLocalStart { if (stoppedAt > 0) setWaiting(id, false) }
            val blocks = try {
                factory(control)
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val ok = try {
                blocks?.prepare() ?: false
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                true
            }
            decision = if (blocks != null && (ok || s.whole == null)) Decision.BLOCKS else Decision.WHOLE
            return blocks
        }

        private fun transcribeBlock(blocks: BlockDictation, block: AudioBlock, last: Boolean) {
            if (stoppedAt > 0) blocks.endPhase(pendingAtStop / RATE.toFloat())
            try {
                blocks.add(block, last)
            } finally {
                pendingSamples.addAndGet(-block.audio.size.toLong())
            }
            val done = blocksDone.incrementAndGet()
            val total = blocksQueued.get() + if (last) 1 else 0
            post(id) { listener.onBlocks(done, maxOf(done, total)) }
        }

        /** Block mode, after the last block: polish, then one insertion. */
        private fun finishBlocks(blocks: BlockDictation) {
            blocks.endPhase(pendingAtStop / RATE.toFloat())
            val result = try {
                blocks.finish()
            } finally {
                setWaiting(id, false)
            }
            if (control.mode == WaitControl.Mode.ABANDON || sessionId.get() != id) return
            deliver(result.copy(waitMillis = monotonicMillis() - stoppedAt))
        }

        /** One request for the whole dictation; the result is inserted in one go. */
        private fun transcribeWhole(job: Job.Whole) {
            setWaiting(id, true)
            // The local fallback ends the server wait: the keyboard drops "Annuler" at once.
            control.onLocalStart { setWaiting(id, false) }
            val wholeFn = s.whole!!
            val result = try {
                wholeFn(job.audio, job.segments, control)
            } finally {
                setWaiting(id, false)
            }
            if (control.mode == WaitControl.Mode.ABANDON || sessionId.get() != id) return
            deliver(if (stoppedAt > 0) result.copy(waitMillis = monotonicMillis() - stoppedAt) else result)
        }

        /** Inserts a whole result in one go (or keeps it until the field is back). */
        private fun deliver(result: DictationResult) {
            val pieces = result.texts.flatMap { s.pipeline.prepare(it, s.spec.family) }
            post(id) {
                val sink = sinkProvider()
                val t = Transcription(result.texts.joinToString(" "), result.language, result.totalMillis, result.audioSeconds)
                if (sink != null && pending.isEmpty()) {
                    val inserted = s.pipeline.apply(pieces, sink)
                    listener.onDictationResult(result, inserted)
                    listener.onSegment(t, inserted)
                } else {
                    pending += PendingText(id, s.pipeline, pieces, t, result)
                    if (sink != null) deliverPending()
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Per session: a dropped dictation still finishing its request in the background
     * must not end the wait of the next one (or start it).
     */
    @Synchronized
    private fun setWaiting(id: Int, waiting: Boolean) {
        if (waiting) {
            if (waitingFor == id || sessionId.get() != id) return
            waitingFor = id
        } else {
            if (waitingFor != id) return
            waitingFor = 0
        }
        post(id) { listener.onServerWait(waiting) }
    }

    private fun post(block: () -> Unit) = mainExecutor.execute(block)

    /** Posts [block] only if session [id] is still current when it runs. */
    private fun post(id: Int, block: () -> Unit) = mainExecutor.execute {
        if (sessionId.get() == id) block()
    }

    private fun setState(s: DictationState, message: String? = null) {
        state = s
        post { listener.onState(s, message) }
    }

    private fun setStateNow(s: DictationState, message: String?) {
        state = s
        listener.onState(s, message)
    }

    private class Ring(val capacity: Int) {
        private val buf = FloatArray(capacity)
        private var pos = 0
        private var filled = 0

        fun write(s: FloatArray) {
            for (v in s) {
                buf[pos] = v
                pos = (pos + 1) % capacity
            }
            filled = minOf(capacity, filled + s.size)
        }

        fun last(n: Int): FloatArray {
            val len = minOf(n, filled)
            val out = FloatArray(len)
            var start = pos - len
            if (start < 0) start += capacity
            for (i in 0 until len) out[i] = buf[(start + i) % capacity]
            return out
        }
    }

    companion object {
        const val RATE = 16000
        internal const val CHUNK = 512 // 32 ms
        private const val RING_SECONDS = 25
        internal const val LOOKBACK = RATE * 6 / 10 // VAD reports speech ~0.25-0.5 s late
        private const val MIN_PARTIAL_SAMPLES = RATE * 8 / 10
        private const val LEVEL_INTERVAL_MS = 50L
        const val MAX_DURATION_MESSAGE = "Durée maximale atteinte (%d min) : envoi de la dictée"

        fun level(samples: FloatArray): Float {
            if (samples.isEmpty()) return 0f
            var sum = 0.0
            for (v in samples) sum += v * v
            val rms = sqrt(sum / samples.size)
            if (rms <= 1e-6) return 0f
            val db = 20 * log10(rms) // -60 dB (silence) .. 0 dB
            return ((db + 60) / 60).toFloat().coerceIn(0f, 1f)
        }
    }
}
