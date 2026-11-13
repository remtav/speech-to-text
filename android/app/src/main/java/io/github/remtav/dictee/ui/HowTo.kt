package io.github.remtav.dictee.ui

import io.github.remtav.dictee.dictation.StringSink
import io.github.remtav.dictee.dictation.TextPipeline
import io.github.remtav.dictee.stt.DictationLanguage
import io.github.remtav.dictee.text.Command
import io.github.remtav.dictee.text.CommandEntry
import io.github.remtav.dictee.text.CommandKind
import io.github.remtav.dictee.text.CommandExample
import io.github.remtav.dictee.text.CommandLanguage
import io.github.remtav.dictee.text.CommandTable

/**
 * Pure logic of the "Comment dicter" screen (issue #15, step 2.3; first screen: issue #56), kept free of Android
 * so that the JVM tests cover it.
 */

/**
 * The steps of the installation screen (issue #55), in the order they are done. [DONE]: nothing left to do.
 * Only the button of [SetupProgress.nextStep] is a filled button on the screen.
 */
enum class SetupStep { ENABLE_KEYBOARD, CHOOSE_KEYBOARD, ALLOW_MIC, GET_MODEL, DONE }

/** Where the first setup stands (the tutorial never opens by itself: issue #56; the setup screen says « C'est prêt » instead). */
object SetupProgress {
    /**
     * "End of the setup" (issue #55, point 5): the keyboard is enabled **and** chosen (the current input
     * method), the microphone is allowed, and a model is ready to use. Only then does the phone really
     * dictate, so only then does the screen say « C'est prêt ». A keyboard cannot be chosen without being enabled:
     * "chosen" alone (an inconsistent reading of the system settings) does not count.
     */
    fun isComplete(keyboardEnabled: Boolean, keyboardChosen: Boolean, micGranted: Boolean, modelReady: Boolean): Boolean =
        nextStep(keyboardEnabled, keyboardChosen, micGranted, modelReady) == SetupStep.DONE

    /** The next thing to do, in the screen's order: enable, choose, microphone, model. */
    fun nextStep(keyboardEnabled: Boolean, keyboardChosen: Boolean, micGranted: Boolean, modelReady: Boolean): SetupStep = when {
        !keyboardEnabled -> SetupStep.ENABLE_KEYBOARD
        !keyboardChosen -> SetupStep.CHOOSE_KEYBOARD
        !micGranted -> SetupStep.ALLOW_MIC
        !modelReady -> SetupStep.GET_MODEL
        else -> SetupStep.DONE
    }

    /**
     * Issue #55, point 6: back on the screen after a first visit, with one of the two keyboard steps missing,
     * the screen starts on the steps rather than on the welcome text. Never on the very first visit: the
     * welcome text is read first.
     */
    fun jumpToKeyboardSteps(seenBefore: Boolean, keyboardEnabled: Boolean, keyboardChosen: Boolean): Boolean =
        seenBefore && !(keyboardEnabled && keyboardChosen)

}

/**
 * One "say this, you get that" card. Several commands can share one example
 * (opening and closing quotes and the colon share one sentence; so do the
 * parentheses): they are shown once, with [commands] listing what it teaches.
 * [result] is what the keyboard itself produces for the user's languages (France
 * typography for a France-only user), not the Québec text written in the table.
 */
data class HowToExample(
    val language: CommandLanguage,
    val example: CommandExample,
    val commands: List<CommandEntry>,
    val result: String,
) {
    val said: String get() = example.said
    val before: String get() = example.before
}

/**
 * One example of the first screen (issue #56), on two lines: what is said, with the command
 * marked, and what is obtained, with the sign marked. The ranges index [said] and [shown].
 * [shown] is the keyboard's own result; only a line break is drawn as [LINE_BREAK_MARK] so
 * that it can be marked.
 */
data class HowToQuickExample(
    val language: CommandLanguage,
    val entry: CommandEntry,
    val said: String,
    val commandRange: IntRange?,
    val shown: String,
    val signRange: IntRange?,
)

