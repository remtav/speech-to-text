package io.github.remtav.dictee.stt

import io.github.remtav.dictee.stt.DictationLanguage.ENGLISH
import io.github.remtav.dictee.stt.DictationLanguage.FRENCH_FRANCE
import io.github.remtav.dictee.stt.DictationLanguage.FRENCH_QUEBEC
import io.github.remtav.dictee.stt.DictationLanguage.PORTUGUESE
import io.github.remtav.dictee.stt.DictationLanguage.SPANISH

/**
 * The phone models that make sense for a choice of languages (issue #36, recentred on the phone on 2026-11-13:
 * the remote server is no longer a product option, issue #57).
 *
 * @property best The most precise model, the default and the app's choice (issue #37: Whisper large-v3-turbo,
 *   never named in the normal path).
 * @property light A lighter and faster model, when one is expected to do for these languages; null otherwise
 *   (Québec French, or a language it does not cover). The quick test then compares the two.
 */
data class PhoneModelChoice(val best: ModelSpec, val light: ModelSpec?) {
    /** The models the quick test transcribes with, the best first. */
    val candidates: List<ModelSpec> get() = listOfNotNull(best, light)
}

/**
 * The table `languages → phone models` (issue #36), next to [ModelRecommendations]. Pure Kotlin.
 *
 * - Québec French, alone or with others → the best model only: the French spoken here needs it.
 * - Only French (France), English, Spanish, Portuguese → the best model, and a lighter one is possible.
 * - Any other language → the best model only (the only one that covers all five languages).
 *
 * The "a lighter model is possible" row is an **estimate**, not a measurement on a phone; the quick test
 * ([QuickTestAdvice]) checks it with the person's own voice.
 */
object TranscriptionAdvisor {

    /** The languages for which a light phone model (Parakeet) is expected to suffice, when Québec is not among them. */
    val lightPhoneLanguages: Set<DictationLanguage> = setOf(FRENCH_FRANCE, ENGLISH, SPANISH, PORTUGUESE)

    /** The five languages of the installation path and of the quick test, in display order. */
    val quickTestLanguages: List<DictationLanguage> =
        listOf(FRENCH_QUEBEC, FRENCH_FRANCE, ENGLISH, SPANISH, PORTUGUESE)

    /** The language of the quick test: the first chosen one among [quickTestLanguages], null if none. */
    fun quickTestLanguage(chosen: Set<DictationLanguage>): DictationLanguage? =
        quickTestLanguages.firstOrNull { it in chosen }

    /** Whether a lighter model is worth offering next to the best one for [chosen]. */
    fun lightPossible(chosen: Set<DictationLanguage>): Boolean =
        chosen.isNotEmpty() && FRENCH_QUEBEC !in chosen && lightPhoneLanguages.containsAll(chosen)

    fun choice(chosen: Set<DictationLanguage>): PhoneModelChoice = PhoneModelChoice(
        best = ModelRecommendations.recommend(chosen).model,
        light = if (lightPossible(chosen)) ModelCatalog.parakeet else null,
    )
}
