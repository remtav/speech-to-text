#!/usr/bin/env python3
"""Variantes de quantification de l'encodeur Lite-Whisper (issue #25), sur l'export fp32.

Catégories de MatMul à poids constant :
  w1    : x @ W1 (entrée d'une couche factorisée)
  w2    : h @ W2 (sortie d'une couche factorisée, h = x @ W1)
  plein : couches non factorisées (et couches recombinées par --recombiner)

Modes par catégorie : fp32 | dyn8 (int8 dynamique par tenseur, = quantifier_int8.py) |
  nb8 / nb4 (MatMulNBits par blocs, poids seulement ; --bloc, --acc niveau de précision) |
  fp16 (poids fp16, Cast avant/après) | st8 (int8 statique : échelle d'activation fixe, --calib)

Transformations exactes (en précision infinie) appliquées avant la quantification :
  --recombiner R : remplace W1 @ W2 par la matrice pleine si r × (in + out) >= R × in × out ;
  --rotation     : W1 -> W1 Q, W2 -> Qᵀ W2, Q orthogonale aléatoire r×r (graine 0). Sans elle, la
                   quantification int8 des h @ W2 détruit le modèle (sortie « ... »), voir
                   android/docs/ACCELERER_WHISPER.md, section 7.2.
--par-colonne w2,w1,plein : échelle des poids int8 par colonne (vecteur dans le Mul final, sans coût).

Meilleure variante essayée (section 7.2 : correcte et 2 % plus rapide que turbo, mais WER
19,5 % contre 14,5 % sur le jeu de l'issue #24 ; critères non remplis, non publiée) :
  python quantifier_lite.py turbo-encoder.onnx turbo-encoder.int8.onnx --recombiner 0.8 --rotation \
      --w2 dyn8 --par-colonne w2,w1,plein

usage : python quantifier_lite.py ENC_FP32.onnx SORTIE.onnx [--recombiner R] [--rotation] [--plein M]
        [--w1 M] [--w2 M] [--par-colonne CATS] [--bloc 32] [--acc 0] [--calib stats.npz]
        python quantifier_lite.py ENC_FP32.onnx --stats SORTIE.npz --mels mels.npz [--pourcentile P]
"""
import argparse
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper


def read_external(t, base):
    info = {e.key: e.value for e in t.external_data}
    with open(base / info["location"], "rb") as f:
        f.seek(int(info.get("offset", 0)))
        raw = f.read(int(info["length"])) if "length" in info else f.read()
    return np.frombuffer(raw, dtype=helper.tensor_dtype_to_np_dtype(t.data_type)).reshape(t.dims)


