package io.github.remtav.dictee.ime.emoji

import android.content.Context
import android.graphics.Paint
import java.util.Locale

/** One emoji of the panel, with the name TalkBack reads (issue #80). */
data class Emoji(val glyph: String, val nameEn: String, val nameFr: String) {
    /** The name in the language of the phone: French, or English for any other language. */
    fun name(locale: Locale = Locale.getDefault()): String = if (locale.language == "fr") nameFr else nameEn
}

/** Groups of the panel, in the order of the tabs. [key] is the group name in `assets/emojis/emojis.tsv`. */
enum class EmojiCategory(val key: String, val tab: String) {
    SMILEYS("Smileys & Emotion", "😀"),
    PEOPLE("People & Body", "👋"),
    ANIMALS("Animals & Nature", "🐻"),
    FOOD("Food & Drink", "🍔"),
    TRAVEL("Travel & Places", "🚗"),
    ACTIVITIES("Activities", "⚽"),
    OBJECTS("Objects", "💡"),
    SYMBOLS("Symbols", "❤"),
    FLAGS("Flags", "🏁"),
    ;

    companion object {
        fun forKey(key: String): EmojiCategory? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The emojis of `assets/emojis/emojis.tsv` (built by `tools/emojis/build_emojis.py` from Unicode data and CLDR names),
 * kept only when the phone's font can draw them: an older Android would show an empty square for a recent emoji.
 */
class EmojiCatalogue(val byCategory: Map<EmojiCategory, List<Emoji>>) {
    private val all: Map<String, Emoji> = byCategory.values.flatten().associateBy { it.glyph }

    fun find(glyph: String): Emoji? = all[glyph]

    val size: Int get() = all.size

    companion object {
        const val ASSET = "emojis/emojis.tsv"

        /** Reads the lines of the asset; [drawable] says whether the font has a glyph for a sequence. */
        fun parse(lines: Sequence<String>, drawable: (String) -> Boolean = { true }): EmojiCatalogue {
            val groups = LinkedHashMap<EmojiCategory, MutableList<Emoji>>()
            for (c in EmojiCategory.entries) groups[c] = ArrayList()
            for (line in lines) {
                val f = line.split('\t')
                if (f.size < 4) continue
                val category = EmojiCategory.forKey(f[0]) ?: continue
                if (!drawable(f[1])) continue
                groups.getValue(category) += Emoji(f[1], f[2], f[3])
            }
            return EmojiCatalogue(groups)
        }

        fun load(context: Context): EmojiCatalogue {
            val paint = Paint()
            return context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { r ->
                parse(r.lineSequence().filter { it.isNotBlank() }.toList().asSequence()) { paint.hasGlyph(it) }
            }
        }
    }
}

/** The emojis used last, most recent first (issue #80). Pure, so it can be tested; stored by `AppSettings.recentEmojis`. */
object RecentEmojis {
    const val MAX = 32

    /** [glyph] moved to the front of [recents], the list kept to [MAX]. */
    fun add(recents: List<String>, glyph: String): List<String> = (listOf(glyph) + recents.filter { it != glyph }).take(MAX)

    // Emojis never contain a space or a line break: one space separates them in the preferences.
    fun encode(recents: List<String>): String = recents.joinToString(" ")

    fun decode(stored: String?): List<String> = stored?.split(' ')?.filter { it.isNotEmpty() }?.take(MAX) ?: emptyList()
}
