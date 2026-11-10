"""HTTP API tests, with a model-free engine running inline."""
import os
import stat
import time
from unittest.mock import patch

import pytest

import app.main


def _post_audio(client, path="/transcribe", content=b"Hello there", data=None, filename="test.wav"):
    files = {"file": (filename, content, "audio/wav")}
    return client.post(path, files=files, data=data or {})


def _wait_job(client, job_id, timeout=10):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        job = client.get(f"/jobs/{job_id}").json()
        if job["status"] not in ("queued", "running"):
            return job
        time.sleep(0.02)
    raise AssertionError(f"job {job_id} did not finish")


class TestAppBasics:
    def test_app_created(self):
        assert app.main.app.title == "Speech-to-Text"

    def test_cors_middleware_added(self):
        assert any(m.cls.__name__ == "CORSMiddleware" for m in app.main.app.user_middleware)

    def test_index_returns_html(self, client):
        response = client.get("/")
        assert response.status_code == 200
        assert "text/html" in response.headers["content-type"]
        assert "/static/app.js" in response.text

    def test_index_works_from_another_directory(self, client, tmp_path, monkeypatch):
        monkeypatch.chdir(tmp_path)
        assert client.get("/").status_code == 200

    def test_static_assets(self, client):
        assert client.get("/static/app.js").status_code == 200
        assert client.get("/static/app.css").status_code == 200

    def test_healthz(self, client):
        assert client.get("/healthz").json() == {"status": "ok"}

    def test_unhandled_errors_are_json(self, make_client):
        client = make_client(raise_server_exceptions=False)
        with patch.object(app.main.system, "nvidia_gpu_info", side_effect=RuntimeError("nvml exploded")):
            response = client.get("/health")
        assert response.status_code == 500
        assert "nvml exploded" in response.json()["detail"]


class TestHealth:
    def test_health_fields(self, client):
        data = client.get("/health").json()
        for key in ("device", "model", "model_loaded", "status", "torch_cuda", "nvidia_gpu",
                    "gpu_available_for_model", "recommendation", "ffmpeg_available", "ffmpeg_path",
                    "diarization_available", "diarize_default", "valid_models", "languages", "busy"):
            assert key in data
        assert data["status"] in ("green", "yellow", "red")
        assert data["model"] == "base"
        assert data["device"] == "cpu"

    def test_health_reports_settings(self, make_client):
        data = make_client(model="small", diarize_default=True, max_upload_mb=10).get("/health").json()
        assert data["model"] == "small"
        assert data["diarize_default"] is True
        assert data["max_upload_mb"] == 10

    def test_health_reports_diarization_availability(self, client, monkeypatch):
        assert client.get("/health").json()["diarization_available"] is False
        monkeypatch.setenv("HF_TOKEN", "hf_from_env")
        data = client.get("/health").json()
        assert data["diarization_available"] is True
        assert data["hf_token_source"] == "env"

    def test_preload_loads_initial_model(self, make_client):
        client = make_client(preload=True, model="tiny")
        deadline = time.monotonic() + 5
        while not client.get("/health").json()["model_loaded"]:
            assert time.monotonic() < deadline
            time.sleep(0.02)
        assert client.engine.calls[0] == ("load", "tiny", "cpu")

    def test_cuda_falls_back_to_cpu_when_unavailable(self, make_client):
        with patch.object(app.main.system, "cuda_available", return_value=False):
            client = make_client(device="cuda")
        assert client.get("/health").json()["device"] == "cpu"


class TestProgress:
    def test_progress_fields(self, client):
        data = client.get("/progress").json()
        assert {"progress", "message", "stage", "done"} <= set(data)

    def test_progress_after_transcription(self, client):
        _post_audio(client)
        data = client.get("/progress").json()
        assert data["done"] is True
        assert data["progress"] == 1.0


