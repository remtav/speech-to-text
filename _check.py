import whisperx
import os

# Check if the base model is available
model_name = os.getenv("WHISPER_MODEL", "base")
device = os.getenv("WHISPER_DEVICE", "cpu")
try:
    m = whisperx.load_model(model_name, device, compute_type="float16" if device == "cuda" else "int8")
    print(f"✅ Model '{model_name}' loaded successfully on {device}")
except Exception as e:
    print(f"❌ Failed to load model '{model_name}': {e}")
    exit(1)
