"""WhisperX transcription engine.

``Engine`` owns the models (Whisper, wav2vec2 alignment, pyannote diarization)
and runs the pipeline. It knows nothing about HTTP or processes: progress is
reported through a ``progress(fraction, message, stage)`` callback, which may
raise ``Cancelled`` to stop the work. In production it runs inside the worker
process (see ``app.worker``).
"""
import fnmatch
import gc
import logging
import os
import threading
import time

from app import hf
from app.audio import load_audio
from app.config import DEFAULT_DIARIZE_MODEL
from app.errors import Cancelled, DiarizationUnavailable, ModelLoadError
from app.speakers import format_speaker_transcript, list_speakers, split_segments_by_speaker, to_builtin

log = logging.getLogger(__name__)

# Files faster-whisper downloads for a model (mirrors faster_whisper.utils.download_model)
MODEL_FILE_PATTERNS = ["config.json", "preprocessor_config.json", "model.bin", "tokenizer.json", "vocabulary.*"]


def _noop_progress(fraction, message, stage):
    pass


def model_cache_dir():
    """Return the Whisper (CTranslate2) model cache directory."""
    xdg = os.getenv("XDG_CACHE_HOME", os.path.join(os.path.expanduser("~"), ".cache"))
    return os.path.join(xdg, "whisper")


def compute_type_for(device, configured=None):
    """CTranslate2 compute type: WHISPER_COMPUTE_TYPE, else float16 on GPU and int8 on CPU."""
    return configured or ("float16" if device == "cuda" else "int8")


def format_bytes(n):
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.0f} {unit}" if unit in ("B", "KB") else f"{n:.1f} {unit}"
        n /= 1024


def _dir_size(path):
    total = 0
    for root, _, files in os.walk(path):
        for f in files:
            try:
                total += os.path.getsize(os.path.join(root, f))
            except OSError:
                pass
    return total


def _progress_range(progress, start, end, message, stage):
    """Map a whisperx 0-100 progress callback onto [start, end] of the overall progress."""
    def callback(pct):
        pct = min(max(float(pct), 0.0), 100.0)
        progress(start + (end - start) * pct / 100.0, f"{message} ({pct:.0f}%)", stage)
    return callback


