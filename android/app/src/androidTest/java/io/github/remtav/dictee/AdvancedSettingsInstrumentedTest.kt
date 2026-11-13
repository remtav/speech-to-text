package io.github.remtav.dictee

import android.view.View
import android.widget.RadioGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.remtav.dictee.stt.RepetitionFix
import io.github.remtav.dictee.ui.SettingsActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #49: the « Avancé » section holds « Silence ajouté pour Whisper » (with « Automatique ») and
 * « Correction des répétitions (Whisper) », greyed out outside « Automatique ».
 * Issue #57: the remote server's settings are inside « Avancé », in a section folded at opening.
 */
@RunWith(AndroidJUnit4::class)
class AdvancedSettingsInstrumentedTest {
    private val settings get() = DicteeApp.from(ApplicationProvider.getApplicationContext()).settings
    private val keys = listOf("whisper_tail_paddings", "whisper_tail_auto", "whisper_repetition_fix")
    private var saved: Map<String, Any?> = emptyMap()

    @Before fun save() {
        saved = keys.associateWith { settings.prefs.all[it] }
        settings.prefs.edit().apply { keys.forEach { remove(it) } }.commit()
    }

    @After fun restore() {
        settings.prefs.edit().apply {
            for ((k, v) in saved) when (v) {
                null -> remove(k)
                is Int -> putInt(k, v)
                is Boolean -> putBoolean(k, v)
                is String -> putString(k, v)
            }
        }.commit()
    }

    @Test fun automaticAndCorrectionLiveInTheAdvancedSection() {
        ActivityScenario.launch(SettingsActivity::class.java).use { s ->
            lateinit var tail: RadioGroup
            lateinit var fix: RadioGroup
            s.onActivity { a ->
                val root = a.window.decorView
                val advanced = root.findViewWithTag<android.view.ViewGroup>(SettingsActivity.ADVANCED_TAG)
                assertNotNull(advanced)
                tail = advanced.findViewWithTag(SettingsActivity.WHISPER_TAIL_TAG)
                fix = advanced.findViewWithTag(SettingsActivity.WHISPER_REPETITION_TAG)
                assertNotNull("tail padding is in « Avancé »", tail)
                assertNotNull("correction is in « Avancé »", fix)
                // Default: 1 000 checked, correction greyed out.
                assertEquals(tail.getChildAt(0).id, tail.checkedRadioButtonId)
                assertEquals(4, tail.childCount)
                assertFalse(fix.getChildAt(0).isEnabled)
                // « Automatique » enables the correction.
                tail.getChildAt(3).performClick()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertTrue(settings.whisperTailAuto)
            assertEquals(1000, settings.whisperTailPaddings)
            s.onActivity {
                assertTrue(fix.getChildAt(0).isEnabled && fix.getChildAt(1).isEnabled)
                fix.getChildAt(1).performClick()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(RepetitionFix.CUT, settings.whisperRepetitionFix)
            assertEquals(300, settings.engineOptions(io.github.remtav.dictee.stt.ModelCatalog.whisperTurbo).whisperTailPaddings)
            // Back to 600: fixed padding, correction greyed out again and no longer applied.
            s.onActivity { tail.getChildAt(1).performClick() }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertFalse(settings.whisperTailAuto)
            assertEquals(600, settings.whisperTailPaddings)
            s.onActivity { assertFalse(fix.getChildAt(0).isEnabled) }
            assertEquals(null, settings.engineOptions(io.github.remtav.dictee.stt.ModelCatalog.whisperTurbo).whisperRepetitionFix)
        }
    }

    @Test fun noWhisperPaddingLeftInPerformance() {
        ActivityScenario.launch(SettingsActivity::class.java).use { s ->
            s.onActivity { a ->
                val tail = a.window.decorView.findViewWithTag<View>(SettingsActivity.WHISPER_TAIL_TAG)
                val advanced = a.window.decorView.findViewWithTag<android.view.ViewGroup>(SettingsActivity.ADVANCED_TAG)
                // The only padding choice is the one inside « Avancé ».
                var p = tail.parent
                while (p != null && p !== advanced) p = p.parent
                assertTrue(p === advanced)
            }
        }
    }

    private fun isInside(view: View, ancestor: View): Boolean {
        var p = view.parent
        while (p != null && p !== ancestor) p = p.parent
        return p === ancestor
    }

    /** Issue #57: every server setting lives in the folded section of « Avancé », nothing about it above. */
    @Test fun serverSettingsAreFoldedInsideTheAdvancedSection() {
        ActivityScenario.launch(SettingsActivity::class.java).use { s ->
            s.onActivity { a ->
                val root = a.window.decorView
                val advanced = root.findViewWithTag<View>(SettingsActivity.ADVANCED_TAG)
                val header = root.findViewWithTag<android.widget.TextView>(SettingsActivity.SERVER_SECTION_TAG)
                val content = root.findViewWithTag<View>(SettingsActivity.SERVER_CONTENT_TAG)
                assertNotNull(header)
                assertTrue(isInside(header, advanced))
                assertTrue(isInside(content, advanced))
                assertTrue(header.text.contains(a.getString(io.github.remtav.dictee.R.string.settings_server_section)))
                assertEquals("folded at opening", View.GONE, content.visibility)
                for (tag in listOf(SettingsActivity.SERVER_MODE_TAG, SettingsActivity.MODE_SERVER_TAG, SettingsActivity.MODE_PHONE_TAG)) {
                    val v = root.findViewWithTag<View>(tag)
                    assertNotNull(tag, v)
                    assertTrue("$tag is in the server section", isInside(v, content))
                }
                // Not a word about a server outside the folded section.
                fun texts(v: View): Sequence<android.widget.TextView> = sequence {
                    if (v === content) return@sequence
                    if (v is android.widget.TextView && v !== header) yield(v)
                    if (v is android.view.ViewGroup) for (i in 0 until v.childCount) yieldAll(texts(v.getChildAt(i)))
                }
                val outside = texts(root).map { it.text.toString() }.filter { it.contains("serveur", ignoreCase = true) }.toList()
                assertEquals(emptyList<String>(), outside)
                header.performClick()
                assertEquals(View.VISIBLE, content.visibility)
                header.performClick()
                assertEquals(View.GONE, content.visibility)
            }
        }
    }

    /** From the server line of the main screen: the section opens unfolded, on the server / phone choice. */
    @Test fun openedFromTheServerLineTheSectionIsUnfolded() {
        val intent = android.content.Intent(ApplicationProvider.getApplicationContext(), SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_SCROLL_TO_SERVER_MODE, true)
        ActivityScenario.launch<SettingsActivity>(intent).use { s ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            s.onActivity { a ->
                val content = a.window.decorView.findViewWithTag<View>(SettingsActivity.SERVER_CONTENT_TAG)
                assertEquals(View.VISIBLE, content.visibility)
            }
        }
    }
}
