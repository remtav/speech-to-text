"""Engine tests with WhisperX mocked, so no model is downloaded."""
import os
import subprocess
import sys
from unittest.mock import MagicMock, patch

import numpy as np
import pytest

import app.pipeline as pipeline
from app.audio import load_audio
from app.config import VALID_MODELS
from app.errors import AudioDecodeError, Cancelled, DiarizationUnavailable, FfmpegMissing, ModelLoadError


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
def engine(monkeypatch):
    """An Engine with a loaded (mock) model and mocked alignment / diarization."""
    whisperx = _fake_whisperx()
    monkeypatch.setitem(sys.modules, "whisperx", whisperx)
    monkeypatch.setattr(pipeline, "load_audio", lambda *a, **k: np.zeros(32000, dtype=np.float32))
    monkeypatch.setenv("HF_TOKEN", "hf_test")

    eng = pipeline.Engine()
    eng.model = MagicMock()
    eng.model.transcribe.return_value = {
        "segments": [{"start": 0.0, "end": 2.0, "text": " Hello. Hi there", "avg_logprob": -0.2}],
        "language": "en",
    }
    eng.model_name, eng.device = "base", "cpu"
    eng.align_models["en"] = ("align-model", {"language": "en"})
    eng.diarizer = MagicMock(return_value="diarize-df")
    eng.whisperx = whisperx
    return eng


class TestTranscription:
    def test_without_diarization(self, engine):
        data = engine.transcribe("a.wav")
        assert data["text"] == "Hello. Hi there"
        assert data["language"] == "en"
        assert data["aligned"] is True
        assert data["duration"] == 2.0
        assert data["warnings"] == []
        assert [w["word"] for w in data["segments"][0]["words"]] == ["Hello.", "Hi", "there"]
        assert "speakers" not in data
        engine.diarizer.assert_not_called()

    def test_with_diarization_splits_segments(self, engine):
        data = engine.transcribe("a.wav", {"diarize": True, "min_speakers": 1, "max_speakers": 3})
        assert data["speakers"] == ["SPEAKER_00", "SPEAKER_01"]
        assert [(s["speaker"], s["text"]) for s in data["segments"]] == [
            ("SPEAKER_00", "Hello."), ("SPEAKER_01", "Hi there"),
        ]
        assert data["text_with_speakers"] == "SPEAKER_00: Hello.\nSPEAKER_01: Hi there"
        kwargs = engine.diarizer.call_args.kwargs
        assert (kwargs["min_speakers"], kwargs["max_speakers"], kwargs["num_speakers"]) == (1, 3, None)

    def test_unaligned_language(self, engine):
        engine.align_models["en"] = None
        data = engine.transcribe("a.wav", {"diarize": True})
        assert data["aligned"] is False
        assert data["segments"][0]["speaker"] == "SPEAKER_01"
        assert "words" not in data["segments"][0]

    def test_language_option(self, engine):
        engine.transcribe("a.wav", {"language": "fr"})
        assert engine.model.transcribe.call_args.kwargs["language"] == "fr"

    def test_alignment_failure_is_a_warning(self, engine):
        engine.whisperx.align.side_effect = LookupError("Resource punkt_tab not found.")
        data = engine.transcribe("a.wav")
        assert data["aligned"] is False
        assert data["text"] == "Hello. Hi there"
        assert "punkt_tab" in data["warnings"][0]

    def test_alignment_model_download_failure_is_a_warning(self, engine):
        del engine.align_models["en"]
        engine.whisperx.load_align_model.side_effect = OSError("connection reset")
        data = engine.transcribe("a.wav")
        assert data["aligned"] is False
        assert "connection reset" in data["warnings"][0]
        assert "en" not in engine.align_models  # retried next time

    def test_cancel_during_alignment(self, engine):
        def progress(fraction, message, stage):
            if stage == "align":
                raise Cancelled("Cancelled")
        with pytest.raises(Cancelled):
            engine.transcribe("a.wav", progress=progress)

    def test_progress_is_reported(self, engine):
        events = []
        engine.transcribe("a.wav", progress=lambda f, m, s: events.append((f, s)))
        stages = [s for _, s in events]
        assert stages.index("decode") < stages.index("transcribe") < stages.index("align")
        fractions = [f for f, _ in events]
        assert fractions == sorted(fractions)

    def test_quick(self, engine):
        data = engine.transcribe("a.wav", {"quick": True})
        assert data == {"text": "Hello. Hi there", "language": "en", "task": "transcribe"}
        engine.whisperx.align.assert_not_called()

    def test_diarization_without_token_fails_fast(self, engine, monkeypatch):
        monkeypatch.delenv("HF_TOKEN")
        engine.diarizer = None
        with pytest.raises(DiarizationUnavailable, match="Hugging Face token"):
            engine.transcribe("a.wav", {"diarize": True})
        engine.model.transcribe.assert_not_called()

    def test_diarization_model_unavailable(self, engine):
        engine.diarizer = None
        with patch.dict(sys.modules, {"whisperx.diarize": MagicMock(
                DiarizationPipeline=MagicMock(side_effect=OSError("gated repo")))}):
            with pytest.raises(DiarizationUnavailable, match="gated repo"):
                engine.transcribe("a.wav", {"diarize": True})

    def test_reloads_when_model_differs(self, engine):
        with patch.object(engine, "load") as load:
            engine.transcribe("a.wav", {"model": "small", "device": "cpu"})
        assert load.call_args.args[:2] == ("small", "cpu")


