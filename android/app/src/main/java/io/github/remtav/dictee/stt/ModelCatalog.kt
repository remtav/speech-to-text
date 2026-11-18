package io.github.remtav.dictee.stt

/**
 * The speech-to-text models the app can download. Adding a model supported by
 * sherpa-onnx is one entry here (plus, for a new architecture, one branch in
 * [SherpaEngine.buildConfig]).
 *
 * Each file is fetched from its public source and verified against its SHA-256
 * before the model counts as installed. Files are taken one by one from
 * Hugging Face mirrors of the sherpa-onnx exports rather than from the
 * .tar.bz2 release archives: no 1 GB bzip2 to unpack on the phone, and a
 * download can resume file by file.
 */
enum class ModelFamily { NEMO_TRANSDUCER, QWEN3_ASR, WHISPER, REMOTE }

/**
 * Where a model is shown. [VISIBLE] models are offered on every screen, subject to the chosen languages
 * ([ModelCatalog.offered]).
 */
enum class ModelVisibility {
    /** Offered on every screen, subject to the chosen languages ([ModelCatalog.offered]). */
    VISIBLE,
    /**
     * Issue #72: listed in « Avancé » (settings) and in the comparison, under its technical name; never offered at
     * installation, never recommended, and in the keyboard's menu only while it is the active model.
     */
    ADVANCED,
}

data class ModelFile(
    /** Path relative to the model directory. */
    val path: String,
    val url: String,
    val sha256: String,
    val size: Long,
)

data class ModelSpec(
    val id: String,
    val displayName: String,
    val shortName: String,
    val publisher: String,
    val family: ModelFamily,
    val description: String,
    val license: String,
    val files: List<ModelFile>,
    /** Short practical note shown on the model's card (empty: none). Not a warning. */
    val note: String = "",
    val visibility: ModelVisibility = ModelVisibility.VISIBLE,
) {
    val totalBytes: Long get() = files.sumOf { it.size }
}

object ModelCatalog {
    /** Shown on Whisper's card (DECISIONS.md, issue #15, « friperie »; no longer « dernier recours » since issue #22). */
    const val WHISPER_NOTE =
        "Observé sur un téléphone : comprend bien des mots que les autres modèles ratent, " +
            "mais la transcription prend plus de temps."
    /** Shown on Parakeet's card in « Avancé » (issue #72). */
    const val PARAKEET_NOTE = "Plus léger et plus rapide, mais comprend moins bien le français québécois."
    private const val PARAKEET =
        "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main"
    private const val WHISPER =
        "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-turbo/resolve/main"
    val parakeet = ModelSpec(
        id = "parakeet-tdt-0.6b-v3-int8",
        displayName = "Parakeet TDT 0.6b v3",
        shortName = "Parakeet",
        publisher = "NVIDIA",
        family = ModelFamily.NEMO_TRANSDUCER,
        description = "Transducteur, 25 langues européennes. Rapide.",
        license = "CC-BY-4.0",
        // Issue #72: out of the normal path (Whisper alone transcribes Quebec French well), kept in « Avancé ».
        visibility = ModelVisibility.ADVANCED,
        note = PARAKEET_NOTE,
        files = listOf(
            ModelFile("encoder.int8.onnx", "$PARAKEET/encoder.int8.onnx",
                "1afbe69c1ffdabee94755f42e4daf1d93549131a3da11356bda84fcef0f2c479", 652282294),
            ModelFile("decoder.int8.onnx", "$PARAKEET/decoder.int8.onnx",
                "0222a86147299d4dee90edf95b6d13827edb42ad8b7c9cf0b2582f030937c0b5", 11845274),
            ModelFile("joiner.int8.onnx", "$PARAKEET/joiner.int8.onnx",
                "cbd02f3696e9944970111fb439d9c3fd365a80374565f13f2b9c7007d253fc98", 6355277),
            ModelFile("tokens.txt", "$PARAKEET/tokens.txt",
                "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d", 93939),
        ),
    )

