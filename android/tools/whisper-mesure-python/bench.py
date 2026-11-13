"""Whisper turbo vs medium (sherpa-onnx 2.0.1, int8) on the app's reference clips.
usage: python bench.py MODELS_DIR REF_DIR THREADS"""
import re, sys, time, os, unicodedata
import numpy as np, soundfile as sf, sherpa_onnx

models_dir, ref_dir, threads = sys.argv[1], sys.argv[2], int(sys.argv[3])
MODELS = [("turbo", "turbo"), ("qc", "turbo"), ("medium", "medium")]
REPEATS = 3


def norm(s):
    s = unicodedata.normalize("NFC", s.lower()).replace("’", "'")
    s = re.sub(r"[^\w'\- ]+", " ", s)
    s = s.replace("-", " ")
    return s.split()


def wer_counts(ref, hyp):
    r, h = norm(ref), norm(hyp)
    d = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(h) + 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (r[i - 1] != h[j - 1]))
            prev, d[j] = d[j], cur
    return d[len(h)], len(r)


refs = []
for line in open(os.path.join(ref_dir, "references.tsv"), encoding="utf-8"):
    if line.startswith("#") or not line.strip():
        continue
    f, lang, text = line.rstrip("\n").split("\t")[:3]
    a, sr = sf.read(os.path.join(ref_dir, f), dtype="float32")
    assert sr == 16000
    refs.append((f, lang, text, a))


def cpu():
    t = os.times()
    return t.user + t.system


for name, prefix in MODELS:
    d = os.path.join(models_dir, name)
    recs = {}
    for lang in sorted({r[1] for r in refs}):
        if name == "qc" and lang != "fr":
            continue
        recs[lang] = sherpa_onnx.OfflineRecognizer.from_whisper(
            encoder=f"{d}/{prefix}-encoder.int8.onnx", decoder=f"{d}/{prefix}-decoder.int8.onnx",
            tokens=f"{d}/{prefix}-tokens.txt", language=lang, task="transcribe",
            num_threads=threads, tail_paddings=1000)
    # warm-up
    s = recs["fr"].create_stream(); s.accept_waveform(16000, refs[0][3]); recs["fr"].decode_stream(s)
    tot_err = {}; tot_w = {}; tot_t = {}; tot_c = {}; tot_audio = {}
    for f, lang, text, a in refs:
        if lang not in recs:
            continue
        group = "frqc" if f.startswith("frqc") else lang
        best_w, best_c, hyp = 1e9, 1e9, ""
        for _ in range(REPEATS):
            c0, t0 = cpu(), time.perf_counter()
            s = recs[lang].create_stream(); s.accept_waveform(16000, a); recs[lang].decode_stream(s)
            w, c = time.perf_counter() - t0, cpu() - c0
            hyp = s.result.text.strip()
            best_w, best_c = min(best_w, w), min(best_c, c)
        e, n = wer_counts(text, hyp)
        for k, v in ((tot_err, e), (tot_w, n), (tot_t, best_w), (tot_c, best_c), (tot_audio, len(a) / 16000)):
            k[group] = k.get(group, 0) + v
        print(f"CLIP|{name}|{f}|{len(a)/16000:.1f}s|wall {best_w:.2f}s|cpu {best_c:.2f}s|err {e}/{n}|{hyp}", flush=True)
    for g in tot_err:
        print(f"SUM|{name}|{g}|audio {tot_audio[g]:.1f}s|wall {tot_t[g]:.2f}s|cpu {tot_c[g]:.2f}s|"
              f"WER {tot_err[g]}/{tot_w[g]} = {100*tot_err[g]/tot_w[g]:.1f}%", flush=True)
