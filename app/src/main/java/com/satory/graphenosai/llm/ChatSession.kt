package com.satory.graphenosai.llm

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages chat conversation history for context-aware responses.
 * Session persists until explicitly cleared.
 */
class ChatSession {
    
    companion object {
        private const val TAG = "ChatSession"
        private const val MAX_HISTORY_SIZE = 20 // Max messages to keep
        private const val MAX_CONTEXT_TOKENS = 8000 // Approximate token limit for context
    }
    
    data class Message(
        val role: String, // "user", "assistant", "system"
        val content: String,
        val timestamp: Long = System.currentTimeMillis(),
        val imageBase64: String? = null // For vision capability
    )
    
    private val messages = mutableListOf<Message>()
    private var sessionStartTime: Long = System.currentTimeMillis()
    
    /**
     * Add a user message to the session.
     */
    fun addUserMessage(content: String, imageBase64: String? = null) {
        messages.add(Message("user", content, imageBase64 = imageBase64))
        trimHistory()
        Log.d(TAG, "Added user message, total: ${messages.size}")
    }
    
    /**
     * Add an assistant response to the session.
     */
    fun addAssistantMessage(content: String) {
        messages.add(Message("assistant", content))
        trimHistory()
        Log.d(TAG, "Added assistant message, total: ${messages.size}")
    }
    
    /**
     * Get full conversation history as JSON array for API call.
     */
    fun getMessagesForApi(systemPrompt: String, includeVision: Boolean = false): JSONArray {
        val result = JSONArray()
        
        // System message first
        result.put(JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })
        
        Log.d(TAG, "Building API messages, includeVision=$includeVision, messageCount=${messages.size}")
        
        // Add conversation history
        for (msg in messages) {
            val msgObj = JSONObject()
            msgObj.put("role", msg.role)
            
            // Ensure content is never empty
            val textContent = msg.content.ifBlank { 
                if (msg.imageBase64 != null) "Describe this image" else "..."
            }
            
            Log.d(TAG, "Processing message: role=${msg.role}, content='${msg.content}', hasImage=${msg.imageBase64 != null}, textContent='$textContent'")
            
            // Handle vision content
            if (includeVision && msg.imageBase64 != null) {
                val contentArray = JSONArray()
                
                // Text part
                contentArray.put(JSONObject().apply {
                    put("type", "text")
                    put("text", textContent)
                })
                
                // Image part
                contentArray.put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64,${msg.imageBase64}")
                    })
                })
                
                msgObj.put("content", contentArray)
            } else {
                msgObj.put("content", textContent)
            }
            
            result.put(msgObj)
        }
        
        return result
    }
    
    /**
     * Get summary of conversation for display.
     */
    fun getConversationSummary(): String {
        if (messages.isEmpty()) return "New conversation"
        
        val userMessages = messages.count { it.role == "user" }
        val assistantMessages = messages.count { it.role == "assistant" }
        
        return "$userMessages questions, $assistantMessages responses"
    }
    
    /**
     * Get the last user message.
     */
    fun getLastUserMessage(): String? = messages.lastOrNull { it.role == "user" }?.content
    
    /**
     * Get the last assistant response.
     */
    fun getLastAssistantMessage(): String? = messages.lastOrNull { it.role == "assistant" }?.content
    
    /**
     * Check if session has any messages.
     */
    fun hasMessages(): Boolean = messages.isNotEmpty()
    
    /**
     * Get message count.
     */
    fun messageCount(): Int = messages.size
    
    /**
     * Get all messages for display.
     */
    fun getAllMessages(): List<Message> = messages.toList()
    
    /**
     * Clear conversation history, start new session.
     */
    fun clear() {
        messages.clear()
        sessionStartTime = System.currentTimeMillis()
        Log.i(TAG, "Session cleared")
    }
    
    /**
     * Trim history to prevent context overflow.
     */
    private fun trimHistory() {
        // Keep max number of messages
        while (messages.size > MAX_HISTORY_SIZE) {
            messages.removeAt(0)
        }
        
        // Estimate tokens and trim if needed
        var estimatedTokens = messages.sumOf { it.content.length / 4 } // Rough estimate
        while (estimatedTokens > MAX_CONTEXT_TOKENS && messages.size > 2) {
            messages.removeAt(0)
            estimatedTokens = messages.sumOf { it.content.length / 4 }
        }
    }
    
    /**
     * Get session duration in milliseconds.
     */
    fun getSessionDuration(): Long = System.currentTimeMillis() - sessionStartTime
}
