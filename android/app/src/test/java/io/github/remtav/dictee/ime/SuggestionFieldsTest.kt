package io.github.remtav.dictee.ime

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #71: which fields get word suggestions. */
class SuggestionFieldsTest {
    private val text = InputType.TYPE_CLASS_TEXT

    @Test
    fun plainTextFieldsGetThem() {
        assertTrue(suggestionsWanted(text))
        assertTrue(suggestionsWanted(text or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertTrue(suggestionsWanted(text or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE))
        assertTrue(suggestionsWanted(text or InputType.TYPE_TEXT_VARIATION_PERSON_NAME))
    }

    @Test
    fun passwordsAddressesAndRefusingFieldsDoNot() {
        for (v in listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI,
        )) assertFalse("variation $v", suggestionsWanted(text or v))
        assertFalse(suggestionsWanted(text or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
        assertFalse(suggestionsWanted(InputType.TYPE_CLASS_NUMBER))
        assertFalse(suggestionsWanted(InputType.TYPE_CLASS_PHONE))
        assertFalse(suggestionsWanted(InputType.TYPE_NULL))
    }
}
