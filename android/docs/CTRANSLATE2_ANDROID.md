# CTranslate2 (moteur de faster-whisper) sur Android : essai de l'issue #84

Statut : 2026-11-19, **prototype fonctionnel, mesure sur le téléphone à faire**. Décision : **aucune pour
l'instant** ; elle attend la comparaison sur le téléphone (section 5). L'application normale est inchangée :
la bibliothèque n'entre dans l'APK qu'avec `-Pct2Libs`, et l'essai n'apparaît que dans « Comparer les modèles ».

Outils et procédure : [`android/tools/ctranslate2-android/README.md`](../tools/ctranslate2-android/README.md).

Convention : **[vérifié]** lu ou exécuté ; **[mesuré hôte]** sur x86-64 (4 cœurs partagés), pas sur un
téléphone ; **[supposé]** à confirmer.

## Résumé

| Question de l'issue | Réponse |
| --- | --- |
| CTranslate2 se compile-t-il pour `arm64-v8a` ? | **Oui** [vérifié] : v4.9.0, NDK r27d, sans MKL ni CUDA, backend **Ruy** (int8 et float32), noyaux **NEON** choisis à l'exécution, OpenMP du NDK. Aucun correctif des sources. |
| Taille | `libct2whisper.so` (CTranslate2 statique + pont + JNI) : **2,4 Mo**, seules dépendances `libc`, `libm`, `libdl`, `liblog` [vérifié]. À comparer aux 31 Mo de sherpa-onnx + onnxruntime, qui restent dans l'APK. |
| Modèle | `openai/whisper-large-v3-turbo` converti en int8 : **780 Mo** contre 1 037 Mo pour l'export sherpa-onnx [vérifié]. Mémoire en pointe sur l'hôte : ~1,0 Go dans les deux cas [mesuré hôte]. |
| Même texte que faster-whisper ? | **Oui pour le pré-traitement** : log-mel identique à 2,4·10⁻⁷ près [vérifié]. Glouton par `ctranslate2::models::Whisper`. |
| Exactitude | **Égale ou meilleure** à entrée d'encodeur égale : jeu québécois de 172 extraits, WER **13,1 %** contre **14,5 %** pour le Whisper de l'application (sherpa-onnx), marge 1 000 [mesuré hôte]. |
| Vitesse | **Inconnue sur le téléphone.** Sur l'hôte, avec le CTranslate2 de PyPI (oneDNN/MKL), 1,08 fois plus lent que sherpa-onnx [mesuré hôte]. Le chemin du téléphone (Ruy + NEON) ne peut pas être mesuré ici. |
| Entrée plus courte que 30 s ? | **Oui** [vérifié] : CTranslate2 accepte jusqu'à 3 000 images. La fenêtre de 30 s de faster-whisper coûte **3 fois plus** pour la même exactitude (12,8 %) [mesuré hôte]. Le pont prend donc la marge de l'application (`tailPaddings`). |

## 1. Compilation

- `compiler.sh` construit CTranslate2 en sous-projet statique dans le CMake du pont : le paquet statique
  installé n'exporte pas de cibles CMake et ne contient pas les bibliothèques de Ruy.
- Deux pièges réglés : CTranslate2 annule `CMAKE_POSITION_INDEPENDENT_CODE` après Ruy (forcé dans le cache) ;
  FindOpenMP choisit `libomp.so`, absent des téléphones (forcé à `libomp.a` du NDK, plus `-static-openmp`).
- libc++ statique (`c++_static`) : l'AAR de sherpa-onnx n'embarque pas `libc++_shared.so`.
- Seuls les 4 points d'entrée JNI sont exportés (`--exclude-libs,ALL`). Pages de 16 Ko (`max-page-size=16384`).
- NEON : CTranslate2 compile `kernels.cc` une seconde fois avec `-DUSE_NEON` et le choisit à l'exécution
  (`cpu_supports_neon()` est toujours vrai sur arm64). Le rapport de « Comparer les modèles » affiche
  `ISA NEON` quand c'est le cas (`Ct2Native.buildInfo`). Ruy contient ses noyaux int8 `sdot` (324 instructions
  dans la `.so`), choisis s'il détecte l'extension dotprod (Snapdragon 8 Gen 2 : oui [supposé]).

## 2. Pont et moteur

- `jni/ct2_whisper.cc` refait ce que faster-whisper 1.2.1 fait en Python : log-mel (FFT de 400 points, Hann
  périodique, filtres de Slaney, 128 bandes), invite `<|startoftranscript|><|fr|><|transcribe|><|notimestamps|>`,
  langue détectée par `detect_language` quand aucune n'est forcée (encodeur calculé une seule fois), beam 1,
  décodage des jetons octet par octet.
