package io.github.remtav.dictee.text.polish

import io.github.remtav.dictee.text.Normalize

/**
 * What [PolishRules.assess] says about one dictated segment.
 *
 *  - [UNCHANGED]: no address or spelling context found, nothing touched.
 *  - [CORRECTED]: the rules rewrote something and nothing suspicious remains.
 *  - [PROBABLE_CASE]: an address or a spelling is probably there but the rules
 *    could not resolve it (a spoken "point com" with no usable name before it, an
 *    "arobase" followed by a domain they could not parse, a long run of
 *    separate letters with no spelling cue). The text may still have been
 *    changed elsewhere. A caller that has something smarter (a local LLM) runs
 *    it on these segments only.
 */
enum class RuleSignal { UNCHANGED, CORRECTED, PROBABLE_CASE }

/**
 * [hasAddress]: the output contains a web address or an e-mail address (built by
 * the rules or already written by the model). The rules cannot check that its
 * name is spelled right; a caller may want a second look at those segments.
 */
data class RuleResult(val text: String, val signal: RuleSignal, val hasAddress: Boolean)

/**
 * Deterministic rules for spoken addresses and spelled-out words (issue #15,
 * step 3.2, approach 1). Pure Kotlin, no Android dependency, no model. NOT wired
 * into the dictation pipeline: nothing calls it in the app.
 *
 * Handles, in French and English: "point com" / "dot ca" → `.com` / `.ca`,
 * "arobase" / "at" → `@`, "barre oblique" / "slash" → `/`, "tiret" /
 * "trait d'union" / "dash" / "underscore" inside an address, spaces inside a
 * written address, letters spelled one by one (`G A G N O N`, `T-R-E-M-B-L-A-Y`,
 * `c, o, i, f…`) and Canadian postal codes (`G5L, 3A1` → `G5L 3A1`).
 *
 * Principle: when in doubt, change nothing. A spoken address is only rewritten
 * when the whole shape is there: a name that follows a lead-in word ("sur",
 * "à", "to", "c'est"…), the dot words, and a known ending (com, ca, org…). Words
 * like "un point commun", "mets un point final", "plan B", "vitamine C", "dot
 * com bubble" or "arobase" used as a word stay untouched.
 */
object PolishRules {
    private val TLDS = setOf("com", "ca", "org", "net", "edu", "io", "info", "gov")
    private val TLD_ALTERNATION = TLDS.joinToString("|")

    /** Words that can never be part of a spoken name or are not safe to glue. */
    private val STOP: Set<String> = setOf(
        // French
        "le", "la", "les", "un", "une", "des", "du", "de", "au", "aux", "a", "et", "ou", "mais", "donc", "ni", "car",
        "que", "qui", "quoi", "dont", "ce", "cet", "cette", "ces", "mon", "ma", "mes", "ton", "ta", "tes", "son", "sa",
        "ses", "notre", "nos", "votre", "vos", "leur", "leurs", "je", "tu", "il", "elle", "on", "nous", "vous", "ils",
        "elles", "me", "te", "se", "lui", "y", "en", "ne", "pas", "plus", "tres", "est", "sont", "suis", "es", "etre",
        "avoir", "ai", "as", "ont", "avons", "avez", "va", "vas", "vont", "aller", "allez", "voir", "sur", "sous",
        "dans", "par", "pour", "avec", "sans", "chez", "vers", "entre", "si", "comme", "aussi", "alors", "puis",
        "ensuite", "ici", "voici", "voila", "visite", "visitez", "consulte", "consultez", "ecris", "ecrivez", "ecrire",
        "envoie", "envoyez", "envoyer", "contacte", "contactez", "courriel", "adresse", "site", "lien", "url", "web",
        "page", "mets", "met", "mettre", "dit", "dis", "dire",
        // English
        "the", "an", "and", "or", "but", "of", "to", "in", "on", "at", "for", "with", "from", "by", "is", "are", "was",
        "were", "be", "been", "it", "its", "this", "that", "these", "those", "my", "your", "his", "her", "our",
        "their", "i", "you", "he", "she", "we", "they", "him", "us", "them", "as", "if", "so", "not", "no", "yes",
        "please", "go", "check", "visit", "send", "email", "mail", "write", "see", "out", "website", "link", "address",
        "contact", "said", "say", "put",
        // joiners, never part of a name
        "point", "dot", "arobase", "arrobase", "arobas", "tiret", "trait", "dash", "hyphen", "underscore", "slash",
        "barre", "oblique", "soulignement",
    )

