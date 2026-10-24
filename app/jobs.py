"""Job queue.

Transcriptions and model operations (load / eject) are jobs. They run one at a
time, in submission order, on a single dispatcher thread, so a model switch
never happens in the middle of a transcription and two uploads never compete
for the GPU. Each job keeps its own progress and result, which clients poll
with short requests instead of holding one HTTP request open for minutes.
"""
import logging
import os
import queue
import threading
import time
import uuid
from collections import OrderedDict
from dataclasses import dataclass, field
from typing import Any

from app.errors import Cancelled, PipelineError

log = logging.getLogger(__name__)

FINISHED = ("done", "error", "cancelled")


@dataclass
class Job:
    kind: str  # transcribe | load | eject
    params: dict = field(default_factory=dict)
    filename: str | None = None
    file_size: int | None = None
    audio_path: str | None = None
    id: str = field(default_factory=lambda: uuid.uuid4().hex)
    status: str = "queued"
    progress: float = 0.0
    message: str = "Queued"
    stage: str = "queued"
    created_at: float = field(default_factory=time.time)
    started_at: float | None = None
    finished_at: float | None = None
    result: Any = None
    error: dict | None = None

    @property
    def finished(self):
        return self.status in FINISHED

    def to_dict(self, include_result=True):
        data = {
            "id": self.id,
            "kind": self.kind,
            "status": self.status,
            "progress": round(self.progress, 4),
            "message": self.message,
            "stage": self.stage,
            "params": self.params,
            "filename": self.filename,
            "file_size": self.file_size,
            "created_at": self.created_at,
            "started_at": self.started_at,
            "finished_at": self.finished_at,
            "error": self.error,
        }
        if include_result:
            data["result"] = self.result
        return data


class JobManager:
    def __init__(self, backend, model, device, history=50):
        self.backend = backend
        self.model = model  # model / device used for transcriptions
        self.device = device
        self.history = history
        self._jobs = OrderedDict()
        self._queue = queue.Queue()
        self._lock = threading.RLock()
        self._current = None
        self._latest = {"progress": 0.0, "message": "Waiting...", "stage": "idle", "done": False,
                        "status": "idle", "job_id": None}
        self._thread = None

    # ------------------------------------------------------------- lifecycle

    def start(self):
        self._thread = threading.Thread(target=self._run, name="stt-dispatcher", daemon=True)
        self._thread.start()

    def stop(self, timeout=15):
        with self._lock:
            pending = [j for j in self._jobs.values() if j.status == "queued"]
            for job in pending:
                self._finish(job, "cancelled", "Server shutting down")
            if self._current is not None:
                self.backend.cancel()
        self._queue.put(None)
        if self._thread is not None:
            self._thread.join(timeout)
        self.backend.close()

    # ---------------------------------------------------------------- public

    def submit(self, kind, params=None, filename=None, file_size=None, audio_path=None):
        job = Job(kind=kind, params=params or {}, filename=filename, file_size=file_size, audio_path=audio_path)
        with self._lock:
            ahead = sum(1 for j in self._jobs.values() if not j.finished)
            if ahead:
                job.message = f"Queued ({ahead} job{'s' if ahead > 1 else ''} ahead)"
            self._jobs[job.id] = job
            self._trim()
        self._queue.put(job)
        return job

    def get(self, job_id):
        with self._lock:
            return self._jobs.get(job_id)

    def list(self):
        with self._lock:
            return list(self._jobs.values())

    def cancel(self, job_id):
        with self._lock:
            job = self._jobs.get(job_id)
            if job is None or job.finished:
                return job
            if job.status == "queued":
                self._finish(job, "cancelled", "Cancelled")
            else:
                job.message = "Cancelling…"
                self.backend.cancel()
            return job

    def state(self):
        backend = self.backend.last_state
        loaded = (backend.get("model_loaded") and backend.get("model") == self.model
                  and backend.get("device") == self.device)
        with self._lock:
            return {
                "model": self.model,
                "device": self.device,
                "model_loaded": bool(loaded),
                "diarization_loaded": bool(backend.get("diarization_loaded")),
                "busy": self._current is not None,
                "current_job": self._current.id if self._current else None,
                "queued": sum(1 for j in self._jobs.values() if j.status == "queued"),
            }

    def latest_progress(self):
        with self._lock:
            return dict(self._latest)

    # --------------------------------------------------------------- private

    def _trim(self):
        finished = [j for j in self._jobs.values() if j.finished]
        for job in finished[:max(len(finished) - self.history, 0)]:
            del self._jobs[job.id]

    def _publish(self, job, done=False):
        progress = job.progress
        if job.status in ("error", "cancelled"):
            progress = min(progress, 0.99)  # /progress clients read "done and < 1" as a failure
        self._latest = {"progress": round(progress, 4), "message": job.message, "stage": job.stage,
                        "done": done, "status": job.status, "job_id": job.id}

    def _update(self, job, fraction, message, stage):
        with self._lock:
            if job.finished:
                return
            job.progress = min(max(float(fraction), job.progress), 1.0)  # never goes backwards
            if job.message != "Cancelling…":
                job.message = message
            job.stage = stage
            self._publish(job)

    def _finish(self, job, status, message, result=None, error=None):
        with self._lock:
            job.status = status
            job.message = message
            job.finished_at = time.time()
            job.result = result
            job.error = error
            if status == "done":
                job.progress = 1.0
            self._publish(job, done=True)
            self._remove_audio(job)
            self._trim()

    @staticmethod
    def _remove_audio(job):
        if job.audio_path:
            try:
                os.unlink(job.audio_path)
            except FileNotFoundError:
                pass
            except OSError as e:
                log.warning("Could not remove %s: %s", job.audio_path, e)
            job.audio_path = None

    def _run(self):
        while True:
            job = self._queue.get()
            if job is None:
                return
            with self._lock:
                if job.finished:  # cancelled while queued
                    continue
                job.status = "running"
                job.started_at = time.time()
                job.message = "Starting…"
                job.stage = job.kind
                self._current = job
                self._publish(job)
            try:
                self._execute(job)
            finally:
                with self._lock:
                    self._current = None

    def _execute(self, job):
        def progress(fraction, message, stage):
            self._update(job, fraction, message, stage)

        try:
            if job.kind == "transcribe":
                options = dict(job.params, model=self.model, device=self.device)
                result = self.backend.call("transcribe", {"audio_path": job.audio_path, "options": options}, progress)
                message = "Transcription complete!"
            elif job.kind == "load":
                model, device = job.params["model"], job.params["device"]
                self.backend.call("load", {"model": model, "device": device}, progress)
                with self._lock:
                    self.model, self.device = model, device
                result = {"model": model, "device": device}
                message = f"Model '{model}' ready on {device}"
            elif job.kind == "eject":
                ejected = bool(self.backend.call("eject", {}, progress))
                result = {"ejected": ejected}
                message = "Models ejected — memory freed" if ejected else "No model was loaded"
            else:
                raise ValueError(f"Unknown job kind {job.kind!r}")
        except Cancelled:
            self._finish(job, "cancelled", "Cancelled")
        except PipelineError as e:
            log.warning("Job %s (%s) failed: %s", job.id, job.kind, e)
            self._finish(job, "error", f"Error: {e}", error={"code": e.code, "message": str(e)})
        except Exception as e:
            log.exception("Job %s (%s) failed", job.id, job.kind)
            text = f"{type(e).__name__}: {e}"
            self._finish(job, "error", f"Error: {text}", error={"code": "internal", "message": text})
        else:
            self._finish(job, "done", message, result=result)
