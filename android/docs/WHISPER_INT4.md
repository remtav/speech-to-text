# Whisper large-v3-turbo en 4 bits (MatMulNBits) : essai de l'issue #26

Statut : **essai terminé sur l'hôte x86-64 (2026-11-13), rien mesuré sur arm64**. Aucun changement de l'application,
aucune entrée de `ModelCatalog`, rien publié sur Hugging Face. Décision : voir la fin.

## 1. Faisabilité (point d'arrêt) : oui, sur l'hôte

| Vérification | Résultat | Preuve |
| --- | --- | --- |
| JAR Linux x86-64 de sherpa-onnx 2.0.1 | ONNX Runtime **1.28.2**, opérateur `MatMulNBits` présent | `strings` sur `libonnxruntime.so` du JAR : `1.28.2`, `MatMulNBits` |
| Paquet Python `sherpa-onnx` 2.0.1 | charge et **exécute** un décodeur avec 33 `MatMulNBits`, puis un encodeur avec 200 | texte exact sur `frqc1.wav` et `en1.wav` (sections 3 et 4) |
| JAR Linux, via les classes Java/Kotlin de l'AAR (`OfflineRecognizer`) | charge et exécute les mêmes modèles | `frqc1.wav` : « Parfois, seul le riz et le pain sont proposés à discrétion. » (décodeur int4) ; sortie sans majuscules ni ponctuation avec l'encodeur int4 |
| AAR arm64-v8a (`libonnxruntime.so`) | ONNX Runtime 1.28.2, chaîne `MatMulNBits` présente (49 occurrences) | `strings` seulement : **cela ne prouve pas l'exécution**. Aucune exécution arm64 n'est possible ici ; l'issue #25 a aussi noté l'opérateur présent dans l'AAR. |

Reste à vérifier sur téléphone : que le modèle se charge et s'exécute sur arm64 (section 6).

## 2. Conversion

`android/tools/whisper-hf-vers-sherpa/quantifier_int4.py` : remplace chaque `MatMul` à poids constant par
`com.microsoft:MatMulNBits` (4 bits, blocs de 32 ou 128, symétrique, échelle = plus grande valeur / −8 comme ORT), en
numpy, un poids à la fois. Mémoire maximale **2,1 Gio**, 11 s pour l'encodeur (fp32 : 2,6 Go). Point de départ : l'export fp32
de `csukuangfj/sherpa-onnx-whisper-turbo` (`turbo-encoder.onnx` + `.weights`, `turbo-decoder.onnx`), c'est-à-dire la
sortie de `export-onnx.py` de sherpa-onnx avant quantification.
Le décodeur a la projection finale `embedding @ xᵀ` (poids à gauche) : le script la traite (`MatMulNBits` puis `Transpose`).

```
python quantifier_int4.py turbo-encoder.onnx turbo-encoder.int4.onnx            # blocs de 32
python quantifier_int4.py turbo-decoder.onnx turbo-decoder.int4.onnx --bloc 128
```
Options : `--bits 8` (témoin), `--acc` (`accuracy_level` 0 = calcul fp32, 4 = activations int8), `--sans-logits`, `--sauf`.

## 3. Tailles [mesuré, fichiers produits ici]

| Fichier | Octets | Remarque |
| --- | --- | --- |
| encodeur int8 (catalogue) | 674 716 297 | |
| encodeur int4, blocs de 32 | 433 456 326 | −36 % |
| encodeur int4, blocs de 128 | 373 245 158 | −45 % |
| décodeur int8 (catalogue) | 361 080 764 | |
| décodeur int4, blocs de 32 | 368 050 439 | **+2 %** |
| décodeur int4, blocs de 128 | 353 224 922 | −2 % |
| décodeur int4, blocs de 32, projection finale en fp32 | 326 557 283 | −10 % |

Le décodeur ne rétrécit pas : sa matrice d'embedding (51 866 × 1 280 fp32, **265 Mo**) sert au `Gather` d'entrée et reste
en fp32 ; les poids 4 bits n'en touchent que ~96 Mo (int8 aujourd'hui). Le gain de taille vient donc de l'encodeur seul.

## 4. Mesures hôte [mesuré]

