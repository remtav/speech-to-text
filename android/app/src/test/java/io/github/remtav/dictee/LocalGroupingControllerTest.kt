package io.github.remtav.dictee

import io.github.remtav.dictee.audio.AudioSource
import io.github.remtav.dictee.audio.PcmAudio
import io.github.remtav.dictee.dictation.DictationController
import io.github.remtav.dictee.dictation.DictationSession
import io.github.remtav.dictee.dictation.DictationState
import io.github.remtav.dictee.dictation.LocalCut
import io.github.remtav.dictee.dictation.LocalGrouping
import io.github.remtav.dictee.dictation.StringSink
import io.github.remtav.dictee.dictation.TextPipeline
import io.github.remtav.dictee.dictation.TextSink
import io.github.remtav.dictee.stt.BenchSample
import io.github.remtav.dictee.stt.Benchmark
import io.github.remtav.dictee.stt.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Issue #53 in [DictationController]: off (the default), a local dictation decodes exactly the VAD's utterances, as
 * before; on, the groups of [LocalCut] (the replay used by the comparison screen and the host bench), with the cap,
 * stop, cancel and field change.
 */
class LocalGroupingControllerTest {
    private val spec = ModelCatalog.whisperTurbo
    private val rate = 16000

    /** Plays [data] in chunks of whatever the controller asks (512), optionally in real time. */
    private class ArraySource(private val data: FloatArray, private val realTime: Boolean) : AudioSource {
        private var pos = 0
        private var t0 = 0L
        @Volatile private var stopped = false
        override fun start() { pos = 0; t0 = System.nanoTime() }
        override fun read(buffer: FloatArray): Int {
            if (stopped || pos >= data.size) return -1
            val n = minOf(buffer.size, data.size - pos)
            if (realTime) {
                val wait = t0 + (pos + n).toLong() * 1_000_000_000L / 16000 - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            }
            data.copyInto(buffer, 0, pos, pos + n)
            pos += n
            return n
        }
        override fun stop() { stopped = true }
    }

    /** Noise bursts (positive seconds) and silences (negative). */
    private fun audio(vararg parts: Float, seed: Int = 1): FloatArray {
        val rng = Random(seed)
        val out = ArrayList<Float>()
        for (p in parts) repeat((kotlin.math.abs(p) * rate).toInt()) { out += if (p > 0) (rng.nextFloat() - 0.5f) * 0.4f else 0f }
        return out.toFloatArray()
    }

    private fun session(data: FloatArray, engine: ScriptedEngine, grouping: LocalGrouping?, realTime: Boolean = false) =
        DictationSession(spec, ArraySource(data, realTime), EnergyVad(), TextPipeline(), { engine.transcribe(it) }, false,
            localGrouping = grouping)

    private fun controller(sink: () -> TextSink?, l: RecordingListener) = DictationController(sink, { it.run() }, l, partialIntervalMillis = 100)

    /** Lengths, in samples, of what the controller decoded. */
    private fun decoded(engine: ScriptedEngine) = engine.durations.map { Math.round(it * rate) }

    @Test fun groupingIsOffByDefault() {
        val s = DictationSession(spec, ArraySource(FloatArray(0), false), EnergyVad(), TextPipeline(), { error("unused") })
        assertNull(s.localGrouping)
    }

    /** Criterion of the issue: with the setting off, the dictation decodes exactly the VAD utterances, one by one. */
    @Test fun offDecodesEachUtteranceAsBefore() {
        val data = audio(-0.5f, 0.8f, -0.8f, 0.8f, -0.8f, 0.8f, -3f, 1f, -1f)
        val sink = StringSink()
        val l = RecordingListener()
        val engine = ScriptedEngine(spec, listOf("un.", "deux.", "trois.", "quatre."), 0)
        controller({ sink }, l).start(session(data, engine, null))
        l.awaitEnd()
        val segments = LocalCut.cut(data, EnergyVad(), null).map { it.audio.size }
        assertEquals(4, segments.size)
        assertEquals(segments, decoded(engine))
        assertEquals("Un. Deux. Trois. Quatre.", sink.text)
        assertEquals(4, l.segments.size)
    }

    /** On: the controller decodes the groups of LocalCut, so the comparison screen and the bench measure the dictation. */
    @Test fun onDecodesTheGroupsOfTheReplay() {
        val data = audio(-0.5f, 0.8f, -0.8f, 0.8f, -0.8f, 0.8f, -3f, 2f, -0.8f, 2f, -0.8f, 2f, -0.8f, 2f, -1f)
        val grouping = LocalGrouping(5f)
        val sink = StringSink()
        val l = RecordingListener()
        val engine = ScriptedEngine(spec, listOf("un deux trois.", "quatre cinq.", "six sept."), 0)
        controller({ sink }, l).start(session(data, engine, grouping))
        l.awaitEnd()
        val groups = LocalCut.cut(data, EnergyVad(), grouping).map { it.audio.size }
        assertEquals(3, groups.size)
        assertEquals(groups, decoded(engine))
        assertEquals("Un deux trois. Quatre cinq. Six sept.", sink.text)
        assertEquals(DictationState.IDLE, l.states.last().first)
    }

