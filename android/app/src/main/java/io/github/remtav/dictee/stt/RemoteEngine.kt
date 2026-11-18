package io.github.remtav.dictee.stt

import io.github.remtav.dictee.audio.PcmAudio
import io.github.remtav.dictee.audio.WavReader
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.util.UUID

/**
 * Transcription on a server the user runs (opt-in, off by default). This is
 * the one place where audio leaves the device: only when the user configured
 * a server and picked "Serveur distant" as the model.
 *
 * Two APIs are spoken:
 * - [RemoteApi.DICTEE_SERVER]: the FastAPI/WhisperX server of this repository.
 *   A whole dictation goes through the job API (`POST /jobs` with `quick` and
 *   `polish`, then `GET /jobs/{id}` until the result), so a long request is
 *   never cut by a proxy's time limit (Cloudflare tunnel). Single segments
 *   (comparison screen) use `POST /transcribe/streaming`;
 * - [RemoteApi.OPENAI]: `POST /v1/audio/transcriptions`, spoken by most
 *   self-hosted servers (faster-whisper-server/speaches, whisper.cpp server,
 *   LocalAI, vLLM...) and by hosted APIs. No polish there.
 *
 * Audio is sent as a 16 kHz 16-bit mono WAV. Every request carries the
 * optional Bearer token and the optional Cloudflare Access service token.
 * Redirects are never followed: a 302 is Cloudflare Access sending an
 * unauthenticated client to its login page.
 */