class TestSwitchModel:
    def test_invalid_name(self, client):
        assert client.post("/switch-model", json={"model": "invalid"}).status_code == 400

    def test_invalid_device(self, client):
        assert client.post("/switch-model", json={"model": "base", "device": "mps"}).status_code == 400

    def test_valid_cpu(self, client):
        response = client.post("/switch-model", json={"model": "tiny", "device": "cpu"})
        assert response.status_code == 200
        data = response.json()
        assert (data["status"], data["model"], data["device"]) == ("success", "tiny", "cpu")
        assert client.engine.calls[-1] == ("load", "tiny", "cpu")
        health = client.get("/health").json()
        assert (health["model"], health["model_loaded"]) == ("tiny", True)

    def test_all_valid_models(self, client):
        for name in app.main.VALID_MODELS:
            assert client.post("/switch-model", json={"model": name, "device": "cpu"}).status_code == 200

    def test_cuda_unavailable(self, client):
        with patch.object(app.main.system, "cuda_available", return_value=False):
            response = client.post("/switch-model", json={"model": "base", "device": "cuda"})
        assert response.status_code == 400

    def test_load_failure(self, client, monkeypatch):
        monkeypatch.setenv("FAKE_ENGINE_LOAD_FAILS", "1")
        with patch.object(app.main.system, "cuda_available", return_value=True):
            response = client.post("/switch-model", json={"model": "large-v3", "device": "cuda"})
        assert response.status_code == 500
        assert "out of memory" in response.json()["detail"]
        assert client.get("/health").json()["model"] == "base"  # unchanged

    def test_no_wait_returns_job(self, client):
        response = client.post("/switch-model", json={"model": "small", "device": "cpu", "wait": False})
        assert response.status_code == 202
        job = _wait_job(client, response.json()["job"]["id"])
        assert job["status"] == "done"
        assert job["result"] == {"model": "small", "device": "cpu"}

    def test_transcription_uses_switched_model(self, client):
        client.post("/switch-model", json={"model": "medium", "device": "cpu"})
        _post_audio(client)
        assert client.engine.calls[-1][2]["model"] == "medium"


class TestEject:
    def test_eject(self, client):
        client.post("/switch-model", json={"model": "base", "device": "cpu"})
        assert client.post("/eject").json()["status"] == "success"
        assert client.get("/health").json()["model_loaded"] is False
        assert client.post("/eject").json()["status"] == "no model was loaded"

    def test_eject_no_wait(self, client):
        response = client.post("/eject?wait=false")
        assert response.status_code == 202
        assert _wait_job(client, response.json()["job"]["id"])["status"] == "done"


class TestTranscribe:
    def test_transcribe(self, client):
        response = _post_audio(client)
        assert response.status_code == 200
        data = response.json()
        assert data["text"] == "Hello there"
        assert data["segments"][0]["text"] == "Hello there"
        assert "speakers" not in data

    def test_options_forwarded(self, client, monkeypatch):
        monkeypatch.setenv("HF_TOKEN", "hf_test")
        _post_audio(client, data={"diarize": "true", "min_speakers": "1", "max_speakers": "3", "language": "fr"})
        options = client.engine.calls[-1][2]
        assert options["diarize"] is True
        assert (options["min_speakers"], options["max_speakers"], options["num_speakers"]) == (1, 3, None)
        assert options["language"] == "fr"

    def test_language_auto(self, client):
        _post_audio(client, data={"language": "auto"})
        assert client.engine.calls[-1][2]["language"] is None

    def test_invalid_language(self, client):
        assert _post_audio(client, data={"language": "klingon"}).status_code == 400

    def test_diarization(self, client, monkeypatch):
        monkeypatch.setenv("HF_TOKEN", "hf_test")
        data = _post_audio(client, data={"diarize": "true"}).json()
        assert data["speakers"] == ["SPEAKER_00"]
        assert data["text_with_speakers"] == "SPEAKER_00: Hello there"

    def test_diarize_default_from_settings(self, make_client, monkeypatch):
        monkeypatch.setenv("HF_TOKEN", "hf_test")
        client = make_client(diarize_default=True)
        _post_audio(client)
        assert client.engine.calls[-1][2]["diarize"] is True

    def test_diarize_without_hf_token(self, client):
        response = _post_audio(client, data={"diarize": "true"})
        assert response.status_code == 503
        assert "HF_TOKEN" in response.json()["detail"]
        assert not any(call[0] == "transcribe" for call in client.engine.calls)

    def test_invalid_speaker_hints(self, client, monkeypatch):
        monkeypatch.setenv("HF_TOKEN", "hf_test")
        assert _post_audio(client, data={"diarize": "true", "num_speakers": "0"}).status_code == 400
        assert _post_audio(client, data={"diarize": "true", "min_speakers": "3",
                                         "max_speakers": "2"}).status_code == 400

    def test_undecodable_audio(self, client):
        response = _post_audio(client, content=b"bad")
        assert response.status_code == 400
        assert "Invalid data" in response.json()["detail"]

    def test_unexpected_engine_error(self, client):
        response = _post_audio(client, content=b"boom")
        assert response.status_code == 500
        assert "KeyError" in response.json()["detail"]

    def test_no_file(self, client):
        assert client.post("/transcribe").status_code == 422

    def test_empty_file(self, client):
        response = _post_audio(client, content=b"")
        assert response.status_code == 400
        assert "empty" in response.json()["detail"]

    def test_file_too_large(self, make_client):
        client = make_client(max_upload_mb=1)
        response = _post_audio(client, content=b"x" * (1024 * 1024 + 1))
        assert response.status_code == 413

    def test_temp_file_removed(self, client):
        _post_audio(client)
        _post_audio(client, content=b"bad")
        paths = [call[1] for call in client.engine.calls if call[0] == "transcribe"]
        assert len(paths) == 2
        assert not any(os.path.exists(p) for p in paths)

    def test_temp_file_keeps_safe_extension(self, client):
        _post_audio(client, filename="../../evil name.m4a")
        path = client.engine.calls[-1][1]
        assert path.endswith(".m4a") and "evil" not in path

    def test_streaming(self, client):
        response = _post_audio(client, "/transcribe/streaming")
        assert response.status_code == 200
        assert response.json() == {"text": "Hello there", "language": "en", "task": "transcribe"}


