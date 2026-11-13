package io.github.remtav.dictee

import io.github.remtav.dictee.stt.DictationLanguage
import io.github.remtav.dictee.text.Command
import io.github.remtav.dictee.text.CommandLanguage
import io.github.remtav.dictee.text.CommandTable
import io.github.remtav.dictee.ui.HowToExamples
import io.github.remtav.dictee.ui.SetupProgress
import io.github.remtav.dictee.ui.SizeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Issue #15, step 2.3 and issue #56: examples of the "Comment dicter" screen (first screen and full list), "end of setup", sizes. */
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
            for (ex in HowToExamples.all(chosen)) {
                assertTrue(
                    "${ex.said} not in the table for $chosen",
                    table.any { it.language == ex.language && it.example == ex.example },
                )
                assertTrue(ex.commands.all { it.example == ex.example && it.language == ex.language })
            }
        }
    }

    @Test
    fun firstScreenHasAtMostThreeExamplesWhateverTheLanguages() {
        for (chosen in listOf(quebec, english, quebecAndEnglish, setOf(DictationLanguage.SPANISH), DictationLanguage.entries.toSet())) {
            val n = HowToExamples.firstScreen(chosen).size
            assertTrue("$chosen gives $n examples", n in 1..HowToExamples.MAX_FIRST_SCREEN)
        }
        // French alone: the three commands of the issue, in this order
        assertEquals(
            listOf(Command.COLON, Command.QUESTION_MARK, Command.NEW_LINE),
            HowToExamples.firstScreen(quebec).map { it.entry.command },
        )
    }

    @Test
    fun firstScreenLanguagesShareTheSlotsAndFrenchComesFirst() {
        val shown = HowToExamples.firstScreen(quebecAndEnglish)
        assertEquals(listOf(CommandLanguage.FRENCH, CommandLanguage.FRENCH, CommandLanguage.ENGLISH), shown.map { it.language })
        assertEquals(setOf(CommandLanguage.ENGLISH), HowToExamples.firstScreen(english).map { it.language }.toSet())
        assertEquals(3, HowToExamples.firstScreen(english).size)
        // Spanish only: no sign command in the table, only line breaks
        val spanish = HowToExamples.firstScreen(setOf(DictationLanguage.SPANISH))
        assertTrue(spanish.isNotEmpty() && spanish.none { it.entry.command.isSign })
    }

    @Test
    fun firstScreenExamplesComeFromTheTableAndGiveWhatTheKeyboardProduces() {
        for (chosen in listOf(quebec, english, quebecAndEnglish, setOf(DictationLanguage.FRENCH_FRANCE))) {
            for (ex in HowToExamples.firstScreen(chosen)) {
                val entry = CommandTable.active(chosen).single { it.language == ex.language && it.command == ex.entry.command }
                assertEquals(entry.firstScreenExample.said, ex.said)
                // the result is the pipeline's own, with the line break only drawn as a mark
                val result = HowToExamples.render(entry.firstScreenExample, chosen)
                assertEquals(result.replace(Regex("\\s*\\n+\\s*"), " "), ex.shown.replace(" ${HowToExamples.LINE_BREAK_MARK} ", " "))
            }
        }
        // The table's own claim: a first-screen sentence gives the result written next to it (Québec typography).
        for (entry in CommandTable.entries.filter { it.firstScreen != null }) {
            assertEquals(entry.firstScreen!!.result, HowToExamples.render(entry.firstScreen!!, setOf(DictationLanguage.FRENCH_QUEBEC, DictationLanguage.ENGLISH)))
        }
    }

    @Test
    fun theCommandAndTheSignAreLocatedInEachFirstScreenExample() {
        for (chosen in listOf(quebec, english, quebecAndEnglish, setOf(DictationLanguage.FRENCH_FRANCE))) {
            for (ex in HowToExamples.firstScreen(chosen)) {
                val label = "${ex.language} ${ex.entry.command} « ${ex.said} »"
                val c = ex.commandRange
                assertTrue("no command range for $label", c != null && !c.isEmpty())
                assertTrue("$label: « ${ex.said.substring(c!!)} » is not a wording of the command", ex.entry.forms.any { it.text.equals(ex.said.substring(c), ignoreCase = true) })
                val sign = ex.signRange
                assertTrue("no sign range for $label", sign != null && !sign.isEmpty())
                val mark = ex.shown.substring(sign!!)
                if (ex.entry.command.isSign) assertEquals(label, ex.entry.produces, mark) else assertEquals(HowToExamples.LINE_BREAK_MARK, mark)
                // each mark is a small part of the line, never the whole of it
                assertTrue(c.count() < ex.said.length && sign.count() < ex.shown.length)
            }
        }
    }

    @Test
    fun theCommandAppearsExactlyOnceInEachFirstScreenSentence() {
        // The marked range comes from the first occurrence (indexOf): a sentence that repeated its command, or
        // that contained another wording of it, would be marked in the wrong place without any other test seeing it.
        for (chosen in listOf(quebec, english, quebecAndEnglish, setOf(DictationLanguage.FRENCH_FRANCE), setOf(DictationLanguage.SPANISH))) {
            for (ex in HowToExamples.firstScreen(chosen)) {
                for (form in ex.entry.forms.map { it.text }) {
                    val n = Regex(Regex.escape(form), RegexOption.IGNORE_CASE).findAll(ex.said).count()
                    val marked = ex.said.substring(ex.commandRange!!)
                    if (form.equals(marked, ignoreCase = true)) {
                        assertEquals("« $form » appears $n times in « ${ex.said} »", 1, n)
                    } else {
                        // another wording of the same command must not be inside the sentence either
                        assertTrue("« $form » also appears in « ${ex.said} »", n == 0 || marked.contains(form, ignoreCase = true))
                    }
                }
            }
        }
    }

    @Test
    fun colonExampleIsTheRuleOfTheIssue() {
        val colon = HowToExamples.firstScreen(quebec).first()
        assertEquals("il a dit deux-points bonjour", colon.said)
        assertEquals("deux-points", colon.said.substring(colon.commandRange!!))
        assertEquals("Il a dit\u00A0: bonjour", colon.shown)
        assertEquals(":", colon.shown.substring(colon.signRange!!))
        // France typography: a no-break space before the sign, the sign itself is still the marked part
        val france = HowToExamples.firstScreen(setOf(DictationLanguage.FRENCH_FRANCE))
        val question = france.single { it.entry.command == Command.QUESTION_MARK }
        assertEquals("?", question.shown.substring(question.signRange!!))
        assertEquals('\u00A0', question.shown[question.signRange!!.first - 1])
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
        val shown = HowToExamples.all(setOf(DictationLanguage.SPANISH))
        assertTrue(shown.isNotEmpty())
        assertTrue(shown.flatMap { it.commands }.none { it.command.isSign })
    }

    @Test
    fun neitherTheScreenNorTheStringsCopyAnExampleByHand() {
        val main = File(System.getProperty("dictee.assetsDir")!!).parentFile
        val sources = listOf(
            main.resolve("java/io/github/remtav/dictee/ui/HowToActivity.kt"),
            main.resolve("java/io/github/remtav/dictee/ui/HowToStyle.kt"),
            main.resolve("java/io/github/remtav/dictee/ui/SetupReady.kt"),
            main.resolve("res/values/strings.xml"),
        ).associateWith { f ->
            // Only the strings of this screen: the older help of the settings quotes some commands.
            if (f.extension == "xml") f.readLines().filter { it.contains("name=\"howto_") || it.contains("name=\"setup_howto") || it.contains("name=\"setup_ready") }.joinToString("\n")
            else f.readText()
        }
        for (entry in CommandTable.entries) {
            for ((file, text) in sources) {
                entry.firstScreen?.let { q ->
                    assertFalse("${file.name} copies « ${q.said} »", text.contains(q.said))
                    assertFalse("${file.name} copies « ${q.result} »", text.contains(q.result))
                }
                assertFalse("${file.name} copies « ${entry.example.said} »", text.contains(entry.example.said))
                assertFalse("${file.name} copies « ${entry.example.result} »", text.contains(entry.example.result))
            }
        }
    }
}

class SetupProgressTest {
    @Test
    fun setupIsCompleteOnlyWithKeyboardMicrophoneAndModel() {
        for (keyboard in listOf(false, true)) for (mic in listOf(false, true)) for (model in listOf(false, true)) {
            assertEquals(
                "keyboard=$keyboard mic=$mic model=$model",
                keyboard && mic && model,
                SetupProgress.isComplete(keyboard, mic, model),
            )
        }
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
