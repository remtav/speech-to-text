#!/usr/bin/env python3
"""Comme exporter.py, pour un Lite-Whisper (efficient-speech/lite-whisper-large-v3-turbo*) :
l'encodeur a des couches linéaires factorisées en deux matrices (rang réduit), que
`openai-whisper` ne connaît pas. On construit le modèle OpenAI, on remplace ces couches
par un module équivalent (mêmes calculs que modeling_lite_whisper.py d'efficient-speech,
lu mais non exécuté), puis on charge le point de contrôle produit par hf_vers_openai.py
(clés `...weight1` / `...weight2`, rangs déduits des formes des tenseurs).

Usage : python exporter_lite.py EXPORT_ONNX.py CHECKPOINT.pt
Écrit turbo-encoder.onnx, turbo-decoder.onnx, turbo-tokens.txt dans le dossier courant.
"""
import runpy
import sys

import torch
import whisper
from torch import nn
from whisper.model import ModelDimensions, Whisper


class LinearLowRank(nn.Module):
    def __init__(self, in_features: int, rank: int, out_features: int):
        super().__init__()
        self.weight1 = nn.Parameter(torch.empty(in_features, rank))
        self.weight2 = nn.Parameter(torch.empty(rank, out_features))
        self.bias = nn.Parameter(torch.zeros(out_features))

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return (x @ self.weight1.to(x.dtype)) @ self.weight2.to(x.dtype) + self.bias.to(x.dtype)


def load_lite(checkpoint: str) -> Whisper:
    ck = torch.load(checkpoint, map_location="cpu")
    state = ck["model_state_dict"]
    model = Whisper(ModelDimensions(**ck["dims"]))
    replaced = 0
    for key, w1 in state.items():
        if not key.endswith(".weight1"):
            continue
        path = key[: -len(".weight1")].split(".")
        parent = model
        for p in path[:-1]:
            parent = parent[int(p)] if p.isdigit() else getattr(parent, p)
        w2 = state[key[: -1] + "2"]
        module = LinearLowRank(w1.shape[0], w1.shape[1], w2.shape[1])
        if path[-1].isdigit():
            parent[int(path[-1])] = module
        else:
            setattr(parent, path[-1], module)
        replaced += 1
    model.load_state_dict(state, strict=True)
    alignment = getattr(model, "alignment_heads", None)
    print(f"{replaced} couches à rang réduit, alignment_heads={'oui' if alignment is not None else 'non'}")
    return model.eval()


def main(script: str, checkpoint: str) -> None:
    real_load = whisper.load_model

    def load_model(name, *a, **kw):
        return load_lite(checkpoint) if name == "turbo" else real_load(name, *a, **kw)

    whisper.load_model = load_model
    real_export = torch.onnx.export

    def export(*a, **kw):
        kw["dynamo"] = False
        return real_export(*a, **kw)

    torch.onnx.export = export
    sys.argv = [script, "--model", "turbo"]
    runpy.run_path(script, run_name="__main__")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    main(sys.argv[1], sys.argv[2])
