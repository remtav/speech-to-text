package io.github.remtav.dictee

import io.github.remtav.dictee.audio.AudioSource
import io.github.remtav.dictee.audio.WavReader
import io.github.remtav.dictee.dictation.DictationController
import io.github.remtav.dictee.dictation.DictationListener
import io.github.remtav.dictee.dictation.DictationSession
import io.github.remtav.dictee.dictation.DictationState
import io.github.remtav.dictee.dictation.StringSink
import io.github.remtav.dictee.dictation.TextPipeline
import io.github.remtav.dictee.stt.BlockBackend
import io.github.remtav.dictee.stt.BlockDictation
import io.github.remtav.dictee.stt.DictationResult
import io.github.remtav.dictee.stt.FallbackEngine
import io.github.remtav.dictee.stt.LentBlockBackend
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.RemoteApi
import io.github.remtav.dictee.stt.RemoteConfig
import io.github.remtav.dictee.stt.RemoteEngine
import io.github.remtav.dictee.stt.SttEngine
import io.github.remtav.dictee.stt.WaitControl
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Issue #35, lot 1: a server dictation is sent block by block while the user speaks, against a fake server that
 * speaks the API of PR #46 (`POST /jobs` with `prompt`, `POST /polish`, `GET /jobs/{id}`, cancel). Covers the
 * order of the blocks, what is left at the end, the fallbacks (failed block, 429, polish not applied, server
 * cut mid-dictation, older server without `POST /polish`), "Annuler", and a dropped dictation (field changed).
 */
class BlockDictationTest {
    private val blockTexts = listOf("un", "deux", "trois", "quatre", "cinq", "six")
    @Volatile private var polished = "Un, deux, trois, quatre."

    // ------------------------------------------------------------- fake server

    /** Answers per block number (1-based, in order of `POST /jobs`). */
    private val failBlocks: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    /** Blocks whose job stays "running" until released. */
    private val heldBlocks: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    @Volatile private var failCode = 502
    /** How `POST /polish` answers: a status code, or 202 then a job result. */
    @Volatile private var polishCode = 202
    @Volatile private var polishResult = { text: String -> """{"text": ${JSONObject.quote(text)}, "language": "fr", "polished_text": "$polished", "polish": {"applied": true, "model": "m", "latency_ms": 412, "fallback_reason": null}}""" }
    @Volatile private var holdPolish = false
    /** Language the server reports for every job. */
    @Volatile private var detected = "fr"
    private val jobCount = AtomicInteger()
    private val polishTexts: MutableMap<String, String> = ConcurrentHashMap()
    private lateinit var server: RecordingServer

    @Before fun setUp() {
        server = RecordingServer { req -> respond(req) }
    }

    @After fun tearDown() {
        server.close()
    }

    private fun respond(req: RecordingServer.Request): Pair<Int, String> {
        val p = req.path
        return when {
            req.method == "POST" && p == "/jobs" -> {
                val n = jobCount.incrementAndGet()
                if (n in failBlocks) failCode to """{"detail": "boom"}"""
                else 202 to """{"id": "b$n", "kind": "transcription", "status": "queued"}"""
            }
            req.method == "POST" && p == "/polish" -> {
                val text = runCatching { JSONObject(String(req.body)).optString("text") }.getOrDefault("")
                when {
                    polishCode != 202 -> polishCode to """{"detail": "x"}"""
                    text.isBlank() -> 400 to """{"detail": "text must be a non-empty string"}"""
                    else -> {
                        val id = "p" + (polishTexts.size + 1)
                        polishTexts[id] = text
                        202 to """{"id": "$id", "kind": "polish", "status": "queued", "result": null}"""
                    }
                }
            }
            req.method == "POST" && p.endsWith("/cancel") -> 200 to """{"status": "cancelled"}"""
            req.method == "GET" && p.startsWith("/jobs/b") -> {
                val n = p.removePrefix("/jobs/b").toInt()
                if (n in heldBlocks) 200 to """{"id": "b$n", "status": "running"}"""
                else 200 to """{"id": "b$n", "status": "done", "result": {"text": " ${blockTexts[n - 1]} ", "language": "$detected"}}"""
            }
            req.method == "GET" && p.startsWith("/jobs/p") -> {
                val id = p.removePrefix("/jobs/")
                if (holdPolish) 200 to """{"id": "$id", "status": "running"}"""
                else 200 to """{"id": "$id", "kind": "polish", "status": "done", "result": ${polishResult(polishTexts[id] ?: "")}}"""
            }
            p == "/v1/audio/transcriptions" -> {
                val n = jobCount.incrementAndGet()
                200 to """{"text": "${blockTexts[n - 1]}"}"""
            }
            else -> 404 to "{}"
        }
    }

