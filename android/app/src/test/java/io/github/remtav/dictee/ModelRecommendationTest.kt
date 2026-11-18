package io.github.remtav.dictee

import io.github.remtav.dictee.stt.DictationLanguage
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.ModelRecommendations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issues #22 and #89: Whisper turbo is the default and the recommendation; Parakeet is offered next to it. */
class ModelRecommendationTest {
    private val langs = DictationLanguage.entries
    private val english = setOf(DictationLanguage.ENGLISH)

    /** Every non-empty subset of the five languages (31). */
    private fun allCombinations(): Sequence<Set<DictationLanguage>> =
        (1 until (1 shl langs.size)).asSequence().map { mask ->
            langs.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
        }

    @Test fun thereAre31Combinations() {
        assertEquals(31, allCombinations().count())
    }

    @Test fun whisperTurboIsAlwaysRecommended() {
        for (combo in allCombinations() + sequenceOf(emptySet())) {
            assertEquals(combo.toString(), ModelCatalog.whisperTurbo, ModelRecommendations.recommend(combo).model)
        }
    }

    @Test fun englishAloneRecommendsWhisperInOneSentenceThatSpeaksOfBothModels() {
        val rec = ModelRecommendations.recommend(english)
        assertEquals(ModelCatalog.whisperTurbo, rec.model)
        assertEquals(
            "Whisper, présélectionné : plus lent que Parakeet, mais le seul à bien transcrire " +
                "le français québécois aux essais (1,04 Go).",
            rec.reason,
        )
        // Issue #89: the quick test stays hidden, the reason never sends to it.
        assertFalse(rec.reason, rec.reason.contains("test d'une minute"))
        assertFalse(rec.reason, rec.reason.contains("test rapide"))
        assertEquals(listOf(ModelCatalog.parakeet, ModelCatalog.whisperTurbo), ModelCatalog.offered(english))
    }

    /** Issue #89: Parakeet and Whisper, in this order, for every combination of the five languages. */
    @Test fun offeredIsParakeetThenWhisperForEveryCombination() {
        val five = listOf(DictationLanguage.FRENCH_QUEBEC, DictationLanguage.FRENCH_FRANCE, DictationLanguage.ENGLISH,
            DictationLanguage.SPANISH, DictationLanguage.PORTUGUESE)
        val fiveCombos = (1 until (1 shl five.size)).map { m -> five.filterIndexed { i, _ -> m and (1 shl i) != 0 }.toSet() }
        assertEquals(31, fiveCombos.size)
        for (combo in fiveCombos + allCombinations()) {
            assertEquals(combo.toString(), listOf(ModelCatalog.parakeet, ModelCatalog.whisperTurbo), ModelCatalog.offered(combo))
        }
    }

    /** Issue #89: Parakeet is back in the normal path, « le plus rapide », never recommended nor preselected. */
    @Test fun parakeetIsOfferedButNeverRecommended() {
        val p = ModelCatalog.parakeet
        assertEquals("Le plus rapide. Moins précis en français québécois.", p.note)
        assertEquals("Plus lent, plus précis. Le meilleur sur le téléphone pour le français d'ici.", ModelCatalog.whisperTurbo.note)
        for (combo in allCombinations() + sequenceOf(emptySet())) {
            assertTrue(combo.toString(), ModelRecommendations.recommend(combo).model != p)
            assertTrue(combo.toString(), ModelCatalog.suits(p, combo))
        }
        assertEquals(ModelCatalog.whisperTurbo, ModelCatalog.default)
    }

    @Test fun offeredModelsCoverEveryChosenLanguage() {
        for (combo in allCombinations()) {
            val offered = ModelCatalog.offered(combo)
            assertTrue(combo.toString(), ModelCatalog.whisperTurbo in offered)
            for (spec in offered) assertTrue("$combo ${spec.id}", ModelRecommendations.uncovered(spec, combo).isEmpty())
        }
    }

