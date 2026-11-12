package io.github.remtav.dictee

import io.github.remtav.dictee.dictation.StringSink
import io.github.remtav.dictee.dictation.TextPipeline
import io.github.remtav.dictee.stt.DictationLanguage
import io.github.remtav.dictee.stt.ModelFamily
import io.github.remtav.dictee.text.Command
import io.github.remtav.dictee.text.CommandLanguage
import io.github.remtav.dictee.text.CommandTable
import io.github.remtav.dictee.text.Typography
import io.github.remtav.dictee.text.HallucinationFilter
import io.github.remtav.dictee.text.Piece
import io.github.remtav.dictee.text.RuleCleaner
import io.github.remtav.dictee.text.SafetyCheck
import io.github.remtav.dictee.text.TextAssembler
import io.github.remtav.dictee.text.TextEdits
import io.github.remtav.dictee.text.VoiceCommands
import io.github.remtav.dictee.text.Vocabulary
import io.github.remtav.dictee.text.Wer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextAssemblerTest {
    @Test fun firstSegmentIsCapitalizedWithoutLeadingSpace() {
        assertEquals("Bonjour tout le monde.", TextAssembler.join("", "bonjour tout le monde."))
    }

    @Test fun afterSentenceEndSpaceAndCapital() {
        assertEquals(" Ensuite on part.", TextAssembler.join("Il pleut.", "ensuite on part."))
    }

    @Test fun midSentenceCommonWordIsLowercased() {
        assertEquals(" et puis nous sommes partis", TextAssembler.join("Nous avons mangé", "Et puis nous sommes partis"))
        assertEquals(" the weather was nice", TextAssembler.join("We went out and", "The weather was nice"))
    }

    @Test fun midSentenceProperNounKeepsCapital() {
        assertEquals(" Paris en train", TextAssembler.join("Nous sommes allés à", "Paris en train"))
        assertEquals(" I think so", TextAssembler.join("Well", "I think so"))
        assertEquals(" NASA launched it", TextAssembler.join("Yesterday", "NASA launched it"))
    }

    @Test fun elidedWordsAreLowercasedMidSentence() {
        assertEquals(" c'était bien", TextAssembler.join("Je pense que", "C'était bien"))
    }

    @Test fun noDoubleSpaceAndNoSpaceAfterNewline() {
        assertEquals("Merci.", TextAssembler.join("Bonjour\n", "merci."))
        assertEquals("ok", TextAssembler.join("Il a dit ", "ok"))
        assertEquals("Ok", TextAssembler.join("Fin. ", "ok"))
    }

    @Test fun punctuationSegmentGetsNoSpace() {
        assertEquals(", voilà", TextAssembler.join("Bon", ", voilà"))
    }

    @Test fun emptySegment() {
        assertEquals("", TextAssembler.join("abc", "   "))
    }
}

class VoiceCommandsTest {
    @Test fun standaloneCommands() {
        assertEquals(listOf(Piece.Cmd(Command.NEW_LINE)), VoiceCommands.parse("Nouvelle ligne."))
        assertEquals(listOf(Piece.Cmd(Command.NEW_PARAGRAPH)), VoiceCommands.parse("New paragraph"))
        assertEquals(listOf(Piece.Cmd(Command.DELETE_LAST_SENTENCE)), VoiceCommands.parse("Efface la dernière phrase."))
        assertEquals(listOf(Piece.Cmd(Command.DELETE_LAST_SENTENCE)), VoiceCommands.parse("delete the last sentence"))
        assertEquals(listOf(Piece.Cmd(Command.DELETE_LAST_WORD)), VoiceCommands.parse("Supprime le dernier mot !"))
        assertEquals(listOf(Piece.Cmd(Command.NEW_LINE)), VoiceCommands.parse("À la ligne."))
    }

    @Test fun inlineNewLineBetweenPunctuation() {
        assertEquals(
            listOf(Piece.Text("Bonjour"), Piece.Cmd(Command.NEW_LINE), Piece.Text("merci.")),
            VoiceCommands.parse("Bonjour, nouvelle ligne, merci."),
        )
        assertEquals(
            listOf(Piece.Text("Dear John."), Piece.Cmd(Command.NEW_PARAGRAPH), Piece.Text("Thanks.")),
            VoiceCommands.parse("Dear John. New paragraph. Thanks."),
        )
    }

    @Test fun phraseInsideSentenceIsNotACommand() {
        val s = "On a ouvert une nouvelle ligne de métro."
        assertEquals(listOf(Piece.Text(s)), VoiceCommands.parse(s))
    }

