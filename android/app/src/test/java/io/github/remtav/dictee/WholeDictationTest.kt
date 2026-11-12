package io.github.remtav.dictee

import io.github.remtav.dictee.audio.PcmAudio
import io.github.remtav.dictee.audio.PcmAudioSource
import io.github.remtav.dictee.audio.WavReader
import io.github.remtav.dictee.dictation.DictationController
import io.github.remtav.dictee.dictation.DictationListener
import io.github.remtav.dictee.dictation.DictationSession
import io.github.remtav.dictee.dictation.DictationState
import io.github.remtav.dictee.dictation.StringSink
import io.github.remtav.dictee.dictation.TextPipeline
import io.github.remtav.dictee.stt.BenchSample
import io.github.remtav.dictee.stt.DictationResult
import io.github.remtav.dictee.stt.FallbackEngine
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.RemoteApi
import io.github.remtav.dictee.stt.RemoteConfig
import io.github.remtav.dictee.stt.RemoteEngine
import io.github.remtav.dictee.stt.RemoteException
import io.github.remtav.dictee.stt.RemoteFailure
import io.github.remtav.dictee.stt.RemoteLog
import io.github.remtav.dictee.stt.ServerDiagnostic
import io.github.remtav.dictee.stt.WaitControl
import io.github.remtav.dictee.stt.WholeDictationEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #13: the whole dictation goes to the server in one request (job API,
 * polish), passes Cloudflare Access, and falls back to the local model on the
 * kept audio whatever goes wrong.
 */
class WholeDictationTest {
    private val secret = "cf-secret-0123456789abcdef"
    private val clientId = "abcd1234.access"
    private val raw = "bonjour euh je voulais dire que le serveur marche"
    private val polished = "Bonjour, je voulais dire que le serveur marche."

    /** What the fake server answers. */
    @Volatile private var jobStatus = "done"
    @Volatile private var jobsCode = 202
    @Volatile private var pollsBeforeDone = 2
    @Volatile private var polishJson = """"polished_text": "$polished", "polish": {"applied": true, "model": "m", "fallback_reason": null}"""
    @Volatile private var errorBody = ""
    @Volatile private var postDelayMillis = 0L
    @Volatile private var pollCode = 200
    private val polls = AtomicInteger()
    private lateinit var server: RecordingServer
    private val logs: MutableList<String> = Collections.synchronizedList(ArrayList())

    @Before fun setUp() {
        server = RecordingServer { req -> respond(req) }
        RemoteEngine.defaultLog = RemoteLog { logs += it }
    }

    @After fun tearDown() {
        server.close()
        RemoteEngine.defaultLog = RemoteLog.NONE
    }

    private fun respond(req: RecordingServer.Request): Pair<Int, String> = when {
        req.method == "POST" && req.path == "/jobs" ->
            if (postDelayMillis > 0) Thread.sleep(postDelayMillis).let { 202 to """{"id": "job1", "status": "queued"}""" }
            else if (jobsCode == 202) 202 to """{"id": "job1", "status": "queued"}""" else jobsCode to errorBody
        req.method == "GET" && req.path == "/jobs/job1" -> {
            val n = polls.incrementAndGet()
            when {
                pollCode != 200 -> pollCode to "{}"
                jobStatus == "running" || n <= pollsBeforeDone -> 200 to """{"id": "job1", "status": "running", "stage": "polish"}"""
                jobStatus == "error" -> 200 to """{"id": "job1", "status": "error", "error": {"code": "x", "message": "GPU out of memory"}}"""
                else -> 200 to """{"id": "job1", "status": "done", "result": {"text": " $raw ", "language": "fr", $polishJson}}"""
            }
        }
        req.method == "POST" && req.path == "/jobs/job1/cancel" -> 200 to """{"id": "job1", "status": "cancelled"}"""
        req.method == "POST" && req.path == "/transcribe/streaming" ->
            200 to """{"text": "$raw", "language": "fr", $polishJson}"""
        req.path == "/health" -> 200 to """{"model": "large-v3-turbo", "device": "cuda", "polish_available": true}"""
        req.path == "/v1/audio/transcriptions" -> 200 to """{"text": "Hello from OpenAI."}"""
        else -> 404 to "{}"
    }

    private fun base() = "http://127.0.0.1:${server.port}"