    private fun reqs(): List<RecordingServer.Request> = synchronized(server.requests) { server.requests.toList() }
    private fun jobPosts() = reqs().filter { it.method == "POST" && it.path == "/jobs" }
    private fun polishPosts() = reqs().filter { it.method == "POST" && it.path == "/polish" }
    private fun realPolishPosts() = polishPosts().filter { JSONObject(String(it.body)).optString("text").isNotBlank() }
    private fun cancels() = reqs().filter { it.path.endsWith("/cancel") }

    private fun field(body: ByteArray, name: String): String? {
        val s = String(body, Charsets.UTF_8)
        val marker = "name=\"$name\"\r\n\r\n"
        val i = s.indexOf(marker).takeIf { it >= 0 } ?: return null
        return s.substring(i + marker.length).substringBefore("\r\n--")
    }

    private fun wavSeconds(body: ByteArray): Float {
        val s = String(body, Charsets.ISO_8859_1)
        val wav = WavReader.read(body.copyOfRange(s.indexOf("RIFF"), body.size).inputStream())
        return wav.samples.size / 16000f
    }

    private fun config(language: String = "", polish: Boolean = true, api: RemoteApi = RemoteApi.DICTEE_SERVER, waitBase: Long = 5_000) =
        RemoteConfig(
            "http://127.0.0.1:${server.port}", api, language = language, connectTimeoutMillis = 500,
            waitBaseMillis = waitBase, pollIntervalMillis = 20, polish = polish,
        )

    // ------------------------------------------------------------ dictation

    /** One utterance per part: [speech] s of "speech" then [pause] s of silence (one block each, see [dictate]). */
    private fun part(speech: Float = 2.2f, pause: Float = 1.0f, seed: Int = 1): FloatArray {
        val rng = Random(seed)
        return FloatArray(((speech + pause) * 16000).toInt()) { i -> if (i < speech * 16000) (rng.nextFloat() - 0.5f) * 0.4f else 0f }
    }

    /**
     * Plays [parts]; before part k (k >= 1) it calls [before] (which may wait for the server), so the test
     * knows what reached the server while the user was still speaking.
     */
    private class GatedSource(private val parts: List<FloatArray>, private val before: (Int) -> Unit) : AudioSource {
        private var part = 0
        private var pos = 0
        @Volatile var endedAtRequest = -1
        var onEnd: () -> Unit = {}

        override fun start() {}

        override fun read(buffer: FloatArray): Int {
            while (part < parts.size && pos >= parts[part].size) {
                part++
                pos = 0
                if (part < parts.size) before(part)
            }
            if (part >= parts.size) {
                if (endedAtRequest < 0) onEnd()
                return -1
            }
            val n = minOf(buffer.size, parts[part].size - pos)
            parts[part].copyInto(buffer, 0, pos, pos + n)
            pos += n
            return n
        }

        override fun stop() {}
    }

    private class Recorder : DictationListener {
        val results: MutableList<DictationResult> = Collections.synchronizedList(ArrayList())
        val inserted: MutableList<String> = Collections.synchronizedList(ArrayList())
        val waits: MutableList<Boolean> = Collections.synchronizedList(ArrayList())
        val progress: MutableList<Pair<Int, Int>> = Collections.synchronizedList(ArrayList())
        val states: MutableList<Pair<DictationState, String?>> = Collections.synchronizedList(ArrayList())
        val done = CountDownLatch(1)
        override fun onState(state: DictationState, message: String?) {
            states += state to message
            if (state == DictationState.IDLE || state == DictationState.ERROR) done.countDown()
        }
        override fun onServerWait(waiting: Boolean) { waits += waiting }
        override fun onBlocks(done: Int, total: Int) { progress += done to total }
        override fun onDictationResult(result: DictationResult, inserted: String) { results += result; this.inserted += inserted }
        fun await() = assertTrue("dictation did not finish", done.await(30, TimeUnit.SECONDS))
    }

