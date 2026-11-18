package io.github.remtav.dictee.ime.words

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #71: the folded form, and the search by prefix in a packed word list. */
class LexiconTest {

    @Test
    fun foldRemovesCapitalsAndAccents() {
        assertEquals("ecole", WordFold.fold("École"))
        assertEquals("coeur", WordFold.fold("cœur"))
        assertEquals("aujourd'hui", WordFold.fold("aujourd’hui"))
        assertEquals("nino", WordFold.fold("niño"))
        assertEquals("nao", WordFold.fold("não"))
    }

    @Test
    fun prefixRangeHoldsExactlyTheWordsStartingWithIt() {
        val lex = WordListsFixture.small("maison" to 150, "mais" to 200, "mai" to 120, "main" to 160, "maïs" to 90, "ma" to 210, "mal" to 140)
        val words = lex.prefixRange("mai").map { lex.word(it) }.toSet()
        assertEquals(setOf("mai", "main", "mais", "maïs", "maison"), words)
        assertTrue(lex.prefixRange("zz").isEmpty())
        assertEquals(lex.size, lex.prefixRange("").count())
    }

    @Test
    fun containsIgnoresCapitalsButNotAccents() {
        val lex = WordListsFixture.small("été" to 150, "Paris" to 120)
        assertTrue(lex.contains("Été"))
        assertTrue(lex.contains("paris"))
        assertFalse(lex.contains("ete"))
    }

    @Test
    fun offensiveFlagAndFrequencyAreRead() {
        val lex = WordListsFixture.small("chat" to 180, "merde!" to 120)
        val i = lex.prefixRange("merde").first
        assertTrue(lex.isOffensive(i))
        assertEquals(120, lex.frequency(i))
        assertFalse(lex.isOffensive(lex.prefixRange("chat").first))
    }

    /** The build script and the Kotlin folding sort the same way: otherwise searches silently miss words. */
    @Test
    fun shippedListsAreSortedByTheKotlinFolding() {
        for (code in listOf("fr", "en", "es", "pt")) {
            val lex = WordListsFixture.real(code)
            assertTrue("$code: ${lex.size} words", lex.size > 150_000)
            var previous = ""
            for (i in 0 until lex.size) {
                val key = lex.key(i)
                assertEquals("$code: folded form of ${lex.word(i)}", WordFold.fold(lex.word(i)), key)
                assertTrue("$code: $previous before $key", previous <= key)
                previous = key
            }
        }
    }
}
