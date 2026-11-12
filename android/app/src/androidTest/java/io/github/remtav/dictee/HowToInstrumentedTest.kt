package io.github.remtav.dictee

import android.Manifest
import android.app.Instrumentation
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.remtav.dictee.stt.DictationLanguage
import io.github.remtav.dictee.stt.ModelRecommendations
import io.github.remtav.dictee.text.CommandTable
import io.github.remtav.dictee.ui.HowToActivity
import io.github.remtav.dictee.ui.HowToExamples
import io.github.remtav.dictee.ui.SetupActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #15, step 2.3: the "Comment dicter" screen opens at the end of the first setup (and not the
 * second time), reopens from the main screen and from the keyboard's help button, and every
 * example it shows comes from the command table.
 *
 * Scenarios follow one another, never nested: `ActivityScenario.launch` starts each activity in a
 * cleared task and destroys the previous one (the cause of a CI failure in lot 1). An activity
 * monitor tells when the screen is started, whoever starts it (a click, the setup screen, the keyboard service).
 */
@RunWith(AndroidJUnit4::class)
class HowToInstrumentedTest : ImeTestBase() {
    private val howTo = HowToActivity::class.java.name

    @Before
    fun allowTheMicrophone() {
        // The keyboard is enabled by ImeTestBase; the microphone cannot be asked for by the test, so it is granted.
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        app.settings.languages = setOf(DictationLanguage.FRENCH_QUEBEC)
    }

    @After
    fun removeMonitors() {
        monitors.forEach { instrumentation.removeMonitor(it) }
        monitors.clear()
    }

    private val monitors = mutableListOf<Instrumentation.ActivityMonitor>()

    private fun monitorHowTo(): Instrumentation.ActivityMonitor =
        instrumentation.addMonitor(howTo, null, false).also { monitors += it }

    private fun View.allViews(): Sequence<View> = sequence {
        yield(this@allViews)
        // `this` alone would be the SequenceScope here, never a ViewGroup: the tree was never walked (review 2.R).
        val view = this@allViews
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(view.getChildAt(i).allViews())
    }

    private fun ActivityScenario<*>.viewsTagged(tag: String): List<View> {
        var found = emptyList<View>()
        onActivity { a -> found = a.window.decorView.allViews().filter { it.tag == tag }.toList() }
        return found
    }

    private fun ActivityScenario<*>.textsTagged(tag: String): List<String> =
        viewsTagged(tag).filterIsInstance<TextView>().map { it.text.toString() }

    private fun ActivityScenario<*>.allText(): String {
        var out = ""
        onActivity { a -> out = a.window.decorView.allViews().filterIsInstance<TextView>().joinToString("\n") { it.text } }
        return out
    }

    @Test
    fun opensAtTheEndOfTheFirstSetupAndNotTheSecondTime() {
        // 1. Keyboard enabled and microphone allowed, but no model: the setup is not complete.
        app.settings.howToShown = false // ImeTestBase marks it seen for the other tests
        TestHooks.assumeInstalled = false
        val notYet = monitorHowTo()
        ActivityScenario.launch(SetupActivity::class.java).use {
            assertNull("no model: not opened", notYet.waitForActivityWithTimeout(2_000))
        }
        assertFalse(app.settings.howToShown)

        // 2. A model is ready: the first setup is complete, the tutorial opens by itself.
        TestHooks.assumeInstalled = true
        val first = monitorHowTo()
        ActivityScenario.launch(SetupActivity::class.java).use {
            assertNotNull("opened at the end of the setup", first.waitForActivityWithTimeout(10_000))
        }
        assertTrue("remembered", app.settings.howToShown)

        // 3. The second time, nothing opens.
        val second = monitorHowTo()
        ActivityScenario.launch(SetupActivity::class.java).use { setup ->
            assertNull("only once", second.waitForActivityWithTimeout(3_000))
            assertEquals(1, setup.viewsTagged(SetupActivity.HOWTO_BUTTON_TAG).size)
        }
    }

