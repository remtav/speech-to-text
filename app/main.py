import gc
import os
import threading
import tempfile
import subprocess
from typing import Optional

import numpy as np
from fastapi import FastAPI, UploadFile, File, Form, HTTPException, Body
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import HTMLResponse
from fastapi.middleware.cors import CORSMiddleware

from app.speakers import (
    split_segments_by_speaker,
    list_speakers,
    format_speaker_transcript,
    to_builtin,
)

app = FastAPI(title="Speech-to-Text", description="Voice transcription with speaker diarization powered by WhisperX")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

SAMPLE_RATE = 16000

VALID_MODELS = ["tiny", "base", "small", "medium", "large", "large-v2", "large-v3", "large-v3-turbo"]
VALID_DEVICES = ["cpu", "cuda"]


def _env_bool(name, default=False):
    return os.getenv(name, str(default)).strip().lower() in ("1", "true", "yes", "on")


HF_TOKEN = os.getenv("HF_TOKEN") or None
DIARIZE_DEFAULT = _env_bool("WHISPER_DIARIZE", False)
DIARIZE_MODEL = os.getenv("WHISPER_DIARIZE_MODEL") or None  # None = whisperx default (pyannote community-1)
BATCH_SIZE = int(os.getenv("WHISPER_BATCH_SIZE", "16"))

# ============================================================
# Global state
# ============================================================
model = None  # whisperx FasterWhisperPipeline
model_lock = threading.Lock()
current_model_name = os.getenv("WHISPER_MODEL", "base")
current_device = os.getenv("WHISPER_DEVICE", "cpu")

# Auxiliary models, loaded lazily on current_device
align_models: dict = {}  # language -> (align_model, metadata)
diarize_model = None
aux_lock = threading.Lock()

# --- Progress tracking ---
# A shared event queue that allows polling from the frontend
_lock = threading.Lock()
_progress_events: list[dict] = []  # [{progress, message, stage, done}]

def _append_progress(progress: float, message: str, stage: str = "transcribe", done: bool = False):
    """Thread-safe: append a progress event."""
    with _lock:
        _progress_events.append({
            "progress": round(progress, 4),
            "message": message,
            "stage": stage,
            "done": done,
        })

def _get_progress():
    """Return the latest progress event (without clearing)."""
    with _lock:
        if _progress_events:
            return _progress_events[-1]
        return {"progress": 0.0, "message": "Waiting...", "stage": "idle", "done": False}

def _clear_progress():
    with _lock:
        _progress_events.clear()


# ============================================================
# GPU model eject
# ============================================================

def _free_device_memory():
    gc.collect()
    try:
        import torch
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
    except ImportError:
        pass


def _unload_aux_models():
    """Drop alignment and diarization models. Returns True if any were loaded."""
    global diarize_model
    with aux_lock:
        had_models = bool(align_models) or diarize_model is not None
        align_models.clear()
        diarize_model = None
    return had_models


def _eject_models():
    """Unload Whisper, alignment and diarization models to free VRAM. Thread-safe.

    The model name and device are kept so the next transcription reloads them.
    """
    global model
    with model_lock:
        had_model = model is not None
        model = None
    had_aux = _unload_aux_models()
    if had_model or had_aux:
        _free_device_memory()
        _clear_progress()
        return True
    return False


# ============================================================
# GPU / Device helpers
# ============================================================

def _get_nvidia_gpu_info():
    """Return GPU info via nvidia-smi or None."""
    try:
        result = subprocess.run(
            ["nvidia-smi", "--query-gpu=index,name,memory.total,memory.used,memory.free,utilization.gpu",
             "--format=csv,noheader,nounits"],
            capture_output=True, text=True, timeout=5,
        )
        if result.returncode == 0:
            gpus = []
            for line in result.stdout.strip().splitlines():
                parts = [p.strip() for p in line.split(",")]
                if len(parts) == 6:
                    gpus.append({
                        "index": int(parts[0]),
                        "name": parts[1],
                        "vram_total_mb": int(parts[2]) * 1024,
                        "vram_used_mb": int(parts[3]) * 1024,
                        "vram_free_mb": int(parts[4]) * 1024,
                        "utilization_pct": int(parts[5]),
                    })
            return gpus
    except Exception:
        pass
    return None


