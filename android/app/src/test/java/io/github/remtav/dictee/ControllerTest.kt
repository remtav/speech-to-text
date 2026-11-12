package io.github.remtav.dictee

import io.github.remtav.dictee.audio.AudioSource
import io.github.remtav.dictee.audio.PcmAudio
import io.github.remtav.dictee.audio.PcmAudioSource
import io.github.remtav.dictee.audio.SpeechChunk
import io.github.remtav.dictee.audio.VoiceActivityDetector
import io.github.remtav.dictee.dictation.DictationController
import io.github.remtav.dictee.dictation.DictationListener
import io.github.remtav.dictee.dictation.DictationSession
import io.github.remtav.dictee.dictation.DictationState
import io.github.remtav.dictee.dictation.StringSink
import io.github.remtav.dictee.dictation.TextPipeline
import io.github.remtav.dictee.dictation.TextSink
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.Transcription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Energy VAD for tests: non-silent samples are speech; [minSilence] of silence ends an utterance. */
class EnergyVad(private val minSilence: Int = 8000, private val maxSpeech: Int = 16000 * 20) : VoiceActivityDetector {
    private var pos = 0L
    private var current: ArrayList<Float>? = null
    private var start = 0L
    private var silence = 0
    private val out = ArrayDeque<SpeechChunk>()

    override fun accept(samples: FloatArray) {
        for (s in samples) {
            val loud = kotlin.math.abs(s) > 0.01f
            val cur = current
            if (cur == null) {
                if (loud) { current = arrayListOf(s); start = pos; silence = 0 }
            } else {
                cur += s
                silence = if (loud) 0 else silence + 1
                if (silence >= minSilence || cur.size >= maxSpeech) finish()
            }
            pos++
        }
    }

    private fun finish() {
        val cur = current ?: return
        out.addLast(SpeechChunk(start, cur.toFloatArray()))
        current = null
    }

    override fun isSpeech() = current != null
    override fun poll(): SpeechChunk? = out.removeFirstOrNull()
    override fun flush() = finish()
    override fun reset() { current = null; out.clear() }
    override fun release() {}
}

/** "Speech": noise bursts of [seconds]. */
fun burst(seconds: Float, rng: Random = Random(1)) =
    PcmAudio(FloatArray((seconds * 16000).toInt()) { (rng.nextFloat() - 0.5f) * 0.4f }, 16000)

class RecordingListener : DictationListener {
    val states: MutableList<Pair<DictationState, String?>> = Collections.synchronizedList(ArrayList())
    val segments: MutableList<String> = Collections.synchronizedList(ArrayList())
    @Volatile var latch = CountDownLatch(1)

    override fun onState(state: DictationState, message: String?) {
        states += state to message
        if (state == DictationState.IDLE || state == DictationState.ERROR) latch.countDown()
    }

    override fun onSegment(transcription: Transcription, inserted: String) {
        segments += inserted
    }

    fun awaitEnd() = assertTrue("dictation did not finish", latch.await(20, TimeUnit.SECONDS))
}

class DictationControllerTest {
    private val spec = ModelCatalog.parakeet

    private fun session(
        audio: AudioSource,
        engine: ScriptedEngine,
        partials: Boolean = false,
        pipeline: TextPipeline = TextPipeline(),
    ) = DictationSession(spec, audio, EnergyVad(), pipeline, { engine.transcribe(it) }, partials)

    private fun controller(sink: () -> TextSink?, listener: DictationListener) =
        DictationController(sink, { it.run() }, listener, partialIntervalMillis = 100)

    @Test fun segmentsAreInsertedWithSpacingAndCapitals() {
        val sink = StringSink()
        val l = RecordingListener()
        val engine = ScriptedEngine(spec, listOf("bonjour tout le monde.", "Et voici la suite", "de la phrase."), 0)
        val c = controller({ sink }, l)
        val audio = PcmAudioSource(listOf(burst(1f), burst(1.2f), burst(0.8f)), realTime = false, gapSeconds = 1f)
        c.start(session(audio, engine))
        l.awaitEnd()
        assertEquals("Bonjour tout le monde. Et voici la suite de la phrase.", sink.text)
        assertEquals(3, engine.calls.get())
        assertEquals(DictationState.IDLE, c.state)
    }

