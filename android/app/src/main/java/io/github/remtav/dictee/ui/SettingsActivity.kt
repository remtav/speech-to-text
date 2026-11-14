package io.github.remtav.dictee.ui

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import io.github.remtav.dictee.DicteeApp
import io.github.remtav.dictee.R
import io.github.remtav.dictee.cleanup.LlmCatalog
import io.github.remtav.dictee.reading.Declaration
import io.github.remtav.dictee.settings.CleanupMode
import io.github.remtav.dictee.ime.LayoutKind
import io.github.remtav.dictee.settings.ThemeMode
import io.github.remtav.dictee.stt.Acceleration
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.ModelVisibility
import io.github.remtav.dictee.stt.RemoteApi
import io.github.remtav.dictee.stt.RemoteEngine
import io.github.remtav.dictee.stt.RepetitionFix
import io.github.remtav.dictee.stt.TranscriptionMode
import io.github.remtav.dictee.stt.ServerTimeout
import io.github.remtav.dictee.stt.WhisperTuning
import io.github.remtav.dictee.stt.asDownloadable
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
    private lateinit var participationBox: LinearLayout
    /** « Avancé » section (issue #49): generic, other advanced settings are added to it (#37). */
    private lateinit var advancedBox: LinearLayout
    /** The « Avancé » models (Parakeet) and the files of withdrawn models (issue #72). */
    private lateinit var otherModelsBox: LinearLayout
    /** « Serveur distant, pour essais (développeurs) », folded inside « Avancé » (issue #57). */
    private lateinit var serverSection: Collapsible
    /** Redraws the server-language choice; set once that section exists. */
    private var refreshRemoteLanguage: () -> Unit = {}
    private val refresh: () -> Unit = { renderLlm(); renderAdvancedModels() }

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
            it.renderLanguageChoices(s) {
                refreshRemoteLanguage()
                // The reading-test offer follows the languages (Québec French).
                if (::participationBox.isInitialized) renderParticipation()
            }
        }

        // Issue #36: the quick test can be done again from here. Issue #72: hidden (QuickTestActivity.REACHABLE).
        if (QuickTestActivity.REACHABLE) {
            col.button(getString(R.string.quick_open), outlined = true) {
                startActivity(android.content.Intent(this, QuickTestActivity::class.java))
            }.tag = QUICK_TEST_TAG
        }

        participationBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(participationBox)
        renderParticipation()

        col.heading(getString(R.string.settings_dictation))
        col.switch(getString(R.string.settings_partials), s.partials) { s.partials = it }
        col.body(getString(R.string.settings_partials_help), small = true)
        col.switch(getString(R.string.settings_commands), s.voiceCommands) { s.voiceCommands = it }
        col.body(getString(R.string.settings_commands_help), small = true)
        col.slider(getString(R.string.settings_pause), 0.3f, 1.5f, 0.1f, s.pauseSeconds, { "%.1f s".format(Locale.ROOT, it) }) {
            s.pauseSeconds = it
        }

        col.heading(getString(R.string.settings_vocabulary)).tag = VOCABULARY_TAG
        col.body(getString(R.string.settings_vocabulary_help), small = true)
        vocabulary = EditText(this).apply {
            setText(s.vocabularyText)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 4
            gravity = Gravity.TOP
            hint = "Tremblay\nwifi -> Wi-Fi"
        }
        col.addView(vocabulary)

        col.heading(getString(R.string.settings_feedback))
        col.radio(ThemeMode.entries.map { it.label }, s.theme.ordinal) {
            s.theme = ThemeMode.entries[it]
            app.applyTheme()
        }
        col.switch(getString(R.string.settings_haptics), s.haptics) { s.haptics = it }
        col.body(getString(R.string.settings_layout), small = false)
        col.radio(LayoutKind.entries.map { it.label }, s.keyboardLayout.ordinal) { s.keyboardLayout = LayoutKind.entries[it] }
        col.body(getString(R.string.settings_layout_help), small = true)



        col.heading(getString(R.string.settings_cleanup))
        col.body(getString(R.string.settings_cleanup_help), small = true)
        col.radio(CleanupMode.entries.map { it.label }, s.cleanupMode.ordinal) {
            s.cleanupMode = CleanupMode.entries[it]
            app.releaseCleaner()
            renderLlm()
        }
        llmBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(llmBox)

        // Issue #49: generic « Avancé » section, last on the screen; the model choices (issue #37) and the server
        // (issue #57) are folded in it.
        advancedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = ADVANCED_TAG }
        col.addView(advancedBox)
        advancedBox.heading(getString(R.string.settings_advanced))
        advancedBox.body(getString(R.string.settings_advanced_help), small = true)
        buildWhisperAdvanced(advancedBox)
        // Issue #37: the technical model choices and the performance settings, folded, in one block.
        buildModelsAdvanced(advancedBox.collapsible(getString(R.string.settings_models_section), MODELS_SECTION_TAG, MODELS_CONTENT_TAG).content)
        // Issue #57: the remote server, a developer setting, folded at opening, last in « Avancé ».
        serverSection = advancedBox.collapsible(getString(R.string.settings_server_section), SERVER_SECTION_TAG, SERVER_CONTENT_TAG)
        buildRemoteSection(serverSection.content)

        // Opened from the server line of the main screen: unfold the server settings and show the mode.
        if (intent.getBooleanExtra(EXTRA_SCROLL_TO_SERVER_MODE, false)) {
            serverSection.expand(true)
            col.post {
                val heading = col.findViewWithTag<View>(SERVER_MODE_TAG)
                (col.parent as? ScrollView)?.scrollTo(0, heading?.let { topWithin(col, it) } ?: 0)
            }
        }
        // Opened from the "Comment dicter" screen: show the vocabulary straight away.
        if (intent.getBooleanExtra(EXTRA_SCROLL_TO_VOCABULARY, false)) {
            col.post {
                val heading = col.findViewWithTag<View>(VOCABULARY_TAG)
                (col.parent as? ScrollView)?.scrollTo(0, heading?.top ?: 0)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Downloads.addListener(refresh)
        renderLlm()
        renderAdvancedModels()
        // Back from the reading test, where the question may have been answered.
        renderParticipation()
    }

    /**
     * The reading-test section (issue #15, lot 4): the answer to « Parlez-vous un français québécois ? »,
     * which can be corrected here, and the participation card (kept here even if closed on the main screen).
     */
    private fun renderParticipation() {
        participationBox.removeAllViews()
        val s = app.settings
        val p = s.participation
        if (!p.declarationInSettings && !p.cardInSettings) return
        participationBox.heading(getString(R.string.settings_participation))
        if (p.declarationInSettings) {
            participationBox.body(getString(R.string.settings_participation_answer), small = true)
            val group = RadioGroup(this)
            val yes = RadioButton(this).apply {
                id = View.generateViewId(); tag = PARTICIPATION_YES_TAG
                text = getString(R.string.settings_participation_yes)
                isChecked = p.declaration == Declaration.YES
            }
            val no = RadioButton(this).apply {
                id = View.generateViewId(); tag = PARTICIPATION_NO_TAG
                text = getString(R.string.settings_participation_no)
                isChecked = p.declaration == Declaration.NO
            }
            group.addView(yes)
            group.addView(no)
            group.setOnCheckedChangeListener { _, checkedId ->
                s.quebecDeclaration = if (checkedId == yes.id) Declaration.YES else Declaration.NO
                // Redrawn after the click is handled: the card comes back after "oui", goes after "non".
                participationBox.post { renderParticipation() }
            }
            participationBox.addView(group)
            when (p.declaration) {
                Declaration.UNANSWERED -> participationBox.body(getString(R.string.settings_participation_unanswered), small = true)
                Declaration.NO -> participationBox.body(getString(R.string.settings_participation_excluded), small = true)
                    .tag = PARTICIPATION_EXCLUDED_TAG
                Declaration.YES -> Unit
            }
        }
        if (p.cardInSettings) {
            participationBox.participationCard(
                onStart = { startActivity(android.content.Intent(this, ReadingTestActivity::class.java)) },
                onDismiss = null,
            )
        }
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
        col.body(getString(R.string.settings_server_section_help), small = true)
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
        col.switch(getString(R.string.settings_remote_polish), s.remotePolish) { s.remotePolish = it }
        col.body(getString(R.string.settings_remote_polish_help), small = true)
        // Issue #23: the mode (server / phone). Choosing "server" here, under a text that says what happens to the
        // audio, is the consent, and this is the only place where it can be chosen (issue #57); not chosen yet = phone.
        col.heading(getString(R.string.settings_server_use)).tag = SERVER_MODE_TAG
        col.body(getString(R.string.settings_server_mode_help), small = true)
        val modeGroup = RadioGroup(this)
        val serverButton = RadioButton(this).apply {
            id = View.generateViewId(); tag = MODE_SERVER_TAG
            text = getString(R.string.settings_mode_server)
            isChecked = s.transcriptionMode == TranscriptionMode.SERVER
        }
        val phoneButton = RadioButton(this).apply {
            id = View.generateViewId(); tag = MODE_PHONE_TAG
            text = getString(R.string.settings_mode_phone)
            isChecked = s.transcriptionMode == TranscriptionMode.PHONE
        }
        modeGroup.addView(serverButton)
        modeGroup.addView(phoneButton)
        modeNote = col.body("", small = true)
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            s.transcriptionMode = if (checkedId == serverButton.id) TranscriptionMode.SERVER else TranscriptionMode.PHONE
            updateModeNote()
        }
        col.addView(modeGroup, col.indexOfChild(modeNote))
        updateModeNote()
        col.slider(getString(R.string.settings_server_timeout), ServerTimeout.MIN_SECONDS.toFloat(), 30f, 1f,
            s.serverTimeoutSeconds.toFloat(), { "${it.toInt()} s" }) { s.serverTimeoutSeconds = it.toInt() }
        col.body(getString(R.string.settings_server_timeout_help, s.activeModel.displayName), small = true)
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

    /**
     * Whisper's padding (issue #15) and, since issue #49, its « Automatique » choice and the repetition
     * correction, which only applies in automatic mode (greyed out otherwise).
     */
    private fun buildWhisperAdvanced(box: LinearLayout) {
        val s = app.settings
        box.body(getString(R.string.settings_whisper_tail), small = false)
        val tailLabels = listOf(
            R.string.settings_whisper_tail_1000, R.string.settings_whisper_tail_600, R.string.settings_whisper_tail_300,
            R.string.settings_whisper_tail_auto,
        ).map { getString(it) }
        // Fixed paddings first (TAIL_PADDING_CHOICES order), then « Automatique ».
        val autoIndex = WhisperTuning.TAIL_PADDING_CHOICES.size
        val tailIndex = if (s.whisperTailAuto) autoIndex else WhisperTuning.TAIL_PADDING_CHOICES.indexOf(s.whisperTailPaddings).coerceAtLeast(0)
        lateinit var fixGroup: RadioGroup
        lateinit var fixNote: android.widget.TextView
        val renderFix = {
            for (i in 0 until fixGroup.childCount) fixGroup.getChildAt(i).isEnabled = s.whisperTailAuto
            fixNote.visibility = if (s.whisperTailAuto) View.GONE else View.VISIBLE
        }
        box.radio(tailLabels, tailIndex) {
            if (it == autoIndex) {
                // The stored fixed value is kept: it comes back if the user leaves « Automatique ».
                s.whisperTailAuto = true
            } else {
                s.whisperTailAuto = false
                s.whisperTailPaddings = WhisperTuning.TAIL_PADDING_CHOICES[it]
            }
            renderFix()
        }.tag = WHISPER_TAIL_TAG
        box.body(getString(R.string.settings_whisper_tail_help), small = true)
        box.body(getString(R.string.settings_whisper_repetition), small = false)
        val fixes = listOf(RepetitionFix.REDECODE, RepetitionFix.CUT)
        val fixLabels = listOf(R.string.settings_whisper_repetition_redecode, R.string.settings_whisper_repetition_cut).map { getString(it) }
        fixGroup = box.radio(fixLabels, fixes.indexOf(s.whisperRepetitionFix).coerceAtLeast(0)) {
            s.whisperRepetitionFix = fixes[it]
        }
        fixGroup.tag = WHISPER_REPETITION_TAG
        fixNote = box.body(getString(R.string.settings_whisper_repetition_inactive), small = true)
        box.body(getString(R.string.settings_whisper_repetition_help), small = true)
        renderFix()
    }

    /**
     * « Modèles et performances » (issue #37): the technical names of the phone models, the comparison, the
     * the « Avancé » models (issue #72) and the performance settings, moved here from the first level.
     */
    private fun buildModelsAdvanced(box: LinearLayout) {
        val s = app.settings
        box.body(getString(R.string.settings_models_names, ModelCatalog.whisperTurbo.displayName, ModelCatalog.whisperTurbo.publisher), small = true)
        box.button(getString(R.string.setup_compare), outlined = true) {
            startActivity(android.content.Intent(this, CompareActivity::class.java))
        }.tag = COMPARE_TAG
        // Issue #72: Parakeet is listed here (and in the comparison), never on the main screen.
        otherModelsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(otherModelsBox)
        renderAdvancedModels()
        box.heading(getString(R.string.settings_performance))
        box.slider(getString(R.string.settings_keep_loaded), 0f, 30f, 1f, s.keepLoadedMinutes.toFloat(), { "${it.toInt()} min" }) {
            s.keepLoadedMinutes = it.toInt()
        }
        box.slider(getString(R.string.settings_threads), 1f, 8f, 1f, s.threads.toFloat(), { "${it.toInt()}" }) {
            s.threads = it.toInt()
        }
        box.body(getString(R.string.settings_acceleration), small = false)
        box.radio(Acceleration.entries.map { it.label }, s.acceleration.ordinal) { s.acceleration = Acceleration.entries[it] }
        box.body(getString(R.string.settings_acceleration_help), small = true)
    }

    private fun saveRemote() {
        val s = app.settings
        s.remoteUrl = remoteUrl.text.toString()
        s.remoteApiKey = remoteKey.text.toString()
        s.remoteModel = remoteModel.text.toString()
        s.cfAccessClientId = cfClientId.text.toString()
        s.cfAccessClientSecret = cfClientSecret.text.toString()
    }

    private lateinit var modeNote: android.widget.TextView

    /** Why dictations stay on the phone despite the choice (no address) or for lack of one. */
    private fun updateModeNote() {
        val s = app.settings
        val text = when {
            s.transcriptionMode == TranscriptionMode.UNSET -> getString(R.string.settings_mode_unset)
            s.transcriptionMode == TranscriptionMode.SERVER && !s.remoteConfig().isConfigured -> getString(R.string.settings_mode_no_address)
            else -> ""
        }
        modeNote.text = text
        modeNote.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
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
            if (c.isConfigured && !app.isInstalled(app.settings.activeModel)) {
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

    /** One card per « Avancé » model (Parakeet, issue #72): choose it, download it, delete it. */
    private fun renderAdvancedModels() {
        otherModelsBox.removeAllViews()
        renderWithdrawnFiles()
        if (ModelCatalog.advanced.isEmpty()) return
        otherModelsBox.heading(getString(R.string.settings_advanced_models))
        otherModelsBox.body(getString(R.string.settings_advanced_models_help), small = true)
        for (spec in ModelCatalog.advanced) {
            val card = otherModelsBox.card()
            card.tag = advancedCardTag(spec)
            val installed = app.store.isInstalled(spec)
            card.addView(RadioButton(this).apply {
                text = spec.displayName
                isChecked = app.settings.activeModelId == spec.id
                isEnabled = installed
                setOnClickListener { app.settings.activeModelId = spec.id; renderAdvancedModels() }
            })
            card.body("${spec.publisher} · ${spec.description}", small = true)
            if (spec.note.isNotEmpty()) card.body(spec.note, small = true)
            card.body(getString(R.string.model_license_runs_local, spec.license), small = true)
            val st = Downloads.status[spec.id]
            card.body(
                when {
                    Downloads.isBusy(spec.id) && st != null ->
                        getString(R.string.setup_downloading, st.percent, size(st.downloaded), size(st.total))
                    st?.error != null -> getString(R.string.setup_download_error, st.error)
                    installed -> getString(R.string.setup_installed, size(app.store.installedBytes(spec.asDownloadable())))
                    else -> getString(R.string.setup_not_installed, size(spec.totalBytes))
                },
                small = true,
            ).apply { if (!Downloads.isBusy(spec.id) && st?.error != null) markError() }
            card.row().apply {
                when {
                    Downloads.isBusy(spec.id) -> button(getString(R.string.cancel), outlined = true) { Downloads.cancel(spec.id) }
                    installed -> button(getString(R.string.delete), outlined = true) {
                        if (app.engines.current()?.spec?.id == spec.id) app.engines.release()
                        app.store.delete(spec)
                        if (app.settings.activeModelId == spec.id) app.settings.activeModelId = ModelCatalog.default.id
                        renderAdvancedModels()
                    }
                    else -> button(getString(R.string.download)) {
                        Downloads.start(this@SettingsActivity, spec.asDownloadable(), getString(R.string.setup_downloading_label, spec.displayName))
                        renderAdvancedModels()
                    }
                }
            }
        }
    }

    /**
     * Issue #72 (review): the files of a withdrawn (hidden) model that is still on the phone, such as Lite-Whisper
     * downloaded and never made active, would otherwise have no way out: one line and one button each, technical name.
     */
    private fun renderWithdrawnFiles() {
        val left = ModelCatalog.all.filter { it.visibility == ModelVisibility.HIDDEN && app.store.isInstalled(it) }
        if (left.isEmpty()) return
        otherModelsBox.heading(getString(R.string.settings_old_models))
        for (spec in left) {
            val card = otherModelsBox.card()
            card.tag = oldModelCardTag(spec)
            card.body(spec.displayName, small = true)
            card.button(getString(R.string.settings_old_models_delete, size(app.store.installedBytes(spec.asDownloadable()))), outlined = true) {
                if (app.engines.current()?.spec?.id == spec.id) app.engines.release()
                app.store.delete(spec)
                renderAdvancedModels()
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

    private fun ViewGroup.switch(label: String, value: Boolean, onChange: (Boolean) -> Unit): MaterialSwitch =
        MaterialSwitch(context).apply {
            text = label
            isChecked = value
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }.also { addView(it) }

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

    companion object {
        /** Intent extra: scroll to the personal vocabulary on opening. */
        const val EXTRA_SCROLL_TO_VOCABULARY = "scroll_to_vocabulary"
        const val VOCABULARY_TAG = "settings_vocabulary"
        /** Intent extra: scroll to the server / phone choice on opening (issue #23). */
        const val EXTRA_SCROLL_TO_SERVER_MODE = "scroll_to_server_mode"
        const val SERVER_MODE_TAG = "settings_server_mode"
        const val MODE_SERVER_TAG = "settings_mode_server"
        const val MODE_PHONE_TAG = "settings_mode_phone"
        const val PARTICIPATION_YES_TAG = "settings_participation_yes"
        const val PARTICIPATION_NO_TAG = "settings_participation_no"
        const val PARTICIPATION_EXCLUDED_TAG = "settings_participation_excluded"
        /** « Avancé » section and its Whisper settings (issue #49), for tests. */
        const val ADVANCED_TAG = "settings_advanced"
        const val WHISPER_TAIL_TAG = "settings_whisper_tail"
        const val WHISPER_REPETITION_TAG = "settings_whisper_repetition"
        /** Header and content of the folded server section inside « Avancé » (issue #57), for tests. */
        const val SERVER_SECTION_TAG = "settings_server_section"
        const val SERVER_CONTENT_TAG = "settings_server_content"
        /** « Modèles et performances » inside « Avancé » (issue #37), the comparison button, the quick test button. */
        const val MODELS_SECTION_TAG = "settings_models_section"
        const val MODELS_CONTENT_TAG = "settings_models_content"
        const val COMPARE_TAG = "settings_compare"
        const val QUICK_TEST_TAG = "settings_quick_test"
        /** Tag of an « Avancé » model's card, for tests. */
        /** Tag of the card of a withdrawn model's files, for tests. */
        fun oldModelCardTag(spec: io.github.remtav.dictee.stt.ModelSpec) = "old_model_card_${spec.id}"

        fun advancedCardTag(spec: io.github.remtav.dictee.stt.ModelSpec) = "advanced_card_${spec.id}"
    }
}