    private fun config(
        cf: Boolean = true,
        api: RemoteApi = RemoteApi.DICTEE_SERVER,
        url: String = base(),
        waitBase: Long = 5_000,
    ) = RemoteConfig(
        url, api, language = "fr",
        cfAccessClientId = if (cf) clientId else "", cfAccessClientSecret = if (cf) secret else "",
        connectTimeoutMillis = 500, waitBaseMillis = waitBase, pollIntervalMillis = 30,
    )

    private fun remote(c: RemoteConfig = config()) = RemoteEngine(ModelCatalog.remote, c)

    private fun field(body: ByteArray, name: String): String? {
        val s = String(body, Charsets.ISO_8859_1)
        val marker = "name=\"$name\"\r\n\r\n"
        val i = s.indexOf(marker).takeIf { it >= 0 } ?: return null
        return s.substring(i + marker.length).substringBefore("\r\n--")
    }

    /** A snapshot: the server thread may still be appending to the list. */
    private fun reqs(): List<RecordingServer.Request> = synchronized(server.requests) { server.requests.toList() }

    private fun jobPosts() = reqs().filter { it.method == "POST" && it.path == "/jobs" }

    // ----------------------------------------------------- controller, end to end

    private class Recorder : DictationListener {
        val results: MutableList<DictationResult> = Collections.synchronizedList(ArrayList())
        val waits: MutableList<Boolean> = Collections.synchronizedList(ArrayList())
        val states: MutableList<Pair<DictationState, String?>> = Collections.synchronizedList(ArrayList())
        val infos: MutableList<String> = Collections.synchronizedList(ArrayList())
        override fun onInfo(message: String) { infos += message }
        val done = java.util.concurrent.CountDownLatch(1)
        override fun onState(state: DictationState, message: String?) {
            states += state to message
            if (state == DictationState.IDLE || state == DictationState.ERROR) done.countDown()
        }
        override fun onServerWait(waiting: Boolean) { waits += waiting }
        override fun onDictationResult(result: DictationResult, inserted: String) { results += result }
        fun await() = assertTrue("dictation did not finish", done.await(30, java.util.concurrent.TimeUnit.SECONDS))
    }

    private fun dictate(
        engine: WholeDictationEngine,
        clips: List<PcmAudio> = listOf(burst(1f), burst(1.2f), burst(0.8f)),
        maxSeconds: Int = 300,
        control: ((DictationController) -> Unit)? = null,
    ): Triple<StringSink, Recorder, DictationController> {
        val sink = StringSink()
        val rec = Recorder()
        val c = DictationController({ sink }, { it.run() }, rec)
        val session = DictationSession(
            ModelCatalog.remote, PcmAudioSource(clips, realTime = false, gapSeconds = 1f), EnergyVad(), TextPipeline(),
            transcribe = { throw AssertionError("no per-segment transcription in whole mode") },
            partials = true, // ignored in whole mode
            whole = { audio, segments, ctl -> engine.transcribeDictation(audio, segments, ctl) },
            maxWholeSeconds = maxSeconds,
        )
        c.start(session)
        control?.invoke(c)
        rec.await()
        return Triple(sink, rec, c)
    }

    @Test fun oneRequestPerDictationWithPolishAndPolishedTextInserted() {
        val (sink, rec, c) = dictate(remote())
        assertEquals(polished, sink.text)
        assertEquals(1, jobPosts().size)
        val post = jobPosts().single()
        assertEquals("true", field(post.body, "polish"))
        assertEquals("true", field(post.body, "quick"))
        assertEquals("fr", field(post.body, "language"))
        // The WAV holds the whole dictation: three bursts and the silences between them.
        val s = String(post.body, Charsets.ISO_8859_1)
        val wav = WavReader.read(post.body.copyOfRange(s.indexOf("RIFF"), post.body.size).inputStream())
        assertTrue("whole audio expected, got ${wav.samples.size}", wav.samples.size >= (1f + 1.2f + 0.8f + 2f) * 16000)
        assertTrue(reqs().none { it.path == "/transcribe/streaming" })
        assertEquals(DictationResult.Source.SERVER_POLISHED, rec.results.single().source)
        assertEquals(listOf(true, false), rec.waits.toList())
        assertEquals(DictationState.IDLE, c.state)
        assertFalse(c.waitingServer)
    }