    @Test fun deleteInsideSentenceIsText() {
        val s = "Il faut effacer la dernière phrase du contrat."
        assertEquals(listOf(Piece.Text(s)), VoiceCommands.parse(s))
    }

    @Test fun lastSentenceLength() {
        val t = "Hello world. This is bad."
        assertEquals(" This is bad.".length, TextEdits.lastSentenceLength(t))
        assertEquals("Only one sentence".length, TextEdits.lastSentenceLength("Only one sentence"))
        assertEquals("Second".length, TextEdits.lastSentenceLength("Line one.\nSecond"))
        assertEquals(" Quoi ?! ".length, TextEdits.lastSentenceLength("Oui. Quoi ?! "))
    }

    @Test fun lastWordLength() {
        assertEquals(" monde".length, TextEdits.lastWordLength("Bonjour monde"))
        assertEquals(" monde.".length, TextEdits.lastWordLength("Bonjour monde."))
        assertEquals(" aujourd'hui ".length, TextEdits.lastWordLength("C'est aujourd'hui "))
    }

    @Test fun pipelineAppliesCommandsToSink() {
        val sink = StringSink()
        val p = TextPipeline()
        p.applyFinal("Bonjour Marie.", ModelFamily.NEMO_TRANSDUCER, sink)
        p.applyFinal("Nouvelle ligne.", ModelFamily.NEMO_TRANSDUCER, sink)
        p.applyFinal("je t'écris pour te dire merci.", ModelFamily.NEMO_TRANSDUCER, sink)
        p.applyFinal("Nouveau paragraphe.", ModelFamily.NEMO_TRANSDUCER, sink)
        p.applyFinal("À bientôt.", ModelFamily.NEMO_TRANSDUCER, sink)
        p.applyFinal("Ceci est une erreur.", ModelFamily.NEMO_TRANSDUCER, sink)
        p.applyFinal("Efface la dernière phrase.", ModelFamily.NEMO_TRANSDUCER, sink)
        assertEquals("Bonjour Marie.\nJe t'écris pour te dire merci.\n\nÀ bientôt.", sink.text)
        p.applyFinal("Un deux trois", ModelFamily.NEMO_TRANSDUCER, sink)
        p.applyFinal("delete last word", ModelFamily.NEMO_TRANSDUCER, sink)
        assertEquals("Bonjour Marie.\nJe t'écris pour te dire merci.\n\nÀ bientôt. Un deux", sink.text)
    }

    @Test fun commandsCanBeDisabled() {
        val sink = StringSink()
        TextPipeline(commandsEnabled = false).applyFinal("Nouvelle ligne.", ModelFamily.NEMO_TRANSDUCER, sink)
        assertEquals("Nouvelle ligne.", sink.text)
    }
}

class VocabularyTest {
    private val vocab = Vocabulary.parse(
        """
        # commentaire
        Rémi
        Kubernetes
        cloud code -> Claude Code
        """.trimIndent(),
    )

    @Test fun parse() {
        assertEquals(listOf("Rémi", "Kubernetes", "Claude Code"), vocab.favoured)
        assertEquals(listOf("cloud code" to "Claude Code"), vocab.corrections)
    }

    @Test fun correctionsAreCaseInsensitiveOnWordBoundaries() {
        assertEquals("J'utilise Claude Code tous les jours.", vocab.apply("J'utilise Cloud Code tous les jours."))
        assertEquals("cloud coder", vocab.apply("cloud coder"))
    }

    @Test fun favouredWordRestoresSpellingAndFixesNearMiss() {
        assertEquals("Merci Rémi.", vocab.apply("Merci remi."))
        assertEquals("On déploie sur Kubernetes.", vocab.apply("On déploie sur Kubernetis."))
    }

    @Test fun unrelatedWordsUntouched() {
        val s = "Le remède est simple et la cuisine est bonne."
        assertEquals(s, vocab.apply(s))
    }
}

class FiltersTest {
    @Test fun ruleCleanerRemovesHesitations() {
        assertEquals("Je pense qu'on peut y aller.", RuleCleaner.clean("Euh, je pense euh qu'on peut y aller."))
        assertEquals("So I think the plan works.", RuleCleaner.clean("Um so I think the the plan works."))
        assertEquals("Le chat dort.", RuleCleaner.clean("Le le chat dort."))
    }

