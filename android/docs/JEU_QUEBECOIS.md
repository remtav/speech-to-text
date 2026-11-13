# Jeu d'extraits québécois (banc d'essai élargi)

Les clips de référence de l'app (`app/src/main/assets/reference/`) ne contiennent que 6 extraits québécois (58 mots) :
une seule erreur fait varier le WER de près de 2 points. Ce jeu en compte 172 (issue #24).

## Source

- **Common Voice 22.0, français, partie `test`**, licence **CC0** (même source que `frqc1` à `frqc6`),
  via `huggingface.co/datasets/fsicoli/common_voice_22_0`.
- Filtre sur la colonne `accents` : contient « Canada », « Québec » ou « québ », et **pas** « France » → 208 clips, 63 locuteurs.
- 3 clips au plus par locuteur (`client_id`), dans l'ordre du fichier → **172 clips, 17,2 min, 1 649 mots** (63 locuteurs).
- WAV 16 kHz mono ; `references.tsv` au format de l'app (`fichier`, `langue` = `fr`, `transcription`, `source`).

Le jeu **n'est ni versionné ni inclus dans l'APK** : un script le produit dans un dossier donné.

## Procédure

```bash
# 1. Produire le jeu (ffmpeg requis, Python standard ; lit l'archive de 687 Mo en flux, ~15 s ici). Idempotent.
python android/tools/jeu-quebecois/preparer.py --sortie $HOME/jeu-quebecois

# 2. Mesurer (modèles téléchargés par l'app dans -PmodelsDir ; JDK 17+, SDK Android)
cd android
./gradlew testDebugUnitTest -PmodelsDir=$HOME/dictee-models -PquebecSetDir=$HOME/jeu-quebecois \
    --tests '*QuebecSetHostTest*' --console=plain
# facultatif : -PquebecSetModels=whisper-large-v3-turbo-int8,parakeet-tdt-0.6b-v3-int8 (ids du catalogue)
```

Sortie : une ligne `QCSET|modèle|WER=…|time=…` par modèle du catalogue téléchargeable (serveur distant exclu),
et `QCSET-CLIP|…` par clip. Sans `-PquebecSetDir`, le test est sauté : la CI est inchangée.
Test du script : `cd android/tools/jeu-quebecois && python -m pytest` (échantillon simulé, sans réseau).

## Normalisation du WER (identique pour tous les modèles)

Minuscules, NFC, apostrophes typographiques → `'`, `œ` → `oe`, traits d'union et ponctuation → espaces.
**Les chiffres ne sont pas convertis en lettres** : un modèle qui écrit « 42 » là où la référence dit
« quarante-deux » (ou l'inverse) compte une erreur. Les 4 modèles sont mesurés sur les mêmes mots (1 673 après découpage des traits d'union).

## Résultats (exécution du 2026-11-13)

Hôte Linux x86-64 (sherpa-onnx 2.0.1, int8, 4 threads, langue forcée `fr`, un décodage d'échauffement non chronométré).
Le temps est le décodage total des 172 clips (1 031 s d'audio), sur une machine partagée : il indique des ordres de
grandeur, pas la latence d'un téléphone.

| Modèle | WER | Erreurs / mots | Temps de décodage |
|---|---|---|---|
| Whisper large-v3-turbo (int8) | **14,4 %** | 241 / 1 673 | 252,8 s |
| Whisper large-v3-turbo, français québécois (ele-sage) | **10,5 %** | 175 / 1 673 | 257,2 s |
| Parakeet TDT 0.6B v3 (int8) | 14,8 % | 247 / 1 673 | 71,3 s |
| Qwen3-ASR 0.6B (int8) | 20,1 % | 336 / 1 673 | 210,2 s |

**À signaler : biais possible en faveur de Whisper QC.** Le modèle d'ele-sage a été entraîné en partie sur Common Voice ;
rien ne garantit que ces clips de la partie `test` lui soient inconnus. Son avance (≈ 4 points sur Whisper turbo) est
donc à lire avec prudence et ne se compare pas directement aux trois autres modèles.

Autres limites : transcriptions de Common Voice parfois imparfaites (bruit sur la référence, pas sur le modèle) ;
chiffres non convertis (voir plus haut) ; une seule exécution, sans intervalle de confiance. Avec 1 673 mots,
une erreur pèse 0,06 point (contre ≈ 2 points sur le jeu de 6 extraits), de sorte que des écarts de 1 point commencent à être lisibles.
