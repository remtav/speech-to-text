# Contribuer au test de lecture en français québécois : politique de contribution

Kalmia Blabla · version 2 du 2026-11-13 (format de l'archive décrit tel qu'implémenté) · texte de lecture `lecture-qc-1`

> **À lire avant tout.** Ceci n'est pas un avis juridique. Cette page est une première version
> rédigée pendant le développement (issue #15, étape 4.1). **Le responsable du projet doit la valider
> au regard de la Loi 25 avant toute collecte réelle**, c'est-à-dire avant de diffuser l'application
> à d'autres personnes que lui. Les points encore ouverts sont listés à la fin
> (« Ce qui reste à valider »). Rien ici n'affirme que la collecte est conforme.

## En bref

- Le test de lecture se fait **entièrement sur votre téléphone**. Rien n'en sort par défaut.
- Si la dictée vous a mal compris, l'application peut vous **proposer** de partager votre lecture.
  Vous seul décidez, et c'est **vous qui envoyez** le courriel depuis votre propre messagerie.
- Destinataire : **remi@kalmia.ca**.
- Conservation : **2 ans au plus à compter de la réception, puis destruction** ; plus tôt si vous le
  demandez ou si l'enregistrement n'est plus utile.
- Pour retirer votre consentement ou faire supprimer votre lecture : écrivez à **remi@kalmia.ca**.

## Le test, étape par étape

1. **Offre.** Une carte « Aider à améliorer la dictée en français québécois » est proposée à la fin
   de la configuration et dans les Réglages, seulement si « Français (Québec) » fait partie de vos
   langues. La refuser ne change rien au fonctionnement de l'application.
2. **Déclaration.** L'application vous demande : « Parlez-vous un français québécois ? ». Si vous
   répondez non, elle vous remercie et s'arrête : pas de test, pas d'enregistrement, pas de partage.
3. **Lecture.** Si vous acceptez le test, vous lisez à voix haute un texte d'environ 200 mots, en
   courts paragraphes. Chaque paragraphe est enregistré et transcrit **sur le téléphone** par le ou
   les modèles installés, puis comparé au texte : vous voyez un taux de ressemblance et les mots mal
   compris. Rien ne quitte le téléphone pendant le test.
4. **Partage, seulement si la ressemblance est faible.** Si le taux d'erreur dépasse 20 % avec le
   modèle recommandé pour vos langues, l'application vous propose de partager votre lecture et vous
   montre exactement ce qui partirait. Sinon, rien n'est proposé.

Ce sont trois gestes distincts : déclarer, accepter le test, puis consentir au partage. Aucun envoi
n'est automatique.

## Ce qui est partagé, si vous l'acceptez

Avant votre consentement, l'écran affiche chacun de ces éléments avec sa valeur : c'est la même
description qui sert à écrire le fichier, l'écran et le fichier ne peuvent donc pas différer.

Un seul fichier, une archive zip nommée `kalmia-blabla-<version du texte>-<date>.zip` (par exemple
`kalmia-blabla-lecture-qc-1-2026-11-13.zip`), jointe au courriel que vous envoyez. Elle contient :

- **l'enregistrement de votre lecture** : un fichier audio WAV par paragraphe lu
  (`paragraphe-1.wav`, `paragraphe-2.wav`…), PCM 16 bits, mono, 16 kHz ;
- **un fichier de renseignements lisible**, `contribution.json`, qui contient exactement ceci, et
  rien d'autre :
  - `audio` : le format et la liste des fichiers WAV, avec leur durée ;
  - `version_du_texte` : le numéro de version du texte lu (par exemple `lecture-qc-1`) ;
  - `texte_lu` : le texte des paragraphes lus ;
  - `transcription` : la transcription obtenue par le **modèle recommandé** pour vos langues,
    paragraphe par paragraphe ;
  - `score` : le taux de ressemblance, le nombre d'erreurs et de mots, au total et par paragraphe,
    pour ce même modèle ;
  - `modele` : l'identifiant et le nom de ce modèle de transcription ;
  - `version_de_l_application` ;
  - `modele_du_telephone` : le fabricant et le modèle (par exemple « Google Pixel 7 ») ;
  - `declaration` : « je parle un français québécois » ;
  - `date` : le jour du test (année, mois, jour), **sans l'heure** ;
  - `region` : votre région, seulement si vous l'écrivez (60 caractères au plus) ; sinon, vide.

Si plusieurs modèles sont installés, seuls la transcription et le score du modèle recommandé sont
inclus : les résultats des autres modèles restent sur votre téléphone. Les fichiers de l'archive
portent tous la même date fictive (2 janvier 1980) : l'archive ne contient pas l'heure du test.

**Ce qui n'est pas partagé** : aucun nom, aucun numéro de téléphone, aucun identifiant de l'appareil
(ni identifiant Android, ni numéro de série, ni identifiant d'installation) ou de compte,
aucune autre dictée, aucun mot de votre vocabulaire personnel, aucun réglage, aucun autre fichier.

**Sur votre téléphone.** Rien n'est écrit tant que vous n'avez pas coché la case de consentement
(non cochée au départ) **et** touché « Préparer le courriel ». L'archive est alors écrite dans
l'espace privé de l'application, inaccessible aux autres applications ; elle n'est remise qu'à
l'application que vous choisissez (votre messagerie, ou une autre par « Partager le fichier
autrement »), avec une permission de lecture limitée à ce fichier. Elle est supprimée de votre
téléphone par le bouton « Supprimer le fichier », par « Ne pas partager », par « Refaire le test »
et quand vous fermez l'écran du test ; si l'application a été arrêtée de force entre-temps, à
l'ouverture suivante du test, ou au démarrage de l'application si elle a plus d'un jour. La copie
que votre messagerie garde dans ses messages envoyés relève de votre messagerie.

**Ce que le courriel révèle de lui-même** : comme tout courriel, il montre au destinataire l'adresse
qui l'envoie, et souvent le nom associé dans votre messagerie. Ce n'est pas l'application qui
l'ajoute, mais c'est une information que le destinataire reçoit.

**Une voix reste reconnaissable.** Même sans votre nom, un enregistrement de votre voix n'est pas
anonyme. Il est traité comme un renseignement personnel.

## Pourquoi

Repérer les mots, les expressions et les accents d'ici que la dictée comprend mal, pour mieux
choisir, recommander et régler les modèles de transcription de l'application. Votre lecture ne sert
à rien d'autre (ni publication, ni vente, ni entraînement d'un modèle) sans un nouveau consentement
de votre part.

