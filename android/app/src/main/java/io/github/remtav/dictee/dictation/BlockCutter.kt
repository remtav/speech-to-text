package io.github.remtav.dictee.dictation

import io.github.remtav.dictee.audio.SpeechChunk
import io.github.remtav.dictee.stt.AudioBlock

/**
 * Cuts a server dictation into blocks while it is captured (issue #35): the VAD's utterances are grouped,
 * with the pauses between them, until the block holds at least [minSamples]; it is then closed at the next
 * pause the VAD confirms (an utterance just ended). [maxSamples] is a hard cap (Whisper's 30 s window): the
 * block is closed there even mid-speech, and the utterance in progress is split between the two blocks.
 *
 * A block starts at the onset of speech (minus the controller's lookback), never before the end of the
 * previous block: no audio is in two blocks, and silence between blocks is not sent. Each block keeps the VAD
 * segments inside it (clipped to the block) for a local fallback.
 *
 * Capture thread only. Sample positions are absolute (samples since the start of the dictation), the
 * coordinates of [SpeechChunk.startSample].
 */
internal class BlockCutter(
    private val minSamples: Int,
    private val maxSamples: Int,
    /** The last n samples read (the controller's ring), to start a block a little before the speech onset. */
    private val history: (Int) -> FloatArray,
    private val emit: (AudioBlock) -> Unit,
) {
    private var buf = FloatArray(0)
    private var len = 0
    private var startAbs = 0L
    private var inBlock = false
    /** Start of the utterance in progress (onset minus lookback), -1 when none. */
    private var utteranceAbs = -1L
    private val segs = ArrayList<FloatArray>()
    /** End of the last closed block: nothing before it goes into a later block. */
    private var lastCut = 0L
    private var index = 0

    /** Blocks emitted so far. */
    val emitted: Int get() = index

    /** Samples held by the open block. */
    val pendingSamples: Int get() = if (inBlock) len else 0

    /**
     * [n] samples were just read ([samples]); [endAbs] is the position after them. [onsetAbs] is where the
     * speech that started in this chunk begins (lookback included), -1 if no speech started.
     */
    fun onAudio(samples: FloatArray, n: Int, endAbs: Long, onsetAbs: Long) {
        if (onsetAbs >= 0) utteranceAbs = maxOf(onsetAbs, lastCut)
        if (inBlock) {
            append(samples, 0, n)
            return
        }
        if (onsetAbs < 0) return
        val from = maxOf(onsetAbs, lastCut)
        val h = history((endAbs - from).coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
        inBlock = true
        startAbs = endAbs - h.size
        append(h, 0, h.size)
    }

    /** An utterance ended (the VAD popped it). */
    fun onSegment(seg: SpeechChunk) {
        var s = seg.samples
        val drop = lastCut - seg.startSample
        if (drop > 0) {
            if (drop >= s.size) return
            s = s.copyOfRange(drop.toInt(), s.size)
        }
        if (s.isEmpty()) return
        if (!inBlock) {
            // The VAD never reported this utterance as speech while it was said: its audio is the block.
            inBlock = true
            startAbs = maxOf(seg.startSample, lastCut)
            append(s, 0, s.size)
        }
        segs += s
    }

    /** After each chunk: closes the block at a confirmed pause past [minSamples], or at [maxSamples]. */
    fun afterChunk(endAbs: Long, speech: Boolean, popped: Boolean) {
        if (!inBlock) return
        if (len >= maxSamples) {
            if (speech && utteranceAbs >= 0) {
                // The utterance in progress is cut: its first part goes local with this block if needed.
                val from = (utteranceAbs - startAbs).coerceIn(0, len.toLong()).toInt()
                if (from < len) segs += buf.copyOfRange(from, len)
            }
            close(endAbs)
            if (speech) {
                inBlock = true
                startAbs = endAbs
            }
        } else if (popped && !speech && len >= minSamples) {
            close(endAbs)
        }
    }

    /** End of the dictation (after the VAD was flushed): the last block, or null when nothing is left. */
    fun finish(): AudioBlock? {
        if (!inBlock || len == 0) return null
        val b = AudioBlock(index++, buf.copyOf(len), segs.toList())
        reset()
        return b
    }

    private fun close(endAbs: Long) {
        emit(AudioBlock(index++, buf.copyOf(len), segs.toList()))
        lastCut = endAbs
        reset()
    }

    private fun reset() {
        segs.clear()
        len = 0
        inBlock = false
    }

    private fun append(src: FloatArray, from: Int, n: Int) {
        if (n <= 0) return
        if (len + n > buf.size) buf = buf.copyOf(maxOf(len + n, minOf(maxOf(buf.size * 2, 16_000), maxSamples + 16_000)))
        System.arraycopy(src, from, buf, len, n)
        len += n
    }
}
