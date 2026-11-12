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

### 3. Rendu du thème Kalmia sur le téléphone, en clair et en sombre

Reconnaît-on Kalmia au premier coup d'œil ? À regarder en particulier :

| Point | Où corriger |
| --- | --- |
| Lisibilité des touches `, . ? !` (IBM Plex Mono 24 sp au lieu du serif) | `android/app/src/main/res/layout/keyboard_view.xml`, styles dans `res/values/themes.xml` |
| Bouton micro carré de 100 dp avec ombre décalée (ombre claire en thème sombre) : trop massif ? | `keyboard_view.xml`, `res/drawable/kalmia_ombre_bouton.xml` |
| Libellés de boutons en majuscules espacées : passent-ils bien à la ligne quand ils sont longs ? | style `TextAppearance.Kalmia.Button` dans `res/values/themes.xml` |
| Filet des cartes et piste du curseur, volontairement discrets (contraste 1,48 : 1) | `colorOutlineVariant` dans `res/values/themes.xml` |
| Icône de lancement (claire, sombre, monochrome d'Android 13) et logo de l'accueil | `res/drawable/ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`, `kalmia_logo.xml` |
| Champs de saisie : soulignement Material conservé | `android/app/src/main/java/io/github/remtav/dictee/ui/Ui.kt` |
| Largeur des touches : environ 36 à 40 dp chacune (huit touches sur une rangée), sous les 48 dp visés ; hauteur de 48 dp respectée. Confortable au pouce ? Sinon, passer à deux rangées | `android/app/src/main/res/layout/keyboard_view.xml` |
| Changer Clair / Sombre dans les Réglages, puis rouvrir le clavier dans une autre application : il doit suivre | `android/app/src/main/java/io/github/remtav/dictee/ime/DicteeImeService.kt` |
| Erreurs en rouge avec icône (échec de téléchargement, comparaison, test du serveur) : rendu correct ? | `markError()` dans `android/app/src/main/java/io/github/remtav/dictee/ui/Ui.kt` |
| Couleurs elles-mêmes (reprises telles quelles de la source Kalmia) | `res/values/colors.xml`, `res/values-night/colors.xml` |

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
| Lisibilité des phrases dites en IBM Plex Mono quand elles passent sur plusieurs lignes | `ui/HowToActivity.kt` |
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
