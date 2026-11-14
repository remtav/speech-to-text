package io.github.remtav.dictee.ime

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import io.github.remtav.dictee.R
import io.github.remtav.dictee.ime.words.StripItem

/**
 * The suggestion bar above the letters (issue #71): up to three words. The word that will replace the
 * typed one is on a coloured background; what was typed, letter for letter, is in quotation marks.
 * The bar keeps its height when it is empty, so the keys do not move while one types.
 */
class SuggestionStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    var onPick: ((StripItem) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private var items: List<StripItem> = emptyList()

    private fun color(attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private val cells: List<TextView> = listOf(R.id.sug_0, R.id.sug_1, R.id.sug_2).mapIndexed { i, viewId ->
        TextView(context).apply {
            id = viewId
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setTextAppearance(R.style.TextAppearance_Dictee_Title)
            setTextColor(color(com.google.android.material.R.attr.colorOnSurface))
            setPadding(dp(6), 0, dp(6), 0)
            visibility = View.INVISIBLE
            isClickable = true
            setOnClickListener { items.getOrNull(i)?.let { item -> onPick?.invoke(item) } }
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { setMargins(dp(2), dp(4), dp(2), dp(4)) }
        }
    }

    init {
        orientation = HORIZONTAL
        cells.forEach { addView(it) }
    }

    fun show(items: List<StripItem>) {
        if (items == this.items) return
        this.items = items
        cells.forEachIndexed { i, cell ->
            val item = items.getOrNull(i)
            cell.visibility = if (item == null) View.INVISIBLE else View.VISIBLE
            if (item == null) return@forEachIndexed
            val typed = item.kind == StripItem.Kind.TYPED
            cell.text = if (typed) context.getString(R.string.suggestion_typed, item.text) else item.text
            cell.contentDescription = context.getString(
                when (item.kind) {
                    StripItem.Kind.TYPED -> R.string.cd_suggestion_typed
                    StripItem.Kind.CORRECTION -> R.string.cd_suggestion_correction
                    StripItem.Kind.WORD -> R.string.cd_suggestion
                },
                item.text,
            )
            cell.background = if (item.kind != StripItem.Kind.CORRECTION) null else GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(color(com.google.android.material.R.attr.colorSecondaryContainer))
            }
        }
    }

    /** The texts shown, for the tests. */
    fun shown(): List<StripItem> = items
}
