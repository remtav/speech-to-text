package io.github.remtav.dictee.ime

/** Letter arrangement of the text keyboard (setting; QWERTY by default). */
enum class LayoutKind(val label: String) {
    QWERTY("QWERTY"), AZERTY("AZERTY");

    companion object {
        fun fromName(n: String?) = entries.firstOrNull { it.name == n } ?: QWERTY
    }
}

/** Shift key state: off, one capital letter, or locked (double tap). */
enum class ShiftState {
    OFF, ONCE, LOCKED;

    val upper: Boolean get() = this != OFF

    /** A tap on Shift; [doubleTap] when it follows another tap closely: locks. */
    fun tapped(doubleTap: Boolean): ShiftState = when {
        this == LOCKED -> OFF
        doubleTap && this == ONCE -> LOCKED
        this == ONCE -> OFF
        else -> ONCE
    }

    /** After a letter was typed: a single capital is used up, a lock stays. */
    fun afterLetter(): ShiftState = if (this == ONCE) OFF else this

    companion object {
        /** Two taps on Shift closer than this lock it (ms). */
        const val DOUBLE_TAP_MS = 400L
    }
}

/**
 * Pure model of the text keyboard: the rows of each layout, the symbols page and
 * the accents reached by a long press. No Android type here, so the JVM tests cover it.
 */
object KeyboardLayout {
    private val qwerty = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val azerty = listOf("azertyuiop", "qsdfghjklm", "wxcvbn")

    /** Symbols page: digits, then `' " - ( ) @ : ; $ %`, then `/ & ! ? , .`. */
    private val symbols = listOf("1234567890", "'\"-()@:;$%", "/&!?,.")

    /** Letter rows (lowercase); the view adds Shift and Backspace around the last one. */
    fun letterRows(kind: LayoutKind): List<List<String>> =
        (if (kind == LayoutKind.AZERTY) azerty else qwerty).map { row -> row.map { it.toString() } }

    fun symbolRows(): List<List<String>> = symbols.map { row -> row.map { it.toString() } }

    /** Accents of each base letter (lowercase), in the order of the long-press window. */
    private val accents: Map<String, List<String>> = mapOf(
        "e" to listOf("é", "è", "ê", "ë"),
        "a" to listOf("à", "â", "æ"),
        "c" to listOf("ç"),
        "i" to listOf("î", "ï"),
        "o" to listOf("ô", "œ"),
        "u" to listOf("ù", "û", "ü"),
        "y" to listOf("ÿ"),
    )

    /** Accented variants of [base] (a letter key label), in capitals when [upper]; empty when none. */
    fun accentsFor(base: String, upper: Boolean = false): List<String> {
        val list = accents[base.lowercase()] ?: return emptyList()
        return if (upper) list.map { it.uppercase() } else list
    }

    /** The label a letter key shows (and types) for the given shift state. */
    fun label(base: String, shift: ShiftState): String = if (shift.upper) base.uppercase() else base

    /** Every character reachable on the letter pages: keys plus accents, for both cases. */
    fun reachable(kind: LayoutKind): Set<String> {
        val set = mutableSetOf<String>()
        for (shift in listOf(ShiftState.OFF, ShiftState.ONCE)) {
            for (row in letterRows(kind)) for (k in row) {
                set += label(k, shift)
                set += accentsFor(k, shift.upper)
            }
        }
        return set
    }
}