class Graphe:
    def __init__(self, path):
        p = Path(path)
        self.model = onnx.load(path, load_external_data=False)
        g = self.model.graph
        self.w = {}
        for t in g.initializer:
            self.w[t.name] = read_external(t, p.parent).copy() if t.external_data else numpy_helper.to_array(t)
        self.nodes = list(g.node)

    def classer(self):
        """Retourne {nom de nœud: (catégorie, nœud)} et les paires (n1, n2) factorisées."""
        out_of = {n.output[0]: n for n in self.nodes if n.op_type == "MatMul"}
        users = {}
        for n in self.nodes:
            for i in n.input:
                users.setdefault(i, []).append(n)
        is_wmm = lambda n: (n.op_type == "MatMul" and n.input[1] in self.w and self.w[n.input[1]].ndim == 2
                            and self.w[n.input[1]].dtype == np.float32)
        pairs = []
        for n2 in self.nodes:
            if not is_wmm(n2):
                continue
            n1 = out_of.get(n2.input[0])
            if n1 is not None and is_wmm(n1) and len(users[n1.output[0]]) == 1:
                pairs.append((n1, n2))
        w1 = {id(a) for a, _ in pairs}
        w2 = {id(b) for _, b in pairs}
        cats = {}
        for n in self.nodes:
            if is_wmm(n):
                cats[n.name] = "w1" if id(n) in w1 else "w2" if id(n) in w2 else "plein"
        return cats, pairs

    def recombiner(self, seuil):
        _, pairs = self.classer()
        k = 0
        drop = set()
        for n1, n2 in pairs:
            a, b = self.w[n1.input[1]], self.w[n2.input[1]]
            (i, r), o = a.shape, b.shape[1]
            if r * (i + o) >= seuil * i * o:
                name = n2.input[1] + "_plein"
                self.w[name] = (a.astype(np.float64) @ b.astype(np.float64)).astype(np.float32)
                n2.input[0], n2.input[1] = n1.input[0], name
                drop.add(id(n1))
                k += 1
        self.nodes = [n for n in self.nodes if id(n) not in drop]
        print(f"{k} couches recombinées (seuil {seuil}), {len(pairs) - k} restent factorisées")

    def tourner(self, graine=0):
        """W1 -> W1 Q, W2 -> Q^T W2 (Q orthogonale r×r aléatoire, graine fixe) : produit inchangé en
        précision infinie, mais h = x @ W1 Q et les lignes de W2 n'ont plus de valeurs extrêmes isolées."""
        _, pairs = self.classer()
        rng = np.random.default_rng(graine)
        for n1, n2 in pairs:
            a, b = self.w[n1.input[1]].astype(np.float64), self.w[n2.input[1]].astype(np.float64)
            q, rr = np.linalg.qr(rng.normal(size=(a.shape[1], a.shape[1])))
            q *= np.sign(np.diag(rr))
            self.w[n1.input[1]] = (a @ q).astype(np.float32)
            self.w[n2.input[1]] = (q.T @ b).astype(np.float32)
        print(f"{len(pairs)} couches factorisées tournées")

    def quantifier(self, modes, bloc, acc, calib, pct, par_colonne=()):
        cats, _ = self.classer()
        new, done = [], {}
        counts = {}
        for n in self.nodes:
            cat = cats.get(n.name)
            mode = modes.get(cat, "fp32") if cat else "fp32"
            if mode == "fp32":
                new.append(n)
                continue
            counts[(cat, mode)] = counts.get((cat, mode), 0) + 1
            wn = n.input[1]
            W = self.w[wn]
            a, y, base = n.input[0], n.output[0], n.name
            if mode == "dyn8" or mode == "st8":
                if wn + "_q" not in self.w:
                    if cat in par_colonne:
                        s = (np.maximum(np.abs(W).max(axis=0), 1e-8) / 127.0).astype(np.float32)
                    else:
                        s = max(float(np.abs(W).max()), 1e-8) / 127.0
                    self.w[wn + "_q"] = np.clip(np.rint(W / s), -127, 127).astype(np.int8)
                    self.w[wn + "_zp"] = np.array(0, np.int8)
                    self.w[wn + "_scale"] = np.array(s, np.float32)
                if mode == "dyn8":
                    new.append(helper.make_node("DynamicQuantizeLinear", [a], [base + "_aq", base + "_as", base + "_az"],
                                                name=base + "_dql"))
                else:
                    st = calib[a]
                    lo, hi = float(min(st[0], 0)), float(max(st[1], 0))
                    s = max(hi - lo, 1e-8) / 255.0
                    zp = int(np.clip(np.rint(-lo / s), 0, 255))
                    self.w[base + "_as"] = np.array(s, np.float32)
                    self.w[base + "_az"] = np.array(zp, np.uint8)
                    new.append(helper.make_node("QuantizeLinear", [a, base + "_as", base + "_az"], [base + "_aq"],
                                                name=base + "_ql"))
                new += [
                    helper.make_node("MatMulInteger", [base + "_aq", wn + "_q", base + "_az", wn + "_zp"],
                                     [base + "_i32"], name=base + "_mmi"),
                    helper.make_node("Cast", [base + "_i32"], [base + "_f"], name=base + "_cast", to=TensorProto.FLOAT),
                    helper.make_node("Mul", [base + "_as", wn + "_scale"], [base + "_s"], name=base + "_ms"),
                    helper.make_node("Mul", [base + "_f", base + "_s"], [y], name=base + "_mul"),
                ]
            elif mode in ("nb8", "nb4"):
                bits = 8 if mode == "nb8" else 4
                key = f"{wn}_nb{bits}"
                K, N = W.shape
                kb = (K + bloc - 1) // bloc
                if key not in self.w:
                    Wp = np.zeros((kb * bloc, N), np.float32)
                    Wp[:K] = W
                    blocks = Wp.T.reshape(N, kb, bloc)
                    qmax = 2 ** (bits - 1) - 1
                    s = np.maximum(np.abs(blocks).max(axis=2), 1e-8) / qmax
                    q = (np.clip(np.rint(blocks / s[..., None]), -qmax - 1, qmax) + qmax + 1).astype(np.uint8)
                    if bits == 4:
                        q = (q[..., 0::2] | (q[..., 1::2] << 4)).astype(np.uint8)
                    self.w[key] = q
                    self.w[key + "_s"] = s.astype(np.float32).reshape(-1)
                new.append(helper.make_node("MatMulNBits", [a, key, key + "_s"], [y], name=base + "_nb",
                                            domain="com.microsoft", K=K, N=N, bits=bits, block_size=bloc,
                                            accuracy_level=acc))
            elif mode == "fp16":
                if wn + "_h" not in self.w:
                    self.w[wn + "_h"] = W.astype(np.float16)
                new += [helper.make_node("Cast", [a], [base + "_ah"], name=base + "_c1", to=TensorProto.FLOAT16),
                        helper.make_node("MatMul", [base + "_ah", wn + "_h"], [base + "_yh"], name=base + "_mmh"),
                        helper.make_node("Cast", [base + "_yh"], [y], name=base + "_c2", to=TensorProto.FLOAT)]
            else:
                raise SystemExit(f"mode inconnu {mode}")
        self.nodes = new
        print("quantifiés :", {f"{c}/{m}": k for (c, m), k in sorted(counts.items())})

    def ecrire(self, path):
        g = self.model.graph
        used = {i for n in self.nodes for i in n.input}
        del g.node[:]
        g.node.extend(self.nodes)
        del g.initializer[:]
        g.initializer.extend(numpy_helper.from_array(v, k) for k, v in self.w.items() if k in used)
        if not any(o.domain == "com.microsoft" for o in self.model.opset_import):
            self.model.opset_import.append(helper.make_opsetid("com.microsoft", 1))
        total = sum(v.nbytes for k, v in self.w.items() if k in used)
        if total > 1.9e9:
            onnx.save(self.model, path, save_as_external_data=True, location=Path(path).name + ".weights")
        else:
            onnx.save(self.model, path)
        print(f"-> {path} : {total / 1e6:.1f} Mo de poids")


