package io.github.remtav.dictee.ui

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.remtav.dictee.DicteeApp
import io.github.remtav.dictee.R
import io.github.remtav.dictee.reading.QuickReading
import io.github.remtav.dictee.stt.ModeOutcome
import io.github.remtav.dictee.stt.ModelPick
import io.github.remtav.dictee.stt.ModelPlainNames
import io.github.remtav.dictee.stt.ModelSpec
import io.github.remtav.dictee.stt.QuickTestAdvice
import io.github.remtav.dictee.stt.TranscriptionAdvisor
import io.github.remtav.dictee.stt.VerdictReason
import io.github.remtav.dictee.stt.asDownloadable
import java.util.Locale
import kotlin.math.roundToInt

/**
 * « Test rapide (1 minute) » (issue #36, recentred on the phone).
 *
 * **Hidden since issue #72** ([REACHABLE] is false): no screen opens it any more. With one phone model (Whisper) it
 * has nothing to compare; Rémi wants it kept, to compare local Whisper and remote Whisper one day (a later issue:
 * it will need a consent, since it is built to send nothing off the phone, and new verdicts, which are still the
 * two-phone-model ones). Everything stays and compiles: this screen, [QuickTestModel], [QuickTestAdvice],
 * `reading/QuickReading.kt`, the `quick_*` strings and their tests. Draws [QuickTestModel].
 */
class QuickTestActivity : AppCompatActivity() {
    private lateinit var app: DicteeApp
    private lateinit var model: QuickTestModel
    private lateinit var column: LinearLayout
    private val downloadsChanged: () -> Unit = { if (model.phase == QuickTestModel.Phase.RESULT) render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = DicteeApp.from(this)
        val language = TranscriptionAdvisor.quickTestLanguage(app.settings.languages)
        if (language == null || !QuickReading.hasText(language)) {
            Toast.makeText(this, R.string.quick_no_language, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val material = QuickReading.load(language) { path -> assets.open(path).bufferedReader().use { it.readText() } }
                return QuickTestModel(app, language, material, TranscriptionAdvisor.choice(app.settings.languages)) as T
            }
        })[QuickTestModel::class.java]
        column = scrollingColumn()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStart() {
        super.onStart()
        if (!::model.isInitialized) return
        model.onChange = { render() }
        model.onProgress = { updateRecordingText() }
        QuickTestModel.onLingeringEnded = { render() }
        Downloads.addListener(downloadsChanged)
        render()
    }

    override fun onStop() {
        if (::model.isInitialized) {
            model.onChange = null
            model.onProgress = null
            QuickTestModel.onLingeringEnded = null
            // The microphone is not kept in the background; a rotation is not leaving (the model survives it).
            if (!isChangingConfigurations) model.interruptRecording()
            Downloads.removeListener(downloadsChanged)
        }
        super.onStop()
    }

    private fun updateRecordingText() {
        if (model.phase != QuickTestModel.Phase.RECORDING) return
        (column.findViewWithTag<View>(TAG_STATE) as? TextView)?.text =
            getString(R.string.quick_recording, model.recordingSeconds, QuickTestAdvice.READING_BUDGET_SECONDS)
    }

    private fun render() {
        column.removeAllViews()
        column.title(getString(R.string.quick_title))
        when (model.phase) {
            QuickTestModel.Phase.READY, QuickTestModel.Phase.RECORDING -> renderReading()
            QuickTestModel.Phase.SCORING -> renderScoring()
            QuickTestModel.Phase.RESULT -> renderResult()
        }
    }

    private fun renderReading() {
        val recording = model.phase == QuickTestModel.Phase.RECORDING
        column.body(getString(R.string.quick_intro, model.language.inSentence), small = true)
        column.body(getString(R.string.quick_private), small = true).tag = TAG_PRIVATE
        column.reading(model.material.text.paragraphs[0], TAG_TEXT)
        column.value(
            if (recording) getString(R.string.quick_recording, model.recordingSeconds, QuickTestAdvice.READING_BUDGET_SECONDS)
            else getString(R.string.quick_ready),
        ).tag = TAG_STATE
        model.notice?.let { column.warning(it, TAG_NOTICE) }
        column.button(getString(if (recording) R.string.quick_stop else R.string.quick_record)) { model.toggleRecording() }
            .tag = TAG_RECORD
    }

