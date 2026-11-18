package io.github.remtav.dictee.cleanup.eval

/**
 * EVALUATION ONLY (issue #15, lot 3, step 3.3). Not wired into the app.
 *
 * The "wide" prompt of approach 3: unlike the production prompt of
 * [io.github.remtav.dictee.cleanup.LlmCleaner] (hesitations and
 * self-corrections only, left unchanged), it asks the model to fix product
 * and technical names, Québec place and institution names, spelled letters,
 * and web or e-mail addresses said aloud, and nothing else. Whatever the
 * model returns is then judged by [RelaxedSafetyCheck].
 *
 * Instructions are in English (small instruction-tuned models follow English
 * instructions best), with French and English examples.
 */
object WidePrompt {
    /** Chat template of the model family. */
    enum class Template {
        /** Qwen2.5 (and other ChatML models). */
        CHATML,
        /** Gemma 2 / Gemma 3 instruction-tuned models. */
        GEMMA,
    }

    /** Largest number of lexicon names given to the model as hints. */
    const val MAX_HINTS = 12

    fun system(language: String, hints: List<String>): String = buildString {
        val lang = if (language == "en") "English" else "French (Québec)"
        append("You fix speech-recognition errors in ONE dictated segment, written in $lang. Fix ONLY these:\n")
        append("1. Product, company and technology names: \"git hub\" -> \"GitHub\", \"whats app\" -> \"WhatsApp\", \"iphone\" -> \"iPhone\".\n")
        append("2. Québec place and institution names: \"lac Saint Jean\" -> \"Lac-Saint-Jean\", \"Trois Rivieres\" -> \"Trois-Rivières\", \"Hydro Québec\" -> \"Hydro-Québec\".\n")
        append("3. Spelled letters: \"B O U C H A R D\" -> \"Bouchard\"; postal codes: \"G5L, 3A1\" -> \"G5L 3A1\".\n")
        append("4. Web and e-mail addresses said aloud: \"québec point ca\" -> \"quebec.ca\", \"info arobase uqar point ca\" -> \"info@uqar.ca\", \"github dot com slash kalmia\" -> \"github.com/kalmia\".\n")
        append("Copy every other word exactly, letter for letter, even when it looks rare, informal or misspelled. ")
        append("These Québec words are correct and must not change: friperie, poudrerie, dépanneur, cégep, pourvoirie, achalandage, tabagie, clavardage, achaler, pantoute, magasiner, tuque, frette, char, chum.\n")
        append("Never add, remove, translate, reorder or rephrase words. Do not change punctuation, and do not change capital letters except in the names you fix. ")
        append("Never invent an address or a name: only join and spell what was said. If nothing needs fixing, output the text unchanged. ")
        append("Output only the text, without quotes, notes or explanations.")
        if (hints.isNotEmpty()) {
            append("\nKnown names (use this spelling only if the text clearly says one of them): ")
            append(hints.joinToString(", "))
            append('.')
        }
    }

    fun build(text: String, language: String, lexicon: Collection<String>, template: Template): String {
        val sys = system(language, LexiconHints.relevant(text, lexicon, MAX_HINTS))
        return when (template) {
            Template.CHATML -> "<|im_start|>system\n$sys<|im_end|>\n<|im_start|>user\n$text<|im_end|>\n<|im_start|>assistant\n"
            Template.GEMMA -> "<start_of_turn>user\n$sys\n\nText:\n$text<end_of_turn>\n<start_of_turn>model\n"
        }
    }
}

/**
 * Picks the lexicon names worth showing to the model for one segment: those
 * close to a window of 1 to 3 words of the text (squashed edit distance at
 * most a third of the name), closest first. Keeps the prompt short (latency)
 * while still telling the model how "Chicoutimi" is spelled when it hears
 * "Chikouti". Addresses in the lexicon are offered only when the text seems
 * to contain a spoken address.
 */
object LexiconHints {
    private val SPOKEN_ADDRESS = Regex("(?i)(?<![\\p{L}\\p{N}])(point|dot|arobase|at)(?![\\p{L}\\p{N}])|@|\\.(com|ca|org|net)\\b")

    fun relevant(text: String, lexicon: Collection<String>, max: Int): List<String> {
        if (lexicon.isEmpty() || max <= 0) return emptyList()
        val words = RelaxedSafetyCheck.tokenize(RelaxedSafetyCheck.normalizeText(text))
            .filter { it.kind != RelaxedSafetyCheck.Kind.PUNCT }
            .map { RelaxedSafetyCheck.squash(it.text) }
            .filter { it.isNotEmpty() }
        val windows = buildList {
            for (i in words.indices) for (len in 1..3) {
                if (i + len <= words.size) add(words.subList(i, i + len).joinToString(""))
            }
        }
        val spokenAddress = SPOKEN_ADDRESS.containsMatchIn(text)
        val scored = lexicon.distinct().mapNotNull { e ->
            val isAddress = RelaxedSafetyCheck.Address.isAddress(e.trim())
            if (isAddress) return@mapNotNull if (spokenAddress) e to 0 else null
            val s = RelaxedSafetyCheck.squash(e)
            if (s.length < 3) return@mapNotNull null
            val best = windows.minOfOrNull { RelaxedSafetyCheck.levenshtein(it, s) } ?: return@mapNotNull null
            if (best <= maxOf(1, s.length / 3)) e to best else null
        }
        return scored.sortedWith(compareBy({ it.second }, { it.first })).take(max).map { it.first }
    }
}
