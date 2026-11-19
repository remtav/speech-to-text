package io.github.remtav.dictee.stt

import io.github.remtav.dictee.text.WhisperRepetition
import java.io.File
import java.util.Locale

/**
 * JNI entry points of `libct2whisper.so` (issue #84, experimental): CTranslate2, the engine of
 * faster-whisper, built for arm64-v8a by `android/tools/ctranslate2-android/compiler.sh`. The library is
 * not in the normal APK: Gradle packages it only with `-Pct2Libs=<dir>` (see that tool's README).
 */
internal object Ct2Native {
    /** Null once the library is loaded; otherwise why it could not be. */
    val loadError: String? = try {
        System.loadLibrary("ct2whisper")
        null
    } catch (e: Throwable) {
        e.message ?: e.javaClass.simpleName
    }

    val available: Boolean get() = loadError == null

    /** "CTranslate2 4.9.0 · ISA NEON · GEMM Ruy · OpenMP …": shows the NEON kernels are the ones in use. */
    external fun buildInfo(): String

    /** Loads a CTranslate2 Whisper model (int8 compute); throws IllegalStateException on failure. */
    external fun load(modelDir: String, threads: Int): Long

    /**
     * {text, language, tokens, encoder frames, features ms, encode ms, decode ms, no-speech probability}.
     * [language] "" detects it; [paddingFrames] < 0 gives the encoder the full 30 s window.
     */
    external fun transcribe(handle: Long, samples: FloatArray, language: String, paddingFrames: Int): Array<String>

    external fun release(handle: Long)
}

/**
 * Whisper large-v3-turbo on CTranslate2 (issue #84): a trial next to [SherpaEngine], kept out of the model
 * catalog and the visible settings. Only the « Comparer les modèles » screen offers it, and only when the
 * library is packaged and a converted model sits in [Ct2Experiment.modelDir].
 *
 * Same contract as the sherpa-onnx Whisper: 16 kHz mono samples, forced or detected language, greedy decoding,
 * the user's tail padding ([EngineOptions.whisperTailPaddings], zeros in feature space as in sherpa-onnx) and
 * the repetition filter of « Automatique » (issue #49). No token ceiling of 6 per second here: the bridge stops
 * at 15 tokens per second of audio, so [Transcription.ceilingExtended] is always false.
 */
class Ct2WhisperEngine(
    override val spec: ModelSpec,
    modelDir: File,
    private val options: EngineOptions,
) : SttEngine {

    private var handle: Long

    init {
        Ct2Native.loadError?.let { throw IllegalStateException("libct2whisper.so absente : $it") }
        require(Ct2Experiment.hasModel(modelDir)) { "Modèle CTranslate2 introuvable dans $modelDir" }
        handle = Ct2Native.load(modelDir.absolutePath, options.numThreads)
    }

    override val provider: String = "cpu (CTranslate2)"

    /** Encoder and decoder times of the last [transcribe], for the trial's report. */
    @Volatile var lastTimings: Ct2Timings? = null
        private set

    private val padding: Int get() = WhisperTuning.sanitizeTailPaddings(options.whisperTailPaddings)

    @Synchronized
    override fun transcribe(samples: FloatArray, sampleRate: Int): Transcription {
        check(handle != 0L) { "engine released" }
        require(sampleRate == SttEngine.SAMPLE_RATE) { "16 kHz attendu, reçu $sampleRate Hz" }
        val audioSeconds = samples.size / sampleRate.toFloat()
        if (samples.isEmpty()) return Transcription("", audioSeconds = 0f)
        val start = System.nanoTime()
        var decoded = decode(samples, padding)
        var outcome = RepetitionOutcome.NONE
        val fix = options.whisperRepetitionFix
        // Ceiling 0: the CEILING rule is sherpa-onnx's 6 tokens per second, which this engine does not have.
        if (fix != null && WhisperRepetition.detect(decoded.text, decoded.tokens, 0) != null) {
            when (fix) {
                RepetitionFix.REDECODE -> {
                    decoded = decode(samples, WhisperTuning.REDECODE_TAIL_PADDINGS)
                    outcome = RepetitionOutcome.REDECODED
                }
                RepetitionFix.CUT -> {
                    val cut = WhisperRepetition.cut(decoded.text)
                    outcome = if (cut != null) RepetitionOutcome.CUT else RepetitionOutcome.DETECTED
                    if (cut != null) decoded = decoded.copy(text = cut)
                }
            }
        }
        return Transcription(
            text = decoded.text,
            language = decoded.language,
            decodeMillis = (System.nanoTime() - start) / 1_000_000,
            audioSeconds = audioSeconds,
            repetition = outcome,
        )
    }

    private data class Decoded(val text: String, val language: String, val tokens: Int)

    /** Pieces of at most 30 s (the encoder's window), decoded one after the other and joined. */
    private fun decode(samples: FloatArray, paddingFrames: Int): Decoded {
        val window = 30 * SttEngine.SAMPLE_RATE
        val texts = ArrayList<String>()
        var language = ""
        var tokens = 0
        var timings = Ct2Timings()
        var offset = 0
        while (offset < samples.size) {
            val piece = samples.copyOfRange(offset, minOf(samples.size, offset + window))
            offset += window
            val r = Ct2Native.transcribe(handle, piece, options.language, paddingFrames)
            if (r[0].isNotBlank()) texts += r[0].trim()
            if (language.isEmpty()) language = r[1]
            tokens += r[2].toInt()
            timings += Ct2Timings(
                encoderFrames = r[3].toInt(),
                featuresMillis = r[4].toDouble(),
                encodeMillis = r[5].toDouble(),
                decodeMillis = r[6].toDouble(),
            )
        }
        lastTimings = timings
        return Decoded(texts.joinToString(" "), language, tokens)
    }

    @Synchronized
    override fun release() {
        if (handle != 0L) Ct2Native.release(handle)
        handle = 0L
    }
}

