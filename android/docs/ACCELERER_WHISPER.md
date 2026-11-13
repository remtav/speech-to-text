# Accélérer Whisper large-v3-turbo sur le téléphone

Statut : enquête, 2026-11-12 (issue #15, demande de l'utilisateur : « investiguer s'il y a des solutions pour accélérer
l'exécution de Whisper v3 Turbo »). Le texte de l'enquête a été écrit sans modifier le code de l'application ; seul
ajout de code alors : le test de mesure hôte `app/src/test/java/io/github/remtav/dictee/WhisperSpeedHostTest.kt`
(sauté sans `-PmodelsDir`).

**Mise à jour 2026-11-12 : faits** (section 11). Pistes 1 et 2 du tableau : plus de texte provisoire avec Whisper (sans
réglage) ; réglage avancé « Silence ajouté pour Whisper » (`tailPaddings`, entier), **défaut inchangé à 1 000**, choix 600
et 300 à valider sur le téléphone. L'écran « Comparer les modèles » mesure maintenant Whisper avec les options de la dictée.

Contexte : sur le téléphone de l'utilisateur, Whisper turbo est le seul des trois modèles qui comprend bien certains
mots québécois dits seuls (« friperie »), mais il est trop lent pour un usage courant. Il veut le garder en dernier
recours et le rendre plus rapide.

Convention de lecture. **[vérifié]** : lu dans le code ou la documentation officiels (source citée), dans l'AAR
sherpa-onnx 2.0.1 (`javap`, `strings`) ou dans un fichier du modèle. **[mesuré hôte]** : mesuré ici sur x86-64, pas
sur un téléphone. **[supposé]** : raisonnement ou extrapolation, à confirmer.

## Résumé

Constat central : le calcul de Whisper turbo est dominé par l'**encodeur** (32 couches, largeur 1280), dont le coût
suit la longueur de l'entrée. Dans l'export sherpa-onnx, cette entrée n'est **pas** une fenêtre fixe de 30 s : c'est
l'audio du segment **plus 1 000 images de silence (10 s)** ajoutées par défaut. Un mot de 1 s paie donc l'encodeur
d'environ 11 s d'audio. Et pendant la parole, l'application relance tout cela toutes les 700 ms (texte provisoire).

| Rang | Piste | Gain attendu | Effort | Risque | Verdict |
| --- | --- | --- | --- | --- | --- |
| 1 | **Couper le texte provisoire pour Whisper** (section 2) | Retire ≈ 3 fois le calcul du décodage final pendant la parole [mesuré hôte] et l'attente du final derrière un provisoire en cours (0,05 à 5,9 s selon le moment, moyenne 1,5 s sur 6 essais hôte). Sur le téléphone, tout est multiplié par la lenteur du calcul [supposé]. | 1 ligne (`DicteeApp.kt`) | Faible : plus de texte souligné pendant la parole avec Whisper | **Faisable maintenant** |
| 2 | **Raccourcir la marge de fin (`tailPaddings`)** de 1 000 à ~600 images (section 1) | −36 % de calcul sur un mot de 1 s (1,33 s contre 2,09 s, 1 thread) et −29 % sur 5 s (2,92 s contre 4,14 s) [mesuré hôte]. 300 (valeur suggérée par sherpa) : −59 % et −44 %, mais des répétitions apparaissent. | Petit : un champ dans `EngineOptions` et `SherpaEngine` | **Moyen** : trop courte, la marge fait répéter ou tronquer la phrase (300 : WER 14,7 % contre 9,3 % ; 100 : 42,7 %) | **Faisable maintenant avec réserve** ; valider sur voix réelles et mots isolés avant de changer le défaut |
| 3 | **Réglages à mesurer sur le téléphone** : threads (3 à 5 sur un Snapdragon 8 Gen 2), XNNPACK, NNAPI (sections 4 et 5) | Threads : inconnu, peut être grand ou nul. XNNPACK/NNAPI : probablement nul ou négatif (le modèle int8 utilise des opérateurs que ces fournisseurs ne prennent pas) [supposé à partir de sources vérifiées]. | Aucun code : tout est déjà dans les Réglages et « Comparer les modèles » | Faible (repli CPU automatique) | **À mesurer sur téléphone** (procédure plus bas) |
| 4 | **NPU Qualcomm par QNN** (section 5) | Potentiellement ×5 à ×10 sur l'encodeur (Qualcomm annonce 0,25 à 0,84 s pour une fenêtre de 30 s sur ses puces) | Très grand : build sherpa-onnx spécial, SDK propriétaire, binaires par puce | Élevé | **À écarter pour l'instant** ; étude séparée si l'usage de Whisper devient courant |

À écarter : un modèle plus petit (Whisper small : 1,1 à 1,3 fois plus rapide sur l'hôte, mais WER 16 % contre 9,3 % ; les
distillations gardent l'encodeur de large-v3, donc ne vont pas plus vite sur des segments courts), un autre moteur
(whisper.cpp, CTranslate2). **Déjà en place** : langue forcée quand une seule langue est choisie (gain de vitesse
négligeable, mais évite une mauvaise détection de la langue sur un mot isolé), cache KV du décodeur, décodage glouton.

Ce qui est inchangé par le modèle québécois en cours d'évaluation (autre enquête) : le modèle communautaire
`ele-sage/whisper-large-v3-turbo-fr-quebecois` a, selon l'outil de conversion du dépôt, **la même architecture** que
Whisper turbo (`android/tools/whisper-hf-vers-sherpa/README.md`) : mêmes tailles de fichiers, donc **même vitesse**.
Les leviers de ce rapport s'appliqueraient tels quels à ce modèle ; une meilleure compréhension du français québécois
ne rend pas le calcul plus rapide, et inversement.

## Comment lire les mesures hôte

- Machine : Intel Xeon @ 2,10 GHz, **4 cœurs** (`nproc`), Linux x86-64, sherpa-onnx 2.0.1 (même version que l'AAR),
  modèles int8 du catalogue. **Ce n'est pas un téléphone arm64** : les chiffres montrent des tendances (coût fixe, effet
  de la marge de fin, effet des threads), pas la latence réelle.
- **La machine était partagée avec d'autres tâches** pendant toute l'enquête (charge moyenne de 2 à 15 sur 4 cœurs, colonne
  « charge » des tableaux). Le temps écoulé varie du simple au quadruple d'une exécution à l'autre pour le même calcul
  (même clip de 5 s : 1,4 s à 5,7 s). Règles suivies : minimum de 2 mesures après un échauffement ; **temps CPU du
  processus** noté à côté (lu dans `/proc/self/stat`), qui ne dépend presque pas de la charge ; comparaisons de fond faites
  **sur 1 thread**, où le temps CPU est le calcul pur (aucune attente active entre threads).
- Trois passages complets ont été faits (charge forte, mixte, faible). Les tableaux montrent le passage le plus calme
  pour chaque mesure, avec sa charge ; les autres passages sont cités quand ils éclairent la variabilité.
- Extraits : ceux de `assets/reference` (français, Common Voice accent « canada »). Le « mot isolé de 1 s » est une
  **coupe de 1 s de parole** (« Du reste, sa mère… », 2 à 3 mots) : aucun enregistrement de « friperie » n'existe ici.
  5 s = `frqc1` (5,02 s) ; 15 s = `frqc1` + `frqc2` + `frqc3` coupés à 15 s.
- Le microbanc `python`/onnxruntime fait en complément (encodeur seul) a tourné à charge forte : cité seulement pour la
  forme de la courbe.

## 1. Coût fixe par segment

**Question** : Whisper traite-t-il une fenêtre fixe de 30 s quel que soit le segment ? Un mot isolé paie-t-il 30 s
d'encodeur ?

**Réponse : non, mais il paie 10 s de silence de plus que sa durée.**

Preuves [vérifié] :

- `offline-recognizer-whisper-impl.h` (sherpa-onnx v2.0.1, `DecodeStream`) : `tail_padding_frames = 1000`
  (« note that 1000 is an experience-value »), remplacé par `whisper.tail_paddings` s'il est > 0 ;
  `actual_frames = min(num_frames + tail_padding_frames, 3000)` ; le tenseur d'entrée est construit à cette longueur
  (`shape{1, actual_frames, feat_dim}`), complété de zéros. Un audio de 29,5 s ou plus est tronqué (message « Only waves
  less than 30 seconds are supported »). Le commentaire du code dit : « Since we have removed the 30 seconds constraint,
  we need tail_padding_frames so that whisper is able to detect the eot token. »
- L'encodeur du modèle accepte une longueur variable : entrée `mel [n_audio, 128, T]` (lue avec onnxruntime sur
  `turbo-encoder.int8.onnx`), sortie `n_layer_cross_k [4, n_audio, T, 1280]`.
- Le champ existe dans l'AAR 2.0.1 : `OfflineWhisperModelConfig` a `tailPaddings: Int` (`javap`), et la bibliothèque
  contient la chaîne `tail_paddings=` et le message d'erreur qui recommande « a larger --whisper-tail-paddings ».
- Aide officielle de l'option (`offline-whisper-model-config.cc`) : « Suggested value: 50 for English models. 300 for
  multilingual models. […] Leave it to -1 to use 1000. »
- `SherpaEngine.buildConfig` ne renseigne pas `tailPaddings` : l'application est donc à 1 000.
- Le décodeur est limité à 6 jetons par seconde d'audio (`num_feature_frames / 100 * 6`, plafonné à `n_text_ctx / 2` = 224)
  [vérifié, `offline-whisper-greedy-search-decoder.cc`] : un mot de 1 s ne peut produire que 6 jetons.

Mesures [mesuré hôte], 1 thread (temps CPU = calcul pur, charge de la machine 1,3 à 2,1), langue forcée `fr`,
secondes :

| Images d'entrée de l'encodeur (marge) | Mot ~1 s | Segment 5 s |
| --- | --- | --- |
| 3 000 (fenêtre fixe de 30 s, `tailPaddings` ≥ 2 900) | **8,40** | **9,57** |
| 1 098 / 1 498 (**défaut, 1 000**) | **2,09** | **4,14** |
| 698 / 1 098 (600) | 1,33 | 2,92 |
| 398 / 798 (300) | 0,86 | 2,33 |
| 198 / 598 (100) | 0,64 | 1,96 |

Lecture : (a) une fenêtre fixe de 30 s coûterait 4 fois plus qu'aujourd'hui pour un mot isolé : l'export sherpa-onnx
évite déjà le pire ; (b) il reste 10 s de zéros ajoutés : sur un mot de 1 s, **60 à 70 % du calcul** (2,09 s de CPU
contre 0,86 s avec une marge de 300 et 0,64 s avec 100) portent sur du silence ; (c) la courbe n'est pas une droite : environ 1,6 ms par image jusqu'à
~1 000 images, puis 3,3 ms par image au-delà (l'attention croît avec le carré de la longueur) ; il reste ~0,3 s
indépendantes de la marge (décodeur, spectrogramme).

Conséquence sur le découpage [supposé, d'après la courbe] : chaque segment paie la marge. Une dictée de 20 s coupée en
deux segments de 10 s (pause de 0,5 s) coûte ~4 000 images d'encodeur au lieu de ~3 000 pour un segment unique. Allonger
la pause de fin de segment (`VadParams.minSilenceSeconds`, 0,5 s) réduirait le nombre de segments mais retarderait chaque
résultat : voir la section 9.

Qualité selon la marge [mesuré hôte, 4 threads, `ModelAccuracyHostTest`-like, 9 clips : 3 anglais/français de studio,
6 Common Voice « canada » ; mots de référence : 141 en détection automatique, 75 sur les 7 clips français en langue
forcée] :

| Marge | WER auto (9 clips) | WER `fr` forcé (7 clips) | Constat |
| --- | --- | --- | --- |
| 1 000 (défaut) | 7,1 % (10/141) | 9,3 % (7/75) | Sorties propres |
| 600 | 5,7 % (8/141) | 6,7 % (5/75) | Pas de dégradation nette ; différences sur 1 à 2 mots, dans le bruit |
| 300 (suggérée par sherpa) | 9,9 % (14/141) | 14,7 % (11/75) | **Répétition** de la phrase sur `frqc5` (« La sorcière décide… La sorcière décide… ») ; sur le mot de 1 s : « Du reste sa mère. Du » |
| 100 | 23,4 % (33/141) | 42,7 % (32/75) | Phrases répétées (`frqc2`, `frqc5`, `frqc6`, `fr1`) : le marqueur de fin n'est pas détecté et le décodeur continue |

Neuf clips ne départagent pas 1 000 et 600 (une différence d'un mot ferait varier le WER de 0,7 à 1,3 point). Ce qui est
net : à 300 et en dessous, la marge trop courte provoque des **répétitions**, le comportement que le message d'erreur
de sherpa décrit (« eot » non détecté). Aucun mot isolé réel (« friperie ») n'a pu être testé ici.

**Verdict : faisable maintenant avec réserve.** Un réglage `tailPaddings` à ~600 gagne ~1/3 du calcul d'un segment
court. À valider avec la procédure téléphone (enregistrer « friperie » seul, une phrase, 10 s) avant d'en faire le
défaut. Un filtre qui retire une phrase répétée (texte = X + espace + X) serait un filet de sécurité peu coûteux
[supposé utile à 300, inutile à 600].

Ce que ce réglage ne fait pas : il ne touche pas aux autres modèles (Parakeet et Qwen3 n'ont pas de marge).

## 2. Décodages provisoires

**Ce que fait le code** [vérifié, `DictationController.kt`] : pendant la parole, si aucun final n'attend, si aucun
provisoire n'est en cours et si 700 ms se sont écoulées depuis le dernier, le contrôleur met en file un décodage de **tout
l'audio accumulé depuis le début du segment** (au moins 0,8 s). Un thread unique exécute tous les décodages, finals comme
provisoires. Un provisoire déjà lancé **ne peut pas être interrompu** : quand la pause arrive, le final attend la fin du
provisoire en cours (`enqueueFinal` retire seulement les provisoires encore en file). `partials` vaut `true` par défaut
(`AppSettings.partials`) et `DicteeApp.kt:145` ne l'éteint que pour le serveur distant.

Simulation [mesuré hôte, horloge virtuelle avec **vrais** décodages, mêmes règles que le contrôleur ; segment de 5 s, pause
de fin de 0,5 s ; 4 threads]. `final seul` : décodage du segment sans provisoires. « Texte après la fin de la parole » :
temps entre la fin des 5 s et l'arrivée du texte final.

| Passage (charge) | Modèle | Provisoires | Calcul des provisoires | Final seul | Attente du final derrière un provisoire | Texte après la parole, avec / sans provisoires |
| --- | --- | --- | --- | --- | --- | --- |
| calme (≈ 5) | Whisper turbo | 5 | 4,63 s | 1,66 s | 0,12 s | 2,02 s / 2,16 s |
| calme (≈ 5) | Whisper turbo, marge 300 | 6 | 3,85 s | 0,95 s | 0,05 s | 1,47 s / 1,45 s |
| calme (≈ 5) | Parakeet | 6 | 1,51 s | 0,45 s | 0 | 0,88 s / 0,95 s |
| calme (≈ 5) | Qwen3-ASR | 5 | 3,68 s | 1,23 s | 0 | 1,92 s / 1,73 s |
| mixte (≈ 5 à 9) | Whisper turbo | 5 | 4,88 s | 1,59 s | 0,31 s | 2,23 s / 2,09 s |
| mixte | Whisper turbo, marge 300 | 5 | 6,05 s | 1,00 s | 2,12 s | 6,20 s / 1,50 s |
| chargée (≈ 12) | Whisper turbo | 2 | 10,60 s | 7,16 s | 5,90 s | 15,41 s / 7,65 s |
| chargée | Whisper turbo, marge 300 | 2 | 5,28 s | 6,75 s | 0,58 s | 7,69 s / 7,25 s |
| chargée | Qwen3-ASR | 2 | 10,15 s | 7,20 s | 5,45 s | 12,30 s / 7,70 s |

Lecture :

- **Le calcul** : pour 5 s de parole, les provisoires consomment 2,8 fois le calcul du décodage final de Whisper (4,63 s
  contre 1,66 s), contre 3,4 fois pour Parakeet. Dès qu'un décodage dure plus de 700 ms, le thread de décodage est
  **occupé en continu** pendant la parole : processeur chaud, batterie.
- **Le retard** : l'attente du final dépend de l'endroit où tombe la pause dans le provisoire en cours : de 0,05 s à
  5,9 s sur ces 6 observations Whisper (moyenne 1,5 s). Elle vaut en moyenne la moitié et au plus la durée d'un
  provisoire. Sur l'hôte rapide et calme, l'effet est petit ; il grandit en proportion de la durée d'un décodage, donc
  sur le téléphone [supposé]. Sous forte charge de l'hôte (qui imite un calcul lent), le texte final arrive **deux fois
  plus tard** avec provisoires (15,4 s contre 7,7 s).
- **Couper le provisoire pour Whisper seulement** supprime les deux effets pour le modèle lent, et laisse aux deux autres
  leur texte provisoire. Le texte provisoire de Whisper est aussi le moins fiable (« Du reste sa mère. Du » sur un mot de 1 s).
  Espacer plutôt que couper (par exemple 3 s) réduirait le calcul mais pas l'attente maximale, parce qu'un provisoire
  lancé tard dans une pause reste non interruptible.

**Verdict : faisable maintenant.** Gain le plus sûr et le plus facile. Alternative qui garde le texte provisoire : rendre
le provisoire interruptible, ce qui demande un changement dans sherpa-onnx (non offert par l'API 2.0.1 : un seul `decode`
bloquant) [vérifié par `javap` : `OfflineRecognizer.decode(OfflineStream)`, pas d'annulation].

## 3. Langue forcée

**Question** : la langue forcée évite-t-elle une passe de détection ?

Oui, une seule petite passe [vérifié, `offline-whisper-greedy-search-decoder.cc` et `offline-whisper-model.cc`] : si
`language` est vide, le décodeur appelle `DetectLanguage`, qui exécute **une étape du décodeur** (jeton de début, cache
vide) sur la sortie de l'encodeur et prend le maximum des logits de langues ; sinon il écrit directement l'identifiant de
langue dans les jetons initiaux. L'encodeur, lui, tourne dans les deux cas.

Mesure [mesuré hôte, 4 threads, charge ≈ 2,5] : mot de 1 s, 682 ms forcé contre 726 ms automatique ; 5 s, 1 422 ms contre
1 434 ms. Gain 0 à 6 % : **négligeable pour la vitesse**.

Intérêt réel : la qualité. Sur un mot isolé, la détection automatique peut se tromper de langue ; la langue forcée l'évite
[supposé, non mesuré : aucun enregistrement de mot isolé ici]. `AppSettings.engineOptions` la transmet déjà quand un seul
code est choisi (Québec + France = `fr`). **Attention** [vérifié, `OfflineWhisperGreedySearchDecoder::Decode`] : un code
absent de la table de Whisper fait appeler `SHERPA_ONNX_EXIT(-1)` (arrêt du processus) ; `DictationLanguages.whisperLanguage`
ne doit transmettre que des codes valides (déjà le cas pour `fr`, `en`, `es`, etc.).

**Verdict : déjà en place** ; vérifier dans les Réglages que les langues choisies se résument à un seul code
(sinon Whisper détecte, pour une perte de vitesse de quelques pour cent et un risque de mauvaise langue).

## 4. Threads et cœurs

**État actuel** [vérifié] : `EngineOptions.defaultThreads() = availableProcessors().coerceIn(1, 4)`, réglable de 1 à 8
(`AppSettings.threads`). sherpa-onnx règle `intra_op` **et** `inter_op` sur ce nombre (`session.cc` ligne 220 à 222) et
**n'expose ni l'affinité aux cœurs ni l'attente active** : on ne peut pas épingler les threads sur les gros cœurs.

Mesures hôte [mesuré hôte, langue forcée, charge ≈ 2,1 à 2,7] :

| Threads | Mot ~1 s : écoulé / CPU | 5 s : écoulé / CPU |
| --- | --- | --- |
| 1 | 2 071 ms / 2 060 ms | 4 114 ms / 4 110 ms |
| 2 | 1 242 ms / 2 510 ms | 2 386 ms / 4 680 ms |
| 4 | 682 ms / 2 720 ms | 1 422 ms / 5 370 ms |

Sur 4 cœurs identiques, le gain est presque linéaire (×3,0 de 1 à 4 threads), au prix de 30 % de CPU en plus (attente
active entre threads). **Ce résultat ne se transpose pas** à un Snapdragon 8 Gen 2 : ce processeur est asymétrique
(1 cœur « prime » Cortex-X3, 4 cœurs moyens, 3 cœurs économiques, selon la fiche publique de la puce : non vérifié ici) ;
`availableProcessors()` rend 8, la valeur par défaut retient 4 sans savoir quels cœurs le système donnera aux threads. Un
thread placé sur un cœur économique ralentit tout le groupe (tous les threads attendent le plus lent à chaque couche)
[supposé, comportement connu des pools de threads synchrones].

**Verdict : à mesurer sur téléphone**, sans changement de code : essayer 2, 3, 4, 5 et 6 threads dans les Réglages (curseur)
avec l'écran « Comparer les modèles ». Si l'optimum n'est pas 4, changer `defaultThreads()` (ou le nombre de gros cœurs
lu dans `/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq`) pour cet appareil, plus un réglage propre à Whisper
si besoin.

## 5. Accélération matérielle

**Ce que l'application offre** [vérifié] : `Acceleration` = CPU (défaut), XNNPACK, NNAPI ; `SherpaEngine` passe le nom
de fournisseur à `OfflineModelConfig.provider` ; `EngineManager` reconstruit sur le CPU si la création échoue.

**Ce que l'AAR 2.0.1 embarque** [vérifié, `strings` sur `jni/arm64-v8a/libonnxruntime.so`] : onnxruntime **1.28.2** avec les
fournisseurs `XnnpackExecutionProvider` et NNAPI (chaînes `nnapi_builtin/builders/…`), des noyaux entiers arm64
(`neon_dotprod`, `neon_i8mm` de KleidiAI). Les chaînes `QNNExecutionProvider` et `VSINPUExecutionProvider` sont des
références du code de onnxruntime ; la **bibliothèque QNN de Qualcomm n'est pas dans l'AAR** (`jni/arm64-v8a` : seulement
`libonnxruntime.so`, `libsherpa-onnx-*`). `sherpa-onnx` active `NNAPI` avec **`nnapi_flags = 0`** (`session.cc`) : le repli
sur l'implémentation CPU de référence de NNAPI n'est pas interdit ; `XNNPACK` s'ajoute avec ses réglages par défaut.

**Compatibilité avec le modèle int8** [vérifié] : les fichiers `turbo-encoder.int8.onnx` et `turbo-decoder.int8.onnx` sont
quantifiés **dynamiquement** : le parcours des chaînes du fichier trouve les opérateurs `MatMulInteger` (200 occurrences
dans l'encodeur, 32 dans le décodeur) et `DynamicQuantizeLinear` (129 et 24), plus des `MatMul` flottants (64 et 17)
(décompte approximatif par `strings`, pas par analyse du graphe) ; les métadonnées portent `onnx.infer: onnxruntime.quant`.
Or :

- la documentation de onnxruntime pour XNNPACK ne liste, parmi les opérateurs quantifiés, que `QLinearConv` et des
  `QLinear*` voisins ; `MatMul` et `Gemm` y sont limités à 2D (les matrices de l'attention sont en 3D ou 4D) ;
- celle de NNAPI liste `MatMul`, `QLinearMatMul` (avec facteurs constants), `Gemm`, `Softmax`, mais **ni `MatMulInteger` ni
  `DynamicQuantizeLinear`** ; elle avertit que le repli NNAPI sur le CPU est souvent plus lent que les noyaux de
  onnxruntime et propose `NNAPI_FLAG_CPU_DISABLED`.

Conséquence [supposé, à partir de ces faits] : avec XNNPACK, presque rien du graphe n'est pris en charge, donc pas de gain ;
avec NNAPI, le graphe est fragmenté en petits morceaux flottants séparés par des opérateurs restés sur le CPU, avec des
copies à chaque frontière : **plus lent que le CPU**, ou égal si NNAPI refuse tout. Le CPU d'onnxruntime utilise déjà
des noyaux entiers arm64 pour `MatMulInteger` [vérifié : chaînes des noyaux ; supposé : sélection effective sur ce
téléphone].

**Retours d'expérience documentés** : aucune source trouvée qui donne un gain NNAPI ou XNNPACK pour Whisper int8 sur
Android avec sherpa-onnx. Cas voisins [vérifié, pages citées plus bas] : un TFLite passe de 584 ms (CPU) à 7 168 ms par
NNAPI sur une carte Qualcomm (forum Qualcomm RB5) ; Qualcomm publie en revanche Whisper large-v3-turbo prêt pour son NPU.

**NPU Qualcomm (QNN)** [vérifié] :

- Qualcomm AI Hub publie `Whisper-Large-V3-Turbo` (précision flottante, formats « QNN context binary » et ONNX précompilé
  QNN) ; fiche : entrée 128 × 3 000 (**fenêtre fixe de 30 s**), décodeur à 4 couches, 200 jetons au plus ; latence annoncée
  de 252 à 842 ms pour l'encodeur et de 5 à 12 ms par jeton pour le décodeur selon la puce ; puces listées : 8 Gen 1, 8 Gen 3,
  8 Elite et d'autres (**le 8 Gen 2 du S23 Ultra n'apparaît pas sur cette fiche**, mais un résultat de recherche l'associe au
  AI Hub : non confirmé).
- sherpa-onnx a une exécution QNN (`SHERPA_ONNX_ENABLE_QNN=ON`, SoC SM8350 ou plus récent, SDK QNN, variable
  `ADSP_LIBRARY_PATH`) et la bibliothèque 2.0.1 contient des messages pour Whisper sur QNN (binaires de contexte
  encodeur et décodeur) ; la page « Models for QNN » de sherpa-onnx ne liste que des modèles chinois, SenseVoice et
  Paraformer, **pas Whisper**.
- Coût : build propre de sherpa-onnx lié au SDK QNN (bibliothèques non redistribuables par le dépôt), binaires compilés
  **par puce**, fenêtre fixe de 30 s (donc coût indépendant de la durée du segment, ce qui avantage les mots isolés),
  poids flottants (taille bien supérieure à 1 Go) [supposé pour la taille]. Gain potentiel élevé, effort et risque très
  élevés. Déjà écarté pour les trois modèles dans `DECISIONS.md` (« Lot 3 »).

**Verdict : à mesurer sur téléphone** pour XNNPACK et NNAPI (zéro code, deux minutes par essai ; s'attendre à rien ou
pire) ; **à écarter** pour QNN dans cette itération.

## 6. Quantification et export

| Point | État | Source |
| --- | --- | --- |
| Quantification actuelle | int8 dynamique (`MatMulInteger`), encodeur 674,7 Mo et décodeur 361,1 Mo | fichiers du modèle [vérifié] |
| Variantes publiques de `csukuangfj/sherpa-onnx-whisper-turbo` | `turbo-decoder.int8.onnx`, `turbo-decoder.onnx` (flottant 32 bits, 636 Mo), `turbo-encoder.int8.onnx`, `turbo-encoder.onnx` (graphe de 0,7 Mo, poids à part) ; **aucun fp16** | API Hugging Face [vérifié] |
| Cache KV du décodeur | **Déjà présent** : entrées `in_n_layer_self_k_cache`/`v_cache` de forme `[4, n, 448, 1280]` et `offset` ; le décodeur ne traite qu'un jeton par étape après l'invite | `onnxruntime`, `offline-whisper-greedy-search-decoder.cc` [vérifié] |
| Décodage | **Glouton seulement** : « Only greedy_search is supported at present for whisper » (message de l'AAR) ; `SherpaEngine` demande déjà `greedy_search` | AAR, code [vérifié] |
| Longueur maximale de sortie | 6 jetons par seconde d'audio, plafond 224 | code [vérifié] |
| Étapes du décodeur par segment | Une invite de 4 jetons (ou 1 pour détecter la langue) puis un appel par jeton. Sur l'hôte, le décodeur coûte quelques dizaines à ~200 ms par jeton à charge forte ; l'encodeur domine | microbanc [mesuré hôte, charge forte] |

Pistes d'export, toutes **à écarter pour l'instant** :

- **fp32** : plus gros (encodeur de plus de 2 Go) et, sur CPU arm64, en général plus lent que les noyaux entiers
  [supposé].
- **fp16** : aucun export public ; le CPU arm64 d'onnxruntime n'a pas de chemin fp16 général pour ces opérateurs [supposé].
  Un export maison n'a de sens que pour un NPU.
- **int8 statique (QDQ)** : ouvrirait `QLinearMatMul` à NNAPI/XNNPACK (si les facteurs sont constants), mais demande un jeu
  de calibration, un export maison et une vérification de la qualité ; gain non démontré [supposé].
- **Encodeur plus court par construction** (fenêtre de 10 s au lieu de 30 s, `audio_ctx` de whisper.cpp) : sherpa-onnx fait
  déjà l'équivalent par la longueur variable ; le levier restant est `tailPaddings` (section 1).

## 7. Modèles plus légers de la même famille

Constat d'architecture [vérifié, fiches Hugging Face et README OpenAI] : **large-v3-turbo = encodeur de large-v3 (32
couches) + décodeur réduit de 32 à 4 couches**. Les distillations (distil-large-v3, distil-large-v3-fr) **gardent
l'encodeur de large-v3 gelé** et réduisent le décodeur à 2 couches (ou 16, 8, 4 pour les variantes françaises de
bofenghuang). Comme l'encodeur domine le calcul d'un segment court (section 1), **aucune distillation de large-v3 n'est
sensiblement plus rapide que le turbo sur des mots ou des phrases courtes** : elles gagnent sur de longs textes générés.

| Modèle | Existence (URL) | Taille int8 | Vitesse relative documentée | Qualité en français | Verdict |
| --- | --- | --- | --- | --- | --- |
| Whisper small | `csukuangfj/sherpa-onnx-whisper-small` (https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small) | 112,4 + 262,2 Mo = **375 Mo** | README OpenAI : ~4× (GPU, anglais) contre ~8× pour turbo ; **mesuré hôte : 1,3× plus rapide que turbo sur 1 s, 1,1× sur 5 s** (4 threads, langue forcée) | **Mesuré hôte** : WER 16,0 % (12/75) contre 9,3 % pour turbo sur les 7 clips français ; sur le mot de 1 s : « du reste à main-ra » | **À écarter** : gain faible, qualité nettement moindre ; ne règle pas « friperie » |
| Whisper medium | `csukuangfj/sherpa-onnx-whisper-medium` | 374,2 + 571,1 Mo = 945 Mo | README OpenAI : ~2× (turbo ~8×) ; **mesuré hôte (2026-11-13) : 4,5× plus lent que turbo** (38,2 s contre 8,6 s sur les 6 clips québécois, 4 threads) | **Mesuré hôte** : WER 12,1 % (7/58) contre 10,3 % pour turbo et 8,6 % pour le turbo québécois | **À écarter** : plus lent et pas meilleur (section 7.1) |
| Whisper tiny / base | `csukuangfj/sherpa-onnx-whisper-tiny`, `-base` | 103 Mo, 160 Mo | ~10×, ~7× | non mesurée ; qualité en dessous de small | **À écarter** |
| distil-large-v3 et v3.5 | `csukuangfj/sherpa-onnx-whisper-distil-large-v3` (et `-v3.5`) | 668,1 + 314,9 Mo | « 6,3× plus rapide que large-v3 » (fiche, GPU) | **anglais seulement** (« English series ») | **À écarter** : pas de français |
| distil-large-v3-fr | `eustlb/distil-large-v3-fr` (https://huggingface.co/eustlb/distil-large-v3-fr) | non convertie ; transformers (F16) et GGML | « 5,9× plus rapide que large-v3 » (A100, 128 jetons) ; **pas de comparaison avec turbo** ; encodeur copié de large-v3, décodeur à 2 couches | Fiche : WER Common Voice 17 : 12,7 % contre 11,0 % pour large-v3 ; Fleurs : 8,0 % contre 5,6 % (le turbo n'est pas comparé) | **À écarter pour la vitesse** (même encodeur) ; piste de qualité si une conversion sherpa existe |
| Distillation française, décodeur à 16 couches | `pantinor/sherpa-onnx-whisper-large-v3-french-distil` (https://huggingface.co/pantinor/sherpa-onnx-whisper-large-v3-french-distil), export sherpa-onnx v2.0.1 | 714,1 + 639,8 Mo = **1,35 Go** | README de l'auteur : RTF 0,94 sous charge contre 0,43 pour turbo au repos (**comparaison non équitable**, dit l'auteur lui-même) | README de l'auteur : WER Fleurs fr 6,54 % contre 8,59 % pour turbo int8 (40 clips, auto-déclaré) | **Plus lent** (décodeur 4× plus profond) ; piste de qualité seulement ; licence ambiguë (Apache-2.0 en en-tête, MIT dans le texte) |
| Turbo, autres quantifications | `ggerganov/whisper.cpp` : `ggml-large-v3-turbo-q5_0.bin` 574 Mo, `-q8_0` 874 Mo | — | Pour whisper.cpp, pas pour sherpa-onnx | — | Voir section 8 |

### 7.1 Whisper medium mesuré (2026-11-13)

Demande de l'utilisateur : essayer un Whisper « un niveau sous large-v3-turbo ». Par le nom, c'est medium (769 M
paramètres). Mesure hôte [mesuré hôte] avec sherpa-onnx 2.0.1 en Python (même version que l'AAR), modèles int8, 4 threads,
langue forcée, `tailPaddings` = 1 000 (défaut de l'app), meilleur de 3 passages par clip, machine au repos (charge 0,1).
Script : `android/tools/whisper-mesure-python/bench.py` (`python bench.py DOSSIER_MODELES app/src/main/assets/reference 4`,
avec les sous-dossiers `turbo/`, `qc/` et `medium/`).

| Modèle | 6 clips québécois (31,1 s d'audio) : temps / WER | 2 clips anglais (23,3 s) : temps / WER | fr1 (6,0 s) : temps / WER |
| --- | --- | --- | --- |
| Whisper large-v3-turbo | 8,6 s / 10,3 % (6/58) | 5,9 s / 4,5 % | 1,8 s / 6,7 % |
| Whisper turbo québécois (ele-sage) | 9,2 s / **8,6 %** (5/58) | — (français seulement) | 1,8 s / 0 % |
| Whisper medium | **38,2 s** / 12,1 % (7/58) | 37,1 s / 0 % | 11,3 s / 6,7 % |

Pourquoi medium est plus lent alors qu'il est plus petit [mesuré hôte, métadonnées des fichiers ONNX] :

- **L'encodeur est bien plus rapide** : 0,51 s contre 0,84 s pour 1 500 images (24 couches de largeur 1 024 contre 32 de
  1 280).
- **Le décodeur annule ce gain, et au-delà** : turbo n'a que 4 couches de décodeur, medium en a 24. Dans l'export
  sherpa-onnx, le cache du décodeur a une taille fixe (couches × 448 positions × largeur) et il est passé en entrée et
  rendu en sortie **à chaque jeton** : ≈ 88 Mo par jeton pour medium contre ≈ 18 Mo pour turbo. Sur un téléphone, où la
  bande passante mémoire est plus faible, l'écart devrait être au moins aussi grand [supposé].
- C'est précisément ce que « turbo » a changé : turbo **est** déjà le Whisper allégé (décodeur réduit de 32 à 4
  couches). Les tailles inférieures (medium, small) ont un décodeur plus profond que turbo.

Conclusion : **medium est écarté** (plus lent et pas meilleur en français québécois) et n'est pas ajouté au catalogue.
Small reste écarté (tableau ci-dessus : 1,1 à 1,3× plus rapide, WER 16 %). Pour réduire la latence, les leviers restent
la marge de fin (section 1), les threads (section 4) et surtout **le serveur distant** (issue #23). Le modèle à garder en
local pour le français québécois est le turbo québécois.

Autres tailles de cette famille à ne pas oublier : aucun export sherpa-onnx « turbo » plus petit que l'int8 actuel
n'existe publiquement [vérifié par la liste des dépôts `csukuangfj/sherpa-onnx-whisper-*`].

**Lien avec l'autre enquête** : la recherche d'un modèle communautaire affiné en français québécois (WER annoncé 6,65 %,
base turbo) est indépendante de celle-ci. S'il remplace Whisper turbo dans le catalogue, il a **la même vitesse** (même
architecture) : tous les leviers de ce rapport lui sont applicables, et la procédure de mesure ci-dessous est à refaire à
l'identique avec lui. Si l'on choisit un modèle plus petit pour la vitesse, on perd ce qui motive l'usage de Whisper
(les mots québécois isolés) : le compromis vitesse/qualité est réel et se joue entre les deux enquêtes.

### 7.2 Lite-Whisper large-v3-turbo-acc : quantification de l'encodeur (2026-11-13, issue #25)

[`efficient-speech/lite-whisper-large-v3-turbo-acc`](https://huggingface.co/efficient-speech/lite-whisper-large-v3-turbo-acc)
(Apache-2.0, arXiv 2502.20583) : large-v3-turbo dont 139 couches linéaires de l'encodeur sont factorisées
(`x @ W1 @ W2 + b`, rang 16 à 1 024), encodeur de 635 M à 421 M paramètres, décodeur inchangé. Objectif de l'issue : un
encodeur int8 **< 675 Mo** et **plus rapide** que turbo int8 sur les 7 clips, avec au plus 1 phrase sur 7 différente de
turbo et un WER ≤ turbo + 2 points sur le jeu de 172 extraits québécois (#24).

**Conclusion : aucune quantification CPU satisfaisante.** La version expérimentale publiée (encodeur de 799 Mo, `W2` en
fp32) reste telle quelle ; rien n'est republié. La meilleure variante trouvée (rotation, ci-dessous) est correcte,
plus petite (461 à 530 Mo) et à peine plus rapide que turbo (−2 % sur 172 extraits), mais son WER est de 19,5 % contre
14,5 % pour turbo. **Même sans quantification, Lite fp32 fait déjà 16,5 %** (+2,0 points) : la compression, calibrée en
anglais, coûte en français québécois, et la quantification ajoute 2 à 3 points.

Protocole [mesuré hôte] : sherpa-onnx 2.0.1 Python (onnxruntime 1.28.2, celui de l'AAR), x86-64 4 cœurs, 4 threads,
langue `fr` forcée, `tailPaddings` 1 000, décodeur int8 de la conversion Lite, turbo int8
(`csukuangfj/sherpa-onnx-whisper-turbo`) remesuré **dans la même exécution, en alternance clip par clip**. Temps = somme
sur les clips du meilleur de N passages (7 clips : N = 1 ou 2 ; 172 extraits : N = 1). La machine est plus lente que
celle des mesures de l'issue (turbo : 12,2 à 14,5 s au lieu de 9,9 s selon la charge) : seules les comparaisons dans
une même exécution valent. « Phrases différentes » : transcription normalisée (casse, ponctuation) différente de turbo.
Outils : `android/tools/whisper-hf-vers-sherpa/quantifier_lite.py` (toutes les variantes ci-dessous, à partir de
l'encodeur fp32 produit par `convertir_lite.sh`), `mels_calibration.py`, `android/tools/whisper-mesure-python/banc_lite.py`.

#### Reproduction (étape 1)

| Encodeur | Taille | 7 clips : temps (turbo dans la même exécution) | Sortie |
| --- | --- | --- | --- |
| Lite int8 par tenseur (`convertir_lite.sh` tel quel) | 460 Mo | 16,2 s (12,97 s) | « ... » |
| Version publiée (`W2` en fp32) | 799 Mo | 15,4 s (12,97 s), **+18 %** | correcte, 2 phrases différentes |
| Même variante refaite par `quantifier_lite.py --w2 fp32` | 798 Mo | 16,4 s (13,35 s) | correcte, 2 phrases différentes |

#### Pistes de l'issue, dans l'ordre

| Piste | Variante | Taille | 7 clips : temps (turbo) | Sortie |
| --- | --- | --- | --- | --- |
| 1 | Recombiner les couches où `r × (in + out) ≥ 0,6 × in × out` (**53** couches sur 139 ; 86 restent factorisées), `W2` restantes en fp32 | **628 Mo** | 14,5 s (13,35 s), +9 % | correcte, 2 phrases différentes |
| 1 | Idem, `W2` en int8 dynamique | 496 Mo | 17,7 s | « ... » |
| 2 | `MatMulNBits` int8, blocs de 32, calcul fp32 (`accuracy_level` 0), sur les `W2` | 503 Mo | 15,6 s (14,5 s), +8 % | correcte, 2 phrases différentes |
| 2 | Idem, `accuracy_level` 4 (activations int8 par bloc) | 503 Mo | 16,0 s | dégradée (WER 52 %) |
| 2 | `MatMulNBits` int8, blocs de 128 ; int4, blocs de 32 (calcul fp32 ou int8) | 480 à 507 Mo | 19,9 à 20,8 s | « ... » |
| 3 | int8 statique (échelle d'activation fixe) des `W2`, calibration sur 20 extraits du jeu #24 (min/max ou pourcentile 99,99) | 496 Mo | 17,0 à 17,3 s | « ... » |
| 3 | Tout l'encodeur en int8 statique (pourcentile 99,99) | 496 Mo | 18,4 s | « ... » |
| 3 | Statique sauf les `W2` (fp32) | 628 Mo | 14,0 s (12,46 s), +12 % | correcte, 2 phrases différentes |
| 4 | `W2` en fp16 (Cast avant et après) | 540 Mo | 13,1 s (12,51 s), +5 % | correcte, 2 phrases différentes |

`MatMulNBits` est bien exécuté par l'onnxruntime de sherpa-onnx 2.0.1 (1.28.2, Python et AAR arm64 : opérateur présent
dans `libonnxruntime.so`) ; la piste 2 n'a donc pas été abandonnée d'office.

#### Cause de l'échec de l'int8 et rotation (complément de la piste 3)

Diagnostic sur les vrais `h` d'un extrait : l'erreur relative de `h @ W2` en int8 par tenseur est de 4 % en médiane et
jusqu'à 9 % par couche (contre ~1 % d'habitude), et elle vient **surtout des poids** : les colonnes de `W2` ont des
valeurs extrêmes isolées (max/écart-type médian 11, jusqu'à 35), de même que `h` (15, jusqu'à 67). Cumulée sur 86 à 139
couches, cette erreur détruit le modèle. Cela explique que seuls les blocs fins (32) de la piste 2 tiennent.

Correctif sans calibration ni coût à l'exécution : une **rotation orthogonale de l'espace de rang**, `W1 → W1 Q` et
`W2 → Qᵀ W2` (Q aléatoire r×r, graine fixe), qui laisse le produit inchangé en fp32 mais répartit les valeurs extrêmes
(max/écart-type ramené à 6,5 pour `W2` et 4,6 pour `h` ; erreur médiane 1,8 %). Avec elle, toutes les `W2` passent en int8
dynamique (`--rotation --w2 dyn8 --par-colonne w2,w1,plein`, échelle des poids par colonne) :

| Variante (rotation, tout en int8 dynamique) | Taille | 7 clips : temps (turbo dans la même exécution) | 7 clips : WER (turbo 9,6 %) |
| --- | --- | --- | --- |
| Recombinaison au seuil 0,4 (72 couches) | 530 Mo | 12,09 s (12,20 s) | 13,7 %, 2 phrases différentes |
| Recombinaison au seuil 0,6 (53 couches) | 498 Mo | 11,73 s (12,20 s) | 11,0 %, 2 phrases différentes |
| Recombinaison au seuil 0,8 (26 couches) | 468 Mo | 11,99 s (12,20 s) | 9,6 %, 2 phrases différentes |
| Sans recombinaison | 461 Mo | 12,09 s (12,20 s) | 13,7 %, 3 phrases différentes |
| Seuil 0,6, sans rotation (témoin) | 498 Mo | 16,2 s | « ... » |

#### Jeu québécois de 172 extraits (#24) : 1 673 mots, même exécution, 1 passage

| Encodeur | Taille | Temps | WER | Phrases différentes de turbo |
| --- | --- | --- | --- | --- |
| **Turbo int8 (référence)** | 675 Mo | 330,0 s | **14,5 %** (243) | — |
| Lite fp32 (non quantifié) | 1,74 Go | 596,7 s | 16,5 % (276) | 77 |
| Lite, version publiée (`W2` fp32) | 799 Mo | 386,4 s (+17 %) | 18,6 % (311) | 82 |
| Lite, rotation, seuil 0,6 | 498 Mo | 324,1 s (−2 %) | 19,5 % (327) | 94 |
| Lite, rotation, seuil 0,8 | 468 Mo | 323,1 s (−2 %) | 19,5 % (327) | 89 |
| Lite, rotation, sans recombinaison | 461 Mo | 324,6 s (−2 %) | 19,9 % (333) | 87 |

Bilan des critères : taille **remplie** (461 à 530 Mo) ; vitesse **à peine** (−2 % sur 172 extraits, −1 à −4 % sur
7 clips, dans le bruit de mesure) ; transcriptions **non remplies** (2 phrases sur 7 différentes, comme Lite fp32 et la
version publiée ; WER +5 points sur le jeu #24, la limite étant +2). Le critère « au plus 1 phrase sur 7 » n'est
atteignable par aucune quantification : Lite fp32 lui-même diffère de turbo sur 77 des 172 extraits.

#### Pourquoi Lite ne peut pas être beaucoup plus rapide sur CPU

Profil de l'encodeur turbo int8 (onnxruntime 1.32, 1 600 trames, 4 threads, 900 ms) [mesuré hôte] : environ **40 %** du
temps est l'attention (produits `QKᵀ` et `AV` en fp32 sur ~1 500 trames, softmax), que LiteASR ne touche pas ; les
multiplications à poids n'en font qu'environ 40 %, et Lite n'en retire qu'un tiers des paramètres. Le décodeur, inchangé,
représente encore à peu près la moitié du temps d'un clip court. Le gain maximal de Lite sur le temps total est donc de
l'ordre de 5 à 7 %, avant même le surcoût des deux petites MatMul quantifiées par couche factorisée. LiteASR vise le GPU
en fp16, où les petites MatMul sont bon marché.

Remarque (demande de l'utilisateur) : turbo garde **la même architecture d'encodeur** que large-v3 (32 couches, largeur
1 280 ; poids réajustés par l'affinage : les tenseurs d'encodeur des deux dépôts `openai/whisper-large-v3*` diffèrent
[vérifié par requêtes partielles sur les `model.safetensors`]) ; c'est le décodeur réduit de 32 à 4 couches qui le rend
rapide. Ce qui distingue large-v3 de turbo, et ce qu'on peut encore en tirer pour la vitesse, est une piste séparée
(issue #33), non traitée ici.

## 8. Autres moteurs

Évaluation honnête, sans implémentation.

| Moteur | Ce que c'est | Gain possible | Coût d'intégration | Verdict |
| --- | --- | --- | --- | --- |
| whisper.cpp | Moteur C/C++ (ggml) avec exemple Android (`examples/whisper.android`), quantifications q5_0/q8_0 (turbo q5_0 : 574 Mo), option `-ac` (contexte audio réduit : même idée que `tailPaddings`) | Incertain : on ne dispose d'aucun chiffre vérifié de turbo sur Android ; un banc tiers cite une grande différence entre moteurs sur Whisper tiny, non reproduit ici. Pas de NPU pour Android dans le README (Core ML pour Apple, VitisAI pour AMD, Vulkan pour GPU) | Élevé : second moteur à côté de sherpa-onnx (JNI, NDK, modèles ggml à héberger et à vérifier par SHA-256), dérive du pipeline VAD/texte | **À écarter** tant que les leviers 1 à 3 n'ont pas été essayés |
| faster-whisper / CTranslate2 | Moteur Python/C++ pour serveur et PC | — | Pas de version Android officielle trouvée | **À écarter** (déjà utilisable côté serveur personnel, voir `DECISIONS.md`, serveur distant) |
| ExecuTorch / LiteRT (Arm AI Portal) | Whisper tiny/small/medium int8 (XNNPACK ExecuTorch, LiteRT) publiés par Arm | Inconnu pour turbo : modèles publiés jusqu'à medium (liste de recherche, non ouverte) | Élevé : autre environnement d'exécution | **À écarter** |
| NPU Qualcomm (QNN, AI Hub) | Voir section 5 | Élevé en théorie | Très élevé | **Étude séparée** si Whisper devient le modèle courant |

## 9. Ergonomie : réduire la latence perçue sans accélérer le calcul

- **État affiché** : « Transcription… » existe déjà (`status_finishing`) [vérifié]. Un indicateur plus parlant pour
  Whisper (« Whisper réfléchit… », durée écoulée) coûte une chaîne et rassure ; à ajouter si le gain mesuré reste modeste.
- **Pause de fin de segment** (`VadParams.minSilenceSeconds` = 0,5 s) : chaque pause ajoute 0,5 s avant le début du calcul.
  La raccourcir à 0,3 s gagne 0,2 s mais coupe plus souvent les phrases, et chaque segment de plus paie la marge de fin
  de Whisper (section 1) : **à déconseiller pour Whisper**. L'allonger à 0,8 s fait l'inverse : moins de segments, moins de
  marge payée, mais chaque résultat arrive 0,3 s plus tard. Plus utile : le laisser à 0,5 s et ne pas multiplier les pauses.
- **Segments plus courts** : à marge égale, un segment court coûte moins qu'un long (courbe de la section 1), mais la marge
  fixe (10 s) fait que deux segments courts coûtent plus qu'un seul long. Pour Whisper, il vaut mieux **dicter des phrases
  entières** que des mots séparés par des pauses ; seul le mot isolé (le cas « friperie ») profite d'un segment court.
- **Longueur maximale** : le VAD coupe à 20 s (`maxSpeechSeconds`). Pour Whisper, rien à changer : à 20 s l'encodeur
  traite 3 000 images, soit la fenêtre complète.
- **Échauffement** : le premier décodage après le chargement est un peu plus lent que les suivants (hôte : 875 ms puis
  810, 746, 749 ms, soit +17 % sur le premier ; chargement du modèle 2,5 s) [mesuré hôte]. Un décodage à blanc après le
  chargement (EngineManager) cacherait ce surcoût ; gain petit sur l'hôte, **à mesurer sur téléphone** où les allocations
  coûtent davantage [supposé].
- **Garder le modèle chargé** : déjà fait (5 minutes par défaut, `DECISIONS.md`, « Cycle de vie du modèle ») ; un
  rechargement de Whisper (≈ 1 Go) est le pire cas : ne pas raccourcir le délai pour ce modèle.
- **Utilisation en dernier recours** : une dictée assez longue amortit mieux que des mots séparés (voir ci-dessus). Pour le
  mot isolé difficile, un réglage « essayer Whisper sur ce segment » (bouton) évite de payer Whisper pour toute la dictée
  [idée, non instruite ici].

## 10. Procédure de mesure sur le téléphone

À suivre avec l'écran « Comparer les modèles » (Réglages > Comparer les modèles). Rien à compiler pour les étapes 1 à 4 ;
l'étape 5 utilise le réglage « Silence ajouté pour Whisper » (Réglages, section Avancé, en bas de l'écran, depuis l'issue #49).

**Préparation** : téléphone branché, économiseur de batterie éteint, autres applications fermées, écran allumé,
température ordinaire ; installer Whisper turbo et, pour comparer, Parakeet. Cocher tous les extraits de référence.
Enregistrer (bouton « Enregistrer », jusqu'à 30 s) **trois enregistrements** de votre voix, à faire un à la fois dans
trois exécutions : (a) « friperie » seul, (b) une phrase de ~5 s contenant un mot québécois, (c) ~15 s de dictée
continue. Dans tous les cas : **une première exécution pour chauffer (à jeter), puis trois exécutions notées**, avec une
minute de pause entre elles (un téléphone qui chauffe ralentit ; noter si les temps montent d'une exécution à l'autre).

**À noter pour chaque exécution** (le rapport « Copier » donne tout) : temps de décodage et RTF de chaque extrait pour
Whisper (et Parakeet en repère) ; temps de chargement ; mémoire en pointe ; fournisseur affiché (`provider cpu`,
`xnnpack` ou `nnapi`) ; le texte obtenu pour « friperie ».

| Étape | Quoi régler | Comparer | Lecture |
| --- | --- | --- | --- |
| 1. Référence | Réglages : threads = valeur actuelle, accélération CPU, ne rien d'autre changer | Temps et RTF de Whisper sur l'enregistrement (a), (b), (c) et les extraits | Base de tous les gains ; noter aussi le modèle du téléphone (l'en-tête du rapport le donne) |
| 2. Threads | Curseur « Threads de calcul » : 2, 3, 4, 5, 6 (une exécution complète par valeur) | Temps de (a) et (b) selon les threads | Prendre la valeur au temps le plus bas ; si 5 ou 6 est plus lent que 4, ou que 3 que 4, c'est l'effet des cœurs asymétriques. **Garder ce réglage** s'il gagne plus de 15 % |
| 3. XNNPACK | « Accélération matérielle » : XNNPACK | Temps de (a), (b) contre l'étape 1 ; fournisseur affiché | Attendu : égal ou plus lent (voir section 5). Si le moteur échoue, le message d'erreur est à noter |
| 4. NNAPI | « Accélération matérielle » : NNAPI | idem | Attendu : égal ou nettement plus lent. Remettre ensuite sur CPU |
| 5. Marge de fin | Réglages > « Silence ajouté pour Whisper » : 1 000 (défaut), 600, 300 ; une exécution de « Comparer les modèles » par valeur (le rapport indique la valeur utilisée) | Temps de (a), (b), (c) **et le texte** (répétitions, mots manquants) | Garder 600 seulement si le texte de (a), (b), (c) est identique ou meilleur sur 5 essais par valeur ; refuser toute répétition |
| 6. Texte provisoire | Aucun réglage : avec Whisper, le texte provisoire est toujours coupé. Dicter **dans le clavier** (pas dans l'écran de comparaison) une phrase de ~5 s, avec cette version, puis, si on l'a encore, avec la version précédente (qui affichait le texte provisoire) | Chronomètre ou enregistrement d'écran : temps entre la fin de la phrase et l'apparition du texte final, 5 essais par version | Attendu : gain moyen d'environ la moitié d'un décodage provisoire, jusqu'à un décodage entier (section 2). Sans la version précédente, noter seulement le temps obtenu. Noter aussi la chaleur du téléphone après 1 minute |

Limites de cet écran (mises à jour 2026-11-12) : il utilise désormais **les mêmes options de moteur que la dictée** (langue forcée
quand une seule langue est choisie, nombre de threads, accélération, silence ajouté pour Whisper) ; l'en-tête du rapport
« Copier » indique le silence ajouté et la langue de Whisper. Il décode toujours des extraits entiers (marge de fin comprise),
pas des segments VAD en direct, et ne mesure ni le texte provisoire ni la pause de fin : l'étape 6 se fait donc dans le clavier.

**Résultat à rapporter** : les rapports « Copier » de chaque étape, la valeur de threads retenue, et pour les étapes 5 et 6
les textes obtenus et les temps chronométrés.

## 11. Changements de code recommandés (faits le 2026-11-12 sauf mention)

| Fichier | Nature | Raison / lien |
| --- | --- | --- |
| **FAIT** `app/src/main/java/io/github/remtav/dictee/DicteeApp.kt` (ligne 145) | `partials = settings.partials && spec.family != ModelFamily.REMOTE && spec.family != ModelFamily.WHISPER` (ou un réglage « texte provisoire pour Whisper », désactivé par défaut) | Section 2. Ajouter dans `res/values/strings.xml` une phrase d'aide (`settings_partials_help`) disant que Whisper n'affiche pas de texte provisoire |
| **FAIT** `stt/SttEngine.kt` (`EngineOptions`) | Ajouter `whisperTailPaddings: Int = 1000` (le défaut de sherpa-onnx) | Section 1 ; fait partie de la clé de `EngineManager`, donc le moteur est rechargé au changement |
| **FAIT** `stt/SherpaEngine.kt` (`buildConfig`, branche `WHISPER`) | Passer `tailPaddings = options.whisperTailPaddings` à `OfflineWhisperModelConfig` | Section 1 ; champ présent dans l'AAR 2.0.1 [vérifié] |
| **FAIT** `settings/AppSettings.kt` | Réglage avancé `whisperTailPaddings` (non exposé dans l'interface principale au début ; lu par `engineOptions` pour la famille Whisper seulement) | Pour l'étape 5 de mesure ; changer le défaut à 600 seulement après la mesure téléphone |
| **NON FAIT** `text/` (filtre, par exemple `Filters.kt`) | Filtre « phrase répétée deux fois » pour la sortie de Whisper (texte = X + espace + X, ou répétition de la fin) avec tests | Filet de sécurité si 300 à 600 est retenu (section 1) |
| **FAIT en partie** `ui/CompareActivity.kt` | Fait : les options de la dictée (langue forcée, silence ajouté) passées à chaque modèle, et l'en-tête du rapport qui les nomme. Non fait : exécuter Whisper en double (marge par défaut et réduite) dans une même comparaison, afficher threads et fournisseur dans chaque carte (on change le réglage et on relance) | Rend les étapes 1 à 5 faisables sans build de mesure |
| **NON FAIT** `stt/EngineManager.kt` | Décodage à blanc (1 s de silence, résultat ignoré) après le chargement de Whisper | Section 9, à garder seulement si la mesure téléphone montre un premier décodage nettement plus lent |
| **NON FAIT** `stt/SttEngine.kt` (`defaultThreads`) | Après l'étape 2 : valeur par défaut propre à l'appareil (nombre de gros cœurs) | Section 4 |

Détails de ce qui a été fait : la décision est la fonction pure `WhisperTuning.provisionalAllowed` (`stt/WhisperTuning.kt`),
sans réglage de remplacement ; le champ sherpa est `OfflineWhisperModelConfig.tailPaddings`, **Int** (`javap` sur l'AAR 2.0.1),
renseigné par `SherpaEngine` pour la famille Whisper seulement ; `EngineOptions.whisperTailPaddings` (défaut 1 000, valeurs
acceptées 1 000, 600, 300, toute autre valeur relue comme 1 000) fait partie de la clé de `EngineManager` (les autres modèles
reçoivent toujours 1 000, donc ne sont pas rechargés) ; `Benchmark` accepte des options par modèle. Le filtre de phrase répétée
n'est pas fait : à décider seulement si 600 est retenu après la mesure sur le téléphone.

Ne **pas** faire : changer le fournisseur par défaut (XNNPACK, NNAPI), ajouter un second moteur, remplacer Whisper par un
modèle plus petit.

## 12. Mesures hôte brutes

Machine : Intel Xeon @ 2,10 GHz, 4 cœurs, partagée (colonne « charge » = moyenne sur 1 minute au moment de la mesure).
Temps = **minimum de 2 mesures** après un échauffement. « CPU » = temps CPU du processus pour la mesure retenue (à 4 threads,
il inclut l'attente active). Commande : `./gradlew testDebugUnitTest -PmodelsDir=/home/user/dictee-models --tests
'*WhisperSpeedHostTest.<test>' --console=plain` ; variables `DICTEE_SPEED_OUT` (fichier de résultats) et
`DICTEE_WHISPER_SMALL_DIR` (Whisper small, test G). Tests : `durationsAndThreads` (A), `tailPadding` (C),
`tailPaddingOneThread` (C1), `tailPaddingQuality` (C2), `provisionalDecodes` (E), `otherModels` (F),
`whisperSmallVsTurbo` (G), `coldStart` (H).

### A : durées, threads, langue (Whisper turbo, marge par défaut)

| Audio | Threads | Langue | Images | Écoulé | CPU | Charge |
| --- | --- | --- | --- | --- | --- | --- |
| 1 s | 4 | `fr` | 1 098 | 682 ms | 2 720 ms | 2,4 |
| 5 s | 4 | `fr` | 1 498 | 1 422 ms | 5 370 ms | 2,5 |
| 15 s | 4 | `fr` | 2 498 | 2 911 ms | 10 720 ms | 2,6 |
| 1 s | 4 | auto | 1 098 | 726 ms | 2 870 ms | 2,7 |
| 5 s | 4 | auto | 1 498 | 1 434 ms | 5 450 ms | 2,8 |
| 1 s | 2 | `fr` | 1 098 | 1 242 ms | 2 510 ms | 2,7 |
| 5 s | 2 | `fr` | 1 498 | 2 386 ms | 4 680 ms | 2,7 |
| 1 s | 1 | `fr` | 1 098 | 2 071 ms | 2 060 ms | 2,4 |
| 5 s | 1 | `fr` | 1 498 | 4 114 ms | 4 110 ms | 2,1 |

RTF à 4 threads, langue forcée : 0,68 (1 s), 0,28 (5 s), 0,19 (15 s). Le coût fixe se voit : un mot de 1 s coûte
48 % du temps d'un segment de 5 s alors qu'il dure 5 fois moins.

### C1 : marge de fin sur 1 thread (la mesure de référence, charge 1,3 à 2,1)

| Marge | Images (1 s / 5 s) | 1 s : écoulé = CPU | 5 s : écoulé = CPU | Texte du mot de 1 s |
| --- | --- | --- | --- | --- |
| 1 000 (défaut) | 1 098 / 1 498 | 2 111 / 2 090 ms | 4 206 / 4 140 ms | « Du reste, sa mère est » |
| 600 | 698 / 1 098 | 1 345 / 1 330 ms | 2 928 / 2 920 ms | « Du reste, sa mère est » |
| 300 | 398 / 798 | 852 / 860 ms | 2 342 / 2 330 ms | « Du reste sa mère. Du » |
| 100 | 198 / 598 | 632 / 640 ms | 1 946 / 1 960 ms | « du reste sa mère et sa » |
| 3 000 (fenêtre fixe) | 3 000 / 3 000 | 8 470 / 8 400 ms | 9 615 / 9 570 ms | « Du reste, sa mère est » |

### C : marge de fin sur 4 threads (charge variable, 2,6 à 7,3 : lecture indicative)

| Marge | 1 s | 5 s | 15 s | Charge |
| --- | --- | --- | --- | --- |
| 600 | 536 ms | 1 210 ms | 2 716 ms | 2,6 à 3,2 |
| 300 | 1 169 ms | 3 823 ms | 7 697 ms | 3,1 à 6,2 (autre tâche démarrée) |
| 100 | 1 413 ms | 3 738 ms | — | 6,8 à 7,3 |
| 3 000 | 2 474 ms | 3 007 ms | — | 6,5 à 6,9 |

Ces lignes à 300, 100 et 3 000 sont **faussées par la charge** (plus lentes que 600) : ne pas les utiliser ; voir C1. Avec la
charge au plus bas, un second passage (fichier « mixte ») avait donné pour 300 : 404 ms (1 s), 1 011 ms (5 s), 2 457 ms
(15 s) et pour 100 : 339 ms et 888 ms, charge 5,2 à 5,6.

### C2 : qualité selon la marge (4 threads, 9 clips)

Voir le tableau de la section 1. Écarts relevés : à 1 000 et 600, `frqc1` « seul le riz » ou « seules riz » (le clip dit
« seuls le riz »), `frqc2` « âmes » pour « sœurs », `frqc3` « nombre » pour « nom » ; à 300, `frqc5` répétée une fois ;
à 100, répétitions sur 4 clips.

### E : texte provisoire (voir le tableau de la section 2, trois passages)

Passage chargé (≈ 12) : Whisper 2 provisoires, 10 601 ms de calcul, final seul 7 155 ms, attente 5 901 ms, texte après la
parole 15,41 s contre 7,65 s ; marge 300 : attente 578 ms, 7,69 s contre 7,25 s ; Parakeet : 4 provisoires, attente 927 ms,
3,92 s contre 2,72 s ; Qwen3-ASR : 2 provisoires, attente 5 446 ms, 12,30 s contre 7,70 s.

### F : les deux autres modèles, 4 threads, langue automatique, charge ≈ 4,3

| Modèle | 1 s | 5 s | 15 s | RTF (1 s / 5 s / 15 s) |
| --- | --- | --- | --- | --- |
| Parakeet TDT 0.6b v3 | 130 ms (CPU 520) | 374 ms (CPU 1 510) | 854 ms (CPU 3 390) | 0,13 / 0,075 / 0,057 |
| Qwen3-ASR 0.6B | 623 ms (CPU 2 470) | 1 322 ms (CPU 5 010) | 3 229 ms (CPU 11 960) | 0,62 / 0,26 / 0,22 |
| Whisper turbo (A, charge ≈ 2,5) | 682 ms (CPU 2 720) | 1 422 ms (CPU 5 370) | 2 911 ms (CPU 10 720) | 0,68 / 0,28 / 0,19 |

Whisper est 5,2 fois plus lent que Parakeet sur 1 s, 3,8 fois sur 5 s, 3,4 fois sur 15 s : l'écart se creuse sur les
segments courts, effet du coût fixe. (Whisper et Qwen3 ont été mesurés à des charges un peu différentes : écart de 10 à 20 %
non significatif.)

### G : Whisper small contre turbo (même exécution, en alternance, 4 threads, langue forcée, charge ≈ 3,9)

| Audio | small | turbo | turbo / small | Texte small |
| --- | --- | --- | --- | --- |
| 1 s | 660 ms | 839 ms | 1,3 | « du reste à main-ra » |
| 5 s | 1 668 ms | 1 799 ms | 1,1 | « Parfois seul le riz et le pain sont prop… » |

WER sur les 7 clips français : small 12 erreurs sur 75 mots (16,0 %), turbo 7 sur 75 (9,3 %). Le faible écart de vitesse
est inattendu (l'encodeur de small est ~7 fois plus petit) : sous charge variable et sur 2 mesures, **à confirmer sur
téléphone** avant d'écarter small sur ce seul critère ; le critère de qualité suffit déjà.

### H : premier décodage après le chargement (charge ≈ 2,6)

Chargement 2 483 ms ; décodages successifs du mot de 1 s : 875, 810, 746, 749 ms.

### Microbanc de l'encodeur seul (python, onnxruntime 1.29, 4 threads, **charge forte ≈ 8 à 10**, indicatif)

| Images | 200 | 400 | 1 100 | 1 300 | 1 600 | 2 500 | 3 000 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Encodeur | 0,49 s | 0,83 s | 2,78 s | 2,91 s | 3,50 s | 7,66 s | 8,90 s |

Même forme que C1 : coût croissant avec la longueur, plus que proportionnel au-delà de 1 500 images.

## 13. Sources

Code et AAR [vérifié] : `SherpaEngine.kt`, `SttEngine.kt`, `AppSettings.kt`, `DicteeApp.kt`, `DictationController.kt`,
`Benchmark.kt`, `CompareActivity.kt`, `Vad.kt` ; AAR `sherpa-onnx-2.0.1.aar` (SHA-256 `a8d09d1d…ee7`, `javap` de
`OfflineWhisperModelConfig`, `OfflineModelConfig`, `OfflineRecognizer`, `strings` de `libonnxruntime.so` et
`libsherpa-onnx-jni.so`).

sherpa-onnx v2.0.1 (lu sur `raw.githubusercontent.com/k2-fsa/sherpa-onnx/v2.0.1/sherpa-onnx/csrc/`) :
`offline-recognizer-whisper-impl.h`, `offline-whisper-greedy-search-decoder.cc`, `offline-whisper-model.cc`, `session.cc` ;
`offline-whisper-model-config.cc` (v1.12.0, aide de `tail_paddings`).

Documentation et fiches :

- onnxruntime, NNAPI : https://onnxruntime.ai/docs/execution-providers/NNAPI-ExecutionProvider.html
- onnxruntime, XNNPACK : https://onnxruntime.ai/docs/execution-providers/Xnnpack-ExecutionProvider.html
- sherpa-onnx, QNN : https://k2-fsa.github.io/sherpa/onnx/qnn/index.html
- Qualcomm AI Hub, Whisper-Large-V3-Turbo : https://huggingface.co/qualcomm/Whisper-Large-V3-Turbo
- OpenAI Whisper, tableau des modèles : https://github.com/openai/whisper (README)
- Whisper large-v3-turbo : https://huggingface.co/openai/whisper-large-v3-turbo
- distil-large-v3 : https://huggingface.co/distil-whisper/distil-large-v3
- distil-large-v3-fr : https://huggingface.co/eustlb/distil-large-v3-fr
- bofenghuang, distillations françaises : https://huggingface.co/bofenghuang/whisper-large-v3-french-distil-dec16
- export sherpa-onnx de la distillation française : https://huggingface.co/pantinor/sherpa-onnx-whisper-large-v3-french-distil
- dépôts sherpa-onnx Whisper (liste et tailles par l'API Hugging Face) : `csukuangfj/sherpa-onnx-whisper-*`
- whisper.cpp : https://github.com/ggml-org/whisper.cpp (README, `examples/cli/README.md`) ; fichiers ggml :
  https://huggingface.co/ggerganov/whisper.cpp
- Forum Qualcomm RB5 (TFLite : 584 ms CPU contre 7 168 ms NNAPI) : https://developer.qualcomm.com/forum/qdn-forums/hardware/qualcomm-robotics-rb5-development-kit/68800
  (cité d'après un résultat de recherche, page non ouverte)

Non vérifié : la composition exacte des cœurs du Snapdragon 8 Gen 2 ; la présence de la puce du S23 Ultra dans les
binaires QNN de Qualcomm ; tout chiffre de latence sur téléphone ; la qualité de `tailPaddings` sur des mots isolés
réels ; la durée de décodage d'un mot québécois isolé (« friperie ») ; le comportement de XNNPACK et NNAPI sur ce modèle
(déduit de la documentation et des opérateurs, non essayé).
