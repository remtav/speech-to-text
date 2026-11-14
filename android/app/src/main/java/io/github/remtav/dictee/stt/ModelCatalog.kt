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
 * Where a model is shown (issue #22). Hiding never deletes: a hidden entry stays in
 * [ModelCatalog.all] so its files can still be found (and removed) and the host tests keep it.
 */
enum class ModelVisibility {
    /** Offered on every screen, subject to the chosen languages ([ModelCatalog.offered]). */
    VISIBLE,
    /**
     * Issue #72: listed in « Avancé » (settings) and in the comparison, under its technical name; never offered at
     * installation, never recommended, and in the keyboard's menu only while it is the active model.
     */
    ADVANCED,
    /** Shown nowhere. */
    HIDDEN,
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
    /**
     * Base URL of the sherpa-onnx conversion of the Quebec French fine-tune of Whisper
     * (issue #15, user request), published on 2026-11-12 under the user's Hugging Face account,
     * at his request (android/tools/whisper-hf-vers-sherpa/README.md).
     */
    private const val WHISPER_QC =
        "https://huggingface.co/remiavelo/sherpa-onnx-whisper-large-v3-turbo-fr-quebecois-int8/resolve/main"
    /** Shown on the Quebec Whisper's card: same size and compute cost as Whisper, hence as slow. */
    const val WHISPER_QC_NOTE =
        "Même taille et même coût de calcul que Whisper large-v3-turbo, donc plus lent sur le téléphone " +
            "(observation de l'utilisateur sur Whisper turbo, pas une mesure sur appareil)."
    /**
     * Lite-Whisper large-v3-turbo-acc (LiteASR, compressed encoder), converted to sherpa-onnx and
     * published on the user's Hugging Face account as an experiment (issues #22, #25).
     */
    private const val LITE_WHISPER =
        "https://huggingface.co/remiavelo/sherpa-onnx-lite-whisper-large-v3-turbo-acc-int8/resolve/main"
    private const val QWEN3 =
        "https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/main"

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

    val qwen3 = ModelSpec(
        id = "qwen3-asr-0.6b-int8",
        displayName = "Qwen3-ASR 0.6B",
        shortName = "Qwen3-ASR",
        publisher = "Alibaba (Qwen)",
        family = ModelFamily.QWEN3_ASR,
        description = "Encodeur audio + décodeur LLM, 52 langues. Accepte un vocabulaire favori.",
        license = "Apache-2.0",
        // Issue #22: hidden (not deleted), Whisper turbo did better in the user's tests.
        visibility = ModelVisibility.HIDDEN,
        files = listOf(
            ModelFile("conv_frontend.onnx", "$QWEN3/conv_frontend.onnx",
                "d22dc4423e0940e49884e903d2ea2f7e5567c14fc1aed97e4e26d6b8f208ef9e", 44148281),
            ModelFile("encoder.int8.onnx", "$QWEN3/encoder.int8.onnx",
                "60748d3e6744a57c9c91e1b17424a6c2990567e8adceb0783940c03ed98fa9d9", 182491662),
            ModelFile("decoder.int8.onnx", "$QWEN3/decoder.int8.onnx",
                "4f6885be5959ae26af3089d38ee7972c5fafbeeb1cf8d5e76eab6d8b61ca5771", 755914231),
            ModelFile("tokenizer/merges.txt", "$QWEN3/tokenizer/merges.txt",
                "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5", 1671853),
            ModelFile("tokenizer/tokenizer_config.json", "$QWEN3/tokenizer/tokenizer_config.json",
                "4942d005604266809309cabc9f4e9cb89ce855d59b14681fdc0e1cc62ea26c4c", 12487),
            ModelFile("tokenizer/vocab.json", "$QWEN3/tokenizer/vocab.json",
                "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910", 2776833),
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
     * `ele-sage/whisper-large-v3-turbo-fr-quebecois` (MIT, fine-tuned on Assemblée nationale du
     * Québec and Common Voice), converted with the sherpa-onnx Whisper export and int8 quantization.
     * Same architecture and files as [whisperTurbo], so the same engine branch loads it.
     */
    val whisperQuebec = ModelSpec(
        id = "whisper-large-v3-turbo-fr-qc-int8",
        displayName = "Whisper large-v3-turbo, français québécois",
        shortName = "Whisper QC",
        publisher = "ele-sage (communauté), d'après OpenAI",
        family = ModelFamily.WHISPER,
        description = "Whisper large-v3-turbo affiné sur du français québécois par ele-sage (communauté). " +
            "WER annoncé par son auteur : 6,65 % sur son jeu de validation. " +
            "Mesuré ici : 5 erreurs sur 59 mots (8,5 %) sur six courts extraits Common Voice " +
            "(une de ses sources d'entraînement : il a pu les entendre), contre 4 pour Parakeet " +
            "et 6 pour Whisper turbo : un indice, pas une preuve. Français seulement.",
        license = "MIT",
        note = WHISPER_QC_NOTE,
        // Issue #22: in the user's tests this fine-tune did worse than the base Whisper turbo. Hidden, not deleted.
        visibility = ModelVisibility.HIDDEN,
        files = listOf(
            ModelFile("turbo-encoder.int8.onnx", "$WHISPER_QC/turbo-encoder.int8.onnx",
                "3f774a722b473fb85fca92e24591afffec5159c53564089fed4dc191fe6fabfc", 674348342),
            ModelFile("turbo-decoder.int8.onnx", "$WHISPER_QC/turbo-decoder.int8.onnx",
                "9775633c180fa256f096fd97ad8825cfff2d159090d8a6f98542d82ae01e567e", 360961539),
            ModelFile("turbo-tokens.txt", "$WHISPER_QC/turbo-tokens.txt",
                "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126", 816730),
        ),
    )

    /**
     * `efficient-speech/lite-whisper-large-v3-turbo-acc` (Apache-2.0): Whisper large-v3-turbo with a
     * low-rank compressed encoder. Same file names and 128 mels as [whisperTurbo], so the same engine
     * branch loads it. Issue #72: officially withdrawn, shown nowhere; the entry stays so that its files, if they
     * are on a phone, can still be found and deleted.
     */
    val liteWhisperTurbo = ModelSpec(
        id = "lite-whisper-large-v3-turbo-acc-int8",
        displayName = "Lite-Whisper large-v3-turbo",
        shortName = "Lite-Whisper",
        publisher = "efficient-speech (LiteASR), d'après OpenAI",
        family = ModelFamily.WHISPER,
        description = "Whisper large-v3-turbo dont l'encodeur est compressé (LiteASR). " +
            "Retiré (issue #72). Sur un ordinateur de test, plus gros et un peu plus lent que Whisper turbo, " +
            "qualité semblable. À comparer sur votre téléphone.",
        license = "Apache-2.0",
        visibility = ModelVisibility.HIDDEN,
        files = listOf(
            ModelFile("turbo-encoder.int8.onnx", "$LITE_WHISPER/turbo-encoder.int8.onnx",
                "ccefad7d2aa1a07569b3894390cd3cb21be06e0f0381b1adbb3c9e3ac9f1ce17", 799004644),
            ModelFile("turbo-decoder.int8.onnx", "$LITE_WHISPER/turbo-decoder.int8.onnx",
                "f7e5ca5fa2eb712b87b9626d46d512d6f1037b4761aadfdc0de2687c98e6df8d", 360961539),
            ModelFile("turbo-tokens.txt", "$LITE_WHISPER/turbo-tokens.txt",
                "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126", 816730),
        ),
    )

    /**
     * Every downloadable on-device model, hidden ones included (their files may still be on the
     * phone). Screens list [offered] and [experimental] instead.
     */
    val all: List<ModelSpec> =
        listOf(whisperTurbo, parakeet, qwen3, whisperQuebec, liteWhisperTurbo)

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
