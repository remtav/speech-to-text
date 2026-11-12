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
    // Order of the list shown to the user; Quebec French comes first.
    FRENCH_QUEBEC("fr", "Français (Québec)"),
    FRENCH_FRANCE("fr", "Français (France)"),
    ENGLISH("en", "Anglais"),
    MANDARIN("zh", "Mandarin"),
    HINDI("hi", "Hindi"),
    SPANISH("es", "Espagnol"),
    ARABIC("ar", "Arabe"),
    BENGALI("bn", "Bengali"),
    PORTUGUESE("pt", "Portugais"),
    RUSSIAN("ru", "Russe"),
    URDU("ur", "Ourdou");

    /** The name inside a sentence: first letter in lower case only, so « (Québec) » keeps its capital. */
    val inSentence: String get() = label.replaceFirstChar { it.lowercase() }

    companion object {
        /** Preselected at first launch. */
        val default = FRENCH_QUEBEC
    }
}

/** What backs a recommendation: the user's observation, or a measured word error rate. */
enum class RecommendationBasis(val word: String, val phrase: String) {
    OBSERVATION("observation", "d'après une observation"),
    /** Six short Common Voice excerpts, 59 words (DECISIONS.md, step 1.2): said as such. */
    MESURE("mesure", "mesuré sur six courts extraits (59 mots)"),
}

/** A model to put forward, with the sentence that justifies it. */
data class ModelRecommendation(val model: ModelSpec, val reason: String)

object ModelRecommendations {

    /**
     * Basis of "Parakeet is better in Quebec French": the WER per model on
     * the six Quebec excerpts (DECISIONS.md, step 1.2: 6.8 % against 10.2 % and
     * 13.6 %). Small sample, so the wording states its scope.
     */
    val quebecFrenchBasis: RecommendationBasis = RecommendationBasis.MESURE

    private val parakeetLanguages = setOf(
        DictationLanguage.FRENCH_QUEBEC, DictationLanguage.FRENCH_FRANCE,
        DictationLanguage.ENGLISH, DictationLanguage.SPANISH,
        DictationLanguage.PORTUGUESE, DictationLanguage.RUSSIAN,
    )

    private val qwen3Languages = parakeetLanguages + setOf(
        DictationLanguage.MANDARIN, DictationLanguage.HINDI, DictationLanguage.ARABIC,
    )

    // Whisper's tokenizer lists all eleven (zh, hi, ar, bn, ur included).
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
     * The table, read top to bottom; the first rule that applies wins.
     *
     * 1. No language chosen: Parakeet, the lightest download and the model of
     *    the preselected language.
     * 2. Every language is among Parakeet's 25: Parakeet (smallest download,
     *    671 Mo). With Quebec French, the reason names the basis.
     * 3. Otherwise Qwen3-ASR (987 Mo) if it covers everything chosen.
     * 4. Otherwise Whisper (1,04 Go), the only model covering all eleven.
     *
     * Nothing is blocked: the other models stay downloadable.
     */
    fun recommend(chosen: Set<DictationLanguage>): ModelRecommendation {
        if (chosen.isEmpty()) {
            return ModelRecommendation(
                ModelCatalog.parakeet,
                "Aucune langue choisie : Parakeet, le plus léger des trois (671 Mo).",
            )
        }
        val outsideParakeet = uncovered(ModelCatalog.parakeet, chosen)
        if (outsideParakeet.isEmpty()) {
            val quebec = DictationLanguage.FRENCH_QUEBEC in chosen
            // Worded to the scope of the evidence: fewest errors on 59 words, not an established superiority
            // (DECISIONS.md, step 1.2: Whisper is one error away once a notation artefact is removed).
            val reason = if (quebec) {
                "Parakeet couvre vos langues et c'est le plus léger des trois (671 Mo). " +
                    "En français québécois, il a fait le moins d'erreurs des trois, ${quebecFrenchBasis.phrase} : " +
                    "un indice, pas une preuve."
            } else {
                "Parakeet couvre vos langues et c'est le plus léger des trois (671 Mo)."
            }
            return ModelRecommendation(ModelCatalog.parakeet, reason)
        }
        val missing = outsideParakeet.joinToString(", ") { it.inSentence }
        val parakeetNote = "Parakeet ne couvre pas : $missing."
        if (uncovered(ModelCatalog.qwen3, chosen).isEmpty()) {
            return ModelRecommendation(
                ModelCatalog.qwen3,
                "$parakeetNote Qwen3-ASR couvre toutes vos langues (987 Mo, plus léger que Whisper).",
            )
        }
        val qwenMissing = uncovered(ModelCatalog.qwen3, chosen).joinToString(", ") { it.inSentence }
        return ModelRecommendation(
            ModelCatalog.whisperTurbo,
            "$parakeetNote Qwen3-ASR ne couvre pas : $qwenMissing. " +
                "Whisper est le seul des trois qui couvre toutes vos langues (1,04 Go). " +
                "Il est plus lent que les deux autres (observé sur un téléphone).",
        )
    }
}
