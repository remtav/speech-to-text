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

/** Silero VAD through sherpa-onnx. The model (0.6 MB) is the only one shipped in the APK. */
class SileroVad private constructor(private val vad: Vad) : VoiceActivityDetector {

    override fun accept(samples: FloatArray) = vad.acceptWaveform(samples)

    override fun isSpeech(): Boolean = vad.isSpeechDetected()

    override fun poll(): SpeechChunk? {
        if (vad.empty()) return null
        val seg = vad.front()
        vad.pop()
        return SpeechChunk(seg.start.toLong(), seg.samples)
    }

    override fun flush() = vad.flush()

    override fun reset() = vad.reset()

    override fun release() = vad.release()

    companion object {
        const val ASSET = "silero_vad.onnx"

        private fun config(model: String, p: VadParams) = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = model,
                threshold = p.threshold,
                minSilenceDuration = p.minSilenceSeconds,
                minSpeechDuration = p.minSpeechSeconds,
                windowSize = 512,
                maxSpeechDuration = p.maxSpeechSeconds,
            ),
            sampleRate = 16000,
            numThreads = 1,
            provider = "cpu",
        )

        fun fromAssets(assets: AssetManager, params: VadParams = VadParams()) =
            SileroVad(Vad(assets, config(ASSET, params)))

        /** For JVM tests: the model as a plain file. */
        fun fromFile(path: String, params: VadParams = VadParams()) =
            SileroVad(Vad(null, config(path, params)))
    }
}
