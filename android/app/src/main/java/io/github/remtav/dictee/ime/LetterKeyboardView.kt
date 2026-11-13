package io.github.remtav.dictee.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import io.github.remtav.dictee.R

/**
 * The text keyboard's keys (letters or symbols page, Shift, Backspace), built from [KeyboardLayout]
 * with plain views. A long press on a letter opens a window of its accents above the key.
 * The bottom row (keyboard switch, symbols, microphone, space, Enter) lives in the layout of the service.
 */
class LetterKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    interface Listener {
        /** A character (letter, accent, symbol) was chosen. */
        fun onText(text: String)
    }

    var listener: Listener? = null

    /** Touch listener of the Backspace key (the service repeats it while held). */
    var backspaceTouchListener: OnTouchListener? = null
        set(v) { field = v; rebuild() }

    /** Called when the keys were pressed (haptic feedback). */
    var onKeyPressed: ((View) -> Unit)? = null

    var kind: LayoutKind = LayoutKind.QWERTY
        set(v) { if (field != v) { field = v; rebuild() } }

    var symbols: Boolean = false
        set(v) { if (field != v) { field = v; rebuild() } }

    var shift: ShiftState = ShiftState.OFF
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var popup: PopupWindow? = null
    private var lastShiftTap = 0L
    private val letterKeys = mutableListOf<Pair<String, TextView>>()
    private var shiftKey: ImageView? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    init {
        orientation = VERTICAL
        rebuild()
    }

    override fun onDetachedFromWindow() {
        dismissPopup()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) dismissPopup()
    }

    /** Back to lowercase and the letters page (new field). */
    fun reset() {
        shift = ShiftState.OFF
        symbols = false
        refreshLabels()
    }

    private fun color(attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun keyBackground(pressed: Boolean = false, special: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
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

    private fun rowContainer(): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(KEY_HEIGHT_DP))
    }

    private fun keyParams(weight: Float) = LayoutParams(0, LayoutParams.MATCH_PARENT, weight).apply {
        setMargins(dp(2), dp(2), dp(2), dp(2))
    }

    private fun rebuild() {
        dismissPopup()
        removeAllViews()
        letterKeys.clear()
        shiftKey = null
        val rows = if (symbols) KeyboardLayout.symbolRows() else KeyboardLayout.letterRows(kind)
        rows.forEachIndexed { i, keys ->
            val row = rowContainer()
            val last = i == rows.lastIndex
            if (last && !symbols) row.addView(shiftKey().also { shiftKey = it }, keyParams(1.5f))
            // The shorter rows are centred, with a half key of margin: AZERTY's middle row is full width.
            else if (!last && keys.size < rows[0].size) row.addView(spacer(), keyParams((rows[0].size - keys.size) / 2f))
            for (k in keys) row.addView(letterKey(k), keyParams(1f))
            if (!last && keys.size < rows[0].size) row.addView(spacer(), keyParams((rows[0].size - keys.size) / 2f))
            if (last) row.addView(backspaceKey(), keyParams(1.5f))
            addView(row)
        }
        refreshLabels()
    }

    private fun spacer() = View(context)

    private fun baseKey(): TextView = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        setTextColor(color(com.google.android.material.R.attr.colorOnSurface))
        background = keyBackground()
        minimumHeight = dp(KEY_HEIGHT_DP)
        isClickable = true
        isFocusable = false
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun letterKey(base: String): TextView {
        val key = baseKey()
        key.text = base
        letterKeys += base to key
        val isLetter = base[0].isLetter()
        var longPressed = false
        val longPress = Runnable {
            val accents = KeyboardLayout.accentsFor(base, shift.upper)
            if (accents.isNotEmpty()) {
                longPressed = true
                showAccents(key, accents)
            }
        }
        key.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dismissPopup()
                    longPressed = false
                    v.background = keyBackground(pressed = true)
                    onKeyPressed?.invoke(v)
                    if (isLetter) handler.postDelayed(longPress, LONG_PRESS_MS)
                }
                MotionEvent.ACTION_MOVE -> popup?.let { highlightAt(e.rawX) }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    v.background = keyBackground()
                    if (longPressed) {
                        // The finger slid onto an accent: type it. Released on the key: the window stays, tap an accent.
                        chosenAt(e.rawX)?.let { typeAccent(it) }
                    } else {
                        listener?.onText(KeyboardLayout.label(base, shift))
                        if (isLetter) afterLetter()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    v.background = keyBackground()
                    dismissPopup()
                }
            }
            true
        }
        return key
    }

    private fun afterLetter() {
        val next = shift.afterLetter()
        if (next != shift) { shift = next; refreshLabels() }
    }

    private fun shiftKey(): ImageView = ImageView(context).apply {
        setImageResource(R.drawable.ic_shift)
        scaleType = ImageView.ScaleType.CENTER
        background = keyBackground(special = true)
        isClickable = true
        contentDescription = context.getString(R.string.cd_shift)
        setOnClickListener { v ->
            onKeyPressed?.invoke(v)
            val now = SystemClock.uptimeMillis()
            shift = shift.tapped(doubleTap = now - lastShiftTap < ShiftState.DOUBLE_TAP_MS)
            lastShiftTap = now
            refreshLabels()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun backspaceKey(): ImageView = ImageView(context).apply {
        setImageResource(R.drawable.ic_backspace)
        scaleType = ImageView.ScaleType.CENTER
        background = keyBackground(special = true)
        isClickable = true
        contentDescription = context.getString(R.string.cd_backspace)
        id = R.id.kb_backspace
        setOnTouchListener(backspaceTouchListener)
    }

    private fun refreshLabels() {
        for ((base, key) in letterKeys) if (base[0].isLetter()) key.text = KeyboardLayout.label(base, shift)
        shiftKey?.let {
            it.isActivated = shift.upper
            it.alpha = if (shift.upper) 1f else 0.7f
            it.background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(
                    color(
                        if (shift == ShiftState.LOCKED) androidx.appcompat.R.attr.colorPrimary
                        else if (shift == ShiftState.ONCE) com.google.android.material.R.attr.colorSecondaryContainer
                        else com.google.android.material.R.attr.colorSurfaceContainerHigh,
                    ),
                )
            }
            it.contentDescription = context.getString(
                when (shift) {
                    ShiftState.OFF -> R.string.cd_shift
                    ShiftState.ONCE -> R.string.cd_shift_once
                    ShiftState.LOCKED -> R.string.cd_shift_locked
                },
            )
        }
    }

    // ----------------------------------------------------------------- accents

    private var accentViews: List<TextView> = emptyList()

    private fun showAccents(anchor: View, accents: List<String>) {
        dismissPopup()
        val box = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(color(com.google.android.material.R.attr.colorSurfaceContainerHighest))
                setStroke(dp(1), color(com.google.android.material.R.attr.colorOutline))
            }
        }
        val views = accents.map { a ->
            TextView(context).apply {
                text = a
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                setTextColor(color(com.google.android.material.R.attr.colorOnSurface))
                minWidth = dp(KEY_HEIGHT_DP)
                minHeight = dp(KEY_HEIGHT_DP)
                isClickable = true
                setOnClickListener { typeAccent(a) }
            }
        }
        views.forEach { box.addView(it) }
        accentViews = views
        val w = dp(8) + dp(KEY_HEIGHT_DP) * accents.size
        val h = dp(8) + dp(KEY_HEIGHT_DP)
        val pw = PopupWindow(box, w, h, false).apply {
            isOutsideTouchable = false
            elevation = dp(6).toFloat()
        }
        // Above the key, kept inside the screen.
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val screenW = resources.displayMetrics.widthPixels
        val x = (loc[0] + anchor.width / 2 - w / 2).coerceIn(0, (screenW - w).coerceAtLeast(0))
        val y = loc[1] - h - dp(4)
        pw.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        popup = pw
    }

    private fun viewAt(rawX: Float): TextView? {
        val loc = IntArray(2)
        return accentViews.firstOrNull { v ->
            v.getLocationOnScreen(loc)
            rawX >= loc[0] && rawX < loc[0] + v.width
        }
    }

    private fun highlightAt(rawX: Float) {
        val hit = viewAt(rawX)
        for (v in accentViews) v.background = if (v === hit) {
            GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(color(com.google.android.material.R.attr.colorSecondaryContainer))
            }
        } else null
    }

    private fun chosenAt(rawX: Float): String? = viewAt(rawX)?.text?.toString()

    private fun typeAccent(accent: String) {
        listener?.onText(accent)
        dismissPopup()
        afterLetter()
    }

    private fun dismissPopup() {
        handler.removeCallbacksAndMessages(null)
        popup?.dismiss()
        popup = null
        accentViews = emptyList()
    }

    companion object {
        /** Keys are at least 48 dp tall (touch target). */
        const val KEY_HEIGHT_DP = 48
        const val LONG_PRESS_MS = 400L
    }
}
