package com.satory.graphenosai.llm

import android.util.Log
import com.satory.graphenosai.security.SecureKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * GitHub Copilot API client with streaming support.
 * Uses https://api.githubcopilot.com endpoint.
 */
class CopilotClient(
    private val keyManager: SecureKeyManager,
    private var modelOverride: String? = null,
    private var systemPromptOverride: String? = null
) {

    companion object {
        private const val TAG = "CopilotClient"
        private const val BASE_URL = "https://api.githubcopilot.com/chat/completions"
        private const val TIMEOUT_MS = 30000
        private const val MAX_TOKENS = 4096
        private const val TEMPERATURE = 0.3f
        
        private const val DEFAULT_MODEL = "gpt-4o"
        
        // Available Copilot models
        val AVAILABLE_MODELS = listOf(
            "gpt-4o",
            "gpt-4o-mini",
            "gpt-4.1",
            "claude-3.5-sonnet",
            "claude-sonnet-4",
            "gemini-2.0-flash-001",
            "o1",
            "o1-mini",
            "o3-mini"
        )
        
        const val DEFAULT_SYSTEM_PROMPT = """You are a helpful AI assistant on GrapheneOS (privacy-focused Android).
Keep responses concise for mobile reading.
Use markdown for formatting: **bold**, *italic*, `code`, [links](url).
If unsure, say so honestly - but use web search first to find accurate info!
ALWAYS use web search to get current, accurate information for any factual questions.
When you find useful links, include them as markdown links.
If the user asks you to open a link or website, include the URL like this: [OPEN_URL:https://example.com] and it will automatically open in their browser.
Respond in the same language as the user.
For any questions about current events, facts, prices, news, weather - search the web first."""
    }

    private var currentModel: String = modelOverride ?: DEFAULT_MODEL
    private var currentSystemPrompt: String = systemPromptOverride ?: DEFAULT_SYSTEM_PROMPT
    
    // Chat session for context
    val chatSession = ChatSession()
    
    fun setModel(model: String) {
        currentModel = model
        Log.i(TAG, "Copilot model set to: $model")
    }
    
    fun setSystemPrompt(prompt: String) {
        currentSystemPrompt = prompt
    }
    
    fun clearSession() {
        chatSession.clear()
    }

    /**
     * Perform a single non-streaming completion.
     * Useful for classification or short tasks without using the chat session.
     */
    suspend fun completion(
        userQuery: String,
        systemPrompt: String? = null
    ): String = withContext(Dispatchers.IO) {
        val effectiveSystemPrompt = systemPrompt ?: currentSystemPrompt
        
        val token = keyManager.getCopilotToken()
        if (token.isNullOrBlank()) {
             Log.e(TAG, "Copilot token not configured")
             return@withContext ""
        }

        val messages = JSONArray()
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", effectiveSystemPrompt)
        })
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", userQuery)
        })
        
        val requestBody = buildRequestBody(messages, stream = false)
        val connection = createConnection(token, isVisionRequest = false)
        
        try {
            connection.outputStream.use { os ->
                os.write(requestBody.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpsURLConnection.HTTP_OK) {
                val errorBody = connection.errorStream?.bufferedReader()?.readText()
                Log.e(TAG, "Completion error $responseCode: $errorBody")
                return@withContext ""
            }

            val responseBody = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(responseBody)
            val choices = json.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                 return@withContext choices.getJSONObject(0).getJSONObject("message").getString("content")
            }
            return@withContext ""
        } catch (e: Exception) {
            Log.e(TAG, "Completion exception", e)
            return@withContext ""
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Stream completion WITHOUT adding user message (already added externally).
     * Used when user message was already added to chatSession.
     */
    fun streamCompletion(
        userQuery: String,
        imageBase64: String? = null
    ): Flow<String> = flow {
        val token = keyManager.getCopilotToken()
        if (token.isNullOrBlank()) {
            emit("[GitHub Copilot token not configured. Add your token in Settings.]")
            return@flow
        }
        
        val hasImage = imageBase64 != null
        val messages = chatSession.getMessagesForApi(currentSystemPrompt, hasImage)
        val requestBody = buildRequestBody(messages, stream = true)
        
        Log.i(TAG, "Streaming with Copilot (${chatSession.messageCount()} messages, hasImage=$hasImage)")
        Log.d(TAG, "Request body: $requestBody")

        val connection = createConnection(token, hasImage)
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
                Log.e(TAG, "Copilot API error $responseCode: $errorBody")
                
                val errorMessage = when (responseCode) {
                    401 -> "Invalid or expired Copilot token. Please update in Settings."
                    403 -> "Access denied. Make sure you have an active GitHub Copilot subscription."
                    429 -> "Rate limit exceeded. Please wait and try again."
                    else -> "Copilot error $responseCode: $errorBody"
                }
                emit("[$errorMessage]")
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
                                if (delta != null) {
                                    val content: String? = delta.optString("content", null)
                                    if (content != null && content.isNotEmpty() && content != "null") {
                                        responseBuilder.append(content)
                                        emit(content)
                                    }
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
     * Stream completion with a direct query (e.g., with search context).
     * Uses chat history but replaces the last user message with the enhanced query.
     * This way the chat history shows original question, but AI sees search results.
     */
    fun streamCompletionDirect(
        enhancedQuery: String,
        imageBase64: String? = null
    ): Flow<String> = flow {
        val token = keyManager.getCopilotToken()
        if (token.isNullOrBlank()) {
            emit("[GitHub Copilot token not configured. Add your token in Settings.]")
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
        Log.i(TAG, "Streaming direct query with Copilot (${allMessages.size} history messages)")

        val connection = createConnection(token, imageBase64 != null)
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
                Log.e(TAG, "Copilot API error $responseCode: $errorBody")
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
                                val content: String? = delta?.optString("content", null)
                                if (content != null && content.isNotEmpty() && content != "null") {
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
            
            // Add assistant response to session (needed for chat history)
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
        val token = keyManager.getCopilotToken()
        if (token.isNullOrBlank()) {
            emit("[GitHub Copilot token not configured. Add your token in Settings.]")
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
        Log.i(TAG, "Streaming with search context (Copilot)")

        val connection = createConnection(token, imageBase64 != null)
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
                Log.e(TAG, "Copilot API error $responseCode: $errorBody")
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
                                val content: String? = delta?.optString("content", null)
                                if (content != null && content.isNotEmpty() && content != "null") {
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
        val token = keyManager.getCopilotToken()
        if (token.isNullOrBlank()) {
            emit("[GitHub Copilot token not configured. Add your token in Settings.]")
            return@flow
        }

        // Add user message to session
        chatSession.addUserMessage(userQuery, imageBase64)
        
        val hasImage = imageBase64 != null
        val messages = chatSession.getMessagesForApi(currentSystemPrompt, hasImage)
        val requestBody = buildRequestBody(messages, stream = true)
        
        Log.i(TAG, "Streaming with Copilot (${chatSession.messageCount()} messages)")

        val connection = createConnection(token, hasImage)
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
                Log.e(TAG, "Copilot API error $responseCode: $errorBody")
                
                // Parse error for better message
                val errorMessage = when (responseCode) {
                    401 -> "Invalid or expired Copilot token. Please update in Settings."
                    403 -> "Access denied. Make sure you have an active GitHub Copilot subscription."
                    429 -> "Rate limit exceeded. Please wait and try again."
                    else -> "Copilot error $responseCode: $errorBody"
                }
                emit("[$errorMessage]")
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
                                if (delta != null) {
                                    val content: String? = delta.optString("content", null)
                                    if (content != null && content.isNotEmpty() && content != "null") {
                                        responseBuilder.append(content)
                                        emit(content)
                                    }
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

    private fun createConnection(token: String, isVisionRequest: Boolean = false): HttpsURLConnection {
        val url = URL(BASE_URL)
        return (url.openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS * 2
            doOutput = true
            
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Editor-Version", "vscode/1.95.0")
            setRequestProperty("Editor-Plugin-Version", "copilot-chat/0.22.0")
            setRequestProperty("Copilot-Integration-Id", "vscode-chat")
            setRequestProperty("User-Agent", "GitHubCopilotChat/0.22.0")
            
            // Required header for vision requests
            if (isVisionRequest) {
                setRequestProperty("Copilot-Vision-Request", "true")
                Log.d(TAG, "Added Copilot-Vision-Request header")
            }
        }
    }

    private fun buildRequestBody(messages: JSONArray, stream: Boolean): String {
        return JSONObject().apply {
            put("model", currentModel)
            put("messages", messages)
            put("max_tokens", MAX_TOKENS)
            put("temperature", TEMPERATURE.toDouble())
            put("stream", stream)
            put("n", 1)
        }.toString()
    }
}