/** Time split of one CTranslate2 transcription: features, encoder, decoder (language detection included). */
data class Ct2Timings(
    val encoderFrames: Int = 0,
    val featuresMillis: Double = 0.0,
    val encodeMillis: Double = 0.0,
    val decodeMillis: Double = 0.0,
) {
    operator fun plus(o: Ct2Timings) = Ct2Timings(
        encoderFrames + o.encoderFrames, featuresMillis + o.featuresMillis,
        encodeMillis + o.encodeMillis, decodeMillis + o.decodeMillis,
    )
}

/**
 * The trial's model (issue #84): Whisper large-v3-turbo converted to CTranslate2 int8 by
 * `android/tools/ctranslate2-android/convertir_modele.sh`, copied by hand (adb) to [modelDir]. Not in
 * [ModelCatalog]: nothing to download, no card, no setting.
 */
object Ct2Experiment {
    const val MODEL_ID = "whisper-large-v3-turbo-ct2-int8"

    /** Files the bridge reads; the converter also writes tokenizer.json and preprocessor_config.json. */
    val REQUIRED_FILES = listOf("model.bin", "config.json", "vocabulary.json")

    val spec = ModelSpec(
        id = MODEL_ID,
        displayName = "Whisper large-v3-turbo · CTranslate2 (essai)",
        shortName = "Whisper CT2",
        publisher = "OpenAI",
        // Whisper: the same language, padding and repetition options as the sherpa-onnx Whisper.
        family = ModelFamily.WHISPER,
        description = "Essai #84 : moteur de faster-whisper (CTranslate2), int8, CPU NEON.",
        license = "MIT",
        files = emptyList(),
    )

    /**
     * `/sdcard/Android/data/<applicationId>/files/ct2/whisper-large-v3-turbo-int8` for [externalFilesDir]
     * = `Context.getExternalFilesDir(null)`: `adb push` reaches it on any build, no root, no run-as.
     */
    fun modelDir(externalFilesDir: File?): File? =
        externalFilesDir?.let { File(it, "ct2/whisper-large-v3-turbo-int8") }

    fun hasModel(dir: File?): Boolean = dir != null && REQUIRED_FILES.all { File(dir, it).isFile }

    /** The trial can run: library packaged and loaded, model present. */
    fun isReady(dir: File?): Boolean = Ct2Native.available && hasModel(dir)

    /**
     * The trial against the app's Whisper ([reference]: sherpa-onnx, `turbo-*.int8.onnx`), sample by sample, on
     * the same phone, samples, threads and options: the measure the issue's decision rests on. Null when either
     * run failed or they share no sample. Speed ratio > 1: CTranslate2 is faster.
     */
    fun comparison(reference: ModelResult, trial: ModelResult): String? {
        if (reference.error != null || trial.error != null) return null
        val byName = reference.samples.associateBy { it.sample }
        val pairs = trial.samples.mapNotNull { t -> byName[t.sample]?.let { it to t } }
        if (pairs.isEmpty()) return null
        fun pct(w: Double?) = w?.let { String.format(Locale.ROOT, "%.1f %%", it * 100) } ?: "—"
        fun ratio(ref: Long, trial: Long) = if (trial > 0) String.format(Locale.ROOT, "×%.2f", ref.toDouble() / trial) else "—"
        return buildString {
            appendLine("== CTranslate2 face au Whisper de l'application (sherpa-onnx), mêmes extraits et réglages")
            for ((r, t) in pairs) {
                appendLine(
                    String.format(
                        Locale.ROOT, "   [%s] %.1f s audio · sherpa %.2f s · CTranslate2 %.2f s · vitesse %s · WER %s → %s",
                        t.sample, t.audioSeconds, r.decodeMillis / 1000f, t.decodeMillis / 1000f,
                        ratio(r.decodeMillis, t.decodeMillis), pct(r.wer), pct(t.wer),
                    ) + if (r.text != t.text) " · textes différents" else "",
                )
            }
            val refMs = pairs.sumOf { it.first.decodeMillis }
            val trialMs = pairs.sumOf { it.second.decodeMillis }
            val refWer = pairs.mapNotNull { it.first.wer }.takeIf { it.isNotEmpty() }?.average()
            val trialWer = pairs.mapNotNull { it.second.wer }.takeIf { it.isNotEmpty() }?.average()
            appendLine(
                String.format(
                    Locale.ROOT, "   TOTAL %d extrait(s) · sherpa %.2f s · CTranslate2 %.2f s · vitesse %s · WER moyen %s → %s",
                    pairs.size, refMs / 1000f, trialMs / 1000f, ratio(refMs, trialMs), pct(refWer), pct(trialWer),
                ),
            )
            append(
                String.format(
                    Locale.ROOT, "   chargement %.2f s → %.2f s · mémoire en pointe %d Mo → %d Mo",
                    reference.loadMillis / 1000f, trial.loadMillis / 1000f, reference.peakRssMb, trial.peakRssMb,
                ),
            )
        }
    }

    /** One line for the comparison report: library state and, when loaded, what CTranslate2 dispatches to. */
    fun describe(dir: File?): String = when {
        !Ct2Native.available -> "CTranslate2 : bibliothèque absente (${Ct2Native.loadError})"
        !hasModel(dir) -> "CTranslate2 : ${Ct2Native.buildInfo()} · modèle absent de $dir"
        else -> Ct2Native.buildInfo()
    }
}
