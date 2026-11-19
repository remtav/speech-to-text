// Whisper on CTranslate2, without Python (issue #84).
// What faster-whisper does in Python for one segment, redone in C++: log-mel features, prompt,
// greedy decoding through ctranslate2::models::Whisper, byte-level token decoding.
#pragma once

#include <memory>
#include <string>
#include <vector>

namespace ctranslate2 { namespace models { class Whisper; } }

namespace ct2w {

  constexpr int kSampleRate = 16000;
  constexpr int kNFft = 400;
  constexpr int kHop = 160;
  constexpr int kChunkFrames = 3000;  // 30 s: the longest encoder input

  // Log-mel spectrogram as faster-whisper's FeatureExtractor computes it (center STFT with reflect
  // padding, periodic Hann window, Slaney mel filters, log10 clamped to max - 8, (x + 4) / 4).
  // `samples` must already carry the zeros appended after the audio. Returns n_mels x frames, row-major,
  // frames = samples.size() / kHop (the last STFT frame is dropped, as in faster-whisper).
  std::vector<float> log_mel(const std::vector<float>& samples, int n_mels, int* frames);

  // Slaney mel filter bank, n_mels x (kNFft / 2 + 1), row-major (librosa.filters.mel with defaults).
  std::vector<float> mel_filters(int n_mels);

  // GPT-2 byte-level BPE token (as stored in the CTranslate2 vocabulary) to its UTF-8 bytes.
  std::string token_bytes(const std::string& token);

  struct Options {
    // Language token without brackets ("fr"); empty: detected on each segment.
    std::string language;
    // Frames (100 per second) of zeros after the audio in the encoder input, as sherpa-onnx's
    // tailPaddings; negative: the full 30 s window, as faster-whisper. The input never exceeds 3000.
    int padding_frames = -1;
    // Most tokens per second of audio before decoding stops (a loop on silence otherwise runs to 448).
    float max_tokens_per_second = 15.0f;
  };

  struct Result {
    std::string text;
    std::string language;
    int tokens = 0;
    int encoder_frames = 0;
    double features_ms = 0, encode_ms = 0, decode_ms = 0;
    float no_speech_prob = 0;
  };

  class Engine {
  public:
    Engine(const std::string& model_dir, int num_threads);
    ~Engine();
    Result transcribe(const std::vector<float>& samples, const Options& options);
    int n_mels() const { return _n_mels; }

  private:
    std::unique_ptr<ctranslate2::models::Whisper> _model;
    int _n_mels;
  };

  // "CTranslate2 4.9.0 · ISA NEON · GEMM Ruy · OpenMP 4 fils": proof the NEON path is in use.
  std::string build_info();

}