    @Test fun stopFinalizesSpeechInProgress() {
        val sink = StringSink()
        val l = RecordingListener()
        val engine = ScriptedEngine(spec, listOf("premier.", "second en cours"), 0)
        val c = controller({ sink }, l)
        // Real-time pacing so stop() lands in the middle of the second burst.
        val audio = PcmAudioSource(listOf(burst(0.6f), burst(3f)), realTime = true, gapSeconds = 0.8f, leadSeconds = 0.1f)
        c.start(session(audio, engine))
        Thread.sleep(2300)
        c.stop()
        l.awaitEnd()
        assertEquals("Premier. Second en cours", sink.text)
    }

    @Test fun cancelDropsPendingResults() {
        val sink = StringSink()
        val l = RecordingListener()
        val engine = ScriptedEngine(spec, listOf("ne doit pas apparaître"), 1500)
        val c = controller({ sink }, l)
        val audio = PcmAudioSource(listOf(burst(0.5f)), realTime = false)
        c.start(session(audio, engine))
        Thread.sleep(400) // the segment is being decoded
        c.cancel()
        Thread.sleep(1800)
        assertEquals("", sink.text)
        assertEquals(DictationState.IDLE, c.state)
    }

    @Test fun resultsForAnotherFieldAreDropped() {
        val field1 = StringSink()
        var current: TextSink? = field1
        val l = RecordingListener()
        val engine = ScriptedEngine(spec, listOf("un.", "deux."), 0)
        val c = controller({ current }, l)
        val audio = PcmAudioSource(listOf(burst(0.5f), burst(0.5f)), realTime = false)
        current = null // focus moved before anything was decoded: the sink provider refuses
        c.start(session(audio, engine))
        l.awaitEnd()
        assertEquals("", field1.text)
    }

    @Test fun fieldAwayMidDictationHoldsTextUntilItIsBack() {
        val field = StringSink()
        var available = true
        val l = RecordingListener()
        val engine = ScriptedEngine(spec, listOf("un.", "deux.", "trois."), 0)
        lateinit var c: DictationController
        c = DictationController({ if (available) field else null }, { it.run() }, object : DictationListener by l {
            override fun onSegment(transcription: Transcription, inserted: String) {
                l.onSegment(transcription, inserted)
                // After the first segment, the field goes away (activity recreated) for a while.
                if (inserted.contains("Un.")) available = false
            }
        }, partialIntervalMillis = 100)
        val audio = PcmAudioSource(listOf(burst(0.5f), burst(0.5f), burst(0.5f)), realTime = false)
        c.start(session(audio, engine))
        l.awaitEnd()
        assertEquals("Un.", field.text)
        available = true
        c.deliverPending()
        assertEquals("Un. Deux. Trois.", field.text)
    }

    @Test fun cancelAfterFieldChangeDropsHeldText() {
        val field = StringSink()
        var available = false
        val l = RecordingListener()
        val c = controller({ if (available) field else null }, l)
        c.start(session(PcmAudioSource(listOf(burst(0.5f)), realTime = false), ScriptedEngine(spec, listOf("perdu."), 0)))
        l.awaitEnd()
        c.cancel() // no-op once idle: held text stays tied to the finished session
        available = true
        c.deliverPending()
        assertEquals("Perdu.", field.text)
        // A new session discards anything older.
        val field2 = StringSink()
        var avail2 = false
        val l2 = RecordingListener()
        val c2 = controller({ if (avail2) field2 else null }, l2)
        c2.start(session(PcmAudioSource(listOf(burst(0.5f)), realTime = true), ScriptedEngine(spec, listOf("jamais."), 400)))
        Thread.sleep(300)
        c2.cancel()
        Thread.sleep(800)
        avail2 = true
        c2.deliverPending()
        assertEquals("", field2.text)
    }

