# Speech to Text

Voice transcription powered by **OpenAI Whisper** — runs locally, supports microphone recording and file upload.

## Features

- 🎤 **Microphone capture** — record directly in the browser with audio visualizer
- 📁 **File upload** — drag-and-drop or browse for audio files
- 🎵 **Multi-format support** — WAV, MP3, MP4, FLAC, OGG, M4A, WEBM
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
                                            │  Whisper Model │
                                            │  (CPU/GPU)     │
                                            └───────┬────────┘
                                                    │
                                            ┌───────▼────────┐
                                            │  ffmpeg /      │
                                            │  librosa       │
                                            │  (audio load)  │
                                            └────────────────┘
```

**Flow:**
1. Browser sends audio file (via mic capture → `MediaRecorder` or file upload → drag-and-drop)
2. FastAPI receives `multipart/form-data`, saves to temp file
3. Audio is converted to WAV via `ffmpeg` if needed (or loaded directly via `librosa`)
4. Whisper model transcribes the audio on CPU or GPU
5. Results (`text`, `language`, `segments`) returned as JSON

## API Endpoints

### POST `/transcribe`

Transcribe an audio file.

**Request:** `multipart/form-data` with field `file`

| Field  | Type    | Description              |
|--------|---------|--------------------------|
| `file` | `file`  | Audio file to transcribe |

**Response (200):**

```json
{
  "text": "Hello, this is a test.",
  "language": "en",
  "segments": [
    {
      "id": 0,
      "start": 0.0,
      "end": 4.24,
      "text": "Hello, this is a test.",
      "tokens": [50364, 2425, ...],
      "temperature": 0.0,
      "avg_logprob": -0.31,
      "compression_ratio": 0.98,
      "no_speech_prob": 0.002
    }
  ]
}
```

**Example with curl:**

```bash
# From file
curl -F "file=@recording.wav" http://localhost:8081/transcribe

# From microphone (webm)
curl -F "file=@recording.webm" http://localhost:8081/transcribe
```

### POST `/transcribe/streaming`

Transcribe with language detection (alias endpoint for real-time workflows).

## Web UI

```
http://localhost:8081
```

The web interface provides:
- **Microphone mode** — click "Record" to capture audio, with a live frequency visualizer
- **Upload mode** — drag and drop audio files or click to browse
- **Model selector** — pick the desired Whisper model before transcribing

## Configuration

Set environment variables in `.env` or at startup:

| Variable            | Default                                         | Description                    |
|---------------------|-------------------------------------------------|--------------------------------|
| `WHISPER_PORT`      | `8081`                                          | HTTP server port               |
| `WHISPER_MODEL`     | `base`                                          | Whisper model name             |
| `WHISPER_DEVICE`    | `cpu`                                           | Inference device (`cpu`/`cuda`)|
| `FFMPEG_PATH`       | auto-detected                                   | Path to ffmpeg binary          |
| `WHISPER_FP16`      | `false`                                         | Enable FP16 for speed (GPU)   |

### Available Models

| Model    | Params  | Disk Space | GPU Memory | Speed   | Accuracy |
|----------|---------|------------|------------|---------|----------|
| `tiny`   | 39M     | ~150 MB    | ~300 MB    | Fastest | Lower    |
| `base`   | 74M     | ~460 MB    | ~600 MB    | Fast    | Good     |
| `small`  | 244M    | ~1.5 GB    | ~1.5 GB    | Moderate| Very Good|
| `medium` | 769M    | ~5 GB      | ~3.5 GB    | Slow    | Excellent|
| `large`  | 1550M   | ~10 GB     | ~6 GB      | Slowest | Best     |
| `large-v3`| 1550M  | ~10 GB     | ~7 GB      | Slowest | Best     |

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
python -c "import whisper; whisper.load_model('large', device='cuda')"
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
│   └── main.py          # FastAPI application
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
| `openai-whisper` | 20240930| Speech-to-text engine       |
| `torch`          | 2.6.0   | Deep learning framework     |
| `ffmpeg-python`  | 0.2.0   | Audio format conversion     |
| `edge-tts`       | 7.0.0   | Text-to-speech (testing)    |

## Performance Notes

- **CPU inference** works for all models but is slow for `large` / `large-v3`
- **GPU inference** with CUDA is highly recommended for `medium` and larger models
- **fp16 precision** can be enabled by setting `fp16=True` in the `model.transcribe()` call (already set in future versions)
- Model weights are cached in `~/.cache/whisper/` — no re-download needed between restarts

## Testing

Run the test suite with pytest:

```bash
source venv/bin/activate
pytest tests/ -v
```

The test suite covers app initialization, health/progress endpoints, model switching, audio conversion, transcription (mocked), error handling, and more. Tests use `fastapi.testclient` and mock Whisper to run without requiring a model download.

## License

MIT
