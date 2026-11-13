package io.github.remtav.dictee

import io.github.remtav.dictee.stt.DictationLanguage
import io.github.remtav.dictee.text.Command
import io.github.remtav.dictee.text.CommandLanguage
import io.github.remtav.dictee.text.CommandTable
import io.github.remtav.dictee.ui.HowToExamples
import io.github.remtav.dictee.ui.SetupProgress
import io.github.remtav.dictee.ui.SetupStep
import io.github.remtav.dictee.ui.SizeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Issue #15, step 2.3: selection of the examples of the "Comment dicter" screen, "end of setup", sizes. */
class HowToExamplesTest {
    private val quebec = setOf(DictationLanguage.FRENCH_QUEBEC)
    private val quebecAndEnglish = setOf(DictationLanguage.FRENCH_QUEBEC, DictationLanguage.ENGLISH)
    private val english = setOf(DictationLanguage.ENGLISH)

    @Test
    fun sharedExamplesAreShownOnceWithAllTheirCommands() {
        val all = HowToExamples.all(quebec)
        // one card for the quotes and the colon, one for the parentheses
        val quotes = all.single { Command.OPEN_QUOTE in it.commands.map { c -> c.command } }
        assertEquals(setOf(Command.OPEN_QUOTE, Command.CLOSE_QUOTE, Command.COLON), quotes.commands.map { it.command }.toSet())
        val parentheses = all.single { Command.OPEN_PARENTHESIS in it.commands.map { c -> c.command } }
        assertEquals(setOf(Command.OPEN_PARENTHESIS, Command.CLOSE_PARENTHESIS), parentheses.commands.map { it.command }.toSet())
        assertEquals("no duplicate", all.size, all.map { it.language to it.example }.toSet().size)
        // every command of the active table is taught by exactly one card
        assertEquals(
            CommandTable.active(quebec).map { it.command to it.language }.toSet(),
            all.flatMap { ex -> ex.commands.map { it.command to it.language } }.toSet(),
        )
    }

    @Test
    fun everyExampleComesFromTheActiveTable() {
        for (chosen in listOf(quebec, english, quebecAndEnglish, setOf(DictationLanguage.SPANISH), DictationLanguage.entries.toSet())) {
            val table = CommandTable.active(chosen)
            for (ex in HowToExamples.featured(chosen)) {
                assertTrue(
                    "${ex.said} not in the table for $chosen",
                    table.any { it.language == ex.language && it.example == ex.example },
                )
                assertTrue(ex.commands.all { it.example == ex.example && it.language == ex.language })
            }
        }
    }

    @Test
    fun betweenFiveAndEightExamplesWhateverTheLanguages() {
        for (chosen in listOf(quebec, english, quebecAndEnglish, setOf(DictationLanguage.SPANISH), DictationLanguage.entries.toSet())) {
            val n = HowToExamples.featured(chosen).size
            assertTrue("$chosen gives $n examples", n in 5..HowToExamples.MAX_FEATURED)
        }
        // more than eight in French: the first eight are shown, the rest stays in the list of all commands
        assertTrue(HowToExamples.all(quebec).size > HowToExamples.MAX_FEATURED)
        assertEquals(HowToExamples.all(quebec).take(HowToExamples.MAX_FEATURED), HowToExamples.featured(quebec))
    }

    @Test
    fun frenchComesFirstAndSharesTheSlotsWithEnglish() {
        val shown = HowToExamples.featured(quebecAndEnglish)
        assertEquals(List(4) { CommandLanguage.FRENCH } + List(4) { CommandLanguage.ENGLISH }, shown.map { it.language })
        // the signs of the issue's initial list come first, in French
        assertTrue(Command.OPEN_QUOTE in shown.first().commands.map { it.command })
        assertEquals(CommandLanguage.FRENCH, shown.first().language)
        // English alone: English only
        assertEquals(setOf(CommandLanguage.ENGLISH), HowToExamples.featured(english).map { it.language }.toSet())
    }

    @Test
    fun resultsAreWhatTheKeyboardProducesForTheUsersTypography() {
        // Québec: the text of the table, as written.
        for (ex in HowToExamples.all(quebec)) assertEquals(ex.said, ex.example.result, ex.result)
        for (ex in HowToExamples.all(english)) assertEquals(ex.said, ex.example.result, ex.result)
        for (ex in HowToExamples.all(quebecAndEnglish)) assertEquals(ex.said, ex.example.result, ex.result)
        // France alone (review 2.R): no-break space before ; ? !, not the Québec text of the table.
        val france = HowToExamples.all(setOf(DictationLanguage.FRENCH_FRANCE))
        val question = france.single { Command.QUESTION_MARK in it.commands.map { c -> c.command } }
        assertEquals("Tu viens demain\u00A0?", question.result)
        val semicolon = france.single { Command.SEMICOLON in it.commands.map { c -> c.command } }
        assertEquals("Du pain\u00A0; du lait", semicolon.result)
    }

    @Test
    fun aLanguageWithoutPunctuationCommandsStillGetsExamples() {
        // Spanish only: no sign command (CommandTable.active), line breaks and deletions remain
        val shown = HowToExamples.featured(setOf(DictationLanguage.SPANISH))
        assertTrue(shown.isNotEmpty())
        assertTrue(shown.flatMap { it.commands }.none { it.command.isSign })
    }

