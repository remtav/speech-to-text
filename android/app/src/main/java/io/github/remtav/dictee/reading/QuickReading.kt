package io.github.remtav.dictee.reading

import io.github.remtav.dictee.stt.DictationLanguage
import io.github.remtav.dictee.stt.DictationLanguage.ENGLISH
import io.github.remtav.dictee.stt.DictationLanguage.FRENCH_FRANCE
import io.github.remtav.dictee.stt.DictationLanguage.FRENCH_QUEBEC
import io.github.remtav.dictee.stt.DictationLanguage.PORTUGUESE
import io.github.remtav.dictee.stt.DictationLanguage.SPANISH

/**
 * The five short texts of the installation quick test (issue #36, step 5): one paragraph of about 40 words per
 * language, `assets/lecture/rapide-<langue>-1.txt`, written by the agent (free of rights). Like the long text, a
 * published version is never edited: a new version is a new file (`-2`).
 *
 * Spanish and Portuguese texts have not been proofread by a native speaker (issue #36a).
 */
object QuickReading {
    /** Languages that have a quick text, in display order (same list as `TranscriptionAdvisor.quickTestLanguages`). */
    val languages: List<DictationLanguage> = listOf(FRENCH_QUEBEC, FRENCH_FRANCE, ENGLISH, SPANISH, PORTUGUESE)

    /** Words of a quick text: about 40 (a reading of about 20 s), never more than the long text's cap. */
    const val MIN_WORDS = 34
    const val MAX_WORDS = ReadingText.MAX_PARAGRAPH_WORDS

    /** `rapide-fr-qc-1`, `rapide-fr-fr-1`, `rapide-en-1`, `rapide-es-1`, `rapide-pt-1`. */
    fun version(language: DictationLanguage): String = when (language) {
        FRENCH_QUEBEC -> "rapide-fr-qc-1"
        FRENCH_FRANCE -> "rapide-fr-fr-1"
        ENGLISH -> "rapide-en-1"
        SPANISH -> "rapide-es-1"
        PORTUGUESE -> "rapide-pt-1"
        else -> throw IllegalArgumentException("pas de texte de lecture rapide pour ${language.label}")
    }

    fun hasText(language: DictationLanguage) = language in languages

    fun assetPath(language: DictationLanguage) = ReadingText.textAsset(version(language))

    /** Loads the text and its normaliser (no table of accepted spellings: standard spelling is expected). */
    fun load(language: DictationLanguage, readAsset: (String) -> String): ReadingMaterial {
        val version = version(language)
        val text = ReadingText.parse(readAsset(assetPath(language)))
        require(text.version == version) { "version incohérente : demandé $version, texte ${text.version}" }
        require(text.paragraphs.size == 1) { "un seul paragraphe attendu : $version" }
        return ReadingMaterial(text, ReadingNormalizer(Equivalences(version, emptyList(), language.engineCode), language.engineCode))
    }
}
