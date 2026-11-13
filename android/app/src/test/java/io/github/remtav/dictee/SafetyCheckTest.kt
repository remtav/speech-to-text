package io.github.remtav.dictee

import io.github.remtav.dictee.text.RuleCleaner
import io.github.remtav.dictee.text.SafetyCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #15, user report: with a phone model and the local LLM clean-up, a
 * correctly transcribed "friperie" came out altered. The guard must reject any
 * output whose words are not the input's words, unchanged and in order.
 */
class SafetyCheckTest {

    /** A long segment (VAD segments often are): the old guard tolerated a few "novel" words there. */
    private fun long(w: String) =
        "Samedi matin, Julie et moi sommes allées chercher des manteaux chauds, des bottes usagées, " +
            "une vieille lampe et quelques livres près de la $w avant de rentrer souper tranquillement."

    private fun short(w: String) = "Je passe à la $w demain."

    private fun assertRejected(raw: String, out: String) {
        val v = SafetyCheck.check(raw, out)
        assertFalse("accepted: «$raw» -> «$out»", v.ok)
    }

    private fun assertAccepted(raw: String, out: String) {
        val v = SafetyCheck.check(raw, out)
        assertTrue("rejected (${v.rule}): «$raw» -> «$out»", v.ok)
    }

    // ---- The reported word ----

    @Test fun friperieAlteredInLongSegmentIsRejected() {
        for (w in listOf("fripperie", "friperies", "friterie", "fripière", "frisperie", "boutique", "fripe", "fripérie")) {
            assertRejected(long("friperie"), long(w))
        }
    }

    @Test fun friperieAlteredInShortSegmentIsRejected() {
        for (w in listOf("fripperie", "friperies", "friterie", "fripière", "boutique", "fripérie")) {
            assertRejected(short("friperie"), short(w))
        }
    }

    @Test fun friperieDroppedIsRejected() {
        assertRejected(long("friperie"), long("friperie").replace(" de la friperie", ""))
        assertRejected(short("friperie"), "Je passe demain.")
    }

    @Test fun friperieUnchangedIsAccepted() {
        assertAccepted("Euh, " + long("friperie").replaceFirstChar { it.lowercase() }, long("friperie"))
        assertAccepted(short("friperie"), short("friperie"))
    }

    // ---- Rare or local words, altered in plausible ways ----

    private val altered = mapOf(
        "dépanneur" to listOf("dépanneurs", "depanneur", "dépanneuse", "épicerie", "dépaneur"),
        "poudrerie" to listOf("poudreries", "poudrière", "tempête", "poudrerié", "poudrerei"),
        "tuque" to listOf("tuques", "toque", "tuqué", "bonnet", "tuke"),
        "magasiner" to listOf("magasinier", "magasines", "faire les courses", "magaziner", "magasiné"),
        "clavardage" to listOf("clavardages", "chat", "bavardage", "clavardâge", "clavarder"),
        "pourvoirie" to listOf("pourvoiries", "pourvoyeur", "pourvoierie", "auberge", "pourvoïrie"),
        "cégep" to listOf("cegep", "cégeps", "collège", "cégèp", "CÉGEP"),
        "courriel" to listOf("courriels", "courrier", "e-mail", "couriel", "courriél"),
        "achalandage" to listOf("achalandages", "affluence", "achalendage", "achalandé", "àchalandage"),
        "tabagie" to listOf("tabagies", "tabac", "tabagisme", "tabagié", "tabagi"),
        "Rimouski" to listOf("Rimouskis", "Rimousky", "Rimouské", "Matane", "Rimoski"),
        "Chicoutimi" to listOf("Chicoutimis", "Chicoutime", "Saguenay", "Chicoutimí", "Chikoutimi"),
    )

    @Test fun localWordsAlteredAreRejected() {
        for ((word, variants) in altered) {
            for (v in variants) {
                assertRejected(long(word), long(v))
                assertRejected(short(word), short(v))
            }
        }
    }

    @Test fun localWordsUnchangedAreAccepted() {
        for (word in altered.keys) {
            assertAccepted("euh " + long(word), long(word))
            assertAccepted(short(word), short(word))
        }
    }

