"""JobManager.decode_stream: priority passes (issue #134)."""
import threading
import time

from app.jobs import JobManager


def test_decode_stream_runs_before_queued_jobs():
    """A pass goes before the jobs waiting, never before the running one, and is not a job."""
    order, release = [], threading.Event()

    class Backend:
        last_state = {}

        def call(self, op, args, progress):
            order.append(op)
            if len(order) == 1:
                release.wait(5)
            return {"words": []} if op == "stream_decode" else True

        def cancel(self):
            pass

        def close(self):
            pass

    manager = JobManager(Backend(), "base", "cpu")
    manager.start()
    try:
        manager.submit("eject")
        deadline = time.time() + 5
        while not order and time.time() < deadline:
            time.sleep(0.01)
        manager.submit("eject")
        future = manager.decode_stream([], {})
        release.set()
        assert future.result(5) == {"words": []}
        deadline = time.time() + 5
        while len(order) < 3 and time.time() < deadline:
            time.sleep(0.01)
        assert order == ["eject", "stream_decode", "eject"]
        assert [j.kind for j in manager.list()] == ["eject", "eject"]
    finally:
        manager.stop()
