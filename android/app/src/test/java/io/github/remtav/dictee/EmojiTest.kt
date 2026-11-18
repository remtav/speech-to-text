package io.github.remtav.dictee

import io.github.remtav.dictee.ime.emoji.Emoji
import io.github.remtav.dictee.ime.emoji.EmojiCatalogue
import io.github.remtav.dictee.ime.emoji.EmojiCategory
import io.github.remtav.dictee.ime.emoji.EmojiCluster
import io.github.remtav.dictee.ime.emoji.RecentEmojis
import io.github.remtav.dictee.settings.AppSettings
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** Issue #80: the emoji panel's pure parts: what Backspace removes, the data, the recents and their storage. */
class EmojiTest {
    private val flag = "🇨🇦" // 🇨🇦
    private val flagFr = "🇫🇷" // 🇫🇷
    private val family = "👨‍👩‍👧‍👦" // 👨‍👩‍👧‍👦
    private val thumbsMedium = "👍🏽" // 👍🏽
    private val heart = "❤️" // ❤️
    private val rainbowFlag = "🏳️‍🌈" // 🏳️‍🌈
    private val keycap = "1️⃣" // 1️⃣
    private val smile = "😀" // 😀
    private val familyTone = "👨🏽‍👩🏽‍👧🏽"

    private fun last(text: String) = EmojiCluster.lengthBefore(text)

    @Test
    fun anEmojiOfSeveralCharactersIsRemovedWhole() {
        for (e in listOf(flag, family, thumbsMedium, heart, rainbowFlag, keycap, smile, familyTone)) {
            assertEquals("whole $e", e.length, last("Salut $e"))
            assertEquals("alone $e", e.length, last(e))
        }
    }

    @Test
    fun onlyTheLastEmojiGoes() {
        assertEquals(flagFr.length, last("$flag$flagFr")) // two flags in a row: two pairs, not one pair shifted
        assertEquals(flag.length, last("$flagFr$flag"))
        assertEquals(smile.length, last("$family$smile"))
        assertEquals(family.length, last("$smile$family"))
        assertEquals(thumbsMedium.length, last("$thumbsMedium$thumbsMedium")) // the tone stays with its hand
    }

    @Test
    fun threeFlagsInARowKeepTheirPairs() {
        assertEquals(flag.length, last(flag + flagFr + flag))
        assertEquals(flag.length, last(flagFr + flagFr + flag))
    }

    @Test
    fun otherCharactersAreLeftToTheField() {
        for (s in listOf("", "a", "Bonjour", "été", "123", "!", "? ", "#", "*", "5", "é")) assertEquals("«$s»", 0, last(s))
        assertEquals(0, last("$smile "))
        assertEquals(0, last("$smile a"))
    }

    @Test
    fun aDigitIsAnEmojiOnlyWithItsKeycap() {
        assertEquals(0, last("1"))
        assertEquals(0, last("1︎")) // text presentation
        assertEquals(keycap.length, last("a$keycap"))
    }

    @Test
    fun aJoinerWithoutAnEmojiAfterIsNotSwallowed() {
        assertEquals(0, last("$smile‍"))
        assertEquals(0, last("a‍"))
    }

    @Test
    fun aLongTextDoesNotTouchTheCount() {
        val text = "x".repeat(500) + family
        assertEquals(family.length, last(text))
    }

    // --- data

    private val tsv = listOf(
        "Smileys & Emotion\t😀\tgrinning face\tvisage rieur",
        "Flags\t$flag\tflag: Canada\tdrapeau : Canada",
        "Flags\t🫨\tshaking face\tvisage qui tremble",
        "Unknown group\t😀\tx\ty",
        "broken line",
    )

    @Test
    fun theCatalogueKeepsOnlyWhatTheFontCanDraw() {
        val all = EmojiCatalogue.parse(tsv.asSequence())
        assertEquals(3, all.size)
        val drawable = EmojiCatalogue.parse(tsv.asSequence()) { it != "🫨" }
        assertEquals(2, drawable.size)
        assertEquals(listOf(flag), drawable.byCategory.getValue(EmojiCategory.FLAGS).map { it.glyph })
    }

    @Test
    fun theNameFollowsTheLanguageOfThePhone() {
        val e = Emoji("😀", "grinning face", "visage rieur")
        assertEquals("visage rieur", e.name(Locale.CANADA_FRENCH))
        assertEquals("visage rieur", e.name(Locale.FRANCE))
        assertEquals("grinning face", e.name(Locale.US))
        assertEquals("grinning face", e.name(Locale("es")))
    }

    @Test
    fun theShippedFileHasEveryGroupAndNamesInBothLanguages() {
        val file = File(System.getProperty("dictee.assetsDir")!!, EmojiCatalogue.ASSET)
        val catalogue = EmojiCatalogue.parse(file.readLines(Charsets.UTF_8).asSequence().filter { it.isNotBlank() })
        for (c in EmojiCategory.entries) assertTrue("${c.key} is empty", catalogue.byCategory.getValue(c).size >= 80)
        assertTrue("${catalogue.size} emojis", catalogue.size > 1_800)
        // multi-character emojis are there, whole
        for (e in listOf(flagFr, family, rainbowFlag, keycap)) assertNotNull("missing $e", catalogue.find(e))
        for ((_, list) in catalogue.byCategory) for (e in list) {
            assertTrue("no English name for ${e.glyph}", e.nameEn.isNotBlank())
            assertTrue("no French name for ${e.glyph}", e.nameFr.isNotBlank())
        }
        assertEquals("drapeau : France", catalogue.find(flagFr)!!.nameFr)
        assertEquals("flag: France", catalogue.find(flagFr)!!.nameEn)
    }

    // --- recents

    @Test
    fun recentsComeFirstWithoutDuplicatesAndAreCapped() {
        var r = emptyList<String>()
        r = RecentEmojis.add(r, "a")
        r = RecentEmojis.add(r, "b")
        r = RecentEmojis.add(r, "a")
        assertEquals(listOf("a", "b"), r)
        for (i in 0 until 50) r = RecentEmojis.add(r, "e$i")
        assertEquals(RecentEmojis.MAX, r.size)
        assertEquals("e49", r.first())
    }

    @Test
    fun recentsSurviveTheKeyboardClosing() {
        val prefs = MemoryPrefs()
        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        }
        fun settings() = AppSettings(context, defaultServerUrl = "")
        assertEquals(emptyList<String>(), settings().recentEmojis)
        settings().recentEmojis = RecentEmojis.add(settings().recentEmojis, family)
        settings().recentEmojis = RecentEmojis.add(settings().recentEmojis, flag)
        // a new AppSettings is what the keyboard has after being closed and opened again
        assertEquals(listOf(flag, family), settings().recentEmojis)
    }
}