    /** Words that, right before a name, make it probable that an address follows. */
    private val LEAD: Set<String> = setOf(
        "sur", "a", "voir", "va", "vas", "allez", "aller", "visite", "visitez", "consulte", "consultez", "c'est",
        "est", "sont", "courriel", "adresse", "site", "lien", "url", "vers", "chez", "via", "ecris", "ecrivez",
        "ecrire", "envoie", "envoyez", "envoyer", "contacte", "contactez", "par", "web", "page", "mets", "dit",
        "to", "at", "on", "visit", "check", "out", "go", "is", "are", "email", "mail", "address", "website", "link",
        "see", "from", "contact", "write", "send", "said", "put",
    )

    private val CUES: Set<String> = setOf(
        "s'ecrit", "s'ecrivent", "s'epelle", "s'epellent", "s'appelle", "epelle", "epeler", "epele", "epelez", "ecrit",
        "lettres", "spelled", "spell", "spells", "spelling", "letters",
    )

    private class Words(
        val dot: Set<String>,
        val at: Set<String>,
        val hyphen: Set<String>,
    )

    private val FR = Words(dot = setOf("point"), at = setOf("arobase", "arrobase", "arobas"), hyphen = setOf("tiret"))
    private val EN = Words(dot = setOf("dot"), at = setOf("at"), hyphen = setOf("dash", "hyphen"))

    private fun words(language: String) = if (language.trim().lowercase().startsWith("en")) EN else FR

    // ----- tokens -----

    private class Tok(var sep: String, var lead: String, var core: String, var trail: String) {
        fun render() = sep + lead + core + trail
        val key: String get() = Normalize.fold(core)
    }

    private const val LEAD_PUNCT = "([{\"«“"
    private const val TRAIL_PUNCT = ".,;:!?…)]}\"»”"
    private val TOKEN = Regex("(\\s*)(\\S+)")

    private fun tokenize(text: String): Pair<MutableList<Tok>, String> {
        val toks = ArrayList<Tok>()
        var end = 0
        for (m in TOKEN.findAll(text)) {
            val word = m.groupValues[2]
            var s = 0
            var e = word.length
            while (s < e && word[s] in LEAD_PUNCT) s++
            while (e > s && word[e - 1] in TRAIL_PUNCT) e--
            toks += if (s == e) Tok(m.groupValues[1], "", word, "") else Tok(m.groupValues[1], word.substring(0, s), word.substring(s, e), word.substring(e))
            end = m.range.last + 1
        }
        return toks to text.substring(end)
    }

    private fun render(toks: List<Tok>, tail: String) = toks.joinToString("") { it.render() } + tail

    // ----- entry points -----

    fun polish(text: String, language: String): String = assess(text, language).text

    /** Never throws: on any internal problem the segment is returned as is. */
    fun assess(text: String, language: String): RuleResult {
        if (text.isBlank()) return RuleResult(text, RuleSignal.UNCHANGED, false)
        return try {
            run(text, words(language))
        } catch (e: RuntimeException) {
            RuleResult(text, RuleSignal.UNCHANGED, false)
        }
    }

    private fun run(input: String, w: Words): RuleResult {
        var text = input
        text = fixWrittenAddresses(text)
        text = POSTAL.replace(text) { "${it.groupValues[1]}${it.groupValues[2]}${it.groupValues[3]} ${it.groupValues[4]}${it.groupValues[5]}${it.groupValues[6]}" }
        var (toks, tail) = tokenize(text)
        var probable = joinHyphenatedLetters(toks)
        probable = joinLetterRuns(toks, w) or probable
        parseAddresses(toks, w)
        probable = probable || leftoverCue(toks, w)
        val out = render(toks, tail)
        val signal = when {
            probable -> RuleSignal.PROBABLE_CASE
            out != input -> RuleSignal.CORRECTED
            else -> RuleSignal.UNCHANGED
        }
        return RuleResult(out, signal, toks.any { isAddressToken(it.core) })
    }

    // ----- pass 0: spaces inside a written address, postal codes -----

