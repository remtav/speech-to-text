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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.github.remtav.dictee.DicteeApp
import io.github.remtav.dictee.R
import io.github.remtav.dictee.TestHooks
import io.github.remtav.dictee.stt.DictationLanguage
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.ModelFamily
import io.github.remtav.dictee.stt.TranscriptionMode
import io.github.remtav.dictee.stt.ModelMigration
import io.github.remtav.dictee.stt.ModelVisibility
import io.github.remtav.dictee.stt.ModelRecommendation
import io.github.remtav.dictee.stt.ModelRecommendations
import io.github.remtav.dictee.stt.ModelSpec
import io.github.remtav.dictee.stt.ModelPlainNames
import io.github.remtav.dictee.stt.TranscriptionAdvisor
import io.github.remtav.dictee.stt.asDownloadable

/**
 * Launcher screen: enable and choose the keyboard (two steps, issue #55), grant the microphone (an IME cannot
 * ask for it itself), choose the languages and get a model. Everything happens on the phone (issue #57): the
 * remote server is a developer setting, in « Avancé » of the settings, and this screen only mentions it when the
 * person set it up there and dictations really go to it.
 *
 * One filled button at most (issue #55): the button of [SetupProgress.nextStep]; every other button is outlined.
 */
class SetupActivity : AppCompatActivity() {
    private lateinit var app: DicteeApp
    private lateinit var scroll: ScrollView
    private lateinit var intro: TextView
    private lateinit var serverBox: LinearLayout
    private lateinit var stepsBox: LinearLayout
    private lateinit var modelsBox: LinearLayout
    private lateinit var languagesBox: LinearLayout
    private lateinit var participationBox: LinearLayout
    private lateinit var readyBox: LinearLayout
    /** Whether the « C'est prêt » card is drawn now; null before the first draw. */
    private var readyShown: Boolean? = null
    /** Whether the participation card is drawn now; null before the first draw. It is redrawn only when this changes. */
    private var participationShown: Boolean? = null
    /**
     * Issue #55, point 6: whether this opening of the screen may jump to a missing keyboard step. Only a fresh
     * opening (not a rotation, not a return from the settings or from a system dialog): a jump on every resume
     * scrolled away from the card the person had just touched.
     */
    private var jumpOnOpen = false
    /** The step the screen was last drawn for: the models are redrawn when it changes (their filled button follows it). */
    private var drawnStep: SetupStep? = null
    // A download that ends can complete the setup: `renderSteps` redraws the steps and « C'est prêt ».
    private val refresh: () -> Unit = { renderModels(); renderSteps() }

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { renderSteps() }
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = DicteeApp.from(this)
        jumpOnOpen = savedInstanceState == null
        val col = scrollingColumn()
        scroll = col.parent as ScrollView
        col.title(getString(R.string.app_name))
        intro = col.body()
        intro.tag = INTRO_TAG
        // Only when the person configured a server in the advanced settings and chose it (issue #57, point 4).
        serverBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(serverBox)

