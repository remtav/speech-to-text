package io.github.remtav.dictee.words

import io.github.remtav.dictee.ime.words.Fold
import io.github.remtav.dictee.ime.words.Lexicon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream

class LexiconTest {
    @Test
    fun foldingRemovesAccentsAndCapitalsAndKeepsTheLength() {
        assertEquals("eleve", Fold.of("Élève"))
        assertEquals("cour", Fold.of("cœur"))
        assertEquals("garcon", Fold.of("GARÇON"))
        assertEquals("nino", Fold.of("niño"))
        assertEquals("l'ete", Fold.of("l’été"))
        assertEquals("coracao", Fold.of("coração"))
    }

    /** The Python script sorts the lists; the Kotlin search needs the order of the Kotlin folding. */
    @Test
    fun theShippedListsAreSortedByFoldedWord() {
        for (lang in listOf("fr", "en", "es", "pt")) {
            var previous = ""
            var count = 0
            GZIPInputStream(Shipped.file(lang).inputStream()).bufferedReader().useLines { lines ->
                for (line in lines) {
                    val folded = Fold.of(line.substringBefore('\t'))
                    assertTrue("$lang: « $previous » before « $folded »", previous <= folded)
                    previous = folded
                    count++
                }
            }
            assertTrue("$lang: $count words", count > 150_000)
            assertEquals(count, Shipped.lexicon(lang).size)
        }
    }

    @Test
    fun prefixRangeHoldsExactlyTheWordsThatStartWithThePrefix() {
        val fr = Shipped.lexicon("fr")
        val range = fr.prefixRange("bonj".toCharArray())
        val words = range.map { fr.word(it) }
        assertTrue(words.toString(), "bonjour" in words)
        assertTrue(words.all { Fold.of(it).startsWith("bonj") })
        val all = (0 until fr.size).count { Fold.of(fr.word(it)).startsWith("bonj") }
        assertEquals(all, words.size)
        assertTrue(fr.prefixRange("zzzzq".toCharArray()).isEmpty())
    }

    @Test
    fun nearWordsAreOneSlipAway() {
        val lexicon = Lexicon.of(listOf("bonjour" to 150, "bonsoir" to 120, "toujours" to 140, "bon" to 160))
        fun near(typed: String, max: Int): Map<String, Int> {
            val found = HashMap<String, Int>()
            lexicon.forEachNear(typed.toCharArray(), max) { i, d -> found[lexicon.word(i)] = d }
            return found
        }
        assertEquals(mapOf("bonjour" to 1), near("bonjuor", 1)) // two letters swapped
        assertEquals(mapOf("bonjour" to 1), near("bonjou", 1)) // a letter missing
        assertEquals(mapOf("bonjour" to 1), near("bonjourr", 1)) // a letter added
        assertEquals(mapOf("bonjour" to 1), near("bonkour", 1)) // a letter replaced
        assertEquals(mapOf("bonjour" to 0), near("bonjour", 1))
        assertEquals(2, near("bomjuor", 2)["bonjour"])
        assertEquals(null, near("bomjuor", 1)["bonjour"])
    }
}
