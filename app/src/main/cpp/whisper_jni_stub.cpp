/**
 * whisper_jni_stub.cpp - Stub JNI implementation when whisper.cpp is not available
 * 
 * This stub allows the app to compile and run without the native whisper library.
 * The app will fall back to cloud ASR when these functions return failure codes.
 */

#include <jni.h>
#include <android/log.h>
#include <string>

#define LOG_TAG "WhisperJNI"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jint JNICALL
Java_com_vincent_ai_1integrated_1into_1android_audio_WhisperJNI_initModel(
        JNIEnv* env,
        jobject /* this */,
        jstring modelPath) {
    LOGW("Whisper stub: initModel called - native library not available");
    return -1; // Return error to indicate initialization failed
}

JNIEXPORT jstring JNICALL
Java_com_vincent_ai_1integrated_1into_1android_audio_WhisperJNI_transcribe(
        JNIEnv* env,
        jobject /* this */,
        jstring audioPath) {
    LOGW("Whisper stub: transcribe called - native library not available");
    return env->NewStringUTF(""); // Return empty string
}

JNIEXPORT jstring JNICALL
Java_com_vincent_ai_1integrated_1into_1android_audio_WhisperJNI_transcribeWithParams(
        JNIEnv* env,
        jobject /* this */,
        jstring audioPath,
        jstring language,
        jboolean translate,
        jint threads) {
    LOGW("Whisper stub: transcribeWithParams called - native library not available");
    return env->NewStringUTF("");
}

JNIEXPORT void JNICALL
Java_com_vincent_ai_1integrated_1into_1android_audio_WhisperJNI_releaseModel(
        JNIEnv* /* env */,
        jobject /* this */) {
    LOGW("Whisper stub: releaseModel called");
}

JNIEXPORT jstring JNICALL
Java_com_vincent_ai_1integrated_1into_1android_audio_WhisperJNI_getVersion(
        JNIEnv* env,
        jobject /* this */) {
    return env->NewStringUTF("stub-1.0 (whisper.cpp not available)");
}

} // extern "C"
