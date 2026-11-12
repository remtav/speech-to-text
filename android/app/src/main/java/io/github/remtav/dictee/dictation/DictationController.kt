package io.github.remtav.dictee.dictation

import io.github.remtav.dictee.audio.AudioSource
import io.github.remtav.dictee.audio.AudioSourceException
import io.github.remtav.dictee.audio.SpeechChunk
import io.github.remtav.dictee.audio.VoiceActivityDetector
import io.github.remtav.dictee.stt.ModelSpec
import io.github.remtav.dictee.stt.Transcription
import io.github.remtav.dictee.text.Piece
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
)

interface DictationListener {
    fun onState(state: DictationState, message: String? = null) {}
    /** Input level, 0..1, a few times per second while listening. */
    fun onLevel(level: Float) {}
    fun onSpeech(active: Boolean) {}
    /** A final segment was transcribed and inserted. */
    fun onSegment(transcription: Transcription, inserted: String) {}
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

    private class PendingText(val session: Int, val pipeline: TextPipeline, val pieces: List<Piece>, val t: Transcription)

    /** Finals decoded while no field was available. Main thread only. */
    private val pending = ArrayList<PendingText>()

    /** Inserts text held back while the field was away. Call on the main thread when it is back. */
    fun deliverPending() {
        val sink = sinkProvider() ?: return
        val items = pending.filter { it.session == sessionId.get() }
        pending.clear()
        for (p in items) listener.onSegment(p.t, p.pipeline.apply(p.pieces, sink))
    }
    @Volatile private var run: Run? = null

    private sealed interface Job {
        val session: Int
        class Final(override val session: Int, val chunk: SpeechChunk) : Job
        class Partial(override val session: Int, val samples: FloatArray) : Job
        class End(override val session: Int, val error: String?) : Job
    }

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
        private val capture = Thread({ captureLoop() }, "dictation-capture").apply { priority = Thread.MAX_PRIORITY }
        private val decode = Thread({ decodeLoop() }, "dictation-decode")

        fun launch() {
            decode.start()
            capture.start()
        }

        fun abort() {
            stopRequested = true
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
            try {
                while (!stopRequested && sessionId.get() == id) {
                    val n = s.audio.read(chunk)
                    if (n < 0) break
                    if (n == 0) { Thread.sleep(5); continue }
                    if (s.audio.silenced) { error = "Micro coupé par le système (appel ?)"; break }
                    val samples = if (n == chunk.size) chunk else chunk.copyOf(n)
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
                    if (speech && !wasSpeech) {
                        speechStart = (totalSamples - LOOKBACK).coerceAtLeast(0)
                        post { listener.onSpeech(true) }
                    } else if (!speech && wasSpeech) {
                        post { listener.onSpeech(false) }
                    }
                    wasSpeech = speech

                    var popped = false
                    while (true) {
                        val seg = s.vad.poll() ?: break
                        enqueueFinal(seg)
                        popped = true
                    }
                    if (popped) {
                        // Next provisional text starts after what was just finalized.
                        speechStart = if (s.vad.isSpeech()) totalSamples - LOOKBACK / 2 else -1
                        if (finalsPending > 0) post { sinkProvider()?.clearComposing() }
                    }

                    if (s.partials && speech && speechStart >= 0 && finalsPending == 0 && !partialInFlight &&
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
            } catch (e: Exception) {
                error = "Erreur audio : ${e.message}"
            } finally {
                try { s.audio.stop() } catch (_: Exception) {}
            }
            if (sessionId.get() == id) {
                // Whatever was being said when the user tapped stop still counts.
                try {
                    s.vad.flush()
                    while (true) enqueueFinal(s.vad.poll() ?: break)
                } catch (_: Exception) {}
                if (error != null) setState(DictationState.FINISHING, error)
                jobs.offer(Job.End(id, error))
            }
            // The VAD belongs to this session: free its native memory now, not at GC.
            try { s.vad.release() } catch (_: Throwable) {}
            post { listener.onSpeech(false); listener.onLevel(0f) }
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
                    }
                }
            } catch (_: InterruptedException) {
                return
            } catch (e: Throwable) {
                error = "Erreur de transcription : ${e.cause?.message ?: e.message ?: e.javaClass.simpleName}"
                stopRequested = true
            }
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
    }

    // ------------------------------------------------------------------ helpers

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
        private const val CHUNK = 512 // 32 ms
        private const val RING_SECONDS = 25
        private const val LOOKBACK = RATE * 6 / 10 // VAD reports speech ~0.25-0.5 s late
        private const val MIN_PARTIAL_SAMPLES = RATE * 8 / 10
        private const val LEVEL_INTERVAL_MS = 50L

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