    @Test fun voiceCommandsAndAssemblyApplyToThePolishedText() {
        polishJson = """"polished_text": "Première ligne. Nouvelle ligne. Deuxième ligne.", "polish": {"applied": true}"""
        val (sink, _, _) = dictate(remote())
        assertEquals("Première ligne.\nDeuxième ligne.", sink.text)
    }

    @Test fun rawTextInsertedWhenPolishFailed() {
        polishJson = """"polish": {"applied": false, "model": "m", "fallback_reason": "timeout"}"""
        val (sink, rec, _) = dictate(remote())
        assertEquals("Bonjour euh je voulais dire que le serveur marche", sink.text)
        val r = rec.results.single()
        assertEquals(DictationResult.Source.SERVER_RAW, r.source)
        assertEquals("timeout", r.polishStatus)
        assertNull(r.polishedText)
    }

    @Test fun rawTextInsertedWhenPolishMissing() {
        polishJson = """"polished_text": null"""
        val (sink, rec, _) = dictate(remote())
        assertEquals("Bonjour euh je voulais dire que le serveur marche", sink.text)
        assertEquals(DictationResult.Source.SERVER_RAW, rec.results.single().source)
    }

    @Test fun nothingSaidSendsNothing() {
        val (sink, rec, _) = dictate(remote(), clips = listOf(PcmAudio(FloatArray(16000), 16000)))
        assertEquals("", sink.text)
        assertTrue(reqs().isEmpty())
        assertTrue(rec.results.isEmpty())
    }

    @Test fun maxDurationStopsRecordingAndSendsWhatWasSaid() {
        val seen = AtomicInteger()
        val engine = object : WholeDictationEngine {
            override fun transcribeDictation(audio: FloatArray, segments: List<FloatArray>, control: WaitControl): DictationResult {
                seen.set(audio.size)
                return DictationResult(listOf("dit pendant deux secondes"), DictationResult.Source.SERVER_RAW)
            }
        }
        val (sink, rec, _) = dictate(engine, clips = listOf(burst(6f)), maxSeconds = 2)
        assertTrue("got ${seen.get()} samples", seen.get() in 1..2 * 16000)
        assertEquals("Dit pendant deux secondes", sink.text)
        assertTrue(rec.infos.any { it.contains("Durée maximale") }) // information, not an error
        assertTrue(rec.states.none { it.second?.contains("Durée maximale") == true })
        assertEquals(DictationState.IDLE, rec.states.last().first)
    }

    // ------------------------------------------------------------ local fallback

    private fun fallback(c: RemoteConfig = config()): Pair<FallbackEngine, ScriptedEngine> {
        val local = ScriptedEngine(ModelCatalog.parakeet, listOf("premier segment.", "deuxième segment.", "troisième."), 0)
        return FallbackEngine(remote(c), { local }) to local
    }

    private fun assertLocalFallback(expectedFailure: String? = null, c: RemoteConfig = config()) {
        val (engine, local) = fallback(c)
        val (sink, rec, _) = dictate(engine)
        // Nothing lost: every VAD segment of the kept audio went through the local model.
        assertEquals("Premier segment. Deuxième segment. Troisième.", sink.text)
        assertEquals(3, local.calls.get())
        val r = rec.results.single()
        assertEquals(DictationResult.Source.LOCAL_FALLBACK, r.source)
        if (expectedFailure != null) assertTrue("reason: ${r.fallbackReason}", r.fallbackReason!!.contains(expectedFailure))
    }

    @Test fun fallsBackOnServerError() { jobsCode = 502; errorBody = """{"detail": "bad gateway"}"""; assertLocalFallback("502") }

    @Test fun fallsBackOnJobError() { jobStatus = "error"; assertLocalFallback("GPU out of memory") }

    @Test fun fallsBackOn401() { jobsCode = 401; assertLocalFallback("Accès refusé") }

    @Test fun fallsBackOn403() { jobsCode = 403; assertLocalFallback("403") }

    @Test fun fallsBackOnAccessLoginRedirect() {
        jobsCode = 302
        assertLocalFallback("Cloudflare Access")
        // The redirect was not followed.
        assertTrue(reqs().none { it.path.contains("cdn-cgi") })
    }