class TestJobs:
    def test_job_lifecycle(self, client):
        response = _post_audio(client, "/jobs", data={"language": "de"})
        assert response.status_code == 202
        created = response.json()
        assert created["kind"] == "transcribe" and created["filename"] == "test.wav"
        job = _wait_job(client, created["id"])
        assert job["status"] == "done"
        assert job["result"]["text"] == "Hello there"
        assert job["result"]["language"] == "de"
        listed = client.get("/jobs").json()["jobs"]
        assert created["id"] in [j["id"] for j in listed]
        assert "result" not in listed[0]

    def test_job_error(self, client):
        job = _wait_job(client, _post_audio(client, "/jobs", content=b"bad").json()["id"])
        assert job["status"] == "error"
        assert job["error"]["code"] == "bad_audio"

    def test_quick_job(self, client):
        job = _wait_job(client, _post_audio(client, "/jobs", data={"quick": "true"}).json()["id"])
        assert job["result"]["task"] == "transcribe"

    def test_unknown_job(self, client):
        assert client.get("/jobs/nope").status_code == 404
        assert client.post("/jobs/nope/cancel").status_code == 404

    def test_cancel_running_job(self, client):
        job_id = _post_audio(client, "/jobs", content=b"slow").json()["id"]
        deadline = time.monotonic() + 5
        while client.get(f"/jobs/{job_id}").json()["status"] != "running":
            assert time.monotonic() < deadline
            time.sleep(0.01)
        assert client.post(f"/jobs/{job_id}/cancel").status_code == 200
        assert _wait_job(client, job_id)["status"] == "cancelled"
        # The engine stays usable
        assert _post_audio(client).status_code == 200

    def test_cancel_queued_job(self, client):
        slow_id = _post_audio(client, "/jobs", content=b"slow").json()["id"]
        queued = _post_audio(client, "/jobs").json()
        assert queued["message"].startswith("Queued")
        cancelled = client.post(f"/jobs/{queued['id']}/cancel").json()
        assert cancelled["status"] == "cancelled"
        client.post(f"/jobs/{slow_id}/cancel")
        assert _wait_job(client, slow_id)["status"] == "cancelled"

    def test_cancelled_job_maps_to_409(self):
        from app.jobs import Job
        with pytest.raises(app.main.HTTPException) as exc:
            app.main._raise_for_job(Job(kind="transcribe", status="cancelled"))
        assert exc.value.status_code == 409


