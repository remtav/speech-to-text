"""Server configuration, read once from environment variables.

Invalid values never stop the server: they are logged and replaced by the
default, so a typo in ``.env`` shows up as a warning instead of a crash.
"""
import logging
import os
from dataclasses import dataclass, replace

log = logging.getLogger(__name__)

VALID_MODELS = ["tiny", "base", "small", "medium", "large", "large-v2", "large-v3", "large-v3-turbo"]
VALID_DEVICES = ["cpu", "cuda"]
DEFAULT_DIARIZE_MODEL = "pyannote/speaker-diarization-community-1"
WORKER_MODES = ["process", "inline"]


def env_bool(name, default=False):
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    return raw.strip().lower() in ("1", "true", "yes", "on")


def env_int(name, default, minimum=None, maximum=None):
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    try:
        value = int(raw)
    except ValueError:
        log.warning("Ignoring %s=%r: not an integer, using %s", name, raw, default)
        return default
    if (minimum is not None and value < minimum) or (maximum is not None and value > maximum):
        log.warning("Ignoring %s=%s: must be between %s and %s, using %s", name, value, minimum, maximum, default)
        return default
    return value


def env_choice(name, choices, default):
    raw = (os.getenv(name) or "").strip()
    if not raw:
        return default
    if raw not in choices:
        log.warning("Ignoring %s=%r: expected one of %s, using %r", name, raw, choices, default)
        return default
    return raw


@dataclass(frozen=True)
class Settings:
    host: str = "0.0.0.0"
    port: int = 8081
    model: str = "base"
    device: str = "cpu"
    compute_type: str | None = None  # None = float16 on GPU, int8 on CPU
    batch_size: int = 16
    diarize_default: bool = False
    diarize_model: str | None = None  # None = whisperx default (pyannote community-1)
    preload: bool = True
    worker_mode: str = "process"
    max_upload_mb: int = 2048
    ffmpeg_path: str | None = None
    ffmpeg_timeout: int = 1800
    cors_origins: tuple = ("*",)
    job_history: int = 50

    @property
    def diarize_model_name(self):
        return self.diarize_model or DEFAULT_DIARIZE_MODEL

    def with_overrides(self, **kwargs):
        return replace(self, **kwargs)


def load_settings():
    origins = tuple(o.strip() for o in (os.getenv("WHISPER_CORS_ORIGINS") or "*").split(",") if o.strip())
    return Settings(
        host=os.getenv("WHISPER_HOST") or "0.0.0.0",
        port=env_int("WHISPER_PORT", 8081, 1, 65535),
        model=env_choice("WHISPER_MODEL", VALID_MODELS, "base"),
        device=env_choice("WHISPER_DEVICE", VALID_DEVICES, "cpu"),
        compute_type=os.getenv("WHISPER_COMPUTE_TYPE") or None,
        batch_size=env_int("WHISPER_BATCH_SIZE", 16, 1, 256),
        diarize_default=env_bool("WHISPER_DIARIZE", False),
        diarize_model=os.getenv("WHISPER_DIARIZE_MODEL") or None,
        preload=env_bool("WHISPER_PRELOAD", True),
        worker_mode=env_choice("WHISPER_WORKER_MODE", WORKER_MODES, "process"),
        max_upload_mb=env_int("WHISPER_MAX_UPLOAD_MB", 2048, 1),
        ffmpeg_path=os.getenv("FFMPEG_PATH") or None,
        ffmpeg_timeout=env_int("WHISPER_FFMPEG_TIMEOUT", 1800, 10),
        cors_origins=origins or ("*",),
        job_history=env_int("WHISPER_JOB_HISTORY", 50, 1, 10000),
    )
