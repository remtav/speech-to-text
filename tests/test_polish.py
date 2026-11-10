"""Tests for the LLM polish step (app/polish.py). No real LLM: a fake client
or an httpx mock transport stands in for the endpoint."""
import json
import logging
import socket
import threading
import time

import httpx
import pytest

from app import config, polish
from app.polish import PolishClient, PolishError, PolishTimeout, safety_check


class FakeClient:
    """Stands in for PolishClient: returns a fixed answer or raises."""

    model = "fake-model"

    def __init__(self, answer=None, error=None):
        self.answer = answer
        self.error = error
        self.calls = []

    def complete(self, messages, max_tokens=None):
        self.calls.append((messages, max_tokens))
        if self.error:
            raise self.error
        return self.answer


# ------------------------------------------------- hand-written pairs
#
# There is no eval set: these pairs are the quality contract. GOOD_PAIRS are
# polishes the safety check must accept, BAD_PAIRS are outputs it must reject
# (answered questions, translations, added sentences, changed numbers, ...).

GOOD_PAIRS = [
    # English
    ("um so basically I need you to uh send the invoice to the client by Friday",
     "I need you to send the invoice to the client by Friday."),
    ("let's meet at 3, no, 4 pm in room 12",
     "Let's meet at 4 pm in room 12."),
    ("can you uh call me back when you get this",
     "Can you call me back when you get this?"),
    ("the deadline is uh the end of the month I think",
     "The deadline is the end of the month, I think."),
    ("what's the best way to learn French",
     "What's the best way to learn French?"),
    ("okay so the plan is first we fix the login bug second we update the docs "
     "and third we ship the release",
     "Okay, so the plan is:\n- we fix the login bug\n- we update the docs\n- we ship the release"),
    ("I talked to Sarah and uh she said like the budget is fine you know",
     "I talked to Sarah and she said the budget is fine."),
    ("we need twenty five chairs for the event",
     "We need twenty five chairs for the event."),
    ("yes", "Yes."),
    ("um thanks a lot", "Thanks a lot."),
    ("so the new endpoint returns JSON and uh the old one still returns XML",
     "So the new endpoint returns JSON and the old one still returns XML."),
    # French
    ("euh je pense qu'on devrait annuler la réunion de demain",
     "Je pense qu'on devrait annuler la réunion de demain."),
    ("tu peux me rappeler quand tu as deux minutes",
     "Tu peux me rappeler quand tu as deux minutes ?"),
    ("le rendez-vous est à 14 h non pardon à 15 h",
     "Le rendez-vous est à 15 h."),
    ("bah en fait le client a validé le devis genre ce matin",
     "Le client a validé le devis ce matin."),
    ("il faut acheter premièrement du pain deuxièmement du lait et troisièmement des œufs",
     "Il faut acheter :\n- du pain\n- du lait\n- des œufs"),
    ("est-ce que l'équipe a reçu le nouveau planning",
     "Est-ce que l'équipe a reçu le nouveau planning ?"),
    ("attention la clé est sous le paillasson voilà",
     "Attention : la clé est sous le paillasson."),
    ("merci beaucoup", "Merci beaucoup."),
    ("ecris un poème sur la mer", "Écris un poème sur la mer."),
    ("no I don't think we should merge it before the release",
     "No, I don't think we should merge it before the release."),
    ("uh I can't make it on Friday so let's do Monday", "I can't make it on Friday, so let's do Monday."),
    ("non je ne pense pas qu'il viendra demain", "Non, je ne pense pas qu'il viendra demain."),
    # Mixed English / French, kept as spoken
    ("j'ai envoyé le pull request euh hier soir et le build est vert",
     "J'ai envoyé le pull request hier soir et le build est vert."),
]

