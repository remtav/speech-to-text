"""Issue #25, piste 3 (calibration statique) : mels de calibration (approximation de l'entrée sherpa : audio + 10 s de silence). usage : mels_calibration.py SORTIE.npz WAV..."""
import sys, numpy as np, soundfile as sf, whisper
out = {}
for i, p in enumerate(sys.argv[2:]):
    a, sr = sf.read(p, dtype="float32"); assert sr == 16000
    a = np.concatenate([a, np.zeros(160000, np.float32)])
    m = whisper.log_mel_spectrogram(a, n_mels=128).numpy()
    out[f"c{i}"] = m[None].astype(np.float32)
np.savez(sys.argv[1], **out); print(len(out), "clips", [v.shape[2] for v in out.values()][:5])
