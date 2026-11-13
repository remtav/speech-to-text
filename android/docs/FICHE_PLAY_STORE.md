# Fiche du Play Store : texte proposé (issue #37)

**Rien n'est publié.** Ce fichier propose le texte d'une future fiche du Play Store, en français et en
anglais. Il n'existe pas encore de fiche ; le texte est à relire et à coller par l'humain qui la créera.

Règles suivies (issues #37, #15 et #57) :

- dire « développée au Québec » et « code ouvert » en mots simples ;
- séparer clairement **le code (public)** et **les données de la personne (privées)** ;
- aucun nom de modèle (« Whisper », « Parakeet », « large-v3-turbo », « quantification ») ;
- chaque phrase correspond à un comportement vérifiable de l'application ;
- pas de « 100 % québécois » : les modèles de transcription viennent d'éditeurs étrangers ;
- pas d'« open source » : le dépôt n'a pas encore de fichier de licence, donc ce n'est pas un logiciel
  libre au sens juridique. « Code ouvert » dit ce qui est vrai : le code est public et chacun peut le lire.
- le serveur distant n'est pas mentionné : c'est un réglage pour développeurs (issue #57), et sans lui
  rien ne quitte le téléphone.

## Formulation retenue pour « open source »

| Langue | Formulation |
|---|---|
| Français | **Code ouvert : tout le monde peut vérifier ce que l'application fait de votre voix. Le code est public ; vos dictées, elles, restent privées.** |
| Anglais | **Open code: anyone can check what the app does with your voice. The code is public; your dictations stay private.** |

Pourquoi celle-ci : des trois pistes de #37, c'est la seule qui dit à la fois ce qui est public (le code),
à quoi ça sert (vérifier ce que l'application fait de la voix) et ce qui reste privé (les dictées). La
même phrase est à l'accueil de l'application (`setup_intro`).

## Français

**Nom** (30 caractères au plus) : Kalmia Blabla : dictée vocale

**Description courte** (80 caractères au plus) :

> Clavier vocal développé au Québec. Votre voix reste sur votre téléphone.

**Description complète** :

> Kalmia Blabla est un clavier vocal développé au Québec, pour le français d'ici.
>
> Vous parlez, il écrit, dans n'importe quelle application. Votre voix est transcrite sur votre
> téléphone : elle n'est envoyée à personne. Une fois le modèle de transcription téléchargé, la dictée
> fonctionne même hors ligne.
>
> • Pensé pour le français québécois. L'application choisit pour vous le modèle qui convient à vos
>   langues : français (Québec ou France), anglais, espagnol, portugais, et d'autres.
> • Un test d'une minute, facultatif : lisez quelques phrases, et l'application vous dit quel modèle
>   vous comprend le mieux sur votre téléphone.
> • Dites la ponctuation : « virgule », « point », « à la ligne »…
> • Pas de compte, pas de publicité, pas de télémétrie.
> • Code ouvert : tout le monde peut vérifier ce que l'application fait de votre voix. Le code est
>   public ; vos dictées, elles, restent privées.
>
> Les modèles de transcription viennent d'éditeurs étrangers ; l'application qui les fait tourner sur
> votre téléphone est développée au Québec.

## English

**Name** (30 characters max): Kalmia Blabla: voice typing

**Short description** (80 characters max):

> Voice keyboard developed in Quebec. Your voice stays on your phone.

**Full description**:

> Kalmia Blabla is a voice keyboard developed in Quebec, for the French spoken here.
>
> You speak, it types, in any app. Your voice is transcribed on your phone: it is sent to no one. Once
> the transcription model is downloaded, dictation works even offline.
>
> • Designed for Quebec French. The app picks the model that suits your languages for you: French
>   (Quebec or France), English, Spanish, Portuguese, and more.
> • An optional one-minute test: read a few sentences, and the app tells you which model understands
>   you best on your phone.
> • Say the punctuation: "comma", "period", "new line"… (voice commands in French and English).
> • No account, no ads, no telemetry.
> • Open code: anyone can check what the app does with your voice. The code is public; your
>   dictations stay private.
>
> The transcription models come from publishers outside Quebec; the app that runs them on your phone is
> developed in Quebec.

## Section « Sécurité des données » du Play Store (à remplir par l'humain)

Réponses qui correspondent au comportement actuel, à vérifier au moment de publier :

- **Données collectées** : aucune. **Données partagées** : aucune.
- L'audio et le texte des dictées restent sur l'appareil. Le réseau sert à télécharger les modèles.
- Le partage d'une lecture du test de lecture (#15) est un courriel que la personne envoie elle-même
  depuis sa messagerie, après un consentement explicite : l'application n'envoie rien.
- Le réglage « Serveur distant, pour essais (développeurs) » envoie l'audio au serveur que la personne a
  elle-même configuré ; il est vide par défaut dans la release publique. Politique complète :
  [`CONFIDENTIALITE.md`](CONFIDENTIALITE.md).

## À vérifier par l'humain avant de publier

- [ ] « Développée au Québec » reste vrai (affirmation de l'auteur).
- [ ] L'anglais : relecture par une personne anglophone. L'application elle-même n'est qu'en français
      (ses écrans) ; les commandes vocales existent en français et en anglais.
- [ ] Les limites de longueur du Play Store (30 / 80 / 4 000 caractères) au moment de publier.
- [ ] Une licence est-elle ajoutée au dépôt ? Si oui, « code ouvert » peut devenir « logiciel libre ».
