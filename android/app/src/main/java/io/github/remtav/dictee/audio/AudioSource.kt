package io.github.remtav.dictee.audio

/**
 * Source of 16 kHz mono float audio. The microphone in the app, a WAV file in
 * the instrumented tests (the IME then never touches the real microphone).
 */
interface AudioSource {
    /** @throws AudioSourceException when the source cannot start (permission, device busy). */
    fun start()

    /**
     * Blocks until some samples are available and copies them into [buffer].
     * Returns the number of samples read, 0 when nothing yet, -1 at end of stream.
     */
    fun read(buffer: FloatArray): Int

    fun stop()

    /** True when the system silenced this client (call, another app took the mic). */
    val silenced: Boolean get() = false
}

class AudioSourceException(message: String, cause: Throwable? = null) : Exception(message, cause)

fun interface AudioSourceFactory {
    fun create(): AudioSource
}

/**
 * Plays [PcmAudio] clips as if spoken: optionally paced in real time, with
 * [gapSeconds] of silence between clips and [tailSeconds] at the end.
 */
class PcmAudioSource(
    clips: List<PcmAudio>,
    private val realTime: Boolean = true,
    gapSeconds: Float = 1.5f,
    leadSeconds: Float = 0.5f,
    tailSeconds: Float = 1.5f,
    private val sampleRate: Int = 16000,
) : AudioSource {
    private val data: FloatArray
    private var pos = 0
    private var startNanos = 0L
    @Volatile private var stopped = false

    init {
        val parts = ArrayList<FloatArray>()
        parts += FloatArray((leadSeconds * sampleRate).toInt())
        clips.forEachIndexed { i, c ->
            if (i > 0) parts += FloatArray((gapSeconds * sampleRate).toInt())
            parts += c.resampled(sampleRate).samples
        }
        parts += FloatArray((tailSeconds * sampleRate).toInt())
        data = FloatArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { p.copyInto(data, o); o += p.size }
    }

    val totalSeconds: Float get() = data.size / sampleRate.toFloat()

    override fun start() {
        pos = 0
        stopped = false
        startNanos = System.nanoTime()
    }

    override fun read(buffer: FloatArray): Int {
        if (stopped || pos >= data.size) return -1
        val n = minOf(buffer.size, data.size - pos)
        if (realTime) {
            val dueNanos = startNanos + (pos + n).toLong() * 1_000_000_000L / sampleRate
            val wait = dueNanos - System.nanoTime()
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
        }
        data.copyInto(buffer, 0, pos, pos + n)
        pos += n
        return n
    }

    override fun stop() {
        stopped = true
    }
}
