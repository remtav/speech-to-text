package io.github.remtav.dictee.stt

/**
 * The languages the user can pick at first launch (issue #15, step 1.1), and
 * the table that turns that choice into a recommended model.
 *
 * Pure Kotlin, no Android dependency: it is tested on the JVM over every
 * combination of languages.
 */
enum class DictationLanguage(
    /** Language code handed to the engines that take one (Whisper, remote server). */
    val engineCode: String,
    /** Name shown to the user. */
    val label: String,
) {
    // Order of the list shown to the user; Quebec French comes first. Five choices since issue #60:
    // Mandarin, Hindi, Arabic, Bengali, Russian and Urdu were removed (DECISIONS.md).
    FRENCH_QUEBEC("fr", "Français (Québec)"),
    FRENCH_FRANCE("fr", "Français (France)"),
    ENGLISH("en", "Anglais"),
    SPANISH("es", "Espagnol"),
    PORTUGUESE("pt", "Portugais");

    /** The name inside a sentence: first letter in lower case only, so « (Québec) » keeps its capital. */
    val inSentence: String get() = label.replaceFirstChar { it.lowercase() }

    companion object {
        /** Preselected at first launch. */
        val default = FRENCH_QUEBEC
    }
}

/** A model to put forward, with the sentence that justifies it. */
data class ModelRecommendation(val model: ModelSpec, val reason: String)

object ModelRecommendations {

    private val parakeetLanguages = setOf(
        DictationLanguage.FRENCH_QUEBEC, DictationLanguage.FRENCH_FRANCE,
        DictationLanguage.ENGLISH, DictationLanguage.SPANISH,
        DictationLanguage.PORTUGUESE,
    )

    private val qwen3Languages = parakeetLanguages

    // Whisper's tokenizer lists all five (and many more).
    private val whisperLanguages = DictationLanguage.entries.toSet()

    /**
     * Languages each downloadable model covers, from the official sources
     * listed in DECISIONS.md. "Not confirmed" counts as not covered.
     */
    val coverage: Map<String, Set<DictationLanguage>> = mapOf(
        ModelCatalog.parakeet.id to parakeetLanguages,
        ModelCatalog.qwen3.id to qwen3Languages,
        ModelCatalog.whisperTurbo.id to whisperLanguages,
        // Fine-tuned on French only: the other languages are not covered.
        ModelCatalog.whisperQuebec.id to setOf(DictationLanguage.FRENCH_QUEBEC, DictationLanguage.FRENCH_FRANCE),
        // Same tokenizer and decoder as Whisper turbo, only the encoder is compressed (issue #22).
        ModelCatalog.liteWhisperTurbo.id to whisperLanguages,
    )

    /**
     * Does [model] cover [language]? Used for the warning when a chosen language
     * is not covered. The remote server is the user's own: the language code is
     * passed along and we cannot judge it, so it is never flagged. Any other
     * unknown model is "not covered".
     */
    fun covers(model: ModelSpec, language: DictationLanguage): Boolean =
        if (model.family == ModelFamily.REMOTE) true
        else coverage[model.id]?.contains(language) == true

    /** The languages of [chosen] that [model] does not cover, in list order. */
    fun uncovered(model: ModelSpec, chosen: Set<DictationLanguage>): List<DictationLanguage> =
        DictationLanguage.entries.filter { it in chosen && !covers(model, it) }

    /**
     * Issue #22: Whisper large-v3-turbo is always the recommended model. It did best in the user's
     * tests, Quebec French included, and it covers all five languages. With French (France), English,
     * Spanish or Portuguese only, Parakeet is offered as a lighter option ([ModelCatalog.offered],
     * [TranscriptionAdvisor.lightPossible]); it is never the recommendation, the quick test may suggest it.
     * Hidden and experimental models are never recommended.
     *
     * Issue #37: the reason names no model (« Whisper », « Parakeet »…): it describes the effect.
     */
    fun recommend(chosen: Set<DictationLanguage>): ModelRecommendation {
        val whisper = ModelCatalog.whisperTurbo
        val reason = when {
            TranscriptionAdvisor.lightPossible(chosen) ->
                "Le plus précis aux essais (1,04 Go). Pour vos langues, un modèle plus léger (671 Mo) " +
                    "et plus rapide est aussi proposé : le test d'une minute vous dit lequel vous convient."
            DictationLanguage.FRENCH_QUEBEC in chosen ->
                "Le plus précis aux essais, en français québécois aussi : c'est celui qu'il faut pour le français d'ici (1,04 Go)."
            else -> "Le seul qui couvre toutes vos langues, et le plus précis aux essais (1,04 Go)."
        }
        return ModelRecommendation(whisper, reason)
    }
}
