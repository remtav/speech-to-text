# Journal des vagues

Mémoire des commandes `/vagues:vague` et `/vagues:planifier` (plugin vagues). `/vagues:vague` ajoute des lignes ; `/vagues:planifier` les lit et met à jour les **réglages**. Personne d'autre n'écrit ici, sauf l'utilisateur pour remplir le champ `utilisation`.

## Réglages

Lus par `/vagues:vague` et `/vagues:planifier`. C'est le **seul** endroit où le budget courant est noté ; seul `/vagues:planifier` le modifie. Budget de départ : 10 points.

budget: 10
estimations: aucune

<!-- estimations : ajustements par type d'issue décidés par /vagues:planifier, séparés par « ; »,
     ex. « estimations: tests e2e +1 ; doc seule -1 » (en points, ajoutés à la taille de l'issue).
     « aucune » tant que /vagues:planifier n'a rien ajusté. -->

## Format

Une ligne par événement, ajoutée **à la fin** du fichier (jamais de modification des lignes passées, sauf le champ `utilisation` par l'utilisateur). Champs séparés par ` | `. Heure en UTC, au format `AAAA-MM-JJTHH:MMZ`.

| Événement | Ligne |
|---|---|
| départ d'une vague | `- <heure> \| vague N \| ouverte \| prévus P` |
| résultat d'une issue | `- <heure> \| vague N \| issue #I \| taille T \| fusionnée\|ouverte\|bloquée \| <PR #n ou —> \| <raison, si ouverte ou bloquée>` |
| coupure constatée | `- <heure> \| vague N \| coupée \| complétés C/P` |
| reprise | `- <heure> \| vague N \| reprise \| prévus P` |
| fin de la vague | `- <heure> \| vague N \| fermée \| complétés C/P \| utilisation: __ %` |

- **ouverte / reprise** : la vague est en cours. Une vague dont la dernière ligne d'état (`ouverte`, `reprise`, `coupée`, `fermée`) n'est pas `fermée` est **non fermée**.
- **coupée** : écrite au moment de la reprise, pour une vague restée non fermée (session coupée, limite atteinte). `C` = points des issues fermées ou dont la PR est fusionnée à ce moment.
- **fermée** : l'orchestrateur est allé au bout de la vague (même si des PR restent ouvertes, avec leur raison dans les lignes `issue`).
- **utilisation** : l'orchestrateur écrit `utilisation: __ %` ; l'utilisateur remplace `__` par le pourcentage d'utilisation affiché dans l'app Claude à la fin de la vague (facultatif). `/vagues:planifier` s'en sert pour ajuster le budget.

Le journal est commité directement sur `main` par la session principale de `/vagues:vague` (voir « Écrire une ligne au journal » dans la commande `vague` du plugin vagues). Il est sous `.claude/`, en principe hors des chemins de la CI du projet : ses commits ne devraient déclencher aucune CI.

## Événements
