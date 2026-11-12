#!/usr/bin/env python3
"""Lance le script d'export de sherpa-onnx (scripts/whisper/export-onnx.py)
sur un point de contrôle OpenAI local, en le faisant passer pour « turbo »
(même architecture : large-v3-turbo, 128 mels, 100 langues).

Usage : python exporter.py EXPORT_ONNX.py CHECKPOINT.pt
Écrit turbo-encoder.int8.onnx, turbo-decoder.int8.onnx, turbo-tokens.txt (et
les versions fp32) dans le dossier courant.
"""
import runpy
import sys

import torch
import whisper


def main(script: str, checkpoint: str) -> None:
    real_load = whisper.load_model

    def load_model(name, *a, **kw):
        # Seul « turbo » est demandé au script ; on y substitue le point de contrôle.
        return real_load(checkpoint if name == "turbo" else name, *a, **kw)

    whisper.load_model = load_model

    # Le script de sherpa-onnx est écrit pour l'exporteur TorchScript ; depuis torch 2.9 l'exporteur
    # par défaut (dynamo) fige la longueur des jetons du décodeur et casse l'inférence.
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
