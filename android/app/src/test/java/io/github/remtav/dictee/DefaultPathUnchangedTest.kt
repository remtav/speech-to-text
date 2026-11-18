package io.github.remtav.dictee

import io.github.remtav.dictee.dictation.TextPipeline
import io.github.remtav.dictee.stt.ModelFamily
import io.github.remtav.dictee.text.Piece
import io.github.remtav.dictee.text.polish.PolishRules
import io.github.remtav.dictee.text.polish.ShippedVocabulary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Nothing is activated by default. The polishing rules and the shipped vocabulary
 * (text/polish/) and the LLM evaluation code (cleanup/eval/) are test-only sources in
 * src/sharedTest; the dictation pipeline neither calls them nor has a setting for them.
 */
class DefaultPathUnchangedTest {
    private val samples = listOf(
        "Va sur coiffe rimouski point com." to "fr",
        "Écris-moi à rémi arobase kalmia point ca." to "fr",
        "Mon code est sur git hub et whats app." to "fr",
        "On passe par trois rivieres." to "fr",
    )

    @Test fun defaultPipelineLeavesTextThatTheRulesWouldChange() {
        for ((raw, lang) in samples) {
            val polished = ShippedVocabulary.apply(PolishRules.polish(raw, lang), lang)
            assertNotEquals("the sample must be something the polishing would change: $raw", raw, polished)
            val pieces = TextPipeline().prepare(raw, ModelFamily.NEMO_TRANSDUCER)
            assertEquals(listOf<Piece>(Piece.Text(raw)), pieces)
        }
    }

    private val mainSources by lazy {
        File(System.getProperty("dictee.assetsDir") ?: "src/main/assets").parentFile.resolve("java")
            .walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private val sharedTestSources by lazy {
        File(System.getProperty("dictee.assetsDir") ?: "src/main/assets").parentFile.parentFile
            .resolve("sharedTest/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** The polishing rules must not depend on the LLM evaluation code (layering rule, still valid in sharedTest). */
    @Test fun polishRulesDoNotDependOnTheLlmEvaluation() {
        val polish = sharedTestSources.filter { "/text/polish/" in it.path }
        assertTrue("text/polish not found in src/sharedTest", polish.isNotEmpty())
        assertTrue("the polish rules must not depend on the LLM evaluation", polish.none { "cleanup.eval" in it.readText() })
    }

    /** The polishing rules and the LLM evaluation code are test-only sources (src/sharedTest), not in the APK. */
    @Test fun evaluationPackagesAreNotInTheApp() {
        assertTrue("no source found in src/main/java", mainSources.isNotEmpty())
        assertTrue("evaluation sources not found in src/sharedTest",
            sharedTestSources.any { "/cleanup/eval/" in it.path } && sharedTestSources.any { "/text/polish/" in it.path })
        val inMain = mainSources.filter { "/text/polish/" in it.path || "/cleanup/eval/" in it.path }.map { it.name }
        assertTrue("evaluation code shipped in main: $inMain", inMain.isEmpty())
    }

    /** Nothing in the app refers to the polishing rules or to the LLM evaluation code. */
    @Test fun nothingInTheAppCallsThePolishOrEvaluationPackages() {
        assertTrue("no source found in src/main/java", mainSources.isNotEmpty())
        val names = listOf(
            "text.polish", "PolishRules", "ShippedVocabulary",
            "cleanup.eval", "RelaxedSafetyCheck", "GuardedLlmPolisher", "RulesThenLlmPolisher", "WidePrompt",
            "RuleSignalDetector", "WiredRules", "ShippedLexicon", "wiredCombination", "ProvisionalProbableCaseDetector",
        )
        val offenders = mainSources.filter { f -> f.readText().let { text -> names.any { it in text } } }.map { it.name }
        assertTrue("wired into the app: $offenders", offenders.isEmpty())
    }
}
