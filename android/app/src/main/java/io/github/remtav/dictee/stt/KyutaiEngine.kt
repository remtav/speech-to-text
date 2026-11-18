package io.github.remtav.dictee.stt

import java.io.File

/**
 * Engine for Kyutai STT ([ModelFamily.KYUTAI_STT], issue #123). sherpa-onnx cannot run this model (Mimi codec at
 * 24 kHz, then a causal transformer over its 32 codebooks), so it needs a native runtime of its own: ggml (GGUF)
 * or candle, through JNI. Lot 2 of the issue chooses and integrates it.
 *
 * Until then, building it fails with a clear message, and [EngineManager] reports a failed load; the model is not
 * offered by any screen ([ModelCatalog.upcoming]), so a user cannot reach this in practice.
 */
class KyutaiEngine(
    override val spec: ModelSpec,
    modelDir: File,
    options: EngineOptions,
) : SttEngine {

    override val provider: String = options.acceleration.provider

    init {
        require(spec.family == ModelFamily.KYUTAI_STT) { "${spec.id} is not a Kyutai STT model" }
        throw UnsupportedOperationException(
            "Kyutai STT ne peut pas encore être chargé sur le téléphone (moteur à venir, issue #123)",
        )
    }

    override fun transcribe(samples: FloatArray, sampleRate: Int): Transcription =
        throw UnsupportedOperationException("Kyutai STT n'est pas encore pris en charge")

    override fun release() {}

    companion object {
        /** Mimi works at 24 kHz; the microphone gives [SttEngine.SAMPLE_RATE] (16 kHz): resampling is for lot 2. */
        const val MIMI_SAMPLE_RATE = 24000
    }
}
