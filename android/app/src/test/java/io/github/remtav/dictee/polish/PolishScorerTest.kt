package io.github.remtav.dictee.polish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests of the instrument itself: verdicts, normalization, percentiles, replay, guard. */
class PolishScorerTest {
    private fun case(id: String, category: String, raw: String, expected: String = raw, lang: String = "fr") =
        EvalCase(id, lang, category, raw, raw, expected, "rédigé")

    private val target = case("t1", "noms-propres", "Mon code est sur git hub.", "Mon code est sur GitHub.")
    private val web = case("t2", "web", "Va voir coiffe rimouski point com.", "Va voir coifferimouski.com.")
    private val control = case("c1", "controle", "On a trouvé un point commun.")
    private val rare = case("c2", "controle-mots-rares", "J'ai trouvé ça dans une friperie.")
    private val cases = listOf(target, web, control, rare)

    private fun fixer(map: Map<String, String>) =
        PolishApproach.of("test", needsLlm = false) { raw, _ -> map[raw] ?: raw }

    @Test fun correctedCaseIsCounted() {
        val s = PolishScorer.score(fixer(mapOf(target.raw to target.expected)), cases, repeats = 1)
        assertEquals(Verdict.CORRECTED, s.results.first { it.case.id == "t1" }.verdict)
        assertEquals(Verdict.UNCHANGED, s.results.first { it.case.id == "t2" }.verdict)
        assertEquals(1, s.all.corrected)
        assertEquals(2, s.all.targets)
        assertEquals(0.5, s.all.correctedRate!!, 1e-9)
        assertEquals(0, s.all.damaged)
    }

    @Test fun damagedControlIsDetected() {
        val s = PolishScorer.score(fixer(mapOf(control.raw to "On a trouvé un .commun.", rare.raw to "J'ai trouvé ça dans une fripperie.")), cases, repeats = 1)
        assertEquals(listOf("c1", "c2"), s.damagedIds)
        assertEquals(1.0, s.all.damagedRate!!, 1e-9)
        assertEquals(1, s.byCategory().getValue("controle-mots-rares").damaged)
    }

    @Test fun wrongChangeIsNeitherCorrectedNorUnchanged() {
        val s = PolishScorer.score(fixer(mapOf(target.raw to "Mon code est sur Github.")), cases, repeats = 1)
        assertEquals(Verdict.WRONG, s.results.first { it.case.id == "t1" }.verdict)
        assertEquals(1, s.all.wrong)
    }

    @Test fun exceptionIsAnErrorAndFailsTheGuard() {
        val boom = PolishApproach.of("boom", needsLlm = false) { _, _ -> error("bug") }
        val s = PolishScorer.score(boom, cases, repeats = 1)
        assertEquals(cases.size, s.all.errors)
        assertEquals(0, s.all.damaged)
    }

    @Test fun normalizationIsStrictOnCaseSpacesInsideWordsAndPunctuation() {
        assertTrue(PolishNormalization.same("Mon  code\tsur GitHub. ", "Mon code sur GitHub."))
        assertTrue(PolishNormalization.same("l’équipe", "l'équipe"))
        assertTrue(PolishNormalization.same("été", "été")) // NFD vs NFC
        assertFalse(PolishNormalization.same("Github", "GitHub"))
        assertFalse(PolishNormalization.same("coifferimouski. com", "coifferimouski.com"))
        assertFalse(PolishNormalization.same("Git Hub", "GitHub"))
        assertFalse(PolishNormalization.same("Trois Rivières", "Trois-Rivières"))
        assertFalse(PolishNormalization.same("Quebec", "Québec"))
        assertFalse(PolishNormalization.same("Zoom ?", "Zoom?"))
        assertFalse(PolishNormalization.same("Zoom ?", "Zoom ?")) // no-break space is typography, not folded
        assertFalse(PolishNormalization.same("kalmia.ca.", "kalmia.ca"))
    }

    @Test fun percentilesUseTheNearestRank() {
        val values = (1..100).map { it.toDouble() }.shuffled()
        assertEquals(50.0, Percentiles.of(values, 50.0)!!, 0.0)
        assertEquals(95.0, Percentiles.of(values, 95.0)!!, 0.0)
        assertEquals(100.0, Percentiles.of(values, 100.0)!!, 0.0)
        assertEquals(1.0, Percentiles.of(values, 0.0)!!, 0.0)
        assertEquals(7.0, Percentiles.of(listOf(7.0), 95.0)!!, 0.0)
        assertEquals(2.0, Percentiles.of(listOf(3.0, 1.0, 2.0), 50.0)!!, 0.0)
        assertNull(Percentiles.of(emptyList(), 50.0))
    }

    @Test fun liveApproachIsTimedOnTheHostOverEveryRepeat() {
        val s = PolishScorer.score(IdentityApproach, cases, repeats = 3)
        assertEquals("hôte", s.latency!!.where)
        assertEquals(cases.size * 3, s.latency!!.samples)
        assertTrue(s.latency!!.p50 <= s.latency!!.p95)
    }

    @Test fun recordedOutputsAreReplayedWithoutHostLatency() {
        val file = """
            # approche: llm-essai
            # llm: oui
            # description: sorties enregistrées sur un téléphone
            id	brut	sortie	latence_ms
            t1	${target.raw}	${target.expected}	850
            c1	${control.raw}	On a trouvé un point com mun.	4100
            t2	une ancienne version du brut	Va voir coifferimouski.com.
        """.trimIndent()
        val rec = RecordedApproach.parse("fichier", file).bind(cases)
        assertEquals("llm-essai", rec.name)
        assertTrue(rec.needsLlm)
        assertEquals(target.expected, rec.polish(target.raw, "fr"))
        val s = PolishScorer.score(rec, cases)
        assertEquals(Verdict.CORRECTED, s.results.first { it.case.id == "t1" }.verdict)
        assertEquals(Verdict.DAMAGED, s.results.first { it.case.id == "c1" }.verdict)
        // Stale (raw changed since recording) and missing lines are errors, not silently skipped.
        assertEquals(listOf("t2", "c2"), s.errorIds)
        assertEquals("appareil", s.latency!!.where)
        assertEquals(2, s.latency!!.samples)
        assertEquals(850.0, s.latency!!.p50, 0.0)
        assertEquals(1, s.latency!!.overBudget) // one segment above LlmCleaner's 4 s delay
    }

    @Test fun recordedFileWithoutLatencyHasNoLatency() {
        val rec = RecordedApproach.parse("sans-latence", "id\tbrut\tsortie\nc1\t${control.raw}\t${control.raw}\n")
        val s = PolishScorer.score(rec, cases)
        assertEquals("sans-latence", rec.name)
        assertNull(s.latency)
        assertEquals(Verdict.KEPT, s.results.first { it.case.id == "c1" }.verdict)
    }

    @Test fun reportListsEveryApproachAndTheRareWordsColumn() {
        val scores = listOf(PolishScorer.score(IdentityApproach, cases, repeats = 1))
        val text = PolishReport.text(scores, cases)
        assertTrue(text, "identite" in text && "dont mots rares" in text && "catégorie controle-mots-rares" in text)
        val json = PolishReport.json(scores, cases)
        val first = json.getJSONArray("approches").getJSONObject(0)
        assertEquals(0, first.getJSONObject("total").getInt("abimes"))
        assertEquals(2, first.getJSONObject("total").getInt("a_corriger"))
        assertTrue(first.getJSONObject("par_categorie").has("controle-mots-rares"))
    }
}
