package io.github.remtav.dictee

import io.github.remtav.dictee.dictation.StringSink
import io.github.remtav.dictee.dictation.TextPipeline
import io.github.remtav.dictee.stt.ModelFamily
import io.github.remtav.dictee.text.Command
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
