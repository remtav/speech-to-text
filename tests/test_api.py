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
