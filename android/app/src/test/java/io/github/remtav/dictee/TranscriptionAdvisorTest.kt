package io.github.remtav.dictee

import io.github.remtav.dictee.stt.DictationLanguage
import io.github.remtav.dictee.stt.DictationLanguage.ENGLISH
import io.github.remtav.dictee.stt.DictationLanguage.FRENCH_FRANCE
import io.github.remtav.dictee.stt.DictationLanguage.FRENCH_QUEBEC
import io.github.remtav.dictee.stt.DictationLanguage.MANDARIN
import io.github.remtav.dictee.stt.DictationLanguage.PORTUGUESE
import io.github.remtav.dictee.stt.DictationLanguage.SPANISH
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.ModelRecommendations
import io.github.remtav.dictee.stt.TranscriptionAdvisor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #36, recentred on the phone (issue #57): the table languages → phone models. */
class TranscriptionAdvisorTest {
    /** Every non-empty combination of the eleven languages. */
    private val combos: List<Set<DictationLanguage>> = (1 until (1 shl DictationLanguage.entries.size)).map { mask ->
        DictationLanguage.entries.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
    }

    @Test fun theBestModelIsAlwaysTheAppsChoice() {
        for (c in combos + listOf(emptySet())) {
            val choice = TranscriptionAdvisor.choice(c)
            assertEquals(c.toString(), ModelCatalog.whisperTurbo, choice.best)
            assertEquals(ModelRecommendations.recommend(c).model, choice.best)
            assertEquals(choice.best, choice.candidates.first())
        }
    }

    /** Issue #72: one phone model, whatever the languages: no light model, no second candidate. */
    @Test fun thereIsNeverASecondPhoneModel() {
        for (c in combos + listOf(emptySet())) {
            val choice = TranscriptionAdvisor.choice(c)
            assertNull(c.toString(), choice.light)
            assertEquals(c.toString(), listOf(ModelCatalog.whisperTurbo), choice.candidates)
            assertEquals(c.toString(), listOf(ModelCatalog.whisperTurbo), ModelCatalog.offered(c))
            assertFalse(c.toString(), ModelCatalog.parakeet in ModelCatalog.offered(c))
        }
    }

    @Test fun quickTestLanguageIsTheFirstChosenOfTheFive() {
        assertEquals(FRENCH_QUEBEC, TranscriptionAdvisor.quickTestLanguage(setOf(PORTUGUESE, FRENCH_QUEBEC)))
        assertEquals(FRENCH_FRANCE, TranscriptionAdvisor.quickTestLanguage(setOf(ENGLISH, FRENCH_FRANCE, MANDARIN)))
        assertEquals(ENGLISH, TranscriptionAdvisor.quickTestLanguage(setOf(MANDARIN, ENGLISH, SPANISH)))
        assertEquals(SPANISH, TranscriptionAdvisor.quickTestLanguage(setOf(SPANISH, PORTUGUESE)))
        assertNull(TranscriptionAdvisor.quickTestLanguage(setOf(MANDARIN)))
        assertNull(TranscriptionAdvisor.quickTestLanguage(emptySet()))
    }

    @Test fun noServerAnywhereInTheAdvice() {
        for (c in combos) assertTrue(TranscriptionAdvisor.choice(c).candidates.none { it == ModelCatalog.remote })
    }
}
