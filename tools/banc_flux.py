#!/usr/bin/env python3
"""Banc de latence du flux (texte presque en direct, issue #130).

But : mesurer, sur le GPU du serveur, ce qui décidera du pas de redécodage
et du réglage par défaut. Le banc concatène les clips québécois de
l'application (avec 0,8 s de silence entre eux), rejoue l'audio comme une
dictée en direct par pas de `--step` secondes, appelle faster-whisper
directement et applique la vraie politique d'accord de `app/streaming.py`
(`StreamState`). Aucun réseau n'est traversé.

Commande d'exemple, sur la machine GPU :

    python3 tools/banc_flux.py --device cuda

Chiffres imprimés :
- temps d'une passe : durée d'un appel à Whisper (médiane, p90, max). Il
  doit rester bien sous le pas ;
- délai d'affichage : entre la fin d'un mot et son premier affichage, temps
  de passe compris (médiane, p90) ;
- délai de confirmation : entre la fin d'un mot et le moment où il devient
  confirmé (définitif), temps de passe compris ;
- part des mots dont l'affichage a changé : positions dont le texte affiché
  a varié avant d'être confirmé ;
- WER du texte final contre une passe unique sur tout l'audio, et contre
  la référence (textes de `references.tsv`).
"""
import argparse
import csv
import sys
import time
import wave
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.streaming import StreamState, Word, normalize  # noqa: E402

SAMPLE_RATE = 16000
SILENCE_S = 0.8
REFERENCE_DIR = Path(__file__).resolve().parent.parent / "android/app/src/main/assets/reference"
DEFAULT_CLIPS = ",".join(f"frqc{i}" for i in range(1, 7))


def read_wav(path) -> np.ndarray:
    with wave.open(str(path), "rb") as w:
        if w.getsampwidth() != 2 or w.getnchannels() != 1:
            raise ValueError(f"{path} : WAV 16 bits mono attendu")
        raw = w.readframes(w.getnframes())
    return np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768.0


def load_references(tsv) -> dict[str, str]:
    refs = {}
    with open(tsv, encoding="utf-8", newline="") as f:
        lines = (l[2:] if l.startswith("# file") else l for l in f)  # en-tête « # file »
        for row in csv.DictReader(lines, delimiter="\t", quoting=csv.QUOTE_NONE):
            refs[Path(row["file"]).stem] = row["transcript"]
    return refs


def _words(text: str) -> list[str]:
    return [m for m in (normalize(w) for w in text.split()) if m]


def wer(ref: str, hyp: str) -> tuple[float, int, int]:
    """Taux d'erreur, nombre d'erreurs, nombre de mots de la référence."""
    r, h = _words(ref), _words(hyp)
    prev = list(range(len(h) + 1))
    for i, rw in enumerate(r, 1):
        cur = [i]
        for j, hw in enumerate(h, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (rw != hw)))
        prev = cur
    errors = prev[-1]
    if not r:
        return (0.0 if not h else 1.0), errors, 0
    return errors / len(r), errors, len(r)


def percentile(values, p: float) -> float:
    if not values:
        return 0.0
    return float(np.percentile(values, p, method="nearest"))


def simulate(audio, decode, step: float, state: StreamState) -> dict:
    """Rejoue `audio` par pas de `step` secondes.

    `decode(samples, prompt)` rend (mots relatifs à la fenêtre, durée de la passe).
    Le temps d'affichage d'une passe est `t_now + durée de la passe`.
    """
    duration = len(audio) / SAMPLE_RATE
    history: list[list[tuple[float, str]]] = []  # par position : (temps d'affichage, mot)
    confirm_time: dict[int, float] = {}
    pass_times = []
    fed = 0
    t_now = 0.0
    while fed < len(audio):
        t_now = min(t_now + step, duration)
        upto = int(round(t_now * SAMPLE_RATE))
        state.add_audio(audio[fed:upto])
        fed = upto
        samples, offset, prompt = state.window()
        words, took = decode(samples, prompt)
        pass_times.append(took)
        state.apply(words, offset, final=t_now >= duration)
        shown_at = t_now + took
        committed = list(state._committed)
        shown = committed + list(state._tail)
        for i, w in enumerate(shown):
            if i >= len(history):
                history.append([])
            if not history[i] or history[i][-1][1] != w.text:
                history[i].append((shown_at, w.text))
            if i < len(committed) and i not in confirm_time:
                confirm_time[i] = shown_at
    first_delays, confirm_delays = [], []
    for i, w in enumerate(state._committed):
        if i < len(history) and history[i]:
            first_delays.append(history[i][0][0] - w.end)
        if i in confirm_time:
            confirm_delays.append(confirm_time[i] - w.end)
    changed = sum(1 for h in history if len({t for _, t in h}) > 1)
    return {
        "text": state.committed_text,
        "pass_times": pass_times,
        "first_delays": first_delays,
        "confirm_delays": confirm_delays,
        "changed": changed,
        "positions": len(history),
    }


