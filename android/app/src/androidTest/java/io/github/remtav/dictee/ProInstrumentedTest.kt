package io.github.remtav.dictee

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.TranscriptionMode
import io.github.remtav.dictee.ui.ProActivity
import io.github.remtav.dictee.ui.SetupActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #89: the « Kalmia Pro » card and screen. Without a server nothing can be turned on and nothing is sent;
 * with one, Pro is turned on only by the button under the consent text of issue #23, never by the card, the
 * screen opening or a menu entry.
 */
@RunWith(AndroidJUnit4::class)
class ProInstrumentedTest {
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
        TestHooks.installedModelIds = null
        app.settings.prefs.edit().clear().commit()
    }

    private fun ActivityScenario<*>.hasTag(tag: String): Boolean {
        var found = false
        onActivity { a -> found = a.window.decorView.findViewWithTag<View>(tag) != null }
        return found
    }

    private fun ActivityScenario<*>.click(tag: String) {
        onActivity { a -> checkNotNull(a.window.decorView.findViewWithTag<View>(tag)) { "no view tagged $tag" }.performClick() }
        instrumentation.waitForIdleSync()
    }

    private fun ActivityScenario<*>.text(tag: String): String {
        var out = ""
        onActivity { a -> out = (a.window.decorView.findViewWithTag<android.widget.TextView>(tag)).text.toString() }
        return out
    }

    @Test
    fun theCardIsOnTheMainScreenWithoutAServerAndOpensNothingToTurnOn() {
        ActivityScenario.launch(SetupActivity::class.java).use { s ->
            assertTrue(s.hasTag(ProActivity.CARD_TAG))
            assertTrue(s.hasTag(ProActivity.CARD_BUTTON_TAG))
        }
        // Looking at the card sent and recorded nothing.
        assertEquals(TranscriptionMode.UNSET, app.settings.transcriptionMode)
        assertFalse(app.settings.serverInUse)
        ActivityScenario.launch(ProActivity::class.java).use { s ->
            assertEquals("Kalmia Pro", s.text(ProActivity.TITLE_TAG))
            assertTrue(s.text(ProActivity.SOON_TAG).contains("Abonnement bientôt disponible"))
            assertTrue(s.text(ProActivity.AUDIO_TAG).contains("envoyé au serveur"))
            assertTrue(s.hasTag(ProActivity.UNAVAILABLE_TAG))
            assertFalse("nothing to turn on without a server", s.hasTag(ProActivity.ACTIVATE_TAG))
            assertFalse(s.hasTag(ProActivity.CONSENT_TAG))
        }
        assertEquals(TranscriptionMode.UNSET, app.settings.transcriptionMode)
        assertFalse(app.settings.serverInUse)
    }

    @Test
    fun withAServerProIsTurnedOnOnlyByTheButtonUnderTheConsentText() {
        app.settings.remoteUrl = "https://dictee.example.ca"
        ActivityScenario.launch(SetupActivity::class.java).use { s ->
            assertTrue(s.hasTag(ProActivity.CARD_TAG))
            // An address alone, the card shown: still nothing sent.
            assertFalse(s.hasTag(SetupActivity.SERVER_STATUS_TAG))
        }
        assertFalse(app.settings.serverInUse)
        ActivityScenario.launch(ProActivity::class.java).use { s ->
            assertFalse(s.hasTag(ProActivity.UNAVAILABLE_TAG))
            // The consent text is on the screen, with the button; opening the screen changed nothing.
            assertTrue(s.text(ProActivity.CONSENT_TAG).contains("l'audio de vos dictées est envoyé au serveur"))
            assertEquals(TranscriptionMode.UNSET, app.settings.transcriptionMode)
            assertFalse(app.settings.serverInUse)
            s.click(ProActivity.ACTIVATE_TAG)
            assertEquals(TranscriptionMode.SERVER, app.settings.transcriptionMode)
            assertTrue(app.settings.serverInUse)
            assertTrue(s.hasTag(ProActivity.ACTIVE_TAG))
            assertFalse(s.hasTag(ProActivity.ACTIVATE_TAG))
            // And back to the phone, from the same screen.
            s.click(ProActivity.DEACTIVATE_TAG)
            assertEquals(TranscriptionMode.PHONE, app.settings.transcriptionMode)
            assertFalse(app.settings.serverInUse)
            assertTrue(s.hasTag(ProActivity.ACTIVATE_TAG))
        }
    }

    /** Issue #89, point 7: an existing install with the server mode active keeps it, and says « Kalmia Pro ». */
    @Test
    fun anActiveServerStaysActiveAndIsCalledKalmiaPro() {
        app.settings.remoteUrl = "https://dictee.example.ca"
        app.settings.transcriptionMode = TranscriptionMode.SERVER
        assertEquals(ModelCatalog.remote, app.shownSpec())
        assertEquals("Kalmia Pro", io.github.remtav.dictee.stt.ModelPlainNames.of(app.shownSpec()))
        ActivityScenario.launch(SetupActivity::class.java).use { s ->
            assertTrue(s.hasTag(SetupActivity.SERVER_STATUS_TAG))
            assertTrue(s.hasTag(ProActivity.CARD_TAG))
        }
        assertEquals(TranscriptionMode.SERVER, app.settings.transcriptionMode)
    }

    /** Issue #89, point 7: an active Parakeet stays active (the catalogue now offers it). */
    @Test
    fun anActiveParakeetStaysActive() {
        app.settings.activeModelId = ModelCatalog.parakeet.id
        ActivityScenario.launch(SetupActivity::class.java).use { }
        app.ensureModelSuitsLanguages()
        assertEquals(ModelCatalog.parakeet.id, app.settings.activeModelId)
    }
}

/**
 * Issue #89, point 5: the keyboard's model menu lists the three choices, in the order Parakeet, Whisper, Pro; a model
 * that is not downloaded is listed all the same; Pro without a server sends nothing and leaves the "server" mode alone.
 */
@RunWith(AndroidJUnit4::class)
class ModelMenuInstrumentedTest : ImeTestBase() {
    private fun openMenu() {
        val button = device.wait(Until.findObject(By.res(BuildConfig.APPLICATION_ID, "model")), 5_000)
        assertNotNull("model button", button)
        button.click()
        assertNotNull("menu opened", device.wait(Until.findObject(By.text("Kalmia Pro")), 5_000))
    }

    @Test
    fun theMenuListsParakeetWhisperAndProInThatOrder() {
        launchFields()
        openMenu()
        val tops = listOf("Parakeet · Rapide", "Whisper · Précis", "Kalmia Pro").map { label ->
            val item = device.findObject(By.text(label))
            assertNotNull(label, item)
            item.visibleBounds.top
        }
        assertEquals(tops.sorted(), tops)
        assertEquals(3, tops.toSet().size)
    }

    @Test
    fun proWithoutAServerOpensItsScreenAndSendsNothing() {
        assertEquals("", app.settings.remoteUrl)
        launchFields()
        openMenu()
        device.findObject(By.text("Kalmia Pro")).click()
        assertNotNull("Pro screen", device.wait(Until.findObject(By.text(context.getString(R.string.pro_unavailable))), 10_000))
        assertEquals(TranscriptionMode.UNSET, app.settings.transcriptionMode)
        assertFalse(app.settings.serverInUse)
        device.pressBack()
    }
}
