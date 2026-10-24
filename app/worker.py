"""Backends that run the transcription ``Engine``.

``ProcessBackend`` (default) runs the engine in a separate worker process.
Native libraries used by WhisperX (CTranslate2, CUDA, cuDNN) can abort the
whole process on errors such as a missing cuDNN library or a GPU out of
memory. In the worker, such a crash no longer takes the web server down —
which the browser reported as "Failed to fetch": the job fails with a clear
message and the worker restarts on the next request. Stopping the worker
also gives back all the GPU memory, CUDA context included.

``InlineBackend`` runs the engine in the calling thread (tests, debugging).
"""
import importlib
import logging
import multiprocessing as mp
import os
import queue
import signal
import threading
import time
import uuid

from app.errors import Cancelled, PipelineError, WorkerCrashed, error_from_code

log = logging.getLogger(__name__)

EMPTY_STATE = {"model": None, "device": None, "model_loaded": False, "diarization_loaded": False}


def _import(path):
    module, _, attr = path.partition(":")
    return getattr(importlib.import_module(module), attr)


def _dispatch(engine, op, args, progress):
    if op == "load":
        return engine.load(args["model"], args["device"], progress)
    if op == "transcribe":
        return engine.transcribe(args["audio_path"], args.get("options"), progress)
    if op == "eject":
        return engine.eject()
    if op == "state":
        return engine.state()
    raise ValueError(f"Unknown operation {op!r}")


class InlineBackend:
    """Run the engine in the calling thread."""

    def __init__(self, engine):
        self.engine = engine
        self._cancel = threading.Event()

    @property
    def last_state(self):
        return self.engine.state()

    def call(self, op, args, progress):
        self._cancel.clear()

        def checked(fraction, message, stage):
            if self._cancel.is_set():
                raise Cancelled("Cancelled")
            progress(fraction, message, stage)

        return _dispatch(self.engine, op, args, checked)

    def cancel(self):
        self._cancel.set()

    def close(self):
        pass


def _describe_exit(code):
    if code is None:
        return "no exit code"
    if code < 0:
        try:
            name = signal.Signals(-code).name
        except ValueError:
            name = f"signal {-code}"
        detail = {
            "SIGKILL": "killed — often the system running out of RAM",
            "SIGSEGV": "segmentation fault",
            "SIGABRT": "aborted by a native library",
            "SIGBUS": "bus error",
        }.get(name)
        return f"{name}, {detail}" if detail else name
    return f"exit code {code}"


