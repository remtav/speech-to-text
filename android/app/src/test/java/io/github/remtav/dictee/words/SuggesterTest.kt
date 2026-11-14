package io.github.remtav.dictee.words

import io.github.remtav.dictee.ime.words.Suggester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggesterTest {
    private val fr = Suggester(listOf(Shipped.lexicon("fr")))
    private val en = Suggester(listOf(Shipped.lexicon("en")))
    private val frEn = Suggester(listOf(Shipped.lexicon("fr"), Shipped.lexicon("en")))

    @Test
    fun completesTheWordBeingTyped() {
        assertTrue(fr.suggest("bonj").words.toString(), "bonjour" in fr.suggest("bonj").words)
        assertTrue(fr.suggest("aujourd").words.toString(), "aujourd'hui" in fr.suggest("aujourd").words)
        assertTrue(en.suggest("tomor").words.toString(), "tomorrow" in en.suggest("tomor").words)
        assertEquals(3, fr.suggest("b").words.size)
    }

    @Test
    fun putsBackAccentsAndCapitals() {
        assertEquals("être", fr.suggest("etre").correction)
        assertEquals("ça", fr.suggest("ca").correction)
        assertEquals("Québec", fr.suggest("quebec").correction)
        assertEquals("Montréal", fr.suggest("montreal").correction)
        assertEquals("déjà", fr.suggest("deja").correction)
        assertEquals("I", en.suggest("i").correction)
        assertEquals("don't", en.suggest("dont").correction)
        // « a » and « à », « ou » and « où » are both words: the typed one stays, the other is offered.
        assertNull(fr.suggest("a").correction)
        assertTrue("à" in fr.suggest("a").words)
        assertNull(fr.suggest("ou").correction)
        assertTrue("où" in fr.suggest("ou").words)
    }

    @Test
    fun correctsASlipOfTheFinger() {
        assertEquals("bonjour", fr.suggest("bonjuor").correction)
        assertEquals("bonjour", fr.suggest("bonkour").correction)
        assertEquals("maison", fr.suggest("maisom").correction)
        assertEquals("demain", fr.suggest("demian").correction)
        assertEquals("the", en.suggest("teh").correction)
        assertEquals("because", en.suggest("becuase").correction)
    }

    @Test
    fun leavesWordsAlone() {
        for (word in listOf("bonjour", "maison", "suis", "Bonjour", "BONJOUR", "peut", "l'école", "c'est", "aujourd'hui")) {
            val r = fr.suggest(word)
            assertTrue(word, r.known)
            assertNull(word, r.correction)
        }
        // Informal words and texting: not in the lists, and not turned into another word.
        for (word in listOf("chu", "tsé", "faque", "lol", "ok", "tk")) assertNull(word, fr.suggest(word).correction)
        // A capital in the middle of a sentence is a name; capitals inside a word are on purpose.
        assertNull(fr.suggest("Tavon", sentenceStart = false).correction)
        assertNull(fr.suggest("iPhoen").correction)
        // Two letters are too few to guess a slip.
        assertNull(fr.suggest("xq").correction)
    }

    @Test
    fun keepsTheCapitalsOfWhatWasTyped() {
        assertTrue(fr.suggest("Bonj").words.toString(), "Bonjour" in fr.suggest("Bonj").words)
        assertEquals("Être", fr.suggest("Etre", sentenceStart = true).correction)
        assertEquals("BONJOUR", fr.suggest("BONJUOR", sentenceStart = true).correction)
    }

    @Test
    fun completesAfterAnElision() {
        val words = fr.suggest("l'arb").words
        assertTrue(words.toString(), "l'arbre" in words)
        assertEquals("l'école", fr.suggest("l'ecole").correction)
    }

    @Test
    fun aLearnedWordIsKnownAndOffered() {
        val s = Suggester(listOf(Shipped.lexicon("fr")), learned = { setOf("Tavon", "kalmia") })
        assertTrue(s.suggest("kalmia").known)
        assertNull(s.suggest("kalmia").correction)
        assertTrue("kalmia" in s.suggest("kalm").words)
    }

    @Test
    fun twoLanguagesAtOnce() {
        assertTrue("bonjour" in frEn.suggest("bonj").words)
        assertTrue("tomorrow" in frEn.suggest("tomor").words)
        // « dont » is a French word: with both languages it is not turned into « don't ».
        assertNull(frEn.suggest("dont").correction)
    }

    @Test
    fun neverOffersAnOffensiveWord() {
        val lexicon = Shipped.lexicon("en")
        val offensive = (0 until lexicon.size).filter { lexicon.isOffensive(it) }.map { lexicon.word(it) }
        assertTrue(offensive.size > 50)
        for (word in offensive.take(200)) {
            if (word.length < 4) continue
            val r = en.suggest(word.dropLast(1))
            assertFalse("$word offered for ${word.dropLast(1)}", word in r.words)
            assertTrue(word, en.suggest(word).known)
        }
    }

    /** Typed on the main thread at every key: it has to be quick even with the four languages. */
    @Test
    fun isQuickEnoughForEveryKey() {
        val all = Suggester(listOf("fr", "en", "es", "pt").map { Shipped.lexicon(it) })
        val typed = listOf("b", "bo", "bon", "bonj", "bonjuor", "aujourd", "tomorow", "extraordinairment", "l'arb", "quebec")
        repeat(3) { typed.forEach { all.suggest(it) } } // warm-up
        val start = System.nanoTime()
        repeat(10) { typed.forEach { all.suggest(it) } }
        val perCall = (System.nanoTime() - start) / 1e6 / (10 * typed.size)
        println("suggest, four languages: %.2f ms per call".format(perCall))
        assertTrue("suggest takes $perCall ms", perCall < 25.0)
    }
}