    private val EMAIL_SPACED = Regex(
        "([\\p{L}\\p{N}._%+-]+)\\s*@\\s*([\\p{L}\\p{N}-]+(?:\\s*\\.\\s*[\\p{L}\\p{N}-]+)*?\\s*\\.\\s*(?:$TLD_ALTERNATION)(?![\\p{L}\\p{N}]))",
        RegexOption.IGNORE_CASE,
    )
    private val SPACED_DOTS = Regex("(?<![\\p{L}\\p{N}.@-])([\\p{L}\\p{N}-]+(?:\\s+\\.\\s+[\\p{L}\\p{N}-]+)+)(?![\\p{L}\\p{N}])")
    private val WS = Regex("\\s+")

    private fun fixWrittenAddresses(text: String): String {
        var out = EMAIL_SPACED.replace(text) { m -> if (WS.containsMatchIn(m.value)) WS.replace(m.value, "") else m.value }
        out = SPACED_DOTS.replace(out) { m ->
            val joined = WS.replace(m.value, "")
            if (joined.substringAfterLast('.').lowercase() in TLDS) joined else m.value
        }
        return out
    }

    /** Canadian postal code written with stray spaces or commas: G5L, 3A1 → G5L 3A1. Uppercase only. */
    private val POSTAL = Regex("(?<![\\p{L}\\p{N}])([ABCEGHJ-NPRSTVXY])\\s?(\\d)\\s?([A-Z])[\\s,]*(\\d)\\s?([A-Z])\\s?(\\d)(?![\\p{L}\\p{N}])")

    // ----- pass 1: letters spelled one by one -----

    private val HYPHEN_LETTERS = Regex("^((?:\\p{L}-)+\\p{L})((?:\\.\\p{L}+)*)$")

    private fun consecutiveAlphabet(letters: String): Boolean {
        val l = letters.lowercase()
        return l.length >= 2 && l.indices.drop(1).all { l[it] == l[it - 1] + 1 }
    }

    private fun spelled(letters: String, inAddress: Boolean): String {
        val l = letters.lowercase()
        val allUpper = letters.all { it.isUpperCase() }
        return if (!inAddress && allUpper) l.replaceFirstChar { it.uppercase() } else l
    }

    /** `T-R-E-M-B-L-A-Y`, `C-O-I-F-F-E-R-I.com`. Returns true when an uncertain case remains. */
    private fun joinHyphenatedLetters(toks: MutableList<Tok>): Boolean {
        for (t in toks) {
            val m = HYPHEN_LETTERS.matchEntire(t.core) ?: continue
            val letters = m.groupValues[1].replace("-", "")
            val suffix = m.groupValues[2]
            val addressSuffix = suffix.isNotEmpty() && suffix.substringAfterLast('.').lowercase() in TLDS
            if (letters.length < (if (addressSuffix) 3 else 4)) continue
            if (!addressSuffix && consecutiveAlphabet(letters)) continue
            t.core = spelled(letters, addressSuffix) + suffix.lowercase()
        }
        return false
    }

    private fun isLetterTok(t: Tok) = t.lead.isEmpty() && t.core.length == 1 && t.core[0].isLetter()

    /** Runs of single-letter tokens: `G, A G N O N` → `Gagnon`. Returns true when an uncertain run remains. */
    private fun joinLetterRuns(toks: MutableList<Tok>, w: Words): Boolean {
        var probable = false
        var i = 0
        while (i < toks.size) {
            if (!isLetterTok(toks[i])) { i++; continue }
            var j = i
            while (true) {
                val t = toks[j]
                val continues = t.trail.isEmpty() || t.trail == ","
                if (!continues || j + 1 >= toks.size || !isLetterTok(toks[j + 1])) break
                j++
            }
            var start = i
            // "elle a G A G N O N": a lone lowercase word in front of capitals is a word, not a letter.
            if (j - start >= 2 && toks[start].core[0].isLowerCase() && toks[start + 1].core[0].isUpperCase()) start++
            val n = j - start + 1
            val letters = (start..j).joinToString("") { toks[it].core }
            val next = toks.getOrNull(j + 1)
            val addressFollow = next != null && (next.key in w.dot || next.key in w.at)
            val cue = (maxOf(0, start - 4) until start).any { toks[it].key in CUES }
            val join = when {
                n >= 3 && addressFollow -> true
                n >= 4 && cue && !consecutiveAlphabet(letters) -> true
                else -> false
            }
            if (join) {
                val first = toks[start]
                val last = toks[j]
                val merged = Tok(first.sep, "", spelled(letters, addressFollow), if (addressFollow && last.trail == ",") "" else last.trail)
                repeat(j - start + 1) { toks.removeAt(start) }
                toks.add(start, merged)
                i = start + 1
            } else {
                if (n >= 4 && !consecutiveAlphabet(letters)) probable = true
                i = j + 1
            }
        }
        return probable
    }

