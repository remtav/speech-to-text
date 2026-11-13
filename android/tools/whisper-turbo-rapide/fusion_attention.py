"""Piste 1.3 de l'issue #40 : cœur de l'attention de l'encodeur fusionné en un nœud `com.microsoft.MultiHeadAttention`.

usage : python fusion_attention.py ENC_IN ENC_OUT

Dans chaque bloc `blocks.i/attn`, la chaîne Reshape → Transpose → Mul(échelle) → MatMul(QKᵀ) → Softmax → MatMul(AV) →
Transpose → Reshape est remplacée par MultiHeadAttention(Q, K, V, num_heads=20), opérateur de contribution d'onnxruntime
(présent dans le build standard, donc dans l'AAR de sherpa-onnx). L'échelle par défaut de l'opérateur, 1/√64, est le
produit des deux facteurs 64^-1/4 appliqués à Q et à K par Whisper. Les projections (MatMulInteger int8) ne changent
pas. Les nœuds devenus inutiles sont retirés."""
import sys

import onnx
from onnx import helper


def main(src, dst, heads=20):
    m = onnx.load(src)
    g = m.graph
    n_att = 0
    for i in range(64):
        b = f"/audioEncoder/blocks.{i}/attn"
        q, k, v = f"{b}/query/Add_output_0", f"{b}/key/MatMul_output_0", f"{b}/value/Add_output_0"
        out = f"{b}/Reshape_3_output_0"
        names = {o for n in g.node for o in n.output}
        if out not in names:
            break
        assert q in names and k in names and v in names, b
        # le producteur de `out` est remplacé par le nouveau nœud
        nodes = [n for n in g.node if out not in n.output]
        del g.node[:]
        g.node.extend(nodes)
        idx = max(j for j, n in enumerate(g.node) if {q, k, v} & set(n.output)) + 1
        g.node.insert(idx, helper.make_node("MultiHeadAttention", [q, k, v], [out], name=f"{b}/MHA",
                                            domain="com.microsoft", num_heads=heads))
        n_att += 1
    # retirer les nœuds morts
    needed = {o.name for o in g.output}
    keep = []
    for n in reversed(g.node):
        if any(o in needed for o in n.output):
            keep.append(n)
            needed.update(n.input)
    del g.node[:]
    g.node.extend(reversed(keep))
    used = {i for n in g.node for i in n.input}
    inits = [t for t in g.initializer if t.name in used]
    del g.initializer[:]
    g.initializer.extend(inits)
    if not any(o.domain == "com.microsoft" for o in m.opset_import):
        m.opset_import.append(helper.make_opsetid("com.microsoft", 1))
    del g.value_info[:]
    onnx.save(m, dst)
    print(f"{n_att} blocs fusionnés en MultiHeadAttention → {dst}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
