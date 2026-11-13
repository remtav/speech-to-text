"""Étape 0 de l'issue #40 : partage encodeur / décodeur de turbo int8.

usage : python etape0.py REF_DIR FILTRE THREADS ENC DEC TOKENS [TAIL_PADDINGS]
FILTRE : préfixes séparés par des virgules (fr1,frqc) ou « * ».
Par clip, en alternance : sherpa-onnx 2.0.1 (temps total de référence), puis la réplique instrumentée
(`replique.py` : temps des caractéristiques, de l'encodeur, du décodeur, nombre de jetons)."""
import os
import sys
import time

import sherpa_onnx
import soundfile as sf

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from replique import Whisper  # noqa: E402

ref_dir, filt, threads, enc, dec, tok = sys.argv[1:7]
tail = int(sys.argv[7]) if len(sys.argv) > 7 else 1000
threads = int(threads)
rec = sherpa_onnx.OfflineRecognizer.from_whisper(encoder=enc, decoder=dec, tokens=tok, language="fr",
                                                 task="transcribe", num_threads=threads, tail_paddings=tail)
w = Whisper(enc, dec, tok, threads)
prefixes = None if filt == "*" else filt.split(",")
clips = []
for line in open(os.path.join(ref_dir, "references.tsv"), encoding="utf-8"):
    if line.startswith("#") or not line.strip():
        continue
    f, lang = line.split("\t")[:2]
    if lang == "fr" and (not prefixes or any(f.startswith(p) for p in prefixes)):
        clips.append(f)


def sherpa(a):
    s = rec.create_stream()
    s.accept_waveform(16000, a)
    rec.decode_stream(s)
    return s.result.text.strip()


a0, _ = sf.read(os.path.join(ref_dir, clips[0]), dtype="float32")
sherpa(a0)
w.decode(a0, tail)
S = dict(sherpa=0.0, feat=0.0, enc=0.0, dec=0.0, tok=0, prompt=0, audio=0.0)
for f in clips:
    a, _ = sf.read(os.path.join(ref_dir, f), dtype="float32")
    t0 = time.perf_counter()
    sherpa(a)
    ts = time.perf_counter() - t0
    r = w.decode(a, tail)
    n = len(r["tokens"])
    S["sherpa"] += ts
    S["feat"] += r["t_feat"]
    S["enc"] += r["t_enc"]
    S["dec"] += r["t_dec"]
    S["tok"] += n
    S["prompt"] += 1
    S["audio"] += len(a) / 16000
    if len(clips) <= 20:
        print(f"CLIP|{f}|{len(a)/16000:.1f}s audio|{r['frames_in']} images|sherpa {ts:.2f}s|enc {r['t_enc']:.2f}s|"
              f"dec {r['t_dec']:.2f}s|{n} jetons|{1000*r['t_dec']/(n+1):.0f} ms/étape", flush=True)
rep = S["feat"] + S["enc"] + S["dec"]
print(f"SUM|{len(clips)} clips|{S['audio']:.1f}s audio|sherpa {S['sherpa']:.2f}s|réplique {rep:.2f}s|"
      f"caractéristiques {S['feat']:.2f}s ({100*S['feat']/rep:.0f} %)|encodeur {S['enc']:.2f}s ({100*S['enc']/rep:.0f} %)|"
      f"décodeur {S['dec']:.2f}s ({100*S['dec']/rep:.0f} %)|{S['tok']} jetons|"
      f"{1000*S['dec']/(S['tok']+S['prompt']):.1f} ms par étape du décodeur (invite comprise)", flush=True)
