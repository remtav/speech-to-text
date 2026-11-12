package io.github.remtav.dictee

import io.github.remtav.dictee.audio.PcmAudio
import io.github.remtav.dictee.audio.Resampler
import io.github.remtav.dictee.audio.WavReader
import io.github.remtav.dictee.cleanup.LlmCatalog
import io.github.remtav.dictee.stt.Acceleration
import io.github.remtav.dictee.stt.Downloadable
import io.github.remtav.dictee.stt.EngineManager
import io.github.remtav.dictee.stt.EngineOptions
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.ModelFamily
import io.github.remtav.dictee.stt.ModelFile
import io.github.remtav.dictee.stt.ModelStore
import io.github.remtav.dictee.stt.SttEngineFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

class CatalogTest {
    @Test fun catalogIsWellFormed() {
        assertEquals(if (ModelCatalog.whisperQuebecHosted) 4 else 3, ModelCatalog.all.size)
        assertEquals("parakeet-tdt-0.6b-v3-int8", ModelCatalog.default.id)
        assertEquals(ModelCatalog.all.size, ModelCatalog.all.map { it.id }.toSet().size)
        val items: List<Downloadable> = ModelCatalog.all.map { object : Downloadable {
            override val id = it.id; override val files = it.files } } + LlmCatalog.all
        for (m in items) {
            assertTrue(m.files.isNotEmpty())
            for (f in m.files) {
                assertTrue(f.url, f.url.startsWith("https://huggingface.co/"))
                assertTrue(f.sha256, Regex("[0-9a-f]{64}").matches(f.sha256))
                assertTrue(f.size > 0)
            }
        }
        // Approximate int8 sizes from the issue: ~0.7 GB, ~1 GB, ~1 GB.
        assertTrue(ModelCatalog.parakeet.totalBytes in 600_000_000L..800_000_000L)
        assertTrue(ModelCatalog.qwen3.totalBytes in 900_000_000L..1_100_000_000L)
        assertTrue(ModelCatalog.whisperTurbo.totalBytes in 900_000_000L..1_200_000_000L)
    }

    /**
     * The Quebec French Whisper (issue #15, user request) is offered only once its files are
     * published: the app never lists a download that cannot succeed.
     */
    @Test fun quebecWhisperIsListedOnlyWhenItsFilesAreHosted() {
        val m = ModelCatalog.whisperQuebec
        assertEquals(ModelCatalog.whisperQuebecHosted, m in ModelCatalog.all)
        assertEquals(ModelCatalog.whisperQuebecHosted, m in ModelCatalog.selectable)
        assertEquals(ModelCatalog.whisperQuebecHosted, m.files.none { it.url.startsWith("/") })
        // Same engine branch, same file names as Whisper large-v3-turbo; its own directory and id.
        assertEquals(ModelFamily.WHISPER, m.family)
        assertEquals(ModelCatalog.whisperTurbo.files.map { it.path }, m.files.map { it.path })
        assertTrue(m.id != ModelCatalog.whisperTurbo.id)
        assertEquals("MIT", m.license)
        assertTrue(m.publisher.contains("ele-sage"))
        for (f in m.files) assertTrue(f.sha256, Regex("[0-9a-f]{64}").matches(f.sha256))
        // int8 conversion of the same architecture: same size as the original, within 5 %.
        val ref = ModelCatalog.whisperTurbo.totalBytes.toDouble()
        assertTrue(m.totalBytes in (ref * 0.95).toLong()..(ref * 1.05).toLong())
        // Different weights than the original: no file may share its checksum, except the tokens.
        val original = ModelCatalog.whisperTurbo.files.associate { it.path to it.sha256 }
        for (f in m.files.filter { it.path.endsWith(".onnx") }) assertTrue(f.sha256 != original[f.path])
    }
}

class ModelStoreTest {
    private lateinit var server: MiniHttpServer
    private lateinit var root: File
    private val content = Random(3).nextBytes(3_000_000)
    private val small = "tokens\n".toByteArray()
    private val requests = AtomicInteger()
    private val ranges = AtomicInteger()
    @Volatile private var failOnceAt = -1
    @Volatile private var badRangeOnce = false

