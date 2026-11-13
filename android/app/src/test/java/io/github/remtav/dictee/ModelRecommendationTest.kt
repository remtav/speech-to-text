package io.github.remtav.dictee

import io.github.remtav.dictee.stt.DictationLanguage
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.ModelRecommendations
import io.github.remtav.dictee.stt.ModelVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #22: Whisper turbo is the default and the recommendation; Parakeet for English alone only. */
class ModelRecommendationTest {
    private val langs = DictationLanguage.entries
    private val english = setOf(DictationLanguage.ENGLISH)

    /** Every non-empty subset of the eleven languages (2047). */
    private fun allCombinations(): Sequence<Set<DictationLanguage>> =
        (1 until (1 shl langs.size)).asSequence().map { mask ->
            langs.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
        }

    @Test fun thereAre2047Combinations() {
        assertEquals(2047, allCombinations().count())
    }

    @Test fun whisperTurboIsAlwaysRecommended() {
        for (combo in allCombinations() + sequenceOf(emptySet())) {
            assertEquals(combo.toString(), ModelCatalog.whisperTurbo, ModelRecommendations.recommend(combo).model)
        }
    }

    @Test fun englishAloneRecommendsWhisperAndOffersParakeet() {
        val rec = ModelRecommendations.recommend(english)
        assertEquals(ModelCatalog.whisperTurbo, rec.model)
        assertTrue(rec.reason, rec.reason.contains("Parakeet"))
        assertEquals(listOf(ModelCatalog.whisperTurbo, ModelCatalog.parakeet), ModelCatalog.offered(english))
    }

    @Test fun quebecFrenchOffersOnlyWhisperTurbo() {
        for (combo in allCombinations().filter { DictationLanguage.FRENCH_QUEBEC in it }) {
            assertEquals(combo.toString(), listOf(ModelCatalog.whisperTurbo), ModelCatalog.offered(combo))
        }
    }

    @Test fun parakeetIsOfferedOnlyForEnglishAlone() {
        for (combo in allCombinations()) {
            assertEquals(combo.toString(), combo == english, ModelCatalog.parakeet in ModelCatalog.offered(combo))
        }
    }

    @Test fun hiddenModelsAreNeverOffered() {
        assertEquals(ModelVisibility.HIDDEN, ModelCatalog.qwen3.visibility)
        assertEquals(ModelVisibility.HIDDEN, ModelCatalog.whisperQuebec.visibility)
        for (combo in allCombinations() + sequenceOf(emptySet())) {
            val offered = ModelCatalog.offered(combo)
            assertFalse(combo.toString(), ModelCatalog.qwen3 in offered)
            assertFalse(combo.toString(), ModelCatalog.whisperQuebec in offered)
            assertFalse(combo.toString(), ModelCatalog.qwen3 in ModelCatalog.listed(combo))
            assertFalse(combo.toString(), ModelCatalog.whisperQuebec in ModelCatalog.listed(combo))
        }
    }

    @Test fun liteWhisperIsExperimentalNeverOfferedNorRecommended() {
        val lite = ModelCatalog.liteWhisperTurbo
        assertEquals(ModelVisibility.EXPERIMENTAL, lite.visibility)
        assertEquals(listOf(lite), ModelCatalog.experimental)
        for (combo in allCombinations()) {
            assertFalse(combo.toString(), lite in ModelCatalog.offered(combo))
            assertTrue(combo.toString(), lite in ModelCatalog.listed(combo))
            assertTrue(combo.toString(), ModelRecommendations.recommend(combo).model != lite)
        }
        // Same coverage as Whisper turbo.
        for (l in langs) assertTrue(l.toString(), ModelRecommendations.covers(lite, l))
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
        }
        val qc = ModelRecommendations.recommend(setOf(DictationLanguage.FRENCH_QUEBEC)).reason
        assertTrue(qc, qc.contains("français québécois"))
    }

    @Test fun defaultModelIsWhisperTurbo() {
        assertEquals(ModelCatalog.whisperTurbo, ModelCatalog.default)
    }

    @Test fun coverageQuestion() {
        assertTrue(ModelRecommendations.covers(ModelCatalog.parakeet, DictationLanguage.FRENCH_QUEBEC))
        assertFalse(ModelRecommendations.covers(ModelCatalog.parakeet, DictationLanguage.MANDARIN))
        assertTrue(ModelRecommendations.covers(ModelCatalog.qwen3, DictationLanguage.HINDI))
        assertFalse(ModelRecommendations.covers(ModelCatalog.qwen3, DictationLanguage.BENGALI))
        assertTrue(ModelRecommendations.covers(ModelCatalog.whisperTurbo, DictationLanguage.URDU))
        assertTrue(ModelRecommendations.covers(ModelCatalog.remote, DictationLanguage.URDU))
        assertEquals(listOf(DictationLanguage.MANDARIN),
            ModelRecommendations.uncovered(ModelCatalog.parakeet,
                setOf(DictationLanguage.ENGLISH, DictationLanguage.MANDARIN)))
    }

    @Test fun quebecWhisperCoversFrenchOnly() {
        val qc = ModelCatalog.whisperQuebec
        assertTrue(ModelRecommendations.covers(qc, DictationLanguage.FRENCH_QUEBEC))
        assertTrue(ModelRecommendations.covers(qc, DictationLanguage.FRENCH_FRANCE))
        for (l in langs.filter { it.engineCode != "fr" }) assertFalse(l.toString(), ModelRecommendations.covers(qc, l))
    }

    @Test fun bothFrenchesShareTheEngineCode() {
        assertEquals("fr", DictationLanguage.FRENCH_QUEBEC.engineCode)
        assertEquals("fr", DictationLanguage.FRENCH_FRANCE.engineCode)
        assertEquals(DictationLanguage.FRENCH_QUEBEC, DictationLanguage.entries.first())
        assertEquals(DictationLanguage.FRENCH_QUEBEC, DictationLanguage.default)
    }

    @Test fun languageNamesInSentencesKeepTheirProperNouns() {
        assertEquals("français (Québec)", DictationLanguage.FRENCH_QUEBEC.inSentence)
        assertEquals("mandarin", DictationLanguage.MANDARIN.inSentence)
    }

    @Test fun whisperNoteIsAnObservationAndNoLongerALastResort() {
        assertTrue(ModelCatalog.whisperTurbo.note.startsWith("Observé"))
        assertFalse(ModelCatalog.whisperTurbo.note.contains("dernier recours"))
        assertEquals("", ModelCatalog.parakeet.note)
    }

    @Test fun parakeetCoversExactlySixOfTheEleven() {
        assertEquals(6, langs.count { ModelRecommendations.covers(ModelCatalog.parakeet, it) })
    }
}
