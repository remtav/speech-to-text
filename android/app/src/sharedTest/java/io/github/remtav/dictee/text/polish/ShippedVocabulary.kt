package io.github.remtav.dictee.text.polish

import io.github.remtav.dictee.text.Vocabulary

/**
 * Shipped list of common tech names and names from here (Québec cities,
 * regions, institutions, companies), each with the badly transcribed forms a
 * model plausibly produces, mapped to the correct form (issue #15, step 3.2,
 * approach 2). Applied with the existing [Vocabulary] corrections mechanism
 * (case-insensitive, on word boundaries). NOT wired into the dictation
 * pipeline: nothing calls it in the app.
 *
 * Rules for an entry: a real, well-known name; a heard form that is not an
 * ordinary word (so no "team → Teams", no "des jardins → Desjardins" alone: a
 * context word is part of the form when the bare form is a word). Written
 * addresses (anything with `@` or a dot ending in com, ca…) are masked first,
 * so a correction never rewrites inside `github.com`.
 *
 * Honesty about the evaluation set: the list was written knowing the set
 * (jeu-evaluation.tsv). Forms tagged [Origin.OBSERVED] were added because they
 * appear literally in a raw transcription of the set (synthetic voice, or
 * written by hand there); they prove little beyond the set. [Origin.GENERAL]
 * forms are the ones one would list for these names anyway (split brand
 * names, missing accent or hyphen, case only, usual phonetic spellings).
 */
object ShippedVocabulary {
    enum class Origin { GENERAL, OBSERVED }

    /** [language]: `fr`, `en` or `*` (both). */
    data class Entry(val heard: String, val correct: String, val language: String, val origin: Origin)

    private fun general(correct: String, language: String, vararg heard: String) =
        heard.map { Entry(it, correct, language, Origin.GENERAL) }

    private fun observed(correct: String, language: String, vararg heard: String) =
        heard.map { Entry(it, correct, language, Origin.OBSERVED) }

