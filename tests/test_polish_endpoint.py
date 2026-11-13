"""Issue #35 (server side): ``POST /polish`` and the ``prompt`` field of ``POST /jobs``.

Long dictations are transcribed block by block: each block goes to ``POST /jobs`` with the end of the
previous one as ``prompt``; the joined text is then polished with ``POST /polish``.
"""
import logging
import time

import pytest

from tests.fake_polish import FakePolishClient

TOKEN = "tok-alice-0123456789abcdef"
OTHER = "tok-bob-0123456789abcdef"
RAW = "um so I was thinking we could uh meet on Monday, no wait, Tuesday at the office"
POLISHED = "I was thinking we could meet on Tuesday at the office."
SECRET = "zanzibar quokka mirabelle"


def _auth(token=TOKEN):
    return {"Authorization": f"Bearer {token}"}


def _polish(client, text=RAW, language="en", headers=None, **extra):
    body = {"text": text, **({"language": language} if language is not None else {}), **extra}
    return client.post("/polish", json=body, headers=headers or {})


def _wait(client, job_id, headers=None, timeout=10):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        job = client.get(f"/jobs/{job_id}", headers=headers or {}).json()
        if job["status"] not in ("queued", "running"):
            return job
        time.sleep(0.02)
    raise AssertionError("job did not finish")


def _post_job(client, content=b"Hello there", data=None, headers=None):
    return client.post("/jobs", files={"file": ("a.wav", content, "audio/wav")}, data=data or {},
                       headers=headers or {})


@pytest.fixture
def secured(make_client):
    return make_client(polish_client=FakePolishClient(reply=lambda raw: raw.upper()),
                       tokens=(TOKEN, OTHER), auth_required=True, rate_limit=30)


class TestPolishEndpoint:
    def test_polished_through_the_queue(self, make_client):
        client = make_client(polish_client=FakePolishClient(reply=POLISHED))
        response = _polish(client)
        assert response.status_code == 202
        job = response.json()
        assert job["kind"] == "polish" and job["status"] in ("queued", "running", "done")
        done = _wait(client, job["id"])
        assert done["status"] == "done"
        assert done["result"]["polished_text"] == POLISHED
        assert done["result"]["text"] == RAW
        assert done["result"]["language"] == "en"
        assert done["result"]["polish"] == {"applied": True, "model": "fake-polish:7b",
                                            "latency_ms": done["result"]["polish"]["latency_ms"],
                                            "fallback_reason": None}
        assert client.polish_client.calls == [RAW]
        assert client.engine.calls == []  # no Whisper, no audio

    def test_language_reaches_the_polish_step(self, make_client):
        client = make_client(polish_client=FakePolishClient(reply=POLISHED))
        _wait(client, _polish(client, language="fr").json()["id"])
        assert "French" in client.polish_client.messages[-1]["content"]

    @pytest.mark.parametrize("language", [None, "", "auto"])
    def test_language_is_optional(self, client, language):
        done = _wait(client, _polish(client, language=language).json()["id"])
        assert done["status"] == "done" and done["result"]["language"] is None

    def test_fallback_keeps_the_job_successful(self, make_client):
        client = make_client(polish_client=FakePolishClient(reply=RuntimeError("boom")))
        done = _wait(client, _polish(client).json()["id"])
        assert done["status"] == "done"
        assert "polished_text" not in done["result"]
        assert done["result"]["polish"]["applied"] is False
        assert done["result"]["polish"]["fallback_reason"] == "error"
        assert done["result"]["text"] == RAW

    def test_safety_check_fallback(self, make_client):
        client = make_client(polish_client=FakePolishClient(reply="Completely different content."))
        done = _wait(client, _polish(client).json()["id"])
        assert done["result"]["polish"]["fallback_reason"].startswith("safety_check:")
        assert "polished_text" not in done["result"]

    def test_too_long_text_is_not_polished(self, client):
        done = _wait(client, _polish(client, text="word " * 500).json()["id"])
        assert done["result"]["polish"] == {"applied": False, "model": "fake-polish:7b", "latency_ms": None,
                                            "fallback_reason": "unsupported"}

    @pytest.mark.parametrize("body", [{}, {"text": ""}, {"text": "   "}, {"text": 42}, {"text": None},
                                      {"text": "hello", "language": 3}])
    def test_invalid_body(self, client, body):
        assert client.post("/polish", json=body).status_code == 400

    def test_unsupported_language(self, client):
        assert _polish(client, language="xx").status_code == 400

    def test_body_must_be_json_object(self, client):
        assert client.post("/polish", content=b"nope").status_code == 422

    def test_text_limit(self, client):
        assert _polish(client, text="a" * 100_001).status_code == 413

    def test_queued_behind_other_jobs_in_order(self, make_client):
        client = make_client(polish_client=FakePolishClient(reply=POLISHED))
        slow = _post_job(client, b"slow").json()
        job = _polish(client).json()
        assert _wait(client, slow["id"])["status"] == "done"
        assert _wait(client, job["id"])["status"] == "done"

    def test_cancel(self, make_client):
        client = make_client(polish_client=FakePolishClient(reply=POLISHED, delay=2))
        job = _polish(client).json()
        client.polish_client.started.wait(5)
        assert client.post(f"/jobs/{job['id']}/cancel").status_code == 200
        assert _wait(client, job["id"])["status"] == "cancelled"

    def test_text_not_listed_nor_in_params(self, client):
        job = _polish(client, text=SECRET).json()
        assert SECRET not in str(job["params"])
        _wait(client, job["id"])
        assert SECRET not in str(client.get("/jobs").json())
        assert client.app.state.jobs.get(job["id"]).text is None  # dropped at the end

    def test_export_refused(self, client):
        job = _polish(client).json()
        _wait(client, job["id"])
        assert client.get(f"/jobs/{job['id']}/export").status_code == 409


