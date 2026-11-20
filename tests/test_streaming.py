"""
Tests for the streaming session state (app/streaming.py).

Run with: pytest tests/ -v
"""
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent.parent))
from app.streaming import StreamState, Word

SR = 16000


def _w(text, start, end):
    return Word(text, start, end)


def test_two_identical_passes_commit_prefix():
    s = StreamState()
    s.apply([_w("a", 0, 0.4), _w("b", 0.5, 0.9)], 0.0)
    assert s.committed_text == "" and s.provisional_text == "a b"
    s.apply([_w("a", 0, 0.4), _w("b", 0.5, 0.9), _w("c", 1.0, 1.4)], 0.0)
    assert s.committed_text == "a b" and s.provisional_text == "c"


def test_differing_word_stays_provisional():
    s = StreamState()
    s.apply([_w("a", 0, 0.4), _w("b", 0.5, 0.9)], 0.0)
    s.apply([_w("a", 0, 0.4), _w("x", 0.5, 0.9)], 0.0)
    assert s.committed_text == "a" and s.provisional_text == "x"


def test_final_commits_everything():
    s = StreamState()
    s.apply([_w("a", 0, 0.4), _w("b", 0.5, 0.9)], 0.0, final=True)
    assert s.committed_text == "a b" and s.provisional_text == ""


def test_normalized_comparison():
    s = StreamState()
    s.apply([_w("Élève,", 0, 0.5)], 0.0)
    s.apply([_w("eleve", 0, 0.5)], 0.0)
    assert s.committed_text == "eleve"


def test_words_before_last_committed_are_ignored():
    s = StreamState()
    s.apply([_w("a", 0, 2.0)], 0.0, final=True)
    s.apply([_w("a", 1.0, 1.95), _w("b", 2.5, 3.0)], 0.0)
    assert s.committed_text == "a" and s.provisional_text == "b"


def test_offset_makes_times_absolute():
    s = StreamState()
    s.apply([_w("a", 0.0, 0.5)], 10.0, final=True)
    assert s._committed == [Word("a", 10.0, 10.5)]


def test_trim_moves_buffer_start():
    s = StreamState()
    s.add_audio(np.zeros(20 * SR, np.float32))
    s.apply([_w("a", 0.0, 12.0)], 0.0, final=True)
    audio, offset, _ = s.window()
    assert len(audio) == 8 * SR and offset == 12.0


def test_prompt_tail():
    s = StreamState(initial_prompt="x" * 49 + "y")
    words = [Word(f"mot{i:03d}", i, i + 0.5) for i in range(50)]
    s.apply(words, 0.0, final=True)
    assert len(s.committed_text) > 300
    p = s.prompt()
    assert len(p) <= 200
    assert s.committed_text.endswith(p)
    assert p.split(" ")[0].startswith("mot") and len(p.split(" ")[0]) == 6


def test_apply_reports_change():
    s = StreamState()
    assert s.apply([_w("a", 0, 0.4)], 0.0) is True
    assert s.apply([_w("a", 0, 0.4)], 0.0) is True  # a becomes committed
    assert s.apply([_w("a", 0, 0.4)], 0.0) is False