object HowToExamples {
    /**
     * The commands the first screen teaches, per language, in this order: the most useful every day
     * (issue #56). In English the colon is left to the full list: « colon » is also a word, so the
     * parser accepts it only between punctuation or at the edge of a segment, which is not "in the
     * middle of your sentence".
     */
    val FIRST_SCREEN_COMMANDS: Map<CommandLanguage, List<Command>> = mapOf(
        CommandLanguage.FRENCH to listOf(Command.COLON, Command.QUESTION_MARK, Command.NEW_LINE),
        CommandLanguage.ENGLISH to listOf(Command.QUESTION_MARK, Command.NEW_LINE, Command.EXCLAMATION_MARK),
    )

    /** At most this many examples on the first screen. */
    const val MAX_FIRST_SCREEN = 3

    /** Drawn in place of a line break in a result, so that the break can be marked. */
    const val LINE_BREAK_MARK = "↵"

    /**
     * The examples of the first screen for [chosen]: at most [MAX_FIRST_SCREEN], taken from
     * [CommandTable.active] in the order of [FIRST_SCREEN_COMMANDS] for their language, the languages sharing the
     * slots like [featured] (French first, with the odd one). The sentence is the entry's
     * [CommandEntry.firstScreenExample] and the result is what the keyboard produces for [chosen].
     */
    fun firstScreen(chosen: Set<DictationLanguage>, max: Int = MAX_FIRST_SCREEN): List<HowToQuickExample> {
        val active = CommandTable.active(chosen)
        val byLanguage = CommandLanguage.entries.mapNotNull { language ->
            FIRST_SCREEN_COMMANDS.getValue(language).mapNotNull { c -> active.firstOrNull { it.language == language && it.command == c } }
                .takeIf { it.isNotEmpty() }
        }
        if (byLanguage.isEmpty()) return emptyList()
        val k = byLanguage.size
        val taken = byLanguage.mapIndexed { i, list -> minOf(list.size, max / k + if (i < max % k) 1 else 0) }.toMutableList()
        var free = max - taken.sum()
        for (i in byLanguage.indices) {
            val more = minOf(free, byLanguage[i].size - taken[i])
            taken[i] += more
            free -= more
        }
        return byLanguage.flatMapIndexed { i, list -> list.take(taken[i]).map { quick(it, chosen) } }
    }

    private fun quick(entry: CommandEntry, chosen: Set<DictationLanguage>): HowToQuickExample {
        val example = entry.firstScreenExample
        val said = example.said
        // The wording as it was said: the longest form found in the sentence.
        val form = entry.forms.map { it.text }.sortedByDescending { it.length }
            .firstOrNull { said.contains(it, ignoreCase = true) }
        val start = form?.let { said.indexOf(it, ignoreCase = true) }
        val commandRange = if (form != null && start != null) start until start + form.length else null
        val result = render(example, chosen)
        return if (entry.command.kind == CommandKind.LAYOUT) {
            val at = result.indexOf('\n')
            if (at < 0) {
                HowToQuickExample(entry.language, entry, said, commandRange, result, null)
            } else {
                val end = result.indexOfFirst(from = at) { it != '\n' }
                val before = result.substring(0, at).trimEnd()
                val after = result.substring(end).trimStart()
                val shown = "$before $LINE_BREAK_MARK $after"
                val markAt = before.length + 1
                HowToQuickExample(entry.language, entry, said, commandRange, shown, markAt until markAt + LINE_BREAK_MARK.length)
            }
        } else {
            val at = result.indexOf(entry.produces)
            val sign = if (at >= 0) at until at + entry.produces.length else null
            HowToQuickExample(entry.language, entry, said, commandRange, result, sign)
        }
    }

    private fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
        for (i in from until length) if (predicate(this[i])) return i
        return length
    }

    /**
     * Every distinct example of the commands active for [chosen], in table order (French
     * first), the entries that share an example merged into one.
     */
    fun all(chosen: Set<DictationLanguage>): List<HowToExample> =
        CommandTable.active(chosen)
            .groupBy { it.language to it.example }
            .map { (key, entries) -> HowToExample(key.first, key.second, entries, render(key.second, chosen)) }

    /**
     * Runs [example] through the keyboard's own text pipeline, with the typography of
     * [chosen] (review 2.R): the card can only show what dictating it gives.
     */
    fun render(example: CommandExample, chosen: Set<DictationLanguage>): String {
        val sink = StringSink(example.before)
        TextPipeline(languages = chosen).applyFinal(example.said, null, sink)
        return sink.text
    }
}