    @Test fun hallucinations() {
        assertTrue(HallucinationFilter.isHallucination("Sous-titres réalisés par la communauté d'Amara.org"))
        assertTrue(HallucinationFilter.isHallucination("Thanks for watching!"))
        assertFalse(HallucinationFilter.isHallucination("Merci beaucoup pour ton aide."))
    }

    @Test fun whisperHallucinationIsDropped() {
        val sink = StringSink("Bonjour.")
        TextPipeline().applyFinal("Sous-titres réalisés par la communauté d'Amara.org", ModelFamily.WHISPER, sink)
        assertEquals("Bonjour.", sink.text)
    }

    @Test fun safetyCheckAcceptsCleanup() {
        val raw = "Euh, on se voit lundi, non, mardi à la réunion avec Paul pour parler du budget."
        val clean = "On se voit mardi à la réunion avec Paul pour parler du budget."
        assertTrue(SafetyCheck.check(raw, clean).ok)
    }

    @Test fun safetyCheckRejectsAnswersAndChanges() {
        assertEquals("assistant_opener", SafetyCheck.check("quelle heure est-il", "Voici : il est midi").rule)
        assertEquals("negation", SafetyCheck.check(
            "je ne veux pas venir à la fête ce soir avec vous tous",
            "je veux venir à la fête ce soir avec vous tous",
        ).rule)
        assertEquals("numbers", SafetyCheck.check(
            "rendez-vous à 15 heures au bureau de la rue principale demain",
            "rendez-vous à 16 heures au bureau de la rue principale demain",
        ).rule)
        assertFalse(SafetyCheck.check("on se voit demain", "on se voit demain pour déjeuner au restaurant").ok)
        assertEquals("empty", SafetyCheck.check("bonjour", "").rule)
    }
}

class WerTest {
    @Test fun ignoresCaseAndPunctuation() {
        assertEquals(0.0, Wer.compute("Hello, World!", "hello world").rate, 1e-9)
    }

    @Test fun countsErrors() {
        val r = Wer.compute("the cat sat on the mat", "the cat sat on mat today")
        assertEquals(2, r.errors)
        assertEquals(6, r.referenceWords)
    }

    @Test fun frenchApostrophes() {
        assertEquals(0.0, Wer.compute("c'était très amusant", "C’était très amusant.").rate, 1e-9)
    }
}

// ---- Issue #15, step 2.2: dictated punctuation, false positives, duplicates, typography ----

private const val NB = ' '
private val QC = setOf(DictationLanguage.FRENCH_QUEBEC)
private val FR = setOf(DictationLanguage.FRENCH_FRANCE)
private val EN = setOf(DictationLanguage.ENGLISH)
private val QC_EN = setOf(DictationLanguage.FRENCH_QUEBEC, DictationLanguage.ENGLISH)

/** Dictates [segments] one after the other into a field holding [before]; returns the field. */
private fun dictate(vararg segments: String, languages: Set<DictationLanguage> = QC_EN, before: String = ""): String {
    val sink = StringSink(before)
    val p = TextPipeline(languages = languages)
    for (s in segments) p.applyFinal(s, ModelFamily.NEMO_TRANSDUCER, sink)
    return sink.text
}

class PunctuationCommandsTest {
    @Test fun issueExampleEndToEnd() {
        // The scripted dictation of the issue, as one segment without any punctuation from the model.
        assertEquals(
            "Il a dit$NB: «${NB}bonjour$NB»",
            dictate("il a dit deux-points ouvrir les guillemets bonjour fermer les guillemets", languages = QC),
        )
        // Without the hyphen: "deux points" is followed by another command, so it is one.
        assertEquals(
            "Il a dit$NB: «${NB}bonjour$NB»",
            dictate("il a dit deux points ouvrir les guillemets bonjour fermer les guillemets", languages = QC),
        )
        // With the model's commas and capital letters.
        assertEquals(
            "Il a dit$NB: «${NB}bonjour$NB».",
            dictate("Il a dit deux-points, ouvrir les guillemets, bonjour, fermer les guillemets.", languages = QC),
        )
        // Spread over several segments (pauses): the model's period after "Bonjour" stays inside.
        assertEquals(
            "Il a dit$NB: «${NB}Bonjour.$NB»",
            dictate("Il a dit", "Deux-points.", "Ouvrir les guillemets.", "Bonjour.", "Fermer les guillemets.", languages = QC),
        )
    }