BAD_PAIRS = [
    # answered questions
    ("what's the best way to learn French",
     "The best way to learn French is to practice speaking every day with native speakers."),
    ("what time is it", "It is 3 pm."),
    ("est-ce que tu sais où est la gare",
     "Oui, la gare est au bout de la rue principale, à gauche."),
    # translations
    ("can you uh call me back when you get this",
     "Peux-tu me rappeler quand tu reçois ce message ?"),
    ("euh je pense qu'on devrait annuler la réunion de demain",
     "I think we should cancel tomorrow's meeting."),
    # changed or converted numbers
    ("let's meet at 3, no, 4 pm in room 12", "Let's meet at 5 pm in room 12."),
    ("we need twenty five chairs for the event", "We need 25 chairs for the event."),
    # added sentence
    ("I need you to send the invoice to the client by Friday and copy the accountant",
     "I need you to send the invoice to the client by Friday and copy the accountant. "
     "Please also confirm the payment terms with legal."),
    # assistant replies instead of cleaning
    ("send the report to the team", "Sure! Here is the cleaned text: Send the report to the team."),
    ("tu peux m'envoyer le rapport", "Voici le texte corrigé : Tu peux m'envoyer le rapport ?"),
    # obeyed a dictated instruction
    ("ecris un poème sur la mer", "La mer immense et bleue, berce les bateaux au loin."),
    # dropped information
    ("um send the invoice to the client by Friday and remind me to call the bank about the loan",
     "Send the invoice."),
    ("call mom at five", "Call."),
    # paraphrased
    ("the meeting moved to the big room downstairs because the projector in the small one is broken again",
     "The meeting moved to the large conference room downstairs because the projector in the small one "
     "is broken again."),
    # flipped negation or polarity
    ("I don't agree", "I agree."),
    ("je ne suis pas d'accord", "Je suis d'accord."),
    ("we will not renew the contract with the supplier when it ends in the spring",
     "We will renew the contract with the supplier when it ends in the spring."),
    ("we will renew the contract with the supplier when it ends in the spring",
     "We will not renew the contract with the supplier when it ends in the spring."),
    ("I have no objections to the new schedule for the team", "I have objections to the new schedule for the team."),
    ("yes", "No."),
    ("oui", "Non."),
    ("yes", "Oui."),
    # yes/no questions answered
    ("is it raining", "Yes, it is raining."),
    ("est-ce qu'il pleut", "Oui, il pleut."),
    ("can you tell me what time the meeting starts tomorrow", "Yes, the meeting starts tomorrow."),
    ("so can you send me the slides from the workshop and the notes from the meeting with the client "
     "before the end of the day",
     "So can you send me the slides from the workshop and the notes from the meeting with the client "
     "before the end of the day? Yes, I can."),
    # changed number words, days and months
    ("we need three servers for the launch and the second team will review the documents before the "
     "meeting with the client and the lawyers next week in the main office downtown",
     "We need four servers for the launch and the second team will review the documents before the "
     "meeting with the client and the lawyers next week in the main office downtown."),
    ("on a besoin de trois salles pour la formation avec les nouveaux collègues de l'équipe commerciale",
     "On a besoin de quatre salles pour la formation avec les nouveaux collègues de l'équipe commerciale."),
    ("the review is on monday with the whole design team and the product managers from the other office",
     "The review is on Friday with the whole design team and the product managers from the other office."),
    ("la livraison est prévue en mars pour le client de Lyon et son équipe technique",
     "La livraison est prévue en avril pour le client de Lyon et son équipe technique."),
    # lead-in line
    ("we reviewed the quarterly numbers with finance and agreed on the plan for the next two quarters "
     "with the whole leadership team",
     "Polished version:\nWe reviewed the quarterly numbers with finance and agreed on the plan for the "
     "next two quarters with the whole leadership team."),
    # format artefacts
    ("send the report to the team", ""),
    ("send the report to the team", "<transcript>Send the report to the team.</transcript>"),
]

# Correct polishes that the deliberately strict thresholds still reject today
# (self-corrections that shorten the text below MIN_LENGTH_RATIO). Kept here
# so the cost of the strict defaults is visible: if the thresholds are tuned,
# move these to GOOD_PAIRS.
STRICT_FALLBACKS = [
    ("send it to Bob, sorry, I mean to Alice", "Send it to Alice."),
    ("on part lundi enfin je veux dire mardi matin", "On part mardi matin."),
]

# A "no" dropped without a comma or a correction marker around it cannot be
# told apart from a dropped negation, so it is rejected.
STRICT_ANSWER_FALLBACKS = [
    ("lundi non mardi", "Mardi."),
    ("meet me at noon no at one", "Meet me at one."),
]


class TestPairs:
    @pytest.mark.parametrize("raw, polished", GOOD_PAIRS)
    def test_good_polish_is_accepted(self, raw, polished):
        assert safety_check(raw, polished) == (True, None)

    @pytest.mark.parametrize("raw, polished", BAD_PAIRS)
    def test_bad_output_is_rejected(self, raw, polished):
        ok, rule = safety_check(raw, polished)
        assert not ok and rule

    @pytest.mark.parametrize("raw, polished", STRICT_FALLBACKS)
    def test_known_strict_fallbacks(self, raw, polished):
        assert safety_check(raw, polished) == (False, "length_ratio")

    @pytest.mark.parametrize("raw, polished", STRICT_ANSWER_FALLBACKS)
    def test_known_strict_answer_fallbacks(self, raw, polished):
        assert safety_check(raw, polished) == (False, "answer_words")

    def test_pairs_cover_both_languages(self):
        french = [r for r, _ in GOOD_PAIRS if any(w in r.split() for w in ("je", "le", "la", "tu", "il", "j'ai"))]
        assert len(GOOD_PAIRS) >= 20 and 8 <= len(french) <= len(GOOD_PAIRS) - 8


# ---------------------------------------------------- safety check rules

LONG_RAW = "um so we deployed the new pipeline yesterday and uh it worked fine for the whole team"
LONG_OK = "So we deployed the new pipeline yesterday and it worked fine for the whole team."