    // ----- pass 2: spoken addresses -----

    private val LABEL = Regex("^[\\p{L}\\p{N}]+(?:[._-][\\p{L}\\p{N}]+)*$")
    private val DOMAIN_TOKEN = Regex("^[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)*\\.(?:$TLD_ALTERNATION)$", RegexOption.IGNORE_CASE)
    private val EMAIL_TOKEN = Regex("^[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)*\\.[\\p{L}]{2,}$")

    private fun isAddressToken(core: String) = DOMAIN_TOKEN.matches(core) || EMAIL_TOKEN.matches(core)

    private fun isLabelWord(t: Tok): Boolean =
        t.lead.isEmpty() && LABEL.matches(t.core) && t.key !in STOP

    /** Nouns that are stop words in a name ("site", "contact") but fine in a path: kalmia.ca/contact. */
    private val PATH_NOUNS = setOf("contact", "site", "page", "web", "lien", "courriel", "adresse", "mail", "email", "link", "address", "website", "url")

    private fun isPathWord(t: Tok): Boolean =
        t.lead.isEmpty() && LABEL.matches(t.core) && (t.key !in STOP || t.key in PATH_NOUNS)

    private class Joiner(val symbol: String, val tokens: Int)

    private fun joinerAt(toks: List<Tok>, i: Int, w: Words): Joiner? {
        val t = toks.getOrNull(i) ?: return null
        if (t.lead.isNotEmpty() || t.trail.isNotEmpty()) return null
        val k = t.key
        val n1 = toks.getOrNull(i + 1)?.takeIf { it.lead.isEmpty() }?.key
        val n2 = toks.getOrNull(i + 2)?.takeIf { it.lead.isEmpty() }?.key
        return when {
            k in w.dot -> Joiner(".", 1)
            k == "underscore" || k == "soulignement" -> Joiner("_", 1)
            k == "tiret" && n1 == "du" && n2 == "bas" -> Joiner("_", 3)
            k == "tiret" && n1 == "bas" -> Joiner("_", 2)
            k == "trait" && n1 == "d'union" -> Joiner("-", 2)
            k in w.hyphen -> Joiner("-", 1)
            else -> null
        }
    }

    private fun slashAt(toks: List<Tok>, i: Int): Int {
        val t = toks.getOrNull(i) ?: return 0
        if (t.lead.isNotEmpty() || t.trail.isNotEmpty()) return 0
        if (t.key == "slash") return 1
        if (t.key == "barre" && toks.getOrNull(i + 1)?.takeIf { it.lead.isEmpty() }?.key == "oblique") return 2
        return 0
    }

    private class Side(val text: String, val next: Int, val joiners: Int)

    /** name (joiner name)* ; a unit is 1 to 3 plain words glued together ("coiffe rimouski" → coifferimouski). */
    private fun parseSide(toks: List<Tok>, from: Int, w: Words): Side? {
        val sb = StringBuilder()
        var idx = from
        var afterDot = false
        var joiners = 0
        while (true) {
            if (idx >= toks.size) return null
            if (afterDot && Normalize.fold(toks[idx].core) in TLDS && toks[idx].lead.isEmpty()) {
                sb.append(Normalize.fold(toks[idx].core))
                idx++
            } else {
                var count = 0
                while (idx < toks.size && count < 3 && isLabelWord(toks[idx])) {
                    // A token that is already an address piece (kalmia.ca) is never glued to other words.
                    val dotted = '.' in toks[idx].core
                    if (dotted && count > 0) break
                    sb.append(Normalize.fold(toks[idx].core))
                    count++
                    idx++
                    if (toks[idx - 1].trail.isNotEmpty() || dotted) break
                }
                if (count == 0) return null
            }
            if (toks[idx - 1].trail.isNotEmpty()) return Side(sb.toString(), idx, joiners)
            val j = joinerAt(toks, idx, w)
            if (j == null) return Side(sb.toString(), idx, joiners)
            sb.append(j.symbol)
            joiners++
            idx += j.tokens
            afterDot = j.symbol == "."
        }
    }