    val entries: List<Entry> = buildList {
        // ----- tech and everyday brands -----
        addAll(general("GitHub", "*", "git hub", "git-hub", "guit hub", "guithub", "gitub", "github"))
        addAll(observed("GitHub", "*", "j-hub", "jhub"))
        addAll(general("WhatsApp", "*", "whats app", "what's app", "whatsapp", "whatsap", "ouatsapp"))
        addAll(general("iPhone", "*", "iphone", "i phone"))
        addAll(general("iPad", "*", "ipad", "i pad"))
        addAll(general("MacBook", "*", "macbook", "mac book"))
        addAll(general("YouTube", "*", "youtube", "you tube", "you-tube"))
        addAll(general("Facebook", "*", "facebook", "face book"))
        addAll(general("Instagram", "*", "instagram", "insta gram"))
        addAll(general("LinkedIn", "*", "linkedin", "linked in"))
        addAll(general("TikTok", "*", "tiktok", "tik tok"))
        addAll(general("Snapchat", "*", "snapchat", "snap chat"))
        addAll(general("Gmail", "*", "g mail"))
        addAll(general("Google Docs", "*", "google docs"))
        addAll(general("Google Drive", "*", "google drive"))
        addAll(general("Google Maps", "*", "google maps"))
        addAll(general("Dropbox", "*", "dropbox", "drop box"))
        addAll(observed("Dropbox", "*", "drawbox"))
        addAll(general("PowerPoint", "*", "powerpoint", "power point"))
        addAll(general("FaceTime", "*", "facetime", "face time"))
        addAll(general("Best Buy", "*", "bestbuy", "best buy"))
        addAll(general("Microsoft Teams", "*", "microsoft team", "microsoft teams"))
        addAll(general("sur Teams", "fr", "sur team"))
        addAll(general("Visual Studio Code", "*", "visual studio code"))
        addAll(general("JavaScript", "*", "javascript", "java script"))
        addAll(general("ChatGPT", "*", "chatgpt", "chat gpt"))
        addAll(general("OpenAI", "*", "openai", "open ai"))
        addAll(general("Android", "*", "android"))
        addAll(general("Bluetooth", "*", "bluetooth"))
        addAll(general("Netflix", "*", "netflix"))
        addAll(general("Spotify", "*", "spotify"))
        addAll(general("Wikipédia", "fr", "wikipedia"))

        // ----- places in Québec -----
        addAll(general("Rimouski", "*", "rimousqui", "rimouskie", "rimousky"))
        addAll(observed("Rimouski", "*", "et mouski", "imouski", "emuski", "remelsky", "rimelski"))
        addAll(general("Chicoutimi", "*", "chicoutimie", "chikoutimi", "chicoutimy"))
        addAll(observed("Chicoutimi", "*", "chikouti", "chikutimi"))
        addAll(general("Saguenay", "*", "saguené", "saguenet"))
        addAll(observed("Saguenay", "*", "sacney", "sacné"))
        addAll(general("Trois-Rivières", "*", "trois rivieres", "trois-rivieres"))
        addAll(general("Abitibi", "*", "abitiby", "abitibie"))
        addAll(observed("Abitibi", "*", "habite tibit"))
        addAll(general("Gaspésie", "*", "gaspésie", "gaspesie"))
        addAll(general("Sherbrooke", "*", "sherbrook", "cherbrooke", "cherbrook"))
        addAll(observed("Sherbrooke", "*", "chebouk", "chebok"))
        addAll(general("Hochelaga", "*", "ochelaga", "hochlaga"))
        addAll(observed("Hochelaga", "*", "auchas", "chlagar"))
        addAll(general("Magog", "*", "magog"))
        addAll(observed("Magog", "*", "magogue"))
        addAll(general("Mont-Sainte-Anne", "*", "mont sainte anne", "mont sainte-anne"))
        addAll(observed("Mont-Sainte-Anne", "*", "mont sainte-annie"))
        addAll(general("Rivière-du-Loup", "*", "rivière du loup", "riviere du loup"))
        addAll(general("Lac-Saint-Jean", "*", "lac saint jean", "lac saint-jean")) // debatable: the lake itself is « lac Saint-Jean »; the region is Lac-Saint-Jean
        addAll(general("Lac-Mégantic", "*", "lac mégantic", "lac megantic"))
        addAll(general("Mont-Tremblant", "*", "mont tremblant"))
        addAll(general("Mont-Royal", "*", "mont royal"))
        addAll(general("Vieux-Québec", "*", "vieux québec", "vieux quebec"))
        addAll(general("Vieux-Montréal", "*", "vieux montréal", "vieux montreal"))
        addAll(general("Sept-Îles", "*", "sept iles", "sept îles"))
        addAll(general("Baie-Comeau", "*", "baie comeau"))
        addAll(general("Val-d'Or", "*", "val d'or", "val dor"))
        addAll(general("Rouyn-Noranda", "*", "rouyn noranda"))
        addAll(general("Notre-Dame-de-Grâce", "*", "notre dame de grâce", "notre-dame-de-grace"))
        addAll(general("Côte-des-Neiges", "*", "côte des neiges", "cote des neiges"))
        addAll(general("Bas-Saint-Laurent", "*", "bas saint laurent", "bas-saint-laurent"))
        addAll(general("Côte-Nord", "*", "côte nord", "cote nord"))
        addAll(general("Chaudière-Appalaches", "*", "chaudière appalaches"))
        addAll(general("Saint-Jean-sur-Richelieu", "*", "saint jean sur richelieu"))
        addAll(general("Drummondville", "*", "drummondville"))
        addAll(general("Gatineau", "*", "gatineau"))
        addAll(general("Granby", "*", "granby"))
        addAll(general("Longueuil", "*", "longueuil"))
        addAll(general("Matane", "*", "matane"))
        addAll(general("Charlevoix", "*", "charlevoix"))
        addAll(general("Mauricie", "*", "mauricie"))
        addAll(general("Outaouais", "*", "outaouais"))
        addAll(general("Estrie", "*", "estrie"))
        addAll(general("Laurentides", "*", "laurentides"))
        addAll(general("Montérégie", "*", "montérégie", "monteregie"))
        addAll(general("Lanaudière", "*", "lanaudière", "lanaudiere"))

        // ----- institutions and companies from here -----
        addAll(general("Hydro-Québec", "*", "hydro québec", "hydro quebec", "hydro-quebec"))
        addAll(observed("Hydro-Québec", "*", "iroqebac"))
        addAll(general("Desjardins", "*", "desjardin"))
        addAll(general("caisse Desjardins", "*", "caisse des jardins"))
        addAll(general("Société de l'assurance automobile du Québec", "fr", "société de l'assurance automobile du québec"))
        addAll(general("SAAQ", "fr", "saaq"))
        addAll(general("SAQ", "fr", "saq"))
        addAll(general("RAMQ", "fr", "ramq"))
        addAll(general("CNESST", "fr", "cnesst"))
        addAll(general("Revenu Québec", "fr", "revenu québec", "revenu quebec"))
        addAll(general("Loto-Québec", "*", "loto québec", "loto quebec"))
        addAll(general("Cirque du Soleil", "*", "cirque du soleil"))
        addAll(general("Vidéotron", "*", "videotron", "vidéotron"))
        addAll(general("Bombardier", "*", "bombardier"))
        addAll(general("Couche-Tard", "*", "couche tard"))
        addAll(general("Jean Coutu", "*", "jean coutu", "jean-coutu"))
        addAll(general("Tim Hortons", "*", "tim horton", "tim hortons"))
        addAll(general("Canadian Tire", "*", "canadian tire"))
        addAll(general("Banque Nationale", "fr", "banque nationale"))
        addAll(general("Centre Bell", "*", "centre bell"))
        addAll(general("Place des Arts", "*", "place des arts"))
        addAll(general("Université du Québec", "fr", "université du québec"))
        addAll(general("Université Laval", "fr", "université laval"))
        addAll(general("Université de Montréal", "fr", "université de montréal"))
        addAll(general("Université McGill", "fr", "université mcgill"))
        addAll(general("UQAM", "*", "uqam"))
        addAll(general("UQAR", "*", "uqar"))
        addAll(general("UQAC", "*", "uqac"))
        addAll(general("HEC Montréal", "*", "hec montréal", "hec montreal"))
        addAll(general("Polytechnique Montréal", "*", "polytechnique montréal"))
    }