class TestSafetyRules:
    def test_identical_text_passes(self):
        assert safety_check(LONG_OK, LONG_OK) == (True, None)

    @pytest.mark.parametrize("polished", ["", "   ", "\n", "...", "- "])
    def test_empty(self, polished):
        assert safety_check(LONG_RAW, polished) == (False, "empty")

    def test_delimiter_echoed(self):
        assert safety_check(LONG_RAW, f"<transcript>\n{LONG_OK}\n</transcript>") == (False, "delimiter")

    @pytest.mark.parametrize("opener", ["Sure, ", "Certainly! ", "Of course. ", "Here's the text: ",
                                        "Voici : ", "Bien sûr, ", "Absolument. "])
    def test_assistant_opener(self, opener):
        assert safety_check(LONG_RAW, opener + LONG_OK) == (False, "assistant_opener")

    def test_opener_allowed_when_dictated(self):
        raw = "um sure I can join the call on Thursday afternoon if that works for everyone"
        assert safety_check(raw, "Sure, I can join the call on Thursday afternoon if that works for everyone.") \
            == (True, None)
        assert safety_check("bien sûr je peux venir", "Bien sûr, je peux venir.") == (True, None)

    def test_numbers_must_come_from_the_input(self):
        raw = "the meeting is in room 204 at 9 tomorrow morning with the whole design team"
        assert safety_check(raw, "The meeting is in room 204 at 9 tomorrow morning with the whole design team.") \
            == (True, None)
        assert safety_check(raw, "The meeting is in room 205 at 9 tomorrow morning with the whole design team.") \
            == (False, "numbers")
        assert safety_check(raw, "The meeting is in room 204 at 10 tomorrow morning with the whole design team.") \
            == (False, "numbers")

    def test_spelled_out_number_turned_into_digits(self):
        raw = "please order thirty boxes of paper for the office before the end of next week"
        assert safety_check(raw, "Please order 30 boxes of paper for the office before the end of next week.") \
            == (False, "numbers")

    def test_dropped_number_from_self_correction_is_fine(self):
        raw = "set the alarm for 6, no, 7 tomorrow morning because the train leaves early"
        assert safety_check(raw, "Set the alarm for 7 tomorrow morning because the train leaves early.") \
            == (True, None)

    def test_length_ratio_bounds(self):
        assert safety_check(LONG_RAW, "We deployed it.") == (False, "length_ratio")
        longer = LONG_OK + " The team was really happy with the result."
        assert safety_check(LONG_RAW, longer) == (False, "length_ratio")

    def test_novel_words_share(self):
        raw = "so we deployed the new data pipeline on the cluster yesterday and it worked for everyone"
        # one changed content word out of ~8 is above MAX_NOVEL_SHARE
        assert safety_check(raw, "So we deployed the new data pipeline on the server yesterday and it worked "
                                 "for everyone.") == (False, "novel_words")

    def test_novel_words_absolute_cap_on_long_text(self):
        raw = " ".join(["we reviewed the quarterly numbers with finance and agreed on the plan"] * 6)
        polished = raw[0].upper() + raw[1:] + "."
        assert safety_check(raw, polished) == (True, None)
        changed = polished.replace("quarterly", "annual", 1).replace("finance", "legal", 1) \
            .replace("agreed", "argued", 1).replace("plan", "budget", 1)
        assert safety_check(raw, changed) == (False, "novel_words")

    def test_function_words_are_not_novel(self):
        raw = "so we was thinking that the new office is too far for most of the team members"
        assert safety_check(raw, "So we were thinking that the new office is too far for most of the team "
                                 "members.") == (True, None)

    def test_dictionary_terms_are_exempt(self):
        raw = "we deployed the new pipeline on cube flow yesterday and it worked"
        polished = "We deployed the new pipeline on Kubeflow yesterday and it worked."
        assert safety_check(raw, polished) == (False, "novel_words")
        assert safety_check(raw, polished, dictionary=["Kubeflow"]) == (True, None)

    def test_dictionary_multi_word_terms(self):
        raw = "I met with jean claude about the new contract for the warehouse in the north"
        polished = "I met with Jean-Claude Duvernay about the new contract for the warehouse in the north."
        assert safety_check(raw, polished)[0] is False
        assert safety_check(raw, polished, dictionary=["Jean-Claude Duvernay"]) == (True, None)


