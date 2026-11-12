# Polissage sur le téléphone : rapport et recommandation

Issue #15, lot 3 (étapes 3.1 à 3.4), 2026-11-12. Les chemins partent de la
racine du dépôt.

**Rien n'est activé par défaut.** Le code évalué existe dans l'application,
mais aucun écran, aucun réglage et aucun appel ne le branche (voir
« Comportement par défaut »).

## La question et la réponse courte

**Question.** Pour corriger « git hub » en `GitHub` et
« c o i f f e … point com » en `coifferimouski.com`, que vaut chaque approche
en qualité, en latence et en mémoire sur le téléphone ? Quatre approches :
règles déterministes (1), vocabulaire livré (2), petit LLM local avec un
prompt élargi et un garde-fou assoupli (3), règles d'abord puis LLM sur les cas
probables (4).

**Réponse courte.**

- **Règles + vocabulaire livré, sans LLM** : 55 cas corrigés sur 79, aucune
  des 53 phrases de contrôle abîmée, moins de 1 ms par segment (sur
  l'ordinateur). C'est la meilleure approche mesurée. Une partie du score vient
  de formes tirées du jeu lui-même : sans elles, 36 sur 79.
- **Un petit LLM n'ajoute presque rien.** Même un LLM parfait (simulé),
  derrière le garde-fou, ne porte la combinaison qu'à 56 sur 79 (un cas de
  plus), et 58 sur 79 s'il passait sur tous les segments. Seul, le LLM parfait
  atteint 36 sur 79. Ce sont des plafonds : aucun vrai modèle ne fera mieux
  sur ce jeu.
- **Sans garde-fou, un LLM abîme des mots corrects** : le LLM « bruyant »
  simulé abîme 13 contrôles sur 53, dont 4 mots d'ici (le cas « friperie »).
- **`coifferimouski.com`** n'est rétabli que lorsque la transcription a
  gardé toutes les lettres du nom. Quand le modèle de transcription a perdu ou
  changé des lettres (`coiffrimouski.com`, `coiffremouski.com`), aucune
  approche sûre ne le rattrape.
- **Aucun LLM n'a tourné** : MediaPipe n'existe que pour arm64. Les vraies
  sorties, la latence et la mémoire sur le téléphone restent **à faire**. La
  procédure est plus bas.

## Le jeu d'évaluation

Fichier : `android/app/src/test/resources/polissage/jeu-evaluation.tsv`. Il
contient 132 paires « transcription brute → texte attendu » :

- **79 cas à corriger** : noms propres et techniques (14), toponymes et
  institutions du Québec (27), adresses web (16), courriels (11), épellation
  et codes postaux (11) ; 58 en français, 21 en anglais.
- **53 contrôles** qui ne doivent pas changer : phrases ordinaires et pièges
  (« un point commun », « mets un point final », « plan B »…, 23), mots rares
  ou d'ici bien transcrits (« friperie », « poudrerie », « pourvoirie »,
  « tuque », « cégep »…, 19), et le mot visé déjà bien transcrit (11).

La notation est stricte : la sortie doit être identique à l'attendu, casse,
accents, traits d'union, ponctuation et espaces compris. Un contrôle qui
change d'un seul caractère compte comme abîmé. Un cas à corriger qui change
sans atteindre l'attendu compte comme « modifié à tort ».

### Provenance

- **80 lignes de vraies sorties** des trois modèles du catalogue
  (Parakeet, Qwen3-ASR, Whisper), obtenues sur l'ordinateur avec le code de
  l'application. L'audio d'entrée était une **voix de synthèse** (Piper, voix
  `fr_FR-siwis-medium` et `en_US-lessac-medium`).
- **52 lignes rédigées à la main**, d'après les erreurs observées sur ces
  sorties.

### Limites (à lire avant les chiffres)

- **Pas de voix québécoise.** La voix de synthèse française a l'accent de
  France ; elle est propre, régulière, et prononce les noms anglais à la
  française. Les erreurs d'une vraie personne du Québec seront différentes,
  souvent plus nombreuses.
