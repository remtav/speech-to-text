package io.github.remtav.dictee.stt

import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** A downloadable file set: STT models and the optional clean-up LLMs share this. */
interface Downloadable {
    val id: String
    val files: List<ModelFile>
}

private class SpecDownloadable(spec: ModelSpec) : Downloadable {
    override val id = spec.id
    override val files = spec.files
}

fun ModelSpec.asDownloadable(): Downloadable = SpecDownloadable(this)

/**
 * Where models live on disk, and how they get there. Pure JVM (no Android
 * types) so the host tests download through exactly this code.
 *
 * A model is installed when its directory holds a `.verified` marker, written
 * only after every file matched its SHA-256. Files are downloaded to `.part`,
 * resumed with an HTTP Range request when interrupted, and verified before
 * being renamed into place.
 */
class ModelStore(private val root: File) {

    fun dirFor(id: String) = File(root, id)

    private fun marker(id: String) = File(dirFor(id), ".verified")

    fun isInstalled(item: Downloadable): Boolean {
        val m = marker(item.id)
        if (!m.isFile) return false
        // Marker lists the hashes it vouches for: a catalog update invalidates it.
        val expected = item.files.joinToString("\n") { "${it.sha256}  ${it.path}" }
        return m.readText().trim() == expected.trim() &&
            item.files.all { File(dirFor(item.id), it.path).length() == it.size }
    }

    fun isInstalled(spec: ModelSpec) = isInstalled(spec.asDownloadable())

    fun installedBytes(item: Downloadable): Long =
        dirFor(item.id).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun delete(item: Downloadable) {
        dirFor(item.id).deleteRecursively()
    }

    fun delete(spec: ModelSpec) = delete(spec.asDownloadable())

    fun interface Progress {
        fun onProgress(downloadedBytes: Long, totalBytes: Long)
    }

    class ChecksumException(message: String) : IOException(message)

    /**
     * Downloads and verifies every file of [item]. Blocking; call from a worker
     * thread. Set [cancel] to stop early (throws [InterruptedIOException]).
     */
    fun download(
        item: Downloadable,
        cancel: AtomicBoolean = AtomicBoolean(false),
        progress: Progress = Progress { _, _ -> },
    ) {
        val dir = dirFor(item.id).apply { mkdirs() }
        marker(item.id).delete()
        val total = item.files.sumOf { it.size }
        var done = 0L
        for (file in item.files) {
            val target = File(dir, file.path)
            if (target.isFile && target.length() == file.size && sha256(target) == file.sha256) {
                done += file.size
                progress.onProgress(done, total)
                continue
            }
            target.parentFile?.mkdirs()
            val part = File(target.path + ".part")
            var attempt = 0
            while (true) {
                try {
                    fetch(file, part, cancel) { bytes -> progress.onProgress(done + bytes, total) }
                    break
                } catch (e: InterruptedIOException) {
                    throw e
                } catch (e: ChecksumException) {
                    part.delete()
                    throw e
                } catch (e: IOException) {
                    if (++attempt >= MAX_ATTEMPTS) throw e
                    Thread.sleep(1000L shl attempt)
                }
            }
            val actual = sha256(part)
            if (actual != file.sha256) {
                part.delete()
                throw ChecksumException("Somme de contrôle invalide pour ${file.path}")
            }
            target.delete()
            if (!part.renameTo(target)) throw IOException("Impossible d'écrire ${target.path}")
            done += file.size
            progress.onProgress(done, total)
        }
        marker(item.id).writeText(item.files.joinToString("\n") { "${it.sha256}  ${it.path}" })
    }

    private fun fetch(file: ModelFile, part: File, cancel: AtomicBoolean, onBytes: (Long) -> Unit) {
        var url = URL(file.url)
        var redirects = 0
        while (true) {
            val existing = if (part.isFile) part.length() else 0L
            if (existing == file.size) return
            if (existing > file.size) part.delete()
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "dictee-android")
                if (part.isFile && part.length() > 0) setRequestProperty("Range", "bytes=${part.length()}-")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: throw IOException("redirect without Location")
                    val next = URL(url, location)
                    // The hash protects integrity anyway, but never step down from HTTPS.
                    if (url.protocol == "https" && next.protocol != "https") throw IOException("redirection vers HTTP refusée")
                    url = next
                    if (++redirects > 10) throw IOException("too many redirects")
                    continue
                }
                val append = code == HttpURLConnection.HTTP_PARTIAL
                if (append) {
                    // A 206 must resume exactly where the partial file ends; otherwise start over.
                    val start = conn.getHeaderField("Content-Range")?.removePrefix("bytes ")?.substringBefore('-')?.toLongOrNull()
                    if (start != part.length()) {
                        conn.disconnect()
                        part.delete()
                        continue
                    }
                }
                if (code != HttpURLConnection.HTTP_OK && !append) {
                    throw IOException("HTTP $code pour ${file.url}")
                }
                var written = if (append) part.length() else 0L
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(1 shl 16)
                        var lastReport = 0L
                        while (true) {
                            if (cancel.get()) throw InterruptedIOException("annulé")
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            if (written - lastReport > 512 * 1024) {
                                lastReport = written
                                onBytes(written)
                            }
                        }
                    }
                }
                onBytes(written)
                if (written != file.size) throw IOException("taille inattendue pour ${file.path}: $written")
                return
            } finally {
                conn.disconnect()
            }
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 4

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
