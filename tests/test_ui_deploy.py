"""Static checks that tie the web UI and the deployment files to the server.

There is no browser in CI, so these catch the mistakes that would otherwise
only show up by hand: an element id the script looks up but the page lacks, a
form field the UI posts that the API does not accept, a setting read by
``app/config.py`` that the compose file or the README forgets.
"""
import re
from pathlib import Path

import yaml

from app.config import DEFAULT_POLISH_MODEL

ROOT = Path(__file__).parent.parent
APP_JS = (ROOT / "static" / "app.js").read_text(encoding="utf-8")
INDEX = (ROOT / "templates" / "index.html").read_text(encoding="utf-8")
CONFIG = (ROOT / "app" / "config.py").read_text(encoding="utf-8")
README = (ROOT / "README.md").read_text(encoding="utf-8")
COMPOSE = yaml.safe_load((ROOT / "docker-compose.yml").read_text(encoding="utf-8"))

# Settings of the polish step, as read by load_settings()
POLISH_VARS = sorted(set(re.findall(r'"((?:WHISPER_POLISH|POLISH_[A-Z_]+))"', CONFIG)))


def _env(service):
    """A compose service's environment as a dict (list or mapping form)."""
    env = COMPOSE["services"][service].get("environment") or {}
    if isinstance(env, dict):
        return {k: "" if v is None else str(v) for k, v in env.items()}
    return dict(item.split("=", 1) if "=" in item else (item, "") for item in env)


def _gpu_reserved(service):
    devices = COMPOSE["services"][service]["deploy"]["resources"]["reservations"]["devices"]
    return any(d.get("driver") == "nvidia" and "gpu" in d.get("capabilities", []) for d in devices)


# ------------------------------------------------------------------ web UI

class TestPage:
    def test_every_element_the_script_uses_exists(self):
        ids = set(re.findall(r'id="([^"]+)"', INDEX))
        used = set(re.findall(r"\$\('([A-Za-z0-9_-]+)'\)", APP_JS))
        assert used, "no $('id') lookups found: the pattern is out of date"
        assert sorted(used - ids) == []

    def test_polish_toggle_next_to_diarization(self):
        options = INDEX.split('id="diarizeToggle"', 1)[1].split("</section>", 1)[0]
        assert 'id="polishToggle"' in options and 'type="checkbox"' in options
        assert 'id="polishStatus"' in options  # where the "not available" hint goes

    def test_raw_polished_switch_in_result_actions(self):
        actions = INDEX.split('id="resultActions"', 1)[1].split("</div>\n            </div>", 1)[0]
        assert 'id="viewSwitch"' in actions
        assert set(re.findall(r'data-view="(\w+)"', actions)) == {"polished", "raw"}

    def test_polish_preference_is_saved(self):
        save = APP_JS.split("function savePrefs()", 1)[1].split("\n}\n", 1)[0]
        assert "$('polishToggle').checked" in save
        # Restoring the capture mode saves prefs before /health answers: the stored
        # choice must survive that, not be overwritten by the still-unset toggle
        assert "polishInitialized ?" in save and "saved.polish" in save

    def test_polish_toggle_follows_health(self):
        assert "updatePolishOptions(!!data.polish_available, !!data.polish_default, data.polish_model)" in APP_JS


class TestFormFields:
    def test_ui_fields_are_accepted_by_the_jobs_api(self, client):
        sent = set(re.findall(r"form\.append\('(\w+)'", APP_JS)) - {"file"}
        assert "polish" in sent
        spec = client.get("/openapi.json").json()
        body = spec["paths"]["/jobs"]["post"]["requestBody"]["content"]["multipart/form-data"]["schema"]
        name = body["$ref"].rsplit("/", 1)[1]
        accepted = set(spec["components"]["schemas"][name]["properties"])
        assert sorted(sent - accepted) == []

    def test_polish_is_always_sent_explicitly(self):
        # Sending nothing would let WHISPER_POLISH decide even while the toggle is greyed out
        options = APP_JS.split("function transcriptionOptions()", 1)[1].split("\n}\n", 1)[0]
        assert "form.append('polish'" in options
        assert "!$('polishToggle').disabled" in options


# -------------------------------------------------------------- deployment

class TestCompose:
    def test_ollama_only_with_the_polish_profile(self):
        assert COMPOSE["services"]["ollama"]["profiles"] == ["polish"]
        assert "profiles" not in COMPOSE["services"]["speech-to-text"]  # plain "up" is unchanged

    def test_app_does_not_depend_on_ollama(self):
        # The profile is optional: a hard dependency would break "docker compose up" without it
        assert "ollama" not in (COMPOSE["services"]["speech-to-text"].get("depends_on") or {})

    def test_ollama_on_the_gpu_and_kept_loaded(self):
        assert _gpu_reserved("ollama") and _gpu_reserved("speech-to-text")
        env = _env("ollama")
        assert env["OLLAMA_KEEP_ALIVE"] == "-1"
        assert int(env["OLLAMA_CONTEXT_LENGTH"]) >= 4096  # the polish prompt is ~3k tokens
        assert env["OLLAMA_NUM_PARALLEL"] == "1"

    def test_models_kept_in_a_volume(self):
        volumes = COMPOSE["services"]["ollama"]["volumes"]
        assert any(v.endswith(":/root/.ollama") for v in volumes)
        name = next(v for v in volumes if v.endswith(":/root/.ollama")).split(":", 1)[0]
        assert name in COMPOSE["volumes"]

    def test_ollama_not_published_on_the_host(self):
        assert "ports" not in COMPOSE["services"]["ollama"]

    def test_app_points_at_the_ollama_service(self):
        assert _env("speech-to-text")["POLISH_BASE_URL"] == "${POLISH_BASE_URL:-http://ollama:11434/v1}"

    def test_every_polish_setting_is_passed_through(self):
        assert "POLISH_API_KEY" in POLISH_VARS and "WHISPER_POLISH" in POLISH_VARS
        env = _env("speech-to-text")
        assert sorted(set(POLISH_VARS) - set(env)) == []
        for name in POLISH_VARS:
            assert env[name].startswith(f"${{{name}:-"), f"{name} must come from the host environment"

    def test_no_secret_in_the_compose_file(self):
        assert _env("speech-to-text")["POLISH_API_KEY"] == "${POLISH_API_KEY:-}"


class TestReadme:
    def test_every_polish_setting_is_documented(self):
        table = README.split("## Configuration", 1)[1].split("\n## ", 1)[0]
        assert sorted(n for n in POLISH_VARS if f"| `{n}`" not in table) == []

    def test_default_model_matches_the_code(self):
        assert f"ollama pull {DEFAULT_POLISH_MODEL}" in README
        assert f"| `POLISH_MODEL`      | `{DEFAULT_POLISH_MODEL}`" in README

    def test_profile_instructions(self):
        assert "docker compose --profile polish up -d" in README
