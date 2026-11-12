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

**Hébergement : bloquant, à la charge de l'utilisateur.** Je n'ai aucun compte pour publier et rien n'a été
publié. L'entrée `ModelCatalog.whisperQuebec` est complète (fichiers, SHA-256, tailles, éditeur, licence), mais
sa constante d'URL `WHISPER_QC` est vide : tant qu'elle l'est, `ModelCatalog.all` ne contient pas le modèle,
donc ni l'écran d'installation ni les Réglages ne le proposent (l'application ne propose jamais un
téléchargement qui échoue). Choix retenu entre « URL à renseigner » et « entrée non listée » : une seule constante,
un test qui vérifie la cohérence liste / URL. Les fichiers convertis sont dans
`/home/user/modele-qc-work/sortie/` (hors dépôt, 1,0 Go).

**Couverture de langues** (`ModelRecommendations.coverage`) : français (Québec et France) seulement ; la logique de
recommandation n'est pas modifiée (un test vérifie que la table ne le recommande jamais). Les mesures ne justifient
pas de le recommander à la place de Parakeet pour « Français (Québec) » : 8,5 % contre 6,8 %, et un coût cinq
fois plus élevé.

**CI.** `ModelAccuracyHostTest.whisperQuebec` est ignoré (`assumeTrue`) tant que le modèle n'est pas publié. Une
fois publié, le job `models` télécharge ~1 Go de plus et décode six extraits de plus (environ +2 minutes ; le
cache est indexé sur `ModelCatalog.kt`, donc il est de toute façon invalidé à chaque changement du catalogue).
Le workflow n'est pas modifié (changement soumis à l'approbation de Rémi) : le titre « 3 models » du job reste exact
tant que le modèle n'est pas publié.

**À valider par l'utilisateur** : redistribuer une conversion int8 d'un modèle MIT est permis par la licence ;
l'auteur est cité sur la carte (éditeur « ele-sage (communauté), d'après OpenAI », licence MIT). Le jeu
d'entraînement inclut les débats de l'Assemblée nationale et Common Voice (CC0) : rien ne l'interdit, mais je n'ai
pas vérifié leurs conditions d'utilisation pour un modèle dérivé ; à trancher avant de publier le dépôt de conversion.
