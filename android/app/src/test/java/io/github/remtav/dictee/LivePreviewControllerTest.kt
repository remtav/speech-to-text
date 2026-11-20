package io.github.remtav.dictee

import io.github.remtav.dictee.audio.AudioSource
import io.github.remtav.dictee.audio.PcmAudioSource
import io.github.remtav.dictee.dictation.DictationController
import io.github.remtav.dictee.dictation.DictationSession
import io.github.remtav.dictee.dictation.DictationState
import io.github.remtav.dictee.dictation.StringSink
import io.github.remtav.dictee.dictation.TextPipeline
import io.github.remtav.dictee.stt.DictationResult
import io.github.remtav.dictee.stt.LiveStream
import io.github.remtav.dictee.stt.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Issue #136 (#130): live preview of a server dictation, as composing text only. */
class LivePreviewControllerTest {
    private class FakeLiveStream : LiveStream {
        val samples = AtomicLong()
        val stops = AtomicInteger()
        val cancels = AtomicInteger()
        override fun send(samples: FloatArray, n: Int) { this.samples.addAndGet(n.toLong()) }
        override fun stop() { stops.incrementAndGet() }
        override fun cancel() { cancels.incrementAndGet() }
    }

    private class CountingSource(private val inner: AudioSource) : AudioSource {
        val read = AtomicLong()
        override fun start() = inner.start()
        override fun read(buffer: FloatArray): Int = inner.read(buffer).also { if (it > 0) read.addAndGet(it.toLong()) }
        override fun stop() = inner.stop()
    }

    private class Run(
        val sink: StringSink,
        val stream: FakeLiveStream,
        val source: CountingSource,
        val states: List<Pair<DictationState, String?>>,
    ) {
        @Volatile var listener: LiveStream.Listener? = null
        /** Composing text seen by the server step, once the capture has ended. */
        @Volatile var composingAtResult: String? = null
    }

    private fun dictate(
        live: Boolean = true,
        factory: ((LiveStream.Listener) -> LiveStream)? = null,
        whileWaiting: (Run) -> Unit = {},
        text: String = "Bonjour tout le monde.",
    ): Run {
        val sink = StringSink()
        val stream = FakeLiveStream()
        val source = CountingSource(PcmAudioSource(listOf(burst(2f)), realTime = false))
        val rec = RecordingListener()
        val c = DictationController({ sink }, { it.run() }, rec)
        val run = Run(sink, stream, source, rec.states)
        val session = DictationSession(
            ModelCatalog.remote, source, EnergyVad(), TextPipeline(),
            transcribe = { throw AssertionError("no per-segment transcription in whole mode") },
            whole = { _, _, _ ->
                whileWaiting(run)
                run.composingAtResult = sink.composing
                DictationResult(listOf(text), DictationResult.Source.SERVER_RAW)
            },
            live = if (!live) null else factory ?: { l -> run.listener = l; stream },
        )
        c.start(session)
        rec.awaitEnd()
        return run
    }

    @Test fun audioIsStreamed() {
        val r = dictate()
        assertTrue(r.source.read.get() > 0)
        assertEquals(r.source.read.get(), r.stream.samples.get())
        assertEquals(1, r.stream.stops.get())
    }

    @Test fun updateShowsComposing() {
        val r = dictate(whileWaiting = { it.listener!!.onUpdate("bonjour", "tout le") })
        assertEquals("bonjour tout le", r.composingAtResult)
    }

    @Test fun updateWithoutCommittedHasNoExtraSpaces() {
        val r = dictate(whileWaiting = { it.listener!!.onUpdate("", "tout le") })
        assertEquals("tout le", r.composingAtResult)
    }

    @Test fun finalReplacesPreview() {
        val r = dictate(whileWaiting = { it.listener!!.onUpdate("bonjour", "tout") })
        assertEquals("Bonjour tout le monde.", r.sink.text)
        assertEquals("", r.sink.composing)
        assertTrue(r.stream.cancels.get() >= 1)
    }

    @Test fun lateUpdateIgnored() {
        val r = dictate()
        r.listener!!.onUpdate("tard", "trop")
        assertEquals("", r.sink.composing)
        assertEquals("Bonjour tout le monde.", r.sink.text)
    }

    @Test fun streamFailureChangesNothing() {
        val plain = dictate(live = false)
        val r = dictate(whileWaiting = { it.listener!!.onClosed("Serveur occupé") })
        assertEquals(plain.sink.text, r.sink.text)
        assertTrue(r.states.none { it.first == DictationState.ERROR })
        assertFalse(r.states.isEmpty())
    }

    @Test fun streamThatCannotStartChangesNothing() {
        val r = dictate(factory = { throw IllegalStateException("pas de réseau") })
        assertEquals("Bonjour tout le monde.", r.sink.text)
        assertTrue(r.states.none { it.first == DictationState.ERROR })
    }

    @Test fun noStreamWhenNull() {
        val r = dictate(live = false)
        assertEquals("Bonjour tout le monde.", r.sink.text)
        assertEquals(0, r.stream.samples.get())
        assertNull(DictationSession(ModelCatalog.remote, r.source, EnergyVad(), TextPipeline(), { throw AssertionError() }).live)
    }

    @Test fun cancelDropsTheStream() {
        val sink = StringSink()
        val stream = FakeLiveStream()
        val c = DictationController({ sink }, { it.run() }, RecordingListener())
        c.start(DictationSession(
            ModelCatalog.remote, PcmAudioSource(listOf(burst(3f)), realTime = true), EnergyVad(), TextPipeline(),
            transcribe = { throw AssertionError() },
            whole = { _, _, _ -> DictationResult(listOf("x"), DictationResult.Source.SERVER_RAW) },
            live = { stream },
        ))
        Thread.sleep(300)
        c.cancel()
        assertTrue(stream.cancels.get() >= 1)
    }
}
