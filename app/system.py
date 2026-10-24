"""Host inspection: ffmpeg location, NVIDIA GPUs and PyTorch CUDA support."""
import functools
import logging
import os
import shutil
import subprocess
import threading
import time

log = logging.getLogger(__name__)


def find_ffmpeg(configured=None):
    """Return the ffmpeg binary: FFMPEG_PATH, the active conda env, then PATH."""
    if configured:
        if os.path.exists(configured):
            return configured
        log.warning("FFMPEG_PATH=%s does not exist, looking for ffmpeg elsewhere", configured)

    conda_env = os.getenv("CONDA_PREFIX")
    if conda_env:
        conda_ffmpeg = os.path.join(conda_env, "bin", "ffmpeg")
        if os.path.exists(conda_ffmpeg):
            return conda_ffmpeg

    found = shutil.which("ffmpeg")
    if found:
        return found
    return "/usr/bin/ffmpeg" if os.path.exists("/usr/bin/ffmpeg") else None


_gpu_cache = {"at": 0.0, "value": None}
_gpu_lock = threading.Lock()
GPU_CACHE_SECONDS = 2.0


def _query_nvidia_smi():
    try:
        result = subprocess.run(
            ["nvidia-smi", "--query-gpu=index,name,memory.total,memory.used,memory.free,utilization.gpu",
             "--format=csv,noheader,nounits"],
            capture_output=True, text=True, timeout=5,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    if result.returncode != 0:
        return None

    gpus = []
    for line in result.stdout.strip().splitlines():
        parts = [p.strip() for p in line.split(",")]
        if len(parts) != 6:
            continue
        try:
            gpus.append({
                "index": int(parts[0]),
                "name": parts[1],
                # nvidia-smi reports MiB with nounits
                "vram_total_mb": int(parts[2]),
                "vram_used_mb": int(parts[3]),
                "vram_free_mb": int(parts[4]),
                "utilization_pct": int(parts[5]) if parts[5].isdigit() else None,
            })
        except ValueError:
            continue
    return gpus


def nvidia_gpu_info(max_age=GPU_CACHE_SECONDS):
    """GPU list from nvidia-smi (cached briefly), or None when unavailable."""
    with _gpu_lock:
        now = time.monotonic()
        if max_age and now - _gpu_cache["at"] < max_age:
            return _gpu_cache["value"]
        value = _query_nvidia_smi()
        _gpu_cache.update(at=now, value=value)
        return value


@functools.lru_cache(maxsize=1)
def torch_cuda_info():
    """PyTorch CUDA support. Computed once: it does not change while the server runs."""
    cuda = {
        "available": False,
        "cuda_version": None,
        "device_count": 0,
        "devices": [],
        "error": None,
    }
    try:
        import torch
    except ImportError:
        cuda["error"] = "PyTorch is not installed"
        return cuda
    try:
        cuda["cuda_version"] = torch.version.cuda
        if torch.cuda.is_available():
            cuda["available"] = True
            cuda["device_count"] = torch.cuda.device_count()
            for i in range(cuda["device_count"]):
                props = torch.cuda.get_device_properties(i)
                cuda["devices"].append({
                    "index": i,
                    "name": props.name,
                    "total_memory_mb": round(props.total_memory / (1024 ** 2)),
                })
    except Exception as e:  # broken driver / CUDA install
        log.warning("CUDA check failed: %s", e)
        cuda["error"] = str(e)
    return cuda


def cuda_available():
    return torch_cuda_info()["available"]


def gpu_summary(gpus):
    """Return (status, busy, recommendation) for the health panel."""
    if not gpus:
        return "red", False, "No NVIDIA GPU detected — use CPU with tiny/base/small models."

    busy = any(
        (g["utilization_pct"] or 0) > 90 or g["vram_used_mb"] > g["vram_total_mb"] * 0.9
        for g in gpus
    )
    free = max(g["vram_free_mb"] for g in gpus)
    if free >= 8000:
        recommendation = "GPU available — safe to use large/large-v3 models."
    elif free >= 4000:
        recommendation = "GPU partially available — medium model OK, large may run out of VRAM."
    else:
        recommendation = "GPU VRAM limited — stay with small/base models or free up VRAM."
    return ("yellow" if busy else "green"), busy, recommendation
