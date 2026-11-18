package io.github.remtav.dictee

import io.github.remtav.dictee.stt.DictationLanguage
import io.github.remtav.dictee.stt.DictationLanguage.*
import io.github.remtav.dictee.stt.DictationLanguages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals("", DictationLanguages.whisperLanguage(setOf(SPANISH, PORTUGUESE)))
        assertEquals("", DictationLanguages.remoteLanguage(setOf(FRENCH_QUEBEC, ENGLISH)))
    }

    @Test
    fun singleLanguageGivesItsEngineCode() {
        for (l in DictationLanguage.entries) {
            assertEquals(l.engineCode, DictationLanguages.whisperLanguage(setOf(l)))
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
        assertEquals("", DictationLanguages.remoteLanguage(setOf(SPANISH)))
    }

    /** Issue #86: in automatic mode, the server's detection is limited to the user's languages. */
    @Test
    fun detectionIsLimitedToSeveralChosenLanguagesInListOrder() {
        assertEquals(listOf("fr", "en"), DictationLanguages.detectionLanguages(setOf(ENGLISH, FRENCH_QUEBEC)))
        assertEquals(listOf("fr", "en", "es"), DictationLanguages.detectionLanguages(setOf(SPANISH, FRENCH_FRANCE, ENGLISH, FRENCH_QUEBEC)))
        // A single language: nothing to choose between, the server is left free (a forced language is the setting's job).
        assertEquals(emptyList<String>(), DictationLanguages.detectionLanguages(setOf(FRENCH_QUEBEC, FRENCH_FRANCE)))
        assertEquals(emptyList<String>(), DictationLanguages.detectionLanguages(setOf(ENGLISH)))
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
        val chosen = setOf(PORTUGUESE, ENGLISH, FRENCH_QUEBEC)
        val back = DictationLanguages.parse(DictationLanguages.serialize(chosen))
        assertEquals(chosen, back)
        assertEquals(listOf(FRENCH_QUEBEC, ENGLISH, PORTUGUESE), back.toList())
    }

    @Test
    fun exactlyTheFiveLanguagesOfIssue60AreOffered() {
        assertEquals(
            listOf(FRENCH_QUEBEC, FRENCH_FRANCE, ENGLISH, SPANISH, PORTUGUESE),
            DictationLanguage.entries.toList(),
        )
    }

    @Test
    fun anUnknownStoredNameIsIgnoredAndAnEmptyResultIsFrenchQuebec() {
        assertEquals(setOf(FRENCH_FRANCE, ENGLISH), DictationLanguages.parse(setOf("ENGLISH", "MANDARIN", "FRENCH_FRANCE", "URDU")))
        assertEquals(setOf(FRENCH_QUEBEC), DictationLanguages.parse(setOf("HINDI", "MANDARIN")))
    }

    @Test
    fun frenchQuebecIsFirstAndTheDefault() {
        assertEquals(FRENCH_QUEBEC, DictationLanguage.entries.first())
        assertEquals(FRENCH_QUEBEC, DictationLanguage.default)
    }
}
