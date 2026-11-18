package io.github.remtav.dictee.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import io.github.remtav.dictee.R
import io.github.remtav.dictee.settings.AppSettings
import io.github.remtav.dictee.stt.RepetitionFix
import io.github.remtav.dictee.stt.WhisperTuning

/**
 * The Whisper settings of « Avancé »: the padding, its « Automatique » choice and the repetition
 * correction, which only applies in automatic mode (greyed out otherwise).
 */
internal fun LinearLayout.whisperAdvanced(s: AppSettings) {
    body(context.getString(R.string.settings_whisper_tail), small = false)
    val tailLabels = listOf(
        R.string.settings_whisper_tail_1000, R.string.settings_whisper_tail_600, R.string.settings_whisper_tail_300,
        R.string.settings_whisper_tail_auto,
    ).map { context.getString(it) }
    // Fixed paddings first (TAIL_PADDING_CHOICES order), then « Automatique ».
    val autoIndex = WhisperTuning.TAIL_PADDING_CHOICES.size
    val tailIndex = if (s.whisperTailAuto) autoIndex else WhisperTuning.TAIL_PADDING_CHOICES.indexOf(s.whisperTailPaddings).coerceAtLeast(0)
    lateinit var fixGroup: RadioGroup
    lateinit var fixNote: TextView
    val renderFix = {
        for (i in 0 until fixGroup.childCount) fixGroup.getChildAt(i).isEnabled = s.whisperTailAuto
        fixNote.visibility = if (s.whisperTailAuto) View.GONE else View.VISIBLE
    }
    radio(tailLabels, tailIndex) {
        if (it == autoIndex) {
            // The stored fixed value is kept: it comes back if the user leaves « Automatique ».
            s.whisperTailAuto = true
        } else {
            s.whisperTailAuto = false
            s.whisperTailPaddings = WhisperTuning.TAIL_PADDING_CHOICES[it]
        }
        renderFix()
    }.tag = SettingsActivity.WHISPER_TAIL_TAG
    body(context.getString(R.string.settings_whisper_tail_help), small = true)
    body(context.getString(R.string.settings_whisper_repetition), small = false)
    val fixes = listOf(RepetitionFix.REDECODE, RepetitionFix.CUT)
    val fixLabels = listOf(R.string.settings_whisper_repetition_redecode, R.string.settings_whisper_repetition_cut).map { context.getString(it) }
    fixGroup = radio(fixLabels, fixes.indexOf(s.whisperRepetitionFix).coerceAtLeast(0)) {
        s.whisperRepetitionFix = fixes[it]
    }
    fixGroup.tag = SettingsActivity.WHISPER_REPETITION_TAG
    fixNote = body(context.getString(R.string.settings_whisper_repetition_inactive), small = true)
    body(context.getString(R.string.settings_whisper_repetition_help), small = true)
    renderFix()
    // Issue #53: off by default, then the two group lengths to try.
    body(context.getString(R.string.settings_whisper_group), small = false)
    val groupLabels = listOf(R.string.settings_whisper_group_off, R.string.settings_whisper_group_5, R.string.settings_whisper_group_10)
        .map { context.getString(it) }
    radio(groupLabels, WhisperTuning.GROUP_CHOICES.indexOf(s.whisperGroupSeconds).coerceAtLeast(0)) {
        s.whisperGroupSeconds = WhisperTuning.GROUP_CHOICES[it]
    }.tag = SettingsActivity.WHISPER_GROUP_TAG
    body(context.getString(R.string.settings_whisper_group_help), small = true)
}
