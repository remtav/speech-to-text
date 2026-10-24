"""
Comprehensive test suite for the Speech-to-Text FastAPI application.

Tests cover:
- Audio decoding
- GPU/device detection helpers
- Progress tracking
- Health info endpoints
- Transcription and speaker diarization endpoints (mocked WhisperX)
- Error handling and edge cases

Run with: pytest tests/ -v
"""
import os
import sys
import tempfile
import subprocess
import shutil
import json
import hashlib
import threading
from pathlib import Path
from unittest.mock import patch, MagicMock, mock_open

import numpy as np
import pytest

# Ensure app module can be imported
sys.path.insert(0, str(Path(__file__).parent.parent))
import app.main

from fastapi.testclient import TestClient

client = TestClient(app.main.app)


class TestAppInitialization:
    """Test that the application initializes correctly."""

    def test_app_created(self):
        assert app.main.app is not None
        assert app.main.app.title == "Speech-to-Text"

    def test_cors_middleware_added(self):
        cors_middleware = [m for m in app.main.app.user_middleware if m.cls.__name__ == "CORSMiddleware"]
        assert len(cors_middleware) > 0


class TestHealthEndpoint:
    """Test the /health endpoint."""

    def test_health_returns_json(self):
        response = client.get("/health")
        assert response.status_code == 200
        data = response.json()
        assert "device" in data
        assert "model" in data
        assert "model_loaded" in data
        assert "status" in data

    def test_health_status_values(self):
        response = client.get("/health")
        data = response.json()
        assert data["status"] in ("green", "yellow", "red")

    def test_health_model_name(self):
        response = client.get("/health")
        data = response.json()
        assert data["model"] == os.getenv("WHISPER_MODEL", "base")

    def test_health_device(self):
        response = client.get("/health")
        data = response.json()
        assert data["device"] == os.getenv("WHISPER_DEVICE", "cpu")


class TestProgressEndpoint:
    """Test the /progress endpoint."""

    def test_progress_returns_json(self):
        response = client.get("/progress")
        assert response.status_code == 200
        data = response.json()
        assert "progress" in data
        assert "message" in data
        assert "stage" in data
        assert "done" in data


class TestSwitchModelEndpoint:
    """Test the /switch-model endpoint."""

    def test_switch_model_invalid_name(self):
        response = client.post("/switch-model", json={"model": "invalid"})
        assert response.status_code == 400

    def test_switch_model_invalid_device(self):
        response = client.post("/switch-model", json={"model": "base", "device": "invalid"})
        assert response.status_code == 400

    def test_switch_model_valid_cpu(self):
        response = client.post("/switch-model", json={"model": "base", "device": "cpu"})
        assert response.status_code == 200
        data = response.json()
        assert data["status"] == "success"
        assert data["model"] == "base"
        assert data["device"] == "cpu"

    def test_switch_model_to_tiny(self):
        response = client.post("/switch-model", json={"model": "tiny", "device": "cpu"})
        assert response.status_code == 200
        data = response.json()
        assert data["model"] == "tiny"


class TestIndexEndpoint:
    """Test the root / endpoint serves the HTML."""

    def test_index_returns_html(self):
        response = client.get("/")
        assert response.status_code == 200
        assert "text/html" in response.headers["content-type"]


class TestGetFFMpegPath:
    """Test the _get_ffmpeg_path helper."""

    def test_ffmpeg_path_from_env(self):
        # This may or may not return the env path depending on if the file exists
        # We just check it doesn't crash
        result = app.main._get_ffmpeg_path()
        assert result is None or isinstance(result, str)


class TestGetModelCacheDir:
    """Test the _get_model_cache_dir helper."""

    def test_default_cache_dir(self):
        with patch.dict(os.environ, {}, clear=False):
            if "XDG_CACHE_HOME" in os.environ:
                del os.environ["XDG_CACHE_HOME"]
            cache_dir = app.main._get_model_cache_dir()
            assert "whisper" in cache_dir
            assert os.path.isdir(cache_dir) or cache_dir.endswith("whisper")

    def test_xdg_cache_home(self):
        with patch.dict(os.environ, {"XDG_CACHE_HOME": "/tmp/test_xdg_cache"}):
            cache_dir = app.main._get_model_cache_dir()
            assert "/tmp/test_xdg_cache" in cache_dir
            assert "whisper" in cache_dir


