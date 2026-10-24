"""FastAPI application: web UI and HTTP API."""
import asyncio
import logging
import os
import re
import tempfile
import threading
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Optional
from urllib.parse import urlsplit

from fastapi import Body, FastAPI, File, Form, HTTPException, Query, Request, UploadFile
from fastapi.concurrency import run_in_threadpool
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles

from app import hf, system
from app.config import VALID_DEVICES, VALID_MODELS, load_settings
from app.errors import http_status_for
from app.formats import EXPORT_FORMATS, export
from app.jobs import JobManager
from app.languages import LANGUAGES
from app.pipeline import Engine, model_cache_dir
from app.worker import InlineBackend, ProcessBackend

__version__ = "2.0.0"

ROOT = Path(__file__).resolve().parent.parent
TEMPLATES_DIR = ROOT / "templates"
STATIC_DIR = ROOT / "static"
UPLOAD_CHUNK = 1024 * 1024

log = logging.getLogger("app")


def create_backend(settings, ffmpeg_path):
    engine_kwargs = {
        "batch_size": settings.batch_size,
        "compute_type": settings.compute_type,
        "diarize_model": settings.diarize_model,
        "ffmpeg_path": ffmpeg_path,
        "ffmpeg_timeout": settings.ffmpeg_timeout,
    }
    if settings.worker_mode == "inline":
        return InlineBackend(Engine(**engine_kwargs))
    return ProcessBackend("app.pipeline:Engine", engine_kwargs)


# ============================================================
# Request helpers
# ============================================================

def _validate_speaker_hints(num_speakers, min_speakers, max_speakers):
    for name, value in (("num_speakers", num_speakers), ("min_speakers", min_speakers),
                        ("max_speakers", max_speakers)):
        if value is not None and value < 1:
            raise HTTPException(status_code=400, detail=f"{name} must be >= 1")
    if min_speakers is not None and max_speakers is not None and min_speakers > max_speakers:
        raise HTTPException(status_code=400, detail="min_speakers must be <= max_speakers")


def _validate_language(language):
    language = (language or "").strip().lower()
    if language in ("", "auto"):
        return None
    if language not in LANGUAGES:
        raise HTTPException(status_code=400, detail=f"Unsupported language '{language}'")
    return language


def _require_diarization_token():
    if hf.get_token() is None:
        raise HTTPException(
            status_code=503,
            detail="Speaker diarization is not configured: add a Hugging Face token in the web UI, "
                   "set HF_TOKEN or run `hf auth login` (the token must have accepted the pyannote model terms).",
        )


async def _save_upload(file: UploadFile, max_bytes):
    """Copy the upload to a temporary file in chunks. Returns (path, size)."""
    suffix = Path(file.filename or "").suffix
    suffix = suffix if re.fullmatch(r"\.[A-Za-z0-9]{1,10}", suffix or "") else ""
    fd, path = tempfile.mkstemp(prefix="stt-upload-", suffix=suffix)
    size = 0
    try:
        with os.fdopen(fd, "wb") as out:
            while chunk := await file.read(UPLOAD_CHUNK):
                size += len(chunk)
                if size > max_bytes:
                    raise HTTPException(status_code=413,
                                        detail=f"File too large (limit {max_bytes // (1024 * 1024)} MB, "
                                               f"set WHISPER_MAX_UPLOAD_MB to change it)")
                out.write(chunk)
        if size == 0:
            raise HTTPException(status_code=400, detail="The uploaded file is empty")
    except BaseException:
        os.unlink(path)
        raise
    return path, size


async def _wait(job, poll=0.1):
    while not job.finished:
        await asyncio.sleep(poll)
    return job


def _raise_for_job(job):
    if job.status == "cancelled":
        raise HTTPException(status_code=409, detail="The job was cancelled")
    if job.status == "error":
        raise HTTPException(status_code=http_status_for(job.error["code"]), detail=job.error["message"])


def _get_job(request, job_id):
    job = request.app.state.jobs.get(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail="Job not found (it may have expired)")
    return job


def _require_same_origin(request: Request):
    """Token settings may only be changed from this server's own page, whatever the CORS policy."""
    origin = request.headers.get("origin")
    if origin and urlsplit(origin).netloc != request.headers.get("host"):
        raise HTTPException(status_code=403, detail="Token settings can only be changed from the server's own web UI")


