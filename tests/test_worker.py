"""Worker process tests: a real child process running the fake engine."""
import threading
import time

import pytest

from app.errors import AudioDecodeError, Cancelled, PipelineError, WorkerCrashed
from app.worker import ProcessBackend, _describe_exit


@pytest.fixture
def backend():
    b = ProcessBackend("tests.fake_engine:FakeEngine", cancel_grace=1.0)
    yield b
    b.close()


@pytest.fixture
def audio(tmp_path):
    def make(content):
        path = tmp_path / f"{content}.wav"
        path.write_text(content)
        return str(path)
    return make


def _transcribe(backend, path, events=None, **options):
    progress = (lambda f, m, s: events.append((f, m, s))) if events is not None else (lambda *a: None)
    return backend.call("transcribe", {"audio_path": path, "options": {"model": "tiny", "device": "cpu", **options}},
                        progress)


def test_transcribe_in_worker(backend, audio):
    events = []
    result = _transcribe(backend, audio("hello world"), events, language="fr")
    assert result["text"] == "hello world"
    assert result["language"] == "fr"
    assert ("load" in [s for _, _, s in events]) and events[-1][1] == "Almost done"
    assert backend.last_state["model_loaded"] is True
    assert backend.last_state["model"] == "tiny"


def test_load_and_eject(backend):
    backend.call("load", {"model": "small", "device": "cpu"}, lambda *a: None)
    assert backend.alive and backend.last_state["model"] == "small"
    assert backend.call("eject", {}, lambda *a: None) is True
    assert not backend.alive  # stopping the worker frees all of its memory
    assert backend.call("eject", {}, lambda *a: None) is False


def test_errors_cross_the_process_boundary(backend, audio):
    with pytest.raises(AudioDecodeError, match="Invalid data"):
        _transcribe(backend, audio("bad"))
    with pytest.raises(PipelineError, match="KeyError") as exc:
        _transcribe(backend, audio("boom"))
    assert exc.value.code == "internal"
    assert _transcribe(backend, audio("still alive"))["text"] == "still alive"


def test_crash_is_reported_and_worker_restarts(backend, audio):
    _transcribe(backend, audio("warm up"))
    first_pid = backend.pid
    with pytest.raises(WorkerCrashed, match="SIGABRT") as exc:
        _transcribe(backend, audio("crash"))
    assert "restarts automatically" in str(exc.value)
    assert backend.last_state["model_loaded"] is False

    assert _transcribe(backend, audio("after crash"))["text"] == "after crash"
    assert backend.pid != first_pid


def test_soft_cancel_keeps_worker(backend, audio):
    _transcribe(backend, audio("warm up"))
    pid = backend.pid
    threading.Timer(0.5, backend.cancel).start()
    with pytest.raises(Cancelled):
        _transcribe(backend, audio("slow"))
    assert backend.pid == pid
    assert _transcribe(backend, audio("next"))["text"] == "next"


def test_hard_cancel_restarts_unresponsive_worker(backend, audio):
    _transcribe(backend, audio("warm up"))
    threading.Timer(0.5, backend.cancel).start()
    started = time.monotonic()
    with pytest.raises(Cancelled, match="restarted"):
        _transcribe(backend, audio("hang"))
    assert time.monotonic() - started < 10
    assert _transcribe(backend, audio("next"))["text"] == "next"


def test_cancel_when_idle_is_harmless(backend, audio):
    backend.cancel()
    assert _transcribe(backend, audio("ok"))["text"] == "ok"


def test_close_is_idempotent(backend, audio):
    _transcribe(backend, audio("ok"))
    backend.close()
    backend.close()
    assert not backend.alive


@pytest.mark.parametrize("code, expected", [
    (-6, "SIGABRT"), (-9, "out of RAM"), (-11, "segmentation fault"), (1, "exit code 1"), (None, "no exit code"),
])
def test_describe_exit(code, expected):
    assert expected in _describe_exit(code)