    @Test fun eachFrenchCommand() {
        val cases = listOf(
            "il a dit deux-points oui" to "Il a dit$NB: oui",
            "du pain point-virgule du lait" to "Du pain; du lait",
            "du pain point virgule du lait" to "Du pain; du lait",
            "on se voit lundi ouvrir la parenthèse ou mardi fermer la parenthèse" to "On se voit lundi (ou mardi)",
            "lundi parenthèse ouvrante ou mardi parenthèse fermante" to "Lundi (ou mardi)",
            "tu viens demain point d'interrogation" to "Tu viens demain?",
            "quelle belle journée point d'exclamation" to "Quelle belle journée!",
            "il a dit guillemet ouvrant oui guillemet fermant" to "Il a dit «${NB}oui$NB»",
            "il a dit ouvrez les guillemets oui fermez les guillemets" to "Il a dit «${NB}oui$NB»",
            "il a dit guillemets ouverts oui guillemets fermés" to "Il a dit «${NB}oui$NB»",
        )
        for ((said, expected) in cases) assertEquals(said, expected, dictate(said, languages = QC))
    }

    @Test fun eachEnglishCommand() {
        val cases = listOf(
            "she said colon open quote hello close quote" to "She said: “hello”",
            "she said open quote hello end quote" to "She said “hello”",
            "bread semicolon milk" to "Bread; milk",
            "see you Monday open parenthesis or Tuesday close parenthesis" to "See you Monday (or Tuesday)",
            "are you coming question mark" to "Are you coming?",
            "what a day exclamation mark" to "What a day!",
            "what a day exclamation point" to "What a day!",
        )
        for ((said, expected) in cases) assertEquals(said, expected, dictate(said, languages = EN))
    }

    @Test fun layoutAndDeletionStillWork() {
        assertEquals("Bonjour.\nMerci.", dictate("Bonjour.", "Nouvelle ligne.", "merci.", languages = QC))
        assertEquals("Dear John.\n\nThanks.", dictate("Dear John.", "New paragraph.", "Thanks.", languages = EN))
        assertEquals("Un deux", dictate("Un deux trois", "Efface le dernier mot.", languages = QC))
    }

    @Test fun signsAreTheirOwnPiecesInTheParser() {
        assertEquals(
            listOf(
                Piece.Text("il a dit"), Piece.Cmd(Command.COLON), Piece.Cmd(Command.OPEN_QUOTE),
                Piece.Text("bonjour"), Piece.Cmd(Command.CLOSE_QUOTE),
            ),
            VoiceCommands.parse("il a dit deux-points ouvrir les guillemets bonjour fermer les guillemets"),
        )
        assertEquals(listOf(Piece.Cmd(Command.QUESTION_MARK)), VoiceCommands.parse("Point d'interrogation."))
    }

    @Test fun commandsFollowTheChosenLanguages() {
        // English wordings are text for a French-only user, and the reverse.
        assertEquals("Are you coming question mark", dictate("are you coming question mark", languages = QC))
        assertEquals("Tu viens point d'interrogation", dictate("tu viens point d'interrogation", languages = EN))
        assertEquals("New paragraph.", dictate("New paragraph.", languages = QC))
        // Both: each wording is typeset in its own language.
        assertEquals("Tu viens?", dictate("tu viens point d'interrogation", languages = QC_EN))
        assertEquals("She said “hi”", dictate("she said open quote hi close quote", languages = QC_EN))
    }

    @Test fun otherLanguagesKeepLineBreaksButNoPunctuation() {
        val es = setOf(DictationLanguage.SPANISH)
        assertEquals("Hola.\nGracias.", dictate("Hola.", "New line.", "Gracias.", languages = es))
        assertEquals("Hola.\nGracias.", dictate("Hola.", "Nouvelle ligne.", "Gracias.", languages = es))
        assertEquals("Hola question mark", dictate("hola question mark", languages = es))
        assertTrue(CommandTable.active(es).none { it.command.isSign })
    }

    @Test fun disabledCommandsLeaveEverythingAsText() {
        val sink = StringSink()
        TextPipeline(commandsEnabled = false, languages = QC).applyFinal("tu viens point d'interrogation", ModelFamily.NEMO_TRANSDUCER, sink)
        assertEquals("Tu viens point d'interrogation", sink.text)
    }

    @Test fun closingWithNothingOpenStaysText() {
        assertEquals("Bonjour fermer les guillemets", dictate("bonjour fermer les guillemets", languages = QC))
        assertEquals("Bonjour fermer la parenthèse", dictate("bonjour fermer la parenthèse", languages = QC))
        // A closing sign cannot start the field.
        assertEquals("Deux-points", dictate("deux-points", languages = QC))
    }

