package io.github.remtav.dictee.reading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Issue #36a: ReadingNormalizer in English, Spanish and Portuguese. */
class NumberWordsTest {
    private fun norm(language: String) = ReadingNormalizer(Equivalences("test-1", emptyList(), language), language)

    private fun check(lang: String, n: Long, expected: String) =
        assertEquals("$lang $n", expected, NumberWords.words(lang, n))

    @Test fun english() {
        check("en", 0, "zero"); check("en", 13, "thirteen"); check("en", 21, "twenty-one")
        check("en", 100, "one hundred"); check("en", 342, "three hundred forty-two")
        check("en", 1000, "one thousand"); check("en", 2026, "two thousand twenty-six")
        check("en", 1_250_000, "one million two hundred fifty thousand")
    }

    @Test fun spanish() {
        check("es", 0, "cero"); check("es", 16, "dieciséis"); check("es", 21, "veintiuno")
        check("es", 31, "treinta y uno"); check("es", 100, "cien"); check("es", 101, "ciento uno")
        check("es", 500, "quinientos"); check("es", 1000, "mil"); check("es", 2026, "dos mil veintiséis")
        check("es", 21_000, "veintiún mil"); check("es", 31_000, "treinta y un mil")
        check("es", 1_000_000, "un millón"); check("es", 2_000_000, "dos millones")
        check("es", 1_234_567, "un millón doscientos treinta y cuatro mil quinientos sesenta y siete")
    }

    @Test fun portuguese() {
        check("pt", 0, "zero"); check("pt", 16, "dezesseis"); check("pt", 21, "vinte e um")
        check("pt", 100, "cem"); check("pt", 101, "cento e um"); check("pt", 123, "cento e vinte e três")
        check("pt", 1000, "mil"); check("pt", 1005, "mil e cinco"); check("pt", 1100, "mil e cem")
        check("pt", 1234, "mil duzentos e trinta e quatro"); check("pt", 2026, "dois mil e vinte e seis")
        check("pt", 1_000_000, "um milhão"); check("pt", 3_000_000, "três milhões")
    }

    @Test fun frenchStillGoesThroughFrenchNumbers() {
        check("fr", 71, "soixante et onze")
        assertNull(NumberWords.words("zh", 5))
    }

    @Test fun digitsMatchWordsInEachLanguage() {
        val en = norm("en"); val es = norm("es"); val pt = norm("pt")
        assertEquals(en.normalize("I paid 21 dollars"), en.normalize("I paid twenty-one dollars"))
        assertEquals(es.normalize("tengo 31 años"), es.normalize("tengo treinta y uno años"))
        assertEquals(pt.normalize("são 1005 reais"), pt.normalize("são mil e cinco reais"))
        // The default (French) normaliser is unchanged.
        assertEquals(ReadingNormalizer(Equivalences("t-1", emptyList())).normalize("21"), listOf("vingt", "et", "un"))
    }

    @Test fun englishKeepsContractionsInOneWord() {
        val en = norm("en")
        assertEquals(listOf("i", "don't", "know", "it's", "here"), en.normalize("I don’t know. It's here!"))
        assertEquals(en.normalize("don't"), en.normalize("Don’t"))
        assertNotEquals(en.normalize("don't"), en.normalize("do not"))
        // French keeps splitting the elision.
        assertEquals(listOf("l'", "auto"), ReadingNormalizer(Equivalences("t-1", emptyList())).normalize("l’auto"))
    }

    @Test fun caseAndPunctuationAreIgnoredButAccentsAreNot() {
        val es = norm("es"); val pt = norm("pt")
        assertEquals(es.normalize("¡Llovía un poco, señor!"), es.normalize("llovía un poco señor"))
        assertNotEquals(es.normalize("sábado"), es.normalize("sabado"))
        assertEquals(pt.normalize("Guarda-chuva, não!"), pt.normalize("guarda chuva não"))
        assertNotEquals(pt.normalize("não"), pt.normalize("nao"))
    }
}