class TestExport:
    def _done_job(self, client, **kwargs):
        return _wait_job(client, _post_audio(client, "/jobs", **kwargs).json()["id"])["id"]

    @pytest.mark.parametrize("fmt, expected", [
        ("txt", "Hello there\n"),
        ("srt", "1\n00:00:00,000 --> 00:00:01,500\nHello there\n"),
        ("vtt", "WEBVTT\n\n00:00:00.000 --> 00:00:01.500\nHello there\n"),
    ])
    def test_formats(self, client, fmt, expected):
        response = client.get(f"/jobs/{self._done_job(client, filename='meeting.mp3')}/export?format={fmt}")
        assert response.status_code == 200
        assert response.text == expected
        assert f'filename="meeting.{fmt}"' in response.headers["content-disposition"]

    def test_json(self, client):
        response = client.get(f"/jobs/{self._done_job(client)}/export?format=json")
        assert response.json()["text"] == "Hello there"

    def test_unknown_format(self, client):
        assert client.get(f"/jobs/{self._done_job(client)}/export?format=doc").status_code == 400

    def test_failed_job(self, client):
        job_id = self._done_job(client, content=b"bad")
        assert client.get(f"/jobs/{job_id}/export").status_code == 409

    def test_quick_job_has_no_subtitles(self, client):
        job_id = self._done_job(client, data={"quick": "true"})
        assert client.get(f"/jobs/{job_id}/export?format=srt").status_code == 409
        assert client.get(f"/jobs/{job_id}/export?format=txt").text == "Hello there\n"


VALID_CHECK = {"valid": True, "username": "remi", "model_access": True, "message": "Token OK"}


class TestHfTokenSettings:
    def test_not_configured(self, client):
        data = client.get("/settings/hf-token").json()
        assert data["configured"] is False
        assert data["source"] is None
        assert "pyannote" in data["model_url"]

    def test_save_token(self, client, isolated_hf_token):
        with patch.object(app.main.hf, "check_token", return_value=VALID_CHECK) as check:
            response = client.put("/settings/hf-token", json={"token": "hf_abcdefghijklmnop"})
        assert response.status_code == 200
        data = response.json()
        assert data["saved"] is True and data["check"]["username"] == "remi"
        assert "hf_abcdefghijklmnop" not in response.text  # never echoed back
        check.assert_called_once_with("hf_abcdefghijklmnop", "pyannote/speaker-diarization-community-1")
        assert isolated_hf_token.read_text() == "hf_abcdefghijklmnop"
        assert stat.S_IMODE(isolated_hf_token.stat().st_mode) == 0o600

        status = client.get("/settings/hf-token").json()
        assert (status["configured"], status["source"], status["masked"]) == (True, "file", "hf_…mnop")
        assert client.get("/health").json()["diarization_available"] is True

    def test_saved_token_enables_diarization(self, client):
        with patch.object(app.main.hf, "check_token", return_value=VALID_CHECK):
            client.put("/settings/hf-token", json={"token": "hf_abcdefghijklmnop"})
        assert _post_audio(client, data={"diarize": "true"}).status_code == 200

    def test_rejects_malformed_token(self, client):
        for token in ("", "short", "has spaces in it", "x" * 600):
            assert client.put("/settings/hf-token", json={"token": token}).status_code == 400

    def test_rejects_invalid_token(self, client, isolated_hf_token):
        check = {"valid": False, "username": None, "model_access": False, "message": "Hugging Face rejected this token"}
        with patch.object(app.main.hf, "check_token", return_value=check):
            response = client.put("/settings/hf-token", json={"token": "hf_abcdefghijklmnop"})
        assert response.status_code == 400
        assert "rejected" in response.json()["detail"]
        assert not isolated_hf_token.exists()

    def test_saves_when_hub_unreachable(self, client):
        check = {"valid": None, "username": None, "model_access": None, "message": "Could not reach Hugging Face"}
        with patch.object(app.main.hf, "check_token", return_value=check):
            response = client.put("/settings/hf-token", json={"token": "hf_abcdefghijklmnop"})
        assert response.status_code == 200
        assert response.json()["check"]["valid"] is None

    def test_skip_verification(self, client):
        with patch.object(app.main.hf, "check_token") as check:
            response = client.put("/settings/hf-token", json={"token": "hf_abcdefghijklmnop", "verify": False})
        assert response.status_code == 200
        check.assert_not_called()

    def test_env_token_takes_precedence(self, client, monkeypatch):
        monkeypatch.setenv("HF_TOKEN", "hf_environment_token")
        with patch.object(app.main.hf, "check_token", return_value=VALID_CHECK):
            data = client.put("/settings/hf-token", json={"token": "hf_abcdefghijklmnop"}).json()
        assert data["source"] == "env"
        assert "HF_TOKEN" in data["warning"]

    def test_delete_token(self, client, isolated_hf_token):
        with patch.object(app.main.hf, "check_token", return_value=VALID_CHECK):
            client.put("/settings/hf-token", json={"token": "hf_abcdefghijklmnop"})
        data = client.delete("/settings/hf-token").json()
        assert data["removed"] is True and data["configured"] is False
        assert not isolated_hf_token.exists()
        assert client.delete("/settings/hf-token").json()["removed"] is False

    def test_check_endpoint(self, client):
        assert client.post("/settings/hf-token/check").status_code == 404
        with patch.object(app.main.hf, "check_token", return_value=VALID_CHECK):
            client.put("/settings/hf-token", json={"token": "hf_abcdefghijklmnop"})
            data = client.post("/settings/hf-token/check").json()
        assert data["check"]["model_access"] is True

    def test_other_origins_cannot_change_the_token(self, client, isolated_hf_token):
        headers = {"Origin": "http://evil.example"}
        assert client.put("/settings/hf-token", json={"token": "hf_abcdefghijklmnop"},
                          headers=headers).status_code == 403
        assert client.delete("/settings/hf-token", headers=headers).status_code == 403
        assert not isolated_hf_token.exists()
        with patch.object(app.main.hf, "check_token", return_value=VALID_CHECK):
            same = client.put("/settings/hf-token", json={"token": "hf_abcdefghijklmnop"},
                              headers={"Origin": "http://testserver"})
        assert same.status_code == 200


