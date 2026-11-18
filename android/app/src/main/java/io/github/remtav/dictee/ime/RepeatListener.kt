package io.github.remtav.dictee.ime

import android.annotation.SuppressLint
import android.os.Handler
import android.view.MotionEvent
import android.view.View

/** Repeats an action while the key is held (backspace), on [handler]'s thread. */
internal class RepeatListener(private val handler: Handler, private val action: () -> Unit) : View.OnTouchListener {
    private val repeat = object : Runnable {
        override fun run() { action(); handler.postDelayed(this, 60) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { v.isPressed = true; action(); handler.postDelayed(repeat, 400) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; handler.removeCallbacks(repeat) }
        }
        return true
    }
}
