package io.github.remtav.dictee

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.remtav.dictee.stt.DictationResult
import io.github.remtav.dictee.stt.TranscriptionMode
import io.github.remtav.dictee.ui.SettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #23: no dictation reaches the server until the "server" mode is recorded in the settings (since issue #57,
 * only in the folded server section of « Avancé »), and the settings choice is respected. Issue #57: a server
 * already set up before the update keeps receiving the dictations, and the main screen says so.
 */
@RunWith(AndroidJUnit4::class)
class ServerModeInstrumentedTest : ImeTestBase() {

    private val serverText = "Bonjour depuis le serveur."

    private fun server() = RecordingServer { req ->
        when {
            req.method == "POST" && req.path == "/jobs" -> 202 to """{"id": "j1", "status": "queued"}"""
            req.path == "/jobs/j1" -> 200 to """{"id": "j1", "status": "done", "result": {"text": "bonjour depuis le serveur", "language": "fr", "polished_text": "$serverText", "polish": {"applied": true}}}"""
            else -> 404 to "{}"
        }
    }

    /** Picks the server / phone choice in the settings screen, like a user. */
    private fun chooseInSettings(tag: String) {
        ActivityScenario.launch(SettingsActivity::class.java).use { s ->
            s.onActivity { a ->
                // Issue #57: the choice is in the folded server section of « Avancé »: unfold it first, like a user.
                a.window.decorView.findViewWithTag<View>(SettingsActivity.SERVER_SECTION_TAG).performClick()
                a.window.decorView.findViewWithTag<View>(tag).performClick()
            }
            instrumentation.waitForIdleSync()
        }
    }

    private fun dictatesLocallyWithoutRequest(server: RecordingServer) {
        useAudio("en1")
        useScript("Sur le téléphone.")
        launchFields()
        tapMic()
        waitFor("local text", 20_000) { field1() == "Sur le téléphone." }
        awaitIdle()
        assertTrue("the server got ${server.requests.size} request(s)", server.requests.isEmpty())
    }

    @Test
    fun modeNotChosenStaysOnThePhone() {
        server().use { server ->
            app.settings.remoteUrl = "http://127.0.0.1:${server.port}"
            TestHooks.networkAvailable = true
            assertEquals(TranscriptionMode.UNSET, app.settings.transcriptionMode)
            dictatesLocallyWithoutRequest(server)
        }
    }

    @Test
    fun phoneModeStaysOnThePhone() {
        server().use { server ->
            app.settings.remoteUrl = "http://127.0.0.1:${server.port}"
            app.settings.transcriptionMode = TranscriptionMode.PHONE
            TestHooks.networkAvailable = true
            dictatesLocallyWithoutRequest(server)
        }
    }

    @Test
    fun phoneChosenInTheSettingsIsRespected() {
        server().use { server ->
            app.settings.remoteUrl = "http://127.0.0.1:${server.port}"
            app.settings.transcriptionMode = TranscriptionMode.SERVER
            TestHooks.networkAvailable = true
            chooseInSettings(SettingsActivity.MODE_PHONE_TAG)
            assertEquals(TranscriptionMode.PHONE, app.settings.transcriptionMode)
            dictatesLocallyWithoutRequest(server)
        }
    }

    @Test
    fun serverChosenInTheSettingsIsRespected() {
        server().use { server ->
            app.settings.remoteUrl = "http://127.0.0.1:${server.port}"
            TestHooks.networkAvailable = true
            chooseInSettings(SettingsActivity.MODE_SERVER_TAG)
            assertEquals(TranscriptionMode.SERVER, app.settings.transcriptionMode)
            TestHooks.engineFactory = null // the real RemoteEngine
            useAudio("fr1")
            launchFields()
            tapMic()
            waitFor("text from the server", 30_000) { field1() == serverText }
            // This fake server has no POST /polish (404): an older server, the dictation goes whole (issue #35).
            assertEquals(1, server.requests.count { it.method == "POST" && it.path == "/jobs" })
            assertEquals(DictationResult.Source.SERVER_POLISHED, service.lastDictation?.source)
        }
    }

    /**
     * Issue #57: an address and the "server" mode recorded before the update (no new choice made): the dictations
     * still go to the server, and the main screen shows « Transcription : serveur distant ».
     */
    @Test
    fun aServerSetUpBeforeTheUpdateKeepsWorkingAndIsShown() {
        server().use { server ->
            app.settings.remoteUrl = "http://127.0.0.1:${server.port}"
            app.settings.transcriptionMode = TranscriptionMode.SERVER
            TestHooks.networkAvailable = true
            app.migrateModels() // what the app does at start-up after an update
            assertEquals(TranscriptionMode.SERVER, app.settings.transcriptionMode)
            ActivityScenario.launch(io.github.remtav.dictee.ui.SetupActivity::class.java).use { s ->
                var shown = false
                s.onActivity { a -> shown = a.window.decorView.findViewWithTag<View>(io.github.remtav.dictee.ui.SetupActivity.SERVER_STATUS_TAG) != null }
                assertTrue("server line on the main screen", shown)
            }
            TestHooks.engineFactory = null // the real RemoteEngine
            useAudio("fr1")
            launchFields()
            tapMic()
            waitFor("text from the server", 30_000) { field1() == serverText }
            assertEquals(1, server.requests.count { it.method == "POST" && it.path == "/jobs" })
        }
    }
}
