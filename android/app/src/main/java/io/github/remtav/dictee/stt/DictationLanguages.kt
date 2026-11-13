package io.github.remtav.dictee.stt

/**
 * What the chosen languages mean for the engines and for storage (issue #15,
 * step 1.3). Pure Kotlin: tested on the JVM.
 */
object DictationLanguages {
    /** The only codes the server accepts today (empty means auto-detect). */
    val remoteCodes = setOf("fr", "en")

    /** The distinct engine codes of [chosen] (Quebec and France share "fr"). */
    fun engineCodes(chosen: Set<DictationLanguage>): Set<String> = chosen.map { it.engineCode }.toSet()

    /**
     * Language forced on local Whisper: the code when every chosen language
     * shares one, "" (automatic detection) otherwise.
     */
    fun whisperLanguage(chosen: Set<DictationLanguage>): String = engineCodes(chosen).singleOrNull() ?: ""

    /**
     * Language names Qwen3-ASR takes in its prompt ("language French"), for the
     * codes of [DictationLanguage] it covers (Qwen3-ASR model card; sherpa-onnx
     * 2.0.1 passes them through `OfflineStream.setOption("language", …)`).
     * Bengali and Urdu are not Qwen3-ASR languages: no forcing.
     */
    private val qwen3Names = mapOf(
        "fr" to "French", "en" to "English", "zh" to "Chinese", "hi" to "Hindi",
        "es" to "Spanish", "ar" to "Arabic", "pt" to "Portuguese", "ru" to "Russian",
    )

    /**
     * Language forced on Qwen3-ASR (issue #15, "friperie": on a single word the
     * model guessed English): same rule as [whisperLanguage], as a Qwen3 name,
     * "" (automatic detection) when several codes are chosen or the code is not
     * a Qwen3-ASR language.
     */
    fun qwen3Language(chosen: Set<DictationLanguage>): String = qwen3Names[whisperLanguage(chosen)] ?: ""

    /**
     * Default of the server language: the single chosen code if the server
     * accepts it, "" otherwise.
     */
    fun remoteLanguage(chosen: Set<DictationLanguage>): String =
        whisperLanguage(chosen).takeIf { it in remoteCodes } ?: ""

    /**
     * Reads a stored set of enum names. Unknown names are dropped; an absent or
     * empty result falls back to [DictationLanguage.default], so the selection
     * is never empty. The result follows the order of the list shown to the user.
     */
    fun parse(names: Collection<String>?): Set<DictationLanguage> {
        val found = DictationLanguage.entries.filter { l -> names?.contains(l.name) == true }
        return if (found.isEmpty()) setOf(DictationLanguage.default) else found.toSet()
    }

    /** Names to store; an empty selection is stored as the default. */
    fun serialize(chosen: Set<DictationLanguage>): Set<String> =
        parse(chosen.map { it.name }).map { it.name }.toSet()
}
