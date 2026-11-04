"""Which code is running: the release number plus the git commit it was built from.

The Docker image stores the commit in ``app/build-info.json`` at build time
(see the Dockerfile's ``buildinfo`` stage).  Run from a checkout, it is read
from git instead.
"""
import json
import subprocess
from functools import lru_cache
from pathlib import Path

__version__ = "2.0.0"

BUILD_INFO = Path(__file__).parent / "build-info.json"
REPO_URL = "https://github.com/remtav/speech-to-text"


def _from_git(repo: Path) -> dict:
    try:
        out = subprocess.run(["git", "-C", str(repo), "log", "-1", "--format=%H%n%cI"],
                             capture_output=True, text=True, timeout=5, check=True).stdout.split()
    except (OSError, subprocess.SubprocessError):
        return {}
    return {"commit": out[0], "date": out[1]} if len(out) == 2 else {}


@lru_cache(maxsize=1)
def build_info() -> dict:
    if BUILD_INFO.is_file():
        try:
            info = json.loads(BUILD_INFO.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            info = {}
    else:
        info = _from_git(Path(__file__).resolve().parents[1])
    commit = info.get("commit") or None
    return {
        "version": __version__,
        "commit": commit,
        "commit_short": commit[:7] if commit else None,
        "date": info.get("date") or None,
        "url": f"{REPO_URL}/commit/{commit}" if commit else REPO_URL,
    }


def build_label() -> str:
    """Short text shown in the UI, e.g. ``v2.0.0 · f963ac0 · 2026-11-03``."""
    info = build_info()
    parts = [f"v{info['version']}"]
    if info["commit_short"]:
        parts.append(info["commit_short"])
    if info["date"]:
        parts.append(info["date"][:10])
    return " · ".join(parts)
