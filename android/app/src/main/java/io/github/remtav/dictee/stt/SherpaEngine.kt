package io.github.remtav.dictee.stt

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File

/** [SttEngine] backed by a sherpa-onnx offline recognizer, on files in [modelDir]. */
class SherpaEngine(
    override val spec: ModelSpec,
    modelDir: File,
    options: EngineOptions,
) : SttEngine {

    private var recognizer: OfflineRecognizer? =
        OfflineRecognizer(config = buildConfig(spec, modelDir, options))

    override val provider: String = options.acceleration.provider

    @Synchronized
    override fun transcribe(samples: FloatArray, sampleRate: Int): Transcription {
        val rec = checkNotNull(recognizer) { "engine released" }
        val audioSeconds = samples.size / sampleRate.toFloat()
        if (samples.isEmpty()) return Transcription("", audioSeconds = 0f)
        val start = System.nanoTime()
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            rec.decode(stream)
            val result = rec.getResult(stream)
            val millis = (System.nanoTime() - start) / 1_000_000
            return Transcription(
                text = result.text.trim(),
                language = result.lang.trim('<', '>', '|', ' '),
                decodeMillis = millis,
                audioSeconds = audioSeconds,
            )
        } finally {
            stream.release()
        }
    }

    @Synchronized
    override fun release() {
        recognizer?.release()
        recognizer = null
    }

    companion object {
        val factory = SttEngineFactory { spec, dir, options -> SherpaEngine(spec, dir, options) }

        fun buildConfig(spec: ModelSpec, dir: File, options: EngineOptions): OfflineRecognizerConfig {
            fun path(name: String) = File(dir, name).absolutePath
            val model = when (spec.family) {
                ModelFamily.NEMO_TRANSDUCER -> OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = path("encoder.int8.onnx"),
                        decoder = path("decoder.int8.onnx"),
                        joiner = path("joiner.int8.onnx"),
                    ),
                    tokens = path("tokens.txt"),
                    modelType = "nemo_transducer",
                )
                ModelFamily.QWEN3_ASR -> OfflineModelConfig(
                    qwen3Asr = OfflineQwen3AsrModelConfig(
                        convFrontend = path("conv_frontend.onnx"),
                        encoder = path("encoder.int8.onnx"),
                        decoder = path("decoder.int8.onnx"),
                        tokenizer = path("tokenizer"),
                        // Comma-separated words the decoder is biased towards.
                        hotwords = options.hotwords
                            .map { it.replace(",", " ").trim() }
                            .filter { it.isNotEmpty() }
                            .take(MAX_HOTWORDS)
                            .joinToString(","),
                    ),
                    tokens = "",
                )
                ModelFamily.REMOTE -> throw IllegalArgumentException("${spec.id} is not a sherpa-onnx model")
                ModelFamily.WHISPER -> OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = path("turbo-encoder.int8.onnx"),
                        decoder = path("turbo-decoder.int8.onnx"),
                        // Empty language: Whisper detects it for each segment; otherwise the single
                        // language chosen by the user is forced (AppSettings.engineOptions).
                        language = options.language,
                        task = "transcribe",
                    ),
                    tokens = path("turbo-tokens.txt"),
                    modelType = "whisper",
                )
            }
            model.numThreads = options.numThreads
            model.provider = options.acceleration.provider
            model.debug = false
            val featureDim = if (spec.family == ModelFamily.WHISPER) 128 else 80
            return OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SttEngine.SAMPLE_RATE, featureDim = featureDim),
                modelConfig = model,
                decodingMethod = "greedy_search",
            )
        }

        private const val MAX_HOTWORDS = 100
    }
}
