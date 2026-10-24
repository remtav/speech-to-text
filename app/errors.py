"""Errors raised by the transcription pipeline.

Each error has a stable ``code`` so it can cross the worker-process boundary
and be mapped to an HTTP status by the API.
"""


class PipelineError(Exception):
    code = "internal"
    http_status = 500


class AudioDecodeError(PipelineError, ValueError):
    """The uploaded file could not be decoded as audio."""
    code = "bad_audio"
    http_status = 400


class FfmpegMissing(PipelineError):
    code = "ffmpeg_missing"
    http_status = 500


class DiarizationUnavailable(PipelineError):
    """Speaker diarization requested but the pipeline cannot be used."""
    code = "diarization_unavailable"
    http_status = 503


class ModelLoadError(PipelineError):
    code = "model_load_failed"
    http_status = 500


class WorkerCrashed(PipelineError):
    """The worker process died (native crash, out of memory, killed…)."""
    code = "worker_crashed"
    http_status = 500


class Cancelled(PipelineError):
    code = "cancelled"
    http_status = 409


_BY_CODE = {cls.code: cls for cls in (PipelineError, AudioDecodeError, FfmpegMissing, DiarizationUnavailable,
                                      ModelLoadError, WorkerCrashed, Cancelled)}


def error_from_code(code, message):
    return _BY_CODE.get(code, PipelineError)(message)


def http_status_for(code):
    return _BY_CODE.get(code, PipelineError).http_status