    @Test fun silencedMicrophoneStopsWithMessageAndKeepsSpeech() {
        val sink = StringSink()
        val l = RecordingListener()
        val engine = ScriptedEngine(spec, listOf("dit avant l'appel."), 0)
        val c = controller({ sink }, l)
        val inner = PcmAudioSource(listOf(burst(0.7f), burst(5f)), realTime = false, gapSeconds = 0.7f, leadSeconds = 0.1f)
        var reads = 0
        val audio = object : AudioSource by inner {
            override fun read(buffer: FloatArray): Int { reads++; return inner.read(buffer) }
            override val silenced: Boolean get() = reads > 60 // ~1.9 s in: a call starts
        }
        c.start(session(audio, engine))
        l.awaitEnd()
        assertEquals("Dit avant l'appel.", sink.text)
        assertTrue(l.states.any { it.second?.contains("appel") == true })
    }

    @Test fun longDictationTranscribesEverySegmentWithBoundedSegments() {
        val sink = StringSink()
        val l = RecordingListener()
        val n = 40
        val engine = ScriptedEngine(spec, (1..n).map { "phrase $it." }, 0)
        val c = controller({ sink }, l)
        val rng = Random(7)
        // 40 utterances, one of them 50 s long: the VAD's 20 s cap splits it.
        val clips = (1 until n).map { burst(1f + rng.nextFloat() * 3, rng) }
        val audio = PcmAudioSource(clips + listOf(burst(50f, rng)), realTime = false, gapSeconds = 0.8f)
        c.start(session(audio, engine))
        l.awaitEnd()
        val expectedCalls = (n - 1) + 3 // 50 s -> 20 + 20 + 10
        assertEquals(expectedCalls, engine.calls.get())
        assertTrue(engine.durations.all { it <= 20.01f })
        assertTrue(sink.text.startsWith("Phrase 1. Phrase 2."))
        assertTrue(sink.text.contains("Phrase 40."))
    }

    @Test fun provisionalTextIsShownThenReplaced() {
        val base = StringSink()
        val composings = Collections.synchronizedList(ArrayList<String>())
        val recording = object : TextSink {
            override fun textBeforeCursor(max: Int) = base.textBeforeCursor(max)
            override fun commit(text: String) = base.commit(text)
            override fun setComposing(text: String) { composings += text; base.setComposing(text) }
            override fun clearComposing() = base.clearComposing()
            override fun deleteBefore(count: Int) = base.deleteBefore(count)
        }
        val l = RecordingListener()
        // Every call returns the same text: provisional decodes and the final alike.
        val engine = ScriptedEngine(spec, List(100) { "en train de parler" }, 20)
        val c = DictationController({ recording }, { it.run() }, l, partialIntervalMillis = 200)
        val audio = PcmAudioSource(listOf(burst(3f)), realTime = true, leadSeconds = 0.1f, tailSeconds = 1f)
        c.start(session(audio, engine, partials = true))
        l.awaitEnd()
        assertTrue("expected provisional text", composings.any { it == "En train de parler" })
        assertEquals("En train de parler", base.text)
        assertEquals("", base.composing)
    }

    @Test fun microphoneFailureReportsError() {
        val l = RecordingListener()
        val c = controller({ StringSink() }, l)
        val broken = object : AudioSource {
            override fun start() = throw io.github.remtav.dictee.audio.AudioSourceException("Micro indisponible")
            override fun read(buffer: FloatArray) = -1
            override fun stop() {}
        }
        c.start(session(broken, ScriptedEngine(spec, emptyList(), 0)))
        l.awaitEnd()
        assertEquals(DictationState.ERROR, c.state)
        assertEquals("Micro indisponible", l.states.last().second)
    }

    @Test fun levelIsNormalized() {
        assertEquals(0f, DictationController.level(FloatArray(100)), 0f)
        assertTrue(DictationController.level(FloatArray(100) { 0.5f }) > 0.8f)
    }
}
