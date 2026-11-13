package io.github.remtav.dictee

import android.app.Instrumentation
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import io.github.remtav.dictee.reading.ReadingMaterial
import io.github.remtav.dictee.stt.EngineOptions
import io.github.remtav.dictee.stt.ModelSpec
import io.github.remtav.dictee.stt.SttEngine
import io.github.remtav.dictee.stt.SttEngineFactory
import io.github.remtav.dictee.ui.ReadingTestActivity
import org.junit.Assert.fail
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scripted models for the reading test (issue #15, step 4.2): each model, when loaded, answers paragraph
 * `n` to its n-th call, with what [transcript] makes of the paragraph text. The reading test transcribes
 * the paragraphs in order, one model at a time, so call n is paragraph n. No model file is involved.
 */
internal class ScriptedReadingModels(
    private val material: ReadingMaterial,
    private val transcript: (modelId: String, paragraph: Int, text: String) -> String,
) : SttEngineFactory {
    val created = AtomicInteger(0)
    val engines: MutableList<ScriptedEngine> = Collections.synchronizedList(ArrayList())

    override fun create(spec: ModelSpec, modelDir: java.io.File, options: EngineOptions): SttEngine {
        created.incrementAndGet()
        val script = material.text.paragraphs.mapIndexed { i, text -> transcript(spec.id, i, text) }
        return ScriptedEngine(spec, script, delayMillis = 0).also { engines += it }
    }

    companion object {
        /** What a model that misses every fourth word gives. */
        fun dropEveryFourthWord(text: String): String =
            text.split(' ').filterIndexed { n, _ -> n % 4 != 3 }.joinToString(" ")
    }
}

/**
 * Drives the reading-test screen through its view tree, like a user (tags are the screen's own):
 * taps, waits for what the screen shows, reads texts. Never nests scenarios: the caller launches
 * one screen at a time (`ActivityScenario.launch` destroys the previous activity).
 */
internal class ReadingDriver(
    private val instrumentation: Instrumentation,
    private val scenario: ActivityScenario<*>,
    private val material: ReadingMaterial,
) {
    /** Every view of the tree: `this@allViews`, not `this` (inside `sequence { }` `this` is the scope). */
    private fun View.allViews(): Sequence<View> = sequence {
        val view = this@allViews
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(view.getChildAt(i).allViews())
    }

    fun <T> onViews(block: (Sequence<View>) -> T): T {
        var out: T? = null
        scenario.onActivity { out = block(it.window.decorView.allViews()) }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    fun has(tag: String): Boolean = onViews { v -> v.any { it.tag == tag } }

    fun text(tag: String): String? = onViews { v -> (v.firstOrNull { it.tag == tag } as? TextView)?.text?.toString() }

    fun allText(): String = onViews { v -> v.filterIsInstance<TextView>().joinToString("\n") { it.text } }

    fun click(tag: String) {
        var problem: String? = null
        scenario.onActivity { a ->
            val view = a.window.decorView.allViews().firstOrNull { it.tag == tag }
            problem = when {
                view == null -> "no view tagged $tag"
                !view.isEnabled -> "view $tag is disabled"
                else -> { view.performClick(); null }
            }
        }
        problem?.let { fail("$it\n${allText()}") }
        instrumentation.waitForIdleSync()
    }

    fun await(what: String, timeoutMillis: Long = 20_000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < end) {
            if (runCatching(condition).getOrDefault(false)) return
            SystemClock.sleep(50)
        }
        fail("timed out waiting for: $what\n${allText()}")
    }

    fun awaitTag(tag: String, timeoutMillis: Long = 20_000) = await("view tagged $tag", timeoutMillis) { has(tag) }

    /** Records every paragraph in order (the clip comes from the test), moving on with « Suivant ». */
    fun readAllParagraphs(redoLabel: String) {
        val paragraphs = material.text.paragraphs
        for (i in paragraphs.indices) {
            await("paragraph $i on screen") { text(ReadingTestActivity.TAG_PARAGRAPH) == paragraphs[i] }
            click(ReadingTestActivity.TAG_RECORD)
            await("paragraph $i recorded") { text(ReadingTestActivity.TAG_RECORD) == redoLabel }
            if (i < paragraphs.size - 1) click(ReadingTestActivity.TAG_NEXT)
        }
    }
}