class Engine:
    def __init__(self, batch_size=16, compute_type=None, diarize_model=None, ffmpeg_path=None, ffmpeg_timeout=1800):
        self.batch_size = batch_size
        self.compute_type = compute_type
        self.diarize_model_name = diarize_model
        self.ffmpeg_path = ffmpeg_path
        self.ffmpeg_timeout = ffmpeg_timeout

        self.model = None  # whisperx FasterWhisperPipeline
        self.model_name = None
        self.device = None
        self.align_models = {}  # language -> (align_model, metadata) or None if unsupported
        self.diarizer = None

    # ------------------------------------------------------------------ state

    def state(self):
        return {
            "model": self.model_name,
            "device": self.device,
            "model_loaded": self.model is not None,
            "diarization_loaded": self.diarizer is not None,
        }

    def _free_device_memory(self):
        gc.collect()
        try:
            import torch
            if torch.cuda.is_available():
                torch.cuda.empty_cache()
        except ImportError:
            pass

    def _unload_aux(self):
        had = bool(self.align_models) or self.diarizer is not None
        self.align_models.clear()
        self.diarizer = None
        return had

    def eject(self):
        """Unload every model. Returns True if anything was loaded."""
        had = self.model is not None
        self.model = None
        had = self._unload_aux() or had
        if had:
            self._free_device_memory()
        return had

    # ---------------------------------------------------------------- loading

    def _download_model(self, name, progress):
        """Download (or find in cache) the faster-whisper model, reporting progress."""
        from faster_whisper.utils import download_model

        cache_dir = model_cache_dir()
        os.makedirs(cache_dir, exist_ok=True)
        try:
            return download_model(name, cache_dir=cache_dir, local_files_only=True)
        except Exception:
            pass  # not cached yet

        repo_id, total = self._model_repo_size(name)
        size_text = f" ({format_bytes(total)})" if total else ""
        progress(0.05, f"Downloading Whisper model '{name}'{size_text}…", "download")

        stop = threading.Event()
        watcher = None
        if repo_id:
            repo_dir = os.path.join(cache_dir, "models--" + repo_id.replace("/", "--"))
            watcher = threading.Thread(target=self._watch_download, daemon=True,
                                       args=(repo_dir, total, name, progress, stop))
            watcher.start()
        try:
            path = download_model(name, cache_dir=cache_dir)
        except Exception as e:
            raise ModelLoadError(f"Failed to download Whisper model '{name}': {e}") from e
        finally:
            stop.set()
            if watcher:
                watcher.join(timeout=2)
        progress(0.45, f"Model '{name}' downloaded", "download")
        log.info("Model %s cached at %s", name, path)
        return path

    @staticmethod
    def _model_repo_size(name):
        """(repo_id, total bytes to download) — best effort, (None, 0) when unknown."""
        try:
            from faster_whisper.utils import _MODELS
            from huggingface_hub import model_info
            repo_id = _MODELS.get(name, name)
            info = model_info(repo_id, files_metadata=True)
            total = sum(
                s.size or 0 for s in info.siblings
                if any(fnmatch.fnmatch(s.rfilename, p) for p in MODEL_FILE_PATTERNS)
            )
            return repo_id, total
        except Exception as e:
            log.debug("Could not get the size of model %s: %s", name, e)
            return None, 0

    @staticmethod
    def _watch_download(repo_dir, total, name, progress, stop):
        """Report download progress by watching the cache directory grow."""
        started = time.monotonic()
        initial = _dir_size(repo_dir)
        while not stop.wait(1.0):
            done = max(_dir_size(repo_dir) - initial, 0)
            elapsed = int(time.monotonic() - started)
            try:
                if total:
                    fraction = min(done / total, 1.0)
                    progress(0.05 + 0.4 * fraction,
                             f"Downloading '{name}': {format_bytes(done)} / {format_bytes(total)} ({fraction:.0%})",
                             "download")
                else:
                    progress(0.05, f"Downloading '{name}': {format_bytes(done)} ({elapsed} s)", "download")
            except Exception:  # e.g. Cancelled: the download itself is stopped by the caller
                return

    def load(self, name, device, progress=_noop_progress):
        """Load the requested Whisper model on `device`, replacing the current one."""
        if self.model is not None and self.model_name == name and self.device == device:
            progress(1.0, f"Model '{name}' ready on {device}", "load")
            return self.state()

        self.model = None
        if device != self.device:
            self._unload_aux()  # alignment / diarization models live on the old device
        self._free_device_memory()

        compute_type = compute_type_for(device, self.compute_type)
        progress(0.02, f"Preparing model '{name}' on {device} ({compute_type})…", "load")
        try:
            import whisperx
            model_path = self._download_model(name, progress)
            progress(0.5, f"Loading '{name}' weights on {device}…", "load")
            self.model = whisperx.load_model(model_path, device, compute_type=compute_type,
                                             download_root=model_cache_dir())
        except ModelLoadError:
            raise
        except Exception as e:
            log.exception("Error loading model %s on %s", name, device)
            hint = ""
            text = str(e).lower()
            if device == "cuda" and ("out of memory" in text or "cuda failed" in text):
                hint = " — not enough GPU memory: free VRAM, pick a smaller model or use the CPU"
            elif "compute type" in text or "float16" in text:
                hint = " — this device does not support the compute type; set WHISPER_COMPUTE_TYPE (e.g. int8)"
            raise ModelLoadError(f"Failed to load model '{name}' on {device}: {e}{hint}") from e

        self.model_name = name
        self.device = device
        progress(1.0, f"Model '{name}' ready on {device}", "load")
        return self.state()

    def _align_model(self, language):
        """Cached (align_model, metadata) for `language`, or None when the language has no model."""
        if language not in self.align_models:
            import whisperx
            try:
                self.align_models[language] = whisperx.load_align_model(language_code=language, device=self.device)
            except ValueError as e:
                # No default wav2vec2 alignment model for this language
                log.info("Alignment unavailable for '%s': %s", language, e)
                self.align_models[language] = None
        return self.align_models[language]

    @staticmethod
    def _alignment_warning(error):
        text = str(error)
        if isinstance(error, LookupError) and "punkt" in text:
            return ("Word timestamps skipped: the NLTK 'punkt_tab' data is missing and could not be downloaded. "
                    "Install it once with: python -m nltk.downloader punkt_tab")
        first_line = text.strip().splitlines()[0] if text.strip() else ""
        return f"Word timestamps skipped: alignment failed ({type(error).__name__}: {first_line})"

    def _diarizer(self):
        if self.diarizer is None:
            name = self.diarize_model_name or DEFAULT_DIARIZE_MODEL
            token = hf.get_token()
            if not token:
                raise DiarizationUnavailable(
                    "Speaker identification needs a Hugging Face token: add it in the web UI "
                    "(Speaker identification settings), set HF_TOKEN or run `hf auth login`."
                )
            from whisperx.diarize import DiarizationPipeline
            try:
                self.diarizer = DiarizationPipeline(model_name=self.diarize_model_name, token=token,
                                                    device=self.device)
            except Exception as e:
                raise DiarizationUnavailable(
                    f"Could not load diarization model '{name}': {e}. Check your Hugging Face token "
                    f"and accept the model terms at https://huggingface.co/{name}"
                ) from e
        return self.diarizer

    # ---------------------------------------------------------- transcription

    def transcribe(self, audio_path, options=None, progress=_noop_progress):
        """Transcribe a file. Options: model, device, language, diarize, num/min/max_speakers, quick.

        `model` / `device` default to the loaded ones; the model is (re)loaded if needed.
        """
        options = options or {}
        self.load(options.get("model") or self.model_name or "base",
                  options.get("device") or self.device or "cpu",
                  lambda f, m, s: progress(0.1 * f, m, s))

        progress(0.1, "Decoding audio…", "decode")
        audio = load_audio(audio_path, self.ffmpeg_path, self.ffmpeg_timeout)
        duration = len(audio) / 16000

        if options.get("quick"):
            return self._quick(audio, options, progress)
        return self._full(audio, duration, options, progress)

    def _quick(self, audio, options, progress):
        """Transcription only (no alignment / diarization), for low-latency use."""
        progress(0.15, "Transcribing (quick)…", "transcribe")
        result = self.model.transcribe(
            audio, batch_size=self.batch_size, language=options.get("language") or None,
            progress_callback=_progress_range(progress, 0.15, 0.95, "Transcribing", "transcribe"),
        )
        return to_builtin({
            "text": " ".join(seg["text"].strip() for seg in result["segments"]).strip(),
            "language": result.get("language", "unknown"),
            "task": "transcribe",
        })

    def _full(self, audio, duration, options, progress):
        import whisperx

        diarize = bool(options.get("diarize"))
        if diarize:
            self._diarizer()  # fail fast, before the long transcription, if it can't be loaded

        # Phase 1: transcribe (VAD-batched faster-whisper)
        progress(0.15, "Running Whisper transcription…", "transcribe")
        result = self.model.transcribe(
            audio, batch_size=self.batch_size, language=options.get("language") or None,
            progress_callback=_progress_range(progress, 0.15, 0.55, "Transcribing", "transcribe"),
        )
        language = result.get("language", "unknown")

        # Phase 2: word-level alignment (wav2vec2). Optional: on failure, keep segment timestamps.
        aligned = False
        warnings = []
        if result["segments"]:
            progress(0.55, f"Loading alignment model ({language})…", "align")
            try:
                align = self._align_model(language)
                if align is not None:
                    align_model, align_metadata = align
                    result = whisperx.align(
                        result["segments"], align_model, align_metadata, audio, self.device,
                        return_char_alignments=False,
                        progress_callback=_progress_range(progress, 0.55, 0.7, "Aligning words", "align"),
                    )
                    aligned = True
            except Cancelled:
                raise
            except Exception as e:
                log.warning("Word alignment failed, keeping segment timestamps: %r", e)
                warnings.append(self._alignment_warning(e))

        # Phase 3: speaker diarization (pyannote)
        if diarize and result["segments"]:
            progress(0.7, "Identifying speakers…", "diarize")
            diarize_segments = self._diarizer()(
                audio,
                num_speakers=options.get("num_speakers"),
                min_speakers=options.get("min_speakers"),
                max_speakers=options.get("max_speakers"),
                progress_callback=_progress_range(progress, 0.7, 0.97, "Identifying speakers", "diarize"),
            )
            result = whisperx.assign_word_speakers(diarize_segments, result, fill_nearest=True)

        segments = result["segments"]
        if diarize:
            segments = split_segments_by_speaker(segments, language)
        else:
            segments = [dict(seg, id=i) for i, seg in enumerate(segments)]

        response = {
            "text": " ".join(seg["text"].strip() for seg in segments if seg.get("text")).strip(),
            "language": language,
            "aligned": aligned,
            "duration": round(duration, 3),
            "segments": segments,
            "warnings": warnings,
        }
        if diarize:
            response["speakers"] = list_speakers(segments)
            response["text_with_speakers"] = format_speaker_transcript(segments, language)
        return to_builtin(response)