# ============================================================
# LLM polish post-step (issue #6, phase 2)
# ============================================================

# A raw transcript and a polish the safety check accepts (a prompt few-shot pair)
RAW = "um so I was thinking we could uh meet on Monday, no wait, Tuesday at the office"
POLISHED = "I was thinking we could meet on Tuesday at the office."


def _fake(**kwargs):
    from tests.fake_polish import FakePolishClient
    return FakePolishClient(**kwargs)


def _polish_job(client, content=RAW, data=None):
    response = _post_audio(client, "/jobs", content=content.encode(), data={"polish": "true", **(data or {})})
    assert response.status_code == 202
    return _wait_job(client, response.json()["id"])


class TestPolish:
    def test_off_by_default(self, client):
        data = _post_audio(client, content=RAW.encode()).json()
        assert data["text"] == RAW
        assert "polish" not in data and "polished_text" not in data
        assert client.polish_client.calls == []

    def test_polished(self, make_client):
        client = make_client(polish_client=_fake(reply=POLISHED))
        data = _post_audio(client, content=RAW.encode(), data={"polish": "true"}).json()
        assert data["text"] == RAW  # always the raw Whisper transcript
        assert data["polished_text"] == POLISHED
        assert data["polish"]["applied"] is True
        assert data["polish"]["model"] == "fake-polish:7b"
        assert data["polish"]["fallback_reason"] is None
        assert isinstance(data["polish"]["latency_ms"], int)
        assert data["warnings"] == []
        assert client.polish_client.calls == [RAW]
        assert data["segments"][0]["text"] == RAW  # subtitles stay raw

    def test_language_passed_to_prompt(self, make_client):
        client = make_client(polish_client=_fake(reply=POLISHED))
        _post_audio(client, content=RAW.encode(), data={"polish": "true", "language": "fr"})
        assert "Detected language: French" in client.polish_client.messages[-1]["content"]

    def test_default_from_settings(self, make_client):
        client = make_client(polish_default=True, polish_client=_fake(reply=POLISHED))
        assert _post_audio(client, content=RAW.encode()).json()["polished_text"] == POLISHED
        data = _post_audio(client, content=RAW.encode(), data={"polish": "false"}).json()
        assert "polished_text" not in data
        assert len(client.polish_client.calls) == 1

    def test_polish_param_not_forwarded_to_engine(self, make_client):
        client = make_client(polish_client=_fake(reply=POLISHED))
        job = _polish_job(client)
        assert job["params"]["polish"] is True
        options = client.engine.calls[-1][2]
        assert "polish" not in options

    def test_streaming(self, make_client):
        client = make_client(polish_client=_fake(reply=POLISHED))
        data = _post_audio(client, "/transcribe/streaming", content=RAW.encode(), data={"polish": "true"}).json()
        assert data["text"] == RAW
        assert data["polished_text"] == POLISHED

    def test_quick_job(self, make_client):
        client = make_client(polish_client=_fake(reply=POLISHED))
        job = _polish_job(client, data={"quick": "true"})
        assert job["status"] == "done"
        assert job["result"]["polished_text"] == POLISHED

    def test_polish_stage_reported(self, make_client):
        client = make_client(polish_client=_fake(reply=POLISHED, delay=0.5))
        job_id = _post_audio(client, "/jobs", content=RAW.encode(), data={"polish": "true"}).json()["id"]
        assert client.polish_client.started.wait(5)
        job = client.get(f"/jobs/{job_id}").json()
        assert job["status"] == "running"
        assert job["stage"] == "polish"
        assert 0.97 <= job["progress"] < 1.0
        assert client.get("/progress").json()["stage"] == "polish"
        assert _wait_job(client, job_id)["status"] == "done"


