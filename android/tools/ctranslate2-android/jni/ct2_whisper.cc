// Whisper on CTranslate2, without Python (issue #84). See ct2_whisper.h.
#include "ct2_whisper.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <complex>
#include <stdexcept>

#include <ctranslate2/models/whisper.h>
#include <ctranslate2/utils.h>

#ifdef _OPENMP
#  include <omp.h>
#endif

// Private CTranslate2 header (static build: the source tree is on the include path).
#include "cpu/cpu_isa.h"

namespace ct2w {

  namespace {

    using cd = std::complex<double>;
    using clock_type = std::chrono::steady_clock;

    double ms_since(clock_type::time_point t) {
      return std::chrono::duration<double, std::milli>(clock_type::now() - t).count();
    }

    // exp(-2 pi i j / kNFft): every sub-size of the mixed-radix FFT below divides kNFft (400 = 2^4 * 5^2).
    const std::vector<cd>& twiddles() {
      static const std::vector<cd> w = [] {
        std::vector<cd> v(kNFft);
        for (int j = 0; j < kNFft; ++j)
          v[j] = std::polar(1.0, -2.0 * M_PI * j / kNFft);
        return v;
      }();
      return w;
    }

    // Recursive decimation-in-time FFT for sizes made of factors 2, 3 and 5 dividing kNFft.
    void fft(const cd* in, cd* out, int n, int stride, cd* scratch) {
      if (n == 1) {
        out[0] = in[0];
        return;
      }
      const int p = (n % 2 == 0) ? 2 : (n % 5 == 0) ? 5 : (n % 3 == 0) ? 3 : n;
      const int m = n / p;
      for (int r = 0; r < p; ++r)
        fft(in + r * stride, out + r * m, m, stride * p, scratch + n);
      const auto& w = twiddles();
      const int step = kNFft / n;
      for (int k = 0; k < m; ++k) {
        for (int q = 0; q < p; ++q) {
          cd s = 0;
          for (int r = 0; r < p; ++r)
            s += out[r * m + k] * w[(static_cast<long>(r) * (k + q * m) * step) % kNFft];
          scratch[k + q * m] = s;
        }
      }
      std::copy(scratch, scratch + n, out);
    }

    // numpy.pad(mode="reflect") index.
    long reflect(long i, long n) {
      if (n == 1)
        return 0;
      const long period = 2 * (n - 1);
      i %= period;
      if (i < 0)
        i += period;
      return i < n ? i : period - i;
    }

    // GPT-2 bytes_to_unicode, inverted: code point -> byte.
    const std::vector<int>& unicode_to_byte() {
      static const std::vector<int> table = [] {
        std::vector<int> t(512, -1);
        std::vector<int> bs;
        for (int b = '!'; b <= '~'; ++b) bs.push_back(b);
        for (int b = 0xA1; b <= 0xAC; ++b) bs.push_back(b);
        for (int b = 0xAE; b <= 0xFF; ++b) bs.push_back(b);
        std::vector<bool> direct(256, false);
        for (int b : bs) {
          direct[b] = true;
          t[b] = b;
        }
        int n = 0;
        for (int b = 0; b < 256; ++b) {
          if (!direct[b])
            t[256 + n++] = b;
        }
        return t;
      }();
      return table;
    }

  }

  std::vector<float> mel_filters(int n_mels) {
    const int bins = kNFft / 2 + 1;
    std::vector<double> fftfreqs(bins);
    for (int i = 0; i < bins; ++i)
      fftfreqs[i] = i * static_cast<double>(kSampleRate) / kNFft;
    const double max_mel = 45.245640471924965;
    const double f_sp = 200.0 / 3;
    const double min_log_hz = 1000.0;
    const double min_log_mel = min_log_hz / f_sp;
    const double logstep = std::log(6.4) / 27.0;
    std::vector<double> freqs(n_mels + 2);
    for (int i = 0; i < n_mels + 2; ++i) {
      const double mel = max_mel * i / (n_mels + 1);
      freqs[i] = mel >= min_log_mel ? min_log_hz * std::exp(logstep * (mel - min_log_mel)) : f_sp * mel;
    }
    std::vector<float> weights(static_cast<size_t>(n_mels) * bins);
    for (int m = 0; m < n_mels; ++m) {
      const double enorm = 2.0 / (freqs[m + 2] - freqs[m]);
      for (int k = 0; k < bins; ++k) {
        const double lower = -(freqs[m] - fftfreqs[k]) / (freqs[m + 1] - freqs[m]);
        const double upper = (freqs[m + 2] - fftfreqs[k]) / (freqs[m + 2] - freqs[m + 1]);
        weights[static_cast<size_t>(m) * bins + k] =
          static_cast<float>(std::max(0.0, std::min(lower, upper)) * enorm);
      }
    }
    return weights;
  }