class TestFactRules:
    @pytest.mark.parametrize("raw, polished", [
        ("I don't agree with the plan", "I agree with the plan."),
        ("I agree with the plan", "I don't agree with the plan."),
        ("we never ship on fridays", "We ship on fridays."),
        ("il ne vient jamais le lundi", "Il vient le lundi."),
        ("je n'ai rien vu", "J'ai vu."),
    ])
    def test_negation(self, raw, polished):
        assert safety_check(raw, polished) == (False, "negation")

    def test_contraction_expansion_keeps_negation(self):
        assert safety_check("I don't know yet", "I do not know yet.")[1] != "negation"
        assert safety_check("we cannot come", "We can't come.") == (True, None)

    def test_dropped_french_ne_is_not_a_negation_change(self):
        raw = "je pense pas que le client va accepter le devis cette semaine"
        assert safety_check(raw, "Je ne pense pas que le client va accepter le devis cette semaine.") \
            == (True, None)

    @pytest.mark.parametrize("raw, polished", [
        ("is it raining", "Yes, it is raining."),
        ("tu viens ce soir", "Oui, tu viens ce soir."),
        ("you have time today", "Yeah, you have time today."),
    ])
    def test_answer_word_added(self, raw, polished):
        assert safety_check(raw, polished) == (False, "answer_words")

    def test_correction_no_may_be_dropped(self):
        assert safety_check("meet on Monday, no, Tuesday", "Meet on Tuesday.") == (True, None)
        assert safety_check("meet on Monday no wait Tuesday", "Meet on Tuesday.") == (True, None)
        assert safety_check("on se voit lundi non pardon mardi", "On se voit mardi.") == (True, None)

    def test_other_no_may_not_be_dropped(self):
        raw = "there is no problem with the new contract for the team in Paris"
        assert safety_check(raw, "There is a problem with the new contract for the team in Paris.") \
            == (False, "answer_words")

    def test_digits_counted(self):
        raw = "we need 2 rooms on the 2nd floor and 3 projectors for the training session"
        assert safety_check(raw, "We need 2 rooms on the 2nd floor and 2 projectors for the training session.") \
            == (False, "numbers")

    def test_number_words(self):
        raw = "please book twenty seats for the dinner with the partners next to the station"
        assert safety_check(raw, "Please book thirty seats for the dinner with the partners next to the "
                                 "station.") == (False, "numbers")
        assert safety_check(raw, "Please book twenty seats for the dinner with the partners next to the "
                                 "station.") == (True, None)

    def test_self_corrected_number_word(self):
        assert safety_check("on se voit à trois heures, non, quatre heures", "On se voit à quatre heures.") \
            == (True, None)

    @pytest.mark.parametrize("raw, polished", [
        ("the launch moved to tomorrow for the whole team and the partners in Berlin",
         "The launch moved to today for the whole team and the partners in Berlin."),
        ("la réunion est jeudi avec toute l'équipe et les partenaires de Berlin",
         "La réunion est vendredi avec toute l'équipe et les partenaires de Berlin."),
    ])
    def test_dates(self, raw, polished):
        assert safety_check(raw, polished) == (False, "dates")

    def test_dictated_colon_line_is_fine(self):
        raw = "the agenda for tomorrow first the budget second the hiring plan"
        assert safety_check(raw, "The agenda for tomorrow:\n- the budget\n- the hiring plan") == (True, None)


class TestFrenchNormalization:
    def test_accents_and_case_are_ignored(self):
        raw = "le cafe est pres de l'ecole et on y va apres le travail avec toute l'equipe"
        assert safety_check(raw, "Le café est près de l'école et on y va après le travail avec toute l'équipe.") \
            == (True, None)

    def test_elisions(self):
        raw = "je pense que il faut que on parle de le projet avec les gens de la direction demain"
        assert safety_check(raw, "Je pense qu'il faut qu'on parle du projet avec les gens de la direction "
                                 "demain.") == (True, None)

    def test_typographic_apostrophe(self):
        raw = "j'ai vu l'article hier soir et c'est vraiment intéressant pour notre équipe"
        assert safety_check(raw, "J’ai vu l’article hier soir et c’est vraiment intéressant pour "
                                 "notre équipe.") == (True, None)

    def test_french_typography_spaces(self):
        raw = "tu as fini le rapport pour le client de lundi"
        assert safety_check(raw, "Tu as fini le rapport pour le client de lundi ?") == (True, None)


class TestShortInputMode:
    def test_one_changed_word_is_tolerated(self):
        # Long mode would reject this (1 novel word out of 2 is far above the share)
        assert safety_check("ok call jon later", "OK, call John later.") == (True, None)

    def test_two_changed_words_fail(self):
        assert safety_check("please call jon and mary later", "Please call John and Marie later.") \
            == (False, "novel_words")

    def test_added_words_fail(self):
        assert safety_check("call me tomorrow", "Please call me back tomorrow.") == (False, "short_length")

    def test_dropped_content_fails(self):
        assert safety_check("buy milk eggs bread and butter", "Buy milk.") == (False, "short_dropped")
        assert safety_check("ok call jon later", "OK, phone John later.") == (False, "short_dropped")

    def test_filler_only_shrink_is_fine(self):
        # ratio 4/16 would fail the long-mode length rule
        assert safety_check("uh um yes um uh", "Yes.") == (True, None)

    def test_self_correction_in_short_input(self):
        assert safety_check("lundi, non, mardi", "Mardi.") == (True, None)
        assert safety_check("lundi non pardon mardi", "Mardi.") == (True, None)

    def test_short_answered_question(self):
        assert safety_check("quelle heure est-il", "Il est midi.")[0] is False


# ---------------------------------------------------------------- prompt

