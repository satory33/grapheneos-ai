package com.satory.graphenosai.audio

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.Locale

/**
 * Android SpeechRecognizer wrapper for voice input.
 * Works with system speech recognition (Google, or alternative providers on GrapheneOS).
 */
class SpeechRecognizerManager(private val context: Context) {

    companion object {
        private const val TAG = "SpeechRecognizerMgr"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    sealed class RecognitionResult {
        data class Partial(val text: String) : RecognitionResult()
        data class Final(val text: String) : RecognitionResult()
        data class Error(val code: Int, val message: String) : RecognitionResult()
        object ReadyForSpeech : RecognitionResult()
        object EndOfSpeech : RecognitionResult()
    }

    fun isAvailable(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return false
        }
        // Check if RECORD_AUDIO permission is granted
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun startListening(): Flow<RecognitionResult> = callbackFlow {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            trySend(RecognitionResult.Error(-1, "Speech recognition not available on this device"))
            close()
            return@callbackFlow
        }
        
        // Check for RECORD_AUDIO permission
        val audioPermissionGranted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        
        if (!audioPermissionGranted) {
            trySend(RecognitionResult.Error(6, "RECORD_AUDIO permission not granted. Please enable in Settings."))
            close()
            return@callbackFlow
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        
        val recognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for speech")
                isListening = true
                trySend(RecognitionResult.ReadyForSpeech)
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Beginning of speech")
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Volume level changed - could be used for UI feedback
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // Raw audio buffer
            }

            override fun onEndOfSpeech() {
                Log.d(TAG, "End of speech")
                isListening = false
                trySend(RecognitionResult.EndOfSpeech)
            }

            override fun onError(error: Int) {
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission not granted. Please enable RECORD_AUDIO permission in Settings."
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected. Please try again."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy. Please try again."
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input detected. Please speak clearly into the microphone."
                    else -> "Unknown error ($error)"
                }
                Log.e(TAG, "Recognition error: $errorMessage")
                isListening = false
                trySend(RecognitionResult.Error(error, errorMessage))
                close()
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val finalText = matches?.firstOrNull() ?: ""
                Log.i(TAG, "Final result: $finalText")
                isListening = false
                trySend(RecognitionResult.Final(finalText))
                close()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partialText = matches?.firstOrNull() ?: ""
                if (partialText.isNotEmpty()) {
                    Log.d(TAG, "Partial result: $partialText")
                    trySend(RecognitionResult.Partial(partialText))
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                Log.d(TAG, "Event: $eventType")
            }
        }

        speechRecognizer?.setRecognitionListener(listener)
        speechRecognizer?.startListening(recognitionIntent)
        Log.i(TAG, "Started listening")

        awaitClose {
            Log.d(TAG, "Closing speech recognizer")
            stopListening()
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
            isListening = false
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping speech recognizer", e)
        }
    }
    
    fun destroy() {
        stopListening()
    }

    fun isCurrentlyListening(): Boolean = isListening
}
