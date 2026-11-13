# Whisper large-v3-turbo « français québécois » → sherpa-onnx

Statut : outil, à lancer à la main (issue #15, demande de l'utilisateur ; voir `android/DECISIONS.md`). La conversion est
publiée : `https://huggingface.co/remiavelo/sherpa-onnx-whisper-large-v3-turbo-fr-quebecois-int8` (voir « Publication »).

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

Dépendances de l'outil (installées dans un environnement virtuel par `convertir.sh`, hors des dépendances de
l'application et du serveur) : torch (CPU), openai-whisper, onnx, onnxruntime, onnxscript, safetensors, numpy.

## Fichiers produits (conversion du 2026-11-12)

| Fichier | Octets | SHA-256 |
| --- | --- | --- |
| `turbo-encoder.int8.onnx` | 674 348 342 | `3f774a722b473fb85fca92e24591afffec5159c53564089fed4dc191fe6fabfc` |
| `turbo-decoder.int8.onnx` | 360 961 539 | `9775633c180fa256f096fd97ad8825cfff2d159090d8a6f98542d82ae01e567e` |
| `turbo-tokens.txt` | 816 730 | `b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126` (identique à celui de Whisper turbo) |

Ces sommes sont celles de `ModelCatalog.whisperQuebec`. La conversion n'est pas garantie reproductible au bit près :
si on la refait, les sommes sont à reporter dans le catalogue (le test de l'application échoue sinon au téléchargement).

## Publication

Publiée le 2026-11-12, à la demande de l'utilisateur et sous son compte Hugging Face (dépôt public) :
`https://huggingface.co/remiavelo/sherpa-onnx-whisper-large-v3-turbo-fr-quebecois-int8`. Les trois fichiers ci-dessus y
sont tels quels ; `WHISPER_QC` de `ModelCatalog.kt` pointe sur `…/resolve/main`, donc l'application propose le modèle
(écran d'installation, Réglages, comparaison) et `ModelAccuracyHostTest.whisperQuebec` le télécharge (~1 Go) en CI.
Si la conversion est refaite et republiée, reporter les nouvelles sommes dans le catalogue.

Vérifier : `./gradlew testDebugUnitTest` puis, hôte, `-PmodelsDir=… --tests '*ModelAccuracyHostTest*'`.

## Lite-Whisper large-v3-turbo-acc (2026-11-13, à l'essai)

Demande de l'utilisateur : un Whisper « juste un peu plus petit » que large-v3-turbo.
[`efficient-speech/lite-whisper-large-v3-turbo-acc`](https://huggingface.co/efficient-speech/lite-whisper-large-v3-turbo-acc)
(Apache-2.0, article arXiv 2502.20583) est large-v3-turbo dont les couches linéaires de l'**encodeur** sont factorisées
en deux matrices de rang réduit : encodeur de 635 M à 421 M paramètres, décodeur inchangé (172 M). WER annoncé en
anglais (ESB) : 10,2 % contre 10,1 % pour turbo. **Aucune mesure en français** à ce jour : la compression a été calibrée
et évaluée en anglais ; l'utilisateur fait son propre banc d'essai sur des extraits québécois (voir aussi l'issue #24).

`bash convertir_lite.sh /chemin/de/travail` : comme `convertir.sh`, avec `exporter_lite.py`, qui remplace dans le modèle
`openai-whisper` les couches factorisées par un module équivalent (mêmes calculs que `modeling_lite_whisper.py`, lu mais
non exécuté) avant de lancer le script d'export de sherpa-onnx. Les noms de fichiers produits sont ceux de turbo.

**Attention : en l'état, `convertir_lite.sh` produit un encodeur int8 cassé** (sortie « ... »). L'export fp32 et le
décodeur int8 sont corrects ; c'est la quantification int8 de l'encodeur (multiplications qui partent de la
représentation réduite) qui casse le modèle. La seule variante correcte trouvée (ces multiplications laissées en fp32)
est plus grosse et plus lente que turbo. Essais, mesures et pistes : issue #25.

Cette variante est publiée à titre expérimental (2026-11-13, à la demande de l'utilisateur) :
`https://huggingface.co/remiavelo/sherpa-onnx-lite-whisper-large-v3-turbo-acc-int8` (encodeur 799 004 644 octets,
`ccefad7d…`). Elle n'est pas encore reproduite par `convertir_lite.sh` (voir #25) ; intégration dans l'app : #22.
