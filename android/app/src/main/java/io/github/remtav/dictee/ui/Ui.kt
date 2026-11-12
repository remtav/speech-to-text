package io.github.remtav.dictee.ui

import android.app.Activity
import android.content.Context
import android.text.format.Formatter
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/** Tiny helpers to build the settings-style screens in code (no layout XML to keep in sync). */
internal fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

internal fun Context.attrColor(attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.data
}

internal fun Activity.scrollingColumn(): LinearLayout {
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(32))
    }
    val scroll = ScrollView(this).apply {
        addView(column)
        setBackgroundColor(attrColor(com.google.android.material.R.attr.colorSurface))
    }
    ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
    setContentView(scroll)
    return column
}

internal fun ViewGroup.title(text: String) = TextView(context).apply {
    this.text = text
    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall)
    setPadding(0, context.dp(8), 0, context.dp(8))
}.also { addView(it) }

internal fun ViewGroup.heading(text: String) = TextView(context).apply {
    this.text = text
    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
    setTextColor(context.attrColor(androidx.appcompat.R.attr.colorPrimary))
    setPadding(0, context.dp(20), 0, context.dp(6))
}.also { addView(it) }

internal fun ViewGroup.body(text: CharSequence = "", small: Boolean = false) = TextView(context).apply {
    this.text = text
    setTextAppearance(
        if (small) com.google.android.material.R.style.TextAppearance_Material3_BodySmall
        else com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
    )
    setTextIsSelectable(false)
    setPadding(0, context.dp(2), 0, context.dp(2))
}.also { addView(it) }

internal fun ViewGroup.button(text: String, outlined: Boolean = false, onClick: (View) -> Unit) =
    MaterialButton(
        context, null,
        if (outlined) com.google.android.material.R.attr.materialButtonOutlinedStyle
        else com.google.android.material.R.attr.materialButtonStyle,
    ).apply {
        this.text = text
        setOnClickListener(onClick)
    }.also { addView(it) }

internal fun ViewGroup.row(): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
}.also { addView(it) }

internal fun ViewGroup.card(): LinearLayout {
    val inner = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
    }
    val card = MaterialCardView(context, null, com.google.android.material.R.attr.materialCardViewOutlinedStyle).apply {
        addView(inner)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = context.dp(6); bottomMargin = context.dp(6) }
    }
    addView(card)
    return inner
}

internal fun Context.size(bytes: Long): String = Formatter.formatShortFileSize(this, bytes)

internal fun View.spaced(start: Int = 0, end: Int = 8): View {
    (layoutParams as? ViewGroup.MarginLayoutParams)?.let {
        it.marginStart = context.dp(start); it.marginEnd = context.dp(end)
        layoutParams = it
    }
    return this
}
