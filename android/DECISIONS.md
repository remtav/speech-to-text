# Décisions de développement — clavier vocal Android (issue #10)

Ce fichier consigne les choix faits pendant les lots 1, 2 et 3, réalisés d'un
seul tenant sans intervention humaine. Les décisions arrêtées dans l'issue ne
sont pas répétées ; chaque entrée ci-dessous donne le choix et sa raison.

## Architecture

| Paquet | Rôle |
|---|---|
| `stt/` | Interface `SttEngine`, catalogue des modèles, `SherpaEngine`, `ModelStore` (téléchargement + SHA-256), `EngineManager` (un seul modèle chargé), `Benchmark` |
| `audio/` | `AudioSource` (micro ou fichier), lecteur WAV + rééchantillonnage, VAD Silero |
| `dictation/` | `DictationController` (capture → VAD → transcription → insertion), `TextPipeline`, `TextSink` |
| `text/` | Règles d'assemblage entre segments, commandes vocales, vocabulaire, filtres, calcul du WER |
| `ime/` | `DicteeImeService` (le clavier), vue, jauge de niveau |
| `ui/` | Configuration, réglages, comparaison des modèles, service de téléchargement |
| `cleanup/` | Nettoyage optionnel par petit LLM local (lot 3) |

- **Ajouter un modèle = une entrée de `ModelCatalog`.** Pour une architecture
  déjà prise en charge (transducteur NeMo, Qwen3-ASR, Whisper), rien d'autre.
  Pour une nouvelle architecture de sherpa-onnx : une branche de plus dans
  `SherpaEngine.buildConfig`. Un moteur autre que sherpa-onnx implémente
  `SttEngine` + `SttEngineFactory`.
- **Le contrôleur de dictée n'a aucune dépendance Android.** Il ne voit que des
  interfaces (source audio, VAD, fonction de transcription, `TextSink`), ce qui
  permet de le tester sur la JVM avec les vrais modèles (voir « Tests »).
- Tout se fait en Views + Material 3, sans Compose : une IME en Compose demande
  de recâbler les `LifecycleOwner`, sans bénéfice pour quatre écrans simples.

## Bibliothèques et modèles

- **sherpa-onnx 2.0.1** (dernière version au moment du lot). Elle n'est publiée
  sur aucun dépôt Maven (JitPack n'a pas de build de cette version) : Gradle
  télécharge l'AAR depuis la release GitHub et vérifie son SHA-256 avant de
  l'utiliser (`app/build.gradle.kts`). Même chose pour le VAD Silero.
- **Fichiers des modèles pris un par un sur Hugging Face** plutôt que dans les
  archives `.tar.bz2` des releases sherpa-onnx : pas de décompression bzip2 de
  1 Go sur le téléphone, reprise possible fichier par fichier (requête HTTP
  `Range`), et chaque fichier a une somme SHA-256 connue. Les dépôts utilisés
  sont les exports de l'auteur de sherpa-onnx (`csukuangfj`, et `csukuangfj2`
  pour Qwen3-ASR). Un modèle n'est « installé » qu'après vérification de tous
  ses fichiers ; le marqueur liste les sommes, donc une mise à jour du
  catalogue invalide une installation périmée.
