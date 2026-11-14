package io.github.remtav.dictee

import android.content.ContextWrapper
import android.content.SharedPreferences
import io.github.remtav.dictee.ime.LayoutDefault
import io.github.remtav.dictee.ime.LayoutKind
import io.github.remtav.dictee.settings.AppSettings
import io.github.remtav.dictee.stt.DictationLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Issue #42 (review): the layout default follows the languages for a new install, never for an existing one. */
class LayoutDefaultTest {
    private fun settings(prefs: MemoryPrefs): AppSettings {
        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        }
        return AppSettings(context, defaultServerUrl = "")
    }

    private val france = setOf(DictationLanguage.FRENCH_FRANCE)

    @Test fun pureRule() {
        assertNull(LayoutDefault.pinnedFor(emptyMap<String, Any>()))
        assertEquals(LayoutKind.QWERTY, LayoutDefault.pinnedFor(mapOf("active_model" to "x")))
        assertNull(LayoutDefault.pinnedFor(mapOf("keyboard_layout" to "AZERTY")))
        assertNull(LayoutDefault.pinnedFor(mapOf("active_model" to "x", LayoutDefault.CHECKED_KEY to true)))
    }

    @Test fun newInstallFollowsFrenchOfFrance() {
        val s = settings(MemoryPrefs())
        s.languages = france
        assertEquals(LayoutKind.AZERTY, s.keyboardLayout)
        s.languages = setOf(DictationLanguage.FRENCH_QUEBEC)
        assertEquals(LayoutKind.QWERTY, s.keyboardLayout)
    }

    @Test fun existingInstallKeepsItsLayoutWhateverItsLanguages() {
        // Stored settings from before this version, French of France chosen, no layout ever picked.
        val prefs = MemoryPrefs(mutableMapOf<String, Any?>("active_model" to "whisper-large-v3-turbo-int8"))
        val s = settings(prefs)
        s.languages = france
        assertEquals(LayoutKind.QWERTY, s.keyboardLayout)
        // Still so after the next start of the app.
        assertEquals(LayoutKind.QWERTY, settings(prefs).keyboardLayout)
    }

    @Test fun anExplicitChoiceIsNeverOverwritten() {
        val prefs = MemoryPrefs(mutableMapOf<String, Any?>("keyboard_layout" to "AZERTY"))
        assertEquals(LayoutKind.AZERTY, settings(prefs).keyboardLayout)
        val other = MemoryPrefs(mutableMapOf<String, Any?>("active_model" to "x", "keyboard_layout" to "QWERTY"))
        val s = settings(other)
        s.languages = france
        assertEquals(LayoutKind.QWERTY, s.keyboardLayout)
    }

    @Test fun anExistingInstallStaysPinnedAfterTheUserChangesLanguages() {
        val prefs = MemoryPrefs(mutableMapOf<String, Any?>("languages" to mutableSetOf("FRENCH_QUEBEC")))
        val s = settings(prefs)
        s.languages = france
        assertEquals(LayoutKind.QWERTY, s.keyboardLayout)
    }
}
