"""Hugging Face token management.

The token is only needed for speaker diarization (the pyannote pipeline is a
gated model). It is looked up on every call, in the same order as
``huggingface_hub``: ``HF_TOKEN`` environment variable first, then the token
saved by ``hf auth login`` or by the web UI (``~/.cache/huggingface/token``).
"""
import logging
import os
from pathlib import Path

log = logging.getLogger(__name__)

ENV_VARS = ("HF_TOKEN", "HUGGING_FACE_HUB_TOKEN")


def get_token():
    try:
        from huggingface_hub import get_token as hub_get_token
        return hub_get_token()
    except ImportError:
        return os.getenv("HF_TOKEN") or None


def token_source():
    """'env' when set by an environment variable, 'file' when saved on disk, else None."""
    if any((os.getenv(name) or "").strip() for name in ENV_VARS):
        return "env"
    return "file" if get_token() else None


def token_path():
    try:
        from huggingface_hub import constants
        return Path(constants.HF_TOKEN_PATH)
    except ImportError:
        return Path(os.path.expanduser("~/.cache/huggingface/token"))


def save_token(token):
    """Save the token where `hf auth login` puts it, readable by the owner only."""
    path = token_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(token)
    os.chmod(path, 0o600)
    log.info("Hugging Face token saved to %s", path)


def delete_token():
    """Remove the saved token. Returns True if a file was removed."""
    path = token_path()
    try:
        path.unlink()
    except FileNotFoundError:
        return False
    log.info("Hugging Face token removed from %s", path)
    return True


def mask(token):
    if not token:
        return None
    return f"{token[:3]}…{token[-4:]}" if len(token) > 12 else "…"


def _status_code(error):
    response = getattr(error, "response", None)
    return getattr(response, "status_code", None)


def check_token(token, model_id):
    """Check a token against the Hub without raising.

    Returns ``{"valid", "username", "model_access", "message"}`` where ``valid`` and
    ``model_access`` are True / False, or None when the Hub could not be reached.
    """
    from huggingface_hub import auth_check, whoami

    try:
        from huggingface_hub.errors import GatedRepoError, RepositoryNotFoundError
    except ImportError:  # older huggingface_hub
        from huggingface_hub.utils import GatedRepoError, RepositoryNotFoundError

    result = {"valid": None, "username": None, "model_access": None, "message": ""}
    try:
        info = whoami(token=token)
    except Exception as e:
        if _status_code(e) in (401, 403) or "invalid user token" in str(e).lower():
            result.update(valid=False, model_access=False,
                          message="Hugging Face rejected this token (invalid or revoked).")
        else:
            log.warning("Could not verify the Hugging Face token: %s", e)
            result["message"] = f"Could not reach Hugging Face to verify the token: {e}"
        return result

    result.update(valid=True, username=info.get("name"))
    model_url = f"https://huggingface.co/{model_id}"
    try:
        auth_check(model_id, token=token)
        result["model_access"] = True
        result["message"] = f"Token OK (user {result['username']}), access to {model_id} granted."
    except GatedRepoError:
        result["model_access"] = False
        result["message"] = f"Token OK, but you must accept the model terms at {model_url}"
    except RepositoryNotFoundError:
        result["model_access"] = False
        result["message"] = f"Token OK, but it cannot access {model_id}. Accept the terms at {model_url}"
    except Exception as e:
        log.warning("Could not check access to %s: %s", model_id, e)
        result["message"] = f"Token OK, but access to {model_id} could not be checked: {e}"
    return result