  std::vector<float> log_mel(const std::vector<float>& samples, int n_mels, int* frames_out) {
    const long n = static_cast<long>(samples.size());
    if (n == 0)
      throw std::invalid_argument("empty audio");
    const int bins = kNFft / 2 + 1;
    const int pad = kNFft / 2;
    // STFT frames over the reflect-padded signal, minus the last one (faster-whisper: stft[..., :-1]).
    const int frames = static_cast<int>(n / kHop);
    *frames_out = frames;
    static const std::vector<float> window = [] {
      // numpy.hanning(n_fft + 1)[:-1] in float32: the periodic Hann window.
      std::vector<float> w(kNFft);
      for (int i = 0; i < kNFft; ++i)
        w[i] = static_cast<float>(0.5 - 0.5 * std::cos(2.0 * M_PI * i / kNFft));
      return w;
    }();
    static thread_local std::vector<float> filters;
    static thread_local int filters_mels = 0;
    if (filters_mels != n_mels) {
      filters = mel_filters(n_mels);
      filters_mels = n_mels;
    }

    std::vector<float> mel(static_cast<size_t>(n_mels) * frames);
    std::vector<cd> in(kNFft), out(kNFft), scratch(4 * kNFft);
    std::vector<float> power(bins);
    for (int f = 0; f < frames; ++f) {
      bool silent = true;
      for (int i = 0; i < kNFft; ++i) {
        const float x = samples[reflect(static_cast<long>(f) * kHop + i - pad, n)];
        silent = silent && x == 0.0f;
        in[i] = cd(static_cast<float>(x * window[i]), 0.0);
      }
      if (silent) {
        std::fill(power.begin(), power.end(), 0.0f);
      } else {
        fft(in.data(), out.data(), kNFft, 1, scratch.data());
        for (int k = 0; k < bins; ++k) {
          // complex64 then |.|^2 in float32, as faster-whisper.
          const std::complex<float> c(static_cast<float>(out[k].real()), static_cast<float>(out[k].imag()));
          power[k] = std::norm(c);
        }
      }
      for (int m = 0; m < n_mels; ++m) {
        const float* w = &filters[static_cast<size_t>(m) * bins];
        float s = 0.0f;
        for (int k = 0; k < bins; ++k)
          s += w[k] * power[k];
        mel[static_cast<size_t>(m) * frames + f] = s;
      }
    }
    float top = -INFINITY;
    for (float& v : mel) {
      v = std::log10(std::max(v, 1e-10f));
      top = std::max(top, v);
    }
    for (float& v : mel)
      v = (std::max(v, top - 8.0f) + 4.0f) / 4.0f;
    return mel;
  }

  std::string token_bytes(const std::string& token) {
    const auto& table = unicode_to_byte();
    std::string bytes;
    for (size_t i = 0; i < token.size();) {
      // UTF-8 decode one code point.
      const unsigned char c = token[i];
      int cp, len;
      if (c < 0x80) { cp = c; len = 1; }
      else if ((c >> 5) == 0x6) { cp = c & 0x1F; len = 2; }
      else if ((c >> 4) == 0xE) { cp = c & 0x0F; len = 3; }
      else { cp = c & 0x07; len = 4; }
      for (int j = 1; j < len && i + j < token.size(); ++j)
        cp = (cp << 6) | (token[i + j] & 0x3F);
      i += len;
      if (cp >= 0 && cp < static_cast<int>(table.size()) && table[cp] >= 0)
        bytes.push_back(static_cast<char>(table[cp]));
    }
    return bytes;
  }

