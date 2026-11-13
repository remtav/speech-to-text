"""Piste 2.2 de l'issue #40 (expérimental) : fusion des positions de silence dans l'encodeur de turbo.

usage : python fusion_silence.py ENC_IN ENC_OUT --apres N --groupe G

Garde les images de silence (marge de fin) à l'entrée, mais après le bloc N de l'encodeur (0 à 31 ; N = 4 signifie
« après les blocs 0 à 3 ») remplace les positions de silence par leur moyenne par groupes de G. Les blocs suivants, la
couche finale et les projections `cross_k` / `cross_v` traitent donc R + (T' − R) / G positions au lieu de T', où
T' = ceil(T / 2) est la longueur après la convolution de pas 2 et R = ceil(n_frames / 2) celle de l'audio réel. Le reste
(moins d'un groupe) est abandonné.

Le graphe reçoit une entrée de plus, `n_frames` (int64, forme [1]) : le nombre d'images de l'audio réel, que sherpa-onnx
connaît mais ne passe pas à l'encodeur. Ce modèle ne se charge donc pas dans sherpa-onnx 2.0.1 tel quel : il se mesure
avec `replique.py` (qui remplit `n_frames`). Les poids sont ceux de `turbo-encoder.int8.onnx`, inchangés (chirurgie du
graphe, pas de réexport) ; le décodeur accepte déjà une longueur de `cross_k` variable."""
import argparse

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper


def main():
    p = argparse.ArgumentParser()
    p.add_argument("enc_in")
    p.add_argument("enc_out")
    p.add_argument("--apres", type=int, required=True, help="nombre de blocs avant la fusion (1 à 31)")
    p.add_argument("--groupe", type=int, required=True)
    a = p.parse_args()
    m = onnx.load(a.enc_in)
    g = m.graph
    src = f"/audioEncoder/blocks.{a.apres - 1}/Add_1_output_0"
    assert any(src in n.output for n in g.node), src
    G = a.groupe
    P = "/fusion_silence"

    def c(name, arr):
        g.initializer.append(numpy_helper.from_array(np.array(arr), P + name))
        return P + name

    g.input.append(helper.make_tensor_value_info("n_frames", TensorProto.INT64, [1]))
    nodes = []

    def nd(op, ins, out, **kw):
        nodes.append(helper.make_node(op, ins, [P + out], name=P + "/" + out, **kw))
        return P + out

    zero, one, deux, grp = c("/0", [0]), c("/1", [1]), c("/2", [2]), c("/G", [G])
    # R = ceil(n_frames / 2) = (n_frames + 1) // 2 ; T' = longueur de x
    r = nd("Div", [nd("Add", ["n_frames", one], "nf1"), deux], "R")
    tp = nd("Gather", [nd("Shape", [src], "shp"), one], "Tp", axis=0)  # forme [1]
    r = nd("Min", [r, tp], "Rmin")
    lg = nd("Mul", [nd("Div", [nd("Sub", [tp, r], "L"), grp], "Lq"), grp], "Lg")
    fin = nd("Add", [r, lg], "fin")
    audio = nd("Slice", [src, zero, r, one], "audio")
    sil = nd("Slice", [src, r, fin, one], "sil")
    shp = nd("Concat", [one, c("/m1", [-1]), grp, c("/D", [1280])], "shp4", axis=0)
    moy = nd("ReduceMean", [nd("Reshape", [sil, shp], "sil4")], "moy", axes=[2], keepdims=0)
    out = nd("Concat", [audio, moy], "x", axis=1)
    for n in g.node:
        for i, name in enumerate(n.input):
            if name == src:
                n.input[i] = out
    # insérer les nœuds juste après le producteur de src (ordre topologique)
    idx = next(i for i, n in enumerate(g.node) if src in n.output)
    for k, n in enumerate(nodes):
        g.node.insert(idx + 1 + k, n)
    del g.value_info[:]
    onnx.checker.check_model(m)
    onnx.save(m, a.enc_out)
    print(f"fusion par groupes de {G} après {a.apres} blocs → {a.enc_out}")


if __name__ == "__main__":
    main()
