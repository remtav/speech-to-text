"""Piste 1.1 de l'issue #40 : produits de l'attention de l'encodeur de turbo (`QKᵀ` et `AV`) en int8.

usage : python attention_int8.py ENC_IN ENC_OUT --av {fp32,dyn,fixe,ligne} --qk {fp32,tenseur,tete} [--couches 0-31]

Part de `turbo-encoder.int8.onnx` de `csukuangfj/sherpa-onnx-whisper-turbo` (les poids restent tels quels ; seules
les deux MatMul à deux entrées dynamiques de chaque bloc changent). Dans chaque bloc `blocks.i/attn` :
  MatMul   (Q·s, (K·s)ᵀ)  → Cast → Softmax → Cast → MatMul_1 (A, V)
Remplacements (MatMulInteger + mise à l'échelle en fp32 de la sortie int32) :
  --av dyn    : A et V quantifiés par DynamicQuantizeLinear (uint8, échelle par tenseur, calculée à l'exécution).
  --av fixe   : A quantifié avec l'échelle fixe 1/255, point zéro 0 (A ∈ [0, 1] après le softmax) ; V dynamique.
  --av ligne  : A quantifié ligne par ligne (échelle = max de la ligne / 255) ; V dynamique.
  --qk tenseur: Q·s et K·s quantifiés par DynamicQuantizeLinear (uint8 asymétrique, par tenseur).
  --qk tete   : Q·s et K·s en int8 symétrique, une échelle par tête (max |x| de la tête / 127).
Le graphe reste lisible par sherpa-onnx 2.0.1 : mêmes entrées, sorties et métadonnées ; opérateurs standard de l'opset
du modèle (MatMulInteger, DynamicQuantizeLinear, QuantizeLinear, ReduceMax)."""
import argparse
import re

import numpy as np
import onnx
from onnx import helper, numpy_helper, TensorProto


def couches(spec):
    out = set()
    for part in spec.split(","):
        a, _, b = part.partition("-")
        out.update(range(int(a), int(b or a) + 1))
    return out


class Graphe:
    def __init__(self, model):
        self.m = model
        self.g = model.graph
        self.new_nodes = {}  # nom du nœud remplacé -> liste de nœuds
        self.inits = []
        self.k = 0

    def nom(self, base):
        self.k += 1
        return f"{base}_q40_{self.k}"

    def const(self, base, arr):
        n = self.nom(base)
        self.inits.append(numpy_helper.from_array(arr, n))
        return n

    def node(self, acc, op, inputs, base, n_out=1, **attrs):
        outs = [self.nom(base + "_" + op) for _ in range(n_out)]
        acc.append(helper.make_node(op, inputs, outs, name=self.nom(base + "/" + op), **attrs))
        return outs if n_out > 1 else outs[0]


def quant_dyn(G, acc, x, base):
    """uint8 par tenseur : retourne (xq, échelle, point zéro)."""
    return G.node(acc, "DynamicQuantizeLinear", [x], base, n_out=3)


def quant_tete(G, acc, x, base):
    """int8 symétrique, une échelle par tête : x de forme [B, H, T, D]. Retourne (xq, échelle [1,H,1,1])."""
    ax = G.node(acc, "Abs", [x], base)
    mx = G.node(acc, "ReduceMax", [ax], base, axes=[0, 2, 3], keepdims=0)  # [H]
    s = G.node(acc, "Div", [mx, G.const(base + "_127", np.array(127.0, np.float32))], base)
    s = G.node(acc, "Max", [s, G.const(base + "_eps", np.array(1e-8, np.float32))], base)
    zp = G.const(base + "_zp", np.zeros(1, np.int8))
    zp = G.node(acc, "Expand", [zp, G.node(acc, "Shape", [s], base)], base)
    xq = G.node(acc, "QuantizeLinear", [x, s, zp], base, axis=1)
    s4 = G.node(acc, "Reshape", [s, G.const(base + "_shp", np.array([1, -1, 1, 1], np.int64))], base)
    return xq, s4


