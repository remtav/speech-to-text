package io.github.remtav.dictee

import io.github.remtav.dictee.stt.LiveStream
import io.github.remtav.dictee.stt.RemoteConfig
import io.github.remtav.dictee.stt.RemoteStream
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class RemoteStreamTest {
    private val server = MockWebServer()
    private val received = LinkedBlockingQueue<Any>()
    private val serverSockets = LinkedBlockingQueue<WebSocket>()
    private val events = LinkedBlockingQueue<String>()

    private val serverListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { serverSockets.add(webSocket) }
        override fun onMessage(webSocket: WebSocket, text: String) { received.add(text) }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { received.add(bytes) }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
    }

    private val listener = object : LiveStream.Listener {
        override fun onUpdate(committed: String, provisional: String) { events.add("update:$committed|$provisional") }
        override fun onFinal(text: String) { events.add("final:$text") }
        override fun onClosed(reason: String?) { events.add("closed:$reason") }
    }

    @Before fun setUp() {
        server.enqueue(MockResponse().withWebSocketUpgrade(serverListener))
        server.start()
    }

    @After fun tearDown() { server.shutdown() }

    private fun config(extra: RemoteConfig.() -> RemoteConfig = { this }) =
        RemoteConfig(baseUrl = "http://${server.hostName}:${server.port}", apiKey = "tok",
            cfAccessClientId = "cid-1234", cfAccessClientSecret = "secret-1234").extra()

    private fun poll(q: LinkedBlockingQueue<*>) = q.poll(5, TimeUnit.SECONDS)

    @Test fun streamUrlSchemes() {
        assertEquals("ws://h:8081/ws/stream", RemoteConfig("http://h:8081").streamUrl())
        assertEquals("wss://h/ws/stream", RemoteConfig("https://h/").streamUrl())
    }

    @Test fun startMessageAndHeaders() {
        val s = RemoteStream(config(), listener)
        s.start("fr", listOf("fr", "en"))
        val o = JSONObject(poll(received) as String)
        assertEquals("start", o.getString("type"))
        assertEquals("fr", o.getString("language"))
        assertEquals("fr", o.getJSONArray("languages").getString(0))
        assertEquals("en", o.getJSONArray("languages").getString(1))
        val req = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        assertEquals("cid-1234", req.getHeader("CF-Access-Client-Id"))
        assertEquals("secret-1234", req.getHeader("CF-Access-Client-Secret"))
        s.cancel()
    }

    @Test fun blankLanguageIsNull() {
        val s = RemoteStream(config(), listener)
        s.start("", listOf("fr", "en"))
        val o = JSONObject(poll(received) as String)
        assertTrue(o.isNull("language"))
        s.cancel()
    }

    @Test fun audioIsPcm16LittleEndian() {
        val expected = byteArrayOf(0xFF.toByte(), 0x3F, 0x01, 0x80.toByte(), 0xFF.toByte(), 0x7F)
        assertArrayEquals(expected, RemoteStream.pcm16(floatArrayOf(0.5f, -1f, 2f), 3))
        val s = RemoteStream(config(), listener)
        s.start("fr", listOf("fr"))
        poll(received)
        s.send(floatArrayOf(0.5f, -1f, 2f), 3)
        val bytes = poll(received) as ByteString
        assertArrayEquals(expected, bytes.toByteArray())
        s.cancel()
    }

    @Test fun updateAndFinalReachListener() {
        val s = RemoteStream(config(), listener)
        s.start("fr", listOf("fr"))
        poll(received)
        val ws = poll(serverSockets) as WebSocket
        ws.send("""{"type":"update","committed":"a b","provisional":"c","audio_ms":1}""")
        ws.send("""{"type":"final","text":"a b c"}""")
        ws.close(1000, null)
        assertEquals("update:a b|c", poll(events))
        assertEquals("final:a b c", poll(events))
        assertEquals("closed:null", poll(events))
        assertNull(events.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test fun busyCloseReportsOnce() {
        val s = RemoteStream(config(), listener)
        s.start("fr", listOf("fr"))
        poll(received)
        (poll(serverSockets) as WebSocket).close(1013, "busy")
        assertEquals("closed:Serveur occupé", poll(events))
        assertNull(events.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test fun cancelSilencesListener() {
        val s = RemoteStream(config(), listener)
        s.start("fr", listOf("fr"))
        poll(received)
        val ws = poll(serverSockets) as WebSocket
        s.cancel()
        ws.send("""{"type":"update","committed":"x","provisional":"y"}""")
        assertNull(events.poll(500, TimeUnit.MILLISECONDS))
    }

    @Test fun unknownTypeIgnored() {
        val s = RemoteStream(config(), listener)
        s.start("fr", listOf("fr"))
        poll(received)
        val ws = poll(serverSockets) as WebSocket
        ws.send("""{"type":"autre"}""")
        ws.send("pas du json")
        assertNull(events.poll(500, TimeUnit.MILLISECONDS))
        s.cancel()
    }
}
