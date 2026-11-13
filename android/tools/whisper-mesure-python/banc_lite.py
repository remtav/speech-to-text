"""Banc de l'issue #25 : sherpa-onnx 2.0.1, langue fr forcée, tailPaddings 1000, N threads.
usage : python banc_lite.py REF_DIR THREADS REPEATS FILTRE nom=encodeur,decodeur,tokens [...]
FILTRE : préfixes de fichiers séparés par des virgules (ex. fr1,frqc) ou « * ».
Temps total = somme, par clip, du meilleur des REPEATS passages. Les modèles sont mesurés
en alternance clip par clip (même charge machine pour tous)."""
import os, re, sys, time, unicodedata
import numpy as np, soundfile as sf, sherpa_onnx

ref_dir, threads, repeats, filt = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
models = []
for spec in sys.argv[5:]:
    name, files = spec.split("=", 1)
    enc, dec, tok = files.split(",")
    models.append((name, sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=enc, decoder=dec, tokens=tok, language="fr", task="transcribe",
        num_threads=threads, tail_paddings=1000)))

def norm(s):
    s = unicodedata.normalize("NFC", s.lower()).replace("’", "'")
    s = re.sub(r"[^\w'\- ]+", " ", s).replace("-", " ")
    return s.split()

def errs(ref, hyp):
    r, h = norm(ref), norm(hyp)
    d = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(h) + 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (r[i - 1] != h[j - 1]))
            prev, d[j] = d[j], cur
    return d[len(h)], len(r)

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
    s = rec.create_stream(); s.accept_waveform(16000, a); rec.decode_stream(s)
    return s.result.text.strip()

for _, rec in models:
    run(rec, refs[0][2])
tot = {n: [0.0, 0, 0] for n, _ in models}
hyps = {n: {} for n, _ in models}
for f, text, a in refs:
    best = {n: 1e9 for n, _ in models}
    for _ in range(repeats):
        for n, rec in models:
            t0 = time.perf_counter(); h = run(rec, a); best[n] = min(best[n], time.perf_counter() - t0)
            hyps[n][f] = h
    for n, _ in models:
        e, w = errs(text, hyps[n][f])
        tot[n][0] += best[n]; tot[n][1] += e; tot[n][2] += w
        if len(refs) <= 20:
            print(f"CLIP|{n}|{f}|{best[n]:.2f}s|err {e}/{w}|{hyps[n][f]}", flush=True)
base = models[0][0]
for n, _ in models:
    diff = sum(norm(hyps[n][f]) != norm(hyps[base][f]) for f, _, _ in refs)
    print(f"SUM|{n}|{len(refs)} clips|temps {tot[n][0]:.2f}s|WER {tot[n][1]}/{tot[n][2]} = "
          f"{100*tot[n][1]/max(tot[n][2],1):.1f}%|phrases différentes de {base}: {diff}", flush=True)
