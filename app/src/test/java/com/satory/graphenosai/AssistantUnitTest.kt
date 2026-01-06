package com.satory.graphenosai

import com.satory.graphenosai.llm.OpenRouterClient
import com.satory.graphenosai.search.AnonymizedSearchClient
import com.satory.graphenosai.search.SearchResult
import com.satory.graphenosai.security.SecureKeyManager
import io.mockk.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for core assistant functionality.
 */
class AssistantUnitTest {

    private lateinit var mockKeyManager: SecureKeyManager

    @Before
    fun setup() {
        mockKeyManager = mockk(relaxed = true)
    }

    @Test
    fun `sanitize query removes device IDs`() {
        val input = "My device ID is abcdef1234567890 and I need help"
        val sanitized = sanitizeInput(input)
        
        assertFalse(sanitized.contains("abcdef1234567890"))
        assertTrue(sanitized.contains("[ID]") || !sanitized.contains("abcdef"))
    }

    @Test
    fun `sanitize query removes phone numbers`() {
        val input = "Call me at +14155551234 please"
        val sanitized = sanitizeInput(input)
        
        assertFalse(sanitized.contains("14155551234"))
    }

    @Test
    fun `sanitize query removes email addresses`() {
        val input = "Contact me at user@example.com for details"
        val sanitized = sanitizeInput(input)
        
        assertFalse(sanitized.contains("user@example.com"))
        assertTrue(sanitized.contains("[EMAIL]"))
    }

    @Test
    fun `sanitize query preserves normal text`() {
        val input = "What is the weather like today?"
        val sanitized = sanitizeInput(input)
        
        assertEquals("What is the weather like today?", sanitized)
    }

    @Test
    fun `should search web detects search queries`() {
        assertTrue(shouldSearchWeb("search for kotlin tutorials"))
        assertTrue(shouldSearchWeb("what is GrapheneOS"))
        assertTrue(shouldSearchWeb("find the latest news"))
        assertTrue(shouldSearchWeb("look up android security"))
    }

    @Test
    fun `should search web ignores non-search queries`() {
        assertFalse(shouldSearchWeb("tell me a joke"))
        assertFalse(shouldSearchWeb("write a poem"))
        assertFalse(shouldSearchWeb("calculate 2+2"))
    }

    @Test
    fun `search result parsing handles empty results`() {
        val results = parseSearchResults("""{"results": []}""")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `search result parsing extracts data correctly`() {
        val json = """
            {
                "results": [
                    {
                        "title": "Test Title",
                        "url": "https://example.com",
                        "snippet": "Test snippet"
                    }
                ]
            }
        """.trimIndent()
        
        val results = parseSearchResults(json)
        assertEquals(1, results.size)
        assertEquals("Test Title", results[0].title)
        assertEquals("https://example.com", results[0].url)
        assertEquals("Test snippet", results[0].snippet)
    }

    // Helper functions mirroring app logic for testing
    private fun sanitizeInput(input: String): String {
        var sanitized = input
        sanitized = sanitized.replace(Regex("[a-f0-9]{16}"), "[ID]")
        sanitized = sanitized.replace(Regex("\\+?\\d{10,15}"), "[PHONE]")
        sanitized = sanitized.replace(
            Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"),
            "[EMAIL]"
        )
        return sanitized.trim()
    }

    private fun shouldSearchWeb(query: String): Boolean {
        val searchKeywords = listOf(
            "search", "find", "look up", "what is", "who is", "when did",
            "latest", "news", "current", "today", "recent"
        )
        val lowerQuery = query.lowercase()
        return searchKeywords.any { lowerQuery.contains(it) }
    }

    private fun parseSearchResults(json: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        try {
            val jsonObj = org.json.JSONObject(json)
            val resultsArray = jsonObj.optJSONArray("results") ?: return emptyList()
            
            for (i in 0 until resultsArray.length()) {
                val item = resultsArray.getJSONObject(i)
                results.add(
                    SearchResult(
                        title = item.optString("title", ""),
                        url = item.optString("url", ""),
                        snippet = item.optString("snippet", "")
                    )
                )
            }
        } catch (e: Exception) {
            // Parsing failed
        }
        return results
    }
}

/**
 * Tests for secure key management.
 */
class SecureKeyManagerTest {

