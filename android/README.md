# Dictée locale — clavier vocal Android

Clavier Android (IME) dont la seule fonction est la dictée vocale, en français
et en anglais. La transcription tourne **entièrement sur le téléphone** avec
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) : ni l'audio ni le texte
ne quittent l'appareil, le réseau ne sert qu'à télécharger les modèles — sauf
si vous choisissez vous-même le « Serveur distant » (voir plus bas).
Spécification : issue #10. Choix de développement : [`DECISIONS.md`](DECISIONS.md).

Ce n'est pas un clavier de frappe : on garde son clavier habituel et on
bascule vers celui-ci pour dicter.

## Aperçu

Captures prises automatiquement par la CI sur un émulateur Pixel 6
(`ScreenshotTest`) ; le texte vient d'un moteur de test, pas d'un modèle.

| Prêt | À l'écoute | Texte inséré | Thème sombre |
|---|---|---|---|
| ![Clavier prêt](docs/screenshots/keyboard-idle-light.png) | ![Clavier à l'écoute](docs/screenshots/keyboard-listening-light.png) | ![Texte inséré](docs/screenshots/keyboard-text-light.png) | ![Clavier sombre](docs/screenshots/keyboard-listening-dark.png) |

| Configuration | Réglages | Comparaison des modèles |
|---|---|---|
| ![Écran de configuration](docs/screenshots/setup-dark.png) | ![Réglages](docs/screenshots/settings-dark.png) | ![Comparaison](docs/screenshots/compare-light.png) |

## Installation

1. Télécharger l'APK de la dernière release `android-v…` du dépôt sur le
   téléphone et l'ouvrir (autoriser l'installation depuis cette source).
   Les releases sont signées avec la clé privée du projet : une nouvelle
   version s'installe par-dessus l'ancienne, et un APK signé par quelqu'un
   d'autre est refusé comme mise à jour.
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

### Serveur distant (optionnel)

Si les modèles du téléphone ne suffisent pas, la dictée peut se faire sur un
serveur que vous contrôlez : dans les Réglages, section « Serveur distant »,
saisir l'adresse (par exemple `http://192.168.1.10:8081` pour le serveur
speech-to-text de ce dépôt, ou l'adresse d'un serveur compatible avec l'API
OpenAI `/v1/audio/transcriptions`), tester la connexion, puis choisir
« Serveur distant » comme modèle (écran principal ou bouton du clavier).
Avec ce choix, l'audio de chaque segment est envoyé au serveur. Si le serveur
ne répond pas, un modèle local prend le relais (réglable). En dehors du réseau
local, utilisez HTTPS ou un VPN (WireGuard, Tailscale).

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

### Clé de signature des releases

Les releases sont signées par une clé privée que seule la CI utilise, fournie
par trois secrets GitHub. À faire une fois, sur votre machine (JDK requis) :

```bash
# 1. Générer la clé (le mot de passe demandé est celui de DICTEE_KEYSTORE_PASSWORD)
keytool -genkeypair -v -keystore dictee-release.p12 -storetype PKCS12 \
    -alias dictee -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Dictee locale"

# 2. Créer les secrets (ou Settings → Secrets and variables → Actions → New repository secret)
base64 -w0 dictee-release.p12 | gh secret set DICTEE_KEYSTORE_BASE64   # macOS : base64 -i dictee-release.p12
gh secret set DICTEE_KEYSTORE_PASSWORD
gh secret set DICTEE_KEY_ALIAS --body dictee
```

Sauvegardez `dictee-release.p12` et son mot de passe hors de GitHub (gestionnaire
de mots de passe) : sans eux, plus aucune mise à jour ne peut s'installer par-dessus
l'application. Ne versionnez jamais ce fichier. Sans les secrets, la CI d'une pull
request construit un APK release non signé, et un push sur `main` ne publie aucune
release (avertissement dans le résumé du workflow).

La CI (`.github/workflows/android.yml`) exécute ces trois suites et publie
l'APK en release à chaque push sur `main`.
