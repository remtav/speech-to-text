# Speech to Text

Voice transcription powered by **[WhisperX](https://github.com/m-bain/whisperX)** (faster-whisper + wav2vec2 word alignment + pyannote speaker diarization) — runs locally, supports microphone recording and file upload.

## Features

- 🎤 **Microphone capture** — record directly in the browser with audio visualizer
- 📁 **File upload** — drag-and-drop or browse for audio files
- 🎵 **Multi-format support** — WAV, MP3, MP4, FLAC, OGG, M4A, WEBM
- 👥 **Speaker identification** — optional diarization labels who said what (`SPEAKER_00`, `SPEAKER_01`, …)
- ⏱️ **Word-level timestamps** — accurate per-word timing via forced alignment
- 📊 **Model selection** — choose from Tiny, Base, Small, Medium, Large models
- 🌐 **Language choice** — auto-detect or force one of Whisper's ~100 languages
- 📋 **Copy & export** — one-click copy, download as TXT, SRT, WebVTT or JSON
- 🔑 **Hugging Face token from the UI** — add, test or remove the token needed for speaker identification
- 🛡️ **Crash-proof worker** — models run in a separate process: a GPU/driver crash fails the job with a clear message instead of killing the server
- ⏳ **Background jobs** — progress per job, cancel button, survives network hiccups and page reloads
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
1. Browser sends audio file (via mic capture → `MediaRecorder` or file upload → drag-and-drop) to `POST /jobs`
2. FastAPI streams the upload to a temp file and queues a job; the browser polls `GET /jobs/{id}`
3. A dispatcher thread runs jobs one at a time (transcriptions, model switches, ejects), in order
4. The job runs in the **worker process**: `ffmpeg` decodes the file once into a 16 kHz mono waveform shared by every stage
5. WhisperX transcribes with faster-whisper (CTranslate2), batched over VAD speech chunks
6. A language-specific wav2vec2 model aligns the transcript to get word-level timestamps (if this fails, the transcript is still returned, with a warning)
7. If `diarize=true`, pyannote finds speaker turns; each word gets the speaker that overlaps it most, and segments are split where the speaker changes
8. Results (`text`, `language`, `segments`, plus `speakers` / `text_with_speakers` when diarizing) are stored on the job

### Robustness

- **Worker process.** WhisperX relies on native libraries (CTranslate2, CUDA, cuDNN) that can abort the whole
  process — a missing cuDNN library, a GPU out of memory. They run in a separate process: if it dies, the job
  fails with an explanation (signal, likely cause) and the worker restarts on the next request. Ejecting the
  model stops the worker, which gives back *all* GPU memory, CUDA context included.
- **Jobs instead of long requests.** The browser no longer holds one HTTP request open for the whole
  transcription (which proxies, sleep or Wi-Fi drops would cut, showing *"Failed to fetch"*). Polling retries
  through network errors, and the current job — or the last result — is restored when the page is reloaded.
- **Clear errors.** Every error is returned as JSON with a readable `detail`; the UI never shows raw parse
  errors. Invalid environment variables are logged and replaced by defaults instead of crashing the server.
- **Non-blocking startup.** The initial model loads in the background: the UI is reachable immediately and shows
  the download progress (with size and percentage for large models).

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

Fields added in v2: `language` (form field, optional: a Whisper language code such as `fr`, or `auto`), and in the response `duration` (seconds) and `warnings` (e.g. word alignment skipped).

**Errors:** `400` for undecodable audio, empty file, invalid language or speaker hints, `413` for files over `WHISPER_MAX_UPLOAD_MB`, `503` when diarization is requested but no Hugging Face token is configured or the pyannote model can't be loaded, `500` with a `detail` message otherwise (including a worker crash).

`POST /transcribe` waits for the result, which is convenient for scripts. For long files, prefer the job API below.

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

### Jobs

| Method & path                     | Description |
|-----------------------------------|-------------|
| `POST /jobs`                      | Same form fields as `/transcribe` (+ `quick=true` for the streaming mode). Returns `202` with the job right away |
| `GET /jobs/{id}`                  | `status` (`queued`, `running`, `done`, `error`, `cancelled`), `progress` (0–1), `message`, `stage`, `result`, `error` (`code`, `message`) |
| `GET /jobs`                       | Recent jobs (without results) |
| `POST /jobs/{id}/cancel`          | Cancel a queued or running job |
| `GET /jobs/{id}/export?format=…`  | Download the result as `txt`, `srt`, `vtt` or `json` |

```bash
JOB=$(curl -s -F "file=@meeting.mp3" -F language=fr http://localhost:8081/jobs | jq -r .id)
curl -s http://localhost:8081/jobs/$JOB | jq '{status, progress, message}'
curl -s -o meeting.srt "http://localhost:8081/jobs/$JOB/export?format=srt"
```

Jobs live in memory (the last `WHISPER_JOB_HISTORY` are kept) and are lost when the server restarts.

### Model and status endpoints

| Method & path            | Description |
|--------------------------|-------------|
| `GET /health`            | Model, device, GPU info, ffmpeg, diarization availability, queue state |
| `GET /healthz`           | Liveness probe (`{"status": "ok"}`), used by the Docker healthcheck |
| `GET /progress`          | Latest progress event of any job |
| `POST /switch-model`     | `{"model": "small", "device": "cuda"}` — waits for the load; add `"wait": false` to get a job instead |
| `POST /eject`            | Unload all models (`?wait=false` to get a job) |

### Hugging Face token endpoints

| Method & path                     | Description |
|-----------------------------------|-------------|
| `GET /settings/hf-token`          | Whether a token is configured, its source (`env` / `file`) and a masked preview |
| `PUT /settings/hf-token`          | `{"token": "hf_…"}` — verifies it with the Hub (rejects invalid tokens, warns if the model terms are not accepted) and saves it |
| `POST /settings/hf-token/check`   | Re-check the configured token |
| `DELETE /settings/hf-token`       | Remove the saved token |

The token is never returned by the API. Interactive API docs are at `/docs`.

## Speaker Diarization Setup

Diarization uses the gated [`pyannote/speaker-diarization-community-1`](https://huggingface.co/pyannote/speaker-diarization-community-1) pipeline:

1. Create a Hugging Face access token (read access) at <https://huggingface.co/settings/tokens>
2. Accept the user conditions on the [model page](https://huggingface.co/pyannote/speaker-diarization-community-1)
3. Give the token to the server, in one of these ways:
   - **Web UI (simplest):** open *Transcription Options → 🔑 Hugging Face token*, paste the token and click *Save*.
     The server checks it with Hugging Face, then saves it where `hf auth login` does (`~/.cache/huggingface/token`,
     readable only by its owner). *Test current token* checks the access to the model at any time.
   - **Saved login from a terminal:** run this once as the user that runs the server:
     ```bash
     source venv/bin/activate
     hf auth login   # paste the token when prompted; it is saved to ~/.cache/huggingface/token
     ```
     The server reads the saved token on each request, so no restart is needed.
     In Docker, the `model-cache` volume mounted on `/root/.cache` keeps it: run `docker compose exec speech-to-text hf auth login` once.
   - **Environment variable:** set `HF_TOKEN` (it takes precedence over the saved login). Don't put it in the tracked `.env` file.

Without a token, transcription works as usual and the "Identify speakers" toggle in the UI is disabled. The diarization pipeline is loaded on first use and freed by `/eject`.

To check that the token can access the model:

```bash
python -c "from huggingface_hub import model_info; print(model_info('pyannote/speaker-diarization-community-1').id)"
```

## Web UI

```
http://localhost:8081
```

The web interface provides:
- **Microphone mode** — click "Record" to capture audio, with a live frequency visualizer
- **Upload mode** — drag and drop audio files or click to browse
- **Model selector** — pick the desired Whisper model and device, with live download / load progress
- **Language** — auto-detect, or force the language when detection gets it wrong (short or accented recordings)
- **Identify speakers** — toggle diarization, optionally with a min/max speaker count; the transcript is grouped by color-coded speaker and "Copy" includes the speaker labels
- **Hugging Face token** — add, test or remove the token used for speaker identification
- **Progress & cancel** — upload percentage, stage, elapsed time, and a Cancel button
- **Result** — copy, timestamps view, download as TXT / SRT / VTT / JSON, warnings when something was skipped
- **Connection banner** — shown when the server can't be reached; polling resumes automatically

## Configuration

Set environment variables in `.env` or at startup:

| Variable            | Default                                         | Description                    |
|---------------------|-------------------------------------------------|--------------------------------|
| `WHISPER_PORT`      | `8081`                                          | HTTP server port               |
| `WHISPER_MODEL`     | `base`                                          | Whisper model name             |
| `WHISPER_DEVICE`    | `cpu`                                           | Inference device (`cpu`/`cuda`)|
| `WHISPER_COMPUTE_TYPE` | `float16` on GPU, `int8` on CPU              | CTranslate2 compute type (`float16`, `int8_float16`, `int8`, `float32`) |
| `WHISPER_BATCH_SIZE`| `16`                                            | Batch size for VAD chunks (lower it if GPU runs out of memory) |
| `HF_TOKEN`          | saved `hf auth login` token                     | Hugging Face token, required for speaker diarization |
| `WHISPER_DIARIZE`   | `false`                                         | Diarize by default when the request doesn't say |
| `WHISPER_DIARIZE_MODEL` | `pyannote/speaker-diarization-community-1`  | pyannote pipeline used for diarization |
| `FFMPEG_PATH`       | auto-detected                                   | Path to ffmpeg binary (conda env, then `PATH`) |
| `WHISPER_HOST`      | `0.0.0.0`                                       | Listen address (`python -m app.main`) |
| `WHISPER_PRELOAD`   | `true`                                          | Load the model in the background at startup |
| `WHISPER_MAX_UPLOAD_MB` | `2048`                                      | Largest accepted upload |
| `WHISPER_FFMPEG_TIMEOUT` | `1800`                                     | Seconds allowed to decode one file |
| `WHISPER_WORKER_MODE` | `process`                                     | `process` (isolated worker, recommended) or `inline` (same process, for debugging) |
| `WHISPER_JOB_HISTORY` | `50`                                          | Finished jobs kept in memory |
| `WHISPER_CORS_ORIGINS` | `*`                                          | Comma-separated allowed origins |
| `WHISPER_LOG_LEVEL` | `INFO`                                          | Log level |

Invalid values (e.g. a port above 65535) are logged as warnings and replaced by the default.

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
Model: large on cuda (worker mode: process)
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
cd speech-to-text
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

## Troubleshooting

| Symptom | Cause and fix |
|---------|---------------|
| **"Failed to fetch"** / *Cannot reach the server* | The browser lost the connection to the server. Before v2 a long transcription held one request open and a native crash killed the server; both are handled now. If the banner stays, check that the server is running and look at its logs. |
| *The transcription worker stopped unexpectedly (SIGABRT / SIGSEGV)* | A native library crashed in the worker, usually CUDA: missing cuDNN/cuBLAS (`LD_LIBRARY_PATH`), or GPU out of memory. Try a smaller model, `WHISPER_BATCH_SIZE=4`, `WHISPER_COMPUTE_TYPE=int8_float16`, or the CPU. The worker restarts by itself. |
| *SIGKILL — often the system running out of RAM* | The OS killed the worker. Use a smaller model or free memory. |
| Warning *Word timestamps skipped: … punkt_tab* | The NLTK sentence data is missing and couldn't be downloaded. Run `python -m nltk.downloader punkt_tab` once (already done in the Docker image). |
| *Microphone needs HTTPS or localhost* | Browsers only allow the microphone on secure origins. Open the UI as `http://localhost:8081` on the server machine, put it behind HTTPS, or use Upload. |
| Speaker identification disabled | No Hugging Face token: add one in the UI. If saving says *accept the model terms*, open the model page while logged in and accept them. |
| *Could not load diarization model* | The token can't access the gated model, or the download failed. Use *Test current token* in the UI. |

## File Structure

```
speech-to-text/
├── app/
│   ├── main.py          # FastAPI application: routes, uploads, startup
│   ├── config.py        # Settings from environment variables (validated)
│   ├── jobs.py          # Job queue: one job at a time, progress, cancel, history
│   ├── worker.py        # Worker process (crash isolation) and inline backend
│   ├── pipeline.py      # WhisperX engine: model download/load, transcribe, align, diarize
│   ├── audio.py         # ffmpeg decoding
│   ├── speakers.py      # Speaker post-processing (split segments, speaker transcript)
│   ├── formats.py       # TXT / SRT / VTT / JSON export
│   ├── hf.py            # Hugging Face token storage and verification
│   ├── system.py        # ffmpeg, NVIDIA GPU and CUDA detection
│   ├── errors.py        # Error types with stable codes
│   └── languages.py     # Languages supported by Whisper
├── templates/
│   └── index.html       # Web UI
├── static/
│   ├── app.css          # Web UI styles
│   └── app.js           # Web UI logic
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

The test suite covers the HTTP API (transcription, jobs, cancel, export, model switching, Hugging Face token settings, error handling), the WhisperX pipeline with mocked models (alignment, diarization, fallbacks), the worker process (including a real crash and restart), the job queue, configuration, exports and speaker post-processing. No model is downloaded: WhisperX is mocked, and a fake engine stands in for the worker tests.

## License

MIT