    // ---- Other ways a word could slip through ----

    @Test fun reorderedWordsAreRejected() {
        assertRejected("Je passe à la friperie demain avec Julie.", "Demain je passe à la friperie avec Julie.")
    }

    @Test fun wordReplacedByStopwordIsRejected() {
        assertRejected("Je passe à la friperie demain.", "Je passe à la demain.")
        assertRejected("On va magasiner à Rimouski.", "On va à Rimouski.")
    }

    @Test fun midWordCaseChangeIsRejected() {
        assertRejected("On va au cégep demain.", "On va au CÉGEP demain.")
    }

    @Test fun hyphenatedWordChangedIsRejected() {
        assertRejected("Est-ce qu'on peut-être aller au dépanneur ?", "Est-ce qu'on peut être aller au dépanneur ?")
    }

    @Test fun contentDroppedWithoutCorrectionIsRejected() {
        assertRejected(
            "On se voit mardi à la réunion avec Paul pour parler du budget de la friperie.",
            "On se voit mardi à la réunion avec Paul pour parler du budget.",
        )
    }

    // ---- Legitimate clean-ups stay accepted ----

    @Test fun hesitationsRemoved() {
        assertAccepted("Je pense euh qu'on peut euh aller à la friperie.", "Je pense qu'on peut aller à la friperie.")
        assertAccepted("Um so I think, uh, the plan works.", "So I think the plan works.")
    }

    @Test fun repetitionRemoved() {
        assertAccepted("On va, on va à la friperie demain.", "On va à la friperie demain.")
        assertAccepted("Le le chat dort au dépanneur.", "Le chat dort au dépanneur.")
        assertAccepted("Je pense que que la tuque est chaude.", "Je pense que la tuque est chaude.")
    }

    @Test fun falseStartRemoved() {
        assertAccepted("On va au, on va à la friperie.", "On va à la friperie.")
    }

    @Test fun selfCorrectionRemoved() {
        assertAccepted(
            "Euh, on se voit lundi, non, mardi à la réunion avec Paul pour parler du budget.",
            "On se voit mardi à la réunion avec Paul pour parler du budget.",
        )
        assertAccepted("On part à Rimouski, pardon, à Chicoutimi demain.", "On part à Chicoutimi demain.")
        assertAccepted("Let's meet Monday, no, Tuesday at noon.", "Let's meet Tuesday at noon.")
        assertAccepted(
            "Envoie-moi un texto, je veux dire, un courriel ce soir.",
            "Envoie-moi un courriel ce soir.",
        )
    }

    @Test fun caseAndPunctuationOnlyAreAccepted() {
        assertAccepted("on se voit au cégep demain", "On se voit au cégep demain.")
        assertAccepted("je passe à la friperie demain est-ce que tu viens", "Je passe à la friperie demain. Est-ce que tu viens ?")
        // Lowering a capital mid-sentence is refused (independent review): "j'ai vu Pierre" -> "pierre"
        // is a changed word; at a sentence start it is a case fix.
        assertRejected("Je passe à la Friperie demain.", "Je passe à la friperie demain.")
        assertAccepted("Friperie demain.", "friperie demain.")
        assertAccepted("c’est la friperie", "C'est la friperie.")
    }

    @Test fun correctionWordWithoutCommaIsNotACorrection() {
        // "non" in the flow of speech is content, not a spoken correction: removing the words before it is a change.
        assertRejected("Je pense que lundi non plus.", "Je pense que plus.")
    }

    @Test fun correctionRemovingANegationIsRejected() {
        assertEquals(
            "negation",
            SafetyCheck.check("Je ne veux pas venir à la fête ce soir avec vous tous.", "Je veux venir à la fête ce soir avec vous tous.").rule,
        )
    }

    @Test fun pipelineInputIsRuleCleaned() {
        // LlmCleaner checks the LLM output against RuleCleaner's output: a word altered there would also be caught.
        val base = RuleCleaner.clean("Euh, je passe à la friperie demain, euh, avec Julie.")
        assertAccepted(base, base)
        assertRejected(base, base.replace("friperie", "fripperie"))
    }
}
