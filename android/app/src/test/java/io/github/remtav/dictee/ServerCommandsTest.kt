package io.github.remtav.dictee

import io.github.remtav.dictee.dictation.StringSink
import io.github.remtav.dictee.dictation.TextPipeline
import io.github.remtav.dictee.stt.ModelCatalog
import io.github.remtav.dictee.stt.RemoteApi
import io.github.remtav.dictee.stt.RemoteConfig
import io.github.remtav.dictee.stt.RemoteEngine
import io.github.remtav.dictee.stt.WaitControl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

/**
 * Issue #15, step 2.4: dictated punctuation in server mode. What the server returns
 * (raw text, or text polished by its LLM) goes through the same TextPipeline as a
 * local transcription, so commands are interpreted on the phone, with the typography
 * of the chosen language. The server's polish step is not changed (out of scope); what
 * it may do to a command is documented in DECISIONS.md.
 */
class ServerCommandsTest {
    private val nb = ' '
    @Volatile private var raw = ""
    @Volatile private var polished: String? = null
    @Volatile private var applied = true
    private lateinit var server: RecordingServer

    @Before fun setUp() {
        server = RecordingServer { req ->
            when {
                req.method == "POST" && req.path == "/jobs" -> 202 to """{"id": "job1", "status": "queued"}"""
                req.method == "GET" && req.path == "/jobs/job1" -> {
                    val p = polished?.let { """"polished_text": "$it", "polish": {"applied": $applied, "fallback_reason": ${if (applied) "null" else "\"safety_check:dropped_fact\""}}""" }
                        ?: """"polished_text": null"""
                    200 to """{"id": "job1", "status": "done", "result": {"text": "$raw", "language": "fr", $p}}"""
                }
                req.path == "/v1/audio/transcriptions" -> 200 to """{"text": "$raw"}"""
                // One segment, synchronous: the server may have polished it, the client reads "text" only.
                req.path == "/transcribe/streaming" ->
                    200 to """{"text": "$raw", "polished_text": "${polished ?: ""}", "language": "fr"}"""
                else -> 404 to "{}"
            }
        }
    }

    @After fun tearDown() = server.close()

    private val samples = FloatArray(16000) { kotlin.math.sin(it / 8.0).toFloat() * 0.3f }

    private fun engine() = RemoteEngine(
        ModelCatalog.remote,
        RemoteConfig("http://127.0.0.1:${server.port}", RemoteApi.DICTEE_SERVER, language = "fr",
            connectTimeoutMillis = 500, waitBaseMillis = 5_000, pollIntervalMillis = 20),
    )

    /** Whole dictation: what the keyboard types for the texts the server returned. */
    private fun whole(): String {
        val sink = StringSink()
        val pipeline = TextPipeline()
        val result = engine().transcribeDictation(samples, listOf(samples), WaitControl())
        for (text in result.texts) pipeline.applyFinal(text, ModelCatalog.remote.family, sink)
        return sink.text
    }

    /** Dictation by segments: each segment transcribed by the server, no polish. */
    private fun segments(vararg texts: String): String {
        val sink = StringSink()
        val pipeline = TextPipeline()
        for (t in texts) {
            raw = t
            pipeline.applyFinal(engine().transcribe(samples, 16000).text, ModelCatalog.remote.family, sink)
        }
        return sink.text
    }

    // ------------------------------------------------------------ whole dictation (issue #13)

    @Test fun polishedTextKeepingTheCommandWordsIsInterpreted() {
        raw = "il a dit deux-points ouvrir les guillemets bonjour fermer les guillemets"
        polished = "Il a dit deux-points ouvrir les guillemets bonjour fermer les guillemets"
        assertEquals("Il a dit$nb: «${nb}bonjour${nb}»", whole())
    }

    @Test fun rawTextAfterAPolishFallbackIsInterpretedToo() {
        // The server's safety check often rejects a polish that dropped a command: the raw text comes back.
        raw = "il a dit deux-points ouvrir les guillemets bonjour fermer les guillemets"
        polished = "Il a dit : « bonjour »"
        applied = false
        assertEquals("Il a dit$nb: «${nb}bonjour${nb}»", whole())
    }

    @Test fun signsAlreadyPlacedByThePolishAreKeptOnceAndLeftAsTheServerWroteThem() {
        // The LLM turned the commands into signs itself (its rule 3 puts an ordinary space before : ? ! ;):
        // no command is left to interpret, so no duplicate and no Québec typography.
        raw = "il a dit deux-points ouvrir les guillemets bonjour fermer les guillemets"
        polished = "Il a dit : « bonjour »"
        assertEquals("Il a dit : « bonjour »", whole())
    }

    @Test fun commandWordsLeftBesideASignTheModelAlreadyPlacedGiveOneSign() {
        raw = "tu viens ce soir point d'interrogation"
        polished = "Tu viens ce soir ? Point d'interrogation."
        assertEquals("Tu viens ce soir?", whole())
    }

    @Test fun aSignTheModelPlacedAndCommandsAfterItGiveNoDuplicate() {
        raw = "c'est prêt deux-points ouvrir la parenthèse voir plus bas fermer la parenthèse"
        polished = "C'est prêt : ouvrir la parenthèse voir plus bas fermer la parenthèse."
        val out = whole()
        // the model's own colon stays as it wrote it; the parenthesis words are still commands
        assertEquals("C'est prêt : (voir plus bas)", out.removeSuffix("."))
    }

    @Test fun openAiCompatibleServerHasNoPolishAndCommandsApply() {
        raw = "il a dit deux-points ouvrir les guillemets bonjour fermer les guillemets"
        val e = RemoteEngine(ModelCatalog.remote, RemoteConfig("http://127.0.0.1:${server.port}", RemoteApi.OPENAI))
        val sink = StringSink()
        val r = e.transcribeDictation(samples, listOf(samples), WaitControl())
        r.texts.forEach { TextPipeline().applyFinal(it, ModelCatalog.remote.family, sink) }
        assertEquals("Il a dit$nb: «${nb}bonjour${nb}»", sink.text)
    }

    // ------------------------------------------------------------ segments, transcribed by the server

    @Test fun segmentsGoThroughTheCommandsEvenWhenTheServerPolishesByDefault() {
        // Server started with WHISPER_POLISH=on: it also returns polished_text, which a segment ignores.
        polished = "Il a dit : « autre chose »"
        assertEquals(
            "Il a dit$nb: «${nb}bonjour${nb}»",
            segments("il a dit deux-points ouvrir les guillemets bonjour fermer les guillemets"),
        )
    }

    @Test fun commandsInSeparateSegmentsStillApply() {
        assertEquals(
            "Il a dit$nb: «${nb}bonjour${nb}»",
            segments("Il a dit deux-points", "ouvrir les guillemets", "bonjour", "fermer les guillemets"),
        )
    }

    @Test fun aSegmentRequestAsksForNoPolish() {
        segments("bonjour")
        val req = server.requests.single()
        assertEquals("/transcribe/streaming", req.path)
        assertNotEquals("true", field(req.body, "polish"))
    }

    private fun field(body: ByteArray, name: String): String? {
        val s = String(body, Charsets.ISO_8859_1)
        val marker = "name=\"$name\"\r\n\r\n"
        val i = s.indexOf(marker).takeIf { it >= 0 } ?: return null
        return s.substring(i + marker.length).substringBefore("\r\n--")
    }
}
