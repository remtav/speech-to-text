"""Audio decoding with ffmpeg."""
import subprocess

import numpy as np

from app.errors import AudioDecodeError, FfmpegMissing

SAMPLE_RATE = 16000


def load_audio(path, ffmpeg_path=None, timeout=1800):
    """Decode any ffmpeg-supported file to a 16 kHz mono float32 waveform.

    The same array is shared by transcription, alignment and diarization,
    so the file is only decoded once.
    """
    cmd = [
        ffmpeg_path or "ffmpeg", "-nostdin", "-threads", "0", "-i", str(path),
        "-f", "s16le", "-ac", "1", "-acodec", "pcm_s16le", "-ar", str(SAMPLE_RATE), "-",
    ]
    try:
        result = subprocess.run(cmd, capture_output=True, check=True, timeout=timeout)
    except FileNotFoundError as e:
        raise FfmpegMissing("ffmpeg not found: install it or set FFMPEG_PATH") from e
    except subprocess.TimeoutExpired as e:
        raise AudioDecodeError(f"Could not decode audio: ffmpeg took longer than {timeout} s") from e
    except subprocess.CalledProcessError as e:
        stderr = (e.stderr or b"").decode(errors="replace").strip().splitlines()
        raise AudioDecodeError(f"Could not decode audio: {stderr[-1] if stderr else e}") from e

    audio = np.frombuffer(result.stdout, np.int16).flatten().astype(np.float32) / 32768.0
    if audio.size == 0:
        raise AudioDecodeError("Could not decode audio: file contains no audio samples")
    return audio
