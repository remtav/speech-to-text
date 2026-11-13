"""Écart de sortie entre l'encodeur de référence et une variante (issue #40), sur un vrai clip.

usage : python comparer_encodeur.py WAV ENC_REF ENC_VAR [ENC_VAR ...] [--marge 1000] [--threads 2]
Affiche, pour `n_layer_cross_k` et `n_layer_cross_v`, l'erreur relative ‖var − ref‖ / ‖ref‖ (norme de Frobenius)."""
import argparse
import os
import sys

import numpy as np
import soundfile as sf

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from replique import features, session  # noqa: E402

p = argparse.ArgumentParser()
p.add_argument("wav")
p.add_argument("ref")
p.add_argument("var", nargs="+")
p.add_argument("--marge", type=int, default=1000)
p.add_argument("--threads", type=int, default=2)
a = p.parse_args()
audio, _ = sf.read(a.wav, dtype="float32")
f = features(audio)
n = f.shape[0]
mel = np.zeros((1, 128, min(n + a.marge, 3000)), np.float32)
mel[0, :, :n] = f.T
ref = session(a.ref, a.threads).run(None, {"mel": mel})
for v in a.var:
    s = session(v, a.threads)
    feed = {"mel": mel}
    if "n_frames" in [i.name for i in s.get_inputs()]:
        feed["n_frames"] = np.array([n], np.int64)
    out = s.run(None, feed)
    errs = [float(np.linalg.norm(o - r) / np.linalg.norm(r)) for o, r in zip(out, ref)]
    print(f"{v}: erreur relative cross_k {errs[0]:.4f}, cross_v {errs[1]:.4f}")