- **Marge** : zéros dans l'espace des caractéristiques après l'audio, comme sherpa-onnx (`tailPaddings`) et
  comme le remplissage de faster-whisper. Un premier essai avec du silence *audio* ajouté avant le log-mel
  faisait répéter Whisper (« … les rebelles. La sorcière décide… », WER 24,7 % sur 7 extraits) ; abandonné.
- **Garde-fou** : au plus 15 jetons par seconde d'audio (+ 8). Le filtre de répétition de « Automatique »
  (`WhisperRepetition`, issue #49) s'applique, sans la règle du plafond de 6 jetons/s, propre à sherpa-onnx.
- Kotlin : `stt/Ct2WhisperEngine.kt` (`Ct2Native`, `Ct2WhisperEngine`, `Ct2Experiment`), une ligne dans
  `DefaultEngineFactory`, l'ajout à `CompareActivity`. Pas d'entrée dans `ModelCatalog`, aucun réglage.

## 3. Mesures hôte (x86-64, 4 threads, langue fr forcée)

Banc `banc_hote.py` : variantes en alternance clip par clip ; « ct2 » = CTranslate2 4.9.0 de PyPI (oneDNN/MKL),
avec exactement les entrées du pont.

**Jeu québécois, 172 extraits (17,2 min, 1 673 mots), un passage :**

| Variante | Temps total | WER | Répétitions (texte > 1,6 × la référence) |
| --- | --- | --- | --- |
| sherpa-onnx, marge 1 000 (**l'application**) | 262,8 s | 14,5 % | 0 |
| CTranslate2, marge 1 000 | 284,6 s (×1,08) | **13,1 %** | 0 |
| sherpa-onnx, marge 300 | 190,2 s | 22,0 % | 10 |
| CTranslate2, marge 300 | 140,2 s | 22,8 % | 10 |
| CTranslate2, fenêtre de 30 s (faster-whisper) | 785,1 s (×2,99) | 12,8 % | 0 |

À marge égale, 60 phrases sur 172 diffèrent entre les deux moteurs (quantifications int8 différentes : par
canal chez CTranslate2, par onnxruntime chez sherpa), avec un léger avantage à CTranslate2. La marge 300 fait
répéter les deux moteurs autant : ce n'est pas un levier propre à l'un d'eux.

**Pont C++ lui-même** (`Ct2WhisperHostTest.againstTheAppWhisper`, 7 extraits du dépôt) : texte juste, WER
13,3 % contre 10,6 % pour sherpa sur ce petit jeu (2 phrases sur 7 différentes), mais **5 fois plus lent** :
sur x86-64, Ruy n'active ses noyaux AVX qu'avec une option de compilation et tourne en C++ portable. Ce chiffre
ne dit rien du téléphone, où Ruy a ses noyaux NEON.

## 4. Ce qui reste incertain

- **La vitesse sur le téléphone** : c'est tout l'enjeu, et rien ici ne la prédit. onnxruntime (sherpa) et Ruy
  ont des noyaux int8 ARM optimisés tous les deux ; le résultat peut aller dans un sens comme dans l'autre.
- Hallucination sur le silence : 2 s de silence donnent « Sous-titrage ST' 501 » (hôte), comme le Whisper de
  sherpa-onnx en produit ; le filtre des génériques de l'application s'applique au texte des deux moteurs.
- Entretien : un second moteur natif, un second format de modèle à héberger et à vérifier par SHA-256.
- Licences : CTranslate2 MIT, Ruy Apache 2.0, cpuinfo BSD-2, spdlog MIT, modèle MIT [vérifié dans les dépôts],
  à reprendre dans l'écran des licences si l'essai est adopté.

## 5. Mesure sur le téléphone (à faire)

Procédure : README de l'outil, section 4. L'écran « Comparer les modèles » mesure le Whisper de l'application
puis l'essai sur les mêmes extraits et réglages, et finit le rapport par le bloc « CTranslate2 face au Whisper
de l'application » (temps par extrait, vitesse ×N, WER avant → après, chargement, mémoire). À faire avec 2, 4
et 6 threads, sur les extraits du dépôt, plus un mot isolé (« friperie », ~1 s), une phrase (~5 s) et un long
segment (~20 à 30 s).

Critères (issue) : **adopter ou garder en option** si la vitesse est nettement meilleure à exactitude égale ;
**écarter** si le gain est faible ou nul.
