package io.github.remtav.dictee.stt

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
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

    @Synchronized
    override fun transcribe(samples: FloatArray, sampleRate: Int): Transcription {
        val rec = checkNotNull(recognizer) { "engine released" }
        val audioSeconds = samples.size / sampleRate.toFloat()
        if (samples.isEmpty()) return Transcription("", audioSeconds = 0f)
        val start = System.nanoTime()
        val first = decode(rec, samples, sampleRate)
        var final = first
        var outcome = RepetitionOutcome.NONE
        val fix = repetitionFix
        if (fix != null) {
            val ceiling = WhisperRepetition.tokenCeiling(first.samples.size, sampleRate)
            if (WhisperRepetition.detect(first.text, first.tokens, ceiling) != null) {
                when (fix) {
                    RepetitionFix.REDECODE -> {
                        rec.setConfig(checkNotNull(redecodeConfig))
                        try {
                            final = decode(rec, samples, sampleRate)
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
            ceilingExtended = final.extended,
        )
    }

    /** [samples] are what was decoded (silence added when [extended]). */
    private data class Decoded(
        val text: String,
        val language: String,
        val tokens: Int,
        val samples: FloatArray,
        val extended: Boolean = false,
    )

    /**
     * One decode, and for Whisper a second one when the first stopped at sherpa-onnx's token ceiling.
     *
     * sherpa-onnx 2.0.1 stops Whisper after `frames / 100 × 6` tokens, 6 per second of audio, tail padding
     * not counted (`offline-whisper-greedy-search-decoder.cc`, « assume at most 6 tokens per second »), and
     * says nothing: the text just ends. Fast French speech needs more (9 per second in a 4.3 s dictation of
     * the user: « … mais on dirait que j » instead of « … que j'ai mis les réglages et que ça ne marche
     * pas. »). The ceiling counts the samples given, so the same audio followed by as much silence doubles
     * it. The longer decode is kept only if it ended by itself, under its own ceiling; one that runs into
     * it again is a loop (issue #40), not speech, and the first decode is kept.
     */
    private fun decode(rec: OfflineRecognizer, samples: FloatArray, sampleRate: Int): Decoded {
        val first = decodeOnce(rec, samples, sampleRate)
        if (spec.family != ModelFamily.WHISPER) return first
        if (!WhisperTuning.atTokenCeiling(first.tokens, samples.size, sampleRate)) return first
        val silence = WhisperTuning.ceilingSilence(samples.size, sampleRate)
        if (silence <= 0) return first
        val longer = decodeOnce(rec, samples.copyOf(samples.size + silence), sampleRate)
        if (WhisperTuning.atTokenCeiling(longer.tokens, longer.samples.size, sampleRate)) return first
        return longer.copy(extended = true)
    }

    private fun decodeOnce(rec: OfflineRecognizer, samples: FloatArray, sampleRate: Int): Decoded {
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            rec.decode(stream)
            val result = rec.getResult(stream)
            return Decoded(result.text.trim(), result.lang.trim('<', '>', '|', ' '), result.tokens.size, samples)
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
                ModelFamily.REMOTE, ModelFamily.KYUTAI_STT ->
                    throw IllegalArgumentException("${spec.id} is not a sherpa-onnx model")
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

    }
}
