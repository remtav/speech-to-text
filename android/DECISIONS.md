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

## Serveur distant (ajouté après les trois lots, à la demande de l'utilisateur)

L'issue avait arrêté « jamais d'audio ni de texte envoyé ». L'utilisateur a
ensuite demandé de pouvoir transcrire sur son propre serveur quand les modèles
du téléphone ne suffisent pas. Choix faits :

- **Option explicite, désactivée par défaut.** « Serveur distant » est une
  entrée de plus du catalogue (`ModelCatalog.remote`). Elle n'est sélectionnable
  qu'une fois une adresse saisie, et le texte des réglages dit clairement que
  l'audio de chaque segment part alors vers ce serveur. Les modèles du téléphone
  n'envoient toujours rien.
- **Deux API** (`RemoteEngine`) :
  - le serveur de ce dépôt, `POST /transcribe/streaming` (chemin rapide : pas
    d'alignement ni de diarisation), champ `language` facultatif ;
  - l'API de transcription d'OpenAI, `POST /v1/audio/transcriptions`, parlée par
    la plupart des serveurs auto-hébergés (faster-whisper-server/speaches,
    serveur whisper.cpp, LocalAI, vLLM…). Les mots favoris du vocabulaire
    partent dans `prompt`.
- *(Remplacé par l'issue #13, voir la dernière section : la dictée entière part
  désormais en une requête, polie par le serveur.)*
- Chaque segment du VAD est envoyé en WAV 16 kHz 16 bits mono, ce qui garde les
  commandes vocales, le vocabulaire et l'assemblage du texte identiques. Le
  texte provisoire est désactivé avec le serveur : une requête toutes les 0,7 s
  n'aurait pas de sens.
- Jeton facultatif envoyé en `Authorization: Bearer` (proxy inverse, API hébergée).
- **Repli** : si le serveur ne répond pas, le modèle local choisi prend le
  relais (Parakeet par défaut), chargé seulement à la première panne ; le
  serveur est réessayé après 60 s. On peut aussi choisir d'afficher une erreur.
- **HTTP en clair autorisé** (`network_security.xml`) : un serveur domestique est
  typiquement `http://192.168.x.x:8081`. Les réglages préviennent quand une
  adresse HTTP n'est pas sur un réseau privé (10/8, 172.16/12, 192.168/16,
  100.64/10 de Tailscale, `.local`, `.lan`, nom sans point) et recommandent
  HTTPS ou un VPN. Les téléchargements de modèles restent en HTTPS et vérifiés.
- Bouton « Tester la connexion » : `GET /health` (serveur du dépôt, qui affiche
  le modèle et le périphérique) ou `GET /v1/models`.
- L'écran de comparaison inclut le serveur s'il est configuré : on compare
  directement qualité et latence avec les modèles du téléphone.
- Tests : `RemoteEngineTest` (serveur HTTP local : format multipart, WAV, jeton,
  `prompt`, erreurs HTTP, serveur injoignable, repli et nouvel essai) et un test
  instrumenté qui dicte dans l'IME vers un serveur sur l'émulateur. Le serveur
  Python n'est pas lancé en CI (torch + WhisperX) : le contrat (`file`
  multipart, réponse JSON avec `text`) est celui de `app/main.py`.

## Revue indépendante (Claude Opus) et corrections

Une revue indépendante du code a relevé les points suivants, corrigés :

- **Identification du champ** : `packageName#fieldId` ne distingue pas deux
  champs d'une page web (même id) ni deux champs sans id. Désormais, tout
  nouveau champ pendant une dictée l'annule, sauf le seul cas « clavier masqué
  il y a moins de 4 s et retour sur le même champ avec un id » (rotation).
  Test instrumenté avec deux champs sans id.
- **Texte perdu pendant une rotation** : un segment terminé pendant l'entrée
  fictive est maintenant gardé et inséré quand le champ revient.
- **Changement de modèle pendant une dictée** : refusé avec un message (les
  segments en attente rechargeaient l'ancien modèle).
- **Pression mémoire pendant la fin d'une dictée** : le modèle n'est plus libéré
  tant que des segments restent à transcrire.
- **LLM de nettoyage** : une génération qui dépasse le délai n'est plus empilée
  (les segments suivants sautent le LLM tant qu'elle tourne) et le modèle n'est
  libéré qu'une fois la génération finie (évite un plantage natif).
- Plus petits : texte provisoire effacé en fin de session même sur erreur ;
  état « chargement » qui pouvait rester affiché ; VAD libéré à la fin de chaque
  session ; `$` dans une correction du vocabulaire ; une modification du
  vocabulaire ne recharge plus que Qwen3-ASR ; reprise HTTP mal alignée
  recommencée ; redirection HTTPS → HTTP refusée ; dictée non démarrée si le
  système refuse le focus audio (appel) ; arrêt du service de téléchargement
  qui pouvait planter sur un téléchargement immédiat ; l'écran de comparaison
  empêche le clavier de charger un second modèle en parallèle ; la CI n'annule
  plus une exécution en cours sur `main`.
- **Clé de signature : décision de l'issue révisée par l'humain.** La revue
  signalait que la clé de debug versionnée (dépôt public) permet à n'importe qui
  de produire un APK qui s'installe en mise à jour par-dessus celui de
  l'utilisateur, alors que l'application est un clavier avec accès au micro.
  Après explication, l'humain a choisi une clé privée en secret GitHub (voir
  « Construction et distribution »). Changé avant la première release : aucune
  désinstallation nécessaire.

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
- **Signature des releases par une clé privée**, jamais dans le dépôt : la CI la
  reçoit des secrets GitHub `DICTEE_KEYSTORE_BASE64`, `DICTEE_KEYSTORE_PASSWORD`
  et `DICTEE_KEY_ALIAS`, l'écrit dans un fichier temporaire le temps du build et
  le supprime. Sur `main`, si les secrets manquent, aucune release n'est publiée
  (avertissement dans le résumé du workflow, jamais de repli sur la clé de
  debug), et un APK signé par la clé de debug est refusé ; il affiche
  l'empreinte SHA-256 du certificat. Sur une pull request sans secrets (fork),
  l'APK release est construit non signé et n'est pas publié.
- La clé de debug versionnée (`keystore/debug.keystore`, mot de passe `android`)
  ne signe que les builds debug et de test, qui ne sont pas distribués.
- Perdre la clé privée empêche toute mise à jour (il faudrait désinstaller,
  donc perdre réglages et modèles) : elle doit être sauvegardée hors de GitHub.
- `versionCode` = numéro d'exécution du workflow, `versionName` = `0.1.<n>` :
  chaque APK publié s'installe par-dessus le précédent.
- Release GitHub `android-v0.1.<n>` créée à chaque push sur `main`, une fois
  les trois jobs de tests passés.
- Permissions : micro ; Internet (téléchargement des modèles, et serveur distant s'il est choisi) ;
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

## Issue #13, lot 1 : dictée entière transcrite et polie par le serveur

Décisions prises pendant le lot, en plus de celles arrêtées dans l'issue.

### Côté serveur : aucune modification

- `POST /jobs` accepte déjà `quick` et `polish`. Le résultat de
  `GET /jobs/{id}` contient `text`, `polished_text` et `polish`, comme
  `/transcribe`. C'est déjà couvert par `tests/test_api.py`
  (`TestPolish.test_quick_job`, `test_polish_stage_reported`). Le point
  « non vérifié » de l'issue est donc tranché : rien à ajouter côté serveur,
  et `pytest` n'est pas concerné par ce lot.

### Transport

- **API de tâches** : `POST /jobs` (`quick=true`, `polish=true`, `language`
  si réglé), puis `GET /jobs/{id}` toutes les 400 ms. Chaque `GET` a un délai
  de lecture d'au plus 15 s : une requête courte ne risque pas le délai du
  proxy Cloudflare.
- **Repli synchrone** sur `POST /transcribe/streaming` avec `polish=true`
  seulement si `POST /jobs` répond **404 ou 405** : c'est la marque d'un
  serveur sans API de tâches. Les autres échecs (5xx, 401/403, 302, réseau)
  ne sont pas des problèmes de route : ils passent directement au repli local.
  Retenter la même requête sur une autre route doublerait l'attente pour rien.
- **Redirections jamais suivies** (`instanceFollowRedirects = false`). Sinon,
  `HttpURLConnection` suivrait le 302 d'Access vers la page de connexion et
  recevrait un 200 en HTML. Tout 3xx est traité comme « redirection vers la
  page de connexion Access ».
- Une réponse 200 qui n'est pas du JSON (page HTML d'un proxy) est un échec
  (`BAD_RESPONSE`) et mène au repli local.
- **Délai total** : 60 s + 1 × la durée de l'audio, plafonné à 6 min, compté
  depuis le début de l'envoi (envoi compris). Une fois le délai dépassé, la
  tâche serveur est annulée (`POST /jobs/{id}/cancel`, au mieux) et l'app
  passe au repli local. Connexion : 4 s, comme avant.
- Le segment isolé (`RemoteEngine.transcribe`, utilisé par l'écran de
  comparaison) garde `/transcribe/streaming` sans polissage, pour comparer
  les modèles à armes égales. Il reçoit aussi les en-têtes Access.

### Audio envoyé et repli

- Pendant une dictée en mode serveur, le contrôleur garde **tout l'audio
  capté** et, à part, **les segments du VAD**. La transcription segment par
  segment et le texte provisoire sont coupés.
- On envoie l'audio complet, du début de la première parole (moins 0,6 s de
  marge, comme pour les segments) jusqu'à l'arrêt. Le silence initial est
  retiré, mais on garde les pauses internes : elles portent la ponctuation et
  le rythme que le serveur et le polissage exploitent. Si le VAD n'a entendu
  aucune parole, rien n'est envoyé et rien n'est inséré.
- **Repli local sur les segments du VAD**, pas sur l'audio complet : les
  modèles du téléphone sont faits pour des segments d'au plus 20 s (une
  dictée de 5 min en un bloc dépasserait leur mémoire). Le texte est assemblé
  comme avant (`TextAssembler`). Mémoire : au pire environ 2 × 19 Mo pour une
  dictée de 5 min, libérés à la fin.
- **Annuler** (pendant l'attente) : la tâche serveur est annulée et le modèle
  local transcrit l'audio gardé. Une annulation par l'utilisateur ne dit rien
  de l'état du serveur : elle **ne déclenche pas** le délai de 60 s avant le
  prochain essai. Les vraies pannes, elles, le déclenchent, comme avant.
- **Dictée abandonnée** (le champ change, l'IME se ferme) : la tâche serveur
  est annulée, et rien n'est transcrit ni inséré. C'est le comportement
  existant pour un changement de champ.
- **Durée maximale** : au bout de 5 min, la capture s'arrête et ce qui a été
  dit est envoyé. Le message « Durée maximale atteinte » est une information,
  pas une erreur.
- **Limite connue** : si l'utilisateur a choisi « Afficher une erreur » comme
  repli (ou si le modèle de repli n'est pas installé), il n'y a pas de modèle
  local. Une panne du serveur affiche alors une erreur et la dictée est
  perdue. Les Réglages l'en avertissent, et le bouton Annuler est alors
  masqué. Le réglage par défaut reste Parakeet, qui ne perd rien.

### Texte inséré

- `polished_text` si `polish.applied` est vrai et que le texte n'est pas
  vide. Sinon, `text` (le brut du serveur) est inséré, avec le message
  discret « Texte non poli (raison) ». Ce n'est pas une erreur.
- Les commandes vocales, le vocabulaire, les filtres et l'assemblage
  s'appliquent au texte final, comme pour un segment. Le filtre
  d'hallucinations Whisper ne s'applique pas : la famille « distant » n'y est
  pas soumise, comme avant.
- Après un repli local, le clavier affiche la raison (« Serveur non utilisé
  (…) : transcrit sur le téléphone ») ou « Attente annulée ».

### Cloudflare Access et secrets

- Deux réglages, Client ID et Client Secret. Les en-têtes ne partent que si
  **les deux** sont remplis : un seul en-tête serait refusé par Access de
  toute façon, et le refus serait moins clair.
- Le secret est stocké dans les préférences privées existantes (l'issue exclut
  le stockage chiffré avancé). Il est saisi dans un champ mot de passe, donc
  masqué, y compris dans les captures d'écran.
- Pour qu'il n'apparaisse jamais dans un journal :
  - `RemoteConfig.toString()` masque le jeton et le service token ;
    `EngineOptions` est un data class qui pourrait être journalisé ;
  - tout message d'erreur qui inclut une réponse du serveur passe par
    `RemoteConfig.redact()`, qui remplace secret, jeton et Client ID par `***`.
    C'est le cas d'un serveur qui renverrait les en-têtes reçus ;
  - le journal du chemin distant (`RemoteLog`, balise logcat `DicteeRemote`)
    ne reçoit que des messages déjà expurgés ;
  - le rapport du diagnostic indique seulement « service token configuré :
    oui/non ».
- Test de connexion : `GET /health` avec tous les en-têtes. Il distingue :
  accès refusé (401/403), redirection Access (3xx, avec le conseil
  « politique Service Auth »), serveur injoignable, réponse illisible et
  succès. En cas de succès, il affiche aussi la disponibilité du polissage
  (`polish_available`).

### Interface

- Pas de nouvel état de dictée : l'attente du serveur est l'état FINISHING
  existant, avec un indicateur `waitingServer`. Le clavier affiche
  « Transcription et polissage… » (« Transcription sur le serveur… » pour une
  API OpenAI) et un bouton **Annuler** à côté de la ligne d'état. Le micro
  reste désactivé pendant l'attente, comme pendant toute transcription de fin.
- **Diagnostic serveur** : écran à part (`DiagnosticActivity`), ouvert depuis
  les Réglages. Chaque lancement fait 4 essais : les trois enregistrements de
  référence, puis leur enchaînement avec 1 s de silence entre chacun
  (environ 31 s, proche de la dictée de 30 s de la cible de latence). Le
  diagnostic n'a pas de repli local, puisqu'il mesure le serveur : un échec
  est affiché comme tel. La logique est dans `ServerDiagnostic`, testée en JVM.

### Tests

- `WholeDictationTest` (JVM, faux serveur HTTP local qui imite l'API de
  tâches) couvre :
  - une seule requête par dictée de trois énoncés, avec `polish=true` et
    `quick=true`, et le WAV qui contient toute la dictée ;
  - les en-têtes Access sur toutes les requêtes (`POST /jobs`, chaque
    `GET /jobs/{id}`, `/health`), et leur absence s'ils ne sont pas configurés
    ou incomplets ;
  - l'insertion de `polished_text`, puis de `text` si le polissage a échoué
    ou est absent ;
  - le repli local sur l'audio gardé en cas de 502, d'erreur de tâche, de
    401, de 403, de 302 (redirection non suivie), de serveur injoignable, de
    délai dépassé (avec annulation de la tâche) et de réponse HTML ;
  - le nouvel essai après 60 s, l'annulation (Annuler, abandon, annulation
    avant l'envoi), la route synchrone si `/jobs` répond 404, l'API OpenAI, le
    budget d'attente, la durée maximale et la dictée sans parole ;
  - le test de connexion par cas ;
  - l'absence du secret dans tous les journaux et messages, y compris quand le
    serveur renvoie le secret dans son erreur ;
  - le rapport du diagnostic.
- Test instrumenté `dictationOnARemoteServer` : deux énoncés dictés dans
  l'IME réelle vers un faux serveur de tâches sur l'émulateur. Il vérifie une
  seule requête et une seule insertion du texte poli, l'état d'attente, les
  en-têtes Access, et que le secret est absent de logcat.
- `RecordingServer` (builds debug) reçoit désormais la requête entière
  (méthode et chemin), et ajoute un `Location` vers une page Access à ses
  réponses 3xx.

### Revue indépendante (Claude) et corrections

Une revue indépendante du lot (un agent qui n'avait que le code et l'issue) a
relevé les points suivants. Tous ont été vérifiés dans le code et corrigés :

- **Attente suivie par dictée** : l'indicateur « en attente du serveur » était
  global. Une dictée abandonnée (le champ change) dont la requête se termine
  en retard pouvait effacer l'état d'attente de la dictée suivante, qui
  restait alors sans bouton Annuler. L'indicateur porte désormais le numéro de
  session, et l'IME le lit dans le contrôleur.
- **Tâche serveur toujours annulée** : si Annuler ou le délai arrivait pendant
  l'envoi de `POST /jobs`, la tâche était créée quand même et personne ne
  l'annulait. Désormais, la tâche est annulée dès que son id arrive. Un échec
  d'interrogation (502/524 passager de Cloudflare) l'annule aussi. Chaque
  requête, `GET` compris, tourne sur un fil auxiliaire : Annuler et le délai
  n'attendent plus une requête bloquée (les E/S réseau d'Android ne
  s'interrompent pas).
- **Fin de l'attente dès le repli local** : l'état « Transcription et
  polissage… » et le bouton Annuler disparaissent dès que le téléphone prend
  le relais (`WaitControl.onLocalStart`), et non à la fin de la transcription
  locale.
- **Pas de modèle de repli** : le bouton Annuler est masqué, puisqu'il ne
  ferait que perdre la dictée, et les Réglages avertissent qu'une panne du
  serveur perdrait alors la dictée.
- **Repli pour toute erreur** : `FallbackEngine` passe au local pour toute
  exception du chemin distant, pas seulement `RemoteException`, et pour un
  manque de mémoire pendant l'envoi. La capture rattrape aussi un manque de
  mémoire en agrandissant son tampon. Si l'audio complet ne peut pas être
  copié, les segments partent directement au modèle local.
- **Mémoire** : le corps multipart est écrit en trois morceaux (en-tête, WAV,
  fin) au lieu d'être copié en entier, ce qui économise environ 20 Mo pour
  une dictée de 5 min.
- **Secret** : le Client ID, le secret et le jeton sont nettoyés des
  caractères invisibles quand on les enregistre et quand on les envoie. Un
  espace de largeur nulle collé avec le secret ferait sinon lever par la pile
  HTTP une erreur qui cite la valeur. Tout message d'exception du chemin
  distant et du test de connexion est expurgé.
- **États d'interface** :
  - « Durée maximale atteinte » est une information (`onInfo`), plus une
    erreur rouge ;
  - si la capture s'arrête seule (erreur du micro), la dictée passe en
    « fin », avec Annuler ;
  - un résultat mis de côté parce que le champ était absent garde ses
    détails (message de repli ou de polissage) ;
  - le diagnostic s'arrête quand on quitte l'écran.
- **Divers** :
  - une redirection vers un autre hôte que Cloudflare Access est signalée
    comme telle, avec l'hôte cible ;
  - un `polished_text` sans objet `polish` est accepté.
- Nouveaux tests dans `WholeDictationTest` : annulation pendant l'envoi,
  échec passager d'interrogation, erreur quelconque du chemin distant, fin de
  l'attente au début du repli local, attente par dictée (dictée abandonnée
  qui finit en retard), résultat mis de côté, caractères invisibles dans le
  secret, redirection vers un autre hôte.

### Pour le point de contrôle

Réglages → Serveur distant → **Diagnostic serveur** donne, pour chaque essai,
le temps d'envoi et le temps total (cible : moins de 5 s pour 30 s de
dictée), le statut du polissage, et le texte brut et le texte poli côte à
côte. « Exporter le rapport » permet de joindre le résultat à l'issue. Pour
la fiabilité, couper le serveur (ou le tunnel) pendant l'attente : la dictée
doit être insérée par le modèle local, avec le message « Serveur non utilisé ».

## Issue #15, lot 1 — étape 1.1 : table de recommandation

Code : `stt/ModelRecommendation.kt` (Kotlin pur, à côté de `ModelCatalog`) ;
tests : `ModelRecommendationTest` (les 2047 combinaisons non vides).

### Couverture vérifiée (2026-11-12)

| Langue | Parakeet v3 | Qwen3-ASR 0.6B | Whisper turbo |
|---|---|---|---|
| Français (Québec et France), anglais, espagnol, portugais, russe | oui | oui | oui |
| Mandarin, arabe, hindi | non | oui | oui |
| Bengali, ourdou | non | **non** | oui |

Sources :

- Parakeet : fiche `https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3`, 25
  langues européennes (dont anglais, français, espagnol, portugais, russe) ;
  ni mandarin, ni hindi, ni arabe, ni bengali, ni ourdou. La fiche précise que
  le portugais d'entraînement est européen alors que la plupart des
  références sont en portugais brésilien.
- Qwen3-ASR : fiche `https://huggingface.co/Qwen/Qwen3-ASR-0.6B`, 30 langues
  plus 22 dialectes chinois. « Chinese » figure sans distinction du mandarin
  (retenu comme mandarin). Hindi et arabe sont listés. **Bengali et ourdou ne
  le sont pas** (le persan l'est, pas l'ourdou) : la mention « non confirmé »
  du tableau de l'issue devient « non couvert ».
- Whisper large-v3-turbo : la fiche `https://huggingface.co/openai/whisper-large-v3-turbo`
  ne nomme aucune langue (« 99 languages », « strong ASR results in ~10
  languages », précision moindre pour les langues peu dotées). La couverture
  des onze langues vient de la liste `LANGUAGES` du code officiel,
  `https://raw.githubusercontent.com/openai/whisper/main/whisper/tokenizer.py`
  (zh, hi, ar, bn, ur, ru, pt, es, fr, en). **Être listé n'est pas être bon** :
  bengali et ourdou sont couverts mais probablement moins bien transcrits ; la
  table ne mesure pas la qualité.

Aucune fiche n'était inaccessible. Les lectures ont été faites par un outil de
résumé de pages, non à la main : la couverture est à relire à l'occasion.

### Règles de la table (`ModelRecommendations.recommend`)

Lues de haut en bas, la première qui s'applique gagne :

1. aucune langue : Parakeet (choix prudent : le plus léger, 671 Mo, et le
   modèle de la langue présélectionnée) ;
2. toutes les langues sont dans les 25 de Parakeet : Parakeet ;
3. sinon Qwen3-ASR (987 Mo) s'il couvre tout ;
4. sinon Whisper (1 037 Mo), le seul qui couvre les onze.

À couverture égale, le plus petit téléchargement l'emporte. Résultat : la
recommandation couvre toujours toutes les langues choisies (testé). Rien n'est
bloqué : les autres modèles restent téléchargeables.

### Français québécois : observation, puis mesure

Parakeet est recommandé pour le français québécois sur la foi de l'observation
de l'utilisateur. La raison affichée dit « observation ». `quebecFrenchBasis`
passe à `RecommendationBasis.MESURE` (une ligne) quand l'étape 1.2 aura chiffré
le WER ; la raison dira alors « mesure ». Sans mesure ni observation sur Qwen3
et Whisper en français québécois, la table ne les départage pas sur ce critère.

### Autres choix

- Québec et France : deux valeurs distinctes de `DictationLanguage`, même
  `engineCode` (`fr`). Québec est en tête de liste et `default`.
- `covers(modèle, langue)` sert à l'avertissement de l'étape 1.3. Le serveur
  distant n'est jamais signalé (c'est le serveur de l'utilisateur, on ne peut
  pas juger) ; tout autre modèle inconnu compte comme non couvert.
- Les raisons sont des phrases en français construites par la table (pas de
  chaînes en ressources) : l'étape 1.3 peut les afficher telles quelles ou les
  déplacer dans `strings.xml`.

## Issue #15, lot 1 — étape 1.3 : renommage, accueil, langues

### Renommage

`app_name` = « Kalmia Blabla », libellé du clavier = « Kalmia Blabla (voix) ».
Mises à jour : `strings.xml` (messages, étapes, zone d'essai), en-tête du
rapport de `CompareActivity` et du diagnostic serveur. Inchangés, comme décidé :
l'identifiant `io.github.remtav.dictee`, les noms de classes, les noms de
fichiers de release. `AndroidManifest.xml` et `res/xml/method.xml` passent par
`@string/app_name`, `@string/ime_name` et `@string/subtype_label` : rien à
changer en dur. **Hors portée de cette étape, à reprendre en 1.4** : le README
et `.github/workflows/android.yml` (titre et description de la release citent
encore « Dictée locale » ; le fichier est un workflow, donc sous approbation).

### Texte d'accueil (`setup_intro`)

Quatre lignes : conçu au Québec pour le français d'ici ; la voix est
transcrite sur le téléphone et n'est envoyée à personne, **sauf si l'utilisateur
choisit lui-même un serveur distant** (la phrase reste vraie par défaut) et le
réseau sert à télécharger les modèles ; pas de compte, pas de publicité, pas de
télémétrie ; code ouvert, mais modèles d'éditeurs étrangers « nommés plus bas »
(l'origine des modèles n'est pas cachée, pas de « 100 % québécois », rien sur
Google). Chaque carte de modèle affiche éditeur, description, licence et
« s'exécute sur votre téléphone ».

### Étape « Vos langues »

- Section 3 de l'écran de configuration, avant « 4. Modèles » : cases à cocher
  pour les onze langues, Québec en tête et présélectionné. Même liste dans les
  Réglages. Pas de « premier lancement » à mémoriser : sans choix enregistré,
  la valeur par défaut est le français (Québec).
- Stockage : `AppSettings.languages` (ensemble de noms d'enum, ordre de la
  liste conservé). **Tout décocher est impossible** : la dernière case reste
  cochée et un message l'explique ; en plus, un choix vide, absent ou inconnu se
  relit comme le français (Québec) (`DictationLanguages.parse`).
- Les cases de l'écran de configuration sont redessinées à chaque `onResume`
  (une modification faite dans les Réglages y apparaît).

### Recommandation et avertissement

La carte du modèle de `ModelRecommendations.recommend` porte « Recommandé pour
vos langues » et la phrase de raison. Un modèle qui ne couvre pas une langue
cochée affiche « Attention : ce modèle ne couvre pas … » (texte, couleur
`colorError` du thème en plus). Rien n'est bloqué : tous les boutons de
téléchargement restent actifs. Le serveur distant n'est jamais badgé ni signalé.

### Langue transmise aux moteurs

- `EngineOptions.language` : Whisper local seulement, = le code unique quand
  tous les choix partagent un `engineCode` (Québec + France = `fr`), vide
  (détection automatique) sinon. Les autres modèles reçoivent toujours vide,
  donc changer de langue ne recharge ni Parakeet ni Qwen3.
- `remoteLanguage` : tant que l'utilisateur ne l'a pas réglé, il suit le choix
  (`fr` ou `en` si c'est l'unique code, sinon vide), car le serveur n'accepte
  que vide, `fr` ou `en`. Une valeur écrite explicitement, y compris vide
  (« Détection automatique »), n'est jamais écrasée.

### Tests

- JVM : `DictationLanguagesTest` (code moteur, contrainte du serveur, repli du
  choix vide, aller-retour du stockage). La persistance dans `SharedPreferences`
  n'est pas testable sur la JVM (pas de `Context`) : couverte par l'instrumenté.
- Instrumenté : `SetupLanguageTest` (lit l'arbre de vues par étiquettes et
  identifiants, sans réseau ni modèle) : accueil au premier lancement, Parakeet
  marqué pour le français québécois, badge et avertissement après ajout du
  mandarin puis du bengali, dernière langue non décochable, changement dans les
  Réglages -> recommandation changée, `remoteLanguage` et langue de Whisper.
  Compilé ici (`assembleDebugAndroidTest`), exécuté par la CI seulement.
- `ScreenshotTest` : aucun changement nécessaire (ne cite pas l'ancien nom) ;
  captures à refaire en 1.4.

### Thème

Aucune couleur ni police en dur : `badge` et `warning` (dans `ui/Ui.kt`)
utilisent `colorPrimary` et `colorError` du thème, l'étape 1.5 les habillera.

## Issue #15, lot 1 — étape 1.2 : extraits québécois et mesure

### Extraits retenus

Six extraits de Common Voice 22.0 (français, partition `test`, CC0), tous
portant le champ d'accent exact « Français du Canada », de six locuteurs
différents (six `client_id` distincts ; genre et âge déclarés quand ils le
sont). Source : miroir Hugging Face `fsicoli/common_voice_22_0`
(`transcript/fr/test.tsv` pour les métadonnées, `audio/fr/test/fr_test_0.tar`
pour l'audio), lu sans compte ni jeton ; les jeux `mozilla-foundation/*` sont
protégés (401) et l'API `datasets-server` est désactivée pour ces miroirs.
Le tar a été lu en flux jusqu'aux six fichiers.

| Fichier | Clip Common Voice | Durée | Transcription (celle du jeu) |
| --- | --- | --- | --- |
| `frqc1.wav` | `common_voice_fr_19133342` | 5,0 s | Parfois, seuls le riz et le pain sont proposés à discrétion. |
| `frqc2.wav` | `common_voice_fr_20306204` | 6,5 s | Du reste sa mère aimait ses sœurs. |
| `frqc3.wav` | `common_voice_fr_35734313` | 5,2 s | À dix-huit ans, elle change légalement de nom. |
| `frqc4.wav` | `common_voice_fr_29487871` | 4,9 s | Mais, n'étant plus maître de sa propre maison, il préfère arrêter. |
| `frqc5.wav` | `common_voice_fr_40487212` | 4,9 s | La sorcière décide de rentrer au palais pour protéger les rebelles. |
| `frqc6.wav` | `common_voice_fr_39757516` | 4,5 s | Je crains que ce ne soit pas franchement satisfaisant. |

Sélection : 152 clips « Français du Canada » de la partition `test` ont 2
votes positifs ou plus et aucun négatif ; parmi eux, un par locuteur, phrases
de 7 à 12 mots sans noms propres ni caractères inhabituels, et sans phrase
visiblement tronquée dans le jeu (plusieurs phrases de Common Voice ont un mot
manquant, écartées). Choix fait uniquement sur le champ d'accent : **personne
n'a écouté les clips**. Le code « canada » est déclaré par le contributeur et
ne distingue pas le Québec des autres provinces ; l'écoute reste à faire
(`A_VERIFIER.md`). Les phrases sont des phrases de Wikipédia et de romans, pas
du vocabulaire québécois : la mesure porte sur l'accent, pas sur le lexique.

Conversion : `ffmpeg -ac 1 -ar 16000 -c:a pcm_s16le`, soit environ 1 Mo au
total pour les six fichiers.

### Format dans `references.tsv`

Les colonnes ne changent pas (`file`, `language`, `transcript`, `source`).
Le code de langue reste `fr` (c'est ce que comprennent les moteurs, `Benchmark`,
`CompareActivity` et `StoreTest`, qui exige exactement `{en, fr}`). Le marqueur
de variante est le **préfixe `frqc` du nom de fichier** : aucune colonne
ajoutée, aucun analyseur modifié. La colonne `source` donne la version du jeu,
l'identifiant du clip, l'accent exact et l'adresse. `ModelAccuracyHostTest`
sépare donc trois groupes : `en`, `fr` (`fr1`, France) et `frqc` (Québec).
Effet de bord voulu : l'écran « Comparer les modèles » affiche aussi les six
clips (« FR »), et le WER moyen d'un modèle les inclut.

### Méthode

`ModelAccuracyHostTest` (sherpa-onnx 2.0.1, CPU Linux x86-64, 4 fils,
`Benchmark` de l'application) transcrit chaque clip entier ; le WER d'un
groupe est (somme des erreurs) / (somme des mots de référence), calculé par
`Wer` (minuscules, ponctuation retirée ; les nombres ne sont pas normalisés).
Il imprime une ligne `WER <modèle> <groupe> = …` par modèle et par groupe.
Seuil : pour `frqc`, **plafond très large de 60 %**, simple détecteur de
décodage cassé (six clips courts sont trop peu pour un seuil serré) ; les
plafonds `en` et `fr` existants (12 % et 15 %) sont inchangés. Le test ne
dépend donc pas du résultat réel dans le détail, ce qui évite une CI fragile.

### Résultats (mesure du 2026-11-12)

| Modèle | Français québécois (6 clips, 59 mots) | Français de France (1 clip, 16 mots) | Anglais (2 clips, 66 mots) |
| --- | --- | --- | --- |
| Parakeet TDT 0.6b v3 | **6,8 %** (4 erreurs) | 0,0 % | 3,0 % |
| Whisper large-v3-turbo | 10,2 % (6 erreurs) | 6,3 % | 4,5 % |
| Qwen3-ASR 0.6B | 13,6 % (8 erreurs) | 0,0 % | 4,5 % |

Par clip (québécois), erreurs : Parakeet `frqc1` 1 (« seul »), `frqc2` 3
(« Du reste sa mère a M. »), les quatre autres 0 ; Qwen3 `frqc1` 1, `frqc2` 3
(« sa mère est mécène »), `frqc3` 4 (« change les gélins de l'ombre »), le
reste 0 ; Whisper `frqc1` 1, `frqc2` 1 (« ses âmes »), `frqc3` 4 (« 18 ans »
écrit en chiffres, compté faux par `Wer`, et « nombre » pour « nom »), le reste
0. Vitesse indicative (RTF CPU) : Parakeet 0,07, Whisper 0,3, Qwen3 0,6 à 0,9.

### Conclusion

**La mesure va dans le sens de l'observation, sans la prouver.** Parakeet a le
WER le plus bas sur les clips québécois (6,8 % contre 10,2 % pour Whisper et
13,6 % pour Qwen3-ASR), et c'est le seul qui transcrit correctement
« dix-huit ans… de nom » (`frqc3`). Mais l'échantillon est de 59 mots : l'écart
avec Whisper tient à deux erreurs, dont une est un artefact de notation
(« 18 » contre « dix-huit »), et un seul clip (`frqc2`) fait échouer les trois
modèles. Whisper et Parakeet sont donc au coude à coude une fois l'artefact
retiré (5 erreurs contre 4) ; l'avantage net de Parakeet sur Qwen3 est plus
solide. **Aucun écart à signaler avec la recommandation** : Parakeet reste
recommandé pour le français québécois (aussi le plus petit et le plus rapide).
À formuler dans l'application comme « mesuré sur six courts extraits », pas
comme une supériorité établie. Pour un chiffre solide, il faudrait des dizaines
de clips, ce que les 152 clips « Français du Canada » de la seule partition
`test` permettraient, après écoute.

## Issue #15, lot 1 — étape 1.5 : thème Kalmia

Étape 1.5 de l'issue #15 : l'application et le clavier prennent l'identité visuelle de Kalmia (couleurs
claires et sombres, polices embarquées, angles droits, ombre décalée, logo). Source Kalmia lue en lecture
seule le 2026-11-12 (`/home/user/Kalmia.ca`) : `index.html` (feuille de style), `design/marque/tokens.css`,
`design/marque/README.md`, `design/marque/icone/` et `design/marque/logo/`, `rapport-exemple/polices/`.

### Valeurs relevées (et fichier source)

Clair / sombre, relevées dans `index.html` (variables `--paper`, `--paper-2`, `--ink`, `--muted`, `--rule`,
`--green`, `--button`, `--on-button`, `--flower`, `--on-accent`, lignes 66 à 84) et confirmées par
`design/marque/tokens.css` :

| Rôle Kalmia | Clair | Sombre | Ressource (`values/colors.xml`, `values-night/colors.xml`) |
| --- | --- | --- | --- |
| Papier | `#F5F1E8` | `#13160F` | `kalmia_paper` |
| Papier 2 | `#EDE7DA` | `#1B1F17` | `kalmia_paper_2` |
| Encre | `#23261F` | `#ECE7DA` | `kalmia_ink` |
| Atténué (lichen) | `#5B5E54` | `#A9AB9B` | `kalmia_muted` |
| Filet | `#CFC8B8` | `#3A3E33` | `kalmia_rule` |
| Vert (feuille) | `#3F5E45` | `#8DB596` | `kalmia_green` |
| Bouton (sauge) | `#8FAE93` | `#8DB596` | `kalmia_button` |
| Texte sur bouton | `#23261F` | `#13160F` | `kalmia_on_button` |
| Fleur (kalmia) | `#A9446A` | `#DE85A7` | `kalmia_flower` |
| Texte sur fleur | `#FFFFFF` | `#13160F` | `kalmia_on_flower` |

Polices : Newsreader (variable : `wght` 200 à 800, `opsz` 6 à 72, défaut 18) et IBM Plex Mono (Regular 400,
Medium 500), fichiers de `rapport-exemple/polices/`, licence SIL OFL 1.1 vérifiée dans les fichiers de licence
fournis (`OFL-Newsreader.txt`, `OFL-IBMPlexMono.txt`) et dans la table `name` des polices. Les fichiers sont
ceux de la source : ils conviennent à Android (TTF, polices variables prises en charge dès l'API 26, `minSdk` = 29),
donc aucun fichier « officiel » de remplacement. Échelle de la marque : étiquette mono 13 px minimum,
interlettrage `.14em` (étiquettes) et `.1em` (boutons), texte 19 px / interligne 1,5, boutons
`box-shadow: 4px 4px 0 var(--ink)`, rayon 0, filets de 1 px.

### Écarts entre la source et l'issue

La source et le tableau de l'issue concordent sur chaque valeur de couleur. Écarts de fond :

- **Filet comme contour** : l'issue range « Filet » dans « bordures, séparateurs ». Le README de la marque le
  réserve au décor (1,48:1 sur papier), donc le filet ne sert ici qu'aux séparateurs et bordures décoratives
  (bordure des cartes, piste du niveau sonore, piste inactive du curseur). Les contours de contrôles (touches du
  clavier, cases, interrupteurs, champs) prennent l'atténué (`colorOutline`, 5,9:1 sur papier) pour atteindre 3:1
  (WCAG 1.4.11). Testé.
- **Bouton sauge sur papier** : 2,16:1, le README de la marque l'avait déjà noté ; l'ombre pleine encre et le texte
  encre (6,32:1) portent le contour, comme sur le site.
- **Texte de l'étiquette** : le site descend à 11 ou 12 px par endroits ; la marque (`tokens.css`) exige 13 px
  minimum. L'application suit `tokens.css` (13 sp).
- **Tailles de corps** : le site fait 19 px / 16 px ; l'application prend 17 sp / 15 sp (les `sp` suivent le réglage
  de taille du système, et un écran de téléphone est plus proche de l'œil).
- **Rouge d'erreur** : `#BA1A1A` / `#FFB4AB`, demandé par l'issue, absent de la source Kalmia. `kalmia_on_error`
  (`#FFFFFF` / `#690005`) vient du jeu Material standard et sert seulement aux composants qui héritent du rôle.
- **Surfaces inverses** (info-bulles, messages) : valeurs du thème opposé (`kalmia_inverse_*`), absentes de la
  source ; testées à 4,5:1.
- **Accent fort** : `--kalmia-profond` (`#7A2F4C`) n'est pas utilisé (aucun texte d'accent fort).
- **Licences** : l'issue demande « `res/font` avec leur fichier de licence ». Un fichier `.txt` n'est pas une
  ressource valide dans `res/font` (nom `OFL-…`, majuscules et tiret refusés par aapt2), donc les licences sont dans
  `app/src/main/assets/licences/`, dans l'APK. Un test vérifie leur présence.

### Correspondance couleurs → rôles Material 3 (`res/values/themes.xml`, `Theme.Dictee`)

Le thème ne contient que des renvois `@color/kalmia_*` (clair et sombre par les qualificatifs de ressources) ; un
seul fichier de thème remplace `values-night/themes.xml` (supprimé : les barres système claires ou sombres
passent par `@bool/kalmia_light_system_bars`). Tous les rôles Material 3 utilisés par un composant sont définis
(un test le vérifie), pour que rien ne retombe sur la palette violette par défaut.

| Rôle Material | Couleur Kalmia | Où on le voit |
| --- | --- | --- |
| `colorSurface`, `colorBackground`, `colorSurfaceContainerLowest/Low/Dim/Bright` | papier | fond des écrans et du clavier |
| `colorSurfaceContainer/High/Highest`, `colorSurfaceVariant` | papier 2 | cartes, touches, zones de saisie |
| `colorOnSurface`, `colorOnBackground` | encre | texte, filet des boutons secondaires, ombre décalée |
| `colorOnSurfaceVariant` | atténué | texte secondaire, étiquettes, état du clavier |
| `colorOutline` / `colorOutlineVariant` | atténué / filet | contours de contrôles / séparateurs |
| `colorPrimary` / `colorOnPrimary` | vert / papier | titres de section, badge « Recommandé », cases et interrupteurs activés, barre de niveau |
| `colorPrimaryContainer` / `colorOnPrimaryContainer` | bouton (sauge) / texte sur bouton | boutons d'action, micro au repos |
| `colorTertiary`, `colorTertiaryContainer` / `colorOn…` | fleur / texte sur fleur | micro à l'écoute ; mots surlignés du lot 4 |
| `colorSecondary…` | atténué, papier 2, encre | éléments sélectionnés des composants Material |
| `colorError` / `colorErrorContainer` | rouge / papier 2 | erreurs et avertissements (toujours avec texte et icône) |
| `colorSurfaceInverse`, `colorPrimaryInverse` | valeurs du thème opposé | messages, info-bulles |

Le clavier choisit toujours son thème par `DicteeImeService.themedInflater()` (réglage système, clair, sombre : un
contexte de configuration `uiMode` forcé) ; comme les couleurs sont des ressources qualifiées, le clavier prend
les couleurs sombres ou claires du réglage sans autre code. Le réglage de thème existant n'a pas changé.

### Polices et styles de texte

- `res/font/newsreader.xml` (Newsreader 400, 500, 700 et italiques par `fontVariationSettings`) et
  `res/font/ibm_plex_mono.xml` (400, 500). `android:fontFamily` du thème = Newsreader : tout texte non stylé est
  en Newsreader. Aucune police téléchargeable : pas de fournisseur de polices, pas de `fontProviderAuthority`
  (test `NoHardcodedStyleTest.noDownloadableFontIsDeclared`).
- Styles nommés (`themes.xml`) : `TextAppearance.Kalmia.Headline` (28 sp), `.Title` (22 sp), `.TitleSmall`
  (18 sp, graisse 500), `.Body` (17 sp), `.BodySmall` (15 sp), `.Label` (Plex Mono 13 sp, majuscules,
  interlettrage 0,14), `.Button` (Plex Mono 500, 14 sp, majuscules, 0,1), `.Value` (Plex Mono 14 sp), `.Key`
  (Plex Mono 500, 24 sp). Les quinze échelles typographiques Material (`textAppearanceBodyMedium`, …) y renvoient,
  donc les composants Material (boutons, cases, dialogues) les héritent. `ui/Ui.kt` n'utilise plus que ces styles :
  `title`, `heading`, `body`, `badge`, `warning`, et les nouveaux `value` (taille des modèles, scores, temps du
  diagnostic) et `label` (« Brut » / « Poli » du diagnostic) ; `logo()` pose le logo sur l'accueil. Les « petites
  capitales » sont des majuscules espacées (`textAllCaps`) : Android n'a pas de vraies petites capitales, et le site
  fait de même (`text-transform: uppercase`).

### Formes et ombre

- Angles droits : `shapeAppearanceCorner*` et `…Component` du thème pointent vers `ShapeAppearance.Kalmia.Square`
  (rayon 0 dp) ; cartes (`Widget.Kalmia.CardView`, papier 2, filet de 1 dp, sans élévation), boutons, champs,
  dialogues l'héritent. Le curseur, les interrupteurs et les cases gardent leur pouce rond (cercle permis par la marque).
- Ombre pleine décalée encre de 4 dp sur les boutons d'action (`Widget.Kalmia.Button`, le style
  `materialButtonStyle` du thème, donc `ViewGroup.button()` sans paramètre de style) : `kalmia_ombre_bouton.xml`
  (deux bandes encre en premier plan) posée sur un bouton dont les marges internes droite et bas font 4 dp : rien ne
  recouvre le texte, aucun parent à ne pas rogner. Pas de translation à l'appui (le site en fait une) : l'ondulation
  Material reste le retour visuel. Les boutons secondaires (`materialButtonOutlinedStyle`) ont un filet encre de 1 dp
  sans ombre.

### Clavier

- Fond papier ; touches papier 2 avec filet atténué (contour 5,9:1), sans ombre ; micro carré de 100 dp (96 dp
  visibles + ombre), vert (couleur bouton) au repos, fleur quand la vue est activée (écoute), icône blanche
  (5,64:1) ou `#13160F` (6,98:1) ; barre de niveau carrée, piste en filet, barre verte (le rose reste à un seul endroit,
  le micro). Le texte d'état est en Plex Mono (`TextAppearance.Kalmia.Value`, `colorOnSurfaceVariant`) ; une erreur
  passe en `colorError` avec l'icône `ic_error` devant le texte (ajoutée aussi aux avertissements et aux échecs
  du diagnostic).
- **Simplifications de touches (lisibilité d'abord)** : les symboles `, . ? !` sont en **Plex Mono 500, 24 sp**, pas en
  Newsreader (la virgule et le point d'un serif sont fins et petits à cette taille) ; **aucune ombre décalée sur les
  touches** (seul le micro et les boutons d'action des écrans en ont : 7 touches voisines avec une ombre de 4 dp
  mangeraient la largeur et brouilleraient les zones tactiles) ; icônes de touches à 24 dp.
- **Zones tactiles** : le bouton de modèle, « Annuler », et les réglages passent de 40 dp à 48 dp ; touches 52 dp ;
  micro 96 dp. Symboles de touches 24 sp (≥ 20 sp). Le clavier gagne 8 dp de hauteur.

### Logo et icône de lancement

- **Logo retenu : celui de la source, sans repli.** `design/marque/logo/kalmia-logo.svg` (coupe + nom vectorisé) est
  converti en `drawable/kalmia_logo.xml` (tracés identiques, nombres arrondis à 0,1 unité, `@color/` : coupe et
  « ia » en fleur, cœur en vert, « Klm » en encre, clair et sombre) et affiché sur l'accueil. L'icône de lancement
  reprend la **coupe** (`design/marque/icone/kalmia-icone.svg`, `kalmia-icone-sombre.svg`) : fond papier,
  trait de 9 unités à l'échelle 0,34 dans la zone sûre (≈ 3 dp de trait, lisible), cœur vert ; version
  monochrome (`ic_launcher_monochrome.xml`) pour les icônes thématiques d'Android 13. La version « petite » (trait 16)
  de la marque n'est pas nécessaire : le trait de 9 ne descend pas sous 3 dp à 48 dp. Fond et couleurs de l'icône
  suivent le thème clair ou sombre du téléphone si le lanceur applique le mode nuit.
- Le pictogramme de micro n'est plus l'icône de l'application (il reste le glyphe du bouton micro).

### Tests ajoutés

- `ThemeContrastTest` : lit `values/colors.xml`, `values-night/colors.xml` et `values/themes.xml`, résout rôle →
  couleur, exige ≥ 4,5:1 pour chaque paire texte / fond utilisée, dans les deux thèmes, et 3:1 pour le contour des
  contrôles ; vérifie les valeurs de la palette contre la source Kalmia, le rouge d'erreur (`#BA1A1A` / `#FFB4AB`,
  jamais la fleur), la définition de tous les rôles et d'une valeur sombre pour chaque couleur.
- `NoHardcodedStyleTest` (garde de CI, tourne dans le job `testDebugUnitTest`) : aucune couleur hexadécimale, constante
  `Color.*`, entier ARGB, `Typeface` ni police en dur dans `app/src/main/java` ; aucune couleur hexadécimale dans les
  ressources hors `colors.xml` (mises en page, drawables, thème, manifeste) ; toute `fontFamily` vient de `res/font` ;
  aucune police téléchargeable ; polices et licences OFL présentes.

Ratios (extraits du test ; clair / sombre) :

| Paire (rôle Material) | Clair | Sombre |
| --- | --- | --- |
| encre sur papier (`colorOnSurface` / `colorSurface`) | 13,61 | 14,80 |
| encre sur papier 2 | 12,45 | 13,55 |
| atténué sur papier | 5,87 | 7,82 |
| atténué sur papier 2 | 5,37 | 7,16 |
| vert sur papier (titres, badge) | 6,43 | 7,99 |
| vert sur papier 2 (badge dans une carte) | 5,88 | 7,32 |
| papier sur vert (`colorOnPrimary`) | 6,43 | 7,99 |
| texte sur bouton sauge | 6,32 | 7,99 |
| texte sur fleur (micro à l'écoute) | 5,64 | 6,98 |
| fleur sur papier / papier 2 (mots surlignés, lot 4) | 5,01 / 4,58 | 6,98 / 6,39 |
| erreur sur papier / papier 2 | 5,73 / 5,24 | 10,76 / 9,86 |
| encre sur papier 2 (éléments sélectionnés) | 12,45 | 13,55 |
| texte inverse sur surface inverse | 13,61 | 14,80 |

Paires Kalmia **écartées pour du texte** (non utilisées comme texte, mesurées à la main) : encre sur fleur (2,72 clair,
2,12 sombre) ; atténué sur sauge (2,72 / 1,02) ; vert sur sauge (2,98 / 1,00) ; encre sombre sur sauge sombre
(1,85) ; sauge sur papier (2,16 clair : fond de bouton seulement, jamais du texte) ; filet sur papier (1,48 :
séparateurs). Texte sur bouton : toujours `colorOnPrimaryContainer` (jamais l'encre du thème, qui est claire en
sombre). Fleur sur papier 2 en clair frôle le seuil (4,58) : usage réservé aux mots surlignés du lot 4, en
texte de corps seulement.

### À juger sur un vrai écran (clair et sombre, à reporter dans `A_VERIFIER.md`)

1. Les touches `, . ? !` en Plex Mono 24 sp sont-elles bien lisibles ? Le clavier a-t-il toujours l'air de Kalmia ?
2. Le micro carré de 100 dp avec ombre encre (surtout en sombre, où l'ombre est claire) : trop massif ? À régler :
   `Widget.Kalmia.Button.Mic` dans `themes.xml`, taille dans `keyboard_view.xml`.
3. Les étiquettes et libellés de boutons en majuscules espacées : un libellé long (« Télécharger… ») passe-t-il à la
   ligne proprement ? À régler : `TextAppearance.Kalmia.Button`.
4. Le filet de 1,48:1 comme bordure de carte et piste du curseur : assez visible sur ton écran ? À régler :
   `colorOutlineVariant` dans `themes.xml`.
5. L'icône de lancement (clair, sombre, monochrome Android 13) et le logo de l'accueil.
6. Les champs de saisie gardent le soulignement Material par défaut (colorés par le thème) : à cadrer si le style
   éditorial l'exige.
7. Aucune capture n'a été produite ici (pas d'émulateur) ; les captures claires et sombres sont refaites à l'étape
   1.4 et en CI. Le test `ScreenshotTest` n'a pas changé et reste compilable.

## Issue #15, lot 1 — étape 1.4 : README, captures

### README (`android/README.md`)

Nouveau nom partout (plus aucune occurrence de « Dictée locale » dans
`android/README.md`, `README.md`, `app/src/main`) ; en-tête repris de la
formulation de l'issue ; tableau des plus-values ; origine des modèles dite
simplement (NVIDIA, Alibaba, OpenAI, MediaPipe de Google pour le LLM
facultatif) ; sections « Vos langues et le modèle recommandé » (avec la mesure
et ses limites : 6 extraits, 59 mots, non écoutés, hors téléphone) et
« Apparence ». Le `README.md` de la racine (serveur) ne cite pas l'ancien nom.
« Rien ne quitte le téléphone » est écrit « par défaut » : le serveur distant
facultatif existe. Le dépôt n'a pas de fichier de licence : le README dit
« code source dans ce dépôt » et non un nom de licence.

### Sources Google consultées (2026-11-12, lues par WebFetch)

1. `https://support.google.com/gboard/answer/11197787?hl=fr`, « Utiliser les
   fonctionnalités avancées de saisie vocale - Aide Gboard » : « Le texte dicté
   est conservé sur votre appareil. » ; pour les modifications détaillées par
   commande vocale, « la transcription de votre commande vocale et le texte du
   champ de saisie sont envoyés aux serveurs Google » et « Aucune donnée audio
   n'est envoyée. » ; « Corriger » est une exception ; outils d'écriture « sur
   l'appareil ». Sert à une seule affirmation : l'endroit du traitement dépend
   de la fonction.
2. `https://policies.google.com/privacy?hl=fr`, « Règles de confidentialité »,
   « À compter du 16 octobre 2026 », section « Informations collectées par
   Google » > « Votre activité » : « Contenus multimédias (comme des images, des
   fichiers, des contenus audio et des vidéos) issus de vos interactions ».
   Citée telle quelle, en précisant qu'elle vise les services de Google en
   général et ne dit pas que la saisie vocale en fait partie.

Les résumés viennent d'un outil de lecture de pages (citations recopiées par
lui, non vérifiées à la main) : les citations sont à relire à l'occasion.

### Volontairement non affirmé

Faute de source officielle lue qui le dise, le README n'écrit rien sur : la
précision de la saisie vocale de Google (français québécois compris), sa
vitesse, un mode hors ligne (la page Gboard lue n'en décrit pas), l'usage de la
voix à des fins publicitaires, la conservation de l'audio, un compte
obligatoire, ni « Google envoie votre voix à ses serveurs ». Pages essayées sans
résultat : `support.google.com/accounts/answer/10000041` (404) ; la page
`support.google.com/gboard/answer/2781851` ne dit rien du hors ligne ni de
l'envoi de l'audio. La section « Pourquoi pas la saisie vocale de Google ? »
décrit donc surtout ce que fait cette application.

### Captures (`ScreenshotTest`)

Noms conservés (`<écran>-<light|dark>.png`), manquants ajoutés. Fichiers
attendus dans `android/docs/screenshots/` (18) :
`keyboard-idle`, `keyboard-listening`, `keyboard-text`, `setup`,
`setup-languages`, `setup-models`, `settings`, `compare`, `diagnostic`, chacun
en `-light` et en `-dark` (soit 18 ; les captures existantes datent de
l'ancien thème et sont remplacées). L'écran de configuration est plus haut que
le téléphone : `setup` montre l'accueil et les étapes, `setup-languages` est
défilé sur l'étape « Vos langues », `setup-models` sur la carte Parakeet avec
son badge. Défilement par l'étiquette des vues (`scrollToTag`) ; aucun réseau,
aucun modèle téléchargé (le diagnostic s'ouvre non configuré). Non exécuté
ici (pas d'émulateur) : compilé par `assembleDebugAndroidTest`, exécuté en CI.

### Libellé de la recommandation

`quebecFrenchBasis` passe à `MESURE` ; la raison affichée devient « Parakeet
couvre vos langues et, mesuré sur six courts extraits, transcrit mieux le
français québécois (671 Mo, le plus léger). » (`RecommendationBasis.phrase`).
`ModelRecommendationTest` adapté.

### Release

Dans `.github/workflows/android.yml`, seuls le nom et le texte de la release
changent (« Kalmia Blabla », phrase de positionnement) ; commit séparé, car un
workflow peut être refusé au push.

## Issue #15, lot 1 — revue indépendante (1.R)

Revue faite sur le diff du lot (`origin/main...HEAD`) et les critères
d'acceptation de l'issue, sans les comptes rendus des étapes. Chaque problème
trouvé est corrigé dans le même commit, avec un test quand c'est vérifiable sur
la JVM.

### Critères d'acceptation du lot 1

| Critère | État | Preuve |
| --- | --- | --- |
| Compilation et CI vertes | rempli (local) ; CI : voir le rapport de la PR #16 | `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug` |
| Plus de « Dictée locale » dans les chaînes affichées ni le README | rempli | `grep` : restent seulement `DECISIONS.md` (historique), la vérification de `SetupLanguageTest` et le `-dname "CN=Dictee locale"` d'une commande `keytool` du README (nom interne d'un certificat, jamais affiché) |
| Tests de la table : couverture de chaque combinaison, Québec seul → Parakeet, jamais Parakeet hors de ses langues | rempli | `ModelRecommendationTest` (2 047 combinaisons) |
| Test instrumenté : accueil → langue → modèle marqué ; Réglages → recommandation changée | rempli après correction (échouait en CI, voir 12) ; exécuté en CI seulement | `SetupLanguageTest` |
| WER par modèle sur les extraits québécois dans `DECISIONS.md` | rempli | section « étape 1.2 » |
| Affirmations de l'accueil et du README vérifiables ; Google : lien officiel | rempli après correction (« code source ouvert », voir plus bas) | citations Google relues le 2026-11-12 |
| Couleurs = source Kalmia | rempli | `ThemeContrastTest.paletteIsTheKalmiaOneFromTheSource`, comparé à `design/marque/tokens.css` |
| Contraste ≥ 4,5 : 1 des paires utilisées, deux thèmes | rempli, couverture élargie | `ThemeContrastTest` |
| Aucune couleur hexadécimale ni police en dur (recherche en CI) | rempli, garde renforcée | `NoHardcodedStyleTest` (job « Build and unit tests ») |
| Aucune police téléchargeable | rempli | `NoHardcodedStyleTest.noDownloadableFontIsDeclared` |
| Captures claires et sombres régénérées | en CI (`ScreenshotTest`, artefact du job émulateur) | non vérifiable sans émulateur ici |

### Problèmes trouvés et corrigés

1. **Texte de toutes les vues sans couleur explicite dans la palette Material
   par défaut.** Le thème ne redéfinissait pas `android:textColorPrimary`,
   `textColorSecondary`, `textColorHint`, etc. : Material 3 les fait pointer vers
   `m3_default_color_primary_text` (encre violacée `#1D1B20` en clair, `#E6E0E9`
   en sombre). Or le corps de texte des écrans (`body()`), les cases à cocher
   des langues et les boutons radio ne fixent pas de couleur : ils n'étaient
   donc pas en encre Kalmia. Corrigé : ces rôles du cadre pointent vers
   `res/color/kalmia_text_primary.xml`, `kalmia_text_secondary.xml`,
   `kalmia_text_highlight.xml` (encre, atténué, vert translucide). Les douze
   rôles « fixes » de Material 1.12 (`colorPrimaryFixed`…) manquaient aussi ;
   ajoutés. Tests : `frameworkTextColoursAreKalmiaOnesNotTheMaterialDefaults`,
   liste des rôles complétée.
2. **« Le code source est ouvert »** (accueil, README, release) : le dépôt est
   public mais n'a **aucun fichier de licence** ; sans licence, le code se lit
   mais n'est pas un logiciel libre au sens juridique. Reformulé en « code
   source public : chacun peut le lire et le vérifier » ; le README le dit. Le
   choix d'une licence est remis à l'humain (ajouter un `LICENSE` rendrait
   « ouvert » exact).
3. **Raison de la recommandation plus forte que la mesure.** « transcrit
   mieux le français québécois » alors que l'étape 1.2 conclut que Whisper est à
   une erreur de Parakeet une fois l'artefact « 18 » retiré. Nouvelle raison :
   « … En français québécois, il a fait le moins d'erreurs des trois, mesuré sur
   six courts extraits (59 mots) : un indice, pas une preuve. » Test
   `quebecReasonStatesTheScopeOfTheMeasure`. Le README cite cette phrase.
4. **Nom de langue mis en minuscules en entier** (`label.lowercase()`) : aurait
   donné « français (québec) ». Remplacé par `DictationLanguage.inSentence`
   (première lettre seulement). Test `languageNamesInSentencesKeepTheirProperNouns`.
5. **« L'erreur reste rouge »** n'était vrai qu'au clavier, dans les
   avertissements et le diagnostic : l'échec d'un téléchargement (accueil,
   modèles LLM des Réglages), l'erreur de la comparaison et l'échec du test de
   connexion au serveur restaient en couleur de texte normale. Nouvel
   utilitaire `TextView.markError()` (`ui/Ui.kt`) : couleur d'erreur + icône
   `ic_error`, ou retour à l'encre sans icône ; utilisé partout où un échec
   s'affiche.
6. **Le clavier ne suivait pas le réglage Système / Clair / Sombre de
   l'application** : la vue n'était reconstruite qu'au changement de
   configuration du système (ou par les tests). `DicteeImeService` mémorise le
   réglage de la vue construite et la reconstruit dans `onStartInputView` s'il
   a changé.
7. **Zones tactiles des touches** : l'écart entre touches était une marge
   externe (3 dp de chaque côté), donc hors de la zone tactile. Il devient une
   marge interne (`insetLeft`/`insetRight`) : la zone tactile couvre toute la
   case. Test `keySymbolsAreAtLeast20spAndKeysAtLeast48dpHigh` (hauteur ≥ 48 dp,
   symboles ≥ 20 sp, pas de marge externe). **Limite non corrigée** : huit
   touches sur une rangée ne peuvent pas faire 48 dp de large sur un téléphone
   de 360 à 412 dp (une case simple fait environ 36 à 40 dp, espace et
   effacement plus) ; tenir 48 dp exigerait deux rangées ou moins de touches,
   un changement de disposition hors de la portée d'une revue. Remis à l'humain.
8. **Langue du serveur affichée en retard dans les Réglages** : cocher une
   langue en haut de l'écran changeait la valeur par défaut de la langue du
   serveur sans mettre à jour son bouton radio. Le bouton suit maintenant
   (`checkSilently`, sans enregistrer la valeur comme choix explicite).
9. **Garde `NoHardcodedStyleTest` contournable** : seuls `textColor`,
   `background`, `tint`, `fontFamily`, `typeface` en `android:` étaient
   contrôlés dans les mises en page (`app:backgroundTint="@android:color/black"`
   passait) ; aucune vérification de `@android:color/` hors des mises en page,
   ni des apparences de texte du système (police du système), ni de
   `0xRRGGBB`, `Typeface.Builder`, `setTypeface`. Élargi ; une ligne morte
   retirée. Vérifié en injectant `app:backgroundTint="@android:color/black"`
   dans `keyboard_view.xml` : le test échoue, puis passe une fois retiré.
10. **`ThemeContrastTest` ne reliait pas les paires mesurées aux composants.**
    Ajouts : chaque liste d'états de `res/color` n'emploie que des rôles du
    thème ou la palette ; le texte des boutons d'action, l'icône du micro au
    repos et à l'écoute, et le texte des touches et boutons à contour
    correspondent à des paires mesurées.
11. Citation de la politique de Google : majuscule rétablie (« Contenus
    multimédias… », mot pour mot). Commentaire de `SherpaEngine` mis à jour
    (la langue de Whisper n'est plus toujours vide).

12. **CI rouge : `SetupLanguageTest.changingTheLanguagesInTheSettingsChangesTheRecommendation`**
    (NPE dans `ActivityScenario.recreate`). Cause racine : `ActivityScenario`
    (androidx.test core 1.6.1, `InstrumentationActivityInvoker`) démarre chaque
    activité avec `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK` (valeur
    `0x10008000` relevée dans le bytecode) : lancer le scénario des Réglages
    pendant que celui de la configuration était ouvert **détruisait**
    l'activité de configuration, et `setup.recreate()` n'avait plus d'activité.
    Corrigé sans sauter le test : les scénarios se suivent (configuration →
    Réglages → configuration, la recommandation passe à Qwen3-ASR), et un
    nouveau test, `setupRedrawsOnResumeAfterTheLanguagesChanged`, couvre le vrai
    chemin « l'écran reste dessous puis revient » par
    `moveToState(CREATED)` / `moveToState(RESUMED)`. Aide `root()` inutilisée
    retirée.
13. **Captures claires des écrans en paysage** (2400x1080 en CI, les sombres et
    celles du clavier en portrait) : une rotation laissée par un test précédent
    n'était pas finie, et `setOrientationNatural()` suivi d'une pause fixe ne
    l'attendait pas. `ScreenshotTest` coupe la rotation automatique et fixe la
    rotation utilisateur à 0 par `settings put system …` (valeur rétablie
    après le test), gèle la rotation, puis **attend** que l'écran soit en
    portrait (rotation 0, hauteur > largeur) et que l'activité capturée ait une
    configuration portrait ; chaque capture vérifie ensuite que l'image est
    plus haute que large, sinon le test échoue. Les captures sont écrites à
    l'échelle 0,5 (540x1200 sur l'émulateur Pixel 6, comme celles du dépôt).

### Vérifié sans changement

- **Citations Google** relues le 2026-11-12 : « Le texte dicté est conservé sur
  votre appareil. », « la transcription de votre commande vocale et le texte du
  champ de saisie sont envoyés aux serveurs Google » et « Aucune donnée audio
  n'est envoyée. » figurent mot pour mot sur la page Gboard 11197787 ; « À
  compter du 16 octobre 2026 » et l'élément « Contenus multimédias (…) issus de
  vos interactions » sur policies.google.com/privacy.
- **Langues** : la sélection n'est jamais vide (`parse` retombe sur le Québec,
  l'écran refuse de décocher la dernière) ; Whisper reçoit la langue seulement
  si un seul code est choisi (Québec + France = `fr`) ; `remoteLanguage` suit
  les langues tant qu'aucune valeur n'est enregistrée, et une valeur explicite
  (même « détection automatique ») n'est jamais écrasée ; l'ouverture des
  Réglages n'enregistre rien (l'écouteur du bouton radio est posé après le
  premier `check`). Le moteur est rechargé quand la langue change, parce que
  `EngineOptions` (qui porte la langue) fait partie de la clé de
  `EngineManager`. Seul Whisper la reçoit : changer de langue ne recharge pas
  Parakeet ni Qwen3.
- **Comparer les modèles** mesure Whisper en détection automatique
  (`engineOptions(remote)`, langue vide) : voulu, les extraits mêlent anglais et
  français ; le WER de la comparaison n'est donc pas celui de Whisper à langue
  forcée.
- **Vie privée** : aucune permission ajoutée au manifeste, aucune nouvelle
  requête réseau (polices embarquées, aucun fournisseur de polices).
- **Risques de plantage relus** (personne n'a lancé l'application) : tous les
  attributs du thème existent dans Material 1.12 (l'assemblage échouerait
  sinon) ; styles parents existants ; `textFontWeight` et
  `fontVariationSettings` disponibles (minSdk 29) ; axes `wght` et `opsz` de
  Newsreader présents ; vecteurs valides (lint et AAPT passent) ; les icônes à
  `@android:color/white` sont teintées par le thème ou par `iconTint`.

### À vérifier par un humain

- Rendu réel du texte en encre Kalmia après la correction 1 (captures de la
  CI, puis téléphone), clair et sombre.
- Changer Clair / Sombre dans les Réglages, puis rouvrir le clavier dans une
  autre application : il doit suivre (correction 6).
- Largeur des touches (environ 40 dp) : assez confortable au pouce ? Sinon,
  passer à deux rangées.
- Choisir une licence pour le dépôt si « code source ouvert » doit être écrit.

## Issue #15, lot 2 — étape 2.1 : formulations des dictées d'Apple et de Google

Date de consultation de toutes les sources : 2026-11-12. Aucun code touché.

**Ce qui est « mot pour mot » et ce qui ne l'est pas.** Les pages d'Apple, de
Google et de l'OQLF ont été téléchargées en HTML et leur texte extrait
mécaniquement (aucun résumé d'outil) : les formulations des tableaux ci-dessous
sont donc celles des pages. Les résumés produits par l'outil de lecture ont été
écartés, sauf pour la lecture initiale. Pour l'usage de France, la seule source
lue en entier est un guide du CNRS de 1995 (un document de référence
typographique, pas l'Imprimerie nationale) ; le *Lexique des règles typographiques
en usage à l'Imprimerie nationale* est un livre et n'a pas pu être consulté
(voir « Non vérifié »). Les propositions de la partie 2 sont des choix de
conception, pas des citations.

### 1. Formulations relevées

Convention : le texte entre guillemets est celui de la page ; « non documenté »
signifie que la page ne liste pas cette commande.

| Signe | Apple FR | Apple EN | Google FR | Google EN |
| --- | --- | --- | --- | --- |
| Guillemet ouvrant | Mac fr-afri et fr-fr : « guillemet ouvrant » ; Mac fr-ca : « guillemet » ; iPhone (fr-afri, fr-ca, fr-fr) : « Guillemets » | Mac et iPhone : « quote » | non documenté | non documenté |
| Guillemet fermant | Mac (fr-afri, fr-ca, fr-fr) : « guillemet fermant » ; iPhone : « Guillemets fermants » | Mac et iPhone : « end quote » | non documenté | non documenté |
| Deux-points | Mac : « deux-points » ; iPhone : « Deux points » (sans trait d'union) | « colon » | non documenté | non documenté |
| Point-virgule | « point-virgule » (Mac et iPhone) | « semicolon » | non documenté | non documenté |
| Parenthèse ouvrante | « parenthèse ouvrante » (Mac et iPhone) | « open parenthesis » | non documenté | non documenté |
| Parenthèse fermante | « parenthèse fermante » (Mac et iPhone) | « close parenthesis » | non documenté | non documenté |
| Point d'interrogation | « point d’interrogation » | « question mark » | « Point d'interrogation » (liste de la page Docs et de l'aide Gboard FR) | « Question mark » (Docs, Gboard) |
| Point d'exclamation | « point d’exclamation » | Mac : « exclamation mark » ; iPhone : « Exclamation point » | « Point d'exclamation » (Docs, Gboard FR) | « Exclamation point » (Docs, Gboard) |
| Nouvelle ligne | Mac : « nouvelle ligne » ; iPhone (fr-afri, fr-ca, fr-fr) : « À la ligne » | « new line » (Mac et iPhone) | « Nouvelle ligne » (Docs, Gboard FR) | « New line » (Docs, Gboard) |
| Nouveau paragraphe | « nouveau paragraphe » (Mac et iPhone) | « new paragraph » (Mac et iPhone) | « Nouveau paragraphe » (Docs, Gboard FR) | « New paragraph » (Docs, Gboard) |
| *(info)* Virgule | « virgule » | « comma » | « Virgule » (Docs, Gboard FR) | « Comma » |
| *(info)* Point | « point » | Mac : « period/point/dot/full stop » ; iPhone : « Period » | Docs FR : « Période » ; Gboard FR : « Point » | « Period » |

Notes qui changent le sens du tableau :

- **Google FR : les commandes de Google Docs ne sont pas des commandes
  françaises.** La page française dit : « Les commandes vocales sont disponibles
  en anglais uniquement. Vous devez choisir l'anglais comme langue du document et
  du compte. » La liste française (« Période », « Virgule »…) est la
  traduction de la liste anglaise, pas ce qu'on dicte. La version anglaise dit
  « Voice commands are available only in English. The account language and
  document language must both be English. » Les deux pages ajoutent que la
  ponctuation « might not be available in every language ». Pour Google, il n'y
  a donc **aucune source française** à reprendre ; la colonne ne décrit que ce que la page affiche.
- **Google ne documente ni guillemets, ni deux-points, ni point-virgule, ni
  parenthèses** (Docs et Gboard, FR et EN). Seuls six éléments sont listés : period, comma,
  exclamation point, question mark, new line, new paragraph. Gboard introduit sa
  liste par « Some phrases you can use are » / « Voici quelques expressions que
  vous pouvez utiliser », donc la liste n'est pas présentée comme exhaustive ;
  on ne peut pas en déduire que le reste est refusé.
- **Apple Mac, variantes régionales.** fr-afri, fr-fr et fr-ca donnent les
  mêmes commandes, sauf : fr-ca appelle l'ouvrant simplement « guillemet »
  (le fermant reste « guillemet fermant ») et écrit « arobas » (hors liste).
  La page fr-afri (l'URL de l'issue) a « guillemet ouvrant ».
- **Apple iPhone** (page « Commandes de dictée de texte » de l'iPhone,
  `iph3bf19d7b9`) : « Guillemets » / « Guillemets fermants » (pluriel), « Deux points »,
  « À la ligne ». Cette page iPhone et la page Mac ne s'accordent donc pas
  sur le même mot (Mac : « nouvelle ligne », iPhone : « À la ligne »). La page
  iPhone « Dicter du texte » écrit : « Dites "nouvelle ligne" ou "nouveau
  paragraphe" » : les deux sont donc comprises.
- Les pages d'Apple signalent que, pour certaines langues, la dictée ajoute
  automatiquement une partie de la ponctuation (« Pour certaines langues, Dictée
  ajoute automatiquement certains signes de ponctuation »).
- Le tableau Mac contient aussi, hors liste initiale : « guillemet anglais simple
  ouvrant / fermant », « apostrophe », « crochet ouvrant / fermant », « points de
  suspension », « tiret », « trait d'union », « arobase », « barre oblique », etc.
  Rien de cela n'est repris : l'issue interdit d'ajouter des signes.

Sources (toutes consultées le 2026-11-12) :

- Apple, Mac : https://support.apple.com/fr-afri/guide/mac-help/mh40695/mac ,
  https://support.apple.com/fr-ca/guide/mac-help/mh40695/mac ,
  https://support.apple.com/fr-fr/guide/mac-help/mh40695/mac ,
  https://support.apple.com/en-us/guide/mac-help/mh40695/mac
- Apple, iPhone : https://support.apple.com/fr-ca/guide/iphone/iph3bf19d7b9/ios ,
  https://support.apple.com/fr-afri/guide/iphone/iph3bf19d7b9/ios ,
  https://support.apple.com/fr-fr/guide/iphone/iph3bf19d7b9/ios ,
  https://support.apple.com/en-us/guide/iphone/iph3bf19d7b9/ios ,
  https://support.apple.com/fr-afri/guide/iphone/dictate-text-iph2c0651d2/ios
- Google Docs : https://support.google.com/docs/answer/4492226?hl=fr et `?hl=en`
  (section « Ajouter des signes de ponctuation » / « Add punctuation »)
- Gboard : https://support.google.com/gboard/answer/2781851?hl=fr et `?hl=en`

### 2. Formulations à accepter (proposition pour l'étape 2.2)

Dans la liste initiale de l'issue seulement. « Source » dit d'où vient la forme :
A = page d'Apple, G = page de Google, U = usage courant ajouté pour la tolérance
(non sourcé, à confirmer par les tests de phrases pièges). Le français d'abord ;
chaque forme s'accepte avec l'article et l'impératif courants (« ouvrir / ouvrez /
ouvre »).

| Signe | Forme principale | Variantes à accepter | Source / remarque |
| --- | --- | --- | --- |
| Guillemet ouvrant `«` / `“` | « ouvrir les guillemets » | « ouvrez / ouvre les guillemets », « ouvrir guillemet(s) », « guillemet ouvrant », « guillemets ouvrants », « guillemet(s) ouvert(s) » | « guillemet ouvrant » : A (Mac). Les formes avec « ouvrir » : U (issue). **Ne pas accepter « guillemet(s) » seul** pour ouvrir, bien qu'Apple (iPhone, Mac fr-ca) le fasse : c'est le mot le plus exposé aux faux positifs (« il a dit guillemets… »). |
| Guillemet fermant `»` / `”` | « fermer les guillemets » | « fermez / ferme les guillemets », « fermer guillemet(s) », « guillemet fermant », « guillemets fermants », « guillemet(s) fermé(s) » | « guillemet(s) fermant(s) » : A. Autres : U. |
| Deux-points `:` | « deux-points » | « deux points » | A (Mac, iPhone). Variante sans trait d'union nécessaire : la transcription l'écrit des deux façons. |
| Point-virgule `;` | « point-virgule » | « point virgule » | A. |
| Parenthèse ouvrante `(` | « ouvrir la parenthèse » | « ouvrez / ouvre la parenthèse », « ouvrir parenthèse », « parenthèse ouvrante » | « parenthèse ouvrante » : A. « ouvrir la parenthèse » : U (issue). |
| Parenthèse fermante `)` | « fermer la parenthèse » | « fermez / ferme la parenthèse », « fermer parenthèse », « parenthèse fermante » | idem. |
| Point d'interrogation `?` | « point d'interrogation » | (aucune) | A, G. |
| Point d'exclamation `!` | « point d'exclamation » | (aucune) | A, G. |
| Nouvelle ligne | « nouvelle ligne » | « à la ligne » | « nouvelle ligne » : A (Mac), G ; « à la ligne » : A (iPhone). **Ne pas ajouter « retour à la ligne »** sans test : pas dans les sources. |
| Nouveau paragraphe | « nouveau paragraphe » | (aucune) | A, G. |

| Signe | Forme principale (EN) | Variantes à accepter (EN) | Source / remarque |
| --- | --- | --- | --- |
| Open quote `“` | « open quote » | « open quotes », « begin quote », « begin quotes », « quote » **seul seulement en début ou fin de segment** | « quote » : A, mais très exposé aux faux positifs ; « open quote » / « begin quote » : U. |
| Close quote `”` | « close quote » | « close quotes », « end quote », « end quotes », « unquote » | « end quote » : A ; « unquote » : U (non sourcé). |
| Colon `:` | « colon » | (aucune) | A. |
| Semicolon `;` | « semicolon » | « semi colon » | A. |
| Open parenthesis `(` | « open parenthesis » | « open paren », « open parentheses » | A ; autres : U. |
| Close parenthesis `)` | « close parenthesis » | « close paren », « close parentheses » | idem. |
| Question mark `?` | « question mark » | (aucune) | A, G. |
| Exclamation mark `!` | « exclamation mark » | « exclamation point » | « exclamation mark » : A (Mac) ; « exclamation point » : A (iPhone), G. |
| New line | « new line » | « newline » | A, G ; « newline » : U. |
| New paragraph | « new paragraph » | (aucune) | A, G. |

Notes de conception pour 2.2 :

- Virgule et point restent hors liste (règle de l'issue). Leurs formulations
  sont consignées ci-dessus pour mémoire seulement.
- Les formes les plus à risque (« guillemet(s) » seul, « quote » seul, « point
  virgule ») sont à protéger par les règles de bord de segment et la liste de
  phrases pièges de l'issue (« j'ai marqué deux points », « c'est un point
  d'interrogation pour moi »).
- Google FR n'offrant aucune commande française, l'alignement sur les
  habitudes prises ailleurs en français se fait sur Apple seulement ; les
  six commandes que Google comprend en anglais coïncident avec celles d'Apple.

### 3. Règle d'espacement typographique (à vérifier pour 2.2)

**OQLF — confirmée, avec une précision.** Source :
Vitrine linguistique, « Espacement avant et après les signes de ponctuation et
les symboles » (dernière mise à jour affichée : 2024),
https://vitrinelinguistique.oqlf.gouv.qc.ca/22039/la-typographie/espacement/espacement-avant-et-apres-les-signes-de-ponctuation-et-les-symboles .
Texte de la page : « L'Office québécois de la langue française opte pour
l'absence d'espace devant le point-virgule, le point d'exclamation et le point
d'interrogation. Cette pratique, qui est répandue dans l'usage, tend à devenir
une convention, notamment parce que certains logiciels ne permettent pas
d'insérer l'espace fine (espace insécable réduite) de façon conviviale […]. Cela
dit, on peut opter pour l'espace fine lorsqu'elle est disponible. » Le tableau :

| Signe | Avant | Après |
| --- | --- | --- |
| `:` | une espace insécable | une espace (sécable) |
| `:` dans une heure (13:52:45) | pas d'espace | pas d'espace |
| `;` | pas d'espace ou une espace fine | une espace |
| `?` | pas d'espace ou une espace fine | une espace |
| `!` | pas d'espace ou une espace fine | une espace |
| `«` | une espace | une espace insécable |
| `»` | une espace insécable | une espace |
| `(` | une espace | pas d'espace |
| `)` | pas d'espace | une espace |

Exemples de la page : « Les étapes sont les suivantes␣: planification… »,
« un "remède miracle␣"␣: un morceau de gingembre! » (␣ = espace insécable).
La page précise aussi : « On ne met pas d'espace insécable entre une apostrophe
et un guillemet ouvrant » et « Les mêmes règles typographiques s'appliquent pour
les guillemets anglais simples […], les guillemets droits doubles ».

Verdict sur la règle citée de mémoire dans l'issue : **exacte** (rien devant
`;` `?` `!` ; espace insécable devant `:` ; espaces insécables à l'intérieur des
« »). Deux précisions à reporter dans 2.2 : (a) l'espace fine est **permise**
au Québec, pas exigée : « rien » est la valeur par défaut, et mettre une espace
fine insécable (U+202F) avant `;` `?` `!` reste conforme ; (b) le tableau
distingue l'espace **sécable** après le signe (`:` `;` `?` `!`) de l'espace
**insécable** à l'intérieur des guillemets, et prévoit l'espace sécable avant `«`
et après `»`. Le `:` ne prend rien dans une heure (« 13:52 ») : un risque de
faux espacement si le `:` est produit par une commande vocale entre deux chiffres ;
à tester. Il n'y a pas de règle pour les parenthèses dans la partie de
l'issue : le tableau dit pas d'espace après `(` ni avant `)`.

**Usage de France — confirmé par une source reconnue de second rang, pas par
l'Imprimerie nationale elle-même.**

- Guide « Règles typographiques de base » du CNRS (délégation aux systèmes
  d'information, 25 novembre 1995, version 1.1),
  https://www.pharmacie.universite-paris-saclay.fr/sites/default/files/media/2020-09/regles-typographie_cnrs.pdf :
  `:` `;` `!` `?` : « espace insécable » avant, « espace sécable » après ;
  `«` : espace sécable avant, espace insécable après ; `»` : espace insécable
  avant, espace sécable après. Le guide ne parle pas d'espace fine.
- Wikipédia, « Espace (typographie) »
  (https://fr.wikipedia.org/wiki/Espace_(typographie)), qui cite le *Lexique de
  l'Imprimerie nationale* (p. 51, 145-149) : l'espace fine insécable (U+202F) est
  la forme « requise en français » devant le point-virgule, le point d'exclamation
  et le point d'interrogation, et le deux-points dans certaines marches
  typographiques ; « En France et dans la plupart des pays francophones d'Europe,
  on a pris l'habitude de remplacer l'espace fine insécable par une espace mots
  insécable » ; l'usage canadien est décrit comme « l'absence d'espace […] excepté
  devant le deux-points et avec les guillemets, où c'est l'espace mots insécable
  qui est la norme », ce qui concorde avec l'OQLF. La page note aussi que
  « l'usage dans la plupart des entreprises de l'industrie graphique française
  toutefois est d'utiliser l'espace fine insécable aussi avec le deux-points ».
  Source de second rang : à lire comme un témoignage de l'usage, pas comme une
  norme.

Règle proposée à implémenter (point de départ pour 2.2, à valider par tests) :

| Locale | `;` `?` `!` | `:` | `«` … `»` | Anglais |
| --- | --- | --- | --- | --- |
| Québec (OQLF) | rien devant (option : U+202F) ; espace sécable après | U+00A0 devant ; espace après | `«` + U+00A0 + texte + U+00A0 + `»` | s.o. |
| France | U+202F (ou U+00A0 à défaut) devant ; espace après | U+00A0 (ou U+202F) devant ; espace après | `«` + U+00A0 + texte + U+00A0 + `»` | s.o. |
| Anglais | rien devant, espace après | rien devant, espace après | `“`…`”` sans espace intérieure | s.o. |

### Non vérifié

- **Lexique des règles typographiques en usage à l'Imprimerie nationale** :
  livre, pas de version en ligne trouvée ; l'usage de France n'est confirmé que par
  un guide du CNRS (1995) et par Wikipédia qui cite le Lexique. Si une valeur
  exacte du Lexique est exigée (espace fine insécable ou espace insécable avant
  `;` `?` `!`), elle reste à lire dans l'ouvrage.
- **Google, commandes françaises** : aucune page officielle consultée ne documente
  de commande vocale en français pour Docs ; l'aide Gboard française liste six
  éléments mais le comportement réel de la saisie vocale de Gboard en français
  (si elle comprend « ouvrir les guillemets ») n'a pas été testé.
- **Rendu réel** des commandes sur un iPhone ou un téléphone Android : non
  essayé ; les formulations viennent des pages d'aide, pas d'un essai.
- Les pages iPhone fr-afri et fr-fr ont été lues pour le tableau des
  commandes ; leur page « Dicter du texte » (iph2c0651d2) n'a été lue que sur
  la version fr-afri.
- Les formes marquées U ne sont pas tirées d'une source officielle.

## Issue #15, lot 2 — étape 2.2 : commandes de ponctuation et typographie

Code : `text/CommandTable.kt` (nouveau), `text/VoiceCommands.kt`, `text/TextAssembler.kt`,
`dictation/TextPipeline.kt`, `DicteeApp.kt` (langues transmises au pipeline), texte d'aide
du réglage « Commandes vocales ». Liste initiale de l'issue seulement, sans ajout de signe
(commentaire « Mode d'exécution »).

### Table unique

`CommandTable.entries` décrit chaque commande une fois par langue : `Command` (signe ou
action, avec son `CommandKind` : signe ouvrant, signe fermant, mise en forme, effacement),
`CommandLanguage` (FRENCH, ENGLISH), les formulations (`CommandForm` : texte affichable,
sources A/G/U, `Strictness`), ce qui est produit (`produces`) et un exemple
`CommandExample(said, result, before)`. La première formulation est la forme principale à
afficher. L'analyse (`VoiceCommands.parse`) ne lit que cette table ; un test vérifie que
chaque exemple donne exactement son résultat dans le pipeline, pour que l'écran « Comment
dicter » de l'étape 2.3 (à générer depuis `CommandTable.active(langues)`) ne puisse pas
diverger. Les commandes existantes (nouvelle ligne, nouveau paragraphe, effacements) y
sont migrées avec toutes leurs formes, y compris « retour à la ligne », « next line »,
« next paragraph » et « scratch that », qui existaient avant 2.1 (marquées U).

Formulations retenues (source entre parenthèses, voir l'étape 2.1) :

| Commande | Français | Anglais |
| --- | --- | --- |
| `«` / `“` | ouvrir / ouvrez / ouvre les guillemets, ouvrir guillemet(s) (U) ; guillemet ouvrant, guillemets ouvrants (A) ; guillemet(s) ouvert(s) (U) | open quote(s), begin quote(s) (U) |
| `»` / `”` | fermer / fermez / ferme les guillemets, fermer guillemet(s) (U) ; guillemet fermant, guillemets fermants (A) ; guillemet(s) fermé(s) (U) | end quote (A) ; close quote(s), end quotes (U) |
| `:` | deux-points ; deux points, sans trait d'union (A, iPhone), traité comme homographe | colon (A), homographe |
| `;` | point-virgule (A) ; « point virgule » est reconnu aussi (le trait d'union est ignoré à la comparaison) | semicolon (A), semi colon (U) |
| `(` | ouvrir / ouvrez / ouvre la parenthèse, ouvrir parenthèse (U) ; parenthèse ouvrante (A) | open parenthesis (A) ; open parentheses, open paren (U) |
| `)` | fermer / fermez / ferme la parenthèse, fermer parenthèse (U) ; parenthèse fermante (A) | close parenthesis (A) ; close parentheses, close paren (U) |
| `?` | point d'interrogation (A, G) | question mark (A, G) |
| `!` | point d'exclamation (A, G) | exclamation mark (A), exclamation point (A, G) |
| nouvelle ligne | nouvelle ligne (A, G), à la ligne (A), retour à la ligne (U) | new line (A, G), newline, next line (U) |
| nouveau paragraphe | nouveau paragraphe (A, G) | new paragraph (A, G), next paragraph (U) |
| effacements | efface / effacer / effacez / supprime / supprimer / supprimez la dernière phrase, … le dernier mot (U) | delete (the) last sentence, scratch that, delete (the) last word (U) |

Écartées, comme le proposait 2.1 : « guillemet(s) » seul (Apple fr-ca, iPhone) et « quote »
seul (Apple EN), trop exposés aux faux positifs ; « unquote » (non sourcé, et « quote
unquote » est une expression).

### Langues actives

- Français (Québec ou France) → formulations françaises ; anglais → anglaises ; les deux si
  les deux sont choisis. Une formulation anglaise dans une dictée « français seulement »
  reste du texte.
- **Autres langues seulement** (espagnol, etc.) : aucune commande de ponctuation (ce
  seraient des mots français ou anglais au milieu d'une autre langue) ; les commandes de
  mise en forme et d'effacement FR et EN restent actives, comme avant 2.2. Choix prudent :
  aucun comportement existant ne disparaît, rien de nouveau ne s'ajoute.
- Le réglage « Commandes vocales » désactivé coupe tout, comme avant.

### Règle de déclenchement

En deux phrases : **une formulation n'est une commande que si le mot qui la précède n'en
fait pas un nom ou un verbe ordinaire (déterminant, nombre, possessif, préposition, verbe
modal ; un adjectif courant comme « gros » est sauté) et si le mot qui la suit ne prolonge
pas un groupe nominal (« pour », « de », « qui »…). Les homographes (« deux points » sans
trait d'union, « colon ») doivent en plus être détachés des deux côtés (ponctuation ou bord
de segment) ou suivis d'une autre commande.**

Détails :

- Les mots d'une formulation doivent se suivre, séparés seulement par des espaces ou des
  traits d'union (« point, virgule » n'est pas « point-virgule »). Accents, majuscules et
  traits d'union sont ignorés à la comparaison ; « deux-points » écrit avec trait d'union
  par le modèle est la forme normale, « deux points » sans trait d'union la forme homographe.
- Une commande voisine acceptée compte comme une ponctuation (calcul itératif jusqu'à
  stabilité). C'est ce qui fait marcher l'exemple de l'issue en plein milieu de phrase, sans
  aucune ponctuation du modèle : « ouvrir les guillemets » est accepté (précédé de
  « points », suivi de « bonjour »), donc « deux points » est suivi d'une commande et
  l'est aussi.
- Mise en forme (nouvelle ligne, nouveau paragraphe) : règle d'avant 2.2 inchangée,
  ponctuation, bord de segment ou commande des deux côtés. Effacements : segment entier.
- Un guillemet ou une parenthèse fermante sans ouvrant correspondant dans le texte avant le
  curseur, ou un signe fermant en tout début de champ ou de ligne, n'est pas inséré : les
  mots dits sont tapés à la place.
- Pourquoi pas la règle d'avant (ponctuation des deux côtés) pour tout : l'exemple de
  l'issue n'a aucune ponctuation, et les modèles ne mettent pas de virgules fiables autour
  des noms de signes. Pourquoi les mots voisins plutôt qu'un mot-clé d'activation : l'issue
  l'exclut (« la commande est le nom du signe »). Les faux positifs observés dans les
  phrases pièges viennent presque tous d'un déterminant ou d'un nombre juste avant.
- Dans le doute, pas de commande : « il a dit deux points, bonjour » (sans trait d'union,
  précédé d'un verbe, suivi de texte) reste du texte, parce qu'il a la même forme que
  « j'ai marqué deux points, puis… ».

Phrases pièges couvertes par `FalsePositiveTest` (aucune commande) : 21 en français
(« J'ai marqué deux points. », « C'est un point d'interrogation pour moi. », « Il a ouvert
les guillemets. », « Il faut ouvrir les guillemets ici. », « Ça reste un gros point
d'interrogation. », « Il y a deux points de vue. », « Il a dit point, virgule, et rien
d'autre. »…) et 13 en anglais (« That's a question mark for me. », « My colon hurts. »,
« Colon cancer screening saves lives. », « I love that quote. », « Open quotes on the
website are free. »…).

### Doublons avec la ponctuation du modèle

- **Avant le signe dicté** : le signe remplace la marque du modèle juste avant lui, dans le
  même segment ou à la fin du texte déjà inséré : le même signe, ou une virgule, un point,
  un deux-points, un point-virgule qui ne marquait qu'une pause (« C'est vrai. » puis
  « Point d'interrogation. » → `C'est vrai?`). Pour `»` et `)`, le point, `?` et `!`
  avant le signe sont gardés (`« Je viens. »` est correct). Des points de suspension ne
  sont jamais retirés.
- **Après le signe dicté** : la ponctuation que le modèle met juste après la commande est
  retirée (même signe, virgule, point, deux-points, point-virgule) ; après `»` et `)`,
  seul le même signe est retiré (`« bonjour », dit-il` garde sa virgule).
- Un segment qui ne contient que des commandes perd la ponctuation du modèle autour
  d'elles (« Fermer les guillemets. » dit seul ne laisse pas de point).

### Typographie (`TextAssembler.sign`)

| Variante | `:` | `;` `?` `!` | guillemets | `(` `)` |
| --- | --- | --- | --- | --- |
| Québec (OQLF) | espace insécable avant | rien avant | `«` + insécable … insécable + `»` | espace avant `(`, rien à l'intérieur |
| France | espace insécable avant | espace insécable avant | `«` + insécable … insécable + `»` | idem |
| Anglais | rien avant | rien avant | `“…”` sans espace intérieure | idem |

- **Quelle variante** : la langue de la formulation dite décide français ou anglais (dans
  une dictée bilingue, « question mark » est typographié à l'anglaise et « point
  d'interrogation » à la française). Pour le français : France seulement si « Français
  (France) » est choisi sans « Français (Québec) » ; sinon Québec, y compris quand les deux
  sont choisis (la règle de l'OQLF ajoute moins d'espaces, et son absence d'espace devant
  `;` `?` `!` est répandue en France aussi).
- **Espace insécable U+00A0, pas l'espace fine U+202F**, y compris pour la France : le texte
  s'affiche dans la police de l'application hôte, que nous ne contrôlons pas, et U+00A0
  existe dans toutes les polices ; U+202F est absente de certaines (carré vide). L'espace
  fine reste permise par l'OQLF et le Lexique, pas exigée.
- **Seuls les signes dictés sont typographiés.** La ponctuation produite par le modèle est
  insérée telle quelle : la modifier toucherait tout le texte dicté, y compris les heures
  (13:52), les adresses, les rapports et les phrases anglaises d'une dictée bilingue.
- Heure dictée : un `:` dicté entre deux chiffres (« 13 deux-points 52 ») est collé
  (« 13:52 ») ; une heure écrite par le modèle n'est jamais touchée.
- Pas d'espace entre une apostrophe et un guillemet ouvrant (`l'« outil »`), règle de
  l'OQLF relevée en 2.1.
- Majuscule après `?` et `!` (fin de phrase), pas après `:` ni `;`. Après un guillemet ou
  une parenthèse ouvrante en début de phrase, la phrase commence encore : majuscule
  (`« Bonjour »`). Pas de double espace : les espaces avant un signe fermant sont
  retirés, une seule espace (sécable) suit.

### Limites connues

- « deux points » sans trait d'union suivi d'un mot ordinaire (« il a dit deux points
  bonjour ») n'est pas reconnu : c'est le prix du « dans le doute, pas de commande ». La
  forme avec trait d'union, une pause après la commande ou une commande juste après
  (« ouvrir les guillemets ») la font reconnaître. Ce que chaque modèle écrit réellement
  (« deux-points » ou « deux points ») est à vérifier en dictant.
- Les listes de mots bloquants sont courtes et écrites à la main ; une phrase ordinaire
  sans déterminant (« Ouvrir les guillemets, c'est facile. ») peut déclencher une commande.
- Une commande coupée par une pause au milieu (« point » / « d'interrogation » dans deux
  segments) n'est pas reconnue.
- Le modèle peut lui-même transformer « deux-points » en `:` ou « point d'interrogation »
  en `?` : alors aucune commande n'est vue et sa ponctuation reste telle quelle (sans
  insécable).
- Le texte provisoire (soulignement pendant la parole) montre les mots dits ; la
  commande ne s'applique qu'au texte final.
- Mode serveur : non vérifié ici (étape 2.4).

### Tests

`TextTest.kt` : `PunctuationCommandsTest` (exemple de l'issue de bout en bout dans le
pipeline, en un segment avec et sans trait d'union, avec la ponctuation du modèle et en
plusieurs segments ; chaque commande FR et EN ; langues actives ; autres langues ; réglage
désactivé ; fermant sans ouvrant ; heure), `FalsePositiveTest` (34 phrases pièges),
`DuplicateSignTest`, `TypographyTest` (Québec, France, les deux, anglais, apostrophe,
majuscules, doubles espaces, heure du modèle intacte, ponctuation du modèle non
typographiée, `SignEdit` unitaire), `CommandTableTest` (couverture exacte de la liste,
langues actives, aucune formulation partagée, chaque forme reconnue seule, chaque exemple
d'aide reproduit par le pipeline, chaque forme sourcée). Tests instrumentés
(`VoiceCommandInstrumentedTest`) : langues FR + EN posées avant chaque test, plus la dictée
scriptée de l'issue (`Il a dit : « bonjour »` avec insécables), le `?` qui remplace le
point du segment précédent et « J'ai marqué deux points. » qui reste du texte.

À essayer en dictant (vérification humaine) : l'exemple de l'issue d'un trait et avec
pauses ; « deux-points » suivi d'une liste ; des phrases ordinaires contenant « deux
points », « point d'interrogation », « guillemets » ; une dictée en France (réglage
« Français (France) » seul) pour voir les insécables devant `;` `?` `!` ; l'affichage des
insécables dans les applications courantes (messagerie, courriel).

## Issue #15, lot 2 — étape 2.4 : commandes en mode serveur, README

Code : `app/src/test/.../ServerCommandsTest.kt` (nouveau). Aucun changement de code
applicatif ni de serveur (prompt, modèle, garde-fou : hors portée de l'issue).
`android/README.md` : section « Dicter la ponctuation » (commandes du lot 2, règle,
faux positifs, doublons, typographie, limites dont celle du mode serveur), lignes
d'image `howto-light.png` / `howto-dark.png` (PNG déposés après la CI, étape 2.3).

### Ce que fait chaque mode

- **Dictée entière du clavier (modèle « Serveur », issue #13)** : c'est le seul chemin du
  clavier avec le serveur (`DicteeApp.kt` : `whole` posé pour `ModelFamily.REMOTE`).
  `RemoteEngine.transcribeDictation` demande `polish=true` (API de ce dépôt) et renvoie
  `texts = [polished_text ou text]` (`serverResult`). `DictationController.transcribeWhole`
  passe chaque texte par `TextPipeline.prepare` puis `apply` : **les commandes sont donc
  interprétées sur le téléphone, après le polissage**, avec la typographie des langues
  choisies. Si le polissage échoue ou est rejeté, le texte brut suit le même chemin.
  API compatible OpenAI : une requête, sans polissage, même pipeline.
- **Repli local** (serveur en panne) : segments transcrits localement, pipeline normal.
- **Segment par segment transcrit par le serveur** (`RemoteEngine.transcribe`) : n'est plus
  utilisé par la dictée du clavier ; seulement l'écran de comparaison (sans pipeline).
  Il envoie `polish` non défini (le serveur applique alors `WHISPER_POLISH`) mais ne lit
  que `text`, toujours brut (`app/main.py`, docstring de la route). Le chemin reste testé.

### Ce que le polissage du serveur peut faire aux commandes

Prompt (`app/polish.py:96-130`) : il ne parle pas des commandes ; règle 3, « corriger la
ponctuation… en français, mettre une espace avant ? ! : et ; et laisser les guillemets
tels que dictés ». Le LLM peut donc écrire lui-même `:` `?` `!` `;` ou « nouvelle ligne »
en saut de ligne, ou laisser les mots. Le garde-fou `safety_check` (même fichier) juge des
mots, pas de la ponctuation. Mesuré avec la fonction réelle sur des paires brut/poli
(non conservées dans un test : elles dépendent du serveur) :

| Remplacement par le LLM | Résultat du garde-fou |
| --- | --- |
| « deux-points » → `:` | rejeté dans tous les cas essayés : « deux » est un mot-nombre (`dropped_fact`) |
| « ouvrir… fermer les guillemets » → « … » | rejeté (`dropped_content` / `short_dropped`) |
| « point d'interrogation » en fin de dictée → `?` | rejeté (`ending`) |
| « point d'interrogation », « point d'exclamation », « point-virgule », « nouvelle ligne » au milieu d'une dictée longue (plus de 8 mots) → signe | **accepté** (deux mots de contenu sur beaucoup) |
| mots laissés tels quels | accepté : ce sont alors des commandes côté téléphone |

Conséquences, fixées par `ServerCommandsTest` :

1. Mots de commande dans le texte reçu (poli ou brut) : interprétés, typographie d'ici.
2. Signe déjà placé par le LLM : conservé tel quel (`Il a dit : « bonjour »`, espaces
   ordinaires), jamais de doublon ; **pas de typographie Québec** (ni insécable, ni retrait
   de l'espace avant `?` `!` `;`), parce que seuls les signes dictés sont typographiés
   (décision 2.2). Le LLM écrit en outre une espace avant `?` `!` `;` même au Québec.
3. Signe du LLM suivi des mots de la commande : un seul signe (`DuplicateSign`).
4. Segments : le polissage éventuel du serveur est ignoré, les commandes s'appliquent.

Pas de correction côté Android : normaliser la ponctuation du texte poli, ou retirer le
polissage quand des commandes sont dictées, changerait le texte de tous et dépasse la
portée. **Limite documentée** dans le README (« Mode serveur »). Pistes pour plus tard,
hors lot 2 : demander au serveur de laisser intactes les formulations de la table, ou lui
passer l'option `commands=off`.

### À essayer avec son serveur (humain)

Dicter, modèle « Serveur » avec polissage actif : (a) « il a dit deux-points ouvrir les
guillemets bonjour fermer les guillemets » ; (b) une phrase longue avec « point
d'exclamation » au milieu ; (c) « nouvelle ligne » au milieu d'une dictée longue ;
comparer l'état « polissage appliqué » du diagnostic avec le texte inséré, et voir si les
espaces avant `? ! :` sont celles du Québec.

## Issue #15, lot 2 — étape 2.3 : écran « Comment dicter »

Écran `ui/HowToActivity.kt` (au thème Kalmia, déclaré au manifeste), logique pure dans
`ui/HowTo.kt` (testée sur la JVM). Rien du contenu des commandes n'est écrit à la main :
l'écran lit `CommandTable.active(settings.languages)`, et un test vérifie qu'aucun exemple
de la table n'est recopié dans `HowToActivity.kt` ni dans les chaînes de l'écran.

### Contenu et ordre de l'écran

1. Titre, puis une ligne de rappel : « Tout se passe sur votre téléphone : votre voix y est
   transcrite et n'est envoyée à personne, sauf si vous choisissez vous-même un serveur
   distant. » Exacte dans les deux cas (par défaut, ou serveur facultatif choisi par
   l'utilisateur) ; la phrase ne dit rien du serveur au-delà de l'exception, car le
   comportement des commandes en mode serveur est l'objet de l'étape 2.4.
2. Si les commandes vocales sont désactivées (Réglages), un paragraphe le dit et un bouton
   ouvre les Réglages ; les exemples restent affichés, car ils décrivent ce qui marche une
   fois l'option remise.
3. Les exemples « Dites ceci / Vous obtenez » : la phrase dite en IBM Plex Mono
   (`TextAppearance.Kalmia.Value`), le résultat en Newsreader (`Body`), une ligne de
   commandes enseignées.
4. « Toutes les commandes » : une ligne par entrée de `CommandTable.active` (formulation
   principale en Plex Mono, ce qu'elle donne, les autres formulations acceptées).
5. Zone d'essai : un `EditText` (touchez, le clavier apparaît ; pas de focus automatique, qui
   cacherait le haut de l'écran), puis le modèle recommandé (`ModelRecommendations.recommend`)
   avec sa raison, son état installé ou non et un bouton vers la configuration, puis le
   lien vers le vocabulaire personnalisé des Réglages (qui s'ouvrent déjà défilés à cette
   section, extra `EXTRA_SCROLL_TO_VOCABULARY`) pour « GitHub » et les noms propres.

L'écran est redessiné à chaque reprise (`onResume`) : langues, commandes vocales ou
modèle ont pu changer dans les Réglages ; le texte de la zone d'essai est conservé.

### Choix des exemples (plus de 8 entrées dans la table)

- Les entrées qui partagent un exemple sont fusionnées : les guillemets ouvrant et fermant et
  le deux-points montrent la même phrase (« il a dit deux-points ouvrir les guillemets
  bonjour fermer les guillemets »), les parenthèses de même. Une seule carte, qui liste les
  commandes qu'elle enseigne.
- Après fusion, le français seul compte 9 exemples (guillemets + deux-points, point-virgule,
  parenthèses, ?, !, nouvelle ligne, nouveau paragraphe, effacer la phrase, effacer le mot) :
  on en montre **8 au plus** (`HowToExamples.MAX_FEATURED`), les 8 premiers dans l'ordre de la
  table, qui est celui de la liste de l'issue (signes d'abord). Le neuvième (« efface le
  dernier mot ») reste dans « Toutes les commandes ».
- Avec deux langues (français et anglais), les places sont partagées à parts égales, le
  français d'abord : **4 exemples français puis 4 anglais**. Une langue qui en a moins que sa
  part cède ses places aux autres. Avec l'anglais seul : 8 exemples anglais. Avec une langue
  sans commande de ponctuation (espagnol seul), la table ne garde que les retours à la
  ligne et les effacements des deux langues : 4 + 4 exemples, ce que `CommandTable.active`
  décide déjà.
- Les résultats des exemples sont ceux de la table, donc en typographie du Québec pour le
  français (espace insécable avant `:` seulement) : une personne en « Français (France) »
  seul verra, dans ses dictées, une espace insécable de plus devant `;` `?` `!` que dans
  l'aide. Écart assumé, relevé pour la vérification finale.

### Ouvertures

- **Fin de la première configuration** (`SetupProgress.isComplete`) : clavier **activé**
  (pas forcément sélectionné : on le choisit en tout temps depuis un autre clavier), micro
  **autorisé**, et **un modèle prêt** (un modèle téléchargé, ou le serveur de l'utilisateur
  une fois configuré). Les trois ensemble : c'est à ce moment seulement que le téléphone dicte
  vraiment. La condition est revérifiée à chaque reprise, au retour du focus (retour du
  sélecteur de clavier) et à la fin d'un téléchargement (écouteur de `Downloads`). **Une seule
  fois** : le drapeau `AppSettings.howToShown` est posé avant le démarrage de l'écran, et par
  toute ouverture de l'écran (l'ouvrir à la main avant la fin de la configuration compte
  comme l'avoir vu).
- **Écran principal** : un bouton « Comment dicter » juste sous le texte d'accueil, donc visible
  sans défiler (`SetupActivity.HOWTO_BUTTON_TAG`).
- **Clavier** : bouton d'aide (`R.id.help`, icône vectorielle `ic_help` teintée par
  `colorControlNormal` comme celle des Réglages, zone tactile 48 × 48 dp, description
  « Comment dicter »), placé **sur la ligne d'état, entre l'état et le bouton de réglages**.
  La rangée des touches n'est pas touchée : les touches gardent leur largeur. C'est le texte
  d'état (poids 1, deux lignes au plus, points de suspension) qui cède 48 dp. Depuis le
  service : `FLAG_ACTIVITY_NEW_TASK`.

### Tailles en français

Les tailles de modèle venaient de `Formatter.formatShortFileSize`, qui écrit « MB » et « GB »
sous un système en anglais. Remplacé par `ui/SizeFormat.kt` (octets décimaux, unités o, ko, Mo,
Go, virgule décimale, indépendant de la langue du téléphone) ; `Context.size()` y renvoie, donc
les cartes de modèles, les Réglages et les téléchargements en profitent. Exemples : 671 Mo,
987 Mo, 1,04 Go. Le texte de recommandation écrit encore « 1 037 Mo » pour Whisper : même
quantité, deux écritures ; à uniformiser si le lot 2 est revu.

### Tests

- JVM (`HowToTest.kt`) : fusion des exemples partagés, chaque commande enseignée par une
  carte, chaque exemple issu de la table active, 5 à 8 exemples pour plusieurs jeux de langues,
  partage 4 + 4 et français d'abord, langue sans ponctuation, aucun exemple recopié à la main
  dans l'écran ni les chaînes ; table de vérité de « fin de configuration » et « une seule
  fois » ; formatage des tailles.
- Instrumentés (`HowToInstrumentedTest.kt`, moteur scripté, scénarios enchaînés et jamais
  imbriqués, un moniteur d'activité dit quand l'écran démarre) : pas d'ouverture sans modèle ;
  ouverture à la fin de la première configuration ; pas la deuxième fois ; réouverture depuis
  l'écran principal ; depuis le bouton d'aide du clavier (zone ≥ 48 dp, description) ; chaque
  exemple affiché vient de la table, mêmes cartes et même ordre que la logique pure ; rappel,
  modèle recommandé, lien du vocabulaire, zone d'essai ; avertissement quand les commandes
  sont désactivées. `ScreenshotTest` : `howto-light.png` et `howto-dark.png`.
- Non exécutés ici (pas d'émulateur) : les tests instrumentés sont compilés et relus.

À juger à l'écran (vérification humaine) : la longueur de l'écran (les 8 cartes plus la liste
complète) ; la place laissée au texte d'état du clavier par le nouveau bouton, sur un
téléphone étroit ; la lisibilité des phrases dites en Plex Mono sur plusieurs lignes ; le
moment de l'ouverture automatique après le dernier téléchargement.

## Issue #15, lot 2 — revue indépendante (2.R)

Revue faite sur le diff du lot (`origin/main...HEAD`, PR #17) et les critères
d'acceptation de l'issue, sans les comptes rendus des agents qui ont codé.

### Critères d'acceptation

| Critère | État | Preuve |
| --- | --- | --- |
| Tests unitaires par commande, FR et EN | rempli | `TextTest.kt` : `PunctuationCommandsTest.eachFrenchCommand`, `eachEnglishCommand`, `CommandTableTest.everyFormIsRecognisedAlone` (chaque formulation de la table) |
| Phrases pièges sans aucune commande | rempli, étendu | `FalsePositiveTest` : 34 phrases d'origine, plus 58 phrases de la revue (`reviewTrapsStayText`), `pronounBeforeAVerbWordingStaysText`, `homographFollowedByACommaAndWordsStaysText` |
| Doublons avec la ponctuation du modèle | rempli | `DuplicateSignTest`, `PunctuationCommandsTest.realisticModelOutputs` |
| Typographie Québec, France, anglais dans `TextTest` | rempli | `TypographyTest` |
| Test instrumenté : ouverture en fin de première configuration, réouverture depuis l'écran principal et le clavier | rempli après correction du test (voir CI) | `HowToInstrumentedTest` ; à confirmer par la CI |
| Test instrumenté : dictée scriptée « il a dit deux-points ouvrir les guillemets bonjour fermer les guillemets » | rempli (vert en CI) | `VoiceCommandInstrumentedTest.dictatedColonAndQuotesAreTypesetForQuebec` |
| Formulations retenues et sources dans `DECISIONS.md` | rempli | sections « Issue #15, lot 2 », étape 2.1 |
| Capture du tutoriel dans le README | en attente | liens présents ; images `docs/screenshots/howto-*.png` à déposer par l'orchestrateur après la CI (`ScreenshotTest` produit `howto-light` / `howto-dark`) |

### Faux positifs trouvés et corrigés (`text/VoiceCommands.kt`)

Une soixantaine de phrases ordinaires nouvelles (FR québécois et EN) passées
dans `TextPipeline`. Sept changeaient le texte de l'utilisateur :

1. « Ouvre les guillemets du rapport et corrige-les. » → `« Du rapport…`.
   Correction : après une formulation de guillemets ou de parenthèses, « du »,
   « des » (et en anglais « are », « were », « from », « about », « with »,
   « mark(s) ») en font un groupe nominal. Limité aux guillemets et parenthèses :
   après `;` `?` `!` la phrase suivante commence souvent ainsi (« du pain
   point-virgule du lait »).
2. « Guillemets ouverts ou fermés, peu importe. » → `« Ou fermés…`. Correction :
   formulation suivie de « ou / et » puis d'un adjectif d'état opposé (ouverts,
   fermés, ouvrants…) : deux adjectifs coordonnés, pas une commande.
3. « Deux points, c'est pas assez. », « Colon, rectum and liver. » → la virgule
   disparaissait, et au milieu d'un champ (« Il a marqué. Deux points, c'est pas
   assez. ») le texte devenait ` : c'est pas assez`. Correction : un homographe
   (« deux points » sans trait d'union, « colon ») suivi d'une virgule puis de
   mots ordinaires reste du texte ; suivi d'une virgule puis d'une autre
   commande, il reste une commande (« Il a dit, deux points, ouvrir les
   guillemets… »).
4. « Point d'exclamation, ça crie trop. », « Question mark, or period? »,
   « End quote. », « Fermer la parenthèse. », « Deux points. » en début de champ :
   le signe ne pouvait pas s'appliquer, les mots revenaient, mais sans la
   ponctuation que la commande avait absorbée (virgule ou point perdus).
   Correction : `Piece.Cmd.spoken` garde les mots et la ponctuation prise ; une
   commande seule dans son segment garde le segment entier.
5. « Je ferme la parenthèse et on continue. », « Je ferme les guillemets. »,
   « I close quotes with a dash. » après un guillemet ou une parenthèse ouverts :
   fermaient le signe. Correction : un pronom sujet ou complément avant une
   formulation qui commence par un verbe (ouvrir, fermer, open, close, begin,
   end) en fait une phrase sur le signe. Pas appliqué aux noms de signes :
   « viens-tu point d'interrogation » donne toujours `Viens-tu?`.

Restent ambiguës, laissées en commande et documentées dans le README : une
formulation de signe dite en tête de segment puis une virgule (« Ouvrez les
guillemets, s'il vous plaît » ; « Il pose la question, point
d'interrogation. »). On ne peut pas les distinguer d'une vraie dictée
(« Ouvrez les guillemets, bonjour… ») sans comprendre la phrase.

Vrais positifs vérifiés (tests `realisticModelOutputs`) : une commande par
segment avec majuscule et point du modèle, « Deux points. » seul entre deux
commandes, commande en fin de segment, segment fait d'une seule commande,
guillemet ouvert dans un segment et fermé dans un autre, deux signes identiques
dictés de suite (un seul reste). Réglage « Commandes vocales » coupé : rien
n'est interprété ni typographié sur plusieurs segments
(`disabledCommandsAcrossSegments`). Le texte provisoire n'interprète rien (il
montre les mots dits) : comportement attendu, documenté.

### Autres corrections

- **Aide juste pour « Français (France) »** (`ui/HowTo.kt`) : la carte affichait
  le texte de la table, en typographie québécoise (`Tu viens demain?`), alors
  que le clavier produit `Tu viens demain ?` pour un utilisateur de France.
  `HowToExample.result` est maintenant calculé en passant l'exemple dans
  `TextPipeline` avec les langues de l'utilisateur ; l'aide montre ce que le
  clavier produit, par construction (`HowToExamplesTest.resultsAreWhatTheKeyboardProducesForTheUsersTypography`).
- **Tailles** : la raison de recommandation de Whisper et le tableau des modèles
  du README écrivent « 1,04 Go », comme la carte (`SizeFormat`).
- **README** : limites précisées (homographe suivi d'une virgule, commande en
  tête de segment suivie d'une virgule, heure collée seulement dans un même
  segment) ; « France sans Québec » au lieu de « France seul » (France +
  anglais donne aussi la typographie de France).

### CI de la PR #17 (commit 7bff6e0) : causes et corrections

- **`HowToInstrumentedTest`, 5 échecs « rien trouvé à l'écran »** : faute du
  test, pas de l'écran. Dans `View.allViews()`, `if (this is ViewGroup)` était
  écrit dans le bloc `sequence { }`, où `this` est le `SequenceScope` : l'arbre
  des vues n'était jamais parcouru, seule la vue racine était rendue. Les parties
  du test qui ne dépendent pas de cette recherche (l'ouverture automatique en fin
  de configuration, le bouton du clavier) passaient déjà. Corrigé en
  `this@allViews`, comme dans `SetupLanguageTest`.
- **`DictationEndToEndTest.audioFileIsDictatedIntoTheField`, « unrecognized IME
  ID »** : c'était le tout premier test de l'exécution, lancé une dizaine de
  secondes après l'installation des APK ; aucun test précédent ne pouvait avoir
  changé l'état. Le système n'avait pas encore enregistré le service du clavier :
  `ime enable` échouait dans `setUp`, et la boucle de `launchFields` ne relançait
  que `ime set`, qui échoue tant que le clavier n'est pas activé. Corrigé dans
  `ImeTestBase` : attente (60 s au plus) que `ime list -a -s` liste le clavier
  avant `ime enable` / `ime set`, et `ime enable` relancé avec `ime set` dans la
  boucle. Course au démarrage, donc intermittente : elle explique qu'il passait au
  lot 1.
- **CI suivante (commit 205d898) : 26 échecs « keyboard not shown », clavier
  pourtant sélectionné**, `showSoftInput` en échec à `PHASE_CLIENT_VIEW_SERVED`
  dès le premier test. Cause : une boîte « Pixel Launcher isn't responding »
  (ANR du lanceur sur l'émulateur à froid) restée au premier plan toute
  l'exécution ; le champ de test n'avait jamais le focus de fenêtre. Preuve :
  les captures de `ScreenshotTest` de cette exécution montrent la boîte sur
  l'écran de configuration ; celles de 7bff6e0 ne l'ont pas. Les tests qui ne
  passent que par `onActivity` (SetupLanguageTest, la plupart de
  HowToInstrumentedTest) passaient. Correction dans `ImeTestBase` :
  `dismissNotRespondingDialogs()` répond « Attendre » (`android:id/aerr_wait`)
  au démarrage de chaque test, après l'ouverture du champ et dans la boucle de
  relance ; `ime enable` / `ime set` ne sont appelés que si le clavier n'est pas
  déjà activé / sélectionné ; l'attente de l'enregistrement du service interroge
  toutes les secondes (au lieu de 250 ms, pour ne pas charger le système au
  démarrage) ; le message d'échec donne la fenêtre qui a le focus.
- **État laissé entre tests** : `HowToInstrumentedTest` accorde le micro
  (permission qu'on ne peut pas retirer sans tuer le processus des tests). Avec
  le clavier activé et le moteur scripté « installé », tout test qui ouvre
  `SetupActivity` aurait vu « Comment dicter » s'ouvrir par-dessus.
  `ImeTestBase` et `SetupLanguageTest` marquent maintenant l'écran comme déjà vu ;
  seul `opensAtTheEndOfTheFirstSetupAndNotTheSecondTime` remet le drapeau à faux.

### Vérifié sans changement

- Table unique : `HowToActivity` ne contient aucun exemple recopié
  (`neitherTheScreenNorTheStringsCopyAnExampleByHand`) ; tout vient de
  `CommandTable.active` et `HowToExamples`.
- Ouverture automatique : une seule fois (drapeau posé avant `startActivity`,
  donc ni boucle ni double ouverture à la rotation) ; l'écouteur de
  téléchargement n'est actif que pendant `onResume`, donc pas d'ouverture depuis
  l'arrière-plan.
- Bouton d'aide du clavier : 48 dp, description « Comment dicter »,
  `FLAG_ACTIVITY_NEW_TASK`.
- Thème : écran construit avec les aides de `ui/Ui.kt` (styles nommés, attributs
  du thème) ; `NoHardcodedStyleTest` vert. La ligne « Tout se passe sur votre
  téléphone… sauf si vous choisissez vous-même un serveur distant » est exacte.
- Mode serveur : la limite du README correspond au code (`RemoteEngine` envoie le
  texte poli tel quel à `TextPipeline`) ; `ServerCommandsTest` vert. Serveur non
  modifié (hors portée).
- Vie privée : aucune permission ni requête réseau ajoutée par le lot (manifeste :
  une activité non exportée).

## Issue #15, lot 3 — étape 3.1 : jeu d'évaluation et notation

L'instrument de mesure du polissage sur le téléphone, sans aucune approche de
polissage (étapes 3.2 et 3.3). Rien ne change sous `app/src/main` : tout est
dans les sources de test, donc absent de l'APK.

### Jeu d'évaluation

`app/src/test/resources/polissage/jeu-evaluation.tsv` : TSV UTF-8 commenté
(en-tête `#` qui documente les colonnes), une paire par ligne :
`id`, `langue` (`fr` / `en`), `categorie`, `dit` (ce que la personne dit),
`brut` (sortie du modèle, entrée du polissage), `attendu`, `provenance`.

**132 paires** : 79 à corriger, 53 contrôles qui ne doivent pas changer.

| Catégorie | fr | en | Rôle |
| --- | --- | --- | --- |
| `noms-propres` (GitHub, WhatsApp, iPhone, Dropbox, Teams…) | 11 | 3 | à corriger |
| `toponymes` (Rimouski, Chicoutimi, Lac-Saint-Jean, Hochelaga, Hydro-Québec, SAAQ, UQAR…) | 20 | 7 | à corriger |
| `web` (dont `coifferimouski.com`, cas de l'issue) | 12 | 4 | à corriger |
| `courriel` | 7 | 4 | à corriger |
| `epellation` (lettres, codes postaux) | 8 | 3 | à corriger |
| `controle` : phrases ordinaires et pièges (« un point commun », « mets un point final », « la barre oblique du terrain », « arobase » comme mot, « bulle des point com », « plan B », « vitamine C », « on the dot »…) | 15 | 8 | inchangé |
| `controle-mots-rares` : mots rares ou d'ici bien transcrits (friperie, dépanneur, poudrerie, tuque, magasiner, clavardage, pourvoirie, cégep, courriel, achalandage, tabagie, pantoute, achaler…) | 18 | 1 | inchangé |
| `controle-deja-correct` : mot visé que le modèle a déjà bien transcrit | 7 | 4 | inchangé |

`controle-mots-rares` a été ajoutée à la demande de l'utilisateur : un
polissage par LLM a abîmé « friperie », qui était bien transcrit. Ces lignes
comptent dans le taux de contrôles abîmés et sont rapportées à part (colonne
« dont mots rares » et ligne de catégorie) : c'est la mesure clé des approches
à LLM de l'étape 3.3.

**Règle de l'attendu.** Le brut corrigé sur le passage visé (le nom,
l'adresse, l'épellation, y compris les mots que la transcription y a fondus :
`kabaroblique emploi` → `.ca/emploi`), et identique au brut partout ailleurs,
fautes non visées comprises (`Mais un point final…`, `Vas voir…`). Un
polissage qui corrige aussi une faute non visée est donc compté « modifié à
tort » : c'est voulu, on mesure ce qu'on a demandé. Les sorties où
l'information est perdue (« remtav » rendu `rentave`, une lettre épelée en
moins, une fin de phrase coupée par Whisper) n'ont pas été retenues comme cas
à corriger. Conventions : une épellation de nom donne le nom en casse de nom
propre (`Gagnon`), une adresse ou un courriel épelé donne des minuscules
collées ; un code postal s'écrit `G5L 3A1`.

### Provenance des transcriptions brutes

Personne ne peut dicter dans le conteneur. Ce qui a été fait :

- **80 lignes `tts+<modèle>`** : vraies sorties des trois modèles du
  catalogue (Parakeet 41 lignes, Qwen3-ASR 33, Whisper 30 ; plusieurs modèles
  sur une ligne quand leurs sorties sont identiques), 54 cas à corriger et
  26 contrôles. 52 énoncés ont été synthétisés hors du dépôt avec
  [Piper](https://github.com/rhasspy/piper) (`pip install piper-tts`, voix
  `fr_FR-siwis-medium` et `en_US-lessac-medium` de `rhasspy/piper-voices`),
  rééchantillonnés en 16 kHz mono (ffmpeg), puis transcrits sur l'hôte Linux
  par `PolishSetTranscriptionHostTest`, qui passe par `SherpaEngine` (code de
  l'application, sherpa-onnx 2.0.1, modèles int8 vérifiés par `ModelStore`),
  Whisper avec la langue forcée comme le fait l'application pour une seule
  langue. Le texte donné à la synthèse est dans la colonne `dit`.
- **52 lignes `rédigé`** : écrites à la main, vraisemblables, d'après les
  erreurs observées sur ces sorties réelles (mots coupés `git hub`, casse
  perdue `iphone`, signes dictés laissés en toutes lettres `point ca`,
  `arobase`, lettres séparées) ; et les contrôles rédigés.

Ni l'audio ni l'outil de synthèse ne sont dans le dépôt. Pour régénérer les
lignes `tts+…` : produire `liste.tsv` (`id<TAB>langue<TAB>dit`) et un
`<id>.wav` par ligne dans un dossier, puis
`./gradlew testDebugUnitTest --tests '*PolishSetTranscriptionHostTest*' -PmodelsDir=… -PpolishAudioDir=…`,
qui écrit `transcriptions.tsv` dans ce dossier ; sans ces deux propriétés, le
test est ignoré (il l'est en CI).

**Limites.** Une voix de synthèse n'est pas une voix québécoise : elle est
propre, régulière, à l'accent de France pour le français, et elle prononce
les noms anglais à la française (`GitHub` → `J-Hub`). Les erreurs réelles
d'une personne du Québec seront différentes (souvent plus nombreuses).
L'épellation par synthèse est artificielle (lettres séparées par des
virgules pour forcer une pause). Le jeu mesure donc la capacité d'un
polissage à réparer des erreurs typiques, pas la qualité de bout en bout sur
une vraie voix ; des lignes issues de vraies dictées pourront s'ajouter
(nouvelle provenance à définir, par exemple `dictee+<modèle>`).

### Notation

Sources de test, paquet `io.github.remtav.dictee.polish` :

- `PolishEval.kt` : `EvalCase`, `PolishEvalSet` (lecture et catégories),
  l'interface `PolishApproach` (`name`, `needsLlm`, `description`,
  `polish(raw: String, language: String): String`), `IdentityApproach`,
  `RecordedApproach` (sorties enregistrées rejouées), `PolishNormalization`,
  `Percentiles`, `PolishScorer`.
- `PolishApproaches.kt` : le registre (`live`, plus les fichiers de
  `src/test/resources/polissage/sorties/*.tsv`).
- `PolishReport.kt` : tableau lisible et rapport machine.
- `PolishEvaluationTest` : note toutes les approches du registre, imprime le
  tableau, écrit `app/build/reports/polissage/resultats.json`,
  `resultats.md` et `sorties-<approche>.tsv` (sortie et verdict de chaque
  cas). Tourne dans `./gradlew testDebugUnitTest` (CI), sans modèle ni réseau,
  en moins d'une seconde.
- `PolishScorerTest` (le noteur lui-même) et `PolishEvalSetTest` (taille,
  couverture, contrôles identiques, cas à corriger réellement différents,
  unicité, provenance bien formée).

**Mesures.** Taux de cas corrigés (sortie = attendu, sur les cas à corriger),
taux de « modifiés à tort » (changé mais faux), taux de contrôles abîmés (doit
rester à 0), erreurs (exception ou sortie enregistrée absente), le tout au
total, par catégorie et par langue ; latence p50 / p95 par appel (rang le plus
proche), mesurée **sur l'hôte** : un passage d'échauffement puis 5 passages
chronométrés. Ce n'est pas la latence sur le téléphone.

**Normalisation (stricte).** Avant comparaison : NFC, apostrophe typographique
= apostrophe droite, suites d'espaces ou tabulations = un espace, espaces de
bord retirés. Tout le reste compte : casse (`Github` ≠ `GitHub`), accents,
traits d'union, ponctuation, espace dans un mot ou une adresse
(`coifferimouski. com` ≠ `coifferimouski.com`) et espaces insécables (la
typographie est le travail de `TextAssembler`, après le polissage).

**Référence.** `identite` (ne change rien) donne 0/79 corrigés et 0/53 abîmés :
le dispositif ne compte rien par erreur (`identityCorrectsNothingAndDamagesNothing`).

### Brancher une approche

- **Sans LLM (étape 3.2)** : l'ajouter à `PolishApproaches.live`, par exemple
  `PolishApproach.of("regles", needsLlm = false) { raw, lang -> … }`. La garde
  `approachesWithoutLlmDamageNoControlSentence` fait alors échouer la CI si
  elle abîme une seule phrase de contrôle ou lève une exception. Le code de
  l'approche reste hors du chemin par défaut de l'application.
- **Avec LLM (étape 3.3)** : MediaPipe n'existe que pour arm64, l'approche ne
  s'exécute pas sur l'hôte. On enregistre ses sorties sur un téléphone dans
  `src/test/resources/polissage/sorties/<approche>.tsv` (en-têtes facultatifs
  `# approche:`, `# llm: oui`, `# description:` ; colonnes
  `id<TAB>brut<TAB>sortie[<TAB>latence_ms]`). Le noteur les rejoue : la
  qualité est notée comme pour les autres, aucune latence d'hôte n'est
  inventée ; une `latence_ms` mesurée sur le téléphone est rapportée comme
  « appareil ». Une ligne dont le `brut` ne correspond plus au jeu (jeu
  modifié depuis l'enregistrement) ou absente compte comme erreur, jamais
  comme réussite. Les approches à LLM ne sont pas bloquées par la garde : le
  rapport de l'étape 3.4 tranche avec les chiffres.

## Issue #15, lot 3 — étape 3.2 : règles déterministes et vocabulaire enrichi

Approches 1 et 2 de l'évaluation du polissage local, sans modèle. **Rien n'est
activé** : le code est dans l'application mais aucun appel ne le branche (pas
d'appel depuis `TextPipeline` ni `DicteeApp`, aucun réglage). Le comportement
par défaut est inchangé ; `DefaultPathUnchangedTest` le garantit (le pipeline
par défaut laisse intactes des phrases que les règles transformeraient, et
aucun fichier hors de `text/polish/` ne mentionne le paquet).

### Où est le code

- `app/src/main/java/io/github/remtav/dictee/text/polish/PolishRules.kt` :
  les règles (Kotlin pur, JVM, aucune dépendance Android).
- `app/src/main/java/io/github/remtav/dictee/text/polish/ShippedVocabulary.kt` :
  la liste livrée, appliquée par le mécanisme existant de `text/Vocabulary.kt`
  (corrections `mal entendu -> correct`, insensibles à la casse, sur frontières
  de mots).
- Dans les sources de test : `polish/RuleBasedApproaches.kt` (les approches
  `PolishApproach` qui enveloppent ce code), enregistrées dans
  `PolishApproaches.live` : `regles`, `vocabulaire`, `vocabulaire-general`
  (mesure du surapprentissage, voir plus bas) et `regles+vocabulaire`.
- Tests : `PolishRulesTest` (chaque règle, chaque piège, signal),
  `ShippedVocabularyTest` (noms hors du jeu, phrases ordinaires, adresses
  masquées), `DefaultPathUnchangedTest`, et le noteur
  (`PolishEvaluationTest`, garde « aucun contrôle abîmé » inchangée et verte).

### API, dont le signal « cas probable » pour l'étape 3.3

```kotlin
object PolishRules {
    fun polish(text: String, language: String): String       // le texte corrigé
    fun assess(text: String, language: String): RuleResult   // texte + signal, jamais d'exception
}
enum class RuleSignal { UNCHANGED, CORRECTED, PROBABLE_CASE }
data class RuleResult(val text: String, val signal: RuleSignal, val hasAddress: Boolean)

object ShippedVocabulary {
    val entries: List<Entry>                                  // (heard, correct, language, origin)
    fun apply(text: String, language: String, origins: Set<Origin> = all): String
    fun forLanguage(language: String, ...): Vocabulary
}
```

`language` : `"fr"` ou `"en"` (toute autre valeur donne le français).

- `UNCHANGED` : aucun contexte d'adresse ni d'épellation, texte intact.
- `CORRECTED` : les règles ont réécrit quelque chose et rien de suspect ne
  reste.
- `PROBABLE_CASE` : une adresse ou une épellation est probablement là mais les
  règles ne l'ont **pas résolue** (le texte peut avoir été modifié ailleurs).
  Déclencheurs : « point com » / « dot ca » précédé d'un nom sans mot
  d'introduction (« l'entreprise point com »), « arobase » (ou « at » après un
  nom) suivi d'un domaine qu'on n'a pas su lire, suite d'au moins quatre lettres
  isolées sans indice d'épellation et qui n'est pas dans l'ordre alphabétique
  (`C O I F F E R I M O U S K Icom`). Les pièges (« un point commun », « des
  point com », « arobase » employé comme mot) ne sont **pas** signalés : un
  signal sur un contrôle enverrait inutilement le segment au LLM.
- `hasAddress` : la sortie contient une adresse web ou un courriel (construite
  ou déjà écrite par le modèle). Les règles ne peuvent pas vérifier
  l'orthographe du nom (`kalmea.ca`, `coifferymusky.com`) : la combinaison de
  3.3 peut choisir d'envoyer aussi ces segments au LLM.
  Proposition pour 3.3 : LLM sur `signal == PROBABLE_CASE || hasAddress`, avec
  `text` (déjà recollé) comme entrée.

### Règles (approche 1)

Principe : **dans le doute, ne rien changer**. Une adresse dite n'est réécrite
que si toute sa forme est là : un nom (1 à 3 mots collés par unité, ex. « coiffe
rimouski » → `coifferimouski`) précédé d'un mot d'introduction (sur, à, voir,
va, c'est, est, to, at, on, visit, check out, is… ou début de segment ou
ponctuation), les mots de point, et une fin connue (com, ca, org, net, edu, io,
info, gov). Sinon rien.

- « point » / « dot » → `.` ; « arobase » / « at » → `@` (en anglais « at »
  exige un nom avant et un domaine lisible après) ; « barre oblique » /
  « slash » → `/` suivi d'un mot (`quebec.ca/emploi`) ; « tiret », « trait
  d'union », « dash », « hyphen » → `-` ; « underscore », « tiret du bas » →
  `_` ; tout cela seulement **à l'intérieur** d'une adresse ou d'un courriel
  reconnu, en français ou en anglais selon `language`.
- Adresse construite : minuscules et accents retirés (`québec` → `quebec`,
  `vidéotron` → `videotron`). Une adresse déjà écrite n'est jamais mise en
  minuscules ni désaccentuée ; seuls les espaces parasites sont retirés
  (`remi @ kalmia . ca` → `remi@kalmia.ca`, `www . kalmia . ca`) et le chemin
  dit après (`kalmea.ca slash contact` → `kalmea.ca/contact`).
- Jamais de mots ordinaires collés à une adresse écrite (`super kalmia.ca`
  reste tel quel).
- Épellation : `T-R-E-M-B-L-A-Y` (quatre lettres et plus), `B O U C H A R D` et
  `G, A G N O N` quand un mot d'indice précède (« s'écrit », « s'appelle »,
  « s'épelle », « spelled »… dans les 4 mots avant), lettres devant « point /
  dot / arobase » (`c, o, i, f, f… point com`), `w w w`. Majuscules : un nom
  épelé en majuscules devient `Gagnon` ; dans une adresse, minuscules. Une suite
  dans l'ordre alphabétique (`A, B, C, D`) est une liste, pas une épellation.
- Code postal canadien : `G 5 L, 3 A 1`, `G5L, 3A1`, `G5L3A1` → `G5L 3A1`
  (majuscules seulement).

Ce que les règles ne font pas : deviner un nom mal transcrit (`coiffrimouski`,
`kelmia`, `kababoblique`), lire les chiffres dits (`G cinq L trois A un`),
reconnaître une adresse sans mot d'introduction (signalée seulement), lire un
domaine épelé sans mot de point (`… K Icom`).

### Vocabulaire livré (approche 2)

178 formes (158 « générales », 20 relevées dans le jeu) pour environ 125 noms
réels et connus : marques et outils (GitHub, WhatsApp, iPhone, YouTube,
Facebook, Instagram, Dropbox, PowerPoint, FaceTime, ChatGPT…), lieux du Québec
(Rimouski, Chicoutimi, Saguenay, Trois-Rivières, Abitibi, Gaspésie,
Sherbrooke, Hochelaga, Rivière-du-Loup, Lac-Mégantic, Sept-Îles, régions…),
institutions et entreprises d'ici (Hydro-Québec, Desjardins, SAAQ, RAMQ,
Vidéotron, Cirque du Soleil, universités, Centre Bell…). Chaque entrée porte sa
langue (`fr`, `en` ou `*`) et son **origine** : `GENERAL` (forme qu'on listerait
de toute façon : nom collé ou coupé, accent ou trait d'union manquant, casse,
graphies phonétiques usuelles) ou `OBSERVED` (forme ajoutée parce qu'elle figure
mot pour mot dans une transcription brute du jeu).

Règles de la liste : une forme entendue qui n'est pas un mot ordinaire ; quand
le mot nu est ordinaire, le contexte fait partie de la forme (`caisse des
jardins` → `caisse Desjardins`, `sur team` → `sur Teams`, pas `team` seul ni
`des jardins`). Les adresses écrites (tout ce qui contient `@` ou un point suivi
de com, ca, org…) sont masquées avant application : aucune correction à
l'intérieur de `github.com`. Variante à apostrophe typographique générée
automatiquement. Entrée discutable, laissée telle quelle et signalée dans le
code : « lac Saint-Jean » → `Lac-Saint-Jean` (le jeu l'attend, mais le lac
lui-même s'écrit « lac Saint-Jean » ; la région, `Lac-Saint-Jean`).

### Scores (jeu de 132 paires : 79 à corriger, 53 contrôles)

Latence : **hôte Linux**, pas téléphone, un appel par phrase.

| Approche | Corrigés | Modifiés à tort | Contrôles abîmés | dont mots rares | p50 / p95 ms (hôte) |
| --- | --- | --- | --- | --- | --- |
| identite | 0/79 (0,0 %) | 0 | 0/53 | 0/19 | 0,009 / 0,032 |
| regles | 17/79 (21,5 %) | 2 (2,5 %) | 0/53 | 0/19 | 0,119 / 0,259 |
| vocabulaire | 38/79 (48,1 %) | 1 (1,3 %) | 0/53 | 0/19 | 0,319 / 0,769 |
| vocabulaire-general (sans les 20 formes relevées) | 19/79 (24,1 %) | 5 (6,3 %) | 0/53 | 0/19 | 0,264 / 0,579 |
| regles+vocabulaire | 55/79 (69,6 %) | 2 (2,5 %) | 0/53 | 0/19 | 0,260 / 0,412 |

Par catégorie (corrigés / à corriger) et par langue :

| Approche | noms-propres (14) | toponymes (27) | web (16) | courriel (11) | épellation (11) | fr (58) | en (21) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| regles | 0 | 0 | 4 | 4 | 9 | 11 | 6 |
| vocabulaire | 14 | 24 | 0 | 0 | 0 | 31 | 7 |
| vocabulaire-general | 11 | 8 | 0 | 0 | 0 | 14 | 5 |
| regles+vocabulaire | 14 | 24 | 4 | 4 | 9 | 42 | 13 |

Contrôles : 0 phrase abîmée sur 53 pour toutes les approches, dont les 19
contrôles de mots rares (friperie, poudrerie, pourvoirie, tuque…), les 11
contrôles déjà corrects et les pièges des règles (« un point commun », « mets un
point final », « à la barre oblique du terrain », « arobase » employé comme
mot, « plan B », « vitamine C », « dot com bubble »). La garde de la CI est
inchangée.

### Cas non corrigés les plus instructifs (regles+vocabulaire, 24 sur 79)

- **Nom mal transcrit dans une adresse** (la majorité des 24) : `coiffrimouski.com`,
  `Kalmia.ka`, `kelmia.ca`, `hydrokebec.com`, `kalmayer.co`, `Rémi@robazcalmier.ca`.
  Les règles ne savent que recoller et mettre la ponctuation, pas retrouver
  « kalmia » : c'est le travail d'un modèle (étape 3.3), ou d'un vocabulaire de
  l'utilisateur.
- **Adresse déjà fusionnée par le modèle** : `québec.kabaroblique emploi`,
  `infoirobase coiffremouski.com`, `atkelmya.com` : le mot de jonction est
  fondu dans un autre mot ; aucune règle sûre.
- **Chiffres dits en lettres** : `G cinq L trois A un`. Faisable (nombres 0-9
  dans un code postal) mais pas fait : hors de la portée de cette étape.
- **Épellation + suffixe fondu** : `C O I F F E R I M O U S K Icom`. Signalé
  `PROBABLE_CASE` (suite de lettres sans indice) mais non réécrit.
- **Noms anglais mal transcrits** (`Chickaltomy`, `Chakotay`, `Chikautami`) :
  aucune forme plausible générale ; ne pas les ajouter.
- **Deux « modifiés à tort »** : `coifferymusky.com` (le modèle a mal entendu le
  nom, les règles ont bien formé l'adresse : la forme est juste, le nom non) et
  `kalmea.ca/contact` (« slash » bien recollé, nom faux). Ce sont des
  améliorations partielles que la notation stricte compte comme erreurs.

### Surapprentissage possible (à lire avant de se fier aux chiffres)

- Le jeu était connu quand la liste a été écrite : les noms visés (GitHub,
  WhatsApp, Rimouski, Hydro-Québec…) sont dans le jeu parce que ce sont les
  noms d'ici et d'outils les plus courants, et dans la liste pour la même
  raison. Cette coïncidence est voulue mais gonfle le score.
- Sur les 38 cas corrigés par le vocabulaire, **19 le sont grâce à des formes
  tirées mot pour mot du jeu** (20 formes tagguées `OBSERVED`) ; sans elles, le
  vocabulaire corrige 19/79 (24,1 %) et se trompe 5 fois (corrections
  partielles : un nom corrigé, l'autre non). Pour les noms propres, la part
  générale est élevée (11/14), pour les toponymes elle est faible (8/27) : les
  mauvaises transcriptions de toponymes d'ici sont irrégulières (`Sacney`,
  `Chebouk`, `Auchas`), ce qui est le vrai constat : une liste ne couvre que ce
  qu'on y a vu.
- Les règles n'ont pas de liste à ajuster, mais leurs mots d'introduction et
  leurs pièges ont été réglés sur les 53 contrôles : un contrôle jamais vu
  (« c'était une bulle point com » sans « des ») pourrait être réécrit si un mot
  d'introduction précède le nom. Aucune mesure hors jeu n'est possible tant que
  de vraies dictées n'ont pas été ajoutées.
- Les tests unitaires (`PolishRulesTest`, `ShippedVocabularyTest`) utilisent des
  phrases hors du jeu (`you tube`, `sept iles`, `jean trait d'union luc arobase`),
  ce qui vérifie le mécanisme, pas la couverture.

### Limites

- Le jeu est en partie synthétique : voix de synthèse Piper (pas une voix
  québécoise), lignes rédigées à la main pour les cas non obtenus. Les erreurs
  réelles d'un accent québécois seront différentes.
- Des listes d'introduction, de mots vides et de fins d'adresse (`TLDS`) en
  français et en anglais : une adresse en `.quebec`, `.gouv.qc.ca` dit
  « point gouv point qc point ca » est couverte, un `.coop` ou un `.ai` non.
- Des noms de domaine à plusieurs mots coupés par un mot vide (« ville de
  québec point ca ») ne sont pas recollés.
- Latences d'hôte seulement ; sur téléphone, ces approches sont négligeables
  (microsecondes à millisecondes) mais ne sont pas mesurées.
- Rien n'est branché : pour les activer, il faudrait un réglage, un ADR et une
  décision de l'étape 3.4.

## Issue #15, lot 3 — étape 3.3 : petit LLM local, garde-fou assoupli, combinaison

Approches 3 (petit LLM local, prompt élargi) et 4 (règles d'abord, LLM sur
les cas probables). **Code d'évaluation seulement, hors du chemin par
défaut** : nouveaux fichiers sous `app/src/main/java/io/github/remtav/dictee/cleanup/eval/`,
appelés par aucun écran, aucun réglage, ni `DicteeApp` ni `TextPipeline` ;
`CleanupMode`, `LlmCleaner`, son prompt et `SafetyCheck` sont inchangés.
L'application se comporte exactement comme avant.

**Aucun LLM n'a tourné pendant cette étape** (MediaPipe n'existe que pour
arm64, le conteneur est x86-64). Tous les chiffres ci-dessous sont soit des
propriétés du garde-fou (testables ici), soit des **simulations** nommées
comme telles. Les vraies sorties, la latence et la mémoire restent **à faire
sur le téléphone** (procédure plus bas).

### Fichiers

| Fichier | Rôle |
| --- | --- |
| `cleanup/eval/RelaxedSafetyCheck.kt` | garde-fou assoupli (spécification ci-dessous) |
| `cleanup/eval/WidePrompt.kt` | prompt élargi (gabarits ChatML pour Qwen, Gemma) et `LexiconHints` |
| `cleanup/eval/EvalPolishers.kt` | `LlmCall`, `GuardedLlmPolisher` (approche 3), `ProbableCaseDetector`, `SegmentClass`, `ProvisionalProbableCaseDetector`, `RulesThenLlmPolisher` (approche 4) |
| `src/test/.../cleanup/eval/RelaxedSafetyCheckTest.kt` | 24 tests du garde-fou, sorties de LLM simulées bonnes et mauvaises |
| `src/test/.../polish/LlmSimulationTest.kt` | plafond, protection, simulations, génération des fichiers `simulation-*` |
| `src/test/resources/polissage/lexique-simulation.txt` | lexique de simulation (voir limites) |
| `src/test/resources/polissage/sorties/simulation-*.tsv` | 6 sorties simulées, rejouées par le noteur de l'étape 3.1 |
| `src/androidTest/.../LlmPolishRecordingTest.kt` | enregistrement sur téléphone (ignoré sans argument) |
| `app/build.gradle.kts` | le jeu et le lexique sont copiés dans les assets de l'**APK de test** seulement |

### Approche 3 : prompt élargi

`WidePrompt.system` (consignes en anglais, que les petits modèles suivent
mieux, exemples français et anglais) : corriger **seulement** les noms de
produits et techniques (`git hub` → `GitHub`), les noms de lieux et
d'institutions du Québec (`lac Saint Jean` → `Lac-Saint-Jean`), les lettres
épelées et codes postaux, les adresses web et courriels dits à voix haute
(`info arobase uqar point ca` → `info@uqar.ca`) ; recopier tout le reste
lettre pour lettre, **même un mot rare, familier ou qui semble mal écrit**,
avec une liste de mots d'ici à ne pas toucher (friperie, poudrerie,
dépanneur, cégep, pourvoirie, achalandage, tabagie, clavardage, achaler,
pantoute, magasiner, tuque, frette, char, chum) ; ne jamais ajouter,
retirer, traduire, réordonner, reformuler ; ne pas toucher la ponctuation ni
les majuscules hors des noms corrigés ; ne jamais inventer d'adresse.
`LexiconHints` ajoute au prompt au plus 12 noms du lexique proches d'un
groupe de 1 à 3 mots du segment (prompt court, donc latence contenue).

Différences avec `LlmCleaner` : pas de `RuleCleaner` avant (les hésitations
restent le travail du prompt de production), pas de seuil de 4 mots (« Va
voir coiffe rimouski point com » est court), pas de délai de 4 s dans la
variante (le test sur téléphone mesure la latence réelle ; l'étape 3.4
compte les appels au-delà de 4 s).

### Spécification du garde-fou assoupli

`RelaxedSafetyCheck.check(entrée, sortie, options)` accepte la sortie
seulement si elle se **dérive** de l'entrée, de gauche à droite, par une
suite de réécritures autorisées, chacune consommant une suite contiguë de
jetons de l'entrée et produisant une suite contiguë de jetons de la sortie
(programmation dynamique, la dérivation acceptée est rendue dans `steps`).
Jetons : mots (lettres et chiffres, traits d'union et apostrophes internes,
élisions françaises séparées : `l'`, `assurance`), signes isolés, et
adresses entières. Les espaces ne comptent que comme espacement avant un
jeton, qui doit être conservé.

| Règle | Permis | Exemple |
| --- | --- | --- |
| `KEEP` | même jeton, même texte (NFC, ’ = '), même espacement | |
| `MIXED_CASE` | 1 à 3 mots de lettres → 1 mot, mêmes lettres, casse « mixte » (majuscule après la 1re lettre et à chaque jonction, au moins une minuscule) | `git hub` → `GitHub`, `iphone` → `iPhone` |
| `HYPHEN_JOIN` | 2 à 4 mots joints par des traits d'union, lettres et casse inchangées, 1re lettre majuscule | `Rivière du Loup` → `Rivière-du-Loup` |
| `SPELLING` | 3 lettres épelées ou plus (virgules ou tirets entre elles permis) → un mot de exactement ces lettres, casse libre | `G, A G N O N` → `Gagnon` |
| `POSTAL_CODE` | lettres, chiffres et chiffres dits qui forment exactement un code postal → `A1A 1A1` | `G cinq L trois A un` → `G5L 3A1` |
| `ADDRESS` | lecture fixe : `point`/`dot` = `.`, `arobase`/`at`/`a commercial` = `@`, `barre oblique`/`slash` = `/`, `tiret`/`dash`/`trait d'union` = `-`, espaces retirés, accents et casse repliés, lettres épelées jointes ; l'adresse doit être en minuscules, **égale exactement** à cette lecture, finir par un domaine connu (com, ca, org, net…) ; aucun mot-outil (de, des, la, the…) dans le passage | `québec point ca` → `quebec.ca` |
| `LEXICON_FORM` | avec un lexique : le passage épelle une entrée, casse, accents, espaces, traits d'union et apostrophes mis à part | `Trois Rivieres` → `Trois-Rivières` |
| `LEXICON_NEAR` | **désactivé par défaut** (`allowLexiconNear`) : 1 à 3 mots à faible distance d'édition d'un nom du lexique (0 sous 5 lettres, puis (n + 2) / 4), sans avaler un mot voisin (refusé si le passage sans son premier ou dernier mot est aussi proche) ; ou une adresse à distance ≤ max(1, n / 6) d'une adresse du lexique | `Drawbox` → `Dropbox` |

**Tout le reste est refusé** : mot ajouté, retiré, remplacé (hors lexique),
réordonné ; ponctuation ou espacement changé ; majuscule simple d'un mot
ordinaire (`Fin de semaine`) ; préambule (« Voici le texte corrigé : ») ;
saut de ligne ajouté ; chiffre changé ; adresse modifiée d'une lettre,
complétée (`www.`, `https://`) ou au domaine changé ; sortie vide ou plus de
deux fois plus longue. Un mot ne peut devenir qu'une entrée du lexique,
jamais autre chose : `friperie` → `fripperie` est refusé dans toutes les
configurations.

Le lexique serait le vocabulaire de l'utilisateur plus la liste livrée par
l'étape 3.2. Sans lexique, seules les réécritures qui ne changent aucune
lettre (casse mixte, traits d'union, épellation, code postal, adresse)
passent.

**Tests** (`RelaxedSafetyCheckTest`, 24 tests) : chaque transformation
permise ; et, avec les options les plus permissives (lexique + proximité),
le refus d'hypercorrections de mots rares (`fripperie`, `CEGEP`, `Cégep`,
`froid` pour `frette`, `voiture` pour `char`…), d'hallucinations, de
traductions, de reformulations, de changements de ponctuation et
d'espacement, d'adresses inventées ou altérées, des pièges des contrôles
(`la bulle des point com` → `des.com`, `arobase` → `@`, `point final` →
`.`), de changements de nombres, de remplacements hors lexique et
d'épellations incomplètes.

### Modèles vérifiés (le 2026-11-12)

- **Qwen2.5 0,5B et 1,5B Instruct** (`.task` q8, Apache-2.0,
  `litert-community` sur Hugging Face) : déjà dans `LlmCatalog`, inchangés.
- **MediaPipe LLM Inference** : la dernière version de
  `com.google.mediapipe:tasks-genai` sur le dépôt Maven de Google est
  **0.10.35** (celle du projet ; métadonnées du 2026-04-27). Les pages de
  documentation MediaPipe LLM Inference renvoient maintenant vers LiteRT-LM
  (mise à jour du 2026-10-06) ; les résultats de recherche citent la
  documentation officielle disant que l'API Android est en maintenance
  seulement (dépréciée) et recommandant LiteRT-LM.
- **Modèle plus récent et petit au format `.task`** : un seul vérifiable,
  `litert-community/Gemma3-1B-IT` (fichier `gemma3-1b-it-int4.task`, 555 Mo,
  aussi en q8 ~1,05 Go), **licence Gemma** (conditions d'utilisation de
  Google, pas Apache) et **dépôt à accès restreint** (il faut un compte
  Hugging Face et accepter la licence pour télécharger) : incompatible avec
  le téléchargement anonyme de l'application, donc **pas ajouté** au
  catalogue. Il peut être mesuré par le test sur téléphone (fichier poussé,
  `-e llmTemplate gemma`).
- **Non retenus** : Qwen3-0.6B (Apache-2.0, 328 Mio en int4), Qwen3.5-0.8B,
  SmolLM2-360M, LFM2.5-1.2B : publiés seulement en `.litertlm`, qui demande
  le moteur LiteRT-LM (`com.google.ai.edge.litertlm:litertlm-android`,
  0.19.1), une nouvelle dépendance ; que `tasks-genai` 0.10.35 charge un
  `.litertlm` n'a pas été vérifié. Passer à LiteRT-LM est une décision à
  part (hors de cette étape), à reconsidérer puisque MediaPipe LLM Inference
  n'évolue plus sur Android.

Sources : `https://huggingface.co/api/models?author=litert-community`,
`https://huggingface.co/litert-community/Qwen3-0.6B`,
`https://dl.google.com/android/maven2/com/google/mediapipe/tasks-genai/maven-metadata.xml`,
`https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android`.

### Chiffres calculés ici

**(a) Plafond du garde-fou** : part des 79 cas à corriger acceptée si le LLM
rendait exactement le texte attendu (`ceilingOfTheRelaxedGuard`, chiffres
épinglés dans le test).

| Configuration | Acceptés | noms-propres | toponymes | web | courriel | épellation |
| --- | --- | --- | --- | --- | --- | --- |
| sans lexique | 28/79 (35 %) | 7/14 | 2/27 | 5/16 | 4/11 | 10/11 |
| lexique (forme) | 36/79 (46 %) | 9/14 | 8/27 | 5/16 | 4/11 | 10/11 |
| lexique + proximité | 44/79 (56 %) | 12/14 | 13/27 | 5/16 | 4/11 | 10/11 |

Les 35 refus restants sont voulus : nom trop déformé pour être rapproché
(`J-Hub`, `Chebouk`, `Iroqebac`, `Auchas` → `Hochelaga`), mot voisin avalé
(`et Mouski` → `Rimouski`), adresse dont la transcription a perdu ou changé
des lettres (`coiffrimouski.com`, `kelmia.ca`, `kalmea.ca slash contact`,
`remy@kelmia.com`) : rétablir ces adresses, c'est inventer des lettres, ce
que le garde-fou interdit sauf si l'adresse est dans le lexique.

**(a bis) Protection des 53 contrôles** : 9 modes de sortie fautive
fabriqués (hypercorrection type `fripperie`, mot retiré, mot ajouté,
ponctuation, majuscule, préambule, adresse inventée, accent retiré,
synonyme) appliqués à chaque contrôle quand ils s'appliquent : **399
sorties fautives, 399 refusées** dans chacune des trois configurations. Les
mêmes modes appliqués au texte attendu des 79 cas (le nom est corrigé mais
autre chose est abîmé) : **539 sorties, 0 acceptée** (un cas écarté et
justifié dans le test : `Trois-Rivieres` sans accent est une réécriture
permise du brut `Trois Rivieres`, aucune lettre du brut n'est changée).

**(b) Simulations rejouées par le noteur** (`PolishEvaluationTest`, fichiers
`sorties/simulation-*.tsv`, en-tête « SIMULATION, aucun LLM n'a tourné ») :

| Fichier | Corrigés | Modifiés à tort | Contrôles abîmés |
| --- | --- | --- | --- |
| `simulation-llm-oracle-gardefou` (approche 3, LLM parfait, lexique) | 36/79 | 0 | 0/53 |
| `simulation-llm-oracle-gardefou-proximite` (idem + proximité) | 44/79 | 0 | 0/53 |
| `simulation-llm-bruyant-gardefou` (approche 3, LLM bruyant) | 13/79 | 0 | 0/53 |
| `simulation-llm-bruyant-sans-gardefou` (même LLM, sans garde-fou) | 39/79 | 28/79 | 13/53 (dont 4/19 mots rares) |
| `simulation-combinaison-oracle` (approche 4, LLM parfait) | 34/79 | 0 | 0/53 |
| `simulation-combinaison-bruyant` (approche 4, LLM bruyant) | 12/79 | 0 | 0/53 |

Le LLM « bruyant » est une invention déterministe (`NoisyLlm`, CRC32 de
l'identifiant) : sur les cas à corriger, 55 % texte attendu, 15 % attendu
abîmé, 15 % brut inchangé, 15 % brut abîmé ; sur les contrôles, 70 % intacts,
30 % abîmés. Ce n'est **pas** un modèle de Qwen : il montre seulement que le
garde-fou transforme les erreurs du LLM en « rien fait » plutôt qu'en mots
abîmés, au prix de corrections perdues (39 → 13). Combinaison : avec les
règles en identité (celles de 3.2 ne sont pas raccordées) et le détecteur
provisoire, le LLM est appelé sur 75 des 132 segments (56/79 cas à corriger,
19/53 contrôles) ; il manque 2 cas par rapport à l'approche 3 (adresse
`coiffe Rimouski.com`, code postal en toutes lettres) que le détecteur
provisoire ne signale pas.

**Limites des simulations.** Le lexique de simulation contient les noms du
jeu : les plafonds avec lexique sont optimistes. L'oracle mesure ce que le
garde-fou laisse passer, pas ce qu'un modèle de 0,5 ou 1,5 milliard de
paramètres sait faire. Aucune latence n'est simulée.

### Interface à raccorder aux règles de 3.2 (approche 4)

```kotlin
enum class SegmentClass { UNCHANGED, CORRECTED, PROBABLE_CASE }
fun interface ProbableCaseDetector { fun classify(raw: String, afterRules: String, language: String): SegmentClass }
class RulesThenLlmPolisher(rules: (String, String) -> String, detector: ProbableCaseDetector, llm: GuardedLlmPolisher)
```

`ProvisionalProbableCaseDetector` (provisoire) signale : adresse dite
(`point|dot` + com, ca, org, net, qc, gouv ; `arobase` ; `at … dot` ;
`barre oblique` ; `slash`), 3 lettres majuscules épelées ou plus, code
postal mal formé, groupe de 1 à 3 mots proche d'un nom du lexique ou qui
l'épelle autrement. À raccorder (orchestrateur ou étape 3.4) : passer la
fonction des règles de 3.2 en `rules` et sa classification en `detector` ;
le garde-fou compare la sortie du LLM au texte **après** les règles (les
règles ont leur propre garde en CI). Puis régénérer
`simulation-combinaison-*` (le test `simulatedOutputFilesAreUpToDate` dit
quoi copier) et relancer l'enregistrement sur téléphone.

### Procédure de mesure sur téléphone (étape 3.4, point de contrôle)

Téléphone arm64, débogage USB activé.

1. Installer le modèle : dans l'application, Réglages → nettoyage « Petit LLM local + garde-fou » →
   télécharger Qwen2.5 0,5B (et 1,5B), puis remettre le nettoyage sur
   « Désactivé ». Pour Gemma 3 1B (accès restreint) : le télécharger sur un
   ordinateur après avoir accepté la licence, puis
   `adb push gemma3-1b-it-int4.task /sdcard/Android/data/io.github.remtav.dictee/files/llm/`.
2. Construire et installer l'application et l'APK de test, depuis `android/` :
   `./gradlew installDebug installDebugAndroidTest`.
3. Lancer l'enregistrement (un modèle à la fois ; 1 ou 2 appels au LLM par
   segment, compter plusieurs minutes) :

   ```
   adb shell am instrument -w \
     -e class io.github.remtav.dictee.LlmPolishRecordingTest \
     -e llmModel llm-qwen2.5-0.5b-instruct-q8 \
     io.github.remtav.dictee.test/androidx.test.runner.AndroidJUnitRunner
   ```

   Variantes : `-e llmModel llm-qwen2.5-1.5b-instruct-q8` ;
   `-e llmModel /sdcard/Android/data/io.github.remtav.dictee/files/llm/gemma3-1b-it-int4.task -e llmTemplate gemma -e llmName gemma3-1b` ;
   `-e nearMatch true` pour le garde-fou avec proximité (changer aussi
   `llmName`). Suivi : `adb logcat -s LlmPolishRecording`.
4. Récupérer les fichiers :
   `adb pull /sdcard/Android/data/io.github.remtav.dictee/files/polissage/ .`
   puis copier les `llm-*.tsv` dans `app/src/test/resources/polissage/sorties/`
   (le noteur les reprend tels quels, latence « appareil ») et reporter
   `memoire-<nom>.txt` dans le rapport.
5. `./gradlew testDebugUnitTest --tests '*PolishEvaluationTest*'` : tableau
   avec qualité réelle et latence p50 / p95 mesurée sur le téléphone.

Fichiers écrits : `llm-<nom>-prompt-large.tsv` (approche 3, après
garde-fou), `llm-<nom>-prompt-large-sans-gardefou.tsv` (mêmes sorties avant
garde-fou : ce que le garde-fou a empêché), `llm-<nom>-combinaison.tsv`
(approche 4 ; latence du segment entier, presque nulle quand le LLM n'est
pas appelé), `memoire-<nom>.txt` (PSS et VmRSS / VmHWM avant chargement,
après chargement, après les passages ; durée de chargement). Un passage
d'échauffement précède les mesures. Le test est ignoré sans `llmModel` : la
CI (émulateur) ne charge jamais MediaPipe.

### Risques

- **C'est précisément ce genre de passe qui peut abîmer un mot correct.** Le
  prompt demande de ne rien toucher d'autre, mais un modèle de 0,5 ou
  1,5 milliard de paramètres ne suit pas toujours la consigne ; seule la
  dérivation stricte du garde-fou l'empêche d'arriver dans le texte. Sans
  garde-fou, le LLM simulé abîme 13 contrôles sur 53, dont 4 mots rares.
- **Limites connues, acceptées par construction** (épinglées dans
  `knownLimitsAreAcceptedByConstruction`) : une entrée du lexique qui est
  aussi une expression ordinaire (`des jardins` → `Desjardins`) ; avec la
  proximité, un mot ordinaire proche d'un nom (`la team` → `la Teams`) ;
  deux prénoms joints (`Pierre Jean` → `Pierre-Jean`). Le garde-fou ne voit
  pas le contexte : la proximité reste désactivée par défaut et le lexique
  livré (3.2) devrait éviter les noms qui sont aussi des mots courants.
- **Le garde-fou ne remplace pas la mesure** : un plafond de 36 à 44 cas sur
  79 ne dit rien de la part que Qwen atteindra ; les « rien fait » coûtent de
  la latence pour rien.
- **MediaPipe LLM Inference ne bouge plus sur Android** : un éventuel
  passage en production devra peut-être passer par LiteRT-LM (nouvelle
  décision).

## Issue #15 — demande de l'utilisateur : pouvoir couper le polissage (« friperie »)

Demande : « Le mot "friperie" sortait bien avant que le petit LLM repasse dessus
pour du polissage. J'ai l'impression que le petit LLM détériore parfois la
qualité de l'entrée. Ce serait mieux de donner au moins l'option de le
désactiver. »

### Constaté

- Deux LLM peuvent repasser sur le texte :
  - **Nettoyage local** (`cleanup/LlmCleaner.kt`, `AppSettings.cleanupMode`) :
    déjà désactivable (`OFF` par défaut ; `DicteeApp.cleaner()` renvoie
    `TextCleaner.NONE` en `OFF`, et `DicteeApp` ne crée même pas de
    `LazyCleaner`). Son garde-fou (`text/Filters.kt`) est local ; il ne s'active
    que si l'utilisateur a choisi « LLM ».
  - **Polissage du serveur** (modèle « Serveur », dictée entière, #13) :
    `RemoteEngine` envoyait toujours `polish=true` et insérait `polished_text`.
    **Aucun réglage ne permettait de le couper.** C'était le suspect principal
    à ce moment (écarté ensuite : voir « Suite du diagnostic » plus bas).
- Le serveur (`app/main.py`, champ `polish: Optional[bool]`) retombe sur
  `WHISPER_POLISH` quand le champ est absent : un serveur démarré avec
  `WHISPER_POLISH=on` polirait même si le client n'envoie rien. Le client
  envoie donc **`polish=false` explicitement** quand le réglage est coupé.
- Sans polissage, le serveur ne renvoie ni `polish` ni `polished_text` utile :
  l'ancien code aurait lu « not returned » et affiché « Texte non poli ».

### Ajouté

- Réglage **« Polissage par le serveur »** (Réglages, section Serveur distant ;
  `AppSettings.remotePolish` → `RemoteConfig.polish`). Coupé : `polish=false`
  (API à tâches et repli synchrone), texte brut inséré, `polishStatus =
  "not requested"` (aucun message d'erreur), `polished_text` ignoré même si le
  serveur en renvoie un. Le texte d'aide dit que le polissage corrige ponctuation
  et hésitations mais qu'un LLM peut remplacer un mot bien transcrit.
- Texte d'aide du nettoyage local complété par la même mise en garde.
- Tests (`WholeDictationTest`) : `polish=false` envoyé et texte brut inséré ;
  cas « friperie » (le serveur renvoie « fripperie » poli : coupé → « friperie »,
  actif → mot altéré) ; repli synchrone ; réglage actif inchangé.

### Défaut retenu : activé

Le comportement par défaut de l'application ne change pas dans cette itération,
et l'utilisateur coupe lui-même le réglage. **À trancher par l'utilisateur** :
passer le défaut à désactivé (le plus sûr pour la fidélité des mots, au prix de
la ponctuation et des hésitations non retirées). Un changement d'une ligne
(`getBoolean("remote_polish", true)`).

### Non fait

- **Retour au texte brut après coup** : le clavier garde `rawText` et
  `polishedText` dans `DictationResult` (`lastDictation`), mais rien ne permet de
  remplacer le texte déjà inséré (il faudrait suivre la plage insérée dans le
  champ, qui peut avoir été modifiée entre-temps) : plus qu'un petit changement.
  Piste : bouton « Texte brut » à côté de « Annuler » juste après l'insertion,
  qui remplace la plage insérée tant que le curseur n'a pas bougé.
- **Cause côté serveur** : le garde-fou de `app/polish.py` laisse passer certaines
  substitutions de mots (« friperie » → un mot voisin). Hors portée (#6, #13) :
  à traiter dans une issue séparée sur le serveur.
- Test instrumenté du réglage : aucun test existant des Réglages ne s'y prête
  sans fragilité.

### Ce que l'utilisateur doit dire

Avec quel modèle le mot a été changé : **« Serveur »** (donc le polissage du
serveur ; question posée avant la suite du diagnostic, qui l'a écarté) ou un **modèle du téléphone** (Parakeet, Qwen3-ASR,
Whisper) avec le nettoyage réglé sur « LLM » (le nettoyage est `OFF` par défaut :
à vérifier dans Réglages > Nettoyage du texte). Test simple : couper le
polissage, redicter « friperie » ; si le mot sort bien, le LLM du serveur était
en cause.

### Suite du diagnostic (2026-11-12) et arrêt de l'enquête

Chronologie :

1. **Polissage du serveur** : suspect de départ ; l'interrupteur ci-dessus a
   été ajouté. Écarté : l'utilisateur dictait avec un modèle du téléphone.
2. **LLM local** (« Petit LLM local + garde-fou ») : écarté par l'utilisateur,
   le nettoyage était sur « Désactivé ». Le trou trouvé dans le garde-fou est
   réel et corrigé (ci-dessous), mais ce n'était pas la cause.
3. **Texte provisoire puis décodage final** : l'utilisateur voyait
   « friperie » s'afficher, puis changer quelques millisecondes plus tard.
   Non conclu (voir constats).
4. **Langue non transmise** : mot dicté seul, avec Parakeet et avec
   Qwen3-ASR ; les deux rendent des mots qui ressemblent à de l'anglais deviné.
   Pas de correction de vocabulaire. Non conclu.

**Enquête arrêtée à la demande de l'utilisateur le 2026-11-12** : plusieurs
mots québécois échouent. Il préfère prioriser un modèle déjà affiné sur le
français québécois. Aucune mesure n'a été faite sur un enregistrement de
« friperie » : il n'en existe pas dans le dépôt.

#### Constats vérifiés

- **Audio du provisoire et du final** (`DictationController.captureLoop`,
  sources de sherpa-onnx 2.0.1 `voice-activity-detector.cc` et
  `silero-vad-model.cc`, lues sur GitHub au tag `v2.0.1`) :
  - le provisoire décode `ring.last(len)`, soit depuis la détection de parole
    moins `LOOKBACK` (0,6 s) jusqu'à l'instant présent ; le dernier provisoire
    avant le final contient donc jusqu'à ~0,5 s de silence après le mot ;
  - le final décode le segment du VAD. Il commence à
    `Tail - 2 × fenêtre - min_speech` au moment du déclenchement (~0,1 s avant
    la première fenêtre jugée parole). Il finit à `Tail - min_silence`, soit
    juste après la première fenêtre sous le seuil bas (0,35) : aucune marge
    après le mot ;
  - au-delà de 20 s sans pause, le VAD passe à `min_silence` 0,1 s et à un
    seuil de 0,9, et peut couper au milieu d'une phrase.
  - Sur l'hôte, en rejouant les 9 extraits de référence avec le vrai Silero, les
    segments finals commencent 0,29 s après la fenêtre du provisoire et
    finissent 0,5 s avant elle. Mots faux sur 141 : Parakeet, extrait entier 6,
    final 9, provisoire le plus large 7, final avec 0,3 s de marge 9. Qwen3-ASR :
    entier 11, final 6, provisoire 7, final avec marge 6. Les écarts vont dans
    les deux sens et une marge de 0,3 s n'améliore rien : **aucune correction
    sûre trouvée**, rien n'a été changé dans le découpage. Phrases entières
    seulement, pas de mots isolés.
- **Langue** (classes de l'AAR 2.0.1 lues par `javap`, plus sources C++
  `offline-recognizer-qwen3-asr-impl.cc` et l'exemple officiel
  `python-api-examples/offline-qwen3-asr-decode-files.py`) :
  - **Qwen3-ASR** : `OfflineQwen3AsrModelConfig` n'a pas de champ de langue
    (seulement `hotwords`), mais le flux en accepte une :
    `OfflineStream.setOption("language", "French")`. `BuildSourceIds` écrit
    alors « language French » dans le prompt au lieu de laisser le modèle
    deviner. **Désormais transmise** (`SherpaEngine`, `AppSettings.engineOptions`,
    `DictationLanguages.qwen3Language`), avec la même règle que Whisper : un
    seul code de langue choisi. Le nom vient d'une table (fr → French,
    en → English, zh, hi, es, ar, pt, ru). Bengali et ourdou ne sont pas des
    langues de Qwen3-ASR : détection automatique. **Effet non mesuré** à ce
    stade : la mesure sur l'hôte a été interrompue (mémoire, puis arrêt de
    l'enquête). Mesuré ensuite par la revue indépendante du lot 3 (dernière
    section) : pas de dégradation sur les sept extraits français.
  - **Parakeet TDT v3** : aucune entrée de langue. `OfflineTransducerModelConfig`
    n'a qu'encodeur, décodeur, joiner et `qnnConfig`, et
    `offline-recognizer-transducer-nemo-impl.h` ne lit aucune option de flux.
    La langue est implicite dans le modèle. Sur un mot seul, il a très peu de
    contexte pour la deviner. L'application ne lui passe pas de hotwords (décodage
    `greedy_search`) ; aucun palliatif n'a été essayé.
  - Pour l'orchestrateur (table de recommandation inchangée) : avec une seule
    langue choisie, Whisper et désormais Qwen3-ASR reçoivent la langue, pas
    Parakeet.

#### Whisper « en dernier recours » (observation de l'utilisateur, 2026-11-12)

Sur son téléphone, Whisper transcrit bien les mots d'ici, dont ceux que
Parakeet et Qwen3-ASR prennent pour de l'anglais, mais trop lentement pour un
usage courant. Aucune latence n'a été mesurée sur appareil. Sur l'hôte, les
facteurs temps réel indicatifs (étape 1.2) sont de 0,07 pour Parakeet, 0,3 pour
Whisper et 0,6 à 0,9 pour Qwen3-ASR. Ils ne prédisent pas l'ordre sur le
téléphone. Changements :

- nouveau champ `ModelSpec.note`, affiché sous la description de chaque carte
  de modèle (`SetupActivity`, style `BodySmall` du thème, pas d'avertissement).
  Seul Whisper en a une : `ModelCatalog.WHISPER_NOTE`, formulée comme une
  observation (« Observé sur un téléphone : … À garder en dernier recours … ») ;
- la raison affichée quand la table désigne Whisper (bengali, ourdou) ajoute
  « Il est plus lent que les deux autres (observé sur un téléphone) ». La
  logique de la table n'a pas changé ;
- `README.md`, section Modèles, donne la même précision.

#### Garde-fou du LLM local (durcissement, hors cause)

- **Trou trouvé** (`text/Filters.kt`, ancien `SafetyCheck.check`) :
  - les segments de 8 mots ou plus toléraient jusqu'à 3 mots « nouveaux »
    (8 % des mots de contenu). À partir d'environ 13 mots de contenu, un mot
    changé passait : « friperie » → « fripperie », « friperies »,
    « boutique »… ;
  - `Normalize.fold` retirait les accents et la casse : « cégep » → « cegep »
    passait, même dans une phrase courte ;
  - la comparaison se faisait par ensemble, donc l'ordre était ignoré ;
  - les mots grammaticaux étaient exclus du contrôle : un mot remplacé ou
    supprimé passait (« la friperie » → « la ») ;
  - jusqu'à 25 % (50 % en phrase courte) des mots de contenu pouvaient
    disparaître ;
  - les mots du vocabulaire comptaient comme permis partout.

  Le test `SafetyCheckTest` reproduit ces cas : il échouait sur l'ancien code
  (10 tests sur 19).
- **Correction** : `SafetyCheck` (implémente la nouvelle interface
  `CleanupGuard`) exige que chaque mot de la sortie soit un mot de l'entrée,
  identique, dans le même ordre. Normalisation minimale : NFC, apostrophe et
  trait d'union typographiques unifiés, ponctuation et espaces ignorés, casse de
  la première lettre seulement. Le trait d'union fait partie du mot
  (« peut-être » ≠ « peut être »). Les mots retirés doivent former des suites
  qui sont chacune :
  - des hésitations pures (euh, um… ; « ben », « bon », « like », « quoi » n'en font plus partie, car ils ont un sens) ;
  - une répétition des mots voisins ;
  - un faux départ repris juste après (« on va au, on va à ») ;
  - une autocorrection terminée par un marqueur détaché par une virgule
    (« lundi, non, » / « je veux dire, »).

  Les contrôles négation, nombres et formule d'assistant sont gardés. Le
  vocabulaire n'est plus passé au garde-fou : le LLM n'est plus rechargé quand
  il change. Le prompt demande en plus de ne jamais corriger ni réépeler un
  mot.
- Le texte d'aide du réglage décrit la règle exacte (il remplace la mise en
  garde ajoutée plus haut) : le nettoyage ne corrige jamais un mot mal entendu.
- Une variante assouplie pour l'évaluation (lot 3, étape 3.3) implémentera
  `CleanupGuard` à côté, sans toucher `SafetyCheck`. `LlmCleaner.createFromPath`
  accepte un `guard`. Défaut inchangé : `CleanupMode.OFF`.
- Limites : la sortie réelle de Qwen2.5 (MediaPipe, arm64) n'a pas été rejouée.
  Le garde-fou strict rejettera plus de nettoyages : une autocorrection sans
  virgule, ou une reformulation de ponctuation qui fusionne des mots. Le texte
  nettoyé par règles est alors gardé.

## Issue #15, lot 3 — étape 3.4 : rapport et recommandation

Rapport : `android/docs/POLISSAGE_LOCAL.md` (question, jeu et ses limites,
tableau comparatif mesuré / simulé / à faire sur le téléphone, cas
`coifferimouski.com`, risques, recommandation, procédure de mesure, décision à
prendre). Lien depuis `android/README.md`.

### Raccord de 3.2 et 3.3 (approche 4)

- Nouveau fichier `app/src/main/java/io/github/remtav/dictee/cleanup/eval/WiredCombination.kt` :
  - `WiredRules.apply` : `PolishRules.polish`, puis `ShippedVocabulary.apply`.
    **Choix** : l'étage déterministe est « règles + vocabulaire », pas les
    règles seules, parce que c'est la meilleure approche sans LLM (55/79,
    0 contrôle abîmé). La combinaison mesure ainsi ce qu'un LLM ajoute à ce
    qu'on livrerait sans lui.
  - `RuleSignalDetector` : envoie au LLM les segments où
    `PolishRules.assess(brut)` donne `PROBABLE_CASE` ou `hasAddress` (la
    proposition de 3.2). Les noms mal transcrits ne sont pas envoyés : c'est la
    définition de l'approche 4 dans l'issue (LLM seulement là où une règle
    signale un cas probable). Le LLM est appelé sur 22 segments sur 132
    (21 cas à corriger : web 9, courriel 10, épellation 2 ; 1 contrôle,
    `fr-web-04-whisper`, une adresse déjà écrite).
  - `ShippedLexicon.names` : formes correctes de `ShippedVocabulary`
    (lexique du garde-fou assoupli, à la place du lexique de simulation).
  - `wiredCombination(llm, allowLexiconNear, template)`.
- `ProvisionalProbableCaseDetector` est gardé pour comparaison, il n'est plus
  utilisé par la combinaison.
- Le raccord est dans `cleanup/eval/` (et non dans `text/polish/`) : les règles
  ne dépendent pas du code d'évaluation du LLM.

### Simulations régénérées et ajoutées

`src/test/resources/polissage/sorties/` :
`simulation-combinaison-oracle` et `simulation-combinaison-bruyant` (approche 4
raccordée : 56/79, 2 modifiés à tort, 0/53 abîmés, oracle comme bruyant) ;
nouveaux `simulation-llm-oracle-gardefou-lexique-livre` (36/79) et
`simulation-llm-bruyant-gardefou-lexique-livre` (13/79), l'approche 3 avec le
lexique qu'on livrerait. Les autres simulations de 3.3 sont inchangées.

Plafonds épinglés dans `LlmSimulationTest` (LLM oracle) :

| Variante | Corrigés / 79 |
| --- | --- |
| Approche 3, lexique livré | 36 |
| Approche 4 raccordée, avec ou sans proximité | 56 |
| Règles + vocabulaire puis LLM sur **tous** les segments | 57 (58 avec proximité) |

Conclusion tirée dans le rapport : derrière le garde-fou, un LLM, même
parfait, n'ajoute qu'un à trois cas aux 55 de règles + vocabulaire ; la
mesure sur le téléphone ne peut pas renverser ce plafond.

### Autres changements

- Nouvelle approche notée `regles+vocabulaire-general` (règles + vocabulaire
  sans les 20 formes `OBSERVED`) : 36/79, 6 modifiés à tort, 0/53 abîmés.
  C'est la borne basse de la meilleure approche, que le rapport cite à côté
  de 55/79.
- Noteur : pour une latence « appareil », compte des segments au-delà de 4 s
  (délai de `LlmCleaner`). Il apparaît dans `resultats.md`
  (`… (appareil, 3/132 > 4 s)`) et dans `resultats.json` (`au_dela_4s`).
  Testé dans `PolishScorerTest`.
- `LlmPolishRecordingTest` : garde-fou avec le lexique livré, combinaison
  raccordée (`wiredCombination`, même gabarit et même option de proximité).
  Procédure dans le rapport.
- `DefaultPathUnchangedTest` : seul `cleanup/eval/` peut citer les règles. Un
  nouveau test vérifie qu'aucun fichier hors de `cleanup/eval/` ne cite le
  paquet d'évaluation du LLM (garde-fou assoupli, prompt élargi, détecteurs,
  combinaison). Un autre vérifie que `text/polish/` ne dépend pas de
  `cleanup.eval`.

### Comportement par défaut, vérifié

- Recherche dans `app/src/main` : seuls `cleanup/eval/` et `text/polish/`
  citent ces classes. Aucun appel depuis `DicteeApp`, `TextPipeline` ou
  `SettingsActivity`. `CleanupMode.fromName` retombe sur `OFF`.
- Changements « friperie », relus dans les commits `fc65b55` et `8c1eb05` :
  - **un seul changement de défaut**, la langue transmise à Qwen3-ASR quand une
    seule langue est choisie (le choix initial, « Français (Québec) » seul,
    est dans ce cas). Il n'avait pas été mesuré à cette étape ; le rapport le
    dit dans une section à part (mesuré depuis par la revue indépendante :
    voir la dernière section) ;
  - `remote_polish` vaut `true` par défaut, et le client envoie `polish=true`
    comme avant ;
  - le garde-fou strict et le nouveau prompt ne jouent qu'en mode LLM, qui
    n'est pas le défaut ;
  - le reste ne change que des textes affichés.

### Recommandation (résumé)

- Rien n'est activé par défaut.
- Ne pas livrer de LLM de polissage.
- Dans une itération suivante, au choix de l'utilisateur, une option
  désactivée par défaut : d'abord les règles, puis le vocabulaire livré après
  relecture.
- Aucun passage en défaut sans zéro contrôle abîmé sur de vraies dictées
  québécoises absentes du jeu.
- Priorité au modèle de transcription affiné sur le français québécois.

## Issue #15 — demande de l'utilisateur : modèle affiné sur le français québécois

**Portée.** L'issue classait « l'ajout de nouveaux modèles au catalogue » hors portée. La demande explicite de
l'utilisateur (« vérifie si tu es capable de trouver ce modèle et si oui, ajoute-le ») la remplace pour ce modèle
précis seulement ; rien d'autre n'est ajouté.

**Le modèle (trouvé, identifié sans ambiguïté).** `ele-sage/whisper-large-v3-turbo-fr-quebecois`
(https://huggingface.co/ele-sage/whisper-large-v3-turbo-fr-quebecois), compte communautaire `ele-sage`, créé le
2025-11-10, modifié le 2025-12-28. Fiche : « It achieves a Word Error Rate (WER) of 6.65% on the validation set »,
dernière ligne du tableau d'entraînement (pas 2400 : 6,65 %). Base : `openai/whisper-large-v3-turbo` (809 M de
paramètres, 4 couches de décodeur, la même architecture que le Whisper du catalogue). Données : ~69 h, soit
Assemblée nationale du Québec (13 h), Common Voice « Français du Canada » (16,5 h) et Common Voice autres
français (39 h), 1 époque. **Le jeu de validation n'est pas décrit** (ni sa composition, ni s'il est disjoint de
l'entraînement) : le 6,65 % n'est comparable ni à nos six extraits ni au WER de l'article cité par l'issue.
**Licence : MIT** (déclarée sur la fiche ; les données d'entraînement ont leurs propres licences, non vérifiées ici).
Formats publiés : safetensors (3,2 Go, fp32) ; dépôt jumeau `…-ct2` (CTranslate2 fp16). Autres dépôts qui le
reprennent (GGML : `MaderaTools/…`, CT2/OpenVINO/GGML : `ArchTianui/…`). Autres candidats « québécois » trouvés et
écartés : `rishabbahal/whisper-small-quebecois-1` (adaptateur LoRA, pas de WER, pas de licence),
`micross50/Whisper_FineTuned_Quebecois` (aucune fiche). Le seul avec 6,65 % est celui d'ele-sage.

**Conversion sherpa-onnx.** Aucune n'existe (recherche sur Hugging Face : `csukuangfj`, `k2-fsa` et les comptes qui
publient des Whisper turbo ajustés ; aucun pour ce modèle). Conversion faite ici, hors dépôt : poids transformers →
format OpenAI (`hf_vers_openai.py`) → export `scripts/whisper/export-onnx.py` de sherpa-onnx (`exporter.py`) →
int8 (`quantifier_int8.py`). Trois écarts à la procédure habituelle, documentés dans
`android/tools/whisper-hf-vers-sherpa/README.md` : l'exporteur TorchScript (`dynamo=False`) parce que celui de
torch 2.15 casse le décodeur ; une quantification int8 sobre en mémoire parce que celle d'onnxruntime dépasse 7 Go ;
rien d'autre n'est modifié. Sortie : 674 348 342 + 360 961 539 octets (Whisper turbo d'origine : 674 716 297 +
361 080 764), `tokens` identique. Contrôle : `openai-whisper` sur le point de contrôle converti, puis
onnxruntime sur les ONNX int8, puis `SherpaEngine` : mêmes phrases que ci-dessous.

**Mesures ici** (hôte Linux x86-64, 4 fils, sherpa-onnx 2.0.1, mêmes extraits `assets/reference`, même exécution de
`ModelAccuracyHostTest`). WER, en erreurs / mots :

| Modèle | en (2 extraits, 66 mots) | fr1 (16 mots) | frqc1..6 (59 mots) |
|---|---|---|---|
| Parakeet | 3,0 % (2) | 0,0 % (0) | 6,8 % (4) |
| **Whisper QC** | 4,5 % (3) | 0,0 % (0) | **8,5 % (5)** |
| Whisper turbo | 4,5 % (3) | 6,3 % (1) | 10,2 % (6) |
| Qwen3-ASR | 4,5 % (3) | 0,0 % (0) | 13,6 % (8) |

Par extrait québécois (WER) : Whisper QC 9,1 / 14,3 / 33,3 / 0 / 0 / 0 ; Whisper turbo 9,1 / 14,3 / 44,4 / 0 / 0 / 0.
Le modèle affiné est donc à une erreur de moins que Whisper turbo et à une de plus que Parakeet sur 59 mots :
**aucune différence significative** (un mot vaut 1,7 point). Il garde son anglais (même WER que Whisper turbo).
Temps réel (RTF moyen, mêmes exécutions) : Parakeet 0,19 ; Qwen3-ASR 0,79 ; Whisper turbo 1,00 ; Whisper QC 1,16.
**Ces RTF sont bruités** : la machine exécutait en parallèle les tests d'autres agents (le RTF d'un même extrait
varie de 0,3 à 2,2 d'une exécution à l'autre pour le même modèle). La seule lecture fiable : Whisper QC a exactement
l'architecture, la taille (1 036 Mo contre 1 037 Mo) et donc le coût de Whisper turbo, ~5 fois celui de Parakeet.
Ce que l'utilisateur reproche à Whisper turbo sur son téléphone (trop lent) vaut donc pour ce modèle aussi.
Non mesuré : le mot « friperie » dicté seul (pas d'enregistrement ; à essayer sur le téléphone une fois le modèle
publié).

**Hébergement : publié le 2026-11-12, sous le compte de l'utilisateur, à sa demande.** L'issue mettait l'ajout de
modèles hors de sa portée ; l'utilisateur l'a demandé explicitement, et a lui-même publié la conversion (dépôt public,
non restreint) : `https://huggingface.co/remiavelo/sherpa-onnx-whisper-large-v3-turbo-fr-quebecois-int8`. Sommes et
tailles vérifiées sur le serveur (identiques à `ModelCatalog.whisperQuebec`). `WHISPER_QC` est renseignée, donc
`ModelCatalog.all` contient le modèle (écran d'installation, Réglages, comparaison). Le test hôte
`ModelAccuracyHostTest.whisperQuebec`, lancé réellement avec le téléchargeur de l'application, a téléchargé les trois
fichiers (sommes vérifiées) et mesuré : en 4,5 % (3/66), fr 0 % (0/16), frqc 8,5 % (5/59) ; plafonds identiques à ceux
de Whisper turbo. Limite : le proxy de cette session ajoute un jeton aux requêtes vers huggingface.co, donc ce test ne
prouve pas l'accès anonyme (le dépôt est public d'après l'API). Carte : texte honnête (WER annoncé, mesure ici et sa
limite, français seulement) et note « même coût que Whisper, donc plus lent » (observation sur Whisper turbo, pas une
mesure sur appareil).

**Couverture de langues** (`ModelRecommendations.coverage`) : français (Québec et France) seulement ; la logique de
recommandation n'est pas modifiée (un test vérifie que la table ne le recommande jamais). Les mesures ne justifient
pas de le recommander à la place de Parakeet pour « Français (Québec) » : 8,5 % contre 6,8 %, et un coût cinq
fois plus élevé.

**CI.** `ModelAccuracyHostTest.whisperQuebec` n'est plus ignoré : le job `models` télécharge ~1 Go de plus (total
~3,8 Go de modèles, largement dans le disque d'un runner `ubuntu-latest`) et décode six extraits de plus (environ
+2 minutes, très en dessous du délai de 60 min ; le cache est indexé sur `ModelCatalog.kt`, donc invalidé par ce
changement). Le workflow n'est pas modifié (approbation de Rémi) ; seul son titre « 3 models » est devenu inexact.

**À valider par l'utilisateur** : redistribuer une conversion int8 d'un modèle MIT est permis par la licence ;
l'auteur est cité sur la carte (éditeur « ele-sage (communauté), d'après OpenAI », licence MIT). Le jeu
d'entraînement inclut les débats de l'Assemblée nationale et Common Voice (CC0) : rien ne l'interdit, mais je n'ai
pas vérifié leurs conditions d'utilisation pour un modèle dérivé ; à trancher avant de publier le dépôt de conversion.

## Issue #15 — demande de l'utilisateur : accélérer Whisper large-v3-turbo

Enquête menée le 2026-11-12, sans modification du code de l'application. Rapport complet, avec sources, mesures hôte et
procédure de mesure sur téléphone : [`docs/ACCELERER_WHISPER.md`](docs/ACCELERER_WHISPER.md). Test de mesure ajouté :
`app/src/test/java/io/github/remtav/dictee/WhisperSpeedHostTest.kt` (sauté sans `-PmodelsDir`).

Constats vérifiés dans le code de sherpa-onnx 2.0.1 et dans les fichiers du modèle :

- L'encodeur de l'export sherpa-onnx accepte une longueur variable : ce n'est **pas** une fenêtre fixe de 30 s. Mais
  sherpa-onnx ajoute **1 000 images (10 s) de silence** à chaque segment (`tail_paddings`, défaut -1 = 1 000) ; l'AAR
  2.0.1 expose `OfflineWhisperModelConfig.tailPaddings`, que `SherpaEngine` ne renseigne pas.
- Le décodeur a déjà un cache KV et un décodage glouton (le seul que sherpa offre pour Whisper) ; la langue forcée est
  déjà transmise (elle n'économise qu'une étape de décodeur : 0 à 6 % mesuré).
- Le modèle int8 est quantifié dynamiquement (`MatMulInteger`, `DynamicQuantizeLinear`), opérateurs absents des listes de
  XNNPACK et de NNAPI d'onnxruntime : pas de gain attendu de ces fournisseurs, risque de ralentissement.

Mesures hôte (x86-64, 4 cœurs, machine partagée : tendances, pas la latence du téléphone) : sur 1 thread, la fenêtre fixe
de 30 s coûterait 8,4 s contre 2,1 s pour la marge par défaut, 1,3 s à 600 et 0,86 s à 300 (mot de 1 s) ; à 300 des
répétitions apparaissent (WER 14,7 % contre 9,3 %), à 100 la sortie est inutilisable (42,7 %). Les provisoires consomment
2,8 fois le calcul du final pour 5 s de parole et le final attend en moyenne 1,5 s (jusqu'à 5,9 s) derrière un provisoire
non interruptible.

Décisions proposées (à confirmer par l'utilisateur ; rien n'est appliqué) :

1. **Couper le texte provisoire pour Whisper** (une ligne dans `DicteeApp.kt`) : gain le plus sûr, faisable tout de suite.
2. **Ajouter un réglage `tailPaddings`** (`EngineOptions`, `SherpaEngine`, `AppSettings`), défaut inchangé (1 000) ; passer
   à 600 seulement après mesure sur le téléphone avec des voix réelles et des mots isolés ; ajouter un filtre de phrase
   répétée si 300 à 600 est retenu.
3. **Mesurer sur le téléphone** (procédure du rapport) le nombre de threads (3 à 5), XNNPACK et NNAPI avant tout changement
   de `defaultThreads()` ou du fournisseur par défaut.
4. **Écarter** : Whisper small (WER 16 % contre 9,3 %), medium, tiny/base, distillations (même encodeur, donc pas plus
   rapides sur segments courts ; distil-large-v3 est anglais seulement), whisper.cpp, QNN (étude séparée si Whisper devient
   courant). Le modèle québécois en évaluation par ailleurs a la même architecture donc la même vitesse : ces leviers
   lui sont applicables tels quels.

### Mise en œuvre du 2026-11-12 (les deux gains les plus sûrs, plus l'écran de comparaison)

Décisions 1 et 2 ci-dessus appliquées à la demande de l'utilisateur ; la 3 reste à faire sur le téléphone, la 4 inchangée.

- **Plus de texte provisoire avec Whisper** (turbo et québécois) : `WhisperTuning.provisionalAllowed(spec, réglage)`
  (`stt/WhisperTuning.kt`) est faux pour Whisper et pour le serveur, quel que soit le réglage « Texte provisoire » ; il le
  suit pour Parakeet et Qwen3-ASR. Pas de réglage de remplacement : l'aide du réglage dit qu'il est sans effet avec Whisper.
  Raison : mesuré sur l'hôte, les provisoires consomment 2,8 fois le calcul du final et le retardent de 1,5 s en moyenne.
  Aucun test existant ne supposait du texte provisoire avec Whisper (`ControllerTest` et `ImeInstrumentedTest` utilisent un
  moteur simulé de spec Parakeet ou le réglage seul ; vérifié).
- **Réglage avancé « Silence ajouté pour Whisper »** (Réglages > Performance) : champ sherpa-onnx 2.0.1
  `OfflineWhisperModelConfig.tailPaddings`, `Int` (vérifié par `javap`). `EngineOptions.whisperTailPaddings`, stocké par
  `AppSettings.whisperTailPaddings`. Choix : **1 000 (défaut, valeur d'origine)**, 600 (plus rapide), 300 (encore plus
  rapide, peut répéter des phrases) ; une valeur stockée hors de ces trois se relit 1 000. Passé à Whisper seulement ; pour les
  autres modèles `engineOptions` met toujours 1 000, donc la clé de `EngineManager` ne change pas et rien ne se recharge ;
  pour Whisper, changer la valeur recharge le modèle.
- **Défaut gardé à 1 000** : le comportement ne change pas tant que l'utilisateur n'a pas validé 600 sur son téléphone (la
  mesure hôte, −36 % de calcul à 600, ne dit rien de la qualité sur ses voix et ses mots isolés ; WER hôte 14,7 % contre
  9,3 % à 300).
- **« Comparer les modèles »** : `Benchmark` accepte des options par modèle ; l'écran passe `engineOptions(modèle)` (langue
  forcée si une seule langue, threads, accélération, silence ajouté) au lieu d'options calquées sur le serveur. Effet de bord
  voulu : Qwen3-ASR y reçoit aussi sa langue forcée et ses mots favorisés, comme au clavier. L'en-tête du rapport indique la
  valeur du silence ajouté et la langue de Whisper.
- **Tests** : `WhisperTuningTest` (provisoire, défaut, valeurs acceptées, valeur transmise à Whisper seulement, clé du moteur) ;
  `ModelAccuracyHostTest.whisperTurbo*` (avec `-PmodelsDir`) vérifie que Whisper se charge avec le nouveau champ.
- **Pas fait, volontairement** : filtre de phrase répétée, threads par défaut, décodage à blanc, XNNPACK/NNAPI/QNN.
- **À mesurer sur le téléphone** : temps et texte de « friperie », d'une phrase de 5 s et de 15 s à 1 000, 600 et 300 (écran
  « Comparer les modèles », une exécution par valeur) ; ne passer le défaut à 600 que sans répétition ni mot manquant sur
  5 essais par valeur (ce serait un changement de défaut, donc une nouvelle décision) ; puis les threads (3 à 5).

## Issue #15, lot 4 — étape 4.1 : texte de lecture, normalisation, consentement

État au 2026-11-13. **Fait** : texte `lecture-qc-1` et sa table d'équivalences (`app/src/main/assets/lecture/`),
paquet pur `reading/` (`ReadingText`, `ReadingMaterial`, `ReadingNormalizer`, `Equivalences`, `FrenchNumbers`,
`ReadingScoring`, `ReadingThresholds`, `Contribution`), `Levenshtein.align` dans `text/Normalize.kt`, chaînes
`contrib_*`, `docs/CONTRIBUTION.md`, tests JVM (`app/src/test/.../reading/`, 36 tests). **Reste à faire (autres
étapes)** : écrans (4.2), consentement au partage, archive et courriel (4.3), README (4.3), revue (4.R). Aucun écran ni
export dans cette étape.

### Texte de lecture (`lecture-qc-1`)

Petit récit du quotidien, 9 paragraphes, **224 mots** affichés (16 à 34 mots par paragraphe, soit 7 à 14 s à
2,5 mots/s : sous la limite de 20 s d'un segment ; plafond codé `ReadingText.MAX_PARAGRAPH_WORDS = 40`). Registre
familier, sans sacre, nombres en lettres, espaces insécables avant le deux-points et dans les guillemets, aucune avant
« ! » (typographie québécoise du lot 2).

**Format** : un fichier texte par version dans les *assets* (`lecture/<version>.txt` : commentaires `#`, une ligne
`version:`, paragraphes séparés par une ligne vide), lu par `ReadingMaterial.load(readAsset)`. Pas des chaînes :
le texte est une donnée, pas une interface ; il ne doit pas être traduit ni réparti en clés, et sa version doit
voyager avec lui (l'archive de 4.3 pourra le citer tel quel). Un texte publié n'est jamais modifié : une nouvelle
version est un nouveau fichier (`lecture-qc-2.txt`), ce que `ReadingTextTest` encadre (version = nom de fichier,
unique).

Couverture de la liste de départ :

| Élément | Présent / écarté | Forme dans le texte |
| --- | --- | --- |
| dépanneur, magasiner, tuque, mitaines, char, cellulaire, courriel, clavardage, poudrerie, cabane à sucre, blonde, chum | présent | tel quel |
| fin de semaine | présent | « en fin de semaine » |
| banc de neige | présent | au pluriel, « les bancs de neige » |
| déjeuner / dîner / souper | présent | « pour le déjeuner », « Au dîner », « on soupe » (verbe) |
| piastre | présent | « vingt piastres » (pluriel) |
| friperie (ajout de l'utilisateur) | présent | « une petite friperie » |
| avoir de la misère | présent | « j'ai toujours de la misère à me décider » |
| il fait frette | présent | « il faisait frette » (imparfait du récit) |
| à matin, pantoute, tiguidou, c'est correct, tantôt | présent | « Elle me l'avait dit tantôt » : sens québécois « plus tôt aujourd'hui » (Usito) |
| jaser, niaiser, achaler, être tanné, pogner | présent | « a jasé », « aime niaiser et achaler », « était pas tannée », « on a pogné tous les feux rouges » |
| Rimouski, Saguenay, Chicoutimi, Trois-Rivières, Abitibi, Gaspésie, Lac-Saint-Jean, Sherbrooke, Magog, Hochelaga | présent | tous |
| tu, dire, petit, mardi (affrication) | présent | « Si tu veux », « à dire », « petite », « mardi » |
| père, fête, neige, pâte (voyelles longues) | présent | « son père », « une fête », « bancs de neige », « des pâtes » |
| chu, y'a, t'sais | présent, **deux phrases seulement** | « Moi, chu pas bon pour choisir… » et « Y'a rien là, t'sais, c'est correct. » |
| Hors liste, ajoutés pour le naturel | — | « Y'a rien là » (« ce n'est pas grave »), « chemise à carreaux », la négation sans « ne » (« elle était pas ») |

Rien n'a été écarté. Limite assumée : « je suis allé » (premier paragraphe) est normalisé comme « chu », voir plus bas.

**Sources consultées** (Usito, Université de Sherbrooke, le 2026-11-12) : pantoute (Q/C fam.), tiguidou (var.
tigidou), frette (adj. Q/C, « il fait frette »), piastre (var. piasse), friperie, tantôt (Q/C : plus tôt dans la
journée), achaler, poudrerie (Q/C), tanné (« être tanné de », fam.), clavardage (Q/C), niaiser, magasiner, misère
(« avoir de la misère à »), jaser (Q/C fam.). « pogner » : entrée Usito `pogner_ou_poigner` vue seulement par un
résumé de recherche (« attraper, saisir », Q/C fam.). « à matin » : absent d'Usito ; attesté « fam., ce matin » par le
Dictionnaire historique du français québécois (dhfq.org, vu par un résumé de recherche). « chu », « tsé » : Lexique du
français québécois (Wikipédia) et je-parle-quebecois.com, vus par des résumés de recherche, sources secondaires.

### Normalisation et équivalences

`ReadingNormalizer` = jetons de `Normalize.werTokens` (même WER que `Benchmark`, vérifié par un test) avec leur
position dans le texte ; nombres en chiffres écrits en lettres (`FrenchNumbers`, orthographe traditionnelle) ;
puis la table `lecture/lecture-qc-1.equivalences.tsv` (forme, variantes, raison), appliquée des deux côtés,
plus longue variante d'abord, chaque groupe devenant un seul jeton. Accents gardés (comme `Benchmark`).

| Forme du texte | Variantes acceptées | Raison |
| --- | --- | --- |
| chu | chus, chui, chuis, j'suis, je suis | même mot dit, graphies des modèles |
| y'a | y a, ya, il y a | idem ; « il n'y a » refusé (« ne » ajouté) |
| t'sais | t'sé, tsé, tsais, tu sais | idem |
| tiguidou | tigidou | variante d'Usito |
| piastre(s) | piasse(s) | variante d'Usito |
| saint / sainte | st / ste | abréviation des toponymes (« Lac-St-Jean ») |

**Refusées volontairement** (testées comme erreurs) : courriel/email, char/auto, piastres/dollars ou « $ »,
à matin/ce matin, fin de semaine/week-end, dépanneur/dep, pantoute/pas du tout, frette/froid, négation complétée
(« n'était pas », « je ne suis pas »). Reconnaître le mot d'ici est ce que le test mesure. Aucune variante
non attestée pour pantoute ou frette. Effet de bord accepté : « je suis » du premier paragraphe devient « chu » des
deux côtés (symétrique, donc sans effet sur le score, mais le dénominateur perd un mot).

### Score et seuils (`ReadingScoring`, `ReadingThresholds`)

- Score de paragraphe : `Levenshtein.align` (nouveau, même distance que `Levenshtein.distance`, testé) ; ressemblance
  = 1 − WER bornée à [0, 1] ; mots mal compris = jetons de référence substitués ou omis, avec leur position dans le
  paragraphe (surlignage) et ce qui a été entendu ; mots en trop à part.
- Score global : somme des erreurs sur somme des mots des paragraphes lus (pas une moyenne de taux) ; `complete`
  quand tous les paragraphes sont lus.
- **Partage proposé** : `SHARE_WER_THRESHOLD_PERCENT = 20`, WER **strictement** supérieur, calculé avec le **modèle
  recommandé**, sur le texte **complet**. Repli prudent : si le modèle recommandé n'est pas installé ou n'a pas tourné,
  rien n'est proposé (on ne propose pas de partager une voix sur la foi d'un autre modèle). Comparaison en entiers.
- **« Nettement mieux »** : les deux à la fois, sur le texte complet et le même nombre de mots : au moins
  `BETTER_MODEL_MIN_GAIN_POINTS = 5` points de WER de moins (10 mots sur 200, plus qu'une hésitation ou un bruit) **et**
  au moins `BETTER_MODEL_MIN_RELATIVE_GAIN_PERCENT = 30` % d'erreurs en moins. `betterModel` donne le meilleur de ceux-là.

### Consentement et politique

- Constantes uniques : `Contribution.RECEIVING_ADDRESS` (`remi@kalmia.ca`), `RETENTION_MAX_YEARS` (2), `POLICY_URL`.
  Les chaînes les reçoivent en argument (`%1$s`, `%1$d`) et n'en contiennent aucune copie ; `CONTRIBUTION.md` (texte
  libre) les cite, et `ContributionConsistencyTest` vérifie que la politique et le consentement affiché annoncent la
  même adresse et la même durée, et qu'aucune autre n'apparaît.
- Chaînes : `contrib_card_*`, `contrib_declare_*`, `contrib_declined_*`, `contrib_accept_*`, `contrib_share_*`.
  Le consentement promet aussi « ne sert à rien d'autre sans un nouveau consentement » (ni publication, ni vente, ni
  entraînement) : engagement fort, conforme à la portée de l'issue, à valider.
- Sources juridiques lues (texte officiel de la Loi 25 en PDF, pages de la CAI) : listées dans `docs/CONTRIBUTION.md`.
  Légis Québec a refusé l'accès à l'outil : le texte consolidé en vigueur n'est **pas vérifié**.

### À valider par un humain

1. **Naturel du texte** : se lit-il comme du français d'ici, sans effet « liste » ? « on a pogné tous les feux
   rouges », « d'Hochelaga », « Y'a rien là » sonnent-ils juste ?
2. **Consentement** : clarté, ton, et l'engagement « rien d'autre sans nouveau consentement ».
3. **Cadre juridique, avant toute diffusion** : durée de 2 ans, accès à la boîte `remi@kalmia.ca`, hébergement de
   la messagerie (art. 17), statut de la voix (sensible ou biométrique), responsable de la protection des
   renseignements personnels, mineurs. Liste dans `docs/CONTRIBUTION.md`, « Ce qui reste à valider ».
4. Seuils (20 %, 5 points et 30 %) après les premiers essais réels.

## Issue #15, lot 3 — revue indépendante

Revue du diff `origin/main...HEAD` (branche du lot 3, PR #18) contre les critères du lot 3, la section
« Positionnement » et la règle 8 de l'issue, sans les comptes rendus des agents. Les changements demandés par
l'utilisateur en cours de route (interrupteur du polissage serveur, garde-fou strict, langue de Qwen3-ASR, modèle
québécois, vitesse de Whisper) ont été relus en priorité : ils touchent le comportement réel.

### Critères du lot 3

- **Jeu et noteur exécutables en CI sans modèle ni réseau** : oui (`PolishEvaluationTest`, `LlmSimulationTest` dans
  `testDebugUnitTest`). Relancé : `app/build/reports/polissage/resultats.md` donne exactement les chiffres du rapport
  (55, 36, 17, 38, 19 ; 36, 44, 13, 39/28/13 ; 56) et les plafonds 56 / 57 / 58 ; les fichiers de simulation
  régénérés sont identiques à ceux du dépôt. Seules les latences d'ordinateur diffèrent (le rapport le dit).
- **Rapport** (`docs/POLISSAGE_LOCAL.md`) : mesuré / simulé / à faire sur téléphone séparés dans chaque tableau ;
  aucun chiffre simulé présenté comme mesure ; recommandation (ne rien activer, ne pas livrer de LLM) cohérente avec
  les plafonds. Corrigé : la réponse courte disait « 58 sur 79 s'il passait sur tous les segments », or 58 exige la
  proximité (désactivée par défaut) : 57, 58 avec proximité.
- **Aucune approche branchée** : `DefaultPathUnchangedTest` cherche les noms des classes de `text/polish/` et de
  `cleanup/eval/` dans tout `src/main/java` ; vérifié aussi à la main (aucun appel depuis `DicteeApp`,
  `TextPipeline`, `SettingsActivity`, `DicteeImeService`). C'est suffisant tant que personne ne passe par la
  réflexion. Le code d'évaluation est compilé dans l'APK (permis par l'issue : « code hors du chemin par défaut ») ;
  le jeu et le lexique de simulation ne sont que dans l'APK de test (vérifié par `unzip -l` sur les deux APK).

### Garde-fou strict du LLM local : huit trous trouvés et fermés

Cas adverses écrits dans `SafetyCheckAdversarialTest` ; tous **passaient** avant la correction (`text/Filters.kt`,
`SafetyCheck`) :

1. Pronoms réfléchis et doublements grammaticaux pris pour une répétition : « Vous vous lavez » → « Vous lavez »,
   « nous nous », « had had », « that that ».
2. Nombres épelés : « cinq cinq cinq, un deux trois quatre » → « cinq, un deux trois quatre » (numéro de téléphone).
3. Lettres épelées : « c o i f f e r i m o u s k i » → « c o i f e r… » (le cas même du lot 3), « a n n e » →
   « a n e ». Le faux départ laissait aussi passer ces cas (préfixe répété sans reste).
4. Répétition à travers une fin de phrase : « Je vois Marie. Marie arrive » → « Je vois Marie. Arrive » (sujet
   supprimé).
5. Autocorrection qui n'en est pas une : « Il pleut, enfin. Je reste » → « Je reste » (phrase entière supprimée).
6. Majuscule abaissée au milieu d'une phrase : « j'ai vu Pierre » → « j'ai vu pierre », « Apple » → « apple ».
7. Séparateur d'un nombre : « 3, 5 kilos » ↔ « 3,5 kilos » (seuls les chiffres étaient comparés).
8. Question changée en affirmation : « Tu viens demain? » → « Tu viens demain. ».

Correction : répétition et faux départ refusés s'ils traversent une fin de phrase (`?`, `!`, point simple ; les
points de suspension restent une hésitation) ou s'ils retirent un mot d'un caractère, un nombre en lettres ou un
doublement grammatical (`NO_DEDUP`) ; autocorrection limitée à sa phrase ; une majuscule de l'entrée ne s'abaisse
qu'en début de phrase (la mettre reste permis partout, « pierre » → « Pierre ») ; nombres comparés avec leurs
séparateurs internes ; un `?` de l'entrée ne disparaît pas. Les nettoyages légitimes passent toujours (hésitations,
« Je… je pense », « très très », « Bon. Donc » → « Bon, donc », « Une une maison », autocorrections avec virgule,
majuscule de phrase). Un cas de `SafetyCheckTest` attendait l'inverse (« la Friperie » → « la friperie » accepté) :
désormais refusé, c'est le même mécanisme que « Pierre » → « pierre ». Le prix est faible : un refus garde le texte
nettoyé par les règles.

Non corrigé, connu : une virgule de sens (« Mange, Marie » / « Mange Marie ») n'est pas contrôlée, puisque le prompt
demande de corriger la ponctuation ; « un un » et « une une » restent retirables (bégaiement fréquent ; les chiffres
sont contrôlés à part) ; un doublement de nom sans ponctuation (« Paul aime Marie Marie aime Paul ») reste ambigu.

### Interrupteur « Polissage par le serveur »

Relu dans `RemoteEngine` : coupé, `polish=false` explicite sur l'API à tâches et sur le repli synchrone, texte brut
inséré même si le serveur renvoie `polished_text`, statut « not requested » (le clavier n'affiche alors aucun
message) ; activé, requêtes identiques à avant. Le chemin OpenAI et le décodage par segment n'ont jamais demandé de
polissage. Le serveur lit bien `polish=false` (`app/main.py`). Ajouté : `polishOffStillAppliesVoiceCommandsToTheRawText`
(les commandes vocales s'appliquent au texte brut).

### Langue transmise à Qwen3-ASR : vérifiée et mesurée

- `javap` sur `app/libs/sherpa-onnx-2.0.1.aar` : `OfflineStream.setOption(String, String)` existe ;
  `OfflineQwen3AsrModelConfig` n'a pas de champ de langue. Sources au tag `v2.0.1`
  (`offline-recognizer-qwen3-asr-impl.cc`) : l'option de flux `language` est écrite telle quelle dans le prompt
  (`"language " + language`), puis le préfixe « language X<asr_text> » est retiré de la sortie ; l'exemple officiel
  passe « Korean, Chinese, English ». Les noms viennent de la liste officielle de Qwen3-ASR
  (`qwen_asr/inference/utils.py`, `SUPPORTED_LANGUAGES`) : French, English, Chinese, Hindi, Spanish, Arabic,
  Portuguese, Russian y sont tous, à la casse près ; bengali et ourdou n'y sont pas et ne reçoivent rien. Table exacte.
- **Mesure** (hôte x86-64, sherpa-onnx 2.0.1, 4 threads, `fr1` + `frqc1..6`, 75 mots, une exécution) : détection
  automatique **8 erreurs (10,7 %)**, « French » forcé **6 erreurs (8,0 %)**. Écarts : `frqc2` 3 → 2, `frqc3` 4 → 3,
  le reste identique. Pas de dégradation : le défaut est gardé. Trop peu pour parler d'un gain ; les mots dits seuls
  ne sont pas mesurés. Épinglé par `ModelAccuracyHostTest.qwen3ForcedFrenchDoesNotDegradeTheFrenchReferences`
  (au plus 2 erreurs de plus que l'automatique), placé dans `ModelAccuracyHostTest` pour que le job `models` de la CI
  le lance (il ne sélectionne que `*ModelAccuracyHostTest*` et `*EndToEndHostTest*`).

### Modèle québécois

- API publique Hugging Face (`?blobs=true`) : tailles et SHA-256 des deux `.onnx` identiques au catalogue ; dépôt
  public, non restreint ; `turbo-tokens.txt` (hors LFS, sans somme dans l'API) téléchargé et vérifié : `b34b360d…a126`.
  Fiche d'origine (`ele-sage`) : MIT, WER 6,65 % sur sa validation, données Assemblée nationale + Common Voice.
- **Corrigé** : nos six extraits québécois viennent de Common Voice « Français du Canada », une des sources
  d'entraînement du modèle : il a pu les entendre. La carte et le README le disent maintenant à côté du « 5 erreurs
  sur 59 mots ». La raison de recommandation disait « le plus léger des trois », « le moins d'erreurs des trois »,
  « le seul des trois » : il y a quatre modèles (toujours vrai à quatre : 4 erreurs contre 5, 6 et 8). Jamais
  recommandé (test unitaire et instrumenté), français seulement, lenteur donnée comme observation.
- Scripts de conversion : aucun secret, aucun chemin absolu de cette machine, crédit et licence corrects. Corrigé
  dans leur README une phrase sur « le `package-lock.json` et `requirements.txt` de kalmia.ca », sans rapport avec
  ce dépôt. Reste : `convertir.sh` télécharge `export-onnx.py` depuis `master` (somme vérifiée, mais seulement
  avertie si elle change).
- Portée : l'issue mettait l'ajout de modèles hors portée ; la demande explicite de l'utilisateur l'a levée pour ce
  modèle seul (section plus haut).

### Whisper

- `WhisperTuning.provisionalAllowed` : coupé pour Whisper et le serveur, réglage respecté ailleurs ;
  `tailPaddingsFor` : la valeur du réglage pour la famille Whisper seulement, 1 000 sinon (donc pas de rechargement
  des autres moteurs) ; valeur stockée invalide → 1 000 (tests `WhisperTuningTest`, `StoreTest`). L'écran de
  comparaison passe les options de la dictée à chaque modèle et les nomme dans l'en-tête.
- `docs/ACCELERER_WHISPER.md` : mesures étiquetées « mesuré hôte ». Corrigé : l'étape 6 de la procédure demandait
  de comparer « texte provisoire activé puis désactivé » alors qu'il est toujours coupé avec Whisper (et une phrase
  finale « l'étape ne compare plus que les autres modèles, ou l'état avant cette mise à jour ») : réécrite (temps
  jusqu'au texte final, comparé à la version précédente si on l'a). Le tableau des changements disait
  `whisperTailPaddings = 0` et « exécuter Whisper en double » comme faits : corrigé (1 000 ; fait en partie).

### Thème, exactitude, vie privée

Aucune couleur ni police en dur dans le diff (la note de modèle utilise `body(…, small = true)`). Aucune permission
ajoutée, aucune nouvelle requête réseau hors du téléchargement du modèle québécois depuis huggingface.co. Rien sur
Google. Les textes d'aide des réglages ajoutés sont vrais au regard du code.

### DECISIONS.md

Chronologie « friperie » rendue cohérente : la section de l'interrupteur disait le polissage serveur « suspect
principal » puis « désormais coupable », alors que la suite l'écarte ; renvois ajoutés vers la mesure de la langue
de Qwen3-ASR (ex-« non mesuré »).

### Échecs de `RobustnessInstrumentedTest` sur 1d0e815 (verts sur c63f218)

Trois échecs dans la CI émulateur de 1d0e815, aucun sur c63f218 ni sur `main`. **c63f218 ne les a pas corrigés** :
il ne touche ni `ime/`, ni `dictation/`, ni les réglages de session. Le lot 3 non plus, sur ce chemin : avec
Parakeet (modèle des tests), `WhisperTuning.provisionalAllowed` rend exactement l'ancienne valeur
(`partials && famille != REMOTE`), `tailPaddingsFor` rend 1 000 pour tout modèle non Whisper donc la clé du moteur
ne change pas, la langue de Parakeet reste vide, et `CleanupGuard` ne joue qu'en mode LLM (désactivé). Lecture du
code exercé (`DicteeImeService.onStartInput`, `onFinishInputView`, `stopIfHidden`) :

- `changingFieldMidDictation…` et `changingBetweenFieldsWithTheSameKey…` : la dictée reste en LISTENING et, dans le
  premier, le texte arrive **dans le champ 1** : le clavier n'a jamais reçu le `onStartInput` du nouveau champ. Or
  le test déplaçait le focus par `requestFocus()` sans vérifier que le clavier l'avait vu ; Android ne signale un
  changement de focus au clavier que dans une fenêtre qui a le focus de fenêtre. Cause la plus probable : une fenêtre
  système (boîte « isn't responding » d'un émulateur chargé, déjà rencontrée par `ImeTestBase`) au-dessus de
  l'activité à ce moment. Le produit se comporte alors correctement (il écrit dans le champ que le clavier sert).
- `rotationDuringDictationKeepsDictating` : état IDLE, journal « dictation IDLE » : `stopIfHidden` a arrêté la dictée,
  c'est-à-dire que le clavier est resté caché plus de `HIDE_GRACE_MS` (4 s) pendant la rotation. Sur un émulateur
  chargé, rotation + réaffichage (plus les 3 s d'attente d'`ensureKeyboardShown` avant de toucher le champ) peut
  dépasser 4 s. C'est une limite connue du produit (une rotation de plus de 4 s arrête la dictée, le texte déjà dit
  est gardé), pas une régression.

Conclusion : fragilité des tests sous charge, pas de course nouvelle dans le produit ; je ne peux pas le prouver
sans journal (le message d'échec ne gardait que 60 lignes de logcat avant filtrage, d'où une seule ligne utile).
Changé dans `ImeTestBase`, sans affaiblir aucun test : `focus()` attend le focus de fenêtre (en répondant « Wait » à
une boîte « isn't responding ») puis **exige** que le clavier ait reçu la nouvelle entrée (`EditorInfo` neuf) avant de
continuer, avec un échec explicite sinon ; le message d'échec de `waitFor` donne maintenant la fenêtre qui a le focus
et les 40 dernières lignes `DicteeIme` (dont « keyboard still hidden: stop »), pour trancher au prochain échec. Le
délai de grâce de rotation n'est pas allongé : ce serait changer le produit sans mesure.

### Vérification

`./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug` : code de retour 0. Mesure Qwen3 :
`--tests '*ModelAccuracyHostTest.qwen3Forced*' -PmodelsDir=…`. Les tests instrumentés ne tournent que dans la CI
(pas d'émulateur ici) : `ImeTestBase` modifié compile, son effet se verra à la prochaine exécution.

### État de la revue

- Fait : points 1 à 9 de la consigne (critères du lot 3, garde-fou, interrupteur serveur, langue de Qwen3-ASR mesurée,
  modèle québécois, Whisper, thème et exactitude, tests instrumentés, cohérence de ce fichier) ; analyse des échecs de
  `RobustnessInstrumentedTest`.
- Reste : confirmer dans la CI que `ImeTestBase` modifié passe ; corrections à reporter dans `docs/A_VERIFIER.md`
  (listées dans le rapport de revue à l'orchestrateur, ce fichier-là n'est pas modifié par la revue).

`--tests '*ModelAccuracyHostTest.qwen3Forced*' -PmodelsDir=…`.

### Échec de CI : `HowToInstrumentedTest.opensAtTheEndOfTheFirstSetupAndNotTheSecondTime` (« only once »)

Diagnostic : pas un défaut du produit. `maybeOpenHowTo` pose `howToShown` (`SetupActivity.kt`, avant
`startActivity`) ; `apply()` met la valeur à jour en mémoire tout de suite dans l'instance de `SharedPreferences`
unique du processus (seule l'écriture disque est asynchrone), et tout se passe sur le fil principal : un deuxième appel
(`onResume`, `onWindowFocusChanged`, fin de téléchargement) voit déjà le drapeau. Le tutoriel ne peut donc pas
s'ouvrir deux fois pour l'utilisateur. Le test, lui, était fragile : le moniteur `second` est enregistré pendant que la
`HowToActivity` de l'étape 2 est encore vivante (seul `SetupActivity` est fermé) ; un moniteur d'activité compte aussi
la création d'une instance par `Instrumentation.newActivity`, donc la recréation de cet ancien écran (mode nuit
appliqué par `ScreenshotTest`, rotation laissée par un autre test) ou sa destruction concurrente au lancement
NEW_TASK|CLEAR_TASK suffit à lui faire « voir » une instance. Cela correspond à l'apparition de l'échec après
`bd7f6e5` et `2911387` (durée et ordre des autres tests changés), sans autre cause dans le chemin d'ouverture.
Cause exacte non reproductible sans émulateur : le test est corrigé sans l'affaiblir.

Correction : à l'étape 3, le moniteur de l'étape 2 est retiré, le premier écran est fermé et on attend sa
destruction, puis seulement le moniteur `second` est posé ; on attend 3 s sans instance (`assertNull`) et on vérifie
`hits == 0`. L'assertion reste : une seconde arrivée sur l'écran de configuration n'ouvre pas le tutoriel.

## Issue #15 — demande de l'utilisateur : retour au style générique

Date : 2026-11-13. Demande : « Retire tout le style lié à Kalmia. Reviens au style générique. » Elle remplace la
section « Identité visuelle Kalmia » de l'issue et l'étape 1.5 : **la section 1.5 de ce fichier et « Identité
visuelle Kalmia » de l'issue sont désormais caduques** (elles restent plus haut comme historique).

Référence du « style générique » : l'apparence d'avant l'étape 1.5 (commit `800d08e`) : thème Material 3 DayNight
bleu (`#2E5E8C`), polices du système, coins arrondis (touches à 12 dp), ombres Material ordinaires, icône de
lancement bleue d'origine.

Portée tranchée par l'orchestrateur (prudente) :

**Retiré**
- La palette Kalmia (papier, encre, vert sauge, fleur…) et les rôles qui la faisaient correspondre à Material 3.
- Les polices embarquées Newsreader et IBM Plex Mono (`res/font/*`, 1,2 Mo) et leurs licences
  (`assets/licences/OFL-*.txt`) : plus aucune police dans l'application.
- Les angles droits imposés, l'ombre pleine décalée (`kalmia_ombre_bouton`), les majuscules espacées « façon
  étiquette », les styles `Widget.Kalmia.*`, `ShapeAppearance.Kalmia.*`, `TextAppearance.Kalmia.*`.
- Le logo Kalmia (`kalmia_logo`, le logo de l'écran d'accueil, `cd_kalmia_logo`), l'icône de lancement dérivée du logo
  et sa couche monochrome ; retour à l'icône d'origine (micro blanc sur fond bleu).
- Les sélecteurs `res/color/kalmia_button_*` et `kalmia_key_text` (les boutons reprennent les couleurs Material).

**Gardé**
- Le **nom** « Kalmia Blabla » et tous les textes (la demande porte sur le style, pas sur le nom ni le
  positionnement) ; toutes les fonctionnalités des lots 1 à 3.
- Les acquis d'accessibilité et de robustesse, qui ne sont pas du « style Kalmia » : erreurs en rouge avec icône
  (`markError`, `ic_error`) ; zones tactiles d'au moins 48 dp (boutons du clavier, bouton d'aide) ; symboles des
  touches d'au moins 20 sp (24 sp) ; écart entre touches en marge interne (zone tactile continue) ; clavier qui
  suit le réglage Système / Clair / Sombre ; texte lisible dans les deux thèmes ; styles de texte nommés
  (`TextAppearance.Dictee.*`, utilisés par `ui/Ui.kt`) qui pointent maintenant vers les apparences Material par
  défaut ; aucun rôle de couleur laissé au thème Material par défaut (tous les rôles utilisés sont définis, clair
  et sombre, y compris les couleurs de texte du cadre Android).
- Le micro garde un état visuel distinct à l'écoute : rouge (couleur d'erreur du thème) au lieu de bleu au repos,
  icône « stop » (jamais la couleur seule).

**Faits**
- `values/colors.xml` et `values-night/colors.xml` : palette Material 3 bleue (noms `md_*`), seul endroit où un
  hexadécimal s'écrit, plus `launcher_bg` et `launcher_fg` pour l'icône. `values/themes.xml` : un seul thème
  `Theme.Dictee` (parent `Theme.Material3.DayNight.NoActionBar`), `KeyButton` (tonal, 12 dp, 52 dp de haut) et le
  style du micro `Widget.Dictee.Button.Mic`. Les barres système suivent un booléen `light_system_bars` (clair/sombre).
- `LevelMeterView` : retour à la version d'avant 1.5 (piste `colorSurfaceVariant`, barre `colorPrimary`).
- Tests : `ThemeContrastTest` garde le principe (ressources lues, 4,5:1 pour chaque paire texte/fond utilisée, clair
  et sombre, contour 3:1, tous les rôles définis, micro, touches, boutons du clavier à 48 dp) ; il perd « palette =
  source Kalmia » et ajoute « bleu d'origine ». `NoHardcodedStyleTest` garde « aucune couleur ni police en dur » et
  « aucune police téléchargeable », et exige maintenant qu'aucune police ne soit embarquée ni nommée. Les tests
  instrumentés n'utilisaient aucune ressource supprimée.
- Les captures de `docs/screenshots/` montrent encore le thème Kalmia : elles sont régénérées par la CI
  (`ScreenshotTest`, mêmes noms de fichiers).

## Issue #15, lot 4 — étape 4.2 : carte, déclaration, écran du test de lecture

État au 2026-11-13. **Fait** : carte de participation (écran principal et Réglages), déclaration et exclusion,
acceptation, écran de lecture paragraphe par paragraphe, transcription par le ou les modèles installés, score global et
par paragraphe avec mots surlignés, suggestion de modèle, point d'extension pour 4.3, logique pure et tests JVM,
test instrumenté avec moteurs scriptés, capture `reading-*.png`, section du README. **Reste à faire (autres
étapes)** : consentement au partage, archive locale, courriel (4.3) ; revue (4.R). **Non vérifié ici** : aucun
émulateur dans ce conteneur, donc les tests instrumentés sont compilés (`assembleDebugAndroidTest`) et relus, pas
exécutés (la CI de la PR les exécute) ; le rendu à l'écran est à juger par un humain (liste plus bas).

Thème : le style Kalmia ayant été retiré à la demande de l'utilisateur, l'écran emploie les helpers de `ui/Ui.kt` et les
attributs du thème Material générique ; aucune couleur ni police en dur.

### Parcours

1. **Carte** (`ui/Ui.kt`, `participationCard`) : « Aider à améliorer la dictée en français québécois », deux phrases,
   « Faire le test » et, sur l'écran principal seulement, « Non merci ». Décision (`reading/Participation.kt`, valeur
   pure construite par `AppSettings.participation`) :
   - langues : seulement avec « Français (Québec) » parmi les langues choisies ;
   - écran principal : **à la fin de la configuration**, c'est-à-dire quand `SetupProgress.isComplete` (clavier activé,
     micro autorisé, un modèle prêt), la carte n'étant ni fermée ni exclue. Avant, rien : la carte ne doit pas
     couvrir les étapes de configuration. Elle n'est redessinée que si cet état change (les téléchargements
     rappellent l'écran à chaque progression : un redessin avalerait les appuis) ;
   - « Non merci » : `participationCardDismissed` (préférence) ; masque la carte de l'écran principal, ne change rien
     d'autre, la carte reste dans les Réglages ;
   - réponse « non » : plus de carte nulle part, plus de test.
2. **Déclaration** (`ReadingSession`, phase `DECLARE`) : « Parlez-vous un français québécois ? » avant tout, y compris
   avant l'acceptation. *Non* : phase `DECLINED` (remerciement, « Fermer »), réponse mémorisée (`AppSettings.quebecDeclaration`,
   valeurs `UNANSWERED/YES/NO`), **aucun enregistrement** : `ReadingSession.store` refuse tout hors de la phase
   `READING`, `accept()` et `beginScoring()` lèvent une erreur après « non ». Le micro n'est pas ouvert (le compteur du
   test instrumenté le vérifie), aucun moteur n'est chargé, aucun fichier n'est créé. *Oui* : acceptation puis test.
   Une personne qui a déjà répondu *oui* va directement à l'acceptation ; l'acceptation est redemandée à chaque test.
3. **Réglages** : section « Test de lecture en français québécois » (après les langues) : la réponse en deux cases
   (Oui / Non, aucune cochée tant qu'on n'a pas répondu) et la carte. Corriger *non* en *oui* fait revenir la carte
   (Réglages et écran principal, sauf si elle y avait été fermée). La réponse reste visible même si
   « Français (Québec) » est décoché, pour pouvoir être corrigée.
4. **Lecture** (`ui/ReadingTestActivity`) : un paragraphe à la fois en style `TextAppearance.Dictee.Reading` (TitleLarge
   du thème, interligne 1,4), un seul bouton Enregistrer / Arrêter / Refaire ce paragraphe, Précédent / Suivant (Suivant
   seulement quand le paragraphe courant est enregistré), « Voir le score » dès qu'un paragraphe est lu. L'enregistrement
   (`ParagraphRecorder`, 16 kHz, via `app.newAudioSource()` donc `MicAudioSource` ou, en test, un clip) s'arrête tout seul
   à 20 s (la limite d'un segment) ou quand la source se termine ; moins de 0,5 s = « rien entendu », rien n'est gardé.
   Un enregistrement en cours s'arrête quand l'écran quitte le premier plan (pas de micro en arrière-plan).
   Pas de VAD : le paragraphe est transcrit tel quel, silences compris (les trois moteurs s'en accommodent).
5. **Transcription** : tous les paragraphes lus, **à la fin** (« Voir le score »), **un modèle à la fois** (le modèle
   est chargé une fois, au lieu de recharger ~1 Go à chaque paragraphe) avec les mêmes moteurs et options que la dictée
   (`app.engines.use`, langue forcée avec une seule langue choisie). `benchmarkRunning` est levé pendant le calcul
   (comme « Comparer les modèles »), le modèle est libéré à la fin. Annulable. Un modèle qui échoue est signalé et les
   autres continuent. **Modèles utilisés : les modèles locaux installés** (`ModelCatalog.all` filtré par `isInstalled`) ;
   **le serveur distant est exclu** : il enverrait la voix sur le réseau, ce qui contredit « rien ne quitte le
   téléphone » (`ReadingPrivacyTest` le garde). Un seul modèle marche comme plusieurs ; aucun modèle installé : l'écran
   d'acceptation le dit et le bouton est grisé.
6. **Score** : ressemblance (1 − WER, `ReadingScoring`) globale et par paragraphe. Pourcentage affiché **arrondi vers le
   bas, en entiers** (`similarityPercent`) : « 79 % » est donc toujours sous la ligne des 80 % du seuil de partage, et
   20 % d'erreurs s'affiche « 80 % ». Avec plusieurs modèles : une ligne par modèle (« Parakeet : 87 % ») et le détail
   d'un modèle à la fois (le modèle actif par défaut), car un texte entier par modèle ne tiendrait pas. Détail : le
   texte du paragraphe avec les mots mal compris surlignés, la ligne « Entendu : … » et la liste des mots mal compris.
   Un test arrêté tôt note ce qui a été lu et le dit ; il n'y a alors ni suggestion ni offre (texte incomplet, règle de
   4.1).
7. **Suggestion de modèle** : `ReadingThresholds.betterModel(scores, modèle actif)`, comparé au modèle **actif** au moment
   du test (c'est lui que la personne utilise). Carte « Un autre modèle vous comprend mieux » et bouton « Choisir X »
   qui change `activeModelId` ; le texte devient « X est maintenant votre modèle pour la dictée ».

### Surlignage (accessibilité)

Les mots mal compris portent `colorTertiaryContainer` en fond et `colorOnTertiaryContainer` en texte (paire déjà mesurée
par `ThemeContrastTest`, libellé complété) **et un soulignement** : la couleur n'est jamais le seul signal. Jamais la
couleur d'erreur (un mot mal compris n'est pas une faute de la personne). Une ligne « Mots mal compris : … » et une
légende doublent le surlignage pour un lecteur d'écran. `ThemeContrastTest.readingHighlightUsesAMeasuredPair…`
vérifie que `ReadingHighlight.kt` n'emploie que cette paire, le soulignement et pas `colorError`.

### Structure de résultat et point d'extension pour 4.3

`reading/ReadingResult.kt` (en mémoire, tenue par `ReadingSession.result`) :

- `ReadingResult(textVersion, paragraphs: List<ParagraphResult>, globalScores: Map<modelId, GlobalScore>,
  modelErrors: Map<modelId, String>, recommendedModelId, activeModelId, betterModelId: String?, offerSharing: Boolean,
  region: String? = null)` ;
- `ParagraphResult(index, audio: PcmAudio /* PCM 16 kHz mono */, transcriptions: Map<modelId, String>,
  scores: Map<modelId, ParagraphScore>)` ; seuls les paragraphes lus y figurent ;
- `offerSharing` = `ReadingThresholds.offerSharing(globalScores, recommendedModelId)` (WER strictement > 20 % avec le
  modèle recommandé, texte complet).

**Où 4.3 se branche** : `ReadingTestActivity.renderShareSlot(parent, result)`, appelée dans `renderResultFooter` ; elle ne
crée qu'un conteneur vide (tag `reading_share_slot`) quand `result.offerSharing` est vrai, et rien sinon. 4.3 y met son
point d'entrée (bouton vers l'écran de consentement) ; l'archive se construit depuis `ReadingTestModel.session.result`.
La région (facultative) n'est pas saisie ici. Les chaînes `contrib_share_*` restent inutilisées.

### Où vit l'audio, quand il disparaît

**En mémoire seulement**, aucun fichier, même temporaire : `ReadingSession` garde un `PcmAudio` par paragraphe (au plus
20 s × 16 kHz × 4 octets, soit 1,3 Mo par paragraphe, 11,5 Mo pour les neuf), et `ReadingResult` en reprend les
références. Le tout est tenu par `ReadingTestModel` (un `ViewModel`), qui survit à une rotation ou à un changement de
thème (sans lui, tourner le téléphone perdrait la lecture) et dont `onCleared()` appelle `session.clear()` quand l'écran
est vraiment fermé (« Fermer », retour arrière, tâche supprimée). « Refaire le test » efface aussi tout. Si le processus
est tué, la mémoire l'est avec lui. Aucune archive n'est créée à cette étape ; 4.3 décidera de ce qui est conservé après
consentement. `ReadingPrivacyTest` interdit dans le code du test les écritures de fichier, `ACTION_SEND`, `java.net`,
`FileProvider`, le serveur distant, etc., et vérifie que l'ensemble des permissions du manifeste n'a pas bougé.

### Choix de conception

- **Logique séparée de l'écran** (`reading/`, sans classe Android) : `Participation` (carte, exclusion, correction),
  `ReadingSession` (phases `DECLARE → ACCEPT → READING → SCORING → RESULT`, `DECLINED`, protection de la vie privée dans
  `store`/`accept`), `ParagraphRecorder` (boucle d'enregistrement sur une `AudioSource`), `ReadingTranscriber` (interface :
  les tests passent un moteur scripté, l'application l'adosse à `EngineManager`). L'activité ne fait que dessiner.
- **Une seule activité** pour question, remerciement, acceptation, lecture, calcul et résultat (phases d'une même
  session) plutôt que cinq écrans : l'état est un seul objet, la rotation ne casse rien.
- **Graine de test** : `TestHooks.installedModelIds` (debug/tests) rend « installés » exactement ces modèles du
  catalogue, pour tester un modèle puis plusieurs ; sans effet hors test (null).
- **Captures** : `reading-light.png` et `reading-dark.png` (résultat à deux modèles, carte de meilleur modèle,
  surlignage) sont produites par `ScreenshotTest.readingTest` dans la CI ; elles ne sont pas encore dans
  `docs/screenshots/` ni liées dans le README (pas de lien mort : la CI les générera avec les autres).
- Chaînes : `settings_participation_*` et `reading_*` dans `strings.xml` ; les textes de la carte, de la déclaration
  et de l'acceptation sont ceux de 4.1 (`contrib_*`), inchangés.

### Tests ajoutés

- JVM : `ParticipationTest` (9 : carte selon les langues, fin de configuration, fermeture, exclusion après « non »,
  correction dans les Réglages), `ReadingSessionTest` (21 : phases, aucun enregistrement après « non » ni avant
  l'acceptation, lecture et refaire, score avec un modèle et avec plusieurs, mots mal compris, pourcentage arrondi vers le
  bas, partage de part et d'autre de 20 %, pas d'offre sans le modèle recommandé ni sur texte incomplet, suggestion de
  modèle, modèle en échec, annulation, erreur imprévue, effacement), `ParagraphRecorderTest` (6), `ReadingPrivacyTest`
  (3), `ThemeContrastTest` (surlignage).
- Instrumentés (`ReadingTestInstrumentedTest`, `ReadingDriver`, compilés non exécutés ici) : carte absente sans
  « Français (Québec) » (et présente avec, en témoin) ; fermeture de la carte ; la carte ouvre le test par la question ;
  **« non » → aucun test proposé, micro jamais ouvert, aucun moteur chargé, aucun fichier créé** (comparaison des
  répertoires `filesDir`, `cacheDir`, `codeCacheDir`, `noBackupFilesDir`, stockage externe, bases), carte retirée,
  correction dans les Réglages, retour de la carte ; aucun modèle installé ; **un modèle** : score affiché égal au calcul,
  mot surlignée avec les couleurs du thème et soulignée, rien d'autre surligné, pas de choix de modèle, enregistrements
  supprimés à la fermeture ; **trois modèles** : une ligne par modèle, détail du modèle actif, suggestion et changement
  de modèle ; emplacement de partage vide quand le modèle recommandé est loin ; arrêt anticipé.

### À valider par un humain

1. **Rendu à l'écran** (clair et sombre, `reading-*.png` de la CI) : taille et aération du texte à lire, lisibilité du
   surlignage (fond tertiaire + soulignement) sur un vrai écran, longueur du détail.
2. **Parcours sur un vrai téléphone** : micro réel, arrêt à 20 s, temps de calcul avec trois ou quatre modèles (chacun est
   rechargé à tour de rôle : plusieurs dizaines de secondes par modèle sont probables), mémoire (un seul modèle chargé à la
   fois, libéré à la fin).
3. **Wording** des nouvelles chaînes (`reading_*`, `settings_participation_*`), notamment « Non merci » (ferme la carte)
   par rapport à la réponse « Non » (exclut).
4. Que la carte n'apparaisse **qu'à la fin de la configuration** (et pas dès le premier lancement) convient.

## Issue #15, lot 4 — étape 4.3 : consentement, archive locale, envoi par courriel

État au 2026-11-13. **Fait** : proposition de partage dans l'écran du score, liste « ce qui partirait » générée depuis
la description unique de l'archive, réécoute, région facultative, consentement (case + bouton), archive zip dans le
stockage privé, `FileProvider` limité au dossier des archives, intention de courriel et sélecteur du système,
suppression (bouton, refus, « Refaire le test », fermeture, balayages), tests JVM et instrumentés, captures
`reading-share-*.png`, README, `docs/CONTRIBUTION.md`. **Reste à faire (autres étapes)** : revue (4.R). **Non vérifié
ici** : aucun émulateur dans ce conteneur ; les tests instrumentés sont compilés et relus, pas exécutés (la CI de la PR
les exécute) ; l'envoi réel d'un courriel avec pièce jointe est à faire par un humain.

C'est la **seule exception** à « rien ne quitte le téléphone » : volontaire (trois gestes, jamais d'envoi automatique),
visible (liste exacte avant consentement), limitée au texte de test (aucune autre dictée, aucun réglage).

### Parcours

1. **Seuil** : la proposition n'existe que si `ReadingResult.offerSharing` (WER > 20 % avec le modèle recommandé, texte
   complet, règle de 4.1) ; sous le seuil, l'emplacement `reading_share_slot` n'est même pas créé. `ShareOffer.offered`
   exige en plus la déclaration *oui* et un score du modèle recommandé (défensif).
2. **Proposition** (carte dans l'emplacement de 4.2) : titre, raison (ressemblance et modèle), puis **« Ce qui partirait,
   et seulement ça »** : une ligne par élément de l'archive, libellé et **valeur réelle** (texte lu et transcription en
   entier, score, nom du modèle, version, téléphone, déclaration, date, région ou « non indiquée »). Puis réécoute,
   destinataire, finalité, « le courriel révèle votre adresse », conservation, voix identifiable, retrait, « c'est vous
   qui envoyez », politique (chaînes de 4.1, inchangées sauf la liste à puces `contrib_share_sent`, retirée : une liste
   écrite à la main pouvait diverger de l'archive).
3. **Région** : champ libre facultatif, une ligne, 60 caractères au plus (`InputFilter` et `normalizeRegion` : espaces
   réduits, caractères de contrôle et de format retirés, vide → `null`). La ligne « Région » de la liste suit la saisie.
4. **Consentement** : case `contrib_share_consent` **non cochée par défaut**, puis bouton « Préparer le courriel »,
   désactivé tant que la case n'est pas cochée. Sans les deux, **aucun fichier, pas même le dossier**. Décocher redésactive
   le bouton. Après suppression du fichier, la case est décochée : recréer un fichier redemande le geste.
5. **Fichier prêt** : nom et taille, rappel de l'emplacement et de la suppression ; « Envoyer par courriel », « Partager le
   fichier autrement », « Supprimer le fichier ». La région et la case disparaissent (le fichier est fait ; pour changer,
   supprimer puis recommencer).
6. « **Ne pas partager** » à tout moment : supprime le fichier s'il existe, ferme la proposition (« aucun fichier n'est
   conservé et rien n'est envoyé »). « Refaire le test » reste dans le pied de page.

### Archive : format et contenu exact

- **Une seule description** : `reading/ArchiveField` (11 éléments, dans l'ordre du fichier) et `ContributionContent.from`,
  qui produit à la fois la liste affichée (avant consentement, via `ShareOffer.preview`) et ce qu'écrit
  `ContributionArchive.write`. Chaque élément a son libellé `contrib_field_<nom>` (test qui l'exige) ; l'écran parcourt
  `content.fields`, l'archive aussi.
- **Fichier** : `files/contribution/kalmia-blabla-<version>-<AAAA-MM-JJ>.zip`, une seule archive à la fois (une nouvelle
  remplace l'ancienne), écrite dans un `.part` puis renommée.
- **Contenu** : `contribution.json` puis `paragraphe-N.wav` (un par paragraphe lu, N à partir de 1).
  - **WAV** : un fichier par paragraphe plutôt qu'un seul : chaque fichier correspond à une transcription et à un score
    du JSON, et reste sous 20 s. PCM 16 bits, mono, 16 kHz (`WavReader.encode`, rééchantillonné si besoin).
  - **JSON** lisible (indentation, clés en français, ordre fixe), écrit par un petit écrivain déterministe (`Json`),
    pas `org.json` dont l'ordre des clés n'est pas garanti. Clés : `audio` (format et fichiers avec durée au dixième),
    `version_du_texte`, `texte_lu`, `transcription`, `score` (ressemblance, erreurs, mots, par paragraphe), `modele`
    (id et nom), `version_de_l_application` (`versionName`), `modele_du_telephone` (`Build.MANUFACTURER` + `Build.MODEL`),
    `declaration` (« je parle un français québécois »), `date`, `region` (toujours présente, `null` si non indiquée :
    l'ensemble des clés ne varie pas).
- **Transcription : modèle recommandé seulement.** C'est sur lui que porte le seuil, et le consentement de 4.1 annonce
  « la transcription obtenue et le score » au singulier. Choix prudent : les résultats des autres modèles restent sur le
  téléphone, et l'écran le dit (`contrib_share_only_recommended`, quand plusieurs modèles ont été notés).
- **Précision de la date** : le jour (`LocalDate`), sans heure ni fuseau : assez pour retrouver une contribution à la
  demande de la personne (la politique lui demande « la date du test »), pas plus. Les entrées du zip portent toutes la
  même date fictive (2 janvier 1980, `ENTRY_TIME`) : sinon le zip trahirait l'heure à deux secondes près. Pas de
  commentaire ni de champ supplémentaire dans le zip.
- **Aucun identifiant** : ni Android ID, ni identifiant d'installation, ni compte, ni numéro de série, ni version
  d'Android (non annoncée), ni autre dictée, vocabulaire ou réglage. Le test JVM parcourt tout le JSON et refuse toute clé
  hors d'une liste fermée.

### Cycle de vie de l'archive

| Événement | Effet |
| --- | --- |
| Case cochée + « Préparer le courriel » | création (seul chemin : `ShareOffer.createArchive`, appelé par `ReadingTestModel.prepareArchive`) |
| « Supprimer le fichier » | suppression du fichier et du dossier ; case décochée |
| « Ne pas partager » | suppression, proposition fermée |
| « Refaire le test » | suppression avec les enregistrements |
| Écran fermé pour de bon (`ViewModel.onCleared` : « Fermer », retour, tâche supprimée) | suppression, envoyé ou non |
| Nouvel écran de test (nouveau `ReadingTestModel`) | balayage : toute archive restante est supprimée |
| Démarrage de l'application | balayage des archives de plus de 24 h (`ContributionArchive.MAX_AGE_MILLIS`) |

Raisons : l'archive ne doit pas traîner. Pendant que la messagerie est ouverte, l'écran du test reste dans la pile (il
n'est pas fini) : l'archive reste lisible le temps d'envoyer. Au démarrage de l'application, on ne supprime **que** ce
qui a plus d'un jour : le même processus démarre aussi pour le clavier, y compris pendant qu'on écrit le courriel dans la
messagerie ; tout supprimer à ce moment couperait la pièce jointe d'un envoi en cours. Limite : une messagerie qui lirait
la pièce jointe seulement après la fermeture de l'écran du test la trouverait supprimée (les messageries courantes la
copient à l'ouverture du brouillon ; à vérifier sur le téléphone).

### Envoi par courriel

- `ContributionShare.emailIntent` : `ACTION_SEND`, type `application/zip`, `EXTRA_EMAIL` = `Contribution.RECEIVING_ADDRESS`,
  objet `contrib_email_subject` (avec la version du texte), corps `contrib_email_body` (nom du fichier, liste des éléments
  générée depuis `ArchiveField`, adresse de la politique), `EXTRA_STREAM` et `ClipData` = l'URI du `FileProvider`,
  `FLAG_GRANT_READ_URI_PERMISSION` seulement (ni écriture, ni permission persistante), et un **sélecteur `mailto:`** qui
  limite le choix aux applications de courriel. Démarrée directement : sans messagerie, `ActivityNotFoundException` →
  message `contrib_share_no_email_app`, le fichier reste supprimable et partageable autrement.
- `otherIntent` : la même intention sans sélecteur, dans `Intent.createChooser` (sélecteur du système, qui transmet la
  permission de lecture à l'application choisie).
- `FileProvider` (`androidx.core`, déjà en dépendance) : `${applicationId}.contribution`, non exporté,
  `grantUriPermissions`, chemins `res/xml/contribution_paths.xml` = **seulement** `files/contribution/`.
- L'application ne sait pas si le courriel est parti : après l'ouverture de la messagerie, l'écran dit seulement que rien
  n'est parti tant que la personne n'a pas appuyé sur « Envoyer », et propose de supprimer le fichier ensuite.

### Réécoute

`ui/ReadingPlayer` : `AudioTrack` en flux, depuis la mémoire, paragraphe après paragraphe ; « Réécouter » / « Arrêter
l'écoute ». S'arrête quand l'écran passe en arrière-plan, au refus et à la fermeture. Aucun fichier.

### Aucune requête réseau : comment c'est garanti, et la limite

- **Code** : `ReadingPrivacyTest` (JVM) interdit dans tout le code du test et du partage (`reading/`, écran, modèle,
  surlignage, lecteur, `ContributionShare`) `java.net`, `Socket`, `HttpURLConnection`, `openStream`, `openConnection`,
  `WebView`, `DownloadManager`, le serveur distant, etc. Il épingle chaque capacité permise à **un seul fichier** :
  l'écriture disque à `ContributionArchive.kt`, le dossier privé, le `FileProvider` et les intentions de partage à
  `ContributionShare.kt` ; il vérifie que `ContributionArchive.write` (interne) n'est appelé qu'une fois, dans
  `ShareOffer.createArchive`, après le test du consentement, que `createArchive` n'est appelé que par
  `ReadingTestModel.prepareArchive`, que la case n'est réglée que par le geste de la personne, et que le manifeste n'a
  ni permission ajoutée ni autre fournisseur que celui-ci, limité à son dossier. Ce garde est plus fort qu'en 4.2 :
  « aucune écriture » devient « une seule écriture, derrière le consentement ».
- **Exécution** (`ContributionInstrumentedTest`, chaque test) : compteurs `TrafficStats.getUidTxBytes/RxBytes` de l'UID
  de l'application avant et après (écart exigé : 0 octet) **et** `StrictMode` (`detectUntaggedSockets`,
  `detectCleartextNetwork`, avec un écouteur qui collecte les violations ; aucune tolérée). Les intentions vers d'autres
  applications sont interceptées par un `ActivityMonitor` et jamais démarrées.
- **Limites** : sur un appareil où les compteurs par UID ne sont pas pris en charge (`UNSUPPORTED`), seul `StrictMode`
  reste (le test l'écrit dans le journal) ; `StrictMode` ne voit que les sockets ouverts depuis Java, pas depuis du code
  natif (les compteurs, côté noyau, les voient) ; un garde sur les sources ne prouve pas l'absence de tout appel
  possible. Ce qui envoie vraiment, la messagerie de la personne, n'est jamais démarré par le test. La permission
  `INTERNET` existe toujours (téléchargement des modèles) ; aucune n'a été ajoutée.

### Tests ajoutés ou adaptés

- JVM : `ContributionArchiveTest` (10 : contenu = liste annoncée, dans les deux sens et dans l'ordre ; région absente =
  élément `null` ; aucune clé hors liste fermée, aucun identifiant, aucune heure, dates des entrées fixes, pas de champ
  supplémentaire ; WAV valides 16 kHz mono 16 bits même enregistrés à 48 kHz ; pas d'archive ni de dossier sans
  consentement ; rien sous le seuil ni sans déclaration ; suppression et remplacement ; balayage à 24 h ; région ; JSON
  échappé et déterministe), `ReadingPrivacyTest` (7, réécrit comme décrit plus haut), `ContributionConsistencyTest`
  (la liste à puces est remplacée par les libellés `contrib_field_*` ; un libellé par élément de l'archive ; adresse et
  durée toujours vérifiées).
- Instrumentés (compilés, non exécutés ici) : `ContributionInstrumentedTest` : sous le seuil rien n'est proposé ni écrit ;
  au-dessus, la liste affichée = les éléments de l'archive, case non cochée, bouton désactivé, saisie de région, cocher
  puis décocher, « Ne pas partager » → **aucun fichier** (répertoires de l'application comparés) ; avec consentement →
  l'archive contient **exactement** les éléments affichés (clés du JSON = étiquettes de la liste, dans l'ordre), valeurs
  (version, date, version de l'application, téléphone, déclaration, région, modèle, transcription du modèle recommandé
  seulement), WAV 16 kHz ; intention de courriel (destinataire, type, sélecteur `mailto:`, objet, corps, URI du
  fournisseur, lecture seule, contenu servi identique au fichier) ; sélecteur du système ; suppression depuis l'écran ;
  suppression par « Refaire le test » et à la fermeture ; balayage d'une archive restante à l'ouverture ; **aucune
  requête réseau** dans chaque test. Le test « emplacement vide » de 4.2 est remplacé par ceux-ci ; « non » → rien
  (4.2) est gardé. `ScreenshotTest.readingShare` → `reading-share-light.png`, `reading-share-dark.png`.

### À valider par un humain

1. **Recevoir un vrai courriel** de contribution sur un téléphone : pièce jointe présente, lisible, objet et corps ;
   avec Gmail et une autre messagerie ; comportement du sélecteur `mailto:` (certaines messageries pourraient ignorer
   la pièce jointe).
2. Que la pièce jointe survive au retour dans l'application et à la fermeture de l'écran après l'envoi (messagerie qui
   copierait tard).
3. Rendu de la proposition (`reading-share-*.png`) : longueur de la liste (texte et transcription en entier), lisibilité.
4. Wording des nouvelles chaînes (`contrib_field_*`, `contrib_value_*`, `contrib_share_*` ajoutées, `contrib_email_*`).
5. Réécoute sur un vrai téléphone (volume, enchaînement des paragraphes).
6. Choix prudents à confirmer : transcription du seul modèle recommandé, date au jour, région limitée à 60 caractères,
   balayage à 24 h.

## Issue #15, lot 4 — revue indépendante (4.R)

Revue du diff `origin/main...HEAD` (branche `ccr-a285fc62-j2yeex`, PR #19), vie privée d'abord, sans le compte rendu des
agents 4.1 à 4.3. Le style Kalmia ayant été retiré (thème Material générique), la règle vérifiée est « aucune couleur
ni police en dur ».

### Fait / reste à faire

- [x] Lecture de l'issue (lot 4, décisions, positionnement, hors portée, « Mode d'exécution »).
- [x] Chemins d'écriture et de sortie tracés (carte → déclaration → test → score → partage).
- [x] Archive réelle ouverte (produite par un test JVM temporaire, non gardé) : en-têtes zip et WAV, `contribution.json`.
- [x] `Intent`, `FileProvider`, chemins, manifeste, sauvegardes.
- [x] Textes (`contrib_*`, `CONTRIBUTION.md`, README) confrontés au code.
- [x] Corrections + tests ; commit 1 (`8092cc6`), commit 2 (champ région, catalogue, politique, cette section).
- [x] Vérification complète `testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug`.
- [ ] Tests instrumentés : n'ont jamais tourné localement ; résultat de la CI de la PR #19 à lire (voir plus bas).

### Ce qui a été vérifié et tient

- **Rien sans consentement.** « Non » à la déclaration → phase `DECLINED`, `mayRecord` faux, `store` refusé : le micro
  n'est jamais ouvert (`ReadingTestModel.toggleRecording` sort avant `newAudioSource`). L'audio ne vit qu'en mémoire
  (`ReadingSession.audio`), effacé par `clear()`. `ContributionArchive.write` est `internal`, appelé une seule fois,
  par `ShareOffer.createArchive`, qui sort avant toute écriture sans offre (score complet du modèle recommandé
  au-dessus de 20 %, déclaration « oui ») **et** sans case cochée. La case n'a pas d'identifiant de vue : elle n'est
  pas restaurée par l'état sauvegardé d'Android, seulement redessinée depuis `ShareOffer.consent`, qui vit dans le
  `ViewModel` (rotation : même écran, même consentement ; processus tué : nouvelle session, pas de résultat, plus
  d'offre, et l'archive orpheline est effacée à l'ouverture du test). « Refaire le test », « Supprimer le fichier »,
  « Ne pas partager » remettent la case à faux ; un nouveau résultat crée une nouvelle offre non cochée. Double appui
  sur « Préparer » : l'archive précédente est effacée avant la nouvelle, jamais deux. L'archive ne peut contenir que
  l'audio du résultat en cours (`ParagraphResult.audio`), jamais une dictée ou un test précédent. Écriture dans un
  `.part` renommé à la fin, effacé en cas d'erreur ; aucun fichier temporaire ni cache ; aucun `Log` dans ces paquets.
- **Contenu de l'archive** (ouverte réellement) : `contribution.json` puis `paragraphe-N.wav`, rien d'autre ; DOS
  date 1980-01-02 00:00 sur chaque entrée, aucun champ « extra » (en-têtes locaux et répertoire central), aucun
  commentaire, système créateur MS-DOS (ni droits Unix, ni uid/gid) ; WAV d'en-tête 44 octets, sans bloc `LIST`.
  Clés JSON = `ArchiveField`, la même liste que l'écran (une seule source). Téléphone = `Build.MANUFACTURER` +
  `Build.MODEL`, aucun nom d'appareil choisi par l'utilisateur, aucun identifiant. Nom du fichier : version du texte
  et jour, déjà dans les métadonnées.
- **Envoi** : `ACTION_SEND` avec `EXTRA_EMAIL` = `Contribution.RECEIVING_ADDRESS` seul, pas de CC/BCC, sélecteur
  `mailto:`, `FLAG_GRANT_READ_URI_PERMISSION` seul (ni écriture, ni persistant, ni préfixe), `ClipData` pour la
  permission ; `FileProvider` non exporté, `grantUriPermissions`, un seul `files-path` `contribution/` ; pas
  d'application de courriel → message `contrib_share_no_email_app`.
- **Réseau** : aucun code réseau, aucune bibliothèque, aucune permission ajoutée ; le test n'utilise que
  `ModelCatalog.all` (sans le serveur) et `DefaultEngineFactory`, qui charge depuis le disque et ne télécharge rien.
  Le garde instrumenté (compteurs `TrafficStats` de l'UID + `StrictMode` sockets) dit ses limites (compteurs non pris
  en charge → StrictMode seul ; StrictMode ne voit pas le natif, les compteurs si) : honnête.
- **Score** : seuil en entiers, strictement au-dessus de 20 %, texte complet exigé (`GlobalScore.complete`) ; pile à
  20 % → rien (testé). Positions surlignées : tokens pris sur le texte d'origine (espaces insécables et guillemets
  sont des séparateurs), nombres et équivalences gardent la plage de leur source.
- **Thème** : surlignage = fond `colorTertiaryContainer` + texte `colorOnTertiaryContainer` + soulignement (pas la
  couleur seule) ; aucune couleur ni police en dur dans le diff.

### Failles trouvées et corrigées

1. **Transfert vers un nouveau téléphone** (`AndroidManifest.xml`, `res/xml/data_extraction_rules.xml`) :
   `allowBackup="false"` arrête la sauvegarde infonuagique, mais, pour une application qui cible Android 12 ou plus
   (`targetSdk` 35), pas le transfert d'appareil à appareil. Une archive présente à ce moment serait partie vers le
   nouvel appareil sans geste de la personne. Les règles d'extraction excluent `files/contribution/` des deux
   (`ReadingPrivacyTest.theArchiveIsInNoBackupNorDeviceTransfer`). Le reste des données de l'application (réglages,
   modèles) n'a pas été touché : hors du lot.
2. **Recherche du modèle par `ModelCatalog.byId`** (`ui/ReadingTestModel.kt`, transcripteur et `labelOf`) : `byId`
   connaît aussi le serveur de l'utilisateur. La liste des modèles du test ne le contient pas, mais un identifiant
   erroné aurait suffi à envoyer la voix au serveur. Recherche limitée à `ModelCatalog.all` ; `ModelCatalog.byId`
   ajouté aux jetons interdits de `ReadingPrivacyTest`, qui vérifie aussi que `ModelCatalog.all` n'a aucun serveur.
3. **Date affichée ≠ date écrite** (`ui/ReadingTestModel.kt`, `ui/ReadingTestActivity.kt`) : la liste « ce qui
   partirait » et l'archive prenaient chacune `deviceFacts()` à leur moment ; passé minuit entre les deux, l'archive
   portait un autre jour que celui montré avant le consentement. Les faits sont pris une fois avec l'offre
   (`shareFacts`) et servent aux deux.
4. **Région** (`reading/ContributionArchive.kt`, `normalizeRegion`) : les séparateurs de ligne et de paragraphe
   (U+2028, U+2029) passaient tels quels (le `\s` de Java ne les reconnaît pas) ; la coupe à 60 caractères pouvait
   couper une paire de substitution (moitié écrite « ? » en UTF-8). Corrigé, avec un test d'injection JSON.
5. **Apprentissage du clavier** (`ui/ReadingTestActivity.kt`) : le champ région demande au clavier en usage de ne pas
   apprendre la saisie (`IME_FLAG_NO_PERSONALIZED_LEARNING`).
6. **Fragilité instrumentée** (`ContributionInstrumentedTest`) : le moniteur qui bloque le courriel et le sélecteur
   n'était retiré qu'en fin de test ; une assertion en échec entre les deux l'aurait laissé en place et aurait bloqué
   **tous** les démarrages d'activité des tests suivants (échecs en cascade trompeurs). Retiré dans `@After`, avec un
   drapeau (`Instrumentation.removeMonitor` lève une exception si aucun moniteur n'a jamais été ajouté).
7. **Politique** (`docs/CONTRIBUTION.md`) : une phrase dit que l'archive n'est incluse ni dans une sauvegarde ni dans
   un transfert vers un nouveau téléphone (vrai depuis le point 1).

### Échec de CI : `files/profileInstalled` (tests instrumentés, API 34)

- **Diagnostic.** `theArchiveGoesWithRedoAndWithTheScreen` échouait seul (57 sur 58) : « files left:
  `files/profileInstalled` ». Ce fichier n'est pas écrit par l'application (aucune occurrence dans le code) : c'est le
  marqueur d'`androidx.profileinstaller` 1.3.1 (`ProfileVerifier`, dépendance transitive ; constante vérifiée dans le
  `.class`), écrit de façon asynchrone après le premier lancement d'une activité. Course entre ce marqueur et la
  photo « avant » : il apparaît ou non selon le moment. Il ne contient que l'état de l'installation du profil.
- **Correction.** Une seule fonction partagée, `AppFiles.list` (`androidTest/.../AppFiles.kt`), remplace les deux
  copies de `appFiles()` (`ContributionInstrumentedTest`, `ReadingTestInstrumentedTest`). Elle exclut une liste
  **nominative** (`files/profileInstalled` seulement), sans motif. Ce que les tests prouvent n'est pas affaibli :
  tout autre fichier créé ou resté (`files/contribution/`, `.zip`, `.part`, `.wav`, `contribution.json`, `cache/`)
  fait toujours échouer. Aucune autre exclusion n'a été ajoutée sans être vérifiée.
- **Non vérifié ici** : pas d'émulateur ; la CI de la PR #19 le dira.

### Laissé ouvert, avec la raison

- **Équivalence « je suis » ↔ « chu »** appliquée partout, y compris au « je suis allé » du paragraphe 1 : un modèle
  qui écrirait « chu allé » pour un « je suis » prononcé n'est pas pénalisé. Gardé : c'est le même mot, l'équivalence
  est symétrique et ne masque pas un mot différent ; le dénominateur perd un mot (2 → 1), effet négligeable sur 220.
- **Fermeture de l'écran avant l'envoi** : l'archive est effacée quand l'écran se ferme ; une messagerie qui lirait la
  pièce jointe tard la trouverait absente. Choix prudent (rien ne traîne), annoncé à l'écran ; à vérifier sur un
  téléphone (A_VERIFIER, point 19).
- **Processus tué en arrière-plan** (pendant que la messagerie est ouverte) : l'archive reste jusqu'à l'ouverture
  suivante du test ou au démarrage après 24 h. Annoncé dans `CONTRIBUTION.md` et le README.
- **Écriture du zip sur le fil principal** (environ 5 Mo pour un texte complet) : bref, sans effet sur la vie privée.

### Critères d'acceptation du lot 4

| Critère | État |
| --- | --- |
| Compilation et CI vertes | Local : vert (commande ci-dessous). CI : voir le rapport de revue (tests instrumentés en cours au moment de la revue). |
| Tests unitaires : normalisation, score, partage de part et d'autre de 20 % (pile à 20 %) | Rempli (`ReadingNormalizerTest`, `ReadingScoreTest`, `ReadingSessionTest`). |
| Instrumenté : « non » → aucun test, aucun enregistrement, aucun fichier | Écrit (`ReadingTestInstrumentedTest`), dépend de la CI. |
| Instrumenté : sans consentement, aucune archive ; avec, contenu exact et intention correcte ; aucune requête réseau | Écrit (`ContributionInstrumentedTest`), dépend de la CI ; contenu exact aussi prouvé sur la JVM. |
| Consentement affiché et `CONTRIBUTION.md` : même durée, même destinataire (test sur les chaînes) | Rempli (`ContributionConsistencyTest`). |
| Un seul modèle comme plusieurs | Rempli en JVM (`ReadingSessionTest`) ; instrumenté dépend de la CI. |
| Texte, consentement, vrai courriel | Humain (A_VERIFIER, points 16 à 20). |

Vérification locale : `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug --console=plain -q`.

## Issue #22 : Whisper turbo par défaut, modèles cachés, Lite-Whisper expérimental

- `ModelSpec.visibility` (`VISIBLE`, `EXPERIMENTAL`, `HIDDEN`) : on cache sans supprimer. `ModelCatalog.all` garde
  tout (cinq modèles) ; `offered(langues)` = visibles, couvrant les langues, Parakeet seulement pour l'anglais seul ;
  `experimental` = Lite-Whisper ; `listed(langues)` = les deux.
- Écrans : installation = `offered` ; clavier, comparaison = `listed` (+ serveur) ; Réglages = section « Modèles
  expérimentaux » (télécharger, choisir, supprimer) et repli du serveur dans `listed` ; test de lecture = `offered`.
- Recommandation : toujours Whisper turbo ; la note de Whisper ne dit plus « dernier recours ».
- Migration (`ModelMigration`, `DicteeApp.migrateModels`) : au démarrage et à chaque changement de langues. Le
  modèle actif passe à Whisper turbo même s'il n'est pas installé (l'avis invite alors à le télécharger : choix le
  plus simple, la dictée affiche déjà « non installé » sinon). L'avis reste sur l'écran principal jusqu'au clic
  « J'ai compris » ; la suppression des fichiers n'est proposée que pour un modèle caché.
- Repli du serveur : un identifiant caché ou qui ne convient plus aux langues se lit comme Whisper turbo.
- `switchModel` (clavier) accepte encore tout identifiant du catalogue : seul le menu filtre (les tests
  instrumentés s'en servent).

## Issue #23 : serveur distant, mode serveur / téléphone, repli local

Décisions de l'utilisateur (2026-11-13) : code seulement, adresse par défaut vide, consentement + suppression.
Mise à jour de l'issue (#36) : le choix du mode et le consentement se font à l'installation ; pas d'écran de
consentement à la première dictée. Choix faits pendant l'implémentation (le plus prudent pour la vie privée en cas
de doute) :

- **Mode** (`TranscriptionMode` : `UNSET`, `SERVER`, `PHONE`, préférence `transcription_mode`) : #36 l'enregistre
  à l'installation, les Réglages (« Envoi au serveur ») le modifient, sous un texte qui dit que l'audio est envoyé
  au serveur configuré et supprimé dès la transcription. `PHONE` est « Toujours transcrire sur le téléphone » (pas
  de second réglage qui ferait doublon).
- **Choix du moteur** (`EngineChoice.route`) : adresse + mode `SERVER` + réseau → serveur ; tout le reste (sans
  adresse, `PHONE`, `UNSET`, sans réseau) → téléphone.
- **Le serveur n'est plus un « modèle » actif.** `activeModelId` désigne toujours le modèle du téléphone, qui sert
  aussi de repli au serveur ; l'ancien réglage « Si le serveur ne répond pas » disparaît des Réglages.
  `remoteFallbackId` reste pour la migration seulement : un utilisateur dont le modèle actif était le serveur garde
  son ancien repli comme modèle du téléphone (Whisper turbo si « aucun ») et son mode reste « pas encore choisi »,
  donc téléphone, jusqu'à ce qu'il choisisse « serveur » sous le texte (l'ancien choix ne disait pas ce qui arrive
  à l'audio).
- Le mode `SERVER` n'est jamais enregistré par un clic ailleurs que sous son texte : « Serveur distant » dans le menu
  du clavier ou sur l'écran principal ouvre les Réglages sur ce choix. Choisir un modèle du téléphone en mode
  `SERVER` passe en `PHONE` (choix sans risque) ; en `UNSET`, le mode n'est pas touché (#36 le demandera).
- **Délai de repli** : 8 s par défaut (2 à 30 s dans les Réglages), compté depuis la fin de la dictée, plus un quart
  de la durée de l'audio (envoi du WAV et transcription d'une longue dictée), 3 min au plus ; le délai de connexion
  est plafonné au même délai. L'ancien budget (60 s + la durée de la dictée) attendait trop longtemps.
- **Réseau** : `ConnectivityManager`, capacité INTERNET (pas VALIDATED : un serveur du réseau local n'a pas besoin
  d'Internet). En cas de doute, la requête est tentée : le repli couvre l'échec.
- **Adresse de compilation** : `BuildConfig.DEFAULT_SERVER_URL` (propriété Gradle `defaultServerUrl`, vide par
  défaut, refusée si ce n'est pas une adresse http(s)). Jamais de jeton dans l'APK. Une adresse saisie (même vide)
  remplace celle de la compilation.
- **Serveur** : `DICTEE_TOKENS` défini protège toutes les routes sauf `/`, `/healthz` et `/static/` (la page web
  ne peut alors plus appeler l'API : usage local seulement) ; défini mais sans jeton utilisable, tout est refusé.
  Chaque jeton ne voit que ses tâches (404 pour les autres, `/progress` muet). La limite de débit ne compte que les
  requêtes qui font travailler le serveur (transcription, changement de modèle, éjection), pas le suivi d'une tâche
  (l'application interroge toutes les 0,4 s). L'audio est supprimé dès que Whisper l'a lu, avant le polissage.
  `POLISH_LOG` (qui contient des transcriptions) est ignoré quand `DICTEE_TOKENS` est défini.
- Politique de confidentialité : `docs/CONFIDENTIALITE.md` (il n'y en avait pas).

## Issue #26 : Whisper turbo en 4 bits (MatMulNBits), essai hôte

- Faisabilité confirmée sur l'hôte (sherpa-onnx 2.0.1, ONNX Runtime 1.28.2, Python et JAR Linux). arm64 non mesuré.
- Mesures hôte (172 extraits québécois) : encodeur + décodeur int4 = WER 15,4 % contre 14,4 % et **2,1 fois plus lent** ; décodeur int4 seul = 14,8 %,
  vitesse égale, **pas plus petit** (embedding fp32 de 265 Mo). Encodeur int4 : 433 Mo (b32) ou 373 Mo (b128) contre 675 Mo.
- Décision proposée : garder l'int8, aucune entrée de catalogue, aucun changement de comportement. À réévaluer après mesure sur téléphone.
- Tableaux, provenance des chiffres et procédure téléphone : `android/docs/WHISPER_INT4.md`. Outils : `quantifier_int4.py`, `bench_int4.py`.

## Issue #49 : filtre de répétition pour Whisper, marge de fin courte (300)

Décisions de l'utilisateur (2026-11-13), appliquées telles quelles : choix « Automatique » dans « Silence ajouté pour
Whisper » (= marge 300 + filtre), réglage « Correction des répétitions (Whisper) » (Redécoder à 1 000 par défaut / Couper),
nouvelle section « Avancé », **défaut inchangé à 1 000**. Choix faits pendant la mise en œuvre :

- **Filtre** : `text/WhisperRepetition.kt`, objet distinct de `SafetyCheck` (non modifié), appelé par `SherpaEngine` pour
  la famille `WHISPER` seulement, et seulement quand `EngineOptions.whisperRepetitionFix` n'est pas nul (mode
  « Automatique »). Détection = `repetition()` de `reprise.py` (#40) : plafond de jetons de sherpa-onnx (6 par seconde
  d'audio, marge exclue, à une unité près), X + X, bloc d'au moins 3 mots répété aussitôt, fin qui reprend le début.
  Mots comparés avec la normalisation de `wer.py`. Deux gardes ajoutées, sans perte sur le jeu de 172 extraits (mêmes
  13 déclenchements que la référence : plafond 11, X + X 2) :
  - **X + X** exige que la première moitié finisse par `. ? ! …` : « oui oui », « Oui, oui. », « C'est ça, c'est ça. »,
    « vous vous » ne déclenchent rien. Whisper recommence une phrase *après l'avoir finie* ; une répétition voulue est
    séparée par une virgule ou rien. Risque assumé : « Oui. Oui. » serait coupé en « Oui. » (redécodé, en option
    Redécoder, ce qui redonne le texte à 1 000).
  - **Fin = début** exige une fin de phrase juste avant la reprise (« Du reste sa mère. Du »), et **au moins 4 mots**.
    La référence l'excluait déjà à 1 ou 2 mots (texte de plus de 2k mots) ; à 3 mots, un seul mot commun sur trois n'est
    pas une preuve (« Oui. Non. Oui », « Allô. Oui allô ») et la coupe retirerait un tiers du texte : la règle est
    coupée sous 4 mots. Un mot isolé répété après un point (« Friperie. Friperie. ») reste détecté par X + X.
- **Couper** : X + X → première moitié ; bloc répété → répétitions retirées, reste gardé, reprise tronquée finale
  retirée (y compris un dernier mot coupé : « Elle s » pour « Elle s'est ») ; fin = début → mots repris retirés, avec,
  pour la coupe seulement (une règle a déjà déclenché, souvent le plafond), une reprise qui s'arrête au milieu d'un mot
  (« … les localités. Le mode … les local »). Plafond sans structure répétée : texte gardé tel quel (issue « détectée,
  texte gardé » dans le rapport). La coupe se fait sur les morceaux du texte d'origine : orthographe et ponctuation
  gardées.
- **Redécoder** : `OfflineRecognizer.setConfig` avec la configuration à 1 000, décodage, puis `setConfig` à 300 (dans un
  `finally`, sous le verrou de `transcribe`). **Vérifié sur l'hôte** (JAR Linux x86-64 de sherpa-onnx 2.0.1,
  `WhisperRepetitionHostTest.setConfigChangesThePaddingOfTheLoadedModel`) : sur les 7 extraits français, le recognizer
  chargé à 300 donne après `setConfig(1000)` exactement le texte d'un recognizer construit à 1 000, puis de nouveau celui
  de 300 ; 3 extraits sur 7 changent de texte entre 300 et 1 000, donc le changement est réel. Aucun second modèle.
- **Réglages** : préférence séparée `whisper_tail_auto` (faux par défaut) à côté de `whisper_tail_paddings`, plutôt
  qu'une valeur sentinelle : une marge enregistrée (1 000, 600, 300) reste telle quelle et revient si l'on quitte
  « Automatique » ; une ancienne version de l'app ignore la nouvelle clé. `whisper_repetition_fix` (REDECODE par défaut ;
  valeur inconnue → REDECODE). Le mode et la correction font partie de `EngineOptions`, donc de la clé de `EngineManager` :
  les changer recharge Whisper (comme la marge), jamais les autres modèles.
- **Section « Avancé »** : générique (titre « Avancé » + texte d'aide, conteneur `ADVANCED_TAG`), placée en bas de l'écran ;
  elle contient pour l'instant les deux réglages Whisper (« Silence ajouté pour Whisper » quitte « Performance », sans le
  suffixe « (avancé) »). #37 y ajoutera les choix de modèle et de serveur. Choix de la marge : 1 000, 600, 300, puis
  « Automatique » (le défaut en premier, comme avant). La correction est **grisée** hors « Automatique », avec une ligne
  qui le dit (plutôt que masquée : on voit ce qu'« Automatique » apporte).
- **« Comparer les modèles »** : mêmes options que la dictée (déjà le cas) ; l'en-tête du rapport dit « silence ajouté
  Whisper automatique (300 + correction des répétitions : redécoder à 1 000 / couper le texte répété) » ou la valeur fixe ;
  une ligne par modèle compte les segments redécodés, coupés ou détectés sans coupure ; chaque extrait concerné est marqué.
  Le temps d'un extrait redécodé compte les deux décodages.
- **Tests** : `WhisperRepetitionTest` (exemples de l'issue, répétitions voulues, même mot au début et à la fin avec
  ponctuation, textes d'un à trois mots), `WhisperAutoSettingsTest` (défauts, préférences existantes gardées, correction
  sans effet hors « Automatique », autres modèles, rapport), `AdvancedSettingsInstrumentedTest` (section, grisé),
  `WhisperRepetitionHostTest` (`setConfig` réel, mode automatique de `SherpaEngine`, banc des 172 extraits, rejeu d'un TSV
  de `banc_rapide.py`), sautés sans `-PmodelsDir` / `-PquebecSetDir` / `-PrepetitionTsv`.
- **Banc hôte** (x86-64 4 cœurs, 4 threads, langue `fr` forcée, 172 extraits québécois, 1 673 mots, WER de `wer.py`),
  `WhisperRepetitionHostTest.quebecSetBench` : `SherpaEngine` de l'app, turbo à 1 000 remesuré dans la même exécution, en
  alternance clip par clip avec les deux options (1 passage, une chauffe) :

  | Variante | Temps | WER | Segments corrigés |
  | --- | --- | --- | --- |
  | Turbo, marge 1 000 (défaut) | 259,8 s | 14,5 % | — |
  | Automatique + Redécoder à 1 000 | 199,6 s (**−23,2 %**) | **14,8 %** | 13 redécodés |
  | Automatique + Couper | 182,8 s (**−29,6 %**) | **15,1 %** | 13 coupés |

  Les deux options remplissent le critère de l'issue (au moins 10 % sous turbo, WER ≤ turbo + 2 points). Rejeu du TSV
  d'une exécution séparée de `banc_rapide.py` (turbo 431,6 s / 14,5 %, marge 300 seule −31,3 % / 22,0 %) par le filtre de
  l'app : Redécoder −21,3 % / 14,8 %, Couper −31,3 % / 15,1 % (16,3 % avant l'extension de la coupe aux reprises arrêtées au
  milieu d'un mot, le chiffre de l'issue). Mesures hôte seulement : la mesure sur le téléphone reste à faire (procédure
  dans la PR de #49).

## Issue #35, lot 1 : transcription par blocs en mode serveur, polissage à la fin

Revient sur deux décisions de #13 : « tout l'audio part en une seule requête à la fin » et « rien n'est envoyé
pendant la dictée ». Le contrat du serveur est celui de la PR #46 (`POST /polish`, champ `prompt` de
`POST /jobs`), suivi tel quel ; aucun changement côté serveur dans ce lot.

### Découpage (`BlockCutter`, fil de capture)

- Un bloc commence au début de la parole (moins les 0,6 s de marge habituelles), jamais avant la fin du bloc
  précédent : aucun échantillon n'est envoyé deux fois, et le silence entre deux blocs n'est pas envoyé. Les
  pauses à l'intérieur d'un bloc sont gardées (ponctuation, rythme).
- Le bloc se ferme à la première pause confirmée par le VAD (un segment vient de se terminer) une fois 15 s
  atteintes, et au plus tard à 30 s (fenêtre de Whisper), même au milieu d'une phrase. Dans ce dernier cas, la
  phrase en cours est partagée entre les deux blocs, et chaque bloc garde sa part pour un repli local.
- Chaque bloc garde ses segments VAD (coupés à ses bornes) : c'est ce que transcrit le modèle du téléphone s'il
  faut se replier, comme pour la dictée entière (les modèles du téléphone ne prennent pas 30 s d'un coup).

### Envoi (`BlockDictation`, fil de décodage)

- Les blocs partent un par un, dans l'ordre (le serveur n'exécute qu'une tâche à la fois) : `POST /jobs` avec
  `quick=true`, `polish=false`, `prompt` = les 200 derniers caractères du texte du bloc précédent (coupés sur un
  mot ; le serveur en accepte 1000), puis `GET /jobs/{id}`. Les textes sont rangés par numéro de bloc.
- **Langue** : si aucune n'est réglée, celle que le serveur a détectée sur le premier bloc est imposée aux
  suivants et au polissage.
- **Délais** : chaque bloc a le budget du réglage « délai avant repli » (8 s + un quart de la durée de l'audio,
  #23). À l'arrêt, les blocs qui restent partagent un seul budget, compté depuis l'arrêt pour l'audio qui reste
  (un serveur plus lent que le temps réel ne fait pas attendre un délai complet par bloc en retard). Au-delà,
  ils passent au téléphone. Le polissage final a son propre budget, le même que la tâche de la dictée entière
  (8 s + un quart de la durée de toute la dictée) ; s'il est dépassé, le texte brut est inséré.
- **Dictée d'un seul bloc** (moins de 15 s environ) : une seule tâche, transcrite et polie ensemble
  (`polish=true`), sans `POST /polish` séparé : pas d'aller-retour de plus pour les dictées courtes.
- **Polissage** : à la fin, `POST /polish` avec tout le texte brut (textes du serveur et du téléphone, dans
  l'ordre) et la langue, puis `GET /jobs/{id}`. Inséré : `polished_text` si `polish.applied`, sinon le texte
  brut, avec le message « Texte non poli (raison) ». Tous les cas de la PR #46 (`error`, `timeout`,
  `unsupported`, `safety_check:…`) sont un repli normal, pas une erreur.
- **Plafond de 400 mots** de `app/polish.py` : une dictée de plus de 3 min environ revient brute
  (`fallback_reason: "unsupported"`, message « texte trop long pour le polissage »). Pas de découpage côté
  client : c'est le lot 2. À mesurer au point de contrôle.
- Le polissage n'est pas demandé quand il ne peut pas servir : réglage coupé, API OpenAI, « Annuler », serveur en
  échec depuis moins de 60 s, ou téléphone qui a pris le relais après l'arrêt (le texte brut est inséré tout
  de suite plutôt que de refaire attendre l'utilisateur).

### Repli et fiabilité

- L'audio d'un bloc est gardé jusqu'à réception de son texte, puis libéré : la mémoire ne dépend plus de la
  durée de la dictée (sauf pendant la détection de l'ancien serveur, voir plus bas).
- **Bloc en échec** (réseau, 5xx, délai, réponse illisible, 401/403/302) : transcrit par le modèle du téléphone
  aussitôt, pendant la dictée ; les textes déjà reçus sont gardés. Le serveur est ensuite évité 60 s, comme
  pour la dictée entière (`FallbackEngine`) : sans cela, chaque bloc suivant coûterait un délai complet.
  Passé ce délai, les blocs suivants retentent le serveur.
- **429** (limite de débit, 30 requêtes par minute et par jeton) : un échec de bloc comme un autre, replié sur
  le téléphone, mais le serveur est évité pendant la durée de `Retry-After` (60 s s'il n'y en a pas), pas
  plus. Un bloc toutes les 15 à 30 s, plus la sonde et le polissage, reste loin de la limite.
- **Annuler** (pendant l'attente finale) : la tâche serveur en cours est annulée, ce qui manque est transcrit
  sur le téléphone, rien n'est poli ; les textes reçus sont gardés. Une annulation ne déclenche pas le délai
  de 60 s.
- **Champ changé, clavier fermé** (`cancel`) : toutes les requêtes en cours sont annulées (la tâche serveur aussi,
  même si son id arrive après coup), aucun bloc en file n'est envoyé, rien n'est poli, rien n'est inséré.
  Chaque requête a son propre `WaitControl` (`child()`), refermé à sa fin : une annulation n'envoie donc pas
  de `cancel` pour les tâches déjà terminées.
- **Bloc que ni le serveur ni le téléphone n'ont transcrit** (pas de modèle de repli, ou modèle du téléphone
  en échec : non chargé, mémoire) : son audio est gardé et il est réessayé une fois à la fin (serveur, puis
  téléphone), avant le polissage. S'il échoue encore, il est compté comme perdu, et **les autres blocs sont
  quand même insérés**, avec le message d'erreur « N bloc(s) sur M non transcrit(s) ». Une erreur sans
  insertion n'arrive que si aucun bloc n'a pu être transcrit.
- Chaque appel au moteur passe par `EngineManager.use` (`LentBlockBackend`) : un moteur libéré ou remplacé
  pendant la dictée n'est jamais utilisé.

### Ancien serveur (sans `POST /polish`) : quand le détecter

- **Au début de la dictée**, avant le premier bloc : une sonde `POST /polish` avec un texte vide. Le serveur de
  la PR #46 la refuse (400) sans créer de tâche ; un ancien serveur répond 404 ou 405. La réponse arrive
  pendant que l'utilisateur parle : elle ne coûte rien à l'attente finale.
- Jusqu'à la réponse, le clavier garde à la fois l'audio complet et les blocs. 404/405 : la dictée continue en
  mode « dictée entière » (une requête avec polissage à la fin, exactement comme avant) et les blocs en file
  sont ignorés ; aucun bloc n'a donc été envoyé pour rien. Toute autre réponse : mode blocs, et l'audio complet
  est libéré.
- Pourquoi pas à la fin, au moment du polissage : il faudrait garder l'audio complet de toute dictée (5 min, au
  moins 19 Mo) pour le renvoyer en entier, et le serveur aurait transcrit deux fois la même dictée ; ou bien
  insérer les blocs sans polissage, ce qui ferait perdre le polissage aux anciens serveurs. Si la sonde n'a pas
  pu conclure (serveur injoignable au début) et que `POST /polish` répond 404/405 à la fin, le texte brut des
  blocs est inséré (« polissage absent du serveur ») : rien n'est perdu.
- La sonde n'est faite que si le polissage est demandé (sinon `POST /jobs` sans polissage suffit, ancien serveur
  compris), et une seule fois par moteur une fois la route vue. Elle compte dans la limite de débit.
- Signalé dans la PR plutôt que changé au serveur : un champ dans `/health` (par exemple `"polish_route": true`)
  éviterait cette sonde. Le contrat actuel ne l'a pas, et elle est conforme au contrat (400 documenté).

### Questions ouvertes de l'issue, tranchées (choix le plus prudent)

- **Limite de 5 min par dictée : gardée**, en mode blocs aussi. Elle venait de la taille du WAV unique, mais le
  polissage ne traite de toute façon pas plus de 400 mots (environ 3 min), le repli « dictée entière » pour les
  anciens serveurs garde encore tout l'audio, et le point de contrôle n'a pas encore mesuré les dictées longues.
  À revoir après le point de contrôle et le lot 2.
- **Mode « dictée entière » : seulement un repli** (serveur sans `POST /polish`), pas un réglage. Un réglage de
  plus toucherait `AppSettings` et l'écran des Réglages, modifiés en parallèle (#49), et l'utilisateur n'a pas
  d'élément pour choisir avant le point de contrôle. La comparaison des deux modes sur le même audio se fait
  dans le « Diagnostic serveur ». Si le point de contrôle montre une qualité moindre par blocs, ajouter le
  réglage à ce moment-là.

### Interface

- Pas de texte provisoire (inchangé, hors portée). L'attente commence à l'arrêt, même si des blocs sont encore
  en file : « Transcription et polissage… (3 blocs sur 4) », avec Annuler. Le bouton disparaît dès que le
  téléphone prend le relais.
- Si des blocs ont été transcrits sur le téléphone : « N bloc(s) sur M transcrit(s) sur le téléphone (raison) ».
- `DictationResult` porte `blocks`, `localBlocks` et `waitMillis` (de l'arrêt au résultat), écrits dans logcat
  (`DicteeIme`) à chaque dictée.
- **Diagnostic serveur** : chaque essai est fait deux fois sur le même audio : par blocs (joué en temps réel à
  travers le vrai `DictationController` et le VAD Silero du clavier, sans repli local), puis en dictée entière
  (une requête, comme #13). Pour chacun : attente après l'arrêt, nombre de blocs, statut du polissage, texte brut
  et poli. Bouton « Essais 30 s, 2 min, 5 min » : les enregistrements de référence enchaînés (1 s de silence
  entre eux), répétés au besoin et coupés à 30 s, 2 min et 4 min 50 s (sous la limite de 5 min, marges de
  lecture comprises) : les trois durées du point de contrôle.

### Tests

- `BlockDictationTest` (JVM, faux serveur HTTP au contrat de la PR #46, source audio « à portes » qui ne
  livre la partie k qu'une fois le bloc k-1 reçu par le serveur, ce qui prouve l'envoi pendant la dictée) :
  N blocs → N requêtes dans l'ordre pendant la dictée, puis seulement le dernier bloc et le polissage à
  l'arrêt, une seule insertion du texte poli ; `prompt`, langue détectée puis imposée, langue forcée ; ordre
  gardé avec un bloc lent ; dictée d'un bloc ; bloc en 502 → téléphone, textes gardés ; 429 avec
  `Retry-After` → téléphone puis serveur de nouveau ; polissage non appliqué (`unsupported`, `timeout`,
  `error`, `safety_check`) et en erreur HTTP → texte brut ; ancien serveur (404 et 405) → dictée entière ;
  serveur coupé au milieu → rien de perdu ; sans modèle local → second essai à la fin ; champ changé pendant la
  dictée et pendant le polissage → tâche annulée, rien d'envoyé ensuite, rien d'inséré ; résultat gardé pour le
  même champ ; Annuler pendant l'attente finale ; dictée sans parole ; polissage coupé ; API OpenAI ; durée
  maximale ; diagnostic.
- `BlockCutterTest` : regroupement jusqu'au minimum, coupe aux pauses, plafond, silence initial et longue pause
  non envoyés, aucun échantillon dans deux blocs, rien de perdu à la coupe forcée.
- Tests instrumentés mis à jour : `dictationOnARemoteServer` (une dictée d'un bloc : une tâche polie, plus la
  sonde) et `serverChosenInTheSettingsIsRespected` (faux serveur sans `POST /polish` : dictée entière).

### Revue indépendante (agent sans le contexte de conception) et corrections

- **Un bloc perdu faisait perdre toute la dictée** : une exception du modèle du téléphone, ou l'échec du
  second essai sans modèle local, remontait jusqu'au fil de décodage. Les textes déjà reçus n'étaient alors
  pas insérés. Corrigé : chaque bloc est rattrapé à part (voir ci-dessus, `lostBlocks`). Tests :
  `localModelFailingOnABlockKeepsItForTheEndAndTheOtherTexts`, `aBlockLostEverywhereDoesNotLoseTheOthers`,
  `everyBlockLostIsAnErrorAndTheWaitEnds`.
- **Indicateur d'attente resté allumé** si le fil de décodage s'arrêtait sur une erreur avant la fin de la
  capture. Corrigé : la capture éteint l'attente qu'elle vient d'allumer si le décodage est déjà terminé
  (`decodeEnded`).
- **Audio en double dans le repli local** : le VAD Silero coupe une parole continue à 20 s sans nouveau début
  de parole. La coupe forcée à 30 s ajoutait alors une seconde fois ces 20 s aux segments du bloc. Corrigé :
  le début de l'énoncé en cours avance à la fin de chaque segment rendu par le VAD. Test
  `vadCuttingLongSpeechWithoutPauseDoesNotDuplicateTheLocalFallbackAudio`, qui échoue sans la correction.
- **Segment isolé de plus de 30 s** (le VAD n'a pas signalé le début de la parole) : découpé en blocs de 30 s
  au plus.
- Non corrigé, limites connues :
  - un bloc déjà en cours d'envoi à l'arrêt garde son propre budget (8 s + un quart de sa durée, au plus
    15,5 s avec le réglage par défaut) avant que le budget commun de fin s'applique aux suivants : l'attente
    peut donc dépasser un peu le réglage ;
  - si le serveur est en échec récent au début de la dictée, la sonde n'est pas faite. Un ancien serveur
    reçoit alors des blocs (sans polissage), et le texte brut est inséré au lieu du texte poli. Rien n'est
    perdu.

## Issues #55 et #57 : les deux étapes du clavier, le serveur hors du parcours (PR 1 du retour de #54)

Fait d'un seul tenant, sans intervention humaine, dans l'ordre demandé par #54 (#55 puis #57, même écran).
Les « décisions proposées, à confirmer » des deux issues sont appliquées telles quelles. Changement
d'orientation du 2026-11-13 au soir (décision de l'utilisateur) : le serveur distant n'est plus une piste
de produit ; il ne sert qu'au débogage. Cela annule ce que #36 et #37 disent du serveur.

### #55 : écran d'installation

- **Une seule page**, comme proposé : les étapes en tête, une carte par étape (numéro et titre, ce que
  fait l'étape et ce qu'Android va montrer, état, bouton). L'avertissement d'Android est annoncé avant
  que la personne le voie.
- **Numérotation** : 1. Activer Kalmia Blabla, 2. Choisir Kalmia Blabla, 3. Autoriser le micro,
  4. Vos langues, 5. Modèles. Le micro passe de 2 à 3 pour garder les deux gestes du clavier côte à côte.
- **Un bouton plein au plus** : celui de `SetupProgress.nextStep` (activer → choisir → micro → modèle).
  Tous les autres boutons de l'écran sont en contour, y compris « Comparer les modèles », le bouton de
  la carte du test de lecture (`participationCard(filled = false)`) et ceux de l'avis de remplacement de
  modèle. Quand l'étape suivante est le modèle, c'est le « Télécharger » du modèle recommandé qui est
  plein ; tout fait, aucun bouton plein. Le style est noté sur chaque bouton (`R.id.button_outlined`)
  pour que le test le lise sans dépendre des attributs Material.
- **Étape inactive** : « Choisir Kalmia Blabla » est désactivé tant que le clavier n'est pas activé, et
  sa carte est atténuée (opacité 0,5, `DIMMED_ALPHA`) avec l'état « À faire après l'étape précédente ».
  Une étape faite garde son bouton, en contour, pour la refaire.
- **Micro** : son bouton reste actif à tout moment (il ne dépend pas du clavier), mais il n'est plein
  que quand il est l'étape suivante.
- **« Complète » veut dire activé et choisi** (`SetupProgress.isComplete` prend quatre arguments). « Choisi »
  sans « activé » (lecture incohérente des réglages système) ne compte pas. Conséquence voulue : le
  tutoriel ne s'ouvre plus tout seul tant que le clavier n'est pas le clavier courant ; et une personne
  qui revient à un autre clavier voit l'étape 2 redevenir « À faire ».
- **Rappel au retour** (point 6) : si la personne a déjà quitté l'écran une fois (`setupSeen`, posé dans
  `onStop`) et qu'une des deux étapes du clavier manque, l'écran défile jusqu'aux étapes à chaque
  `onResume`. Jamais à la toute première ouverture : l'accueil est lu d'abord. Le retour de la liste des
  claviers d'Android passe par `onStop`, donc après l'étape 1 l'écran revient directement sur l'étape 2.
- **« Comment dicter »** descend sous les étapes ; ni son contenu ni son ouverture automatique ne
  changent (#56).
- **Modèle prêt** : un serveur configuré ne compte que s'il est réellement utilisé (adresse **et** mode
  « serveur ») ; avant, une simple adresse suffisait.
- **Tests** : l'état du système (clavier activé, choisi, micro) se force par `TestHooks` pour dessiner
  les trois états sans toucher aux claviers de l'émulateur ni retirer une permission (ce qui tue le
  processus).

### #57 : le serveur hors du parcours

- **Installation** : plus de carte « Serveur distant », plus aucun mot sur un serveur sans adresse ni
  mode « serveur » (test instrumenté qui lit tout le texte de l'écran). L'aide « Vos langues » ne dit plus
  « Whisper et le serveur la reçoivent ».
- **Réglages** : les sections « Serveur distant » et « Envoi au serveur » (« Où transcrire ») passent dans
  « Avancé », dans une sous-section repliée à l'ouverture, titrée « Serveur distant, pour essais
  (développeurs) ». Pas de geste secret (décision de l'utilisateur). « Avancé » lui-même reste déplié :
  les réglages Whisper de #49 ne bougent pas, et la session de #53 y ajoute les siens. La sous-section
  est placée après les réglages Whisper, en un seul bloc, pour limiter les conflits.
- **Repli** : un en-tête de la taille d'un titre, avec un chevron (▸/▾), d'au moins 48 dp, dont l'état
  (« replié » / « déplié ») est annoncé aux lecteurs d'écran (`Ui.collapsible`). Ouvert depuis la ligne
  du serveur de l'écran principal, ou depuis le menu du clavier, l'écran déplie la section et défile
  jusqu'au choix « Envoi au serveur ».
- **Textes vrais dans les deux cas** : l'accueil (`setup_intro`) et « Comment dicter » (`howto_privacy`)
  disent simplement que la voix est transcrite sur le téléphone et n'est envoyée à personne. Quand un
  serveur est réellement utilisé (`AppSettings.serverInUse` : adresse et mode « serveur »), ils sont
  remplacés par `setup_intro_server` et `howto_privacy_server`, et l'avertissement d'Android de l'étape 1
  perd sa fin « votre voix, elle, reste sur votre téléphone ». Aucune phrase n'est donc fausse pour
  quelqu'un qui a configuré un serveur. L'exception du serveur est expliquée dans sa section.
- **Mise à jour sans perte** : une adresse et le mode « serveur » déjà enregistrés restent actifs (aucune
  migration ne touche au mode). Dans ce cas seulement, l'écran principal affiche « Transcription :
  serveur distant » avec l'adresse et un bouton « Voir le réglage ».
- **Code du serveur gardé** : `RemoteEngine`, le mode blocs de #35, le diagnostic, le repli et leurs
  tests ne changent pas. Le serveur reste dans le menu de modèles du clavier, mais seulement une fois
  son adresse saisie (comportement inchangé).
- **`-PdefaultServerUrl`** reste pour les builds d'essai ; la release publique est compilée sans adresse.
  Même avec une adresse compilée, rien ne part sans le mode « serveur ».

### La règle sans compromis, et sa preuve

« Aucune dictée ne part vers un serveur sans que la personne l'ait configuré elle-même dans les réglages
avancés. » Elle tient à deux conditions, chacune testée :

1. **Le routage** : une dictée va au serveur seulement avec une adresse **et** le mode « serveur »
   (`EngineChoice.route`, toutes les combinaisons dans `ServerOnlyByChoiceTest`, sur l'appareil dans
   `ServerModeInstrumentedTest`, qui vérifie aussi qu'aucune requête n'arrive au faux serveur sans ce
   choix).
2. **Le consentement** : le mode « serveur » n'est écrit qu'à un seul endroit de l'application, le choix
   de `SettingsActivity`, dans la section repliée d'« Avancé ». `ServerOnlyByChoiceTest` parcourt les
   sources et échoue si un autre fichier affecte `SERVER` au mode ou écrit la préférence directement.
   L'installation ne peut plus l'enregistrer (la carte qui y menait a disparu), et la PR suivante (test
   rapide) ne l'enregistre pas non plus.

### Revue indépendante de la PR (corrections)

Revue par un autre agent, relayée par l'orchestrateur ; chaque constat vérifié dans le code avant correction.

- **Saut vers les étapes** : il se faisait à chaque `onResume`, et `setupSeen` était posé même pendant une
  rotation. Une rotation à la première visite sautait l'accueil, et un retour (Réglages, dialogue de
  notification d'Android 13+ ouvert par « Télécharger ») ramenait en haut une personne qui avait fait
  défiler. Désormais : seulement à une **nouvelle ouverture** de l'écran (`savedInstanceState == null`), et
  `setupSeen` n'est pas posé pendant un changement de configuration. Limite connue : rouvrir
  l'application depuis le lanceur alors que l'écran est encore en mémoire ne relance pas le saut.
- **Étape 2, « choisi »** (décision de l'orchestrateur pour lever l'ambiguïté de #55) : faite si Kalmia est
  le clavier actif **ou** l'a déjà été une fois (`keyboardChosenOnce`, mémorisé dès que c'est constaté). Une
  personne qui garde son clavier habituel et ne bascule que pour dicter a fait l'étape ; l'état dit alors
  « ✓ Fait (un autre clavier est actif en ce moment) », sans bouton plein. « Complète » suit la même règle.
- **Ligne du serveur** : « Le modèle du téléphone sert de repli » n'est dite que si un modèle est installé ;
  sinon, la ligne dit qu'une dictée est perdue si le serveur ne répond pas.
- **Textes** : la politique de confidentialité redit, en tête de « Sur le téléphone », l'exception du serveur
  configuré dans les réglages avancés ; le README et le commentaire du clavier disent que le serveur figure
  dans le menu des modèles dès qu'une adresse est configurée, compilée avec `-PdefaultServerUrl` comprise.
- **Comparaison des modèles** (code déjà dans `main`) : elle incluait le serveur dès qu'une adresse était
  configurée, quel que soit le mode, et lui aurait envoyé l'audio de la comparaison. Elle ne l'inclut plus
  que s'il est utilisé (adresse **et** mode « serveur », `EngineChoice.comparedModels`), testé.
- **`ServerOnlyByChoiceTest`** : le test par recherche de texte est renommé
  `noSourceFileButTheSettingsAssignsTheServerMode` et son commentaire dit sa limite (il manquerait une
  écriture par `valueOf` ou par un `.name` stocké).

### À vérifier par un humain (rien n'a tourné sur un vrai téléphone)

- Refaire l'installation sur un téléphone remis à zéro, idéalement avec une personne qui n'a jamais vu
  l'application, sans l'aider : l'ordre des deux gestes est-il évident ? L'avertissement d'Android
  fait-il reculer ?
- Au retour de la liste des claviers d'Android, l'étape 1 a-t-elle sa coche ? Au retour du sélecteur de
  clavier, l'étape 2 passe-t-elle tout de suite à « Fait », ou faut-il quitter et revenir
  (`onWindowFocusChanged`) ?
- Revenir à son clavier habituel : l'étape 2 affiche-t-elle « Fait (un autre clavier est actif en ce
  moment) » ?
- Avec un serveur déjà configuré avant la mise à jour : les dictées vont-elles toujours au serveur, et la
  ligne « Transcription : serveur distant » est-elle visible ?
- Lecteur d'écran (TalkBack) : l'en-tête replié annonce-t-il son état ?

## Issue #53 : Whisper local, contexte entre segments et regroupement des énoncés

Décisions de l'utilisateur (2026-11-13), appliquées telles quelles : deux mécanismes essayables par un réglage chacun, dans
« Avancé », **désactivés par défaut** ; un résultat de banc défavorable ne retire pas un réglage. Choix faits pendant la
mise en œuvre :

### Contexte entre segments : pas possible avec l'AAR sherpa-onnx 2.0.1 standard (point d'arrêt)

- **Code source de la v2.0.1** (tag `v2.0.1`, commit `af2d8d81dbfa61c4cea4c568da78f3982edca005`) :
  - `sherpa-onnx/csrc/offline-whisper-model-config.h` : `encoder`, `decoder`, `language`, `task`, `tail_paddings`,
    `enable_token_timestamps`, `enable_segment_timestamps`. Rien d'autre ; la classe Kotlin
    (`OfflineWhisperModelConfig`) et la lecture JNI (`jni/offline-recognizer.cc`, lignes 146 à 172) ont les mêmes champs.
    Les seuls `prompt` de la JNI sont ceux de FunASR-nano (`systemPrompt`, `userPrompt`).
  - `offline-whisper-greedy-search-decoder.cc` : le décodage part de `model_->GetInitialTokens()`, c'est-à-dire la
    métadonnée `sot_sequence` lue dans le fichier ONNX au chargement (`offline-whisper-model.cc`, ligne 371) ; seuls la
    langue et la tâche y sont remplacées, puis `no_timestamps` est ajouté. Aucun jeton `<|startofprev|>`, aucune entrée
    pour des jetons de texte avant `<|startoftranscript|>`.
  - `OfflineStream::SetOption` existe, mais `offline-recognizer-whisper-impl.h` ne lit aucune option du flux
    (`GetOption` n'est appelé que par Qwen3-ASR, FunASR-nano, Canary, SenseVoice, FireRedASR et des modèles en ligne).
- **Essai sur le JAR Linux x86-64 de la 2.0.1** (`WhisperPromptHostTest`) : la classe de configuration n'a pas de champ
  d'amorce (vérifié aussi sans modèle, donc sur la CI : le test échouera si une version future en ajoute un) ; avec le vrai
  turbo, une amorce (fin du texte précédent, 200 caractères) passée par les options de flux `prompt`, `initial_prompt`,
  `context`, `hotwords`, `prefix` et `text` est bien enregistrée par le flux (relue par `getOption`), mais le texte décodé
  est identique octet pour octet à celui sans amorce, sur les 7 extraits français.
- **`setConfig` ne peut pas servir** : il ne remplace que le bloc Whisper de la configuration (les champs ci-dessus).
- **Ce qui est démontré, et ce qui ne l'est pas** : démontré par l'exécution, l'absence de champ d'amorce dans la
  configuration (réflexion, vérifiée en CI) et l'absence d'effet des six options de flux essayées ; le reste (aucun autre
  chemin vers le décodeur) repose sur la lecture du code source. Une JNI compilée sur mesure reste une voie possible
  (ci-dessous) : la conclusion vaut pour l'AAR 2.0.1 standard, pas pour sherpa-onnx en général.
- **Donc ni réglage ni code pour le contexte dans cette PR** : un réglage sans effet tromperait l'utilisateur. Ce qu'il
  faudrait : (1) une version de sherpa-onnx qui ajoute une amorce au décodeur Whisper hors ligne (un champ de
  configuration ou une option de flux lue par `OfflineWhisperGreedySearchDecoder`, qui préfixe `<|startofprev|>` + les
  jetons du texte à `sot_sequence` et décale `offset`), à proposer en amont ; ou (2) une JNI maison, compilée avec l'app,
  qui fait ce préfixe : le décodeur ONNX exporté reçoit déjà des séquences initiales de longueurs différentes (3 ou 4
  jetons selon les horodatages, `offset` = `initial_tokens.size()`), à vérifier pour une centaine de jetons ; mais il faut encoder le texte en jetons (le `tokens.txt` de sherpa-onnx n'a que
  la table jeton → texte) ; ou (3) réécrire `sot_sequence` dans le modèle, ce qui figerait une seule amorce par fichier :
  inutilisable pour un contexte qui change à chaque segment. Aucune des trois n'est dans le périmètre de l'issue.

### Regroupement des énoncés (`LocalGrouping`, `BlockCutter`)

- **`BlockCutter` réutilisé** (le découpeur des blocs du serveur, #35), avec un paramètre de plus, `idleSamples`
  (0 pour le serveur : comportement inchangé, `BlockCutterTest` intact). Un groupe se ferme : à la première pause confirmée
  par le VAD une fois le seuil atteint ; après **1 s de silence** qui suit son dernier énoncé, même s'il est plus court
  (coupé à la fin de cet énoncé : le silence n'est pas décodé, et un énoncé que le VAD rendrait plus tard n'est pas
  perdu) ; à **29 s** au plus, même au milieu d'une phrase (voir « Revue indépendante » : sherpa-onnx jette tout ce qui
  dépasse 29,5 s d'une entrée Whisper) ; à l'arrêt.
- **Pourquoi la fermeture sur silence** : sans elle, une phrase isolée suivie d'un silence n'apparaîtrait qu'à la phrase
  suivante ou à l'arrêt, ce qui contredit l'insertion à chaque pause du mode local. Avec elle, le retard d'une phrase
  isolée est borné (pause du VAD, 0,5 s par défaut, + 1 s). Une pause tolérée dans un groupe vaut donc la pause du VAD + 1 s
  (1,5 s avec le réglage par défaut) : les phrases enchaînées sont groupées, une vraie pause rend le texte.
- **Seuils proposés : 5 s et 10 s** (réglage « Non / 5 s / 10 s »), plutôt que 15 s : en local le texte arrive groupe par
  groupe, et le seuil borne le retard d'un énoncé dans une dictée continue. Les deux sont offerts parce que le banc ne les
  départage pas nettement (10 s : un peu meilleur, plus lent à venir), et l'utilisateur juge sur le téléphone.
- **Whisper seulement** (`WhisperTuning.groupingFor`), en dictée locale : sans effet sur Parakeet, Qwen3-ASR et le mode
  serveur. Signalé sans l'activer : le mécanisme ne dépend pas du modèle, mais il n'est pas mesuré pour Parakeet (qui se
  dégrade au-delà de 20 s environ et dont les textes provisoires suivent les énoncés).
- **Pas dans `EngineOptions`** : le réglage ne change que la découpe, pas le modèle ; le changer ne recharge rien.
  Préférence `whisper_group_seconds` (0 par défaut ; valeur inconnue → 0).
- **Contrôleur** : `DictationSession.localGrouping` (nul par défaut). Le groupe vit dans le fil de capture et passe en
  `Job.Final` comme un énoncé aujourd'hui (insertion, textes gardés pendant l'absence du champ, numéro de session) : une
  annulation (champ changé, clavier fermé) jette le groupe en cours sans le décoder, un arrêt le décode.
- **Écran « Comparer les modèles »** : avec le réglage actif, chaque extrait est découpé comme une dictée (VAD Silero de
  l'app + groupes, `LocalCut`, le rejeu exact des appels du contrôleur) et chaque groupe est décodé ; le rapport « Copier »
  écrit le réglage dans l'en-tête et le nombre de groupes par extrait. Réglage inactif : extrait décodé entier, comme avant
  (ce n'est pas la découpe de la dictée par énoncé, mais c'est le comportement d'avant, gardé tel quel).

### Banc hôte (`LocalGroupingHostTest`, 172 extraits québécois, 1 673 mots, x86-64 4 cœurs, 4 threads, `fr` forcé)

Les extraits de Common Voice sont des phrases isolées : pris trois par trois dans l'ordre du fichier (58 dictées,
945,7 s d'audio), silence de début et de fin retiré au VAD (0,3 s gardées), 0,7 s de silence entre eux (pause d'environ
1,3 s entre deux phrases). Chaque dictée passe par `LocalCut` avec le vrai VAD Silero (pause 0,5 s) et le vrai turbo
(marge 1 000, défaut) de `SherpaEngine` ; variantes en alternance dans la même exécution ; WER avec la normalisation de
`wer.py`, comme #49.

| Variante | Temps de décodage (mesuré) | Décodages | WER (mesuré) | Délai fin d'énoncé → insertion (estimé, hôte) |
| --- | --- | --- | --- | --- |
| Turbo, extrait entier (repère du banc de #49) | 258,8 s | 172 | 14,5 % | — |
| **Actuel** : un décodage par énoncé (défaut) | 229,2 s | 186 | **17,5 %** | moyenne 1,73 s, médiane 1,74, 90e centile 2,14, max 2,54 |
| Groupes de 5 s | 239,9 s (+4,7 %) | 171 | **14,0 %** | moyenne 2,72 s, médiane 2,47, 90e centile 3,06, max 9,66 |
| Groupes de 10 s | 239,2 s (+4,4 %) | 165 | **13,7 %** | moyenne 3,40 s, médiane 2,89, 90e centile 4,16, max 13,37 |

- Le repère redonne le 14,5 % de #49 : la mesure est comparable. La dictée locale actuelle fait **3 points de plus** que
  l'extrait entier : le VAD coupe 14 phrases en deux (186 énoncés pour 172 extraits) et ses segments sont serrés.
- **D'où vient le gain** (analyse du TSV) : sur les 44 dictées où les trois variantes font le même nombre de décodages,
  les erreurs passent de 179 à 148 (5 s) ; sur les 14 où le regroupement réunit des morceaux, de 114 à 86. Plus de la
  moitié du gain vient donc des marges du découpage par groupes (0,6 s avant le début de la parole, et la pause jusqu'à ce
  que le VAD la confirme), pas du regroupement lui-même. Piste signalée, hors portée : ajouter ces marges aux énoncés du
  mode actuel améliorerait peut-être aussi le défaut. Avec des pauses de 1,3 s, les groupes réunissent rarement deux
  phrases ; des pauses plus courtes en réuniraient davantage.
- **Délai** : estimé, pas mesuré. L'audio est supposé arriver en temps réel, chaque morceau décodé dès qu'il est prêt et
  que le décodage précédent est fini, avec les temps mesurés sur l'hôte. Le regroupement ajoute environ 1 s (5 s) à 1,7 s
  (10 s) en moyenne ; le maximum vient d'un énoncé au début d'un long groupe. Sur le téléphone, où Whisper est plus lent
  (#23), le délai sera plus long dans les trois variantes ; non mesuré.
- Contexte : aucun chiffre (pas possible avec l'AAR standard, voir plus haut), donc ni « contexte seul » ni « les deux ensemble ».

### Tests

- `LocalGroupingTest` (rejeu `LocalCut`, VAD à énergie) : hors réglage, exactement les énoncés du VAD ; énoncés courts
  groupés avec leurs pauses ; fermeture à la première pause passé le seuil ; fermeture sur silence sans décoder le silence ;
  pause tolérée = pause du VAD + 1 s ; plafond de 29 s atteint et jamais dépassé avec 50 s de parole continue ; fin de dictée ; aucun échantillon
  perdu ni décodé deux fois ; valeurs du réglage et familles ; libellé du rapport.
- `LocalGroupingControllerTest` (vrai `DictationController`) : réglage nul par défaut ; **hors réglage, le contrôleur décode
  exactement les énoncés du VAD, un par un (critère « identique à aujourd'hui »)** ; avec réglage, il décode exactement
  les groupes de `LocalCut` (le rejeu de l'écran de comparaison et du banc est donc fidèle) ; plafond ; arrêt avec un
  groupe ouvert (décodé) ; annulation avec un groupe ouvert (rien décodé, rien inséré) ; changement de champ (le groupe du
  champ 1 n'arrive jamais au champ 2) ; phrase isolée insérée après le silence, avant la suivante ; écran de comparaison.
- `WhisperPromptHostTest` : la preuve du point d'arrêt (ci-dessus).
- `LocalGroupingInstrumentedTest` (appareil, leçon de la PR #52) : `LocalCut` avec le VAD Silero de l'APK sur les extraits
  inclus, libellé du rapport, réglage dans « Avancé », désactivé par défaut, enregistré.
- Aucune expression régulière nouvelle ; `AndroidRegexGuardTest` inchangé.
- **Écueil trouvé en écrivant le banc** : un seul `acceptWaveform` de tout un extrait au VAD Silero de sherpa-onnx 2.0.1
  rend un segment faux (début à 4,5 s pour une parole qui commence à 1,3 s) ; par morceaux de 512, comme le contrôleur, le
  segment est juste. Le contrôleur et `LocalCut` passent toujours par morceaux de 512.

### Revue indépendante (session distincte) et corrections

1. **Plafond des groupes trop haut (bloquant, corrigé)**. Vérifié dans `offline-recognizer-whisper-impl.h` (v2.0.1,
   `DecodeStream`) : `if (num_frames >= max_num_frames - 50)` avec `max_num_frames = 3000`, donc toute entrée Whisper
   de 29,5 s ou plus est tronquée à 2 950 trames, avec un simple message dans le journal. Le plafond de 30 s moins
   512 échantillons laissait jeter environ 0,45 s à chaque fermeture au plafond, échantillons que le groupe suivant ne
   reprenait pas. `LocalGrouping.MAX_SECONDS` passe à **29 s**. Le plafond de jetons de `WhisperRepetition` (calculé
   sur les échantillons) reste donc juste. Tests resserrés : plafond atteint (plus de 28 s) et jamais dépassé
   (29 s), dans `LocalCut` et dans le contrôleur. Les chiffres du premier banc ne changent pas : aucune de ses dictées ne
   dépasse 25,9 s. Les blocs du mode serveur (#35, 30 s) partent au serveur, pas à sherpa-onnx : non touchés.
2. **Marge de fin rognée sur les groupes longs (mesuré, plafond gardé à 29 s)**. `LocalGroupingHostTest.continuousSpeechBench` :
   23 dictées de parole continue de 25 à 30 s (extraits rognés à 0,1 s de leur parole, mis bout à bout sans écart),
   1 490 mots, même VAD et même turbo ; groupes de 10 s plafonnés à 29 s puis à 20 s ; marge 1 000 puis « Automatique ».

   | Marge | Découpe | Décodages (le plus long) | WER | Fins « manquées » | Répétitions restées | Redécodés |
   | --- | --- | --- | --- | --- | --- | --- |
   | 1 000 | actuel (énoncés du VAD) | 53 (24,0 s) | 15,4 % | 8/23 | 0 | — |
   | 1 000 | groupes, plafond 29 s | 45 (27,7 s) | **13,2 %** | 6/23 | 0 | — |
   | 1 000 | groupes, plafond 20 s | 46 (20,0 s) | 13,5 % | 3/23 | 0 | — |
   | Automatique | actuel | 53 (24,0 s) | 15,2 % | 6/23 | 0 | 5 |
   | Automatique | groupes, plafond 29 s | 45 (27,7 s) | **14,5 %** | 6/23 | 0 | 1 |
   | Automatique | groupes, plafond 20 s | 46 (20,0 s) | 15,6 % | 4/23 | 0 | 1 |

   « Fin manquée » : un des 3 derniers mots de la référence absent des 6 derniers mots du texte. À la lecture des cas
   où les variantes diffèrent, l'indicateur compte surtout des mots mal reconnus en fin de dictée (« prostitution » →
   « pression », « Maikal » → « Mekhav ») : aucun texte ne disparaît en fin de groupe avec le plafond de 29 s. Aucune
   répétition ne reste, quelle que soit la variante. Le plafond de 20 s n'améliore pas le WER (+0,3 point à 1 000,
   +1,1 en « Automatique ») : **29 s est gardé**. Constat au passage : le VAD Silero rend déjà, aujourd'hui, des énoncés
   de 24 s (au-delà de ses 20 s nominales), qui ont la même marge rognée ; rien de nouveau dû au regroupement.
3. **Touche tapée pendant la dictée (limite connue, documentée)**. `DicteeImeService.stopDictationForTyping` appelle
   `controller.stop()`, qui est asynchrone : le caractère s'insère tout de suite, le groupe ouvert est décodé ensuite et
   son texte arrive après le caractère. Sans regroupement, seule la phrase en cours avait ce défaut. Non corrigé :
   retenir les touches jusqu'à la fin du décodage ferait paraître le clavier figé pendant plusieurs secondes, et
   toucherait le chemin du clavier par défaut. Écrit dans l'aide du réglage.
4. **Changement de champ** : `cancel()` jette le groupe ouvert (voulu : rien dans le mauvais champ), ce qui peut faire
   perdre un groupe entier de phrases finies, qui auraient été insérées sans regroupement. Écrit dans l'aide du réglage.
5. **Formulation** : « pas possible avec l'AAR 2.0.1 standard » au lieu d'« impossible », avec ce qui est démontré par
   l'exécution et ce qui repose sur la lecture du code ; titre de la PR corrigé.
6. **Tests** : `LocalGroupingInstrumentedTest` vérifie sur l'appareil, avec le vrai VAD Silero, qu'aucun échantillon
   n'est dans deux groupes et que l'audio de chaque énoncé est entièrement dans les groupes ; même contrôle sur l'hôte
   (`withTheRealVadNothingIsLostOrDecodedTwice`, extraits à 0,3 s d'écart, groupes jusqu'au plafond).
   `aLoneSentenceIsInsertedAfterTheIdleTime` attend le texte au lieu d'un `sleep` fixe, puis vérifie que seul le
   premier groupe a été décodé.

## Issues #36 (recentrée sur le téléphone) et #37 : le modèle choisi pour la personne, test rapide, mots simples (PR 2)

Empilée sur la PR de #55 et #57 (branche `claude/installation-local-d-abord-36b`, partie de
`claude/installation-local-d-abord`). Le serveur n'est plus une option du produit (décision du
2026-11-13 au soir) : de #36 il ne reste que « une personne sait en une minute quoi utiliser, sans
jargon » ; de #37, tout sauf le serveur préconfiguré.

### Le modèle choisi à la place de la personne

- **`TranscriptionAdvisor`** est réécrit pour le téléphone seul : `choice(langues)` donne le meilleur
  modèle (`best`, toujours Whisper large-v3-turbo, la recommandation de #22 et le défaut demandé par #37)
  et, quand il est possible, un modèle plus léger (`light`, Parakeet) : français de France, anglais,
  espagnol, portugais, sans le Québec. Québec, ou toute autre langue : le meilleur seul. Plus aucun mode,
  plus aucune raison « serveur ».
- **Choix tranché : le plus précis reste le défaut, même quand un modèle léger est possible.** Lecture
  possible de la consigne : recommander le léger d'emblée pour ces quatre langues (la table de #36a le
  faisait en mode téléphone). Écartée : #37 demande « large-v3-turbo sur le téléphone » par défaut, #22
  l'a retenu pour sa précision, et la table de #36a n'est pas mesurée. Le léger est proposé à côté
  (« Plus léger et plus rapide », 671 Mo) et c'est le test rapide, avec la voix de la personne, qui peut
  le recommander.
- L'étape « 5. Modèle » affiche donc une carte (Québec, autres langues) ou deux (quatre langues),
  chacune décrite par son effet, avec l'éditeur et la licence, mais sans nom de modèle ni description
  technique.

### Le test rapide : deux modèles du téléphone

- **`QuickTestAdvice` adaptée, pas dupliquée** : mêmes `ModeOutcome` et même budget, la règle devient
  « léger contre meilleur ». Le léger est recommandé seulement s'il fait au moins 90 %, à 5 points ou
  moins du meilleur, **et** si le meilleur a mis plus de 5 s ; sinon le meilleur reste (il est le défaut).
  Un seul modèle évalué : c'est lui. Aucun : pas de recommandation. **Seuils : estimations acceptées
  comme telles par l'utilisateur, rien n'est mesuré sur un téléphone.**
- **Budget** : lecture ≤ 25 s ; transcription ≤ 30 s **pour les deux modèles ensemble**, le léger
  d'abord (il est rapide et laisse le reste au meilleur). Un modèle qui dépasse ce qui reste est noté
  « trop lent » et le test se termine sans l'attendre (le moteur finit en arrière-plan, puis est libéré).
  Le chargement d'un modèle ne compte pas, comme le téléchargement : c'est de la préparation, pas la
  vitesse de dictée.
- **Un seul modèle a du sens** (Québec, ou une langue sans modèle léger) : le test donne simplement le
  score, et un bouton seulement si le modèle actif est un autre (un modèle expérimental choisi dans
  « Avancé »).
- **Modèle pas téléchargé** : noté « Pas encore téléchargé » avec « Télécharger (taille) pour
  comparer » ; le test se refait ensuite. L'enregistrement n'est pas gardé pour le rejouer : #36 veut
  qu'il soit effacé dès le score affiché, et une relecture coûte vingt secondes, hors de la minute comme
  le téléchargement.
- **Rien n'est gardé ni envoyé** : la lecture est en mémoire entre la fin de l'enregistrement et la fin du
  calcul, puis effacée (et à la fermeture de l'écran). Les modèles sont cherchés dans
  `ModelCatalog.all`, jamais par `byId` qui connaît aussi le serveur : même configuré et choisi, le
  serveur n'est jamais utilisé, et le test n'écrit jamais le mode de transcription (test instrumenté
  avec un faux serveur, et `ServerOnlyByChoiceTest`).
- **Où** : un bouton en contour sous les cartes de l'étape « Modèle » (jamais le bouton plein de #55),
  seulement pour les cinq langues qui ont un texte et une fois un modèle à tester téléchargé ; et dans
  les Réglages, section « Vos langues », pour le refaire.
- **Simplification retenue par rapport à #36** : pas d'écran « Où transcrire ? », pas de consentement,
  pas de case « téléphone seul » (il n'y a plus que le téléphone), pas de choix « garder l'autre » : le
  modèle reste changeable sur l'écran principal, et le résultat affiche les deux scores.

### #37 : mots simples, réglages techniques dans « Avancé »

- **Noms par effet** (`ModelPlainNames`) : « Plus précis » et « Plus léger et plus rapide » sur l'écran
  principal, dans le test rapide, le menu des modèles du clavier ; « Précis » et « Léger » sur le bouton
  du modèle du clavier ; « le modèle plus précis » dans les messages (chargement, modèle absent, avis de
  remplacement, suppression, notification de téléchargement). Les noms techniques restent dans
  « Avancé », la comparaison, le diagnostic et les tests hôtes (`shortName` ne change pas : il étiquette
  les mesures).
- **`PlainWordsTest`** refuse « Whisper », « Parakeet », « large-v3 », « turbo », « quantification »,
  « int8 », « Qwen », « Hugging Face », « SHA-256 » dans toutes les chaînes du parcours normal
  (`setup_`, `quick_`, avis de modèle, états du clavier) et dans les raisons de recommandation ; le test
  instrumenté de l'installation lit tout l'écran.
- **« Avancé » → « Modèles et performances »**, sous-section repliée, en un seul bloc après les réglages
  Whisper de #49 (non réorganisés) et avant le serveur : les noms des deux modèles, « Comparer les
  modèles » (retiré des « Outils » de l'écran principal), les modèles expérimentaux et la section
  « Performances » (modèle gardé en mémoire, threads, accélération), qui étaient au premier niveau.
- **Formulation de « open source »** : « Code ouvert : tout le monde peut vérifier ce que l'application
  fait de votre voix. Le code est public ; vos dictées, elles, restent privées. » En anglais : « Open
  code: anyone can check what the app does with your voice. The code is public; your dictations stay
  private. » « Code ouvert » plutôt que « logiciel libre » ou « open source » : le dépôt n'a pas de
  fichier de licence. « Conçu au Québec » devient « développé au Québec », les mots de #37.
- **Accueil** : vrai et simple (« Votre voix est transcrite sur votre téléphone : elle n'est envoyée à
  personne »). Avec un serveur configuré et choisi dans « Avancé », la variante de la PR 1 garde
  « développé au Québec » et « Code ouvert » mais pas « vos dictées restent privées », qui ne serait pas
  sûr avec un serveur tiers.
- **Fiche du Play Store** : proposée dans `docs/FICHE_PLAY_STORE.md` (français et anglais, rien publié).
- **Pas de traduction anglaise de l'application** : elle n'a que des écrans en français ; l'anglais est
  livré pour la fiche du Play Store.

### Revue indépendante de la PR (corrections)

Revue par un autre agent, relayée par l'orchestrateur ; chaque constat vérifié dans le code avant correction.

- **Plantage à la fermeture de l'écran** (bloquant, confirmé) : `onCleared()` arrêtait l'exécuteur, puis le
  fil de calcul y soumettait la libération après un dépassement : `RejectedExecutionException` non attrapée,
  qui tuait le processus, clavier compris. Plus d'exécuteur : chaque transcription a son propre fil, le fil de
  calcul est entièrement protégé (`try`/`finally`), et `benchmarkRunning` est rendu dans le `finally`.
  Test : fermer l'écran pendant le calcul, avec un modèle qui dépasse.
- **Dépassement du modèle léger** (bloquant, confirmé) : sherpa-onnx ne permet pas d'interrompre un décodage.
  Le décodage en retard gardait le verrou du moteur, et le chargement du meilleur modèle attendait sans
  limite. Désormais, après un dépassement, le test n'essaie plus aucun modèle (« Pas essayé : le modèle
  précédent calculait encore »), se termine tout de suite avec un texte honnête, et rend le clavier
  (`benchmarkRunning` faux) sans attendre. Il ne libère pas le moteur, qui pourrait déjà servir au clavier ; la
  libération automatique du clavier (modèle gardé en mémoire N minutes) s'en charge. Un nouveau test est
  refusé tant que ce décodage n'est pas fini (`QuickTestModel.lingering`, pour tout le processus), si bien que
  deux décodages ne se disputent jamais le moteur. `benchmarkRunning` n'est rendu que par le test qui l'a pris
  (jeton), donc un test ancien ne peut pas le remettre à faux au milieu d'un nouveau. Le cas « libération mise
  en file par un test précédent » ne peut plus se produire : il n'y a plus de libération différée.
- **Léger recommandé sans contrôle** (confirmé) : quand le meilleur modèle n'a pas de score, le léger n'est
  recommandé que s'il fait au moins 90 % ; sinon, pas de recommandation, et le plus précis reste.
- **Un texte par cas réel** : non téléchargé, trop lent, échec, pas essayé, pour chacun des deux modèles ; « les
  deux trop lents » ne dit plus d'en télécharger un.
- **Lecture interrompue** : quitter l'écran pendant la lecture coupe le micro et la lecture n'est pas notée
  (« Lecture interrompue… Recommencez quand vous voulez. ») ; une rotation ne coupe rien.
- **Noms techniques hors des chaînes** : une erreur de téléchargement affiche « Échec du téléchargement… » sur
  l'écran principal (détail dans le journal ; il reste visible dans « Avancé ») ; un échec de chargement ne
  recopie plus le texte brut de l'exception dans le clavier ; l'aide des « Résultats provisoires » dit « le
  modèle plus précis » ; le test de lecture québécois nomme les modèles par leur effet à l'écran (l'archive de
  contribution garde le nom technique, à côté de l'identifiant, pour qui améliore les modèles).
- **`PlainWordsTest` étendu** : aux chaînes `howto_`, `reading_`, `contrib_`, `msg_`, `cd_`, et aux écrans
  du parcours normal, où `displayName`, `shortName` et `description` ne doivent pas être formatés dans un
  texte. Exception provisoire, nommée dans le test : `HowToActivity` (« Recommandé pour vos langues :
  Whisper large-v3-turbo »), que la PR #63 retire ; l'exception est à supprimer dès que #63 est fusionnée ici.
- **Serveur configuré** : le résultat du test le dit (« Vos dictées passent par le serveur distant… ») pour
  que « Utiliser … » ne laisse pas croire que la voix change de chemin.

### Hors de cette PR, à noter

- « Comment dicter » affiche encore le nom du modèle actif (#56, hors de portée ici).
- Le test de lecture long (#15, lot 4) nomme les modèles dans son résultat : c'est un parcours facultatif
  de participation, laissé tel quel.
- Le nettoyage par petit LLM (Réglages, « Nettoyage du texte ») garde ses noms ; il est désactivé par
  défaut.
- Les textes de lecture espagnol et portugais n'ont pas été relus par une personne de langue maternelle
  (accepté par l'utilisateur).

### À vérifier par un humain (rien n'a tourné sur un vrai téléphone)

- Le test rapide sur un vrai téléphone, chronomètre en main : la minute tient-elle (lecture,
  transcription des deux modèles) ? Les délais réels du modèle plus précis pour 20 s de lecture
  (le seuil de 5 s en dépend).
- Une personne qui n'a jamais vu l'application comprend-elle « Plus précis » / « Plus léger et plus
  rapide » et la phrase de recommandation ?
- Le bouton du modèle du clavier (« Précis », « Léger ») reste lisible.
