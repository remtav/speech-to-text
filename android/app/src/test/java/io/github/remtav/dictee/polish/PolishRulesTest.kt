package io.github.remtav.dictee.polish

import io.github.remtav.dictee.text.polish.PolishRules
import io.github.remtav.dictee.text.polish.RuleSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each rule, each trap (issue #15, step 3.2). The rules are not wired into the app. */
class PolishRulesTest {
    private fun fr(s: String) = PolishRules.polish(s, "fr")
    private fun en(s: String) = PolishRules.polish(s, "en")

    // ----- spoken web addresses -----

    @Test fun pointComBecomesDotCom() {
        assertEquals("Va sur coifferimouski.com.", fr("Va sur coiffe rimouski point com."))
        assertEquals("Le formulaire est sur quebec.ca.", fr("Le formulaire est sur québec point ca."))
    }

    @Test fun severalDotsAndSpelledWww() {
        assertEquals("Renouvelle ton permis sur saaq.gouv.qc.ca.", fr("Renouvelle ton permis sur SAAQ point gouv point QC point ca."))
        assertEquals("Tout est sur www.hydroquebec.com.", fr("Tout est sur w w w point hydro québec point com."))
    }

    @Test fun slashAfterAnAddress() {
        assertEquals("Inscris-toi sur quebec.ca/emploi", fr("Inscris-toi sur québec point ca barre oblique emploi"))
        assertEquals("Check out github.com/kalmia.", en("Check out github dot com slash Kalmia."))
        assertEquals("Go to kalmia.ca/contact.", en("Go to kalmia dot ca slash contact."))
        assertEquals("Go to kalmea.ca/contact.", en("Go to kalmea.ca slash contact."))
        assertEquals("Va sur example.org/docs/fr", fr("Va sur example point org slash docs slash fr"))
    }

    @Test fun hyphenAndUnderscoreInsideAnAddress() {
        assertEquals("Va sur ville-quebec.ca", fr("Va sur ville tiret québec point ca"))
        assertEquals("Écris à jean-luc@gmail.com", fr("Écris à jean trait d'union luc arobase gmail point com"))
        assertEquals("Écris à marie_tremblay@gmail.com", fr("Écris à marie tiret du bas tremblay arobase gmail point com"))
        assertEquals("Write to jean_luc@gmail.com", en("Write to jean underscore luc at gmail dot com"))
        assertEquals("Write to jean-luc@gmail.com", en("Write to jean dash luc at gmail dot com"))
    }

    // ----- spoken e-mail addresses -----

    @Test fun emailFrench() {
        assertEquals("Écris-moi à remi@kalmia.ca.", fr("Écris-moi à rémi arobase kalmia point ca."))
        assertEquals("Mon courriel c'est marie.tremblay@gmail.com.", fr("Mon courriel c'est marie point tremblay arobase gmail point com."))
        assertEquals("Envoie la facture à info@coifferimouski.com", fr("Envoie la facture à info arobase coiffe rimouski point com"))
        assertEquals("Écris à info@uqar.ca.", fr("Écris à info arobase UQAR point ca."))
        assertEquals("Mon adresse, c'est jean.tremblay@videotron.ca.", fr("Mon adresse, c'est jean.tremblay arobase vidéotron.ca."))
    }

    @Test fun emailEnglish() {
        assertEquals("Email me at remi@kalmia.ca.", en("Email me at remi at kalmia dot ca."))
        assertEquals("Write to support@github.com.", en("Write to support at GitHub dot com."))
        assertEquals("My email is marie.gagnon@gmail.com.", en("My email is Marie dot Gagnon at gmail.com."))
    }

    // ----- spaces inside a written address -----

    @Test fun spacesInsideAWrittenAddress() {
        assertEquals("Écris à remi@kalmia.ca merci", fr("Écris à remi @ kalmia . ca merci"))
        assertEquals("Écris à remi@kalmia.ca merci", fr("Écris à remi@ kalmia.ca merci"))
        assertEquals("Va sur www.kalmia.ca demain", fr("Va sur www . kalmia . ca demain"))
        assertEquals("Mon courriel: a.b@x.ca. Merci", fr("Mon courriel: a.b@x.ca. Merci")) // nothing to fix
    }

    // ----- letters spelled out -----

    @Test fun spelledLettersWithACue() {
        assertEquals("Mon nom de famille s'appelle Gagnon.", fr("Mon nom de famille s'appelle G, A G N O N."))
        assertEquals("Ça s'écrit Bouchard.", fr("Ça s'écrit B O U C H A R D."))
        assertEquals("That's spelled Kalmia.", en("That's spelled K-A-L-M-I-A."))
        assertEquals("My last name is spelled Tremblay.", en("My last name is spelled T-R-E-M-B-L-A-Y."))
    }

    @Test fun spelledLettersBeforeADotWord() {
        assertEquals("coifferimouski.com.", fr("c, o, i, f, f, e, r, i, m, o, u, s, k, i, point com."))
        assertEquals("coifferimouski.com", fr("C-O-I-F-F-E-R-I-M-O-U-S-K-I.com"))
    }

    @Test fun postalCodes() {
        assertEquals("Le code postal est G5L 3A1.", fr("Le code postal est G 5 L, 3 A 1."))
        assertEquals("Le code postal est G5L 3A1.", fr("Le code postal est G5L, 3A1."))
        assertEquals("Le code postal est G5L 3A1.", fr("Le code postal est G5L3A1."))
        assertEquals("Le code postal, c'est H2X 1Y4.", fr("Le code postal, c'est H 2 X, 1 Y 4."))
        assertEquals("My postal code is G5L 3A1.", en("My postal code is G 5 L, 3 A 1."))
    }

    // ----- traps: nothing may change -----

    private val traps = listOf(
        "fr" to "On a trouvé un point commun entre les deux projets.",
        "fr" to "Mets un point final à cette histoire.",
        "fr" to "On s'est arrêté à la barre oblique du terrain.",
        "fr" to "Trace une barre oblique au bout de la ligne.",
        "fr" to "On a un plan B si le plan A ne marche pas.",
        "fr" to "J'ai eu un C en maths et un A en français.",
        "fr" to "Prends de la vitamine C pendant l'hiver.",
        "fr" to "Le symbole arobase vient d'une ancienne unité de mesure.",
        "fr" to "Il dit arobase au lieu de a commercial.",
        "fr" to "Ils ont fait faillite pendant la bulle des point com.",
        "fr" to "Le point de presse est à dix heures.",
        "fr" to "Le point de rencontre est à côté du dépanneur.",
        "fr" to "Mon site est super kalmia.ca",
        "fr" to "Les options sont A, B, C, D.",
        "en" to "That company died in the dot-com bubble.",
        "en" to "That company died in the dot com bubble.",
        "en" to "She got an A in math and a B in French.",
        "en" to "Take vitamin D in the winter.",
        "en" to "Plan B is to take the bus.",
        "en" to "Be there at nine on the dot.",
        "en" to "He said at least three times that it was fine.",
        "en" to "The letter C stands for carbon.",
        "en" to "Use a forward slash between the two dates.",
        "en" to "Meet me at noon at the corner.",
        "fr" to "",
        "fr" to "   ",
    )

    @Test fun trapsStayUntouchedAndUnflagged() {
        for ((lang, s) in traps) {
            val r = PolishRules.assess(s, lang)
            assertEquals("changed: $s", s, r.text)
            assertEquals("flagged: $s", RuleSignal.UNCHANGED, r.signal)
        }
    }

    @Test fun uncuedSpelledRunIsNotJoinedButFlagged() {
        val r = PolishRules.assess("Elle a G A G N O N comme nom.", "fr")
        assertEquals("Elle a G A G N O N comme nom.", r.text)
        assertEquals(RuleSignal.PROBABLE_CASE, r.signal)
    }

    @Test fun alreadyCorrectAddressesAreLeftAlone() {
        for (s in listOf("Tout est sur www.hydroquebec.com.", "Écris à Rémi@Kalmia.ca", "Le code postal est G5L 3A1.", "Go to kalmia.ca/contact.")) {
            assertEquals(s, fr(s))
        }
    }

    @Test fun rulesAreIdempotentOnTheirOutput() {
        for (s in listOf("Va sur coiffe rimouski point com.", "rémi arobase kalmia point ca", "G 5 L, 3 A 1", "Check out github dot com slash Kalmia.")) {
            val once = fr(s)
            assertEquals(once, fr(once))
        }
    }

    @Test fun languageOfTheWordsMatters() {
        // "dot" and "at" are English words, "point" and "arobase" French ones.
        assertEquals("Va sur kalmia dot ca", fr("Va sur kalmia dot ca"))
        assertEquals("Go to kalmia point ca", en("Go to kalmia point ca"))
    }

    // ----- the "probable case" signal, for the step that combines rules and a model -----

    @Test fun signalUnchangedCorrectedProbable() {
        assertEquals(RuleSignal.UNCHANGED, PolishRules.assess("Bonjour tout le monde.", "fr").signal)
        val fixed = PolishRules.assess("Va sur québec point ca.", "fr")
        assertEquals(RuleSignal.CORRECTED, fixed.signal)
        assertTrue(fixed.hasAddress)
    }

    @Test fun signalProbableWhenACueIsLeftUnresolved() {
        // a name is there but no lead-in word: the rules refuse to guess
        val a = PolishRules.assess("J'ai visité l'entreprise point com hier.", "fr")
        assertEquals("J'ai visité l'entreprise point com hier.", a.text)
        assertEquals(RuleSignal.PROBABLE_CASE, a.signal)
        // an uncued run of letters
        val b = PolishRules.assess("C O I F F E R I M O U S K Icom", "fr")
        assertEquals(RuleSignal.PROBABLE_CASE, b.signal)
        // arobase followed by something the rules cannot parse
        val c = PolishRules.assess("Envoie la facture infoirobase coiffremouski.com", "fr")
        assertEquals(RuleSignal.UNCHANGED, c.signal)
        assertTrue(c.hasAddress)
        val d = PolishRules.assess("Écris à info arobase coiffremouski.com", "fr")
        assertEquals(RuleSignal.CORRECTED, d.signal)
        assertEquals("Écris à info@coiffremouski.com", d.text)
    }

    @Test fun probableCaseCanComeWithPartialCorrection() {
        val r = PolishRules.assess("G5L, 3A1 et A B C D E F ensuite", "fr")
        assertEquals("G5L 3A1 et A B C D E F ensuite", r.text)
        // consecutive alphabet is a list, not a spelling: not flagged
        assertFalse(r.signal == RuleSignal.PROBABLE_CASE)
    }

    @Test fun neverThrows() {
        for (s in listOf("@", ".", " . ", "point com", "arobase", "point point com", "à point ca", "-", "A-", "a @ b . c", "\u0000", "w w w point")) {
            PolishRules.assess(s, "fr")
            PolishRules.assess(s, "en")
        }
    }
}
