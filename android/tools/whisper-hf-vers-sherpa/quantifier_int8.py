#!/usr/bin/env python3
"""Quantification int8 « dynamique » d'un export ONNX de Whisper, avec peu de mémoire.

Fait ce que fait `onnxruntime.quantization.quantize_dynamic(op_types_to_quantize=["MatMul"],
weight_type=QInt8)` utilisé par le script d'export de sherpa-onnx (poids int8
symétriques par tenseur ; activations quantifiées à la volée par
DynamicQuantizeLinear ; MatMulInteger), mais en lisant les poids un à un dans le
fichier de données externes au lieu de tout charger : l'encodeur de
large-v3-turbo fait 2,6 Go en fp32 et la version d'onnxruntime fait dépasser 7 Go
de mémoire vive à `quantize_dynamic`.

Le modèle écrit est un seul fichier .onnx (poids embarqués), comme ceux de
csukuangfj/sherpa-onnx-whisper-turbo.

Usage : python quantifier_int8.py MODELE_FP32.onnx MODELE_INT8.onnx
"""
import sys
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


def main(src: str, dst: str) -> None:
    src_path = Path(src)
    model = onnx.load(src, load_external_data=False)
    graph = model.graph
    inits = {t.name: t for t in graph.initializer}

    def load(t: TensorProto) -> np.ndarray:
        return read_external(t, src_path.parent) if t.external_data else numpy_helper.to_array(t)

    quantized = {}  # nom du poids -> (int8, échelle)
    new_nodes = []
    for node in graph.node:
        w = inits.get(node.input[1]) if node.op_type == "MatMul" and len(node.input) == 2 else None
        if w is None or len(w.dims) != 2 or w.data_type != TensorProto.FLOAT:
            new_nodes.append(node)
            continue
        if w.name not in quantized:
            arr = load(w)
            scale = max(float(np.abs(arr).max()), 1e-8) / 127.0
            q = np.clip(np.rint(arr / scale), -127, 127).astype(np.int8)
            quantized[w.name] = (q, np.float32(scale))
        base = node.name or node.output[0]
        a = node.input[0]
        aq, a_scale, a_zp = f"{base}_a_q", f"{base}_a_scale", f"{base}_a_zp"
        acc, accf, comb = f"{base}_int32", f"{base}_f32", f"{base}_scale"
        new_nodes += [
            helper.make_node("DynamicQuantizeLinear", [a], [aq, a_scale, a_zp], name=f"{base}_dql"),
            helper.make_node("MatMulInteger", [aq, w.name + "_q", a_zp, w.name + "_zp"], [acc], name=f"{base}_mmi"),
            helper.make_node("Cast", [acc], [accf], name=f"{base}_cast", to=TensorProto.FLOAT),
            helper.make_node("Mul", [a_scale, w.name + "_scale"], [comb], name=f"{base}_mulscale"),
            helper.make_node("Mul", [accf, comb], [node.output[0]], name=f"{base}_mul"),
        ]

    # Initialiseurs : poids quantifiés remplacés, les autres relus et embarqués.
    new_inits = []
    for t in graph.initializer:
        if t.name in quantized:
            q, scale = quantized[t.name]
            new_inits.append(numpy_helper.from_array(q, t.name + "_q"))
            new_inits.append(numpy_helper.from_array(np.array(0, dtype=np.int8), t.name + "_zp"))
            new_inits.append(numpy_helper.from_array(np.array(scale, dtype=np.float32), t.name + "_scale"))
        elif t.external_data:
            new_inits.append(numpy_helper.from_array(load(t), t.name))
        else:
            new_inits.append(t)
    del graph.node[:]
    graph.node.extend(new_nodes)
    del graph.initializer[:]
    graph.initializer.extend(new_inits)
    onnx.save(model, dst)
    print(f"{len(quantized)} poids quantifiés -> {dst} ({Path(dst).stat().st_size} octets)")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    main(sys.argv[1], sys.argv[2])
