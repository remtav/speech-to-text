"""
Pure state of a streaming dictation session (no network, no model).

Policy "local agreement between two passes" (LocalAgreement-2, as in
whisper_streaming):

- audio accumulates in a buffer starting at ``buffer_start`` (seconds since
  the session began);
- each pass decodes the whole buffer and returns words with times relative
  to the start of the decoded window;
- only words ending more than 0.1 s after the last confirmed word are kept;
- the longest common prefix of those words and of the previous pass's
  provisional tail (normalized: lowercase, no accents, letters and digits
  only) becomes confirmed; the rest becomes the new provisional tail;
- on the final pass, everything is confirmed;
- when the buffer exceeds ``trim_after`` seconds and the last confirmed word
  ends more than 1 s after ``buffer_start``, the buffer restarts at the end
  of that word (earlier samples are dropped);
- the prompt given to Whisper is the end of the confirmed text, preceded by
  the session's initial prompt: at most 200 characters, cut on a word.

This module handles user text: it never prints or logs.
"""
import re
import unicodedata
from dataclasses import dataclass

import numpy as np


@dataclass(frozen=True)
class Word:
    text: str
    start: float
    end: float


def normalize(word: str) -> str:
    folded = unicodedata.normalize("NFKD", word.lower())
    ascii_only = folded.encode("ascii", "ignore").decode("ascii")
    return re.sub(r"[^a-z0-9]", "", ascii_only)


class StreamState:
    def __init__(self, sample_rate=16000, trim_after=15.0, prompt_chars=200, initial_prompt=""):
        self.sample_rate = sample_rate
        self.trim_after = trim_after
        self.prompt_chars = prompt_chars
        self.initial_prompt = initial_prompt
        self._buf = np.zeros(0, np.float32)
        self._buffer_start = 0.0
        self._total = 0
        self._committed: list[Word] = []
        self._tail: list[Word] = []

    def add_audio(self, samples) -> None:
        arr = np.asarray(samples, dtype=np.float32)
        self._buf = np.concatenate([self._buf, arr])
        self._total += len(arr)

    @property
    def audio_seconds(self) -> float:
        return self._total / self.sample_rate

    @property
    def buffered_seconds(self) -> float:
        return len(self._buf) / self.sample_rate

    @property
    def committed_text(self) -> str:
        return " ".join(w.text for w in self._committed)

    @property
    def provisional_text(self) -> str:
        return " ".join(w.text for w in self._tail)

    def window(self) -> tuple[np.ndarray, float, str]:
        # Copy on purpose: a pass may run while audio keeps arriving.
        return self._buf.copy(), self._buffer_start, self.prompt()

    def prompt(self) -> str:
        text = re.sub(r"\s+", " ", (self.initial_prompt + " " + self.committed_text)).strip()
        if len(text) <= self.prompt_chars:
            return text
        cut = text[len(text) - self.prompt_chars:]
        space = cut.find(" ")
        if 0 <= space < len(cut) - 1:
            return cut[space + 1:]
        return cut

    def apply(self, words: list[Word], offset: float, final: bool = False) -> bool:
        before = (self.committed_text, self.provisional_text)
        absolute = []
        for w in words:
            text = w.text.strip()
            if text:
                absolute.append(Word(text, offset + w.start, offset + w.end))
        last_end = self._committed[-1].end if self._committed else 0.0
        hyp = [w for w in absolute if w.end > last_end + 0.1]

        if final:
            k = len(hyp)
        else:
            k = 0
            while (
                k < len(hyp)
                and k < len(self._tail)
                and normalize(hyp[k].text) == normalize(self._tail[k].text)
            ):
                k += 1
        self._committed += hyp[:k]
        self._tail = hyp[k:]

        if (
            self.buffered_seconds > self.trim_after
            and self._committed
            and self._committed[-1].end > self._buffer_start + 1
        ):
            new_start = self._committed[-1].end
            cut = int((new_start - self._buffer_start) * self.sample_rate)
            self._buf = self._buf[cut:]
            self._buffer_start = new_start
            self._tail = [w for w in self._tail if w.end > new_start]

        return before != (self.committed_text, self.provisional_text)
