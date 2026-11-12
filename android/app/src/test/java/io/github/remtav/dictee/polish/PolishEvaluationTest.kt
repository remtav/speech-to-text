package io.github.remtav.dictee.polish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Scores every registered approach on the evaluation set (issue #15, step 3.1),
 * prints the table and writes `build/reports/polissage/` (resultats.json,
 * resultats.md, sorties-<approche>.tsv). Runs in the CI unit-test job: no
 * model, no network.
 *
 *     ./gradlew testDebugUnitTest --tests '*PolishEvaluationTest*'
 */
class PolishEvaluationTest {
    private val cases = PolishEvalSet.load()
    private val scores by lazy { PolishApproaches.all(cases).map { PolishScorer.score(it, cases) } }

    @Test fun scoreAllApproachesAndWriteTheReport() {
        val report = PolishReport.text(scores, cases)
        println(report)
        val dir = File(System.getProperty("dictee.polishReportDir") ?: "build/reports/polissage")
        PolishReport.write(dir, scores, cases)
        assertTrue(File(dir, "resultats.json").isFile)
        assertEquals(scores.map { it.approach.name }.toSet().size, scores.size) // names are unique (report files)
    }

    /** The reference approach validates the instrument: nothing corrected, nothing damaged. */
    @Test fun identityCorrectsNothingAndDamagesNothing() {
        val s = scores.first { it.approach === IdentityApproach }.all
        assertEquals(0, s.corrected)
        assertEquals(0, s.wrong)
        assertEquals(0, s.damaged)
        assertEquals(0, s.errors)
        assertEquals(cases.count { !it.isControl }, s.targets)
    }

    /**
     * Guard for approaches without an LLM (step 3.2 must pass it): no control
     * sentence damaged, no exception. LLM approaches are reported, not gated
     * here: step 3.3 decides with the numbers.
     */
    @Test fun approachesWithoutLlmDamageNoControlSentence() {
        val offenders = scores.filter { !it.approach.needsLlm }
            .filter { it.all.damaged > 0 || it.all.errors > 0 }
            .map { "${it.approach.name}: abîmés ${it.damagedIds}, erreurs ${it.errorIds}" }
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }
}