    @Test fun fallsBackWhenUnreachable() {
        val dead = ServerSocket(0).use { it.localPort }
        assertLocalFallback("injoignable", config(url = "http://127.0.0.1:$dead"))
    }

    @Test fun fallsBackOnTimeoutAndCancelsTheServerJob() {
        jobStatus = "running"
        // Budget: 0.5 s + 1 × the audio (~5 s of dictation) — short for the test.
        val c = config(waitBase = 500).copy(waitCapMillis = 1_500)
        assertLocalFallback("Délai dépassé", c)
        waitUntil { reqs().any { it.path == "/jobs/job1/cancel" } }
    }

    @Test fun fallsBackOnHtmlInsteadOfJson() {
        jobsCode = 200; errorBody = "<html>login</html>"
        // 200 with HTML on POST /jobs: respond() gives errorBody for codes != 202.
        assertLocalFallback("illisible")
    }

    @Test fun serverSkippedForAMinuteAfterAFailure() {
        jobsCode = 500
        var now = 0L
        val local = ScriptedEngine(ModelCatalog.parakeet, List(10) { "local $it." }, 0)
        val engine = FallbackEngine(remote(), { local }, 60_000, { now })
        val seg = burst(0.5f).samples
        assertEquals(DictationResult.Source.LOCAL_FALLBACK, engine.transcribeDictation(seg, listOf(seg), WaitControl()).source)
        assertEquals(1, jobPosts().size)
        now += 10_000
        engine.transcribeDictation(seg, listOf(seg), WaitControl())
        assertEquals(1, jobPosts().size) // skipped, no new request
        jobsCode = 202
        now += 60_000
        assertEquals(DictationResult.Source.SERVER_POLISHED, engine.transcribeDictation(seg, listOf(seg), WaitControl()).source)
    }

    // -------------------------------------------------------------- cancellation

    @Test fun cancelWhileWaitingCancelsTheJobAndTranscribesLocally() {
        jobStatus = "running"
        val (engine, local) = fallback()
        val (sink, rec, _) = dictate(engine) { c ->
            waitUntil { c.waitingServer && polls.get() >= 2 }
            c.cancelServerWait()
        }
        assertEquals("Premier segment. Deuxième segment. Troisième.", sink.text)
        assertEquals(3, local.calls.get())
        assertEquals(DictationResult.Source.LOCAL_FALLBACK, rec.results.single().source)
        waitUntil { reqs().any { it.method == "POST" && it.path == "/jobs/job1/cancel" } }
        // A user cancel says nothing about the server: the next dictation tries it again.
        jobStatus = "done"
        val seg = burst(0.5f).samples
        assertEquals(DictationResult.Source.SERVER_POLISHED, engine.transcribeDictation(seg, listOf(seg), WaitControl()).source)
    }

    @Test fun droppedDictationTranscribesNothing() {
        jobStatus = "running"
        val (engine, local) = fallback()
        val control = WaitControl()
        val seg = burst(0.5f).samples
        val t = Thread { Thread.sleep(300); control.abandon() }.apply { start() }
        val r = engine.transcribeDictation(seg, listOf(seg), control)
        t.join()
        assertTrue(r.texts.isEmpty())
        assertEquals(0, local.calls.get())
        waitUntil { reqs().any { it.path == "/jobs/job1/cancel" } }
    }

    @Test fun cancelBeforeAnyRequestGoesStraightToLocal() {
        val (engine, local) = fallback()
        val control = WaitControl().apply { toLocal() }
        val seg = burst(0.5f).samples
        val r = engine.transcribeDictation(seg, listOf(seg), control)
        assertEquals(DictationResult.Source.LOCAL_FALLBACK, r.source)
        assertEquals(1, local.calls.get())
        assertTrue(reqs().isEmpty())
    }

    // ---------------------------------------------------- transports and headers

    @Test fun syncRouteWhenTheServerHasNoJobApi() {
        jobsCode = 404
        val (sink, rec, _) = dictate(remote())
        assertEquals(polished, sink.text)
        assertEquals("sync", rec.results.single().transport)
        val sync = reqs().single { it.path == "/transcribe/streaming" }
        assertEquals("true", field(sync.body, "polish"))
    }

