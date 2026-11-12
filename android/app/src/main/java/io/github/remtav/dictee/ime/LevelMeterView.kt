package io.github.remtav.dictee.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/** Thin input-level bar under the status line. */
class LevelMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var shown = 0f
    private var target = 0f

    init {
        track.color = themeColor(com.google.android.material.R.attr.colorSurfaceVariant)
        bar.color = themeColor(androidx.appcompat.R.attr.colorPrimary)
    }

    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    fun setActiveColor(color: Int) { bar.color = color; invalidate() }

    fun setLevel(level: Float) {
        target = level.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // Fast attack, slow release.
        shown = if (target > shown) target else shown * 0.85f + target * 0.15f
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, track)
        rect.right = width * shown
        if (rect.right > 0.5f) canvas.drawRoundRect(rect, r, r, bar)
        if (kotlin.math.abs(shown - target) > 0.01f) postInvalidateOnAnimation()
    }
}