class RemoteEngine(
    override val spec: ModelSpec,
    private val config: RemoteConfig,
    /** Words to favour: sent as the `prompt` of OpenAI-compatible requests. */
    private val hotwords: List<String> = emptyList(),
    private val log: RemoteLog = defaultLog,
) : SttEngine, WholeDictationEngine, BlockBackend, BlockServer {
    override val provider: String = "remote"

    init {
        require(config.isConfigured) { "Adresse du serveur invalide" }
    }

    private fun log(msg: String) = log.log(config.redact(msg))

    // ------------------------------------------------------------- one segment

    /** One segment, synchronous (comparison screen). No polish. */
    override fun transcribe(samples: FloatArray, sampleRate: Int): Transcription {
        val seconds = samples.size / sampleRate.toFloat()
        if (samples.isEmpty()) return Transcription("", audioSeconds = 0f)
        val wav = WavReader.encode(PcmAudio(samples, sampleRate))
        val start = System.nanoTime()
        val body = post(config.transcriptionUrl(), syncFields(polish = false), wav, config.readTimeoutMillis)
        val millis = (System.nanoTime() - start) / 1_000_000
        val json = parse(body)
        return Transcription(
            text = json.optString("text", "").trim(),
            language = json.optString("language", ""),
            decodeMillis = millis,
            audioSeconds = seconds,
        )
    }

    private fun syncFields(polish: Boolean, explicitOff: Boolean = false): LinkedHashMap<String, String> {
        val fields = LinkedHashMap<String, String>()
        when (config.api) {
            RemoteApi.DICTEE_SERVER -> {
                putLanguage(fields)
                if (polish) fields["polish"] = "true" else if (explicitOff) fields["polish"] = "false"
            }
            RemoteApi.OPENAI -> {
                fields["model"] = config.model.ifBlank { "whisper-1" }
                fields["response_format"] = "json"
                if (config.language.isNotBlank()) fields["language"] = config.language
                if (hotwords.isNotEmpty()) fields["prompt"] = hotwords.take(50).joinToString(", ")
            }
        }
        return fields
    }

    /**
     * This repository's server: the forced language, or else the user's languages the detection chooses among
     * (issue #86: a wrongly detected language makes Whisper translate instead of transcribe).
     */
    private fun putLanguage(fields: MutableMap<String, String>) {
        if (config.language.isNotBlank()) fields["language"] = config.language
        else if (config.detectLanguages.isNotEmpty()) fields["languages"] = config.detectLanguages.joinToString(",")
    }

    // --------------------------------------------------------- whole dictation

    /**
     * The whole dictation in one request: the job API with polish for this
     * repository's server (synchronous `/transcribe/streaming` if the server has
     * no job API), one `/v1/audio/transcriptions` call for OpenAI-compatible
     * servers. Throws [RemoteException] on any failure, including a timeout and
     * a cancellation; never falls back by itself (see [FallbackEngine]).
     */
    override fun transcribeDictation(audio: FloatArray, segments: List<FloatArray>, control: WaitControl): DictationResult {
        val seconds = audio.size / SttEngine.SAMPLE_RATE.toFloat()
        if (audio.isEmpty()) return DictationResult(emptyList(), DictationResult.Source.SERVER_RAW, transport = "none")
        val deadline = System.nanoTime() / 1_000_000 + config.waitBudgetMillis(seconds)
        val wav = WavReader.encode(PcmAudio(audio, SttEngine.SAMPLE_RATE))
        log("dictation: %.1f s, %d bytes, api %s".format(java.util.Locale.ROOT, seconds, wav.size, config.api))
        val start = System.nanoTime()
        return when (config.api) {
            RemoteApi.OPENAI -> {
                val body = postCancellable(config.transcriptionUrl(), syncFields(polish = false), wav, deadline, control)
                val upload = elapsed(start)
                val json = parse(body)
                val text = json.optString("text", "").trim()
                DictationResult(
                    texts = listOf(text), source = DictationResult.Source.SERVER_RAW, rawText = text,
                    polishStatus = "not requested", language = json.optString("language", ""),
                    uploadMillis = upload, totalMillis = elapsed(start), audioSeconds = seconds, transport = "sync",
                )
            }
            RemoteApi.DICTEE_SERVER -> {
                val fields = LinkedHashMap<String, String>().apply {
                    put("quick", "true")
                    put("polish", if (config.polish) "true" else "false")
                    putLanguage(this)
                }
                val job = runJob(fields, wav, deadline, control, syncFields(polish = config.polish, explicitOff = true))
                serverResult(job.result, job.uploadMillis, elapsed(start), seconds, job.transport, config.polish)
            }
        }
    }

    private class JobOutcome(val result: JSONObject, val uploadMillis: Long, val transport: String)

    /**
     * `POST /jobs` with [fields] and [wav], then `GET /jobs/{id}` until the result. A server without the job API
     * (404/405) gets the synchronous route with [syncFields]. Every way out (failure, timeout, cancel) leaves no
     * job running on the server.
     */
    private fun runJob(
        fields: Map<String, String>, wav: ByteArray, deadline: Long, control: WaitControl, syncFields: Map<String, String>,
    ): JobOutcome {
        val start = System.nanoTime()
        // If we give up while the upload is still going (Annuler, deadline), the job may be
        // created anyway: cancel it as soon as its id arrives.
        val created = try {
            postCancellable(config.jobsUrl(), fields, wav, deadline, control, orphanJob)
        } catch (e: RemoteException) {
            if (e.httpCode != 404 && e.httpCode != 405) throw e
            // A server without the job API: the synchronous route, same options.
            log("job API unavailable (HTTP ${e.httpCode}): synchronous request")
            val body = postCancellable(config.transcriptionUrl(), syncFields, wav, deadline, control)
            return JobOutcome(parse(body), elapsed(start), "sync")
        }
        val upload = elapsed(start)
        val id = parse(created).optString("id", "")
        if (id.isEmpty()) throw RemoteException("Réponse du serveur sans identifiant de tâche", failure = RemoteFailure.BAD_RESPONSE)
        log("job $id created in $upload ms")
        return JobOutcome(followJob(id, deadline, control), upload, "jobs")
    }

    /** Polls job [id]; cancels it on the server on any failure other than its own. */
    private fun followJob(id: String, deadline: Long, control: WaitControl): JSONObject {
        control.onCancel { cancelJobAsync(id) }
        return try {
            pollJob(id, deadline, control)
        } catch (e: RemoteException) {
            // A poll failure or a timeout leaves the job running for nothing: cancel it.
            // (A cancel already did through the hook; a failed job has nothing to cancel.)
            if (e.failure != RemoteFailure.CANCELLED && e.failure != RemoteFailure.JOB_FAILED) cancelJobAsync(id)
            throw e
        }
    }

    private val orphanJob: (String) -> Unit = { body ->
        runCatching { JSONObject(body).optString("id", "") }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { cancelJobAsync(it) }
    }

    // ------------------------------------------------- block dictation (issue #35)

    override val blockServer: BlockServer get() = this
    override fun serverAvailable(): Boolean = true
    override fun serverFailed(e: Exception) {}
    override val canFallBack: Boolean get() = false
    override fun transcribeLocally(samples: FloatArray): String = throw IllegalStateException("pas de modèle local")

    override val polishWanted: Boolean get() = config.polish && config.api == RemoteApi.DICTEE_SERVER

    override fun budgetMillis(audioSeconds: Float): Long = config.waitBudgetMillis(audioSeconds)

    /** Set once `POST /polish` answered as a route that exists: no need to ask again. */
    @Volatile private var polishRouteSeen = false

    /**
     * `POST /polish` with an empty text: a server that has the route refuses it (400, see the contract of
     * PR #46) without creating a job; an older server answers 404 or 405. Anything else (401, 5xx, network)
     * says nothing about the route and is thrown.
     */
    override fun supportsBlocks(control: WaitControl): Boolean {
        if (config.api != RemoteApi.DICTEE_SERVER || polishRouteSeen) return true
        val deadline = now() + config.waitBaseMillis
        val probe = "{\"text\": \"\"}".toByteArray()
        return try {
            val body = cancellable(deadline, control, orphanJob) {
                request("POST", config.polishUrl(), "application/json", listOf(probe), it)
            }
            // A 2xx would mean a job was created for nothing (not in the contract): cancel it.
            orphanJob(body)
            polishRouteSeen = true
            true
        } catch (e: RemoteException) {
            when (e.httpCode) {
                400, 413, 422 -> { polishRouteSeen = true; true }
                404, 405 -> { log("POST /polish: HTTP ${e.httpCode}, server without blocks: whole dictation"); false }
                else -> throw e
            }
        }
    }

    override fun transcribeBlock(
        audio: FloatArray, prompt: String, polish: Boolean, notAfter: Long, control: WaitControl,
    ): BlockText {
        val seconds = audio.size / SttEngine.SAMPLE_RATE.toFloat()
        if (audio.isEmpty()) return BlockText("", "")
        val deadline = minOf(notAfter, now() + config.waitBudgetMillis(seconds))
        val wav = WavReader.encode(PcmAudio(audio, SttEngine.SAMPLE_RATE))
        val askPolish = polish && polishWanted
        log("block: %.1f s, %d bytes, api %s, polish %s".format(java.util.Locale.ROOT, seconds, wav.size, config.api, askPolish))
        return when (config.api) {
            RemoteApi.OPENAI -> {
                val json = parse(postCancellable(config.transcriptionUrl(), syncFields(polish = false), wav, deadline, control))
                BlockText(json.optString("text", "").trim(), json.optString("language", "").ifBlank { config.language })
            }
            RemoteApi.DICTEE_SERVER -> {
                val fields = LinkedHashMap<String, String>().apply {
                    put("quick", "true")
                    put("polish", if (askPolish) "true" else "false")
                    putLanguage(this)
                    promptTail(prompt).takeIf { it.isNotEmpty() }?.let { put("prompt", it) }
                }
                val sync = syncFields(polish = askPolish, explicitOff = true)
                val job = runJob(fields, wav, deadline, control, sync)
                val raw = job.result.optString("text", "").trim()
                val detected = job.result.optString("language", "").takeIf { it != "null" }.orEmpty()
                if (!askPolish) return BlockText(raw, detected)
                val (polished, status) = polishOf(job.result)
                BlockText(raw, detected, polished, status)
            }
        }
    }

    override fun polish(text: String, language: String, audioSeconds: Float, control: WaitControl): PolishOutcome {
        val deadline = now() + config.waitBudgetMillis(audioSeconds)
        val lang = config.language.ifBlank { language }
        val body = JSONObject().put("text", text).apply { if (lang.isNotBlank()) put("language", lang) }
            .toString().toByteArray(Charsets.UTF_8)
        log("polish: ${text.length} chars")
        val created = cancellable(deadline, control, orphanJob) {
            request("POST", config.polishUrl(), "application/json", listOf(body), it)
        }
        val id = parse(created).optString("id", "")
        if (id.isEmpty()) throw RemoteException("Réponse du serveur sans identifiant de tâche", failure = RemoteFailure.BAD_RESPONSE)
        val (polished, status) = polishOf(followJob(id, deadline, control))
        log("polish job $id: $status")
        return PolishOutcome(polished, status)
    }

    /** The polished text when the server applied it, and the polish status ("applied" or the reason it was not). */
    private fun polishOf(json: JSONObject): Pair<String?, String> {
        val polishedRaw = if (json.isNull("polished_text")) "" else json.optString("polished_text", "").trim()
        val polish = json.optJSONObject("polish")
        val applied = polish?.optBoolean("applied", false) == true
        val polished = polishedRaw.takeIf { (applied || polish == null) && it.isNotEmpty() }
        val status = when {
            polished != null -> "applied"
            polish == null -> "not returned"
            else -> polish.optString("fallback_reason", "").takeIf { it.isNotEmpty() && it != "null" } ?: "failed"
        }
        return polished to status
    }

    private fun serverResult(json: JSONObject, upload: Long, total: Long, seconds: Float, transport: String, polishRequested: Boolean = true): DictationResult {
        val raw = json.optString("text", "").trim()
        if (!polishRequested) {
            // Polish turned off in the settings: the raw transcript, whatever the server added.
            log("result: polish not requested, ${raw.length} chars raw, $total ms")
            return DictationResult(
                texts = listOf(raw), source = DictationResult.Source.SERVER_RAW, rawText = raw,
                polishStatus = "not requested", language = json.optString("language", ""),
                uploadMillis = upload, totalMillis = total, audioSeconds = seconds, transport = transport,
            )
        }
        val (polished, status) = polishOf(json)
        log("result: polish $status, ${raw.length} chars raw, ${polished?.length ?: 0} chars polished, $total ms")
        return DictationResult(
            texts = listOf(polished ?: raw),
            source = if (polished != null) DictationResult.Source.SERVER_POLISHED else DictationResult.Source.SERVER_RAW,
            rawText = raw, polishedText = polished, polishStatus = status,
            language = json.optString("language", ""),
            uploadMillis = upload, totalMillis = total, audioSeconds = seconds, transport = transport,
        )
    }

    /** Polls `GET /jobs/{id}` until the job is done; returns its result. */
    private fun pollJob(id: String, deadline: Long, control: WaitControl): JSONObject {
        val url = config.jobsUrl() + "/" + id
        while (true) {
            checkCancelled(control)
            val remaining = deadline - now()
            if (remaining <= 0) throw RemoteException("Délai dépassé en attendant le serveur", failure = RemoteFailure.TIMEOUT)
            // On a helper thread too: Annuler and the deadline do not wait for a stalled GET.
            val body = cancellable(deadline, control, null) { request("GET", url, null, null, readTimeout = it.coerceAtMost(15_000)) }
            val job = parse(body)
            when (val status = job.optString("status")) {
                "done" -> return job.optJSONObject("result")
                    ?: throw RemoteException("Tâche terminée sans résultat", failure = RemoteFailure.BAD_RESPONSE)
                "error" -> {
                    val msg = job.optJSONObject("error")?.optString("message").orEmpty()
                    throw RemoteException("Le serveur a échoué : ${msg.take(160)}".trim(), failure = RemoteFailure.JOB_FAILED)
                }
                "cancelled" -> throw RemoteException("Tâche annulée sur le serveur", failure = RemoteFailure.JOB_FAILED)
                "queued", "running" -> {}
                else -> throw RemoteException("Statut de tâche inconnu : ${status.take(40)}", failure = RemoteFailure.BAD_RESPONSE)
            }
            try {
                Thread.sleep(config.pollIntervalMillis.coerceAtMost((deadline - now()).coerceAtLeast(1)))
            } catch (e: InterruptedException) {
                cancelJobAsync(id)
                throw e
            }
        }
    }

    private fun checkCancelled(control: WaitControl) {
        if (control.cancelled) throw RemoteException("Attente du serveur annulée", failure = RemoteFailure.CANCELLED)
    }

    /** Best effort, on its own thread: the caller may be interrupted or about to fall back. */
    private fun cancelJobAsync(id: String) {
        Thread({
            try {
                request("POST", config.jobsUrl() + "/" + id + "/cancel", null, listOf(ByteArray(0)), readTimeout = 5_000)
                log("job $id cancelled")
            } catch (e: Exception) {
                log("job $id: cancel failed (${e.message})")
            }
        }, "remote-cancel").apply { isDaemon = true }.start()
    }

    private fun postCancellable(
        url: String, fields: Map<String, String>, wav: ByteArray, deadline: Long, control: WaitControl,
        orphan: ((String) -> Unit)? = null,
    ): String = cancellable(deadline, control, orphan) { post(url, fields, wav, it) }

    /**
     * Runs a blocking request on a helper thread; the caller gives up as soon as
     * [control] is cancelled or the deadline passes (socket I/O is not
     * interruptible on Android). If the caller gave up and the request still
     * succeeds, [orphan] gets its body (to cancel a job created meanwhile).
     */
    private fun cancellable(deadline: Long, control: WaitControl, orphan: ((String) -> Unit)?, call: (readTimeout: Int) -> String): String {
        checkCancelled(control)
        val remaining = deadline - now()
        if (remaining <= 0) throw RemoteException("Délai dépassé en attendant le serveur", failure = RemoteFailure.TIMEOUT)
        val gaveUp = java.util.concurrent.atomic.AtomicBoolean(false)
        val result = java.util.concurrent.CompletableFuture<String>()
        val worker = Thread({
            try {
                val body = call(remaining.coerceIn(1, Int.MAX_VALUE.toLong()).toInt())
                if (!result.complete(body) || gaveUp.get()) orphan?.invoke(body)
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }, "remote-request").apply { isDaemon = true }
        control.onCancel { result.cancel(false) }
        worker.start()
        try {
            return result.get(remaining + 1_000, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.CancellationException) {
            gaveUp.set(true)
            throw RemoteException("Attente du serveur annulée", failure = RemoteFailure.CANCELLED)
        } catch (e: java.util.concurrent.ExecutionException) {
            val c = e.cause
            if (control.cancelled) throw RemoteException("Attente du serveur annulée", failure = RemoteFailure.CANCELLED)
            throw c as? RemoteException
                ?: RemoteException(config.redact(c?.message ?: c?.javaClass?.simpleName ?: "Erreur"), c, RemoteFailure.BAD_RESPONSE)
        } catch (_: java.util.concurrent.TimeoutException) {
            gaveUp.set(true)
            result.cancel(false)
            throw RemoteException("Délai dépassé en attendant le serveur", failure = RemoteFailure.TIMEOUT)
        } catch (e: InterruptedException) {
            gaveUp.set(true)
            result.cancel(false)
            throw e
        }
    }

    // ---------------------------------------------------------------- HTTP

    private fun post(url: String, fields: Map<String, String>, wav: ByteArray, readTimeout: Int): String {
        val boundary = "dictee-" + UUID.randomUUID()
        val head = ByteArrayOutputStream().apply {
            for ((k, v) in fields) {
                write("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n".toByteArray())
            }
            write(
                ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"dictation.wav\"\r\n" +
                    "Content-Type: audio/wav\r\n\r\n").toByteArray(),
            )
        }.toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        // Streamed in three parts: no second copy of a WAV of up to ~10 MB.
        return request("POST", url, "multipart/form-data; boundary=$boundary", listOf(head, wav, tail), readTimeout)
    }

    private fun request(method: String, url: String, contentType: String?, payload: List<ByteArray>?, readTimeout: Int): String {
        val conn = try {
            open(config, url, readTimeout).apply {
                requestMethod = method
                if (payload != null) {
                    doOutput = true
                    setFixedLengthStreamingMode(payload.sumOf { it.size.toLong() })
                    setRequestProperty("Content-Type", contentType)
                }
            }
        } catch (e: IOException) {
            throw RemoteException(config.redact("Serveur injoignable : ${e.message ?: e.javaClass.simpleName}"), e, RemoteFailure.UNREACHABLE)
        } catch (e: RuntimeException) {
            // e.g. a header value the HTTP stack refuses; its message may quote the value.
            throw RemoteException(config.redact("Requête impossible : ${e.message ?: e.javaClass.simpleName}"), e, RemoteFailure.BAD_RESPONSE)
        }
        try {
            if (payload != null) conn.outputStream.use { out -> payload.forEach { out.write(it) } }
            val code = conn.responseCode
            if (code !in 200..299) throw httpError(config, conn, code)
            return conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: RemoteException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw RemoteException("Délai dépassé en attendant le serveur", e, RemoteFailure.TIMEOUT)
        } catch (e: IOException) {
            throw RemoteException(config.redact("Serveur injoignable : ${e.message ?: e.javaClass.simpleName}"), e, RemoteFailure.UNREACHABLE)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String): JSONObject = try {
        JSONObject(body)
    } catch (e: Exception) {
        throw RemoteException("Réponse du serveur illisible (pas du JSON)", e, RemoteFailure.BAD_RESPONSE)
    }

    private fun now() = System.nanoTime() / 1_000_000
    private fun elapsed(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000

    override fun release() {}

    companion object {
        /** Longest `prompt` sent with a block: the end of the previous block's text (the server accepts 1000). */
        const val PROMPT_CHARS = 200

        /** The end of [text], at most [PROMPT_CHARS], starting on a word. */
        fun promptTail(text: String): String {
            val t = text.trim().replace(Regex("\\s+"), " ")
            if (t.length <= PROMPT_CHARS) return t
            val cut = t.substring(t.length - PROMPT_CHARS)
            val space = cut.indexOf(' ')
            return if (space in 0 until cut.length - 1) cut.substring(space + 1) else cut
        }

        /** Set by the app to Android's log; tests pass their own. */
        @Volatile var defaultLog: RemoteLog = RemoteLog.NONE

        /** A connection with the timeouts, no redirect following, and every configured credential. */
        internal fun open(config: RemoteConfig, url: String, readTimeout: Int): HttpURLConnection =
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = config.connectTimeoutMillis
                this.readTimeout = readTimeout
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                if (config.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${RemoteConfig.cleanHeaderValue(config.apiKey)}")
                if (config.hasCfAccess) {
                    setRequestProperty("CF-Access-Client-Id", RemoteConfig.cleanHeaderValue(config.cfAccessClientId))
                    setRequestProperty("CF-Access-Client-Secret", RemoteConfig.cleanHeaderValue(config.cfAccessClientSecret))
                }
            }

        internal fun httpError(config: RemoteConfig, conn: HttpURLConnection, code: Int): RemoteException = when (code) {
            in 300..399 -> {
                val target = runCatching { URI(conn.getHeaderField("Location")).host }.getOrNull().orEmpty()
                val msg = if (target.isEmpty() || target.contains("cloudflareaccess")) {
                    "Redirection (HTTP $code) vers la page de connexion de Cloudflare Access : " +
                        "vérifiez le service token et que la politique de l'application est « Service Auth »"
                } else {
                    "Redirection (HTTP $code) vers $target : vérifiez l'adresse (https:// ?) ou la politique Cloudflare Access " +
                        "(« Service Auth »)"
                }
                RemoteException(msg, failure = RemoteFailure.ACCESS_REDIRECT, httpCode = code)
            }
            401, 403 -> RemoteException(
                "Accès refusé (HTTP $code) : vérifiez le jeton et le service token Cloudflare Access",
                failure = RemoteFailure.ACCESS_DENIED, httpCode = code,
            )
            else -> {
                val detail = try {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200).orEmpty()
                } catch (_: IOException) { "" }
                // Rate limit (issue #35): the server says when to come back.
                val retryAfter = if (code == 429) conn.getHeaderField("Retry-After")?.trim()?.toIntOrNull()?.coerceIn(0, 3600) ?: 0 else 0
                RemoteException(
                    config.redact("Serveur : HTTP $code ${detail.replace('\n', ' ')}").trim(),
                    failure = RemoteFailure.HTTP_ERROR, httpCode = code, retryAfterSeconds = retryAfter,
                )
            }
        }

        /** Checks that the server answers; returns a short human-readable status. Blocking. */
        fun check(config: RemoteConfig): Result<String> = runCatching {
            if (!config.isConfigured) throw RemoteException("Adresse du serveur invalide", failure = RemoteFailure.BAD_RESPONSE)
            val conn = try {
                open(config, config.healthUrl(), 10_000)
            } catch (e: Exception) {
                throw RemoteException(config.redact("requête impossible : ${e.message ?: e.javaClass.simpleName}"), e, RemoteFailure.BAD_RESPONSE)
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw httpError(config, conn, code)
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val access = if (config.hasCfAccess) " · Cloudflare Access accepté" else ""
                when (config.api) {
                    RemoteApi.DICTEE_SERVER -> {
                        val j = try {
                            JSONObject(body)
                        } catch (e: Exception) {
                            throw RemoteException("Réponse illisible (pas du JSON) : est-ce bien le serveur speech-to-text ?", e, RemoteFailure.BAD_RESPONSE)
                        }
                        listOfNotNull(
                            j.optString("model").takeIf { it.isNotEmpty() }?.let { "modèle $it" },
                            j.optString("device").takeIf { it.isNotEmpty() },
                            if (j.has("polish_available")) {
                                if (j.optBoolean("polish_available")) "polissage disponible" else "polissage indisponible"
                            } else null,
                        ).joinToString(" · ").ifEmpty { "serveur joignable" } + access
                    }
                    RemoteApi.OPENAI -> "serveur joignable$access"
                }
            } catch (e: RemoteException) {
                throw e
            } catch (e: IOException) {
                throw RemoteException(config.redact("injoignable : ${e.message ?: e.javaClass.simpleName}"), e, RemoteFailure.UNREACHABLE)
            } catch (e: RuntimeException) {
                throw RemoteException(config.redact("requête impossible : ${e.message ?: e.javaClass.simpleName}"), e, RemoteFailure.BAD_RESPONSE)
            } finally {
                conn.disconnect()
            }
        }
    }
}