class TestPrompt:
    def test_structure(self):
        messages = polish.build_messages("euh bonjour", "fr")
        assert messages[0]["role"] == "system"
        roles = [m["role"] for m in messages[1:]]
        assert roles == ["user", "assistant"] * len(polish.FEW_SHOTS) + ["user"]
        last = messages[-1]["content"]
        assert last.endswith("<transcript>\neuh bonjour\n</transcript>")
        assert "Detected language: French" in last

    def test_rules_are_in_the_system_prompt(self):
        system = polish.build_messages("x")[0]["content"]
        for needle in ("not a request to you", "Never translate", "self-corrections", "filler",
                       "Never answer or act", "euh", "du coup", "je veux dire", "space before ?",
                       "never turn words into digits", "Output only the cleaned text"):
            assert needle in system

    def test_unknown_language_is_omitted(self):
        assert "Detected language" not in polish.build_messages("x", None)[-1]["content"]
        assert "Detected language" not in polish.build_messages("x", "zz")[-1]["content"]

    def test_dictionary(self):
        system = polish.build_messages("x", dictionary=["Kubeflow", "", "Jean-Claude"])[0]["content"]
        assert "Kubeflow, Jean-Claude." in system
        assert "names and terms" not in polish.build_messages("x")[0]["content"]

    def test_few_shots_half_english_half_french(self):
        languages = [lang for lang, _, _ in polish.FEW_SHOTS]
        assert 5 <= len(languages) <= 10
        assert languages.count("en") == languages.count("fr")

    @pytest.mark.parametrize("language, raw, polished", polish.FEW_SHOTS)
    def test_few_shots_pass_the_safety_check(self, language, raw, polished):
        assert safety_check(raw, polished) == (True, None)

    def test_few_shots_are_delimited(self):
        for message in polish.build_messages("x")[1:-1:2]:
            assert message["content"].count("<transcript>") == 1

    def test_max_tokens_grows_with_input(self):
        assert polish.max_tokens_for("a" * 1000) > polish.max_tokens_for("a" * 100) >= 32


# --------------------------------------------------------- orchestration

class TestPolish:
    def test_applied(self):
        client = FakeClient(f"  {LONG_OK}\n")
        result = polish.polish(LONG_RAW, client, language="en")
        assert result.applied and result.text == LONG_OK and result.fallback_reason is None
        assert result.raw_text == LONG_RAW
        assert result.latency_ms is not None and result.latency_ms >= 0
        messages, max_tokens = client.calls[0]
        assert LONG_RAW in messages[-1]["content"] and max_tokens == polish.max_tokens_for(LONG_RAW)

    def test_result_fields(self):
        fields = polish.polish(LONG_RAW, FakeClient(LONG_OK)).to_fields()
        assert fields["polished_text"] == LONG_OK
        assert fields["polish"]["applied"] is True and fields["polish"]["model"] == "fake-model"
        assert fields["polish"]["fallback_reason"] is None
        assert "text" not in fields  # the raw Whisper text is never replaced
        json.dumps(fields)

    def test_fallback_fields(self):
        result = polish.polish(LONG_RAW, FakeClient(error=PolishTimeout("slow")))
        fields = result.to_fields()
        assert "polished_text" not in fields and "text" not in fields
        assert fields["polish"] == {"applied": False, "model": "fake-model",
                                    "latency_ms": result.latency_ms, "fallback_reason": "timeout"}
        assert result.text == LONG_RAW
        assert "timeout" in result.warning()
        assert polish.polish(LONG_RAW, FakeClient(LONG_OK)).warning() is None

    @pytest.mark.parametrize("error, reason", [
        (PolishTimeout("slow"), "timeout"),
        (PolishError("HTTP 500"), "error"),
        (RuntimeError("unexpected"), "error"),
        (ConnectionError("refused"), "error"),
    ])
    def test_client_errors_fall_back(self, error, reason):
        result = polish.polish(LONG_RAW, FakeClient(error=error))
        assert not result.applied and result.fallback_reason == reason and result.text == LONG_RAW

    @pytest.mark.parametrize("answer, rule", [
        (None, "empty"),
        ("", "empty"),
        ("Sure! " + LONG_OK, "assistant_opener"),
        ("We deployed it.", "length_ratio"),
        (LONG_OK.replace("yesterday", "on 3 May"), "numbers"),
        ("<transcript>" + LONG_OK + "</transcript>", "delimiter"),
    ])
    def test_failed_safety_check_falls_back(self, answer, rule):
        result = polish.polish(LONG_RAW, FakeClient(answer))
        assert result.fallback_reason == f"safety_check:{rule}" and result.text == LONG_RAW

    def test_non_string_answer_falls_back(self):
        assert polish.polish(LONG_RAW, FakeClient(answer=42)).fallback_reason == "error"

    @pytest.mark.parametrize("text", ["", "   ", None, "word " * (polish.MAX_WORDS + 1)])
    def test_unsupported_input_skips_the_llm(self, text):
        client = FakeClient(LONG_OK)
        result = polish.polish(text, client)
        assert result.fallback_reason == "unsupported" and not client.calls
        assert result.latency_ms is None and result.text == (text or "")

    def test_word_limit_is_inclusive(self):
        text = "word " * polish.MAX_WORDS
        client = FakeClient(text.strip())
        assert polish.polish(text, client).applied and client.calls

    def test_dictionary_reaches_prompt_and_check(self):
        raw = "we deployed the new pipeline on cube flow yesterday and it worked"
        polished = "We deployed the new pipeline on Kubeflow yesterday and it worked."
        client = FakeClient(polished)
        assert polish.polish(raw, client).fallback_reason == "safety_check:novel_words"
        result = polish.polish(raw, client, dictionary=("Kubeflow",))
        assert result.applied
        assert "Kubeflow" in client.calls[-1][0][0]["content"]

    def test_fallback_logged_without_transcript(self, caplog):
        with caplog.at_level(logging.INFO, logger="app.polish"):
            polish.polish(LONG_RAW, FakeClient("Sure! " + LONG_OK))
        assert "safety_check:assistant_opener" in caplog.text
        assert "pipeline" not in caplog.text

    def test_error_logged_without_transcript(self, caplog):
        with caplog.at_level(logging.INFO, logger="app.polish"):
            polish.polish(LONG_RAW, FakeClient(error=PolishError("HTTP 503")))
        assert "HTTP 503" in caplog.text and "pipeline" not in caplog.text


