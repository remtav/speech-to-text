package io.github.remtav.dictee.ime.words

import io.github.remtav.dictee.ime.LayoutKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #71: suggestions and automatic correction on the real word lists. */
class SuggesterTest {

    private val fr = Suggester(listOf(WordListsFixture.real("fr")), KeyNeighbours.of(LayoutKind.QWERTY))
    private val frAzerty = Suggester(listOf(WordListsFixture.real("fr")), KeyNeighbours.of(LayoutKind.AZERTY))
    private val en = Suggester(listOf(WordListsFixture.real("en")), KeyNeighbours.of(LayoutKind.QWERTY))
    private val frEn = Suggester(listOf(WordListsFixture.real("fr"), WordListsFixture.real("en")), KeyNeighbours.of(LayoutKind.QWERTY))

    @Test
    fun completesTheWordBeingTyped() {
        assertTrue(fr.suggest("bonj").words.toString(), "bonjour" in fr.suggest("bonj").words)
        assertTrue(fr.suggest("aujourd").words.toString(), "aujourd'hui" in fr.suggest("aujourd").words)
        assertTrue(en.suggest("tomor").words.toString(), "tomorrow" in en.suggest("tomor").words)
    }

    @Test
    fun restoresAccents() {
        val s = fr.suggest("ecole")
        assertEquals("école", s.autoCorrect)
        assertEquals("école", s.words.first())
        assertEquals("très", fr.suggest("tres").autoCorrect)
    }

    @Test
    fun correctsTypos() {
        assertEquals("the", en.suggest("teh").autoCorrect)
        assertEquals("maison", fr.suggest("maisno").autoCorrect)
        // A neighbouring key: w is next to e on QWERTY.
        assertEquals("merci", fr.suggest("mwrci").autoCorrect)
        // On AZERTY, q sits where a is on QWERTY.
        assertEquals("parle", frAzerty.suggest("pqrle").autoCorrect)
    }

    @Test
    fun aKnownWordIsNeverCorrected() {
        for (w in listOf("a", "à", "ou", "où", "la", "là", "mais", "mai")) {
            val s = fr.suggest(w)
            assertTrue(w, s.typedIsWord)
            assertNull(w, s.autoCorrect)
        }
        assertNull(frEn.suggest("the").autoCorrect)
        assertNull(frEn.suggest("maison").autoCorrect)
    }

    @Test
    fun theTypedWordIsNotAmongTheSuggestions() {
        assertFalse("mais" in fr.suggest("mais").words)
        assertTrue(fr.suggest("mais").words.isNotEmpty())
    }

    @Test
    fun shortOrStrangeWordsAreLeftAlone() {
        assertNull(fr.suggest("zx").autoCorrect)
        assertNull(fr.suggest("qwzkp").autoCorrect)
        assertEquals(Suggestions.NONE, fr.suggest(""))
        assertTrue(fr.suggest("abc1").words.isEmpty())
    }

    @Test
    fun capitalsFollowTheTypedWord() {
        assertEquals("École", fr.suggest("Ecole").autoCorrect)
        assertTrue(fr.suggest("Bonj").words.toString(), "Bonjour" in fr.suggest("Bonj").words)
        assertEquals("ÉCOLE", fr.suggest("ECOLE").words.firstOrNull())
        // All capitals or capitals inside: never corrected automatically.
        assertNull(fr.suggest("ECOLE").autoCorrect)
        assertNull(en.suggest("iPhon").autoCorrect)
    }

    @Test
    fun elisionsAndCompoundsKeepTheirBeginning() {
        val s = fr.suggest("l'ecole")
        assertEquals("l'école", s.autoCorrect)
        assertTrue(fr.suggest("qu'il").typedIsWord || fr.suggest("qu'il").autoCorrect == null)
        assertTrue(fr.suggest("j'aim").words.toString(), "j'aime" in fr.suggest("j'aim").words)
    }

    @Test
    fun offensiveWordsAreNeverOffered() {
        val lex = WordListsFixture.small("merci" to 200, "merde!" to 210)
        val s = Suggester(listOf(lex)).suggest("mer")
        assertEquals(listOf("merci"), s.words)
        assertEquals("merci", Suggester(listOf(lex)).suggest("merdi").autoCorrect)
    }

    @Test
    fun quebecWordsAreNotInTheListsYet() {
        // Decision of 2026-11-14: nothing for Québec words in this first pass (#78). The correction is off by
        // default, so "chu" or "tsé" typed stay as typed unless the person turns it on.
        assertFalse(fr.suggest("chu").typedIsWord && fr.suggest("tsé").typedIsWord && fr.suggest("faque").typedIsWord)
    }

    @Test
    fun fastEnoughForEveryKey() {
        val words = listOf("b", "bo", "bon", "bonj", "bonjo", "bonjou", "bonjour", "maisno", "aujourdhui", "developpement", "anticonstitutionnel")
        frEn.suggest("warm")
        val start = System.nanoTime()
        repeat(5) { for (w in words) frEn.suggest(w) }
        val perKey = (System.nanoTime() - start) / 1e6 / (5 * words.size)
        assertTrue("$perKey ms per key", perKey < 30)
        println("suggestions: %.2f ms per key (fr + en)".format(perKey))
    }
}
