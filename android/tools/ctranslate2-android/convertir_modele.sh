#!/usr/bin/env bash
# Convertit openai/whisper-large-v3-turbo au format CTranslate2, poids int8 (issue #84).
# Source : les poids publiés par OpenAI sur Hugging Face, révision fixée ; aucune conversion tierce.
#
# usage : ./convertir_modele.sh SORTIE
# Environnement : python3 -m venv venv && . venv/bin/activate
#                 pip install ctranslate2==4.9.0 transformers torch --extra-index-url https://download.pytorch.org/whl/cpu
# Résultat : SORTIE/{model.bin, config.json, vocabulary.json, tokenizer.json, preprocessor_config.json} et
# SORTIE/SHA256SUMS. Le pont n'utilise que les trois premiers.
set -euo pipefail
OUT=$(realpath -m "$1")
REPO=openai/whisper-large-v3-turbo
REV=41f01f3fe87f28c78e2fbf8b568835947dd65ed9
# SHA-256 des poids à cette révision (oid LFS donné par Hugging Face).
SHA_POIDS=542566a422ae4f3fd23f1ba11add198fca01bbf82e66e6a2857b3f608b1eb9d1
SRC="$OUT.hf"
mkdir -p "$SRC"
for f in config.json generation_config.json preprocessor_config.json tokenizer.json tokenizer_config.json \
         vocab.json merges.txt normalizer.json added_tokens.json special_tokens_map.json model.safetensors; do
  [ -s "$SRC/$f" ] || curl -fsSL -o "$SRC/$f" "https://huggingface.co/$REPO/resolve/$REV/$f"
done
echo "$SHA_POIDS  $SRC/model.safetensors" | sha256sum -c -
ct2-transformers-converter --model "$SRC" --output_dir "$OUT" --quantization int8 --force \
  --copy_files tokenizer.json preprocessor_config.json
(cd "$OUT" && sha256sum model.bin config.json vocabulary.json tokenizer.json preprocessor_config.json > SHA256SUMS)
cat "$OUT/SHA256SUMS"
du -sh "$OUT"