- **Lignes rédigées.** Plus d'un tiers des paires sont vraisemblables, pas
  observées.
- **Surapprentissage du vocabulaire.** La liste livrée
  (`android/app/src/main/java/io/github/remtav/dictee/text/polish/ShippedVocabulary.kt`)
  a été écrite en connaissant le jeu. Sur ses 178 formes, 20 (11 %) sont
  marquées `OBSERVED` : elles figurent mot pour mot dans une transcription du
  jeu. Elles font **la moitié** des corrections du vocabulaire (19 sur 38).
  Sans elles, règles + vocabulaire passe de 55 à 36 cas corrigés.
- **Réglage des règles sur les contrôles.** Les mots d'introduction et les
  pièges des règles ont été ajustés sur ces 53 contrôles. Un contrôle jamais
  vu pourrait être réécrit.
- **53 contrôles, c'est peu.** « Zéro contrôle abîmé » ici ne prouve pas
  qu'aucune phrase réelle ne sera abîmée.

## Tableau comparatif

Trois natures de chiffres, à ne pas mélanger :

- **Mesuré ici** : l'approche a vraiment tourné sur le jeu, sur l'ordinateur
  (Linux x86-64).
- **Simulé** : aucun modèle n'a tourné. Un LLM « oracle » rend exactement
  le texte attendu : c'est le plafond de ce que le garde-fou laisse passer. Un
  LLM « bruyant » est une invention déterministe (55 % de bonnes réponses,
  le reste abîmé ou inchangé ; 30 % des contrôles abîmés) qui montre comment
  le garde-fou réagit aux erreurs. **Ce ne sont pas des résultats de modèle.**
- **À faire sur le téléphone** : les mesures que seul un téléphone arm64 peut
  donner.

### Qualité

Format des cellules : cas corrigés / 79 · modifiés à tort · contrôles abîmés
/ 53 (dont mots rares / 19).

| Approche | Mesuré ici | Simulé, LLM oracle | Simulé, LLM bruyant | À faire sur le téléphone |
| --- | --- | --- | --- | --- |
| Rien (référence) | 0 · 0 · 0 (0) | — | — | — |
| 1. Règles | 17 · 2 · 0 (0) | — | — | — |
| 2. Vocabulaire livré | 38 · 1 · 0 (0) | — | — | — |
| 2 bis. Vocabulaire sans formes `OBSERVED` | 19 · 5 · 0 (0) | — | — | — |
| 1 + 2. Règles + vocabulaire | **55 · 2 · 0 (0)** | — | — | — |
| 1 + 2 bis. Règles + vocabulaire sans `OBSERVED` | 36 · 6 · 0 (0) | — | — | — |
| 3. LLM, prompt élargi, garde-fou, lexique livré | — | 36 · 0 · 0 (0) | 13 · 0 · 0 (0) | **à faire** (Qwen2.5 0,5B et 1,5B) |
| 3. Idem avec proximité (lexique de simulation) | — | 44 · 0 · 0 (0) | — | **à faire** (`nearMatch`) |
| 3. Même LLM sans garde-fou | — | — | 39 · 28 · **13 (4)** | **à faire** (fichier `-sans-gardefou`) |
| 4. Règles + vocabulaire, puis LLM sur les cas signalés | — | 56 · 2 · 0 (0) | 56 · 2 · 0 (0) | **à faire** |

Autres plafonds simulés (LLM oracle) : la combinaison avec proximité, 56 ;
règles + vocabulaire puis LLM sur **tous** les segments, 57, et 58 avec
proximité.

### Par catégorie et par langue (cas corrigés)

