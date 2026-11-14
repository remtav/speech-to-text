package io.github.remtav.dictee.stt

import io.github.remtav.dictee.stt.DictationLanguage.ENGLISH
import io.github.remtav.dictee.stt.DictationLanguage.FRENCH_FRANCE
import io.github.remtav.dictee.stt.DictationLanguage.FRENCH_QUEBEC
import io.github.remtav.dictee.stt.DictationLanguage.PORTUGUESE
import io.github.remtav.dictee.stt.DictationLanguage.SPANISH

/**
 * The phone model of a choice of languages (issue #36, recentred on the phone on 2026-11-13; since issue #72
 * there is only one: Whisper).
 *
 * @property best The model of the app's choice: Whisper large-v3-turbo.
 * @property light A lighter and faster second model. Always null since issue #72 (Parakeet is in « Avancé » only);
 *   the field stays, with the quick test's two-model code ([QuickTestAdvice]), for the day a second model comes back.
 */
data class PhoneModelChoice(val best: ModelSpec, val light: ModelSpec? = null) {
    /** The models the quick test transcribes with, the best first. */
    val candidates: List<ModelSpec> get() = listOfNotNull(best, light)
}

/**
 * The table `languages → phone model`, next to [ModelRecommendations]. Pure Kotlin. Whisper whatever the languages.
 */
object TranscriptionAdvisor {

    /** The five languages of the installation path and of the quick test, in display order. */
    val quickTestLanguages: List<DictationLanguage> =
        listOf(FRENCH_QUEBEC, FRENCH_FRANCE, ENGLISH, SPANISH, PORTUGUESE)

    /** The language of the quick test: the first chosen one among [quickTestLanguages], null if none. */
    fun quickTestLanguage(chosen: Set<DictationLanguage>): DictationLanguage? =
        quickTestLanguages.firstOrNull { it in chosen }

    fun choice(chosen: Set<DictationLanguage>): PhoneModelChoice =
        PhoneModelChoice(best = ModelRecommendations.recommend(chosen).model)
}
