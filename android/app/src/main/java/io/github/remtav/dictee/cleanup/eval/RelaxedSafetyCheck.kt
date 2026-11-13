package io.github.remtav.dictee.cleanup.eval

import java.text.Normalizer

/**
 * EVALUATION ONLY (issue #15, lot 3, step 3.3). Not wired into the app: no
 * setting, no call from DicteeApp or TextPipeline. The production guard is
 * [io.github.remtav.dictee.text.SafetyCheck], which this object neither uses
 * nor changes.
 *
 * Relaxed guard for an LLM asked to fix names and spoken addresses. It accepts
 * the LLM output only if the output can be DERIVED from the input by a
 * sequence of allowed rewrites, each one consuming a contiguous span of input
 * tokens and producing a contiguous span of output tokens, left to right.
 * Anything that is not such a derivation is rejected: an added, dropped,
 * replaced or reordered word, a changed punctuation mark or spacing, a
 * translation, a reformulation, an invented address.
 *
 * Allowed rewrites (specification, see android/DECISIONS.md, step 3.3):
 *
 *  - [Rule.KEEP]: the same token, same text (NFC, ’ = '), same spacing before it.
 *  - [Rule.MIXED_CASE]: 1 to 3 letter-only words joined into one word with the
 *    same letters (case aside) whose case is "mixed": an upper-case letter after
 *    the first one, and one at every join (git hub → GitHub, iphone → iPhone,
 *    whats app → WhatsApp). A plain capital (gaspésie → Gaspésie) is NOT this.
 *  - [Rule.HYPHEN_JOIN]: 2 to 4 words joined by hyphens, letters and case
 *    unchanged, first letter upper-case (Hydro Québec → Hydro-Québec).
 *  - [Rule.SPELLING]: 3 or more spelled letters (single letters, or one word
 *    like T-R-E-M-B-L-A-Y, commas or hyphens between them allowed) → one word
 *    with exactly those letters, any case (G, A G N O N → Gagnon).
 *  - [Rule.POSTAL_CODE]: letters, digits and digit words that spell exactly a
 *    Canadian postal code → "A1A 1A1" (G cinq L trois A un → G5L 3A1).
 *  - [Rule.ADDRESS]: words said aloud → one web or e-mail address, by a fixed
 *    reading: "point"/"dot" = ".", "arobase"/"at"/"a commercial" = "@",
 *    "barre oblique"/"slash" = "/", "tiret"/"dash" = "-", spaces removed,
 *    accents and case folded, spelled letters joined. The address must be in
 *    lower case and equal that reading EXACTLY (no letter added, dropped or
 *    changed, no "www." added), end with a known top-level domain, and the
 *    span may not contain a function word (de, des, the…): "la bulle des
 *    point com" never becomes "la bulle des.com".
 *  - [Rule.LEXICON_FORM] (only with a lexicon): the span spells a lexicon
 *    entry once case, accents, spaces, hyphens and apostrophes are ignored
 *    (trois rivieres → Trois-Rivières, caisse des jardins → caisse Desjardins).
 *  - [Rule.LEXICON_NEAR] (only with a lexicon AND [Options.allowLexiconNear]):
 *    1 to 3 words within a small edit distance of a lexicon name (Drawbox →
 *    Dropbox; budget [nameBudget]), or an address within a small distance of
 *    a lexicon address ([addressBudget]). The span may not swallow a
 *    neighbouring word: if the span without its first or last word is as
 *    close to the name, it is refused ("face time ce" → "FaceTime" would
 *    drop "ce"). Off by default: this is a word replacement, the riskiest
 *    rewrite.
 *
 * The lexicon is the user's dictionary plus a shipped list of names (step
 * 3.2). A word can only be replaced by a lexicon entry, never by anything
 * else: "friperie" → "fripperie" is always rejected.
 */
object RelaxedSafetyCheck {

