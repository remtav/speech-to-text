package io.github.remtav.dictee.reading

import io.github.remtav.dictee.audio.AudioSource
import io.github.remtav.dictee.audio.AudioSourceException
import io.github.remtav.dictee.audio.PcmAudio
import io.github.remtav.dictee.audio.PcmAudioSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ParagraphRecorderTest {
    private fun clip(seconds: Float) = PcmAudio(FloatArray((seconds * 16000).toInt()) { 0.1f }, 16000)

    private fun source(seconds: Float) =
        PcmAudioSource(listOf(clip(seconds)), realTime = false, leadSeconds = 0f, tailSeconds = 0f)

    @Test
    fun recordsUntilTheSourceEnds() {
        val r = ParagraphRecorder.record(source(3f))
        assertNotNull(r)
        assertEquals(16000, r!!.sampleRate)
        assertEquals(48000, r.samples.size)
    }

    @Test
    fun stopsAtTheTwentySecondLimit() {
        val r = ParagraphRecorder.record(source(30f))!!
        assertEquals(20 * 16000, r.samples.size)
        assertEquals(ReadingText.MAX_PARAGRAPH_SECONDS.toInt(), ParagraphRecorder.MAX_SECONDS)
    }

    @Test
    fun stopsWhenThePersonStops() {
        var reads = 0
        val r = ParagraphRecorder.record(source(10f), shouldStop = { reads++ >= 5 })!!
        assertEquals(5 * 1600, r.samples.size)
    }

    @Test
    fun nothingHeardKeepsNothing() {
        assertNull(ParagraphRecorder.record(source(0.2f)))
    }

    @Test
    fun reportsTheElapsedSecondsInOrder() {
        val seen = mutableListOf<Float>()
        ParagraphRecorder.record(source(2f), onProgress = { seen += it })
        assertTrue(seen.isNotEmpty())
        assertEquals(seen.sorted(), seen)
        assertEquals(2f, seen.last(), 0.01f)
    }

    private class Fake(private val failToStart: Boolean = false) : AudioSource {
        var started = false
        var stopped = false
        var left = 16000
        override fun start() {
            if (failToStart) throw AudioSourceException("occupé")
            started = true
        }
        override fun read(buffer: FloatArray): Int {
            if (left <= 0) return -1
            val n = minOf(buffer.size, left)
            left -= n
            return n
        }
        override fun stop() { stopped = true }
    }

    @Test
    fun theSourceIsAlwaysStoppedAndAStartFailureReachesTheCaller() {
        val ok = Fake()
        ParagraphRecorder.record(ok)
        assertTrue(ok.started && ok.stopped)
        val busy = Fake(failToStart = true)
        try {
            ParagraphRecorder.record(busy)
            fail("la source occupée doit être signalée")
        } catch (e: AudioSourceException) {
            assertEquals("occupé", e.message)
        }
        assertFalse(busy.started)
    }
}
