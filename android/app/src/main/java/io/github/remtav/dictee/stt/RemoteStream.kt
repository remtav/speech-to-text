package io.github.remtav.dictee.stt

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A live audio stream to the server (issue #130): audio out, provisional text back. */
interface LiveStream {
    /** Called on an OkHttp thread: the caller must switch to the main thread itself. */
    interface Listener {
        fun onUpdate(committed: String, provisional: String)
        fun onFinal(text: String)

        /** Once, whatever the cause; [reason] is null for a normal close. */
        fun onClosed(reason: String?)
    }

    /** [n] samples of [samples], 16 kHz mono float. Never blocks. */
    fun send(samples: FloatArray, n: Int)

    /** End of the audio: the server sends its final text, then closes. */
    fun stop()

    /** Drops the stream at once: no more listener calls. */
    fun cancel()
}

/**
 * WebSocket client of `GET /ws/stream` (issue #133). Listener calls arrive on an OkHttp thread.
 * Never logs audio, received text, token or Cloudflare secrets; error messages go through [RemoteConfig.redact].
 */
class RemoteStream(
    private val config: RemoteConfig,
    private val listener: LiveStream.Listener,
    private val client: OkHttpClient = defaultClient,
) : LiveStream {
    @Volatile private var cancelled = false
    @Volatile private var serverError: String? = null
    private val closed = AtomicBoolean(false)
    @Volatile private var ws: WebSocket? = null

    fun start(language: String, languages: List<String>, prompt: String = "") {
        val builder = Request.Builder().url(config.streamUrl())
        if (config.apiKey.isNotBlank()) builder.header("Authorization", "Bearer ${RemoteConfig.cleanHeaderValue(config.apiKey)}")
        if (config.hasCfAccess) {
            builder.header("CF-Access-Client-Id", RemoteConfig.cleanHeaderValue(config.cfAccessClientId))
            builder.header("CF-Access-Client-Secret", RemoteConfig.cleanHeaderValue(config.cfAccessClientSecret))
        }
        val socket = client.newWebSocket(builder.build(), socketListener)
        ws = socket
        val msg = JSONObject()
            .put("type", "start")
            .put("language", if (language.isBlank()) JSONObject.NULL else language)
            .put("languages", JSONArray(languages))
            .put("prompt", prompt)
        socket.send(msg.toString())
    }

    override fun send(samples: FloatArray, n: Int) {
        val socket = ws ?: return
        if (cancelled || socket.queueSize() > MAX_QUEUE_BYTES) return
        socket.send(pcm16(samples, n).toByteString())
    }

    override fun stop() {
        if (cancelled) return
        ws?.send(JSONObject().put("type", "stop").toString())
    }

    override fun cancel() {
        cancelled = true
        ws?.cancel()
    }

    private fun closeOnce(reason: String?) {
        if (cancelled || !closed.compareAndSet(false, true)) return
        listener.onClosed(reason)
    }

    private fun reasonFor(code: Int): String = when (code) {
        1008 -> "Accès refusé"
        1013 -> "Serveur occupé"
        else -> serverError?.let { config.redact(it) } ?: "Connexion fermée ($code)"
    }

    private val socketListener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (cancelled) return
            val o = try { JSONObject(text) } catch (_: Exception) { return }
            when (o.optString("type")) {
                "update" -> listener.onUpdate(o.optString("committed"), o.optString("provisional"))
                "final" -> listener.onFinal(o.optString("text"))
                "error" -> serverError = o.optString("message")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closeOnce(if (code == 1000) null else reasonFor(code))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            closeOnce(config.redact(t.message ?: t.javaClass.simpleName))
        }
    }

    companion object {
        const val MAX_QUEUE_BYTES = 256L * 1024

        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .pingInterval(15, TimeUnit.SECONDS)
            .connectTimeout(4, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()

        /** 16-bit little-endian PCM of the first [n] samples (values clamped to [-1, 1]). */
        fun pcm16(samples: FloatArray, n: Int): ByteArray {
            val out = ByteArray(n * 2)
            for (i in 0 until n) {
                val v = (samples[i].coerceIn(-1f, 1f) * 32767).toInt()
                out[2 * i] = (v and 0xFF).toByte()
                out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
            }
            return out
        }
    }
}
