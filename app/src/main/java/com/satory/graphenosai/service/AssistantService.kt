package com.satory.graphenosai.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.satory.graphenosai.AssistantApplication
import com.satory.graphenosai.MainActivity
import com.satory.graphenosai.R
import com.satory.graphenosai.audio.AudioCaptureManager
import com.satory.graphenosai.audio.SpeechRecognizerManager
import com.satory.graphenosai.audio.VoskTranscriber
import com.satory.graphenosai.audio.WhisperTranscriber
import com.satory.graphenosai.llm.ChatSession
import com.satory.graphenosai.llm.CopilotClient
import com.satory.graphenosai.llm.OpenRouterClient
import com.satory.graphenosai.search.BraveSearchClient
import com.satory.graphenosai.storage.ChatHistoryManager
import com.satory.graphenosai.tts.TTSManager
import com.satory.graphenosai.ui.SettingsManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Foreground service managing the AI assistant lifecycle.
 * Supports chat sessions with context.
 */
class AssistantService : Service() {

    private val binder = AssistantBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private lateinit var audioCaptureManager: AudioCaptureManager
    private lateinit var voskTranscriber: VoskTranscriber
    private lateinit var whisperTranscriber: WhisperTranscriber
    private lateinit var speechRecognizerManager: SpeechRecognizerManager
    lateinit var openRouterClient: OpenRouterClient
    private lateinit var copilotClient: CopilotClient
    private lateinit var braveSearchClient: BraveSearchClient
    private lateinit var ttsManager: TTSManager
    lateinit var settingsManager: SettingsManager
    lateinit var chatHistoryManager: ChatHistoryManager
    
    private var speechRecognitionJob: Job? = null

    // State flow for UI binding
    private val _assistantState = MutableStateFlow<AssistantState>(AssistantState.Idle)
    val assistantState: StateFlow<AssistantState> = _assistantState.asStateFlow()

    private val _transcription = MutableStateFlow("")
    val transcription: StateFlow<String> = _transcription.asStateFlow()

    private val _response = MutableStateFlow("")
    val response: StateFlow<String> = _response.asStateFlow()
    
    // Chat session messages for UI
    private val _chatMessages = MutableStateFlow<List<ChatSession.Message>>(emptyList())
    val chatMessages: StateFlow<List<ChatSession.Message>> = _chatMessages.asStateFlow()
    
    // Pending URL to open (detected from AI response)
    private val _pendingUrl = MutableStateFlow<String?>(null)
    val pendingUrl: StateFlow<String?> = _pendingUrl.asStateFlow()
    
    // Currently loaded chat ID (null = new chat)
    private var _currentChatId: String? = null
    
    // Web search enabled for current query
    private val _webSearchEnabled = MutableStateFlow(true)
    val webSearchEnabled: StateFlow<Boolean> = _webSearchEnabled.asStateFlow()
    
    fun toggleWebSearch() {
        _webSearchEnabled.value = !_webSearchEnabled.value
    }
    
