package io.github.remtav.dictee.reading

import io.github.remtav.dictee.text.Normalize

/**
 * The reading text of the Québec French test (issue #15, lot 4, step 4.1): a stable version id and
 * its paragraphs, each read and transcribed on its own (one recording segment, under 20 s).
 *
 * Stored as an asset (`assets/lecture/<version>.txt`), next to its table of accepted spellings
 * (`<version>.equivalences.tsv`): one plain file per version, readable and diffable, that the
 * screen reads paragraph by paragraph and that the contribution archive can quote byte for byte.
 * Not string resources: the text is data that must not be translated, reworded by a resource
 * overlay or split across keys, and its version must travel with it. Pure Kotlin: tested on the JVM.
 */
data class ReadingText(val version: String, val paragraphs: List<String>) {

    /** Words of paragraph [index] as displayed (an elided or contracted form like « y'a » is one word). */
    fun wordCount(index: Int): Int = Normalize.words(paragraphs[index]).size

    /** Displayed words of the whole text. */
    val wordCount: Int get() = paragraphs.indices.sumOf { wordCount(it) }

    /** Seconds needed to read paragraph [index] aloud at [READING_WORDS_PER_SECOND]. */
    fun readingSeconds(index: Int): Double = wordCount(index) / READING_WORDS_PER_SECOND

    companion object {
        /** The version the test shows today. A new version is a new file, never an edit of a published one. */
        const val CURRENT_VERSION = "lecture-qc-1"

        /** Asset directory of the reading texts. */
        const val ASSET_DIR = "lecture"

        /** Unhurried reading pace used to size the paragraphs (about 150 words a minute). */
        const val READING_WORDS_PER_SECOND = 2.5

        /** Longest recording segment of the dictation (the paragraph must fit in one). */
        const val MAX_PARAGRAPH_SECONDS = 20.0

        /** Word cap per paragraph: 40 words = 16 s at 2.5 words/s, a margin under [MAX_PARAGRAPH_SECONDS]. */
        const val MAX_PARAGRAPH_WORDS = 40

        fun textAsset(version: String = CURRENT_VERSION) = "$ASSET_DIR/$version.txt"
        fun equivalencesAsset(version: String = CURRENT_VERSION) = "$ASSET_DIR/$version.equivalences.tsv"

        private const val VERSION_KEY = "version:"

        /**
         * Parses a text file: `#` lines are comments, `version: <id>` gives the id (exactly once),
         * paragraphs are separated by blank lines (lines of one paragraph are joined by a space).
         */
        fun parse(content: String): ReadingText {
            var version: String? = null
            val paragraphs = mutableListOf<String>()
            val current = StringBuilder()
            fun flush() {
                if (current.isNotBlank()) paragraphs += current.toString().trim()
                current.clear()
            }
            for (raw in content.lines()) {
                val line = raw.trimEnd()
                when {
                    line.startsWith("#") -> Unit
                    line.startsWith(VERSION_KEY) -> {
                        require(version == null) { "version déclarée deux fois" }
                        version = line.removePrefix(VERSION_KEY).trim()
                    }
                    line.isBlank() -> flush()
                    else -> {
                        if (current.isNotEmpty()) current.append(' ')
                        current.append(line.trim())
                    }
                }
            }
            flush()
            val v = requireNotNull(version) { "ligne « version: » absente" }
            require(VERSION_ID.matches(v)) { "identifiant de version invalide : $v" }
            require(paragraphs.isNotEmpty()) { "aucun paragraphe" }
            return ReadingText(v, paragraphs)
        }

        /** lowercase words joined by hyphens, ending with a number: lecture-qc-1. */
        val VERSION_ID = Regex("[a-z]+(?:-[a-z]+)*-[1-9][0-9]*")
    }
}

/**
 * A reading text with the normalisation that goes with its version. Load it with [load], giving a
 * function that reads an asset as text (on Android: `assets.open(path).bufferedReader().use { it.readText() }`).
 */
class ReadingMaterial(val text: ReadingText, val normalizer: ReadingNormalizer) {
    val version: String get() = text.version

    companion object {
        fun load(version: String = ReadingText.CURRENT_VERSION, readAsset: (String) -> String): ReadingMaterial {
            val text = ReadingText.parse(readAsset(ReadingText.textAsset(version)))
            val equivalences = Equivalences.parse(readAsset(ReadingText.equivalencesAsset(version)))
            require(text.version == version && equivalences.version == version) {
                "versions incohérentes : demandé $version, texte ${text.version}, équivalences ${equivalences.version}"
            }
            return ReadingMaterial(text, ReadingNormalizer(equivalences))
        }
    }
}
