package io.github.remtav.dictee.ime

import android.text.InputType
import java.util.Locale

/**
 * Which page the keyboard opens for a field (issue #42): the usual views for text, a number pad for the
 * fields that take digits. A pure function of `EditorInfo.inputType`, tested on the JVM; the pad's keys
 * are only the ones the field accepts.
 */
sealed interface InputPage {
    /** The usual views: microphone, or the text keys. */
    object Text : InputPage

    /**
     * A pad of big keys, `1 2 3` on top. [rows] are the keys of the left block (equal widths inside a row);
     * [PadKeys.SPACE] is the space bar and [PadKeys.DECIMAL] the decimal separator. Backspace, the switch
     * to another keyboard and Enter are added on the right by the view. No microphone: see DECISIONS.md.
     */
    data class Pad(val rows: List<List<String>>) : InputPage {
        /** Every character this pad can type. */
        val characters: Set<String> get() = rows.flatten().filter { it != PadKeys.DECIMAL }.toSet()
        val hasDecimal: Boolean get() = rows.any { PadKeys.DECIMAL in it }
    }
}

object PadKeys {
    /** Placeholder for the decimal separator, which depends on what the field takes (see [DecimalSeparator]). */
    const val DECIMAL = "<decimal>"
    const val SPACE = " "
}

object InputPages {
    private val digits = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))

    fun forInputType(inputType: Int): InputPage {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val flags = inputType and InputType.TYPE_MASK_FLAGS
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> {
                // A numeric password is a number field with no flag: digits only.
                val signed = flags and InputType.TYPE_NUMBER_FLAG_SIGNED != 0
                val decimal = flags and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0
                val last = buildList {
                    if (signed) add("-")
                    add("0")
                    if (decimal) add(PadKeys.DECIMAL)
                }
                InputPage.Pad(digits + listOf(last))
            }
            InputType.TYPE_CLASS_PHONE ->
                InputPage.Pad(digits + listOf(listOf("*", "0", "#"), listOf("+", "(", ")", "-", PadKeys.SPACE)))
            InputType.TYPE_CLASS_DATETIME -> when (variation) {
                InputType.TYPE_DATETIME_VARIATION_DATE -> InputPage.Pad(digits + listOf(listOf("/", "0", "-")))
                InputType.TYPE_DATETIME_VARIATION_TIME -> InputPage.Pad(digits + listOf(listOf(":", "0")))
                else -> InputPage.Pad(digits + listOf(listOf("/", "0", "-"), listOf(":", PadKeys.SPACE)))
            }
            else -> InputPage.Text
        }
    }
}

/**
 * The decimal separator. Android lets each field filter what it takes, and many decimal fields take only
 * the point: what the field accepts matters more than the phone's language. The key therefore starts with
 * the phone's separator and, when the field refuses it, the service types the other one (see
 * `DicteeImeService.typeDecimal`). The order to try is this pure function.
 */
object DecimalSeparator {
    fun order(locale: Locale): List<String> {
        val first = when (java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator) {
            ',' -> ","
            else -> "."
        }
        return listOf(first, if (first == ".") "," else ".")
    }
}