    /** Typical model outputs checked by the review (2.R): capitals, periods, one command per segment. */
    @Test fun realisticModelOutputs() {
        assertEquals("Voici la liste$NB: du pain.", dictate("Voici la liste.", "Deux-points.", "Du pain.", languages = QC))
        assertEquals("Voici la liste$NB:", dictate("Voici la liste", "Deux-points.", languages = QC))
        assertEquals("Super!\n\nMerci.", dictate("Super.", "Point d'exclamation.", "Nouveau paragraphe.", "Merci.", languages = QC))
        assertEquals("Tu viens?\n", dictate("Tu viens.", "Point d'interrogation, nouvelle ligne.", languages = QC))
        assertEquals("Wow!", dictate("Wow point d'exclamation point d'exclamation", languages = QC))
        assertEquals("Bon$NB: «${NB}Allô$NB»", dictate("Bon", "Deux points.", "Ouvrir les guillemets", "Allô", "Fermer les guillemets.", languages = QC))
        assertEquals("Le chat (Félix)", dictate("Le chat", "Ouvrir la parenthèse", "Félix", "Fermer la parenthèse.", languages = QC))
        assertEquals("“Hello”.", dictate("Open quote hello close quote.", languages = EN))
        assertEquals("DEUX-POINTS", dictate("DEUX-POINTS", languages = QC))
        // A sign that cannot apply gives back the words with the model's punctuation.
        assertEquals("Point d'interrogation.", dictate("Point d'interrogation.", languages = QC))
    }

    @Test fun disabledCommandsAcrossSegments() {
        val sink = StringSink()
        val p = TextPipeline(commandsEnabled = false, languages = QC)
        for (seg in listOf("Il a dit", "Deux-points.", "Ouvrir les guillemets.", "Bonjour.")) p.applyFinal(seg, ModelFamily.NEMO_TRANSDUCER, sink)
        assertEquals("Il a dit Deux-points. Ouvrir les guillemets. Bonjour.", sink.text)
        assertFalse(sink.text.contains(NB))
    }

    @Test fun timeDictatedWithAColonHasNoSpaces() {
        assertEquals("Rendez-vous à 13:52", dictate("rendez-vous à 13 deux-points 52", languages = QC))
    }
}

/** Phrases that must not trigger any command: the words stay as the model wrote them. */
class FalsePositiveTest {
    private val french = listOf(
        "J'ai marqué deux points.",
        "J'ai marqué deux points hier soir.",
        "Elle a mis deux points sur la carte.",
        "Il y a deux points de vue.",
        "En deux points, d'abord le budget.",
        "C'est un point d'interrogation pour moi.",
        "Ça reste un gros point d'interrogation.",
        "Son avenir est un point d'interrogation.",
        "Le point d'exclamation est de trop.",
        "Il manque un point-virgule.",
        "Le point-virgule est rare.",
        "Il a ouvert les guillemets.",
        "Il faut ouvrir les guillemets ici.",
        "Pour ouvrir la parenthèse, il faut du courage.",
        "Je vais ouvrir la parenthèse sur ce sujet.",
        "Les guillemets ouverts, il cite.",
        "On a ouvert une nouvelle ligne de métro.",
        "Il est allé à la ligne d'arrivée.",
        "Il faut effacer la dernière phrase du contrat.",
        "Tu dois fermer les guillemets toi-même.",
        "Il a dit point, virgule, et rien d'autre.",
    )
    private val english = listOf(
        "That's a question mark for me.",
        "His future is a big question mark.",
        "Put a colon here.",
        "My colon hurts.",
        "Colon cancer screening saves lives.",
        "I have colon problems.",
        "The semicolon is rare.",
        "I love that quote.",
        "Open quotes on the website are free.",
        "In the end quotes are fine.",
        "Add an exclamation mark to the title.",
        "We opened a new line of products.",
        "Delete the last sentence of the contract.",
    )