    data class Options(
        /** Known names (and optionally addresses) in their correct spelling. */
        val lexicon: Collection<String> = emptyList(),
        /** Allows [Rule.LEXICON_NEAR]: replacing a near-sounding span by a lexicon entry. */
        val allowLexiconNear: Boolean = false,
    )

    enum class Rule { KEEP, MIXED_CASE, HYPHEN_JOIN, SPELLING, POSTAL_CODE, ADDRESS, LEXICON_FORM, LEXICON_NEAR }

    /** One rewrite of the derivation: [raw] span of the input became [output]. */
    data class Step(val rule: Rule, val raw: String, val output: String)

    data class Verdict(val ok: Boolean, val reason: String? = null, val steps: List<Step> = emptyList()) {
        /** The rewrites other than [Rule.KEEP]. */
        val changes: List<Step> get() = steps.filter { it.rule != Rule.KEEP }
    }

    fun check(input: String, output: String, options: Options = Options()): Verdict {
        val r = normalizeText(input).trim()
        val o = normalizeText(output).trim()
        if (o.isEmpty()) return Verdict(false, "sortie vide")
        if (r.isEmpty()) return Verdict(false, "entrée vide")
        if ((o.contains('\n') || o.contains('\r')) && !(r.contains('\n') || r.contains('\r'))) {
            return Verdict(false, "saut de ligne ajouté")
        }
        if (o.length > 2 * r.length + 20) return Verdict(false, "sortie trop longue")
        val rt = tokenize(r)
        val ot = tokenize(o)
        if (rt.isEmpty() || ot.isEmpty()) return Verdict(false, "aucun mot")
        return Derivation(rt, ot, Lexicon.of(options.lexicon), options.allowLexiconNear).run()
    }

    // ------------------------------------------------------------------ tokens

    internal enum class Kind { WORD, PUNCT, ADDRESS }

    internal data class Tok(val text: String, val kind: Kind, val spaceBefore: Boolean)

    private val WORD = Regex("[\\p{L}\\p{N}]+(?:['\\-][\\p{L}\\p{N}]+)*")
    private val ELISION = Regex("(?i)(l|d|j|c|qu|s|n|m|t|jusqu|lorsqu|puisqu)'(.+)")
    private val SPELLED_WORD = Regex("(?:\\p{L}-){2,}\\p{L}")
    private const val OPENERS = "([{«\"“'"
    private const val CLOSERS = ".,;:!?)]}»\"”'…"

    internal fun normalizeText(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC).replace('’', '\'')

    /** Lowercase, accents removed (NFD, combining marks dropped). */
    internal fun fold(s: String): String =
        Regex("\\p{Mn}+").replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase()

    /** Letters and digits only, folded: the "spelling" of a span, blind to case, accents, spaces, hyphens, apostrophes. */
    internal fun squash(s: String): String = fold(s).filter { it.isLetterOrDigit() }

    /**
     * Words (letters and digits, inner hyphens and apostrophes kept, French
     * elisions split: "l'assurance" → "l'", "assurance"), single punctuation
     * marks, and whole addresses (a whitespace-separated chunk that is a web or
     * e-mail address once surrounding punctuation is set aside).
     */
    internal fun tokenize(text: String): List<Tok> {
        val out = ArrayList<Tok>()
        for (m in Regex("\\S+").findAll(text)) {
            val chunk = m.value
            val spaced = m.range.first > 0
            var a = 0
            var b = chunk.length
            while (a < b && chunk[a] in OPENERS) a++
            while (b > a && chunk[b - 1] in CLOSERS) b--
            val core = chunk.substring(a, b)
            var first = true
            fun add(text: String, kind: Kind) {
                out += Tok(text, kind, first && spaced)
                first = false
            }
            if (core.isNotEmpty() && Address.isAddress(core)) {
                for (k in 0 until a) add(chunk[k].toString(), Kind.PUNCT)
                add(core, Kind.ADDRESS)
                for (k in b until chunk.length) add(chunk[k].toString(), Kind.PUNCT)
                continue
            }
            var pos = 0
            while (pos < chunk.length) {
                val w = WORD.find(chunk, pos)?.takeIf { it.range.first == pos }
                if (w == null) {
                    add(chunk[pos].toString(), Kind.PUNCT)
                    pos++
                    continue
                }
                val el = ELISION.matchEntire(w.value)
                if (el != null) {
                    add(w.value.substring(0, el.groupValues[1].length + 1), Kind.WORD)
                    add(el.groupValues[2], Kind.WORD)
                } else {
                    add(w.value, Kind.WORD)
                }
                pos = w.range.last + 1
            }
        }
        return out
    }

