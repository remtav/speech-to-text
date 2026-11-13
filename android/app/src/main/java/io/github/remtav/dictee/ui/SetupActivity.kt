package io.github.remtav.dictee.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.github.remtav.dictee.DicteeApp
import io.github.remtav.dictee.R
import io.github.remtav.dictee.stt.DictationLanguage
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.ModelFamily
import io.github.remtav.dictee.stt.ServerConsent
import io.github.remtav.dictee.stt.ModelMigration
import io.github.remtav.dictee.stt.ModelVisibility
import io.github.remtav.dictee.stt.ModelRecommendation
import io.github.remtav.dictee.stt.ModelRecommendations
import io.github.remtav.dictee.stt.ModelSpec
import io.github.remtav.dictee.stt.asDownloadable

/**
 * Launcher screen: enable the keyboard, grant the microphone (an IME cannot
 * ask for it itself), and manage the models (download, delete, choose).
 */
class SetupActivity : AppCompatActivity() {
    private lateinit var app: DicteeApp
    private lateinit var keyboardStatus: TextView
    private lateinit var micStatus: TextView
    private lateinit var modelsBox: LinearLayout
    private lateinit var languagesBox: LinearLayout
    private lateinit var participationBox: LinearLayout
    /** Whether the participation card is drawn now; null before the first draw. It is redrawn only when this changes. */
    private var participationShown: Boolean? = null
    // A download that ends can complete the setup: `maybeOpenHowTo` checks it.
    private val refresh: () -> Unit = { renderModels(); maybeOpenHowTo(); renderParticipation() }

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { renderSteps() }
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = DicteeApp.from(this)
        val col = scrollingColumn()
        col.title(getString(R.string.app_name))
        col.body(getString(R.string.setup_intro))
        col.button(getString(R.string.setup_howto), outlined = true) {
            startActivity(Intent(this@SetupActivity, HowToActivity::class.java))
        }.tag = HOWTO_BUTTON_TAG

        col.heading(getString(R.string.setup_step_keyboard))
        keyboardStatus = col.body()
        col.row().apply {
            button(getString(R.string.setup_open_ime_settings)) {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }.spaced()
            button(getString(R.string.setup_pick_ime), outlined = true) {
                getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            }
        }

        col.heading(getString(R.string.setup_step_mic))
        micStatus = col.body()
        col.button(getString(R.string.setup_grant_mic)) { askMic.launch(Manifest.permission.RECORD_AUDIO) }

        col.heading(getString(R.string.setup_step_languages))
        col.body(getString(R.string.setup_languages_help), small = true)
        languagesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(languagesBox)

        col.heading(getString(R.string.setup_models))
        col.body(getString(R.string.setup_models_note), small = true)
        modelsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(modelsBox)

        // The reading-test card: at the end of the setup, Québec French chosen, not answered no, not closed.
        participationBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(participationBox)

        col.heading(getString(R.string.setup_more))
        col.row().apply {
            button(getString(R.string.setup_compare)) { startActivity(Intent(this@SetupActivity, CompareActivity::class.java)) }.spaced()
            button(getString(R.string.setup_settings), outlined = true) {
                startActivity(Intent(this@SetupActivity, SettingsActivity::class.java))
            }
        }

        col.heading(getString(R.string.setup_try))
        col.addView(EditText(this).apply {
            hint = getString(R.string.setup_try_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
        })
    }

    override fun onResume() {
        super.onResume()
        Downloads.addListener(refresh)
        renderSteps()
        // Redrawn on each resume: the languages may have been changed in the settings.
        languagesBox.renderLanguageChoices(app.settings) { renderModels(); renderParticipation() }
        renderModels()
        renderParticipation()
    }

