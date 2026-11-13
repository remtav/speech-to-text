package io.github.remtav.dictee.reading

import io.github.remtav.dictee.audio.AudioSource
import io.github.remtav.dictee.audio.PcmAudio

/**
 * Records one paragraph from an [AudioSource] (the microphone, or a clip in the instrumented tests):
 * 16 kHz mono, stopped by the person ([shouldStop]), by the end of the source, or by the 20 s limit of
 * a segment. Nothing is written anywhere: the samples come back in memory. Pure Kotlin.
 */
object ParagraphRecorder {
    const val SAMPLE_RATE = 16000

    /** Same limit as a dictation segment ([ReadingText.MAX_PARAGRAPH_SECONDS]). */
    const val MAX_SECONDS = 20

    /** Under this, nothing was said: no recording is kept. */
    const val MIN_SECONDS = 0.5f

    /**
     * @return the recording, or null when it is shorter than [MIN_SECONDS].
     * @throws io.github.remtav.dictee.audio.AudioSourceException when the source cannot start.
     */
    fun record(
        source: AudioSource,
        maxSeconds: Int = MAX_SECONDS,
        shouldStop: () -> Boolean = { false },
        onProgress: (seconds: Float) -> Unit = {},
    ): PcmAudio? {
        val limit = maxSeconds * SAMPLE_RATE
        val out = FloatArray(limit)
        var total = 0
        source.start()
        try {
            val buffer = FloatArray(SAMPLE_RATE / 10)
            while (!shouldStop() && total < limit) {
                val n = source.read(buffer)
                if (n < 0) break
                val take = minOf(n, limit - total)
                buffer.copyInto(out, total, 0, take)
                total += take
                if (n > 0) onProgress(total / SAMPLE_RATE.toFloat())
            }
        } finally {
            source.stop()
        }
        if (total < MIN_SECONDS * SAMPLE_RATE) return null
        return PcmAudio(out.copyOf(total), SAMPLE_RATE)
    }
}
