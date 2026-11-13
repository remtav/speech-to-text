package io.github.remtav.dictee

import io.github.remtav.dictee.ime.KeyLanguage
import io.github.remtav.dictee.ime.KeyboardLayout
import io.github.remtav.dictee.ime.LayoutKind
import io.github.remtav.dictee.stt.DictationLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #42: each language of the text keyboard is complete, and a long-press window shows only the chosen languages. */
class KeyboardLanguagesTest {

    /** ASCII punctuation and signs every language needs (mots de passe, adresses, codes). */
    private val common = "1234567890.,?!'\"-():;@/&$%+=*#_<>[]{}\\|~^°"

    /** Reference list of each language: letters beyond a-z, then punctuation beyond [common]. */
    private val reference = mapOf(
        KeyLanguage.FRENCH to ("àâæçéèêëîïôœùûüÿ" to "«»’…€"),
        KeyLanguage.ENGLISH to ("" to "’“”…"),
        KeyLanguage.SPANISH to ("áéíóúüñ" to "¿¡«»“”…"),
        KeyLanguage.PORTUGUESE to ("áàâãéêíóôõúç" to "“”…€"),
    )

    @Test
    fun everyLanguageReachesItsWholeAlphabetAndPunctuationInBothCases() {
        for ((language, ref) in reference) for (kind in LayoutKind.entries) {
            val reachable = KeyboardLayout.reachableAll(kind, setOf(language))
            val letters = ('a'..'z').joinToString("") + ref.first
            for (c in letters + letters.uppercase()) assertTrue("$c missing for $language/$kind", c.toString() in reachable)
            for (c in common + ref.second) assertTrue("$c missing for $language/$kind", c.toString() in reachable)
        }
    }

    @Test
    fun referenceListsAreEntirelyDistinctFromWhatTheTablesHold() {
        // Every accent in a table is in the language's reference list: no stray character.
        for ((language, ref) in reference) {
            val allowed = ref.first.toSet() + ref.second.toSet() + "€£¥¢–—".toSet()
            val offered = language.accents.values.flatten() + language.marks.values.flatten()
            for (c in offered) assertTrue("$c is not in the reference of $language", c.single() in allowed)
        }
    }

    @Test
    fun longPressWindowHoldsOnlyTheChosenLanguages() {
        val fr = setOf(KeyLanguage.FRENCH)
        val es = setOf(KeyLanguage.SPANISH)
        val en = setOf(KeyLanguage.ENGLISH)
        assertEquals(listOf("é", "è", "ê", "ë"), KeyboardLayout.accentsFor("e", languages = fr))
        assertEquals(listOf("é"), KeyboardLayout.accentsFor("e", languages = es))
        assertTrue(KeyboardLayout.accentsFor("n", languages = fr).isEmpty())
        assertEquals(listOf("ñ"), KeyboardLayout.accentsFor("n", languages = es))
        assertEquals(listOf("Ñ"), KeyboardLayout.accentsFor("n", upper = true, languages = es))
        assertTrue(KeyboardLayout.accentsFor("e", languages = en).isEmpty())
        assertTrue(KeyboardLayout.marksFor("?", fr).isEmpty())
        assertEquals(listOf("¿"), KeyboardLayout.marksFor("?", es))
        assertEquals(listOf("¡"), KeyboardLayout.marksFor("!", es))
        assertEquals(listOf("«", "»"), KeyboardLayout.marksFor("\"", fr))
        assertEquals(listOf("’"), KeyboardLayout.marksFor("'", fr))
        assertEquals(listOf("…"), KeyboardLayout.marksFor(".", fr))
    }

    @Test
    fun severalLanguagesMergeWithoutDuplicatesAndMostFrequentFirst() {
        val both = setOf(KeyLanguage.FRENCH, KeyLanguage.SPANISH, KeyLanguage.PORTUGUESE)
        val a = KeyboardLayout.accentsFor("a", languages = both)
        assertEquals(a.distinct(), a)
        assertEquals(listOf("à", "â", "æ", "á", "ã"), a)
        assertEquals(listOf("é", "è", "ê", "ë"), KeyboardLayout.accentsFor("e", languages = both))
    }

    @Test
    fun theChoiceAtSetupSelectsTheKeyboardLanguages() {
        assertEquals(setOf(KeyLanguage.FRENCH), KeyLanguage.forDictation(setOf(DictationLanguage.FRENCH_QUEBEC)))
        assertEquals(
            setOf(KeyLanguage.FRENCH, KeyLanguage.PORTUGUESE),
            KeyLanguage.forDictation(setOf(DictationLanguage.FRENCH_FRANCE, DictationLanguage.PORTUGUESE, DictationLanguage.HINDI)),
        )
        // None of the four: the keyboard stays complete rather than empty.
        assertEquals(KeyLanguage.entries.toSet(), KeyLanguage.forDictation(setOf(DictationLanguage.MANDARIN)))
        assertEquals(KeyLanguage.entries.toSet(), KeyLanguage.forDictation(emptySet()))
    }

    @Test
    fun azertyIsDeducedFromFrenchOfFrance() {
        assertEquals(LayoutKind.AZERTY, LayoutKind.defaultFor(setOf(DictationLanguage.FRENCH_FRANCE)))
        assertEquals(LayoutKind.QWERTY, LayoutKind.defaultFor(setOf(DictationLanguage.FRENCH_QUEBEC, DictationLanguage.ENGLISH)))
    }

    @Test
    fun symbolsPagesAreDistinctFromTheLongPressWindowsOfOtherKeys() {
        assertFalse(KeyboardLayout.symbolRows(2).flatten().any { it[0].isLetter() })
        assertEquals(KeyboardLayout.symbolRows(1), KeyboardLayout.symbolRows())
    }
}
