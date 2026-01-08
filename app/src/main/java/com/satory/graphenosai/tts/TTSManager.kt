package com.satory.graphenosai.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * TTS Manager using Android's built-in TextToSpeech engine.
 * Falls back to on-device synthesis when available.
 */
class TTSManager(context: Context) {

    companion object {
        private const val TAG = "TTSManager"
        private const val UTTERANCE_ID_PREFIX = "assistant_tts_"
        
        /**
         * Check if TTS is available on this device without initializing it.
         */
        fun isTTSAvailable(context: Context): Boolean {
            return try {
                val engines = TextToSpeech(context, null).engines
                engines.isNotEmpty()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check TTS availability", e)
                false
            }
        }
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var utteranceCounter = 0

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                isInitialized = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                
                if (isInitialized) {
                    // Configure for optimal speech
                    tts?.setSpeechRate(1.0f)
                    tts?.setPitch(1.0f)
                    Log.i(TAG, "TTS initialized successfully")
                } else {
                    Log.w(TAG, "TTS language not supported")
                }
            } else {
                Log.e(TAG, "TTS initialization failed: $status")
            }
        }
    }
    
    /**
     * Check if TTS is initialized and ready to use.
     */
    fun isAvailable(): Boolean = isInitialized

    /**
     * Speak text asynchronously.
     */
    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        if (!isInitialized) {
            Log.w(TAG, "TTS not initialized")
            return
        }

        val utteranceId = "${UTTERANCE_ID_PREFIX}${utteranceCounter++}"
        
        // Split long text into chunks to avoid TTS limits
        val chunks = splitIntoChunks(text, 4000)
        
        chunks.forEachIndexed { index, chunk ->
            val mode = if (index == 0) queueMode else TextToSpeech.QUEUE_ADD
            tts?.speak(chunk, mode, null, "$utteranceId-$index")
        }
    }

    /**
     * Speak text and suspend until complete.
     */
    suspend fun speakAndWait(text: String): Boolean = suspendCancellableCoroutine { cont ->
        if (!isInitialized) {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }

        val utteranceId = "${UTTERANCE_ID_PREFIX}${utteranceCounter++}"
        val chunks = splitIntoChunks(text, 4000)
        val totalChunks = chunks.size
        var completedChunks = 0

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                Log.d(TAG, "TTS started: $id")
            }

            override fun onDone(id: String?) {
                if (id?.startsWith(utteranceId) == true) {
                    completedChunks++
                    if (completedChunks >= totalChunks) {
                        cont.resume(true)
                    }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                if (id?.startsWith(utteranceId) == true) {
                    Log.e(TAG, "TTS error: $id")
                    cont.resume(false)
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId?.startsWith(this@TTSManager.toString()) == true) {
                    Log.e(TAG, "TTS error $errorCode: $utteranceId")
                    cont.resume(false)
                }
            }
        })

        chunks.forEachIndexed { index, chunk ->
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts?.speak(chunk, mode, null, "$utteranceId-$index")
        }

        cont.invokeOnCancellation {
            stop()
        }
    }

    /**
     * Stop ongoing speech.
     */
    fun stop() {
        tts?.stop()
    }

    /**
     * Check if currently speaking.
     */
    fun isSpeaking(): Boolean {
        return tts?.isSpeaking == true
    }

    /**
     * Set speech rate (0.5 to 2.0, 1.0 is normal).
     */
    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
    }

    /**
     * Set pitch (0.5 to 2.0, 1.0 is normal).
     */
    fun setPitch(pitch: Float) {
        tts?.setPitch(pitch.coerceIn(0.5f, 2.0f))
    }

    /**
     * Get available TTS engines.
     */
    fun getAvailableEngines(): List<TextToSpeech.EngineInfo> {
        return tts?.engines ?: emptyList()
    }

    /**
     * Check if a specific language is available.
     */
    fun isLanguageAvailable(locale: Locale): Boolean {
        val result = tts?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
        return result >= TextToSpeech.LANG_AVAILABLE
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    private fun splitIntoChunks(text: String, maxLength: Int): List<String> {
        if (text.length <= maxLength) {
            return listOf(text)
        }

        val chunks = mutableListOf<String>()
        var remaining = text

        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxLength) {
                chunks.add(remaining)
                break
            }

            // Try to split at sentence boundary
            var splitIndex = remaining.lastIndexOf(". ", maxLength)
            if (splitIndex < maxLength / 2) {
                splitIndex = remaining.lastIndexOf(" ", maxLength)
            }
            if (splitIndex < maxLength / 2) {
                splitIndex = maxLength
            }

            chunks.add(remaining.substring(0, splitIndex + 1).trim())
            remaining = remaining.substring(splitIndex + 1).trim()
        }

        return chunks
    }
}