    @Before fun setUp() {
        root = Files.createTempDirectory("store").toFile()
        server = MiniHttpServer { path, range, out ->
            when (path) {
                "/redirect/big.bin" -> out.write("HTTP/1.1 302 Found\r\nLocation: /big.bin\r\nContent-Length: 0\r\n\r\n".toByteArray())
                "/big.bin" -> {
                    requests.incrementAndGet()
                    var from = 0
                    if (range != null) {
                        ranges.incrementAndGet()
                        from = range.removePrefix("bytes=").substringBefore('-').toInt()
                        if (badRangeOnce) { badRangeOnce = false; from = 0 } // claims 206 but restarts at 0
                        out.write(("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $from-${content.size - 1}/${content.size}\r\n" +
                            "Content-Length: ${content.size - from}\r\n\r\n").toByteArray())
                    } else {
                        out.write("HTTP/1.1 200 OK\r\nContent-Length: ${content.size}\r\n\r\n".toByteArray())
                    }
                    val cut = failOnceAt
                    if (cut in (from + 1) until content.size) {
                        failOnceAt = -1
                        out.write(content, from, cut - from) // then drop the connection
                    } else {
                        out.write(content, from, content.size - from)
                    }
                }
                "/tokens.txt" -> {
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: ${small.size}\r\n\r\n".toByteArray())
                    out.write(small)
                }
                else -> out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
            }
        }
    }

    @After fun tearDown() {
        server.close()
        root.deleteRecursively()
    }

    private fun url(p: String) = "http://127.0.0.1:${server.port}$p"

    private fun item(bigSha: String = sha(content), bigPath: String = "/big.bin") = object : Downloadable {
        override val id = "m1"
        override val files = listOf(
            ModelFile("sub/big.bin", url(bigPath), bigSha, content.size.toLong()),
            ModelFile("tokens.txt", url("/tokens.txt"), sha(small), small.size.toLong()),
        )
    }

    @Test fun downloadsVerifiesAndMarksInstalled() {
        val store = ModelStore(root)
        val it = item(bigPath = "/redirect/big.bin")
        assertFalse(store.isInstalled(it))
        var last = 0L
        store.download(it) { done, total -> assertTrue(done <= total); last = done }
        assertTrue(store.isInstalled(it))
        assertEquals(content.size + small.size.toLong(), last)
        assertTrue(File(store.dirFor("m1"), "sub/big.bin").readBytes().contentEquals(content))
        // Second call: nothing re-downloaded.
        val before = requests.get()
        store.download(it)
        assertEquals(before, requests.get())
        store.delete(it)
        assertFalse(store.isInstalled(it))
    }

    @Test fun checksumMismatchIsRejected() {
        val store = ModelStore(root)
        try {
            store.download(item(bigSha = "0".repeat(64)))
            fail("expected checksum failure")
        } catch (e: ModelStore.ChecksumException) {
            assertTrue(e.message!!.contains("big.bin"))
        }
        assertFalse(store.isInstalled(item()))
        assertFalse(File(store.dirFor("m1"), "sub/big.bin").exists())
    }

    @Test fun interruptedDownloadResumesWithRange() {
        failOnceAt = 1_000_000
        val store = ModelStore(root)
        store.download(item())
        assertTrue(store.isInstalled(item()))
        assertEquals(1, ranges.get())
    }

    @Test fun misalignedPartialResponseRestartsFromScratch() {
        failOnceAt = 1_000_000
        badRangeOnce = true
        val store = ModelStore(root)
        store.download(item())
        assertTrue(store.isInstalled(item()))
    }

    @Test fun cancelStopsDownload() {
        val store = ModelStore(root)
        val cancel = AtomicBoolean(true)
        try {
            store.download(item(), cancel)
            fail("expected cancellation")
        } catch (_: java.io.InterruptedIOException) {
        }
        assertFalse(store.isInstalled(item()))
    }

    @Test fun catalogChangeInvalidatesInstall() {
        val store = ModelStore(root)
        store.download(item())
        val changed = object : Downloadable {
            override val id = "m1"
            override val files = item().files.map { if (it.path == "tokens.txt") it.copy(sha256 = "f".repeat(64)) else it }
        }
        assertFalse(store.isInstalled(changed))
    }
}

class EngineManagerTest {
    private val root = Files.createTempDirectory("em").toFile()
    private val store = ModelStore(root)

