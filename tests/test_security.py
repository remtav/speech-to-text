"""Issue #23: a server exposed on the internet.

Token authentication (DICTEE_TOKENS), rate limit per token (DICTEE_RATE_LIMIT),
audio deleted after success / error / cancellation, no audio nor transcript
in the logs.
"""
import logging
import tempfile
import time

import pytest

from app.config import load_settings
from app.security import RateLimiter, parse_tokens, token_id

TOKEN = "tok-alice-0123456789abcdef"
OTHER = "tok-bob-0123456789abcdef"


def _auth(token=TOKEN):
    return {"Authorization": f"Bearer {token}"}


def _post(client, path="/jobs", content=b"Hello there", headers=None, data=None):
    return client.post(path, files={"file": ("a.wav", content, "audio/wav")}, data=data or {}, headers=headers or {})


def _wait(client, job_id, headers, timeout=10):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        job = client.get(f"/jobs/{job_id}", headers=headers).json()
        if job["status"] not in ("queued", "running"):
            return job
        time.sleep(0.02)
    raise AssertionError("job did not finish")


@pytest.fixture
def secured(make_client):
    return make_client(tokens=(TOKEN, OTHER), auth_required=True, rate_limit=30)


# ----------------------------------------------------------------- config

class TestConfig:
    def test_tokens_parsed_from_env(self, monkeypatch):
        monkeypatch.setenv("DICTEE_TOKENS", f"{TOKEN}, {OTHER}\n")
        monkeypatch.setenv("DICTEE_RATE_LIMIT", "5")
        s = load_settings()
        assert s.tokens == (TOKEN, OTHER)
        assert s.auth_required and s.rate_limit == 5

    def test_unset_means_open(self, monkeypatch):
        monkeypatch.delenv("DICTEE_TOKENS", raising=False)
        monkeypatch.delenv("DICTEE_RATE_LIMIT", raising=False)
        s = load_settings()
        assert s.tokens == () and not s.auth_required and s.rate_limit == 30

    def test_set_but_empty_fails_closed(self, monkeypatch):
        monkeypatch.setenv("DICTEE_TOKENS", " , ")
        s = load_settings()
        assert s.auth_required and s.tokens == ()

    def test_tokens_never_in_repr(self, monkeypatch):
        monkeypatch.setenv("DICTEE_TOKENS", TOKEN)
        assert TOKEN not in repr(load_settings())

    def test_polish_log_refused_with_tokens(self, monkeypatch, tmp_path):
        monkeypatch.setenv("POLISH_LOG", str(tmp_path / "log.jsonl"))
        monkeypatch.setenv("DICTEE_TOKENS", TOKEN)
        assert load_settings().polish_log is None
        monkeypatch.delenv("DICTEE_TOKENS")
        assert load_settings().polish_log is not None

    def test_parse_tokens(self):
        assert parse_tokens(None) == ()
        assert parse_tokens("a,b c\nd") == ("a", "b", "c", "d")


# ------------------------------------------------------------------ token

