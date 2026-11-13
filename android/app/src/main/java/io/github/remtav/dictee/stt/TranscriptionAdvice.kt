package io.github.remtav.dictee.stt

import io.github.remtav.dictee.stt.DictationLanguage.ENGLISH
import io.github.remtav.dictee.stt.DictationLanguage.FRENCH_FRANCE
import io.github.remtav.dictee.stt.DictationLanguage.FRENCH_QUEBEC
import io.github.remtav.dictee.stt.DictationLanguage.PORTUGUESE
import io.github.remtav.dictee.stt.DictationLanguage.SPANISH

/** Why [TranscriptionAdvice.recommend] answered as it did. The screens pick the wording from it. */
enum class AdviceReason {
    /** No server address in this build: everything stays on the phone, nothing about the server is shown. */
    NO_SERVER,
    /** Québec French, alone or with others: the best model is too slow on a phone. */
    QUEBEC_FRENCH,
    /** French (France), English, Spanish, Portuguese without Québec: a light model should do. */
    LIGHT_MODEL_ENOUGH,
    /** Any other language, or a mix with one: only Whisper covers them, and it is too slow on a phone. */
    OTHER_LANGUAGES,
}

/**
 * Where dictation should be transcribed for a given choice of languages (issue #36, step 4), before any test.
 *
 * @property mode [TranscriptionMode.SERVER] or [TranscriptionMode.PHONE], never UNSET.
 * @property phoneModel The model to put forward for the phone: the light one when it suffices, else Whisper turbo.
 * @property phoneDiscouraged True when the server is recommended and available: the "on the phone" choice stays
 *   offered, with the mention « moins bon et plus lent ».
 */
data class TranscriptionAdvice(
    val mode: TranscriptionMode,
    val phoneModel: ModelSpec,
    val reason: AdviceReason,
    val phoneDiscouraged: Boolean,
) {
    val serverRecommended: Boolean get() = mode == TranscriptionMode.SERVER
}

/**
 * The table `languages → recommended mode, reason` (issue #36a), next to [ModelRecommendations]. Pure Kotlin.
 *
 * Starting values of the issue, **estimates not confirmed by a measurement on a phone**: the "light model is
 * enough" row is a guess, and the automatic rule after the quick test ([QuickTestAdvice]) corrects it with the
 * person's own voice.
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

    /**
     * - no server address → phone, always;
     * - Québec French, alone or with others → server;
     * - only French (France), English, Spanish, Portuguese → phone with the light model;
     * - anything else (another language, or an empty choice) → server.
     */
    fun recommend(chosen: Set<DictationLanguage>, serverConfigured: Boolean): TranscriptionAdvice {
        val light = chosen.isNotEmpty() && FRENCH_QUEBEC !in chosen && lightPhoneLanguages.containsAll(chosen)
        val phoneModel = if (light) ModelCatalog.parakeet else ModelRecommendations.recommend(chosen).model
        val (mode, reason) = when {
            !serverConfigured -> TranscriptionMode.PHONE to AdviceReason.NO_SERVER
            FRENCH_QUEBEC in chosen -> TranscriptionMode.SERVER to AdviceReason.QUEBEC_FRENCH
            light -> TranscriptionMode.PHONE to AdviceReason.LIGHT_MODEL_ENOUGH
            else -> TranscriptionMode.SERVER to AdviceReason.OTHER_LANGUAGES
        }
        return TranscriptionAdvice(
            mode = mode,
            phoneModel = phoneModel,
            reason = reason,
            phoneDiscouraged = serverConfigured && mode == TranscriptionMode.SERVER,
        )
    }
}
