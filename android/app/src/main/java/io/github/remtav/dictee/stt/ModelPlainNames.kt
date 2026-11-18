package io.github.remtav.dictee.stt

/**
 * Issue #89 (a step forward from #72): the normal path (installation, keyboard, reading test) offers three
 * choices and names them plainly: « Parakeet » (short « Rapide »), « Whisper » (short « Précis ») and
 * « Kalmia Pro » (short « Pro »). The variants (« large-v3-turbo », « TDT 0.6b v3 »), the quantization and the
 * other technical names stay in « Avancé », the comparison and the diagnostic, for whoever wants them.
 */
object ModelPlainNames {
    /** The name in a list or on a card: « Parakeet », « Whisper », « Kalmia Pro ». */
    fun of(spec: ModelSpec): String = when (spec.id) {
        ModelCatalog.parakeet.id -> "Parakeet"
        ModelCatalog.whisperTurbo.id -> "Whisper"
        ModelCatalog.remote.id -> "Kalmia Pro"
        else -> spec.displayName
    }

    /** The name inside a sentence: a proper name each, no article. */
    fun inSentence(spec: ModelSpec): String = of(spec)

    /** The short label of the keyboard's model button: « Rapide », « Précis », « Pro ». */
    fun short(spec: ModelSpec): String = when (spec.id) {
        ModelCatalog.parakeet.id -> "Rapide"
        ModelCatalog.whisperTurbo.id -> "Précis"
        ModelCatalog.remote.id -> "Pro"
        else -> spec.shortName
    }

    /** Words that must never appear in the normal path (checked by the tests). « Parakeet » and « Whisper » may. */
    val technicalWords: List<String> =
        listOf("TDT", "large-v3", "turbo", "quantification", "int8", "Qwen", "Hugging Face", "SHA-256")
}
