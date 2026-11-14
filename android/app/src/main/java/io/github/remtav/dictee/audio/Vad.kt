package io.github.remtav.dictee.audio

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/** One utterance between two pauses. [startSample] counts from the last reset. */
class SpeechChunk(val startSample: Long, val samples: FloatArray)

/** Voice activity detection: cuts the audio stream at pauses. */
interface VoiceActivityDetector {
    fun accept(samples: FloatArray)

    /** True while the detector is inside speech. */
    fun isSpeech(): Boolean

    /** Next finished utterance, or null. */
    fun poll(): SpeechChunk?

    /** Ends the current utterance (if any) so [poll] returns it. */
    fun flush()

    fun reset()

    fun release()
}

data class VadParams(
    /** Pause that ends an utterance. Long enough not to cut in the middle of a sentence. */
    val minSilenceSeconds: Float = 0.5f,
    val minSpeechSeconds: Float = 0.25f,
    /** Longer utterances are split (Whisper takes at most 30 s, Parakeet degrades past ~20 s). */
    val maxSpeechSeconds: Float = 20f,
    val threshold: Float = 0.5f,
)

/**
 * Silero VAD through sherpa-onnx. The model (0.6 MB) is the only one shipped in the APK.
 *
 * Each utterance is given back with the end that sherpa-onnx drops ([SegmentTail]), so the last word
 * of a segment is not cut.
 */
class SileroVad private constructor(private val vad: Vad, minSilenceSeconds: Float) : VoiceActivityDetector {

    private val tail = SegmentTail((minSilenceSeconds * SAMPLE_RATE).toInt())

    override fun accept(samples: FloatArray) {
        tail.record(samples)
        vad.acceptWaveform(samples)
    }

    override fun isSpeech(): Boolean = vad.isSpeechDetected()

    override fun poll(): SpeechChunk? {
        while (!vad.empty()) {
            val seg = vad.front()
            vad.pop()
            tail.extend(SpeechChunk(seg.start.toLong(), seg.samples))?.let { return it }
        }
        return null
    }

    override fun flush() = vad.flush()

    override fun reset() {
        vad.reset()
        tail.reset()
    }

    override fun release() = vad.release()

    companion object {
        const val ASSET = "silero_vad.onnx"
        private const val SAMPLE_RATE = 16000

        private fun config(model: String, p: VadParams) = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = model,
                threshold = p.threshold,
                minSilenceDuration = p.minSilenceSeconds,
                minSpeechDuration = p.minSpeechSeconds,
                windowSize = 512,
                maxSpeechDuration = p.maxSpeechSeconds,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
        )

        fun fromAssets(assets: AssetManager, params: VadParams = VadParams()) =
            SileroVad(Vad(assets, config(ASSET, params)), params.minSilenceSeconds)

        /** For JVM tests: the model as a plain file. */
        fun fromFile(path: String, params: VadParams = VadParams()) =
            SileroVad(Vad(null, config(path, params)), params.minSilenceSeconds)
    }
}

/**
 * Puts back the end of each utterance that sherpa-onnx's VAD drops (sherpa-onnx 2.0.1,
 * `voice-activity-detector.cc`).
 *
 * When the VAD decides an utterance is over, it ends the segment at `Tail - min_silence` and throws away
 * the last `min_silence` samples it holds. This is right only when the pause began exactly
 * `min_silence` ago. It often did not: in `silero-vad-model.cc` the start of the pause (`temp_end_`) is
 * cleared only by a window above the threshold (0.5), while windows between the low threshold (0.35)
 * and 0.5 still count as speech. After a short gap between two words, a soft ending (a word said more
 * quietly, a voice that fades) keeps the old pause start; the utterance then ends at the first window
 * under 0.35, and the last `min_silence` (0.5 s by default) of what was said is dropped, from this
 * segment and from the next. On the host, with the real Silero model, a reference clip whose last
 * 1.2 s is said more quietly loses 0.37 s that the VAD itself still rated as speech.
 *
 * [extend] adds the samples after the segment's end, up to [maxTail] (the configured pause) and up to
 * what was recorded: the audio the VAD dropped, and at most a short pause otherwise. A segment never
 * starts before the end of the previous one handed out, so no audio is in two segments.
 *
 * Positions are those of [SpeechChunk.startSample]: samples given to [record] since the last [reset],
 * the same count as the VAD's own buffer (it is fed the same samples).
 */
internal class SegmentTail(private val maxTail: Int) {
    /** The last recorded samples: the dropped end, plus room for a poll that comes a little late. */
    private val ring = FloatArray(maxOf(maxTail, 0) + HISTORY)
    private var total = 0L
    /** End of the last segment handed out. */
    private var lastEnd = 0L

    fun record(samples: FloatArray) {
        var from = maxOf(0, samples.size - ring.size)
        while (from < samples.size) {
            val pos = ((total + from) % ring.size).toInt()
            val n = minOf(samples.size - from, ring.size - pos)
            System.arraycopy(samples, from, ring, pos, n)
            from += n
        }
        total += samples.size
    }

    /** [chunk] with the end the VAD dropped, and without what an earlier segment already holds; null when empty. */
    fun extend(chunk: SpeechChunk): SpeechChunk? {
        val start = chunk.startSample
        val end = start + chunk.samples.size
        val skip = (lastEnd - start).coerceIn(0L, chunk.samples.size.toLong()).toInt()
        val tailFrom = maxOf(end, lastEnd)
        val tailTo = minOf(end + maxOf(maxTail, 0), total)
        // Only audio still in the ring, and only right after the segment (never a hole in the middle).
        val tailLen = if (tailFrom == end && tailFrom >= total - ring.size) (tailTo - tailFrom).coerceAtLeast(0L).toInt() else 0
        val out = FloatArray(chunk.samples.size - skip + tailLen)
        System.arraycopy(chunk.samples, skip, out, 0, chunk.samples.size - skip)
        var o = chunk.samples.size - skip
        var p = tailFrom
        while (p < tailFrom + tailLen) {
            val pos = (p % ring.size).toInt()
            val n = minOf((tailFrom + tailLen - p).toInt(), ring.size - pos)
            System.arraycopy(ring, pos, out, o, n)
            o += n
            p += n
        }
        lastEnd = maxOf(lastEnd, end + tailLen)
        if (out.isEmpty()) return null
        return SpeechChunk(start + skip, out)
    }

    fun reset() {
        total = 0
        lastEnd = 0
    }

    private companion object {
        const val HISTORY = 16000 * 2
    }
}
