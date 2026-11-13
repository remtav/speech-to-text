package io.github.remtav.dictee.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import io.github.remtav.dictee.DicteeApp
import io.github.remtav.dictee.R
import io.github.remtav.dictee.stt.DictationLanguage
import io.github.remtav.dictee.text.CommandEntry
import io.github.remtav.dictee.text.CommandLanguage
import io.github.remtav.dictee.text.CommandTable

/**
 * "Comment dicter" (issue #15, step 2.3; reorganised by issue #56). Never opens by itself: from the
 * main screen (the invitation "Aller plus loin" at the end of the setup, the button) and from the
 * keyboard's help button. First screen: the rule in one sentence, at most three examples on two
 * lines each (command and sign marked, see [emphasised]), then the try field. Everything else is in
 * "Toutes les commandes", closed at first. Every command shown comes from [CommandTable.active]
 * for the chosen languages (nothing is written by hand here), so the help cannot drift from what
 * the keyboard does.
 *
 * Redrawn on each resume: the languages, the voice-command switch or the vocabulary may have
 * been changed in the settings in the meantime.
 */
class HowToActivity : AppCompatActivity() {
    private lateinit var app: DicteeApp
    private lateinit var column: LinearLayout
    private var tryText: CharSequence = ""
    private var listOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = DicteeApp.from(this)
        column = scrollingColumn()
        listOpen = savedInstanceState?.getBoolean(STATE_LIST_OPEN) ?: false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_LIST_OPEN, listOpen)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        column.findViewWithTag<EditText>(TRY_TAG)?.let { tryText = it.text }
        column.removeAllViews()
        val s = app.settings
        val chosen = s.languages
        val col = column

        col.title(getString(R.string.howto_title)).tag = TITLE_TAG
        col.body(getString(R.string.howto_rule)).tag = RULE_TAG
        if (!s.voiceCommands) {
            col.body(getString(R.string.howto_commands_off)).tag = COMMANDS_OFF_TAG
            col.button(getString(R.string.howto_open_settings), outlined = true) {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }
        val quick = HowToExamples.firstScreen(chosen)
        val several = quick.map { it.language }.distinct().size > 1
        quick.forEachIndexed { i, ex ->
            val card = col.card()
            card.tag = exampleTag(i)
            val say = getString(if (several) R.string.howto_say_in else R.string.howto_say, ex.language.label.lowercase())
            card.body(emphasised("$say « ", ex.said, ex.commandRange, Emphasis.COMMAND).append(" »")).tag = SAID_TAG
            val get = getString(R.string.howto_get)
            card.body(emphasised("$get « ", ex.shown, ex.signRange, Emphasis.SIGN).append(" »")).tag = RESULT_TAG
        }

        col.heading(getString(R.string.howto_try))
        col.addView(EditText(this).apply {
            tag = TRY_TAG
            hint = getString(R.string.howto_try_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            setText(tryText)
        })

        renderAllCommands(col, chosen)
        col.body(getString(R.string.howto_privacy), small = true).tag = PRIVACY_TAG
    }

    /** "Toutes les commandes": a button that opens or closes the full list, closed at first. */
    private fun renderAllCommands(col: LinearLayout, chosen: Set<DictationLanguage>) {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = ALL_LIST_TAG
            visibility = if (listOpen) View.VISIBLE else View.GONE
        }
        val toggle = col.button(getString(R.string.howto_all_show), outlined = true) {}
        toggle.tag = ALL_TOGGLE_TAG
        fun sync() {
            toggle.text = getString(if (listOpen) R.string.howto_all_hide else R.string.howto_all_show)
            list.visibility = if (listOpen) View.VISIBLE else View.GONE
        }
        toggle.setOnClickListener { listOpen = !listOpen; sync() }
        sync()
        col.addView(list)

        list.body(getString(R.string.howto_all_help), small = true)
        val entries = CommandTable.active(chosen)
        val examples = HowToExamples.all(chosen)
        for (language in CommandLanguage.entries) {
            val inLanguage = entries.filter { it.language == language }
            if (inLanguage.isEmpty()) continue
            if (entries.any { it.language != language }) list.label(language.label)
            inLanguage.forEach { entry ->
                // The example is shown once per shared sentence, under the first command that uses it.
                val shared = examples.firstOrNull { ex -> ex.language == language && ex.commands.firstOrNull() == entry }
                list.commandLine(entry, shared)
            }
        }

        list.heading(getString(R.string.howto_vocabulary))
        list.body(getString(R.string.howto_vocabulary_help), small = true)
        list.button(getString(R.string.howto_vocabulary_open), outlined = true) {
            startActivity(
                Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SCROLL_TO_VOCABULARY, true),
            )
        }.tag = VOCABULARY_BUTTON_TAG
    }

    /** One line of the full list: the main wording as a value, what it gives and the other wordings. */
    private fun LinearLayout.commandLine(entry: CommandEntry, example: HowToExample?) {
        value(entry.primary).tag = COMMAND_TAG
        val others = entry.forms.drop(1).joinToString(" · ") { it.text }
        body(
            buildString {
                append(getString(R.string.howto_produces, entry.produces))
                if (others.isNotEmpty()) append("\n").append(getString(R.string.howto_also, others))
                if (example != null) {
                    if (example.before.isNotEmpty()) append("\n").append(getString(R.string.howto_before, example.before))
                    append("\n").append(getString(R.string.howto_example_line, example.said, example.result))
                }
            },
            small = true,
        )
    }

    companion object {
        const val TITLE_TAG = "howto_title"
        const val RULE_TAG = "howto_rule"
        const val PRIVACY_TAG = "howto_privacy"
        const val ALL_TOGGLE_TAG = "howto_all_toggle"
        const val ALL_LIST_TAG = "howto_all_list"
        private const val STATE_LIST_OPEN = "list_open"
        const val SAID_TAG = "howto_said"
        const val RESULT_TAG = "howto_result"
        const val COMMAND_TAG = "howto_command"
        const val TRY_TAG = "howto_try"
        const val COMMANDS_OFF_TAG = "howto_commands_off"
        const val VOCABULARY_BUTTON_TAG = "howto_vocabulary_button"
        fun exampleTag(index: Int) = "howto_example_$index"
    }
}
