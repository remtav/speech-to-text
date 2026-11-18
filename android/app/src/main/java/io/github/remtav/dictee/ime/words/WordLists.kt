package io.github.remtav.dictee.ime.words

import android.util.Log
import io.github.remtav.dictee.ime.KeyLanguage
import io.github.remtav.dictee.ime.LayoutKind
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The word lists of the suggestions (issue #71), read from the APK's assets once per language, and the
 * [Suggester] over the lists of the chosen languages. Reading a list and searching it both run on one
 * background thread: a key press never waits for a list being read.
 */
class WordLists(private val open: (String) -> InputStream) {
    private val loaded = ConcurrentHashMap<KeyLanguage, Lexicon>()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "word-lists").apply { isDaemon = true } }

    private class Built(val languages: Set<KeyLanguage>, val layout: LayoutKind, val suggester: Suggester)

    @Volatile private var built: Built? = null

    /** Starts reading the lists of [languages] in the background, if not done yet. */
    fun preload(languages: Set<KeyLanguage>) {
        if (languages.all { loaded.containsKey(it) }) return
        worker.execute { languages.forEach { lexicon(it) } }
    }

    /** Computes the suggestions for [typed] in the background and hands them to [done] (on that thread). */
    fun suggest(languages: Set<KeyLanguage>, layout: LayoutKind, typed: String, done: (Suggestions) -> Unit) {
        worker.execute {
            val result = try {
                suggester(languages, layout).suggest(typed)
            } catch (e: Exception) {
                Log.w(TAG, "suggestions failed", e)
                Suggestions.NONE
            }
            done(result)
        }
    }

    /**
     * The suggestions for [typed] right away, on the caller's thread, or null while a list is still being read
     * (then the word is typed as is: the automatic correction never makes a key wait).
     */
    fun suggestNow(languages: Set<KeyLanguage>, layout: LayoutKind, typed: String): Suggestions? {
        if (!languages.all { loaded.containsKey(it) }) return null
        return suggester(languages, layout).suggest(typed)
    }

    private fun suggester(languages: Set<KeyLanguage>, layout: LayoutKind): Suggester {
        built?.let { if (it.languages == languages && it.layout == layout) return it.suggester }
        val s = Suggester(languages.sortedBy { it.ordinal }.mapNotNull { lexicon(it) }, KeyNeighbours.of(layout))
        built = Built(languages, layout, s)
        return s
    }

    private fun lexicon(language: KeyLanguage): Lexicon? {
        loaded[language]?.let { return it }
        synchronized(this) {
            loaded[language]?.let { return it }
            return try {
                val started = System.nanoTime()
                val lex = open(assetName(language)).use { Lexicon.read(it) }
                Log.i(TAG, "${language.name}: ${lex.size} words in ${(System.nanoTime() - started) / 1_000_000} ms")
                loaded[language] = lex
                lex
            } catch (e: Exception) {
                Log.w(TAG, "word list of ${language.name} unreadable", e)
                null
            }
        }
    }

    companion object {
        private const val TAG = "WordLists"

        /**
         * The file of [language] in the assets, written by `tools/listes-de-mots/construire.py`. Not `.gz`: the
         * Android Gradle plugin unpacks such assets and drops the suffix, so the name would not exist in the APK.
         */
        fun assetName(language: KeyLanguage): String = "words/${language.wordList}.txt.gzip"
    }
}
