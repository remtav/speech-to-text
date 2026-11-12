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
 * Issue #15, lot 3: nothing is activated by default. The polishing rules and the
 * shipped vocabulary exist (text/polish/) but the dictation pipeline neither
 * calls them nor has a setting for them.
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

    @Test fun nothingInTheAppCallsThePolishPackage() {
        val main = File(System.getProperty("dictee.assetsDir") ?: "src/main/assets").parentFile.resolve("java")
        val offenders = main.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { !it.path.contains("/text/polish/") }
            .filter { f -> f.readText().let { "text.polish" in it || "PolishRules" in it || "ShippedVocabulary" in it } }
            .map { it.name }.toList()
        assertTrue("wired into the app: $offenders", offenders.isEmpty())
    }
}
