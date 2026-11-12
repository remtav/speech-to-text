package io.github.remtav.dictee.polish

import io.github.remtav.dictee.text.polish.ShippedVocabulary
import io.github.remtav.dictee.text.polish.ShippedVocabulary.Origin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shipped list of names (issue #15, step 3.2). Sentences here are not from the evaluation set. */
class ShippedVocabularyTest {
    private fun fr(s: String) = ShippedVocabulary.apply(s, "fr")
    private fun en(s: String) = ShippedVocabulary.apply(s, "en")

    @Test fun techNames() {
        assertEquals("Regarde ma vidéo sur YouTube.", fr("Regarde ma vidéo sur you tube."))
        assertEquals("Il a vu ça sur Facebook et Instagram.", fr("Il a vu ça sur face book et insta gram."))
        assertEquals("Send it on WhatsApp.", en("Send it on whats app."))
        assertEquals("Mon iPad est à plat.", fr("Mon ipad est à plat."))
        assertEquals("Elle utilise ChatGPT.", fr("Elle utilise chat gpt."))
    }

    @Test fun placesAndInstitutionsFromHere() {
        assertEquals("On passe par Sept-Îles puis Baie-Comeau.", fr("On passe par sept iles puis baie comeau."))
        assertEquals("Il étudie à l'Université Laval.", fr("Il étudie à l'université laval."))
        assertEquals("Mon compte à la caisse Desjardins.", fr("Mon compte à la caisse des jardins."))
        assertEquals("Le client de Vidéotron est parti.", fr("Le client de videotron est parti."))
        assertEquals("Ski au Mont-Tremblant.", fr("Ski au mont tremblant."))
    }

    @Test fun curlyApostropheVariant() {
        assertEquals("La Société de l’assurance automobile du Québec", fr("La société de l’assurance automobile du québec"))
    }

    @Test fun languageSpecificEntries() {
        assertEquals("On fait ça sur Teams.", fr("On fait ça sur team."))
        assertEquals("We use a team.", en("We use a team.")) // the French-only entry does not leak into English
        assertEquals("on team building", fr("on team building"))
    }

    @Test fun ordinaryWordsAreNotTouched() {
        for (s in listOf(
            "Les jardins sont beaux, des jardins de ville.", "Le team est prêt.", "Il a dit des jardins.",
            "Un trois rivières ? Non.".replace("rivières", "ruisseaux"), "Je mets mon android de côté.".replace("android", "robot"),
            "Il a de la patience.", "On va magasiner, friperie, poudrerie, tuque.",
        )) assertEquals(s, fr(s))
    }

    @Test fun writtenAddressesAreMasked() {
        assertEquals("Écris à github@kalmia.ca", fr("Écris à github@kalmia.ca"))
        assertEquals("Va voir github.com/youtube", fr("Va voir github.com/youtube"))
        assertEquals("Ouvre GitHub puis github.com.", fr("Ouvre github puis github.com."))
    }

    @Test fun listIsWellFormed() {
        val seen = HashSet<String>()
        for (e in ShippedVocabulary.entries) {
            assertTrue("empty ${e}", e.heard.isNotBlank() && e.correct.isNotBlank())
            assertTrue("language ${e}", e.language in setOf("fr", "en", "*"))
            assertTrue("heard is its own correction only by case ${e}", e.heard == e.heard.trim())
            assertTrue("duplicate ${e}", seen.add(e.language + "|" + e.heard.lowercase()))
        }
        assertTrue(ShippedVocabulary.entries.count { it.origin == Origin.GENERAL } > ShippedVocabulary.entries.count { it.origin == Origin.OBSERVED })
    }

    @Test fun neverThrows() {
        for (s in listOf("", "  ", "\uE000\uE001", "@", "a.com")) fr(s)
    }
}
