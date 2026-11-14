package io.github.remtav.dictee.ui

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import io.github.remtav.dictee.R
import io.github.remtav.dictee.settings.AppSettings
import io.github.remtav.dictee.stt.DictationLanguage

/**
 * Tiny helpers to build the settings-style screens in code (no layout XML to keep in sync).
 *
 * Look: generic Material 3 theme. Colours come only from theme attributes and text only from the named
 * text styles of `res/values/themes.xml` (headline, title, body, label, value): no colour or font is
 * written here (checked by `NoHardcodedStyleTest`). A new screen uses these helpers.
 */
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
    setTextAppearance(R.style.TextAppearance_Dictee_Headline)
    setPadding(0, context.dp(8), 0, context.dp(8))
}.also { addView(it) }

internal fun ViewGroup.heading(text: String) = TextView(context).apply {
    this.text = text
    setTextAppearance(R.style.TextAppearance_Dictee_Title)
    setTextColor(context.attrColor(androidx.appcompat.R.attr.colorPrimary))
    setPadding(0, context.dp(20), 0, context.dp(6))
}.also { addView(it) }

internal fun ViewGroup.body(text: CharSequence = "", small: Boolean = false) = TextView(context).apply {
    this.text = text
    setTextAppearance(
        if (small) R.style.TextAppearance_Dictee_BodySmall else R.style.TextAppearance_Dictee_Body,
    )
    setTextIsSelectable(false)
    setPadding(0, context.dp(2), 0, context.dp(2))
}.also { addView(it) }

/** Text to read aloud (reading test): large and airy. Colour and font come from the theme. */
internal fun ViewGroup.reading(text: CharSequence, tagValue: String? = null) = TextView(context).apply {
    this.text = text
    tagValue?.let { tag = it }
    setTextAppearance(R.style.TextAppearance_Dictee_Reading)
    setLineSpacing(0f, 1.4f)
    setPadding(0, context.dp(12), 0, context.dp(12))
}.also { addView(it) }

/** A technical value (size, score, timing, state). */
internal fun ViewGroup.value(text: CharSequence) = TextView(context).apply {
    this.text = text
    setTextAppearance(R.style.TextAppearance_Dictee_Value)
    setPadding(0, context.dp(2), 0, context.dp(2))
}.also { addView(it) }

