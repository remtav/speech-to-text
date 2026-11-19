"""Banc hôte de l'issue #84 : sherpa-onnx 2.0.1 contre CTranslate2 (moteur de faster-whisper), Whisper turbo int8.

Protocole de `../whisper-mesure-python/banc_lite.py` : langue fr forcée, glouton, N threads, meilleur de REPEATS
passages par clip, variantes mesurées en alternance clip par clip (même charge machine pour toutes).

usage : python banc_hote.py REF_DIR THREADS REPEATS FILTRE VARIANTE [...] [--tsv sortie.tsv]
  VARIANTE  nom=sherpa:encodeur,decodeur,tokens,marge   (marge = tailPaddings, 1000 dans l'application)
            nom=ct2:dossier_modele,marge                 (marge : images de zéros après l'audio ;
                                                           -1 = fenêtre de 30 s, comme faster-whisper)
  FILTRE    préfixes de fichiers séparés par des virgules (ex. fr1,frqc) ou « * ».

La variante ct2 refait en Python exactement ce que fait le pont C++ (`jni/ct2_whisper.cc`) : caractéristiques de
faster-whisper 1.2.1, invite <|startoftranscript|><|fr|><|transcribe|><|notimestamps|>, beam 1, plafond de
15 jetons par seconde d'audio. Le paquet Python ctranslate2 sur x86-64 calcule avec oneDNN/MKL, pas Ruy : les
temps hôte ne prédisent pas ceux du téléphone ; l'exactitude, si (même modèle, mêmes entrées).
"""
import math
import os
import sys
import time

import numpy as np
import soundfile as sf

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "whisper-turbo-rapide"))
from wer import errs, norm  # noqa: E402

HOP, CHUNK = 160, 3000


def bytes_to_unicode():
    """Table octet -> caractère du BPE de GPT-2 (la même que `unicode_to_byte` dans jni/ct2_whisper.cc)."""
    bs = list(range(ord("!"), ord("~") + 1)) + list(range(0xA1, 0xAD)) + list(range(0xAE, 0x100))
    cs = bs[:]
    n = 0
    for b in range(256):
        if b not in bs:
            bs.append(b)
            cs.append(256 + n)
            n += 1
    return dict(zip(bs, map(chr, cs)))


class Sherpa:
    def __init__(self, files, threads):
        import sherpa_onnx
        enc, dec, tok, marge = files.split(",")
        self.rec = sherpa_onnx.OfflineRecognizer.from_whisper(
            encoder=enc, decoder=dec, tokens=tok, language="fr", task="transcribe",
            num_threads=threads, tail_paddings=int(marge))

    def __call__(self, a):
        s = self.rec.create_stream()
        s.accept_waveform(16000, a)
        self.rec.decode_stream(s)
        return s.result.text.strip()


class Ct2:
    def __init__(self, files, threads):
        import ctranslate2
        from faster_whisper.feature_extractor import FeatureExtractor
        dossier, marge = files.split(",")
        self.model = ctranslate2.models.Whisper(dossier, device="cpu", compute_type="int8",
                                                intra_threads=threads)
        self.fe = FeatureExtractor(feature_size=self.model.n_mels)
        self.dossier = dossier
        self.marge = int(marge)
        self.vocab_bytes = None

    def features(self, a):
        mel = self.fe(a)  # audio + 160 zéros
        content = min(max(mel.shape[-1] - 1, 1), CHUNK)
        total = CHUNK if self.marge < 0 else min(content + self.marge, CHUNK)
        out = np.zeros((mel.shape[0], total), dtype=np.float32)  # zéros dans l'espace des caractéristiques
        out[:, :content] = mel[:, :content]
        return out

    def __call__(self, a):
        import ctranslate2
        feats = ctranslate2.StorageView.from_array(self.features(a)[None])
        prompt = ["<|startoftranscript|>", "<|fr|>", "<|transcribe|>", "<|notimestamps|>"]
        max_length = min(448, len(prompt) + 8 + math.ceil(len(a) / 16000 * 15))
        r = self.model.generate(feats, [prompt], beam_size=1, max_length=max_length)[0]
        ids = [i for i in r.sequences_ids[0] if i < 50257]
        return self.decode(ids).strip()

    def decode(self, ids):
        if self.vocab_bytes is None:
            import json
            inv = {c: b for b, c in bytes_to_unicode().items()}
            vocab = json.load(open(os.path.join(self.dossier, "vocabulary.json"), encoding="utf-8"))
            self.vocab_bytes = [bytes(inv[c] for c in t if c in inv) for t in vocab]
        return b"".join(self.vocab_bytes[i] for i in ids).decode("utf-8", errors="replace")


def construire(spec, threads):
    nom, reste = spec.split("=", 1)
    genre, files = reste.split(":", 1)
    if genre == "sherpa":
        return nom, Sherpa(files, threads)
    if genre == "ct2":
        return nom, Ct2(files, threads)
    raise SystemExit(f"variante inconnue : {spec}")


def main():
    args = sys.argv[1:]
    tsv = None
    if "--tsv" in args:
        i = args.index("--tsv")
        tsv = args[i + 1]
        del args[i:i + 2]
    ref_dir, threads, repeats, filt = args[0], int(args[1]), int(args[2]), args[3]
    variantes = [construire(s, threads) for s in args[4:]]
    prefixes = None if filt == "*" else filt.split(",")
    refs = []
    for line in open(os.path.join(ref_dir, "references.tsv"), encoding="utf-8"):
        if line.startswith("#") or not line.strip():
            continue
        f, lang, text = line.rstrip("\n").split("\t")[:3]
        if lang != "fr" or (prefixes and not any(f.startswith(p) for p in prefixes)):
            continue
        a, sr = sf.read(os.path.join(ref_dir, f), dtype="float32")
        assert sr == 16000, f
        refs.append((f, text, a))
    for _, run in variantes:  # préchauffage
        run(refs[0][2])
    tot = {n: [0.0, 0, 0] for n, _ in variantes}
    hyps = {n: {} for n, _ in variantes}
    sortie = open(tsv, "w", encoding="utf-8") if tsv else None
    if sortie:
        sortie.write("variante\tfichier\tsecondes_audio\tsecondes_calcul\terreurs\tmots\ttexte\n")
    for f, text, a in refs:
        best = {n: 1e9 for n, _ in variantes}
        for _ in range(repeats):
            for n, run in variantes:
                t0 = time.perf_counter()
                h = run(a)
                best[n] = min(best[n], time.perf_counter() - t0)
                hyps[n][f] = h
        for n, _ in variantes:
            e, w = errs(text, hyps[n][f])
            tot[n][0] += best[n]
            tot[n][1] += e
            tot[n][2] += w
            if sortie:
                sortie.write(f"{n}\t{f}\t{len(a) / 16000:.2f}\t{best[n]:.3f}\t{e}\t{w}\t{hyps[n][f]}\n")
            if len(refs) <= 20:
                print(f"CLIP|{n}|{f}|{len(a) / 16000:.1f}s audio|{best[n]:.2f}s|err {e}/{w}|{hyps[n][f]}", flush=True)
    base = variantes[0][0]
    for n, _ in variantes:
        diff = sum(norm(hyps[n][f]) != norm(hyps[base][f]) for f, _, _ in refs)
        print(f"SUM|{n}|{len(refs)} clips|temps {tot[n][0]:.2f}s|WER {tot[n][1]}/{tot[n][2]} = "
              f"{100 * tot[n][1] / max(tot[n][2], 1):.1f}%|phrases différentes de {base}: {diff}", flush=True)


if __name__ == "__main__":
    main()
