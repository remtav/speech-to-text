"""Banc de l'issue #40 (protocole de #25, `../whisper-mesure-python/banc_lite.py`), avec une marge par variante.

usage : python banc_rapide.py REF_DIR THREADS REPEATS FILTRE SORTIE.tsv nom=enc,dec,tokens[,marge] [...]

sherpa-onnx 2.0.1 Python, langue fr forcée, `tail_paddings` = marge (1 000 par défaut). Les variantes sont mesurées en
alternance clip par clip (même charge machine pour toutes) ; la première est la référence. Temps d'un clip = meilleur
des REPEATS passages. SORTIE.tsv reçoit une ligne par clip et par variante (clip, variante, marge, secondes, durée de
l'audio, nombre de jetons, plafond de jetons, référence, hypothèse) : `reprise.py` simule la marge courte avec reprise
à partir de ce fichier."""
import os
import sys
import time

import sherpa_onnx
import soundfile as sf

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from wer import errs, norm  # noqa: E402

ref_dir, threads, repeats, filt, out = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4], sys.argv[5]
models = []
for spec in sys.argv[6:]:
    name, files = spec.split("=", 1)
    parts = files.split(",")
    enc, dec, tok = parts[:3]
    pad = int(parts[3]) if len(parts) > 3 else 1000
    models.append((name, pad, sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=enc, decoder=dec, tokens=tok, language="fr", task="transcribe",
        num_threads=threads, tail_paddings=pad)))

prefixes = None if filt == "*" else filt.split(",")
refs = []
for line in open(os.path.join(ref_dir, "references.tsv"), encoding="utf-8"):
    if line.startswith("#") or not line.strip():
        continue
    f, lang, text = line.rstrip("\n").split("\t")[:3]
    if lang != "fr" or (prefixes and not any(f.startswith(p) for p in prefixes)):
        continue
    a, sr = sf.read(os.path.join(ref_dir, f), dtype="float32")
    assert sr == 16000
    refs.append((f, text, a))


def run(rec, a):
    s = rec.create_stream()
    s.accept_waveform(16000, a)
    rec.decode_stream(s)
    return s.result.text.strip(), len(s.result.tokens)


for _, _, rec in models:
    run(rec, refs[0][2])
tot = {n: [0.0, 0, 0] for n, _, _ in models}
hyps = {n: {} for n, _, _ in models}
with open(out, "w", encoding="utf-8") as fo:
    fo.write("clip\tvariante\tmarge\tsecondes\taudio_s\tjetons\tplafond\treference\thypothese\n")
    for f, text, a in refs:
        best = {n: 1e9 for n, _, _ in models}
        ntok = {}
        for _ in range(repeats):
            for n, _, rec in models:
                t0 = time.perf_counter()
                h, k = run(rec, a)
                best[n] = min(best[n], time.perf_counter() - t0)
                hyps[n][f], ntok[n] = h, k
        # plafond de sherpa : num_feature_frames / 100 * 6, avec ~1 image par 10 ms
        cap = int((len(a) // 160) / 100.0 * 6)
        for n, pad, _ in models:
            e, w = errs(text, hyps[n][f])
            tot[n][0] += best[n]
            tot[n][1] += e
            tot[n][2] += w
            fo.write(f"{f}\t{n}\t{pad}\t{best[n]:.4f}\t{len(a)/16000:.3f}\t{ntok[n]}\t{cap}\t{text}\t{hyps[n][f]}\n")
            if len(refs) <= 20:
                print(f"CLIP|{n}|{f}|{best[n]:.2f}s|err {e}/{w}|{ntok[n]} jetons|{hyps[n][f]}", flush=True)
        fo.flush()
base = models[0][0]
for n, _, _ in models:
    diff = sum(norm(hyps[n][f]) != norm(hyps[base][f]) for f, _, _ in refs)
    print(f"SUM|{n}|{len(refs)} clips|temps {tot[n][0]:.2f}s ({100*(tot[n][0]/tot[base][0]-1):+.1f} %)|"
          f"WER {tot[n][1]}/{tot[n][2]} = {100*tot[n][1]/max(tot[n][2],1):.1f}%|phrases différentes de {base}: {diff}",
          flush=True)