    private fun isLetterWord(t: Tok) = t.kind == Kind.WORD && t.text.all { it.isLetter() }
    private fun isSingleLetter(t: Tok) = t.kind == Kind.WORD && t.text.length == 1 && t.text[0].isLetter()
    private fun isSpelledWord(t: Tok) = t.kind == Kind.WORD && SPELLED_WORD.matches(t.text)

    private fun join(toks: List<Tok>): String = buildString {
        toks.forEachIndexed { i, t ->
            if (i > 0 && t.spaceBefore) append(' ')
            append(t.text)
        }
    }

    // --------------------------------------------------------------- addresses

    internal object Address {
        val TLDS = setOf(
            "com", "ca", "org", "net", "info", "io", "fr", "quebec", "edu", "gov",
            "co", "me", "app", "dev", "ai", "be", "ch", "uk", "us", "tv",
        )
        private const val LABEL = "[a-z0-9]+(?:-[a-z0-9]+)*"
        private val EMAIL = Regex("[a-z0-9]+(?:[._+-][a-z0-9]+)*@$LABEL(?:\\.$LABEL)*\\.([a-z]+)")
        private val WEB = Regex("(?:$LABEL\\.)+([a-z]+)(?:/[a-z0-9._~-]+)*/?")

        fun isAddress(s: String): Boolean {
            val l = s.lowercase()
            val m = EMAIL.matchEntire(l) ?: WEB.matchEntire(l) ?: return false
            return m.groupValues[1] in TLDS
        }

        /** Spoken symbol words, one token. */
        private val SYMBOLS_1 = mapOf(
            "point" to ".", "dot" to ".", "arobase" to "@", "at" to "@", "slash" to "/",
            "tiret" to "-", "dash" to "-", "hyphen" to "-", "underscore" to "_",
        )

        /** Spoken symbol words, two tokens. */
        private val SYMBOLS_2 = mapOf(
            listOf("barre", "oblique") to "/", listOf("forward", "slash") to "/",
            listOf("a", "commercial") to "@",
        ) // "trait d'union" (three tokens) is read in [read].
        private val PUNCT = setOf(".", "@", "/", "-", "_")

        /** Function words that never belong inside a dictated address (French, English). */
        val FUNCTION_WORDS = setOf(
            "le", "la", "les", "l'", "de", "des", "du", "d'", "un", "une", "à", "au", "aux", "en", "et", "ou",
            "sur", "dans", "par", "pour", "avec", "mon", "ma", "mes", "ton", "ta", "tes", "son", "sa", "ses",
            "ce", "c'", "qui", "que", "qu'", "est", "the", "an", "of", "to", "in", "on", "for", "and", "or",
            "is", "it", "this", "that", "my", "your", "a",
        )

