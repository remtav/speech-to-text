# CLAUDE.md

## Règles de planification

Mécanique des vagues fournie par le plugin `vagues` (marketplace `outils-claude`). Le travail est découpé en **issues** (une unité implémentable seule, modèle [`tache.md`](.github/ISSUE_TEMPLATE/tache.md)), regroupées en **vagues** (étiquette `vague-N`, lancées par `/vagues:vague N`), puis en **releases** (`/vagues:release`).

- **Tailles** : petite = 1, moyenne = 2, grosse = 4 points, posées en étiquette `taille-1`, `taille-2` ou `taille-4`. Au-delà de 4, scinder l'issue.
- **Budget par vague** : 10 points au départ, ajusté par `/vagues:planifier`. La valeur courante est notée à **un seul endroit** : la ligne `budget:` des réglages de [`.claude/journal-vagues.md`](.claude/journal-vagues.md). La somme des tailles d'une vague ne dépasse pas le budget (une issue seule plus grosse forme sa propre vague).
- **Estimations calibrées** : ligne `estimations:` des réglages du même journal (ajustements par type d'issue, tenus par `/vagues:planifier`).
- **Fichiers** : deux issues d'une même vague ne touchent pas les mêmes fichiers (section « Fichiers probablement touchés » du modèle).
- **Dépendances** : une issue qui dépend d'une autre va dans une vague suivante.
- **Fusion** : seulement si la CI a réussi **et** que le réviseur a approuvé le dernier commit (garde de fusion du plugin).

**Commandes de vérification** (lues par les hooks du plugin, dans ce projet) :
- rapide, avant qu'un implémenteur termine : `python3 -m pytest -q tests` — fichier [`.claude/verification-implementeur`](.claude/verification-implementeur) (la variable `VERIFICATION_IMPLEMENTEUR` la remplace) ;
- suite complète, avant un tag de release : `python3 -m pytest -q tests && (cd android && ./gradlew --no-daemon testDebugUnitTest)` — fichier [`.claude/tests-complets`](.claude/tests-complets) (la variable `RELEASE_TEST_CMD` la remplace).

Commandes : `/vagues:planifier [K] [lecture-seule]`, `/vagues:vague <N | reprise>`, `/vagues:release <tag> <N…>`. Le journal est commité directement sur `main` par la session qui lance `/vagues:vague` ou `/vagues:planifier`, sans PR.
