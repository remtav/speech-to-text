"""Tests for the job queue, configuration, host inspection, exports and token helpers."""
import os
import time
from unittest.mock import MagicMock, patch

import pytest

from app import config, formats, hf, system
from app.jobs import JobManager
from app.worker import InlineBackend
from tests.fake_engine import FakeEngine


# ------------------------------------------------------------------- jobs

@pytest.fixture
def manager():
    m = JobManager(InlineBackend(FakeEngine()), "base", "cpu", history=3)
    m.start()
    yield m
    m.stop()


def _audio(tmp_path, content, name="a.wav"):
    path = tmp_path / name
    path.write_text(content)
    return str(path)


def _wait(job, timeout=10):
    deadline = time.monotonic() + timeout
    while not job.finished:
        assert time.monotonic() < deadline, "job did not finish"
        time.sleep(0.01)
    return job


class TestJobManager:
    def test_jobs_run_in_order(self, manager, tmp_path):
        load = manager.submit("load", {"model": "small", "device": "cpu"})
        job = manager.submit("transcribe", {}, audio_path=_audio(tmp_path, "hi"))
        _wait(job)
        assert load.status == "done" and job.status == "done"
        assert job.result["text"] == "hi"
        assert manager.backend.engine.calls[0] == ("load", "small", "cpu")
        assert manager.state()["model"] == "small"

    def test_audio_removed_after_job(self, manager, tmp_path):
        path = _audio(tmp_path, "hi")
        _wait(manager.submit("transcribe", {}, audio_path=path))
        assert not os.path.exists(path)

    def test_error(self, manager, tmp_path):
        job = _wait(manager.submit("transcribe", {}, audio_path=_audio(tmp_path, "bad")))
        assert job.status == "error"
        assert job.error["code"] == "bad_audio"
        progress = manager.latest_progress()
        assert progress["done"] is True and progress["progress"] < 1

    def test_cancel_queued_job_removes_audio(self, manager, tmp_path):
        slow = manager.submit("transcribe", {}, audio_path=_audio(tmp_path, "slow", "slow.wav"))
        path = _audio(tmp_path, "hi")
        queued = manager.submit("transcribe", {}, audio_path=path)
        assert manager.cancel(queued.id).status == "cancelled"
        assert not os.path.exists(path)
        manager.cancel(slow.id)
        assert _wait(slow).status == "cancelled"

    def test_history_is_trimmed(self, manager, tmp_path):
        jobs = [_wait(manager.submit("transcribe", {}, audio_path=_audio(tmp_path, str(i), f"{i}.wav")))
                for i in range(5)]
        assert [j.id for j in manager.list()] == [j.id for j in jobs[-3:]]

    def test_state(self, manager, tmp_path):
        assert manager.state()["model_loaded"] is False
        _wait(manager.submit("load", {"model": "base", "device": "cpu"}))
        state = manager.state()
        assert state["model_loaded"] is True and state["busy"] is False and state["queued"] == 0

    def test_stop_cancels_pending_jobs(self, tmp_path):
        m = JobManager(InlineBackend(FakeEngine()), "base", "cpu")
        m.start()
        slow = m.submit("transcribe", {}, audio_path=_audio(tmp_path, "slow", "slow.wav"))
        pending = m.submit("transcribe", {}, audio_path=_audio(tmp_path, "hi"))
        m.stop()
        assert pending.status == "cancelled"
        assert slow.status == "cancelled"


# ----------------------------------------------------------------- config

class TestConfig:
    def test_defaults(self, monkeypatch):
        for name in ("WHISPER_PORT", "WHISPER_MODEL", "WHISPER_DEVICE", "WHISPER_CORS_ORIGINS"):
            monkeypatch.delenv(name, raising=False)
        settings = config.load_settings()
        assert (settings.port, settings.model, settings.device) == (8081, "base", "cpu")
        assert settings.cors_origins == ("*",)
        assert settings.diarize_model_name == "pyannote/speaker-diarization-community-1"

    def test_invalid_values_fall_back_to_defaults(self, monkeypatch, caplog):
        monkeypatch.setenv("WHISPER_PORT", "80801")
        monkeypatch.setenv("WHISPER_MODEL", "huge")
        monkeypatch.setenv("WHISPER_BATCH_SIZE", "lots")
        settings = config.load_settings()
        assert (settings.port, settings.model, settings.batch_size) == (8081, "base", 16)
        assert "WHISPER_PORT" in caplog.text and "WHISPER_MODEL" in caplog.text

    def test_values(self, monkeypatch):
        monkeypatch.setenv("WHISPER_PORT", "9000")
        monkeypatch.setenv("WHISPER_DIARIZE", "yes")
        monkeypatch.setenv("WHISPER_CORS_ORIGINS", "http://a.test, http://b.test")
        settings = config.load_settings()
        assert settings.port == 9000 and settings.diarize_default is True
        assert settings.cors_origins == ("http://a.test", "http://b.test")


# ----------------------------------------------------------------- system

