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
