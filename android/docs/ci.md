# CI Android : quels jobs tournent, et comment les régler

Workflow : [`.github/workflows/android.yml`](../../.github/workflows/android.yml) (issue #76, PR #93).
Cette page décrit le workflow tel qu'il est ; en cas d'écart, c'est le workflow qui fait foi.

## Quand le workflow démarre

- **Pull request** ou **push sur `main`** qui touche `android/**` ou `.github/workflows/android.yml`.
- **Lancement manuel** : Actions → Android → *Run workflow*.

Une PR qui ne touche rien sous `android/` ne lance pas ce workflow du tout.

## Sur une pull request : le job « changes » choisit

Le premier job, « Pick the jobs from the changed files », compare les fichiers changés à des
filtres (`dorny/paths-filter`) et écrit son choix dans le résumé du run (tableau
« Jobs picked from the changed files »). Un job sauté compte comme réussi pour les vérifications
requises.

Réserve pour le jour où des vérifications requises seront ajoutées : quand l'émulateur est sauté,
GitHub rapporte un seul job au nom non développé, « Instrumented keyboard tests (emulator
${{ matrix.shard }}/3) », au lieu des trois « 1/3 », « 2/3 », « 3/3 ». Exiger ces trois noms
bloquerait les PR qui ne lancent pas l'émulateur (documentation comprise).

| Fichiers changés | Build et tests unitaires | Tests des modèles | Émulateur (3 parts) |
|---|---|---|---|
| Documentation seule : `android/**/*.md`, `android/docs/**` | non | non | non |
| Code de transcription : `audio/`, `dictation/`, `stt/`, `text/`, `ui/CompareActivity.kt`, `assets/reference/` | oui | oui | oui (ce sont aussi des fichiers de `src/main/**`) |
| `src/test/java/…/HostModelTest.kt` | oui | oui | non |
| Reste de l'application : `src/main/**`, `src/debug/**` | oui | non | oui |
| Tests instrumentés : `src/androidTest/**`, `src/test/resources/polissage/**` | oui | non | oui |
| Autres tests JVM (`src/test/**` hors cas ci-dessus) | oui | non | non |
| Build : `android/*.gradle.kts`, `android/app/*.gradle.kts`, `gradle.properties`, `gradle/**` | oui | oui | oui |
| `android/keystore/**` | oui | non | oui |
| Le workflow lui-même | oui | oui | oui |

`.github/scripts/test_durations.py` est un cas à part : il n'est pas dans les chemins du
déclencheur (`on.pull_request.paths`), donc une PR qui ne change que ce script **ne lance pas** le
workflow Android. Changé avec d'autres fichiers sous `android/`, il compte comme du code : le build
tourne, et l'émulateur aussi (il est dans le filtre `emulator`).

Règle du build : il tourne dès qu'un fichier changé sous `android/` n'est **pas** de la
documentation (le job compte les fichiers sous `android/` et ceux de documentation ; s'ils sont
égaux, le build est sauté).

Le workflow **Tests** (suite Python du serveur, `tests.yml`) a son propre filtre : il ne tourne pas
pour un changement sous `android/` seulement.

## Sur `main` et au lancement manuel : tout tourne

- **Push sur `main`** : build, modèles et les trois parts de l'émulateur tournent, quels que soient
  les fichiers changés (y compris un push de documentation seule sous `android/`), puis le job
  « Publish APK » publie la release si tout est vert et si l'APK est signé avec la clé de release.
- **Lancement manuel** : tout tourne aussi. Sur `main`, il publie comme un push ; sur une autre
  branche, il ne fait que construire et tester.
- **Option `skip_slow_tests`** (lancement manuel seulement, PR #79) : les tests des modèles et
  l'émulateur sont sautés ; le build (tests unitaires, signature) doit quand même passer, et la
  release publiée le dit dans le résumé du run.

Les runs de `main` ne sont jamais annulés en cours de route (pour ne pas couper une publication) ;
un nouveau run en attente remplace l'ancien en attente. Sur une PR, un nouveau commit annule le run
précédent.

## Émulateur : trois parts en parallèle

Les tests instrumentés (environ 130) tournent sur trois émulateurs à la fois (`pixel_6`, API 34),
**par classes entières** : une classe garde son ordre et sa préparation.

| Part | Classes |
|---|---|
| 1/3 | `SHARD1_CLASSES` : `TextKeyboardInstrumentedTest`, `ReadingTestInstrumentedTest`, `DictationEndToEndTest`, `ServerModeInstrumentedTest` |
| 2/3 | `SHARD2_CLASSES` : `ScreenshotTest`, `HowToInstrumentedTest`, `ContributionInstrumentedTest`, `SetupLanguageTest`, `VoiceCommandInstrumentedTest` |
| 3/3 | **toutes les autres** classes (filtre `notClass` calculé à partir des deux listes) |

Conséquences :

- Une **nouvelle classe** de tests tourne d'office dans la part 3, sans rien changer au workflow.
- Une classe des parts 1 ou 2 **renommée** sans mettre la liste à jour fait échouer la part
  (classe introuvable) au lieu d'être sautée. Mettre à jour `SHARD1_CLASSES` ou `SHARD2_CLASSES`
  dans le même commit.

### Rééquilibrer les parts

1. Ouvrir un run récent et vert, et lire dans le résumé de chaque part le tableau
   **« Test duration per class »** (écrit par `.github/scripts/test_durations.py` à partir des
   rapports JUnit XML).
2. Viser à peu près le même temps de tests par part. La durée d'une même classe varie d'un run à
   l'autre (jusqu'à environ ±1 min 30 par part) : comparer plusieurs runs avant de déplacer une classe.
3. Déplacer des noms de classes entre `SHARD1_CLASSES` et `SHARD2_CLASSES` (variables `env` du job
   `emulator`) ; retirer un nom des deux listes l'envoie dans la part 3.
4. Si les trois parts sont trop longues (par exemple avec le cas « barre à 3 boutons » de #70),
   ajouter une dimension à la matrice ou une part plutôt que d'allonger un émulateur.

Rapports et captures d'écran : artefacts `instrumented-test-reports-<n>` et `screenshots-<n>` de
chaque part.

## Cache de l'AVD

- L'émulateur démarré est gardé en **instantané** dans le cache GitHub (clé
  `avd-34-google_apis-x86_64-pixel_6-4096M-v1`), selon la méthode documentée par
  `reactivecircus/android-emulator-runner`.
- **Cache absent** : une étape crée l'AVD et son instantané (environ 1 min 35 s de plus, une fois
  par clé et par branche qui n'a pas accès à un cache existant). Le cache de `main` sert ensuite
  aux PR.
- Le run des tests repart de l'instantané et n'enregistre rien (`-no-snapshot-save`).
- Pour **forcer un nouvel AVD** (autre API, autre profil, autre mémoire, instantané abîmé) :
  changer la clé dans le workflow (par exemple `-v2`) en même temps que les réglages de
  l'émulateur, dans les deux étapes `android-emulator-runner`.

## Durées de référence

Mesurées sur la PR #93 (run #136, PR qui touche le workflow, donc tous les jobs) : **8 min 44 s** au
total, contre 14 min 15 s avant le découpage. Détail : émulateur 8 min 35 s, 8 min 26 s et
7 min 10 s par part ; modèles 5 min 38 s ; build et tests unitaires 4 min 03 s ; choix des jobs 5 s.