class TestTuningLog:
    def test_appends_one_json_line_per_attempt(self, tmp_path):
        path = tmp_path / "polish.jsonl"
        polish.polish(LONG_RAW, FakeClient(LONG_OK), language="en", log_path=str(path))
        polish.polish("euh bonjour", FakeClient(error=PolishTimeout("slow")), language="fr", log_path=str(path))
        polish.polish("", FakeClient(LONG_OK), log_path=str(path))
        records = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
        assert [r["applied"] for r in records] == [True, False, False]
        assert records[0]["raw"] == LONG_RAW and records[0]["polished"] == LONG_OK
        assert records[0]["language"] == "en" and records[0]["model"] == "fake-model"
        assert records[1]["fallback_reason"] == "timeout" and records[1]["polished"] is None
        assert records[1]["raw"] == "euh bonjour"
        assert records[2]["fallback_reason"] == "unsupported" and records[2]["latency_ms"] is None
        assert all(set(r) >= {"time", "raw", "polished", "applied", "fallback_reason", "latency_ms"}
                   for r in records)

    def test_unicode_is_kept_readable(self, tmp_path):
        path = tmp_path / "polish.jsonl"
        polish.polish("euh on se voit à l'école", FakeClient("On se voit à l'école."), log_path=str(path))
        assert "à l'école" in path.read_text(encoding="utf-8")

    def test_unwritable_log_never_raises(self, tmp_path, caplog):
        result = polish.polish(LONG_RAW, FakeClient(LONG_OK), log_path=str(tmp_path / "missing" / "x.jsonl"))
        assert result.applied
        assert "POLISH_LOG" in caplog.text

    def test_off_by_default(self, tmp_path, monkeypatch):
        monkeypatch.chdir(tmp_path)
        polish.polish(LONG_RAW, FakeClient(LONG_OK))
        assert list(tmp_path.iterdir()) == []


# ---------------------------------------------------------------- client

def _completion(content="Hello.", finish_reason="stop"):
    return {"choices": [{"index": 0, "message": {"role": "assistant", "content": content},
                         "finish_reason": finish_reason}]}


def _client(handler, **kwargs):
    return PolishClient(transport=httpx.MockTransport(handler), **kwargs)


