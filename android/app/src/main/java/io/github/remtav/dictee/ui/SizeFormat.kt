package io.github.remtav.dictee.ui

import java.util.Locale

/**
 * Sizes in French units (o, ko, Mo, Go), decimal like the rest of the screens (671 Mo, 1 037 Mo),
 * whatever the language of the phone: Android's own formatter writes "MB" and "GB" under an
 * English system. Pure Kotlin, tested on the JVM.
 */
object SizeFormat {
    fun format(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        return when {
            b < 1_000 -> "$b o"
            b < 1_000_000 -> number(b / 1e3) + " ko"
            b < 1_000_000_000 -> number(b / 1e6) + " Mo"
            else -> number(b / 1e9) + " Go"
        }
    }

    /** 3 digits at most before the comma, and a decimal comma: 1,04 · 12,3 · 671. */
    private fun number(v: Double): String = when {
        v >= 100 -> String.format(Locale.FRENCH, "%.0f", v)
        v >= 10 -> String.format(Locale.FRENCH, "%.1f", v)
        else -> String.format(Locale.FRENCH, "%.2f", v)
    }
}