class TestToken:
    @pytest.mark.parametrize("method,path", [
        ("post", "/jobs"), ("post", "/polish"), ("post", "/transcribe"), ("post", "/transcribe/streaming"),
        ("get", "/jobs"), ("get", "/jobs/abc"), ("post", "/jobs/abc/cancel"), ("get", "/health"),
        ("get", "/progress"), ("post", "/eject"), ("post", "/switch-model"), ("get", "/settings/hf-token"),
    ])
    def test_required_on_api_routes(self, secured, method, path):
        response = getattr(secured, method)(path)
        assert response.status_code == 401
        assert response.headers["www-authenticate"] == "Bearer"

    def test_wrong_token_refused(self, secured):
        assert _post(secured, headers=_auth("nope")).status_code == 401
        assert _post(secured, headers={"Authorization": TOKEN}).status_code == 401  # no "Bearer"
        assert _post(secured, headers={"Authorization": "Basic " + TOKEN}).status_code == 401

    def test_valid_token_transcribes(self, secured):
        response = _post(secured, "/transcribe/streaming", b"bonjour", headers=_auth())
        assert response.status_code == 200
        assert response.json()["text"] == "bonjour"
        job = _post(secured, headers=_auth(OTHER), data={"quick": "true"}).json()
        assert _wait(secured, job["id"], _auth(OTHER))["status"] == "done"

    def test_public_routes_stay_open(self, secured):
        assert secured.get("/healthz").status_code == 200
        assert secured.get("/").status_code == 200
        assert secured.get("/static/app.js").status_code == 200

    def test_cors_preflight_needs_no_token(self, secured):
        response = secured.options("/jobs", headers={"Origin": "https://example.org",
                                                     "Access-Control-Request-Method": "POST"})
        assert response.status_code == 200

    def test_no_token_needed_when_unset(self, client):
        assert _post(client, "/transcribe/streaming").status_code == 200

    def test_empty_token_list_refuses_everything(self, make_client):
        client = make_client(tokens=(), auth_required=True)
        assert _post(client, headers=_auth("")).status_code == 401
        assert _post(client, headers=_auth("anything")).status_code == 401

    def test_jobs_are_private_to_their_token(self, secured):
        job = _post(secured, headers=_auth(), data={"quick": "true"}).json()
        _wait(secured, job["id"], _auth())
        assert secured.get(f"/jobs/{job['id']}", headers=_auth(OTHER)).status_code == 404
        assert secured.post(f"/jobs/{job['id']}/cancel", headers=_auth(OTHER)).status_code == 404
        assert secured.get(f"/jobs/{job['id']}/export", headers=_auth(OTHER)).status_code == 404
        assert secured.get("/jobs", headers=_auth(OTHER)).json()["jobs"] == []
        assert [j["id"] for j in secured.get("/jobs", headers=_auth()).json()["jobs"]] == [job["id"]]
        assert secured.get("/progress", headers=_auth(OTHER)).json()["job_id"] is None
        assert secured.get("/progress", headers=_auth()).json()["job_id"] == job["id"]

    def test_token_not_in_job(self, secured):
        job = _post(secured, headers=_auth(), data={"quick": "true"}).json()
        assert TOKEN not in str(job) and token_id(TOKEN) not in str(job)


# ------------------------------------------------------------- rate limit

class TestRateLimit:
    def test_429_beyond_the_limit(self, make_client):
        client = make_client(tokens=(TOKEN, OTHER), auth_required=True, rate_limit=3)
        for _ in range(3):
            assert _post(client, "/transcribe/streaming", headers=_auth()).status_code == 200
        response = _post(client, "/transcribe/streaming", headers=_auth())
        assert response.status_code == 429
        assert int(response.headers["retry-after"]) >= 1
        # Per token: the other one still has its quota
        assert _post(client, "/transcribe/streaming", headers=_auth(OTHER)).status_code == 200

    def test_polling_is_not_counted(self, make_client):
        client = make_client(tokens=(TOKEN,), auth_required=True, rate_limit=1)
        job = _post(client, headers=_auth(), data={"quick": "true"}).json()
        for _ in range(10):
            assert client.get(f"/jobs/{job['id']}", headers=_auth()).status_code == 200
        assert _post(client, headers=_auth()).status_code == 429

    def test_zero_means_no_limit(self, make_client):
        client = make_client(tokens=(TOKEN,), auth_required=True, rate_limit=0)
        for _ in range(40):
            assert _post(client, "/transcribe/streaming", headers=_auth()).status_code == 200

    def test_sliding_window(self):
        now = [0.0]
        limiter = RateLimiter(2, window=60, clock=lambda: now[0])
        assert limiter.allow("a")[0] and limiter.allow("a")[0]
        allowed, retry = limiter.allow("a")
        assert not allowed and retry == 60
        now[0] = 30
        assert not limiter.allow("a")[0]
        now[0] = 60.5
        assert limiter.allow("a")[0]


# ---------------------------------------------------------- audio removal

@pytest.fixture
def upload_dir(tmp_path, monkeypatch):
    """Uploads land in an empty directory of their own, checked after each case."""
    d = tmp_path / "uploads"
    d.mkdir()
    monkeypatch.setattr(tempfile, "tempdir", str(d))
    return d


def _empty_soon(d, timeout=5):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if not list(d.iterdir()):
            return True
        time.sleep(0.02)
    return not list(d.iterdir())


