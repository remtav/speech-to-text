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

    // Whisper's tokenizer lists all five (and many more).
    private val whisperLanguages = DictationLanguage.entries.toSet()

    /**
     * Languages each downloadable model covers, from the official sources
     * listed in DECISIONS.md. "Not confirmed" counts as not covered.
     */
    val coverage: Map<String, Set<DictationLanguage>> = mapOf(
        ModelCatalog.parakeet.id to parakeetLanguages,
        ModelCatalog.whisperTurbo.id to whisperLanguages,
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
     * Issue #22: Whisper large-v3-turbo is always the recommended model. It did best in the user's tests,
     * Quebec French included, and it covers all five languages. Hidden and advanced models are
     * never recommended.
     *
     * Issue #72: the model is named « Whisper » (a step back from #37), in one sentence that mentions no second model.
     */
    @Suppress("UNUSED_PARAMETER")
    fun recommend(chosen: Set<DictationLanguage>): ModelRecommendation =
        ModelRecommendation(
            ModelCatalog.whisperTurbo,
            "Whisper : le modèle qui comprend le mieux le français d'ici aux essais (1,04 Go).",
        )
}
