"""
Tests for speaker post-processing (app/speakers.py).

Run with: pytest tests/ -v
"""
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent.parent))
from app.speakers import (
    split_segments_by_speaker,
    list_speakers,
    format_speaker_transcript,
    to_builtin,
)


def _word(text, start, end, speaker=None):
    w = {"word": text, "start": start, "end": end, "score": 0.9}
    if speaker:
        w["speaker"] = speaker
    return w


class TestSplitSegmentsBySpeaker:
    def test_single_speaker_segment_kept_intact(self):
        seg = {"start": 0.0, "end": 1.0, "text": " Hello there.", "speaker": "SPEAKER_00",
               "words": [_word("Hello", 0.0, 0.4, "SPEAKER_00"), _word("there.", 0.5, 1.0, "SPEAKER_00")]}
        result = split_segments_by_speaker([seg])
        assert len(result) == 1
        assert result[0]["text"] == " Hello there."
        assert result[0]["speaker"] == "SPEAKER_00"
        assert result[0]["id"] == 0

    def test_splits_on_speaker_change(self):
        seg = {"start": 0.0, "end": 3.0, "text": "Hello. Hi, how are you?", "speaker": "SPEAKER_01",
               "words": [
                   _word("Hello.", 0.0, 0.8, "SPEAKER_00"),
                   _word("Hi,", 1.4, 1.7, "SPEAKER_01"),
                   _word("how", 1.8, 2.0, "SPEAKER_01"),
                   _word("are", 2.1, 2.3, "SPEAKER_01"),
                   _word("you?", 2.4, 3.0, "SPEAKER_01"),
               ]}
        result = split_segments_by_speaker([seg])
        assert [s["speaker"] for s in result] == ["SPEAKER_00", "SPEAKER_01"]
        assert result[0]["text"] == "Hello."
        assert (result[0]["start"], result[0]["end"]) == (0.0, 0.8)
        assert result[1]["text"] == "Hi, how are you?"
        assert (result[1]["start"], result[1]["end"]) == (1.4, 3.0)
        assert [s["id"] for s in result] == [0, 1]

    def test_unlabelled_words_stay_with_neighbours(self):
        seg = {"start": 0.0, "end": 4.0, "text": "In 2024 yes. Okay 42", "words": [
            {"word": "In"},  # unaligned leading word
            _word("2024", 0.5, 1.0, "SPEAKER_00"),
            _word("yes.", 1.1, 1.5, "SPEAKER_00"),
            _word("Okay", 2.0, 2.5, "SPEAKER_01"),
            {"word": "42"},  # unaligned trailing word
        ]}
        result = split_segments_by_speaker([seg])
        assert [s["text"] for s in result] == ["In 2024 yes.", "Okay 42"]
        assert [s["speaker"] for s in result] == ["SPEAKER_00", "SPEAKER_01"]
        assert result[1]["end"] == 2.5

    def test_languages_without_spaces(self):
        seg = {"start": 0.0, "end": 2.0, "text": "你好谢谢", "words": [
            _word("你", 0.0, 0.2, "SPEAKER_00"), _word("好", 0.2, 0.4, "SPEAKER_00"),
            _word("谢", 1.0, 1.2, "SPEAKER_01"), _word("谢", 1.2, 1.4, "SPEAKER_01"),
        ]}
        result = split_segments_by_speaker([seg], language="zh")
        assert [s["text"] for s in result] == ["你好", "谢谢"]

    def test_unaligned_segments_use_segment_speaker(self):
        segs = [{"start": 0.0, "end": 1.0, "text": "Hola.", "speaker": "SPEAKER_00"},
                {"start": 1.0, "end": 2.0, "text": "Buenas.", "speaker": "SPEAKER_01"}]
        result = split_segments_by_speaker(segs)
        assert [s["speaker"] for s in result] == ["SPEAKER_00", "SPEAKER_01"]
        assert [s["id"] for s in result] == [0, 1]

    def test_does_not_mutate_input(self):
        seg = {"start": 0.0, "end": 1.0, "text": "Hi", "words": [_word("Hi", 0.0, 1.0, "SPEAKER_00")]}
        split_segments_by_speaker([seg])
        assert "id" not in seg and "speaker" not in seg


class TestSpeakerTranscript:
    def test_list_speakers_in_order_of_appearance(self):
        segs = [{"speaker": "SPEAKER_01"}, {"speaker": "SPEAKER_00"}, {"speaker": "SPEAKER_01"}, {}]
        assert list_speakers(segs) == ["SPEAKER_01", "SPEAKER_00"]

    def test_format_merges_consecutive_turns(self):
        segs = [
            {"text": " Hello.", "speaker": "SPEAKER_00"},
            {"text": "How are you?", "speaker": "SPEAKER_00"},
            {"text": "Fine.", "speaker": "SPEAKER_01"},
            {"text": "   ", "speaker": "SPEAKER_01"},
            {"text": "Good.", "speaker": None},
        ]
        assert format_speaker_transcript(segs) == (
            "SPEAKER_00: Hello. How are you?\nSPEAKER_01: Fine.\nUNKNOWN: Good."
        )

    def test_format_empty(self):
        assert format_speaker_transcript([]) == ""


class TestToBuiltin:
    def test_converts_numpy_and_nan(self):
        data = {"a": np.float32(1.5), "b": [np.int64(3), float("nan")], "c": "x", "d": None, "e": True}
        assert to_builtin(data) == {"a": 1.5, "b": [3, None], "c": "x", "d": None, "e": True}
        assert type(to_builtin(np.int64(3))) is int