    private inner class Run(val sink: StringSink, val rec: Recorder, val c: DictationController, val source: GatedSource) {
        /** Requests the server had received when the audio ended (the user tapped stop). */
        val atStop: Int get() = source.endedAtRequest
    }

    /** Gate failures (the server did not get a block in time), checked by every test. */
    private val gateFailures: MutableList<String> = Collections.synchronizedList(ArrayList())

    /** Waits until the server got [k] block requests: block k was sent while the user still spoke. */
    private fun untilBlocks(k: Int): (Int) -> Unit = { part ->
        if (part <= k) waitFor("block $part sent during the dictation") { jobCount.get() >= part }
    }

    private fun waitFor(what: String, timeout: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (!condition()) {
            if (System.currentTimeMillis() > end) { gateFailures += what; return }
            Thread.sleep(10)
        }
    }

    private fun dictate(
        engine: SttEngine,
        parts: List<FloatArray> = listOf(part(seed = 1), part(seed = 2), part(seed = 3), part(pause = 0.1f, seed = 4)),
        before: (Int) -> Unit = untilBlocks(parts.size - 1),
        sinkProvider: ((StringSink) -> StringSink?)? = null,
        await: Boolean = true,
        during: ((DictationController) -> Unit)? = null,
    ): Run {
        val sink = StringSink()
        val rec = Recorder()
        val c = DictationController({ if (sinkProvider != null) sinkProvider(sink) else sink }, { it.run() }, rec)
        val source = GatedSource(parts, before)
        source.onEnd = { source.endedAtRequest = reqs().size }
        val backend = engine as BlockBackend
        c.start(
            DictationSession(
                ModelCatalog.remote, source, EnergyVad(), TextPipeline(),
                transcribe = { throw AssertionError("no per-segment transcription in block mode") },
                whole = { audio, segments, ctl -> (engine as io.github.remtav.dictee.stt.WholeDictationEngine).transcribeDictation(audio, segments, ctl) },
                blocks = { ctl -> BlockDictation(backend, ctl) },
                minBlockSeconds = 2f, maxBlockSeconds = 4f,
                canFallBack = backend.canFallBack,
            ),
        )
        during?.invoke(c)
        if (await) rec.await()
        return Run(sink, rec, c, source)
    }

    private fun remote(c: RemoteConfig = config()) = RemoteEngine(ModelCatalog.remote, c)

    private fun withFallback(c: RemoteConfig = config(), local: List<String> = listOf("local a", "local b", "local c", "local d")): Pair<FallbackEngine, ScriptedEngine> {
        val engine = ScriptedEngine(ModelCatalog.parakeet, local, 0)
        return FallbackEngine(remote(c), { engine }) to engine
    }

    private fun assertNoGateFailure() = assertTrue("not sent during the dictation: $gateFailures", gateFailures.isEmpty())

    // ------------------------------------------------------------------ tests

