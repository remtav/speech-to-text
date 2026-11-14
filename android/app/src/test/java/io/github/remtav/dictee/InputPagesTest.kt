package io.github.remtav.dictee

import android.text.InputType
import io.github.remtav.dictee.ime.DecimalSeparator
import io.github.remtav.dictee.ime.InputPage
import io.github.remtav.dictee.ime.InputPages
import io.github.remtav.dictee.ime.PadKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Issue #42: the page chosen from `EditorInfo.inputType` (a pure function), and the decimal separator order. */
class InputPagesTest {

    private val number = InputType.TYPE_CLASS_NUMBER
    private val digits = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))

    private fun pad(type: Int) = InputPages.forInputType(type) as InputPage.Pad

    @Test
    fun textFieldsKeepTheUsualViews() {
        assertEquals(InputPage.Text, InputPages.forInputType(InputType.TYPE_CLASS_TEXT))
        assertEquals(InputPage.Text, InputPages.forInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertEquals(InputPage.Text, InputPages.forInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertEquals(InputPage.Text, InputPages.forInputType(InputType.TYPE_NULL))
    }

    @Test
    fun plainNumberFieldHasDigitsOnly() {
        val p = pad(number)
        assertEquals(digits + listOf(listOf("0")), p.rows)
        assertEquals("0123456789".map { it.toString() }.toSet(), p.characters)
        assertFalse(p.hasDecimal)
    }

    @Test
    fun minusOnlyWhenTheFieldIsSigned() {
        assertEquals(listOf("-", "0"), pad(number or InputType.TYPE_NUMBER_FLAG_SIGNED).rows.last())
        assertFalse("-" in pad(number or InputType.TYPE_NUMBER_FLAG_DECIMAL).characters)
    }

    @Test
    fun decimalKeyOnlyWhenTheFieldIsDecimal() {
        assertEquals(listOf("0", PadKeys.DECIMAL), pad(number or InputType.TYPE_NUMBER_FLAG_DECIMAL).rows.last())
        assertEquals(
            listOf("-", "0", PadKeys.DECIMAL),
            pad(number or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED).rows.last(),
        )
        assertTrue(pad(number or InputType.TYPE_NUMBER_FLAG_DECIMAL).hasDecimal)
    }

    @Test
    fun numericPasswordTakesTheSamePad() {
        assertEquals(pad(number), pad(number or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
    }

    @Test
    fun phoneFieldHasDigitsAndPhoneSigns() {
        val p = pad(InputType.TYPE_CLASS_PHONE)
        assertEquals(digits, p.rows.take(3))
        for (c in "0123456789+*#()- ") assertTrue("'$c' missing", c.toString() in p.characters)
        assertEquals(listOf("*", "0", "#"), p.rows[3])
    }

    @Test
    fun dateAndTimeFieldsKeepOnlyWhatTheyNeed() {
        val date = pad(InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE).characters
        val time = pad(InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME).characters
        val both = pad(InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_NORMAL).characters
        assertTrue("/" in date && "-" in date && ":" !in date && " " !in date)
        assertTrue(":" in time && "/" !in time && "-" !in time)
        for (c in "/-: ") assertTrue("'$c' missing", c.toString() in both)
        for (set in listOf(date, time, both)) for (c in "0123456789") assertTrue(c.toString() in set)
    }

    @Test
    fun everyPadHasTheTelephoneDigitsOnTop() {
        for (type in listOf(
            number, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME,
            InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE,
        )) assertEquals(digits, pad(type).rows.take(3))
    }

    @Test
    fun decimalSeparatorFollowsThePhoneButFallsBackOnTheOther() {
        assertEquals(listOf(",", "."), DecimalSeparator.order(Locale.FRANCE))
        assertEquals(listOf(".", ","), DecimalSeparator.order(Locale.US))
        assertEquals(listOf(",", "."), DecimalSeparator.order(Locale("pt", "BR")))
    }
}
