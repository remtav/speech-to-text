package io.github.remtav.dictee.stt

/**
 * Issue #37: in the normal path (installation, keyboard, quick test) a model is named by its effect, never by a
 * technical name (« Whisper », « Parakeet », « large-v3-turbo », « quantification »). Technical names stay in
 * « Avancé », the comparison and the diagnostic, for whoever wants them.
 */
object ModelPlainNames {
    /** The name in a list or on a card: « Plus précis », « Plus léger et plus rapide ». */
    fun of(spec: ModelSpec): String = when (spec.id) {
        ModelCatalog.whisperTurbo.id -> "Plus précis"
        ModelCatalog.parakeet.id -> "Plus léger et plus rapide"
        ModelCatalog.remote.id -> "Serveur distant"
        // Hidden and experimental models are reached only from « Avancé »: their own name is fine there.
        else -> spec.displayName
    }

    /** The name inside a sentence: « le modèle plus précis ». */
    fun inSentence(spec: ModelSpec): String = when (spec.id) {
        ModelCatalog.whisperTurbo.id -> "le modèle plus précis"
        ModelCatalog.parakeet.id -> "le modèle plus léger et plus rapide"
        ModelCatalog.remote.id -> "le serveur distant"
        else -> spec.displayName
    }

    /** The short label of the keyboard's model button: « Précis », « Léger ». */
    fun short(spec: ModelSpec): String = when (spec.id) {
        ModelCatalog.whisperTurbo.id -> "Précis"
        ModelCatalog.parakeet.id -> "Léger"
        ModelCatalog.remote.id -> "Serveur"
        else -> spec.shortName
    }

    /** Words that must never appear in the normal path (checked by the tests). */
    val technicalWords: List<String> =
        listOf("Whisper", "Parakeet", "large-v3", "turbo", "quantification", "int8", "Qwen", "Hugging Face", "SHA-256")
}
