"""Réplique Python du décodage Whisper de sherpa-onnx 2.0.1 (issue #40), instrumentée.

Reproduit `OfflineRecognizerWhisperImpl::DecodeStream` et `OfflineWhisperGreedySearchDecoder::Decode` :
caractéristiques log-mel de kaldi-native-fbank (`OnlineWhisperFbank`, sans centrage), normalisation
`NormalizeWhisperFeatures` (log10, plancher max − 8, (x + 4) / 4), marge de fin de `tail_paddings` images de zéros
(plafond 3 000), invite `sot, fr, transcribe, no_timestamps`, glouton, au plus 6 jetons par seconde d'audio
(plafond n_text_ctx / 2). Sessions onnxruntime : ORT_ENABLE_ALL, intra = inter = threads (comme `session.cc`).

En plus de sherpa : chronomètre séparé de l'encodeur et du décodeur, nombre de jetons, et une entrée optionnelle
`n_frames` pour les encodeurs expérimentaux de la piste 2.2 (longueur de l'audio réel, en images de 10 ms).

Utiliser l'onnxruntime Python 1.28.0 (le plus proche du 1.28.2 de sherpa-onnx 2.0.1 publié sur PyPI)."""
import time

import kaldi_native_fbank as knf
import numpy as np
import onnxruntime as ort


def features(audio, n_mels=128):
    opts = knf.WhisperFeatureOptions()
    opts.dim = n_mels
    opts.frame_opts.samp_freq = 16000
    fb = knf.OnlineWhisperFbank(opts)
    fb.accept_waveform(16000, audio.astype(np.float32))
    fb.input_finished()
    n = fb.num_frames_ready
    f = np.stack([fb.get_frame(i) for i in range(n)]).astype(np.float32)
    f = np.log10(np.maximum(f, 1e-10))
    f = np.maximum(f, f.max() - 8.0)
    return (f + 4.0) / 4.0  # (T, n_mels)


def session(path, threads, profile=None):
    so = ort.SessionOptions()
    so.intra_op_num_threads = threads
    so.inter_op_num_threads = threads
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    if profile:
        so.enable_profiling = True
        so.profile_file_prefix = profile
    return ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])


class Whisper:
    def __init__(self, encoder, decoder, tokens, threads=4, language="fr", profile=None):
        self.enc = session(encoder, threads, profile and profile + "-enc")
        self.dec = session(decoder, threads, profile and profile + "-dec")
        meta = self.enc.get_modelmeta().custom_metadata_map
        self.n_mels = int(meta["n_mels"])
        self.n_text_ctx = int(meta["n_text_ctx"])
        self.n_text_layer = int(meta["n_text_layer"])
        self.n_text_state = int(meta["n_text_state"])
        self.eot = int(meta["eot"])
        sot = [int(x) for x in meta["sot_sequence"].split(",")]
        codes = meta["all_language_codes"].split(",")
        langs = [int(x) for x in meta["all_language_tokens"].split(",")]
        sot[1] = langs[codes.index(language)]
        self.prompt = sot + [int(meta["no_timestamps"])]
        self.enc_inputs = [i.name for i in self.enc.get_inputs()]
        dec_ctx = self.dec.get_inputs()[1].shape[2]
        if isinstance(dec_ctx, int) and dec_ctx != self.n_text_ctx:
            raise ValueError(f"décodeur à {dec_ctx} positions, encodeur annonce n_text_ctx={self.n_text_ctx}")
        self.sym = {}
        for line in open(tokens, encoding="utf-8"):
            p = line.rstrip("\n").split()
            if len(p) == 2:
                self.sym[int(p[1])] = p[0]
            elif len(p) == 1:  # jeton vide
                self.sym[int(p[0])] = ""

    def text(self, toks):
        import base64
        b = b"".join(base64.b64decode(self.sym[t]) for t in toks if t in self.sym)
        return b.decode("utf-8", errors="ignore").strip()

    def decode(self, audio, tail_paddings=1000, feats=None):
        """Retourne dict(text, tokens, t_feat, t_enc, t_dec, n_frames, frames_in)."""
        t0 = time.perf_counter()
        f = features(audio, self.n_mels) if feats is None else feats
        n = min(f.shape[0], 3000 - 50)
        f = f[:n]
        total = min(n + tail_paddings, 3000)
        mel = np.zeros((1, self.n_mels, total), dtype=np.float32)
        mel[0, :, :n] = f.T
        t1 = time.perf_counter()
        feed = {"mel": mel}
        if "n_frames" in self.enc_inputs:
            feed["n_frames"] = np.array([n], dtype=np.int64)
        ck, cv = self.enc.run(None, feed)
        t2 = time.perf_counter()
        shape = (self.n_text_layer, 1, self.n_text_ctx, self.n_text_state)
        k = np.zeros(shape, np.float32)
        v = np.zeros(shape, np.float32)
        tok = np.array([self.prompt], dtype=np.int64)
        off = np.array([0], dtype=np.int64)
        logits, k, v = self.dec.run(None, {"tokens": tok, "in_n_layer_self_k_cache": k,
                                           "in_n_layer_self_v_cache": v, "n_layer_cross_k": ck,
                                           "n_layer_cross_v": cv, "offset": off})
        off = np.array([len(self.prompt)], dtype=np.int64)
        best = int(np.argmax(logits[0, -1]))
        out = []
        limit = min(int(n / 100.0 * 6), self.n_text_ctx // 2)
        for _ in range(limit):
            if best == self.eot:
                break
            out.append(best)
            logits, k, v = self.dec.run(None, {"tokens": np.array([[best]], dtype=np.int64),
                                               "in_n_layer_self_k_cache": k, "in_n_layer_self_v_cache": v,
                                               "n_layer_cross_k": ck, "n_layer_cross_v": cv, "offset": off})
            off = off + 1
            if off[0] >= self.n_text_ctx - 1:
                break
            best = int(np.argmax(logits[0, -1]))
        t3 = time.perf_counter()
        return dict(text=self.text(out), tokens=out, t_feat=t1 - t0, t_enc=t2 - t1, t_dec=t3 - t2,
                    n_frames=n, frames_in=total, capped=len(out) >= limit)
