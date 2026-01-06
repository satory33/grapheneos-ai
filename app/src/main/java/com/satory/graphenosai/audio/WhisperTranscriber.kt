package com.satory.graphenosai.audio

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * OpenAI Whisper API client for high-quality cloud speech-to-text.
 * Requires OpenRouter API key (which provides access to Whisper).
 */
class WhisperTranscriber(
    private val apiKeyProvider: () -> String?
) {
    companion object {
        private const val TAG = "WhisperTranscriber"
        private const val WHISPER_URL = "https://api.openai.com/v1/audio/transcriptions"
        private const val GROQ_WHISPER_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        private const val TIMEOUT_MS = 60000
    }
    
    enum class Provider {
        OPENAI,  // Original OpenAI Whisper
        GROQ     // Groq's Whisper (faster, free tier available)
    }
    
    var provider: Provider = Provider.GROQ
    
    /**
     * Transcribe audio file using Whisper API.
     */
    suspend fun transcribe(
        audioFile: File,
        language: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            return@withContext Result.failure(Exception("API key not configured"))
        }
        
        try {
            val url = when (provider) {
                Provider.OPENAI -> URL(WHISPER_URL)
                Provider.GROQ -> URL(GROQ_WHISPER_URL)
            }
            
            val connection = url.openConnection() as HttpsURLConnection
            val boundary = "----WebKitFormBoundary${System.currentTimeMillis()}"
            
            connection.apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            
            connection.outputStream.use { output ->
                val writer = output.bufferedWriter()
                
                // Model field
                writer.write("--$boundary\r\n")
                writer.write("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
                writer.write(when (provider) {
                    Provider.OPENAI -> "whisper-1"
                    Provider.GROQ -> "whisper-large-v3"
                })
                writer.write("\r\n")
                
                // Language field (optional)
                language?.let {
                    writer.write("--$boundary\r\n")
                    writer.write("Content-Disposition: form-data; name=\"language\"\r\n\r\n")
                    writer.write(it)
                    writer.write("\r\n")
                }
                
                // Response format
                writer.write("--$boundary\r\n")
                writer.write("Content-Disposition: form-data; name=\"response_format\"\r\n\r\n")
                writer.write("json")
                writer.write("\r\n")
                
                // Audio file
                writer.write("--$boundary\r\n")
                writer.write("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n")
                writer.write("Content-Type: audio/wav\r\n\r\n")
                writer.flush()
                
                audioFile.inputStream().use { input ->
                    input.copyTo(output)
                }
                
                writer.write("\r\n--$boundary--\r\n")
                writer.flush()
            }
            
            val responseCode = connection.responseCode
            
            if (responseCode != 200) {
                val error = try {
                    connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                } catch (e: Exception) { "Error $responseCode" }
                Log.e(TAG, "Whisper API error: $error")
                return@withContext Result.failure(Exception("Transcription failed: $responseCode"))
            }
            
            val response = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(response)
            val text = json.optString("text", "").trim()
            
            if (text.isBlank()) {
                return@withContext Result.failure(Exception("No speech detected"))
            }
            
            Log.i(TAG, "Transcription successful: ${text.take(50)}...")
            Result.success(text)
            
        } catch (e: Exception) {
            Log.e(TAG, "Whisper transcription error", e)
            Result.failure(e)
        }
    }
}
