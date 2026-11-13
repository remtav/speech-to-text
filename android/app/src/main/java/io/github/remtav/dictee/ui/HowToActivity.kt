package io.github.remtav.dictee.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import io.github.remtav.dictee.DicteeApp
import io.github.remtav.dictee.R
import io.github.remtav.dictee.stt.ModelFamily
import io.github.remtav.dictee.stt.ModelRecommendations
import io.github.remtav.dictee.text.CommandEntry
import io.github.remtav.dictee.text.CommandLanguage
import io.github.remtav.dictee.text.CommandTable

/**
 * "Comment dicter" (issue #15, step 2.3). Every command shown comes from
 * [CommandTable.active] for the chosen languages (nothing is written by hand here), so the help
 * cannot drift from what the keyboard does. Opens by itself at the end of the first setup, from
 * the main screen and from the keyboard's help button.
 *
 * Redrawn on each resume: the languages, the voice-command switch or the vocabulary may have
 * been changed in the settings in the meantime.
 */
class HowToActivity : AppCompatActivity() {
    private lateinit var app: DicteeApp
    private lateinit var column: LinearLayout
    private var tryText: CharSequence = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = DicteeApp.from(this)
        column = scrollingColumn()
        // Any opening counts: the screen then no longer opens by itself.
        app.settings.howToShown = true
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
        // Issue #57: true in both cases; the server exists only once set up in the advanced settings.
        col.body(getString(if (s.serverInUse) R.string.howto_privacy_server else R.string.howto_privacy)).tag = PRIVACY_TAG

        col.heading(getString(R.string.howto_examples))
        col.body(getString(R.string.howto_examples_help), small = true)
        if (!s.voiceCommands) {
            col.body(getString(R.string.howto_commands_off)).tag = COMMANDS_OFF_TAG
            col.button(getString(R.string.howto_open_settings), outlined = true) {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }
        val featured = HowToExamples.featured(chosen)
        val languages = featured.map { it.language }.distinct()
        featured.forEachIndexed { i, ex ->
            val card = col.card()
            card.tag = exampleTag(i)
            if (languages.size > 1) card.label(ex.language.label)
            card.label(getString(R.string.howto_say))
            card.value(getString(R.string.howto_said, ex.said)).tag = SAID_TAG
            if (ex.before.isNotEmpty()) card.body(getString(R.string.howto_before, ex.before), small = true)
            card.label(getString(R.string.howto_get))
            card.body(ex.result).tag = RESULT_TAG
            card.body(
                getString(R.string.howto_covers, ex.commands.joinToString(" · ") { it.primary }),
                small = true,
            )
        }

        col.heading(getString(R.string.howto_all))
        col.body(getString(R.string.howto_all_help), small = true)
        val entries = CommandTable.active(chosen)
        for (language in CommandLanguage.entries) {
            val inLanguage = entries.filter { it.language == language }
            if (inLanguage.isEmpty()) continue
            if (entries.any { it.language != language }) col.label(language.label)
            inLanguage.forEach { col.commandLine(it) }
        }

        col.heading(getString(R.string.howto_try))
        col.body(getString(R.string.howto_try_hint), small = true)
        col.addView(EditText(this).apply {
            tag = TRY_TAG
            hint = getString(R.string.howto_try_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            setText(tryText)
        })

        col.heading(getString(R.string.howto_model))
        val recommendation = ModelRecommendations.recommend(chosen)
        col.body(getString(R.string.howto_model_recommended, recommendation.model.displayName)).tag = MODEL_TAG
        col.body(recommendation.reason, small = true)
        val ready = app.isInstalled(recommendation.model)
        col.value(getString(if (ready) R.string.howto_model_installed else R.string.howto_model_missing))
        if (!ready || app.shownSpec().family == ModelFamily.REMOTE) {
            col.button(getString(R.string.howto_model_choose), outlined = true) {
                startActivity(
                    Intent(this, SetupActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
        }

        col.heading(getString(R.string.howto_vocabulary))
        col.body(getString(R.string.howto_vocabulary_help), small = true)
        col.button(getString(R.string.howto_vocabulary_open), outlined = true) {
            startActivity(
                Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SCROLL_TO_VOCABULARY, true),
            )
        }.tag = VOCABULARY_BUTTON_TAG
    }

    /** One line of the full list: the main wording as a value, what it gives and the other wordings. */
    private fun LinearLayout.commandLine(entry: CommandEntry) {
        value(entry.primary).tag = COMMAND_TAG
        val others = entry.forms.drop(1).joinToString(" · ") { it.text }
        body(
            buildString {
                append(getString(R.string.howto_produces, entry.produces))
                if (others.isNotEmpty()) append("\n").append(getString(R.string.howto_also, others))
            },
            small = true,
        )
    }

    companion object {
        const val TITLE_TAG = "howto_title"
        const val PRIVACY_TAG = "howto_privacy"
        const val SAID_TAG = "howto_said"
        const val RESULT_TAG = "howto_result"
        const val COMMAND_TAG = "howto_command"
        const val TRY_TAG = "howto_try"
        const val MODEL_TAG = "howto_model"
        const val COMMANDS_OFF_TAG = "howto_commands_off"
        const val VOCABULARY_BUTTON_TAG = "howto_vocabulary_button"
        fun exampleTag(index: Int) = "howto_example_$index"
    }
}
