package io.github.remtav.dictee.audio

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.min

/** Mono float PCM. */
class PcmAudio(val samples: FloatArray, val sampleRate: Int) {
    val seconds: Float get() = samples.size / sampleRate.toFloat()

    fun resampled(target: Int): PcmAudio =
        if (target == sampleRate) this else PcmAudio(Resampler.linear(samples, sampleRate, target), target)
}

/** Minimal RIFF/WAVE reader: PCM 16/24/32-bit integer or 32-bit float, any channel count (mixed to mono). */
object WavReader {
    fun read(input: InputStream): PcmAudio {
        val bytes = input.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") {
            "not a WAV file"
        }
        var pos = 12
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        var dataStart = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val len = buf.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    format = buf.getShort(body).toInt() and 0xffff
                    channels = buf.getShort(body + 2).toInt()
                    rate = buf.getInt(body + 4)
                    bits = buf.getShort(body + 14).toInt()
                    if (format == 0xFFFE && len >= 26) format = buf.getShort(body + 24).toInt() and 0xffff
                }
                "data" -> {
                    dataStart = body
                    dataLen = min(len.toLong() and 0xffffffffL, (bytes.size - body).toLong()).toInt()
                }
            }
            if (dataStart >= 0 && format != 0) break
            pos = body + len + (len and 1)
        }
        require(dataStart >= 0 && channels > 0 && rate > 0) { "unsupported WAV layout" }
        val bytesPerSample = bits / 8
        val frames = dataLen / (bytesPerSample * channels)
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            var acc = 0f
            for (c in 0 until channels) {
                val off = dataStart + (i * channels + c) * bytesPerSample
                acc += when {
                    format == 3 && bits == 32 -> buf.getFloat(off)
                    bits == 16 -> buf.getShort(off) / 32768f
                    bits == 24 -> {
                        val v = (bytes[off].toInt() and 0xff) or ((bytes[off + 1].toInt() and 0xff) shl 8) or
                            (bytes[off + 2].toInt() shl 16)
                        v / 8388608f
                    }
                    bits == 32 -> buf.getInt(off) / 2147483648f
                    bits == 8 -> ((bytes[off].toInt() and 0xff) - 128) / 128f
                    else -> throw IllegalArgumentException("unsupported bit depth $bits")
                }
            }
            out[i] = acc / channels
        }
        return PcmAudio(out, rate)
    }

    /** Encodes 16-bit PCM mono WAV (used by the comparison screen to keep a recording). */
    fun encode(audio: PcmAudio): ByteArray {
        val data = ByteBuffer.allocate(audio.samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in audio.samples) data.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.capacity()); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(audio.sampleRate); putInt(audio.sampleRate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data.capacity())
        }
        return ByteArrayOutputStream().apply { write(header.array()); write(data.array()) }.toByteArray()
    }
}

object Resampler {
    /** Linear interpolation; enough for speech going into a 16 kHz model front end. */
    fun linear(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || input.isEmpty()) return input
        val outLen = (input.size.toLong() * to / from).toInt()
        val out = FloatArray(outLen)
        val step = from.toDouble() / to
        for (i in 0 until outLen) {
            val x = i * step
            val i0 = floor(x).toInt().coerceAtMost(input.size - 1)
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val t = (x - i0).toFloat()
            out[i] = input[i0] * (1 - t) + input[i1] * t
        }
        return out
    }
}