    @Test fun oneModelAtATimeAndSwitchReleasesPrevious() {
        val factory = ScriptedEngine.Factory(listOf("a"), 0)
        val em = EngineManager(store, factory, isInstalled = { true })
        val e1 = em.await(ModelCatalog.parakeet, EngineOptions()) as ScriptedEngine
        assertFalse(e1.released)
        // Same model and options: no reload.
        assertTrue(em.await(ModelCatalog.parakeet, EngineOptions()) === e1)
        val e2 = em.await(ModelCatalog.whisperTurbo, EngineOptions()) as ScriptedEngine
        assertTrue(e1.released)
        assertFalse(e2.released)
        assertEquals(2, factory.created.get())
        em.release()
        Thread.sleep(100)
        assertTrue(e2.released)
        assertEquals(EngineManager.State.Empty, em.state)
    }

    @Test fun acceleratorFailureFallsBackToCpu() {
        val inner = ScriptedEngine.Factory(listOf("a"), 0)
        val factory = SttEngineFactory { spec, dir, opts ->
            if (opts.acceleration != Acceleration.CPU) throw IllegalArgumentException("NNAPI refused")
            inner.create(spec, dir, opts)
        }
        val em = EngineManager(store, factory, isInstalled = { true })
        em.await(ModelCatalog.parakeet, EngineOptions(acceleration = Acceleration.NNAPI))
        val st = em.state as EngineManager.State.Ready
        assertNotNull(st.note)
        assertTrue(st.note!!.contains("CPU"))
    }

    @Test fun missingModelFails() {
        val em = EngineManager(store, ScriptedEngine.Factory(emptyList()))
        try {
            em.await(ModelCatalog.qwen3, EngineOptions())
            fail()
        } catch (e: ExecutionException) {
            assertTrue(em.state is EngineManager.State.Failed)
        }
    }

    @Test fun releaseWaitsForDecodeInProgress() {
        val factory = ScriptedEngine.Factory(listOf("long"), 600)
        val em = EngineManager(store, factory, isInstalled = { true })
        val t = Thread { em.use(ModelCatalog.parakeet, EngineOptions()) { it.transcribe(FloatArray(16000)) } }
        t.start()
        Thread.sleep(150)
        em.release() // must not free the engine under the running decode
        t.join()
        Thread.sleep(100)
        assertTrue(factory.last!!.released)
    }
}

class WavTest {
    @Test fun encodeDecodeRoundTrip() {
        val audio = PcmAudio(FloatArray(1600) { kotlin.math.sin(it / 10.0).toFloat() * 0.5f }, 16000)
        val back = WavReader.read(WavReader.encode(audio).inputStream())
        assertEquals(16000, back.sampleRate)
        assertEquals(audio.samples.size, back.samples.size)
        for (i in audio.samples.indices) assertEquals(audio.samples[i], back.samples[i], 1e-3f)
    }

    @Test fun resampleLength() {
        assertEquals(16000, Resampler.linear(FloatArray(24000), 24000, 16000).size)
        assertEquals(16000, Resampler.linear(FloatArray(22050), 22050, 16000).size)
    }

    @Test fun referencesAreReadable() {
        val dir = File(System.getProperty("dictee.refDir"))
        val tsv = File(dir, "references.tsv").readText()
        val refs = io.github.remtav.dictee.ui.CompareActivity.parseReferences(tsv) { name ->
            File(dir, name).inputStream().use { WavReader.read(it) }
        }
        assertEquals(setOf("en", "fr"), refs.map { it.language }.toSet())
        assertTrue(refs.all { it.audio.seconds in 2f..30f && !it.reference.isNullOrBlank() })
    }
}

/** One-request-per-connection HTTP/1.1 server for the downloader tests (no JDK httpserver on the Android test classpath). */
class MiniHttpServer(private val handler: (path: String, range: String?, out: java.io.OutputStream) -> Unit) : AutoCloseable {
    private val socket = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort
    private val thread = Thread {
        while (!socket.isClosed) {
            val client = try { socket.accept() } catch (_: java.io.IOException) { break }
            Thread {
                client.use { c ->
                    val reader = c.getInputStream().bufferedReader()
                    val request = reader.readLine() ?: return@use
                    var range: String? = null
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
                    }
                    val path = request.split(' ')[1]
                    try {
                        val out = java.io.BufferedOutputStream(c.getOutputStream())
                        handler(path, range, out)
                        out.flush()
                    } catch (_: java.io.IOException) {
                    }
                }
            }.start()
        }
    }.apply { isDaemon = true; start() }

    override fun close() = socket.close()
}