| Approche | Nature | noms (14) | toponymes (27) | web (16) | courriel (11) | épellation (11) | fr (58) | en (21) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1. Règles | mesuré | 0 | 0 | 4 | 4 | 9 | 11 | 6 |
| 2. Vocabulaire | mesuré | 14 | 24 | 0 | 0 | 0 | 31 | 7 |
| 2 bis. Sans `OBSERVED` | mesuré | 11 | 8 | 0 | 0 | 0 | 14 | 5 |
| 1 + 2 | mesuré | 14 | 24 | 4 | 4 | 9 | 42 | 13 |
| 1 + 2 bis | mesuré | 11 | 8 | 4 | 4 | 9 | 25 | 11 |
| 3. LLM + garde-fou | simulé, oracle | 9 | 8 | 5 | 4 | 10 | 25 | 11 |
| 3. Avec proximité | simulé, oracle | 12 | 13 | 5 | 4 | 10 | 31 | 13 |
| 3. LLM + garde-fou | simulé, bruyant | 3 | 1 | 3 | 2 | 4 | 7 | 6 |
| 3. Sans garde-fou | simulé, bruyant | 6 | 13 | 9 | 7 | 4 | 25 | 14 |
| 4. Combinaison | simulé, oracle et bruyant | 14 | 24 | 5 | 4 | 9 | 43 | 13 |

### Latence, mémoire, appels

| Approche | Mesuré ici (ordinateur, p50 / p95 par segment) | À faire sur le téléphone |
| --- | --- | --- |
| 1. Règles | 0,07 / 0,22 ms | — (négligeable, non mesuré) |
| 2. Vocabulaire | 0,29 / 0,92 ms | — (négligeable, non mesuré) |
| 1 + 2 | 0,28 / 0,60 ms | — (négligeable, non mesuré) |
| 3. LLM sur chaque segment | non mesurable ici (132 appels sur 132 segments) | latence p50 / p95, segments au-delà de 4 s, mémoire (PSS, pointe), durée de chargement |
| 4. Combinaison | non mesurable ici (LLM appelé sur 22 segments sur 132 : 21 cas à corriger, 1 contrôle) | idem, plus le nombre d'appels réellement faits |

Les latences d'ordinateur varient d'un passage à l'autre (facteur 2 à 4
entre deux exécutions) ; elles ne servent qu'à dire « moins d'une
milliseconde ». Le délai de 4 s est celui au-delà duquel le nettoyage par LLM
existant (`android/app/src/main/java/io/github/remtav/dictee/cleanup/LlmCleaner.kt`)
abandonne un segment. Batterie : non mesurée, et le test ne la mesure pas.

## Le cas de l'issue : « coifferimouski.com »

Le jeu le décline huit fois, selon ce que la transcription en a fait. Chaque
ligne montre la sortie et son verdict.

| Ligne | Transcription brute | Règles + vocabulaire (mesuré) | LLM + garde-fou (simulé, oracle) | Combinaison (simulé, oracle) |
| --- | --- | --- | --- | --- |
| `fr-web-r01` (rédigée) | Va sur coiffe rimouski point com. | `coifferimouski.com` ✔ | ✔ | ✔ |
| `fr-ep-03-whisper` | C-O-I-F-F-E-R-I-M-O-U-S-K-I.com | `coifferimouski.com` ✔ | ✔ | ✔ |
| `fr-web-01-whisper` | Va voir coiffe Rimouski.com | inchangé | ✔ | ✔ (le seul cas gagné par le LLM) |
| `fr-web-01-qwen3` | Vas voir coiffe rymusky point com. | `coifferymusky.com` (forme d'adresse juste, nom faux) | refusé, inchangé | `coifferymusky.com` |
| `fr-web-01-parakeet` | Va voir coiffrimouski.com | inchangé | refusé, inchangé | inchangé |
| `fr-cou-03-parakeet` | Envoie la facture infoirobase coiffremouski.com | inchangé | refusé, inchangé | inchangé |
| `fr-cou-03-qwen3` | Envoie la facture à info@bascoafrymousqui.com | inchangé | refusé, inchangé | inchangé |
| `fr-ep-03-parakeet` | C O I F F E R I M O U S K Icom | inchangé (signalé « cas probable ») | refusé, inchangé | inchangé |

Ce qu'il faut en retenir :

- Quand toutes les lettres du nom sont là, les règles suffisent : elles
  recollent l'adresse et remplacent « point com » par `.com`.
- Quand le modèle de transcription a perdu ou changé des lettres, le
  garde-fou refuse de les inventer : c'est ce qui protège « friperie ». Seul le
  LLM bruyant **sans** garde-fou « corrige » ces lignes, et ce même LLM abîme
  13 contrôles.
- Pour une adresse qu'on dicte souvent, l'outil sûr existe déjà : le
  vocabulaire personnalisé de l'utilisateur (Réglages, corrections
  `mal entendu -> correct`, par exemple `coiffrimouski -> coifferimouski`).