    val whisperTurbo = ModelSpec(
        id = "whisper-large-v3-turbo-int8",
        displayName = "Whisper large-v3-turbo",
        shortName = "Whisper",
        publisher = "OpenAI",
        family = ModelFamily.WHISPER,
        description = "Encodeur-décodeur, ~100 langues.",
        license = "MIT",
        // Observation of the user on his phone (issue #15, 2026-11-12), not a measurement on a device.
        note = WHISPER_NOTE,
        files = listOf(
            ModelFile("turbo-encoder.int8.onnx", "$WHISPER/turbo-encoder.int8.onnx",
                "b02dcdf54f348741e93fe732b67d933c8dcb6735655f710640143081db38878b", 674716297),
            ModelFile("turbo-decoder.int8.onnx", "$WHISPER/turbo-decoder.int8.onnx",
                "20accd02388482eb3a46bd615631adfdc85e1eb2c7db9ea3f02a40ffe6b81547", 361080764),
            ModelFile("turbo-tokens.txt", "$WHISPER/turbo-tokens.txt",
                "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126", 816730),
        ),
    )

    /**
     * Every downloadable on-device model. Screens list [offered] and [listed] by language.
     */
    val all: List<ModelSpec> =
        listOf(whisperTurbo, parakeet)

    /** Issue #72: the models of « Avancé » (Parakeet): never recommended, never at installation. */
    val advanced: List<ModelSpec> = all.filter { it.visibility == ModelVisibility.ADVANCED }

    /**
     * The models offered for [languages] (issue #22): visible and covering every chosen language,
     * in catalogue order. Since issue #72 that is Whisper alone (Parakeet is in « Avancé »).
     */
    fun offered(languages: Set<DictationLanguage>): List<ModelSpec> =
        all.filter { spec ->
            spec.visibility == ModelVisibility.VISIBLE &&
                ModelRecommendations.uncovered(spec, languages).isEmpty()
        }

    /** What « Avancé » and the comparison list: [offered] then [advanced]. */
    fun listed(languages: Set<DictationLanguage>): List<ModelSpec> = offered(languages) + advanced

    /**
     * What the keyboard's menu lists (issue #72): [offered], plus an [advanced] model only while it is the active
     * one ([activeId]), so that a person who chose Parakeet in « Avancé » can come back from the keyboard.
     */
    fun forKeyboard(languages: Set<DictationLanguage>, activeId: String): List<ModelSpec> =
        offered(languages) + advanced.filter { it.id == activeId }

    /**
     * The model to use when [active] was chosen and [languages] are the ones dictated: [active] while it
     * [suits] them, the [default] otherwise (e.g. the languages changed).
     */
    fun forLanguages(active: ModelSpec, languages: Set<DictationLanguage>): ModelSpec =
        if (suits(active, languages)) active else default

    /**
     * Can [spec] stay the active (or fallback) model with [languages]? The server always can; so can an
     * [advanced] model, whatever the languages (issue #72: Parakeet may be chosen with Quebec French).
     */
    fun suits(spec: ModelSpec, languages: Set<DictationLanguage>): Boolean =
        spec.family == ModelFamily.REMOTE || spec in listed(languages)

    /**
     * A server the user runs (opt-in): nothing to download, "installed" once its
     * address is set. See [RemoteEngine].
     */
    val remote = ModelSpec(
        id = "remote-server",
        displayName = "Serveur distant",
        shortName = "Serveur",
        publisher = "Votre serveur",
        family = ModelFamily.REMOTE,
        description = "Transcription sur un serveur que vous contrôlez. L'audio quitte le téléphone.",
        license = "",
        files = emptyList(),
    )

    /** Everything the keyboard can dictate with. */
    val selectable: List<ModelSpec> = all + remote

    /** Issue #22: Whisper turbo did best in the user's tests, Quebec French included. */
    val default: ModelSpec = whisperTurbo

    fun byId(id: String?): ModelSpec? = selectable.firstOrNull { it.id == id }
}