def remplacer_av(G, mm, mode, base):
    a, v = mm.input
    acc = []
    if mode == "dyn":
        aq, sa, za = quant_dyn(G, acc, a, base + "/A")
    elif mode == "fixe":
        sa = G.const(base + "_sa", np.array(1.0 / 255.0, np.float32))
        za = G.const(base + "_za", np.array(0, np.uint8))
        aq = G.node(acc, "QuantizeLinear", [a, sa, za], base + "/A")
    elif mode == "ligne":
        mx = G.node(acc, "ReduceMax", [a], base + "/A", axes=[-1], keepdims=1)  # [B,H,T,1]
        sa = G.node(acc, "Div", [mx, G.const(base + "_255", np.array(255.0, np.float32))], base + "/A")
        sa = G.node(acc, "Max", [sa, G.const(base + "_eps", np.array(1e-12, np.float32))], base + "/A")
        r = G.node(acc, "Div", [a, sa], base + "/A")
        r = G.node(acc, "Round", [r], base + "/A")
        aq = G.node(acc, "Cast", [r], base + "/A", to=TensorProto.UINT8)
        za = G.const(base + "_za", np.array(0, np.uint8))
    else:
        raise ValueError(mode)
    vq, sv, zv = quant_dyn(G, acc, v, base + "/V")
    y = G.node(acc, "MatMulInteger", [aq, vq, za, zv], base)
    y = G.node(acc, "Cast", [y], base, to=TensorProto.FLOAT)
    s = G.node(acc, "Mul", [sa, sv], base)
    acc.append(helper.make_node("Mul", [y, s], [mm.output[0]], name=G.nom(base + "/out")))
    return acc


def remplacer_qk(G, mm, mode, base):
    q, kt = mm.input  # kt = (K·s)ᵀ, forme [B, H, D, T]
    acc = []
    if mode == "tenseur":
        qq, sq, zq = quant_dyn(G, acc, q, base + "/Q")
        kq, sk, zk = quant_dyn(G, acc, kt, base + "/K")
        y = G.node(acc, "MatMulInteger", [qq, kq, zq, zk], base)
    elif mode == "tete":
        qq, sq = quant_tete(G, acc, q, base + "/Q")
        kq, sk = quant_tete(G, acc, kt, base + "/K")
        y = G.node(acc, "MatMulInteger", [qq, kq], base)
    else:
        raise ValueError(mode)
    y = G.node(acc, "Cast", [y], base, to=TensorProto.FLOAT)
    s = G.node(acc, "Mul", [sq, sk], base)
    acc.append(helper.make_node("Mul", [y, s], [mm.output[0]], name=G.nom(base + "/out")))
    return acc


def main():
    p = argparse.ArgumentParser()
    p.add_argument("enc_in")
    p.add_argument("enc_out")
    p.add_argument("--av", default="fp32", choices=["fp32", "dyn", "fixe", "ligne"])
    p.add_argument("--qk", default="fp32", choices=["fp32", "tenseur", "tete"])
    p.add_argument("--couches", default="0-31")
    a = p.parse_args()
    keep = couches(a.couches)
    m = onnx.load(a.enc_in)
    G = Graphe(m)
    pat = re.compile(r"/audioEncoder/blocks\.(\d+)/attn/(MatMul|MatMul_1)$")
    out_nodes, n_av, n_qk = [], 0, 0
    for nd in m.graph.node:
        r = pat.match(nd.name)
        if r and int(r.group(1)) in keep:
            if r.group(2) == "MatMul_1" and a.av != "fp32":
                out_nodes.extend(remplacer_av(G, nd, a.av, nd.name))
                n_av += 1
                continue
            if r.group(2) == "MatMul" and a.qk != "fp32":
                out_nodes.extend(remplacer_qk(G, nd, a.qk, nd.name))
                n_qk += 1
                continue
        out_nodes.append(nd)
    del m.graph.node[:]
    m.graph.node.extend(out_nodes)
    m.graph.initializer.extend(G.inits)
    del m.graph.value_info[:]
    onnx.checker.check_model(m)
    onnx.save(m, a.enc_out)
    print(f"AV remplacés : {n_av} ({a.av}) ; QKᵀ remplacés : {n_qk} ({a.qk}) → {a.enc_out}")


if __name__ == "__main__":
    main()