class TestPolishClient:
    def test_request(self):
        seen = {}

        def handler(request):
            seen["url"] = str(request.url)
            seen["auth"] = request.headers.get("authorization")
            seen["body"] = json.loads(request.content)
            return httpx.Response(200, json=_completion("Hi."))

        client = _client(handler, base_url="http://llm.test:11434/v1/", model="m:7b")
        messages = [{"role": "user", "content": "hi"}]
        assert client.complete(messages, max_tokens=50) == "Hi."
        assert seen["url"] == "http://llm.test:11434/v1/chat/completions"
        assert seen["auth"] is None
        assert seen["body"] == {"model": "m:7b", "messages": messages, "temperature": 0,
                                "stream": False, "max_tokens": 50}

    def test_api_key_header(self):
        seen = {}

        def handler(request):
            seen["auth"] = request.headers.get("authorization")
            return httpx.Response(200, json=_completion())

        _client(handler, api_key="sk-test").complete([])
        assert seen["auth"] == "Bearer sk-test"

    def test_defaults(self):
        client = PolishClient()
        assert client.base_url == "http://localhost:11434/v1"
        assert client.model == config.DEFAULT_POLISH_MODEL and client.timeout == 5.0

    @pytest.mark.parametrize("exc", [httpx.ReadTimeout("slow"), httpx.ConnectTimeout("slow")])
    def test_timeout(self, exc):
        def handler(request):
            raise exc

        with pytest.raises(PolishTimeout):
            _client(handler).complete([])

    def test_connection_error(self):
        def handler(request):
            raise httpx.ConnectError("refused")

        with pytest.raises(PolishError) as info:
            _client(handler).complete([])
        assert not isinstance(info.value, PolishTimeout)

    @pytest.mark.parametrize("response", [
        httpx.Response(500, text="boom"),
        httpx.Response(404, json={"error": "model not found"}),
        httpx.Response(200, text="not json"),
        httpx.Response(200, json={"choices": []}),
        httpx.Response(200, json={"choices": [{"message": {"content": None}}]}),
        httpx.Response(200, json=_completion("cut off mid", finish_reason="length")),
    ])
    def test_bad_responses(self, response):
        with pytest.raises(PolishError):
            _client(lambda r: response).complete([])

    def test_end_to_end_with_polish(self):
        client = _client(lambda r: httpx.Response(200, json=_completion(LONG_OK)), model="m")
        result = polish.polish(LONG_RAW, client, language="en")
        assert result.applied and result.model == "m"

    def test_end_to_end_timeout_falls_back(self):
        def handler(request):
            raise httpx.ReadTimeout("slow")

        result = polish.polish(LONG_RAW, _client(handler))
        assert result.fallback_reason == "timeout" and result.text == LONG_RAW

    def test_end_to_end_server_down_falls_back(self):
        def handler(request):
            raise httpx.ConnectError("refused")

        assert polish.polish(LONG_RAW, _client(handler)).fallback_reason == "error"

    def test_from_settings(self):
        settings = config.Settings(polish_base_url="http://x.test/v1", polish_model="m",
                                   polish_api_key="k", polish_timeout=2.5)
        client = polish.client_from_settings(settings)
        assert (client.base_url, client.model, client.api_key, client.timeout) == ("http://x.test/v1", "m", "k", 2.5)


class _SlowServer:
    """A real local HTTP server that misbehaves on purpose.

    ``trickle``: sends headers, then one body byte every 0.3 s for ~10 s.
    ``silent``: accepts the connection and never answers.
    ``ok``: answers at once with a valid completion.
    """

    def __init__(self, mode):
        self.mode = mode
        self.sock = socket.socket()
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(5)
        self.url = f"http://127.0.0.1:{self.sock.getsockname()[1]}/v1"
        self.stop = threading.Event()
        threading.Thread(target=self._serve, daemon=True).start()

    def _serve(self):
        self.sock.settimeout(0.2)
        while not self.stop.is_set():
            try:
                conn, _ = self.sock.accept()
            except OSError:
                continue
            threading.Thread(target=self._handle, args=(conn,), daemon=True).start()

    def _handle(self, conn):
        with conn:
            try:
                conn.recv(65536)
                if self.mode == "silent":
                    self.stop.wait(10)
                    return
                body = json.dumps(_completion(LONG_OK)).encode()
                conn.sendall(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                             b"Content-Length: " + str(len(body)).encode() + b"\r\n\r\n")
                if self.mode == "ok":
                    conn.sendall(body)
                    return
                for byte in body[:30]:
                    if self.stop.wait(0.3):
                        return
                    conn.sendall(bytes([byte]))
            except OSError:
                pass

    def close(self):
        self.stop.set()
        self.sock.close()


@pytest.fixture
def slow_server():
    servers = []

    def factory(mode):
        server = _SlowServer(mode)
        servers.append(server)
        return server

    yield factory
    for server in servers:
        server.close()


class TestHardTimeout:
    @pytest.mark.parametrize("mode", ["trickle", "silent"])
    def test_polish_returns_within_the_timeout(self, slow_server, mode):
        client = PolishClient(slow_server(mode).url, "m", timeout=1.0)
        started = time.monotonic()
        result = polish.polish(LONG_RAW, client)
        elapsed = time.monotonic() - started
        assert result.fallback_reason == "timeout" and result.text == LONG_RAW
        assert elapsed < 2.5, f"took {elapsed:.2f}s"

    def test_slow_handler_is_cut_off(self):
        def handler(request):
            time.sleep(2)
            return httpx.Response(200, json=_completion())

        started = time.monotonic()
        with pytest.raises(PolishTimeout):
            _client(handler, timeout=0.5).complete([])
        assert time.monotonic() - started < 1.8

    def test_real_server_answer(self, slow_server):
        client = PolishClient(slow_server("ok").url, "m", timeout=2.0)
        assert polish.polish(LONG_RAW, client).applied


