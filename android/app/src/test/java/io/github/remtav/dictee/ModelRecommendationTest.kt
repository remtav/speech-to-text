package io.github.remtav.dictee

import io.github.remtav.dictee.stt.DictationLanguage
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.ModelRecommendations
import io.github.remtav.dictee.stt.ModelVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issues #22 and #72: Whisper turbo is the default, the recommendation and the only model offered. */
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

    @Test fun englishAloneRecommendsWhisperInOneSentenceThatNamesNoSecondModel() {
        val rec = ModelRecommendations.recommend(english)
        assertEquals(ModelCatalog.whisperTurbo, rec.model)
        assertEquals("Whisper : le modèle qui comprend le mieux le français d'ici aux essais (1,04 Go).", rec.reason)
        assertFalse(rec.reason, rec.reason.contains("léger"))
        assertFalse(rec.reason, rec.reason.contains("test d'une minute"))
        assertEquals(listOf(ModelCatalog.whisperTurbo), ModelCatalog.offered(english))
    }

    /** Issue #72: Whisper alone, for every combination (all combinations of the five languages). */
    @Test fun offeredIsWhisperAloneForEveryCombination() {
        val five = listOf(DictationLanguage.FRENCH_QUEBEC, DictationLanguage.FRENCH_FRANCE, DictationLanguage.ENGLISH,
            DictationLanguage.SPANISH, DictationLanguage.PORTUGUESE)
        val fiveCombos = (1 until (1 shl five.size)).map { m -> five.filterIndexed { i, _ -> m and (1 shl i) != 0 }.toSet() }
        assertEquals(31, fiveCombos.size)
        for (combo in fiveCombos + allCombinations()) {
            assertEquals(combo.toString(), listOf(ModelCatalog.whisperTurbo), ModelCatalog.offered(combo))
        }
    }

    /** Issue #72: Parakeet is in « Avancé » only: listed there and in the comparison, never offered nor recommended. */
    @Test fun parakeetIsAdvancedOnly() {
        val p = ModelCatalog.parakeet
        assertEquals(ModelVisibility.ADVANCED, p.visibility)
        assertEquals(listOf(p), ModelCatalog.advanced)
        assertEquals("Plus léger et plus rapide, mais comprend moins bien le français québécois.", p.note)
        for (combo in allCombinations() + sequenceOf(emptySet())) {
            assertFalse(combo.toString(), p in ModelCatalog.offered(combo))
            assertTrue(combo.toString(), p in ModelCatalog.listed(combo))
            assertTrue(combo.toString(), ModelRecommendations.recommend(combo).model != p)
            // It may be chosen whatever the languages, Québec French included.
            assertTrue(combo.toString(), ModelCatalog.suits(p, combo))
            // The keyboard's menu shows it only while it is the active model.
            assertFalse(combo.toString(), p in ModelCatalog.forKeyboard(combo, ModelCatalog.whisperTurbo.id))
            assertEquals(combo.toString(), listOf(ModelCatalog.whisperTurbo, p), ModelCatalog.forKeyboard(combo, p.id))
        }
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
            // Issue #72: « Whisper » is named; the other technical words stay out of the normal path.
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

    @Test fun whisperNoteIsAnObservationAndNoLongerALastResort() {
        assertTrue(ModelCatalog.whisperTurbo.note.startsWith("Observé"))
        assertFalse(ModelCatalog.whisperTurbo.note.contains("dernier recours"))
    }

    @Test fun parakeetCoversAllFiveLanguages() {
        assertEquals(5, langs.count { ModelRecommendations.covers(ModelCatalog.parakeet, it) })
    }
}
