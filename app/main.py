import os
import sys
import time
import hashlib
import json
import threading
import tempfile
import subprocess
from pathlib import Path
from fastapi import FastAPI, UploadFile, File, HTTPException, Body
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.middleware.cors import CORSMiddleware

app = FastAPI(title="Speech-to-Text", description="Voice transcription powered by OpenAI Whisper")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# ============================================================
# Global state
# ============================================================
model = None
model_lock = threading.Lock()
current_model_name = os.getenv("WHISPER_MODEL", "base")
current_device = os.getenv("WHISPER_DEVICE", "cpu")

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

def _eject_models():
    """Unload all Whisper models from GPU to free VRAM. Thread-safe."""
    global model, current_model_name, current_device
    with model_lock:
        if model is not None:
            # Move model to CPU then delete to free GPU memory
            model.cpu()
            del model
            model = None
            current_model_name = None
            current_device = None
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
    """Return the Whisper model cache directory."""
    xdg = os.getenv("XDG_CACHE_HOME", os.path.join(os.path.expanduser("~"), ".cache"))
    return os.path.join(xdg, "whisper")


# ============================================================
# Model pre-download with progress reporting
# ============================================================

def _predownload_model(model_name: str, download_root: str):
    """
    Force-download a Whisper model and keep it cached.
    Reports progress via _append_progress.
    Returns (checkpoint_path, file_size, cached_size) or (None, None, None) on error.
    """
    import whisper

    cache_dir = _get_model_cache_dir()
    os.makedirs(cache_dir, exist_ok=True)

    if model_name not in whisper._MODELS:
        return None, None, None

    url = whisper._MODELS[model_name]
    expected_hash = ""  # hash not stored in whisper._MODELS (dict of str)
    filename = url.split("/")[-1]
    checkpoint_path = os.path.join(cache_dir, filename)

    # Already cached? (skip hash check since it's not available)
    if os.path.exists(checkpoint_path):
        file_size = os.path.getsize(checkpoint_path)
        return checkpoint_path, 0, file_size

    _append_progress(0.0, f"Downloading Whisper model '{model_name}'", "download", False)

    try:
        import urllib.request

        tmp_path = checkpoint_path + ".tmp"
        with urllib.request.urlopen(url, timeout=60) as response:
            total = int(response.headers.get("Content-Length", 0))
            downloaded = 0
            with open(tmp_path, "wb") as f:
                while True:
                    chunk = response.read(8192)
                    if not chunk:
                        break
                    f.write(chunk)
                    downloaded += len(chunk)
                    pct = downloaded / total if total else 0
                    _append_progress(pct,
                                     f"Downloading '{model_name}': {downloaded}/{total} bytes ({pct*100:.1f}%)",
                                     "download", False)

        os.replace(tmp_path, checkpoint_path)
        file_size = os.path.getsize(checkpoint_path)
        _append_progress(1.0, f"Model '{model_name}' cached at {checkpoint_path}", "download", True)
        print(f"[Model {model_name} cached at {checkpoint_path}]")
        return checkpoint_path, 0, file_size

    except Exception as e:
        print(f"[Failed to pre-download {model_name}: {e}]")
        tmp_path = checkpoint_path + ".tmp"
        if os.path.exists(tmp_path):
            os.unlink(tmp_path)
        return None, None, None


# ============================================================
# Model loading with progress reporting
# ============================================================

def _ensure_model(name=None, device=None):
    """Load or switch to the requested Whisper model. Thread-safe. Reports progress."""
    global model, current_model_name, current_device
    name = name or current_model_name
    device = device or current_device

    with model_lock:
        if model is not None and current_model_name == name and current_device == device:
            return True

        if model is not None:
            del model
            model = None

        if device == "cuda":
            import torch
            if not torch.cuda.is_available():
                device = "cpu"

        _append_progress(0.1, f"Loading model '{name}' on {device}", "load", False)

        try:
            result = _predownload_model(name, _get_model_cache_dir())
            if result[0]:
                checkpoint_path, _, _ = result
                import torch
                import whisper

                if name in whisper._MODELS:
                    _append_progress(0.5, "Loading weights into model…", "load", False)

                    kwargs = {"weights_only": True} if torch.__version__ >= "1.13" else {}
                    checkpoint = torch.load(checkpoint_path, map_location=device, **kwargs)

                    dims = whisper.ModelDimensions(**checkpoint["dims"])
                    model = whisper.Whisper(dims)
                    model.load_state_dict(checkpoint["model_state_dict"])

                    alignment_heads = whisper._ALIGNMENT_HEADS.get(name)
                    if alignment_heads is not None:
                        model.set_alignment_heads(alignment_heads)

                    model = model.to(device)
                    current_model_name = name
                    current_device = device
                    _append_progress(1.0, f"Model '{name}' ready on {device}", "load", True)
                    return True
        except Exception as e:
            print(f"Error loading model {name}: {e}")
            import traceback
            traceback.print_exc()
            _append_progress(0.0, f"Failed to load model: {e}", "load", True)
            return False

        # Fallback
        _append_progress(0.3, "Using Whisper built-in loader (fallback)", "load", False)
        import whisper
        model = whisper.load_model(name, device=device)
        current_model_name = name
        current_device = device
        _append_progress(1.0, f"Model '{name}' ready on {device}", "load", True)
        return True


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
        "/home/remi/miniconda3/bin/ffmpeg",
    ]:
        if os.path.exists(path):
            return path
    return None


