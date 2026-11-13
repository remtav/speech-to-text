package io.github.remtav.dictee.reading

/**
 * Turns a stream of elapsed-time readings (one per 100 ms audio buffer) into at most one call per whole
 * second (issue #20): the screen must not be touched ten times a second while a recording is in progress.
 * [onSecond] receives the whole number of seconds, each value once, in increasing order.
 */
class SecondTicker(private val onSecond: (Int) -> Unit) {
    private var last = -1

    fun update(seconds: Float) {
        val whole = seconds.toInt()
        if (whole > last) {
            last = whole
            onSecond(whole)
        }
    }

    fun reset() { last = -1 }
}
