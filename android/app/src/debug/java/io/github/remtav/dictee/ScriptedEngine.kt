package io.github.remtav.dictee

import io.github.remtav.dictee.stt.EngineOptions
import io.github.remtav.dictee.stt.ModelSpec
import io.github.remtav.dictee.stt.SttEngine
import io.github.remtav.dictee.stt.SttEngineFactory
import io.github.remtav.dictee.stt.Transcription
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test engine (debug builds only): the n-th call returns the n-th scripted
 * text (empty past the end). Tests that check ordering turn provisional text
 * off so every call is a final segment. Lets the instrumented tests drive the
 * real IME, audio path and VAD on an emulator that cannot hold a 1 GB model.
 */
class ScriptedEngine(
    override val spec: ModelSpec,
    private val script: List<String>,
    private val delayMillis: Long = 50,
) : SttEngine {
    val calls = AtomicInteger(0)
    val durations: MutableList<Float> = Collections.synchronizedList(ArrayList())
    @Volatile var released = false
    override val provider = "scripted"

    override fun transcribe(samples: FloatArray, sampleRate: Int): Transcription {
        check(!released) { "engine used after release" }
        val i = calls.getAndIncrement()
        val seconds = samples.size / sampleRate.toFloat()
        durations += seconds
        if (delayMillis > 0) Thread.sleep(delayMillis)
        return Transcription(script.getOrElse(i) { "" }, decodeMillis = delayMillis, audioSeconds = seconds)
    }

    override fun release() { released = true }

    class Factory(private val script: List<String>, private val delayMillis: Long = 50) : SttEngineFactory {
        @Volatile var last: ScriptedEngine? = null
        val created = AtomicInteger(0)
        override fun create(spec: ModelSpec, modelDir: java.io.File, options: EngineOptions): SttEngine {
            created.incrementAndGet()
            return ScriptedEngine(spec, script, delayMillis).also { last = it }
        }
    }
}