    @Test fun cloudflareHeadersOnEveryRequestWhenConfigured() {
        dictate(remote(config(cf = true)))
        RemoteEngine.check(config(cf = true)).getOrThrow()
        assertTrue(reqs().size >= 4) // POST /jobs, polls, /health
        for (r in reqs()) {
            assertEquals(r.path, clientId, r.headers["cf-access-client-id"])
            assertEquals(r.path, secret, r.headers["cf-access-client-secret"])
        }
    }

    @Test fun noCloudflareHeadersWhenNotConfigured() {
        dictate(remote(config(cf = false)))
        RemoteEngine.check(config(cf = false)).getOrThrow()
        // One of the two alone sends neither.
        RemoteEngine.check(config(cf = false).copy(cfAccessClientId = clientId)).getOrThrow()
        for (r in reqs()) {
            assertFalse(r.headers.containsKey("cf-access-client-id"))
            assertFalse(r.headers.containsKey("cf-access-client-secret"))
        }
    }

    @Test fun bearerTokenKeptAlongsideCloudflare() {
        dictate(remote(config().copy(apiKey = "bearer-token")))
        assertTrue(reqs().all { it.headers["authorization"] == "Bearer bearer-token" })
    }

    @Test fun openAiApiSendsTheWholeDictationOnceWithoutPolish() {
        val (sink, rec, _) = dictate(remote(config(api = RemoteApi.OPENAI)))
        assertEquals("Hello from OpenAI.", sink.text)
        val req = reqs().single()
        assertEquals("/v1/audio/transcriptions", req.path)
        assertNull(field(req.body, "polish"))
        assertEquals("not requested", rec.results.single().polishStatus)
        assertEquals(secret, req.headers["cf-access-client-secret"])
    }

    @Test fun waitBudget() {
        val c = RemoteConfig("http://h")
        assertEquals(90_000, c.waitBudgetMillis(30f))
        assertEquals(360_000, c.waitBudgetMillis(300f)) // 60 + 300 s, capped at 6 min
        assertEquals(4_000, c.connectTimeoutMillis)
    }

    // -------------------------------------------------------- connection test

    @Test fun connectionTestTellsTheCasesApart() {
        assertTrue(RemoteEngine.check(config()).getOrThrow().contains("Cloudflare Access accepté"))
        for ((code, kind) in listOf(401 to RemoteFailure.ACCESS_DENIED, 403 to RemoteFailure.ACCESS_DENIED, 302 to RemoteFailure.ACCESS_REDIRECT)) {
            RecordingServer { code to "{}" }.use { s ->
                val e = RemoteEngine.check(config(url = "http://127.0.0.1:${s.port}")).exceptionOrNull() as RemoteException
                assertEquals("HTTP $code", kind, e.failure)
            }
        }
        val dead = ServerSocket(0).use { it.localPort }
        val e = RemoteEngine.check(config(url = "http://127.0.0.1:$dead")).exceptionOrNull() as RemoteException
        assertEquals(RemoteFailure.UNREACHABLE, e.failure)
        RecordingServer { 302 to "" }.use { s ->
            val msg = RemoteEngine.check(config(url = "http://127.0.0.1:${s.port}")).exceptionOrNull()!!.message!!
            assertTrue(msg.contains("Service Auth"))
        }
    }

    // ------------------------------------------------------------ no secret leaks

    @Test fun secretNeverLogged() {
        // A server that echoes the request headers in its error (worst case).
        jobsCode = 500
        errorBody = """{"detail": "bad headers: CF-Access-Client-Secret=$secret"}"""
        val (engine, _) = fallback()
        val (_, rec, _) = dictate(engine)
        val messages = ArrayList<String>()
        messages += rec.results.mapNotNull { it.fallbackReason }
        messages += rec.states.mapNotNull { it.second }
        // Every other path that logs or reports: success, timeout, cancel, check, diagnostic.
        jobsCode = 202
        dictate(remote())
        jobStatus = "running"
        dictate(FallbackEngine(remote(config(waitBase = 300).copy(waitCapMillis = 800)), { ScriptedEngine(ModelCatalog.parakeet, List(5) { "x" }, 0) }))
        RemoteEngine.check(config()).getOrThrow()
        RecordingServer { 500 to "echo $secret" }.use { s ->
            messages += RemoteEngine.check(config(url = "http://127.0.0.1:${s.port}")).exceptionOrNull()!!.message!!
        }
        val diag = ServerDiagnostic(config())
        jobStatus = "done"
        val trials = listOf(diag.run(BenchSample("en1", "en", burst(0.5f), "ref")))
        messages += diag.report(trials)
        messages += config().toString()
        assertTrue("no log captured", logs.isNotEmpty())
        for (line in logs + messages) {
            assertFalse("secret in: $line", line.contains(secret))
        }
        assertTrue(messages.any { it.contains("***") }) // the echoed secret was redacted
    }