    @Test fun blocksGoToTheServerDuringTheDictationInOrderThenOnePolishAndOneInsertion() {
        val (engine, local) = withFallback()
        val run = dictate(engine)
        assertNoGateFailure()
        // One probe of POST /polish, then N block requests in order, then one polish.
        val posts = reqs().filter { it.method == "POST" }
        assertEquals("/polish", posts.first().path)
        assertEquals("", JSONObject(String(posts.first().body)).optString("text"))
        assertEquals(listOf("/jobs", "/jobs", "/jobs", "/jobs", "/polish"), posts.drop(1).map { it.path })
        val blocks = jobPosts()
        assertTrue(blocks.all { field(it.body, "quick") == "true" && field(it.body, "polish") == "false" })
        // Each block holds its own utterance only (2.2 s of speech, the pause, the lookback): nothing twice.
        blocks.forEach { val s = wavSeconds(it.body); assertTrue("block of $s s", s in 2.2f..4.0f) }
        // Whisper gets the end of the previous text; the language of the first block is imposed on the next ones.
        assertEquals(listOf(null, "un", "deux", "trois"), blocks.map { field(it.body, "prompt") })
        assertEquals(listOf(null, "fr", "fr", "fr"), blocks.map { field(it.body, "language") })
        // At the stop, only the last block and the polish were left.
        val after = reqs().drop(run.atStop).filter { it.method == "POST" }
        assertEquals(listOf("/jobs", "/polish"), after.map { it.path })
        // The whole text is polished once, and inserted once.
        val polish = JSONObject(String(realPolishPosts().single().body))
        assertEquals("un deux trois quatre", polish.getString("text"))
        assertEquals("fr", polish.getString("language"))
        assertEquals(polished, run.sink.text)
        assertEquals(1, run.rec.results.size)
        val r = run.rec.results.single()
        assertEquals(DictationResult.Source.SERVER_POLISHED, r.source)
        assertEquals("blocks", r.transport)
        assertEquals(4, r.blocks)
        assertEquals(0, r.localBlocks)
        assertTrue(r.waitMillis > 0)
        assertEquals(0, local.calls.get())
        assertEquals(listOf(true, false), run.rec.waits.toList())
        assertEquals(4 to 4, run.rec.progress.last())
        assertEquals(DictationState.IDLE, run.c.state)
    }

    @Test fun forcedLanguageGoesWithEveryBlockAndThePolish() {
        detected = "en" // what the server would detect: ignored, the setting wins
        dictate(remote(config(language = "fr")))
        assertNoGateFailure()
        assertTrue(jobPosts().all { field(it.body, "language") == "fr" })
        assertEquals("fr", JSONObject(String(realPolishPosts().single().body)).getString("language"))
    }

    @Test fun orderKeptWhenABlockIsSlow() {
        heldBlocks += 1
        val (engine, _) = withFallback()
        val run = dictate(engine, before = { part ->
            if (part == 1) {
                waitFor("block 1 sent") { jobCount.get() >= 1 }
                Thread.sleep(300)
                heldBlocks -= 1 // block 1 answers late, after the user said more
            }
        })
        assertNoGateFailure()
        assertEquals("un deux trois quatre", JSONObject(String(realPolishPosts().single().body)).getString("text"))
        assertEquals(polished, run.sink.text)
    }

    @Test fun shortDictationIsOneJobWithThePolish() {
        val run = dictate(remote(), parts = listOf(part(pause = 0.1f)), before = {})
        assertEquals(1, jobPosts().size)
        assertEquals("true", field(jobPosts().single().body, "polish"))
        assertTrue(realPolishPosts().isEmpty())
        assertEquals(DictationResult.Source.SERVER_RAW, run.rec.results.single().source) // the fake job has no polished_text
        assertEquals("not returned", run.rec.results.single().polishStatus)
        assertEquals("Un", run.sink.text)
    }

    // ------------------------------------------------------------- fallbacks

    @Test fun failedBlockIsTranscribedLocallyAndServerTextsAreKept() {
        failBlocks += 2
        val (engine, local) = withFallback(local = listOf("deux local", "trois local", "quatre local"))
        val run = dictate(engine, before = { part -> if (part == 1) waitFor("block 1") { jobCount.get() >= 1 } })
        assertNoGateFailure()
        // Block 1 from the server, block 2 failed (502): local; then the server is skipped for a while (local).
        assertEquals(2, jobPosts().size)
        assertEquals(3, local.calls.get())
        assertEquals("Un deux local trois local quatre local", run.sink.text)
        val r = run.rec.results.single()
        assertEquals(DictationResult.Source.SERVER_RAW, r.source)
        assertEquals(3, r.localBlocks)
        assertTrue(r.fallbackReason!!, r.fallbackReason!!.contains("502"))
        // A server that just failed is not asked to polish: the raw text goes in, nothing lost.
        assertTrue(realPolishPosts().isEmpty())
        assertEquals("serveur non utilisé", r.polishStatus)
    }

