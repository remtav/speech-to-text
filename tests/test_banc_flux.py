"""Tests du banc de latence du flux : calculs seulement, aucun modèle."""
import importlib.util
from pathlib import Path

import numpy as np

from app.streaming import StreamState, Word

_CHEMIN = Path(__file__).resolve().parent.parent / "tools" / "banc_flux.py"
_spec = importlib.util.spec_from_file_location("banc_flux", _CHEMIN)
banc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(banc)

SR = 16000


def test_wer():
    taux, erreurs, total = banc.wer("Le chat dort.", "le chat dors")
    assert (erreurs, total) == (1, 3)
    assert abs(taux - 1 / 3) < 1e-9
    assert banc.wer("Été, à l'école !", "ete a lecole") == (0.0, 0, 3)


def test_simulate_with_fake_decoder():
    def decode(samples, prompt):
        n = int(len(samples) / SR)
        return [Word(f"m{i}", float(i), i + 1.0) for i in range(n)], 0.2

    audio = np.zeros(5 * SR, np.float32)
    res = banc.simulate(audio, decode, 1.0, StreamState())
    assert res["text"] == "m0 m1 m2 m3 m4"
    assert len(res["pass_times"]) == 5
    assert res["first_delays"] and all(d > 0 for d in res["first_delays"])
    assert res["confirm_delays"] and all(d > 0 for d in res["confirm_delays"])
    assert res["changed"] == 0
    assert res["positions"] == 5


def test_simulate_counts_changes():
    appels = []

    def decode(samples, prompt):
        appels.append(1)
        texte = "x" if len(appels) == 1 else "a"
        return [Word(texte, 0.0, 0.5)], 0.1

    audio = np.zeros(2 * SR, np.float32)
    res = banc.simulate(audio, decode, 1.0, StreamState())
    assert res["changed"] == 1
    assert res["text"] == "a"


def test_percentile_and_references(tmp_path):
    assert banc.percentile([1, 2, 3, 4, 5], 50) == 3
    assert banc.percentile([], 90) == 0.0
    tsv = tmp_path / "r.tsv"
    tsv.write_text("# file\tlanguage\ttranscript\tsource\nfrqc1.wav\tfr\tBonjour.\tx\n", encoding="utf-8")
    assert banc.load_references(tsv) == {"frqc1": "Bonjour."}