    @Test fun diagnosticReportHasTimingsAndBothTextsButNoSecret() {
        val diag = ServerDiagnostic(config().copy(apiKey = "bearer-token-xyz"))
        val plan = diag.plan(listOf(BenchSample("en1", "en", burst(0.5f), "a."), BenchSample("fr1", "fr", burst(0.7f), "b.")))
        assertEquals(listOf("en1", "fr1", "enchaînement"), plan.map { it.name })
        assertEquals((0.5f + 1f + 0.7f) * 16000, plan.last().audio.samples.size.toFloat(), 1f)
        val trials = plan.map { polls.set(0); diag.run(it) }
        assertTrue(trials.all { it.error == null && it.polishStatus == "applied" && it.totalMillis >= it.uploadMillis })
        assertEquals(3, jobPosts().size)
        val report = diag.report(trials)
        assertTrue(report.contains("Brut : $raw"))
        assertTrue(report.contains("Poli : $polished"))
        assertTrue(report.contains("Cloudflare Access : service token configuré"))
        assertFalse(report.contains(secret))
        assertFalse(report.contains(clientId))
        assertFalse(report.contains("bearer-token-xyz"))
    }


    // ------------------------------------------------- findings of the review

    @Test fun cancelDuringUploadCancelsTheJobOnceCreated() {
        postDelayMillis = 1_000 // the server answers POST /jobs late (slow upload)
        val (engine, local) = fallback()
        val (sink, _, _) = dictate(engine) { c ->
            waitUntil { jobPosts().isNotEmpty() }
            c.cancelServerWait()
        }
        assertEquals("Premier segment. Deuxième segment. Troisième.", sink.text)
        assertEquals(3, local.calls.get())
        // The job created after we gave up is cancelled as soon as its id arrives.
        waitUntil { reqs().any { it.method == "POST" && it.path == "/jobs/job1/cancel" } }
    }

    @Test fun transientPollFailureFallsBackAndCancelsTheJob() {
        pollCode = 524 // Cloudflare timeout on one GET
        assertLocalFallback("524")
        waitUntil { reqs().any { it.method == "POST" && it.path == "/jobs/job1/cancel" } }
    }

    @Test fun anyRemoteFailureStillFallsBack() {
        val broken = object : io.github.remtav.dictee.stt.SttEngine, WholeDictationEngine {
            override val spec = ModelCatalog.remote
            override val provider = "remote"
            override fun transcribe(samples: FloatArray, sampleRate: Int) = throw IllegalStateException("bug")
            override fun transcribeDictation(audio: FloatArray, segments: List<FloatArray>, control: WaitControl): DictationResult =
                throw IllegalArgumentException("Unexpected char 0x200b in header value")
            override fun release() {}
        }
        val local = ScriptedEngine(ModelCatalog.parakeet, listOf("sauvé."), 0)
        val seg = burst(0.5f).samples
        val r = FallbackEngine(broken, { local }).transcribeDictation(seg, listOf(seg), WaitControl())
        assertEquals(listOf("sauvé."), r.texts)
        assertFalse(r.fallbackReason!!.contains("0x200b")) // only the exception class
        // Whole audio lost (out of memory) but segments kept: straight to local.
        val r2 = FallbackEngine(remote(), { local }).transcribeDictation(FloatArray(0), listOf(seg), WaitControl())
        assertEquals(DictationResult.Source.LOCAL_FALLBACK, r2.source)
        assertTrue(reqs().isEmpty())
    }

