# Dictée locale — clavier vocal Android

Clavier Android (IME) dont la seule fonction est la dictée vocale, en français
et en anglais. La transcription tourne **entièrement sur le téléphone** avec
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) : ni l'audio ni le texte
ne quittent l'appareil, le réseau ne sert qu'à télécharger les modèles.
Spécification : issue #10. Choix de développement : [`DECISIONS.md`](DECISIONS.md).

Ce n'est pas un clavier de frappe : on garde son clavier habituel et on
bascule vers celui-ci pour dicter.

## Installation

1. Télécharger l'APK de la dernière release `android-v…` du dépôt sur le
   téléphone et l'ouvrir (autoriser l'installation depuis cette source).
   Les APK sont signés avec la même clé : une nouvelle version s'installe
   par-dessus l'ancienne.
2. Ouvrir **Dictée locale** :
   - « Activer » le clavier « Dictée locale (voix) » dans les réglages Android ;
   - « Autoriser le micro » (un clavier ne peut pas demander la permission lui-même) ;
   - télécharger au moins un modèle (Wi-Fi conseillé, 0,7 à 1 Go chacun).
3. Dans n'importe quelle application, basculer vers le clavier Dictée locale
   (icône clavier de la barre de navigation, ou touche micro de votre clavier
   habituel) et appuyer sur le micro. Appuyer de nouveau pour arrêter.

## Le clavier

- Gros bouton micro : appui pour démarrer, appui pour arrêter. Le texte
  s'insère à chaque pause.
- Bouton du modèle (en haut à gauche) : changer de modèle sans quitter le champ.
- Touches : clavier précédent, `,` `.` espace `?` `!`, effacer (maintenir pour
  répéter), entrée.
- Commandes vocales : « nouvelle ligne », « nouveau paragraphe », « efface la
  dernière phrase », « efface le dernier mot » (et en anglais).
- Désactivé dans les champs de mot de passe.

## Modèles

| Modèle | Éditeur | Taille (int8) |
|---|---|---|
| Parakeet TDT 0.6b v3 (défaut) | NVIDIA | 671 Mo |
| Qwen3-ASR 0.6B | Alibaba (Qwen) | 987 Mo |
| Whisper large-v3-turbo | OpenAI | 1 037 Mo |

Chaque fichier est vérifié par SHA-256 ; les modèles se suppriment depuis
l'écran principal. **Comparer les modèles** transcrit les extraits de
référence (ou votre enregistrement) avec chaque modèle installé et affiche
texte, temps de chargement, facteur temps réel, mémoire en pointe et WER ;
« Partager » exporte les résultats.

Réglages : texte provisoire, commandes vocales, pause de fin de segment,
vocabulaire personnalisé, thème, vibrations, durée de maintien du modèle en
mémoire, threads, accélération matérielle (CPU par défaut, XNNPACK, NNAPI),
nettoyage du texte (désactivé, règles, ou petit LLM local avec garde-fou).

## Développement

Prérequis : JDK 17+, SDK Android (plateforme 35). Les dépendances natives
(AAR sherpa-onnx, VAD Silero) sont téléchargées et vérifiées par Gradle.

```bash
cd android
./gradlew assembleDebug              # APK debug (arm64 + x86_64)
./gradlew assembleRelease            # APK release (arm64)
./gradlew testDebugUnitTest          # tests JVM (dont le VAD sherpa-onnx réel)
./gradlew testDebugUnitTest -PmodelsDir=$HOME/dictee-models \
    --tests '*ModelAccuracyHostTest*' --tests '*EndToEndHostTest*'
                                     # télécharge les 3 modèles et mesure le WER FR/EN
./gradlew connectedDebugAndroidTest  # tests du clavier sur émulateur/appareil
```

La CI (`.github/workflows/android.yml`) exécute ces trois suites et publie
l'APK en release à chaque push sur `main`.
