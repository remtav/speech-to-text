package io.github.remtav.dictee.ui

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.remtav.dictee.DicteeApp
import io.github.remtav.dictee.R
import io.github.remtav.dictee.reading.ArchiveAudio
import io.github.remtav.dictee.reading.ArchiveField
import io.github.remtav.dictee.reading.ContributionContent
import io.github.remtav.dictee.reading.Contribution
import io.github.remtav.dictee.reading.ParagraphRecorder
import io.github.remtav.dictee.reading.ReadingMaterial
import io.github.remtav.dictee.reading.ReadingModel
import io.github.remtav.dictee.reading.ReadingPhase
import io.github.remtav.dictee.reading.ReadingResult
import io.github.remtav.dictee.reading.ReadingSession
import io.github.remtav.dictee.reading.similarityPercent
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.ModelRecommendations

/**
 * The reading test (issue #15, lot 4, step 4.2): declaration, acceptance, a text read paragraph by
 * paragraph, then the resemblance score of every installed on-device model with the misheard words
 * highlighted. Opened by the participation card (main screen and settings).
 *
 * Nothing leaves the phone and nothing is written during the test: the recordings live in
 * [ReadingTestModel] (memory) and are dropped when this screen is finished. No network request, no new
 * permission. The decisions are in [ReadingSession]; this class only draws its state.
 *
 * Step 4.3, the one voluntary exception: when the recommended model misheard more than 20 % of the text,
 * the result proposes to share the reading ([renderShareSlot]). The screen lists exactly what would leave
 * (built from the same [ContributionContent] as the archive), lets the person listen again, redo or
 * refuse; only the ticked consent box AND the button create the archive, in private storage; the person
 * then hands it to their own mail (or another app) and presses « Envoyer » there. The app itself sends
 * nothing.
 */
class ReadingTestActivity : AppCompatActivity() {
    private lateinit var app: DicteeApp
    private lateinit var model: ReadingTestModel
    private lateinit var column: LinearLayout

