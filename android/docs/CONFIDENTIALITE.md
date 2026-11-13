# Politique de confidentialité — Kalmia Blabla

Kalmia Blabla est un clavier vocal. Cette page dit ce qui arrive à votre voix et à votre texte.

## Sur le téléphone

Sauf si vous avez configuré et choisi un serveur dans les réglages avancés (voir la section suivante),
votre voix est transcrite **sur le téléphone**, par le modèle que vous avez
téléchargé. Ni l'audio ni le texte ne quittent l'appareil. L'application n'a ni compte, ni publicité,
ni télémétrie.

## Envoi à un serveur (réglage avancé pour développeurs, seulement si vous le choisissez)

Un serveur de transcription ne sert qu'aux essais. Il se configure dans **Réglages → Avancé →
« Serveur distant, pour essais (développeurs) »**, section repliée ; l'installation n'en parle pas.
Si une adresse de serveur y est saisie (ou intégrée à un APK d'essai par la personne qui l'a compilé),
vous choisissez au même endroit, sous « Envoi au serveur », où vos dictées sont transcrites :

- **« Sur le serveur configuré »** : l'audio de chaque dictée est envoyé, par blocs pendant la dictée, au
  serveur configuré, qui renvoie le texte. Le serveur de ce dépôt (`docs/DEPLOIEMENT_SERVEUR.md`)
  **supprime l'audio dès la transcription**, ne garde le texte qu'en mémoire le temps que le
  téléphone le récupère, et n'écrit **ni l'audio ni le texte dans ses journaux**. Utilisez une adresse
  en HTTPS : l'application avertit quand l'audio traverserait Internet en clair.
- **« Toujours transcrire sur le téléphone »** : rien n'est envoyé.

Tant qu'aucun choix n'est enregistré, rien n'est envoyé. Le choix se change à tout moment au même
endroit. Tant que vos dictées vont à un serveur, l'écran principal de l'application affiche
« Transcription : serveur distant ». Sans réseau, ou si le serveur ne répond pas dans le délai réglé (8 s par défaut), la dictée
est transcrite sur le téléphone. Le jeton d'accès au serveur reste dans les préférences privées de l'application,
s'affiche masqué et n'apparaît pas dans les journaux.

La personne qui exploite le serveur est responsable de ce qu'il en fait ; avec un serveur autre que
celui de ce dépôt, lisez sa propre politique.

## Test de lecture

Le test de lecture en français québécois reste sur le téléphone ; un envoi de vos enregistrements
n'a lieu que si vous le choisissez, par courriel, après un consentement séparé (voir
[`CONTRIBUTION.md`](CONTRIBUTION.md)).