class TestProxy:
    @pytest.mark.parametrize("url, local", [
        ("http://localhost:11434/v1", True),
        ("http://127.0.0.1:11434/v1", True),
        ("http://[::1]:11434/v1", True),
        ("http://ollama:11434/v1", True),
        ("https://api.example.com/v1", False),
        ("http://192.168.1.20:11434/v1", False),
    ])
    def test_is_local(self, url, local):
        assert polish._is_local(url) is local

    def test_local_endpoint_ignores_proxy_env(self, slow_server, monkeypatch):
        # a dead proxy: the request only succeeds if it is not used
        monkeypatch.setenv("HTTP_PROXY", "http://127.0.0.1:9")
        monkeypatch.setenv("http_proxy", "http://127.0.0.1:9")
        monkeypatch.delenv("NO_PROXY", raising=False)
        monkeypatch.delenv("no_proxy", raising=False)
        client = PolishClient(slow_server("ok").url, "m", timeout=2.0)
        assert polish.polish(LONG_RAW, client).applied


# ------------------------------------------------------------ dictionary

class TestDictionary:
    def test_load(self, tmp_path):
        path = tmp_path / "dict.txt"
        path.write_text("# names\nKubeflow\n\n  Jean-Claude Duvernay  \nRémi\n", encoding="utf-8")
        assert polish.load_dictionary(str(path)) == ("Kubeflow", "Jean-Claude Duvernay", "Rémi")

    def test_unset(self):
        assert polish.load_dictionary(None) == () and polish.load_dictionary("") == ()

    def test_missing_file_is_ignored(self, tmp_path, caplog):
        assert polish.load_dictionary(str(tmp_path / "nope.txt")) == ()
        assert "POLISH_DICTIONARY" in caplog.text

    def test_capped(self, tmp_path, caplog):
        path = tmp_path / "dict.txt"
        path.write_text("\n".join(f"term{i}" for i in range(polish.MAX_DICTIONARY_TERMS + 5)))
        assert len(polish.load_dictionary(str(path))) == polish.MAX_DICTIONARY_TERMS
        assert "POLISH_DICTIONARY" in caplog.text


# ---------------------------------------------------------------- config

POLISH_ENV = ("WHISPER_POLISH", "POLISH_BASE_URL", "POLISH_MODEL", "POLISH_API_KEY",
              "POLISH_TIMEOUT", "POLISH_DICTIONARY", "POLISH_LOG")


class TestPolishConfig:
    @pytest.fixture(autouse=True)
    def clean_env(self, monkeypatch):
        for name in POLISH_ENV:
            monkeypatch.delenv(name, raising=False)

    def test_defaults(self):
        s = config.load_settings()
        assert s.polish_default is False
        assert s.polish_base_url == "http://localhost:11434/v1"
        assert s.polish_model == "qwen2.5:7b-instruct-q4_K_M"
        assert s.polish_api_key is None and s.polish_timeout == 5.0
        assert s.polish_dictionary is None and s.polish_log is None

    def test_values(self, monkeypatch):
        monkeypatch.setenv("WHISPER_POLISH", "true")
        monkeypatch.setenv("POLISH_BASE_URL", "http://ollama:11434/v1/")
        monkeypatch.setenv("POLISH_MODEL", "mistral:7b-instruct")
        monkeypatch.setenv("POLISH_API_KEY", "sk-secret")
        monkeypatch.setenv("POLISH_TIMEOUT", "2.5")
        monkeypatch.setenv("POLISH_DICTIONARY", "/data/dict.txt")
        monkeypatch.setenv("POLISH_LOG", "/data/polish.jsonl")
        s = config.load_settings()
        assert s.polish_default is True
        assert s.polish_base_url == "http://ollama:11434/v1"
        assert s.polish_model == "mistral:7b-instruct" and s.polish_api_key == "sk-secret"
        assert s.polish_timeout == 2.5
        assert (s.polish_dictionary, s.polish_log) == ("/data/dict.txt", "/data/polish.jsonl")

    @pytest.mark.parametrize("name, value, attr, expected", [
        ("POLISH_TIMEOUT", "fast", "polish_timeout", 5.0),
        ("POLISH_TIMEOUT", "0", "polish_timeout", 5.0),
        ("POLISH_TIMEOUT", "1000", "polish_timeout", 5.0),
        ("POLISH_TIMEOUT", "nan", "polish_timeout", 5.0),
        ("POLISH_BASE_URL", "ollama:11434", "polish_base_url", "http://localhost:11434/v1"),
    ])
    def test_invalid_values_fall_back(self, monkeypatch, caplog, name, value, attr, expected):
        monkeypatch.setenv(name, value)
        assert getattr(config.load_settings(), attr) == expected
        assert name in caplog.text

    def test_blank_values_use_defaults(self, monkeypatch):
        for name in POLISH_ENV:
            monkeypatch.setenv(name, "  ")
        s = config.load_settings()
        assert s.polish_model == config.DEFAULT_POLISH_MODEL and s.polish_api_key is None
        assert s.polish_timeout == 5.0

    def test_api_key_not_in_repr(self, monkeypatch):
        monkeypatch.setenv("POLISH_API_KEY", "sk-secret")
        assert "sk-secret" not in repr(config.load_settings())