    /** Model whose detail is shown on the result screen (the active model by default). */
    private var detailModelId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = DicteeApp.from(this)
        model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ReadingTestModel(app, newSession()) as T
        })[ReadingTestModel::class.java]
        column = scrollingColumn()
        // Reading and scoring take minutes: the screen stays on (nothing else changes).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun newSession(): ReadingSession {
        val settings = app.settings
        val material = ReadingMaterial.load { path -> assets.open(path).bufferedReader().use { it.readText() } }
        // On-device models only: the user's own server would take the voice over the network.
        val models = ModelCatalog.all.filter { app.isInstalled(it) }.map { ReadingModel(it.id, it.displayName) }
        return ReadingSession(
            material = material,
            models = models,
            recommendedModelId = ModelRecommendations.recommend(settings.languages).model.id,
            activeModelId = settings.activeModelId,
            declaration = settings.quebecDeclaration,
            saveDeclaration = { settings.quebecDeclaration = it },
        )
    }

    override fun onStart() {
        super.onStart()
        model.onChange = { render() }
        render()
    }

    override fun onStop() {
        model.onChange = null
        model.stopRecording()
        super.onStop()
    }

    private fun render() {
        column.removeAllViews()
        when (model.session.phase) {
            ReadingPhase.DECLARE -> renderDeclare()
            ReadingPhase.DECLINED -> renderDeclined()
            ReadingPhase.ACCEPT -> renderAccept()
            ReadingPhase.READING -> renderReading()
            ReadingPhase.SCORING -> renderScoring()
            ReadingPhase.RESULT -> renderResult()
        }
    }

    // ---- declaration, thanks, acceptance ----

    private fun renderDeclare() {
        column.title(getString(R.string.contrib_declare_title))
        column.heading(getString(R.string.contrib_declare_question))
        column.body(getString(R.string.contrib_declare_help), small = true)
        column.button(getString(R.string.contrib_declare_yes)) { model.answer(true) }.tag = TAG_DECLARE_YES
        column.button(getString(R.string.contrib_declare_no), outlined = true) { model.answer(false) }.tag = TAG_DECLARE_NO
    }

    private fun renderDeclined() {
        column.title(getString(R.string.contrib_declined_title))
        column.body(getString(R.string.contrib_declined_body))
        column.button(getString(R.string.contrib_declined_close)) { finish() }.tag = TAG_DECLINED_CLOSE
    }

    private fun renderAccept() {
        val session = model.session
        column.title(getString(R.string.contrib_accept_title))
        column.body(getString(R.string.contrib_accept_body, session.paragraphCount))
        if (session.canStart) {
            column.body(getString(R.string.reading_models_used, session.models.joinToString(", ") { it.label }), small = true)
        } else {
            column.warning(getString(R.string.reading_no_model), TAG_NO_MODEL)
        }
        column.button(getString(R.string.contrib_accept_start)) { model.accept() }.apply {
            tag = TAG_ACCEPT_START
            isEnabled = session.canStart
        }
        column.button(getString(R.string.contrib_accept_cancel), outlined = true) { finish() }.tag = TAG_ACCEPT_CANCEL
    }

    // ---- reading ----

    private fun renderReading() {
        val session = model.session
        val index = session.current
        val recorded = session.audioOf(index)
        column.title(getString(R.string.reading_title))
        column.label(getString(R.string.reading_progress, index + 1, session.paragraphCount)).tag = TAG_PROGRESS
        column.reading(session.material.text.paragraphs[index], TAG_PARAGRAPH)
        column.body(getString(R.string.reading_hint, ParagraphRecorder.MAX_SECONDS), small = true)
        column.value(
            when {
                model.recording -> getString(R.string.reading_recording, model.recordingSeconds, ParagraphRecorder.MAX_SECONDS)
                recorded != null -> getString(R.string.reading_recorded, Math.round(recorded.seconds))
                else -> ""
            },
        ).tag = TAG_RECORD_STATE
        model.notice?.let { column.warning(it, TAG_NOTICE) }
        column.button(
            getString(
                when {
                    model.recording -> R.string.reading_stop
                    recorded != null -> R.string.reading_redo
                    else -> R.string.reading_record
                },
            ),
        ) { model.toggleRecording() }.tag = TAG_RECORD
        column.row().apply {
            button(getString(R.string.reading_previous), outlined = true) { model.previous() }.apply {
                tag = TAG_PREVIOUS
                isEnabled = session.canGoPrevious && !model.recording
            }.spaced()
            button(getString(R.string.reading_next), outlined = true) { model.next() }.apply {
                tag = TAG_NEXT
                isEnabled = session.canGoNext && !model.recording
            }
        }
        if (session.recordedCount > 0) {
            column.button(getString(R.string.reading_score)) { model.startScoring() }.apply {
                tag = TAG_SCORE
                isEnabled = !model.recording
            }
        }
    }

    // ---- scoring ----

    private fun renderScoring() {
        column.title(getString(R.string.reading_scoring_title))
        column.addView(ProgressBar(this).apply { isIndeterminate = true })
        val p = model.progress
        column.value(
            if (p == null) "" else getString(
                R.string.reading_scoring_progress, p.model.label, p.modelIndex + 1, p.modelCount, p.paragraph + 1, p.paragraphCount,
            ),
        ).tag = TAG_SCORING_PROGRESS
        column.body(getString(R.string.reading_scoring_note), small = true)
        column.button(getString(R.string.reading_scoring_cancel), outlined = true) { model.cancelScoring() }
    }

    // ---- result ----

    private fun renderResult() {
        val session = model.session
        val result = session.result ?: return
        column.title(getString(R.string.reading_result_title))
        model.notice?.let { column.warning(it, TAG_NOTICE) }
        if (result.paragraphs.size < session.paragraphCount) {
            column.body(getString(R.string.reading_result_partial, result.paragraphs.size, session.paragraphCount), small = true)
        }
        val labels = session.models.associate { it.id to it.label }
        for ((id, message) in result.modelErrors) {
            column.warning(getString(R.string.reading_result_error, labels[id] ?: id, message), TAG_MODEL_ERROR)
        }
        val scored = session.models.filter { it.id in result.globalScores }
        if (scored.isEmpty()) {
            renderResultFooter(result)
            return
        }
        // One line per model, the detail of one model below (a long text times several models would not fit).
        val shown = detailModelId?.takeIf { id -> scored.any { it.id == id } }
            ?: scored.firstOrNull { it.id == result.activeModelId }?.id ?: scored.first().id
        detailModelId = shown
        if (scored.size == 1) {
            val only = scored.single()
            column.heading(only.label)
            column.value(getString(R.string.reading_result_global, result.globalScores.getValue(only.id).similarityPercent))
                .tag = globalTag(only.id)
        } else {
            column.heading(getString(R.string.reading_result_detail_for))
            val group = RadioGroup(this)
            for (m in scored) {
                group.addView(RadioButton(this).apply {
                    id = android.view.View.generateViewId()
                    tag = modelChoiceTag(m.id)
                    text = getString(R.string.reading_result_model_line, m.label, result.globalScores.getValue(m.id).similarityPercent)
                    isChecked = m.id == shown
                    setOnClickListener { detailModelId = m.id; render() }
                })
            }
            column.addView(group)
        }
        renderBetterModel(result, labels)
        renderDetail(result, shown)
        renderResultFooter(result)
    }

    private fun renderBetterModel(result: ReadingResult, labels: Map<String, String>) {
        val betterId = result.betterModelId ?: return
        val better = result.globalScores[betterId] ?: return
        val current = result.globalScores[result.activeModelId] ?: return
        val betterLabel = labels[betterId] ?: betterId
        val card = column.card()
        card.tag = TAG_BETTER
        card.heading(getString(R.string.reading_better_title)).setPadding(0, 0, 0, dp(6))
        card.body(
            getString(
                R.string.reading_better_body, betterLabel, better.similarityPercent, current.similarityPercent,
                labels[result.activeModelId] ?: result.activeModelId,
            ),
            small = true,
        )
        if (app.settings.activeModelId == betterId) {
            card.body(getString(R.string.reading_better_chosen, betterLabel), small = true).tag = TAG_BETTER_CHOSEN
        } else {
            card.row().apply {
                button(getString(R.string.reading_better_choose, betterLabel)) {
                    app.settings.activeModelId = betterId
                    render()
                }.tag = TAG_BETTER_CHOOSE
            }
        }
    }

    private fun renderDetail(result: ReadingResult, modelId: String) {
        val global = result.globalScores.getValue(modelId)
        if (result.globalScores.size > 1) {
            column.value(getString(R.string.reading_result_global, global.similarityPercent)).tag = globalTag(modelId)
        }
        column.label(getString(R.string.reading_result_legend))
        val paragraphs = model.session.material.text.paragraphs
        val byIndex = result.paragraphs.associateBy { it.index }
        for (i in paragraphs.indices) {
            val read = byIndex[i]
            val score = read?.scores?.get(modelId)
            if (read == null || score == null) {
                column.label(getString(R.string.reading_progress, i + 1, paragraphs.size) + " · " + getString(R.string.reading_result_unread))
                continue
            }
            column.value(getString(R.string.reading_result_paragraph, i + 1, score.similarityPercent))
            column.body(highlighted(paragraphs[i], score.misheard)).tag = paragraphTag(i)
            column.label(getString(R.string.reading_result_heard, read.transcriptions[modelId].orEmpty()))
            column.body(
                if (score.misheard.isEmpty()) getString(R.string.reading_result_all_good)
                else getString(R.string.reading_result_misheard, score.misheard.joinToString(", ") { it.word }),
                small = true,
            )
        }
    }

    private fun renderResultFooter(result: ReadingResult) {
        // Step 4.3 plugs the sharing offer here, when `result.offerSharing` is true. Nothing in step 4.2.
        renderShareSlot(column, result)
        if (model.archive == null) column.body(getString(R.string.reading_result_kept), small = true)
        column.row().apply {
            button(getString(R.string.contrib_share_redo), outlined = true) { model.restart() }.apply { tag = TAG_REDO }.spaced()
            button(getString(R.string.reading_close)) { finish() }.tag = TAG_CLOSE
        }
    }

    /**
     * The sharing offer (step 4.3), present only when [ReadingResult.offerSharing] is true (the WER of the
     * recommended model, on the complete text, is above the threshold). Below it, nothing at all.
     */
    private fun renderShareSlot(parent: ViewGroup, result: ReadingResult) {
        if (!result.offerSharing) return
        val slot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = TAG_SHARE_SLOT
        }
        parent.addView(slot)
        val offer = model.shareOffer() ?: return
        val modelLabel = model.labelOf(result.recommendedModelId)
        val card = slot.card()
        card.heading(getString(R.string.contrib_share_title)).setPadding(0, 0, 0, dp(6))
        if (model.shareDeclined) {
            card.body(getString(R.string.contrib_share_declined), small = true).tag = TAG_SHARE_DECLINED
            return
        }
        val archive = model.archive
        val global = result.globalScores.getValue(result.recommendedModelId)
        card.body(getString(R.string.contrib_share_reason, "${global.similarityPercent} %", modelLabel), small = true)

        // Exactly what would leave: the same content the archive is written from (one source).
        val facts = model.shareFacts
        val content = checkNotNull(offer.preview(facts))
        card.heading(getString(R.string.contrib_share_sent_title)).tag = TAG_SHARE_LIST
        var regionValue: android.widget.TextView? = null
        for (item in content.fields) {
            card.label(ContributionShare.fieldLabel(this, item.field, modelLabel)).tag = fieldTag(item.field)
            val v = card.body(describe(item.field, content), small = true)
            if (item.field == ArchiveField.REGION) regionValue = v
        }
        if (result.globalScores.size > 1) card.body(getString(R.string.contrib_share_only_recommended), small = true)
        card.button(getString(if (model.playing) R.string.contrib_share_listen_stop else R.string.contrib_share_listen), outlined = true) {
            model.togglePlayback()
        }.tag = TAG_SHARE_LISTEN
        for (text in listOf(
            getString(R.string.contrib_share_not_sent),
            getString(R.string.contrib_share_recipient, Contribution.RECEIVING_ADDRESS),
            getString(R.string.contrib_share_purpose),
            getString(R.string.contrib_share_email_reveals),
            getString(R.string.contrib_share_retention, Contribution.RETENTION_MAX_YEARS),
            getString(R.string.contrib_share_voice),
            getString(R.string.contrib_share_withdraw, Contribution.RECEIVING_ADDRESS),
            getString(R.string.contrib_share_you_send),
            getString(R.string.contrib_share_policy, Contribution.POLICY_URL),
        )) card.body(text, small = true)
        model.shareNotice?.let { card.warning(it, TAG_SHARE_NOTICE) }

        if (archive == null) {
            if (model.archiveDeleted) card.body(getString(R.string.contrib_share_deleted), small = true).tag = TAG_SHARE_DELETED
            // The optional region (a region, not an address: short, one line).
            card.label(getString(R.string.contrib_share_region_label))
            card.addView(EditText(this).apply {
                tag = TAG_SHARE_REGION
                hint = getString(R.string.contrib_share_region_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                isSingleLine = true
                filters = arrayOf(InputFilter.LengthFilter(ContributionContent.REGION_MAX_CHARS))
                setText(offer.region)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        model.setRegion(s?.toString().orEmpty())
                        regionValue?.text = describe(ArchiveField.REGION, offer.preview(facts) ?: return)
                    }
                })
            })
            // The third consent gesture: a box, unticked by default, then a separate button.
            lateinit var prepare: android.view.View
            card.addView(CheckBox(this).apply {
                tag = TAG_SHARE_CONSENT
                text = getString(R.string.contrib_share_consent)
                isChecked = offer.consent
                setOnCheckedChangeListener { _, checked ->
                    model.setConsent(checked)
                    prepare.isEnabled = checked
                }
            })
            prepare = card.button(getString(R.string.contrib_share_prepare)) { model.prepareArchive() }.apply {
                tag = TAG_SHARE_PREPARE
                isEnabled = offer.consent
            }
        } else {
            card.value(getString(R.string.contrib_share_ready, archive.name, size(archive.length()))).tag = TAG_SHARE_READY
            card.body(getString(R.string.contrib_share_ready_where), small = true)
            if (model.mailOpened) card.body(getString(R.string.contrib_share_mail_opened), small = true).tag = TAG_SHARE_MAIL_OPENED
            card.button(getString(R.string.contrib_share_email)) { sendByEmail(result.textVersion, modelLabel) }.tag = TAG_SHARE_EMAIL
            card.button(getString(R.string.contrib_share_other), outlined = true) {
                val file = model.archive ?: return@button
                startActivity(ContributionShare.otherIntent(this, file, result.textVersion, modelLabel))
            }.tag = TAG_SHARE_OTHER
            card.button(getString(R.string.contrib_share_delete), outlined = true) { model.deleteArchive() }.tag = TAG_SHARE_DELETE
        }
        card.button(getString(R.string.contrib_share_decline), outlined = true) { model.declineSharing() }.tag = TAG_SHARE_DECLINE
    }

    private fun sendByEmail(textVersion: String, modelLabel: String) {
        val file = model.archive ?: return
        try {
            startActivity(ContributionShare.emailIntent(this, file, textVersion, modelLabel))
            model.mailOpened = true
            model.shareNotice = null
        } catch (e: ActivityNotFoundException) {
            model.shareNotice = getString(R.string.contrib_share_no_email_app)
        }
        render()
    }

    /** The value of one element of the archive, as shown before consent (from the content the archive is written from). */
    private fun describe(field: ArchiveField, content: ContributionContent): String {
        val value = content[field]
        return when (field) {
            ArchiveField.AUDIO -> {
                val tenths = content.audio.sumOf(ArchiveAudio::tenths)
                getString(R.string.contrib_value_audio, content.audio.size, tenths / 10) + "\n" +
                    content.audio.joinToString(", ") { it.name }
            }
            ArchiveField.TEXT, ArchiveField.TRANSCRIPTION -> (value as List<*>).joinToString("\n") { p ->
                val m = p as Map<*, *>
                getString(R.string.contrib_value_paragraph, m["paragraphe"] as Int, m["texte"] as String)
            }
            ArchiveField.SCORE -> {
                val m = value as Map<*, *>
                getString(R.string.contrib_value_score, m["ressemblance_pourcent"] as Int, m["erreurs"] as Int, m["mots"] as Int)
            }
            ArchiveField.MODEL -> (value as Map<*, *>)["nom"] as String
            ArchiveField.REGION -> (value as String?) ?: getString(R.string.contrib_value_region_none)
            ArchiveField.TEXT_VERSION, ArchiveField.APP_VERSION, ArchiveField.PHONE_MODEL,
            ArchiveField.DECLARATION, ArchiveField.DATE -> value as String
        }
    }

    companion object {
        const val TAG_DECLARE_YES = "reading_declare_yes"
        const val TAG_DECLARE_NO = "reading_declare_no"
        const val TAG_DECLINED_CLOSE = "reading_declined_close"
        const val TAG_ACCEPT_START = "reading_accept_start"
        const val TAG_ACCEPT_CANCEL = "reading_accept_cancel"
        const val TAG_NO_MODEL = "reading_no_model"
        const val TAG_PROGRESS = "reading_progress"
        const val TAG_PARAGRAPH = "reading_paragraph"
        const val TAG_RECORD = "reading_record"
        const val TAG_RECORD_STATE = "reading_record_state"
        const val TAG_PREVIOUS = "reading_previous"
        const val TAG_NEXT = "reading_next"
        const val TAG_SCORE = "reading_score"
        const val TAG_NOTICE = "reading_notice"
        const val TAG_SCORING_PROGRESS = "reading_scoring_progress"
        const val TAG_MODEL_ERROR = "reading_model_error"
        const val TAG_BETTER = "reading_better"
        const val TAG_BETTER_CHOOSE = "reading_better_choose"
        const val TAG_BETTER_CHOSEN = "reading_better_chosen"
        const val TAG_SHARE_SLOT = "reading_share_slot"
        const val TAG_SHARE_LIST = "reading_share_list"
        const val TAG_SHARE_LISTEN = "reading_share_listen"
        const val TAG_SHARE_REGION = "reading_share_region"
        const val TAG_SHARE_CONSENT = "reading_share_consent"
        const val TAG_SHARE_PREPARE = "reading_share_prepare"
        const val TAG_SHARE_DECLINE = "reading_share_decline"
        const val TAG_SHARE_DECLINED = "reading_share_declined"
        const val TAG_SHARE_READY = "reading_share_ready"
        const val TAG_SHARE_EMAIL = "reading_share_email"
        const val TAG_SHARE_OTHER = "reading_share_other"
        const val TAG_SHARE_DELETE = "reading_share_delete"
        const val TAG_SHARE_DELETED = "reading_share_deleted"
        const val TAG_SHARE_MAIL_OPENED = "reading_share_mail_opened"
        const val TAG_SHARE_NOTICE = "reading_share_notice"
        const val TAG_REDO = "reading_redo_test"
        const val TAG_CLOSE = "reading_close"

        /** Tag of the highlighted text of paragraph [index] on the result screen. */
        fun paragraphTag(index: Int) = "reading_result_paragraph_$index"
        /** Tag of the global score line of a model. */
        fun globalTag(modelId: String) = "reading_global_$modelId"
        /** Tag of the radio button that shows the detail of a model. */
        fun modelChoiceTag(modelId: String) = "reading_model_$modelId"
        /** Tag of the label of one element of the archive in the « what would leave » list. */
        fun fieldTag(field: ArchiveField) = "reading_share_field_${field.key}"
    }
}