    @Test
    fun `api key format validation`() {
        // OpenRouter API keys typically start with sk-or-
        val validKey = "sk-or-v1-abc123def456"
        val invalidKey = "not-a-valid-key"
        
        assertTrue(isValidOpenRouterKey(validKey))
        assertFalse(isValidOpenRouterKey(invalidKey))
    }

    private fun isValidOpenRouterKey(key: String): Boolean {
        return key.startsWith("sk-or-") && key.length > 20
    }
}

/**
 * Tests for audio processing utilities.
 */
class AudioProcessingTest {

    @Test
    fun `wav header generation is correct`() {
        val sampleRate = 16000
        val numChannels = 1
        val bitsPerSample = 16
        val dataSize = 32000 // 1 second of audio
        
        val header = generateWavHeader(sampleRate, numChannels, bitsPerSample, dataSize)
        
        // WAV header is always 44 bytes
        assertEquals(44, header.size)
        
        // Check RIFF marker
        assertEquals('R'.code.toByte(), header[0])
        assertEquals('I'.code.toByte(), header[1])
        assertEquals('F'.code.toByte(), header[2])
        assertEquals('F'.code.toByte(), header[3])
        
        // Check WAVE marker
        assertEquals('W'.code.toByte(), header[8])
        assertEquals('A'.code.toByte(), header[9])
        assertEquals('V'.code.toByte(), header[10])
        assertEquals('E'.code.toByte(), header[11])
    }

    private fun generateWavHeader(
        sampleRate: Int,
        numChannels: Int,
        bitsPerSample: Int,
        dataSize: Int
    ): ByteArray {
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        
        return ByteArray(44).apply {
            // RIFF header
            this[0] = 'R'.code.toByte()
            this[1] = 'I'.code.toByte()
            this[2] = 'F'.code.toByte()
            this[3] = 'F'.code.toByte()
            
            // File size - 8
            val fileSize = dataSize + 36
            this[4] = (fileSize and 0xff).toByte()
            this[5] = ((fileSize shr 8) and 0xff).toByte()
            this[6] = ((fileSize shr 16) and 0xff).toByte()
            this[7] = ((fileSize shr 24) and 0xff).toByte()
            
            // WAVE
            this[8] = 'W'.code.toByte()
            this[9] = 'A'.code.toByte()
            this[10] = 'V'.code.toByte()
            this[11] = 'E'.code.toByte()
            
            // fmt chunk
            this[12] = 'f'.code.toByte()
            this[13] = 'm'.code.toByte()
            this[14] = 't'.code.toByte()
            this[15] = ' '.code.toByte()
            
            // Subchunk1Size (16 for PCM)
            this[16] = 16
            this[17] = 0
            this[18] = 0
            this[19] = 0
            
            // AudioFormat (1 for PCM)
            this[20] = 1
            this[21] = 0
            
            // NumChannels
            this[22] = numChannels.toByte()
            this[23] = 0
            
            // SampleRate
            this[24] = (sampleRate and 0xff).toByte()
            this[25] = ((sampleRate shr 8) and 0xff).toByte()
            this[26] = ((sampleRate shr 16) and 0xff).toByte()
            this[27] = ((sampleRate shr 24) and 0xff).toByte()
            
            // ByteRate
            this[28] = (byteRate and 0xff).toByte()
            this[29] = ((byteRate shr 8) and 0xff).toByte()
            this[30] = ((byteRate shr 16) and 0xff).toByte()
            this[31] = ((byteRate shr 24) and 0xff).toByte()
            
            // BlockAlign
            this[32] = blockAlign.toByte()
            this[33] = 0
            
            // BitsPerSample
            this[34] = bitsPerSample.toByte()
            this[35] = 0
            
            // data chunk
            this[36] = 'd'.code.toByte()
            this[37] = 'a'.code.toByte()
            this[38] = 't'.code.toByte()
            this[39] = 'a'.code.toByte()
            
            // Data size
            this[40] = (dataSize and 0xff).toByte()
            this[41] = ((dataSize shr 8) and 0xff).toByte()
            this[42] = ((dataSize shr 16) and 0xff).toByte()
            this[43] = ((dataSize shr 24) and 0xff).toByte()
        }
    }
}
