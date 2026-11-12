# Whisper large-v3-turbo « français québécois » → sherpa-onnx

Statut : outil, à lancer à la main (issue #15, demande de l'utilisateur ; voir `android/DECISIONS.md`).

Le modèle communautaire [`ele-sage/whisper-large-v3-turbo-fr-quebecois`](https://huggingface.co/ele-sage/whisper-large-v3-turbo-fr-quebecois)
(licence MIT, WER annoncé 6,65 %) n'existe qu'au format transformers, CTranslate2 et GGML : aucune conversion
sherpa-onnx publique. Ces scripts la produisent (même architecture que `csukuangfj/sherpa-onnx-whisper-turbo`,
donc mêmes noms de fichiers et même branche `ModelFamily.WHISPER` dans `SherpaEngine`).

## Étapes

`bash convertir.sh /chemin/de/travail` enchaîne :

1. `hf_vers_openai.py` : poids transformers (`model.safetensors`) → point de contrôle au format OpenAI (`.pt`), le
   seul que lit le script d'export de sherpa-onnx. Vérifié : `openai-whisper` transcrit correctement avec ce `.pt`.
2. `exporter.py` : lance `scripts/whisper/export-onnx.py` de sherpa-onnx (SHA-256 du script utilisé dans
   `convertir.sh`) sur ce point de contrôle, avec l'exporteur TorchScript (`dynamo=False`) : l'exporteur par défaut de
   torch >= 2.9 fige la longueur des jetons du décodeur et le décodage échoue. Le script plante à l'étape int8 sur
   une machine de moins de ~10 Go de mémoire.
3. `quantifier_int8.py` : quantification int8 des `MatMul` (équivalente à `quantize_dynamic` d'onnxruntime, mais
   en lisant les poids un à un : ~1 Go de mémoire au lieu de plus de 7 Go).

Dépendances de l'outil (hors du `package-lock.json` et de `requirements.txt` de kalmia.ca, car sans rapport avec
l'application) : torch (CPU), openai-whisper, onnx, onnxruntime, onnxscript, safetensors, numpy.

## Fichiers produits (conversion du 2026-11-12)

| Fichier | Octets | SHA-256 |
| --- | --- | --- |
| `turbo-encoder.int8.onnx` | 674 348 342 | `3f774a722b473fb85fca92e24591afffec5159c53564089fed4dc191fe6fabfc` |
| `turbo-decoder.int8.onnx` | 360 961 539 | `9775633c180fa256f096fd97ad8825cfff2d159090d8a6f98542d82ae01e567e` |
| `turbo-tokens.txt` | 816 730 | `b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126` (identique à celui de Whisper turbo) |

Ces sommes sont celles de `ModelCatalog.whisperQuebec`. La conversion n'est pas garantie reproductible au bit près :
si on la refait, les sommes sont à reporter dans le catalogue (le test de l'application échoue sinon au téléchargement).

## Publier pour que l'application le propose

Rien n'est publié par ce dépôt. Une fois (compte Hugging Face de l'équipe) :

1. Créer un dépôt de modèle, par exemple `<compte>/sherpa-onnx-whisper-turbo-fr-quebecois-int8`, y téléverser les
   trois fichiers ci-dessus tels quels, avec une fiche qui cite le modèle d'origine (`ele-sage/…`, MIT, l'auteur et le
   jeu d'entraînement) et la licence MIT de la conversion.
2. Dans `ModelCatalog.kt`, renseigner `WHISPER_QC` avec `https://huggingface.co/<compte>/<dépôt>/resolve/main`.
   Le modèle apparaît alors dans l'écran d'installation et les Réglages ; `ModelAccuracyHostTest.whisperQuebec` cesse
   d'être ignoré (téléchargement de ~1 Go dans la CI).
3. Vérifier : `./gradlew testDebugUnitTest` puis, hôte, `-PmodelsDir=… --tests '*ModelAccuracyHostTest*'`.