/** A small label in the secondary text colour. */
internal fun ViewGroup.label(text: CharSequence) = TextView(context).apply {
    this.text = text
    setTextAppearance(R.style.TextAppearance_Dictee_Label)
    setTextColor(context.attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
    setPadding(0, context.dp(2), 0, context.dp(2))
}.also { addView(it) }

internal fun ViewGroup.button(text: String, outlined: Boolean = false, onClick: (View) -> Unit) =
    MaterialButton(
        context, null,
        if (outlined) com.google.android.material.R.attr.materialButtonOutlinedStyle
        else com.google.android.material.R.attr.materialButtonStyle,
    ).apply {
        this.text = text
        setTag(R.id.button_outlined, outlined)
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

/**
 * A section folded at opening (issue #57): a header that says what it holds, touched to show or hide
 * [content]. The header is a title-styled line with a chevron, at least 48 dp tall; its state (folded or
 * unfolded) is announced to accessibility services.
 */
internal class Collapsible(val header: TextView, val content: LinearLayout, private val title: String) {
    val expanded: Boolean get() = content.visibility == View.VISIBLE

    fun expand(open: Boolean) {
        content.visibility = if (open) View.VISIBLE else View.GONE
        header.text = (if (open) "▾ " else "▸ ") + title
        ViewCompat.setStateDescription(
            header,
            header.context.getString(if (open) R.string.collapsible_open else R.string.collapsible_closed),
        )
    }
}

internal fun ViewGroup.collapsible(title: String, headerTag: String, contentTag: String): Collapsible {
    val header = TextView(context).apply {
        tag = headerTag
        setTextAppearance(R.style.TextAppearance_Dictee_Title)
        setTextColor(context.attrColor(androidx.appcompat.R.attr.colorPrimary))
        setPadding(0, context.dp(16), 0, context.dp(8))
        minHeight = context.dp(48)
        isClickable = true
        isFocusable = true
        val ripple = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        setBackgroundResource(ripple.resourceId)
    }
    addView(header)
    val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; tag = contentTag }
    addView(content)
    val section = Collapsible(header, content, title)
    section.expand(false)
    header.setOnClickListener { section.expand(!section.expanded) }
    return section
}

/** Vertical offset of [view] inside [ancestor] (sum of the tops of the views in between). */
internal fun topWithin(ancestor: View, view: View): Int {
    var y = 0
    var v: View = view
    while (v !== ancestor) {
        y += v.top
        v = v.parent as? View ?: break
    }
    return y
}

/** A size in French units (Mo, Go), whatever the language of the phone. */
@Suppress("UnusedReceiverParameter")
internal fun Context.size(bytes: Long): String = SizeFormat.format(bytes)

internal fun View.spaced(start: Int = 0, end: Int = 8): View {
    (layoutParams as? ViewGroup.MarginLayoutParams)?.let {
        it.marginStart = context.dp(start); it.marginEnd = context.dp(end)
        layoutParams = it
    }
    return this
}

/** A short label in the primary colour (theme attribute), e.g. the "recommended" badge. */
internal fun ViewGroup.badge(text: CharSequence, viewId: Int = View.NO_ID) = TextView(context).apply {
    this.text = text
    if (viewId != View.NO_ID) id = viewId
    setTextAppearance(R.style.TextAppearance_Dictee_Label)
    setTextColor(context.attrColor(androidx.appcompat.R.attr.colorPrimary))
    setPadding(0, context.dp(2), 0, context.dp(2))
}.also { addView(it) }

/**
 * Shows this text as an error (theme error colour, red, plus the error icon: never the colour
 * alone) or, with [isError] false, back in the normal text colour without the icon.
 */
internal fun TextView.markError(isError: Boolean = true) {
    setTextColor(
        context.attrColor(
            if (isError) androidx.appcompat.R.attr.colorError else com.google.android.material.R.attr.colorOnSurface,
        ),
    )
    setCompoundDrawablesRelativeWithIntrinsicBounds(if (isError) R.drawable.ic_error else 0, 0, 0, 0)
    compoundDrawablePadding = context.dp(8)
}

/** A warning line: always text and an icon (never only a colour), in the theme's error colour. */
internal fun ViewGroup.warning(text: CharSequence, tagValue: String) = TextView(context).apply {
    this.text = text
    tag = tagValue
    setTextAppearance(R.style.TextAppearance_Dictee_BodySmall)
    markError()
    setPadding(0, context.dp(2), 0, context.dp(2))
}.also { addView(it) }

/** Tag of the check box of [language], for tests. */
internal fun languageTag(language: DictationLanguage) = "lang_${language.name}"

/**
 * (Re)draws the multiple-choice list of dictation languages into this column and
 * saves each change. The last checked language cannot be unchecked: an empty
 * choice would leave the recommendation and the engines without a language, so
 * the box stays checked and a short message says why. [onChange] runs after a save.
 */
internal fun LinearLayout.renderLanguageChoices(settings: AppSettings, onChange: () -> Unit) {
    removeAllViews()
    val chosen = settings.languages
    var updating = false
    for (language in DictationLanguage.entries) {
        addView(CheckBox(context).apply {
            text = language.label
            tag = languageTag(language)
            isChecked = language in chosen
            setOnCheckedChangeListener { box, checked ->
                if (updating) return@setOnCheckedChangeListener
                val next = if (checked) settings.languages + language else settings.languages - language
                if (next.isEmpty()) {
                    updating = true
                    box.isChecked = true
                    updating = false
                    Toast.makeText(context, R.string.languages_keep_one, Toast.LENGTH_SHORT).show()
                } else {
                    settings.languages = next
                    // Issue #22: an active model that no longer suits the chosen languages is replaced.
                    io.github.remtav.dictee.DicteeApp.from(context).migrateModels()
                    onChange()
                }
            }
        })
    }
}

/** Tags of the participation card and its buttons, for tests. */
internal const val PARTICIPATION_CARD_TAG = "participation_card"
internal const val PARTICIPATION_START_TAG = "participation_start"
internal const val PARTICIPATION_DISMISS_TAG = "participation_dismiss"

/**
 * The card « Aider à améliorer la dictée en français québécois » (issue #15, lot 4): two sentences and a
 * button that starts the reading test. [onDismiss] adds the close button (main screen only: in the settings
 * the card stays). [filled] false on the installation screen, whose one filled button is the next step
 * (issue #55). Showing it changes nothing in how the app works; the decision to show it is
 * [io.github.remtav.dictee.reading.Participation].
 */
internal fun ViewGroup.participationCard(onStart: () -> Unit, onDismiss: (() -> Unit)?, filled: Boolean = true) {
    val card = card()
    card.tag = PARTICIPATION_CARD_TAG
    card.heading(context.getString(R.string.contrib_card_title)).setPadding(0, 0, 0, context.dp(6))
    card.body(context.getString(R.string.contrib_card_body), small = true)
    card.row().apply {
        button(context.getString(R.string.contrib_card_start), outlined = !filled) { onStart() }.apply { tag = PARTICIPATION_START_TAG }.spaced()
        if (onDismiss != null) {
            button(context.getString(R.string.contrib_card_dismiss), outlined = true) { onDismiss() }
                .apply { tag = PARTICIPATION_DISMISS_TAG }
        }
    }
}
