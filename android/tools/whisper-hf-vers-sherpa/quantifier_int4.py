#!/usr/bin/env python3
"""Quantification 4 bits par blocs (MatMulNBits, poids seulement) d'un export ONNX de Whisper, avec peu de mémoire.

Issue #26. Remplace chaque `MatMul` dont le second opérande est un poids constant 2D fp32 par un nœud
`com.microsoft:MatMulNBits` (poids entiers par blocs de `--bloc` valeurs le long de la dimension K, un
facteur d'échelle par bloc, symétrique : point zéro implicite 8). Même quantification que
`onnxruntime.quantization.matmul_nbits_quantizer` en mode symétrique (échelle = valeur de plus grande
amplitude / -8), mais écrite en numpy, un poids à la fois, lu dans le fichier de données externes : pas de
chargement du modèle entier (l'encodeur de large-v3-turbo fait 2,6 Go en fp32).

Le fichier produit est un seul .onnx (poids embarqués), comme ceux de csukuangfj/sherpa-onnx-whisper-turbo.
Les autres opérations (attention, LayerNorm, convolutions) restent en fp32 ; les convolutions de l'encodeur
et les embeddings ne sont pas touchés.

Usage :
  python quantifier_int4.py MODELE_FP32.onnx SORTIE.onnx [--bloc 32] [--bits 4] [--acc 0]
                            [--sauf SOUS_CHAINE[,…]] [--min-k 64]

  --bits 8 : même opérateur en 8 bits (témoin). --acc : `accuracy_level` de MatMulNBits (0 : calcul fp32,
  4 : activations quantifiées en int8 par bloc, noyaux plus rapides sur arm64, moins précis).
  --sauf : ne pas quantifier les nœuds dont le nom contient une de ces sous-chaînes.
"""
import argparse
from pathlib import Path

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper


def read_external(t: TensorProto, base: Path) -> np.ndarray:
    info = {e.key: e.value for e in t.external_data}
    offset = int(info.get("offset", 0))
    length = int(info["length"]) if "length" in info else None
    with open(base / info["location"], "rb") as f:
        f.seek(offset)
        raw = f.read(length) if length is not None else f.read()
    return np.frombuffer(raw, dtype=helper.tensor_dtype_to_np_dtype(t.data_type)).reshape(t.dims)