def stats(src, out, mels_path, pct):
    """Calibration : min/max (au pourcentile pct) des entrées de toutes les MatMul à poids, fp32."""
    import onnxruntime as ort
    g = Graphe(src)
    cats, _ = g.classer()
    names = sorted({n.input[0] for n in g.nodes if n.name in cats})
    m = onnx.load(src)
    shapes = {}
    for t in names:
        m.graph.output.append(helper.make_tensor_value_info(t, TensorProto.FLOAT, None))
    tmp = Path(out).with_suffix(".tmp.onnx")
    onnx.save(m, tmp, save_as_external_data=True, location=tmp.name + ".w")
    sess = ort.InferenceSession(str(tmp), providers=["CPUExecutionProvider"])
    feed_name = sess.get_inputs()[0].name
    mels = np.load(mels_path)
    acc = {t: [np.inf, -np.inf] for t in names}
    outs = [o.name for o in sess.get_outputs()]
    for k in mels.files:
        res = sess.run(outs, {feed_name: mels[k]})
        for o, v in zip(outs, res):
            if o in acc:
                lo, hi = np.percentile(v, [100 - pct, pct]) if pct < 100 else (v.min(), v.max())
                acc[o][0] = min(acc[o][0], float(lo))
                acc[o][1] = max(acc[o][1], float(hi))
        print("calib", k, flush=True)
    np.savez(out, **{t: np.array(v, np.float32) for t, v in acc.items()})
    tmp.unlink()
    Path(str(tmp) + ".w").unlink()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dst", nargs="?")
    ap.add_argument("--recombiner", type=float)
    ap.add_argument("--plein", default="dyn8")
    ap.add_argument("--w1", default="dyn8")
    ap.add_argument("--w2", default="fp32")
    ap.add_argument("--bloc", type=int, default=32)
    ap.add_argument("--acc", type=int, default=0)
    ap.add_argument("--calib")
    ap.add_argument("--pourcentile", type=float, default=100.0)
    ap.add_argument("--stats")
    ap.add_argument("--rotation", action="store_true")
    ap.add_argument("--par-colonne", default="", help="catégories à échelle de poids par colonne, ex. w2,w1")
    ap.add_argument("--mels")
    a = ap.parse_args()
    if a.stats:
        return stats(a.src, a.stats, a.mels, a.pourcentile)
    g = Graphe(a.src)
    if a.recombiner is not None:
        g.recombiner(a.recombiner)
    if a.rotation:
        g.tourner()
    calib = None
    if a.calib:
        calib = {k: v for k, v in np.load(a.calib).items()}
    g.quantifier({"plein": a.plein, "w1": a.w1, "w2": a.w2}, a.bloc, a.acc, calib, a.pourcentile,
                tuple(c for c in a.par_colonne.split(",") if c))
    g.ecrire(a.dst)


if __name__ == "__main__":
    main()
