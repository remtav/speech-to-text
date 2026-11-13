package io.github.remtav.dictee.dictation

import io.github.remtav.dictee.EnergyVad
import io.github.remtav.dictee.stt.AudioBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #35: blocks are cut at pauses past the minimum, at the maximum at the latest, with no audio twice. */
class BlockCutterTest {
    private val rate = 16000
    private val lookback = rate * 6 / 10

    /** Speech (noise) and silence, in seconds: positive = speech, negative = silence. Each sample is unique. */
    private fun stream(vararg parts: Float): FloatArray {
        val out = ArrayList<Float>()
        var k = 0
        for (p in parts) repeat((kotlin.math.abs(p) * rate).toInt()) {
            // Distinct values (a step far above the float spacing), loud enough for the energy VAD.
            out += if (p > 0) 0.02f + (k++) * 1e-6f else 0f
        }
        return out.toFloatArray()
    }

    /** Runs [audio] through the cutter the way the controller's capture loop does. */
    private fun cut(audio: FloatArray, min: Float = 2f, max: Float = 4f): List<AudioBlock> {
        val blocks = ArrayList<AudioBlock>()
        val history = ArrayList<Float>()
        val vad = EnergyVad(minSilence = rate / 2, maxSpeech = rate * 20)
        val cutter = BlockCutter((min * rate).toInt(), (max * rate).toInt(), { n -> history.takeLast(n).toFloatArray() }) { blocks += it }
        var total = 0L
        var was = false
        var i = 0
        while (i < audio.size) {
            val n = minOf(512, audio.size - i)
            val chunk = audio.copyOfRange(i, i + n)
            i += n
            history.addAll(chunk.toList())
            total += n
            vad.accept(chunk)
            val speech = vad.isSpeech()
            val onset = if (speech && !was) (total - lookback).coerceAtLeast(0) else -1L
            was = speech
            cutter.onAudio(chunk, n, total, onset)
            var popped = false
            while (true) { cutter.onSegment(vad.poll() ?: break); popped = true }
            cutter.afterChunk(total, speech, popped)
        }
        vad.flush()
        while (true) cutter.onSegment(vad.poll() ?: break)
        cutter.finish()?.let { blocks += it }
        return blocks
    }

    /** Every sample of [audio] appears at most once across all blocks (the stream has unique values). */
    private fun assertNoAudioTwice(blocks: List<AudioBlock>) {
        val seen = HashSet<Float>()
        for (b in blocks) for (v in b.audio) if (v != 0f) assertTrue("sample in two blocks", seen.add(v))
        val segSeen = HashSet<Float>()
        for (b in blocks) for (s in b.segments) for (v in s) if (v != 0f) assertTrue("segment audio in two blocks", segSeen.add(v))
    }

    @Test fun utterancesAreGroupedUntilTheMinimumThenCutAtThePause() {
        // 0.8 s utterances and pauses: two per block (with the 0.6 s lookback and the 0.5 s the VAD needs to see a
        // pause, one utterance is under 2 s; the block closes at the pause after the second one).
        val blocks = cut(stream(-1f, 0.8f, -0.8f, 0.8f, -0.8f, 0.8f, -0.8f, 0.8f, -0.8f, 0.5f))
        assertEquals(3, blocks.size)
        assertEquals(listOf(2, 2, 1), blocks.map { it.segments.size })
        assertEquals(listOf(0, 1, 2), blocks.map { it.index })
        blocks.forEach { assertTrue("block of ${it.seconds} s", it.seconds <= 4f) }
        assertNoAudioTwice(blocks)
    }

    @Test fun leadingSilenceIsNotSent() {
        val blocks = cut(stream(-6f, 1f, -0.2f))
        assertEquals(1, blocks.size)
        assertTrue("block of ${blocks.single().seconds} s", blocks.single().seconds < 2f)
    }

    @Test fun longSpeechWithoutPauseIsCutAtTheMaximumAndSplitForTheLocalFallback() {
        val speech = stream(-0.5f, 10f, -0.6f)
        val blocks = cut(speech)
        assertTrue("${blocks.size} blocks", blocks.size >= 3)
        blocks.forEach { assertTrue("block of ${it.seconds} s", it.seconds <= 4.05f) }
        // The utterance in progress is split between the blocks: each block has its part for a local fallback.
        assertTrue(blocks.all { it.segments.isNotEmpty() })
        assertNoAudioTwice(blocks)
        // Nothing of the speech is lost: the blocks hold all 10 s.
        val held = blocks.sumOf { b -> b.audio.count { it != 0f } }
        assertEquals(10 * rate, held)
        val inSegments = blocks.sumOf { b -> b.segments.sumOf { s -> s.count { it != 0f } } }
        assertEquals(10 * rate, inSegments)
    }

    @Test fun longPauseBetweenUtterancesIsNotSent() {
        val blocks = cut(stream(1f, -0.8f, 1.5f, -0.8f, -20f, 1f, -0.3f))
        assertEquals(2, blocks.size)
        // The 20 s of silence are in no block.
        assertTrue(blocks.all { it.seconds < 4.1f })
        assertNoAudioTwice(blocks)
    }

    @Test fun nothingSaidNoBlock() {
        assertTrue(cut(stream(-5f)).isEmpty())
    }
}
