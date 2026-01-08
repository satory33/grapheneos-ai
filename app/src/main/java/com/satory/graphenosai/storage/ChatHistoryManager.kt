package com.satory.graphenosai.storage

import android.content.Context
import android.util.Log
import com.satory.graphenosai.llm.ChatSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manages chat history persistence on device.
 * Each chat session is saved as a JSON file.
 */
class ChatHistoryManager(private val context: Context) {
    
    companion object {
        private const val TAG = "ChatHistoryManager"
        private const val HISTORY_DIR = "chat_history"
        private const val MAX_SAVED_CHATS = 50
    }
    
    data class ChatSummary(
        val id: String,
        val title: String,
        val timestamp: Long,
        val messageCount: Int,
        val preview: String
    )
    
    private val historyDir: File
        get() = File(context.filesDir, HISTORY_DIR).also { 
            if (!it.exists()) it.mkdirs() 
        }
    
    /**
     * Save current chat session.
     */
    fun saveChat(messages: List<ChatSession.Message>, title: String? = null): String {
        val chatId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        
        // Generate title from first user message if not provided
        val chatTitle = title ?: messages.firstOrNull { it.role == "user" }?.content?.take(50)?.let {
            if (it.length >= 50) "$it..." else it
        } ?: "Chat ${SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(timestamp))}"
        
        val chatJson = JSONObject().apply {
            put("id", chatId)
            put("title", chatTitle)
            put("timestamp", timestamp)
            put("messages", JSONArray().apply {
                messages.forEach { msg ->
                    put(JSONObject().apply {
                        put("role", msg.role)
                        put("content", msg.content)
                        put("timestamp", msg.timestamp)
                        // Save images in history (compressed as Base64)
                        msg.imageBase64?.let { put("imageBase64", it) }
                    })
                }
            })
        }
        
        val file = File(historyDir, "$chatId.json")
        file.writeText(chatJson.toString(2))
        
        Log.i(TAG, "Saved chat $chatId with ${messages.size} messages")
        
        // Cleanup old chats
        cleanupOldChats()
        
        return chatId
    }
    
    /**
     * Update existing chat with new messages.
     */
    fun updateChat(chatId: String, messages: List<ChatSession.Message>): Boolean {
        val file = File(historyDir, "$chatId.json")
        if (!file.exists()) {
            Log.w(TAG, "Cannot update non-existent chat $chatId, saving as new")
            saveChat(messages)
            return false
        }
        
        return try {
            // Load existing data to preserve title and original timestamp
            val existingJson = JSONObject(file.readText())
            val originalTitle = existingJson.getString("title")
            val originalTimestamp = existingJson.getLong("timestamp")
            
            val chatJson = JSONObject().apply {
                put("id", chatId)
                put("title", originalTitle)
                put("timestamp", originalTimestamp) // Keep original timestamp
                put("lastUpdated", System.currentTimeMillis())
                put("messages", JSONArray().apply {
                    messages.forEach { msg ->
                        put(JSONObject().apply {
                            put("role", msg.role)
                            put("content", msg.content)
                            put("timestamp", msg.timestamp)
                            msg.imageBase64?.let { put("imageBase64", it) }
                        })
                    }
                })
            }
            
            file.writeText(chatJson.toString(2))
            Log.i(TAG, "Updated chat $chatId with ${messages.size} messages")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update chat $chatId", e)
            false
        }
    }
    
    /**
     * Load a specific chat by ID.
     */
    fun loadChat(chatId: String): List<ChatSession.Message>? {
        val file = File(historyDir, "$chatId.json")
        if (!file.exists()) return null
        
        return try {
            val json = JSONObject(file.readText())
            val messagesArray = json.getJSONArray("messages")
            
            val messages = mutableListOf<ChatSession.Message>()
            for (i in 0 until messagesArray.length()) {
                val msgJson = messagesArray.getJSONObject(i)
                messages.add(ChatSession.Message(
                    role = msgJson.getString("role"),
                    content = msgJson.getString("content"),
                    timestamp = msgJson.optLong("timestamp", System.currentTimeMillis()),
                    imageBase64 = msgJson.optString("imageBase64").takeIf { it.isNotBlank() }
                ))
            }
            
            Log.i(TAG, "Loaded chat $chatId with ${messages.size} messages")
            messages
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load chat $chatId", e)
            null
        }
    }
    
    /**
     * Get list of all saved chats.
     */
    fun getSavedChats(): List<ChatSummary> {
        return historyDir.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    val json = JSONObject(file.readText())
                    val messagesArray = json.getJSONArray("messages")
                    
                    // Get preview from last assistant message
                    var preview = ""
                    for (i in messagesArray.length() - 1 downTo 0) {
                        val msg = messagesArray.getJSONObject(i)
                        if (msg.getString("role") == "assistant") {
                            preview = msg.getString("content").take(100)
                            if (preview.length >= 100) preview = "$preview..."
                            break
                        }
                    }
                    
                    ChatSummary(
                        id = json.getString("id"),
                        title = json.getString("title"),
                        timestamp = json.getLong("timestamp"),
                        messageCount = messagesArray.length(),
                        preview = preview
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse chat file: ${file.name}", e)
                    null
                }
            }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }
    
    /**
     * Delete a specific chat.
     */
    fun deleteChat(chatId: String): Boolean {
        val file = File(historyDir, "$chatId.json")
        return if (file.exists()) {
            file.delete().also {
                Log.i(TAG, "Deleted chat $chatId: $it")
            }
        } else false
    }
    
    /**
     * Delete all saved chats.
     */
    fun clearAllChats() {
        historyDir.listFiles()?.forEach { it.delete() }
        Log.i(TAG, "Cleared all chat history")
    }
    
    /**
     * Remove old chats if exceeding max limit.
     */
    private fun cleanupOldChats() {
        val chats = getSavedChats()
        if (chats.size > MAX_SAVED_CHATS) {
            chats.drop(MAX_SAVED_CHATS).forEach { chat ->
                deleteChat(chat.id)
            }
            Log.i(TAG, "Cleaned up ${chats.size - MAX_SAVED_CHATS} old chats")
        }
    }
}
