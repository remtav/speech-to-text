# Déployer le serveur de transcription (issue #23)

Ce guide met le serveur de ce dépôt (`app/`, FastAPI + WhisperX) sur une machine louée avec
GPU, joignable en HTTPS par l'application Android « Kalmia Blabla ». Sur le téléphone, la
transcription locale avec Whisper est trop lente pour une dictée confortable ; le serveur
répond en une fraction du temps.

Rien ici n'est fait automatiquement : vous louez la machine, vous la configurez, puis vous
entrez l'adresse et le jeton dans l'application (Réglages → Serveur distant).

## 1. Ce qu'il faut

- Une machine Linux avec un GPU NVIDIA d'au moins 8 Go de mémoire (16 à 24 Go si le
  polissage par LLM est activé sur la même carte), le pilote NVIDIA, Docker et le
  [NVIDIA Container Toolkit](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/install-guide.html).
- Un nom de domaine qui pointe vers la machine (enregistrement DNS A/AAAA), pour le certificat HTTPS.
- **Région recommandée : Canada** (par exemple Montréal ou Toronto). L'audio dicté reste
  ainsi au Canada, et la latence depuis le Québec est la plus faible.

### Coûts indicatifs (à vérifier)

Les prix changent souvent et varient selon le fournisseur ; vérifiez-les vous-même avant
de louer. Ordres de grandeur constatés fin 2026, à titre indicatif seulement :

| Type de machine | GPU | Ordre de grandeur |
|---|---|---|
| Petite instance GPU | NVIDIA T4 / L4 (16 / 24 Go) | 0,40 à 1 $ CA / heure, soit environ 300 à 750 $ CA / mois en continu |
| Instance GPU moyenne | NVIDIA A10 / L40S | 1 à 2,50 $ CA / heure |

Pour un usage personnel, une instance arrêtée la nuit ou facturée à la seconde coûte
nettement moins cher qu'une instance allumée en permanence. Le stockage (images Docker et
modèles, environ 15 Go) et le trafic sortant sont souvent facturés à part.

## 2. Lancer le serveur avec Docker et le GPU

```bash
git clone https://github.com/remtav/speech-to-text.git
cd speech-to-text

# Jetons d'accès : un par téléphone, longs et aléatoires. Gardez-les hors du dépôt.
echo "DICTEE_TOKENS=$(openssl rand -hex 32)" > .env.secret
chmod 600 .env.secret
cat .env.secret   # à copier dans l'application, champ « Jeton d'accès »

# Modèle et GPU
cat >> .env.secret <<'EOF'
WHISPER_MODEL=large-v3-turbo
WHISPER_DEVICE=cuda
DICTEE_RATE_LIMIT=30
EOF

docker compose --env-file .env.secret up -d --build
docker compose logs -f speech-to-text   # attendre « Access: token required »
```

Avec le polissage par LLM (optionnel, voir le README, section « Transcript polish ») :
`docker compose --env-file .env.secret --profile polish up -d`, puis
`docker compose exec ollama ollama pull qwen2.5:7b-instruct-q4_K_M` et
`WHISPER_POLISH=true` dans `.env.secret`.

**Ne publiez pas le port 8081 sur Internet.** Le serveur parle HTTP en clair ; seul le
reverse proxy HTTPS (section 3) doit être joignable de l'extérieur. Dans
`docker-compose.yml`, remplacez `"8081:8081"` par `"127.0.0.1:8081:8081"` et ouvrez
uniquement les ports 80 et 443 dans le pare-feu de la machine (et celui du fournisseur).

## 3. HTTPS avec un reverse proxy