        /**
         * The address that [span] reads as, or null when the span contains
         * something that cannot be part of a dictated address.
         */
        fun read(span: List<Tok>): String? {
            val sb = StringBuilder()
            var k = 0
            while (k < span.size) {
                val t = span[k]
                when (t.kind) {
                    Kind.PUNCT -> {
                        if (t.text !in PUNCT) return null
                        sb.append(t.text)
                    }
                    Kind.ADDRESS -> sb.append(
                        fold(t.text).split('.').joinToString(".") { if (SPELLED_WORD.matches(it)) it.replace("-", "") else it },
                    )
                    Kind.WORD -> {
                        val l = t.text.lowercase()
                        val next = span.getOrNull(k + 1)?.takeIf { it.kind == Kind.WORD }?.text?.lowercase()
                        val next2 = span.getOrNull(k + 2)?.takeIf { it.kind == Kind.WORD }?.text?.lowercase()
                        if (l == "trait" && next == "d'" && next2 == "union") {
                            sb.append('-'); k += 3; continue
                        }
                        val two = next?.let { SYMBOLS_2[listOf(l, it)] }
                        if (two != null) {
                            sb.append(two); k += 2; continue
                        }
                        val one = SYMBOLS_1[l]
                        if (one != null) {
                            sb.append(one); k++; continue
                        }
                        if (SPELLED_WORD.matches(t.text)) {
                            sb.append(fold(t.text.replace("-", "")))
                        } else {
                            val spellingRun = isSingleLetter(t) &&
                                (span.getOrNull(k - 1)?.let(::isSingleLetter) == true ||
                                    span.getOrNull(k + 1)?.let(::isSingleLetter) == true)
                            if (l in FUNCTION_WORDS && !spellingRun) return null
                            if (t.text.contains('\'')) return null
                            sb.append(fold(t.text))
                        }
                    }
                }
                k++
            }
            return sb.toString()
        }
    }

    // ----------------------------------------------------------------- lexicon

    private class Entry(val text: String, val toks: List<Tok>, val squashed: String, val isAddress: Boolean)

    private class Lexicon(val entries: List<Entry>) {
        companion object {
            fun of(words: Collection<String>) = Lexicon(
                words.map { normalizeText(it).trim() }.filter { it.isNotEmpty() }.distinct().map { e ->
                    val toks = tokenize(e)
                    Entry(e, toks, squash(e), toks.any { it.kind == Kind.ADDRESS })
                }.filter { it.toks.isNotEmpty() && it.squashed.isNotEmpty() },
            )
        }
    }

    internal fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /**
     * Largest edit distance accepted by [Rule.LEXICON_NEAR] for a name of this squashed length:
     * 0 below 5 letters, then (length + 2) / 4 (5 letters: 1, 6 to 9: 2, 10 to 13: 3).
     */
    internal fun nameBudget(length: Int) = if (length < 5) 0 else (length + 2) / 4

    /** Same for an address entry. */
    internal fun addressBudget(length: Int) = maxOf(1, length / 6)

    // -------------------------------------------------------------- derivation

    private val DIGIT_WORDS = mapOf(
        "zéro" to '0', "zero" to '0', "un" to '1', "une" to '1', "one" to '1', "deux" to '2', "two" to '2',
        "trois" to '3', "three" to '3', "quatre" to '4', "four" to '4', "cinq" to '5', "five" to '5',
        "six" to '6', "sept" to '7', "seven" to '7', "huit" to '8', "eight" to '8', "neuf" to '9', "nine" to '9',
    )
    private val POSTAL = Regex("[A-Z]\\d[A-Z]\\d[A-Z]\\d")
    private val ALNUM_SHORT = Regex("[\\p{L}\\p{N}]{1,6}")

    private class Back(val i: Int, val j: Int, val step: Step?)

