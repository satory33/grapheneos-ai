/**
 * whisper_jni.cpp - JNI bridge for whisper.cpp
 * 
 * Provides native speech-to-text functionality for the Android assistant.
 * Runs inference on a background native thread to avoid blocking UI.
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <thread>
#include <mutex>

#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace {
    // Global model context (loaded once)
    whisper_context* g_ctx = nullptr;
    std::mutex g_mutex;
    
    // Default inference parameters
    struct whisper_params {
        int32_t n_threads = 4;
        int32_t offset_ms = 0;
        int32_t duration_ms = 0;
        bool translate = false;
        bool print_special = false;
        bool print_progress = false;
        bool no_timestamps = true;
        std::string language = "en";
    };
    
    whisper_params g_params;
}

extern "C" {

/**
 * Initialize whisper model from file path.
 * @param modelPath Absolute path to GGML model file
 * @return 0 on success, negative error code on failure
 */
JNIEXPORT jint JNICALL
Java_com_vincent_ai_1integrated_1into_1android_audio_WhisperJNI_initModel(
        JNIEnv* env,
        jobject /* this */,
        jstring modelPath) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    // Release existing model if any
    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }
    
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) {
        LOGE("Failed to get model path string");
        return -1;
    }
    
    LOGI("Loading whisper model from: %s", path);
    
    // Initialize model with default parameters
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false; // GPU support requires additional setup
    
    g_ctx = whisper_init_from_file_with_params(path, cparams);
    
    env->ReleaseStringUTFChars(modelPath, path);
    
    if (g_ctx == nullptr) {
        LOGE("Failed to initialize whisper model");
        return -2;
    }
    
    // Set optimal thread count based on available cores
    int n_cores = std::thread::hardware_concurrency();
    g_params.n_threads = std::min(n_cores, 4);
    
    LOGI("Whisper model initialized successfully (threads: %d)", g_params.n_threads);
    return 0;
}

/**
 * Transcribe audio file to text.
 * @param audioPath Path to WAV file (16kHz, 16-bit, mono)
 * @return Transcribed text
 */
JNIEXPORT jstring JNICALL
Java_com_vincent_ai_1integrated_1into_1android_audio_WhisperJNI_transcribe(
        JNIEnv* env,
        jobject /* this */,
        jstring audioPath) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    if (g_ctx == nullptr) {
        LOGE("Model not initialized");
        return env->NewStringUTF("");
    }
    
    const char* path = env->GetStringUTFChars(audioPath, nullptr);
    if (path == nullptr) {
        LOGE("Failed to get audio path string");
        return env->NewStringUTF("");
    }
    
    LOGI("Transcribing audio: %s", path);
    
    // Read WAV file
    std::vector<float> pcm_data;
    {
        // Simple WAV reader (16-bit PCM mono)
        FILE* file = fopen(path, "rb");
        if (!file) {
            LOGE("Failed to open audio file: %s", path);
            env->ReleaseStringUTFChars(audioPath, path);
            return env->NewStringUTF("");
        }
        
        // Skip WAV header (44 bytes for standard PCM)
        fseek(file, 44, SEEK_SET);
        
        // Read 16-bit samples and convert to float
        int16_t sample;
        while (fread(&sample, sizeof(int16_t), 1, file) == 1) {
            pcm_data.push_back(static_cast<float>(sample) / 32768.0f);
        }
        
        fclose(file);
    }
    
    env->ReleaseStringUTFChars(audioPath, path);
    
    if (pcm_data.empty()) {
        LOGE("No audio data read");
        return env->NewStringUTF("");
    }
    
    LOGD("Audio samples: %zu", pcm_data.size());
    
    // Run inference
    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    
    wparams.print_realtime   = false;
    wparams.print_progress   = g_params.print_progress;
    wparams.print_timestamps = !g_params.no_timestamps;
    wparams.print_special    = g_params.print_special;
    wparams.translate        = g_params.translate;
    wparams.language         = g_params.language.c_str();
    wparams.n_threads        = g_params.n_threads;
    wparams.offset_ms        = g_params.offset_ms;
    wparams.duration_ms      = g_params.duration_ms;
    
    // Single segment mode for faster processing
    wparams.single_segment   = true;
    
    if (whisper_full(g_ctx, wparams, pcm_data.data(), pcm_data.size()) != 0) {
        LOGE("Whisper inference failed");
        return env->NewStringUTF("");
    }
    
    // Collect transcription
    std::string result;
    const int n_segments = whisper_full_n_segments(g_ctx);
    
    for (int i = 0; i < n_segments; ++i) {
        const char* text = whisper_full_get_segment_text(g_ctx, i);
        if (text != nullptr) {
            result += text;
        }
    }
    
    LOGI("Transcription complete: %zu chars", result.size());
    
    return env->NewStringUTF(result.c_str());
}

/**
 * Transcribe with custom parameters.
 */
JNIEXPORT jstring JNICALL
Java_com_vincent_ai_1integrated_1into_1android_audio_WhisperJNI_transcribeWithParams(
        JNIEnv* env,
        jobject /* this */,
        jstring audioPath,
        jstring language,
        jboolean translate,
        jint threads) {
    
    // Update parameters
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        
        const char* lang = env->GetStringUTFChars(language, nullptr);
        if (lang != nullptr) {
            g_params.language = lang;
            env->ReleaseStringUTFChars(language, lang);
        }
        
        g_params.translate = translate;
        if (threads > 0) {
            g_params.n_threads = threads;
        }
    }
    
    // Call main transcribe function
    return Java_com_vincent_ai_1integrated_1into_1android_audio_WhisperJNI_transcribe(
            env, nullptr, audioPath);
}

/**
 * Release model resources.
 */
JNIEXPORT void JNICALL
Java_com_vincent_ai_1integrated_1into_1android_audio_WhisperJNI_releaseModel(
        JNIEnv* /* env */,
        jobject /* this */) {
    
    std::lock_guard<std::mutex> lock(g_mutex);
    
    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
        LOGI("Whisper model released");
    }
}

/**
 * Get whisper.cpp version string.
 */
JNIEXPORT jstring JNICALL
Java_com_vincent_ai_1integrated_1into_1android_audio_WhisperJNI_getVersion(
        JNIEnv* env,
        jobject /* this */) {
    
    return env->NewStringUTF(whisper_print_system_info());
}

} // extern "C"