class TestPolishNeverFailsTheJob:
    """Whatever the LLM does, the job succeeds with the raw text."""

    @pytest.mark.parametrize("reply, reason", [
        (RuntimeError("model crashed"), "error"),
        (ConnectionError("refused"), "error"),
        ("__polish_error__", "error"),
        ("__polish_timeout__", "timeout"),
        ("The meeting starts at nine.", "safety_check:"),  # an answer, not the text
        ("Je pensais qu'on pourrait se voir mardi au bureau.", "safety_check:"),  # a translation
        ("", "safety_check:empty"),
    ])
    def test_fallbacks(self, make_client, reply, reason):
        from app.polish import PolishError, PolishTimeout
        reply = {"__polish_error__": PolishError("HTTP 500"),
                 "__polish_timeout__": PolishTimeout("no answer within 5s")}.get(reply, reply)
        client = make_client(polish_client=_fake(reply=reply))
        for path in ("/transcribe", "/transcribe/streaming"):
            response = _post_audio(client, path, content=RAW.encode(), data={"polish": "true"})
            assert response.status_code == 200
            data = response.json()
            assert data["text"] == RAW
            assert "polished_text" not in data
            assert data["polish"]["applied"] is False
            assert data["polish"]["fallback_reason"].startswith(reason)
            assert any("not polished" in w for w in data["warnings"])

    def test_slow_llm_times_out_with_the_real_client(self, make_client):
        """The real PolishClient's hard timeout, inside a job: done with the raw text."""
        import httpx

        from app.polish import PolishClient

        def handler(request):
            time.sleep(3)
            return httpx.Response(200, json={"choices": [{"message": {"content": POLISHED}}]})

        llm = PolishClient("http://llm.test/v1", "m", timeout=0.5, transport=httpx.MockTransport(handler))
        client = make_client(polish_client=llm)
        started = time.monotonic()
        job = _polish_job(client)
        assert time.monotonic() - started < 2.5
        assert job["status"] == "done"
        assert job["result"]["text"] == RAW
        assert job["result"]["polish"]["fallback_reason"] == "timeout"

    def test_unreachable_endpoint_with_the_real_client(self, make_client):
        import httpx

        from app.polish import PolishClient

        def handler(request):
            raise httpx.ConnectError("connection refused")

        client = make_client(polish_client=PolishClient("http://llm.test/v1", transport=httpx.MockTransport(handler)))
        job = _polish_job(client)
        assert job["status"] == "done"
        assert job["result"]["polish"]["fallback_reason"] == "error"
        assert client.get("/health").json()["polish_available"] is False

    def test_bug_in_polish_step(self, make_client):
        client = make_client(polish_client=_fake(reply=POLISHED))
        with patch("app.polish.polish", side_effect=ValueError("bug")):
            job = _polish_job(client)
        assert job["status"] == "done"
        assert job["result"]["text"] == RAW
        assert job["result"]["polish"]["fallback_reason"] == "error"

    def test_bug_outside_the_helper_thread(self, make_client, monkeypatch):
        client = make_client(polish_client=_fake(reply=POLISHED))
        monkeypatch.setattr("app.polish.PolishResult.to_fields", lambda self: 1 / 0, raising=True)
        job = _polish_job(client)
        assert job["status"] == "done"
        assert job["result"]["text"] == RAW
        assert job["result"]["polish"]["fallback_reason"] == "error"
        assert "polished_text" not in job["result"]

    def test_whisper_errors_are_still_errors(self, make_client):
        client = make_client(polish_client=_fake(reply=POLISHED))
        job = _polish_job(client, content="bad")
        assert job["status"] == "error"
        assert job["error"]["code"] == "bad_audio"
        assert client.polish_client.calls == []


