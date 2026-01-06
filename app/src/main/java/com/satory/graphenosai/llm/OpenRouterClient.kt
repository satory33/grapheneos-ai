package com.satory.graphenosai.llm

import android.util.Log
import com.satory.graphenosai.security.SecureKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * OpenRouter API client with streaming support, chat sessions, and vision capability.
 */
class OpenRouterClient(
    private val keyManager: SecureKeyManager,
    private var modelOverride: String? = null,
    private var systemPromptOverride: String? = null
) {

    companion object {
        private const val TAG = "OpenRouterClient"
        private const val BASE_URL = "https://openrouter.ai/api/v1/chat/completions"
        private const val TIMEOUT_MS = 30000
        private const val MAX_TOKENS = 4096
        private const val TEMPERATURE = 0.3f
        
        private const val DEFAULT_MODEL = "openai/gpt-4o-mini"
        
        // Vision-capable models
        private val VISION_MODELS = listOf(
            "openai/gpt-4o",
            "openai/gpt-4o-mini", 
            "anthropic/claude-3.5-sonnet",
            "anthropic/claude-3-haiku",
            "google/gemini-flash-1.5",
            "google/gemini-pro-1.5"
        )
        
        private val FALLBACK_MODELS = listOf(
            "openai/gpt-4o-mini",
            "google/gemini-flash-1.5",
            "meta-llama/llama-3-8b-instruct"
        )
        
        const val DEFAULT_SYSTEM_PROMPT = """You are a helpful AI assistant on GrapheneOS (privacy-focused Android).
Keep responses concise for mobile reading.
Use markdown for formatting.
If unsure, say so honestly.
When you find useful links, include them.
If user asks to open a link, respond with the URL like: [OPEN_URL:https://example.com]
You can analyze screenshots when the user shares their screen.
Respond in the same language as the user."""
    }

    private var currentModel: String = modelOverride ?: DEFAULT_MODEL
    private var currentSystemPrompt: String = systemPromptOverride ?: DEFAULT_SYSTEM_PROMPT
    
    // Chat session for context
    val chatSession = ChatSession()
    
    fun setModel(model: String) {
        currentModel = model
        Log.i(TAG, "Model set to: $model")
    }
    
    fun setSystemPrompt(prompt: String) {
        currentSystemPrompt = prompt
    }
    
    fun isVisionCapable(): Boolean = currentModel in VISION_MODELS
    
    fun clearSession() {
        chatSession.clear()
    }

    /**
     * Stream completion WITHOUT adding user message (already added externally).
     * Used when user message was already added to chatSession.
     */
    fun streamCompletion(
        userQuery: String,
        imageBase64: String? = null
    ): Flow<String> = flow {
        val apiKey = keyManager.getOpenRouterApiKey()
        if (apiKey.isNullOrBlank()) {
            emit("[API key not configured. Add your key in Settings.]")
            return@flow
        }
        
        val hasImage = imageBase64 != null
        val messages = chatSession.getMessagesForApi(currentSystemPrompt, hasImage)
        val requestBody = buildRequestBody(messages, stream = true)
        
        Log.i(TAG, "Streaming (${chatSession.messageCount()} messages, no user add)")

        val connection = createConnection(apiKey)
        val responseBuilder = StringBuilder()
        
        try {
            connection.outputStream.use { os ->
                os.write(requestBody.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            
            if (responseCode != HttpsURLConnection.HTTP_OK) {
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                } catch (e: Exception) { "Error: ${e.message}" }
                Log.e(TAG, "API error $responseCode: $errorBody")
                emit("[Error $responseCode: $errorBody]")
                return@flow
            }

            BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line!!.startsWith("data: ")) {
                        val data = line!!.removePrefix("data: ").trim()
                        
                        if (data == "[DONE]") break
                        if (data.isEmpty()) continue
                        
                        try {
                            val json = JSONObject(data)
                            val choices = json.optJSONArray("choices")
                            if (choices != null && choices.length() > 0) {
                                val delta = choices.getJSONObject(0).optJSONObject("delta")
                                val content = delta?.optString("content", "") ?: ""
                                if (content.isNotEmpty()) {
                                    responseBuilder.append(content)
                                    emit(content)
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Parse error: $data")
                        }
                    }
                }
            }
            
            // Add assistant response to session
            if (responseBuilder.isNotEmpty()) {
                chatSession.addAssistantMessage(responseBuilder.toString())
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Stream error", e)
            emit("[Error: ${e.message}]")
        } finally {
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Stream completion with a direct query (e.g., with search context).
     * Uses chat history but replaces the last user message with the enhanced query.
     * This way the chat history shows original question, but AI sees search results.
     */
    fun streamCompletionDirect(
        enhancedQuery: String,
        imageBase64: String? = null
    ): Flow<String> = flow {
        val apiKey = keyManager.getOpenRouterApiKey()
        if (apiKey.isNullOrBlank()) {
            emit("[API key not configured. Add your key in Settings.]")
            return@flow
        }
        
        // Build messages with history, but use enhanced query for last user message
        val messages = JSONArray()
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", currentSystemPrompt)
        })
        
        // Add all history except the last user message
        val allMessages = chatSession.getAllMessages()
        for (i in 0 until (allMessages.size - 1).coerceAtLeast(0)) {
            val msg = allMessages[i]
            messages.put(JSONObject().apply {
                put("role", msg.role)
                put("content", msg.content)
            })
        }
        
        // Add enhanced query instead of original last user message
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", enhancedQuery)
        })
        
        val requestBody = buildRequestBody(messages, stream = true)
        Log.i(TAG, "Streaming direct query with OpenRouter (${allMessages.size} history messages)")

        val connection = createConnection(apiKey)
        val responseBuilder = StringBuilder()
        
        try {
            connection.outputStream.use { os ->
                os.write(requestBody.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            
            if (responseCode != HttpsURLConnection.HTTP_OK) {
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                } catch (e: Exception) { "Error: ${e.message}" }
                Log.e(TAG, "API error $responseCode: $errorBody")
                emit("[Error $responseCode: $errorBody]")
                return@flow
            }

            BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line!!.startsWith("data: ")) {
                        val data = line!!.removePrefix("data: ").trim()
                        
                        if (data == "[DONE]") break
                        if (data.isEmpty()) continue
                        
                        try {
                            val json = JSONObject(data)
                            val choices = json.optJSONArray("choices")
                            if (choices != null && choices.length() > 0) {
                                val delta = choices.getJSONObject(0).optJSONObject("delta")
                                val content = delta?.optString("content", "") ?: ""
                                if (content.isNotEmpty()) {
                                    responseBuilder.append(content)
                                    emit(content)
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Parse error: $data")
                        }
                    }
                }
            }
            
            // Add assistant response to session
            if (responseBuilder.isNotEmpty()) {
                chatSession.addAssistantMessage(responseBuilder.toString())
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Streaming error", e)
            emit("[Error: ${e.message}]")
        } finally {
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Stream completion for search queries - uses enhanced query directly without adding to history.
     * This is used when we have web search context embedded in the query.
     */
    fun streamCompletionWithSearch(
        enhancedQuery: String,
        imageBase64: String? = null
    ): Flow<String> = flow {
        val apiKey = keyManager.getOpenRouterApiKey()
        if (apiKey.isNullOrBlank()) {
            emit("[API key not configured. Add your key in Settings.]")
            return@flow
        }
        
        // Create a temporary message list with the enhanced query
        val messages = JSONArray()
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", currentSystemPrompt)
        })
        
        // Add previous conversation (without the last user message which is enhanced)
        val allMessages = chatSession.getAllMessages()
        for (i in 0 until (allMessages.size - 1).coerceAtLeast(0)) {
            val msg = allMessages[i]
            messages.put(JSONObject().apply {
                put("role", msg.role)
                put("content", msg.content)
            })
        }
        
        // Add enhanced query with search context
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", enhancedQuery)
        })
        
        val requestBody = buildRequestBody(messages, stream = true)
        Log.i(TAG, "Streaming with search context (OpenRouter)")

        val connection = createConnection(apiKey)
        val responseBuilder = StringBuilder()
        
        try {
            connection.outputStream.use { os ->
                os.write(requestBody.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            
            if (responseCode != HttpsURLConnection.HTTP_OK) {
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                } catch (e: Exception) { "Error: ${e.message}" }
                Log.e(TAG, "API error $responseCode: $errorBody")
                emit("[Error $responseCode: $errorBody]")
                return@flow
            }

            BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line!!.startsWith("data: ")) {
                        val data = line!!.removePrefix("data: ").trim()
                        
                        if (data == "[DONE]") break
                        if (data.isEmpty()) continue
                        
                        try {
                            val json = JSONObject(data)
                            val choices = json.optJSONArray("choices")
                            if (choices != null && choices.length() > 0) {
                                val delta = choices.getJSONObject(0).optJSONObject("delta")
                                val content = delta?.optString("content", "") ?: ""
                                if (content.isNotEmpty()) {
                                    responseBuilder.append(content)
                                    emit(content)
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Parse error: $data")
                        }
                    }
                }
            }
            
            // Add assistant response to session
            if (responseBuilder.isNotEmpty()) {
                chatSession.addAssistantMessage(responseBuilder.toString())
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Streaming error", e)
            emit("[Error: ${e.message}]")
        } finally {
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Stream completion with chat session context.
     */
    fun streamCompletionWithSession(
        userQuery: String,
        imageBase64: String? = null
    ): Flow<String> = flow {
        val apiKey = keyManager.getOpenRouterApiKey()
        if (apiKey.isNullOrBlank()) {
            emit("[API key not configured. Add your key in Settings.]")
            return@flow
        }

        // Add user message to session
        chatSession.addUserMessage(userQuery, imageBase64)
        
        val hasImage = imageBase64 != null
        val messages = chatSession.getMessagesForApi(currentSystemPrompt, hasImage)
        val requestBody = buildRequestBody(messages, stream = true)
        
        Log.i(TAG, "Streaming with session (${chatSession.messageCount()} messages)")

        val connection = createConnection(apiKey)
        val responseBuilder = StringBuilder()
        
        try {
            connection.outputStream.use { os ->
                os.write(requestBody.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            
            if (responseCode != HttpsURLConnection.HTTP_OK) {
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                } catch (e: Exception) { "Error: ${e.message}" }
                Log.e(TAG, "API error $responseCode: $errorBody")
                emit("[Error $responseCode: $errorBody]")
                return@flow
            }

            BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line!!.startsWith("data: ")) {
                        val data = line!!.removePrefix("data: ").trim()
                        
                        if (data == "[DONE]") break
                        if (data.isEmpty()) continue
                        
                        try {
                            val json = JSONObject(data)
                            val choices = json.optJSONArray("choices")
                            if (choices != null && choices.length() > 0) {
                                val delta = choices.getJSONObject(0).optJSONObject("delta")
                                val content = delta?.optString("content", "") ?: ""
                                if (content.isNotEmpty()) {
                                    responseBuilder.append(content)
                                    emit(content)
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Parse error: $data")
                        }
                    }
                }
            }
            
            // Add assistant response to session
            if (responseBuilder.isNotEmpty()) {
                chatSession.addAssistantMessage(responseBuilder.toString())
            }
            
        } catch (e: java.net.UnknownHostException) {
            emit("[No internet connection]")
        } catch (e: java.net.SocketTimeoutException) {
            emit("[Request timed out]")
        } catch (e: Exception) {
            Log.e(TAG, "Stream error", e)
            emit("[Error: ${e.message}]")
        } finally {
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Legacy stream completion (without session).
     */
    fun streamCompletion(
        userQuery: String,
        context: String? = null,
        systemPrompt: String? = null
    ): Flow<String> = flow {
        val effectiveSystemPrompt = systemPrompt ?: currentSystemPrompt
        
        val apiKey = keyManager.getOpenRouterApiKey()
        if (apiKey.isNullOrBlank()) {
            throw IllegalStateException("OpenRouter API key not configured")
        }

        val messages = buildMessages(effectiveSystemPrompt, context, userQuery)
        val requestBody = buildRequestBody(messages, stream = true)

        val connection = createConnection(apiKey)
        
        try {
            connection.outputStream.use { os ->
                os.write(requestBody.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            
            if (responseCode != HttpsURLConnection.HTTP_OK) {
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.readText() ?: "No error body"
                } catch (e: Exception) { "Could not read error" }
                emit("[API Error $responseCode: $errorBody]")
                return@flow
            }

            BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line!!.startsWith("data: ")) {
                        val data = line!!.removePrefix("data: ").trim()
                        
                        if (data == "[DONE]") break
                        if (data.isEmpty()) continue
                        
                        try {
                            val json = JSONObject(data)
                            val choices = json.optJSONArray("choices")
                            if (choices != null && choices.length() > 0) {
                                val delta = choices.getJSONObject(0).optJSONObject("delta")
                                val content = delta?.optString("content", "") ?: ""
                                if (content.isNotEmpty()) emit(content)
                            }
                        } catch (e: Exception) { }
                    }
                }
            }
        } catch (e: java.net.UnknownHostException) {
            emit("[Network error: No internet]")
        } catch (e: java.net.SocketTimeoutException) {
            emit("[Request timed out]")
        } catch (e: Exception) {
            emit("[Error: ${e.message}]")
        } finally {
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun completion(
        userQuery: String,
        context: String? = null,
        systemPrompt: String? = null
    ): String = withContext(Dispatchers.IO) {
        val effectiveSystemPrompt = systemPrompt ?: currentSystemPrompt
        
        val apiKey = keyManager.getOpenRouterApiKey()
            ?: throw IllegalStateException("API key not configured")

        val messages = buildMessages(effectiveSystemPrompt, context, userQuery)
        val requestBody = buildRequestBody(messages, stream = false)

        val connection = createConnection(apiKey)
        
        try {
            connection.outputStream.use { os ->
                os.write(requestBody.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpsURLConnection.HTTP_OK) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText()
                throw OpenRouterException(responseCode, errorBody ?: "Unknown error")
            }

            val responseBody = connection.inputStream.bufferedReader().readText()
            parseCompletionResponse(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun createConnection(apiKey: String): HttpsURLConnection {
        val url = URL(BASE_URL)
        return (url.openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS * 2
            doOutput = true
            
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("HTTP-Referer", "https://github.com/user/ai-assistant")
            setRequestProperty("X-Title", "GrapheneOS AI Assistant")
            setRequestProperty("User-Agent", "AIAssistant/1.0")
        }
    }

    private fun buildMessages(
        systemPrompt: String,
        context: String?,
        userQuery: String
    ): JSONArray {
        val messages = JSONArray()
        
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })
        
        if (!context.isNullOrBlank()) {
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", "Context:\n$context")
            })
        }
        
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", sanitizeUserInput(userQuery))
        })
        
        return messages
    }

    private fun buildRequestBody(messages: JSONArray, stream: Boolean): String {
        return JSONObject().apply {
            put("model", currentModel)
            put("messages", messages)
            put("max_tokens", MAX_TOKENS)
            put("temperature", TEMPERATURE.toDouble())
            put("stream", stream)
            put("safe_mode", true)
            put("provider", JSONObject().apply {
                put("allow_fallbacks", true)
            })
        }.toString()
    }

    private fun parseCompletionResponse(responseBody: String): String {
        val json = JSONObject(responseBody)
        val choices = json.getJSONArray("choices")
        
        if (choices.length() == 0) {
            throw OpenRouterException(-1, "No choices in response")
        }
        
        val message = choices.getJSONObject(0).getJSONObject("message")
        return message.getString("content")
    }

    private fun sanitizeUserInput(input: String): String {
        var sanitized = input
        sanitized = sanitized.replace(Regex("[a-f0-9]{16}"), "[ID]")
        sanitized = sanitized.replace(Regex("\\+?\\d{10,15}"), "[PHONE]")
        sanitized = sanitized.replace(
            Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"),
            "[EMAIL]"
        )
        sanitized = sanitized.replace(Regex("\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}"), "[CARD]")
        
        if (sanitized.length > 4000) {
            sanitized = sanitized.take(4000) + "..."
        }
        
        return sanitized.trim()
    }

    fun rotateFallbackModel() {
        val currentIndex = FALLBACK_MODELS.indexOf(currentModel)
        currentModel = if (currentIndex < 0 || currentIndex >= FALLBACK_MODELS.size - 1) {
            FALLBACK_MODELS.first()
        } else {
            FALLBACK_MODELS[currentIndex + 1]
        }
        Log.i(TAG, "Rotated to fallback model: $currentModel")
    }
}

class OpenRouterException(val code: Int, message: String) : Exception("OpenRouter API error ($code): $message")
