package io.github.remtav.dictee

import io.github.remtav.dictee.audio.WavReader
import io.github.remtav.dictee.stt.DefaultEngineFactory
import io.github.remtav.dictee.stt.EngineOptions
import io.github.remtav.dictee.stt.FallbackEngine
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.RemoteApi
import io.github.remtav.dictee.stt.RemoteConfig
import io.github.remtav.dictee.stt.RemoteEngine
import io.github.remtav.dictee.stt.RemoteException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket

class RemoteEngineTest {
    private lateinit var server: RecordingServer
    @Volatile private var reply: Pair<Int, String> = 200 to """{"text": " Bonjour depuis le serveur. ", "language": "fr"}"""

    @Before fun setUp() { server = RecordingServer { reply } }
    @After fun tearDown() = server.close()

    private fun base() = "http://127.0.0.1:${server.port}"
    private val samples = FloatArray(16000) { kotlin.math.sin(it / 8.0).toFloat() * 0.3f }

    private fun multipartField(body: ByteArray, name: String): String? {
        val s = String(body, Charsets.ISO_8859_1)
        val marker = "name=\"$name\"\r\n\r\n"
        val i = s.indexOf(marker).takeIf { it >= 0 } ?: return null
        return s.substring(i + marker.length).substringBefore("\r\n--")
    }

    @Test fun dicteeServerApi() {
        val engine = RemoteEngine(ModelCatalog.remote, RemoteConfig(base(), RemoteApi.DICTEE_SERVER, language = "fr"))
        val t = engine.transcribe(samples, 16000)
        assertEquals("Bonjour depuis le serveur.", t.text)
        assertEquals("fr", t.language)
        assertEquals(1f, t.audioSeconds, 1e-3f)
        val req = server.requests.single()
        assertEquals("POST", req.method)
        assertEquals("/transcribe/streaming", req.path)
        assertTrue(req.headers["content-type"]!!.startsWith("multipart/form-data; boundary="))
        assertEquals("fr", multipartField(req.body, "language"))
        assertFalse(req.headers.containsKey("authorization"))
        // The file part is a valid 16 kHz WAV of the same length.
        val s = String(req.body, Charsets.ISO_8859_1)
        val start = s.indexOf("RIFF")
        val wav = WavReader.read(req.body.copyOfRange(start, req.body.size).inputStream())
        assertEquals(16000, wav.sampleRate)
        assertTrue(wav.samples.size >= 16000)
    }

    @Test fun openAiApiWithKeyModelAndPrompt() {
        reply = 200 to """{"text": "Hello from the server."}"""
        val config = RemoteConfig("${base()}/", RemoteApi.OPENAI, apiKey = "secret", model = "large-v3")
        val engine = RemoteEngine(ModelCatalog.remote, config, hotwords = listOf("Rémi", "Kubernetes"))
        assertEquals("Hello from the server.", engine.transcribe(samples, 16000).text)
        val req = server.requests.single()
        assertEquals("/v1/audio/transcriptions", req.path)
        assertEquals("Bearer secret", req.headers["authorization"])
        assertEquals("large-v3", multipartField(req.body, "model"))
        assertEquals("Rémi, Kubernetes", String(multipartField(req.body, "prompt")!!.toByteArray(Charsets.ISO_8859_1)))
        assertEquals(null, multipartField(req.body, "language"))
    }

    @Test fun baseUrlEndingInV1IsNotDoubled() {
        assertEquals("http://h:8000/v1/audio/transcriptions", RemoteConfig("http://h:8000/v1", RemoteApi.OPENAI).transcriptionUrl())
    }

    @Test fun httpErrorIsReported() {
        reply = 500 to """{"detail": "model not loaded"}"""
        val engine = RemoteEngine(ModelCatalog.remote, RemoteConfig(base()))
        try {
            engine.transcribe(samples, 16000)
            fail()
        } catch (e: RemoteException) {
            assertTrue(e.message!!.contains("500"))
            assertTrue(e.message!!.contains("model not loaded"))
        }
    }

