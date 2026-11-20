"""Défauts de déploiement : compose, Dockerfile et .env doivent s'accorder sur le modèle Whisper."""
from pathlib import Path

from app.config import VALID_MODELS

RACINE = Path(__file__).resolve().parents[1]


def _lire(nom):
    return (RACINE / nom).read_text(encoding="utf-8")


def _lignes(nom):
    return [ligne.strip() for ligne in _lire(nom).splitlines()]


def test_compose_transmet_modele_et_peripherique():
    lignes = _lignes("docker-compose.yml")
    assert "- WHISPER_MODEL=${WHISPER_MODEL:-large-v3}" in lignes
    assert "- WHISPER_DEVICE=${WHISPER_DEVICE:-cuda}" in lignes
    assert "WHISPER_MODEL=base" not in _lire("docker-compose.yml")


def test_dockerfile_defaut_large_v3():
    assert "ENV WHISPER_MODEL=large-v3" in _lignes("Dockerfile")


def test_env_versionne_defaut_large_v3():
    assert "WHISPER_MODEL=large-v3" in _lignes(".env")


def test_defaut_compose_est_un_modele_valide():
    assert "large-v3" in VALID_MODELS