    private val ADDRESS_LIKE = Regex("\\S*@\\S*|\\S*\\.(?:com|ca|org|net|edu|io|info|gov)(?![\\p{L}\\p{N}])\\S*", RegexOption.IGNORE_CASE)
    private val PRIVATE_USE_BASE = 0xE000

    private val cache = HashMap<String, Vocabulary>()

    /**
     * The corrections that apply to [language] (`fr`, `en`; anything else gets French and common entries).
     * [origins] lets the evaluation measure the list without the forms observed in the evaluation set.
     */
    @Synchronized
    fun forLanguage(language: String, origins: Set<Origin> = Origin.values().toSet()): Vocabulary {
        val lang = if (language.trim().lowercase().startsWith("en")) "en" else "fr"
        return cache.getOrPut(lang + origins.sorted().joinToString("")) {
            val pairs = entries.filter { (it.language == "*" || it.language == lang) && it.origin in origins }
                .flatMap { e ->
                    val base = e.heard to e.correct
                    if ('\'' in e.heard) listOf(base, e.heard.replace('\'', '’') to e.correct.replace('\'', '’')) else listOf(base)
                }
                .sortedByDescending { it.first.length } // longest form first
            Vocabulary(emptyList(), pairs)
        }
    }

    /** Applies the shipped corrections, leaving written addresses alone. Never throws. */
    fun apply(text: String, language: String, origins: Set<Origin> = Origin.values().toSet()): String {
        if (text.isBlank()) return text
        return try {
            val masked = ArrayList<String>()
            val safe = ADDRESS_LIKE.replace(text) { m ->
                masked += m.value
                (PRIVATE_USE_BASE + masked.size - 1).toChar().toString()
            }
            val fixed = forLanguage(language, origins).apply(safe)
            val sb = StringBuilder()
            for (c in fixed) {
                val k = c.code - PRIVATE_USE_BASE
                if (k in masked.indices) sb.append(masked[k]) else sb.append(c)
            }
            sb.toString()
        } catch (e: RuntimeException) {
            text
        }
    }
}
