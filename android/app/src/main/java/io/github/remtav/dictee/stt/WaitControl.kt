package io.github.remtav.dictee.stt

/** Lets the keyboard stop a whole-dictation request: "Annuler" (go local) or the dictation was dropped. */
class WaitControl {
    enum class Mode { NONE, TO_LOCAL, ABANDON }

    @Volatile var mode: Mode = Mode.NONE
        private set
    private val hooks = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    val cancelled: Boolean get() = mode != Mode.NONE

    /** User pressed "Annuler": stop waiting for the server and transcribe on the phone. */
    fun toLocal() = cancel(Mode.TO_LOCAL)

    /** The dictation is dropped (field changed): stop everything, insert nothing. */
    fun abandon() = cancel(Mode.ABANDON)

    private fun cancel(m: Mode) {
        synchronized(this) {
            if (mode != Mode.NONE) return
            mode = m
        }
        hooks.forEach { runCatching { it() } }
    }

    private val localHooks = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Runs [hook] when the fallback starts transcribing on the phone (the server wait is over). */
    fun onLocalStart(hook: () -> Unit) { localHooks += hook }

    /** Called by [FallbackEngine] before it transcribes the kept audio locally. */
    fun localStarted() = localHooks.forEach { runCatching { it() } }

    /** Runs [hook] at cancellation (at once if already cancelled). */
    fun onCancel(hook: () -> Unit) {
        hooks += hook
        if (cancelled) runCatching { hook() }
    }

    @Volatile private var closed = false

    /**
     * A control for one request of a longer task (a block of a dictation, issue #35): cancelled with this one,
     * in the same mode, until it is [close]d. A finished request is closed so that a later "Annuler" does not
     * cancel its (already done) server job again.
     */
    fun child(): WaitControl {
        val c = WaitControl()
        onCancel { if (!c.closed) c.cancel(mode) }
        return c
    }

    /** The request this control was made for is over: cancelling the parent no longer reaches it. */
    fun close() { closed = true }
}