        // Issue #55: the two keyboard steps and the microphone, first, one card each.
        stepsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = STEPS_TAG }
        col.addView(stepsBox)

        // Below the steps (issue #55, point 4); the tutorial itself is issue #56.
        col.button(getString(R.string.setup_howto), outlined = true) {
            startActivity(Intent(this@SetupActivity, HowToActivity::class.java))
        }.tag = HOWTO_BUTTON_TAG

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
        // « Comparer les modèles » names the models: it is in « Avancé » of the settings (issue #37).
        col.button(getString(R.string.setup_settings), outlined = true) {
            startActivity(Intent(this@SetupActivity, SettingsActivity::class.java))
        }

        // « C'est prêt » (issue #56): drawn here, just above the try field, once the setup is complete.
        readyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(readyBox)

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
        // Redrawn on each resume: the languages, or the server, may have been changed in the settings.
        languagesBox.renderLanguageChoices(app.settings) { renderModels(); renderParticipation() }
        renderSteps()
        renderModels()
        renderParticipation()
        // Issue #55, point 6: reopened with a keyboard step missing, that step is the first thing on screen.
        if (jumpOnOpen && SetupProgress.jumpToKeyboardSteps(app.settings.setupSeen, isKeyboardEnabled(), isKeyboardChosen())) {
            scroll.post { scroll.scrollTo(0, topWithin(scroll.getChildAt(0), stepsBox)) }
        }
        jumpOnOpen = false
    }

    override fun onPause() {
        Downloads.removeListener(refresh)
        super.onPause()
    }

    override fun onStop() {
        // A rotation is not a visit: the first visit must still start on the welcome text.
        if (!isChangingConfigurations) app.settings.setupSeen = true
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) renderSteps() // back from the IME picker
    }

    private fun currentStep(): SetupStep =
        SetupProgress.nextStep(isKeyboardEnabled(), isKeyboardChosen(), isMicGranted(), isModelReady())

    /** Redraws the welcome text, the server line and the steps; called on resume, on focus and when a download ends. */
    private fun renderSteps() {
        val serverInUse = app.settings.serverInUse
        intro.text = getString(if (serverInUse) R.string.setup_intro_server else R.string.setup_intro)
        renderServer(serverInUse)

        val enabled = isKeyboardEnabled()
        val chosen = enabled && isKeyboardChosen()
        // Chosen once, another keyboard active now: done, said apart (a voice keyboard is often used in turns).
        val chosenElsewhere = chosen && !isKeyboardCurrent()
        val mic = isMicGranted()
        val next = currentStep()
        stepsBox.removeAllViews()
        step(
            title = getString(R.string.setup_step_enable_title),
            text = getString(R.string.setup_step_enable_text) + " " +
                getString(if (serverInUse) R.string.setup_step_enable_warning_server else R.string.setup_step_enable_warning),
            done = enabled, available = true, filled = next == SetupStep.ENABLE_KEYBOARD,
            buttonText = getString(R.string.setup_open_ime_settings),
            tagPrefix = STEP_ENABLE,
        ) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        step(
            title = getString(R.string.setup_step_choose_title),
            text = getString(R.string.setup_step_choose_text),
            done = chosen, available = enabled, filled = next == SetupStep.CHOOSE_KEYBOARD,
            buttonText = getString(R.string.setup_pick_ime),
            tagPrefix = STEP_CHOOSE,
            doneText = if (chosenElsewhere) getString(R.string.setup_step_done_other_keyboard) else null,
        ) { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
        step(
            title = getString(R.string.setup_step_mic),
            text = getString(R.string.setup_mic_missing),
            done = mic, available = true, filled = next == SetupStep.ALLOW_MIC,
            buttonText = getString(R.string.setup_grant_mic),
            tagPrefix = STEP_MIC,
        ) { askMic.launch(Manifest.permission.RECORD_AUDIO) }

        // The download button of the recommended model is filled only when the model is the next step.
        if (drawnStep != null && (drawnStep == SetupStep.GET_MODEL) != (next == SetupStep.GET_MODEL)) renderModels()
        drawnStep = next
        renderReady()
        renderParticipation()
    }

    /**
     * One step card (issue #55): number and title, what it does and what Android shows, its state, and its button.
     * A step that cannot be done yet ([available] false) is dimmed and its button inactive; a done step keeps its
     * button (outlined), to redo it.
     */
    private fun step(
        title: String, text: String, done: Boolean, available: Boolean, filled: Boolean,
        buttonText: String, tagPrefix: String, doneText: String? = null, onClick: () -> Unit,
    ) {
        val card = stepsBox.card()
        card.tag = "${tagPrefix}_card"
        card.heading(title).setPadding(0, 0, 0, dp(6))
        card.body(text, small = true)
        card.value(
            when {
                done -> doneText ?: getString(R.string.setup_step_done)
                !available -> getString(R.string.setup_step_after_previous)
                else -> getString(R.string.setup_step_todo)
            },
        ).tag = "${tagPrefix}_state"
        card.button(buttonText, outlined = !filled) { onClick() }.apply {
            tag = "${tagPrefix}_button"
            isEnabled = available
        }
        // Dimmed while it waits for the previous step: the theme's disabled look for the button, a lighter text.
        card.alpha = if (available) 1f else DIMMED_ALPHA
    }

    /** The line « Transcription : serveur distant » with a link to the setting (issue #57, point 4); nothing otherwise. */
    private fun renderServer(serverInUse: Boolean) {
        serverBox.removeAllViews()
        if (!serverInUse) return
        val card = serverBox.card()
        card.tag = SERVER_STATUS_TAG
        card.heading(getString(R.string.setup_server_in_use)).setPadding(0, 0, 0, dp(6))
        val localModel = ModelCatalog.listed(app.settings.languages).any { app.isInstalled(it) }
        card.body(
            getString(R.string.setup_server_in_use_text, app.settings.remoteConfig().normalizedBase()) + " " +
                getString(if (localModel) R.string.setup_server_fallback else R.string.setup_server_no_fallback),
            small = true,
        )
        card.button(getString(R.string.setup_server_open_setting), outlined = true) {
            startActivity(
                Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SCROLL_TO_SERVER_MODE, true),
            )
        }.tag = SERVER_SETTING_BUTTON_TAG
    }

    /**
     * Draws or removes the « C'est prêt » card (issue #56): once the setup is complete ([setupComplete], the same
     * rule as the steps), with an invitation to "Comment dicter", which never opens by itself. Touches nothing when
     * it is already as it should be, and never scrolls.
     */
    private fun renderReady() {
        val show = setupComplete()
        if (show == readyShown) return
        readyShown = show
        readyBox.removeAllViews()
        if (show) readyBox.readyCard { startActivity(Intent(this, HowToActivity::class.java)) }
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
                filled = false,
            )
        }
    }

    /**
     * Only a model the keyboard can use counts (a hidden model's files may still be on the phone); a configured
     * server counts only when dictations really go to it.
     */
    private fun isModelReady(): Boolean =
        ModelCatalog.listed(app.settings.languages).any { app.isInstalled(it) } || app.settings.serverInUse

    private fun setupComplete(): Boolean = currentStep() == SetupStep.DONE

    private fun isKeyboardEnabled(): Boolean = TestHooks.keyboardEnabled
        ?: getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == packageName }

    /** Kalmia Blabla is the input method in use right now. */
    private fun isKeyboardCurrent(): Boolean = TestHooks.keyboardChosen
        ?: (Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: "").startsWith("$packageName/")

    /**
     * Step 2 done (decision for issue #55, 2026-11-13): Kalmia Blabla is the current keyboard **or** has been seen
     * as the current keyboard once. A person who keeps their usual keyboard and switches to Kalmia only to dictate
     * has done the step. The first time it is seen current, that is remembered.
     */
    private fun isKeyboardChosen(): Boolean {
        if (isKeyboardCurrent()) {
            if (!app.settings.keyboardChosenOnce) app.settings.keyboardChosenOnce = true
            return true
        }
        return app.settings.keyboardChosenOnce
    }

    private fun isMicGranted(): Boolean = TestHooks.micGranted
        ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)

    private fun renderModels() {
        modelsBox.removeAllViews()
        val chosen = app.settings.languages
        val recommendation = ModelRecommendations.recommend(chosen)
        renderRetiredNotice()
        // Issue #22: only the models offered for the chosen languages (experimental ones are in the settings).
        val modelIsNext = currentStep() == SetupStep.GET_MODEL
        for (spec in ModelCatalog.offered(chosen)) renderModel(spec, chosen, recommendation, modelIsNext && spec == recommendation.model)
        // Issue #72: the quick test is hidden (see QuickTestActivity.REACHABLE); its code stays.
        if (QuickTestActivity.REACHABLE) renderQuickTest(chosen)
    }

    /**
     * « Faire le test (1 minute) » (issue #36, recentred on the phone): optional, outlined, under the model cards.
     * Only for the five languages that have a text, and once a model to test is on the phone.
     */
    private fun renderQuickTest(chosen: Set<DictationLanguage>) {
        if (TranscriptionAdvisor.quickTestLanguage(chosen) == null) return
        if (TranscriptionAdvisor.choice(chosen).candidates.none { app.isInstalled(it) }) return
        modelsBox.body(getString(R.string.quick_open_help), small = true)
        modelsBox.button(getString(R.string.quick_open), outlined = true) {
            startActivity(Intent(this, QuickTestActivity::class.java))
        }.tag = QUICK_TEST_BUTTON_TAG
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
        card.body(getString(R.string.model_notice_replaced, ModelPlainNames.inSentence(replacement)))
        val missing = !app.store.isInstalled(replacement) && !Downloads.isBusy(replacement.id)
        if (missing) card.body(getString(R.string.model_notice_download), small = true)
        val deletable = retired.visibility == ModelVisibility.HIDDEN && app.store.isInstalled(retired)
        if (missing) {
            card.button(getString(R.string.model_notice_download_button, size(replacement.totalBytes)), outlined = true) {
                startDownload(replacement)
            }.tag = RETIRED_DOWNLOAD_TAG
        }
        if (deletable) {
            card.button(
                getString(R.string.model_notice_delete_button, size(app.store.installedBytes(retired.asDownloadable()))),
                outlined = true,
            ) {
                if (app.engines.current()?.spec?.id == retired.id) app.engines.release()
                app.store.delete(retired)
                Toast.makeText(this, getString(R.string.model_notice_deleted), Toast.LENGTH_SHORT).show()
                renderModels()
            }.tag = RETIRED_DELETE_TAG
        }
        card.button(getString(R.string.model_notice_ok), outlined = true) {
            app.settings.retiredModelNotice = ""
            renderModels()
        }.tag = RETIRED_OK_TAG
    }

    /** [filledDownload]: this card's « Télécharger » is the screen's one filled button (the model is the next step). */
    private fun renderModel(
        spec: ModelSpec, chosen: Set<DictationLanguage>, recommendation: ModelRecommendation, filledDownload: Boolean,
    ) {
        val card = modelsBox.card()
        card.tag = modelCardTag(spec)
        val installed = app.store.isInstalled(spec)
        val active = app.settings.activeModelId == spec.id && app.shownSpec().family != ModelFamily.REMOTE
        card.addView(RadioButton(this).apply {
            // Issue #72: « Whisper », nothing more technical (the variant is in « Avancé »).
            text = ModelPlainNames.of(spec)
            isChecked = active
            isEnabled = installed
            setOnClickListener {
                app.settings.activeModelId = spec.id
                if (app.settings.transcriptionMode == TranscriptionMode.SERVER) app.settings.transcriptionMode = TranscriptionMode.PHONE
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
        // The publisher, not the technical description (issue #37); that one is in « Avancé ».
        card.body(getString(R.string.model_publisher, spec.publisher), small = true)
        if (spec.note.isNotEmpty()) card.body(spec.note, small = true).tag = modelNoteTag(spec)
        card.body(getString(R.string.model_license_runs_local, spec.license), small = true)
        val status = Downloads.status[spec.id]
        val failed = !Downloads.isBusy(spec.id) && status?.error != null
        card.value(
            when {
                Downloads.isBusy(spec.id) && status != null ->
                    getString(R.string.setup_downloading, status.percent, size(status.downloaded), size(status.total))
                // Issue #37: a plain message here; the technical detail (file names, checksums) goes to the log.
                status?.error != null -> {
                    android.util.Log.w("DicteeSetup", "download of ${spec.id} failed: ${status.error}")
                    getString(R.string.setup_download_failed)
                }
                installed -> getString(R.string.setup_installed, size(app.store.installedBytes(spec.asDownloadable())))
                else -> getString(R.string.setup_not_installed, size(spec.totalBytes))
            },
        ).apply { if (failed) markError() }
        card.row().apply {
            when {
                Downloads.isBusy(spec.id) -> button(getString(R.string.cancel), outlined = true) { Downloads.cancel(spec.id) }
                installed -> button(getString(R.string.delete), outlined = true) { confirmDelete(spec) }
                else -> button(getString(R.string.download), outlined = !filledDownload) { startDownload(spec) }
            }
        }
    }

    private fun startDownload(spec: ModelSpec) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Downloads.start(this, spec.asDownloadable(), getString(R.string.setup_downloading_label, ModelPlainNames.inSentence(spec)))
        renderModels()
    }

    private fun confirmDelete(spec: ModelSpec) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_title, ModelPlainNames.inSentence(spec)))
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
        const val INTRO_TAG = "setup_intro"
        const val STEPS_TAG = "setup_steps"
        /** Prefixes of the step cards: `<prefix>_card`, `<prefix>_state`, `<prefix>_button` (issue #55). */
        const val STEP_ENABLE = "setup_step_enable"
        const val STEP_CHOOSE = "setup_step_choose"
        const val STEP_MIC = "setup_step_mic"
        const val SERVER_STATUS_TAG = "setup_server_status"
        const val SERVER_SETTING_BUTTON_TAG = "setup_server_setting"
        const val QUICK_TEST_BUTTON_TAG = "setup_quick_test"
        /** Opacity of a step that waits for the previous one. */
        const val DIMMED_ALPHA = 0.5f
    }
}
