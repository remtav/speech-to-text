"""Job queue.

Transcriptions and model operations (load / eject) are jobs. They run one at a
time, in submission order, on a single dispatcher thread, so a model switch
never happens in the middle of a transcription and two uploads never compete
for the GPU. Each job keeps its own progress and result, which clients poll
with short requests instead of holding one HTTP request open for minutes.

A transcription can end with an optional LLM "polish" post-step (see
``app/polish.py``). It runs here, in the server process, after the worker
returns, so Whisper and the LLM never compute at the same time. It adds
``polished_text`` and ``polish`` to the result and never fails the job:
``text`` always stays the raw Whisper transcript.
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
from app.polish import PolishResult

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
    cancel_requested: threading.Event = field(default_factory=threading.Event, repr=False, compare=False)

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


# Share of a polished transcription's progress bar given to the Whisper part;
# the polish stage fills the rest.
POLISH_PROGRESS_START = 0.97
POLISH_CANCEL_POLL = 0.05  # seconds


class JobManager:
    def __init__(self, backend, model, device, history=50, polisher=None):
        self.backend = backend
        self.polisher = polisher  # app.polish.Polisher, or None when polishing is impossible
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
                job.cancel_requested.set()  # seen by the polish step, which runs outside the backend
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
                polishing = bool(job.params.get("polish"))
                options = {k: v for k, v in job.params.items() if k != "polish"}
                options.update(model=self.model, device=self.device)
                whisper_progress = progress
                if polishing:  # leave the end of the bar to the polish stage
                    def whisper_progress(fraction, message, stage):
                        progress(fraction * POLISH_PROGRESS_START, message, stage)
                result = self.backend.call("transcribe", {"audio_path": job.audio_path, "options": options},
                                           whisper_progress)
                if polishing:
                    try:
                        self._polish(job, result, progress)
                    except Cancelled:
                        raise
                    except Exception as e:  # a polish problem must never fail the transcription
                        log.warning("Polish step failed: %s", type(e).__name__)
                        self._polish_failed(result)
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
                # Whisper's memory is the worker's; the polish model lives in the LLM server
                polish_unloaded = bool(self.polisher and self.polisher.unload())
                result = {"ejected": ejected, "polish_unloaded": polish_unloaded}
                message = "Models ejected — memory freed" if ejected or polish_unloaded else "No model was loaded"
            else:
                raise ValueError(f"Unknown job kind {job.kind!r}")
        except Cancelled:
            if not job.finished:  # the polish step finishes a cancelled job itself
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

    def _polish(self, job, result, progress):
        """Add the polished text to ``result``, in place. Raises only ``Cancelled``.

        The LLM call runs on a helper thread so a cancel is answered at once;
        the dispatcher still waits for that thread (bounded by the polish
        timeout) before the next job, so it never overlaps with Whisper.
        """
        if job.cancel_requested.is_set():
            raise Cancelled("Cancelled")
        text = result.get("text") or ""
        progress(POLISH_PROGRESS_START, "Polishing transcript…", "polish")

        if self.polisher is None:
            outcome = PolishResult(text, None, None, None, "error")
            warning = outcome.warning()
        elif job.params.get("diarize"):
            # per-speaker polishing is a follow-up; timestamps stay tied to the raw words anyway
            outcome = self.polisher.skipped(text, "unsupported")
            warning = ("Polish is not available with speaker identification yet; "
                       "showing the raw transcript.")
        else:
            box = {}

            def run():
                try:
                    box["result"] = self.polisher.run(text, result.get("language"))
                except Exception as e:  # Polisher.run never raises; belt and braces
                    log.warning("Polish step failed: %s", type(e).__name__)

            thread = threading.Thread(target=run, name="stt-polish", daemon=True)
            thread.start()
            while thread.is_alive():
                if job.cancel_requested.wait(POLISH_CANCEL_POLL):
                    self._finish(job, "cancelled", "Cancelled")
                    thread.join()
                    raise Cancelled("Cancelled")
            outcome = box.get("result")
            if outcome is None:
                outcome = PolishResult(text, None, self.polisher.model, None, "error")
            warning = outcome.warning()

        result.update(outcome.to_fields())
        if warning:
            result.setdefault("warnings", []).append(warning)
        progress(1.0, "Transcript polished" if outcome.applied else "Polish skipped", "polish")

    def _polish_failed(self, result):
        """Last resort when the polish step itself broke: built by hand, no shared code."""
        result.pop("polished_text", None)
        result["polish"] = {"applied": False, "model": getattr(self.polisher, "model", None),
                            "latency_ms": None, "fallback_reason": "error"}
        warnings = result.get("warnings")
        if not isinstance(warnings, list):
            warnings = result["warnings"] = []
        warnings.append("Transcript not polished (error); showing the raw transcript.")