    @Test fun rateLimitedBlockGoesLocalAndTheServerIsBackAfterRetryAfter() {
        RetryAfterServer(1).use {
            val c2 = config().copy(baseUrl = "http://127.0.0.1:${it.port}")
            val e2 = FallbackEngine(remote(c2), { ScriptedEngine(ModelCatalog.parakeet, listOf("deux local"), 0) }, retryAfterMillis = 60_000)
            val run = dictate(e2, before = { part ->
                if (part == 1) waitFor("block 1") { it.jobs.get() >= 1 }
                if (part == 2) { waitFor("block 2") { it.jobs.get() >= 2 }; Thread.sleep(1_300) } // past Retry-After: 1
            })
            assertNoGateFailure()
            // Block 2 refused (429, Retry-After 1 s): local, not an error; blocks 3 and 4 back on the server.
            assertEquals(4, it.jobs.get())
            assertEquals("Un deux local trois quatre", run.sink.text)
            assertEquals(1, run.rec.results.single().localBlocks)
            assertEquals(DictationState.IDLE, run.c.state)
        }
    }

    /** A server whose second `POST /jobs` is rate-limited with `Retry-After: [seconds]`. */
    private inner class RetryAfterServer(seconds: Int) : AutoCloseable {
        val jobs = AtomicInteger()
        private val s = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val port get() = s.localPort

        init {
            Thread {
                while (!s.isClosed) {
                    val sock = try { s.accept() } catch (_: java.io.IOException) { break }
                    Thread {
                        sock.use {
                            val input = java.io.BufferedInputStream(sock.getInputStream())
                            fun line(): String {
                                val sb = StringBuilder()
                                while (true) { val b = input.read(); if (b < 0 || b == '\n'.code) break; if (b != '\r'.code) sb.append(b.toChar()) }
                                return sb.toString()
                            }
                            val (method, path) = line().split(' ').let { it[0] to it[1] }
                            var len = 0
                            while (true) { val l = line(); if (l.isEmpty()) break; if (l.lowercase().startsWith("content-length:")) len = l.substringAfter(':').trim().toInt() }
                            val body = ByteArray(len); var r = 0
                            while (r < len) { val n = input.read(body, r, len - r); if (n < 0) break; r += n }
                            var extra = ""
                            val (code, text) = when {
                                method == "POST" && path == "/polish" && String(body).contains("\"\"") -> 400 to "{}"
                                method == "POST" && path == "/polish" -> 202 to """{"id": "p1", "status": "queued"}"""
                                method == "POST" && path == "/jobs" -> {
                                    val n = jobs.incrementAndGet()
                                    if (n == 2) { extra = "Retry-After: $seconds\r\n"; 429 to """{"detail": "rate limit"}""" }
                                    else 202 to """{"id": "b$n", "status": "queued"}"""
                                }
                                path.startsWith("/jobs/b") -> 200 to """{"status": "done", "result": {"text": "${blockTexts[path.removePrefix("/jobs/b").toInt() - 1]}", "language": "fr"}}"""
                                path == "/jobs/p1" -> 200 to """{"status": "done", "result": {"text": "x", "polish": {"applied": false, "fallback_reason": "error"}}}"""
                                else -> 404 to "{}"
                            }
                            val bytes = text.toByteArray()
                            sock.getOutputStream().write("HTTP/1.1 $code X\r\nContent-Type: application/json\r\n${extra}Content-Length: ${bytes.size}\r\n\r\n".toByteArray() + bytes)
                        }
                    }.start()
                }
            }.apply { isDaemon = true; start() }
        }

        override fun close() = s.close()
    }

    @Test fun polishNotAppliedInsertsTheRawText() {
        for (reason in listOf("unsupported", "timeout", "error", "safety_check:numbers")) {
            setUp()
            polishResult = { text -> """{"text": ${JSONObject.quote(text)}, "language": "fr", "polish": {"applied": false, "model": "m", "fallback_reason": "$reason"}}""" }
            val run = dictate(remote())
            assertNoGateFailure()
            assertEquals("Un deux trois quatre", run.sink.text)
            val r = run.rec.results.single()
            assertEquals(DictationResult.Source.SERVER_RAW, r.source)
            assertEquals(reason, r.polishStatus)
            assertNull(r.polishedText)
            tearDown()
            jobCount.set(0)
            polishTexts.clear()
        }
    }

