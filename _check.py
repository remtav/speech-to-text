import tempfile
import whisper
import os

# Check if the base model is available
model_name = os.getenv("WHISPER_MODEL", "base")
try:
    m = whisper.load_model(model_name)
    print(f"✅ Model '{model_name}' loaded successfully: {m.dims}")
except Exception as e:
    print(f"❌ Failed to load model '{model_name}': {e}")
    exit(1)