- Le vrai levier est en amont : un modèle de transcription qui entend mieux le
  français d'ici.

## Risques

- **Un LLM qui repasse sur le texte peut abîmer un mot correct.**
  L'utilisateur l'a vu avec « friperie » et d'autres mots d'ici bien
  transcrits, puis remplacés. Le prompt demande de ne rien toucher, mais un
  modèle de 0,5 ou 1,5 milliard de paramètres ne suit pas toujours la
  consigne. Seul le garde-fou empêche la sortie fautive d'arriver dans le
  texte.
- **Ce que le garde-fou assoupli protège.** Il est défini dans
  `android/app/src/main/java/io/github/remtav/dictee/cleanup/eval/RelaxedSafetyCheck.kt`.
  La sortie n'est acceptée que si elle se dérive du texte d'entrée par des
  réécritures fixes : casse « mixte » (`git hub` → `GitHub`), traits d'union,
  lettres épelées recollées, code postal, adresse lue mot à mot, nom du
  lexique. Tout le reste est refusé : mot ajouté, retiré ou remplacé,
  ponctuation, majuscule simple, préambule, adresse modifiée d'une lettre.
  `friperie` → `fripperie` est refusé dans toutes les configurations. En
  simulation, 399 sorties fautives sur les contrôles : 399 refusées.
- **Ce qu'il ne protège pas : trois limites épinglées** dans
  `knownLimitsAreAcceptedByConstruction`
  (`android/app/src/test/java/io/github/remtav/dictee/cleanup/eval/RelaxedSafetyCheckTest.kt`).
  Le garde-fou ne voit pas le contexte.
  1. Une entrée du lexique qui est aussi une expression ordinaire :
     `des jardins` → `Desjardins`. La liste livrée évite ce cas en gardant le
     contexte dans l'entrée (`caisse Desjardins`), mais une entrée ajoutée sans
     précaution le rouvrirait.
  2. Avec la proximité, un mot ordinaire proche d'un nom : `la team` →
     `la Teams`. C'est pour cette raison que la proximité est désactivée par
     défaut.
  3. Deux prénoms joints par un trait d'union : `Pierre Jean` →
     `Pierre-Jean`.
- **Ce que les approches sans LLM peuvent mal faire.** Elles produisent
  parfois une adresse bien formée avec un nom faux (`coifferymusky.com`), ce
  qui est mieux que le brut mais reste faux. Une forme du vocabulaire peut
  aussi tomber sur un usage légitime qu'on n'a pas prévu.
- **Coût du LLM non mesuré.** Pour la mémoire, l'application téléchargerait
  et chargerait un second modèle (0,5 à 1,6 Go) à côté du modèle de
  transcription. La latence s'ajoute à chaque segment signalé, et la batterie
  n'est pas mesurée.
- **Bibliothèque en fin de vie.** L'API Android de MediaPipe LLM Inference
  est en maintenance seulement ; la documentation renvoie vers LiteRT-LM.
  Livrer un LLM local demanderait probablement de changer de moteur (décision
  à part).

## Recommandation

