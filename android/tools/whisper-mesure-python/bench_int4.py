"""Banc de l'issue #26 : Whisper turbo int8 contre les variantes 4 bits (MatMulNBits), sherpa-onnx 2.0.1.

Comparaison (par défaut) : les modèles sont chargés une fois, puis mesurés en alternance clip par clip,
dans la même exécution et avec le même nombre de threads. Langue forcée selon le clip (en pour « en* »,
fr sinon), tailPaddings 1000, comme `SherpaEngine`. Temps = somme, par clip, du meilleur des REPEATS passages.
WER normalisé comme `QuebecWer` (issue #24) ; groupes comme `HostModelTest` : en, fr, frqc (préfixe du fichier).

usage : python bench_int4.py REF_DIR THREADS REPEATS FILTRE nom=encodeur,decodeur,tokens [...]
        FILTRE : préfixes de fichiers séparés par des virgules (ex. frqc,fr1) ou « * ».

Mode « solo » (mémoire maximale et temps de chargement d'un seul modèle, dans un processus à part) :
        python bench_int4.py --solo REF_DIR THREADS nom=encodeur,decodeur,tokens
  imprime SOLO|nom|chargement (s, minimum de 3 chargements successifs)|mémoire résidente maximale (Mio)|temps de
  décodage des clips fr. La mémoire est le pic du processus (ru_maxrss), chargement compris.
"""
import os, re, resource, sys, time, unicodedata
import soundfile as sf, sherpa_onnx


def norm(s):
    s = unicodedata.normalize("NFC", s).lower().replace("’", "'").replace("œ", "oe")
    s = re.sub(r"[^\w' ]+|_", " ", s)
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


def make(enc, dec, tok, lang, threads):
    return sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=enc, decoder=dec, tokens=tok, language=lang, task="transcribe",
        num_threads=threads, tail_paddings=1000)


def run(rec, a):
    s = rec.create_stream(); s.accept_waveform(16000, a); rec.decode_stream(s)
    return s.result.text.strip()


def load_refs(ref_dir, prefixes):
    refs = []
    for line in open(os.path.join(ref_dir, "references.tsv"), encoding="utf-8"):
        if line.startswith("#") or not line.strip():
            continue
        f, lang, text = line.rstrip("\n").split("\t")[:3]
        if prefixes and not any(f.startswith(p) for p in prefixes):
            continue
        a, sr = sf.read(os.path.join(ref_dir, f), dtype="float32")
        assert sr == 16000
        refs.append((f, lang, text, a))
    return refs


def solo(argv):
    ref_dir, threads, spec = argv[0], int(argv[1]), argv[2]
    name, files = spec.split("=", 1)
    enc, dec, tok = files.split(",")
    loads = []
    for _ in range(3):
        t0 = time.perf_counter(); rec = make(enc, dec, tok, "fr", threads); loads.append(time.perf_counter() - t0)
        if _ < 2:
            del rec
    refs = [r for r in load_refs(ref_dir, None) if r[1] == "fr"]
    run(rec, refs[0][3])
    t0 = time.perf_counter()
    for _, _, _, a in refs:
        run(rec, a)
    dt = time.perf_counter() - t0
    print(f"SOLO|{name}|chargement {min(loads):.2f}s (essais {', '.join(f'{x:.2f}' for x in loads)})|"
          f"mémoire max {resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024:.0f} Mio|"
          f"{len(refs)} clips fr {dt:.2f}s", flush=True)


def compare(argv):
    ref_dir, threads, repeats, filt = argv[0], int(argv[1]), int(argv[2]), argv[3]
    prefixes = None if filt == "*" else filt.split(",")
    refs = load_refs(ref_dir, prefixes)
    langs = sorted({r[1] for r in refs})
    models = []  # (nom, {langue: reconnaisseur})
    for spec in argv[4:]:
        name, files = spec.split("=", 1)
        enc, dec, tok = files.split(",")
        recs = {}
        for lang in langs:
            t0 = time.perf_counter(); recs[lang] = make(enc, dec, tok, lang, threads)
            print(f"LOAD|{name}|{lang}|{time.perf_counter() - t0:.2f}s", flush=True)
        models.append((name, recs))
    for _, recs in models:  # échauffement
        for lang, rec in recs.items():
            run(rec, next(r[3] for r in refs if r[1] == lang))
    group = lambda f, lang: "frqc" if f.startswith("frqc") else lang
    tot = {n: {} for n, _ in models}
    hyps = {n: {} for n, _ in models}
    for f, lang, text, a in refs:
        best = {n: 1e9 for n, _ in models}
        for _ in range(repeats):
            for n, recs in models:
                t0 = time.perf_counter(); h = run(recs[lang], a); best[n] = min(best[n], time.perf_counter() - t0)
                hyps[n][f] = h
        for n, _ in models:
            e, w = errs(text, hyps[n][f])
            t = tot[n].setdefault(group(f, lang), [0.0, 0, 0, 0.0])
            t[0] += best[n]; t[1] += e; t[2] += w; t[3] += len(a) / 16000
            if len(refs) <= 20:
                print(f"CLIP|{n}|{f}|{len(a) / 16000:.1f}s|{best[n]:.2f}s|err {e}/{w}|{hyps[n][f]}", flush=True)
    base = models[0][0]
    for n, _ in models:
        for g, (t, e, w, au) in sorted(tot[n].items()):
            print(f"SUM|{n}|{g}|audio {au:.1f}s|temps {t:.2f}s|WER {e}/{w} = {100 * e / max(w, 1):.1f}%", flush=True)
        all_t = sum(v[0] for v in tot[n].values()); all_e = sum(v[1] for v in tot[n].values()); all_w = sum(v[2] for v in tot[n].values())
        diff = sum(norm(hyps[n][f]) != norm(hyps[base][f]) for f, *_ in refs)
        print(f"TOTAL|{n}|{len(refs)} clips|temps {all_t:.2f}s|WER {all_e}/{all_w} = {100 * all_e / max(all_w, 1):.1f}%|"
              f"phrases différentes de {base}: {diff}", flush=True)


if __name__ == "__main__":
    if sys.argv[1] == "--solo":
        solo(sys.argv[2:])
    else:
        compare(sys.argv[1:])