def _hf_token_status(settings):
    token = hf.get_token()
    return {
        "configured": token is not None,
        "source": hf.token_source(),
        "masked": hf.mask(token),
        "diarize_model": settings.diarize_model_name,
        "model_url": f"https://huggingface.co/{settings.diarize_model_name}",
        "token_path": str(hf.token_path()),
    }


# ============================================================
# Application
# ============================================================

def create_app(settings=None, backend=None):
    settings = settings or load_settings()
    ffmpeg_path = system.find_ffmpeg(settings.ffmpeg_path)
    max_upload_bytes = settings.max_upload_mb * 1024 * 1024

    @asynccontextmanager
    async def lifespan(app):
        device = settings.device
        if device == "cuda" and not await run_in_threadpool(system.cuda_available):
            log.warning("WHISPER_DEVICE=cuda but CUDA is not available to PyTorch: using the CPU")
            device = "cpu"
        else:
            threading.Thread(target=system.torch_cuda_info, daemon=True).start()  # warm the cache

        manager = JobManager(backend or create_backend(settings, ffmpeg_path), settings.model, device,
                             settings.job_history)
        app.state.jobs = manager
        manager.start()

        log.info("=" * 60)
        log.info("Speech-to-Text %s on http://%s:%s", __version__, settings.host, settings.port)
        log.info("Model: %s on %s (worker mode: %s)", settings.model, device, settings.worker_mode)
        log.info("Model cache dir: %s", model_cache_dir())
        log.info("ffmpeg: %s", ffmpeg_path or "NOT FOUND (install it or set FFMPEG_PATH)")
        source = hf.token_source()
        log.info("Speaker diarization: %s", f"available (token from {source})" if source
                 else "disabled until a Hugging Face token is added")
        log.info("=" * 60)

        if settings.preload:
            manager.submit("load", {"model": settings.model, "device": device})
        try:
            yield
        finally:
            await run_in_threadpool(manager.stop)

    app = FastAPI(
        title="Speech-to-Text",
        description="Voice transcription with speaker diarization powered by WhisperX",
        version=__version__,
        lifespan=lifespan,
    )
    app.state.settings = settings
    app.add_middleware(
        CORSMiddleware,
        allow_origins=list(settings.cors_origins),
        allow_methods=["*"],
        allow_headers=["*"],
    )
    if STATIC_DIR.is_dir():
        app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")

    @app.exception_handler(Exception)
    async def unhandled_error(request: Request, exc: Exception):
        # Always answer JSON, so clients can show the reason instead of a parse error
        log.error("Unhandled error on %s %s: %r", request.method, request.url.path, exc)
        return JSONResponse(status_code=500, content={"detail": f"Internal server error: {type(exc).__name__}: {exc}"})

    # -------------------------------------------------------------- UI

    @app.get("/", response_class=HTMLResponse)
    def index():
        html = (TEMPLATES_DIR / "index.html").read_text(encoding="utf-8")
        return HTMLResponse(html.replace("{{version}}", __version__), headers={"Cache-Control": "no-cache"})

    # ---------------------------------------------------------- status

    @app.get("/healthz")
    def healthz():
        """Liveness probe: answers as long as the web server runs."""
        return {"status": "ok"}

    @app.get("/health")
    def health(request: Request):
        state = request.app.state.jobs.state()
        gpus = system.nvidia_gpu_info()
        status, busy, recommendation = system.gpu_summary(gpus)
        source = hf.token_source()
        return {
            "status": status,
            "version": __version__,
            "device": state["device"],
            "model": state["model"],
            "model_loaded": state["model_loaded"],
            "busy": state["busy"],
            "current_job": state["current_job"],
            "queued": state["queued"],
            "torch_cuda": system.torch_cuda_info(),
            "nvidia_gpu": gpus,
            "gpu_available_for_model": status == "green",
            "gpu_busy": busy,
            "recommendation": recommendation,
            "ffmpeg_available": ffmpeg_path is not None,
            "ffmpeg_path": ffmpeg_path,
            "diarization_available": source is not None,
            "diarization_loaded": state["diarization_loaded"],
            "diarize_default": settings.diarize_default,
            "hf_token_source": source,
            "valid_models": VALID_MODELS,
            "valid_devices": VALID_DEVICES,
            "languages": LANGUAGES,
            "max_upload_mb": settings.max_upload_mb,
            "worker_mode": settings.worker_mode,
        }

    @app.get("/progress")
    def progress(request: Request):
        """Latest progress event (model download / load / transcription), for any job."""
        return request.app.state.jobs.latest_progress()

    # ---------------------------------------------------------- models

    @app.post("/eject")
    async def eject(request: Request, wait: bool = True):
        """Unload all models to free GPU / RAM memory (after the running job, if any)."""
        job = request.app.state.jobs.submit("eject")
        if not wait:
            return JSONResponse(status_code=202, content={"status": "queued", "job": job.to_dict()})
        await _wait(job)
        _raise_for_job(job)
        ejected = job.result["ejected"]
        return {"status": "success" if ejected else "no model was loaded",
                "message": "Models ejected from memory" if ejected else "No model was loaded"}

    @app.post("/switch-model")
    async def switch_model(request: Request, body: dict = Body(...)):
        """Switch the Whisper model and device. Pass `"wait": false` to get a job to poll instead."""
        jobs = request.app.state.jobs
        new_model = body.get("model") or jobs.model
        new_device = body.get("device") or jobs.device

        if new_model not in VALID_MODELS:
            raise HTTPException(status_code=400, detail=f"Invalid model. Choose from: {VALID_MODELS}")
        if new_device not in VALID_DEVICES:
            raise HTTPException(status_code=400, detail=f"Invalid device. Choose from: {VALID_DEVICES}")
        if new_device == "cuda" and not await run_in_threadpool(system.cuda_available):
            raise HTTPException(status_code=400, detail="CUDA not available on this system.")

        job = jobs.submit("load", {"model": new_model, "device": new_device})
        if body.get("wait") is False:
            return JSONResponse(status_code=202, content={"status": "queued", "job": job.to_dict()})
        await _wait(job)
        _raise_for_job(job)
        return {
            "status": "success",
            "model": new_model,
            "device": new_device,
            "message": f"Switched to {new_model} on {new_device}",
        }

    # --------------------------------------------------- transcription

    async def submit_transcription(request, file, diarize, num_speakers, min_speakers, max_speakers,
                                   language, quick=False):
        params = {"quick": quick, "language": _validate_language(language)}
        if not quick:
            diarize = settings.diarize_default if diarize is None else diarize
            _validate_speaker_hints(num_speakers, min_speakers, max_speakers)
            if diarize:
                _require_diarization_token()
            params.update(diarize=bool(diarize), num_speakers=num_speakers, min_speakers=min_speakers,
                          max_speakers=max_speakers)
        path, size = await _save_upload(file, max_upload_bytes)
        return request.app.state.jobs.submit("transcribe", params, filename=file.filename, file_size=size,
                                             audio_path=path)

    @app.post("/transcribe")
    async def transcribe(
        request: Request,
        file: UploadFile = File(...),
        diarize: Optional[bool] = Form(None),
        num_speakers: Optional[int] = Form(None),
        min_speakers: Optional[int] = Form(None),
        max_speakers: Optional[int] = Form(None),
        language: Optional[str] = Form(None),
    ):
        """Transcribe audio with word timestamps, optionally labelling speakers. Waits for the result."""
        job = await submit_transcription(request, file, diarize, num_speakers, min_speakers, max_speakers, language)
        await _wait(job)
        _raise_for_job(job)
        return job.result

    @app.post("/transcribe/streaming")
    async def transcribe_streaming(request: Request, file: UploadFile = File(...),
                                   language: Optional[str] = Form(None)):
        """Fast transcription with language detection, skipping alignment and diarization."""
        job = await submit_transcription(request, file, None, None, None, None, language, quick=True)
        await _wait(job)
        _raise_for_job(job)
        return job.result

    # ------------------------------------------------------------ jobs

    @app.post("/jobs", status_code=202)
    async def create_job(
        request: Request,
        file: UploadFile = File(...),
        diarize: Optional[bool] = Form(None),
        num_speakers: Optional[int] = Form(None),
        min_speakers: Optional[int] = Form(None),
        max_speakers: Optional[int] = Form(None),
        language: Optional[str] = Form(None),
        quick: bool = Form(False),
    ):
        """Queue a transcription and return immediately; poll GET /jobs/{id} for progress and result."""
        job = await submit_transcription(request, file, diarize, num_speakers, min_speakers, max_speakers,
                                         language, quick)
        return job.to_dict()

    @app.get("/jobs")
    def list_jobs(request: Request):
        return {"jobs": [job.to_dict(include_result=False) for job in request.app.state.jobs.list()]}

    @app.get("/jobs/{job_id}")
    def get_job(request: Request, job_id: str):
        return _get_job(request, job_id).to_dict()

    @app.post("/jobs/{job_id}/cancel")
    def cancel_job(request: Request, job_id: str):
        _get_job(request, job_id)
        return request.app.state.jobs.cancel(job_id).to_dict()

    @app.get("/jobs/{job_id}/export")
    def export_job(request: Request, job_id: str, format: str = Query("txt")):
        job = _get_job(request, job_id)
        if format not in EXPORT_FORMATS:
            raise HTTPException(status_code=400, detail=f"Unknown format. Choose from: {list(EXPORT_FORMATS)}")
        if job.kind != "transcribe" or job.status != "done":
            raise HTTPException(status_code=409, detail="This job has no transcription to export")
        if format in ("srt", "vtt") and not job.result.get("segments"):
            raise HTTPException(status_code=409, detail="Quick transcriptions have no timestamps to export")
        stem = re.sub(r"[^\w.-]+", "_", Path(job.filename or "transcription").stem)[:80] or "transcription"
        return Response(
            export(job.result, format),
            media_type=EXPORT_FORMATS[format],
            headers={"Content-Disposition": f'attachment; filename="{stem}.{format}"'},
        )

    # -------------------------------------------------------- settings

    @app.get("/settings/hf-token")
    def get_hf_token():
        """Whether a Hugging Face token is configured (the token itself is never returned)."""
        return _hf_token_status(settings)

    @app.put("/settings/hf-token")
    async def put_hf_token(request: Request, body: dict = Body(...)):
        """Verify and save a Hugging Face token (used for speaker identification)."""
        _require_same_origin(request)
        token = str(body.get("token") or "").strip()
        if not re.fullmatch(r"[A-Za-z0-9_\-]{8,512}", token):
            raise HTTPException(status_code=400, detail="This does not look like a Hugging Face token "
                                                        "(expected something like hf_xxxxxxxx).")
        check = None
        if body.get("verify", True):
            check = await run_in_threadpool(hf.check_token, token, settings.diarize_model_name)
            if check["valid"] is False:
                raise HTTPException(status_code=400, detail=check["message"])
        await run_in_threadpool(hf.save_token, token)
        status = _hf_token_status(settings)
        warning = None
        if status["source"] == "env":
            warning = ("The token was saved, but the HF_TOKEN environment variable is set and takes "
                       "precedence. Unset it to use the saved token.")
        return {**status, "saved": True, "check": check, "warning": warning}

    @app.delete("/settings/hf-token")
    async def delete_hf_token(request: Request):
        """Remove the saved Hugging Face token."""
        _require_same_origin(request)
        removed = await run_in_threadpool(hf.delete_token)
        status = _hf_token_status(settings)
        warning = None
        if status["source"] == "env":
            warning = "The HF_TOKEN environment variable is still set: speaker identification stays enabled."
        return {**status, "removed": removed, "warning": warning}

    @app.post("/settings/hf-token/check")
    async def check_hf_token(request: Request):
        """Check the configured token against the Hub (validity and access to the diarization model)."""
        _require_same_origin(request)
        token = hf.get_token()
        if token is None:
            raise HTTPException(status_code=404, detail="No Hugging Face token is configured")
        check = await run_in_threadpool(hf.check_token, token, settings.diarize_model_name)
        return {**_hf_token_status(settings), "check": check}

    return app


if not logging.getLogger().handlers:
    logging.basicConfig(level=os.getenv("WHISPER_LOG_LEVEL", "INFO").upper(),
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")

app = create_app()


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host=app.state.settings.host, port=app.state.settings.port)
