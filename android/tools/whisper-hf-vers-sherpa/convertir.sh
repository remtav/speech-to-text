#!/usr/bin/env bash
# Convertit ele-sage/whisper-large-v3-turbo-fr-quebecois (transformers) en fichiers
# sherpa-onnx int8 (turbo-encoder.int8.onnx, turbo-decoder.int8.onnx, turbo-tokens.txt).
# Usage : bash convertir.sh DOSSIER_DE_TRAVAIL   (prévoir ~15 Go de disque, ~8 Go de mémoire)
# Sortie : DOSSIER_DE_TRAVAIL/sortie/ et SHA256SUMS.
set -euo pipefail
ici="$(cd "$(dirname "$0")" && pwd)"
tr="${1:?dossier de travail}"; mkdir -p "$tr"/{src,work,sortie}; cd "$tr"
HF=https://huggingface.co/ele-sage/whisper-large-v3-turbo-fr-quebecois/resolve/main
# Version de la fiche convertie ; ajouter ?revision=... à l'URL pour figer un commit.
for f in model.safetensors config.json; do [ -s "src/$f" ] || curl -fL -o "src/$f" "$HF/$f"; done
# Le script d'export de sherpa-onnx tel que relevé le 2026-11-12 (SHA-256 ci-dessous).
[ -s export-onnx.py ] || curl -fL -o export-onnx.py \
  https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/scripts/whisper/export-onnx.py
echo "5e0ddabfac8330ee9aecaba4c1b4286d1cd9f88df4cd30e54706b011bf976e45  export-onnx.py" | sha256sum -c - \
  || echo "AVERTISSEMENT : le script d'export de sherpa-onnx a changé depuis la conversion documentée"
[ -d venv ] || python3 -m venv venv
venv/bin/pip install -q torch --index-url https://download.pytorch.org/whl/cpu
venv/bin/pip install -q openai-whisper onnx onnxruntime onnxscript safetensors numpy
cd work
../venv/bin/python -I "$ici/hf_vers_openai.py" ../src qc.pt
# Le script de sherpa-onnx s'arrête par un MemoryError/OOM à l'étape int8 sur une machine modeste :
# c'est normal, la quantification est refaite ci-dessous avec moins de mémoire.
../venv/bin/python -I "$ici/exporter.py" ../export-onnx.py "$PWD/qc.pt" || true
../venv/bin/python -I "$ici/quantifier_int8.py" turbo-decoder.onnx ../sortie/turbo-decoder.int8.onnx
../venv/bin/python -I "$ici/quantifier_int8.py" turbo-encoder.onnx ../sortie/turbo-encoder.int8.onnx
cp turbo-tokens.txt ../sortie/
cd ../sortie && sha256sum * | tee SHA256SUMS && stat -c '%n %s' *
