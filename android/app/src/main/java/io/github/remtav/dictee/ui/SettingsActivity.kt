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
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.RemoteApi
import io.github.remtav.dictee.stt.RemoteEngine
import java.util.Locale

/** Dictation preferences (lots 2 and 3). Every optional feature can be turned off. */
class SettingsActivity : AppCompatActivity() {
    private lateinit var app: DicteeApp
    private lateinit var vocabulary: EditText
    private lateinit var remoteUrl: EditText
    private lateinit var remoteKey: EditText
    private lateinit var remoteModel: EditText
    private lateinit var cfClientId: EditText
    private lateinit var cfClientSecret: EditText
    private lateinit var remoteStatus: android.widget.TextView
    private lateinit var llmBox: LinearLayout
    /** Redraws the server-language choice; set once that section exists. */
    private var refreshRemoteLanguage: () -> Unit = {}
    private val refresh: () -> Unit = { renderLlm() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = DicteeApp.from(this)
        val s = app.settings
        val col = scrollingColumn()
        col.title(getString(R.string.settings_title))

        col.heading(getString(R.string.settings_languages))
        col.body(getString(R.string.setup_languages_help), small = true)
        LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also {
            col.addView(it)
            // The server language follows the languages until set explicitly: keep its radio in step.
            it.renderLanguageChoices(s) { refreshRemoteLanguage() }
        }

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

        buildRemoteSection(col)

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
        saveRemote()
        if (app.settings.vocabularyText != vocabulary.text.toString()) {
            app.settings.vocabularyText = vocabulary.text.toString()
            app.releaseCleaner()
        }
        super.onPause()
    }

    private fun buildRemoteSection(col: LinearLayout) {
        val s = app.settings
        col.heading(getString(R.string.settings_remote))
        col.body(getString(R.string.settings_remote_help), small = true)
        fun field(hintText: String, value: String, type: Int) = EditText(this).apply {
            hint = hintText
            setText(value)
            inputType = type
            isSingleLine = true
        }.also { col.addView(it) }
        remoteUrl = field("http://192.168.1.10:8081", s.remoteUrl,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        col.body(getString(R.string.settings_remote_api), small = true)
        col.radio(RemoteApi.entries.map { it.label }, s.remoteApi.ordinal) { s.remoteApi = RemoteApi.entries[it] }
        remoteModel = field(getString(R.string.settings_remote_model_hint), s.remoteModel, InputType.TYPE_CLASS_TEXT)
        remoteKey = field(getString(R.string.settings_remote_key_hint), s.remoteApiKey,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        col.body(getString(R.string.settings_cf_access), small = false)
        col.body(getString(R.string.settings_cf_access_help), small = true)
        cfClientId = field(getString(R.string.settings_cf_id_hint), s.cfAccessClientId,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        // A password field: the secret is never shown in clear (nor in screenshots).
        cfClientSecret = field(getString(R.string.settings_cf_secret_hint), s.cfAccessClientSecret,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        col.body(getString(R.string.settings_remote_language), small = true)
        val langs = listOf("" to getString(R.string.settings_remote_lang_auto), "fr" to "Français", "en" to "English")
        val remoteLanguageIndex = { langs.indexOfFirst { it.first == s.remoteLanguage }.coerceAtLeast(0) }
        val onRemoteLanguage: (Int) -> Unit = { s.remoteLanguage = langs[it].first }
        val remoteLanguageGroup = col.radio(langs.map { it.second }, remoteLanguageIndex(), onRemoteLanguage)
        refreshRemoteLanguage = { remoteLanguageGroup.checkSilently(remoteLanguageIndex(), onRemoteLanguage) }
        col.body(getString(R.string.settings_remote_fallback), small = true)
        val fallbacks = listOf("" to getString(R.string.settings_remote_no_fallback)) +
            ModelCatalog.all.map { it.id to it.displayName }
        col.radio(fallbacks.map { it.second }, fallbacks.indexOfFirst { it.first == s.remoteFallbackId }.coerceAtLeast(0)) {
            s.remoteFallbackId = fallbacks[it].first
            if (::remoteStatus.isInitialized) updateRemoteWarning()
        }
        col.row().apply {
            button(getString(R.string.settings_remote_test), outlined = true) { testRemote() }.spaced()
            button(getString(R.string.settings_diagnostic), outlined = true) {
                saveRemote()
                startActivity(android.content.Intent(this@SettingsActivity, DiagnosticActivity::class.java))
            }
        }
        remoteStatus = col.body("", small = true)
        updateRemoteWarning()
    }

    private fun saveRemote() {
        val s = app.settings
        s.remoteUrl = remoteUrl.text.toString()
        s.remoteApiKey = remoteKey.text.toString()
        s.remoteModel = remoteModel.text.toString()
        s.cfAccessClientId = cfClientId.text.toString()
        s.cfAccessClientSecret = cfClientSecret.text.toString()
        // Using the server needs a valid address; never leave it selected without one.
        if (s.activeModel.family == io.github.remtav.dictee.stt.ModelFamily.REMOTE && !s.remoteConfig().isConfigured) {
            s.activeModelId = ModelCatalog.default.id
        }
    }

    private fun updateRemoteWarning() {
        val c = app.settings.remoteConfig()
        val lines = listOfNotNull(
            when {
                !c.isConfigured && app.settings.remoteUrl.isNotBlank() -> getString(R.string.settings_remote_invalid)
                c.isInsecurePublic -> getString(R.string.settings_remote_insecure)
                else -> null
            },
            // Without an installed local model, a server failure loses the dictation.
            if (c.isConfigured && ModelCatalog.byId(app.settings.remoteFallbackId)?.let { app.isInstalled(it) } != true) {
                getString(R.string.settings_remote_no_fallback_warning)
            } else null,
        )
        remoteStatus.text = lines.joinToString("\n")
        remoteStatus.markError(lines.isNotEmpty())
    }

    private fun testRemote() {
        saveRemote()
        remoteStatus.text = getString(R.string.settings_remote_testing)
        remoteStatus.markError(false)
        val config = app.settings.remoteConfig()
        kotlin.concurrent.thread(name = "remote-check") {
            val r = RemoteEngine.check(config)
            runOnUiThread {
                remoteStatus.text = r.fold(
                    { getString(R.string.settings_remote_ok, it) },
                    { getString(R.string.settings_remote_failed, it.message) },
                ) + if (config.isInsecurePublic) "\n" + getString(R.string.settings_remote_insecure) else ""
                remoteStatus.markError(r.isFailure || config.isInsecurePublic)
            }
        }
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
            ).apply { if (!Downloads.isBusy(spec.id) && st?.error != null) markError() }
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

    private fun ViewGroup.radio(labels: List<String>, selected: Int, onChange: (Int) -> Unit): RadioGroup {
        val group = RadioGroup(context)
        labels.forEachIndexed { i, l ->
            group.addView(RadioButton(context).apply { id = 1000 + i; text = l })
        }
        group.check(1000 + selected)
        group.setOnCheckedChangeListener { _, id -> onChange(id - 1000) }
        addView(group)
        return group
    }

    /** Checks [index] without running the change callback (it would store the value as an explicit choice). */
    private fun RadioGroup.checkSilently(index: Int, onChange: (Int) -> Unit) {
        setOnCheckedChangeListener(null)
        check(1000 + index)
        setOnCheckedChangeListener { _, id -> onChange(id - 1000) }
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
