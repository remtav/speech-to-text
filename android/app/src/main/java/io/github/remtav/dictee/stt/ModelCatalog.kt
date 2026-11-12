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
) {
    val totalBytes: Long get() = files.sumOf { it.size }
}

object ModelCatalog {
    /** Shown on Whisper's card and in its recommendation (DECISIONS.md, issue #15, « friperie »). */
    const val WHISPER_NOTE =
        "Observé sur un téléphone : comprend bien des mots que les autres modèles ratent, mais plus lent. " +
            "À garder en dernier recours, quand les autres modèles comprennent mal un mot."
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
            "Mesuré ici : 5 erreurs sur 59 mots (8,5 %) sur six courts extraits, contre 4 pour Parakeet " +
            "et 6 pour Whisper turbo : un indice, pas une preuve. Français seulement.",
        license = "MIT",
        note = WHISPER_QC_NOTE,
        files = listOf(
            ModelFile("turbo-encoder.int8.onnx", "$WHISPER_QC/turbo-encoder.int8.onnx",
                "3f774a722b473fb85fca92e24591afffec5159c53564089fed4dc191fe6fabfc", 674348342),
            ModelFile("turbo-decoder.int8.onnx", "$WHISPER_QC/turbo-decoder.int8.onnx",
                "9775633c180fa256f096fd97ad8825cfff2d159090d8a6f98542d82ae01e567e", 360961539),
            ModelFile("turbo-tokens.txt", "$WHISPER_QC/turbo-tokens.txt",
                "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126", 816730),
        ),
    )

    /** Downloadable on-device models. */
    val all: List<ModelSpec> =
        listOf(parakeet, qwen3, whisperTurbo, whisperQuebec)

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

    val default: ModelSpec = parakeet

    fun byId(id: String?): ModelSpec? = selectable.firstOrNull { it.id == id }
}