  Engine::Engine(const std::string& model_dir, int num_threads) {
    ctranslate2::ReplicaPoolConfig config;
    config.num_threads_per_replica = static_cast<size_t>(std::max(1, num_threads));
    _model = std::make_unique<ctranslate2::models::Whisper>(
      model_dir, ctranslate2::Device::CPU, ctranslate2::ComputeType::INT8,
      std::vector<int>{0}, false, config);
    _n_mels = static_cast<int>(_model->n_mels());
  }

  Engine::~Engine() = default;

  Result Engine::transcribe(const std::vector<float>& audio, const Options& options) {
    Result result;
    auto t0 = clock_type::now();
    // faster-whisper 1.2.1: features of the audio + 160 zeros, `content_frames = frames - 1`, then
    // zeros in feature space up to 3000 frames (pad_or_trim). With padding_frames >= 0, the zeros stop
    // `padding_frames` after the audio, as sherpa-onnx's tailPaddings (zeros in feature space too).
    std::vector<float> padded(audio);
    padded.resize(audio.size() + kHop, 0.0f);
    int frames = 0;
    const auto mel = log_mel(padded, _n_mels, &frames);
    const int content = std::min(std::max(frames - 1, 1), kChunkFrames);
    const int input_frames = options.padding_frames < 0
      ? kChunkFrames
      : std::min(content + options.padding_frames, kChunkFrames);
    std::vector<float> encoder_input(static_cast<size_t>(_n_mels) * input_frames, 0.0f);
    for (int m = 0; m < _n_mels; ++m)
      std::copy_n(&mel[static_cast<size_t>(m) * frames], content,
                  &encoder_input[static_cast<size_t>(m) * input_frames]);
    result.encoder_frames = input_frames;
    result.features_ms = ms_since(t0);

    t0 = clock_type::now();
    ctranslate2::StorageView features({1, static_cast<ctranslate2::dim_t>(_n_mels),
                                       static_cast<ctranslate2::dim_t>(input_frames)},
                                      std::move(encoder_input));
    ctranslate2::StorageView encoded = _model->encode(features, /*to_cpu=*/true).get();
    result.encode_ms = ms_since(t0);

    t0 = clock_type::now();
    std::string language = options.language;
    if (language.empty() && _model->is_multilingual()) {
      auto detected = _model->detect_language(encoded);
      const auto probs = detected[0].get();
      if (!probs.empty())
        language = probs.front().first.substr(2, probs.front().first.size() - 4);  // "<|fr|>" -> "fr"
    }
    result.language = language;
    std::vector<std::string> prompt{"<|startoftranscript|>"};
    if (_model->is_multilingual() && !language.empty())
      prompt.push_back("<|" + language + "|>");
    prompt.push_back("<|transcribe|>");
    prompt.push_back("<|notimestamps|>");

    ctranslate2::models::WhisperOptions wo;
    wo.beam_size = 1;  // greedy, as sherpa-onnx
    const double seconds = static_cast<double>(audio.size()) / kSampleRate;
    wo.max_length = std::min<size_t>(448, prompt.size() + 8 +
                                     static_cast<size_t>(std::ceil(seconds * options.max_tokens_per_second)));
    wo.return_no_speech_prob = true;
    auto futures = _model->generate(encoded, {prompt}, wo);
    const auto generated = futures[0].get();
    result.decode_ms = ms_since(t0);
    result.no_speech_prob = generated.no_speech_prob;

    std::string text;
    const auto& ids = generated.sequences_ids[0];
    const auto& tokens = generated.sequences[0];
    for (size_t i = 0; i < tokens.size(); ++i) {
      if (ids[i] >= 50257)  // <|endoftext|> and every special token after it
        continue;
      text += token_bytes(tokens[i]);
      ++result.tokens;
    }
    // Trim the leading space Whisper puts before the first word.
    const auto first = text.find_first_not_of(' ');
    const auto last = text.find_last_not_of(' ');
    result.text = first == std::string::npos ? "" : text.substr(first, last - first + 1);
    return result;
  }

  std::string build_info() {
    std::string info = "CTranslate2 4.9.0 · ISA ";
    info += ctranslate2::cpu::isa_to_str(ctranslate2::cpu::get_cpu_isa());
    info += " · GEMM Ruy";
#ifdef _OPENMP
    info += " · OpenMP " + std::to_string(omp_get_max_threads()) + " fils max";
#endif
    return info;
  }

}
