package io.github.remtav.dictee.polish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The evaluation set itself: size, coverage and consistency (issue #15, step 3.1). */
class PolishEvalSetTest {
    private val cases = PolishEvalSet.load()

    @Test fun atLeastSixtyPairsInBothLanguages() {
        assertTrue("${cases.size} pairs", cases.size >= 60)
        assertEquals(PolishEvalSet.LANGUAGES.toSet(), cases.map { it.language }.toSet())
    }

    @Test fun everyCategoryIsKnownAndCovered() {
        val known = PolishEvalSet.TARGET_CATEGORIES + PolishEvalSet.CONTROL_CATEGORIES
        assertTrue(cases.filterNot { it.category in known }.map { it.id }.toString(), cases.all { it.category in known })
        for (c in known) assertTrue("no $c", cases.any { it.category == c })
        for (c in PolishEvalSet.TARGET_CATEGORIES) {
            for (lang in PolishEvalSet.LANGUAGES) assertTrue("no $c in $lang", cases.any { it.category == c && it.language == lang })
        }
    }

    @Test fun enoughControlsIncludingRareWords() {
        assertTrue(cases.count { it.category == "controle" } >= 20)
        assertTrue(cases.count { it.category == "controle-mots-rares" } >= 10)
        val rare = cases.filter { it.category == "controle-mots-rares" }.joinToString(" ") { it.raw }
        for (w in listOf("friperie", "dépanneur", "poudrerie", "tuque", "magasiner", "clavardage", "pourvoirie", "cégep", "courriel", "achalandage", "tabagie")) {
            assertTrue("rare-word controls lack « $w »", w in rare)
        }
    }

    @Test fun controlsStayUnchangedAndTargetsNeedAChange() {
        for (c in cases) {
            if (c.isControl) assertEquals(c.id, c.raw, c.expected)
            else assertNotEquals("${c.id}: nothing to fix", PolishNormalization.normalize(c.raw), PolishNormalization.normalize(c.expected))
        }
    }

    @Test fun idsAndRawTextsAreUnique() {
        assertEquals(cases.size, cases.map { it.id }.toSet().size)
        // A recorded output is found back by (language, raw): no two lines share them.
        assertEquals(cases.size, cases.map { it.language to it.raw }.toSet().size)
    }

    @Test fun provenanceIsHonestAndWellFormed() {
        for (c in cases) assertTrue("${c.id}: ${c.provenance}", PolishEvalSet.isValidProvenance(c.provenance))
        val tts = cases.filter { it.provenance.startsWith("tts+") }
        assertTrue("real model outputs: ${tts.size}", tts.size >= 30)
        for (m in PolishEvalSet.MODEL_TAGS) assertTrue("no output of $m", tts.any { "+$m" in it.provenance })
        // The id of a model output names its first model.
        for (c in tts) assertTrue(c.id, c.id.endsWith("-" + c.provenance.removePrefix("tts+").substringBefore('+')))
    }

    @Test fun theIssueCaseIsThere() {
        assertTrue(cases.any { !it.isControl && it.expected.contains("coifferimouski.com") && it.provenance.startsWith("tts+") })
        assertTrue(cases.any { !it.isControl && "GitHub" in it.expected && "GitHub" !in it.raw })
    }
}