## Qui le reçoit

Le responsable du projet Kalmia Blabla, à l'adresse **remi@kalmia.ca**. L'application elle-même ne
se connecte à aucun serveur pour ce partage : elle prépare le fichier et ouvre votre messagerie avec
le destinataire, un objet (« Kalmia Blabla : test de lecture » et la version du texte), un court
message qui rappelle le contenu du fichier et l'adresse de cette politique, et le fichier joint.
C'est vous qui appuyez sur « Envoyer ». Vous pouvez aussi annuler à ce moment-là, ou partager le
fichier autrement.

Qui d'autre a accès à cette boîte de courriel : **à préciser** (voir « Ce qui reste à valider »).

## Combien de temps, et comment c'est détruit

- **2 ans au plus à compter de la réception, puis destruction.**
- Destruction **plus tôt** si vous le demandez, ou si l'enregistrement n'est plus utile à la fin
  décrite plus haut.
- **Mode de destruction** : le responsable supprime à la main le courriel et sa pièce jointe de la
  boîte de réception, puis de la corbeille, ainsi que toute copie qu'il en aurait faite pour l'analyse.
  Rien dans l'application ne peut automatiser cette destruction : elle se fait dans la boîte de
  courriel du destinataire, hors de l'application.

## Retirer votre consentement, demander la suppression, l'accès ou la rectification

Écrivez à **remi@kalmia.ca**, de préférence depuis l'adresse qui a servi à l'envoi, en indiquant la
date du test. Votre lecture est alors détruite, avec le courriel qui l'accompagnait, et ne sert plus
à rien. Vous pouvez aussi demander à savoir ce qui a été reçu de vous, ou le faire corriger (par
exemple la région indiquée).

## Limites du test

- **Auto-déclaration.** « Je parle un français québécois » est une réponse que l'application ne
  vérifie pas.
- **Texte lu ≠ dictée spontanée.** Le test mesure l'accent et le vocabulaire sur un texte lu ; il ne
  dit rien des hésitations, des reprises ou des phrases improvisées.
- **Un seul texte, une seule lecture** : le score dépend aussi du micro, du bruit ambiant et du
  rythme de lecture.
- Le texte de lecture est rédigé pour l'application et libre de droits. Chaque nouvelle version porte
  un nouveau numéro, pour que les résultats de versions différentes ne soient pas mélangés.

## Ce qui reste à valider (par le responsable, avant toute collecte réelle)

Questions ouvertes de l'issue #15, à trancher avant de diffuser l'application à d'autres personnes :

