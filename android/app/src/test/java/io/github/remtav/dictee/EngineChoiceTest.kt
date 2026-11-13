package io.github.remtav.dictee

import io.github.remtav.dictee.stt.DictationResult
import io.github.remtav.dictee.stt.EngineChoice
import io.github.remtav.dictee.stt.EngineRoute
import io.github.remtav.dictee.stt.FallbackEngine
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.RemoteConfig
import io.github.remtav.dictee.stt.RemoteEngine
import io.github.remtav.dictee.stt.ServerConsent
import io.github.remtav.dictee.stt.ServerTimeout
import io.github.remtav.dictee.stt.WaitControl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Issue #23: server first. Without an address → phone; address + consent → server; address + refusal → phone;
 * unreachable or silent server → the phone model, within the fallback delay.
 */
class EngineChoiceTest {
    private val local = ModelCatalog.whisperTurbo

    // ------------------------------------------------------------ route

    @Test fun noAddressMeansLocal() {
        for (consent in ServerConsent.entries) {
            assertEquals(EngineRoute.LOCAL, EngineChoice.route(serverConfigured = false, consent = consent, alwaysLocal = false))
        }
        assertEquals(local, EngineChoice.spec(EngineRoute.LOCAL, local))
    }

    @Test fun addressAndConsentMeansServer() {
        val r = EngineChoice.route(serverConfigured = true, consent = ServerConsent.ACCEPTED, alwaysLocal = false)
        assertEquals(EngineRoute.SERVER, r)
        assertEquals(ModelCatalog.remote, EngineChoice.spec(r, local))
    }

    @Test fun addressAndRefusalMeansLocal() {
        val r = EngineChoice.route(serverConfigured = true, consent = ServerConsent.REFUSED, alwaysLocal = false)
        assertEquals(EngineRoute.LOCAL, r)
        assertEquals(local, EngineChoice.spec(r, local))
    }

    @Test fun addressWithoutAnswerAsksAndSendsNothing() {
        val r = EngineChoice.route(serverConfigured = true, consent = ServerConsent.UNASKED, alwaysLocal = false)
        assertEquals(EngineRoute.ASK_CONSENT, r)
        // Whatever happens next, a dictation on this route never uses the server.
        assertEquals(local, EngineChoice.spec(r, local))
        // Asked even without network: the answer is about the audio, not about this dictation.
        assertEquals(EngineRoute.ASK_CONSENT, EngineChoice.route(true, ServerConsent.UNASKED, false, networkAvailable = false))
    }

    @Test fun alwaysLocalWins() {
        for (consent in ServerConsent.entries) {
            assertEquals(EngineRoute.LOCAL, EngineChoice.route(true, consent, alwaysLocal = true))
        }
    }

    @Test fun noNetworkMeansLocal() {
        assertEquals(EngineRoute.LOCAL, EngineChoice.route(true, ServerConsent.ACCEPTED, false, networkAvailable = false))
    }

    @Test fun consentNamesRoundTrip() {
        for (c in ServerConsent.entries) assertEquals(c, ServerConsent.fromName(c.name))
        assertEquals(ServerConsent.UNASKED, ServerConsent.fromName(null))
        assertEquals(ServerConsent.UNASKED, ServerConsent.fromName("bogus"))
    }

    // ---------------------------------------------------------- timeout

    @Test fun timeoutSetsTheWaitBudget() {
        val c = ServerTimeout.apply(RemoteConfig("https://dictee.example.ca"), ServerTimeout.DEFAULT_SECONDS)
        assertEquals(8_000L, c.waitBudgetMillis(0f))
        assertEquals(10_500L, c.waitBudgetMillis(10f)) // + a quarter of the audio
        assertEquals(180_000L, c.waitBudgetMillis(3600f)) // capped
        assertEquals(4_000, c.connectTimeoutMillis)
        val short = ServerTimeout.apply(RemoteConfig("https://dictee.example.ca"), 2)
        assertEquals(2_000, short.connectTimeoutMillis)
        assertEquals(ServerTimeout.MIN_SECONDS, ServerTimeout.sanitize(0))
        assertEquals(ServerTimeout.MAX_SECONDS, ServerTimeout.sanitize(1000))
    }

    // --------------------------------------------------------- fallback

    private val sockets = CopyOnWriteArrayList<Socket>()
    private var silent: ServerSocket? = null

    @After fun tearDown() {
        sockets.forEach { runCatching { it.close() } }
        runCatching { silent?.close() }
    }

    /** A server that accepts connections and never answers (overloaded or stuck). */
    private fun silentServer(): Int {
        val s = ServerSocket(0)
        silent = s
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                sockets += c
                thread(isDaemon = true) { runCatching { c.getInputStream().readBytes() } } // swallow the upload
            }
        }
        return s.localPort
    }

    private val audio = FloatArray(16000) { kotlin.math.sin(it / 8.0).toFloat() * 0.3f } // 1 s
    private fun localEngine() = ScriptedEngine(local, List(5) { "sur le téléphone" }, 0)

    @Test fun silentServerFallsBackToLocalWithinTheDelay() {
        val config = ServerTimeout.apply(RemoteConfig("http://127.0.0.1:${silentServer()}"), 2)
        val engine = FallbackEngine(RemoteEngine(ModelCatalog.remote, config), { localEngine() })
        val start = System.nanoTime()
        val result = engine.transcribeDictation(audio, listOf(audio), WaitControl())
        val millis = (System.nanoTime() - start) / 1_000_000
        assertEquals(DictationResult.Source.LOCAL_FALLBACK, result.source)
        assertEquals(listOf("sur le téléphone"), result.texts)
        // 2 s + 0.25 s for 1 s of audio, plus scheduling slack: never the old 60 s budget.
        assertTrue("fell back after $millis ms", millis in 1_500..4_500)
    }

    @Test fun unreachableServerFallsBackAtOnce() {
        val dead = ServerSocket(0).use { it.localPort }
        val config = ServerTimeout.apply(RemoteConfig("http://127.0.0.1:$dead"), ServerTimeout.DEFAULT_SECONDS)
        val engine = FallbackEngine(RemoteEngine(ModelCatalog.remote, config), { localEngine() })
        val start = System.nanoTime()
        val result = engine.transcribeDictation(audio, listOf(audio), WaitControl())
        val millis = (System.nanoTime() - start) / 1_000_000
        assertEquals(DictationResult.Source.LOCAL_FALLBACK, result.source)
        assertEquals(listOf("sur le téléphone"), result.texts)
        assertTrue("fell back after $millis ms", millis < ServerTimeout.DEFAULT_SECONDS * 1000L)
    }
}
