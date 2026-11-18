package io.github.remtav.dictee.dictation

/** Fixed-size buffer of the last samples heard, kept to recover the speech the VAD reports late. */
internal class AudioRing(val capacity: Int) {
    private val buf = FloatArray(capacity)
    private var pos = 0
    private var filled = 0

    fun write(s: FloatArray) {
        for (v in s) {
            buf[pos] = v
            pos = (pos + 1) % capacity
        }
        filled = minOf(capacity, filled + s.size)
    }

    fun last(n: Int): FloatArray {
        val len = minOf(n, filled)
        val out = FloatArray(len)
        var start = pos - len
        if (start < 0) start += capacity
        for (i in 0 until len) out[i] = buf[(start + i) % capacity]
        return out
    }
}