def _check_torch_cuda():
    """Check PyTorch CUDA availability without loading any models."""
    cuda = {
        "available": False,
        "driver_version": None,
        "runtime_version": None,
        "device_count": 0,
        "current_device": None,
        "devices": [],
    }
    try:
        import torch
        if torch.cuda.is_available():
            cuda["available"] = True
            cuda["device_count"] = torch.cuda.device_count()
            cuda["current_device"] = torch.cuda.current_device()
            try:
                cuda["driver_version"] = torch.version.cuda
            except Exception:
                pass
            for i in range(cuda["device_count"]):
                cuda["devices"].append({
                    "index": i,
                    "name": torch.cuda.get_device_name(i),
                    "total_memory_mb": torch.cuda.get_device_properties(i).total_memory / (1024 ** 2),
                    "free_memory_mb": torch.cuda.get_device_properties(i).total_memory / (1024 ** 2),
                })
    except ImportError:
        pass
    return cuda


def _get_model_cache_dir():
    """Return the Whisper (CTranslate2) model cache directory."""
    xdg = os.getenv("XDG_CACHE_HOME", os.path.join(os.path.expanduser("~"), ".cache"))
    return os.path.join(xdg, "whisper")


def _get_compute_type(device):
    """CTranslate2 compute type: WHISPER_COMPUTE_TYPE, else float16 on GPU and int8 on CPU."""
    return os.getenv("WHISPER_COMPUTE_TYPE") or ("float16" if device == "cuda" else "int8")


# ============================================================
# Model pre-download with progress reporting
# ============================================================

def _predownload_model(model_name: str, download_root: str):
    """
    Download a faster-whisper (CTranslate2) model from the Hugging Face Hub and keep it cached.
    Reports progress via _append_progress.
    Returns (model_path, 0, cached_size) or (None, None, None) on error.
    """
    from faster_whisper.utils import available_models, download_model

    if model_name not in available_models():
        return None, None, None

    os.makedirs(download_root, exist_ok=True)

    try:
        # Cached already? Avoid touching the network.
        model_path = download_model(model_name, cache_dir=download_root, local_files_only=True)
    except Exception:
        _append_progress(0.0, f"Downloading Whisper model '{model_name}'", "download", False)
        try:
            model_path = download_model(model_name, cache_dir=download_root)
        except Exception as e:
            print(f"[Failed to pre-download {model_name}: {e}]")
            _append_progress(0.0, f"Failed to download model '{model_name}': {e}", "download", False)
            return None, None, None
        _append_progress(1.0, f"Model '{model_name}' cached at {model_path}", "download", False)
        print(f"[Model {model_name} cached at {model_path}]")

    cached_size = sum(
        os.path.getsize(os.path.join(root, f))
        for root, _, files in os.walk(model_path) for f in files
    )
    return model_path, 0, cached_size


# ============================================================
# Model loading with progress reporting
# ============================================================

def _ensure_model(name=None, device=None, final=True):
    """Load or switch to the requested Whisper model. Thread-safe. Reports progress.

    `final=False` keeps the "ready" event from being marked done, for loads that
    happen in the middle of a transcription.
    """
    global model, current_model_name, current_device
    name = name or current_model_name
    device = device or current_device

    if device == "cuda":
        import torch
        if not torch.cuda.is_available():
            device = "cpu"

    with model_lock:
        if model is not None and current_model_name == name and current_device == device:
            return True

        model = None
        if device != current_device:
            # Alignment / diarization models live on the old device
            _unload_aux_models()
        _free_device_memory()

        compute_type = _get_compute_type(device)
        _append_progress(0.1, f"Loading model '{name}' on {device} ({compute_type})", "load", False)

        try:
            import whisperx

            model_path, _, _ = _predownload_model(name, _get_model_cache_dir())
            _append_progress(0.5, "Loading weights into model…", "load", False)
            model = whisperx.load_model(
                model_path or name,
                device,
                compute_type=compute_type,
                download_root=_get_model_cache_dir(),
            )
            current_model_name = name
            current_device = device
            _append_progress(1.0, f"Model '{name}' ready on {device}", "load", final)
            return True
        except Exception as e:
            print(f"Error loading model {name}: {e}")
            import traceback
            traceback.print_exc()
            _append_progress(0.0, f"Failed to load model: {e}", "load", True)
            return False


def _get_align_model(language):
    """Return a cached (align_model, metadata) for `language`, or None if unsupported."""
    with aux_lock:
        if language not in align_models:
            import whisperx
            try:
                align_models[language] = whisperx.load_align_model(language_code=language, device=current_device)
            except ValueError as e:
                # No default wav2vec2 alignment model for this language
                print(f"[Alignment unavailable for '{language}': {e}]")
                align_models[language] = None
        return align_models[language]


class DiarizationUnavailable(RuntimeError):
    """Speaker diarization requested but the pipeline cannot be used."""


