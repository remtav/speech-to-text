package io.github.remtav.dictee

import io.github.remtav.dictee.stt.AdviceReason
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
import io.github.remtav.dictee.stt.TranscriptionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #36a: the table languages → recommended mode. */
class TranscriptionAdvisorTest {
    private fun advice(vararg l: DictationLanguage, server: Boolean = true) =
        TranscriptionAdvisor.recommend(l.toSet(), server)

    /** Every non-empty combination of the eleven languages. */
    private val combos: List<Set<DictationLanguage>> = (1 until (1 shl DictationLanguage.entries.size)).map { mask ->
        DictationLanguage.entries.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
    }

    @Test fun quebecFrenchAloneOrWithOthersGoesToTheServer() {
        for (c in combos.filter { FRENCH_QUEBEC in it }) {
            val a = TranscriptionAdvisor.recommend(c, serverConfigured = true)
            assertEquals(c.toString(), TranscriptionMode.SERVER, a.mode)
            assertEquals(AdviceReason.QUEBEC_FRENCH, a.reason)
            assertTrue(a.phoneDiscouraged)
        }
    }

    @Test fun otherFourLanguagesWithoutQuebecGoToThePhoneWithTheLightModel() {
        val light = setOf(FRENCH_FRANCE, ENGLISH, SPANISH, PORTUGUESE)
        for (c in combos.filter { light.containsAll(it) }) {
            val a = TranscriptionAdvisor.recommend(c, serverConfigured = true)
            assertEquals(c.toString(), TranscriptionMode.PHONE, a.mode)
            assertEquals(ModelCatalog.parakeet, a.phoneModel)
            assertEquals(AdviceReason.LIGHT_MODEL_ENOUGH, a.reason)
            assertFalse(a.phoneDiscouraged)
            assertTrue(c.toString(), ModelCatalog.parakeet in ModelCatalog.offered(c))
        }
    }

    @Test fun otherLanguagesGoToTheServer() {
        for (c in combos.filter { FRENCH_QUEBEC !in it && !TranscriptionAdvisor.lightPhoneLanguages.containsAll(it) }) {
            val a = TranscriptionAdvisor.recommend(c, serverConfigured = true)
            assertEquals(c.toString(), TranscriptionMode.SERVER, a.mode)
            assertEquals(AdviceReason.OTHER_LANGUAGES, a.reason)
            assertEquals(ModelRecommendations.recommend(c).model, a.phoneModel)
        }
        assertEquals(TranscriptionMode.SERVER, advice(ENGLISH, MANDARIN).mode)
    }

    @Test fun withoutServerAddressAlwaysThePhoneAndNothingDiscouraged() {
        for (c in combos) {
            val a = TranscriptionAdvisor.recommend(c, serverConfigured = false)
            assertEquals(c.toString(), TranscriptionMode.PHONE, a.mode)
            assertEquals(AdviceReason.NO_SERVER, a.reason)
            assertFalse(a.phoneDiscouraged)
            assertFalse(a.serverRecommended)
        }
        assertEquals(TranscriptionMode.PHONE, TranscriptionAdvisor.recommend(emptySet(), false).mode)
    }

    @Test fun phoneModelWithoutQuebecServerIsWhisperTurbo() {
        assertEquals(ModelCatalog.whisperTurbo, advice(FRENCH_QUEBEC, server = false).phoneModel)
        assertEquals(ModelCatalog.whisperTurbo, advice(MANDARIN, server = false).phoneModel)
        assertEquals(ModelCatalog.parakeet, advice(ENGLISH, server = false).phoneModel)
    }

    @Test fun emptyChoiceNeverUnsetAndGoesToTheServer() {
        val a = TranscriptionAdvisor.recommend(emptySet(), serverConfigured = true)
        assertEquals(TranscriptionMode.SERVER, a.mode)
        assertTrue(combos.all { TranscriptionAdvisor.recommend(it, true).mode != TranscriptionMode.UNSET })
    }

    @Test fun quickTestLanguageIsTheFirstChosenOfTheFive() {
        assertEquals(FRENCH_QUEBEC, TranscriptionAdvisor.quickTestLanguage(setOf(PORTUGUESE, FRENCH_QUEBEC)))
        assertEquals(FRENCH_FRANCE, TranscriptionAdvisor.quickTestLanguage(setOf(ENGLISH, FRENCH_FRANCE, MANDARIN)))
        assertEquals(ENGLISH, TranscriptionAdvisor.quickTestLanguage(setOf(MANDARIN, ENGLISH, SPANISH)))
        assertEquals(SPANISH, TranscriptionAdvisor.quickTestLanguage(setOf(SPANISH, PORTUGUESE)))
        assertNull(TranscriptionAdvisor.quickTestLanguage(setOf(MANDARIN)))
        assertNull(TranscriptionAdvisor.quickTestLanguage(emptySet()))
    }
}
