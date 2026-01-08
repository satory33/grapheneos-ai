package com.satory.graphenosai.llm

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * GitHub OAuth Device Flow for Copilot authentication.
 * Uses GitHub's device flow to get an access token that can be used with Copilot API.
 * 
 * Token Lifecycle:
 * - GitHub Access Token: Long-lived, used to refresh Copilot tokens
 * - Copilot Token (JWT): Short-lived (~30 min), must be refreshed regularly
 */
class GitHubCopilotAuth {
    
    companion object {
        private const val TAG = "GitHubCopilotAuth"
        
        // GitHub OAuth endpoints
        private const val DEVICE_CODE_URL = "https://github.com/login/device/code"
        private const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"
        private const val COPILOT_TOKEN_URL = "https://api.github.com/copilot_internal/v2/token"
        
        // Client ID for VS Code Copilot (publicly known)
        private const val CLIENT_ID = "Iv1.b507a08c87ecfe98"
        
        // Scopes needed for Copilot
        private const val SCOPE = "read:user"
        
        /**
         * Parse expiry time from Copilot JWT token.
         * JWT format: header.payload.signature (Base64 encoded)
         */
        fun parseTokenExpiry(copilotToken: String): Long {
            return try {
                val parts = copilotToken.split(".")
                if (parts.size >= 2) {
                    // Decode the payload (second part)
                    val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP))
                    val json = JSONObject(payload)
                    // exp is in seconds, convert to milliseconds
                    val exp = json.optLong("exp", 0)
                    if (exp > 0) {
                        exp * 1000L
                    } else {
                        0L
                    }
                } else {
                    Log.w(TAG, "Invalid JWT format")
                    0L
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse token expiry", e)
                0L
            }
        }
    }
    
    data class DeviceCodeResponse(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresIn: Int,
        val interval: Int
    )
    
    data class AuthResult(
        val accessToken: String,
        val tokenType: String
    )
    
    /**
     * Full authentication result including both tokens.
     */
    data class FullAuthResult(
        val githubAccessToken: String,
        val copilotToken: String,
        val copilotTokenExpiry: Long
    )
    
    sealed class AuthState {
        object Idle : AuthState()
        data class WaitingForUser(val userCode: String, val verificationUri: String) : AuthState()
        object Polling : AuthState()
        data class Success(val token: String) : AuthState()
        data class Error(val message: String) : AuthState()
    }
    
    /**
     * Step 1: Request device code from GitHub
     */
    suspend fun requestDeviceCode(): Result<DeviceCodeResponse> = withContext(Dispatchers.IO) {
        try {
            val url = URL(DEVICE_CODE_URL)
            val connection = url.openConnection() as HttpsURLConnection
            
            connection.requestMethod = "POST"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.doOutput = true
            
            val body = "client_id=$CLIENT_ID&scope=$SCOPE"
            connection.outputStream.use { os ->
                os.write(body.toByteArray())
            }
            
            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val error = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                Log.e(TAG, "Device code request failed: $responseCode - $error")
                return@withContext Result.failure(Exception("Failed to get device code: $responseCode"))
            }
            
            val response = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(response)
            
            val deviceCode = DeviceCodeResponse(
                deviceCode = json.getString("device_code"),
                userCode = json.getString("user_code"),
                verificationUri = json.getString("verification_uri"),
                expiresIn = json.getInt("expires_in"),
                interval = json.optInt("interval", 5)
            )
            
            Log.i(TAG, "Got device code, user should visit: ${deviceCode.verificationUri}")
            Result.success(deviceCode)
            
        } catch (e: Exception) {
            Log.e(TAG, "Device code request error", e)
            Result.failure(e)
        }
    }
    
    /**
     * Step 2: Poll for access token after user authorizes
     */
    suspend fun pollForAccessToken(
        deviceCode: DeviceCodeResponse,
        onStateChange: (suspend (AuthState) -> Unit)? = null
    ): Result<AuthResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val timeout = deviceCode.expiresIn * 1000L
        var isPolling = false
        
        while (System.currentTimeMillis() - startTime < timeout) {
            try {
                val url = URL(ACCESS_TOKEN_URL)
                val connection = url.openConnection() as HttpsURLConnection
                
                connection.requestMethod = "POST"
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.doOutput = true
                
                val body = "client_id=$CLIENT_ID&device_code=${deviceCode.deviceCode}&grant_type=urn:ietf:params:oauth:grant-type:device_code"
                connection.outputStream.use { os ->
                    os.write(body.toByteArray())
                }
                
                val response = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                
                // Check for errors
                if (json.has("error")) {
                    val error = json.getString("error")
                    when (error) {
                        "authorization_pending" -> {
                            // User hasn't authorized yet, keep polling
                            if (!isPolling) {
                                isPolling = true
                                onStateChange?.invoke(AuthState.Polling)
                            }
                            Log.d(TAG, "Authorization pending, waiting...")
                        }
                        "slow_down" -> {
                            // We're polling too fast
                            delay((deviceCode.interval * 2 * 1000).toLong())
                        }
                        "expired_token" -> {
                            return@withContext Result.failure(Exception("Device code expired. Please try again."))
                        }
                        "access_denied" -> {
                            return@withContext Result.failure(Exception("Access denied by user."))
                        }
                        else -> {
                            return@withContext Result.failure(Exception("Auth error: $error"))
                        }
                    }
                } else if (json.has("access_token")) {
                    // Success!
                    val authResult = AuthResult(
                        accessToken = json.getString("access_token"),
                        tokenType = json.optString("token_type", "bearer")
                    )
                    Log.i(TAG, "Successfully obtained access token!")
                    return@withContext Result.success(authResult)
                }
                
            } catch (e: Exception) {
                Log.w(TAG, "Poll error (will retry)", e)
            }
            
            delay((deviceCode.interval * 1000).toLong())
        }
        
        Result.failure(Exception("Timeout waiting for authorization"))
    }
    
    /**
     * Step 3: Exchange GitHub access token for Copilot token.
     * Returns pair of (copilotToken, expiryTimestamp)
     */
    suspend fun getCopilotToken(githubAccessToken: String): Result<Pair<String, Long>> = withContext(Dispatchers.IO) {
        try {
            val url = URL(COPILOT_TOKEN_URL)
            val connection = url.openConnection() as HttpsURLConnection
            
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "token $githubAccessToken")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Editor-Version", "vscode/1.95.0")
            connection.setRequestProperty("Editor-Plugin-Version", "copilot-chat/0.22.0")
            connection.setRequestProperty("User-Agent", "GitHubCopilotChat/0.22.0")
            
            val responseCode = connection.responseCode
            
            if (responseCode != 200) {
                val error = try {
                    connection.errorStream?.bufferedReader()?.readText() ?: "Unknown"
                } catch (e: Exception) { "Unknown" }
                
                Log.e(TAG, "Copilot token request failed: $responseCode - $error")
                
                // If 401/403, the user might not have Copilot subscription or GitHub token expired
                if (responseCode == 401 || responseCode == 403) {
                    return@withContext Result.failure(Exception(
                        "Access denied. Make sure you have an active GitHub Copilot subscription. You may need to re-authenticate."
                    ))
                }
                
                return@withContext Result.failure(Exception("Failed to get Copilot token: $responseCode"))
            }
            
            val response = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(response)
            
            val copilotToken = json.getString("token")
            val expiry = parseTokenExpiry(copilotToken)
            
            Log.i(TAG, "Successfully obtained Copilot token! Expires at: $expiry")
            
            Result.success(Pair(copilotToken, expiry))
            
        } catch (e: Exception) {
            Log.e(TAG, "Copilot token error", e)
            Result.failure(e)
        }
    }
    
    /**
     * Refresh Copilot token using stored GitHub access token.
     * This is the key method for token refresh!
     */
    suspend fun refreshCopilotToken(githubAccessToken: String): Result<Pair<String, Long>> {
        Log.i(TAG, "Refreshing Copilot token...")
        return getCopilotToken(githubAccessToken)
    }
    
    /**
     * Full authentication flow: device code -> user auth -> copilot token
     * Returns FullAuthResult with both GitHub access token and Copilot token
     */
    suspend fun authenticate(
        onStateChange: suspend (AuthState) -> Unit
    ): Result<FullAuthResult> {
        onStateChange(AuthState.Idle)
        
        // Step 1: Get device code
        val deviceCodeResult = requestDeviceCode()
        if (deviceCodeResult.isFailure) {
            val error = deviceCodeResult.exceptionOrNull()?.message ?: "Unknown error"
            onStateChange(AuthState.Error(error))
            return Result.failure(deviceCodeResult.exceptionOrNull()!!)
        }
        
        val deviceCode = deviceCodeResult.getOrNull()!!
        onStateChange(AuthState.WaitingForUser(deviceCode.userCode, deviceCode.verificationUri))
        
        // Wait a bit for user to see the code before showing polling state
        delay(2000)
        
        // Step 2: Poll for access token (this will show polling state internally)
        val accessTokenResult = pollForAccessToken(deviceCode, onStateChange)
        if (accessTokenResult.isFailure) {
            val error = accessTokenResult.exceptionOrNull()?.message ?: "Unknown error"
            onStateChange(AuthState.Error(error))
            return Result.failure(accessTokenResult.exceptionOrNull()!!)
        }
        
        val authResult = accessTokenResult.getOrNull()!!
        
        // Step 3: Get Copilot token
        val copilotTokenResult = getCopilotToken(authResult.accessToken)
        if (copilotTokenResult.isFailure) {
            val error = copilotTokenResult.exceptionOrNull()?.message ?: "Unknown error"
            onStateChange(AuthState.Error(error))
            return Result.failure(copilotTokenResult.exceptionOrNull()!!)
        }
        
        val (copilotToken, expiry) = copilotTokenResult.getOrNull()!!
        onStateChange(AuthState.Success(copilotToken))
        
        return Result.success(FullAuthResult(
            githubAccessToken = authResult.accessToken,
            copilotToken = copilotToken,
            copilotTokenExpiry = expiry
        ))
    }
}