FFMPEG_PATH = _get_ffmpeg_path()
if FFMPEG_PATH:
    print(f"ffmpeg found at: {FFMPEG_PATH}")
else:
    print("Warning: ffmpeg not found, will rely on librosa for audio loading")


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

    valid_models = ["tiny", "base", "small", "medium", "large", "large-v3"]
    if new_model not in valid_models:
        raise HTTPException(status_code=400, detail=f"Invalid model. Choose from: {valid_models}")

    valid_devices = ["cpu", "cuda"]
    if new_device not in valid_devices:
        raise HTTPException(status_code=400, detail=f"Invalid device. Choose from: {valid_devices}")

    if new_device == "cuda":
        import torch
        if not torch.cuda.is_available():
            raise HTTPException(status_code=400, detail="CUDA not available on this system.")
        if not _check_torch_cuda()["available"]:
            raise HTTPException(status_code=400, detail="CUDA not available on this system.")

    _clear_progress()

    try:
        success = _ensure_model(new_model, new_device)
        if not success:
            raise HTTPException(status_code=500, detail=f"Failed to load model: {current_model_name}")

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
# Audio conversion
# ============================================================

def convert_audio_to_wav(tmp_path):
    """Convert uploaded audio to WAV using ffmpeg."""
    ext = os.path.splitext(tmp_path)[1].lower()
    if ext in ('.wav', '.wave'):
        return tmp_path, ext

    if FFMPEG_PATH:
        try:
            wav_path = tmp_path + ".wav"
            result = subprocess.run(
                [FFMPEG_PATH, "-y", "-i", tmp_path, wav_path],
                capture_output=True, text=True, timeout=30,
            )
            if result.returncode == 0 and os.path.exists(wav_path):
                return wav_path, ".wav"
        except (FileNotFoundError, subprocess.TimeoutExpired):
            pass

    return tmp_path, ext


# ============================================================
# Transcribe endpoint (with progress tracking)
# ============================================================

@app.post("/transcribe")
async def transcribe(file: UploadFile = File(...)):
    """Transcribe uploaded audio file with progress tracking."""
    try:
        # Save uploaded file
        with tempfile.NamedTemporaryFile(suffix=os.path.splitext(file.filename)[1], delete=False) as tmp:
            content = await file.read()
            tmp.write(content)
            tmp_path = tmp.name

        file_size = os.path.getsize(tmp_path)
        _append_progress(0.0, f"Processing '{file.filename}' ({file_size} bytes)", "transcribe", False)

        # Phase 1: Convert
        _append_progress(0.1, "Converting audio format…", "transcribe", False)
        processed_path, _ = convert_audio_to_wav(tmp_path)
        _append_progress(0.25, "Analyzing audio…", "transcribe", False)

        # Phase 2: Transcribe with progress callback
        _append_progress(0.35, "Running Whisper transcription…", "transcribe", False)

        result = model.transcribe(
            processed_path,
            fp16=(current_device == "cuda"),
        )

        # Mark complete
        _append_progress(0.95, "Processing segments…", "transcribe", False)
        _append_progress(1.0, "Transcription complete!", "transcribe", True)

        os.unlink(tmp_path)
        if processed_path != tmp_path:
            os.unlink(processed_path)

        return {
            "text": result["text"],
            "language": result.get("language", "unknown"),
            "segments": result.get("segments", []),
        }

    except Exception as e:
        _append_progress(0.0, f"Error: {e}", "transcribe", True)
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/transcribe/streaming")
async def transcribe_streaming(file: UploadFile = File(...)):
    """Return partial results in real-time."""
    try:
        with tempfile.NamedTemporaryFile(suffix=os.path.splitext(file.filename)[1], delete=False) as tmp:
            content = await file.read()
            tmp.write(content)
            tmp_path = tmp.name

        _append_progress(0.0, f"Streaming: '{file.filename}'", "streaming", False)
        processed_path, _ = convert_audio_to_wav(tmp_path)
        _append_progress(0.3, "Transcribing (streaming)…", "streaming", False)

        audio = model.load_audio(processed_path)
        language = model.detect_language(audio)
        result = model.transcribe(processed_path, fp16=(current_device == "cuda"))
        _append_progress(1.0, "Streaming transcription complete!", "streaming", True)

        os.unlink(tmp_path)
        if processed_path != tmp_path:
            os.unlink(processed_path)

        return {
            "text": result["text"],
            "language": language.language,
            "task": "transcribe",
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


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
