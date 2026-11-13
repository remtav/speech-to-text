package io.github.remtav.dictee.ui

import io.github.remtav.dictee.dictation.StringSink
import io.github.remtav.dictee.dictation.TextPipeline
import io.github.remtav.dictee.stt.DictationLanguage
import io.github.remtav.dictee.text.CommandEntry
import io.github.remtav.dictee.text.CommandExample
import io.github.remtav.dictee.text.CommandLanguage
import io.github.remtav.dictee.text.CommandTable

/**
 * Pure logic of the "Comment dicter" screen (issue #15, step 2.3), kept free of Android
 * so that the JVM tests cover it.
 */

/**
 * The steps of the installation screen (issue #55), in the order they are done. [DONE]: nothing left to do.
 * Only the button of [SetupProgress.nextStep] is a filled button on the screen.
 */
enum class SetupStep { ENABLE_KEYBOARD, CHOOSE_KEYBOARD, ALLOW_MIC, GET_MODEL, DONE }

/** Where the first setup stands, and when the tutorial opens by itself: once, at the end of the first setup. */
object SetupProgress {
    /**
     * "End of the setup" (issue #55, point 5): the keyboard is enabled **and** chosen (the current input
     * method), the microphone is allowed, and a model is ready to use. Only then does the phone really
     * dictate, so only then is the tutorial useful. A keyboard cannot be chosen without being enabled:
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

    /** Opens once: [alreadyShown] is remembered in the settings, by the first opening of the screen, however it happened. */
    fun shouldOpenHowTo(complete: Boolean, alreadyShown: Boolean): Boolean = complete && !alreadyShown
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

object HowToExamples {
    /** Most examples on the card list; the rest is in the list of all commands below. */
    const val MAX_FEATURED = 8

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

    /**
     * The examples put forward: at most [max], the languages sharing the slots equally
     * (the first one, French, gets the odd one), each in table order, so that the first
     * ones are the signs of the issue's initial list (quotes, semicolon, parenthesis,
     * question and exclamation marks). A language with fewer examples than its share
     * leaves its slots to the others. The rest stays reachable in the list of all commands.
     */
    fun featured(chosen: Set<DictationLanguage>, max: Int = MAX_FEATURED): List<HowToExample> {
        val byLanguage = all(chosen).groupBy { it.language }.values.toList()
        if (byLanguage.isEmpty()) return emptyList()
        val k = byLanguage.size
        val taken = byLanguage.mapIndexed { i, list -> minOf(list.size, max / k + if (i < max % k) 1 else 0) }.toMutableList()
        var free = max - taken.sum()
        for (i in byLanguage.indices) {
            val more = minOf(free, byLanguage[i].size - taken[i])
            taken[i] += more
            free -= more
        }
        return byLanguage.flatMapIndexed { i, list -> list.take(taken[i]) }
    }
}
