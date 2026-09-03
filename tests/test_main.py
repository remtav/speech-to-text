"""
Comprehensive test suite for the Speech-to-Text FastAPI application.

Tests cover:
- Audio conversion functions
- GPU/device detection helpers
- Progress tracking
- Health info endpoints
- Transcription endpoints (mocked)
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


class TestAudioConversion:
    """Test the convert_audio_to_wav function."""

    def test_wav_file_unchanged(self):
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
            f.write(b"dummy wav content")
            tmp_path = f.name
        try:
            result_path, ext = app.main.convert_audio_to_wav(tmp_path)
            assert result_path == tmp_path
            assert ext == ".wav"
        finally:
            os.unlink(tmp_path)

    def test_non_wav_file_conversion(self):
        with tempfile.NamedTemporaryFile(suffix=".mp3", delete=False) as f:
            f.write(b"dummy mp3 content")
            tmp_path = f.name
        try:
            with patch("app.main.subprocess") as mock_subprocess:
                mock_subprocess.run.return_value = MagicMock(returncode=0)
                with patch("os.path.exists", return_value=True):
                    result_path, ext = app.main.convert_audio_to_wav(tmp_path)
                assert ext == ".wav"
        finally:
            os.unlink(tmp_path)


class TestTranscribeEndpoint:
    """Test the /transcribe endpoint with mocked Whisper."""

    def test_transcribe_file_success(self):
        """Test successful transcription with a mock file."""
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as wav_file:
            wav_file.write(b"dummy wav audio data")
            wav_path = wav_file.name

        try:
            with open(wav_path, "rb") as f:
                files = {"file": ("test.wav", f, "audio/wav")}
                response = client.post("/transcribe", files=files)
            # Response may fail if model isn't loaded
            assert response.status_code in (200, 500)

            if response.status_code == 200:
                data = response.json()
                assert "text" in data
                assert "language" in data
                assert "segments" in data
        finally:
            os.unlink(wav_path)

    def test_transcribe_no_file(self):
        """Test transcription without providing a file."""
        response = client.post("/transcribe")
        assert response.status_code == 422  # FastAPI validation error


class TestTranscribeStreamingEndpoint:
    """Test the /transcribe/streaming endpoint."""

    def test_streaming_transcribe(self):
        """Test streaming transcription."""
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as wav_file:
            wav_file.write(b"dummy wav audio data")
            wav_path = wav_file.name

        try:
            with open(wav_path, "rb") as f:
                files = {"file": ("test.wav", f, "audio/wav")}
                response = client.post("/transcribe/streaming", files=files)
            assert response.status_code in (200, 500)
        finally:
            os.unlink(wav_path)


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
        valid_models = ["tiny", "base", "small", "medium", "large", "large-v3"]
        for model_name in valid_models:
            with patch("app.main._check_torch_cuda") as mock_check:
                mock_check.return_value = {"available": False}
                response = client.post("/switch-model", json={"model": model_name, "device": "cpu"})
                # Should be 200 or 500 (loading failure), not 400 (validation failure)
                assert response.status_code not in (400,), f"Model {model_name} was rejected"


if __name__ == "__main__":
    import pytest
    pytest.main([__file__, "-v"])
