# Turbo plus rapide sans réentraînement (issue #40)

Statut : outils de mesure, à lancer à la main. Résultats et conclusions : `android/docs/ACCELERER_WHISPER.md`,
section 7.3. Rien n'est publié et l'application n'est pas modifiée.

Environnement (Python 3.13 testé, hors des dépendances de l'app et du serveur) :

```bash
python3 -m venv venv && . venv/bin/activate
pip install sherpa-onnx==2.0.1 onnx onnxruntime==1.28.0 soundfile numpy kaldi-native-fbank huggingface_hub
```

`onnxruntime==1.28.0` sert à la réplique et aux scripts de graphe (le plus proche du 1.28.2 embarqué par sherpa-onnx
2.0.1) ; sherpa-onnx utilise sa propre copie. Modèles : `csukuangfj/sherpa-onnx-whisper-turbo` (`turbo-encoder.int8.onnx`,
`turbo-decoder.int8.onnx`, `turbo-tokens.txt`). Jeu de 172 extraits : `../jeu-quebecois/preparer.py --sortie qc172`.
Mémoire : ~1,1 Go par variante chargée (un banc de 7 variantes tient dans 10 Go).

| Script | Rôle |
| --- | --- |
| `replique.py` | Réplique Python du décodage Whisper de sherpa-onnx 2.0.1 (caractéristiques, marge, glouton), avec chronomètre séparé de l'encodeur et du décodeur, et entrée `n_frames` pour la piste 2.2 |
| `etape0.py` | Étape 0 : partage encodeur / décodeur, temps par étape du décodeur (sherpa et réplique en alternance) |
| `banc_rapide.py` | Banc sherpa-onnx 2.0.1 (protocole de `../whisper-mesure-python/banc_lite.py`), une marge par variante, sortie TSV par clip |
| `banc_replique.py` | Même banc avec la réplique (encodeurs à entrée `n_frames`) |
| `reprise.py` | Piste 2.1 : simulation « marge courte, reprise à 1 000 si répétition » à partir d'un TSV ; `COURTE/REPRISE` pour reprendre sur une autre variante |
| `cache_court.py` | Piste 1.2 : cache du décodeur de 448 à 256 positions (formes du décodeur et métadonnée `n_text_ctx` de l'encodeur), sans réexport |
| `attention_int8.py` | Piste 1.1 : `QKᵀ` et `AV` de l'encodeur en `MatMulInteger` (`--av dyn/fixe/ligne`, `--qk tenseur/tete`) |
| `fusion_attention.py` | Piste 1.3 : cœur de l'attention fusionné en `com.microsoft.MultiHeadAttention` |
| `fusion_silence.py` | Piste 2.2 : moyenne des positions de silence par groupes de G après le bloc N (entrée `n_frames`, hors sherpa) |
| `profil.py` | Profil onnxruntime de l'encodeur (temps par type de nœud, part de l'attention) |
| `comparer_encodeur.py` | Erreur relative des sorties d'un encodeur modifié face à la référence |
| `wer.py` | Normalisation et WER, identiques à `banc_lite.py` |

Exemples (depuis un dossier de travail qui contient `turbo/`, `qc172/` et l'environnement) :

```bash
T=chemin/vers/android/tools/whisper-turbo-rapide
R=chemin/vers/android/app/src/main/assets/reference
F=turbo/turbo-encoder.int8.onnx,turbo/turbo-decoder.int8.onnx,turbo/turbo-tokens.txt
python $T/etape0.py qc172 '*' 4 turbo/turbo-encoder.int8.onnx turbo/turbo-decoder.int8.onnx turbo/turbo-tokens.txt
python $T/cache_court.py turbo/turbo-encoder.int8.onnx turbo/turbo-decoder.int8.onnx \
    ctx256/turbo-encoder.int8.onnx ctx256/turbo-decoder.int8.onnx 256
cp turbo/turbo-tokens.txt ctx256/
C=ctx256/turbo-encoder.int8.onnx,ctx256/turbo-decoder.int8.onnx,ctx256/turbo-tokens.txt
python $T/banc_rapide.py qc172 4 1 '*' c172.tsv turbo=$F,1000 m300=$F,300 ctx256=$C,1000 c300=$C,300
python $T/reprise.py c172.tsv turbo m300 ctx256 c300/ctx256
```

Machine partagée : seules les comparaisons dans une même exécution valent (variantes mesurées en alternance clip par
clip). Toutes les mesures sont sur x86-64 : à refaire sur le téléphone (section 7.3 du rapport, « À vérifier sur le
téléphone »).