    private fun renderScoring() {
        val current = model.scoringModel
        column.value(
            if (current == null) getString(R.string.quick_scoring)
            else getString(R.string.quick_scoring_model, ModelPlainNames.inSentence(current)),
        ).tag = TAG_SCORING
    }

    private fun renderResult() {
        val active = app.settings.activeModel
        for (spec in model.choice.candidates) {
            val card = column.card()
            card.tag = resultTag(spec)
            val title = ModelPlainNames.of(spec) + if (spec.id == active.id) " " + getString(R.string.quick_current_suffix) else ""
            card.heading(title).setPadding(0, 0, 0, dp(6))
            when (val o = model.outcomes[spec.id] ?: ModeOutcome.Failed) {
                is ModeOutcome.Scored -> card.value(getString(R.string.quick_score, percent(o.similarity), seconds(o.delaySeconds)))
                ModeOutcome.TooSlow -> card.value(getString(R.string.quick_too_slow, QuickTestAdvice.TRANSCRIPTION_BUDGET_SECONDS))
                ModeOutcome.Skipped -> card.value(getString(R.string.quick_skipped))
                ModeOutcome.Failed -> card.value(getString(R.string.quick_failed))
                ModeOutcome.NotInstalled -> renderUnavailable(card, spec)
            }
        }
        renderVerdict(active)
        column.body(getString(R.string.quick_hint), small = true)
        column.row().apply {
            button(getString(R.string.quick_redo), outlined = true) { model.restart() }.apply { tag = TAG_REDO }.spaced()
            button(getString(R.string.quick_close), outlined = true) { finish() }.tag = TAG_CLOSE
        }
    }

    private fun renderUnavailable(card: LinearLayout, spec: ModelSpec) {
        val busy = Downloads.isBusy(spec.id)
        val status = Downloads.status[spec.id]
        when {
            app.isInstalled(spec) -> card.value(getString(R.string.quick_installed_redo))
            busy && status != null -> card.value(getString(R.string.setup_downloading, status.percent, size(status.downloaded), size(status.total)))
            else -> {
                card.value(getString(R.string.quick_not_installed))
                card.button(getString(R.string.quick_download, size(spec.totalBytes)), outlined = true) {
                    Downloads.start(this, spec.asDownloadable(), getString(R.string.setup_downloading_label, ModelPlainNames.inSentence(spec)))
                    render()
                }.tag = downloadTag(spec)
            }
        }
    }

