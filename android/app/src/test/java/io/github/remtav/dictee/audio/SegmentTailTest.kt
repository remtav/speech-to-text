package io.github.remtav.dictee.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [SegmentTail] gives back the end of an utterance that sherpa-onnx's VAD drops (the last `min_silence`
 * samples it holds when it ends a segment), without ever putting the same audio in two segments.
 */
class SegmentTailTest {
    /** Sample i is worth i: a segment's content tells exactly which samples it holds. */
    private fun ramp(from: Int, to: Int) = FloatArray(to - from) { (from + it).toFloat() }

    private fun recorded(tail: SegmentTail, total: Int, chunk: Int = 512): SegmentTail {
        var p = 0
        while (p < total) {
            val n = minOf(chunk, total - p)
            tail.record(ramp(p, p + n))
            p += n
        }
        return tail
    }

    @Test fun theDroppedEndIsGivenBack() {
        // The VAD ends the segment at 16 000 when it has read 24 000 (min_silence 8 000): the last
        // 8 000 samples, often the end of a word said quietly, go back into the segment.
        val tail = recorded(SegmentTail(8000), 24_000)
        val out = tail.extend(SpeechChunk(4000, ramp(4000, 16_000)))!!
        assertEquals(4000L, out.startSample)
        assertArrayEquals(ramp(4000, 24_000), out.samples, 0f)
    }

    @Test fun neverMoreThanThePause() {
        // Polled late: only the pause after the segment is added, not the audio read since.
        val tail = recorded(SegmentTail(8000), 30_000)
        val out = tail.extend(SpeechChunk(0, ramp(0, 16_000)))!!
        assertArrayEquals(ramp(0, 24_000), out.samples, 0f)
    }

    @Test fun onlyWhatWasRecorded() {
        // Long speech: sherpa-onnx shortens the pause to 0.1 s, so less was read after the segment.
        // A flush ends the segment at the last sample read: nothing to add.
        val tail = recorded(SegmentTail(8000), 17_600)
        assertArrayEquals(ramp(0, 17_600), tail.extend(SpeechChunk(0, ramp(0, 16_000)))!!.samples, 0f)
        val flushed = recorded(SegmentTail(8000), 20_000)
        assertArrayEquals(ramp(100, 20_000), flushed.extend(SpeechChunk(100, ramp(100, 20_000)))!!.samples, 0f)
    }

    @Test fun noAudioInTwoSegments() {
        // The next utterance starts right after the pause: it begins where the previous segment now ends.
        val tail = recorded(SegmentTail(8000), 40_000)
        assertArrayEquals(ramp(0, 24_000), tail.extend(SpeechChunk(0, ramp(0, 16_000)))!!.samples, 0f)
        val next = tail.extend(SpeechChunk(20_000, ramp(20_000, 30_000)))!!
        assertEquals(24_000L, next.startSample)
        assertArrayEquals(ramp(24_000, 38_000), next.samples, 0f)
        // Wholly inside what was already handed out: dropped.
        val again = recorded(SegmentTail(8000), 40_000)
        again.extend(SpeechChunk(0, ramp(0, 16_000)))
        assertNull(again.extend(SpeechChunk(17_000, ramp(17_000, 18_000))))
    }

    @Test fun audioNoLongerHeldIsNotInvented() {
        // Polled far too late: the samples after the segment left the ring; the segment is kept as it is.
        val tail = recorded(SegmentTail(8000), 200_000)
        assertArrayEquals(ramp(0, 16_000), tail.extend(SpeechChunk(0, ramp(0, 16_000)))!!.samples, 0f)
    }

    @Test fun resetStartsCountingAgain() {
        val tail = recorded(SegmentTail(8000), 40_000)
        tail.extend(SpeechChunk(0, ramp(0, 30_000)))
        tail.reset()
        recorded(tail, 12_000)
        val out = tail.extend(SpeechChunk(0, ramp(0, 4000)))!!
        assertEquals(0L, out.startSample)
        assertArrayEquals(ramp(0, 12_000), out.samples, 0f)
    }
}
