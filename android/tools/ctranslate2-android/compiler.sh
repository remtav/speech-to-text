#!/usr/bin/env bash
# Compile libct2whisper.so : CTranslate2 (moteur de faster-whisper) + pont Whisper + JNI (issue #84).
# CPU seulement : backend Ruy (int8 et float32), noyaux NEON choisis à l'exécution, OpenMP et libc++
# du NDK liés en statique : une seule .so à côté de celles de sherpa-onnx. Sans MKL, sans CUDA.
#
# usage : NDK=/chemin/android-ndk-r27d ./compiler.sh SOURCES SORTIE [ABI]
#   SOURCES : clone de CTranslate2 au tag v4.9.0 avec ses sous-modules (voir README.md).
#   SORTIE  : dossier de travail ; la bibliothèque finit dans SORTIE/jniLibs/ABI/libct2whisper.so.
#   ABI     : arm64-v8a (défaut) ; « host » compile pour la machine (Linux x86-64) avec les mêmes
#             options, pour les tests JVM et l'outil ct2_whisper_cli.
set -euo pipefail
ICI=$(cd "$(dirname "$0")" && pwd)
SRC=$(realpath "$1"); OUT=$(realpath -m "$2"); ABI=${3:-arm64-v8a}
VERSION_ATTENDUE=a22ba1c63ee35d1a0d25db149aa2a66d36a6a732   # tag v4.9.0
REV=$(git -C "$SRC" rev-parse HEAD)
[ "$REV" = "$VERSION_ATTENDUE" ] || { echo "CTranslate2 $REV : attendu v4.9.0 ($VERSION_ATTENDUE)" >&2; exit 1; }

CIBLE=()
if [ "$ABI" != host ]; then
  : "${NDK:?NDK doit pointer vers le NDK Android (r27d testé)}"
  CIBLE=(-DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
         -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_static)
fi
cmake -S "$ICI/jni" -B "$OUT/$ABI/jni-build" -G Ninja "${CIBLE[@]}" \
  -DCMAKE_BUILD_TYPE=Release -DCT2_SRC="$SRC" -DCMAKE_INSTALL_PREFIX="$OUT/$ABI/jni"
cmake --build "$OUT/$ABI/jni-build" -j"$(nproc)"
cmake --install "$OUT/$ABI/jni-build" --strip
mkdir -p "$OUT/jniLibs/$ABI"
cp "$OUT/$ABI/jni/lib/libct2whisper.so" "$OUT/jniLibs/$ABI/"
ls -l "$OUT/jniLibs/$ABI/libct2whisper.so"
sha256sum "$OUT/jniLibs/$ABI/libct2whisper.so"
