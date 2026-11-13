package io.github.remtav.dictee

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import io.github.remtav.dictee.dictation.DictationState
import io.github.remtav.dictee.stt.DictationResult
import io.github.remtav.dictee.stt.ServerConsent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #23: with a server address, the first dictation shows the consent screen in the keyboard; the
 * answer is asked only once; « Rester sur le téléphone » keeps every dictation on the phone, and nothing
 * reaches the server before « Accepter ».
 */
@RunWith(AndroidJUnit4::class)
class ServerConsentInstrumentedTest : ImeTestBase() {

    private val serverText = "Bonjour depuis le serveur."

    private fun server() = RecordingServer { req ->
        when {
            req.method == "POST" && req.path == "/jobs" -> 202 to """{"id": "j1", "status": "queued"}"""
            req.path == "/jobs/j1" -> 200 to """{"id": "j1", "status": "done", "result": {"text": "bonjour depuis le serveur", "language": "fr", "polished_text": "$serverText", "polish": {"applied": true}}}"""
            else -> 404 to "{}"
        }
    }

    /** Taps a consent button through the accessibility tree, like a user (or directly if it is not found). */
    private fun answer(accept: Boolean) {
        val id = if (accept) "consent_accept" else "consent_local"
        val button = device.wait(Until.findObject(By.res(BuildConfig.APPLICATION_ID, id)), 5_000)
        if (button != null) button.click() else onMain { service.onConsentAnswered(accept) }
    }

    @Test
    fun consentIsAskedOnceThenTheServerIsUsed() {
        server().use { server ->
            app.settings.remoteUrl = "http://127.0.0.1:${server.port}"
            TestHooks.networkAvailable = true
            TestHooks.engineFactory = null // the real RemoteEngine
            assertEquals(ServerConsent.UNASKED, app.settings.serverConsent)
            useAudio("fr1")
            launchFields()

            tapMic()
            waitFor("consent screen", 10_000) { onMain { service.consentShown } }
            // Nothing recorded, nothing sent before the answer.
            assertEquals(DictationState.IDLE, state())
            assertTrue(server.requests.isEmpty())

            answer(accept = true)
            waitFor("text from the server", 30_000) { field1() == serverText }
            assertEquals(ServerConsent.ACCEPTED, app.settings.serverConsent)
            assertFalse(onMain { service.consentShown })
            assertEquals(DictationResult.Source.SERVER_POLISHED, service.lastDictation?.source)
            awaitIdle()

            // Second dictation: no question, straight to the server.
            tapMic()
            waitFor("second request to the server", 30_000) { server.requests.count { it.method == "POST" } == 2 }
            assertFalse(onMain { service.consentShown })
            awaitIdle()
        }
    }

    @Test
    fun refusalKeepsDictationOnThePhone() {
        server().use { server ->
            app.settings.remoteUrl = "http://127.0.0.1:${server.port}"
            TestHooks.networkAvailable = true
            useAudio("en1")
            useScript("Sur le téléphone.", "Encore ici.")
            launchFields()

            tapMic()
            waitFor("consent screen", 10_000) { onMain { service.consentShown } }
            answer(accept = false)
            waitFor("local text", 20_000) { field1() == "Sur le téléphone." }
            assertEquals(ServerConsent.REFUSED, app.settings.serverConsent)
            awaitIdle()

            // The refusal is kept: no second question, still on the phone.
            tapMic()
            waitFor("second local text", 20_000) { field1().endsWith("Encore ici.") }
            assertFalse(onMain { service.consentShown })
            awaitIdle()
            assertTrue("the server got ${server.requests.size} request(s)", server.requests.isEmpty())
        }
    }

    @Test
    fun noAddressNoQuestion() {
        TestHooks.networkAvailable = true
        useAudio("en1")
        useScript("Local.")
        launchFields()
        tapMic()
        waitFor("local text", 20_000) { field1() == "Local." }
        assertFalse(onMain { service.consentShown })
        assertEquals(ServerConsent.UNASKED, app.settings.serverConsent)
    }
}
