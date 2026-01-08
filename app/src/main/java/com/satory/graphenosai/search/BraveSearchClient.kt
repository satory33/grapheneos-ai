package com.satory.graphenosai.search

import android.util.Log
import com.satory.graphenosai.security.SecureKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/**
 * Search result data class.
 */
data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String
)

/**
 * Brave Search API client.
 * Brave Search provides high-quality results with a free tier (2000 queries/month).
 * Get API key at: https://brave.com/search/api/
 */
class BraveSearchClient(private val keyManager: SecureKeyManager) {

    companion object {
        private const val TAG = "BraveSearchClient"
        private const val BRAVE_API_URL = "https://api.search.brave.com/res/v1/web/search"
        private const val TIMEOUT_MS = 15000
        private const val MAX_RESULTS = 5
    }

    /**
     * Perform web search using Brave Search API.
     * Returns empty list if no API key is configured.
     */
    suspend fun search(query: String, maxResults: Int = MAX_RESULTS): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val apiKey = keyManager.getBraveApiKey()
            
            if (apiKey.isNullOrBlank()) {
                Log.w(TAG, "Brave Search API key not configured. Get free key at brave.com/search/api")
                return@withContext emptyList()
            }
            
            try {
                val results = searchBrave(query, apiKey, maxResults)
                Log.i(TAG, "Brave search returned ${results.size} results")
                return@withContext results
            } catch (e: Exception) {
                Log.e(TAG, "Brave search failed", e)
                return@withContext emptyList()
            }
        }

    private fun searchBrave(query: String, apiKey: String, maxResults: Int): List<SearchResult> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = URL("$BRAVE_API_URL?q=$encodedQuery&count=$maxResults&safesearch=moderate")
        
        val connection = url.openConnection() as HttpsURLConnection
        connection.apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Subscription-Token", apiKey)
        }
        
        try {
            val responseCode = connection.responseCode
            if (responseCode != HttpsURLConnection.HTTP_OK) {
                Log.e(TAG, "Brave API error: $responseCode")
                return emptyList()
            }
            
            val responseBody = connection.inputStream.bufferedReader().readText()
            return parseBraveResponse(responseBody, maxResults)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseBraveResponse(responseBody: String, maxResults: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        
        try {
            val json = JSONObject(responseBody)
            val webResults = json.optJSONObject("web")?.optJSONArray("results") ?: return emptyList()
            
            for (i in 0 until minOf(webResults.length(), maxResults)) {
                val item = webResults.getJSONObject(i)
                results.add(
                    SearchResult(
                        title = item.optString("title", ""),
                        url = item.optString("url", ""),
                        snippet = item.optString("description", "")
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Brave response", e)
        }
        
        return results
    }
    
    /**
     * Check if Brave API is available.
     */
    fun isConfigured(): Boolean = keyManager.hasBraveApiKey()
}
