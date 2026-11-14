package io.github.remtav.dictee.ui

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan

/**
 * How the first screen of "Comment dicter" marks what matters (issue #56). Never the colour alone:
 * the spoken command is bold, underlined and on the tonal container colour; the sign obtained is
 * bold and in the primary colour (the bold weight carries it without the colour). The pairs are
 * those measured by `ThemeContrastTest` (4.5:1 in the light and the dark theme). No colour and no font is written here (`NoHardcodedStyleTest`).
 */
internal enum class Emphasis { COMMAND, SIGN }

/**
 * [prefix] then [body], with [range] (indexes into [body]) marked as [emphasis]. A null or
 * empty range, or one that does not fit in [body], marks nothing.
 */
internal fun Context.emphasised(prefix: String, body: String, range: IntRange?, emphasis: Emphasis): SpannableStringBuilder {
    val out = SpannableStringBuilder(prefix).append(body)
    if (range == null || range.isEmpty() || range.first < 0 || range.last >= body.length) return out
    val from = prefix.length + range.first
    val to = prefix.length + range.last + 1
    fun mark(span: Any) = out.setSpan(span, from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    mark(StyleSpan(Typeface.BOLD))
    when (emphasis) {
        Emphasis.COMMAND -> {
            mark(BackgroundColorSpan(attrColor(com.google.android.material.R.attr.colorSecondaryContainer)))
            mark(ForegroundColorSpan(attrColor(com.google.android.material.R.attr.colorOnSecondaryContainer)))
            mark(UnderlineSpan())
        }
        Emphasis.SIGN -> mark(ForegroundColorSpan(attrColor(androidx.appcompat.R.attr.colorPrimary)))
    }
    return out
}
