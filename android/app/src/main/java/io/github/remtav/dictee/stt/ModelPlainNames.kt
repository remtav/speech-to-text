package io.github.remtav.dictee.stt

/**
 * Issue #72 (a step back from #37): in the normal path (installation, keyboard, reading test) the phone model is
 * named « Whisper », and no more than that: the variant (« large-v3-turbo »), the quantization and the other
 * technical names stay in « Avancé », the comparison and the diagnostic, for whoever wants them. Parakeet is in
 * « Avancé » only, so it has no plain name: it falls back to its own technical one.
 */
object ModelPlainNames {
    /** The name in a list or on a card: « Whisper ». */
    fun of(spec: ModelSpec): String = when (spec.id) {
        ModelCatalog.whisperTurbo.id -> "Whisper"
        ModelCatalog.remote.id -> "Serveur distant"
        // Advanced and hidden models are reached only from « Avancé »: their own name is fine there.
        else -> spec.displayName
    }

    /** The name inside a sentence: « Whisper » (a proper name: no article). */
    fun inSentence(spec: ModelSpec): String = when (spec.id) {
        ModelCatalog.whisperTurbo.id -> "Whisper"
        ModelCatalog.remote.id -> "le serveur distant"
        else -> spec.displayName
    }

    /** The short label of the keyboard's model button: « Whisper ». */
    fun short(spec: ModelSpec): String = when (spec.id) {
        ModelCatalog.whisperTurbo.id -> "Whisper"
        ModelCatalog.remote.id -> "Serveur"
        else -> spec.shortName
    }

    /** Words that must never appear in the normal path (checked by the tests). */
    val technicalWords: List<String> =
        listOf("Parakeet", "large-v3", "turbo", "quantification", "int8", "Qwen", "Hugging Face", "SHA-256")
}
