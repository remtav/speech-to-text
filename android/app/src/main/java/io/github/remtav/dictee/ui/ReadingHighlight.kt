package io.github.remtav.dictee.ui

import android.content.Context
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import io.github.remtav.dictee.reading.MisheardWord

/**
 * The paragraph with its misheard words marked (issue #15, step 4.2): the tertiary-container colour as
 * background with its own "on" colour for the text (a pair measured by `ThemeContrastTest`), AND an
 * underline, so the colour is never the only signal. Never the error colour: a misheard word is not an
 * error of the person. Positions come from `MisheardWord.range` (in the paragraph text).
 */
internal fun Context.highlighted(paragraph: String, misheard: List<MisheardWord>): CharSequence {
    val spannable = SpannableString(paragraph)
    val background = attrColor(com.google.android.material.R.attr.colorTertiaryContainer)
    val foreground = attrColor(com.google.android.material.R.attr.colorOnTertiaryContainer)
    for (word in misheard) {
        val start = word.range.first.coerceIn(0, paragraph.length)
        val end = (word.range.last + 1).coerceIn(start, paragraph.length)
        if (end == start) continue
        spannable.setSpan(BackgroundColorSpan(background), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(ForegroundColorSpan(foreground), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return spannable
}
