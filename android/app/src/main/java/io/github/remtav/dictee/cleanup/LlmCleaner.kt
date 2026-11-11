package io.github.remtav.dictee.cleanup

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import io.github.remtav.dictee.dictation.TextCleaner
import io.github.remtav.dictee.stt.Downloadable
import io.github.remtav.dictee.stt.ModelFile
import io.github.remtav.dictee.stt.ModelStore
import io.github.remtav.dictee.text.RuleCleaner
import io.github.remtav.dictee.text.SafetyCheck
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A small instruction-tuned LLM in MediaPipe's .task format, downloaded on demand. */
data class LlmSpec(
    override val id: String,
    val displayName: String,
    val file: ModelFile,
) : Downloadable {
    override val files: List<ModelFile> get() = listOf(file)
}

object LlmCatalog {
    private const val Q15 = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main"
    private const val Q05 = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main"

    val qwen15 = LlmSpec(
        "llm-qwen2.5-1.5b-instruct-q8", "Qwen2.5 1.5B Instruct (q8, 1,6 Go)",
        ModelFile(
            "model.task", "$Q15/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            "8d867a7c93a6acf2892f08e0174e2f6f351ad256b7e3cfb6d6cd9c89794b42e0", 1597913616,
        ),
    )
    val qwen05 = LlmSpec(
        "llm-qwen2.5-0.5b-instruct-q8", "Qwen2.5 0.5B Instruct (q8, 0,5 Go)",
        ModelFile(
            "model.task", "$Q05/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            "e608953f169aeb1bd7b9155fec2559825e08453fc209b84eda3a781ed0452fd2", 546660344,
        ),
    )
    val all = listOf(qwen15, qwen05)
    fun byId(id: String?) = all.firstOrNull { it.id == id }
}

/**
 * Optional clean-up of each finished segment by a local LLM (lot 3, in the
 * spirit of issue #6): hesitations and self-corrections removed, nothing else
 * changed. The rule-based clean-up runs first; the LLM result replaces it only
 * if [SafetyCheck] accepts it, within [timeoutMillis]. Any failure (missing
 * native library on this device, model error, timeout, rejected output) keeps
 * the rule-cleaned text: this never throws and never blocks dictation for long.
 */
class LlmCleaner private constructor(
    private val llm: LlmInference,
    private val dictionary: Collection<String>,
    private val timeoutMillis: Long,
) : TextCleaner {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "llm-cleanup") }

    override fun clean(text: String): String {
        val base = RuleCleaner.clean(text)
        if (base.split(' ').size < 4) return base // nothing worth rewriting
        return try {
            val future = executor.submit<String> { generate(base) }
            val out = future.get(timeoutMillis, TimeUnit.MILLISECONDS).trim().trim('"', '«', '»').trim()
            val verdict = SafetyCheck.check(base, out, dictionary)
            if (verdict.ok) out else { Log.i(TAG, "rejected: ${verdict.rule}"); base }
        } catch (e: Throwable) {
            Log.w(TAG, "cleanup failed: ${e.javaClass.simpleName}")
            base
        }
    }

    private fun generate(text: String): String {
        val options = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTemperature(0f).setTopK(1).setRandomSeed(0).build()
        LlmInferenceSession.createFromOptions(llm, options).use { session ->
            session.addQueryChunk(prompt(text))
            return session.generateResponse()
        }
    }

    fun close() {
        executor.shutdownNow()
        try { llm.close() } catch (_: Throwable) {}
    }

    companion object {
        private const val TAG = "LlmCleaner"

        fun prompt(text: String) = buildString {
            append("<|im_start|>system\n")
            append(
                "You clean up dictated text. Remove hesitations (euh, um, uh), stutters and false starts. " +
                    "When the speaker corrects themselves (\"lundi, non, mardi\" / \"Monday, no, Tuesday\"), keep only " +
                    "the correction. Fix punctuation. Keep the same language, words, tone and meaning. Never add, " +
                    "answer, translate or summarize. If the text is a question, output the question. Output only " +
                    "the cleaned text.",
            )
            append("<|im_end|>\n<|im_start|>user\n")
            append(text)
            append("<|im_end|>\n<|im_start|>assistant\n")
        }

        /**
         * Builds the cleaner, or returns null when the device cannot run it
         * (no arm64 native library, model missing or unreadable).
         */
        fun create(
            context: Context,
            store: ModelStore,
            spec: LlmSpec,
            dictionary: Collection<String>,
            timeoutMillis: Long = 4000,
        ): LlmCleaner? {
            if (!store.isInstalled(spec)) return null
            return try {
                val path = File(store.dirFor(spec.id), spec.file.path).absolutePath
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(path)
                    .setMaxTokens(512)
                    .setMaxTopK(1)
                    .build()
                LlmCleaner(LlmInference.createFromOptions(context, options), dictionary, timeoutMillis)
            } catch (e: Throwable) {
                // UnsatisfiedLinkError on x86_64 / armv7 builds, IllegalStateException on bad model...
                Log.w(TAG, "LLM unavailable: $e")
                null
            }
        }
    }
}