    override fun onPause() {
        Downloads.removeListener(refresh)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) renderSteps() // back from the IME picker
    }

    private fun renderSteps() {
        // Called again on resume, on focus and when a download ends: `maybeOpenHowTo` opens once.
        val imm = getSystemService(InputMethodManager::class.java)
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        keyboardStatus.text = when {
            current.startsWith("$packageName/") -> getString(R.string.setup_keyboard_current)
            enabled -> getString(R.string.setup_keyboard_enabled)
            else -> getString(R.string.setup_keyboard_disabled)
        }
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        micStatus.text = getString(if (mic) R.string.setup_mic_ok else R.string.setup_mic_missing)
        maybeOpenHowTo()
        renderParticipation()
    }

    /** Draws or removes the participation card; touching nothing when it is already as it should be (downloads call this often). */
    private fun renderParticipation() {
        val show = app.settings.participation.cardOnSetup(setupComplete())
        if (show == participationShown) return
        participationShown = show
        participationBox.removeAllViews()
        if (show) {
            participationBox.participationCard(
                onStart = { startActivity(Intent(this, ReadingTestActivity::class.java)) },
                onDismiss = {
                    app.settings.participationCardDismissed = true
                    renderParticipation()
                },
            )
        }
    }

    private fun setupComplete(): Boolean {
        // Only a model the keyboard can use counts (a hidden model's files may still be on the phone).
        val modelReady = (ModelCatalog.listed(app.settings.languages) + ModelCatalog.remote).any { app.isInstalled(it) }
        return SetupProgress.isComplete(isKeyboardEnabled(), isMicGranted(), modelReady)
    }

    /**
     * Opens "Comment dicter" once, when the setup is complete (keyboard enabled, microphone
     * allowed, a model ready: see [SetupProgress]). The screen marks itself as seen, but the flag
     * is also set here before starting it, so a second call before it appears cannot open it twice.
     */
    private fun maybeOpenHowTo() {
        if (SetupProgress.shouldOpenHowTo(setupComplete(), app.settings.howToShown)) {
            app.settings.howToShown = true
            startActivity(Intent(this, HowToActivity::class.java))
        }
    }

    private fun isKeyboardEnabled() =
        getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == packageName }

    private fun isMicGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun renderModels() {
        modelsBox.removeAllViews()
        val chosen = app.settings.languages
        val recommendation = ModelRecommendations.recommend(chosen)
        renderRetiredNotice()
        // Issue #22: only the models offered for the chosen languages (experimental ones are in the settings).
        for (spec in ModelCatalog.offered(chosen)) renderModel(spec, chosen, recommendation)
        renderRemote()
    }

    /**
     * The one-time notice after the active model was replaced ([DicteeApp.migrateModels]): an invitation to
     * download Whisper turbo when it is missing, and a button to delete a hidden model's files. Nothing is
     * deleted without that click; closing the notice keeps the files.
     */
    private fun renderRetiredNotice() {
        val retired = ModelCatalog.byId(app.settings.retiredModelNotice) ?: return
        val replacement = ModelMigration.replacement
        val card = modelsBox.card()
        card.tag = RETIRED_NOTICE_TAG
        card.heading(getString(R.string.model_notice_title))
        card.body(getString(R.string.model_notice_replaced, retired.displayName, replacement.displayName))
        val missing = !app.store.isInstalled(replacement) && !Downloads.isBusy(replacement.id)
        if (missing) card.body(getString(R.string.model_notice_download, replacement.displayName), small = true)
        val deletable = retired.visibility == ModelVisibility.HIDDEN && app.store.isInstalled(retired)
        if (missing) {
            card.button(getString(R.string.model_notice_download_button, replacement.displayName)) {
                startDownload(replacement)
            }.tag = RETIRED_DOWNLOAD_TAG
        }
        if (deletable) {
            card.button(
                getString(R.string.model_notice_delete_button, retired.displayName, size(app.store.installedBytes(retired.asDownloadable()))),
                outlined = true,
            ) {
                if (app.engines.current()?.spec?.id == retired.id) app.engines.release()
                app.store.delete(retired)
                Toast.makeText(this, getString(R.string.model_notice_deleted, retired.displayName), Toast.LENGTH_SHORT).show()
                renderModels()
            }.tag = RETIRED_DELETE_TAG
        }
        card.button(getString(R.string.model_notice_ok), outlined = true) {
            app.settings.retiredModelNotice = ""
            renderModels()
        }.tag = RETIRED_OK_TAG
    }

    /**
     * The user's server (issue #23): the default as soon as its address is set in the settings (after the
     * consent screen); picking a phone model above means « Toujours transcrire sur le téléphone ».
     */
    private fun renderRemote() {
        val spec = ModelCatalog.remote
        val card = modelsBox.card()
        val config = app.settings.remoteConfig()
        card.addView(RadioButton(this).apply {
            text = spec.displayName
            isChecked = app.shownSpec().id == spec.id
            isEnabled = config.isConfigured
            setOnClickListener {
                app.settings.alwaysLocal = false
                // A refusal is asked again at the next dictation, never turned into consent here.
                if (app.settings.serverConsent == ServerConsent.REFUSED) app.settings.serverConsent = ServerConsent.UNASKED
                renderModels()
            }
        })
        card.body(spec.description, small = true)
        card.body(
            if (config.isConfigured) getString(R.string.setup_remote_configured, config.normalizedBase())
            else getString(R.string.setup_remote_not_configured),
            small = true,
        )
        card.row().apply {
            button(getString(R.string.setup_remote_configure), outlined = true) {
                startActivity(Intent(this@SetupActivity, SettingsActivity::class.java))
            }
        }
    }

    private fun renderModel(spec: ModelSpec, chosen: Set<DictationLanguage>, recommendation: ModelRecommendation) {
        val card = modelsBox.card()
        card.tag = modelCardTag(spec)
        val installed = app.store.isInstalled(spec)
        val active = app.settings.activeModelId == spec.id && app.shownSpec().family != ModelFamily.REMOTE
        card.addView(RadioButton(this).apply {
            text = buildString {
                append(spec.displayName)
                if (spec == ModelCatalog.default) append(' ').append(getString(R.string.setup_default_suffix).trim())
            }
            isChecked = active
            isEnabled = installed
            setOnClickListener {
                app.settings.activeModelId = spec.id
                if (app.settings.remoteConfig().isConfigured) app.settings.alwaysLocal = true
                renderModels()
            }
        })
        if (spec == recommendation.model) {
            card.badge(getString(R.string.model_recommended_badge), R.id.recommended_badge)
            card.body(recommendation.reason, small = true)
        }
        val uncovered = ModelRecommendations.uncovered(spec, chosen)
        if (uncovered.isNotEmpty()) {
            card.warning(
                getString(R.string.model_warning_uncovered, uncovered.joinToString(", ") { it.inSentence }),
                WARNING_TAG,
            )
        }
        card.body("${spec.publisher} · ${spec.description}", small = true)
        if (spec.note.isNotEmpty()) card.body(spec.note, small = true).tag = modelNoteTag(spec)
        card.body(getString(R.string.model_license_runs_local, spec.license), small = true)
        val status = Downloads.status[spec.id]
        val failed = !Downloads.isBusy(spec.id) && status?.error != null
        card.value(
            when {
                Downloads.isBusy(spec.id) && status != null ->
                    getString(R.string.setup_downloading, status.percent, size(status.downloaded), size(status.total))
                status?.error != null -> getString(R.string.setup_download_error, status.error)
                installed -> getString(R.string.setup_installed, size(app.store.installedBytes(spec.asDownloadable())))
                else -> getString(R.string.setup_not_installed, size(spec.totalBytes))
            },
        ).apply { if (failed) markError() }
        card.row().apply {
            when {
                Downloads.isBusy(spec.id) -> button(getString(R.string.cancel), outlined = true) { Downloads.cancel(spec.id) }
                installed -> button(getString(R.string.delete), outlined = true) { confirmDelete(spec) }
                else -> button(getString(R.string.download)) { startDownload(spec) }
            }
        }
    }

    private fun startDownload(spec: ModelSpec) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Downloads.start(this, spec.asDownloadable(), getString(R.string.setup_downloading_label, spec.displayName))
        renderModels()
    }

    private fun confirmDelete(spec: ModelSpec) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_title, spec.displayName))
            .setMessage(getString(R.string.delete_message))
            .setPositiveButton(R.string.delete) { _, _ ->
                if (app.engines.current()?.spec?.id == spec.id) app.engines.release()
                app.store.delete(spec)
                renderModels()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        /** Tag of a model card, for tests. */
        fun modelCardTag(spec: ModelSpec) = "model_card_${spec.id}"
        /** Tag of a model's note (ModelSpec.note), for tests. */
        fun modelNoteTag(spec: ModelSpec) = "model_note_${spec.id}"
        const val WARNING_TAG = "model_warning"
        const val HOWTO_BUTTON_TAG = "setup_howto_button"
        const val RETIRED_NOTICE_TAG = "retired_model_notice"
        const val RETIRED_DOWNLOAD_TAG = "retired_model_download"
        const val RETIRED_DELETE_TAG = "retired_model_delete"
        const val RETIRED_OK_TAG = "retired_model_ok"
    }
}