1. **Par défaut, ne rien changer maintenant.** Aucun polissage local n'est
   activé, et l'interrupteur « Polissage par le serveur » reste comme il est.
   Les chiffres ne permettent d'activer par défaut aucune approche. Zéro
   contrôle abîmé est établi sur 53 phrases seulement, en partie rédigées,
   prononcées par une voix de synthèse à l'accent de France, et la liste a été
   écrite en connaissant le jeu.
2. **Ne pas livrer de LLM de polissage, ni pour l'approche 3 ni pour
   l'approche 4.** La conclusion tient sans mesure sur le téléphone : un LLM
   parfait derrière le garde-fou n'ajoute qu'un cas sur 79 à la combinaison, et
   trois au mieux en passant sur chaque segment. La mesure sur le téléphone ne
   peut que confirmer ou aggraver ce constat (latence, mémoire, sorties
   refusées). Elle reste utile seulement si l'on veut garder un LLM pour une
   autre raison, par exemple le nettoyage des hésitations qui existe déjà.
3. **Prochaine itération, si l'utilisateur le veut : proposer « règles +
   vocabulaire livré » comme option désactivée par défaut**, branchée entre la
   transcription et le texte inséré. C'est déterministe et instantané, ne
   touche ni la ponctuation ni les mots ordinaires du jeu, et l'utilisateur
   peut la couper. Dans cet ordre :
   1. les règles seules, d'abord : adresses, épellation, code postal (17 cas,
      0 contrôle abîmé, sans liste à entretenir) ;
   2. puis le vocabulaire livré, après relecture de la liste pour écarter
      toute forme qui pourrait être un mot ordinaire.
4. **Avant tout passage en défaut** : ajouter au jeu de vraies dictées
   québécoises, faites par l'utilisateur ou par les volontaires du lot 4, avec
   des contrôles jamais vus des règles ni de la liste. Il faudra **zéro
   contrôle abîmé** sur ces nouvelles lignes.
5. **La priorité de l'utilisateur est la bonne.** Un modèle de transcription
   affiné sur le français québécois agit à la source. Le jeu d'évaluation
   pourra le mesurer : il suffit de transcrire à nouveau ses phrases avec ce
   modèle (`PolishSetTranscriptionHostTest`).

Ce que les chiffres ne permettent pas de conclure : la qualité d'un vrai Qwen
(seulement son plafond), sa latence et sa mémoire, le comportement sur une
vraie voix du Québec, et l'innocuité des règles et du vocabulaire sur des
phrases hors du jeu.

## Procédure de mesure sur le téléphone

Elle reprend celle de l'étape 3.3, vérifiée dans le code (identifiants des
modèles, chemins, arguments, noms de fichiers). Elle n'a jamais été exécutée.

**Prérequis.** Un téléphone arm64, le débogage USB activé, et `adb devices`
qui le liste. Le JDK 17 et le SDK Android sont requis sur l'ordinateur.

1. **Installer l'application de débogage et l'APK de test**, depuis
   `android/` :

   ```bash
   ./gradlew installDebug installDebugAndroidTest
   ```

   Si une version release est installée, l'installation échoue (signature
   différente). Il faut alors désinstaller l'application, ce qui supprime les
   modèles téléchargés : faire l'étape 2 après celle-ci.
2. **Télécharger les LLM dans l'application.** Ouvrir Réglages → Nettoyage du
   texte → « Petit LLM local + garde-fou ». Télécharger Qwen2.5 0.5B Instruct
   (0,5 Go) et Qwen2.5 1.5B Instruct (1,6 Go), puis **remettre le nettoyage
   sur « Désactivé »**. Pour Gemma 3 1B (facultatif) : le dépôt Hugging Face
   est à accès restreint. Il faut accepter la licence sur un ordinateur, puis :

   ```bash
   adb push gemma3-1b-it-int4.task /sdcard/Android/data/io.github.remtav.dictee/files/llm/
   ```