    @Test fun serverWaitEndsWhenTheLocalFallbackStarts() {
        jobStatus = "running"
        lateinit var controller: DictationController
        val waitingDuringLocal = Collections.synchronizedList(ArrayList<Boolean>())
        val local = object : io.github.remtav.dictee.stt.SttEngine {
            override val spec = ModelCatalog.parakeet
            override val provider = "local"
            override fun transcribe(samples: FloatArray, sampleRate: Int): io.github.remtav.dictee.stt.Transcription {
                waitingDuringLocal += controller.waitingServer
                return io.github.remtav.dictee.stt.Transcription("local.")
            }
            override fun release() {}
        }
        val (_, rec, _) = dictate(FallbackEngine(remote(), { local })) { c ->
            controller = c
            waitUntil { c.waitingServer }
            c.cancelServerWait()
        }
        assertEquals(listOf(false, false, false), waitingDuringLocal.toList())
        assertEquals(listOf(true, false), rec.waits.toList())
    }

    @Test fun waitingStateIsPerDictation() {
        val sink = StringSink()
        val rec = Recorder()
        val c = DictationController({ sink }, { it.run() }, rec)
        val releaseA = java.util.concurrent.CountDownLatch(1)
        val releaseB = java.util.concurrent.CountDownLatch(1)
        fun session(release: java.util.concurrent.CountDownLatch, text: String) = DictationSession(
            ModelCatalog.remote, PcmAudioSource(listOf(burst(0.5f)), realTime = false), EnergyVad(), TextPipeline(),
            transcribe = { throw AssertionError() },
            whole = { _, _, _ ->
                // A request that does not react to interrupts, like socket I/O on Android.
                while (!release.await(50, java.util.concurrent.TimeUnit.MILLISECONDS)) Unit
                DictationResult(listOf(text), DictationResult.Source.SERVER_RAW)
            },
        )
        c.start(session(releaseA, "a."))
        waitUntil { c.waitingServer }
        c.cancel() // field changed: A is dropped but its request is still running
        assertFalse(c.waitingServer)
        c.start(session(releaseB, "b."))
        waitUntil { c.waitingServer }
        releaseA.countDown() // A's request ends late: it must not end B's wait
        Thread.sleep(300)
        assertTrue(c.waitingServer)
        assertTrue(c.canCancelServerWait)
        releaseB.countDown()
        waitUntil { c.state == DictationState.IDLE }
        assertEquals("B.", sink.text)
    }

    @Test fun resultHeldWhileTheFieldIsAwayKeepsItsDetails() {
        var available = false
        val sink = StringSink()
        val rec = Recorder()
        val c = DictationController({ if (available) sink else null }, { it.run() }, rec)
        c.start(DictationSession(
            ModelCatalog.remote, PcmAudioSource(listOf(burst(0.5f)), realTime = false), EnergyVad(), TextPipeline(),
            transcribe = { throw AssertionError() },
            whole = { a, segs, ctl -> remote().transcribeDictation(a, segs, ctl) },
        ))
        rec.await()
        assertEquals("", sink.text)
        available = true
        c.deliverPending()
        assertEquals(polished, sink.text)
        assertEquals(DictationResult.Source.SERVER_POLISHED, rec.results.single().source)
    }

    @Test fun invisibleCharactersPastedWithTheSecretAreDropped() {
        assertEquals(secret, RemoteConfig.cleanHeaderValue(" ​$secret\n"))
        val c = config().copy(cfAccessClientSecret = "​$secret ")
        dictate(remote(c))
        assertTrue(reqs().all { it.headers["cf-access-client-secret"] == secret })
    }

    @Test fun redirectElsewhereIsNotCalledAnAccessLogin() {
        val s = java.net.ServerSocket(0)
        Thread {
            s.accept().use { sock ->
                sock.getInputStream().read(ByteArray(4096))
                sock.getOutputStream().write("HTTP/1.1 301 X\r\nLocation: https://stt.example.com/health\r\nContent-Length: 0\r\n\r\n".toByteArray())
            }
        }.start()
        val e = RemoteEngine.check(config(url = "http://127.0.0.1:${s.localPort}")).exceptionOrNull() as RemoteException
        s.close()
        assertEquals(RemoteFailure.ACCESS_REDIRECT, e.failure)
        assertTrue(e.message!!, e.message!!.contains("stt.example.com"))
    }

    private fun waitUntil(timeout: Long = 5_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while (!condition()) {
            if (System.currentTimeMillis() > end) fail("condition not met in $timeout ms")
            Thread.sleep(20)
        }
    }
}
