package io.github.remtav.dictee.stt

/**
 * Issue #66, point 3: the one guard of the three measuring screens (the quick test, « Comparer les modèles » and the
 * reading test). Each of them loads models itself, so:
 *
 * - **One screen at a time, with a token.** [tryClaim] gives a [Claim] only when nothing measures and no decode
 *   lingers; only that claim can [release] the lock. A thread that outlives its screen can thus never give the
 *   keyboard back in the middle of another screen's measure, nor release that screen's engine: the other screen
 *   could not have started.
 * - **The keyboard waits** while a screen holds the lock ([held]): it does not load its own model meanwhile.
 * - **A decode over its budget** (sherpa-onnx cannot interrupt one) is counted by [beginLingering] until it ends.
 *   The keyboard gets the engine back at once (it is not [held]), but no screen starts a measure meanwhile, so
 *   two big models are never loaded together. A count, not a flag: the end of one decode does not hide another
 *   (issue #66, point 4).
 *
 * Pure Kotlin, for the JVM tests (`BenchmarkLockTest`); one instance for the process (`DicteeApp.benchmark`).
 */
class BenchmarkLock {
    /** Proof of ownership; [holder] names the screen, for the logs and the tests. */
    class Claim internal constructor(val holder: String)

    /** A decode that went on alone; [end] is called once it is over (calling it again does nothing). */
    inner class Lingering internal constructor() {
        private var ended = false

        fun end() {
            synchronized(this@BenchmarkLock) {
                if (ended) return
                ended = true
                lingeringCount--
            }
        }
    }

    private var owner: Claim? = null
    private var lingeringCount = 0

    /** A measuring screen holds the lock: the keyboard must not load another model meanwhile. */
    val held: Boolean get() = synchronized(this) { owner != null }

    /** At least one decode of an earlier measure is still running. */
    val lingering: Boolean get() = synchronized(this) { lingeringCount > 0 }

    /** No measure can start now: one is running, or a decode of an earlier one has not ended. */
    val busy: Boolean get() = synchronized(this) { owner != null || lingeringCount > 0 }

    /** The lock for [holder], or null when it is [busy]. */
    fun tryClaim(holder: String): Claim? = synchronized(this) {
        if (owner != null || lingeringCount > 0) null else Claim(holder).also { owner = it }
    }

    /** Gives the lock back if [claim] still owns it; returns whether it did. Never frees another screen's lock. */
    fun release(claim: Claim): Boolean = synchronized(this) {
        if (owner === claim) {
            owner = null
            true
        } else {
            false
        }
    }

    /** Counts a decode that goes on alone, until [Lingering.end]. */
    fun beginLingering(): Lingering = synchronized(this) {
        lingeringCount++
        Lingering()
    }
}