    @Test fun polishRequestFailureInsertsTheRawText() {
        // The route exists (the probe got its 400) but the polish request itself fails.
        val (engine, local) = withFallback()
        val run = dictate(engine, before = { part ->
            untilBlocks(3)(part)
            if (part == 1) polishCode = 500
        })
        assertNoGateFailure()
        assertEquals("Un deux trois quatre", run.sink.text)
        assertEquals(0, local.calls.get())
        val r = run.rec.results.single()
        assertTrue(r.polishStatus, r.polishStatus.contains("500"))
        assertEquals(DictationResult.Source.SERVER_RAW, r.source)
    }

    @Test fun olderServerWithoutPolishGetsTheWholeDictationAsBefore() {
        for (code in listOf(404, 405)) {
            setUp()
            polishCode = code
            val run = dictate(remote(), before = {})
            // No block was sent: one request with the whole audio and polish, as before issue #35.
            val post = jobPosts().single()
            assertEquals("true", field(post.body, "polish"))
            assertNull(field(post.body, "prompt"))
            assertTrue("whole audio expected", wavSeconds(post.body) > 4 * 2.2f)
            assertEquals(1, polishPosts().size) // the probe only
            assertEquals("Un", run.sink.text) // the fake job answers with block 1's text
            assertEquals("jobs", run.rec.results.single().transport)
            tearDown()
            jobCount.set(0)
        }
    }

    @Test fun serverCutMidDictationLosesNothing() {
        val (engine, local) = withFallback(local = listOf("trois local", "quatre local"))
        val run = dictate(engine, before = { part ->
            untilBlocks(2)(part)
            if (part == 2) server.close() // the server (or the tunnel) goes away
        })
        assertNoGateFailure()
        assertEquals("Un deux trois local quatre local", run.sink.text)
        val r = run.rec.results.single()
        assertEquals(2, r.localBlocks)
        assertEquals(4, r.blocks)
        assertTrue(r.fallbackReason!!, r.fallbackReason!!.contains("injoignable"))
        assertEquals(DictationState.IDLE, run.c.state)
    }

    @Test fun withoutALocalModelAFailedBlockIsRetriedAtTheEnd() {
        failBlocks += 2
        val run = dictate(remote())
        assertNoGateFailure()
        // Block 2 failed once (502) and was sent again at the end (as job 5): nothing lost.
        assertEquals(5, jobPosts().size)
        // (The fake server answers by job number: the retried block 2 is job 5, "cinq".) Order kept.
        assertEquals("un cinq trois quatre", JSONObject(String(realPolishPosts().single().body)).getString("text"))
        assertEquals(polished, run.sink.text)
    }

    // --------------------------------------------- cancel, field change, Annuler

    @Test fun fieldChangedMidDictationLeavesNoServerJobAndInsertsNothing() {
        heldBlocks += 2
        val run = dictate(remote(), await = false, before = { part ->
            untilBlocks(2)(part)
            if (part == 2) Thread.sleep(5_000) // still speaking when the field changes
        })
        waitFor("block 2 polled") { reqs().count { it.path == "/jobs/b2" } >= 2 }
        run.c.cancel() // focus moved to another field
        waitFor("cancel of the running job") { cancels().any { it.path == "/jobs/b2/cancel" } }
        Thread.sleep(600)
        assertNoGateFailure()
        assertEquals(2, jobPosts().size) // nothing more sent
        assertTrue(realPolishPosts().isEmpty())
        assertEquals("", run.sink.text)
        assertTrue(run.rec.results.isEmpty())
        assertEquals(DictationState.IDLE, run.c.state)
        assertFalse(run.c.waitingServer)
    }

    @Test fun fieldChangedDuringThePolishInsertsNothingAndCancelsIt() {
        holdPolish = true
        val run = dictate(remote(), await = false)
        waitFor("polish job polled") { reqs().count { it.path == "/jobs/p1" } >= 2 }
        assertTrue(run.c.waitingServer)
        run.c.cancel()
        waitFor("polish job cancelled") { cancels().any { it.path == "/jobs/p1/cancel" } }
        Thread.sleep(300)
        assertNoGateFailure()
        assertEquals("", run.sink.text)
        assertTrue(run.rec.results.isEmpty())
    }