    /**
     * Open URL in default browser.
     */
    fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            _pendingUrls.value = emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open URL: $url", e)
        }
    }
    
    // Multiple pending URLs for user selection
    private val _pendingUrls = MutableStateFlow<List<String>>(emptyList())
    val pendingUrls: StateFlow<List<String>> = _pendingUrls.asStateFlow()
    
    fun clearPendingUrls() {
        _pendingUrls.value = emptyList()
    }
    
    /**
     * Detect all URLs in response that should be opened.
     * Returns list of URLs for user to choose from.
     */
    private fun detectUrlsToOpen(response: String): List<String> {
        val urls = mutableListOf<String>()
        
        // Look for patterns like "I'll open" or "opening" followed by URL
        val openPatterns = listOf(
            Regex("(?:open(?:ing)?|launch(?:ing)?|navigate to)\\s+(?:this )?(?:link|url|page)?:?\\s*(https?://[^\\s\\)\\]]+)", RegexOption.IGNORE_CASE),
            Regex("\\[OPEN_URL:(https?://[^]]+)]"),
            Regex("(?:открываю|открой)\\s+(?:ссылку)?:?\\s*(https?://[^\\s\\)\\]]+)", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in openPatterns) {
            pattern.findAll(response).forEach { match ->
                val url = match.groupValues[1].trimEnd('.', ',', '!', '?')
                if (url !in urls) {
                    urls.add(url)
                }
            }
        }
        
        return urls
    }

    companion object {
        private const val TAG = "AssistantService"
        private const val NOTIFICATION_ID = 1001
        
        const val ACTION_ACTIVATE = "com.satory.graphenosai.ACTIVATE"
        const val ACTION_START_VOICE = "com.satory.graphenosai.START_VOICE"
        const val ACTION_STOP_VOICE = "com.satory.graphenosai.STOP_VOICE"
        const val ACTION_QUERY_TEXT = "com.satory.graphenosai.QUERY_TEXT"
        const val ACTION_STOP = "com.satory.graphenosai.STOP"
        
        const val EXTRA_TRIGGER = "trigger"
        const val EXTRA_QUERY = "query"
    }

    inner class AssistantBinder : Binder() {
        fun getService(): AssistantService = this@AssistantService
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "AssistantService created")
        
        val app = application as AssistantApplication
        
        audioCaptureManager = AudioCaptureManager(this)
        voskTranscriber = VoskTranscriber(this)
        speechRecognizerManager = SpeechRecognizerManager(this)
        settingsManager = SettingsManager(this)
        chatHistoryManager = ChatHistoryManager(this)
        
        // Initialize Whisper with API key provider
        whisperTranscriber = WhisperTranscriber {
            // Use OpenRouter API key for Groq or OpenAI key
            when (settingsManager.whisperProvider) {
                SettingsManager.WHISPER_OPENAI -> app.secureKeyManager.getOpenRouterApiKey()
                else -> app.secureKeyManager.getGroqApiKey() ?: app.secureKeyManager.getOpenRouterApiKey()
            }
        }.apply {
            provider = when (settingsManager.whisperProvider) {
                SettingsManager.WHISPER_OPENAI -> WhisperTranscriber.Provider.OPENAI
                else -> WhisperTranscriber.Provider.GROQ
            }
        }
        
        openRouterClient = OpenRouterClient(app.secureKeyManager).apply {
            setModel(settingsManager.getEffectiveModel())
            setSystemPrompt(settingsManager.systemPrompt)
        }
        
        copilotClient = CopilotClient(app.secureKeyManager).apply {
            setModel(settingsManager.selectedModel.let { 
                // Convert OpenRouter model ID to Copilot model ID if needed
                if (it.contains("/")) it.substringAfter("/") else it
            })
            setSystemPrompt(settingsManager.systemPrompt)
        }
        
        braveSearchClient = BraveSearchClient(app.secureKeyManager)
        ttsManager = TTSManager(this)
        
        // Initialize Vosk with selected language (and secondary for multilingual)
        serviceScope.launch(Dispatchers.IO) {
            val language = settingsManager.voiceLanguage
            if (!voskTranscriber.needsModelDownload(language)) {
                Log.i(TAG, "Vosk model found for $language, initializing...")
                
                // Check for multilingual mode
                if (settingsManager.multilingualEnabled) {
                    val secondaryLang = settingsManager.secondaryVoiceLanguage
                    if (!voskTranscriber.needsModelDownload(secondaryLang)) {
                        val initialized = voskTranscriber.initializeMultilingual(language, secondaryLang)
                        Log.i(TAG, "Vosk multilingual initialization ($language + $secondaryLang): $initialized")
                    } else {
                        val initialized = voskTranscriber.initialize(language)
                        Log.i(TAG, "Vosk initialization (secondary model not downloaded): $initialized")
                    }
                } else {
                    val initialized = voskTranscriber.initialize(language)
                    Log.i(TAG, "Vosk initialization result: $initialized")
                }
            } else {
                Log.i(TAG, "Vosk model not downloaded for $language")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACTIVATE -> {
                startForeground(NOTIFICATION_ID, createNotification("Assistant ready"))
                // Clear session on each activation for fresh start
                clearSession()
                launchOverlay()
            }
            ACTION_START_VOICE -> startVoiceCapture()
            ACTION_STOP_VOICE -> stopVoiceCapture()
            ACTION_QUERY_TEXT -> {
                val query = intent.getStringExtra(EXTRA_QUERY) ?: return START_NOT_STICKY
                processTextQuery(query)
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        audioCaptureManager.release()
        speechRecognizerManager.destroy()
        ttsManager.shutdown()
        Log.i(TAG, "AssistantService destroyed")
    }

    private fun createNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, AssistantApplication.CHANNEL_SERVICE)
            .setContentTitle("AI Assistant")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_assistant)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun launchOverlay() {
        val overlayIntent = Intent(this, com.satory.graphenosai.ui.CompactAssistantActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(overlayIntent)
    }

    /**
     * Start voice capture using Vosk, System speech, or Whisper cloud transcription.
     */
    fun startVoiceCapture() {
        // Reset any stuck listening state - check if recognizer is actually listening
        if (_assistantState.value == AssistantState.Listening) {
            if (!speechRecognizerManager.isCurrentlyListening() && !audioCaptureManager.isCapturing()) {
                // State is stuck - reset it
                Log.w(TAG, "Resetting stuck listening state")
                _assistantState.value = AssistantState.Idle
            } else {
                // Actually recording - don't start again
                return
            }
        }
        
        _assistantState.value = AssistantState.Listening
        _transcription.value = ""
        
        val voiceMethod = settingsManager.voiceInputMethod
        val preferVosk = voiceMethod == SettingsManager.VOICE_INPUT_VOSK
        val preferWhisper = voiceMethod == SettingsManager.VOICE_INPUT_WHISPER
        val voskReady = voskTranscriber.isReady()
        val systemAvailable = speechRecognizerManager.isAvailable()
        
        // Decision tree:
        // 1. If user prefers Whisper -> start audio capture for Whisper
        // 2. If user prefers Vosk and it's ready -> use Vosk
        // 3. If user prefers System and it's available -> use System
        // 4. If preferred method unavailable, try fallback
        // 5. If nothing available -> show error
        
        when {
            // Whisper cloud transcription preferred
            preferWhisper -> {
                Log.i(TAG, "Using Whisper cloud transcription")
                startWhisperCapture()
            }
            // Vosk preferred and ready
            preferVosk && voskReady -> {
                Log.i(TAG, "Using Vosk transcription (preferred)")
                startVoskCapture()
            }
            // System preferred and available
            !preferVosk && !preferWhisper && systemAvailable -> {
                Log.i(TAG, "Using system speech recognition (preferred)")
                startSystemSpeechRecognition()
            }
            // Vosk preferred but not ready, try system as fallback
            preferVosk && !voskReady && systemAvailable -> {
                Log.i(TAG, "Using system speech recognition (Vosk not ready, fallback)")
                startSystemSpeechRecognition()
            }
            // System preferred but unavailable, try Vosk as fallback
            !preferVosk && !systemAvailable && voskReady -> {
                Log.i(TAG, "Using Vosk transcription (system unavailable, fallback)")
                startVoskCapture()
            }
            // Nothing available
            else -> {
                val message = when {
                    preferVosk && !voskReady -> "Please download Vosk model in Settings for offline voice input."
                    !preferVosk && !systemAvailable -> "No system speech recognition available. Enable Vosk in Settings."
                    else -> "Voice recognition unavailable. Please check Settings."
                }
                _transcription.value = ""
                _response.value = message
                _assistantState.value = AssistantState.Error("No voice input available")
            }
        }
    }
    
    private fun startSystemSpeechRecognition() {
        Log.i(TAG, "Starting system speech recognition")
        
        speechRecognitionJob = serviceScope.launch(Dispatchers.Main) {
            try {
                val partialResult = StringBuilder()
                var shouldSwitchToVosk = false
                
                speechRecognizerManager.startListening()
                    .collect { result ->
                        when (result) {
                            is SpeechRecognizerManager.RecognitionResult.ReadyForSpeech -> {
                                Log.d(TAG, "Ready for speech")
                            }
                            is SpeechRecognizerManager.RecognitionResult.Partial -> {
                                Log.d(TAG, "Partial: ${result.text}")
                                partialResult.clear()
                                partialResult.append(result.text)
                                _transcription.value = result.text
                            }
                            is SpeechRecognizerManager.RecognitionResult.Final -> {
                                Log.i(TAG, "Final: ${result.text}")
                                _transcription.value = result.text
                                
                                if (settingsManager.autoSendVoice && result.text.isNotBlank()) {
                                    _assistantState.value = AssistantState.Processing
                                    processVoiceQuery(result.text)
                                } else {
                                    _assistantState.value = AssistantState.Idle
                                }
                            }
                            is SpeechRecognizerManager.RecognitionResult.Error -> {
                                Log.e(TAG, "Speech recognition error (${result.code}): ${result.message}")
                                
                                // For CLIENT_ERROR (code 5), it means no recognition service is available
                                if (result.code == 5) {
                                    Log.w(TAG, "No system speech recognition service available, switching to Vosk")
                                    
                                    // Stop system recognition flow
                                    speechRecognizerManager.stopListening()
                                    shouldSwitchToVosk = true
                                    return@collect
                                }
                                
                                // For permission errors, show helpful message
                                val displayMessage = if (result.code == 6) {
                                    "Microphone permission not granted. Please enable it in Settings."
                                } else if (result.code == 12) {
                                    "No speech detected. Please try again and speak clearly."
                                } else {
                                    "Voice input error: ${result.message}"
                                }
                                
                                _response.value = displayMessage
                                _assistantState.value = AssistantState.Error(result.message)
                            }
                            is SpeechRecognizerManager.RecognitionResult.EndOfSpeech -> {
                                Log.d(TAG, "End of speech")
                            }
                        }
                    }
                
                // After collect finishes, check if we need to switch to Vosk
                if (shouldSwitchToVosk) {
                    if (voskTranscriber.isReady()) {
                        Log.i(TAG, "Switching to Vosk for voice input")
                        _assistantState.value = AssistantState.Listening
                        startVoskCapture()
                    } else {
                        _response.value = "No voice recognition service available. Please download Vosk model in Settings."
                        _assistantState.value = AssistantState.Error("No recognition service")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Speech recognition exception", e)
                _response.value = "Voice error: ${e.message}"
                _assistantState.value = AssistantState.Error(e.message ?: "Speech recognition failed")
            }
        }
    }
    
    private fun startVoskCapture() {
        Log.i(TAG, "Starting Vosk voice capture")
        
        serviceScope.launch(Dispatchers.IO) {
            try {
                audioCaptureManager.startCapture()
                    .collect { audioChunk ->
                        // Buffer audio for batch processing
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Audio capture error", e)
                _assistantState.value = AssistantState.Error(e.message ?: "Audio capture failed")
            }
        }
    }
    
    private fun startWhisperCapture() {
        Log.i(TAG, "Starting Whisper cloud capture")
        
        // Configure Whisper provider from settings
        whisperTranscriber.provider = when (settingsManager.whisperProvider) {
            SettingsManager.WHISPER_OPENAI -> WhisperTranscriber.Provider.OPENAI
            else -> WhisperTranscriber.Provider.GROQ
        }
        
        serviceScope.launch(Dispatchers.IO) {
            try {
                audioCaptureManager.startCapture()
                    .collect { audioChunk ->
                        // Buffer audio for batch processing
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Audio capture error (Whisper)", e)
                _assistantState.value = AssistantState.Error(e.message ?: "Audio capture failed")
            }
        }
    }

    /**
     * Stop voice capture and process transcription.
     */
    fun stopVoiceCapture() {
        if (_assistantState.value != AssistantState.Listening) return
        
        // Cancel system speech recognition if active
        speechRecognitionJob?.cancel()
        speechRecognizerManager.stopListening()
        
        val voiceMethod = settingsManager.voiceInputMethod
        val useSystemRecognition = voiceMethod == SettingsManager.VOICE_INPUT_SYSTEM
        val useWhisper = voiceMethod == SettingsManager.VOICE_INPUT_WHISPER
        
        if (useSystemRecognition && speechRecognizerManager.isAvailable()) {
            // For system recognition, the result is already processed in startSystemSpeechRecognition
            // Just process what we have if auto-send is off
            if (!settingsManager.autoSendVoice && _transcription.value.isNotBlank()) {
                _assistantState.value = AssistantState.Processing
                serviceScope.launch(Dispatchers.IO) {
                    processVoiceQuery(_transcription.value)
                }
            } else if (_transcription.value.isBlank()) {
                _assistantState.value = AssistantState.Idle
            }
            return
        }
        
        // Whisper or Vosk processing
        _assistantState.value = AssistantState.Processing
        
        serviceScope.launch(Dispatchers.IO) {
            try {
                val audioFile = audioCaptureManager.stopCapture()
                
                if (useWhisper) {
                    // Use Whisper cloud transcription
                    Log.i(TAG, "Transcribing with Whisper (${whisperTranscriber.provider})")
                    val language = settingsManager.voiceLanguage.split("-").firstOrNull()
                    
                    whisperTranscriber.transcribe(audioFile, language).fold(
                        onSuccess = { text ->
                            _transcription.value = text
                            processVoiceQuery(text)
                        },
                        onFailure = { error ->
                            Log.e(TAG, "Whisper transcription error", error)
                            _transcription.value = ""
                            _response.value = "Voice input error: ${error.message}"
                            _assistantState.value = AssistantState.Error(error.message ?: "Transcription failed")
                        }
                    )
                    return@launch
                }
                
                // Check if Vosk is ready
                if (!voskTranscriber.isReady()) {
                    _transcription.value = ""
                    _response.value = "Voice recognition unavailable. Please download Vosk model in Settings."
                    _assistantState.value = AssistantState.Complete
                    return@launch
                }
                
                // Transcribe using Vosk
                val transcribedText = voskTranscriber.transcribe(audioFile)
                
                // Don't send error messages to LLM
                if (transcribedText.startsWith("[") && transcribedText.endsWith("]")) {
                    _transcription.value = ""
                    _response.value = transcribedText.removeSurrounding("[", "]")
                    _assistantState.value = AssistantState.Complete
                    return@launch
                }
                
                _transcription.value = transcribedText
                
                // Process the transcription
                processVoiceQuery(transcribedText)
            } catch (e: Exception) {
                Log.e(TAG, "Transcription error", e)
                _response.value = "Voice input error: ${e.message}"
                _assistantState.value = AssistantState.Error(e.message ?: "Transcription failed")
            }
        }
    }

    /**
     * Process a text query directly (text input mode).
     * Immediately adds user message to chat before AI starts responding.
     */
    fun processTextQuery(query: String, imageBase64: String? = null) {
        // Ensure query is never empty
        val effectiveQuery = query.ifBlank { 
            if (imageBase64 != null) "Describe this image" else return // Can't process empty query without image
        }
        
        _transcription.value = effectiveQuery
        _assistantState.value = AssistantState.Processing
        
        Log.i(TAG, "Processing query: '$effectiveQuery', hasImage=${imageBase64 != null}, imageLength=${imageBase64?.length}")
        
        // Immediately add user message to chat UI
        addUserMessageToChat(effectiveQuery, imageBase64)
        
        serviceScope.launch(Dispatchers.IO) {
            processQueryInternal(effectiveQuery, imageBase64)
        }
    }
    
    /**
     * Helper to add user message to chat and update UI immediately.
     */
    private fun addUserMessageToChat(query: String, imageBase64: String? = null) {
        val useCopilot = settingsManager.apiProvider == SettingsManager.PROVIDER_COPILOT
        if (useCopilot) {
            copilotClient.chatSession.addUserMessage(query, imageBase64)
            _chatMessages.value = copilotClient.chatSession.getAllMessages()
            Log.d(TAG, "Added to Copilot session: '$query', imageLength=${imageBase64?.length}")
        } else {
            openRouterClient.chatSession.addUserMessage(query, imageBase64)
            _chatMessages.value = openRouterClient.chatSession.getAllMessages()
            Log.d(TAG, "Added to OpenRouter session: '$query', imageLength=${imageBase64?.length}")
        }
    }
    
    /**
     * Process voice query - adds user message and processes.
     * Called from voice capture paths.
     */
    private suspend fun processVoiceQuery(query: String) {
        // Add user message to chat (on main thread for UI update)
        withContext(Dispatchers.Main) {
            addUserMessageToChat(query, null)
        }
        // Process the query
        processQueryInternal(query, null)
    }

    /**
     * Main query processing pipeline:
     * 1. Perform web search if enabled (except for images)
     * 2. Call LLM with context
     * 3. Speak response via TTS
     */
    private suspend fun processQueryInternal(query: String, imageBase64: String? = null) {
        try {
            val sanitizedQuery = sanitizeQuery(query)
            
            var contextSources: List<String> = emptyList()
            var searchContext: String? = null
            
            // Perform web search if enabled and it's a text query
            if (_webSearchEnabled.value && imageBase64 == null) {
                _assistantState.value = AssistantState.Searching
                
                val searchResults = braveSearchClient.search(sanitizedQuery)
                
                if (searchResults.isNotEmpty()) {
                    contextSources = searchResults.map { it.url }
                    searchContext = searchResults.joinToString("\n\n") { 
                        "Source: ${it.title}\nURL: ${it.url}\n${it.snippet}"
                    }
                    Log.i(TAG, "Brave search returned ${searchResults.size} results")
                }
            }
            
            val finalContext = searchContext
            
            // Query LLM with context
            streamLLMResponse(sanitizedQuery, finalContext, contextSources, imageBase64)
        } catch (e: Exception) {
            Log.e(TAG, "Query processing error", e)
            _assistantState.value = AssistantState.Error(e.message ?: "Processing failed")
        }
    }
    
    private suspend fun streamLLMResponse(
        query: String,
        context: String?,
        sources: List<String>,
        imageBase64: String? = null
    ) {
        _assistantState.value = AssistantState.Responding
        _response.value = ""
        
        val fullResponse = StringBuilder()
        val useCopilot = settingsManager.apiProvider == SettingsManager.PROVIDER_COPILOT
        
        Log.i(TAG, "Starting LLM request with ${if (useCopilot) "Copilot" else "OpenRouter"}, hasContext=${context != null}, contextLength=${context?.length ?: 0}")
        
        try {
            // If we have search context, modify the last user message to include it
            val responseFlow = if (context != null && context.isNotBlank()) {
                // Build enhanced prompt with search results
                val searchPrompt = """I found the following information from web search. Use this to answer the user's question comprehensively. Don't just list links - synthesize the information into a helpful answer.

--- WEB SEARCH RESULTS ---
$context
--- END RESULTS ---

Now answer the user's question: $query"""
                
                Log.i(TAG, "Using search context: ${context.take(200)}...")
                
                // Use streamCompletionDirect which sends the enhanced query without modifying chat history
                if (useCopilot) {
                    copilotClient.streamCompletionDirect(searchPrompt, imageBase64)
                } else {
                    openRouterClient.streamCompletionDirect(searchPrompt, imageBase64)
                }
            } else {
                // No search context - use normal completion (message already in chat history)
                if (useCopilot) {
                    copilotClient.streamCompletion(query, imageBase64)
                } else {
                    openRouterClient.streamCompletion(query, imageBase64)
                }
            }
            
            responseFlow
                .catch { e ->
                    Log.e(TAG, "LLM streaming error", e)
                    _response.value = "Error: ${e.message}"
                    _assistantState.value = AssistantState.Error(e.message ?: "LLM error")
                }
                .collect { chunk ->
                    fullResponse.append(chunk)
                    _response.value = fullResponse.toString()
                }
            
            Log.i(TAG, "LLM response complete: ${fullResponse.length} chars")
            
            if (fullResponse.isEmpty()) {
                _response.value = "No response. Check your API key."
                _assistantState.value = AssistantState.Error("Empty response")
                return
            }
            
            // Append sources if web search was used
            if (sources.isNotEmpty()) {
                val sourcesText = "\n\n📚 Sources:\n" + sources.take(3).mapIndexed { i, url -> 
                    "${i + 1}. $url" 
                }.joinToString("\n")
                fullResponse.append(sourcesText)
                _response.value = fullResponse.toString()
            }
            
            // Update chat messages for UI (get fresh state after assistant message was added)
            withContext(Dispatchers.Main) {
                _chatMessages.value = if (useCopilot) {
                    copilotClient.chatSession.getAllMessages()
                } else {
                    openRouterClient.chatSession.getAllMessages()
                }
            }
            
            // Check if AI wants to open URLs
            val responseText = fullResponse.toString()
            val urlsToOpen = detectUrlsToOpen(responseText)
            
            // Always ask user before opening URLs (for safety and user control)
            if (urlsToOpen.isNotEmpty()) {
                _pendingUrls.value = urlsToOpen
                Log.i(TAG, "Detected ${urlsToOpen.size} URLs for user approval")
            }
            
            // Speak the response if enabled
            if (settingsManager.ttsEnabled) {
                _assistantState.value = AssistantState.Speaking
                ttsManager.speak(fullResponse.toString())
            }
            
            _assistantState.value = AssistantState.Complete
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in streamLLMResponse", e)
            _response.value = "Error: ${e.message}"
            _assistantState.value = AssistantState.Error(e.message ?: "Unknown error")
        }
    }
    
    /**
     * Clear chat session and start fresh.
     * Saves current chat to history if it has messages.
     */
    fun clearSession(saveToHistory: Boolean = true) {
        // Save current chat to history before clearing
        if (saveToHistory) {
            val messages = _chatMessages.value
            if (messages.size >= 2) { // At least one exchange
                if (_currentChatId != null) {
                    // Update existing chat instead of creating new one
                    chatHistoryManager.updateChat(_currentChatId!!, messages)
                } else {
                    // Save as new chat
                    chatHistoryManager.saveChat(messages)
                }
            }
        }
        
        // Reset current chat ID for new session
        _currentChatId = null
        
        openRouterClient.clearSession()
        copilotClient.clearSession()
        _chatMessages.value = emptyList()
        _response.value = ""
        _transcription.value = ""
        _pendingUrls.value = emptyList()
        _assistantState.value = AssistantState.Idle
        Log.i(TAG, "Chat session cleared")
    }
    
    /**
     * Load a chat from history.
     */
    fun loadChatFromHistory(chatId: String) {
        val messages = chatHistoryManager.loadChat(chatId) ?: return
        
        // Clear current session without saving
        clearSession(saveToHistory = false)
        
        // Track that we're continuing an existing chat
        _currentChatId = chatId
        
        // Load messages into active session
        val useCopilot = settingsManager.apiProvider == SettingsManager.PROVIDER_COPILOT
        val session = if (useCopilot) copilotClient.chatSession else openRouterClient.chatSession
        
        messages.forEach { msg ->
            when (msg.role) {
                "user" -> session.addUserMessage(msg.content, msg.imageBase64)
                "assistant" -> session.addAssistantMessage(msg.content)
            }
        }
        
        _chatMessages.value = session.getAllMessages()
        Log.i(TAG, "Loaded chat $chatId with ${messages.size} messages (continuing existing)")
    }
    
    /**
     * Reload settings - call this when settings are changed.
     */
    fun reloadSettings() {
        Log.i(TAG, "Reloading settings...")
        
        // Reload model settings
        val effectiveModel = settingsManager.getEffectiveModel()
        openRouterClient.setModel(effectiveModel)
        openRouterClient.setSystemPrompt(settingsManager.systemPrompt)
        
        val copilotModel = effectiveModel.let { 
            if (it.contains("/")) it.substringAfter("/") else it 
        }
        copilotClient.setModel(copilotModel)
        copilotClient.setSystemPrompt(settingsManager.systemPrompt)
        
        Log.i(TAG, "Model set to: $effectiveModel (Copilot: $copilotModel)")
        
        // Reload voice language
        serviceScope.launch(Dispatchers.IO) {
            val language = settingsManager.voiceLanguage
            val currentLang = voskTranscriber.getCurrentLanguage()
            
            if (currentLang != language) {
                Log.i(TAG, "Switching voice language from $currentLang to $language")
                
                if (!voskTranscriber.needsModelDownload(language)) {
                    if (settingsManager.multilingualEnabled) {
                        val secondaryLang = settingsManager.secondaryVoiceLanguage
                        if (!voskTranscriber.needsModelDownload(secondaryLang)) {
                            voskTranscriber.initializeMultilingual(language, secondaryLang)
                        } else {
                            voskTranscriber.initialize(language)
                        }
                    } else {
                        voskTranscriber.initialize(language)
                    }
                } else {
                    Log.w(TAG, "Cannot switch to $language - model not downloaded")
                }
            }
        }
    }

    /**
     * Remove device identifiers and PII from query before sending to cloud.
     */
    private fun sanitizeQuery(query: String): String {
        var sanitized = query
        sanitized = sanitized.replace(Regex("[a-f0-9]{16}"), "[REDACTED]")
        sanitized = sanitized.replace(Regex("\\+?\\d{10,15}"), "[PHONE]")
        sanitized = sanitized.replace(
            Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"),
            "[EMAIL]"
        )
        return sanitized.trim()
    }

    fun cancelOperation() {
        serviceScope.coroutineContext.cancelChildren()
        speechRecognitionJob?.cancel()
        speechRecognizerManager.stopListening()
        audioCaptureManager.cancelCapture()
        ttsManager.stop()
        _assistantState.value = AssistantState.Idle
    }
}

sealed class AssistantState {
    object Idle : AssistantState()
    object Listening : AssistantState()
    object Processing : AssistantState()
    object Searching : AssistantState()
    object Responding : AssistantState()
    object Speaking : AssistantState()
    object Complete : AssistantState()
    data class Error(val message: String) : AssistantState()
}
