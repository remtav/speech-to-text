package io.github.remtav.dictee.cleanup.eval

import io.github.remtav.dictee.cleanup.eval.RelaxedSafetyCheck.Options
import io.github.remtav.dictee.cleanup.eval.RelaxedSafetyCheck.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The relaxed guard of step 3.3 (issue #15), with simulated LLM outputs, good
 * and bad. Every "bad" output here is something a small LLM can plausibly
 * produce; each must be rejected. The guard is evaluation-only code.
 */
class RelaxedSafetyCheckTest {
    private val names = listOf(
        "GitHub", "WhatsApp", "iPhone", "Dropbox", "Teams", "Google Docs", "Best Buy", "Rimouski", "Chicoutimi",
        "Lac-Saint-Jean", "Trois-Rivières", "Gaspésie", "Desjardins", "Hydro-Québec", "Magog",
        "Université du Québec à Rimouski", "Société de l'assurance automobile du Québec",
    )
    private val lex = Options(lexicon = names)
    private val near = Options(lexicon = names, allowLexiconNear = true)

    private fun ok(input: String, output: String, options: Options = Options(), vararg rules: Rule) {
        val v = RelaxedSafetyCheck.check(input, output, options)
        assertTrue("should accept « $input » → « $output » : ${v.reason}", v.ok)
        if (rules.isNotEmpty()) assertEquals(rules.toSet(), v.changes.map { it.rule }.toSet())
    }

    private fun rejected(input: String, output: String, options: Options = near) {
        // Bad outputs are tested with the MOST permissive options (lexicon + near match): if they are
        // rejected there, they are rejected with every configuration.
        val v = RelaxedSafetyCheck.check(input, output, options)
        assertFalse("should reject « $input » → « $output » (steps: ${v.steps})", v.ok)
    }

    // ------------------------------------------------------------ accepted

    @Test fun identityIsAlwaysAccepted() {
        for (s in listOf(
            "Mon code est sur J-Hub, je t'envoie le lien sur WhatsApp.",
            "J'ai trouvé ce manteau dans une friperie de Rimouski.",
            "Tout est sur www.hydroquebec.com.",
            "On fait la réunion sur Team ou sur Zoom ?",
            "Ils ont fait faillite pendant la bulle des point com.",
            "« Bonjour », a-t-elle dit (vraiment) !",
        )) {
            val v = RelaxedSafetyCheck.check(s, s, near)
            assertTrue("$s : ${v.reason}", v.ok)
            assertTrue(v.changes.isEmpty())
        }
    }

    @Test fun mixedCaseNamesWithoutLexicon() {
        ok("J'ai poussé le correctif sur git hub hier soir.", "J'ai poussé le correctif sur GitHub hier soir.", Options(), Rule.MIXED_CASE)
        ok("Installe l'application sur ton iphone.", "Installe l'application sur ton iPhone.", Options(), Rule.MIXED_CASE)
        ok("Send it to me on whats app.", "Send it to me on WhatsApp.", Options(), Rule.MIXED_CASE)
        ok("Open the slides in power point.", "Open the slides in PowerPoint.", Options(), Rule.MIXED_CASE)
        ok("Mon code est sur github. Je t'envoie le lien sur whatsapp.", "Mon code est sur GitHub. Je t'envoie le lien sur WhatsApp.", Options(), Rule.MIXED_CASE)
    }

    @Test fun hyphenJoinWithoutLexicon() {
        ok("Le train passe à Rivière du Loup vers midi.", "Le train passe à Rivière-du-Loup vers midi.", Options(), Rule.HYPHEN_JOIN)
        ok("We visited Abitibi and Lac Saint Jean.", "We visited Abitibi and Lac-Saint-Jean.", Options(), Rule.HYPHEN_JOIN)
    }

    @Test fun spelling() {
        ok("Mon nom de famille s'appelle G, A G N O N.", "Mon nom de famille s'appelle Gagnon.", Options(), Rule.SPELLING)
        ok("My last name is spelled T-R-E-M-B-L-A-Y.", "My last name is spelled Tremblay.", Options(), Rule.SPELLING)
        ok("Ça s'écrit B O U C H A R D.", "Ça s'écrit Bouchard.", Options(), Rule.SPELLING)
        ok("That's spelled K-A-L-M-I-A.", "That's spelled kalmia.", Options(), Rule.SPELLING)
    }

    @Test fun postalCodes() {
        ok("Le code postal est G5L, 3A1.", "Le code postal est G5L 3A1.", Options(), Rule.POSTAL_CODE)
        ok("Le code postal est G cinq L trois A un.", "Le code postal est G5L 3A1.", Options(), Rule.POSTAL_CODE)
        ok("Le code postal est G5L3A1.", "Le code postal est G5L 3A1.", Options(), Rule.POSTAL_CODE)
    }

    @Test fun spokenAddresses() {
        ok("Va sur coiffe rimouski point com.", "Va sur coifferimouski.com.", Options(), Rule.ADDRESS)
        ok("Le formulaire est sur québec point ca.", "Le formulaire est sur quebec.ca.", Options(), Rule.ADDRESS)
        ok("Renouvelle ton permis sur SAAQ point gouv point QC point ca.", "Renouvelle ton permis sur saaq.gouv.qc.ca.", Options(), Rule.ADDRESS)
        ok("Check out github dot com slash Kalmia.", "Check out github.com/kalmia.", Options(), Rule.ADDRESS)
        ok("Écris à info arobase UQAR point ca.", "Écris à info@uqar.ca.", Options(), Rule.ADDRESS)
        ok("Mon adresse, c'est jean.tremblay arobase vidéotron.ca.", "Mon adresse, c'est jean.tremblay@videotron.ca.", Options(), Rule.ADDRESS)
        ok("Write to support at GitHub dot com.", "Write to support@github.com.", Options(), Rule.ADDRESS)
        ok("My email is Marie dot Gagnon at gmail.com.", "My email is marie.gagnon@gmail.com.", Options(), Rule.ADDRESS)
        ok("C-O-I-F-F-E-R-I-M-O-U-S-K-I.com", "coifferimouski.com", Options(), Rule.ADDRESS)
        ok("Va voir coiffe Rimouski.com", "Va voir coifferimouski.com", Options(), Rule.ADDRESS)
        ok("Le site c'est r e m i arobase kalmia point ca.", "Le site c'est remi@kalmia.ca.", Options(), Rule.ADDRESS)
        ok("Mets un trait d'union : jean tiret luc point ca.", "Mets un trait d'union : jean-luc.ca.", Options(), Rule.ADDRESS)
    }

    @Test fun lexiconForm() {
        ok("Partage le document dans google docs.", "Partage le document dans Google Docs.", lex, Rule.LEXICON_FORM)
        ok("On passe par la gaspésie cet été.", "On passe par la Gaspésie cet été.", lex, Rule.LEXICON_FORM)
        ok("She moved to Trois Rivieres last year.", "She moved to Trois-Rivières last year.", lex, Rule.LEXICON_FORM)
        ok("Mon compte est à la caisse des jardins.", "Mon compte est à la caisse Desjardins.", lex, Rule.LEXICON_FORM)
        ok("J'ai acheté un nouvel iPhone chez BestBuy", "J'ai acheté un nouvel iPhone chez Best Buy", lex, Rule.LEXICON_FORM)
        ok("Le colloque a lieu à l'université du Québec à Rimouski.", "Le colloque a lieu à l'Université du Québec à Rimouski.", lex, Rule.LEXICON_FORM)
        ok(
            "La société de l'assurance Automobile du Québec a fermé le bureau de Matane.",
            "La Société de l'assurance automobile du Québec a fermé le bureau de Matane.", lex, Rule.LEXICON_FORM,
        )
    }

    @Test fun lexiconNearOnlyWhenEnabled() {
        val input = "Je l'ai mis sur Google Drive et dans Drawbox."
        val output = "Je l'ai mis sur Google Drive et dans Dropbox."
        ok(input, output, near, Rule.LEXICON_NEAR)
        assertFalse(RelaxedSafetyCheck.check(input, output, lex).ok)
        ok("Je pars pour Imouski demain matin.", "Je pars pour Rimouski demain matin.", near, Rule.LEXICON_NEAR)
        // "et Mouski" → "Rimouski" would swallow "et": "Mouski" alone is as close to the name, so "et" is a
        // neighbour, not part of the name. Rejected on purpose (a dropped word is the risk being guarded).
        rejected("Je pars pour et Mouski demain matin.", "Je pars pour Rimouski demain matin.")
        rejected("On se parle sur face time ce soir.", "On se parle sur FaceTime soir.", Options(listOf("FaceTime"), true))
        rejected("Le bureau de Matane.", "Le bureau Matane.", Options(listOf("Matane"), true))
        // Budget: (letters + 2) / 4 from 5 letters up, so "Magogue" (2 edits from the 5-letter "Magog") stays out.
        rejected("Il habite à Magogue depuis deux ans.", "Il habite à Magog depuis deux ans.")
        // An address of the lexicon, said slightly wrong, only with the near match.
        val addr = Options(listOf("kalmia.ca"), allowLexiconNear = true)
        ok("Le site c'est kelmia.ca.", "Le site c'est kalmia.ca.", addr, Rule.LEXICON_NEAR)
        assertFalse(RelaxedSafetyCheck.check("Le site c'est kelmia.ca.", "Le site c'est kalmia.ca.", Options(listOf("kalmia.ca"))).ok)
    }

    @Test fun combinedRewritesInOneSegment() {
        ok(
            "Mon code est sur git hub, écris à info arobase uqar point ca.",
            "Mon code est sur GitHub, écris à info@uqar.ca.", Options(), Rule.MIXED_CASE, Rule.ADDRESS,
        )
    }

    // ------------------------------------------------------------ rejected

    /** The user's case: a correct rare word "corrected" by the LLM. Never accepted, whatever the options. */
    @Test fun rareWordHypercorrectionIsRejected() {
        rejected("J'ai trouvé ce manteau dans une friperie de Rimouski.", "J'ai trouvé ce manteau dans une fripperie de Rimouski.")
        rejected("Avec la poudrerie, on voyait rien sur l'autoroute.", "Avec la poudrerie, on ne voyait rien sur l'autoroute.")
        rejected("Le point de rencontre est à côté du dépanneur.", "Le point de rencontre est à côté du dépanneur du coin.")
        rejected("Elle finit son cégep au printemps.", "Elle finit son CEGEP au printemps.")
        rejected("Elle finit son cégep au printemps.", "Elle finit son Cégep au printemps.")
        rejected("Arrête de m'achaler avec ça.", "Arrête de m'achaler avec cela.")
        rejected("J'ai rien compris pantoute.", "Je n'ai rien compris du tout.")
        rejected("Mon chum a stationné son char devant le dépanneur.", "Mon chum a stationné sa voiture devant le dépanneur.")
        rejected("On va magasiner en fin de semaine.", "On va magasiner en Fin de semaine.")
        rejected("On va magasiner en fin de semaine.", "On va magasiner en FIN de semaine.")
        rejected("Il y a beaucoup d'achalandage à la tabagie le samedi.", "Il y a beaucoup d'achalandage à la tabagie le samedi")
        rejected("Mets ta tuque, il fait frette dehors.", "Mets ta tuque, il fait froid dehors.")
    }

    @Test fun hallucinationsAreRejected() {
        rejected("On va à la cabane à sucre dimanche.", "On va à la cabane à sucre dimanche. C'est une belle tradition québécoise.")
        rejected("On va à la cabane à sucre dimanche.", "Voici le texte corrigé : On va à la cabane à sucre dimanche.")
        rejected("On va à la cabane à sucre dimanche.", "Sure! On va à la cabane à sucre dimanche.")
        rejected("Quelle heure est-il?", "Il est dix heures.")
        rejected("On va à la cabane à sucre dimanche.", "On va à la cabane à sucre dimanche.\nOn va à la cabane à sucre dimanche.")
        rejected("Je pars pour Imouski demain matin.", "Je pars pour Rimouski demain matin avec GitHub.")
    }

    @Test fun translationsAreRejected() {
        rejected("Mets ta tuque, il fait frette dehors.", "Put on your toque, it's freezing outside.")
        rejected("Send it to me on whats app.", "Envoie-le-moi sur WhatsApp.")
        rejected("Le plan B, c'est de partir plus tôt.", "Plan B is to leave earlier.")
    }

    @Test fun reformulationsAreRejected() {
        rejected("On a jasé pendant une heure au téléphone.", "Nous avons discuté pendant une heure au téléphone.")
        rejected("On a jasé pendant une heure au téléphone.", "Au téléphone, on a jasé pendant une heure.")
        rejected("On a trouvé un point commun entre les deux projets.", "On a trouvé un point commun entre ces deux projets.")
        rejected("Mais un point final à cette histoire.", "Mets un point final à cette histoire.") // fixing an untargeted error is still a change
        rejected("Be there at nine on the dot.", "Be there at 9 on the dot.")
        rejected("Le point de presse est à dix heures.", "Le point de presse n'est pas à dix heures.")
    }

    @Test fun punctuationAndSpacingChangesAreRejected() {
        rejected("Le site, c'est kalmia.ca.", "Le site c'est kalmia.ca.")
        rejected("On fait la réunion sur Teams ou sur Zoom?", "On fait la réunion sur Teams ou sur Zoom ?")
        rejected("Mets un point final à cette discussion.", "Mets un point final à cette discussion !")
        rejected("Mets un point final à cette discussion.", "Mets un point final à cette discussion. »")
        rejected("Mets un point final à cette discussion.", "« Mets un point final à cette discussion. »", Options())
    }

    @Test fun inventedOrAlteredAddressesAreRejected() {
        rejected("Va voir coiffrimouski.com", "Va voir coifferimouski.com", lex) // a letter added (no lexicon address)
        rejected("Tout est sur hydroquebec.com.", "Tout est sur www.hydroquebec.com.") // www. added
        rejected("Le site, c'est Kalmia.ka", "Le site, c'est kalmia.ca", lex) // ka → ca: a replaced letter
        rejected("Email me at remy@kelmia.com", "Email me at remi@kalmia.ca", lex)
        rejected("Mon courriel c'est marie point tremblay à 18 gmail com.", "Mon courriel c'est marie.tremblay@gmail.com.")
        rejected("Écris-moi à remy.scalmia.ca", "Écris-moi à remi@kalmia.ca")
        rejected("Envoie la facture à Marie.", "Envoie la facture à marie@gmail.com.")
        rejected("Go to kalmea.ca slash contact.", "Go to kalmia.ca/contact.", lex)
        rejected("Le formulaire est sur québec point ca.", "Le formulaire est sur https://quebec.ca.")
        rejected("Le formulaire est sur québec point ca.", "Le formulaire est sur quebec.com.")
    }

    @Test fun controlTrapsAreNotTurnedIntoAddresses() {
        rejected("Ils ont fait faillite pendant la bulle des point com.", "Ils ont fait faillite pendant la bulle des.com.")
        rejected("Ils ont fait faillite pendant la bulle des point com.", "Ils ont fait faillite pendant la bulle.des.com.")
        rejected("Le symbole arobase vient d'une ancienne unité de mesure.", "Le symbole @ vient d'une ancienne unité de mesure.")
        rejected("On s'est arrêté à la barre oblique du terrain.", "On s'est arrêté à la / du terrain.")
        rejected("Mets un point final à cette discussion.", "Mets un . final à cette discussion.")
        rejected("That company died in the dot-com bubble.", "That company died in the .com bubble.")
        rejected("Envoie-le à la maison point com.", "Envoie-le à la.maison.com.")
    }

    @Test fun numbersAreNotChanged() {
        rejected("Le code postal est G cinq L trois A deux.", "Le code postal est G5L 3A1.")
        rejected("Il y a trois rivières.", "Il y a 3 rivières.")
        rejected("Rendez-vous au 125 rue Saint-Joseph.", "Rendez-vous au 152 rue Saint-Joseph.")
    }

    @Test fun wordReplacementsOutsideTheLexiconAreRejected() {
        rejected("Mon code est sur J-Hub, je t'envoie le lien.", "Mon code est sur GitLab, je t'envoie le lien.")
        rejected("Il travaille chez Hydro Québec à Sacney.", "Il travaille chez Hydro-Québec à Sydney.")
        rejected("Je pars pour Imouski demain matin.", "Je pars pour Rimouski demain matin.", lex) // near match off
        rejected("We drove to Remelsky.", "We drove to Rimouski.", lex)
    }

    @Test fun spellingMustKeepEveryLetter() {
        rejected("Mon nom s'écrit G A G N O N.", "Mon nom s'écrit Gagnion.")
        rejected("Mon nom s'écrit G A G N O N.", "Mon nom s'écrit Gagno.")
        rejected("Ça s'écrit B E L A N G E R.", "Ça s'écrit Bélanger.") // an accent is a letter the speaker did not spell
        rejected("J'ai eu un C en maths et un A en français.", "J'ai eu un Ca en maths et un A en français.")
    }

    @Test fun plainCapitalisationNeedsTheLexicon() {
        rejected("On passe par la gaspésie cet été.", "On passe par la Gaspésie cet été.", Options())
        rejected("Le train passe à rivière du loup vers midi.", "Le train passe à Rivière-du-Loup vers midi.", Options())
        rejected("Partage le document dans google docs.", "Partage le document dans Google Docs.", Options())
    }

    @Test fun dropsAndEmptyOutputsAreRejected() {
        rejected("On va à la cabane à sucre dimanche.", "")
        rejected("On va à la cabane à sucre dimanche.", "   ")
        rejected("On va à la cabane à sucre dimanche.", "On va à la cabane dimanche.")
        rejected("On va à la cabane à sucre dimanche.", "On va à la cabane à sucre.")
    }

    // ------------------------------------------------- known, documented limits

    /**
     * Known limits of the relaxed guard (DECISIONS.md, step 3.3, "Risques"): these are allowed
     * rewrites by construction, so the guard cannot tell them from a real fix. Pinned here so a
     * change of behaviour is noticed.
     */
    @Test fun knownLimitsAreAcceptedByConstruction() {
        // A lexicon name that is also an ordinary phrase: the guard cannot see the context.
        ok("On a visité des jardins.", "On a visité Desjardins.", lex, Rule.LEXICON_FORM)
        // With the near match on, an ordinary word close to a name can become the name.
        ok("Toute la team était là.", "Toute la Teams était là.", near, Rule.LEXICON_NEAR)
        // Two first names joined into a compound one.
        ok("Pierre Jean est arrivé.", "Pierre-Jean est arrivé.", Options(), Rule.HYPHEN_JOIN)
    }

    @Test fun failureReasonsNameThePlace() {
        val v = RelaxedSafetyCheck.check("Une friperie de Rimouski.", "Une fripperie de Rimouski.")
        assertFalse(v.ok)
        assertTrue(v.reason!!, v.reason!!.contains("friperie") && v.reason!!.contains("fripperie"))
        val added = RelaxedSafetyCheck.check("On y va.", "On y va. Merci.")
        assertTrue(added.reason!!, added.reason!!.contains("ajouté"))
        val dropped = RelaxedSafetyCheck.check("On y va demain.", "On y va.")
        assertFalse(dropped.ok)
    }

    @Test fun tokenizerSplitsElisionsAndKeepsAddressesWhole() {
        val t = RelaxedSafetyCheck.tokenize("Écris à l'adresse info@uqar.ca, merci.")
        assertEquals(listOf("Écris", "à", "l'", "adresse", "info@uqar.ca", ",", "merci", "."), t.map { it.text })
        assertEquals(RelaxedSafetyCheck.Kind.ADDRESS, t[4].kind)
        assertFalse(RelaxedSafetyCheck.Address.isAddress("St.Lawrence"))
        assertFalse(RelaxedSafetyCheck.Address.isAddress("a.m"))
        assertTrue(RelaxedSafetyCheck.Address.isAddress("saaq.gouv.qc.ca"))
        assertTrue(RelaxedSafetyCheck.Address.isAddress("github.com/kalmia"))
    }
}
