# Politique de confidentialité — Kalmia Blabla

Kalmia Blabla est un clavier vocal. Cette page dit ce qui arrive à votre voix et à votre texte.

## Sur le téléphone (par défaut)

Sans serveur configuré, votre voix est transcrite **sur le téléphone**, par le modèle que vous avez
téléchargé. Ni l'audio ni le texte ne quittent l'appareil. L'application n'a ni compte, ni publicité,
ni télémétrie.

## Envoi à un serveur (seulement avec votre accord)

Si une adresse de serveur est configurée (dans les Réglages, ou intégrée à l'APK par la personne qui
l'a compilé), l'application vous demande votre accord **à la première dictée** qui utiliserait ce
serveur :

- **« Accepter »** : l'audio de chaque dictée est envoyé, à la fin de la dictée, au serveur configuré
  (dont l'adresse est affichée dans la question), qui renvoie le texte. Le serveur de ce dépôt
  (`docs/DEPLOIEMENT_SERVEUR.md`) **supprime l'audio dès la transcription**, ne garde le texte qu'en
  mémoire le temps que le téléphone le récupère, et n'écrit **ni l'audio ni le texte dans ses
  journaux**. Utilisez une adresse en HTTPS : l'application avertit quand l'audio traverserait
  Internet en clair.
- **« Rester sur le téléphone »** : rien n'est envoyé ; les dictées sont transcrites sur le téléphone.

Le choix se change à tout moment dans **Réglages → Serveur distant** (« J'accepte d'envoyer l'audio
de mes dictées au serveur configuré », « Toujours transcrire sur le téléphone »). Sans réseau, ou si
le serveur ne répond pas dans le délai réglé (8 s par défaut), la dictée est transcrite sur le
téléphone. Le jeton d'accès au serveur reste dans les préférences privées de l'application,
s'affiche masqué et n'apparaît pas dans les journaux.

La personne qui exploite le serveur est responsable de ce qu'il en fait ; avec un serveur autre que
celui de ce dépôt, lisez sa propre politique.

## Test de lecture

Le test de lecture en français québécois reste sur le téléphone ; un envoi de vos enregistrements
n'a lieu que si vous le choisissez, par courriel, après un consentement séparé (voir
[`CONTRIBUTION.md`](CONTRIBUTION.md)).