- Variantes retenues (int8 partout, comme décidé) :
  - Parakeet TDT 0.6b v3 : `encoder/decoder/joiner.int8.onnx` — 671 Mo.
  - Qwen3-ASR 0.6B (export du 2026-03-25) : `conv_frontend.onnx` (fp32, il n'en
    existe pas d'int8), `encoder/decoder.int8.onnx`, tokenizer — 987 Mo.
  - Whisper large-v3-turbo : `turbo-encoder/decoder.int8.onnx` — 1 037 Mo.
- **Langue détectée automatiquement** : Whisper reçoit une langue vide (il la
  détecte à chaque segment) ; Parakeet et Qwen3-ASR détectent d'eux-mêmes.
- **Le VAD Silero (0,6 Mo) est dans l'APK** : ce n'est pas un modèle de
  transcription, il est indispensable avant tout téléchargement, et sa taille
  est négligeable. C'est le seul modèle embarqué.
- **Threads** : `min(cœurs, 4)` par défaut, réglable. Sur le Snapdragon 8 Gen 2
  cela correspond aux cœurs rapides ; à mesurer au point de contrôle.

## Découpage et assemblage du texte

- VAD Silero : une pause de **0,5 s** termine un segment (0,25 s, la valeur par
  défaut de sherpa-onnx, coupe au milieu des phrases), réglable de 0,3 à 1,5 s.
  Un segment est coupé de force à **20 s** : Whisper accepte au plus 30 s et la
  fiche de Parakeet v3 recommande de rester sous 15–20 s.
- Entre deux segments : espace inséré sauf après un retour à la ligne ou une
  parenthèse ouvrante, ou devant une ponctuation ; majuscule en début de champ
  ou après `. ! ? …` ; en milieu de phrase, la majuscule que le modèle met au
  premier mot est retirée **seulement** si ce mot est un mot grammatical courant
  (articles, pronoms, conjonctions, en français et en anglais) ou une élision
  (« C'était »). Les noms propres, « I » et les sigles gardent leur majuscule.
- Whisper produit parfois des génériques de sous-titres sur du silence
  (« Sous-titres réalisés par la communauté d'Amara.org ») : ces segments sont
  supprimés.

## Cycle de vie du modèle

- Chargé dès que le clavier s'affiche (préchargement : le premier appui est
  rapide), un seul à la fois — charger un modèle libère d'abord le précédent.
- Gardé tant que le clavier est visible. Une fois le clavier masqué, il est
  libéré sous pression mémoire (`onTrimMemory`) ou après un délai d'inactivité
  (5 min par défaut, réglable de 0 à 60) : rouvrir le clavier quelques secondes
  après l'avoir fermé ne coûte pas un rechargement de 2 à 4 s.
- Un verrou lecture/écriture empêche de libérer un modèle pendant un décodage.
- On peut commencer à parler pendant le chargement : l'audio est découpé par le
  VAD et mis en file, puis transcrit dès que le modèle est prêt.

## Lot 2

- **Texte provisoire** : les modèles retenus ne sont pas en streaming. Pendant
  la parole, le segment en cours est re-décodé au plus toutes les 700 ms, et
  seulement si aucun segment final n'attend ; le résultat s'affiche en texte de
  composition (souligné) puis est remplacé par le texte final à la pause. C'est
  coûteux en calcul : désactivable dans les réglages.
- **Commandes vocales** (FR/EN) : « nouvelle ligne » / « à la ligne » /
  « new line », « nouveau paragraphe » / « new paragraph », « efface la
  dernière phrase » / « delete last sentence » / « scratch that », « efface le
  dernier mot » / « delete last word ». Pour éviter les faux positifs :
  - retour à la ligne et paragraphe peuvent être dans une phrase, mais
    seulement entre ponctuations ou en bord de segment (« Bonjour, nouvelle
    ligne, merci. » oui ; « une nouvelle ligne de métro » non) ;
  - les commandes d'effacement doivent constituer le segment entier.
- **Vocabulaire personnalisé** : un mot par ligne à favoriser, ou
  `mal entendu -> correct`. Les corrections s'appliquent après transcription
  (insensible à la casse, aux limites de mots). Les mots favoris :
  - sont passés à Qwen3-ASR comme *hotwords* (seul des trois modèles à les
    accepter dans sherpa-onnx : les hotwords des transducteurs exigent un
    vocabulaire BPE que l'export de Parakeet ne fournit pas, et Whisper n'a pas
    de prompt dans sherpa-onnx) ;
  - corrigent les quasi-homonymes pour tous les modèles : un mot de 5 lettres
    ou plus, même initiale, à distance d'édition ≤ 1 (≤ 2 dès 8 lettres) est
    remplacé, et la graphie (accents, casse) est rétablie.
- **Robustesse** :
  - perte du focus audio (appel, assistant vocal, autre application) → arrêt,
    ce qui a été dit est quand même transcrit ;
  - mode audio « sonnerie / appel / communication » (API 31+) → arrêt ; le
    micro coupé par le système (`isClientSilenced`) → arrêt avec message ;
  - rotation : la dictée vit dans le service, pas dans la vue ; masquer le
    clavier n'arrête la dictée qu'après 4 s, car une rotation le masque et le
    réaffiche sur le même champ (si l'application ne réaffiche pas le clavier dans ce délai —
    constaté sur l'émulateur — la dictée s'arrête proprement, ce qui a été dit est
    inséré ; le test de rotation retouche le champ comme le ferait l'utilisateur) ;
  - changement de champ en cours de dictée → arrêt immédiat et abandon de ce
    qui n'est pas encore inséré : un numéro de session garantit qu'aucun texte
    n'est jamais tapé dans un autre champ que celui de départ ;
  - longues dictées : mémoire bornée (tampon circulaire de 25 s pour le texte
    provisoire, segments de 20 s au plus), aucune accumulation par session.
- **Retour** : jauge de niveau sonore, état (« À l'écoute », « Parole
  détectée », « Transcription… », erreurs en couleur d'erreur), vibration au
  démarrage/arrêt et sur erreur (désactivable), thème clair/sombre/système.
- Le clavier déclare un sous-type « voix » : la touche micro d'un autre clavier
  (Samsung, Gboard, SwiftKey) peut basculer directement vers lui.

## Lot 3

- **Accélération matérielle** : choix CPU (défaut), XNNPACK, NNAPI. Ce sont les
  fournisseurs d'exécution compris dans l'AAR standard de sherpa-onnx. Repli
  automatique à deux niveaux : sherpa-onnx revient seul au CPU si le
  fournisseur est absent, et `EngineManager` reconstruit le moteur sur le CPU
  si la création échoue (message affiché dans le clavier).
  - **QNN (NPU Qualcomm) n'est pas intégré** : il faut un build spécial de
    sherpa-onnx lié au SDK QNN de Qualcomm (bibliothèques propriétaires non
    redistribuables par ce dépôt) et des binaires de contexte compilés par
    modèle et par SoC, qui n'existent pas pour ces trois modèles. NNAPI est la
    voie standard vers le NPU/GPU du Snapdragon ; NNAPI est déprécié depuis
    Android 15 mais toujours présent sur le S23 Ultra.
  - Il n'existe pas de fournisseur GPU dans onnxruntime pour Android en dehors
    de NNAPI.
- **Voxtral** : constat documenté dans [`docs/VOXTRAL.md`](docs/VOXTRAL.md),
  pas d'intégration.
- **Nettoyage du texte** (dans l'esprit de #6), désactivé par défaut :
  - « Règles simples » : hésitations retirées (euh, um…), bégaiements de mots
    grammaticaux (« le le »), sans modèle ;
  - « Petit LLM local » : MediaPipe LLM Inference (`tasks-genai` 0.10.35) avec
    Qwen2.5 1,5B Instruct (q8, 1,6 Go, par défaut) ou 0,5B (q8, 0,5 Go),
    exports LiteRT de `litert-community` (Apache-2.0, non restreints),
    téléchargés à la demande avec vérification SHA-256. Température 0. Le
    texte passe d'abord par les règles ; la sortie du LLM ne le remplace que si
    le garde-fou (`SafetyCheck`, adapté de `app/polish.py` : longueur, mots
    nouveaux, contenu perdu, négations, nombres, formule d'assistant) l'accepte,
    en moins de 4 s. Sinon, ou si la bibliothèque native manque (elle n'existe
    qu'en arm64 et armv7 : pas sur l'émulateur x86_64), le texte nettoyé par
    règles est gardé. Rien ne peut planter.

## Tests et écarts avec l'issue

- **Les modèles ne tournent pas sur l'émulateur de la CI** (1 Go par modèle à
  pousser à chaque exécution, 4 Go de RAM, x86_64 émulé). Comme prévu par
  l'issue, ils sont testés **hors émulateur, sur la machine de CI, avec la même
  bibliothèque** : la release sherpa-onnx 2.0.1 publie, à côté de l'AAR, la
  même bibliothèque JNI compilée pour Linux x86-64. Les tests JVM du module
  `app` chargent les classes Kotlin de l'AAR avec cette bibliothèque native, et
  passent par le code de l'application (`ModelStore` pour télécharger et
  vérifier, `SherpaEngine`, `SileroVad`, `Benchmark`, `DictationController`).
- **Sur l'émulateur**, les tests instrumentés pilotent la vraie IME : activée
  par `ime enable/set`, un fichier WAV remplace le micro, l'audio passe par la
  vraie boucle de capture, le vrai VAD Silero (JNI sherpa-onnx sur l'émulateur)
  et la vraie `InputConnection` ; seul le modèle est remplacé par un moteur
  scripté (`ScriptedEngine`, présent uniquement dans les builds debug).
- **Appel entrant** : un test sur émulateur ne peut pas recevoir un vrai appel.
  Le test simule le changement de mode audio que signale `AudioManager`
  (sonnerie), et la perte du focus audio est, elle, réellement provoquée.
- **Fichiers de référence** (dans l'APK, pour l'écran de comparaison) : deux
  extraits LibriSpeech en anglais (domaine public, via le dépôt Whisper turbo
  de sherpa-onnx) et un extrait français de la démo Qwen3-ASR. Faute de jeu de
  test français libre plus fourni et accessible, il n'y a qu'un extrait FR ;
  l'écran de comparaison permet d'ajouter son propre enregistrement.
- **Seuils de WER** fixés après mesure sur la machine de développement
  (Linux x86-64, 4 threads), avec marge :

  | Modèle | WER EN mesuré | WER FR mesuré | RTF hôte | Seuil EN / FR |
  |---|---|---|---|---|
  | Parakeet TDT 0.6b v3 | 3,0 % | 0,0 % | 0,08 | 12 % / 15 % |
  | Qwen3-ASR 0.6B | 4,5 % | 0,0 % | 0,30 | 12 % / 15 % |
  | Whisper large-v3-turbo | 4,5 % | 6,3 % | 0,30 | 12 % / 15 % |

  Une partie des « erreurs » anglaises sont des graphies américaines
  (« dishonored », « forever ») face à la référence britannique. Le test de
  bout en bout (flux audio → VAD → Parakeet → texte assemblé) exige ≤ 15 %.

## Construction et distribution

- APK release : arm64-v8a uniquement (le S23 Ultra), ce qui évite d'embarquer
  trois fois onnxruntime. APK debug : arm64-v8a + x86_64 pour l'émulateur.
- Signature par la clé de debug versionnée (`keystore/debug.keystore`, mot de
  passe `android`) pour les deux types de build.
- `versionCode` = numéro d'exécution du workflow, `versionName` = `0.1.<n>` :
  chaque APK publié s'installe par-dessus le précédent.
- Release GitHub `android-v0.1.<n>` créée à chaque push sur `main`, une fois
  les trois jobs de tests passés.
- Permissions : micro ; Internet (téléchargement des modèles uniquement) ;
  service au premier plan + notifications pour que les téléchargements de
  1 Go survivent à la mise en arrière-plan. Aucune télémétrie, `allowBackup`
  désactivé.

## Pour le point de contrôle humain

Sur le S23 Ultra, l'écran « Comparer les modèles » donne pour chaque modèle le
temps de chargement à froid, le RTF, la mémoire en pointe et le WER sur les
références, plus le texte de son propre enregistrement ; « Partager » exporte
le tout en texte. Points à trancher : modèle par défaut, nombre de threads,
intérêt de NNAPI, valeur de la pause de fin de segment, intérêt du texte
provisoire selon la consommation constatée.
