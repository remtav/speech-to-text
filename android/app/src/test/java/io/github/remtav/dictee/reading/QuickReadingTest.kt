package io.github.remtav.dictee.reading

import io.github.remtav.dictee.stt.DictationLanguage
import io.github.remtav.dictee.stt.TranscriptionAdvisor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Issue #36a: the five quick texts exist, have about 40 words, and normalise cleanly. */
class QuickReadingTest {
    private val files = mapOf(
        DictationLanguage.FRENCH_QUEBEC to "rapide-fr-qc-1",
        DictationLanguage.FRENCH_FRANCE to "rapide-fr-fr-1",
        DictationLanguage.ENGLISH to "rapide-en-1",
        DictationLanguage.SPANISH to "rapide-es-1",
        DictationLanguage.PORTUGUESE to "rapide-pt-1",
    )

    private fun load(l: DictationLanguage) = QuickReading.load(l, ReadingTestSupport::readAsset)

    @Test fun fiveTextsArePresentAtTheExpectedPath() {
        assertEquals(files.keys.toList(), QuickReading.languages)
        assertEquals(QuickReading.languages, TranscriptionAdvisor.quickTestLanguages)
        for ((l, v) in files) {
            assertEquals(v, QuickReading.version(l))
            assertEquals("lecture/$v.txt", QuickReading.assetPath(l))
            assertTrue(v, File(ReadingTestSupport.assetsDir, "lecture/$v.txt").isFile)
        }
        assertEquals(5, DictationLanguage.entries.count { QuickReading.hasText(it) })
    }

    @Test fun eachTextIsOneParagraphOfAboutFortyWords() {
        for (l in QuickReading.languages) {
            val m = load(l)
            assertEquals(l.label, 1, m.text.paragraphs.size)
            val words = m.text.wordCount
            assertTrue("${l.label}: $words mots", words in QuickReading.MIN_WORDS..QuickReading.MAX_WORDS)
            // About 16 s at the unhurried pace: a reading well inside the 25 s budget.
            assertTrue(m.text.readingSeconds(0) <= QuickTestBudgetSeconds)
        }
    }

    @Test fun normalisationOfTheTextAgainstItselfHasNoErrorAndNoDigit() {
        for (l in QuickReading.languages) {
            val m = load(l)
            val p = m.text.paragraphs[0]
            val tokens = m.normalizer.normalize(p)
            assertTrue(l.label, tokens.isNotEmpty())
            assertTrue(l.label, tokens.none { t -> t.any { it.isDigit() } })
            assertEquals(l.label, tokens, m.normalizer.normalize(p.uppercase().replace(",", " ;")))
            val score = ReadingScoring.scoreParagraph(m, 0, p)
            assertEquals(l.label, 0, score.errors)
            assertEquals(l.label, 1.0, score.similarity, 0.0)
        }
    }

    @Test fun oneMissedWordCostsAboutTwoAndAHalfPoints() {
        for (l in QuickReading.languages) {
            val m = load(l)
            val words = m.text.paragraphs[0].split(' ')
            val without = words.filterIndexed { i, _ -> i != 5 }.joinToString(" ")
            val s = ReadingScoring.scoreParagraph(m, 0, without)
            assertEquals(l.label, 1, s.errors)
            assertTrue(l.label, s.similarity in 0.95..0.98)
        }
    }

    @Test fun quebecTextHasWordsFromHere() {
        val t = load(DictationLanguage.FRENCH_QUEBEC).text.paragraphs[0].lowercase()
        val here = listOf("tuque", "dépanneur", "poudrerie", "bleuets", "souper", "stationné", "mitaines")
        assertTrue(here.count { it in t } >= 4)
    }

    @Test fun versionMismatchIsRefused() {
        val text = "version: rapide-en-1\n\nHello there."
        try {
            QuickReading.load(DictationLanguage.SPANISH) { text }
            org.junit.Assert.fail("version incohérente acceptée")
        } catch (_: IllegalArgumentException) {
        }
        assertNotEquals(QuickReading.version(DictationLanguage.FRENCH_QUEBEC), QuickReading.version(DictationLanguage.FRENCH_FRANCE))
    }

    private companion object {
        const val QuickTestBudgetSeconds = 25.0
    }
}