class TestPolishSecurity:
    def test_token_required(self, secured):
        response = _polish(secured)
        assert response.status_code == 401 and response.headers["www-authenticate"] == "Bearer"
        assert _polish(secured, headers=_auth("wrong")).status_code == 401

    def test_valid_token(self, secured):
        job = _polish(secured, headers=_auth()).json()
        assert _wait(secured, job["id"], _auth())["result"]["polished_text"] == RAW.upper()

    def test_open_without_tokens(self, client):
        assert _polish(client).status_code == 202

    def test_job_private_to_its_token(self, secured):
        job = _polish(secured, headers=_auth()).json()
        _wait(secured, job["id"], _auth())
        assert secured.get(f"/jobs/{job['id']}", headers=_auth(OTHER)).status_code == 404
        assert secured.post(f"/jobs/{job['id']}/cancel", headers=_auth(OTHER)).status_code == 404
        assert secured.get("/jobs", headers=_auth(OTHER)).json()["jobs"] == []

    def test_rate_limited_with_the_other_work_routes(self, make_client):
        client = make_client(tokens=(TOKEN,), auth_required=True, rate_limit=2)
        assert _polish(client, headers=_auth()).status_code == 202
        assert _post_job(client, headers=_auth()).status_code == 202
        limited = _polish(client, headers=_auth())
        assert limited.status_code == 429 and int(limited.headers["retry-after"]) >= 1

    def test_polling_is_not_counted(self, make_client):
        client = make_client(tokens=(TOKEN,), auth_required=True, rate_limit=1)
        job = _polish(client, headers=_auth()).json()
        for _ in range(5):
            assert client.get(f"/jobs/{job['id']}", headers=_auth()).status_code == 200

    def test_no_text_in_logs(self, make_client, caplog):
        caplog.set_level(logging.DEBUG)
        client = make_client(polish_client=FakePolishClient(reply=lambda raw: raw.upper()),
                             tokens=(TOKEN,), auth_required=True)
        job = _polish(client, text=SECRET, headers=_auth()).json()
        assert _wait(client, job["id"], _auth())["result"]["polished_text"] == SECRET.upper()
        failing = make_client(polish_client=FakePolishClient(reply=RuntimeError(f"echoed {SECRET}")))
        _wait(failing, _polish(failing, text=SECRET).json()["id"])
        assert caplog.records
        for word in SECRET.split():
            assert word not in caplog.text.lower()
        assert TOKEN not in caplog.text


class TestPromptField:
    def test_forwarded_to_the_engine(self, client):
        job = _post_job(client, data={"quick": "true", "prompt": "  Bonjour, Maëlle.  "}).json()
        assert _wait(client, job["id"])["status"] == "done"
        options = client.engine.calls[-1][2]
        assert options["prompt"] == "Bonjour, Maëlle."

    def test_without_prompt_nothing_changes(self, client):
        job = _post_job(client, data={"quick": "true"}).json()
        _wait(client, job["id"])
        assert "prompt" not in client.engine.calls[-1][2]

    @pytest.mark.parametrize("value", ["", "   "])
    def test_blank_prompt_is_ignored(self, client, value):
        _wait(client, _post_job(client, data={"quick": "true", "prompt": value}).json()["id"])
        assert "prompt" not in client.engine.calls[-1][2]

    def test_also_for_full_jobs(self, client):
        _wait(client, _post_job(client, data={"prompt": "Tuesday"}).json()["id"])
        assert client.engine.calls[-1][2]["prompt"] == "Tuesday"

    def test_too_long(self, client):
        assert _post_job(client, data={"prompt": "a" * 1001}).status_code == 400

    def test_not_echoed_in_the_job(self, client):
        job = _post_job(client, data={"quick": "true", "prompt": SECRET}).json()
        done = _wait(client, job["id"])
        assert SECRET not in str(job) and SECRET not in str(done)
        assert SECRET not in str(client.get("/jobs").json())

    def test_not_logged(self, make_client, caplog):
        caplog.set_level(logging.DEBUG)
        client = make_client()
        _wait(client, _post_job(client, data={"quick": "true", "prompt": SECRET}).json()["id"])
        assert caplog.records and SECRET not in caplog.text.lower()

    def test_transcribe_routes_unchanged(self, client):
        assert client.post("/transcribe/streaming", files={"file": ("a.wav", b"hi", "audio/wav")},
                           data={"prompt": SECRET}).status_code == 200
        assert "prompt" not in client.engine.calls[-1][2]