    /** Found by the independent review (2.R): each one changed the text before its fix. */
    private val reviewFrench = listOf(
        "Ouvre les guillemets du rapport et corrige-les.",
        "Deux points, c'est pas assez.",
        "Deux points de suture, c'est rien.",
        "Point d'exclamation, ça crie trop.",
        "Guillemets ouverts ou fermés, peu importe.",
        "Fermer la parenthèse.",
        "Deux points.",
        // and ordinary sentences that already stayed text
        "On a gagné par deux points.",
        "On a gagné par deux points hier.",
        "Mets ça entre parenthèses.",
        "C'est un nouveau paragraphe de ma vie.",
        "Il a fait deux points, puis il est parti.",
        "Ils ont perdu deux points au classement.",
        "Le match s'est fini à deux points d'écart.",
        "Ferme les guillemets quand tu as fini.",
        "J'ouvre une parenthèse, ma mère est malade.",
        "Bon, je ferme la parenthèse.",
        "Une ligne, deux lignes, trois lignes.",
        "Il faut écrire à la ligne suivante.",
        "Va à la ligne, s'il te plaît.",
        "Il est revenu à la ligne de départ.",
        "J'ai écrit nouvelle ligne dans le courriel.",
        "La nouvelle ligne de bus passe chez nous.",
        "Le nouveau paragraphe est trop long.",
        "Un point d'interrogation plane sur le projet.",
        "Point d'interrogation sur le budget.",
        "Le point-virgule, c'est compliqué.",
        "Point-virgule ou virgule, je ne sais pas.",
        "Elle a mis un point d'exclamation partout.",
        "Les guillemets fermés, on passe à autre chose.",
        "Elle met toujours ses idées entre guillemets.",
        "Je mets toujours deux points avant une liste.",
        "Avec deux points d'avance, on est premiers.",
        "Montréal a deux points de plus que Québec.",
        "Tu me dois deux points, mon chum.",
        "C'était un point d'interrogation, pas un point.",
        "Est-ce que c'est un deux-points ou un point-virgule?",
        "Pas de point d'exclamation, merci.",
        "Mettez deux-points après le titre.",
        "Il a un problème au colon.",
    )
    private val reviewEnglish = listOf(
        "Question mark, or period?",
        "Colon, rectum and liver.",
        "End quote.",
        "The colon is an organ.",
        "Quote me on that.",
        "I need a new line of credit.",
        "She opened a new paragraph in her life.",
        "The question mark is at the end.",
        "That's a quote from Shakespeare.",
        "He scored two points.",
        "The colon, which is long, absorbs water.",
        "Semicolon usage is rare.",
        "He closed the parenthesis.",
        "Open parenthesis is the left one.",
        "The next line is important.",
        "Next paragraph talks about cats.",
        "I had a colon exam.",
        "Scratch that idea.",
    )

    @Test fun reviewTrapsStayText() {
        for (s in reviewFrench) assertEquals(s, s, dictate(s, languages = QC))
        for (s in reviewEnglish) assertEquals(s, s, dictate(s, languages = EN))
        for (s in reviewFrench + reviewEnglish) assertEquals(s, s, dictate(s, languages = QC_EN))
    }

    /** A sentence about the sign, said after a quote or parenthesis was opened: stays text (review 2.R). */
    @Test fun pronounBeforeAVerbWordingStaysText() {
        assertEquals("Il a dit (voir annexe je ferme la parenthèse et on continue.", dictate("Il a dit (voir annexe", "Je ferme la parenthèse et on continue.", languages = QC))
        assertEquals("Il a écrit « oui je ferme les guillemets.", dictate("Il a écrit « oui", "Je ferme les guillemets.", languages = QC))
        assertEquals("He wrote “hi I close quotes with a dash.", dictate("He wrote “hi", "I close quotes with a dash.", languages = EN))
        // Names of signs after a pronoun still work: "viens-tu?" is Québec French.
        assertEquals("Viens-tu?", dictate("viens-tu point d'interrogation", languages = QC))
    }

    /** Homograph after another sentence, with a comma: stays text, even mid-field. */
    @Test fun homographFollowedByACommaAndWordsStaysText() {
        assertEquals("Il a marqué. Deux points, c'est pas assez.", dictate("Il a marqué.", "Deux points, c'est pas assez.", languages = QC))
        // But between two commands, with the model's commas, it is one.
        assertEquals("Il a dit$NB: «${NB}bonjour$NB».", dictate("Il a dit, deux points, ouvrir les guillemets, bonjour, fermer les guillemets.", languages = QC))
    }

    @Test fun frenchTrapsStayText() {
        for (s in french) {
            assertEquals(s, listOf(Piece.Text(s)), VoiceCommands.parse(s, CommandTable.active(QC)))
            assertEquals(s, s, dictate(s, languages = QC))
        }
    }

    @Test fun englishTrapsStayText() {
        for (s in english) {
            assertEquals(s, listOf(Piece.Text(s)), VoiceCommands.parse(s, CommandTable.active(EN)))
            assertEquals(s, s, dictate(s, languages = EN))
        }
    }
}

