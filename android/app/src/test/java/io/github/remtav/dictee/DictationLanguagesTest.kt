package io.github.remtav.dictee

import io.github.remtav.dictee.stt.DictationLanguage
import io.github.remtav.dictee.stt.DictationLanguage.*
import io.github.remtav.dictee.stt.DictationLanguages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #15, step 1.3: from the chosen languages to the engine settings and to storage. */
class DictationLanguagesTest {

    @Test
    fun quebecAndFranceTogetherStillForceFrench() {
        assertEquals("fr", DictationLanguages.whisperLanguage(setOf(FRENCH_QUEBEC)))
        assertEquals("fr", DictationLanguages.whisperLanguage(setOf(FRENCH_QUEBEC, FRENCH_FRANCE)))
    }

    @Test
    fun severalDistinctCodesMeanAutomaticDetection() {
        assertEquals("", DictationLanguages.whisperLanguage(setOf(FRENCH_QUEBEC, ENGLISH)))
        assertEquals("", DictationLanguages.whisperLanguage(setOf(HINDI, URDU)))
        assertEquals("", DictationLanguages.remoteLanguage(setOf(FRENCH_QUEBEC, ENGLISH)))
    }

    @Test
    fun singleLanguageGivesItsEngineCode() {
        for (l in DictationLanguage.entries) {
            assertEquals(l.engineCode, DictationLanguages.whisperLanguage(setOf(l)))
        }
    }

    @Test
    fun qwen3GetsALanguageNameOnlyForOneCoveredCode() {
        assertEquals("French", DictationLanguages.qwen3Language(setOf(FRENCH_QUEBEC)))
        assertEquals("French", DictationLanguages.qwen3Language(setOf(FRENCH_QUEBEC, FRENCH_FRANCE)))
        assertEquals("English", DictationLanguages.qwen3Language(setOf(ENGLISH)))
        assertEquals("", DictationLanguages.qwen3Language(setOf(FRENCH_QUEBEC, ENGLISH)))
        // Not Qwen3-ASR languages: automatic detection rather than a wrong name.
        assertEquals("", DictationLanguages.qwen3Language(setOf(URDU)))
        assertEquals("", DictationLanguages.qwen3Language(setOf(DictationLanguage.BENGALI)))
        for (l in DictationLanguage.entries) {
            val name = DictationLanguages.qwen3Language(setOf(l))
            assertTrue("$l -> $name", name.isEmpty() || name.first().isUpperCase())
        }
    }

    @Test
    fun serverOnlyAcceptsEmptyFrenchOrEnglish() {
        for (l in DictationLanguage.entries) {
            val code = DictationLanguages.remoteLanguage(setOf(l))
            assertTrue("$l -> $code", code in setOf("", "fr", "en"))
        }
        assertEquals("fr", DictationLanguages.remoteLanguage(setOf(FRENCH_FRANCE)))
        assertEquals("en", DictationLanguages.remoteLanguage(setOf(ENGLISH)))
        assertEquals("", DictationLanguages.remoteLanguage(setOf(MANDARIN)))
    }

    @Test
    fun emptyOrUnknownStoredChoiceFallsBackToTheDefault() {
        assertEquals(setOf(FRENCH_QUEBEC), DictationLanguages.parse(null))
        assertEquals(setOf(FRENCH_QUEBEC), DictationLanguages.parse(emptySet()))
        assertEquals(setOf(FRENCH_QUEBEC), DictationLanguages.parse(setOf("KLINGON")))
        assertEquals(setOf(FRENCH_QUEBEC), DictationLanguages.serialize(emptySet()).let(DictationLanguages::parse))
    }

    @Test
    fun storageRoundTripKeepsListOrder() {
        val chosen = setOf(RUSSIAN, ENGLISH, FRENCH_QUEBEC)
        val back = DictationLanguages.parse(DictationLanguages.serialize(chosen))
        assertEquals(chosen, back)
        assertEquals(listOf(FRENCH_QUEBEC, ENGLISH, RUSSIAN), back.toList())
    }

    @Test
    fun frenchQuebecIsFirstAndTheDefault() {
        assertEquals(FRENCH_QUEBEC, DictationLanguage.entries.first())
        assertEquals(FRENCH_QUEBEC, DictationLanguage.default)
    }
}
