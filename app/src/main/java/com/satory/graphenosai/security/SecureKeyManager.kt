package com.satory.graphenosai.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secure key management using Android Keystore.
 * Encrypts API keys and sensitive data at rest.
 */
class SecureKeyManager(private val context: Context) {

    companion object {
        private const val TAG = "SecureKeyManager"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "assistant_encryption_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12
        
        private const val PREFS_NAME = "assistant_secure_prefs"
        private const val PREF_OPENROUTER_KEY = "openrouter_api_key"
        private const val PREF_COPILOT_TOKEN = "copilot_token"
        private const val PREF_GITHUB_ACCESS_TOKEN = "github_access_token"
        private const val PREF_COPILOT_TOKEN_EXPIRY = "copilot_token_expiry"
        private const val PREF_GROQ_KEY = "groq_api_key"
        private const val PREF_SEARCH_PROXY_URL = "search_proxy_url"
        private const val PREF_TOKEN_REFRESH_TIME = "token_refresh_time"
        private const val PREF_BRAVE_API_KEY = "brave_api_key"
    }

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    init {
        createKeyIfNeeded()
    }

    /**
     * Create encryption key in Android Keystore if it doesn't exist.
     */
    private fun createKeyIfNeeded() {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )

            val keySpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false) // Set true for biometric protection
                .setRandomizedEncryptionRequired(true)
                .build()

            keyGenerator.init(keySpec)
            keyGenerator.generateKey()
            Log.i(TAG, "Encryption key created in Keystore")
        }
    }

    private fun getSecretKey(): SecretKey {
        return keyStore.getKey(KEY_ALIAS, null) as SecretKey
    }

    /**
     * Encrypt data using AES-GCM with Android Keystore key.
     * Returns Base64-encoded string containing IV + ciphertext.
     */
    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        
        // Combine IV and ciphertext
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * Decrypt Base64-encoded data using Android Keystore key.
     */
    private fun decrypt(encryptedData: String): String {
        val combined = Base64.decode(encryptedData, Base64.NO_WRAP)
        
        // Extract IV and ciphertext
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
        
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
        
        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, Charsets.UTF_8)
    }

    // ========== API Key Management ==========

    /**
     * Store OpenRouter API key securely.
     */
    fun setOpenRouterApiKey(apiKey: String) {
        val encrypted = encrypt(apiKey)
        prefs.edit().putString(PREF_OPENROUTER_KEY, encrypted).apply()
        Log.i(TAG, "OpenRouter API key stored securely")
    }

    /**
     * Retrieve OpenRouter API key.
     * @return Decrypted API key or null if not set
     */
    fun getOpenRouterApiKey(): String? {
        val encrypted = prefs.getString(PREF_OPENROUTER_KEY, null) ?: return null
        return try {
            decrypt(encrypted)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt API key", e)
            null
        }
    }

    /**
     * Check if API key is configured.
     */
    fun hasOpenRouterApiKey(): Boolean {
        return prefs.contains(PREF_OPENROUTER_KEY)
    }

    /**
     * Clear stored API key.
     */
    fun clearOpenRouterApiKey() {
        prefs.edit().remove(PREF_OPENROUTER_KEY).apply()
    }

    // ========== GitHub Copilot Token Management ==========
    
    /**
     * Store GitHub Copilot token securely.
     */
    fun setCopilotToken(token: String) {
        val encrypted = encrypt(token)
        prefs.edit().putString(PREF_COPILOT_TOKEN, encrypted).apply()
        Log.i(TAG, "Copilot token stored securely")
    }
    
    /**
     * Retrieve GitHub Copilot token.
     */
    fun getCopilotToken(): String? {
        val encrypted = prefs.getString(PREF_COPILOT_TOKEN, null) ?: return null
        return try {
            decrypt(encrypted)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt Copilot token", e)
            null
        }
    }
    
    /**
     * Check if Copilot token is configured.
     */
    fun hasCopilotToken(): Boolean {
        return prefs.contains(PREF_COPILOT_TOKEN)
    }
    
    /**
     * Clear stored Copilot token.
     */
    fun clearCopilotToken() {
        prefs.edit().remove(PREF_COPILOT_TOKEN).apply()
    }
    
    // ========== GitHub Access Token Management (for Copilot token refresh) ==========
    
    /**
     * Store GitHub Access Token securely.
     * This is the long-lived token used to refresh Copilot tokens.
     */
    fun setGitHubAccessToken(token: String) {
        val encrypted = encrypt(token)
        prefs.edit().putString(PREF_GITHUB_ACCESS_TOKEN, encrypted).apply()
        Log.i(TAG, "GitHub Access Token stored securely")
    }
    
    /**
     * Retrieve GitHub Access Token.
     */
    fun getGitHubAccessToken(): String? {
        val encrypted = prefs.getString(PREF_GITHUB_ACCESS_TOKEN, null) ?: return null
        return try {
            decrypt(encrypted)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt GitHub Access Token", e)
            null
        }
    }
    
    /**
     * Check if GitHub Access Token is configured.
     */
    fun hasGitHubAccessToken(): Boolean {
        return prefs.contains(PREF_GITHUB_ACCESS_TOKEN)
    }
    
    /**
     * Clear stored GitHub Access Token and related Copilot data.
     */
    fun clearGitHubAccessToken() {
        prefs.edit()
            .remove(PREF_GITHUB_ACCESS_TOKEN)
            .remove(PREF_COPILOT_TOKEN)
            .remove(PREF_COPILOT_TOKEN_EXPIRY)
            .apply()
        Log.i(TAG, "GitHub auth data cleared")
    }
    
    /**
     * Store Copilot token expiry timestamp.
     */
    fun setCopilotTokenExpiry(expiryTimestamp: Long) {
        prefs.edit().putLong(PREF_COPILOT_TOKEN_EXPIRY, expiryTimestamp).apply()
        Log.d(TAG, "Copilot token expiry set to: $expiryTimestamp")
    }
    
    /**
     * Get Copilot token expiry timestamp.
     */
    fun getCopilotTokenExpiry(): Long {
        return prefs.getLong(PREF_COPILOT_TOKEN_EXPIRY, 0)
    }
    
    /**
     * Check if Copilot token is expired or about to expire.
     * Returns true if token expires in less than 5 minutes.
     */
    fun isCopilotTokenExpired(): Boolean {
        val expiry = getCopilotTokenExpiry()
        if (expiry == 0L) return true
        // Refresh 5 minutes before actual expiry
        return System.currentTimeMillis() >= (expiry - 5 * 60 * 1000)
    }

    // ========== Groq API Key Management ==========
    
    /**
     * Store Groq API key securely (for Whisper cloud transcription).
     */
    fun setGroqApiKey(apiKey: String) {
        val encrypted = encrypt(apiKey)
        prefs.edit().putString(PREF_GROQ_KEY, encrypted).apply()
        Log.i(TAG, "Groq API key stored securely")
    }
    
    /**
     * Retrieve Groq API key.
     */
    fun getGroqApiKey(): String? {
        val encrypted = prefs.getString(PREF_GROQ_KEY, null) ?: return null
        return try {
            decrypt(encrypted)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt Groq API key", e)
            null
        }
    }
    
    /**
     * Check if Groq API key is configured.
     */
    fun hasGroqApiKey(): Boolean {
        return prefs.contains(PREF_GROQ_KEY)
    }
    
    /**
     * Clear stored Groq API key.
     */
    fun clearGroqApiKey() {
        prefs.edit().remove(PREF_GROQ_KEY).apply()
    }

    // ========== Search Proxy Configuration ==========

    fun setSearchProxyUrl(url: String) {
        val encrypted = encrypt(url)
        prefs.edit().putString(PREF_SEARCH_PROXY_URL, encrypted).apply()
    }

    fun getSearchProxyUrl(): String? {
        val encrypted = prefs.getString(PREF_SEARCH_PROXY_URL, null) ?: return null
        return try {
            decrypt(encrypted)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt proxy URL", e)
            null
        }
    }

    // ========== Brave Search API Key Management ==========
    
    /**
     * Store Brave Search API key securely.
     * Get key at: https://brave.com/search/api/
     */
    fun setBraveApiKey(apiKey: String) {
        val encrypted = encrypt(apiKey)
        prefs.edit().putString(PREF_BRAVE_API_KEY, encrypted).apply()
        Log.i(TAG, "Brave API key stored securely")
    }
    
    /**
     * Retrieve Brave Search API key.
     */
    fun getBraveApiKey(): String? {
        val encrypted = prefs.getString(PREF_BRAVE_API_KEY, null) ?: return null
        return try {
            decrypt(encrypted)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt Brave API key", e)
            null
        }
    }
    
    /**
     * Check if Brave API key is configured.
     */
    fun hasBraveApiKey(): Boolean {
        return prefs.contains(PREF_BRAVE_API_KEY)
    }
    
    /**
     * Clear stored Brave API key.
     */
    fun clearBraveApiKey() {
        prefs.edit().remove(PREF_BRAVE_API_KEY).apply()
    }

    // ========== Token Rotation Support ==========

    /**
     * Store token refresh timestamp for rotation scheduling.
     */
    fun setTokenRefreshTime(timestamp: Long) {
        prefs.edit().putLong(PREF_TOKEN_REFRESH_TIME, timestamp).apply()
    }

    fun getTokenRefreshTime(): Long {
        return prefs.getLong(PREF_TOKEN_REFRESH_TIME, 0)
    }

    /**
     * Check if token needs rotation (e.g., every 24 hours).
     */
    fun needsTokenRotation(rotationIntervalMs: Long = 24 * 60 * 60 * 1000): Boolean {
        val lastRefresh = getTokenRefreshTime()
        return System.currentTimeMillis() - lastRefresh > rotationIntervalMs
    }
}
