package com.satory.graphenosai.search

import android.util.Log
import com.satory.graphenosai.security.SecureKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class LangSearchClient(private val keyManager: SecureKeyManager) {

    companion object {
        private const val TAG = "LangSearchClient"
        private const val LANGSEARCH_API_URL = "https://api.langsearch.com/v1/web-search"
        private const val TIMEOUT_MS = 15000
        private const val MAX_RESULTS = 5
    }

    suspend fun search(query: String, maxResults: Int = MAX_RESULTS): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val apiKey = keyManager.getLangSearchApiKey()

            if (apiKey.isNullOrBlank()) {
                Log.w(TAG, "LangSearch API key not configured. Get key at langsearch.com")
                return@withContext emptyList()
            }

            try {
                val results = searchLangSearch(query, apiKey, maxResults)
                Log.i(TAG, "LangSearch returned ${results.size} results")
                return@withContext results
            } catch (e: Exception) {
                Log.e(TAG, "LangSearch search failed", e)
                return@withContext emptyList()
            }
        }

    private fun searchLangSearch(query: String, apiKey: String, maxResults: Int): List<SearchResult> {
        val url = URL(LANGSEARCH_API_URL)

        val connection = url.openConnection() as HttpsURLConnection
        connection.apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }

        val requestBody = JSONObject().apply {
            put("q", query)
            put("max_results", maxResults)
        }

        try {
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpsURLConnection.HTTP_OK) {
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                } catch (e: Exception) { "Error: ${e.message}" }
                Log.e(TAG, "LangSearch API error $responseCode: $errorBody")
                return emptyList()
            }

            val responseBody = connection.inputStream.bufferedReader().readText()
            return parseLangSearchResponse(responseBody, maxResults)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseLangSearchResponse(responseBody: String, maxResults: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        try {
            val json = JSONObject(responseBody)
            Log.d(TAG, "LangSearch response keys: ${json.keys().asSequence().toList()}")

            // LangSearch native API format: { "results": [...] }
            val resultsArray = json.optJSONArray("results")
            if (resultsArray != null) {
                for (i in 0 until minOf(resultsArray.length(), maxResults)) {
                    val item = resultsArray.getJSONObject(i)
                    results.add(
                        SearchResult(
                            title = item.optString("title", ""),
                            url = item.optString("url", ""),
                            snippet = item.optString("snippet", "").take(500)
                        )
                    )
                }
                return results
            }

            // Fallback: Bing-compatible format { "webPages": { "value": [...] } }
            val webPages = json.optJSONObject("webPages")
            val value = webPages?.optJSONArray("value")
            if (value != null) {
                for (i in 0 until minOf(value.length(), maxResults)) {
                    val item = value.getJSONObject(i)
                    val snippet = item.optString("snippet", "").take(500).ifBlank {
                        item.optString("summary", "").take(500)
                    }
                    results.add(
                        SearchResult(
                            title = item.optString("name", ""),
                            url = item.optString("url", ""),
                            snippet = snippet
                        )
                    )
                }
                return results
            }

            Log.w(TAG, "Unknown LangSearch response format: ${responseBody.take(200)}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse LangSearch response", e)
        }

        return results
    }

    fun isConfigured(): Boolean = keyManager.hasLangSearchApiKey()
}