3. **Enregistrer**, un modèle à la fois. Compter plusieurs minutes : 132
   segments pour l'approche 3, 22 pour la combinaison.

   ```bash
   adb shell am instrument -w \
     -e class io.github.remtav.dictee.LlmPolishRecordingTest \
     -e llmModel llm-qwen2.5-0.5b-instruct-q8 \
     io.github.remtav.dictee.test/androidx.test.runner.AndroidJUnitRunner
   ```

   Variantes :
   - `-e llmModel llm-qwen2.5-1.5b-instruct-q8` ;
   - `-e llmModel /sdcard/Android/data/io.github.remtav.dictee/files/llm/gemma3-1b-it-int4.task -e llmTemplate gemma -e llmName gemma3-1b` ;
   - garde-fou avec proximité : ajouter `-e nearMatch true -e llmName qwen05-proximite`.
     Sans changer `llmName`, les fichiers précédents sont écrasés.

   Suivi : `adb logcat -s LlmPolishRecording`. Le garde-fou utilise le
   lexique livré (`ShippedVocabulary`), comme s'il était livré. Sans
   l'argument `llmModel`, le test est ignoré : la CI ne charge jamais
   MediaPipe.
4. **Récupérer les fichiers** :

   ```bash
   adb pull /sdcard/Android/data/io.github.remtav.dictee/files/polissage/ .
   ```

   Le test écrit, pour un nom `<nom>` (`qwen05`, `qwen15` par défaut) :
   - `llm-<nom>-prompt-large.tsv` : approche 3, après garde-fou ;
   - `llm-<nom>-prompt-large-sans-gardefou.tsv` : les mêmes sorties, avant
     garde-fou (ce que le garde-fou a empêché) ;
   - `llm-<nom>-combinaison.tsv` : approche 4 raccordée (latence du segment
     entier, presque nulle quand le LLM n'est pas appelé) ;
   - `memoire-<nom>.txt` : PSS, VmRSS et VmHWM (pointe) avant chargement,
     après chargement et après les passages, durée de chargement, nombre
     d'appels de la combinaison.
5. **Déposer** les trois `llm-*.tsv` dans
   `android/app/src/test/resources/polissage/sorties/`, à côté des
   simulations. Ne pas les renommer : le nom de l'approche vient de leur
   en-tête.
6. **Noter** :

   ```bash
   cd android && ./gradlew testDebugUnitTest --tests '*PolishEvaluationTest*'
   ```

   Lire `android/app/build/reports/polissage/resultats.md`. Les fichiers du
   téléphone y apparaissent avec leur qualité réelle et une latence marquée
   « appareil », suivie du nombre de segments au-delà de 4 s (par exemple
   `850.0 / 3900.0 (appareil, 3/132 > 4 s)`). Les mémoires se lisent dans
   `memoire-<nom>.txt`.
7. **Reporter** les résultats dans le tableau ci-dessous, puis dans
   `android/DECISIONS.md`.

### Tableau à remplir

| Modèle | Approche | Corrigés / 79 | Modifiés à tort | Contrôles abîmés / 53 (mots rares / 19) | Latence p50 / p95 (ms) | Segments > 4 s | Appels LLM | PSS après chargement − avant (Mo) | Pointe VmHWM (Mo) | Chargement (ms) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Qwen2.5 0,5B | 3, garde-fou | | | | | | 132 | | | |
| Qwen2.5 0,5B | 3, sans garde-fou | | | | (même passage) | | 132 | (même) | (même) | (même) |
| Qwen2.5 0,5B | 4, combinaison | | | | | | | (même) | (même) | (même) |
| Qwen2.5 1,5B | 3, garde-fou | | | | | | 132 | | | |
| Qwen2.5 1,5B | 3, sans garde-fou | | | | (même passage) | | 132 | (même) | (même) | (même) |
| Qwen2.5 1,5B | 4, combinaison | | | | | | | (même) | (même) | (même) |
| Gemma 3 1B (facultatif) | 3 et 4 | | | | | | | | | |

Pour lire ce tableau : la ligne « sans garde-fou » dit combien de mots
corrects le modèle aurait abîmés. La ligne « garde-fou » ne peut pas dépasser
36 cas (plafond simulé avec le lexique livré, sans proximité), et la
combinaison ne peut pas dépasser 56, avec ou sans proximité.

## Comportement par défaut

### Polissage local : inchangé

- `android/app/src/main/java/io/github/remtav/dictee/text/polish/` (règles,
  vocabulaire livré) et
  `android/app/src/main/java/io/github/remtav/dictee/cleanup/eval/`
  (garde-fou assoupli, prompt élargi, approches 3 et 4) ne sont appelés par
  aucun autre fichier de l'application. Aucun appel n'existe depuis
  `DicteeApp`, `TextPipeline` ou `SettingsActivity`, et aucun réglage ne les
  active.
- `DefaultPathUnchangedTest`
  (`android/app/src/test/java/io/github/remtav/dictee/DefaultPathUnchangedTest.kt`)
  le vérifie en CI. Le pipeline par défaut laisse intactes des phrases que les
  règles transformeraient. Aucun fichier hors de ces deux paquets ne cite leurs
  classes, et les règles ne dépendent pas du paquet d'évaluation.
- `CleanupMode` reste `OFF` par défaut. Le jeu d'évaluation n'est copié que
  dans l'APK de test.

### Changements de l'itération faits à la demande de l'utilisateur (« friperie »)

Ils sont hors de l'évaluation, et un seul change le comportement par défaut.

- **Langue transmise à Qwen3-ASR : changement du comportement par défaut,
  non mesuré.** Quand une seule langue de dictée est choisie, Qwen3-ASR reçoit
  désormais son nom (« French »), comme Whisper reçoit son code. Le choix
  initial étant « Français (Québec) » seul, **toute personne qui utilise
  Qwen3-ASR sans toucher aux langues dicte maintenant en français forcé.**
  L'effet n'a pas été mesuré : la mesure sur l'ordinateur a été interrompue.
  Risque possible : un mot anglais dans une phrase française pourrait être
  francisé. Code : `DictationLanguages.qwen3Language`, `AppSettings.engineOptions`,
  `SherpaEngine`. Parakeet, le modèle recommandé, n'a pas d'entrée de langue
  et n'est pas touché.
- **Polissage par le serveur** : nouvel interrupteur, **activé par défaut**.
  Le client envoie `polish=true` comme avant ; seul l'interrupteur coupé
  change quelque chose.
- **Garde-fou du nettoyage par LLM durci, prompt précisé** : effet seulement
  quand le nettoyage est sur « Petit LLM local + garde-fou », qui n'est pas le
  défaut.
- **Textes affichés** : note « dernier recours » sur la carte de Whisper,
  raison de recommandation de Whisper (bengali, ourdou) et textes d'aide des
  réglages. Seul l'affichage change, pas la transcription.

## La décision à prendre

| Option | Ce que ça donne | Ce que ça coûte |
| --- | --- | --- |
| **A. Rien de plus** (recommandé pour cette itération) | Comportement actuel, aucun risque nouveau sur les mots | « coiffe rimouski point com » reste en toutes lettres |
| **B. Option « règles » désactivée par défaut**, puis vocabulaire livré | Adresses dites et épellations recollées, noms courants corrigés pour qui l'active | Un réglage de plus ; une liste à entretenir ; non validé sur de vraies dictées |
| **C. Mesurer le LLM sur le téléphone** (procédure ci-dessus) | Vraies sorties et coût réel de Qwen2.5 | Une à deux heures ; ne peut pas renverser la conclusion sur la qualité (plafond 56 sur 79) |
| **D. Garder la langue forcée sur Qwen3-ASR ou revenir à la détection automatique** | Garder : moins de mots devinés en anglais (non mesuré) | Garder : risque de franciser les mots anglais dictés en français |

À trancher aussi, hors de ce lot : le défaut de « Polissage par le serveur »
(activé aujourd'hui) et la priorité donnée au modèle de transcription affiné
sur le français québécois.
