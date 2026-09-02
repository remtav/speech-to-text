import os
import whisper
import tempfile
from fastapi import FastAPI, UploadFile, File, HTTPException
from fastapi.responses import HTMLResponse
from fastapi.middleware.cors import CORSMiddleware

app = FastAPI(title="Speech-to-Text", description="Voice transcription powered by OpenAI Whisper")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# Load model on CPU to avoid GPU memory conflicts with other processes
MODEL_NAME = os.getenv("WHISPER_MODEL", "base")
DEVICE = os.getenv("WHISPER_DEVICE", "cpu")  # Use CPU since GPU is shared with ollama
print(f"Loading Whisper model: {MODEL_NAME} on {DEVICE}...")
model = whisper.load_model(MODEL_NAME, device=DEVICE)

# Get ffmpeg path for audio conversion
FFMPEG_PATH = None
def get_ffmpeg_path():
    """Get the ffmpeg path, checking environment variable first."""
    ffmpeg_path = os.getenv("FFMPEG_PATH")
    if ffmpeg_path and os.path.exists(ffmpeg_path):
        return ffmpeg_path
    
    # Check common conda locations
    conda_env = os.getenv("CONDA_PREFIX")
    if conda_env:
        conda_ffmpeg = os.path.join(conda_env, "bin", "ffmpeg")
        if os.path.exists(conda_ffmpeg):
            return conda_ffmpeg
    
    # Last resort: try to find it
    possible_paths = [
        "/home/remi/miniconda3/envs/whisper-gpu/bin/ffmpeg",
        "/home/remi/miniconda3/bin/ffmpeg",
    ]
    for path in possible_paths:
        if os.path.exists(path):
            return path
    
    return None

FFMPEG_PATH = get_ffmpeg_path()
if FFMPEG_PATH:
    print(f"ffmpeg found at: {FFMPEG_PATH}")
else:
    print("Warning: ffmpeg not found, will rely on librosa for audio loading")

@app.get("/", response_class=HTMLResponse)
async def index():
    with open("templates/index.html", "r") as f:
        return f.read()

def convert_audio_to_wav(tmp_path):
    """Convert uploaded audio to WAV using ffmpeg or whisper's built-in loader."""
    ext = os.path.splitext(tmp_path)[1].lower()
    
    # If already WAV, no conversion needed
    if ext in ('.wav', '.wave'):
        return tmp_path, ext
    
    # Try ffmpeg for conversion
    if FFMPEG_PATH:
        try:
            import subprocess
            wav_path = tmp_path + ".wav"
            result = subprocess.run(
                [FFMPEG_PATH, "-y", "-i", tmp_path, wav_path],
                capture_output=True, text=True, timeout=30
            )
            if result.returncode == 0 and os.path.exists(wav_path):
                return wav_path, ".wav"
        except (FileNotFoundError, subprocess.TimeoutExpired):
            pass
    
    # Whisper can load most formats directly via librosa
    # Return the original file - whisper's load_audio will handle it
    print(f"Using original file for transcription ({ext})")
    return tmp_path, ext

@app.post("/transcribe")
async def transcribe(file: UploadFile = File(...)):
    try:
        # Save uploaded file
        with tempfile.NamedTemporaryFile(suffix=os.path.splitext(file.filename)[1], delete=False) as tmp:
            content = await file.read()
            tmp.write(content)
            tmp_path = tmp.name

        # Convert to WAV if possible (Whisper needs WAV or FLAC)
        processed_path, _ = convert_audio_to_wav(tmp_path)
        
        # Transcribe
        result = model.transcribe(processed_path, fp16=False)
        os.unlink(tmp_path)
        if processed_path != tmp_path:
            os.unlink(processed_path)

        return {
            "text": result["text"],
            "language": result.get("language", "unknown"),
            "segments": result.get("segments", []),
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/transcribe/streaming")
async def transcribe_streaming(file: UploadFile = File(...)):
    """Return partial results in real-time"""
    try:
        with tempfile.NamedTemporaryFile(suffix=os.path.splitext(file.filename)[1], delete=False) as tmp:
            content = await file.read()
            tmp.write(content)
            tmp_path = tmp.name

        # Convert to WAV if needed
        processed_path, _ = convert_audio_to_wav(tmp_path)
        
        # Use detect_language first, then transcribe
        audio = model.load_audio(processed_path)
        language = model.detect_language(audio)
        result = model.transcribe(processed_path, fp16=False)
        os.unlink(tmp_path)
        if processed_path != tmp_path:
            os.unlink(processed_path)

        return {
            "text": result["text"],
            "language": language.language,
            "task": "transcribe",
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=int(os.getenv("WHISPER_PORT", "8081")))
