package io.github.remtav.dictee

import io.github.remtav.dictee.text.SafetyCheck
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #15, lot 3, independent review: LLM outputs written to slip a changed or
 * dropped word past [SafetyCheck] (hidden as a repetition, a correction, a case or
 * punctuation fix). Each one must be rejected; the clean-ups next to them must pass.
 */
class SafetyCheckAdversarialTest {

    private fun assertRejected(raw: String, out: String) {
        val v = SafetyCheck.check(raw, out)
        assertFalse("accepted: «$raw» -> «$out»", v.ok)
    }

    private fun assertAccepted(raw: String, out: String) {
        val v = SafetyCheck.check(raw, out)
        assertTrue("rejected (${v.rule}): «$raw» -> «$out»", v.ok)
    }

    // ---- A doubled word that is not a stutter ----

    @Test fun reflexivePronounIsNotARepetition() {
        assertRejected("Vous vous lavez les mains avant le souper.", "Vous lavez les mains avant le souper.")
        assertRejected("Nous nous voyons demain au dépanneur.", "Nous voyons demain au dépanneur.")
        assertRejected("We had had enough of it by then.", "We had enough of it by then.")
        assertRejected("I know that that is true.", "I know that is true.")
    }

    @Test fun spelledNumbersAreNotRepetitions() {
        assertRejected("Mon numéro est cinq cinq cinq, un deux trois quatre.", "Mon numéro est cinq, un deux trois quatre.")
        assertRejected("Call me at five five five, one two three.", "Call me at five, one two three.")
    }

    @Test fun spelledLettersAreNotRepetitions() {
        assertRejected("c o i f f e r i m o u s k i point com", "c o i f e r i m o u s k i point com")
        assertRejected("Mon courriel, c'est a n n e arobase kalmia point ca.", "Mon courriel, c'est a n e arobase kalmia point ca.")
    }

    @Test fun repetitionAcrossASentenceEndIsADroppedWord() {
        assertRejected("Je vois Marie. Marie arrive demain.", "Je vois Marie. Arrive demain.")
        assertRejected("Je vois Marie. Marie arrive demain.", "Je vois. Marie arrive demain.")
    }

    // ---- A correction that is not one ----

    @Test fun markerEndingASentenceIsNotACorrection() {
        assertRejected("Il pleut, enfin. Je reste à la maison.", "Je reste à la maison.")
        assertRejected("It works, sorry. Let's go home.", "Let's go home.")
    }

    // ---- Case, numbers, punctuation ----

    @Test fun properNounLowercasedMidSentenceIsRejected() {
        assertRejected("J'ai vu Pierre au dépanneur.", "J'ai vu pierre au dépanneur.")
        assertRejected("Il a acheté des actions d'Apple hier.", "Il a acheté des actions d'apple hier.")
    }

    @Test fun numberSeparatorChangeIsRejected() {
        assertRejected("J'en prends 3, 5 kilos.", "J'en prends 3,5 kilos.")
        assertRejected("J'en prends 3,5 kilos.", "J'en prends 3, 5 kilos.")
        assertRejected("Rendez-vous à 10:30.", "Rendez-vous à 10, 30.")
    }

    @Test fun questionTurnedIntoStatementIsRejected() {
        assertRejected("Tu viens demain à la friperie?", "Tu viens demain à la friperie.")
        assertRejected("Are you coming tomorrow?", "Are you coming tomorrow.")
    }

    @Test fun addedOrDuplicatedWordIsRejected() {
        assertRejected("Le chat dort.", "Le chat chat dort.")
        assertRejected("Le chat dort.", "Le gros chat dort.")
    }

    @Test fun lookalikeCharactersAreRejected() {
        assertRejected("Mon cœur bat vite.", "Mon coeur bat vite.")
        assertRejected("Je vais à Trois-Rivières.", "Je vais à Trois Rivières.")
        assertRejected("Je vais à l'arbre.", "Je vais à larbre.")
    }

    // ---- Legitimate clean-ups still pass ----

    @Test fun legitimateCleanupsStillPass() {
        assertAccepted("Je… je pense que c'est bon.", "Je pense que c'est bon.")
        assertAccepted("C'est très très bon.", "C'est très bon.")
        assertAccepted("Bon. Donc on part demain.", "Bon, donc on part demain.")
        assertAccepted("j'ai vu pierre au dépanneur", "J'ai vu Pierre au dépanneur.")
        assertAccepted("tu viens demain", "Tu viens demain ?")
        assertAccepted("Tu viens demain ?", "Tu viens demain?")
        assertAccepted("On part à Rimouski, pardon, à Chicoutimi demain.", "On part à Chicoutimi demain.")
        assertAccepted("J'en prends 3,5 kilos, euh, demain.", "J'en prends 3,5 kilos demain.")
        assertAccepted("Le café est chaud.", "Le café est chaud.")
        assertAccepted("Une une maison au bord du lac.", "Une maison au bord du lac.")
    }
}