    private class Derivation(
        val r: List<Tok>,
        val o: List<Tok>,
        val lexicon: Lexicon,
        val allowNear: Boolean,
    ) {
        val n = r.size
        val m = o.size
        val reach = Array(n + 1) { arrayOfNulls<Back>(m + 1) }

        fun run(): Verdict {
            reach[0][0] = Back(-1, -1, null)
            for (i in 0 until n) for (j in 0 until m) {
                if (reach[i][j] != null) expand(i, j)
            }
            if (reach[n][m] != null) {
                val steps = ArrayList<Step>()
                var i = n
                var j = m
                while (i > 0 || j > 0) {
                    val b = reach[i][j]!!
                    steps += b.step!!
                    i = b.i; j = b.j
                }
                return Verdict(true, null, steps.reversed())
            }
            return Verdict(false, failure())
        }

        private fun failure(): String {
            var bi = 0
            var bj = 0
            for (i in 0..n) for (j in 0..m) {
                if (reach[i][j] != null && (i + j > bi + bj)) { bi = i; bj = j }
            }
            return when {
                bj == m -> "mot ou signe retiré : « ${join(r.subList(bi, n))} »"
                bi == n -> "mot ou signe ajouté : « ${join(o.subList(bj, m))} »"
                else -> "modification non autorisée : « ${r[bi].text} » → « ${o[bj].text} »"
            }
        }

        private fun accept(i: Int, j: Int, ni: Int, nj: Int, rule: Rule) {
            if (reach[ni][nj] == null) {
                val rs = r.subList(i, ni)
                val os = o.subList(j, nj)
                // A rewrite that reproduces its span token for token (a lexicon name already
                // written right, for instance) is reported as what it is: no change.
                val same = rs.size == os.size && rs.indices.all { rs[it].kind == os[it].kind && rs[it].text == os[it].text }
                reach[ni][nj] = Back(i, j, Step(if (same) Rule.KEEP else rule, join(rs), join(os)))
            }
        }

        private fun expand(i: Int, j: Int) {
            keep(i, j)
            mixedCase(i, j)
            hyphenJoin(i, j)
            spelling(i, j)
            postalCode(i, j)
            address(i, j)
            if (lexicon.entries.isNotEmpty()) lexiconRules(i, j)
        }

        private fun keep(i: Int, j: Int) {
            val a = r[i]
            val b = o[j]
            if (a.kind == b.kind && a.text == b.text && a.spaceBefore == b.spaceBefore) accept(i, j, i + 1, j + 1, Rule.KEEP)
        }

        private fun mixedCase(i: Int, j: Int) {
            val out = o[j]
            // Mixed case: an upper-case letter after the first one, and at least one lower-case letter
            // (an all-capitals word is not a "mixed-case" name: SAAQ needs the lexicon).
            if (!isLetterWord(out) || out.text.drop(1).none { it.isUpperCase() } || out.text.none { it.isLowerCase() }) return
            for (len in 1..3) {
                if (i + len > n) break
                val span = r.subList(i, i + len)
                if (!span.all(::isLetterWord)) break
                val concat = span.joinToString("") { it.text }
                if (len == 1 && concat == out.text) continue
                if (concat.lowercase() != out.text.lowercase()) continue
                var pos = 0
                var joinsOk = true
                for (t in span.dropLast(1)) {
                    pos += t.text.length
                    if (!out.text[pos].isUpperCase()) joinsOk = false
                }
                if (joinsOk) accept(i, j, i + len, j + 1, Rule.MIXED_CASE)
            }
        }

        private fun hyphenJoin(i: Int, j: Int) {
            val out = o[j]
            if (out.kind != Kind.WORD || !out.text.contains('-') || !out.text[0].isUpperCase()) return
            for (len in 2..4) {
                if (i + len > n) break
                val span = r.subList(i, i + len)
                if (span.any { it.kind != Kind.WORD }) break
                if (span.joinToString("-") { it.text } == out.text) accept(i, j, i + len, j + 1, Rule.HYPHEN_JOIN)
            }
        }

        private fun spelling(i: Int, j: Int) {
            val out = o[j]
            if (!isLetterWord(out)) return
            val letters = StringBuilder()
            var k = i
            while (k < n) {
                val t = r[k]
                when {
                    isSingleLetter(t) -> letters.append(t.text)
                    isSpelledWord(t) -> letters.append(t.text.replace("-", ""))
                    t.kind == Kind.PUNCT && (t.text == "," || t.text == "-") && letters.isNotEmpty() &&
                        r.getOrNull(k + 1)?.let { isSingleLetter(it) || isSpelledWord(it) } == true -> {
                        k++
                        continue
                    }
                    else -> break
                }
                k++
                if (letters.length >= 3 && letters.toString().lowercase() == out.text.lowercase()) {
                    accept(i, j, k, j + 1, Rule.SPELLING)
                }
            }
        }

        private fun postalCode(i: Int, j: Int) {
            if (j + 1 >= m) return
            val o1 = o[j]
            val o2 = o[j + 1]
            if (o1.kind != Kind.WORD || o2.kind != Kind.WORD || !o2.spaceBefore) return
            if (!POSTAL.matches(o1.text + o2.text) || o1.text.length != 3) return
            val code = StringBuilder()
            for (k in i until minOf(n, i + 12)) {
                val t = r[k]
                when {
                    t.kind == Kind.PUNCT && t.text == "," && code.isNotEmpty() -> continue
                    t.kind != Kind.WORD -> return
                    else -> {
                        val d = DIGIT_WORDS[t.text.lowercase()]
                        if (d != null && t.text.length > 1) code.append(d)
                        else if (ALNUM_SHORT.matches(t.text)) code.append(t.text.uppercase())
                        else return
                    }
                }
                if (code.length > 6) return
                if (code.toString() == o1.text + o2.text) accept(i, j, k + 1, j + 2, Rule.POSTAL_CODE)
            }
        }

        private fun address(i: Int, j: Int) {
            val out = o[j]
            if (out.kind != Kind.ADDRESS) return
            // Addresses are written in lower case: a capital in a rewritten address is a change, not a reading.
            val target = out.text
            if (target != target.lowercase()) return
            for (k in i + 1..minOf(n, i + 40)) {
                val read = Address.read(r.subList(i, k)) ?: break
                if (read == target) accept(i, j, k, j + 1, Rule.ADDRESS)
                if (read.length > target.length + 2) break
            }
        }

        /**
         * True when the span without its first or its last word is at least as close to the
         * name: the edge word is not part of the misheard name, and the near match would
         * swallow it ("face time ce" → "FaceTime" drops "ce", "de Matane" → "Matane" drops "de").
         */
        private fun absorbsANeighbour(span: List<Tok>, name: String, d: Int): Boolean {
            if (span.size < 2) return false
            val withoutFirst = squash(span.drop(1).joinToString("") { it.text })
            val withoutLast = squash(span.dropLast(1).joinToString("") { it.text })
            return levenshtein(withoutFirst, name) <= d || levenshtein(withoutLast, name) <= d
        }

        private fun lexiconRules(i: Int, j: Int) {
            for (e in lexicon.entries) {
                val size = e.toks.size
                if (j + size > m) continue
                val matches = (0 until size).all { x ->
                    val a = o[j + x]
                    val b = e.toks[x]
                    a.kind == b.kind && (if (a.kind == Kind.ADDRESS) a.text.lowercase() == b.text.lowercase() else a.text == b.text)
                }
                if (!matches) continue
                if (e.isAddress) {
                    if (!allowNear || size != 1) continue
                    val target = e.text.lowercase()
                    if (o[j].text != target) continue
                    for (k in i + 1..minOf(n, i + 40)) {
                        val read = Address.read(r.subList(i, k)) ?: break
                        val d = levenshtein(read, target)
                        if (d in 1..addressBudget(target.length)) accept(i, j, k, j + 1, Rule.LEXICON_NEAR)
                        if (read.length > target.length + addressBudget(target.length)) break
                    }
                    continue
                }
                for (len in 1..minOf(8, size + 4)) {
                    if (i + len > n) break
                    val span = r.subList(i, i + len)
                    if (span.any { it.kind != Kind.WORD }) break
                    val s = squash(span.joinToString("") { it.text })
                    if (s == e.squashed) {
                        accept(i, j, i + len, j + size, Rule.LEXICON_FORM)
                    } else if (allowNear && len <= 3 && s.length >= 3 && span.none { t -> t.text.any { it.isDigit() } }) {
                        val d = levenshtein(s, e.squashed)
                        if (d in 1..nameBudget(e.squashed.length) && !absorbsANeighbour(span, e.squashed, d)) {
                            accept(i, j, i + len, j + size, Rule.LEXICON_NEAR)
                        }
                    }
                }
            }
        }
    }
}