    @Test fun resultHeldWhileTheFieldIsAwayGoesToTheSameFieldOnly() {
        var available = false
        val run = dictate(remote(), sinkProvider = { if (available) it else null })
        assertEquals("", run.sink.text)
        available = true
        run.c.deliverPending()
        assertEquals(polished, run.sink.text)
        assertEquals(1, run.rec.results.size)
    }

    @Test fun annulerDuringTheFinalWaitTranscribesWhatIsMissingLocally() {
        heldBlocks += 4 // the last block hangs on the server
        val (engine, local) = withFallback(local = listOf("quatre local"))
        val run = dictate(engine, await = false)
        waitFor("waiting for the last block") { run.c.waitingServer && reqs().count { it.path == "/jobs/b4" } >= 2 }
        run.c.cancelServerWait()
        run.rec.await()
        assertNoGateFailure()
        assertEquals("Un deux trois quatre local", run.sink.text)
        assertEquals(1, local.calls.get())
        waitFor("cancel of the last block's job") { cancels().any { it.path == "/jobs/b4/cancel" } }
        assertTrue(cancels().none { it.path == "/jobs/b1/cancel" }) // finished jobs are left alone
        assertTrue(realPolishPosts().isEmpty())
        val r = run.rec.results.single()
        assertEquals("attente annulée", r.polishStatus)
        assertEquals(1, r.localBlocks)
        assertFalse(run.c.waitingServer)
    }

    @Test fun nothingSaidSendsNoBlock() {
        val run = dictate(remote(), parts = listOf(FloatArray(16000 * 2)), before = {})
        assertTrue(jobPosts().isEmpty())
        assertTrue(run.rec.results.isEmpty())
        assertEquals("", run.sink.text)
    }

    // --------------------------------------------------------- other paths

    @Test fun polishOffSendsBlocksWithoutProbeNorPolish() {
        val run = dictate(remote(config(polish = false)))
        assertNoGateFailure()
        assertTrue(polishPosts().isEmpty())
        assertEquals(4, jobPosts().size)
        assertEquals("Un deux trois quatre", run.sink.text)
        assertEquals("not requested", run.rec.results.single().polishStatus)
    }

    @Test fun openAiApiOneCallPerBlockWithoutPolish() {
        val run = dictate(remote(config(api = RemoteApi.OPENAI)), before = untilBlocksOpenAi(3))
        assertNoGateFailure()
        assertEquals(4, reqs().count { it.path == "/v1/audio/transcriptions" })
        assertTrue(polishPosts().isEmpty())
        assertEquals("Un deux trois quatre", run.sink.text)
    }

    private fun untilBlocksOpenAi(k: Int): (Int) -> Unit = { part ->
        if (part <= k) waitFor("block $part") { jobCount.get() >= part }
    }

    @Test fun maximumDurationStopsAndSendsWhatWasSaid() {
        val parts = List(6) { part(seed = it + 1) }
        val sink = StringSink()
        val rec = Recorder()
        val c = DictationController({ sink }, { it.run() }, rec)
        val engine = remote()
        c.start(
            DictationSession(
                ModelCatalog.remote, GatedSource(parts) {}, EnergyVad(), TextPipeline(),
                transcribe = { throw AssertionError() },
                whole = { a, s, ctl -> engine.transcribeDictation(a, s, ctl) },
                blocks = { ctl -> BlockDictation(engine, ctl) },
                minBlockSeconds = 2f, maxBlockSeconds = 4f, maxWholeSeconds = 10,
            ),
        )
        rec.await()
        // 10 s at most: three utterances of 3.2 s, the fourth cut by the limit; nothing past it.
        assertTrue("${jobPosts().size} blocks", jobPosts().size in 3..4)
        assertEquals(polished, sink.text)
        assertTrue(rec.results.single().audioSeconds <= 10f)
    }