def quantifier_nbits(w: np.ndarray, bits: int, bloc: int):
    """w : (K, N) fp32. Retourne (B, scales) au format MatMulNBits.
    B : uint8 (N, K/bloc, bloc*bits/8), scales : fp32 (N * K/bloc)."""
    k, n = w.shape
    nb = -(-k // bloc)
    wt = np.zeros((n, nb * bloc), dtype=np.float32)
    wt[:, :k] = w.T
    blocs = wt.reshape(n, nb, bloc)
    # Valeur signée de plus grande amplitude -> -2^(bits-1), comme ORT en symétrique.
    idx = np.abs(blocs).argmax(axis=2)
    pic = np.take_along_axis(blocs, idx[..., None], axis=2)[..., 0]
    scale = pic / -float(1 << (bits - 1))
    inv = np.where(scale != 0, 1.0 / np.where(scale != 0, scale, 1.0), 0.0).astype(np.float32)
    zero = 1 << (bits - 1)
    q = np.clip(np.rint(blocs * inv[..., None]) + zero, 0, (1 << bits) - 1).astype(np.uint8)
    if bits == 4:
        packed = (q[..., 0::2] | (q[..., 1::2] << 4)).astype(np.uint8)
    else:
        packed = q
    return np.ascontiguousarray(packed), scale.astype(np.float32).reshape(-1)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("src")
    ap.add_argument("dst")
    ap.add_argument("--bloc", type=int, default=32)
    ap.add_argument("--bits", type=int, default=4, choices=(4, 8))
    ap.add_argument("--acc", type=int, default=0)
    ap.add_argument("--sauf", default="")
    ap.add_argument("--min-k", type=int, default=64)
    ap.add_argument("--sans-logits", action="store_true",
                    help="garder en fp32 la projection finale du décodeur (embedding des jetons, poids à gauche du MatMul)")
    a = ap.parse_args()

    src_path = Path(a.src)
    exclus = [s for s in a.sauf.split(",") if s]
    model = onnx.load(a.src, load_external_data=False)
    graph = model.graph
    inits = {t.name: t for t in graph.initializer}
    # `Transpose` d'un poids constant suivi d'un MatMul (logits du décodeur) : on le traite comme un poids.
    transposes = {n.output[0]: n for n in graph.node
                  if n.op_type == "Transpose" and n.input[0] in inits and len(inits[n.input[0]].dims) == 2}

    def load(t: TensorProto) -> np.ndarray:
        return read_external(t, src_path.parent) if t.external_data else numpy_helper.to_array(t)

    def poids_de(nom):
        """(tableau (K, N) fp32 ou None, nom de l'initialiseur d'origine)."""
        if nom in inits:
            t = inits[nom]
            if len(t.dims) == 2 and t.data_type == TensorProto.FLOAT:
                return load(t), nom
        elif nom in transposes:
            tn = transposes[nom]
            perm = [at.ints for at in tn.attribute if at.name == "perm"]
            t = inits[tn.input[0]]
            if t.data_type == TensorProto.FLOAT and (not perm or list(perm[0]) == [1, 0]):
                return load(t).T, tn.input[0] + "_T"
        return None, None

    alias = {n.output[0]: n.input[0] for n in graph.node if n.op_type == "Identity" and n.input[0] in inits}
    produit_par = {o: n for n in graph.node for o in n.output}

    quantifies = {}  # clé -> (nom B, nom scales)
    utilises = set()
    nouveaux = []
    n_q = n_skip = 0
    for node in graph.node:
        if node.op_type != "MatMul" or len(node.input) != 2 or any(s in (node.name or "") for s in exclus):
            nouveaux.append(node)
            continue
        # Poids à gauche : logits = embedding[V, D] @ x^T[B, D, T] -> [B, V, T]. Équivalent : x[B, T, D] @ W^T
        # (MatMulNBits, B = W tel quel, K = D, N = V) puis Transpose (0, 2, 1) pour retrouver [B, V, T].
        gauche = alias.get(node.input[0], node.input[0])
        tr = produit_par.get(node.input[1])
        if (gauche in inits and len(inits[gauche].dims) == 2 and inits[gauche].data_type == TensorProto.FLOAT
                and tr is not None and tr.op_type == "Transpose"
                and [list(at.ints) for at in tr.attribute if at.name == "perm"] == [[0, 2, 1]]):
            if a.sans_logits or any(s in (node.name or "") for s in exclus):
                nouveaux.append(node); n_skip += 1; continue
            if gauche not in quantifies:
                w = load(inits[gauche])
                b, sc = quantifier_nbits(np.ascontiguousarray(np.asarray(w, dtype=np.float32).T), a.bits, a.bloc)
                quantifies[gauche] = (gauche + "_nb", gauche + "_nbs", b, sc, (w.shape[1], w.shape[0]))
                del w
            nb_name, s_name, _, _, (k, n) = quantifies[gauche]
            utilises.add(gauche)
            base = node.name or node.output[0]
            nouveaux.append(helper.make_node(
                "MatMulNBits", [tr.input[0], nb_name, s_name], [base + "_nbout"], name=base + "_nb",
                domain="com.microsoft", K=int(k), N=int(n), bits=a.bits, block_size=a.bloc, accuracy_level=a.acc))
            nouveaux.append(helper.make_node("Transpose", [base + "_nbout"], [node.output[0]],
                                             name=base + "_nbt", perm=[0, 2, 1]))
            n_q += 1
            continue
        src_w = node.input[1]
        if src_w in inits and (len(inits[src_w].dims) != 2 or inits[src_w].data_type != TensorProto.FLOAT):
            nouveaux.append(node); n_skip += 1; continue
        if src_w not in inits and src_w not in transposes:
            nouveaux.append(node)  # produit d'activations (attention)
            continue
        dims = list(inits[src_w].dims) if src_w in inits else list(inits[transposes[src_w].input[0]].dims)[::-1]
        if dims[0] < a.min_k:
            nouveaux.append(node); n_skip += 1; continue
        if src_w not in quantifies:
            w, cle = poids_de(src_w)
            if w is None:
                nouveaux.append(node); n_skip += 1; continue
            b, s = quantifier_nbits(np.asarray(w, dtype=np.float32), a.bits, a.bloc)
            quantifies[src_w] = (src_w + "_nb", src_w + "_nbs", b, s, w.shape)
            del w
        nb_name, s_name, _, _, (k, n) = quantifies[src_w]
        utilises.add(src_w)
        nouveaux.append(helper.make_node(
            "MatMulNBits", [node.input[0], nb_name, s_name], [node.output[0]],
            name=(node.name or node.output[0]) + "_nb", domain="com.microsoft",
            K=int(k), N=int(n), bits=a.bits, block_size=a.bloc, accuracy_level=a.acc))
        n_q += 1

    # Transpose devenus inutiles (poids partagé quantifié, plus d'autre utilisateur).
    encore_utilises = {i for nd in nouveaux for i in nd.input}
    nouveaux = [nd for nd in nouveaux
                if not (nd.op_type == "Transpose" and nd.output[0] in utilises and nd.output[0] not in encore_utilises)]
    encore_utilises = {i for nd in nouveaux for i in nd.input}

    new_inits = []
    for t in graph.initializer:
        # Un initialiseur n'est gardé que s'il est encore lu (poids quantifiés : plus lus).
        if t.name in encore_utilises:
            new_inits.append(numpy_helper.from_array(load(t), t.name) if t.external_data else t)
    for nom, (nb_name, s_name, b, s, _) in quantifies.items():
        new_inits.append(numpy_helper.from_array(b, nb_name))
        new_inits.append(numpy_helper.from_array(s, s_name))
    del graph.node[:]
    graph.node.extend(nouveaux)
    del graph.initializer[:]
    graph.initializer.extend(new_inits)
    if not any(o.domain == "com.microsoft" for o in model.opset_import):
        model.opset_import.append(helper.make_opsetid("com.microsoft", 1))
    onnx.save(model, a.dst)
    print(f"{n_q} MatMul -> MatMulNBits ({a.bits} bits, blocs de {a.bloc}, accuracy_level {a.acc}), "
          f"{n_skip} laissés tels quels -> {a.dst} ({Path(a.dst).stat().st_size} octets)")


if __name__ == "__main__":
    main()
