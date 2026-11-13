"""Profil onnxruntime de l'encodeur (issue #40, piste 1.1) : temps par type de nœud et pour l'attention.

usage : python profil.py ENC [ENC ...] [--images 1500] [--threads 4] [--passes 5]
Entrée : mel de zéros de 128 × images (le coût des MatMul ne dépend pas des valeurs ; DynamicQuantizeLinear non plus,
à une passe sur les données près). Les nœuds ajoutés par `attention_int8.py` (suffixe `_q40_`) sont comptés avec
l'attention. Moyenne sur les passes, après une passe d'échauffement non comptée."""
import argparse
import collections
import json
import os
import re
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from replique import session  # noqa: E402

p = argparse.ArgumentParser()
p.add_argument("enc", nargs="+")
p.add_argument("--images", type=int, default=1500)
p.add_argument("--threads", type=int, default=4)
p.add_argument("--passes", type=int, default=5)
a = p.parse_args()
mel = np.zeros((1, 128, a.images), np.float32)
att = re.compile(r"/attn/(MatMul|MatMul_1|Softmax|Cast_3|Cast_4)(_q40_|$|/)")
for enc in a.enc:
    s = session(enc, a.threads, profile="/tmp/profil40")
    feed = {"mel": mel}
    if "n_frames" in [i.name for i in s.get_inputs()]:
        feed["n_frames"] = np.array([a.images - 1000], np.int64)
    s.run(None, feed)
    for _ in range(a.passes):
        s.run(None, feed)
    path = s.end_profiling()
    ev = json.load(open(path))
    runs = [e for e in ev if e.get("cat") == "Session" and e["name"] == "model_run"]
    total = sum(e["dur"] for e in runs[1:]) / a.passes / 1000
    by_op, by_att = collections.Counter(), collections.Counter()
    first_run_end = runs[0]["ts"] + runs[0]["dur"]
    for e in ev:
        if e.get("cat") != "Node" or not e["name"].endswith("_kernel_time") or e["ts"] < first_run_end:
            continue
        name = e["name"][: -len("_kernel_time")]
        op = e["args"].get("op_name", "?")
        by_op[op] += e["dur"]
        m = att.search(name)
        if m:
            role = {"MatMul": "QKᵀ", "MatMul_1": "AV", "Softmax": "softmax", "Cast_3": "softmax",
                    "Cast_4": "softmax"}[m.group(1)]
            by_att[role] += e["dur"]
    os.remove(path)
    f = 1 / a.passes / 1000
    print(f"== {enc} : {a.images} images, {a.threads} threads, {total:.0f} ms par passe")
    print("   attention : " + ", ".join(f"{k} {v*f:.0f} ms ({100*v*f/total:.0f} %)" for k, v in by_att.most_common()))
    print("   par type : " + ", ".join(f"{k} {v*f:.0f}" for k, v in by_op.most_common(10)))
