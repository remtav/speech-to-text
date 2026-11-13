package io.github.remtav.dictee.reading

import io.github.remtav.dictee.text.Levenshtein
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingScoreTest {
    private val m = ReadingTestSupport.material
    private val text = m.text

    @Test
    fun alignmentHasExactlyTheEditDistance() {
        val cases = listOf("a b c d" to "a x c", "" to "a b", "a b" to "", "le chat dort" to "le chien dort bien", "a a a" to "a")
        for ((a, b) in cases) {
            val x = a.split(' ').filter { it.isNotEmpty() }
            val y = b.split(' ').filter { it.isNotEmpty() }
            val steps = Levenshtein.align(x, y)
            assertEquals("$a / $b", Levenshtein.distance(x, y), steps.count { it.op != Levenshtein.Op.MATCH })
            assertEquals(x.indices.toList(), steps.filter { it.ai >= 0 }.map { it.ai })
            assertEquals(y.indices.toList(), steps.filter { it.bi >= 0 }.map { it.bi })
        }
    }

    @Test
    fun aPerfectReadingScoresOneEvenWithOtherSpellings() {
        for (i in text.paragraphs.indices) {
            val s = ReadingScoring.scoreParagraph(m, i, text.paragraphs[i].uppercase())
            assertEquals(0, s.errors)
            assertEquals(1.0, s.similarity, 0.0)
            assertTrue(s.misheard.isEmpty())
        }
        val p = text.paragraphs.indexOfFirst { it.contains("chu pas bon") }
        val heard = "Ma blonde a jasé avec la vendeuse. Moi je suis pas bon pour choisir, j'ai toujours de la misère " +
            "à me décider. Elle m'a dit il y a rien là, tsé, c'est correct."
        assertEquals(0, ReadingScoring.scoreParagraph(m, p, heard).errors)
    }

    @Test
    fun misheardWordsAreLocatedInTheParagraph() {
        val p = text.paragraphs.indexOfFirst { it.contains("friperie") }
        val paragraph = text.paragraphs[p]
        val heard = paragraph.replace("friperie", "fripe rit").replace(", à vingt piastres", " à vingt")
        val s = ReadingScoring.scoreParagraph(m, p, heard)
        val words = s.misheard.map { it.word }
        assertEquals(listOf("friperie", "piastres"), words)
        for (w in s.misheard) assertEquals(w.word, paragraph.substring(w.range))
        // « fripe rit » for « friperie »: one substitution and one extra word (either way round)
        assertEquals(setOf("fripe", "rit"), setOf(s.misheard[0].heard, s.extra.single().heard))
        assertNull(s.misheard[1].heard)
        assertEquals(3, s.errors) // substitution + insertion + deletion
        assertEquals(1.0 - 3.0 / s.referenceWords, s.similarity, 1e-12)
    }

    @Test
    fun aHyphenatedPlaceIsHighlightedWordByWord() {
        val p = text.paragraphs.indexOfFirst { it.contains("Lac-Saint-Jean") }
        val heard = text.paragraphs[p].replace("Lac-Saint-Jean", "lac Saint-Jean").replace("Chicoutimi", "Chicoutimie")
        val s = ReadingScoring.scoreParagraph(m, p, heard)
        assertEquals(listOf("Chicoutimi"), s.misheard.map { it.word })
    }

    @Test
    fun similarityNeverGoesBelowZero() {
        val s = ReadingScoring.scoreParagraph(m, 0, text.paragraphs[0] + " " + text.paragraphs[1] + " " + text.paragraphs[2])
        assertTrue(s.wer > 1.0)
        assertEquals(0.0, s.similarity, 0.0)
    }

    @Test
    fun theGlobalScoreSumsErrorsOverWords() {
        val a = ParagraphScore(0, 10, 5, emptyList(), emptyList())
        val b = ParagraphScore(1, 30, 1, emptyList(), emptyList())
        val g = ReadingScoring.global(listOf(a, b), paragraphsTotal = 2)
        assertEquals(6.0 / 40, g.wer, 1e-12)
        assertTrue(g.complete)
        assertFalse(ReadingScoring.global(listOf(a), paragraphsTotal = 2).complete)
    }

    private fun score(errors: Int, words: Int = 200, complete: Boolean = true) =
        GlobalScore(words, errors, if (complete) 9 else 8, 9)

    @Test
    fun sharingIsOfferedOnlyAboveTwentyPercentWithTheRecommendedModel() {
        assertEquals(20, ReadingThresholds.SHARE_WER_THRESHOLD_PERCENT)
        assertFalse(ReadingThresholds.offerSharing(score(40)))          // exactly 20 %
        assertTrue(ReadingThresholds.offerSharing(score(41)))           // 20.5 %
        assertFalse(ReadingThresholds.offerSharing(score(39)))
        assertFalse(ReadingThresholds.offerSharing(GlobalScore(225, 45, 9, 9))) // 20 % exactly, not a round number of words
        assertTrue(ReadingThresholds.offerSharing(GlobalScore(225, 46, 9, 9)))
        assertFalse(ReadingThresholds.offerSharing(score(150, complete = false)))
        assertFalse(ReadingThresholds.offerSharing(null))
        val scores = mapOf("parakeet" to score(10), "qwen" to score(90))
        assertFalse(ReadingThresholds.offerSharing(scores, recommendedModelId = "parakeet"))
        assertTrue(ReadingThresholds.offerSharing(scores, recommendedModelId = "qwen"))
        assertFalse(ReadingThresholds.offerSharing(scores, recommendedModelId = "whisper")) // not installed
    }

    @Test
    fun aClearlyBetterModelNeedsBothGains() {
        assertTrue(ReadingThresholds.clearlyBetter(score(4), score(16)))     // 2 % vs 8 %
        assertTrue(ReadingThresholds.clearlyBetter(score(20), score(30)))    // −5 points, −33 %
        assertFalse(ReadingThresholds.clearlyBetter(score(21), score(30)))   // −4.5 points
        assertFalse(ReadingThresholds.clearlyBetter(score(80), score(90)))   // −5 points but −11 %
        assertFalse(ReadingThresholds.clearlyBetter(score(0), score(8)))     // −4 points only
        assertFalse(ReadingThresholds.clearlyBetter(score(2, complete = false), score(60)))
        assertFalse(ReadingThresholds.clearlyBetter(GlobalScore(150, 2, 9, 9), score(60)))
        val scores = linkedMapOf("parakeet" to score(60), "qwen" to score(30), "whisper" to score(20), "qc" to score(55))
        assertEquals("whisper", ReadingThresholds.betterModel(scores, currentModelId = "parakeet"))
        assertNull(ReadingThresholds.betterModel(scores, currentModelId = "whisper"))
        assertNull(ReadingThresholds.betterModel(mapOf("parakeet" to score(60)), currentModelId = "parakeet"))
        assertNull(ReadingThresholds.betterModel(scores, currentModelId = "absent"))
    }
}