Machine : Xeon 2,1 GHz, 4 cœurs, 4 threads, sherpa-onnx 2.0.1 Python (ONNX Runtime 1.28.2, celui de l'AAR), `tailPaddings` 1 000,
langue forcée selon le clip. Les modèles sont mesurés **dans la même exécution, en alternance clip par clip**, int8 remesuré à
chaque fois (fichiers du catalogue : tailles identiques). Outil : `android/tools/whisper-mesure-python/bench_int4.py`. WER normalisé
comme `QuebecWer` (issue #24), pas par `Wer` de l'application : écart possible de quelques dixièmes de point. Pas d'exécution
de `HostModelTest` / `WhisperSpeedHostTest` : ils exigent une entrée de `ModelCatalog`, interdite ici.

**Jeu québécois de 172 extraits (#24), 1 673 mots, 1 passage** (`ref` = int8) :

| Variante | Taille enc + déc | WER | Phrases différentes | Temps total | vs int8 |
| --- | --- | --- | --- | --- | --- |
| int8 (référence) | 675 + 361 Mo | 14,4 % (241) | — | 259,5 s | — |
| encodeur et décodeur int4 (b32) | 433 + 368 Mo | 15,4 % (258) | 65 | 553,6 s | **×2,13** |
| décodeur int4 seul (b32), encodeur int8 | 675 + 368 Mo | 14,8 % (248) | 34 | 249,0 s | −4 % (bruit) |

**9 clips de référence de l'application** (en 66 mots, fr 15, frqc 58 ; plafonds de `HostModelTest` : 0,12 / 0,15 / 0,60 ; meilleur de
3 passages ; 9 clips : les écarts de WER valent quelques mots, c'est du bruit) :

| Variante | WER en / fr / frqc | Temps total | Phrases différentes |
| --- | --- | --- | --- |
| int8 | 4,5 % / 6,7 % / 10,3 % | 14,6 à 15,0 s | — |
| enc + déc int4 b32 | 4,5 % / 0 % / 8,6 % | 31,2 s | 3 |
| déc int4 b32 | 4,5 % / 0 % / 12,1 % | 14,7 s | 2 |
| enc + déc int4 b128 | 4,5 % / 0 % / 5,2 % | 29,9 s | 3 |
| déc int4 b128 | 4,5 % / 0 % / 6,9 % | 13,9 s | 2 |
| enc + déc int4 b32, `accuracy_level` 4 | 4,5 % / 0 % / 12,1 % | 34,6 s | 3 |

Tous sous les plafonds. Les blocs de 128 n'ont été mesurés que sur les 9 clips (pas sur les 172).

**Chargement et mémoire maximale** (un processus par modèle, 3 chargements, mémoire = pic du processus, 7 clips fr décodés) :

| Variante | Chargement (meilleur de 3) | Mémoire max | 7 clips fr |
| --- | --- | --- | --- |
| int8 | 1,77 s | 1 641 Mio | 10,2 s |
| enc + déc int4 b32 | 1,66 s | 1 443 Mio (−12 %) | 22,3 s |
| déc int4 b32 | 1,75 s | 1 610 Mio | 9,9 s |
| enc + déc int4 b128 | 1,56 s | 1 346 Mio (−18 %) | 20,4 s |

Le chargement est dominé par autre chose que la lecture des poids (fichiers en cache disque, page cache chaud) : écarts négligeables.

## 5. Lecture

- **Taille** : l'encodeur int4 est 36 à 45 % plus petit ; le décodeur ne change pas (embedding fp32). Total : 1 036 Mo → 802 Mo (b32)
  ou 726 Mo (b128), soit −23 % à −30 %, pas « la moitié ».
- **Précision** : sans effondrement, contrairement à l'int8 par tenseur de Lite-Whisper (#25) : le 4 bits **par blocs** tient.
  Coût mesuré sur 172 extraits : +1,0 point (enc + déc) ou +0,4 point (déc seul) ; 65 ou 34 phrases sur 172 diffèrent. Le modèle
  perd majuscules et ponctuation avec l'encodeur int4 (visible sur `frqc1`) : à vérifier si l'application s'en sert.
- **Vitesse x86-64** : encodeur int4 **2,1 fois plus lent** (le noyau `MatMulNBits` dequantifie en fp32 ; les noyaux int8 d'ORT sont
  bien plus rapides sur x86 : même constat que les « 19,9 à 20,8 s » de #25). `accuracy_level` 4 n'aide pas sur cette machine. Décodeur int4 :
  vitesse égale.
- **Mémoire** : −12 % à −18 % de pic.
- **arm64 : inconnu.** Les noyaux 4 bits d'ORT sont optimisés pour arm64 (NEON, dot-product ; `accuracy_level` 4 les utilise), mais
  aucun chiffre ici ne permet de dire s'ils battent l'int8. Aucune extrapolation.

## 6. À mesurer sur le téléphone (arm64)

L'application vérifie qu'un modèle installé correspond au catalogue (marqueur `.verified` avec les SHA-256 et tailles exacts) :
on ne peut donc pas simplement remplacer les fichiers de Whisper turbo. Procédure, sans rien commiter :

1. Produire les fichiers (section 2). Pour l'essai : `turbo-encoder.int4.onnx` (b32 ou b128) et `turbo-decoder.int8.onnx`
   (celui du catalogue) ; variante B : aussi `turbo-decoder.int4.onnx`. Calculer `sha256sum` et la taille de chaque fichier.
2. Dans une copie **locale non commitée** du dépôt, copier `whisperTurbo` dans `ModelCatalog.kt` sous un autre identifiant
   (ex. `whisper-turbo-int4-essai`, `visibility = ModelVisibility.EXPERIMENTAL`) avec les nouveaux noms de fichiers, SHA-256 et
   tailles ; et dans la branche `WHISPER` de `SherpaEngine.buildConfig`, lire les noms de fichiers de l'entrée (`spec.files`)
   au lieu de `turbo-encoder.int8.onnx` / `turbo-decoder.int8.onnx` écrits en dur. Construire l'APK debug (`./gradlew assembleDebug`).
3. Installer l'APK debug, puis copier les fichiers : `adb push` vers `/data/local/tmp/`, puis
   `adb shell run-as io.github.remtav.dictee sh -c 'mkdir -p files/models/<id> && cp /data/local/tmp/<fichier> files/models/<id>/'`
   (vérifier le dossier réel de `ModelStore` dans `DicteeApp.kt`), le fichier `turbo-tokens.txt` aussi, puis le marqueur :
   `.verified` contenant une ligne `<sha256>  <nom>` par fichier, dans l'ordre du catalogue.
4. Dans Réglages > Comparer les modèles : cocher tous les extraits de référence, comparer Whisper turbo (int8) et l'essai, même nombre
   de threads, **une exécution de chauffe jetée puis trois exécutions notées**, une minute entre elles (procédure de la section 10 de
   `ACCELERER_WHISPER.md`).
5. **Chiffres à relever** : s'il se charge sans erreur (sinon le message exact) ; temps de décodage et RTF par extrait ; temps de
   chargement ; mémoire en pointe ; WER par groupe (en, fr, frqc) et textes de `frqc1`, `frqc2` ; tailles des fichiers.
6. À essayer si la variante b32 se charge : b128, puis `--acc 4` (noyaux int8 d'ORT sur arm64 ; seulement là qu'il peut aider).

Critère suggéré : l'int4 ne vaut le coup que s'il est **au plus aussi lent** que l'int8 sur le téléphone (car le gain est la taille, −23 à −30 %)
avec un WER à moins d'1 point.

## 7. Décision proposée

**Garder l'int8 par défaut ; ne pas ajouter l'int4 au catalogue pour l'instant.** Raisons mesurées : sur l'hôte, l'encodeur int4 est 2,1 fois plus
lent pour 23 à 30 % de taille en moins et un WER +1 point ; le décodeur int4 ne rétrécit pas et n'accélère pas. Seul fait encourageant : la
précision tient et la mémoire baisse de 12 à 18 %. Le verdict final dépend de la mesure arm64 (section 6) : si l'int4 y est aussi rapide, le
proposer comme variante légère (publication Hugging Face à la demande de l'utilisateur, entrée de catalogue, noms de fichiers de la branche
`WHISPER` configurables).
