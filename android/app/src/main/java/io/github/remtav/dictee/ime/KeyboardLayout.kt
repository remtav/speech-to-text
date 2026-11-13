package io.github.remtav.dictee.ime

import io.github.remtav.dictee.stt.DictationLanguage

/** Letter arrangement of the text keyboard (setting; QWERTY by default). */
enum class LayoutKind(val label: String) {
    QWERTY("QWERTY"), AZERTY("AZERTY");

    companion object {
        fun fromName(n: String?) = entries.firstOrNull { it.name == n } ?: QWERTY

        /** AZERTY when "Français (France)" is among the languages chosen, QWERTY otherwise. */
        fun defaultFor(chosen: Set<DictationLanguage>) =
            if (DictationLanguage.FRENCH_FRANCE in chosen) AZERTY else QWERTY
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
 * A language the text keyboard types completely. Adding one is one entry here: its accents (by base
 * letter) and its punctuation (by key of the symbols page), most frequent first. No view code to touch.
 */
enum class KeyLanguage(
    /** The dictation languages (chosen at setup) that select this keyboard language. */
    val dictation: Set<DictationLanguage>,
    /** Accented letters of each base letter (lowercase), in the order of the long-press window. */
    val accents: Map<String, List<String>>,
    /** Punctuation reached by a long press on a key of the symbols page. */
    val marks: Map<String, List<String>>,
) {
    FRENCH(
        setOf(DictationLanguage.FRENCH_QUEBEC, DictationLanguage.FRENCH_FRANCE),
        mapOf(
            "e" to listOf("é", "è", "ê", "ë"),
            "a" to listOf("à", "â", "æ"),
            "c" to listOf("ç"),
            "i" to listOf("î", "ï"),
            "o" to listOf("ô", "œ"),
            "u" to listOf("ù", "û", "ü"),
            "y" to listOf("ÿ"),
        ),
        mapOf("'" to listOf("’"), "\"" to listOf("«", "»"), "." to listOf("…")),
    ),
    ENGLISH(
        setOf(DictationLanguage.ENGLISH),
        emptyMap(),
        mapOf("'" to listOf("’"), "\"" to listOf("“", "”"), "." to listOf("…")),
    ),
    SPANISH(
        setOf(DictationLanguage.SPANISH),
        mapOf(
            "a" to listOf("á"),
            "e" to listOf("é"),
            "i" to listOf("í"),
            "o" to listOf("ó"),
            "u" to listOf("ú", "ü"),
            "n" to listOf("ñ"),
        ),
        mapOf("?" to listOf("¿"), "!" to listOf("¡"), "\"" to listOf("«", "»", "“", "”"), "." to listOf("…")),
    ),
    PORTUGUESE(
        setOf(DictationLanguage.PORTUGUESE),
        mapOf(
            "a" to listOf("á", "ã", "à", "â"),
            "e" to listOf("é", "ê"),
            "i" to listOf("í"),
            "o" to listOf("ó", "õ", "ô"),
            "u" to listOf("ú"),
            "c" to listOf("ç"),
        ),
        mapOf("\"" to listOf("“", "”", "«", "»"), "." to listOf("…")),
    );

    companion object {
        /**
         * The keyboard languages for the languages chosen at setup. A choice that holds none of the four
         * (Mandarin alone, say) gets all four: the keyboard stays complete rather than empty.
         */
        fun forDictation(chosen: Set<DictationLanguage>): Set<KeyLanguage> =
            entries.filter { l -> l.dictation.any { it in chosen } }.toSet().ifEmpty { entries.toSet() }

        /** Punctuation of every language, on the key it is reached from. */
        private val common = mapOf("$" to listOf("€", "£", "¥", "¢"), "-" to listOf("–", "—"))

        internal fun commonMarks(key: String): List<String> = common[key].orEmpty()
    }
}

/** Pure model of the text keyboard: its rows, symbols pages and long-press windows. No Android type here. */
object KeyboardLayout {
    private val qwerty = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val azerty = listOf("azertyuiop", "qsdfghjklm", "wxcvbn")

    /** Symbols page 1: digits, then `' " - ( ) @ : ; $ %`, then `/ & ! ? , .`. */
    private val symbols = listOf("1234567890", "'\"-()@:;$%", "/&!?,.")

    /** Symbols page 2: brackets and operators, other currencies, then a few signs. */
    private val moreSymbols = listOf("[]{}#%^*+=", "_\\|~<>€£¥¢", "°•·§¶")

    /** Letter rows (lowercase); the view adds Shift and Backspace around the last one. */
    fun letterRows(kind: LayoutKind): List<List<String>> =
        (if (kind == LayoutKind.AZERTY) azerty else qwerty).map { row -> row.map { it.toString() } }

    fun symbolRows(page: Int = 1): List<List<String>> =
        (if (page == 2) moreSymbols else symbols).map { row -> row.map { it.toString() } }

    private val allLanguages = KeyLanguage.entries.toSet()

    /** Accented variants of [base] (a letter key label) for [languages], in capitals when [upper]; empty when none. */
    fun accentsFor(base: String, upper: Boolean = false, languages: Set<KeyLanguage> = allLanguages): List<String> {
        val list = KeyLanguage.entries.filter { it in languages }
            .flatMap { it.accents[base.lowercase()].orEmpty() }.distinct()
        return if (upper) list.map { it.uppercase() } else list
    }

    /** Punctuation behind a long press on the symbols-page key [key] (`?` gives `¿` in Spanish, say). */
    fun marksFor(key: String, languages: Set<KeyLanguage> = allLanguages): List<String> =
        (KeyLanguage.entries.filter { it in languages }.flatMap { it.marks[key].orEmpty() } +
            KeyLanguage.commonMarks(key)).distinct()

    /** What a long press on [key] offers: accents for a letter, punctuation for any other key. */
    fun alternatesFor(key: String, upper: Boolean, languages: Set<KeyLanguage> = allLanguages): List<String> =
        if (key.isNotEmpty() && key[0].isLetter()) accentsFor(key, upper, languages) else marksFor(key, languages)

    /** The label a letter key shows (and types) for the given shift state. */
    fun label(base: String, shift: ShiftState): String = if (shift.upper) base.uppercase() else base

    /** Every character reachable on the letter pages: keys plus accents, for both cases. */
    fun reachable(kind: LayoutKind, languages: Set<KeyLanguage> = allLanguages): Set<String> {
        val set = mutableSetOf<String>()
        for (shift in listOf(ShiftState.OFF, ShiftState.ONCE)) {
            for (row in letterRows(kind)) for (k in row) {
                set += label(k, shift)
                set += accentsFor(k, shift.upper, languages)
            }
        }
        return set
    }

    /** Everything that can be typed: letters pages, both symbols pages and their long-press windows. */
    fun reachableAll(kind: LayoutKind, languages: Set<KeyLanguage> = allLanguages): Set<String> {
        val set = reachable(kind, languages).toMutableSet()
        for (page in 1..2) for (row in symbolRows(page)) for (k in row) {
            set += k
            set += marksFor(k, languages)
        }
        return set
    }
}