class TestSystem:
    def test_nvidia_smi_missing(self):
        with patch("app.system.subprocess.run", side_effect=FileNotFoundError()):
            assert system.nvidia_gpu_info(max_age=0) is None

    def test_nvidia_smi_no_output(self):
        with patch("app.system.subprocess.run", return_value=MagicMock(returncode=0, stdout="")):
            assert system.nvidia_gpu_info(max_age=0) == []

    def test_parses_gpu_info_in_mib(self):
        out = "0, NVIDIA GeForce RTX 3090, 24576, 1024, 23552, 5"
        with patch("app.system.subprocess.run", return_value=MagicMock(returncode=0, stdout=out)):
            gpu = system.nvidia_gpu_info(max_age=0)[0]
        assert gpu == {"index": 0, "name": "NVIDIA GeForce RTX 3090", "vram_total_mb": 24576,
                       "vram_used_mb": 1024, "vram_free_mb": 23552, "utilization_pct": 5}

    def test_utilization_not_supported(self):
        out = "0, Tesla T4, 15360, 0, 15360, [N/A]"
        with patch("app.system.subprocess.run", return_value=MagicMock(returncode=0, stdout=out)):
            assert system.nvidia_gpu_info(max_age=0)[0]["utilization_pct"] is None

    @pytest.mark.parametrize("free, status, text", [
        (20000, "green", "safe to use large"),
        (5000, "green", "medium model OK"),
        (2000, "yellow", "VRAM limited"),
    ])
    def test_gpu_summary(self, free, status, text):
        gpu = {"vram_total_mb": 24000, "vram_used_mb": 24000 - free, "vram_free_mb": free, "utilization_pct": 10}
        result = system.gpu_summary([gpu])
        assert result[0] == status and text in result[2]

    def test_gpu_summary_without_gpu(self):
        assert system.gpu_summary(None)[0] == "red"

    def test_find_ffmpeg(self, tmp_path, monkeypatch):
        fake = tmp_path / "ffmpeg"
        fake.write_text("")
        assert system.find_ffmpeg(str(fake)) == str(fake)
        monkeypatch.delenv("CONDA_PREFIX", raising=False)
        with patch("app.system.shutil.which", return_value="/opt/bin/ffmpeg"):
            assert system.find_ffmpeg("/missing/ffmpeg") == "/opt/bin/ffmpeg"

    def test_torch_cuda_info(self):
        info = system.torch_cuda_info()
        assert {"available", "device_count", "devices"} <= set(info)


# ---------------------------------------------------------------- formats

RESULT = {
    "text": "Hello. Hi there",
    "text_with_speakers": "SPEAKER_00: Hello.\nSPEAKER_01: Hi there",
    "segments": [
        {"start": 0.5, "end": 1.25, "text": " Hello.", "speaker": "SPEAKER_00"},
        {"start": 3661.0, "end": None, "text": "Hi there", "speaker": "SPEAKER_01"},
        {"start": 4000.0, "end": 4001.0, "text": "  "},
    ],
}


class TestFormats:
    def test_srt(self):
        assert formats.to_srt(RESULT) == (
            "1\n00:00:00,500 --> 00:00:01,250\n[SPEAKER_00] Hello.\n\n"
            "2\n01:01:01,000 --> 01:01:02,000\n[SPEAKER_01] Hi there\n"
        )

    def test_vtt(self):
        assert formats.to_vtt(RESULT).startswith("WEBVTT\n\n00:00:00.500 --> 00:00:01.250\n[SPEAKER_00] Hello.\n")

    def test_txt_prefers_speakers(self):
        assert formats.to_txt(RESULT) == "SPEAKER_00: Hello.\nSPEAKER_01: Hi there\n"
        assert formats.to_txt({"text": "plain"}) == "plain\n"

    def test_json_keeps_unicode(self):
        assert "é" in formats.export({"text": "été"}, "json")


# --------------------------------------------------------------------- hf

class TestHfToken:
    def test_from_env(self, monkeypatch):
        monkeypatch.setenv("HF_TOKEN", "hf_from_env")
        assert hf.get_token() == "hf_from_env"
        assert hf.token_source() == "env"

    def test_saved_token(self, isolated_hf_token):
        assert hf.get_token() is None and hf.token_source() is None
        hf.save_token("hf_saved_login")
        assert hf.get_token() == "hf_saved_login"
        assert hf.token_source() == "file"
        assert hf.delete_token() is True
        assert hf.get_token() is None
        assert hf.delete_token() is False

    def test_mask(self):
        assert hf.mask("hf_abcdefghijklmnop") == "hf_…mnop"
        assert hf.mask("short") == "…"
        assert hf.mask(None) is None

    def test_check_invalid_token(self):
        error = Exception("401 Client Error")
        error.response = MagicMock(status_code=401)
        with patch("huggingface_hub.whoami", side_effect=error):
            result = hf.check_token("hf_x", "org/model")
        assert result["valid"] is False

    def test_check_unreachable(self):
        with patch("huggingface_hub.whoami", side_effect=ConnectionError("no network")):
            result = hf.check_token("hf_x", "org/model")
        assert result["valid"] is None and "no network" in result["message"]

    def test_check_gated_model(self):
        from huggingface_hub.errors import GatedRepoError
        with patch("huggingface_hub.whoami", return_value={"name": "remi"}), \
                patch("huggingface_hub.auth_check", side_effect=GatedRepoError("gated", response=MagicMock())):
            result = hf.check_token("hf_x", "org/model")
        assert result["valid"] is True and result["model_access"] is False
        assert "https://huggingface.co/org/model" in result["message"]

    def test_check_ok(self):
        with patch("huggingface_hub.whoami", return_value={"name": "remi"}), \
                patch("huggingface_hub.auth_check", return_value=None):
            result = hf.check_token("hf_x", "org/model")
        assert result == {"valid": True, "username": "remi", "model_access": True,
                          "message": "Token OK (user remi), access to org/model granted."}
