// JNI bridge for io.github.remtav.dictee.stt.Ct2Native (issue #84).
#include <jni.h>

#include <exception>
#include <string>
#include <vector>

#include "ct2_whisper.h"

namespace {

  void throw_java(JNIEnv* env, const char* message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr)
      env->ThrowNew(cls, message);
  }

  std::string to_string(JNIEnv* env, jstring s) {
    if (s == nullptr)
      return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
  }

  // NewStringUTF wants modified UTF-8; Whisper can produce any code point (emoji, CJK), so go through
  // a byte array and java.lang.String(byte[], "UTF-8"). Invalid sequences (a token cut in the middle of
  // a character) become U+FFFD there instead of crashing the VM.
  jstring utf8_string(JNIEnv* env, const std::string& s) {
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(s.size()));
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte*>(s.data()));
    jclass string_class = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(string_class, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    auto out = static_cast<jstring>(env->NewObject(string_class, ctor, bytes, charset));
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(string_class);
    return out;
  }

}

extern "C" {

JNIEXPORT jstring JNICALL
Java_io_github_remtav_dictee_stt_Ct2Native_buildInfo(JNIEnv* env, jobject) {
  return utf8_string(env, ct2w::build_info());
}

JNIEXPORT jlong JNICALL
Java_io_github_remtav_dictee_stt_Ct2Native_load(JNIEnv* env, jobject, jstring model_dir, jint threads) {
  try {
    return reinterpret_cast<jlong>(new ct2w::Engine(to_string(env, model_dir), threads));
  } catch (const std::exception& e) {
    throw_java(env, e.what());
    return 0;
  }
}

// Returns {text, language, tokens, encoder frames, features ms, encode ms, decode ms, no-speech prob}.
JNIEXPORT jobjectArray JNICALL
Java_io_github_remtav_dictee_stt_Ct2Native_transcribe(JNIEnv* env, jobject, jlong handle, jfloatArray samples,
                                                      jstring language, jint padding_frames) {
  auto* engine = reinterpret_cast<ct2w::Engine*>(handle);
  if (engine == nullptr) {
    throw_java(env, "engine released");
    return nullptr;
  }
  try {
    const jsize n = env->GetArrayLength(samples);
    std::vector<float> audio(static_cast<size_t>(n));
    env->GetFloatArrayRegion(samples, 0, n, audio.data());
    ct2w::Options options;
    options.language = to_string(env, language);
    options.padding_frames = padding_frames;
    const ct2w::Result r = engine->transcribe(audio, options);
    const std::vector<std::string> fields{
      r.text, r.language, std::to_string(r.tokens), std::to_string(r.encoder_frames),
      std::to_string(r.features_ms), std::to_string(r.encode_ms), std::to_string(r.decode_ms),
      std::to_string(r.no_speech_prob)};
    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(fields.size()), string_class, nullptr);
    for (size_t i = 0; i < fields.size(); ++i) {
      jstring s = utf8_string(env, fields[i]);
      env->SetObjectArrayElement(out, static_cast<jsize>(i), s);
      env->DeleteLocalRef(s);
    }
    return out;
  } catch (const std::exception& e) {
    throw_java(env, e.what());
    return nullptr;
  }
}

JNIEXPORT void JNICALL
Java_io_github_remtav_dictee_stt_Ct2Native_release(JNIEnv*, jobject, jlong handle) {
  delete reinterpret_cast<ct2w::Engine*>(handle);
}

}