class TestPolishUnsupported:
    def test_diarization_not_polished(self, make_client, monkeypatch):
        monkeypatch.setenv("HF_TOKEN", "hf_test")
        client = make_client(polish_client=_fake(reply=POLISHED))
        data = _post_audio(client, content=RAW.encode(), data={"polish": "true", "diarize": "true"}).json()
        assert data["text"] == RAW
        assert data["polish"] == {"applied": False, "model": "fake-polish:7b", "latency_ms": None,
                                  "fallback_reason": "unsupported"}
        assert any("speaker identification" in w for w in data["warnings"])
        assert client.polish_client.calls == []

    def test_long_transcript_not_polished(self, make_client):
        client = make_client(polish_client=_fake(reply=POLISHED))
        long_text = " ".join(["word"] * 401)
        job = _polish_job(client, content=long_text)
        assert job["status"] == "done"
        assert job["result"]["polish"]["fallback_reason"] == "unsupported"
        assert client.polish_client.calls == []


class TestPolishCancel:
    def test_cancel_during_polish(self, make_client):
        client = make_client(polish_client=_fake(reply=POLISHED, delay=1.0))
        job_id = _post_audio(client, "/jobs", content=RAW.encode(), data={"polish": "true"}).json()["id"]
        assert client.polish_client.started.wait(5)
        started = time.monotonic()
        client.post(f"/jobs/{job_id}/cancel")
        job = _wait_job(client, job_id)
        assert job["status"] == "cancelled"
        assert job["result"] is None
        assert time.monotonic() - started < 0.8  # answered before the LLM finishes

        # the next job waits for the abandoned LLM call, then runs normally
        follow_up = _polish_job(client)
        assert follow_up["status"] == "done"
        assert follow_up["result"]["polished_text"] == POLISHED

    def test_cancel_before_polish(self, make_client):
        client = make_client(polish_client=_fake(reply=POLISHED))
        job_id = _post_audio(client, "/jobs", content=b"slow", data={"polish": "true"}).json()["id"]
        time.sleep(0.2)
        client.post(f"/jobs/{job_id}/cancel")
        assert _wait_job(client, job_id)["status"] == "cancelled"
        assert client.polish_client.calls == []


class TestPolishHealth:
    def test_fields(self, make_client):
        client = make_client(polish_default=True, polish_model="qwen2.5:7b-instruct-q4_K_M")
        data = client.get("/health").json()
        assert data["polish_available"] is True
        assert data["polish_default"] is True
        assert data["polish_model"] == "qwen2.5:7b-instruct-q4_K_M"

    def test_defaults(self, client):
        data = client.get("/health").json()
        assert data["polish_default"] is False
        assert data["polish_model"] == "qwen2.5:7b-instruct-q4_K_M"

    @pytest.mark.parametrize("available", [False, RuntimeError("probe exploded")])
    def test_unavailable(self, make_client, available):
        client = make_client(polish_client=_fake(available=available))
        assert client.get("/health").json()["polish_available"] is False

    def test_probe_is_cached(self, client):
        for _ in range(3):
            client.get("/health")
        assert client.polish_client.probes == 1

    def test_api_key_never_reported(self, make_client):
        client = make_client(polish_api_key="sk-secret-value")
        assert "sk-secret-value" not in client.get("/health").text