def _get_diarize_model():
    """Return the cached pyannote diarization pipeline, loading it on first use."""
    global diarize_model
    with aux_lock:
        if diarize_model is None:
            from whisperx.diarize import DiarizationPipeline
            try:
                diarize_model = DiarizationPipeline(model_name=DIARIZE_MODEL, token=HF_TOKEN, device=current_device)
            except Exception as e:
                name = DIARIZE_MODEL or "pyannote/speaker-diarization-community-1"
                raise DiarizationUnavailable(
                    f"Could not load diarization model '{name}': {e}. "
                    f"Check HF_TOKEN and accept the model terms at https://huggingface.co/{name}"
                ) from e
        return diarize_model


# ============================================================
# Health info
# ============================================================

def get_health_info():
    """Return server health info."""
    torch_cuda = _check_torch_cuda()
    nvidia_gpu = _get_nvidia_gpu_info()

    has_nvidia_gpu = nvidia_gpu is not None and len(nvidia_gpu) > 0
    gpu_available_for_model = has_nvidia_gpu
    gpu_busy = False

    if has_nvidia_gpu:
        for gpu in nvidia_gpu:
            if gpu["utilization_pct"] > 90 or gpu["vram_used_mb"] > gpu["vram_total_mb"] * 0.9:
                gpu_busy = True

    recommendation = "CPU is fine for small/medium models."
    if has_nvidia_gpu:
        total_free = sum(g["vram_free_mb"] for g in nvidia_gpu)
        if total_free >= 8000:
            recommendation = "GPU available — safe to use large/large-v3 models."
        elif total_free >= 4000:
            recommendation = "GPU partially available — medium model OK, large may run out of VRAM."
        else:
            recommendation = "GPU VRAM limited — stay with small/base models or free up VRAM."
    else:
        recommendation = "No NVIDIA GPU detected — use CPU or base/small models."

    ffmpeg_path = _get_ffmpeg_path()
    return {
        "device": current_device,
        "model": current_model_name,
        "model_loaded": model is not None,
        "torch_cuda": torch_cuda,
        "nvidia_gpu": nvidia_gpu,
        "gpu_available_for_model": gpu_available_for_model and not gpu_busy,
        "gpu_busy": gpu_busy,
        "recommendation": recommendation,
        "ffmpeg_available": ffmpeg_path is not None,
        "ffmpeg_path": ffmpeg_path,
        "diarization_available": HF_TOKEN is not None,
        "diarization_loaded": diarize_model is not None,
        "diarize_default": DIARIZE_DEFAULT,
    }


def _get_ffmpeg_path():
    """Get the ffmpeg path."""
    ffmpeg_path = os.getenv("FFMPEG_PATH")
    if ffmpeg_path and os.path.exists(ffmpeg_path):
        return ffmpeg_path

    conda_env = os.getenv("CONDA_PREFIX")
    if conda_env:
        conda_ffmpeg = os.path.join(conda_env, "bin", "ffmpeg")
        if os.path.exists(conda_ffmpeg):
            return conda_ffmpeg

    for path in [
        "/home/remi/miniconda3/envs/whisper-gpu/bin/ffmpeg",
        "/usr/bin/ffmpeg",
    ]:
        if os.path.exists(path):
            return path
    return None


FFMPEG_PATH = _get_ffmpeg_path()
if FFMPEG_PATH:
    print(f"ffmpeg found at: {FFMPEG_PATH}")
else:
    print("Warning: ffmpeg not found, falling back to 'ffmpeg' on PATH for audio decoding")


# ============================================================
# FastAPI routes
# ============================================================

@app.get("/", response_class=HTMLResponse)
async def index():
    with open("templates/index.html", "r") as f:
        return f.read()


@app.get("/health")
async def health():
    info = get_health_info()
    if info["gpu_available_for_model"]:
        info["status"] = "green"
    elif info.get("gpu_busy"):
        info["status"] = "yellow"
    else:
        info["status"] = "red"
    return info


@app.get("/progress")
async def progress():
    """Return current progress event for model download / load / transcription."""
    return _get_progress()


@app.post("/eject")
async def eject():
    """Eject all loaded models from GPU to free VRAM."""
    success = _eject_models()
    return {"status": "success" if success else "no model was loaded",
            "message": "Models ejected from GPU" if success else "No model was loaded"}


