// Host check of the bridge without the JVM (issue #84): transcribes 16 kHz mono 16-bit WAV files.
// usage : ct2_whisper_cli MODEL_DIR THREADS PADDING LANGUAGE file.wav...   (LANGUAGE "-" : detected)
// Prints one tab-separated line per file: file, language, encoder frames, features/encode/decode ms, text.
// With the environment variable CT2W_DUMP_MEL=path, also writes the encoder input of the first file
// (float32, n_mels x frames) for comparison with faster-whisper's FeatureExtractor.
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <vector>

#include "ct2_whisper.h"

static std::vector<float> read_wav(const char* path) {
  std::ifstream in(path, std::ios::binary);
  if (!in)
    throw std::runtime_error(std::string("cannot open ") + path);
  std::vector<char> data((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
  // Walk the RIFF chunks to "fmt " and "data".
  size_t pos = 12;
  int channels = 0, bits = 0, rate = 0;
  while (pos + 8 <= data.size()) {
    const std::string id(&data[pos], 4);
    uint32_t size;
    std::memcpy(&size, &data[pos + 4], 4);
    if (id == "fmt ") {
      uint16_t c, b;
      uint32_t r;
      std::memcpy(&c, &data[pos + 10], 2);
      std::memcpy(&r, &data[pos + 12], 4);
      std::memcpy(&b, &data[pos + 22], 2);
      channels = c; rate = static_cast<int>(r); bits = b;
    } else if (id == "data") {
      if (channels != 1 || bits != 16 || rate != ct2w::kSampleRate)
        throw std::runtime_error(std::string(path) + ": 16 kHz mono 16-bit expected");
      const size_t n = std::min<size_t>(size, data.size() - pos - 8) / 2;
      std::vector<float> samples(n);
      for (size_t i = 0; i < n; ++i) {
        int16_t s;
        std::memcpy(&s, &data[pos + 8 + 2 * i], 2);
        samples[i] = s / 32768.0f;
      }
      return samples;
    }
    pos += 8 + size + (size & 1);
  }
  throw std::runtime_error(std::string(path) + ": no data chunk");
}

int main(int argc, char** argv) {
  if (argc < 6) {
    std::cerr << "usage: " << argv[0] << " MODEL_DIR THREADS PADDING LANGUAGE file.wav...\n";
    return 2;
  }
  std::cerr << ct2w::build_info() << "\n";
  ct2w::Engine engine(argv[1], std::atoi(argv[2]));
  ct2w::Options options;
  options.padding_frames = std::atoi(argv[3]);
  options.language = std::strcmp(argv[4], "-") == 0 ? "" : argv[4];
  if (const char* dump = std::getenv("CT2W_DUMP_MEL")) {
    auto audio = read_wav(argv[5]);
    audio.resize(audio.size() + ct2w::kHop, 0.0f);
    int frames = 0;
    const auto mel = ct2w::log_mel(audio, engine.n_mels(), &frames);
    std::ofstream(dump, std::ios::binary).write(reinterpret_cast<const char*>(mel.data()), mel.size() * 4);
    std::cerr << "mel " << engine.n_mels() << " x " << frames << " -> " << dump << "\n";
  }
  for (int i = 5; i < argc; ++i) {
    const auto r = engine.transcribe(read_wav(argv[i]), options);
    std::printf("%s\t%s\t%d\t%.1f\t%.1f\t%.1f\t%s\n", argv[i], r.language.c_str(), r.encoder_frames,
                r.features_ms, r.encode_ms, r.decode_ms, r.text.c_str());
    std::fflush(stdout);
  }
  return 0;
}
