package io.github.remtav.dictee.ime.words

import io.github.remtav.dictee.ime.KeyLanguage
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The word lists shipped with the application (`assets/words`), read once per process and off the
 * main thread: a list holds about 200,000 words. [open] gives the file of a language code.
 */
class WordLists(private val open: (String) -> InputStream) {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "words").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }
    private val loaded = ConcurrentHashMap<KeyLanguage, Lexicon>()

    /** Runs [work] on the thread of the word lists (also used to build a [GestureDecoder]). */
    fun inBackground(work: () -> Unit) {
        executor.execute { runCatching(work) }
    }

    /** Reads the lists of [languages] if needed, then calls [ready] with them, on the background thread. */
    fun load(languages: Set<KeyLanguage>, ready: (List<Lexicon>) -> Unit) = inBackground {
        ready(KeyLanguage.entries.filter { it in languages }.map { l -> loaded.getOrPut(l) { open(file(l)).use { Lexicon.read(it) } } })
    }

    companion object {
        fun file(language: KeyLanguage): String = "words/" + when (language) {
            KeyLanguage.FRENCH -> "fr"
            KeyLanguage.ENGLISH -> "en"
            KeyLanguage.SPANISH -> "es"
            KeyLanguage.PORTUGUESE -> "pt"
        } + ".tsv.gz"
    }
}
