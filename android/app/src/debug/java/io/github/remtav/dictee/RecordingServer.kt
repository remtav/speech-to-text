package io.github.remtav.dictee

import java.net.InetAddress
import java.net.ServerSocket

/** Debug builds only, for tests: records each HTTP request (headers + body) and answers with [respond]. */
class RecordingServer(private val respond: (path: String) -> Pair<Int, String>) : AutoCloseable {
    class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port get() = socket.localPort
    val requests = java.util.Collections.synchronizedList(ArrayList<Request>())

    init {
        Thread {
            while (!socket.isClosed) {
                val c = try { socket.accept() } catch (_: java.io.IOException) { break }
                Thread {
                    c.use {
                        val input = java.io.BufferedInputStream(c.getInputStream())
                        fun line(): String {
                            val sb = StringBuilder()
                            while (true) {
                                val b = input.read()
                                if (b < 0 || b == '\n'.code) break
                                if (b != '\r'.code) sb.append(b.toChar())
                            }
                            return sb.toString()
                        }
                        val (method, path) = line().split(' ').let { it[0] to it[1] }
                        val headers = HashMap<String, String>()
                        while (true) {
                            val l = line()
                            if (l.isEmpty()) break
                            headers[l.substringBefore(':').trim().lowercase()] = l.substringAfter(':').trim()
                        }
                        val len = headers["content-length"]?.toInt() ?: 0
                        val body = ByteArray(len)
                        var read = 0
                        while (read < len) { val n = input.read(body, read, len - read); if (n < 0) break; read += n }
                        requests += Request(method, path, headers, body)
                        val (code, text) = respond(path)
                        val bytes = text.toByteArray()
                        c.getOutputStream().apply {
                            write("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                }.start()
            }
        }.apply { isDaemon = true; start() }
    }

    override fun close() = socket.close()
}