    /** One sentence and, when it changes something, one button to adopt the recommendation. */
    private fun renderVerdict(active: ModelSpec) {
        val card = column.card()
        card.tag = TAG_VERDICT
        val verdict = model.verdict
        val best = model.choice.best
        val light = model.choice.light
        val bestOutcome = model.outcomes[best.id] ?: ModeOutcome.Failed
        if (light == null) {
            // One model makes sense for these languages: the test simply gives its score.
            card.body(
                when (bestOutcome) {
                    is ModeOutcome.Scored -> getString(R.string.quick_single_scored, percent(bestOutcome.similarity))
                    ModeOutcome.TooSlow -> getString(R.string.quick_single_too_slow)
                    ModeOutcome.NotInstalled -> getString(R.string.quick_single_unavailable)
                    else -> getString(R.string.quick_single_failed)
                },
            ).tag = TAG_SENTENCE
            if (bestOutcome is ModeOutcome.Scored && active.id != best.id) adoptButton(card, best)
            renderServerNote(card)
            return
        }
        val lightOutcome = model.outcomes[light.id] ?: ModeOutcome.Failed
        if (verdict == null) {
            card.body(
                when {
                    // Measured but below 90 %, the best one unmeasured: the default stays, nothing to adopt.
                    lightOutcome is ModeOutcome.Scored -> getString(R.string.quick_keep_best_unmeasured)
                    lightOutcome == ModeOutcome.NotInstalled && bestOutcome == ModeOutcome.NotInstalled ->
                        getString(R.string.quick_no_verdict)
                    else -> getString(R.string.quick_no_verdict_slow)
                },
            ).tag = TAG_SENTENCE
            renderServerNote(card)
            return
        }
        val pick = model.specOf(verdict.pick)
        card.body(
            when (verdict.reason) {
                VerdictReason.BEST_PRECISE_AND_QUICK -> getString(R.string.quick_verdict_best_quick)
                VerdictReason.LIGHT_GOOD_ENOUGH_AND_FASTER -> getString(R.string.quick_verdict_light_enough)
                VerdictReason.LIGHT_TOO_INACCURATE -> getString(R.string.quick_verdict_light_inaccurate)
                VerdictReason.LIGHT_FAR_BEHIND -> getString(R.string.quick_verdict_light_behind)
                VerdictReason.LIGHT_NOT_TESTED -> when (lightOutcome) {
                    ModeOutcome.NotInstalled -> getString(R.string.quick_verdict_light_not_installed)
                    ModeOutcome.TooSlow -> getString(R.string.quick_verdict_light_too_slow)
                    else -> getString(R.string.quick_verdict_light_failed)
                }
                VerdictReason.BEST_NOT_TESTED -> when (bestOutcome) {
                    ModeOutcome.NotInstalled -> getString(R.string.quick_verdict_best_not_installed)
                    ModeOutcome.TooSlow, ModeOutcome.Skipped -> getString(R.string.quick_verdict_best_too_slow)
                    else -> getString(R.string.quick_verdict_best_failed)
                }
            },
        ).tag = TAG_SENTENCE
        if (pick.id == active.id) {
            card.body(getString(R.string.quick_already), small = true).tag = TAG_ALREADY
        } else {
            adoptButton(card, pick)
        }
        if (verdict.pick == ModelPick.LIGHT) card.body(getString(R.string.quick_light_note), small = true)
        renderServerNote(card)
    }

    /**
     * Review of #65: with a server set up and chosen in « Avancé », dictations go there whatever the phone model:
     * the screen says so, so that « Utiliser … » does not look like it changes where the voice goes.
     */
    private fun renderServerNote(card: LinearLayout) {
        if (app.settings.serverInUse) card.body(getString(R.string.quick_server_note), small = true).tag = TAG_SERVER_NOTE
    }

    private fun adoptButton(card: LinearLayout, spec: ModelSpec) {
        card.button(getString(R.string.quick_adopt, ModelPlainNames.inSentence(spec))) { model.adopt(spec) }.tag = TAG_ADOPT
    }

    private fun percent(similarity: Double) = (similarity * 100).roundToInt()
    private fun seconds(s: Double) = String.format(Locale.CANADA_FRENCH, "%.1f", s)

    companion object {
        /** Issue #72: whether the installation and the settings offer the test. Set to true when it is wanted again. */
        const val REACHABLE = false

        const val TAG_TEXT = "quick_text"
        const val TAG_PRIVATE = "quick_private"
        const val TAG_STATE = "quick_state"
        const val TAG_RECORD = "quick_record"
        const val TAG_NOTICE = "quick_notice"
        const val TAG_SCORING = "quick_scoring"
        const val TAG_VERDICT = "quick_verdict"
        const val TAG_SENTENCE = "quick_sentence"
        const val TAG_ADOPT = "quick_adopt"
        const val TAG_ALREADY = "quick_already"
        const val TAG_REDO = "quick_redo"
        const val TAG_SERVER_NOTE = "quick_server_note"
        const val TAG_CLOSE = "quick_close"
        fun resultTag(spec: ModelSpec) = "quick_result_${spec.id}"
        fun downloadTag(spec: ModelSpec) = "quick_download_${spec.id}"
    }
}
