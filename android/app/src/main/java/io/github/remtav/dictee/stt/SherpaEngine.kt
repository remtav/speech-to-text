package io.github.remtav.dictee.stt

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import io.github.remtav.dictee.text.WhisperRepetition
import java.io.File

/** [SttEngine] backed by a sherpa-onnx offline recognizer, on files in [modelDir]. */
class SherpaEngine(
    override val spec: ModelSpec,
    modelDir: File,
    options: EngineOptions,
) : SttEngine {

    private val config: OfflineRecognizerConfig = buildConfig(spec, modelDir, options)

    private var recognizer: OfflineRecognizer? = OfflineRecognizer(config = config)

    /** Whisper « Automatique » padding (issue #49): the repetition filter's correction, null when the filter is off. */
    private val repetitionFix: RepetitionFix? = options.whisperRepetitionFix.takeIf { spec.family == ModelFamily.WHISPER }

    /**
     * Same model, padding [WhisperTuning.REDECODE_TAIL_PADDINGS]: swapped in with `OfflineRecognizer.setConfig`
     * for one decode, so no second model is loaded. sherpa-onnx 2.0.1 replaces only the Whisper block of the
     * loaded recognizer's config (`OfflineRecognizerWhisperImpl::SetConfig`) and reads the padding again at each
     * decode (`DecodeStream`); checked on the host by WhisperRepetitionHostTest.
     */
    private val redecodeConfig: OfflineRecognizerConfig? = if (repetitionFix == RepetitionFix.REDECODE) {
        buildConfig(spec, modelDir, options.copy(whisperTailPaddings = WhisperTuning.REDECODE_TAIL_PADDINGS))
    } else null

    override val provider: String = options.acceleration.provider

    /** Qwen3-ASR reads the forced language per stream, not from its model config. */
    private val streamLanguage: String = if (spec.family == ModelFamily.QWEN3_ASR) options.language else ""

    @Synchronized
    override fun transcribe(samples: FloatArray, sampleRate: Int): Transcription {
        val rec = checkNotNull(recognizer) { "engine released" }
        val audioSeconds = samples.size / sampleRate.toFloat()
        if (samples.isEmpty()) return Transcription("", audioSeconds = 0f)
        val start = System.nanoTime()
        val first = decodeOnce(rec, samples, sampleRate)
        var final = first
        var outcome = RepetitionOutcome.NONE
        val fix = repetitionFix
        if (fix != null) {
            val ceiling = WhisperRepetition.tokenCeiling(samples.size, sampleRate)
            if (WhisperRepetition.detect(first.text, first.tokens, ceiling) != null) {
                when (fix) {
                    RepetitionFix.REDECODE -> {
                        rec.setConfig(checkNotNull(redecodeConfig))
                        try {
                            final = decodeOnce(rec, samples, sampleRate)
                        } finally {
                            rec.setConfig(config)
                        }
                        outcome = RepetitionOutcome.REDECODED
                    }
                    RepetitionFix.CUT -> {
                        val cut = WhisperRepetition.cut(first.text)
                        if (cut != null) {
                            final = first.copy(text = cut)
                            outcome = RepetitionOutcome.CUT
                        } else {
                            outcome = RepetitionOutcome.DETECTED
                        }
                    }
                }
            }
        }
        return Transcription(
            text = final.text,
            language = final.language,
            decodeMillis = (System.nanoTime() - start) / 1_000_000,
            audioSeconds = audioSeconds,
            repetition = outcome,
        )
    }

    private data class Decoded(val text: String, val language: String, val tokens: Int)

    private fun decodeOnce(rec: OfflineRecognizer, samples: FloatArray, sampleRate: Int): Decoded {
        val stream = rec.createStream()
        try {
            // "language French" is then written into the prompt instead of being guessed
            // (offline-recognizer-qwen3-asr-impl.cc, BuildSourceIds).
            if (streamLanguage.isNotEmpty()) stream.setOption("language", streamLanguage)
            stream.acceptWaveform(samples, sampleRate)
            rec.decode(stream)
            val result = rec.getResult(stream)
            return Decoded(result.text.trim(), result.lang.trim('<', '>', '|', ' '), result.tokens.size)
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
                        tailPaddings = WhisperTuning.sanitizeTailPaddings(options.whisperTailPaddings),
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
