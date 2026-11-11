package io.github.remtav.dictee.ui

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import io.github.remtav.dictee.DicteeApp
import io.github.remtav.dictee.R
import io.github.remtav.dictee.cleanup.LlmCatalog
import io.github.remtav.dictee.settings.CleanupMode
import io.github.remtav.dictee.settings.ThemeMode
import io.github.remtav.dictee.stt.Acceleration
import java.util.Locale

/** Dictation preferences (lots 2 and 3). Every optional feature can be turned off. */
class SettingsActivity : AppCompatActivity() {
    private lateinit var app: DicteeApp
    private lateinit var vocabulary: EditText
    private lateinit var llmBox: LinearLayout
    private val refresh: () -> Unit = { renderLlm() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = DicteeApp.from(this)
        val s = app.settings
        val col = scrollingColumn()
        col.title(getString(R.string.settings_title))

        col.heading(getString(R.string.settings_dictation))
        col.switch(getString(R.string.settings_partials), s.partials) { s.partials = it }
        col.body(getString(R.string.settings_partials_help), small = true)
        col.switch(getString(R.string.settings_commands), s.voiceCommands) { s.voiceCommands = it }
        col.body(getString(R.string.settings_commands_help), small = true)
        col.slider(getString(R.string.settings_pause), 0.3f, 1.5f, 0.1f, s.pauseSeconds, { "%.1f s".format(Locale.ROOT, it) }) {
            s.pauseSeconds = it
        }

        col.heading(getString(R.string.settings_vocabulary))
        col.body(getString(R.string.settings_vocabulary_help), small = true)
        vocabulary = EditText(this).apply {
            setText(s.vocabularyText)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 4
            gravity = Gravity.TOP
            hint = "Rémi\nclaude code -> Claude Code"
        }
        col.addView(vocabulary)

        col.heading(getString(R.string.settings_feedback))
        col.radio(ThemeMode.entries.map { it.label }, s.theme.ordinal) {
            s.theme = ThemeMode.entries[it]
            app.applyTheme()
        }
        col.switch(getString(R.string.settings_haptics), s.haptics) { s.haptics = it }

        col.heading(getString(R.string.settings_performance))
        col.slider(getString(R.string.settings_keep_loaded), 0f, 30f, 1f, s.keepLoadedMinutes.toFloat(), { "${it.toInt()} min" }) {
            s.keepLoadedMinutes = it.toInt()
        }
        col.slider(getString(R.string.settings_threads), 1f, 8f, 1f, s.threads.toFloat(), { "${it.toInt()}" }) {
            s.threads = it.toInt()
        }
        col.body(getString(R.string.settings_acceleration), small = false)
        col.radio(Acceleration.entries.map { it.label }, s.acceleration.ordinal) { s.acceleration = Acceleration.entries[it] }
        col.body(getString(R.string.settings_acceleration_help), small = true)

        col.heading(getString(R.string.settings_cleanup))
        col.body(getString(R.string.settings_cleanup_help), small = true)
        col.radio(CleanupMode.entries.map { it.label }, s.cleanupMode.ordinal) {
            s.cleanupMode = CleanupMode.entries[it]
            app.releaseCleaner()
            renderLlm()
        }
        llmBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(llmBox)
    }

    override fun onResume() {
        super.onResume()
        Downloads.addListener(refresh)
        renderLlm()
    }

    override fun onPause() {
        Downloads.removeListener(refresh)
        if (app.settings.vocabularyText != vocabulary.text.toString()) {
            app.settings.vocabularyText = vocabulary.text.toString()
            app.releaseCleaner()
        }
        super.onPause()
    }

    private fun renderLlm() {
        llmBox.removeAllViews()
        if (app.settings.cleanupMode != CleanupMode.LLM) return
        val selected = LlmCatalog.byId(app.settings.llmModelId) ?: LlmCatalog.qwen15
        for (spec in LlmCatalog.all) {
            val card = llmBox.card()
            val installed = app.store.isInstalled(spec)
            card.addView(RadioButton(this).apply {
                text = spec.displayName
                isChecked = spec == selected
                setOnClickListener { app.settings.llmModelId = spec.id; app.releaseCleaner(); renderLlm() }
            })
            val st = Downloads.status[spec.id]
            card.body(
                when {
                    Downloads.isBusy(spec.id) && st != null ->
                        getString(R.string.setup_downloading, st.percent, size(st.downloaded), size(st.total))
                    st?.error != null -> getString(R.string.setup_download_error, st.error)
                    installed -> getString(R.string.setup_installed, size(spec.file.size))
                    else -> getString(R.string.setup_not_installed, size(spec.file.size))
                },
                small = true,
            )
            card.row().apply {
                when {
                    Downloads.isBusy(spec.id) -> button(getString(R.string.cancel), outlined = true) { Downloads.cancel(spec.id) }
                    installed -> button(getString(R.string.delete), outlined = true) {
                        app.releaseCleaner(); app.store.delete(spec); renderLlm()
                    }
                    else -> button(getString(R.string.download)) {
                        Downloads.start(this@SettingsActivity, spec, getString(R.string.setup_downloading_label, spec.displayName))
                        renderLlm()
                    }
                }
            }
        }
        llmBox.body(getString(R.string.settings_llm_note), small = true)
    }

    private fun ViewGroup.switch(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
        addView(MaterialSwitch(context).apply {
            text = label
            isChecked = value
            setOnCheckedChangeListener { _, v -> onChange(v) }
        })
    }

    private fun ViewGroup.radio(labels: List<String>, selected: Int, onChange: (Int) -> Unit) {
        val group = RadioGroup(context)
        labels.forEachIndexed { i, l ->
            group.addView(RadioButton(context).apply { id = 1000 + i; text = l })
        }
        group.check(1000 + selected)
        group.setOnCheckedChangeListener { _, id -> onChange(id - 1000) }
        addView(group)
    }

    private fun ViewGroup.slider(
        label: String, from: Float, to: Float, step: Float, value: Float,
        format: (Float) -> String, onChange: (Float) -> Unit,
    ) {
        val text = body("$label : ${format(value)}")
        addView(Slider(context).apply {
            valueFrom = from
            valueTo = to
            stepSize = step
            this.value = (Math.round(value.coerceIn(from, to) / step) * step).coerceIn(from, to)
            setLabelFormatter { format(it) }
            addOnChangeListener { _, v, fromUser ->
                text.text = "$label : ${format(v)}"
                if (fromUser) onChange(v)
            }
        })
    }
}
