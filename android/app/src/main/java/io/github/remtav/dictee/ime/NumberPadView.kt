package io.github.remtav.dictee.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.remtav.dictee.R

/**
 * The pad for digit fields (issue #42), built from an [InputPage.Pad] with plain views, in the look of the
 * letter keys. Big keys, `1 2 3` on top; Backspace, the switch to another keyboard and Enter on the right.
 */
class NumberPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    interface Listener {
        fun onText(text: String)
        /** The decimal key was pressed; the service types the separator the field accepts. */
        fun onDecimal()
        fun onEnter()
        fun onSwitch()
    }

    var listener: Listener? = null
    var onKeyPressed: ((View) -> Unit)? = null

    var backspaceTouchListener: OnTouchListener? = null
        set(v) { field = v; rebuild() }

    var page: InputPage.Pad? = null
        set(v) { if (field != v) { field = v; rebuild() } }

    /** What the decimal key shows. */
    var decimalLabel: String = "."
        set(v) { field = v; decimalKey?.text = v }

    private var decimalKey: TextView? = null
    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    init { orientation = HORIZONTAL }

    private fun color(attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun background(special: Boolean, pressed: Boolean = false) = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(
            color(
                when {
                    pressed -> com.google.android.material.R.attr.colorSecondaryContainer
                    special -> com.google.android.material.R.attr.colorSurfaceContainerHigh
                    else -> com.google.android.material.R.attr.colorSurfaceContainerHighest
                },
            ),
        )
    }

    private fun params(weight: Float) =
        LayoutParams(0, LayoutParams.MATCH_PARENT, weight).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }

    private fun vParams(weight: Float) =
        LayoutParams(LayoutParams.MATCH_PARENT, 0, weight).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }

    @SuppressLint("ClickableViewAccessibility")
    private fun textKey(label: String, onTap: () -> Unit): TextView = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        setTextColor(color(com.google.android.material.R.attr.colorOnSurface))
        text = label
        background = background(special = false)
        isClickable = true
        if (label == PadKeys.SPACE) {
            text = ""
            contentDescription = context.getString(R.string.cd_space)
            setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_space, 0, 0)
        }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.background = background(false, pressed = true); onKeyPressed?.invoke(v) }
                MotionEvent.ACTION_UP -> { v.background = background(false); onTap() }
                MotionEvent.ACTION_CANCEL -> v.background = background(false)
            }
            true
        }
    }

    private fun iconKey(icon: Int, description: Int, keyId: Int, onClick: (() -> Unit)?): ImageView = ImageView(context).apply {
        setImageResource(icon)
        scaleType = ImageView.ScaleType.CENTER
        background = background(special = true)
        isClickable = true
        contentDescription = context.getString(description)
        id = keyId
        if (onClick != null) setOnClickListener { v -> onKeyPressed?.invoke(v); onClick() }
    }

    private fun rebuild() {
        removeAllViews()
        decimalKey = null
        val pad = page ?: return
        val left = LinearLayout(context).apply { orientation = VERTICAL }
        for (row in pad.rows) {
            val r = LinearLayout(context).apply { orientation = HORIZONTAL }
            // Every row fills the block's width: a row of two or five keys shares it like a row of three.
            for (k in row) {
                val key = when (k) {
                    PadKeys.DECIMAL -> textKey(decimalLabel) { listener?.onDecimal() }.also {
                        decimalKey = it
                        it.id = R.id.pad_decimal
                        it.contentDescription = context.getString(R.string.cd_decimal)
                    }
                    else -> textKey(k) { listener?.onText(k) }
                }
                r.addView(key, params(1f))
            }
            left.addView(r, LayoutParams(LayoutParams.MATCH_PARENT, dp(KEY_HEIGHT_DP)))
        }
        addView(left, LayoutParams(0, LayoutParams.WRAP_CONTENT, 3f))

        val right = LinearLayout(context).apply { orientation = VERTICAL }
        val backspace = iconKey(R.drawable.ic_backspace, R.string.cd_backspace, R.id.pad_backspace, null)
        backspace.setOnTouchListener(backspaceTouchListener)
        right.addView(backspace, vParams(1f))
        right.addView(iconKey(R.drawable.ic_keyboard, R.string.cd_switch, R.id.pad_switch) { listener?.onSwitch() }, vParams(1f))
        right.addView(
            iconKey(R.drawable.ic_enter, R.string.cd_enter, R.id.pad_enter) { listener?.onEnter() },
            vParams((pad.rows.size - 2).coerceAtLeast(1).toFloat()),
        )
        addView(right, LayoutParams(0, dp(KEY_HEIGHT_DP) * pad.rows.size, 1f))
    }

    companion object {
        /** Big keys: 56 dp tall, more than the 48 dp touch target. */
        const val KEY_HEIGHT_DP = 56
    }
}
