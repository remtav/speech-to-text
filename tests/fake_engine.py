"""A model-free stand-in for ``app.pipeline.Engine``.

Importable by path ("tests.fake_engine:FakeEngine") so the real worker
process can run it. The audio file's content selects the behaviour:

- ``crash``: abort the process, like a native library failure
- ``slow``: report progress for ~5 s (cancellable)
- ``hang``: sleep without reporting progress (needs a hard cancel)
- ``bad``: raise AudioDecodeError
- ``boom``: raise an unexpected exception
- anything else: return it as the transcription text
"""
import os
import time

from app.errors import AudioDecodeError


class FakeEngine:
    def __init__(self, **kwargs):
        self.kwargs = kwargs
        self.model_name = None
        self.device = None
        self.loaded = False
        self.calls = []

    def state(self):
        return {"model": self.model_name, "device": self.device, "model_loaded": self.loaded,
                "diarization_loaded": False}

    def load(self, name, device, progress):
        self.calls.append(("load", name, device))
        if name == "large-v3" and device == "cuda" and os.getenv("FAKE_ENGINE_LOAD_FAILS"):
            raise RuntimeError("CUDA failed with error out of memory")
        progress(0.5, f"Loading {name}", "load")
        self.model_name, self.device, self.loaded = name, device, True
        progress(1.0, f"Model '{name}' ready on {device}", "load")
        return self.state()

    def eject(self):
        had, self.loaded = self.loaded, False
        return had

    def transcribe(self, audio_path, options=None, progress=None):
        options = options or {}
        self.load(options.get("model") or "base", options.get("device") or "cpu", progress)
        self.calls.append(("transcribe", audio_path, options))
        with open(audio_path, "rb") as f:
            content = f.read().decode(errors="replace").strip()

        if content == "crash":
            os.abort()
        if content == "slow":
            for i in range(100):
                progress(i / 100, f"Working {i}%", "transcribe")
                time.sleep(0.05)
        if content == "hang":
            progress(0.2, "Hanging", "transcribe")
            time.sleep(60)
        if content == "bad":
            raise AudioDecodeError("Could not decode audio: Invalid data found when processing input")
        if content == "boom":
            raise KeyError("unexpected")

        progress(0.9, "Almost done", "transcribe")
        segments = [{"id": 0, "start": 0.0, "end": 1.5, "text": content}]
        result = {"text": content, "language": options.get("language") or "en"}
        if options.get("quick"):
            return dict(result, task="transcribe")
        result.update(aligned=False, duration=1.5, segments=segments, warnings=[])
        if options.get("diarize"):
            segments[0]["speaker"] = "SPEAKER_00"
            result.update(speakers=["SPEAKER_00"], text_with_speakers=f"SPEAKER_00: {content}")
        return result
