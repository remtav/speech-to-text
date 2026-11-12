package io.github.remtav.dictee

import io.github.remtav.dictee.stt.DictationLanguage
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.ModelRecommendations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRecommendationTest {
    private val langs = DictationLanguage.entries
    private val downloadable = ModelCatalog.all

    /** Every non-empty subset of the eleven languages (2047). */
    private fun allCombinations(): Sequence<Set<DictationLanguage>> =
        (1 until (1 shl langs.size)).asSequence().map { mask ->
            langs.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
        }

    @Test fun thereAre2047Combinations() {
        assertEquals(2047, allCombinations().count())
    }

    @Test fun recommendedModelCoversEverythingWhenSomeModelDoes() {
        for (combo in allCombinations()) {
            val rec = ModelRecommendations.recommend(combo)
            val someModelCoversAll = downloadable.any { ModelRecommendations.uncovered(it, combo).isEmpty() }
            if (someModelCoversAll) {
                assertTrue("$combo -> ${rec.model.shortName}",
                    ModelRecommendations.uncovered(rec.model, combo).isEmpty())
            }
        }
    }

    @Test fun whisperCoversAllElevenSoARecommendationAlwaysCoversEverything() {
        for (combo in allCombinations()) {
            assertTrue(combo.toString(),
                ModelRecommendations.uncovered(ModelCatalog.whisperTurbo, combo).isEmpty())
            assertTrue(combo.toString(),
                ModelRecommendations.uncovered(ModelRecommendations.recommend(combo).model, combo).isEmpty())
        }
    }

    @Test fun quebecFrenchAloneRecommendsParakeet() {
        val rec = ModelRecommendations.recommend(setOf(DictationLanguage.FRENCH_QUEBEC))
        assertEquals(ModelCatalog.parakeet, rec.model)
        assertTrue(rec.reason, rec.reason.contains("mesuré sur six courts extraits"))
        assertTrue(rec.reason, !rec.reason.contains("observation"))
    }

    @Test fun neverParakeetWithALanguageOutsideItsCoverage() {
        val outside = setOf(DictationLanguage.MANDARIN, DictationLanguage.HINDI,
            DictationLanguage.ARABIC, DictationLanguage.BENGALI, DictationLanguage.URDU)
        for (combo in allCombinations()) {
            if (combo.any { it in outside }) {
                assertTrue(combo.toString(),
                    ModelRecommendations.recommend(combo).model != ModelCatalog.parakeet)
            }
        }
    }

    @Test fun everyRecommendationHasAReason() {
        for (combo in allCombinations()) {
            assertTrue(combo.toString(), ModelRecommendations.recommend(combo).reason.isNotBlank())
        }
        assertTrue(ModelRecommendations.recommend(emptySet()).reason.isNotBlank())
    }

    @Test fun emptySelectionIsCautiousParakeet() {
        assertEquals(ModelCatalog.parakeet, ModelRecommendations.recommend(emptySet()).model)
    }

    @Test fun recommendationIsOneOfTheDownloadableModels() {
        for (combo in allCombinations()) {
            assertTrue(ModelRecommendations.recommend(combo).model in downloadable)
        }
    }

    @Test fun coverageQuestion() {
        assertTrue(ModelRecommendations.covers(ModelCatalog.parakeet, DictationLanguage.FRENCH_QUEBEC))
        assertFalse(ModelRecommendations.covers(ModelCatalog.parakeet, DictationLanguage.MANDARIN))
        assertTrue(ModelRecommendations.covers(ModelCatalog.qwen3, DictationLanguage.HINDI))
        assertFalse(ModelRecommendations.covers(ModelCatalog.qwen3, DictationLanguage.BENGALI))
        assertFalse(ModelRecommendations.covers(ModelCatalog.qwen3, DictationLanguage.URDU))
        assertTrue(ModelRecommendations.covers(ModelCatalog.whisperTurbo, DictationLanguage.URDU))
        assertTrue(ModelRecommendations.covers(ModelCatalog.remote, DictationLanguage.URDU))
        assertEquals(listOf(DictationLanguage.MANDARIN),
            ModelRecommendations.uncovered(ModelCatalog.parakeet,
                setOf(DictationLanguage.ENGLISH, DictationLanguage.MANDARIN)))
    }

    @Test fun bothFrenchesShareTheEngineCode() {
        assertEquals("fr", DictationLanguage.FRENCH_QUEBEC.engineCode)
        assertEquals("fr", DictationLanguage.FRENCH_FRANCE.engineCode)
        assertEquals(DictationLanguage.FRENCH_QUEBEC, DictationLanguage.entries.first())
        assertEquals(DictationLanguage.FRENCH_QUEBEC, DictationLanguage.default)
    }

    @Test fun quebecReasonStatesTheScopeOfTheMeasure() {
        val reason = ModelRecommendations.recommend(setOf(DictationLanguage.FRENCH_QUEBEC)).reason
        assertTrue(reason, reason.contains("59 mots"))
        assertTrue(reason, reason.contains("pas une preuve"))
        assertTrue(reason, !reason.contains("transcrit mieux"))
    }

    @Test fun languageNamesInSentencesKeepTheirProperNouns() {
        assertEquals("français (Québec)", DictationLanguage.FRENCH_QUEBEC.inSentence)
        assertEquals("mandarin", DictationLanguage.MANDARIN.inSentence)
        val reason = ModelRecommendations.recommend(setOf(DictationLanguage.FRENCH_QUEBEC, DictationLanguage.URDU)).reason
        assertTrue(reason, reason.contains("ourdou") && !reason.contains("Ourdou"))
    }

    @Test fun parakeetCoversExactlySixOfTheEleven() {
        assertEquals(6, langs.count { ModelRecommendations.covers(ModelCatalog.parakeet, it) })
    }
}