    @Test
    fun reopensFromTheMainScreen() {
        app.settings.howToShown = true // seen: nothing opens by itself
        val monitor = monitorHowTo()
        ActivityScenario.launch(SetupActivity::class.java).use { setup ->
            val button = setup.viewsTagged(SetupActivity.HOWTO_BUTTON_TAG).single()
            setup.onActivity { assertTrue("visible button", button.isShown) }
            setup.onActivity { button.performClick() }
            val opened = monitor.waitForActivityWithTimeout(10_000)
            assertNotNull("opened from the main screen", opened)
        }
    }

    @Test
    fun reopensFromTheKeyboardHelpButton() {
        app.settings.howToShown = true
        val monitor = monitorHowTo()
        useScript("Bonjour")
        launchFields()
        val help = device.wait(Until.findObject(By.res(BuildConfig.APPLICATION_ID, "help")), 10_000)
        assertNotNull("help button on the keyboard", help)
        val minPx = 48 * context.resources.displayMetrics.density
        assertTrue("touch target of at least 48 dp: ${help.visibleBounds}", help.visibleBounds.width() >= minPx - 1 && help.visibleBounds.height() >= minPx - 1)
        assertEquals(context.getString(R.string.cd_help), help.contentDescription)
        help.click()
        assertNotNull("opened from the keyboard", monitor.waitForActivityWithTimeout(15_000))
    }

    @Test
    fun everyExampleShownComesFromTheTable() {
        for (languages in listOf(
            setOf(DictationLanguage.FRENCH_QUEBEC),
            setOf(DictationLanguage.FRENCH_QUEBEC, DictationLanguage.ENGLISH),
        )) {
            app.settings.languages = languages
            ActivityScenario.launch(HowToActivity::class.java).use { s ->
                val table = CommandTable.active(languages)
                val allowedSaid = table.map { context.getString(R.string.howto_said, it.example.said) }.toSet()
                val allowedResult = table.map { it.example.result }.toSet()
                val said = s.textsTagged(HowToActivity.SAID_TAG)
                val results = s.textsTagged(HowToActivity.RESULT_TAG)
                assertTrue("$languages: ${said.size} examples", said.size in 5..8)
                assertEquals(said.size, results.size)
                assertTrue("said comes from the table: $said", allowedSaid.containsAll(said))
                assertTrue("results come from the table: $results", allowedResult.containsAll(results))
                // the very selection the pure logic makes, in the same order
                val featured = HowToExamples.featured(languages)
                assertEquals(featured.map { context.getString(R.string.howto_said, it.said) }, said)
                assertEquals(featured.map { it.result }, results)
                // the full list: one line per command of the table
                assertEquals(table.map { it.primary }, s.textsTagged(HowToActivity.COMMAND_TAG))
            }
        }
    }

    @Test
    fun theScreenCarriesTheReminderTheModelTheVocabularyLinkAndATryField() {
        app.settings.languages = setOf(DictationLanguage.FRENCH_QUEBEC)
        ActivityScenario.launch(HowToActivity::class.java).use { s ->
            assertTrue(s.textsTagged(HowToActivity.PRIVACY_TAG).single().contains("sur votre téléphone"))
            val recommended = ModelRecommendations.recommend(app.settings.languages).model
            assertTrue(s.textsTagged(HowToActivity.MODEL_TAG).single().contains(recommended.displayName))
            assertTrue(s.allText().contains(ModelRecommendations.recommend(app.settings.languages).reason))
            assertEquals(1, s.viewsTagged(HowToActivity.VOCABULARY_BUTTON_TAG).size)
            val field = s.viewsTagged(HowToActivity.TRY_TAG).single() as EditText
            s.onActivity { assertTrue("a field that brings the keyboard up", field.isFocusableInTouchMode && field.isEnabled) }
            // voice commands are on: no warning
            assertTrue(s.viewsTagged(HowToActivity.COMMANDS_OFF_TAG).isEmpty())
        }
    }

    @Test
    fun saysWhenVoiceCommandsAreOff() {
        app.settings.voiceCommands = false
        ActivityScenario.launch(HowToActivity::class.java).use { s ->
            assertEquals(1, s.viewsTagged(HowToActivity.COMMANDS_OFF_TAG).size)
            assertTrue(s.textsTagged(HowToActivity.COMMANDS_OFF_TAG).single().contains("désactivées"))
        }
    }
}