class TestCheckTorchCuda:
    """Test the _check_torch_cuda helper."""

    def test_returns_cuda_info(self):
        result = app.main._check_torch_cuda()
        assert isinstance(result, dict)
        assert "available" in result
        assert "device_count" in result


class TestGetNvidiaGpuInfo:
    """Test the _get_nvidia_gpu_info helper."""

    def test_returns_none_when_nvidia_smi_missing(self):
        with patch("app.main.subprocess") as mock_subprocess:
            mock_subprocess.run.side_effect = FileNotFoundError()
            result = app.main._get_nvidia_gpu_info()
            assert result is None

    def test_returns_empty_list_when_no_output(self):
        with patch("app.main.subprocess") as mock_subprocess:
            mock_subprocess.run.return_value = MagicMock(returncode=0, stdout="", stderr="")
            result = app.main._get_nvidia_gpu_info()
            assert result == []

    def test_parses_gpu_info(self):
        with patch("app.main.subprocess") as mock_subprocess:
            mock_subprocess.run.return_value = MagicMock(
                returncode=0,
                stdout="0, NVIDIA GeForce RTX 3090, 24576, 1024, 23552, 5"
            )
            result = app.main._get_nvidia_gpu_info()
            assert len(result) == 1
            gpu = result[0]
            assert gpu["index"] == 0
            assert "RTX" in gpu["name"]
            assert gpu["vram_total_mb"] > 0


class TestLoadAudio:
    """Test the load_audio function (ffmpeg decode to 16 kHz mono)."""

    def test_decodes_pcm(self):
        pcm = np.array([0, 16384, -16384], dtype=np.int16).tobytes()
        with patch("app.main.subprocess.run", return_value=MagicMock(stdout=pcm)) as mock_run:
            audio = app.main.load_audio("input.mp3")
        assert audio.dtype == np.float32
        np.testing.assert_allclose(audio, [0.0, 0.5, -0.5])
        cmd = mock_run.call_args[0][0]
        assert "input.mp3" in cmd and "16000" in cmd

    def test_decode_failure_raises(self):
        error = subprocess.CalledProcessError(1, "ffmpeg", stderr=b"Invalid data found when processing input")
        with patch("app.main.subprocess.run", side_effect=error):
            with pytest.raises(app.main.AudioDecodeError, match="Invalid data"):
                app.main.load_audio("bad.wav")

    def test_empty_audio_raises(self):
        with patch("app.main.subprocess.run", return_value=MagicMock(stdout=b"")):
            with pytest.raises(app.main.AudioDecodeError):
                app.main.load_audio("empty.wav")


def _fake_whisperx():
    """A stand-in whisperx module: alignment adds words, diarization splits speakers by time."""
    fake = MagicMock()

    def align(segments, model, metadata, audio, device, **kwargs):
        aligned = []
        for seg in segments:
            words = seg["text"].split()
            step = (seg["end"] - seg["start"]) / len(words)
            aligned.append(dict(seg, words=[
                {"word": w, "start": seg["start"] + i * step, "end": seg["start"] + (i + 1) * step, "score": 0.9}
                for i, w in enumerate(words)
            ]))
        return {"segments": aligned, "word_segments": []}

    def assign_word_speakers(diarize_segments, result, fill_nearest=False):
        for seg in result["segments"]:
            for word in seg.get("words", []):
                # Words are spread evenly over 0-2 s: "Hello." starts at 0, "Hi" at ~0.67
                word["speaker"] = "SPEAKER_00" if word["start"] < 0.5 else "SPEAKER_01"
            seg["speaker"] = "SPEAKER_01"
        return result

    fake.align.side_effect = align
    fake.assign_word_speakers.side_effect = assign_word_speakers
    return fake


@pytest.fixture
def mock_engine():
    """Mock the WhisperX pipeline so /transcribe runs without models."""
    asr = MagicMock()
    asr.transcribe.return_value = {
        "segments": [{"start": 0.0, "end": 2.0, "text": " Hello. Hi there", "avg_logprob": -0.2}],
        "language": "en",
    }
    diarizer = MagicMock(return_value="diarize-df")
    with patch.dict(sys.modules, {"whisperx": _fake_whisperx()}), \
            patch.object(app.main, "model", asr), \
            patch.object(app.main, "_ensure_model", return_value=True), \
            patch.object(app.main, "load_audio", return_value=np.zeros(32000, dtype=np.float32)), \
            patch.object(app.main, "_get_align_model", return_value=("align-model", {"language": "en"})), \
            patch.object(app.main, "_get_diarize_model", return_value=diarizer), \
            patch.object(app.main, "_get_hf_token", return_value="hf_test"):
        yield {"asr": asr, "diarizer": diarizer}


