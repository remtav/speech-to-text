"""Banc de la piste 2.2 de l'issue #40 : comme `banc_rapide.py`, mais avec la réplique Python (`replique.py`) au lieu de
sherpa-onnx, pour les encodeurs qui prennent l'entrée `n_frames` (`fusion_silence.py`).

usage : python banc_replique.py REF_DIR THREADS REPEATS FILTRE SORTIE.tsv DEC TOKENS nom=encodeur[,marge] [...]

La première variante est la référence (en général turbo int8, marge 1 000, dans la même réplique). Même format de sortie
que `banc_rapide.py` (lisible par `reprise.py`), avec en plus le temps de l'encodeur seul dans la colonne `enc_s`."""
import os
import sys
import time

import soundfile as sf

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from replique import Whisper, features  # noqa: E402
from wer import errs, norm  # noqa: E402

ref_dir, threads, repeats, filt, out, dec, tok = sys.argv[1:8]
threads, repeats = int(threads), int(repeats)
models = []
for spec in sys.argv[8:]:
    name, files = spec.split("=", 1)
    parts = files.split(",")
    models.append((name, int(parts[1]) if len(parts) > 1 else 1000, Whisper(parts[0], dec, tok, threads)))

prefixes = None if filt == "*" else filt.split(",")
refs = []
for line in open(os.path.join(ref_dir, "references.tsv"), encoding="utf-8"):
    if line.startswith("#") or not line.strip():
        continue
    f, lang, text = line.rstrip("\n").split("\t")[:3]
    if lang != "fr" or (prefixes and not any(f.startswith(p) for p in prefixes)):
        continue
    a, sr = sf.read(os.path.join(ref_dir, f), dtype="float32")
    refs.append((f, text, a))

for _, pad, w in models:
    w.decode(refs[0][2], pad)
tot = {n: [0.0, 0, 0, 0.0] for n, _, _ in models}
hyps = {n: {} for n, _, _ in models}
with open(out, "w", encoding="utf-8") as fo:
    fo.write("clip\tvariante\tmarge\tsecondes\taudio_s\tjetons\tplafond\treference\thypothese\tenc_s\n")
    for f, text, a in refs:
        best = {n: (1e9, 0.0) for n, _, _ in models}
        res = {}
        for _ in range(repeats):
            for n, pad, w in models:
                t0 = time.perf_counter()
                r = w.decode(a, pad)
                dt = time.perf_counter() - t0
                if dt < best[n][0]:
                    best[n] = (dt, r["t_enc"])
                res[n] = r
        for n, pad, _ in models:
            r = res[n]
            hyps[n][f] = r["text"]
            cap = min(int(r["n_frames"] / 100.0 * 6), 224)
            e, wd = errs(text, r["text"])
            tot[n][0] += best[n][0]
            tot[n][1] += e
            tot[n][2] += wd
            tot[n][3] += best[n][1]
            fo.write(f"{f}\t{n}\t{pad}\t{best[n][0]:.4f}\t{len(a)/16000:.3f}\t{len(r['tokens'])}\t{cap}\t{text}\t"
                     f"{r['text']}\t{best[n][1]:.4f}\n")
            if len(refs) <= 20:
                print(f"CLIP|{n}|{f}|{best[n][0]:.2f}s (enc {best[n][1]:.2f}s)|err {e}/{wd}|{len(r['tokens'])} jetons|"
                      f"{r['text']}", flush=True)
        fo.flush()
base = models[0][0]
for n, _, _ in models:
    diff = sum(norm(hyps[n][f]) != norm(hyps[base][f]) for f, _, _ in refs)
    print(f"SUM|{n}|{len(refs)} clips|temps {tot[n][0]:.2f}s ({100*(tot[n][0]/tot[base][0]-1):+.1f} %)|"
          f"encodeur {tot[n][3]:.2f}s ({100*(tot[n][3]/tot[base][3]-1):+.1f} %)|"
          f"WER {tot[n][1]}/{tot[n][2]} = {100*tot[n][1]/max(tot[n][2],1):.1f}%|phrases différentes de {base}: {diff}",
          flush=True)