    @Test fun everyRecommendationHasAReasonWithoutTheOldParakeetClaim() {
        for (combo in allCombinations() + sequenceOf(emptySet())) {
            val reason = ModelRecommendations.recommend(combo).reason
            assertTrue(combo.toString(), reason.isNotBlank())
            assertFalse(reason, reason.contains("Parakeet est meilleur"))
            assertFalse(reason, reason.contains("59 mots"))
            // Issue #89: « Whisper » and « Parakeet » are named; the other technical words stay out of the normal path.
            assertTrue(reason, reason.startsWith("Whisper"))
            for (word in io.github.remtav.dictee.stt.ModelPlainNames.technicalWords) assertFalse(reason, reason.contains(word))
        }
    }

    /** The active model stays while it suits the chosen languages; otherwise the default takes its place. */
    @Test fun anActiveModelThatNoLongerSuitsGivesWayToTheDefault() {
        for (combo in allCombinations() + sequenceOf(emptySet())) {
            for (spec in listOf(ModelCatalog.whisperTurbo, ModelCatalog.parakeet, ModelCatalog.remote))
                assertEquals(combo.toString(), spec, ModelCatalog.forLanguages(spec, combo))
        }
        val unlisted = ModelCatalog.whisperTurbo.copy(id = "not-in-the-catalogue")
        assertFalse(ModelCatalog.suits(unlisted, setOf(DictationLanguage.ENGLISH)))
        assertEquals(ModelCatalog.default, ModelCatalog.forLanguages(unlisted, setOf(DictationLanguage.ENGLISH)))
    }

    @Test fun defaultModelIsWhisperTurbo() {
        assertEquals(ModelCatalog.whisperTurbo, ModelCatalog.default)
    }

    @Test fun coverageQuestion() {
        assertTrue(ModelRecommendations.covers(ModelCatalog.parakeet, DictationLanguage.FRENCH_QUEBEC))
        assertTrue(ModelRecommendations.covers(ModelCatalog.whisperTurbo, DictationLanguage.SPANISH))
        assertTrue(ModelRecommendations.covers(ModelCatalog.remote, DictationLanguage.PORTUGUESE))
        // The model covers every language the app offers: no « ne couvre pas » warning remains.
        for (model in ModelCatalog.offered(DictationLanguage.entries.toSet()))
            assertEquals(emptyList<DictationLanguage>(), ModelRecommendations.uncovered(model, DictationLanguage.entries.toSet()))
    }

    @Test fun bothFrenchesShareTheEngineCode() {
        assertEquals("fr", DictationLanguage.FRENCH_QUEBEC.engineCode)
        assertEquals("fr", DictationLanguage.FRENCH_FRANCE.engineCode)
        assertEquals(DictationLanguage.FRENCH_QUEBEC, DictationLanguage.entries.first())
        assertEquals(DictationLanguage.FRENCH_QUEBEC, DictationLanguage.default)
    }

    @Test fun languageNamesInSentencesKeepTheirProperNouns() {
        assertEquals("français (Québec)", DictationLanguage.FRENCH_QUEBEC.inSentence)
        assertEquals("portugais", DictationLanguage.PORTUGUESE.inSentence)
    }

    @Test fun whisperNoteOpposesSpeedAndPrecisionAndIsNoLongerALastResort() {
        // Issue #89: « Plus lent, plus précis. » (the earlier « Observé… » wording is replaced).
        assertEquals(ModelCatalog.WHISPER_NOTE, ModelCatalog.whisperTurbo.note)
        assertTrue(ModelCatalog.whisperTurbo.note.startsWith("Plus lent, plus précis"))
        assertFalse(ModelCatalog.whisperTurbo.note.contains("dernier recours"))
    }

    @Test fun parakeetCoversAllFiveLanguages() {
        assertEquals(5, langs.count { ModelRecommendations.covers(ModelCatalog.parakeet, it) })
    }
}