def _post_audio(path="/transcribe", data=None):
    files = {"file": ("test.wav", b"dummy wav audio data", "audio/wav")}
    return client.post(path, files=files, data=data or {})


class TestTranscribeEndpoint:
    """Test the /transcribe endpoint with mocked WhisperX."""

    def test_transcribe_without_diarization(self, mock_engine):
        response = _post_audio()
        assert response.status_code == 200
        data = response.json()
        assert data["text"] == "Hello. Hi there"
        assert data["language"] == "en"
        assert data["aligned"] is True
        assert len(data["segments"]) == 1
        assert [w["word"] for w in data["segments"][0]["words"]] == ["Hello.", "Hi", "there"]
        assert "speakers" not in data
        assert "text_with_speakers" not in data
        mock_engine["diarizer"].assert_not_called()

    def test_transcribe_with_diarization_splits_segments(self, mock_engine):
        response = _post_audio(data={"diarize": "true", "min_speakers": "1", "max_speakers": "3"})
        assert response.status_code == 200
        data = response.json()
        assert data["speakers"] == ["SPEAKER_00", "SPEAKER_01"]
        assert [(s["speaker"], s["text"]) for s in data["segments"]] == [
            ("SPEAKER_00", "Hello."), ("SPEAKER_01", "Hi there"),
        ]
        assert data["text_with_speakers"] == "SPEAKER_00: Hello.\nSPEAKER_01: Hi there"
        kwargs = mock_engine["diarizer"].call_args.kwargs
        assert (kwargs["min_speakers"], kwargs["max_speakers"], kwargs["num_speakers"]) == (1, 3, None)

    def test_transcribe_unaligned_language(self, mock_engine):
        with patch.object(app.main, "_get_align_model", return_value=None):
            response = _post_audio(data={"diarize": "true"})
        assert response.status_code == 200
        data = response.json()
        assert data["aligned"] is False
        assert data["segments"][0]["speaker"] == "SPEAKER_01"
        assert "words" not in data["segments"][0]

    def test_diarize_without_hf_token(self, mock_engine):
        with patch.object(app.main, "_get_hf_token", return_value=None):
            response = _post_audio(data={"diarize": "true"})
        assert response.status_code == 503
        assert "HF_TOKEN" in response.json()["detail"]

    def test_hf_token_from_env(self):
        with patch.dict(os.environ, {"HF_TOKEN": "hf_from_env"}):
            assert app.main._get_hf_token() == "hf_from_env"

    def test_hf_token_from_saved_login(self, tmp_path):
        token_file = tmp_path / "token"
        token_file.write_text("hf_saved_login\n")
        hf_constants = pytest.importorskip("huggingface_hub.constants")
        env = {k: v for k, v in os.environ.items() if k not in ("HF_TOKEN", "HUGGING_FACE_HUB_TOKEN")}
        with patch.dict(os.environ, env, clear=True), \
                patch.object(hf_constants, "HF_TOKEN_PATH", str(token_file)):
            assert app.main._get_hf_token() == "hf_saved_login"

    def test_health_reports_diarization_availability(self):
        with patch.object(app.main, "_get_hf_token", return_value=None):
            assert client.get("/health").json()["diarization_available"] is False
        with patch.object(app.main, "_get_hf_token", return_value="hf_x"):
            assert client.get("/health").json()["diarization_available"] is True

    def test_diarization_model_unavailable(self, mock_engine):
        error = app.main.DiarizationUnavailable("gated model")
        with patch.object(app.main, "_get_diarize_model", side_effect=error):
            response = _post_audio(data={"diarize": "true"})
        assert response.status_code == 503
        assert "gated model" in response.json()["detail"]

    def test_invalid_speaker_hints(self, mock_engine):
        assert _post_audio(data={"diarize": "true", "num_speakers": "0"}).status_code == 400
        assert _post_audio(data={"diarize": "true", "min_speakers": "3", "max_speakers": "2"}).status_code == 400

    def test_undecodable_audio(self, mock_engine):
        with patch.object(app.main, "load_audio", side_effect=app.main.AudioDecodeError("bad audio")):
            response = _post_audio()
        assert response.status_code == 400

    def test_temp_file_removed(self, mock_engine):
        with patch.object(app.main.os, "unlink", wraps=os.unlink) as mock_unlink:
            _post_audio()
        assert mock_unlink.call_count == 1

    def test_transcribe_no_file(self):
        """Test transcription without providing a file."""
        response = client.post("/transcribe")
        assert response.status_code == 422  # FastAPI validation error


