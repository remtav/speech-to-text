"""A stand-in for ``app.polish.PolishClient``: no HTTP, no model.

``reply`` decides what ``complete`` does: a string is returned as the model's
output, a callable is called with the raw transcript, an exception instance
is raised. ``delay`` sleeps first (for timeout and cancel tests).
"""
import threading
import time

from app.polish import TRANSCRIPT_CLOSE, TRANSCRIPT_OPEN


class FakePolishClient:
    def __init__(self, reply=None, delay=0.0, available=True, loaded=True, model="fake-polish:7b"):
        self.reply = reply
        self.delay = delay
        self.model = model
        self.is_available = available
        self.loaded = loaded
        self.calls = []  # raw transcripts sent to complete()
        self.messages = None  # the last chat messages
        self.probes = 0
        self.unloads = 0
        self.started = threading.Event()

    @staticmethod
    def transcript(messages):
        content = messages[-1]["content"]
        return content.split(TRANSCRIPT_OPEN + "\n", 1)[1].rsplit("\n" + TRANSCRIPT_CLOSE, 1)[0]

    def complete(self, messages, max_tokens=None):
        raw = self.transcript(messages)
        self.messages = messages
        self.calls.append(raw)
        self.started.set()
        if self.delay:
            time.sleep(self.delay)
        if isinstance(self.reply, BaseException):
            raise self.reply
        if callable(self.reply):
            return self.reply(raw)
        return raw if self.reply is None else self.reply

    def available(self):
        self.probes += 1
        if isinstance(self.is_available, BaseException):
            raise self.is_available
        return self.is_available

    def unload(self):
        self.unloads += 1
        had, self.loaded = self.loaded, False
        return had