class ProcessBackend:
    """Run the engine in a worker process, restarted automatically after a crash."""

    def __init__(self, engine_factory="app.pipeline:Engine", engine_kwargs=None, cancel_grace=10.0):
        self.engine_factory = engine_factory
        self.engine_kwargs = engine_kwargs or {}
        self.cancel_grace = cancel_grace
        self.last_state = dict(EMPTY_STATE)
        self._ctx = mp.get_context("spawn")  # never fork a process that may hold CUDA state
        self._proc = None
        self._conn = None
        self._send_lock = threading.Lock()
        self._current_id = None
        self._cancel_deadline = None

    @property
    def alive(self):
        return self._proc is not None and self._proc.is_alive()

    @property
    def pid(self):
        return self._proc.pid if self.alive else None

    def _start(self):
        parent_conn, child_conn = self._ctx.Pipe()
        proc = self._ctx.Process(target=worker_main, name="stt-worker", daemon=True,
                                 args=(child_conn, self.engine_factory, self.engine_kwargs))
        proc.start()
        child_conn.close()
        self._proc, self._conn = proc, parent_conn
        self.last_state = dict(EMPTY_STATE)
        log.info("Worker process started (pid %s)", proc.pid)

    def _send(self, message):
        with self._send_lock:
            self._conn.send(message)

    def _cleanup(self):
        if self._conn is not None:
            try:
                self._conn.close()
            except OSError:
                pass
        self._proc = self._conn = None
        self.last_state = dict(self.last_state, model_loaded=False, diarization_loaded=False)

    def _kill(self):
        proc = self._proc
        if proc is not None and proc.is_alive():
            proc.terminate()
            proc.join(5)
            if proc.is_alive():
                proc.kill()
                proc.join(5)
        self._cleanup()

    def _crashed(self, during):
        proc = self._proc
        proc.join(5)
        description = _describe_exit(proc.exitcode)
        log.error("Worker process %s died (%s) during: %s", proc.pid, description, during)
        self._cleanup()
        return WorkerCrashed(
            f"The transcription worker stopped unexpectedly ({description}) during: {during}. "
            "This usually comes from a native library: GPU out of memory, missing or incompatible "
            "CUDA/cuDNN libraries, or the system running out of RAM. Details are in the server logs. "
            "The worker restarts automatically on the next request."
        )

    def call(self, op, args, progress):
        if op == "eject":
            return self._eject()
        if not self.alive:
            self._start()

        request_id = uuid.uuid4().hex
        self._current_id, self._cancel_deadline = request_id, None
        during = op
        try:
            try:
                self._send({"op": op, "id": request_id, "args": args})
            except OSError:
                raise self._crashed(during)
            while True:
                if self._cancel_deadline is not None and time.monotonic() > self._cancel_deadline:
                    log.warning("Worker did not stop after cancel, restarting it")
                    self._kill()
                    raise Cancelled("Cancelled (the worker was restarted)")
                try:
                    if not self._conn.poll(0.25):
                        if not self._proc.is_alive():
                            raise self._crashed(during)
                        continue
                    message = self._conn.recv()
                except (EOFError, OSError):
                    raise self._crashed(during)

                if message.get("id") not in (request_id, None):
                    continue  # late message from a cancelled request
                kind = message["type"]
                if kind == "progress":
                    during = message["message"]
                    progress(message["progress"], message["message"], message["stage"])
                elif kind == "result":
                    self.last_state = message["state"]
                    return message["value"]
                elif kind == "error":
                    self.last_state = message["state"]
                    raise error_from_code(message["code"], message["message"])
                elif kind == "ready":
                    self.last_state = message["state"]
        finally:
            self._current_id = self._cancel_deadline = None

    def _eject(self):
        """Stop the worker: frees all its memory, CUDA context included."""
        had = bool(self.last_state.get("model_loaded") or self.last_state.get("diarization_loaded"))
        self.close()
        return had

    def cancel(self):
        if self._current_id is None or not self.alive:
            return
        try:
            self._send({"op": "cancel", "id": self._current_id})
        except OSError:
            pass
        self._cancel_deadline = time.monotonic() + self.cancel_grace

    def close(self):
        if self.alive:
            try:
                self._send({"op": "shutdown"})
            except OSError:
                pass
            self._proc.join(10)
        self._kill()


# ---------------------------------------------------------------------------
# Worker process side
# ---------------------------------------------------------------------------

def worker_main(conn, engine_factory, engine_kwargs):
    logging.basicConfig(level=os.getenv("WHISPER_LOG_LEVEL", "INFO").upper(),
                        format="%(asctime)s %(levelname)s [worker] %(name)s: %(message)s")
    # Ctrl+C reaches the whole process group: let the server shut the worker down cleanly
    signal.signal(signal.SIGINT, signal.SIG_IGN)

    send_lock = threading.Lock()
    requests = queue.Queue()
    cancelled = set()

    def send(message):
        with send_lock:
            conn.send(message)

    def listen():
        while True:
            try:
                message = conn.recv()
            except (EOFError, OSError):
                os._exit(0)  # the server is gone: don't keep holding the GPU
            if message.get("op") == "cancel":
                cancelled.add(message["id"])
            else:
                requests.put(message)

    engine = _import(engine_factory)(**engine_kwargs)
    threading.Thread(target=listen, daemon=True, name="stt-worker-listener").start()
    send({"type": "ready", "id": None, "state": engine.state()})

    while True:
        message = requests.get()
        if message["op"] == "shutdown":
            break
        request_id = message["id"]

        def progress(fraction, text, stage, request_id=request_id):
            if request_id in cancelled:
                raise Cancelled("Cancelled")
            send({"type": "progress", "id": request_id, "progress": fraction, "message": text, "stage": stage})

        try:
            value = _dispatch(engine, message["op"], message.get("args") or {}, progress)
            send({"type": "result", "id": request_id, "value": value, "state": engine.state()})
        except PipelineError as e:
            send({"type": "error", "id": request_id, "code": e.code, "message": str(e), "state": engine.state()})
        except Exception as e:
            log.exception("Worker operation %s failed", message["op"])
            send({"type": "error", "id": request_id, "code": "internal",
                  "message": f"{type(e).__name__}: {e}", "state": engine.state()})
        finally:
            cancelled.discard(request_id)
    engine.eject()
