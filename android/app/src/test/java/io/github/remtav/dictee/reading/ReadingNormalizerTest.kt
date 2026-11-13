package io.github.remtav.dictee.reading

import io.github.remtav.dictee.text.Normalize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test

class ReadingNormalizerTest {
    private val n = ReadingTestSupport.material.normalizer

    private fun same(a: String, b: String) = assertEquals("« $a » / « $b »", n.normalize(a), n.normalize(b))
    private fun different(a: String, b: String) = assertNotEquals("« $a » / « $b »", n.normalize(a), n.normalize(b))

    @Test
    fun baseTokensAreTheBenchmarkTokens() {
        val samples = ReadingTestSupport.material.text.paragraphs + listOf(
            "Aujourd’hui, l'auto s'est arrêtée à Trois-Rivières !", "« Bon » ; (voilà) : 20 $, OK?", "",
        )
        for (s in samples) assertEquals(s, Normalize.werTokens(s), ReadingNormalizer.baseTokens(s).map { it.text })
    }

    @Test
    fun rangesPointAtTheSourceWords() {
        val s = "Sur mon Cellulaire, l’auto"
        val t = ReadingNormalizer.baseTokens(s)
        assertEquals(listOf("Sur", "mon", "Cellulaire", "l’", "auto"), t.map { s.substring(it.range) })
    }

    @Test
    fun caseAndPunctuationAndApostrophesAndHyphens() {
        same("À matin, il faisait frette.", "à matin il faisait frette")
        same("l’auto", "l'auto")
        same("Trois-Rivières", "Trois Rivières")
        same("« Y'a rien là »", "y'a rien là")
        same("Lac-Saint-Jean", "lac saint jean")
    }

    @Test
    fun accentsStayDistinct() {
        different("tantôt", "tantot")
        different("père", "pere")
    }

    @Test
    fun numbersInDigitsMatchNumbersInWords() {
        same("son père a 60 ans", "son père a soixante ans")
        same("à 20 piastres", "à vingt piastres")
        same("21", "vingt et un")
        same("vingt-et-un", "vingt et un")
        assertEquals("soixante et onze", FrenchNumbers.words(71))
        assertEquals("quatre-vingts", FrenchNumbers.words(80))
        assertEquals("quatre-vingt-un", FrenchNumbers.words(81))
        assertEquals("quatre-vingt-dix-neuf", FrenchNumbers.words(99))
        assertEquals("deux cents", FrenchNumbers.words(200))
        assertEquals("deux cent un", FrenchNumbers.words(201))
        assertEquals("mille neuf cent quatre-vingt-dix", FrenchNumbers.words(1990))
        assertEquals("deux mille vingt-six", FrenchNumbers.words(2026))
        assertEquals("quatre-vingt mille", FrenchNumbers.words(80_000))
        assertEquals("deux millions trois cent mille", FrenchNumbers.words(2_300_000))
        assertEquals("zéro", FrenchNumbers.words(0))
    }

    @Test
    fun everyEquivalenceOfTheTable() {
        same("chu pas bon", "chus pas bon")
        same("chu pas bon", "chui pas bon")
        same("chu pas bon", "chuis pas bon")
        same("chu pas bon", "j'suis pas bon")
        same("chu pas bon", "j’suis pas bon")
        same("chu pas bon", "je suis pas bon")
        same("Y'a rien là", "Y a rien là")
        same("Y'a rien là", "Ya rien là")
        same("Y'a rien là", "Il y a rien là")
        same("t'sais", "t'sé")
        same("t'sais", "tsé")
        same("t'sais", "tsais")
        same("t'sais", "tu sais")
        same("tiguidou", "tigidou")
        same("vingt piastres", "vingt piasses")
        same("une piastre", "une piasse")
        same("Lac-Saint-Jean", "Lac-St-Jean")
        same("Sainte-Foy", "Ste-Foy")
        // every row of the file is exercised above
        assertEquals(8, ReadingTestSupport.material.normalizer.equivalences.entries.size)
    }

    @Test
    fun wordsOfHereAndRealErrorsStayErrors() {
        different("courriel", "email")
        different("char", "auto")
        different("vingt piastres", "vingt dollars")
        different("vingt piastres", "20 $")
        different("à matin", "ce matin")
        different("fin de semaine", "week-end")
        different("dépanneur", "dep")
        different("pantoute", "pas du tout")
        different("frette", "froid")
        different("tiguidou", "tiguidi")
        different("friperie", "fripe rie")
        different("Y'a rien là", "Il n'y a rien là")
        different("elle était pas tannée", "elle n'était pas tannée")
        different("chu pas bon", "je ne suis pas bon")
        different("Si tu veux", "Si tsé veux")
        different("Chicoutimi", "Chicoutimie")
    }

    @Test
    fun theTableRejectsAmbiguityAndMissingReasons() {
        val bad = listOf(
            "version: x-1\nchu\tje suis\tr\ny'a\tje suis\tr",
            "version: x-1\nchu\tje suis",
            "version: x-1\nchu\t\tr",
            "chu\tje suis\tr",
        )
        for (b in bad) try { Equivalences.parse(b); fail(b) } catch (_: IllegalArgumentException) {}
    }
}