    @Test
    fun neitherTheScreenNorTheStringsCopyAnExampleByHand() {
        val main = File(System.getProperty("dictee.assetsDir")!!).parentFile
        val sources = listOf(
            main.resolve("java/io/github/remtav/dictee/ui/HowToActivity.kt"),
            main.resolve("res/values/strings.xml"),
        ).associateWith { f ->
            // Only the strings of this screen: the older help of the settings quotes some commands.
            if (f.extension == "xml") f.readLines().filter { it.contains("name=\"howto_") || it.contains("name=\"setup_howto") }.joinToString("\n")
            else f.readText()
        }
        for (entry in CommandTable.entries) {
            for ((file, text) in sources) {
                assertFalse("${file.name} copies « ${entry.example.said} »", text.contains(entry.example.said))
                assertFalse("${file.name} copies « ${entry.example.result} »", text.contains(entry.example.result))
            }
        }
    }
}

class SetupProgressTest {
    @Test
    fun setupIsCompleteOnlyWithKeyboardEnabledAndChosenMicrophoneAndModel() {
        val b = listOf(false, true)
        for (enabled in b) for (chosen in b) for (mic in b) for (model in b) {
            assertEquals(
                "enabled=$enabled chosen=$chosen mic=$mic model=$model",
                enabled && chosen && mic && model,
                SetupProgress.isComplete(enabled, chosen, mic, model),
            )
        }
    }

    /** Issue #55, point 5: a keyboard enabled but not chosen does not dictate anywhere. */
    @Test
    fun enabledButNotChosenIsNotComplete() {
        assertFalse(SetupProgress.isComplete(keyboardEnabled = true, keyboardChosen = false, micGranted = true, modelReady = true))
        assertEquals(SetupStep.CHOOSE_KEYBOARD, SetupProgress.nextStep(true, false, true, true))
    }

    @Test
    fun theNextStepFollowsTheScreenOrder() {
        assertEquals(SetupStep.ENABLE_KEYBOARD, SetupProgress.nextStep(false, false, false, false))
        // "Chosen" without "enabled" is an inconsistent reading: enabling comes first.
        assertEquals(SetupStep.ENABLE_KEYBOARD, SetupProgress.nextStep(false, true, true, true))
        assertEquals(SetupStep.CHOOSE_KEYBOARD, SetupProgress.nextStep(true, false, false, false))
        assertEquals(SetupStep.ALLOW_MIC, SetupProgress.nextStep(true, true, false, false))
        assertEquals(SetupStep.GET_MODEL, SetupProgress.nextStep(true, true, true, false))
        assertEquals(SetupStep.DONE, SetupProgress.nextStep(true, true, true, true))
    }

    /** Issue #55, point 6: back on the screen with a keyboard step missing, the steps come first; never the first time. */
    @Test
    fun theScreenJumpsToAMissingKeyboardStepOnlyAfterAFirstVisit() {
        assertFalse("first visit", SetupProgress.jumpToKeyboardSteps(seenBefore = false, keyboardEnabled = false, keyboardChosen = false))
        assertTrue(SetupProgress.jumpToKeyboardSteps(seenBefore = true, keyboardEnabled = false, keyboardChosen = false))
        assertTrue(SetupProgress.jumpToKeyboardSteps(seenBefore = true, keyboardEnabled = true, keyboardChosen = false))
        assertFalse(SetupProgress.jumpToKeyboardSteps(seenBefore = true, keyboardEnabled = true, keyboardChosen = true))
    }

    @Test
    fun theTutorialOpensOnlyOnceWhenComplete() {
        assertTrue(SetupProgress.shouldOpenHowTo(complete = true, alreadyShown = false))
        assertFalse("second time", SetupProgress.shouldOpenHowTo(complete = true, alreadyShown = true))
        assertFalse("not complete", SetupProgress.shouldOpenHowTo(complete = false, alreadyShown = false))
        assertFalse(SetupProgress.shouldOpenHowTo(complete = false, alreadyShown = true))
    }
}

class SizeFormatTest {
    @Test
    fun sizesAreInFrenchUnitsWithADecimalComma() {
        assertEquals("0 o", SizeFormat.format(0))
        assertEquals("999 o", SizeFormat.format(999))
        assertEquals("1,50 ko", SizeFormat.format(1_500))
        assertEquals("671 Mo", SizeFormat.format(671_000_000))
        assertEquals("98,5 Mo", SizeFormat.format(98_500_000))
        assertEquals("987 Mo", SizeFormat.format(987_000_000))
        assertEquals("1,04 Go", SizeFormat.format(1_037_000_000))
        assertEquals("0 o", SizeFormat.format(-5))
    }

    @Test
    fun noEnglishUnitIsEverShown() {
        for (b in listOf(1L, 1_234L, 12_345_678L, 5_000_000_000L)) {
            val s = SizeFormat.format(b)
            assertFalse(s, s.contains("MB") || s.contains("GB") || s.contains("KB") || s.contains('.'))
        }
    }
}