class TestPolishExport:
    def _job(self, client, **data):
        return _polish_job(client, data=data)["id"]

    def test_txt(self, make_client):
        client = make_client(polish_client=_fake(reply=POLISHED))
        job_id = self._job(client)
        assert client.get(f"/jobs/{job_id}/export?format=txt").text == RAW + "\n"
        assert client.get(f"/jobs/{job_id}/export?format=txt&polished=true").text == POLISHED + "\n"

    def test_json(self, make_client):
        client = make_client(polish_client=_fake(reply=POLISHED))
        job_id = self._job(client)
        raw = client.get(f"/jobs/{job_id}/export?format=json").json()
        assert raw["text"] == RAW and raw["polished_text"] == POLISHED
        polished = client.get(f"/jobs/{job_id}/export?format=json&polished=true").json()
        assert polished["text"] == POLISHED
        assert polished["raw_text"] == RAW
        assert polished["segments"] == raw["segments"]

    def test_subtitles_stay_raw(self, make_client):
        client = make_client(polish_client=_fake(reply=POLISHED))
        job_id = self._job(client)
        for fmt in ("srt", "vtt"):
            raw = client.get(f"/jobs/{job_id}/export?format={fmt}").text
            assert client.get(f"/jobs/{job_id}/export?format={fmt}&polished=true").text == raw
            assert RAW in raw

    def test_falls_back_to_raw_without_polished_text(self, make_client):
        client = make_client(polish_client=_fake(reply=RuntimeError("down")))
        job_id = self._job(client)
        assert client.get(f"/jobs/{job_id}/export?format=txt&polished=true").text == RAW + "\n"
        data = client.get(f"/jobs/{job_id}/export?format=json&polished=true").json()
        assert data["text"] == RAW and "raw_text" not in data


class TestPolishEject:
    def test_eject_unloads_polish_model(self, client):
        _post_audio(client)  # loads Whisper
        data = client.post("/eject").json()
        assert data["status"] == "success"
        assert data["polish_unloaded"] is True
        assert client.polish_client.unloads == 1

    def test_eject_with_only_the_polish_model_loaded(self, client):
        data = client.post("/eject").json()
        assert data["status"] == "success"
        assert data["polish_unloaded"] is True

    def test_nothing_loaded(self, make_client):
        client = make_client(polish_client=_fake(loaded=False))
        data = client.post("/eject").json()
        assert data["status"] == "no model was loaded"
        assert data["polish_unloaded"] is False

    def test_unload_failure_does_not_fail_eject(self, make_client):
        llm = _fake()
        llm.unload = lambda: 1 / 0
        client = make_client(polish_client=llm)
        _post_audio(client)
        response = client.post("/eject")
        assert response.status_code == 200
        assert response.json()["polish_unloaded"] is False


class TestPolishSettingsWiring:
    def test_dictionary_and_log(self, make_client, tmp_path):
        import json

        dictionary = tmp_path / "dict.txt"
        dictionary.write_text("Kubernetes\n# comment\nRémi\n", encoding="utf-8")
        log_path = tmp_path / "polish.jsonl"
        client = make_client(polish_dictionary=str(dictionary), polish_log=str(log_path),
                             polish_client=_fake(reply=POLISHED))
        _polish_job(client)
        assert "Kubernetes, Rémi" in client.polish_client.messages[0]["content"]
        record = json.loads(log_path.read_text(encoding="utf-8").splitlines()[-1])
        assert record["raw"] == RAW and record["polished"] == POLISHED and record["applied"] is True

    def test_real_client_built_from_settings(self):
        from app.config import Settings
        from app.polish import PolishClient, polisher_from_settings

        polisher = polisher_from_settings(Settings(polish_base_url="http://ollama:11434/v1", polish_model="m:1",
                                                   polish_api_key="k", polish_timeout=7.0))
        assert isinstance(polisher.client, PolishClient)
        assert (polisher.client.base_url, polisher.client.model, polisher.client.api_key,
                polisher.client.timeout) == ("http://ollama:11434/v1", "m:1", "k", 7.0)