/** The model already wrote the sign: only one remains. */
class DuplicateSignTest {
    @Test fun modelSignBeforeTheCommand() {
        assertEquals("Il a dit$NB: oui", dictate("Il a dit : deux-points oui", languages = QC))
        assertEquals("Il a dit$NB: oui", dictate("Il a dit: deux-points oui", languages = QC))
        assertEquals("Vraiment?", dictate("Vraiment ? Point d'interrogation.", languages = QC))
        assertEquals("Vraiment?", dictate("Vraiment? point d'interrogation", languages = QC))
        assertEquals("Super!", dictate("Super ! Point d'exclamation !", languages = QC))
        assertEquals("Really?", dictate("Really? Question mark.", languages = EN))
    }

    @Test fun modelSignAfterTheCommand() {
        assertEquals("Vraiment?", dictate("Vraiment point d'interrogation ?", languages = QC))
        assertEquals("Il a dit$NB: oui", dictate("Il a dit deux-points : oui", languages = QC))
        assertEquals("Really?", dictate("Really question mark?", languages = EN))
    }

    @Test fun finalPeriodAfterTheCommandGoes() {
        assertEquals("Tu viens?", dictate("Tu viens, point d'interrogation.", languages = QC))
        assertEquals("Du pain; du lait.", dictate("Du pain, point-virgule, du lait.", languages = QC))
    }

    @Test fun signFromThePreviousSegmentIsReplaced() {
        assertEquals("C'est vrai?", dictate("C'est vrai.", "Point d'interrogation.", languages = QC))
        assertEquals("Il a dit$NB:", dictate("Il a dit :", "Deux-points.", languages = QC))
        assertEquals("C'est vrai${NB}?", dictate("C'est vrai.", "Point d'interrogation.", languages = FR))
    }

    @Test fun quotesTheModelAlreadyClosedAreNotDoubled() {
        assertEquals(
            "Il a dit «${NB}oui$NB»",
            dictate("il a dit ouvrir les guillemets oui", "» fermer les guillemets", languages = QC),
        )
    }
}

class TypographyTest {
    private val said = "il a dit deux-points ouvrir les guillemets oui fermer les guillemets point-virgule " +
        "tu viens point d'interrogation super point d'exclamation"

    @Test fun quebec() {
        assertEquals("Il a dit$NB: «${NB}oui$NB»; tu viens? Super!", dictate(said, languages = QC))
    }

    @Test fun france() {
        assertEquals("Il a dit$NB: «${NB}oui$NB»$NB; tu viens$NB? Super$NB!", dictate(said, languages = FR))
    }

    @Test fun bothFrenchVariantsChosenUseQuebec() {
        assertEquals(
            "Il a dit$NB: «${NB}oui$NB»; tu viens? Super!",
            dictate(said, languages = setOf(DictationLanguage.FRENCH_QUEBEC, DictationLanguage.FRENCH_FRANCE)),
        )
    }

    @Test fun english() {
        assertEquals(
            "She said: “yes”; are you coming? Great!",
            dictate(
                "she said colon open quote yes close quote semicolon are you coming question mark great exclamation mark",
                languages = EN,
            ),
        )
    }

    @Test fun noSpaceBetweenApostropheAndOpeningQuote() {
        assertEquals(
            "C'est l'«${NB}outil$NB»",
            dictate("ouvrir les guillemets outil fermer les guillemets", languages = QC, before = "C'est l'"),
        )
    }

    @Test fun capitalAfterSentenceEndAndInsideQuotesAtStart() {
        assertEquals("Tu viens? On part.", dictate("tu viens point d'interrogation on part.", languages = QC))
        assertEquals("«${NB}Bonjour$NB»", dictate("ouvrir les guillemets bonjour fermer les guillemets", languages = QC))
        assertEquals("Il a dit$NB: et voilà", dictate("il a dit deux-points Et voilà", languages = QC))
    }

    @Test fun noDoubleSpaces() {
        val out = dictate("Il a dit  :  deux-points ,  ouvrir les guillemets ,  oui ,  fermer les guillemets", languages = QC)
        assertEquals("Il a dit$NB: «${NB}oui$NB»", out)
        assertFalse(out.contains("  "))
    }

    @Test fun modelTimeIsUntouched() {
        assertEquals("Rendez-vous à 13:52.", dictate("Rendez-vous à 13:52.", languages = QC))
        assertEquals("Rendez-vous à 13:52.", dictate("Rendez-vous à 13:52.", languages = FR))
    }

