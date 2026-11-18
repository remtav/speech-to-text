"""Kyutai STT on the server (issue #124), through Hugging Face Transformers.

Kyutai publishes the weights of ``kyutai/stt-1b-en_fr`` for Transformers as
``kyutai/stt-1b-en_fr-trfs`` (same weights, ``transformers >= 4.53``): the model
runs in the worker process next to WhisperX, with no other runtime to install.

The model reads 24 kHz audio (the Mimi codec), the pipeline decodes 16 kHz: each
piece is resampled here. Long files are cut
into pieces of at most ``MAX_CHUNK_SECONDS``, each cut placed in the quietest
moment near the limit, never in the middle of a loud word if a pause is near.
Its generation config caps a pass at 375 frames (30 s at 12.5 Hz), padding
included: past that, words were lost in a test on 76 s of speech.

Kyutai does not detect the language and gives no word timestamps: the language
comes from the request, the timestamps from the wav2vec2 alignment (see
``app.pipeline``). It takes no prompt either.
"""
import logging

import numpy as np

log = logging.getLogger(__name__)

# Model name in the API -> Transformers repository on Hugging Face
KYUTAI_MODELS = {"kyutai-stt-1b-en_fr": "kyutai/stt-1b-en_fr-trfs"}
# Languages the model was trained on
KYUTAI_LANGUAGES = {"kyutai-stt-1b-en_fr": ("fr", "en")}

INPUT_RATE = 16000  # what app.audio.load_audio gives
MODEL_RATE = 24000  # Mimi
# 30 s per pass (max_length 375 frames at 12.5 Hz), minus the 1.5 s of silence the feature extractor appends
MAX_CHUNK_SECONDS = 25.0
SEARCH_SECONDS = 8.0  # where to look for a pause, before the limit
QUIET_WINDOW_SECONDS = 0.1


def is_kyutai(name):
    return name in KYUTAI_MODELS


def language_for(name, language=None, candidates=None):
    """The language of a Kyutai transcription: the forced one, else the first of the user's languages the model
    knows, else "" (unknown: alignment is skipped). Kyutai has no language detection."""
    known = KYUTAI_LANGUAGES.get(name, ())
    if language:
        return language
    for code in candidates or ():
        if code in known:
            return code
    return ""


def chunk_bounds(audio, rate=INPUT_RATE, max_seconds=MAX_CHUNK_SECONDS, search_seconds=SEARCH_SECONDS,
                 window_seconds=QUIET_WINDOW_SECONDS):
    """[(start, end)] sample ranges covering ``audio``, each at most ``max_seconds`` long.

    Every cut but the last falls in the quietest ``window_seconds`` (lowest energy) of the ``search_seconds``
    before the limit, so a piece ends in a pause when there is one.
    """
    n = len(audio)
    limit = int(max_seconds * rate)
    if n <= limit:
        return [(0, n)] if n else []
    window = max(int(window_seconds * rate), 1)
    search = min(int(search_seconds * rate), limit - window)
    bounds = []
    start = 0
    while n - start > limit:
        lo, hi = start + limit - search, start + limit
        region = np.asarray(audio[lo:hi], dtype=np.float32)
        usable = len(region) // window * window
        energy = (region[:usable].reshape(-1, window) ** 2).mean(axis=1)
        cut = lo + int(np.argmin(energy)) * window + window // 2
        bounds.append((start, cut))
        start = cut
    bounds.append((start, n))
    return bounds


def resample(audio, src=INPUT_RATE, dst=MODEL_RATE):
    import torch
    import torchaudio.functional as F
    return F.resample(torch.from_numpy(np.ascontiguousarray(audio, dtype=np.float32)), src, dst).numpy()


class KyutaiModel:
    """A loaded Kyutai STT model. ``transcribe`` takes 16 kHz audio and returns segments, one per piece."""

    def __init__(self, name, path, device):
        import torch
        from transformers import KyutaiSpeechToTextForConditionalGeneration, KyutaiSpeechToTextProcessor

        self.name = name
        self.device = device
        # bf16 as published on the GPU; float32 on the CPU, where bf16 is slow or unsupported.
        dtype = torch.bfloat16 if device == "cuda" else torch.float32
        self.processor = KyutaiSpeechToTextProcessor.from_pretrained(path)
        self.model = KyutaiSpeechToTextForConditionalGeneration.from_pretrained(path, torch_dtype=dtype).to(device)
        self.model.eval()

    def _decode(self, piece):
        import torch
        # The feature extractor appends the silence the model needs to let out its last words (its delay + 1 s).
        inputs = self.processor(audio=resample(piece), sampling_rate=MODEL_RATE, return_tensors="pt").to(self.device)
        with torch.inference_mode():
            tokens = self.model.generate(**inputs)
        return self.processor.batch_decode(tokens, skip_special_tokens=True)[0].strip()

    def transcribe(self, audio, progress_callback=None):
        bounds = chunk_bounds(audio)
        segments = []
        for i, (start, end) in enumerate(bounds):
            text = self._decode(audio[start:end])
            if text:
                segments.append({"start": round(start / INPUT_RATE, 3), "end": round(end / INPUT_RATE, 3),
                                 "text": " " + text})
            if progress_callback:
                progress_callback(100.0 * (i + 1) / len(bounds))
        return {"segments": segments}