L'audio et le jeton ne doivent jamais circuler en clair. Le plus simple est
[Caddy](https://caddyserver.com/), qui obtient et renouvelle seul un certificat
Let's Encrypt :

```bash
sudo apt install -y caddy
sudo tee /etc/caddy/Caddyfile <<'EOF'
dictee.example.ca {
    # Une dictée de plusieurs minutes fait quelques Mo
    request_body {
        max_size 50MB
    }
    reverse_proxy 127.0.0.1:8081
    # Pas de journal d'accès : ni adresse IP ni chemin conservés
    log {
        output discard
    }
}
EOF
sudo systemctl reload caddy
```

Avec nginx, l'équivalent est un bloc `server` en `listen 443 ssl` avec `proxy_pass
http://127.0.0.1:8081;`, `client_max_body_size 50m;`, `proxy_read_timeout 360s;`,
`access_log off;` et un certificat obtenu par `certbot --nginx`.

Vérification depuis votre ordinateur :

```bash
curl https://dictee.example.ca/healthz                    # {"status":"ok"}, sans jeton
curl https://dictee.example.ca/health                     # 401 : jeton exigé
curl -H "Authorization: Bearer <jeton>" https://dictee.example.ca/health   # état du serveur
```

## 4. Variables du serveur

| Variable | Défaut | Rôle |
|---|---|---|
| `DICTEE_TOKENS` | *(non définie : aucune authentification)* | Liste de jetons séparés par des virgules. Définie, toutes les routes sauf `/`, `/healthz` et `/static/` exigent `Authorization: Bearer <jeton>` (401 sinon), et chaque jeton ne voit que ses propres tâches. Définie mais vide de jetons valides (par exemple `,`), le serveur refuse tout. |
| `DICTEE_RATE_LIMIT` | `30` | Requêtes de transcription, de polissage (`POST /polish`) et de changement de modèle par minute et par jeton ; 429 au-delà, avec l'en-tête `Retry-After`. `0` = sans limite. Le suivi d'une tâche (`GET /jobs/{id}`) n'est pas compté. |
| `WHISPER_MODEL` | `base` | `large-v3-turbo` recommandé sur GPU. |
| `WHISPER_DEVICE` | `cpu` | `cuda` sur la machine GPU. |
| `WHISPER_CORS_ORIGINS` | `*` | L'application Android n'en a pas besoin ; vous pouvez le restreindre. |

Les autres variables sont décrites dans le README, section « Configuration ».

La page web du serveur (`/`) reste affichée sans jeton, mais elle ne peut pas appeler l'API
quand `DICTEE_TOKENS` est défini : elle sert à l'usage local, sans jeton.

## 5. Confidentialité

- **Audio supprimé dès la transcription** : le fichier reçu est effacé dès que Whisper l'a
  lu, avant même le polissage, et dans tous les autres cas (erreur, annulation, envoi
  refusé, arrêt du serveur). Les tests `tests/test_security.py` le vérifient.
- **Journaux sans audio ni texte** : le serveur ne journalise ni l'audio, ni le texte
  transcrit, ni le texte poli, ni les jetons. `POLISH_LOG` (journal de réglage qui
  contient des transcriptions) est ignoré dès que `DICTEE_TOKENS` est défini.
- Le texte du résultat reste en mémoire vive, dans l'historique des tâches
  (`WHISPER_JOB_HISTORY`, 50 tâches par défaut), le temps que l'application le récupère ;
  il disparaît au redémarrage. Seul le jeton qui a créé une tâche peut la lire.
- Désactivez les journaux d'accès du reverse proxy (voir la section 3) et les
  sauvegardes automatiques du disque si votre fournisseur en fait.

## 6. Configurer l'application

Dans l'application : **Réglages → Serveur distant**, entrez l'adresse
(`https://dictee.example.ca`) et le jeton, puis « Tester la connexion ». Choisissez ensuite le
mode « Sur le serveur configuré » (à l'installation ou dans Réglages → Serveur distant → Envoi au
serveur) : sans ce choix, rien n'est envoyé. Si le serveur ne répond pas dans le délai (8 s par
défaut, réglable), la dictée est transcrite sur le téléphone.

Pour distribuer un APK qui pointe déjà vers votre serveur, compilez-le avec
`./gradlew assembleRelease -PdefaultServerUrl=https://dictee.example.ca` (le jeton, lui,
n'est jamais intégré à l'APK : chaque personne entre le sien).

## 7. Mettre à jour

```bash
cd speech-to-text && git pull
docker compose --env-file .env.secret up -d --build
```

Pour retirer l'accès d'un téléphone, enlevez son jeton de `DICTEE_TOKENS` et relancez
`docker compose --env-file .env.secret up -d`.