class TestTranscribeStreamingEndpoint:
    """Test the /transcribe/streaming endpoint."""

    def test_streaming_transcribe(self, mock_engine):
        response = _post_audio("/transcribe/streaming")
        assert response.status_code == 200
        assert response.json() == {"text": "Hello. Hi there", "language": "en", "task": "transcribe"}


class TestGetHealthInfo:
    """Test the get_health_info helper function."""

    def test_returns_comprehensive_info(self):
        info = app.main.get_health_info()
        assert isinstance(info, dict)
        assert "device" in info
        assert "model" in info
        assert "model_loaded" in info
        assert "torch_cuda" in info
        assert "nvidia_gpu" in info
        assert "gpu_available_for_model" in info
        assert "recommendation" in info
        assert "ffmpeg_available" in info
        assert "ffmpeg_path" in info


class TestProgressTracking:
    """Test the progress tracking functions."""

    def test_append_progress(self):
        app.main._clear_progress()
        app.main._append_progress(0.5, "Test progress", "transcribe", False)
        progress = app.main._get_progress()
        assert progress["progress"] == 0.5
        assert progress["message"] == "Test progress"
        assert progress["stage"] == "transcribe"
        assert progress["done"] is False
        app.main._clear_progress()

    def test_get_progress_empty(self):
        app.main._clear_progress()
        progress = app.main._get_progress()
        assert progress["progress"] == 0.0
        assert progress["stage"] == "idle"

    def test_clear_progress(self):
        app.main._append_progress(0.5, "Test", "transcribe", False)
        app.main._clear_progress()
        progress = app.main._get_progress()
        assert progress["progress"] == 0.0


class TestEnsureModel:
    """Test the _ensure_model function."""

    def test_calls_ensure_model(self):
        assert callable(app.main._ensure_model)


class TestPredownloadModel:
    """Test the _predownload_model function."""

    def test_returns_none_for_invalid_model(self):
        result = app.main._predownload_model("invalid_model_xyz", "/tmp")
        assert result[0] is None

    def test_finds_cached_model(self):
        result = app.main._predownload_model("tiny", "/tmp")
        # tiny should exist in whisper models
        assert result[0] is None or result[1] is not None


class TestMultipleEndpointRequests:
    """Test multiple sequential requests to ensure no state corruption."""

    def test_health_multiple_requests(self):
        for _ in range(3):
            response = client.get("/health")
            assert response.status_code == 200

    def test_progress_multiple_requests(self):
        for _ in range(3):
            response = client.get("/progress")
            assert response.status_code == 200


class TestErrorHandling:
    """Test error handling and edge cases."""

    def test_invalid_model_switch(self):
        response = client.post("/switch-model", json={"model": "super_large_plus"})
        assert response.status_code == 400

    def test_invalid_device_switch(self):
        response = client.post("/switch-model", json={"device": "mps"})
        assert response.status_code == 400

    def test_switch_model_invalid_device_cuda_unavailable(self):
        """Test switching to CUDA when it's not available."""
        with patch("app.main._check_torch_cuda") as mock_check:
            mock_check.return_value = {"available": False}
            response = client.post("/switch-model", json={"model": "base", "device": "cuda"})
            assert response.status_code == 400


class TestModelSelection:
    """Test valid model names."""

    def test_all_valid_models(self):
        """Test that all listed models are accepted (as valid names)."""
        for model_name in app.main.VALID_MODELS:
            # Validation only: don't download every model
            with patch("app.main._ensure_model", return_value=True):
                response = client.post("/switch-model", json={"model": model_name, "device": "cpu"})
                assert response.status_code == 200, f"Model {model_name} was rejected"

    def test_valid_models_known_to_faster_whisper(self):
        faster_whisper_utils = pytest.importorskip("faster_whisper.utils")
        assert set(app.main.VALID_MODELS) <= set(faster_whisper_utils.available_models())


if __name__ == "__main__":
    import pytest
    pytest.main([__file__, "-v"])