1. **La durée de 2 ans est-elle justifiable par la fin poursuivie ?** La loi ne fixe pas de durée :
   elle demande de détruire (ou d'anonymiser) une fois la fin accomplie. 2 ans est une durée
   **maximale** choisie par le projet, à confirmer.
2. **Qui d'autre que le responsable a accès aux enregistrements reçus à remi@kalmia.ca ?** À
   préciser (autres personnes, fournisseur de la messagerie).
3. **Où la boîte de courriel est-elle hébergée ?** Si c'est hors du Québec, l'article 17 (évaluation
   avant une communication à l'extérieur du Québec) est-il en jeu ? À préciser.
4. **La voix est-elle ici un renseignement sensible, voire biométrique ?** Si oui, le consentement
   doit être exprès (art. 12 et 13). Le texte de consentement prévu est un geste exprès et distinct
   (case à cocher, puis envoi par la personne), mais sa suffisance reste à valider. Le projet ne
   compare pas les voix pour identifier qui que ce soit ; le régime propre à la biométrie servant à
   vérifier une identité ne semble donc pas visé, sans que ce soit vérifié.
5. **Responsable de la protection des renseignements personnels** : titre et coordonnées à publier
   (art. 3.1), et cette page à approuver par lui (art. 3.2).
6. **Mineurs** : le consentement d'un mineur de moins de 14 ans est donné par le titulaire de
   l'autorité parentale (art. 14). L'application ne demande pas l'âge : faut-il le faire ?

## Sources lues pour cette page

Lues le 2026-11-12 et le 2026-11-13. Résumé, pas un avis juridique.

- **Loi modernisant des dispositions législatives en matière de protection des renseignements
  personnels** (projet de loi no 64, L.Q. 2021, c. 25, « Loi 25 »), texte officiel en PDF, Éditeur
  officiel du Québec :
  <https://www.publicationsduquebec.gouv.qc.ca/fileadmin/Fichiers_client/lois_et_reglements/LoisAnnuelles/fr/2021/2021C25F.PDF>.
  Lu dans ce texte, tel qu'il modifie la *Loi sur la protection des renseignements personnels dans le
  secteur privé* (RLRQ, c. P-39.1) :
  - **art. 23** (inséré par l'art. 119) : « Lorsque les fins auxquelles un renseignement personnel a
    été recueilli ou utilisé sont accomplies, la personne qui exploite une entreprise doit le détruire
    ou l'anonymiser pour l'utiliser à des fins sérieuses et légitimes, sous réserve d'un délai de
    conservation prévu par une loi. » Le renseignement est anonymisé lorsqu'il ne permet plus, « de
    façon irréversible, d'identifier directement ou indirectement cette personne ».
  - **art. 3.2** : les politiques et pratiques de gouvernance prévoient « l'encadrement applicable à la
    conservation et à la destruction de ces renseignements » ; des informations détaillées sont
    publiées « en termes simples et clairs ».
  - **art. 8** : informer, lors de la collecte, des fins, des moyens, des droits d'accès et de
    rectification et du droit de retirer son consentement ; sur demande, de la durée de conservation
    et des catégories de personnes qui ont accès.
  - **art. 8.2** : politique de confidentialité publiée lorsque la collecte se fait par un moyen
    technologique.
  - **art. 12** : renseignement « sensible lorsque, de par sa nature notamment médicale, biométrique ou
    autrement intime […] il suscite un haut degré d'attente raisonnable en matière de vie privée » ;
    consentement exprès pour un renseignement sensible.
  - **art. 14** : consentement « manifeste, libre, éclairé et […] donné à des fins spécifiques »,
    demandé « distinctement de toute autre information » lorsqu'il est écrit ; mineurs de moins de 14 ans.
  - **art. 17** : évaluation des facteurs relatifs à la vie privée avant toute communication à
    l'extérieur du Québec.
- **Commission d'accès à l'information**, « Conservation et destruction des renseignements
  personnels » (entreprises privées) :
  <https://www.cai.gouv.qc.ca/protection-renseignements-personnels/information-entreprises-privees/conservation-destruction-renseignements-personnels>
  — aucune durée fixe ; destruction sécuritaire une fois la fin atteinte ; seule restriction, un délai
  prévu par une loi.
- **Commission d'accès à l'information**, « Biométrie » :
  <https://www.cai.gouv.qc.ca/protection-renseignements-personnels/sujets-et-domaines-dinteret/biometrie>
  — cite la voix parmi les caractéristiques comportementales et qualifie les renseignements
  biométriques de sensibles ; les obligations de déclaration visent la vérification ou la confirmation
  d'identité. La page ne dit pas si tout enregistrement de voix est biométrique.
- **Commission d'accès à l'information**, « Consentement » (entreprises) :
  <https://www.cai.gouv.qc.ca/protection-renseignements-personnels/information-entreprises-privees/consentement-personnes-entreprises>
  — critères : manifeste, libre, éclairé, spécifique, granulaire, compréhensible, temporaire ;
  consentement exprès pour un renseignement sensible.
- **Commission d'accès à l'information**, guide « Rédiger une politique de confidentialité » (PDF) :
  <https://www.cai.gouv.qc.ca/uploads/pdfs/CAI_GU_POL_Confidentialite.pdf> — droits à indiquer
  (accès, rectification, plainte) et coordonnées du responsable.

**Non vérifié** : le texte consolidé en vigueur de la loi P-39.1 sur Légis Québec (la page a répondu
« accès refusé » à l'outil de lecture le 2026-11-12) ; les modifications postérieures à 2021 ; le
*Règlement sur l'anonymisation des renseignements personnels* ; les lignes directrices 2023-1 de la
Commission sur le consentement (lues seulement à travers la page « Consentement » ci-dessus).