class TestLoading:
    def test_same_model_is_not_reloaded(self, engine):
        model = engine.model
        engine.load("base", "cpu")
        assert engine.model is model

    def test_load(self, engine, monkeypatch):
        monkeypatch.setattr(engine, "_download_model", lambda name, progress: f"/cache/{name}")
        engine.load("small", "cpu")
        engine.whisperx.load_model.assert_called_once()
        assert engine.whisperx.load_model.call_args.args[:2] == ("/cache/small", "cpu")
        assert engine.whisperx.load_model.call_args.kwargs["compute_type"] == "int8"
        assert engine.state() == {"model": "small", "device": "cpu", "model_loaded": True,
                                  "diarization_loaded": True}

    def test_device_change_drops_aux_models(self, engine, monkeypatch):
        monkeypatch.setattr(engine, "_download_model", lambda name, progress: name)
        engine.load("base", "cuda")
        assert engine.align_models == {} and engine.diarizer is None

    def test_load_failure_has_hint(self, engine, monkeypatch):
        monkeypatch.setattr(engine, "_download_model", lambda name, progress: name)
        engine.whisperx.load_model.side_effect = RuntimeError("CUDA failed with error out of memory")
        with pytest.raises(ModelLoadError, match="not enough GPU memory"):
            engine.load("large-v3", "cuda")
        assert engine.model is None

    def test_eject(self, engine):
        assert engine.eject() is True
        assert engine.state()["model_loaded"] is False
        assert engine.eject() is False

    def test_download_failure(self, monkeypatch):
        utils = pytest.importorskip("faster_whisper.utils")
        monkeypatch.setattr(utils, "download_model", MagicMock(side_effect=OSError("no network")))
        monkeypatch.setattr(pipeline.Engine, "_model_repo_size", staticmethod(lambda name: (None, 0)))
        with pytest.raises(ModelLoadError, match="no network"):
            pipeline.Engine()._download_model("tiny", lambda *a: None)


class TestHelpers:
    def test_compute_type(self):
        assert pipeline.compute_type_for("cuda") == "float16"
        assert pipeline.compute_type_for("cpu") == "int8"
        assert pipeline.compute_type_for("cpu", "float32") == "float32"

    def test_model_cache_dir(self, monkeypatch):
        monkeypatch.setenv("XDG_CACHE_HOME", "/tmp/test_xdg_cache")
        assert pipeline.model_cache_dir() == "/tmp/test_xdg_cache/whisper"

    def test_progress_range(self):
        events = []
        callback = pipeline._progress_range(lambda f, m, s: events.append((f, m)), 0.2, 0.4, "Step", "x")
        callback(50)
        callback(150)
        assert events == [(pytest.approx(0.3), "Step (50%)"), (pytest.approx(0.4), "Step (100%)")]

    def test_format_bytes(self):
        assert pipeline.format_bytes(512) == "512 B"
        assert pipeline.format_bytes(3 * 1024 ** 3) == "3.0 GB"

    def test_valid_models_known_to_faster_whisper(self):
        utils = pytest.importorskip("faster_whisper.utils")
        assert set(VALID_MODELS) <= set(utils.available_models())


class TestLoadAudio:
    def test_decodes_pcm(self):
        pcm = np.array([0, 16384, -16384], dtype=np.int16).tobytes()
        with patch("app.audio.subprocess.run", return_value=MagicMock(stdout=pcm)) as run:
            audio = load_audio("input.mp3", "/usr/bin/ffmpeg")
        assert audio.dtype == np.float32
        np.testing.assert_allclose(audio, [0.0, 0.5, -0.5])
        cmd = run.call_args[0][0]
        assert cmd[0] == "/usr/bin/ffmpeg" and "input.mp3" in cmd and "16000" in cmd

    def test_decode_failure(self):
        error = subprocess.CalledProcessError(1, "ffmpeg", stderr=b"Invalid data found when processing input")
        with patch("app.audio.subprocess.run", side_effect=error):
            with pytest.raises(AudioDecodeError, match="Invalid data"):
                load_audio("bad.wav")

    def test_empty_audio(self):
        with patch("app.audio.subprocess.run", return_value=MagicMock(stdout=b"")):
            with pytest.raises(AudioDecodeError):
                load_audio("empty.wav")

    def test_ffmpeg_missing(self):
        with pytest.raises(FfmpegMissing):
            load_audio("a.wav", "/nonexistent/ffmpeg")

    def test_timeout(self):
        with patch("app.audio.subprocess.run", side_effect=subprocess.TimeoutExpired("ffmpeg", 5)):
            with pytest.raises(AudioDecodeError, match="longer than"):
                load_audio("a.wav", timeout=5)

    @pytest.mark.skipif(not os.path.exists("/usr/bin/ffmpeg"), reason="ffmpeg not installed")
    def test_real_ffmpeg(self, tmp_path):
        path = tmp_path / "tone.wav"
        subprocess.run(["/usr/bin/ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i",
                        "sine=frequency=440:duration=0.5", str(path)], check=True)
        audio = load_audio(path, "/usr/bin/ffmpeg")
        assert len(audio) == 8000
        with pytest.raises(AudioDecodeError):
            bad = tmp_path / "bad.wav"
            bad.write_bytes(b"not audio")
            load_audio(bad, "/usr/bin/ffmpeg")
