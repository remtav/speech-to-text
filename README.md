# Speech to Text

Voice transcription powered by **[WhisperX](https://github.com/m-bain/whisperX)** (faster-whisper + wav2vec2 word alignment + pyannote speaker diarization) — runs locally, supports microphone recording and file upload.

## Features

- 🎤 **Microphone capture** — record directly in the browser with audio visualizer
- 📁 **File upload** — drag-and-drop or browse for audio files
- 🎵 **Multi-format support** — WAV, MP3, MP4, FLAC, OGG, M4A, WEBM
- 👥 **Speaker identification** — optional diarization labels who said what (`SPEAKER_00`, `SPEAKER_01`, …)
- ⏱️ **Word-level timestamps** — accurate per-word timing via forced alignment
- 📊 **Model selection** — choose from Tiny, Base, Small, Medium, Large models
- 📋 **Copy results** — one-click copy of transcribed text
- 🌐 **CORS-enabled** — easily integrates with external frontends

## Architecture

```
┌──────────────┐      HTTP/Multipart      ┌─────────────────┐
│   Browser     │ ◄─────────────────────► │   FastAPI App    │
│   (index.html)│    POST /transcribe      │   (app/main.py) │
└──────────────┘                          └────────┬────────┘
                                                    │
                                            ┌───────▼────────┐
                                            │  ffmpeg        │
                                            │  (decode once, │
                                            │  16 kHz mono)  │
                                            └───────┬────────┘
                                                    │
                          ┌─────────────────────────┼─────────────────────────┐
                  ┌───────▼────────┐        ┌───────▼────────┐        ┌───────▼────────┐
                  │ faster-whisper │        │ wav2vec2 align │        │ pyannote       │
                  │ (VAD-batched)  │──────► │ (word times)   │──────► │ diarization    │
                  └────────────────┘        └────────────────┘        │ (optional)     │
                                                                      └────────────────┘
```

**Flow:**
1. Browser sends audio file (via mic capture → `MediaRecorder` or file upload → drag-and-drop)
2. FastAPI receives `multipart/form-data`, saves to temp file
3. `ffmpeg` decodes the file once into a 16 kHz mono waveform shared by every stage
4. WhisperX transcribes with faster-whisper (CTranslate2), batched over VAD speech chunks
5. A language-specific wav2vec2 model aligns the transcript to get word-level timestamps
6. If `diarize=true`, pyannote finds speaker turns; each word gets the speaker that overlaps it most, and segments are split where the speaker changes
7. Results (`text`, `language`, `segments`, plus `speakers` / `text_with_speakers` when diarizing) returned as JSON

The heavy work runs in a worker thread, so `/progress` keeps responding during transcription (stages: `transcribe`, `align`, `diarize`).

## API Endpoints

### POST `/transcribe`

Transcribe an audio file, optionally identifying speakers.

**Request:** `multipart/form-data`

| Field          | Type    | Description                                                        |
|----------------|---------|--------------------------------------------------------------------|
| `file`         | `file`  | Audio file to transcribe                                           |
| `diarize`      | `bool`  | Label speakers (default: `WHISPER_DIARIZE`, i.e. `false`)          |
| `num_speakers` | `int`   | Exact number of speakers, if known (optional)                      |
| `min_speakers` | `int`   | Minimum number of speakers (optional)                              |
| `max_speakers` | `int`   | Maximum number of speakers (optional)                              |

**Response (200), without diarization:**

```json
{
  "text": "Hello, this is a test.",
  "language": "en",
  "aligned": true,
  "segments": [
    {
      "id": 0,
      "start": 0.03,
      "end": 1.92,
      "text": "Hello, this is a test.",
      "words": [
        {"word": "Hello,", "start": 0.03, "end": 0.41, "score": 0.93},
        ...
      ]
    }
  ]
}
```

**Response (200), with `diarize=true`:** segments are split wherever the speaker changes, and each segment and word carries a `speaker`:

```json
{
  "text": "Hello. Hi, how are you?",
  "language": "en",
  "aligned": true,
  "speakers": ["SPEAKER_00", "SPEAKER_01"],
  "segments": [
    {"id": 0, "start": 0.0, "end": 1.2, "text": "Hello.", "speaker": "SPEAKER_00", "words": [...]},
    {"id": 1, "start": 1.4, "end": 3.0, "text": "Hi, how are you?", "speaker": "SPEAKER_01", "words": [...]}
  ],
  "text_with_speakers": "SPEAKER_00: Hello.\nSPEAKER_01: Hi, how are you?"
}
```

`aligned` is `false` when no wav2vec2 alignment model exists for the detected language; segments then have no `words`, and speakers are assigned per segment.

**Errors:** `400` for undecodable audio or invalid speaker hints, `503` when diarization is requested but `HF_TOKEN` is missing or the pyannote model can't be loaded.

**Example with curl:**

```bash
# From file
curl -F "file=@recording.wav" http://localhost:8081/transcribe

# With speaker identification (2 to 4 speakers)
curl -F "file=@meeting.mp3" -F diarize=true -F min_speakers=2 -F max_speakers=4 \
  http://localhost:8081/transcribe

# From microphone (webm)
curl -F "file=@recording.webm" http://localhost:8081/transcribe
```

### POST `/transcribe/streaming`

Fast transcription with language detection, skipping alignment and diarization. Returns `text`, `language` and `task`.

## Speaker Diarization Setup

Diarization uses the gated [`pyannote/speaker-diarization-community-1`](https://huggingface.co/pyannote/speaker-diarization-community-1) pipeline:

1. Create a Hugging Face access token (read access) at <https://huggingface.co/settings/tokens>
2. Accept the user conditions on the [model page](https://huggingface.co/pyannote/speaker-diarization-community-1)
3. Set `HF_TOKEN` in the server environment and restart

Without `HF_TOKEN`, transcription works as usual and the "Identify speakers" toggle in the UI is disabled. The diarization pipeline is loaded on first use and freed by `/eject`.

## Web UI

```
http://localhost:8081
```

The web interface provides:
- **Microphone mode** — click "Record" to capture audio, with a live frequency visualizer
- **Upload mode** — drag and drop audio files or click to browse
- **Model selector** — pick the desired Whisper model before transcribing
- **Identify speakers** — toggle diarization, optionally with a min/max speaker count; the transcript is grouped by color-coded speaker and "Copy" includes the speaker labels

## Configuration

Set environment variables in `.env` or at startup:

| Variable            | Default                                         | Description                    |
|---------------------|-------------------------------------------------|--------------------------------|
| `WHISPER_PORT`      | `8081`                                          | HTTP server port               |
| `WHISPER_MODEL`     | `base`                                          | Whisper model name             |
| `WHISPER_DEVICE`    | `cpu`                                           | Inference device (`cpu`/`cuda`)|
| `WHISPER_COMPUTE_TYPE` | `float16` on GPU, `int8` on CPU              | CTranslate2 compute type (`float16`, `int8_float16`, `int8`, `float32`) |
| `WHISPER_BATCH_SIZE`| `16`                                            | Batch size for VAD chunks (lower it if GPU runs out of memory) |
| `HF_TOKEN`          | unset                                           | Hugging Face token, required for speaker diarization |
| `WHISPER_DIARIZE`   | `false`                                         | Diarize by default when the request doesn't say |
| `WHISPER_DIARIZE_MODEL` | `pyannote/speaker-diarization-community-1`  | pyannote pipeline used for diarization |
| `FFMPEG_PATH`       | auto-detected                                   | Path to ffmpeg binary          |

### Available Models

Whisper models run through faster-whisper (CTranslate2), which is faster and lighter than the original implementation. Weights are downloaded from the Hugging Face Hub on first use.

| Model    | Params  | Speed   | Accuracy |
|----------|---------|---------|----------|
| `tiny`   | 39M     | Fastest | Lower    |
| `base`   | 74M     | Fast    | Good     |
| `small`  | 244M    | Moderate| Very Good|
| `medium` | 769M    | Slow    | Excellent|
| `large-v3-turbo` | 809M | Fast | Near large-v3 |
| `large-v2`| 1550M  | Slowest | Best     |
| `large` / `large-v3`| 1550M  | Slowest | Best     |

Word alignment additionally downloads a ~360 MB–1.2 GB wav2vec2 model per language (first use), and diarization a ~30 MB pyannote pipeline.

## GPU Inference

### Prerequisites

- NVIDIA GPU with **≥8 GB VRAM** for `large` / `large-v3` models
- NVIDIA driver installed and working
- `torch` with CUDA support (already included in `requirements.txt`)

### Using GPU for Larger Models

1. **Set `WHISPER_DEVICE=cuda`** in `.env`:

```bash
# .env
WHISPER_DEVICE=cuda
WHISPER_MODEL=large
```

2. **Stop any other GPU-consuming processes** (e.g., ollama, other ML models). Verify free VRAM:

```bash
nvidia-smi
```

Ensure sufficient free memory (≥8 GB for `large`, ≥12 GB for `large-v3` recommended).

3. **Restart the server:**

```bash
source venv/bin/activate
. .env
python -m uvicorn app.main:app --host 0.0.0.0 --port $WHISPER_PORT
```

4. **Verify CUDA is active** by checking server logs at startup:

```
Loading Whisper model: large on cuda...
```

5. **Download model weights** (first run will auto-download to `~/.cache/whisper/`):

```bash
# Download large model explicitly
python -c "from faster_whisper.utils import download_model; import os; download_model('large-v3', cache_dir=os.path.expanduser('~/.cache/whisper'))"
```

### Docker GPU Support

To use GPU inside Docker:

1. Install **NVIDIA Container Toolkit**:

```bash
# Debian/Ubuntu
curl -fsSL https://nvidia.github.io/libnvidia-container/gpgkey | sudo gpg --dearmor -o /usr/share/keyrings/nvidia-container-toolkit-keyring.gpg
curl -s -L https://nvidia.github.io/libnvidia-container/stable/deb/nvidia-container-toolkit.list | sed 's#deb https://#deb [signed-by=/usr/share/keyrings/nvidia-container-toolkit-keyring.gpg] https://#g' | sudo tee /usr/share/keyrings/nvidia-container-toolkit.list > /dev/null
sudo apt-get update
sudo apt-get install -y nvidia-container-toolkit
sudo nvidia-ctk runtime configure --runtime=docker
sudo systemctl restart docker
```

2. Update `docker-compose.yml` — the `deploy.resources.reservations.devices` section is already configured for GPU passthrough.

3. Build and run:

```bash
docker compose up --build
```

#### One-shot GPU container

If you don't want to use `docker compose` or have a custom image/tag, you can start the container directly with GPU support:

```bash
docker run -d --name whisper --gpus all -p 8081:8081 \
  -e WHISPER_MODEL=base \
  -e WHISPER_DEVICE=cuda \
  -e WHISPER_PORT=8081 \
  -e FFMPEG_PATH=/usr/bin/ffmpeg \
  -v whisper-model-cache:/root/.cache \
  --restart unless-stopped \
  whisper:latest
```

## Running

### Virtual Environment

```bash
cd /home/remi/speech-to-text
python -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

### Start the Server (CPU)

```bash
# Using .env config
. .env
python -m uvicorn app.main:app --host 0.0.0.0 --port $WHISPER_PORT
```

### Start the Server (GPU)

```bash
# Set device to cuda in .env
. .env
# Verify CUDA is available
python -c "import torch; print('CUDA:', torch.cuda.is_available())"
python -m uvicorn app.main:app --host 0.0.0.0 --port $WHISPER_PORT
```

### Docker

```bash
docker compose up --build
```

## File Structure

```
speech-to-text/
├── app/
│   ├── main.py          # FastAPI application + WhisperX pipeline
│   └── speakers.py      # Speaker post-processing (split segments, speaker transcript)
├── templates/
│   └── index.html       # Web UI
├── requirements.txt     # Python dependencies
├── .env                 # Environment configuration
├── docker-compose.yml   # Docker configuration
└── Dockerfile           # Docker build file
```

## Dependencies

| Package          | Version | Purpose                     |
|------------------|---------|-----------------------------|
| `fastapi`        | 0.115.6 | Web framework               |
| `uvicorn`        | 0.34.0  | ASGI server                 |
| `whisperx`       | 3.8.6   | Speech-to-text engine (faster-whisper, alignment, pyannote diarization) |
| `torch`          | ~2.8.0  | Deep learning framework (pinned by WhisperX) |
| `ffmpeg-python`  | 0.2.0   | Audio format conversion     |
| `edge-tts`       | 7.0.0   | Text-to-speech (testing)    |

## Performance Notes

- **CPU inference** works for all models but is slow for `large` / `large-v3`
- **GPU inference** with CUDA is highly recommended for `medium` and larger models
- **Compute type** defaults to `float16` on GPU and `int8` on CPU; override with `WHISPER_COMPUTE_TYPE`
- **Diarization** adds noticeable time on CPU for long recordings; GPU is recommended
- Whisper weights are cached in `~/.cache/whisper/`, alignment and pyannote models in `~/.cache/huggingface/` — no re-download needed between restarts
- **Known limitations:** overlapping speech is attributed to a single speaker per word; words at a speaker boundary can occasionally be mislabelled

## Testing

Run the test suite with pytest:

```bash
source venv/bin/activate
pytest tests/ -v
```

The test suite covers app initialization, health/progress endpoints, model switching, audio decoding, transcription and speaker diarization (mocked), speaker post-processing, error handling, and more. Tests use `fastapi.testclient` and mock WhisperX so the transcription tests run without a model download.

## License

MIT