@app.post("/switch-model")
async def switch_model(request: dict = Body(...)):
    """Dynamically switch the Whisper model and device."""
    new_model = request.get("model", current_model_name)
    new_device = request.get("device", current_device)

    if new_model not in VALID_MODELS:
        raise HTTPException(status_code=400, detail=f"Invalid model. Choose from: {VALID_MODELS}")

    if new_device not in VALID_DEVICES:
        raise HTTPException(status_code=400, detail=f"Invalid device. Choose from: {VALID_DEVICES}")

    if new_device == "cuda":
        import torch
        if not torch.cuda.is_available():
            raise HTTPException(status_code=400, detail="CUDA not available on this system.")
        if not _check_torch_cuda()["available"]:
            raise HTTPException(status_code=400, detail="CUDA not available on this system.")

    _clear_progress()

    try:
        success = await run_in_threadpool(_ensure_model, new_model, new_device)
        if not success:
            raise HTTPException(status_code=500, detail=f"Failed to load model: {new_model}")

        return {
            "status": "success",
            "model": current_model_name,
            "device": current_device,
            "message": f"Switched to {current_model_name} on {current_device}",
        }
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to load model: {str(e)}")


# ============================================================
# Audio decoding
# ============================================================

class AudioDecodeError(ValueError):
    """The uploaded file could not be decoded as audio."""


def load_audio(path):
    """Decode any ffmpeg-supported file to a 16 kHz mono float32 waveform.

    The same array is shared by transcription, alignment and diarization,
    so the file is only decoded once.
    """
    cmd = [
        FFMPEG_PATH or "ffmpeg", "-nostdin", "-threads", "0", "-i", path,
        "-f", "s16le", "-ac", "1", "-acodec", "pcm_s16le", "-ar", str(SAMPLE_RATE), "-",
    ]
    try:
        result = subprocess.run(cmd, capture_output=True, check=True, timeout=300)
    except FileNotFoundError as e:
        raise RuntimeError("ffmpeg not found: install it or set FFMPEG_PATH") from e
    except subprocess.CalledProcessError as e:
        stderr = e.stderr.decode(errors="replace").strip().splitlines()
        raise AudioDecodeError(f"Could not decode audio: {stderr[-1] if stderr else e}") from e

    audio = np.frombuffer(result.stdout, np.int16).flatten().astype(np.float32) / 32768.0
    if audio.size == 0:
        raise AudioDecodeError("Could not decode audio: file contains no audio samples")
    return audio


async def _save_upload(file: UploadFile):
    with tempfile.NamedTemporaryFile(suffix=os.path.splitext(file.filename or "")[1], delete=False) as tmp:
        tmp.write(await file.read())
        return tmp.name


# ============================================================
# Transcription pipeline
# ============================================================

def _progress_range(start, end, message, stage):
    """Map a whisperx 0-100 progress callback onto [start, end] of the overall progress."""
    def callback(pct):
        _append_progress(start + (end - start) * min(pct, 100.0) / 100.0,
                         f"{message} ({pct:.0f}%)", stage, False)
    return callback


def _run_transcription(audio, diarize=False, num_speakers=None, min_speakers=None, max_speakers=None):
    """Transcribe, align and optionally diarize a 16 kHz waveform. Blocking."""
    import whisperx

    if not _ensure_model(final=False):
        raise RuntimeError(f"Failed to load model: {current_model_name}")
    asr_model = model

    # Phase 1: Transcribe (VAD-batched faster-whisper)
    _append_progress(0.1, "Running Whisper transcription…", "transcribe", False)
    result = asr_model.transcribe(
        audio,
        batch_size=BATCH_SIZE,
        progress_callback=_progress_range(0.1, 0.5, "Transcribing", "transcribe"),
    )
    language = result.get("language", "unknown")

    # Phase 2: Word-level alignment (wav2vec2)
    aligned = False
    align = _get_align_model(language) if result["segments"] else None
    if align is not None:
        _append_progress(0.5, f"Aligning words ({language})…", "align", False)
        align_model, align_metadata = align
        result = whisperx.align(
            result["segments"], align_model, align_metadata, audio, current_device,
            return_char_alignments=False,
            progress_callback=_progress_range(0.5, 0.65, "Aligning words", "align"),
        )
        aligned = True

    # Phase 3: Speaker diarization (pyannote)
    if diarize and result["segments"]:
        _append_progress(0.65, "Identifying speakers…", "diarize", False)
        diarize_segments = _get_diarize_model()(
            audio,
            num_speakers=num_speakers,
            min_speakers=min_speakers,
            max_speakers=max_speakers,
            progress_callback=_progress_range(0.65, 0.95, "Identifying speakers", "diarize"),
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
        "segments": segments,
    }
    if diarize:
        response["speakers"] = list_speakers(segments)
        response["text_with_speakers"] = format_speaker_transcript(segments, language)
    return to_builtin(response)


