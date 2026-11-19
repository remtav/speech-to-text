# CTranslate2 (moteur de faster-whisper) sur Android : essai de l'issue #84

Statut : **expérimental**. Rien de ceci n'entre dans l'APK normal ni dans le catalogue des modèles. Rapport et
mesures : [`android/docs/CTRANSLATE2_ANDROID.md`](../../docs/CTRANSLATE2_ANDROID.md).

| Fichier | Rôle |
| --- | --- |
| `compiler.sh` | Compile `libct2whisper.so` : CTranslate2 v4.9.0 (statique, Ruy, NEON, OpenMP du NDK) + pont Whisper + JNI, pour `arm64-v8a` ou pour l'hôte |
| `jni/ct2_whisper.{h,cc}` | Le pont : log-mel identique à faster-whisper 1.2.1, invite, glouton par `ctranslate2::models::Whisper`, décodage des jetons octet par octet |
| `jni/ct2_whisper_jni.cc` | Points d'entrée JNI de `io.github.remtav.dictee.stt.Ct2Native` |
| `jni/ct2_whisper_cli.cc` | Outil hôte : transcrit des WAV sans la JVM (`CT2W_DUMP_MEL` écrit le log-mel pour comparaison) |
| `convertir_modele.sh` | Convertit `openai/whisper-large-v3-turbo` (révision fixée, SHA-256 vérifié) en CTranslate2 int8 |
| `banc_hote.py` | Banc hôte sherpa-onnx contre CTranslate2, protocole de `../whisper-mesure-python/banc_lite.py` |

Côté Kotlin : `stt/Ct2WhisperEngine.kt` (`Ct2Native`, `Ct2WhisperEngine`, `Ct2Experiment`), une ligne dans
`DefaultEngineFactory`, l'ajout à l'écran « Comparer les modèles », et `Ct2WhisperHostTest`.

## 1. Sources de CTranslate2

```bash
git clone --depth 1 --branch v4.9.0 https://github.com/OpenNMT/CTranslate2 ctranslate2
cd ctranslate2
for m in cpu_features spdlog ruy cxxopts; do git submodule update --init --depth 1 third_party/$m; done
(cd third_party/ruy && git submodule update --init --depth 1)   # cpuinfo
```

`compiler.sh` refuse un autre commit que `a22ba1c63ee35d1a0d25db149aa2a66d36a6a732` (tag v4.9.0).

## 2. Compiler

NDK r27d testé (`android-ndk-r27d-linux.zip` de dl.google.com), CMake ≥ 3.18 et Ninja.

```bash
NDK=/chemin/android-ndk-r27d ./compiler.sh /chemin/ctranslate2 /tmp/ct2out            # arm64-v8a
./compiler.sh /chemin/ctranslate2 /tmp/ct2out host                                    # hôte (tests JVM)
```

Résultat : `/tmp/ct2out/jniLibs/arm64-v8a/libct2whisper.so` (≈ 2 Mo, dépendances système seulement :
`libc`, `libm`, `libdl`, `liblog`), et `/tmp/ct2out/host/jni/bin/ct2_whisper_cli` pour l'hôte.

## 3. Convertir le modèle

```bash
python3 -m venv venv && . venv/bin/activate
pip install ctranslate2==4.9.0 transformers torch --extra-index-url https://download.pytorch.org/whl/cpu
./convertir_modele.sh /tmp/turbo-ct2-int8        # 780 Mo, SHA256SUMS à côté
```

## 4. Essayer sur le téléphone

```bash
cd android
./gradlew assembleDebug -Pct2Libs=/tmp/ct2out/jniLibs
adb install -r app/build/outputs/apk/debug/app-debug.apk
D=/sdcard/Android/data/com.kalmia.stt/files/ct2/whisper-large-v3-turbo-int8
adb shell mkdir -p $D
adb push /tmp/turbo-ct2-int8/model.bin /tmp/turbo-ct2-int8/config.json /tmp/turbo-ct2-int8/vocabulary.json $D/
```

Installer aussi le Whisper normal de l'application (sherpa-onnx), c'est la référence. Puis Réglages →
« Comparer les modèles » : le Whisper de l'application est mesuré, puis « Whisper large-v3-turbo ·
CTranslate2 (essai) » juste après, sur les mêmes extraits, avec les mêmes options (threads, langue forcée,
silence ajouté, mode « Automatique »). Le rapport finit par un bloc « CTranslate2 face au Whisper de
l'application » : temps de chaque extrait pour les deux moteurs, rapport de vitesse (×2,00 = CTranslate2 deux
fois plus rapide), WER avant → après, textes différents, chargement et mémoire. L'en-tête dit `ISA NEON` quand
les noyaux NEON sont actifs. Sans la bibliothèque ou sans le modèle, l'écran ne montre rien de nouveau.

Pour la mesure de l'issue : cocher les extraits de référence, enregistrer en plus un mot isolé (~1 s,
« friperie »), une phrase (~5 s) et un long segment (~20 à 30 s), relancer avec 2, 4 et 6 threads (Réglages),
et copier le rapport dans l'issue.

## 5. Mesurer sur l'hôte

```bash
# Pont C++ (Ruy en noyaux portables sur x86-64 : texte fiable, temps non représentatifs)
/tmp/ct2out/host/jni/bin/ct2_whisper_cli /tmp/turbo-ct2-int8 4 1000 fr ../../app/src/main/assets/reference/fr1.wav
# Moteur Kotlin
./gradlew testDebugUnitTest --tests '*Ct2WhisperHostTest' -Pct2HostLib=/tmp/ct2out/host-lib -Pct2Model=/tmp/turbo-ct2-int8
# Banc Python (CTranslate2 de PyPI, oneDNN/MKL sur x86-64), jeu de 172 extraits de ../jeu-quebecois
pip install faster-whisper sherpa-onnx==2.0.1 soundfile
python banc_hote.py qc172 4 1 '*' sherpa1000=sherpa:enc.onnx,dec.onnx,tokens.txt,1000 ct2_1000=ct2:/tmp/turbo-ct2-int8,1000
```