    @Test fun groupsInTheControllerNeverExceedThirtySeconds() {
        val data = audio(-0.5f, 50f, -0.8f, 1f, -1f)
        val l = RecordingListener()
        val engine = ScriptedEngine(spec, List(10) { "x." }, 0)
        controller({ StringSink() }, l).start(session(data, engine, LocalGrouping(10f)))
        l.awaitEnd()
        assertTrue(engine.durations.toString(), engine.durations.all { it <= 30.001f })
        assertTrue(engine.calls.get() >= 2)
    }

    @Test fun stopDecodesTheGroupInProgress() {
        val sink = StringSink()
        val l = RecordingListener()
        val engine = ScriptedEngine(spec, listOf("dit avant l'arrêt."), 0)
        val c = controller({ sink }, l)
        // 1 s of speech then silence: stop 0.6 s after the speech, before the idle time closes the group.
        c.start(session(audio(-0.2f, 1f, -10f), engine, LocalGrouping(5f), realTime = true))
        Thread.sleep(1800)
        assertEquals("nothing decoded while the group is open", 0, engine.calls.get())
        c.stop()
        l.awaitEnd()
        assertEquals(1, engine.calls.get())
        assertEquals("Dit avant l'arrêt.", sink.text)
    }

    @Test fun cancelWithAGroupInProgressInsertsNothing() {
        val sink = StringSink()
        val l = RecordingListener()
        val engine = ScriptedEngine(spec, listOf("ne doit pas apparaître."), 0)
        val c = controller({ sink }, l)
        c.start(session(audio(-0.2f, 1f, -10f), engine, LocalGrouping(5f), realTime = true))
        Thread.sleep(1500) // the utterance is over, its group still open
        c.cancel()
        Thread.sleep(2500) // well past the idle time
        assertEquals(0, engine.calls.get())
        assertEquals("", sink.text)
        assertEquals(DictationState.IDLE, c.state)
    }

    /** The keyboard cancels the dictation when the focus moves: the group of field 1 never reaches field 2. */
    @Test fun fieldChangeWithAGroupInProgressKeepsEachTextInItsField() {
        val field1 = StringSink()
        val field2 = StringSink()
        var current: TextSink = field1
        val l1 = RecordingListener()
        val c = controller({ current }, l1)
        val engine1 = ScriptedEngine(spec, listOf("pour le champ un."), 0)
        c.start(session(audio(-0.2f, 1f, -10f), engine1, LocalGrouping(5f), realTime = true))
        Thread.sleep(1500)
        current = field2
        c.cancel()
        // Same controller, as in the keyboard: a new dictation in the new field.
        l1.latch = java.util.concurrent.CountDownLatch(1)
        val engine2 = ScriptedEngine(spec, listOf("pour le champ deux."), 0)
        c.start(session(audio(-0.2f, 1f, -0.5f), engine2, LocalGrouping(5f)))
        l1.awaitEnd()
        Thread.sleep(2500)
        assertEquals("", field1.text)
        assertEquals("Pour le champ deux.", field2.text)
        assertEquals(0, engine1.calls.get())
    }

    /** The idle time inserts a lone sentence while the user stays silent, without waiting for the end. */
    @Test fun aLoneSentenceIsInsertedAfterTheIdleTime() {
        val sink = StringSink()
        val l = RecordingListener()
        val engine = ScriptedEngine(spec, listOf("seule.", "suite."), 0)
        val c = controller({ sink }, l)
        c.start(session(audio(-0.2f, 1f, -4f, 1f, -1f), engine, LocalGrouping(5f), realTime = true))
        // Speech ends at 1.2 s, VAD pause 0.5 s, idle 1 s: inserted at about 2.7 s, long before the second sentence.
        Thread.sleep(3500)
        assertEquals("Seule.", sink.text)
        l.awaitEnd()
        assertEquals("Seule. Suite.", sink.text)
    }

    /** « Comparer les modèles »: with a cut the samples are decoded in groups and the report says how many. */
    @Test fun comparisonScreenCutsTheSamplesLikeTheDictation() {
        val factory = ScriptedEngine.Factory(List(20) { "mot." }, 0)
        val sample = BenchSample("essai", "fr", PcmAudio(audio(-0.5f, 1f, -3f, 1f, -0.5f), rate), "mot. mot.")
        val whole = Benchmark(factory, { File("unused") }).runOne(spec, listOf(sample))
        assertNull(whole.samples.single().groups)
        assertFalse(Benchmark.report(listOf(whole)).contains("groupe"))
        val grouped = Benchmark(factory, { File("unused") }, cutFor = { s ->
            if (s == spec) { a: FloatArray -> LocalCut.cut(a, EnergyVad(), LocalGrouping(5f)).map { it.audio } } else null
        }).runOne(spec, listOf(sample))
        val r = grouped.samples.single()
        assertEquals(2, r.groups)
        assertEquals("mot. mot.", r.text)
        assertTrue(Benchmark.report(listOf(grouped)).contains("2 groupe(s) décodé(s)"))
    }
}