def _validate_speaker_hints(num_speakers, min_speakers, max_speakers):
    for name, value in (("num_speakers", num_speakers), ("min_speakers", min_speakers),
                        ("max_speakers", max_speakers)):
        if value is not None and value < 1:
            raise HTTPException(status_code=400, detail=f"{name} must be >= 1")
    if min_speakers is not None and max_speakers is not None and min_speakers > max_speakers:
        raise HTTPException(status_code=400, detail="min_speakers must be <= max_speakers")


# ============================================================
# Transcribe endpoint (with progress tracking)
# ============================================================

@app.post("/transcribe")
async def transcribe(
    file: UploadFile = File(...),
    diarize: Optional[bool] = Form(None),
    num_speakers: Optional[int] = Form(None),
    min_speakers: Optional[int] = Form(None),
    max_speakers: Optional[int] = Form(None),
):
    """Transcribe uploaded audio with word timestamps, optionally labelling speakers."""
    diarize = DIARIZE_DEFAULT if diarize is None else diarize
    _validate_speaker_hints(num_speakers, min_speakers, max_speakers)
    if diarize and HF_TOKEN is None:
        raise HTTPException(
            status_code=503,
            detail="Speaker diarization is not configured: set HF_TOKEN to a Hugging Face token "
                   "that has accepted the pyannote model terms.",
        )

    tmp_path = await _save_upload(file)
    try:
        file_size = os.path.getsize(tmp_path)
        _append_progress(0.0, f"Processing '{file.filename}' ({file_size} bytes)", "transcribe", False)

        _append_progress(0.05, "Decoding audio…", "transcribe", False)
        audio = await run_in_threadpool(load_audio, tmp_path)

        response = await run_in_threadpool(
            _run_transcription, audio, diarize, num_speakers, min_speakers, max_speakers,
        )
        _append_progress(1.0, "Transcription complete!", "transcribe", True)
        return response

    except AudioDecodeError as e:
        _append_progress(0.0, f"Error: {e}", "transcribe", True)
        raise HTTPException(status_code=400, detail=str(e))
    except DiarizationUnavailable as e:
        _append_progress(0.0, f"Error: {e}", "diarize", True)
        raise HTTPException(status_code=503, detail=str(e))
    except Exception as e:
        _append_progress(0.0, f"Error: {e}", "transcribe", True)
        raise HTTPException(status_code=500, detail=str(e))
    finally:
        os.unlink(tmp_path)


def _run_quick_transcription(audio):
    """Transcription only (no alignment / diarization), for low-latency use. Blocking."""
    if not _ensure_model(final=False):
        raise RuntimeError(f"Failed to load model: {current_model_name}")
    result = model.transcribe(audio, batch_size=BATCH_SIZE)
    return {
        "text": " ".join(seg["text"].strip() for seg in result["segments"]).strip(),
        "language": result.get("language", "unknown"),
        "task": "transcribe",
    }


@app.post("/transcribe/streaming")
async def transcribe_streaming(file: UploadFile = File(...)):
    """Fast transcription with language detection, skipping alignment and diarization."""
    tmp_path = await _save_upload(file)
    try:
        _append_progress(0.0, f"Streaming: '{file.filename}'", "streaming", False)
        audio = await run_in_threadpool(load_audio, tmp_path)
        _append_progress(0.3, "Transcribing (streaming)…", "streaming", False)
        response = await run_in_threadpool(_run_quick_transcription, audio)
        _append_progress(1.0, "Streaming transcription complete!", "streaming", True)
        return response
    except AudioDecodeError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
    finally:
        os.unlink(tmp_path)


# ============================================================
# Startup
# ============================================================

@app.on_event("startup")
async def startup_event():
    """Pre-download and cache the initial model on server startup."""
    print(f"\n{'='*60}")
    print(f"Starting Speech-to-Text server")
    print(f"Initial model: {current_model_name} on {current_device}")
    print(f"Model cache dir: {_get_model_cache_dir()}")
    print(f"Speaker diarization: {'available' if HF_TOKEN else 'disabled (HF_TOKEN not set)'}")
    print(f"{'='*60}\n")

    _clear_progress()
    _append_progress(0.0, "Loading initial model…", "load", False)
    success = _ensure_model(current_model_name, current_device)
    if success:
        print(f"Whisper model ready: {current_model_name} on {current_device}")
    else:
        print(f"Warning: Failed to load initial model {current_model_name}")


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=int(os.getenv("WHISPER_PORT", "8081")))