    @Test fun lentBackendGoesThroughTheEngineLenderForEveryCall() {
        val lends = AtomicInteger()
        val engine = remote()
        val lent = LentBlockBackend { op -> lends.incrementAndGet(); op(engine) }
        val bd = BlockDictation(lent, WaitControl())
        assertTrue(bd.prepare())
        bd.add(io.github.remtav.dictee.stt.AudioBlock(0, part(), emptyList()), last = false)
        bd.add(io.github.remtav.dictee.stt.AudioBlock(1, part(seed = 2), emptyList()), last = true)
        val r = bd.finish()
        assertEquals(polished, r.texts.single())
        assertTrue(lends.get() >= 5)
    }

    // ------------------------------------------------ "Diagnostic serveur"

    @Test fun diagnosticMeasuresTheWaitAfterTheStopAndTheBlocksThenTheWholeDictation() {
        val diag = io.github.remtav.dictee.stt.ServerDiagnostic(config().copy(apiKey = "bearer-token-xyz"))
        // About 32 s: with the real block sizes (15 to 30 s), two or three blocks.
        val audio = io.github.remtav.dictee.audio.PcmAudio(joined(List(10) { part(seed = it + 1) }), 16000)
        val sample = io.github.remtav.dictee.stt.BenchSample("enchaînement", "fr", audio, "Un, deux, trois, quatre.")
        val byBlocks = diag.runBlocks(sample, EnergyVad(), realTime = false)
        assertNull(byBlocks.error)
        assertEquals(io.github.remtav.dictee.stt.ServerDiagnostic.MODE_BLOCKS, byBlocks.mode)
        assertTrue("${byBlocks.blocks} blocks", byBlocks.blocks in 2..3)
        assertTrue(byBlocks.waitMillis > 0)
        assertEquals("applied", byBlocks.polishStatus)
        assertEquals(polished, byBlocks.polishedText)
        val whole = diag.run(sample)
        assertEquals(io.github.remtav.dictee.stt.ServerDiagnostic.MODE_WHOLE, whole.mode)
        assertEquals(whole.totalMillis, whole.waitMillis)
        val report = diag.report(listOf(byBlocks, whole))
        assertTrue(report, report.contains("dictée par blocs"))
        assertTrue(report, report.contains("Attente après l'arrêt : ${byBlocks.waitMillis} ms · blocs : ${byBlocks.blocks}"))
        assertTrue(report, report.contains("dictée entière"))
        assertFalse(report.contains("bearer-token-xyz"))
    }

    @Test fun diagnosticCheckpointTrialsLast30sTwoAndFiveMinutes() {
        val diag = io.github.remtav.dictee.stt.ServerDiagnostic(config())
        val clips = listOf(
            io.github.remtav.dictee.stt.BenchSample("a", "fr", io.github.remtav.dictee.audio.PcmAudio(part(), 16000), "a"),
            io.github.remtav.dictee.stt.BenchSample("b", "en", io.github.remtav.dictee.audio.PcmAudio(part(seed = 2), 16000), "b"),
        )
        val long = diag.longPlan(clips)
        assertEquals(listOf("30 s", "2 min", "5 min"), long.map { it.name })
        assertEquals(30f, long[0].audio.samples.size / 16000f, 0.01f)
        assertEquals(120f, long[1].audio.samples.size / 16000f, 0.01f)
        // Under the 5 min limit of a dictation, lead and tail of the playback included.
        assertTrue(long[2].audio.samples.size / 16000f + 1.5f < 300f)
    }

    private fun joined(ps: List<FloatArray>): FloatArray {
        val out = FloatArray(ps.sumOf { it.size })
        var pos = 0
        for (p in ps) { p.copyInto(out, pos); pos += p.size }
        return out
    }

    @Test fun promptIsTheEndOfThePreviousTextOnAWord() {
        assertEquals("", RemoteEngine.promptTail("  "))
        assertEquals("Bonjour à tous.", RemoteEngine.promptTail(" Bonjour   à tous. "))
        val long = (1..100).joinToString(" ") { "mot$it" }
        val tail = RemoteEngine.promptTail(long)
        assertTrue(tail.length <= RemoteEngine.PROMPT_CHARS)
        assertTrue(tail.endsWith("mot100"))
        assertTrue(long.contains(" $tail"))
    }
}