class TestAudioRemoval:
    def test_after_success(self, secured, upload_dir):
        assert _post(secured, "/transcribe", headers=_auth()).status_code == 200
        assert _empty_soon(upload_dir)

    def test_after_job_success(self, secured, upload_dir):
        job = _post(secured, headers=_auth(), data={"quick": "true"}).json()
        assert _wait(secured, job["id"], _auth())["status"] == "done"
        assert _empty_soon(upload_dir)

    def test_after_decode_error(self, secured, upload_dir):
        assert _post(secured, "/transcribe", b"bad", headers=_auth()).status_code == 400
        assert _empty_soon(upload_dir)

    def test_after_unexpected_error(self, make_client, upload_dir):
        client = make_client(raise_server_exceptions=False, tokens=(TOKEN,), auth_required=True)
        assert _post(client, "/transcribe", b"boom", headers=_auth()).status_code >= 500
        assert _empty_soon(upload_dir)

    def test_after_cancel_while_running(self, secured, upload_dir):
        job = _post(secured, content=b"slow", headers=_auth()).json()
        deadline = time.monotonic() + 5
        while secured.get(f"/jobs/{job['id']}", headers=_auth()).json()["status"] == "queued":
            assert time.monotonic() < deadline
            time.sleep(0.02)
        secured.post(f"/jobs/{job['id']}/cancel", headers=_auth())
        assert _wait(secured, job["id"], _auth())["status"] == "cancelled"
        assert _empty_soon(upload_dir)

    def test_after_cancel_while_queued(self, secured, upload_dir):
        slow = _post(secured, content=b"slow", headers=_auth()).json()
        queued = _post(secured, content=b"queued audio", headers=_auth()).json()
        assert secured.post(f"/jobs/{queued['id']}/cancel", headers=_auth()).json()["status"] == "cancelled"
        secured.post(f"/jobs/{slow['id']}/cancel", headers=_auth())
        _wait(secured, slow["id"], _auth())
        assert _empty_soon(upload_dir)

    def test_after_rejected_upload(self, make_client, upload_dir):
        client = make_client(tokens=(TOKEN,), auth_required=True, max_upload_mb=1)
        assert _post(client, content=b"x" * (2 * 1024 * 1024), headers=_auth()).status_code == 413
        assert _post(client, content=b"", headers=_auth()).status_code == 400
        assert _empty_soon(upload_dir)

    def test_removed_before_polish(self, make_client, upload_dir):
        """Deleted as soon as Whisper is done, while the LLM is still polishing."""
        from tests.fake_polish import FakePolishClient
        polish = FakePolishClient(delay=1.0)
        client = make_client(polish_client=polish, tokens=(TOKEN,), auth_required=True)
        job = _post(client, content=b"un texte a polir", headers=_auth(), data={"polish": "true"}).json()
        assert polish.started.wait(5)
        assert not list(upload_dir.iterdir())
        assert _wait(client, job["id"], _auth())["status"] == "done"


# ------------------------------------------------------------------- logs

SECRET_TEXT = "zanzibar quokka mirabelle"


class TestLogs:
    def test_no_transcript_nor_audio_in_logs(self, make_client, caplog):
        from tests.fake_polish import FakePolishClient
        caplog.set_level(logging.DEBUG)
        client = make_client(polish_client=FakePolishClient(reply=lambda raw: raw.upper()),
                             tokens=(TOKEN,), auth_required=True)
        audio = SECRET_TEXT.encode()
        assert _post(client, "/transcribe", audio, headers=_auth(), data={"polish": "true"}).status_code == 200
        job = _post(client, content=audio, headers=_auth(), data={"quick": "true", "polish": "true"}).json()
        result = _wait(client, job["id"], _auth())["result"]
        assert result["text"] == SECRET_TEXT
        assert result["polished_text"] == SECRET_TEXT.upper()
        logged = caplog.text.lower()
        assert caplog.records  # the server did log something
        for word in SECRET_TEXT.split():
            assert word not in logged
        assert TOKEN not in caplog.text

    def test_polish_failure_logs_no_text(self, make_client, caplog):
        from tests.fake_polish import FakePolishClient
        caplog.set_level(logging.DEBUG)
        client = make_client(polish_client=FakePolishClient(reply=RuntimeError(f"model echoed {SECRET_TEXT}")),
                             tokens=(TOKEN,), auth_required=True)
        response = _post(client, "/transcribe", SECRET_TEXT.encode(), headers=_auth(), data={"polish": "true"})
        assert response.status_code == 200
        assert "zanzibar" not in caplog.text.lower()

    def test_refused_requests_log_no_token(self, secured, caplog):
        caplog.set_level(logging.DEBUG)
        _post(secured, headers=_auth("wrong-token-value-123"))
        assert "wrong-token-value-123" not in caplog.text