    @Test fun unreachableServerIsARemoteException() {
        val dead = ServerSocket(0).use { it.localPort }
        val engine = RemoteEngine(ModelCatalog.remote, RemoteConfig("http://127.0.0.1:$dead", connectTimeoutMillis = 500))
        try { engine.transcribe(samples, 16000); fail() } catch (_: RemoteException) {}
    }

    @Test fun healthCheck() {
        reply = 200 to """{"model": "large-v3-turbo", "device": "cuda"}"""
        val r = RemoteEngine.check(RemoteConfig(base()))
        assertEquals("modèle large-v3-turbo · cuda", r.getOrThrow())
        assertEquals("/health", server.requests.single().path)
        assertTrue(RemoteEngine.check(RemoteConfig("ftp://x")).isFailure)
    }

    @Test fun addressValidationAndInsecureWarning() {
        assertFalse(RemoteConfig("").isConfigured)
        assertFalse(RemoteConfig("192.168.1.10:8081").isConfigured) // no scheme
        assertTrue(RemoteConfig("http://192.168.1.10:8081").isConfigured)
        assertFalse(RemoteConfig("http://192.168.1.10:8081").isInsecurePublic)
        assertFalse(RemoteConfig("http://maison.local:8081").isInsecurePublic)
        assertFalse(RemoteConfig("http://100.101.1.2:8081").isInsecurePublic) // Tailscale
        assertFalse(RemoteConfig("https://stt.example.com").isInsecurePublic)
        assertTrue(RemoteConfig("http://stt.example.com").isInsecurePublic)
        assertTrue(RemoteConfig("http://203.0.113.5:8081").isInsecurePublic)
    }

    @Test fun fallsBackToLocalModelAndRetriesLater() {
        var now = 0L
        val local = ScriptedEngine(ModelCatalog.parakeet, List(10) { "local $it" }, 0)
        var created = 0
        val dead = ServerSocket(0).use { it.localPort }
        val remote = RemoteEngine(ModelCatalog.remote, RemoteConfig("http://127.0.0.1:$dead", connectTimeoutMillis = 300))
        val engine = FallbackEngine(remote, { created++; local }, retryAfterMillis = 60_000, clock = { now })
        assertEquals("local 0", engine.transcribe(samples, 16000).text)
        assertTrue(engine.lastUsedLocal)
        now += 1_000
        assertEquals("local 1", engine.transcribe(samples, 16000).text) // server skipped, no timeout
        assertEquals(1, created)
        engine.release()
        assertTrue(local.released)
    }

    @Test fun serverBackAfterRetryDelay() {
        var now = 0L
        var up = false
        val remote = object : io.github.remtav.dictee.stt.SttEngine {
            override val spec = ModelCatalog.remote
            override val provider = "remote"
            override fun transcribe(samples: FloatArray, sampleRate: Int) =
                if (up) io.github.remtav.dictee.stt.Transcription("remote") else throw RemoteException("down")
            override fun release() {}
        }
        val engine = FallbackEngine(remote, { ScriptedEngine(ModelCatalog.parakeet, List(5) { "local" }, 0) }, 60_000, { now })
        assertEquals("local", engine.transcribe(samples, 16000).text)
        up = true
        now += 61_000
        assertEquals("remote", engine.transcribe(samples, 16000).text)
    }

    @Test fun factoryBuildsRemoteWithOptionalFallback() {
        val f = DefaultEngineFactory { File("/tmp") }
        val plain = f.create(ModelCatalog.remote, File("/x"), EngineOptions(remote = RemoteConfig(base())))
        assertTrue(plain is RemoteEngine)
        val withFallback = f.create(ModelCatalog.remote, File("/x"),
            EngineOptions(remote = RemoteConfig(base()), remoteFallback = ModelCatalog.parakeet))
        assertTrue(withFallback is FallbackEngine)
        try { f.create(ModelCatalog.remote, File("/x"), EngineOptions()); fail() } catch (_: IllegalStateException) {}
    }
}
