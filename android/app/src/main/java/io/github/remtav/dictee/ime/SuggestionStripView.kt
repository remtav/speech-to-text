package io.github.remtav.dictee.ime

import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import io.github.remtav.dictee.R
import io.github.remtav.dictee.ime.words.Suggestions

/**
 * The bar above the text keys (issue #71): three words for the word being typed. The best one sits in the middle;
 * with the automatic correction on, it is the correction, in bold, and the word as typed sits on the left so a tap
 * keeps it. Always the same height, empty or not: the keyboard does not jump while typing.
 */
class SuggestionStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** A word was tapped: [word] replaces the typed word. */
    var onPick: ((word: String) -> Unit)? = null

    private val slots: List<TextView>

    /** Words currently shown, left to right ("" for an empty slot): for the tests. */
    val shown: List<String> get() = slots.map { it.text.toString() }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val ripple = TypedValue().also { context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }.resourceId
        slots = listOf(R.id.suggestion_left, R.id.suggestion_middle, R.id.suggestion_right).map { viewId ->
            TextView(context).apply {
                id = viewId
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(4), 0, dp(4), 0)
                setTextAppearance(R.style.TextAppearance_Dictee_Suggestion)
                if (ripple != 0) setBackgroundResource(ripple)
                visibility = View.INVISIBLE
                setOnClickListener { onPick?.invoke(tag as? String ?: return@setOnClickListener) }
            }.also { addView(it) }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun show(s: Suggestions) {
        val words = s.words
        // Middle, left, right: the best word in the middle.
        val order = arrayOfNulls<String>(3)
        var strong = -1
        if (s.autoCorrect != null) {
            order[1] = s.autoCorrect
            strong = 1
            order[0] = s.typed
            order[2] = words.firstOrNull { it != s.autoCorrect && it != s.typed }
        } else {
            order[1] = words.getOrNull(0)
            order[0] = words.getOrNull(1)
            order[2] = words.getOrNull(2)
        }
        slots.forEachIndexed { i, v ->
            val w = order[i]
            v.tag = w
            v.text = w ?: ""
            v.visibility = if (w == null) View.INVISIBLE else View.VISIBLE
            v.setTextAppearance(if (i == strong) R.style.TextAppearance_Dictee_Suggestion_Strong else R.style.TextAppearance_Dictee_Suggestion)
            v.contentDescription = when {
                w == null -> null
                strong >= 0 && i == 0 -> context.getString(R.string.cd_suggestion_keep, w)
                i == strong -> context.getString(R.string.cd_suggestion_correction, w)
                else -> context.getString(R.string.cd_suggestion, w)
            }
        }
    }
}
