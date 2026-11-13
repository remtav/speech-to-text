package io.github.remtav.dictee.reading

import io.github.remtav.dictee.text.Normalize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.text.Normalizer

class ReadingTextTest {
    private val text = ReadingTestSupport.material.text

    @Test
    fun theCurrentVersionIsTheFileAndIsPresentOnce() {
        assertEquals(ReadingText.CURRENT_VERSION, text.version)
        assertTrue(ReadingText.VERSION_ID.matches(text.version))
        val raw = ReadingTestSupport.readAsset(ReadingText.textAsset())
        assertEquals(1, raw.lines().count { it.startsWith("version:") })
    }

    @Test
    fun everyVersionIdIsUniqueAndMatchesItsFileName() {
        val dir = File(ReadingTestSupport.assetsDir, ReadingText.ASSET_DIR)
        val texts = dir.listFiles { f -> f.name.endsWith(".txt") }!!.toList()
        assertTrue(texts.isNotEmpty())
        val versions = texts.map { f -> ReadingText.parse(f.readText()).version.also { assertEquals(f.nameWithoutExtension, it) } }
        assertEquals(versions.size, versions.toSet().size)
        // The quick texts of the installation test (issue #36a) have no table of accepted spellings.
        for (v in versions.filterNot { it.startsWith("rapide-") }) {
            val eq = Equivalences.parse(File(dir, "$v.equivalences.tsv").readText())
            assertEquals(v, eq.version)
        }
    }

    @Test
    fun aboutTwoHundredWordsInShortParagraphs() {
        assertTrue("${text.wordCount} mots", text.wordCount in 180..240)
        assertTrue(text.paragraphs.size >= 6)
        for (i in text.paragraphs.indices) {
            val n = text.wordCount(i)
            assertTrue("paragraphe $i : $n mots", n in 10..ReadingText.MAX_PARAGRAPH_WORDS)
            assertTrue("paragraphe $i : ${text.readingSeconds(i)} s", text.readingSeconds(i) < ReadingText.MAX_PARAGRAPH_SECONDS)
        }
    }

    @Test
    fun theTextIsNfcAndHasNoDigits() {
        for (p in text.paragraphs) {
            assertEquals(Normalizer.normalize(p, Normalizer.Form.NFC), p)
            assertTrue(p, p.none { it.isDigit() })
        }
    }

    @Test
    fun theStartingListIsCovered() {
        val all = Normalize.words(text.paragraphs.joinToString(" ")).joinToString(" ") { it.first.lowercase() }
        val expected = listOf(
            // vocabulary (plural or verb form where the story needs it)
            "dépanneur", "magasiner", "fin de semaine", "tuque", "mitaines", "char", "cellulaire", "courriel",
            "clavardage", "poudrerie", "bancs de neige", "cabane à sucre", "déjeuner", "dîner", "soupe",
            "blonde", "chum", "piastres", "friperie",
            // expressions
            "de la misère", "faisait frette", "à matin", "pantoute", "tiguidou", "c'est correct", "jasé",
            "niaiser", "achaler", "tannée", "pogné", "tantôt",
            // place names
            "rimouski", "saguenay", "chicoutimi", "trois rivières", "abitibi", "gaspésie", "lac saint jean",
            "sherbrooke", "magog", "hochelaga",
            // pronunciation
            "tu", "dire", "petite", "mardi", "père", "fête", "neige", "pâtes",
            // spoken contractions
            "chu", "y'a", "t'sais",
        )
        for (e in expected) assertTrue("« $e » absent", Regex("(^|[ '])${Regex.escape(e)}( |$)").containsMatchIn(all))
    }

    @Test
    fun contractionsStayInTwoSentencesAtMost() {
        val sentences = text.paragraphs.flatMap { it.split(Regex("(?<=[.!?»])\\s+")) }
        val withContraction = sentences.filter { s -> Normalize.words(s).any { it.first.lowercase() in setOf("chu", "y'a", "t'sais") } }
        assertTrue(withContraction.joinToString(" | "), withContraction.size in 1..2)
    }

    @Test
    fun parseRejectsMalformedFiles() {
        for (bad in listOf("Un paragraphe sans version.", "version: lecture-qc-1\n", "version: Lecture 1\n\nTexte.",
            "version: a-1\nversion: a-2\n\nTexte.")) {
            try { ReadingText.parse(bad); fail(bad) } catch (_: IllegalArgumentException) {}
        }
        val ok = ReadingText.parse("# commentaire\nversion: essai-2\n\nUne ligne\nqui continue.\n\n\nDeux.")
        assertEquals(listOf("Une ligne qui continue.", "Deux."), ok.paragraphs)
    }
}
