package io.github.remtav.dictee

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.google.android.material.button.MaterialButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.TranscriptionMode
import io.github.remtav.dictee.ui.SetupActivity
import io.github.remtav.dictee.ui.SetupActivity.Companion.STEP_CHOOSE
import io.github.remtav.dictee.ui.SetupActivity.Companion.STEP_ENABLE
import io.github.remtav.dictee.ui.SetupActivity.Companion.STEP_MIC
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #55: the two keyboard steps (enable, then choose), one filled button at most (the next step's), the
 * choose button inactive until the keyboard is enabled, and the screen starting on a missing keyboard step when
 * the person comes back. Issue #57: no server on this screen unless one is set up and chosen in the advanced
 * settings, and then a line that says so.
 *
 * The system state (keyboard enabled / chosen, microphone) is forced through [TestHooks]: the emulator's input
 * methods are left alone and no permission is revoked (that would kill the process).
 */
@RunWith(AndroidJUnit4::class)
class SetupStepsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app: DicteeApp get() = context.applicationContext as DicteeApp

    @Before
    fun firstLaunch() {
        app.settings.prefs.edit().clear().commit()
        TestHooks.installedModelIds = emptySet()
    }

    @After
    fun cleanUp() {
        TestHooks.keyboardEnabled = null
        TestHooks.keyboardChosen = null
        TestHooks.micGranted = null
        TestHooks.installedModelIds = null
        app.settings.prefs.edit().clear().commit()
    }

    private fun state(enabled: Boolean, chosen: Boolean, mic: Boolean) {
        TestHooks.keyboardEnabled = enabled
        TestHooks.keyboardChosen = chosen
        TestHooks.micGranted = mic
    }

    private fun View.allViews(): Sequence<View> = sequence {
        yield(this@allViews)
        if (this@allViews is ViewGroup) for (i in 0 until childCount) yieldAll(getChildAt(i).allViews())
    }

    /** Tags of the buttons drawn filled (not outlined) and shown, anywhere on the screen. */
    private fun ActivityScenario<*>.filledButtons(): List<String> {
        val out = mutableListOf<String>()
        onActivity { a ->
            for (b in a.window.decorView.allViews().filterIsInstance<MaterialButton>()) {
                if (b.visibility != View.VISIBLE || !isDrawn(b)) continue
                val outlined = b.getTag(R.id.button_outlined) as? Boolean
                assertNotNull("button « ${b.text} » not built by Ui.button", outlined)
                if (outlined == false) out += (b.tag as? String ?: b.text.toString())
            }
        }
        return out
    }

    /** Visible as far as its ancestors go (the screen scrolls: off-screen still counts as drawn). */
    private fun isDrawn(v: View): Boolean {
        var p: View? = v
        while (p != null) {
            if (p.visibility != View.VISIBLE) return false
            p = p.parent as? View
        }
        return true
    }

    private fun ActivityScenario<*>.button(prefix: String): Button {
        var b: Button? = null
        onActivity { b = it.window.decorView.findViewWithTag("${prefix}_button") }
        return checkNotNull(b) { "no button for $prefix" }
    }

    private fun ActivityScenario<*>.stateText(prefix: String): String {
        var t = ""
        onActivity { t = it.window.decorView.findViewWithTag<TextView>("${prefix}_state").text.toString() }
        return t
    }

    private fun ActivityScenario<*>.allText(): String {
        var out = ""
        onActivity { a -> out = a.window.decorView.allViews().filterIsInstance<TextView>().joinToString("\n") { it.text } }
        return out
    }

    private fun ActivityScenario<*>.hasTag(tag: String): Boolean {
        var found = false
        onActivity { found = it.window.decorView.findViewWithTag<View>(tag) != null }
        return found
    }

    private val done get() = context.getString(R.string.setup_step_done)

    @Test
    fun nothingDoneOnlyEnableIsFilledAndChooseIsInactive() {
        state(enabled = false, chosen = false, mic = false)
        ActivityScenario.launch(SetupActivity::class.java).use { s ->
            assertEquals(listOf("${STEP_ENABLE}_button"), s.filledButtons())
            assertTrue(s.button(STEP_ENABLE).isEnabled)
            assertFalse("choose waits for enable", s.button(STEP_CHOOSE).isEnabled)
            assertEquals(context.getString(R.string.setup_step_todo), s.stateText(STEP_ENABLE))
            assertEquals(context.getString(R.string.setup_step_after_previous), s.stateText(STEP_CHOOSE))
            assertEquals(context.getString(R.string.setup_open_ime_settings), s.button(STEP_ENABLE).text.toString())
            assertEquals(context.getString(R.string.setup_pick_ime), s.button(STEP_CHOOSE).text.toString())
            // Each step: a number, a title and a sentence saying what Android shows.
            val text = s.allText()
            assertTrue(text.contains("1. Activer Kalmia Blabla"))
            assertTrue(text.contains("2. Choisir Kalmia Blabla"))
            assertTrue(text.contains("Android ouvre la liste des claviers"))
            assertTrue(text.contains("avertissement"))
            assertTrue(text.contains("Android demande quel clavier utiliser"))
        }
    }

    @Test
    fun enabledOnlyChooseIsFilled() {
        state(enabled = true, chosen = false, mic = false)
        ActivityScenario.launch(SetupActivity::class.java).use { s ->
            assertEquals(listOf("${STEP_CHOOSE}_button"), s.filledButtons())
            assertTrue(s.button(STEP_CHOOSE).isEnabled)
            assertEquals(done, s.stateText(STEP_ENABLE))
            assertEquals(context.getString(R.string.setup_step_todo), s.stateText(STEP_CHOOSE))
        }
    }

    @Test
    fun enabledAndChosenTheMicrophoneIsFilled() {
        state(enabled = true, chosen = true, mic = false)
        ActivityScenario.launch(SetupActivity::class.java).use { s ->
            assertEquals(listOf("${STEP_MIC}_button"), s.filledButtons())
            assertEquals(done, s.stateText(STEP_ENABLE))
            assertEquals(done, s.stateText(STEP_CHOOSE))
        }
    }

    @Test
    fun stepsDoneTheRecommendedModelDownloadIsTheOnlyFilledButton() {
        state(enabled = true, chosen = true, mic = true)
        ActivityScenario.launch(SetupActivity::class.java).use { s ->
            assertEquals(listOf(context.getString(R.string.download)), s.filledButtons())
            var inRecommendedCard = false
            s.onActivity { a ->
                val card = a.window.decorView.findViewWithTag<ViewGroup>(SetupActivity.modelCardTag(ModelCatalog.whisperTurbo))
                inRecommendedCard = card.allViews().filterIsInstance<MaterialButton>().any { it.getTag(R.id.button_outlined) == false }
            }
            assertTrue(inRecommendedCard)
        }
        // Everything done: nothing left to put forward.
        TestHooks.installedModelIds = setOf(ModelCatalog.whisperTurbo.id)
        ActivityScenario.launch(SetupActivity::class.java).use { s ->
            assertEquals(emptyList<String>(), s.filledButtons())
        }
    }

    @Test
    fun theHowToButtonComesAfterTheSteps() {
        state(enabled = false, chosen = false, mic = false)
        ActivityScenario.launch(SetupActivity::class.java).use { s ->
            s.onActivity { a ->
                val col = (a.window.decorView.findViewWithTag<View>(SetupActivity.STEPS_TAG).parent as ViewGroup)
                val steps = col.indexOfChild(a.window.decorView.findViewWithTag(SetupActivity.STEPS_TAG))
                val howTo = col.indexOfChild(a.window.decorView.findViewWithTag(SetupActivity.HOWTO_BUTTON_TAG))
                assertTrue("how-to at $howTo, steps at $steps", howTo > steps)
            }
        }
    }

    private fun ActivityScenario<*>.stepsTop(): Int {
        var top = -1
        onActivity { a -> top = a.window.decorView.findViewWithTag<View>(SetupActivity.STEPS_TAG).top }
        return top
    }

    /**
     * Issue #55, point 6: the first visit starts on the welcome text; a return to the screen (settings, a system
     * dialog) does not scroll; a new opening with a step missing starts on the steps.
     */
    @Test
    fun reopeningWithAMissingStepStartsOnTheSteps() {
        state(enabled = true, chosen = false, mic = true)
        ActivityScenario.launch(SetupActivity::class.java).use { s ->
            instrumentation.waitForIdleSync()
            assertEquals("first visit: welcome first", 0, s.scrollY())
            s.moveToState(Lifecycle.State.CREATED) // left (onStop), as for the settings
            assertTrue(app.settings.setupSeen)
            s.moveToState(Lifecycle.State.RESUMED)
            instrumentation.waitForIdleSync()
            assertEquals("a return does not scroll", 0, s.scrollY())
        }
        ActivityScenario.launch(SetupActivity::class.java).use { s ->
            instrumentation.waitForIdleSync()
            val y = s.scrollY()
            assertTrue("reopened: scrolled to the steps, scrollY=$y top=${s.stepsTop()}", y > 0 && y <= s.stepsTop())
        }
    }

    /** A rotation at the first visit is not a visit: the welcome text stays first. */
    @Test
    fun aRotationAtTheFirstVisitDoesNotJump() {
        state(enabled = false, chosen = false, mic = false)
        ActivityScenario.launch(SetupActivity::class.java).use { s ->
            instrumentation.waitForIdleSync()
            s.recreate()
            instrumentation.waitForIdleSync()
            assertFalse(app.settings.setupSeen)
            assertEquals(0, s.scrollY())
        }
    }

    /** Step 2: done when Kalmia is the current keyboard, and stays done once it has been (decision for #55). */
    @Test
    fun chosenOnceStaysDoneWhenAnotherKeyboardIsActive() {
        // Never chosen: to do.
        state(enabled = true, chosen = false, mic = true)
        ActivityScenario.launch(SetupActivity::class.java).use { s ->
            assertEquals(context.getString(R.string.setup_step_todo), s.stateText(STEP_CHOOSE))
            assertEquals(listOf("${STEP_CHOOSE}_button"), s.filledButtons())
        }
        assertFalse(app.settings.keyboardChosenOnce)
        // Chosen and current: done, remembered.
        state(enabled = true, chosen = true, mic = true)
        ActivityScenario.launch(SetupActivity::class.java).use { s ->
            assertEquals(done, s.stateText(STEP_CHOOSE))
        }
        assertTrue(app.settings.keyboardChosenOnce)
        // Back to another keyboard: still done, said apart, no filled button for it.
        state(enabled = true, chosen = false, mic = false)
        ActivityScenario.launch(SetupActivity::class.java).use { s ->
            assertEquals(context.getString(R.string.setup_step_done_other_keyboard), s.stateText(STEP_CHOOSE))
            assertEquals(listOf("${STEP_MIC}_button"), s.filledButtons())
        }
    }

    private fun ActivityScenario<*>.scrollY(): Int {
        var y = -1
        onActivity { a ->
            val steps = a.window.decorView.findViewWithTag<View>(SetupActivity.STEPS_TAG)
            y = generateSequence(steps.parent) { it.parent }.filterIsInstance<ScrollView>().first().scrollY
        }
        return y
    }

    /** Issue #57: without a server address, not a card, not a button, not a word about a server. */
    @Test
    fun noServerAnywhereWithoutAnAddress() {
        assertEquals("", app.settings.remoteUrl)
        for (enabled in listOf(false, true)) {
            state(enabled = enabled, chosen = enabled, mic = enabled)
            ActivityScenario.launch(SetupActivity::class.java).use { s ->
                val text = s.allText().lowercase()
                assertFalse(text, text.contains("serveur"))
                assertFalse(text.contains("server"))
                assertFalse(s.hasTag(SetupActivity.SERVER_STATUS_TAG))
                assertTrue(text.contains("n'est envoyée à personne"))
            }
        }
    }

    /** An address alone (a build with -PdefaultServerUrl, or one typed but not chosen) changes nothing here. */
    @Test
    fun anAddressWithoutTheServerModeShowsNoServer() {
        app.settings.remoteUrl = "https://dictee.example.ca"
        state(enabled = true, chosen = true, mic = true)
        ActivityScenario.launch(SetupActivity::class.java).use { s ->
            assertEquals(TranscriptionMode.UNSET, app.settings.transcriptionMode)
            assertFalse(s.hasTag(SetupActivity.SERVER_STATUS_TAG))
            assertFalse(s.allText().lowercase().contains("serveur"))
        }
    }

    /** Issue #57, point 4: a server already set up and chosen stays visible on the main screen, with the welcome text adapted. */
    @Test
    fun aServerInUseIsShownWithALinkToItsSetting() {
        app.settings.remoteUrl = "https://dictee.example.ca"
        app.settings.transcriptionMode = TranscriptionMode.SERVER
        state(enabled = true, chosen = true, mic = true)
        ActivityScenario.launch(SetupActivity::class.java).use { s ->
            assertTrue(s.hasTag(SetupActivity.SERVER_STATUS_TAG))
            assertTrue(s.hasTag(SetupActivity.SERVER_SETTING_BUTTON_TAG))
            val text = s.allText()
            assertTrue(text.contains(context.getString(R.string.setup_server_in_use)))
            assertTrue(text.contains("dictee.example.ca"))
            // No model on the phone here: the line does not promise a fallback.
            assertTrue(text.contains(context.getString(R.string.setup_server_no_fallback)))
            assertFalse(text.contains(context.getString(R.string.setup_server_fallback)))
            // No sentence says the voice is sent to no one.
            assertFalse(text, text.contains("n'est envoyée à personne"))
            assertFalse(text, text.contains("reste sur votre téléphone"))
        }
        TestHooks.installedModelIds = setOf(ModelCatalog.whisperTurbo.id)
        ActivityScenario.launch(SetupActivity::class.java).use { s ->
            assertTrue(s.allText().contains(context.getString(R.string.setup_server_fallback)))
        }
    }
}
