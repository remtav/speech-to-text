#!/usr/bin/env bash
# Convertit efficient-speech/lite-whisper-large-v3-turbo-acc (transformers, Apache-2.0) en fichiers
# sherpa-onnx int8 aux noms de Whisper turbo (turbo-encoder.int8.onnx, turbo-decoder.int8.onnx,
# turbo-tokens.txt) : même branche ModelFamily.WHISPER dans SherpaEngine, 128 mels.
# Usage : bash convertir_lite.sh DOSSIER_DE_TRAVAIL [VARIANTE]   (VARIANTE : turbo-acc par défaut,
#         ou turbo, turbo-fast). Prévoir ~15 Go de disque, ~8 Go de mémoire.
set -euo pipefail
ici="$(cd "$(dirname "$0")" && pwd)"
tr="${1:?dossier de travail}"; var="${2:-turbo-acc}"
mkdir -p "$tr"/{src,work,sortie}; cd "$tr"
HF="https://huggingface.co/efficient-speech/lite-whisper-large-v3-$var/resolve/main"
for f in model.safetensors config.json; do [ -s "src/$f" ] || curl -fL -o "src/$f" "$HF/$f"; done
[ -s export-onnx.py ] || curl -fL -o export-onnx.py \
  https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/master/scripts/whisper/export-onnx.py
echo "5e0ddabfac8330ee9aecaba4c1b4286d1cd9f88df4cd30e54706b011bf976e45  export-onnx.py" | sha256sum -c - \
  || echo "AVERTISSEMENT : le script d'export de sherpa-onnx a changé depuis la conversion documentée"
[ -d venv ] || python3 -m venv venv
venv/bin/pip install -q torch --index-url https://download.pytorch.org/whl/cpu
venv/bin/pip install -q openai-whisper onnx onnxruntime onnxscript safetensors numpy
cd work
# Les clés ...weight1/...weight2 (rang réduit) passent telles quelles dans le .pt.
../venv/bin/python -I "$ici/hf_vers_openai.py" ../src lite.pt
# Échec possible à l'étape int8 du script sherpa sur une machine modeste : refaite ci-dessous.
../venv/bin/python -I "$ici/exporter_lite.py" ../export-onnx.py "$PWD/lite.pt" || true
../venv/bin/python -I "$ici/quantifier_int8.py" turbo-decoder.onnx ../sortie/turbo-decoder.int8.onnx
# Encodeur : l'int8 par tenseur de toutes les MatMul casse le modèle (sortie « ... », issue #25) ;
# comme la version publiée, les MatMul h @ W2 des couches factorisées restent en fp32.
../venv/bin/python -I "$ici/quantifier_lite.py" turbo-encoder.onnx ../sortie/turbo-encoder.int8.onnx --w2 fp32
cp turbo-tokens.txt ../sortie/
cd ../sortie && sha256sum * | tee SHA256SUMS && stat -c '%n %s' *
