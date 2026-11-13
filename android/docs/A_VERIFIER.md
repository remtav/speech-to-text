# À vérifier par un humain — Kalmia Blabla (issue #15)

> **Avant toute diffusion de l'application à d'autres personnes** : la validation
> juridique de la collecte du lot 4 (durée de conservation de 2 ans, consentement
> pour un enregistrement de voix, texte de `CONTRIBUTION.md`, au regard de la
> Loi 25) reste à faire. Tant que l'application n'est installée que par son
> auteur, aucune collecte réelle n'a lieu.

Cette liste est tenue par l'orchestrateur de l'issue #15. Elle réunit, lot par
lot, ce qu'aucun agent n'a pu vérifier lui-même : écouter un extrait, juger un
rendu sur un vrai téléphone, mesurer sur l'appareil, valider un texte. Pour
chaque point : ce qu'il faut regarder, le choix fait en attendant, et où
corriger si ça ne convient pas. Les chemins partent de la racine du dépôt.

## Lot 1 — marque, accueil, langues, modèle recommandé

### 1. Les six extraits « québécois » le sont-ils vraiment ?

- **Quoi faire** : écouter `android/app/src/main/assets/reference/frqc1.wav` à
  `frqc6.wav` (aussi lisibles dans l'application, écran « Comparer les modèles »).
- **Pourquoi** : ils ont été choisis sur le seul champ d'accent « Français du
  Canada » de Common Voice 22.0, déclaré par les contributeurs. Ce champ ne
  distingue pas le Québec des autres provinces, et personne ne les a écoutés.
- **Où corriger** : retirer ou remplacer un fichier et sa ligne dans
  `android/app/src/main/assets/reference/references.tsv` (152 autres extraits
  candidats dans la partition `test`, méthode dans `android/DECISIONS.md`,
  section « étape 1.2 »).

### 2. La mesure confirme-t-elle ton observation sur Parakeet ?

- **Résultat mesuré** (6 extraits, 59 mots, sur l'hôte de CI, pas sur le
  téléphone) : Parakeet 6,8 %, Whisper large-v3-turbo 10,2 %, Qwen3-ASR 13,6 %
  d'erreurs de mots. Une des erreurs de Whisper est un artefact de notation
  (« 18 » contre « dix-huit ») : sans elle, Whisper et Parakeet sont au coude à
  coude. L'échantillon est trop petit pour conclure ; les phrases viennent de
  Wikipédia et de romans, donc la mesure porte sur l'accent, pas sur le
  vocabulaire d'ici.
- **Choix fait** : Parakeet reste recommandé pour le français québécois. La
  raison affichée dit : « il a fait le moins d'erreurs des trois, mesuré sur
  six courts extraits (59 mots) : un indice, pas une preuve ».
- **Où corriger** : la table et le libellé de la raison sont dans
  `android/app/src/main/java/io/github/remtav/dictee/stt/ModelRecommendation.kt`.

### 3. Rendu de l'apparence générique sur le téléphone, en clair et en sombre

Le thème Kalmia (palette, polices Newsreader et IBM Plex Mono, angles droits,
logo) a été retiré à ta demande le 2026-11-13. L'application a retrouvé
l'apparence d'origine : thème Material bleu, polices du système, coins
arrondis, icône au micro blanc sur fond bleu. Le nom « Kalmia Blabla » et les
textes n'ont pas changé.

| Point | Où corriger |
| --- | --- |
| Clavier à l'écoute : micro rouge avec l'icône d'arrêt, lisible en clair et en sombre | `android/app/src/main/res/layout/keyboard_view.xml`, `res/color/mic_bg.xml` |
| Touches : coins de 12 dp, symboles de 24 sp ; largeur d'environ 36 à 40 dp chacune (huit sur une rangée), sous les 48 dp visés. Confortable au pouce ? | `keyboard_view.xml`, style `KeyButton` dans `res/values/themes.xml` |
| Couleurs : seuls le bleu d'origine et le rouge d'erreur viennent de l'ancienne version ; les autres teintes ont été écrites à la main par l'agent. Rien de violet ni de mal assorti en sombre ? | `res/values/colors.xml`, `res/values-night/colors.xml` |
| Changer Clair / Sombre dans les Réglages, puis rouvrir le clavier dans une autre application : il doit suivre | `android/app/src/main/java/io/github/remtav/dictee/ime/DicteeImeService.kt` |
| Erreurs en rouge avec icône (échec de téléchargement, comparaison, test du serveur) | `markError()` dans `android/app/src/main/java/io/github/remtav/dictee/ui/Ui.kt` |
| Écran d'accueil sans logo, icône de lancement d'origine | `ui/SetupActivity.kt`, `res/drawable/ic_launcher_foreground.xml` |

### 4. Le texte d'accueil dit-il ce que tu veux dire ?

- **Texte actuel** : chaîne `setup_intro` de
  `android/app/src/main/res/values/strings.xml` (quatre lignes : conçu au
  Québec, la voix reste sur le téléphone sauf serveur distant choisi par toi,
  ni compte ni publicité ni télémétrie, code source public et modèles
  d'éditeurs étrangers).
- **Écart avec ta formulation, à trancher** : l'issue dit « code source
  ouvert ». La revue a écrit « code source public : chacun peut le lire et le
  vérifier », parce que le dépôt n'a aucun fichier de licence : sans licence,
  le code est lisible mais pas « ouvert » au sens habituel. Ajouter un fichier
  `LICENSE` à la racine rendrait « ouvert » exact ; le mot se rétablit alors
  dans `strings.xml`, `android/README.md` et le texte de release de
  `.github/workflows/android.yml`.
- **Où corriger** : `android/app/src/main/res/values/strings.xml`.

### 5. La table de recommandation te convient-elle ?

- **Règle** : Parakeet si toutes les langues choisies sont parmi ses 25 ;
  sinon Qwen3-ASR s'il les couvre toutes ; sinon Whisper. Bengali et ourdou ne
  sont couverts que par Whisper (absents de la fiche de Qwen3-ASR). « Chinese »
  sur la fiche de Qwen3-ASR a été lu comme le mandarin.
- **Non mesuré** : la qualité réelle de Whisper en bengali et en ourdou, et
  l'effet de la langue forcée sur Whisper quand une seule langue est choisie.
- **Où corriger** : `android/app/src/main/java/io/github/remtav/dictee/stt/ModelRecommendation.kt`.

## Lot 2 — ponctuation dictée et tutoriel « Comment dicter »

### 6. Les commandes en usage réel : manques et faux positifs

- **Quoi faire** : dicter de vrais messages avec ponctuation pendant quelques
  jours, avec le modèle que tu utilises. Commencer par l'exemple de l'issue,
  d'un trait puis avec des pauses : « il a dit deux-points ouvrir les
  guillemets bonjour fermer les guillemets » doit donner `Il a dit : « bonjour »`.
- **Règle retenue** : dans le doute, pas de commande (un faux positif abîme le
  texte, un manqué laisse seulement les mots). Liste initiale de l'issue, sans
  ajout de signes.
- **Cas à surveiller** :
  - « deux points » sans trait d'union : reconnu seulement s'il est détaché
    (ponctuation, bord de segment ou autre commande à côté). Regarder ce que
    ton modèle écrit vraiment : « deux-points » ou « deux points ».
  - Une commande en tête de phrase suivie d'une virgule reste une commande :
    « Ouvrez les guillemets, s'il vous plaît » donne `« S'il vous plaît`.
    Limite connue, non corrigée.
  - Une commande coupée par une pause (« point » … « d'interrogation ») n'est
    pas vue.
  - Si le modèle écrit lui-même le signe (`:` au lieu de « deux-points »), ce
    signe garde l'espacement du modèle : seuls les signes dictés sont
    typographiés.
  - Phrases ordinaires contenant « deux points », « point d'interrogation »,
    « guillemets », « parenthèse » : elles doivent rester du texte (92 phrases
    pièges sont testées).
- **Où corriger** : listes de mots bloquants dans
  `android/app/src/main/java/io/github/remtav/dictee/text/VoiceCommands.kt` ;
  formulations acceptées dans `text/CommandTable.kt` ; ajouter chaque cas
  trouvé à `FalsePositiveTest` dans
  `android/app/src/test/java/io/github/remtav/dictee/TextTest.kt`.

### 7. Typographie du Québec, de France et anglaise

- **Quoi regarder** : au Québec, espace insécable devant `:` et dans les « »,
  rien devant `;` `?` `!` (règle de l'OQLF, confirmée sur la Vitrine
  linguistique). Avec « Français (France) » coché sans le Québec : espace
  insécable aussi devant `;` `?` `!`. En anglais : aucune espace, guillemets “ ”.
  Si Québec et France sont cochés ensemble, la règle du Québec s'applique.
- **Choix à confirmer** : l'espace insécable ordinaire (U+00A0) partout, pas
  l'espace fine (U+202F), parce que celle-ci manque dans certaines polices.
  Vérifier l'affichage dans ta messagerie et ton courriel.
- **Non vérifié** : la règle de France vient de sources de second rang ; le
  *Lexique de l'Imprimerie nationale* n'a pas pu être consulté.
- **Où corriger** : `sign()` dans
  `android/app/src/main/java/io/github/remtav/dictee/text/TextAssembler.kt`.

### 8. Écran « Comment dicter » sur le téléphone, clair et sombre

| Point | Où corriger |
| --- | --- |
| Longueur de l'écran (jusqu'à 8 cartes, puis la liste complète) | `android/app/src/main/java/io/github/remtav/dictee/ui/HowTo.kt` (`HowToExamples`) |
| Lisibilité des phrases dites quand elles passent sur plusieurs lignes | `ui/HowToActivity.kt` |
| Moment de l'ouverture automatique : une seule fois, quand le clavier est activé, le micro autorisé et un modèle prêt | `SetupProgress` dans `ui/HowTo.kt`, appelé par `ui/SetupActivity.kt` |
| Bouton d'aide du clavier sur la ligne d'état : reste-t-il assez de place au texte d'état sur un téléphone étroit ? | `android/app/src/main/res/layout/keyboard_view.xml` |
| Le lien « vocabulaire » fait-il bien défiler les Réglages au bon endroit ? | `ui/SettingsActivity.kt` |
| Texte des exemples et de la ligne de rappel | `android/app/src/main/res/values/strings.xml` (chaînes `howto_*`) ; les exemples eux-mêmes viennent de `text/CommandTable.kt` |

### 9. Commandes avec ton serveur (polissage actif)

- **Quoi faire** : avec le modèle « Serveur », dicter l'exemple de l'issue,
  puis une phrase longue avec « point d'exclamation » au milieu, puis
  « nouvelle ligne » au milieu d'une dictée longue.
- **Ce qui est attendu** : les commandes sont interprétées sur le téléphone
  après le polissage. Limite documentée : dans une dictée longue, le polissage
  peut remplacer lui-même la commande par le signe, qui garde alors les
  espaces du LLM et non celles du Québec. Jamais de doublon.
- **Où corriger** : hors portée de cette itération (prompt et garde-fou du
  serveur, `app/polish.py`). La limite est décrite dans `android/README.md`,
  section « Dicter la ponctuation ».

## Lot 3 — polissage local, et demandes ajoutées en cours de route

### 10. Lire le rapport et décider de ce qui sera livré

- **Quoi lire** : `android/docs/POLISSAGE_LOCAL.md`, section « La décision à
  prendre ».
- **Recommandation du rapport** : ne livrer aucun LLM de polissage. Sur le jeu
  de 132 paires, « règles + vocabulaire » corrige 55 cas sur 79 sans abîmer
  aucune des 53 phrases de contrôle ; un LLM parfait (simulé) derrière le
  garde-fou n'en ajoute qu'un. Aucun LLM n'a réellement tourné : ces chiffres
  sont des plafonds et des simulations.
- **Réserves** : la liste de vocabulaire a été écrite en connaissant le jeu
  (sans les formes tirées du jeu : 36 cas corrigés, 6 modifiés à tort) ; les
  transcriptions viennent d'une voix de synthèse à accent de France ;
  « coifferimouski.com » n'est rétabli que si la transcription a gardé toutes
  les lettres du nom.
- **Choix fait en attendant** : rien n'est branché ni activé.
- **Où corriger** : pour proposer « règles + vocabulaire » en option, brancher
  `android/app/src/main/java/io/github/remtav/dictee/text/polish/PolishRules.kt`
  et `ShippedVocabulary.kt` derrière un nouveau réglage ; relire d'abord la
  liste de `ShippedVocabulary.kt` (une forme qui serait un mot ordinaire
  abîmerait du texte) et ajouter de vraies dictées québécoises au jeu
  `android/app/src/test/resources/polissage/jeu-evaluation.tsv`.
- **Facultatif** : mesurer le LLM sur le téléphone avec la procédure du
  rapport. Elle n'a jamais été exécutée : signaler toute étape fausse.

### 11. Le modèle Whisper affiné sur le français québécois

- **Ce que c'est** : `ele-sage/whisper-large-v3-turbo-fr-quebecois` (MIT),
  converti et publié sous ton compte :
  https://huggingface.co/remiavelo/sherpa-onnx-whisper-large-v3-turbo-fr-quebecois-int8.
  Relire la fiche de ce dépôt : elle est sous ton nom.
- **Quoi faire** : le télécharger depuis l'écran de configuration, dicter
  « friperie » seul et les autres mots d'ici qui échouaient, puis le comparer
  aux autres dans « Comparer les modèles » avec ta voix.
- **Mesuré ici** : 5 erreurs sur 59 mots sur les six extraits québécois
  (Parakeet 4, Whisper turbo 6, Qwen3-ASR 8). Trop peu pour conclure ; les
  mots isolés n'ont pas été mesurés. Ce score est peut-être flatté : le modèle
  a été entraîné en partie sur Common Voice « Français du Canada », d'où
  viennent ces six extraits ; il a pu les entendre. Le WER de 6,65 % est celui annoncé par
  l'auteur sur son propre jeu.
- **À savoir** : même taille et même coût que Whisper turbo, donc aussi lent ;
  français seulement. Parakeet reste le modèle recommandé.
- **Non vérifié** : le téléchargement sans compte depuis un téléphone (le
  dépôt est public d'après Hugging Face, mais l'essai fait ici passait par ton
  jeton) ; les conditions des données d'entraînement de l'auteur pour la
  redistribution d'un dérivé.
- **Où corriger** : `android/app/src/main/java/io/github/remtav/dictee/stt/ModelCatalog.kt`
  (entrée `whisperQuebec`) ; la recommandation est dans `stt/ModelRecommendation.kt`.
- **Jeton** : le jeton Hugging Face est enregistré sur ton environnement
  « Default » de Claude Code ; le retirer s'il ne sert plus.

### 12. Vitesse de Whisper (turbo et québécois)

- **Changé pour tous** : avec un modèle Whisper, plus de texte provisoire
  pendant qu'on parle (il retardait le résultat final). Vérifier dans le
  clavier que le texte arrive plus tôt après la pause.
- **À essayer** : Réglages > Performance > « Silence ajouté pour Whisper ».
  Valeur par défaut 1 000 (inchangée). Dans « Comparer les modèles », faire
  une exécution à jeter puis une par valeur (1 000, 600, 300), avec
  « friperie » seul, une phrase de 5 s et une dictée de 15 s ; regarder le
  texte autant que le temps. Ne garder 600 que sans répétition ni mot manquant
  sur environ cinq essais ; à 300, des phrases répétées ont été observées.
- **Mesuré sur ordinateur seulement** (x86-64, machine partagée) : ce sont des
  tendances, pas la latence du téléphone. Procédure complète, threads compris :
  `android/docs/ACCELERER_WHISPER.md`.
- **Si une dictée s'arrête toute seule après une rotation de l'écran** : la
  dictée s'arrête quand le clavier reste caché plus de 4 secondes. Sur un
  émulateur chargé, un test de la CI a échoué une fois ainsi ; à juger sur le
  téléphone (`ime/DicteeImeService.kt`).
- **Où corriger** : `stt/WhisperTuning.kt` (texte provisoire),
  `settings/AppSettings.kt` (`whisperTailPaddings`, défaut).

### 13. Langue transmise à Qwen3-ASR (changement de défaut)

- **Ce qui a changé** : quand une seule langue est cochée — c'est le cas du
  choix initial, « Français (Québec) » —, Qwen3-ASR reçoit maintenant cette
  langue, comme Whisper. Parakeet n'accepte aucune langue : il devine toujours,
  ce qui explique qu'un mot dit seul parte vers l'anglais.
- **Mesuré par la revue** (ordinateur, 7 extraits français, 75 mots) :
  6 erreurs avec « French » forcé contre 8 en détection automatique. Pas de
  dégradation ; trop peu pour parler de gain, et les mots dits seuls n'ont pas
  été mesurés.
- **Quoi faire** : avec Qwen3-ASR, dicter des mots d'ici dits seuls, puis une
  phrase française contenant quelques mots anglais ; vérifier que rien n'est
  francisé à tort.
- **Où corriger** : `DictationLanguages.qwen3Language` dans
  `android/app/src/main/java/io/github/remtav/dictee/stt/DictationLanguages.kt`
  et `AppSettings.engineOptions`.

### 14. Polissage : ce qui peut encore repasser sur le texte

- **Polissage par le serveur** : nouvel interrupteur dans les Réglages,
  activé par défaut (comportement inchangé). Le couper insère le texte brut.
  À décider : le laisser activé ou le couper par défaut (une ligne,
  `AppSettings.remotePolish`).
- **Petit LLM local** : toujours désactivé par défaut. Son garde-fou refuse
  maintenant tout mot changé, même d'une lettre, ainsi qu'une majuscule
  abaissée en cours de phrase, un nombre ou une lettre épelée retirés comme
  « répétition », et un point d'interrogation supprimé. Il rejettera donc plus
  souvent la sortie du LLM ; le texte nettoyé par règles est alors gardé.
  Limites connues : une virgule qui change le sens, « un un ». Pour le confirmer : l'activer,
  dicter des phrases longues contenant des mots d'ici, vérifier qu'aucun mot
  ne change, puis le remettre sur « Désactivé ».
- **Enquête « friperie »** : arrêtée à ta demande, sans cause trouvée. Le cas
  exact n'a jamais été reproduit, faute d'enregistrement. Chronologie dans
  `android/DECISIONS.md`, section « pouvoir couper le polissage ».

### 15. Un vrai banc d'essai avec ta voix (proposé, non fait)

- **Idée** : mesurer les quatre modèles sur des mots québécois dits seuls et
  en phrases, avec ta voix. « Comparer les modèles » (bouton « Enregistrer une
  phrase ») et le test de lecture du lot 4 en couvrent une partie ; une liste
  de mots isolés d'ici serait une petite issue à part.

## Lot 4 — test de lecture en français québécois

Rappel : la validation juridique en tête de ce fichier précède toute diffusion
de l'application à d'autres personnes. Tant que tu es le seul à l'installer,
aucune collecte réelle n'a lieu.

### 16. Le texte de lecture est-il naturel ?

- **Quoi lire** : `android/app/src/main/assets/lecture/lecture-qc-1.txt`
  (9 paragraphes, 224 mots, avec « friperie »).
- **À regarder de près** : « on a pogné tous les feux rouges », « d'Hochelaga »,
  « Y'a rien là ». Quatre formes (« chu », « tsé », « à matin », « pogner »)
  n'ont été vérifiées que dans des sources secondaires ; les autres mots l'ont
  été dans Usito.
- **Équivalence à confirmer** : « je suis » et « chu » comptent comme le même
  mot dans le calcul, y compris dans « je suis allé » (premier paragraphe).
- **Où corriger** : le fichier ci-dessus. Tout changement du texte demande un
  nouveau numéro de version (`lecture-qc-2`), pour ne pas mélanger les
  résultats ; les variantes d'écriture acceptées sont dans
  `lecture-qc-1.equivalences.tsv`, à côté.

### 17. Faire le test toi-même, puis avec deux ou trois personnes du Québec

- **Parcours** : carte « Aider à améliorer la dictée en français québécois »
  (écran principal, une fois la configuration finie, ou Réglages) → question
  « Parlez-vous un français québécois ? » → lecture paragraphe par paragraphe
  → score par modèle.
- **Quoi regarder** : taille et aération du texte à lire ; arrêt à 20 secondes ;
  lisibilité des mots surlignés, en clair et en sombre ; longueur de l'écran
  de résultat ; temps de calcul quand plusieurs modèles sont installés (ils
  sont chargés l'un après l'autre) ; la suggestion « un autre modèle vous
  comprend nettement mieux ».
- **Réponse « non »** : vérifier qu'aucun test n'est proposé ensuite, et que la
  réponse se corrige dans les Réglages.
- **Choix à confirmer** : le serveur distant est exclu du test (il enverrait
  la voix sur le réseau), même s'il est le modèle actif ; « nettement mieux » veut dire au moins 5 points et
  30 % d'erreurs en moins.
- **Où corriger** : écran dans
  `android/app/src/main/java/io/github/remtav/dictee/ui/ReadingTestActivity.kt` ;
  seuils dans `reading/ReadingThresholds` ; textes `contrib_*` dans
  `android/app/src/main/res/values/strings.xml`.

### 18. Le seuil de partage (20 %)

- **Règle** : le partage n'est proposé que si le taux d'erreur dépasse 20 %
  avec le modèle recommandé, sur le texte lu en entier.
- **Quoi faire** : après quelques essais réels, voir si 20 % sépare bien les
  lectures mal comprises des autres.
- **Où corriger** : `SHARE_WER_THRESHOLD_PERCENT` dans le paquet
  `io.github.remtav.dictee.reading`.

### 19. Recevoir un vrai courriel de contribution

- **Quoi faire** : obtenir un score sous 80 % (lire vite ou mal exprès),
  cocher le consentement, « Préparer le courriel », puis « Envoyer par
  courriel » avec Gmail et avec une autre application de courriel.
- **Quoi vérifier** : le destinataire (`remi@kalmia.ca`), l'objet, le corps,
  la pièce jointe présente et lisible ; dans l'archive, `contribution.json` et
  un fichier WAV par paragraphe ; aucun identifiant, la date au jour près.
- **Risques connus, non vérifiés** : certaines applications de courriel
  pourraient ignorer la pièce jointe ; une application qui ne lirait la pièce
  jointe qu'après la fermeture de l'écran du test la trouverait supprimée
  (l'archive est effacée à la fermeture de l'écran, par « Ne pas partager »,
  par « Refaire le test », et au démarrage si elle a plus de 24 heures).
  Essayer en fermant l'écran du test juste après avoir appuyé sur « Envoyer ».
- **Corrigé par la revue** : l'archive n'est jamais incluse dans une
  sauvegarde ni dans un transfert vers un nouveau téléphone ; la date écrite
  dans l'archive est celle affichée avant le consentement ; le champ région
  demande au clavier de ne pas retenir ce qui est tapé (à vérifier : la région
  ne doit pas réapparaître dans les suggestions du clavier).
- **Choix à confirmer** : seule la transcription du modèle recommandé part ;
  la région est un champ libre de 60 caractères au plus.
- **Où corriger** : contenu de l'archive dans `reading/ContributionArchive.kt`
  (la liste affichée à l'écran vient de la même description) ; envoi dans
  `ui/ContributionShare.kt` ; adresse et durée dans `reading/Contribution`.

### 20. Le consentement et la politique publique

- **Quoi lire** : l'écran de partage dans l'application et
  `android/docs/CONTRIBUTION.md`, qui tient lieu de politique publique. Un
  test vérifie qu'ils annoncent le même destinataire et la même durée.
- **À valider** : l'engagement « ne sert à rien d'autre sans un nouveau
  consentement » ; la durée de 2 ans au plus ; le fait que la destruction se
  fait à la main dans ta boîte de courriel.
- **Questions laissées « à préciser »** : qui d'autre que toi a accès à
  `remi@kalmia.ca` ; où cette messagerie est hébergée ; qui est le responsable
  de la protection des renseignements personnels ; le cas des mineurs
  (l'application ne demande pas l'âge) ; la voix comme renseignement sensible
  ou biométrique.
- **Non vérifié** : le texte consolidé de la loi sur Légis Québec, qui a
  refusé l'accès à l'agent. Il a lu le texte officiel de la Loi 25 et quatre
  pages de la Commission d'accès à l'information. Ce n'est pas un avis
  juridique.

### 21. Transfert des réglages vers un nouveau téléphone (hors du lot, à décider)

- **Constat de la revue** : sous Android 12 ou plus, les réglages de
  l'application, dont la réponse à « Parlez-vous un français québécois ? »,
  peuvent être copiés lors d'un transfert d'appareil à appareil. Seul le
  dossier des contributions en est exclu.
- **À décider** : exclure aussi les réglages.
- **Où corriger** : `android/app/src/main/res/xml/data_extraction_rules.xml`.