    private fun isDomain(s: String) = '.' in s && '@' !in s && s.substringAfterLast('.') in TLDS && !s.endsWith("..") && !s.startsWith(".")

    private fun startOk(toks: List<Tok>, i: Int): Boolean {
        if (i == 0) return true
        val p = toks[i - 1]
        return p.key in LEAD || p.trail.isNotEmpty()
    }

    private class Built(val core: String, val end: Int)

    private fun parseAddress(toks: List<Tok>, i: Int, w: Words): Built? {
        if (!startOk(toks, i)) return null
        val side = parseSide(toks, i, w) ?: return null
        val atTok = toks.getOrNull(side.next)
        if (atTok != null && atTok.key in w.at && atTok.lead.isEmpty() && atTok.trail.isEmpty() &&
            toks[side.next - 1].trail.isEmpty() && side.text.isNotEmpty() && '.' != side.text.last()
        ) {
            val dom = parseSide(toks, side.next + 1, w) ?: return null
            if (!isDomain(dom.text)) return null
            return Built(side.text + "@" + dom.text, dom.next)
        }
        if (!isDomain(side.text) || side.joiners == 0) return null
        var text = side.text
        var end = side.next
        while (toks[end - 1].trail.isEmpty()) {
            val n = slashAt(toks, end)
            if (n == 0) break
            val path = toks.getOrNull(end + n) ?: break
            if (!isPathWord(path)) break
            text += "/" + Normalize.fold(path.core)
            end += n + 1
        }
        return Built(text, end)
    }

    private fun parseAddresses(toks: MutableList<Tok>, w: Words) {
        var i = 0
        while (i < toks.size) {
            val built = parseAddress(toks, i, w)
            if (built != null) {
                val first = toks[i]
                val merged = Tok(first.sep, first.lead, built.core, toks[built.end - 1].trail)
                repeat(built.end - i) { toks.removeAt(i) }
                toks.add(i, merged)
                i++
                continue
            }
            // An address already written: only the spoken path after it is rewritten.
            val t = toks[i]
            if (t.lead.isEmpty() && DOMAIN_TOKEN.matches(t.core) && t.trail.isEmpty()) {
                var end = i + 1
                var text = t.core
                while (toks[end - 1].trail.isEmpty()) {
                    val n = slashAt(toks, end)
                    if (n == 0) break
                    val path = toks.getOrNull(end + n) ?: break
                    if (!isPathWord(path)) break
                    text += "/" + Normalize.fold(path.core)
                    end += n + 1
                }
                if (end > i + 1) {
                    val merged = Tok(t.sep, t.lead, text, toks[end - 1].trail)
                    repeat(end - i) { toks.removeAt(i) }
                    toks.add(i, merged)
                }
            }
            i++
        }
    }

    // ----- unresolved cases -----

    private fun domainAhead(toks: List<Tok>, from: Int, w: Words): Boolean {
        val t = toks.getOrNull(from) ?: return false
        if (DOMAIN_TOKEN.matches(t.core)) return true
        // name point com, within a few tokens
        for (k in from until minOf(toks.size - 1, from + 4)) {
            if (toks[k].key in w.dot && toks[k + 1].key in TLDS) return true
        }
        return false
    }

    /** A spoken cue left in the output that looks like part of an address. */
    private fun leftoverCue(toks: List<Tok>, w: Words): Boolean {
        for ((i, t) in toks.withIndex()) {
            val prev = toks.getOrNull(i - 1)
            val prevIsName = prev != null && prev.trail.isEmpty() && (prev.key !in STOP)
            when {
                t.key in w.dot && t.trail.isEmpty() && toks.getOrNull(i + 1)?.key in TLDS && prevIsName -> return true
                t.key in w.at && t.trail.isEmpty() && (w !== EN || prevIsName) && domainAhead(toks, i + 1, w) -> return true
            }
        }
        return false
    }
}
