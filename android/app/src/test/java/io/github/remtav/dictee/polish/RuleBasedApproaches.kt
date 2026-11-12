package io.github.remtav.dictee.polish

import io.github.remtav.dictee.text.polish.PolishRules
import io.github.remtav.dictee.text.polish.ShippedVocabulary

/** Approach 1 (issue #15, step 3.2): deterministic rules for spoken addresses and spelled letters. */
object RulesApproach : PolishApproach {
    override val name = "regles"
    override val needsLlm = false
    override val description = "règles déterministes : adresses dites, épellation, code postal (PolishRules)"
    override fun polish(raw: String, language: String) = PolishRules.polish(raw, language)
}

/** Approach 2: the shipped list of names and their usual mis-transcriptions. */
object VocabularyApproach : PolishApproach {
    override val name = "vocabulaire"
    override val needsLlm = false
    override val description = "vocabulaire livré : noms techniques et d'ici, formes mal transcrites (ShippedVocabulary)"
    override fun polish(raw: String, language: String) = ShippedVocabulary.apply(raw, language)
}

/**
 * The same list without the forms tagged OBSERVED (those that appear literally in the evaluation set):
 * a lower bound that says how much of the score comes from forms chosen after seeing the set.
 */
object GeneralVocabularyApproach : PolishApproach {
    override val name = "vocabulaire-general"
    override val needsLlm = false
    override val description = "vocabulaire livré sans les formes relevées dans le jeu (borne basse : mesure le surapprentissage)"
    override fun polish(raw: String, language: String) =
        ShippedVocabulary.apply(raw, language, setOf(ShippedVocabulary.Origin.GENERAL))
}

/** Approaches 1 + 2: rules first, then the vocabulary (which leaves written addresses alone). */
object RulesAndVocabularyApproach : PolishApproach {
    override val name = "regles+vocabulaire"
    override val needsLlm = false
    override val description = "règles, puis vocabulaire livré"
    override fun polish(raw: String, language: String) =
        ShippedVocabulary.apply(PolishRules.polish(raw, language), language)
}