    @Test fun modelPunctuationIsNotTypeset() {
        // Only dictated signs get the no-break spaces; the model's text is inserted as it was.
        assertEquals("Il a dit: oui? Non!", dictate("Il a dit: oui? Non!", languages = FR))
    }

    @Test fun signEditUnit() {
        assertEquals(TextAssembler.SignEdit(0, "$NB:"), TextAssembler.sign("Voici", Command.COLON, Typography.QUEBEC))
        assertEquals(TextAssembler.SignEdit(0, ":"), TextAssembler.sign("Here", Command.COLON, Typography.ENGLISH))
        assertEquals(TextAssembler.SignEdit(0, "?"), TextAssembler.sign("Oui", Command.QUESTION_MARK, Typography.QUEBEC))
        assertEquals(TextAssembler.SignEdit(0, "$NB?"), TextAssembler.sign("Oui", Command.QUESTION_MARK, Typography.FRANCE))
        assertEquals(TextAssembler.SignEdit(2, "?"), TextAssembler.sign("Oui ?", Command.QUESTION_MARK, Typography.QUEBEC))
        assertEquals(TextAssembler.SignEdit(0, " «$NB"), TextAssembler.sign("Il a dit", Command.OPEN_QUOTE, Typography.QUEBEC))
        assertEquals(TextAssembler.SignEdit(0, "«$NB"), TextAssembler.sign("", Command.OPEN_QUOTE, Typography.QUEBEC))
        assertEquals(TextAssembler.SignEdit(0, " “"), TextAssembler.sign("She said", Command.OPEN_QUOTE, Typography.ENGLISH))
        assertEquals(TextAssembler.SignEdit(0, ":", glueNext = true), TextAssembler.sign("à 13", Command.COLON, Typography.FRANCE, "52"))
        assertEquals(null, TextAssembler.sign("", Command.QUESTION_MARK, Typography.QUEBEC))
        assertEquals(null, TextAssembler.sign("Ligne\n", Command.COLON, Typography.QUEBEC))
        assertEquals(null, TextAssembler.sign("Rien d'ouvert", Command.CLOSE_QUOTE, Typography.QUEBEC))
        // "etc..." keeps its periods.
        assertEquals(TextAssembler.SignEdit(0, "?"), TextAssembler.sign("Et puis...", Command.QUESTION_MARK, Typography.QUEBEC))
    }
}

class CommandTableTest {
    @Test fun coversExactlyTheInitialListInBothLanguages() {
        for (lang in CommandLanguage.entries) {
            assertEquals(lang.name, Command.entries.toSet(), CommandTable.entries.filter { it.language == lang }.map { it.command }.toSet())
        }
        assertEquals(Command.entries.size * CommandLanguage.entries.size, CommandTable.entries.size)
    }

    @Test fun activeCommandsFollowLanguages() {
        assertEquals(setOf(CommandLanguage.FRENCH), CommandTable.active(QC).map { it.language }.toSet())
        assertEquals(setOf(CommandLanguage.FRENCH), CommandTable.active(FR).map { it.language }.toSet())
        assertEquals(setOf(CommandLanguage.ENGLISH), CommandTable.active(EN).map { it.language }.toSet())
        assertEquals(CommandTable.entries, CommandTable.active(QC_EN))
    }

    @Test fun noWordingIsSharedByTwoCommands() {
        val seen = HashMap<List<String>, Command>()
        for (e in CommandTable.entries) for (f in e.forms) {
            val previous = seen.put(f.words, e.command)
            assertTrue("${f.text} used by $previous and ${e.command}", previous == null || previous == e.command)
        }
    }

    @Test fun everyFormIsRecognisedAlone() {
        // Each wording, said alone in a field after some text, gives its command (deletions included).
        for (e in CommandTable.entries) for (f in e.forms) {
            val pieces = VoiceCommands.parse("${f.text}.", CommandTable.active(QC_EN))
            assertEquals(f.text, listOf(Piece.Cmd(e.command)), pieces)
        }
    }

    @Test fun helpExamplesAreWhatTheAppProduces() {
        // Step 2.3 shows these examples: they must match the behaviour (French in Québec typography).
        for (e in CommandTable.entries) {
            val langs = if (e.language == CommandLanguage.FRENCH) QC else EN
            assertEquals(e.example.said, e.example.result, dictate(e.example.said, languages = langs, before = e.example.before))
        }
    }

    @Test fun everyFormHasASource() {
        for (e in CommandTable.entries) for (f in e.forms) assertTrue(f.text, f.sources.isNotEmpty())
    }
}