def make_decoder(model):
    def decode(samples, prompt):
        t0 = time.perf_counter()
        segments, _ = model.transcribe(
            samples,
            language="fr",
            beam_size=1,
            word_timestamps=True,
            condition_on_previous_text=False,
            vad_filter=False,
            initial_prompt=prompt or None,
        )
        words = [Word(w.word, w.start, w.end) for s in segments for w in (s.words or [])]
        return words, time.perf_counter() - t0

    return decode


def build_audio(names: list[str], directory=REFERENCE_DIR) -> np.ndarray:
    gap = np.zeros(int(SILENCE_S * SAMPLE_RATE), np.float32)
    parts = []
    for k, name in enumerate(names):
        if k:
            parts.append(gap)
        parts.append(read_wav(Path(directory) / f"{name}.wav"))
    return np.concatenate(parts)


def _stats(values) -> str:
    return f"médiane {percentile(values, 50):.2f} s, p90 {percentile(values, 90):.2f} s"


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Banc de latence du flux (passes Whisper rejouées comme une dictée).")
    ap.add_argument("--model", default="large-v3-turbo", help="modèle faster-whisper (défaut : %(default)s)")
    ap.add_argument("--device", default="cuda", help="cuda ou cpu (défaut : %(default)s)")
    ap.add_argument("--compute-type", default=None, help="défaut : float16 sur cuda, int8 sinon")
    ap.add_argument("--step", type=float, default=1.0, help="pas de redécodage en secondes (défaut : %(default)s)")
    ap.add_argument("--clips", default=DEFAULT_CLIPS,
                    help="clips séparés par des virgules (défaut : %(default)s)")
    args = ap.parse_args(argv)
    compute_type = args.compute_type or ("float16" if args.device == "cuda" else "int8")
    names = [c.strip() for c in args.clips.split(",") if c.strip()]

    from faster_whisper import WhisperModel

    audio = build_audio(names)
    refs = load_references(REFERENCE_DIR / "references.tsv")
    reference = " ".join(refs[n] for n in names)
    decode = make_decoder(WhisperModel(args.model, device=args.device, compute_type=compute_type))

    decode(audio[: SAMPLE_RATE * 2], "")  # échauffement
    once_words, once_took = decode(audio, "")
    once_text = " ".join(w.text.strip() for w in once_words)
    res = simulate(audio, decode, args.step, StreamState())

    print(f"Modèle {args.model} sur {args.device} ({compute_type}), pas {args.step} s, "
          f"audio {len(audio) / SAMPLE_RATE:.1f} s, clips {', '.join(names)}")
    print(f"Passe unique sur tout l'audio : {once_took:.2f} s")
    pt = res["pass_times"]
    print(f"Temps d'une passe ({len(pt)} passes) : médiane {percentile(pt, 50):.2f} s, "
          f"p90 {percentile(pt, 90):.2f} s, max {max(pt):.2f} s")
    print(f"Délai fin de mot -> premier affichage : {_stats(res['first_delays'])}")
    print(f"Délai fin de mot -> confirmation : {_stats(res['confirm_delays'])}")
    share = res["changed"] / res["positions"] if res["positions"] else 0.0
    print(f"Mots dont l'affichage a changé : {res['changed']} sur {res['positions']} ({share:.1%})")
    for label, other in (("passe unique", once_text), ("référence", reference)):
        rate, errors, total = wer(other, res["text"])
        print(f"WER du texte final contre {label} : {rate:.1%} ({errors} erreurs sur {total} mots)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
