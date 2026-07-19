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
import com.satory.graphenosai.llm.*
import com.satory.graphenosai.search.BraveSearchClient
import com.satory.graphenosai.search.ExaSearchClient
import com.satory.graphenosai.search.LangSearchClient
import com.satory.graphenosai.search.SearchResult
import com.satory.graphenosai.llm.buildSearchResultsMessage
import com.satory.graphenosai.llm.containsDsmlToolCalls
import com.satory.graphenosai.llm.extractDsmlQuery
import com.satory.graphenosai.llm.parseDsmlToolCalls
import com.satory.graphenosai.storage.ChatHistoryManager
import com.satory.graphenosai.tts.TTSManager
import com.satory.graphenosai.ui.SettingsManager
import com.satory.graphenosai.weather.OpenMeteoClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Foreground service managing the AI assistant lifecycle.
 * Supports chat sessions with context, including local offline LLMs.
 */
class AssistantService : Service() {

    private val binder = AssistantBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private lateinit var audioCaptureManager: AudioCaptureManager
    private lateinit var voskTranscriber: VoskTranscriber
    private lateinit var whisperTranscriber: WhisperTranscriber
    private lateinit var speechRecognizerManager: SpeechRecognizerManager
    lateinit var openRouterClient: OpenRouterClient
        private set
    val isOpenRouterReady: Boolean get() = ::openRouterClient.isInitialized
    private lateinit var llamaCppClient: LlamaCppClient
    lateinit var localModelManager: LocalModelManager
        private set
    private lateinit var braveSearchClient: BraveSearchClient
    private lateinit var exaSearchClient: ExaSearchClient
    private lateinit var langSearchClient: LangSearchClient
    private lateinit var openMeteoClient: OpenMeteoClient
    lateinit var ttsManager: TTSManager
        private set
    lateinit var settingsManager: SettingsManager
        private set
    private lateinit var chatHistoryManager: ChatHistoryManager
    
    private var speechRecognitionJob: Job? = null
    private var audioCaptureJob: Job? = null
    private var activeResponseJob: Job? = null

    private enum class VoiceCaptureMode { NONE, SYSTEM, VOSK, WHISPER }
    private var activeVoiceCaptureMode = VoiceCaptureMode.NONE
    private val localModelMutex = Mutex()
    private var localModelLoadJob: Deferred<Result<Unit>>? = null

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
     * Safely check if current model supports vision, even if OpenRouter client hasn't been initialized yet.
     */
    fun isVisionCapable(): Boolean {
        return isOpenRouterReady && openRouterClient.isVisionCapable()
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
        
        // Initialize local LLM client
        localModelManager = LocalModelManager(this)
        llamaCppClient = LlamaCppClient().apply {
            setSystemPrompt(settingsManager.systemPrompt)
        }
        
        // Initialize local model if provider is set to local
        if (settingsManager.apiProvider == SettingsManager.PROVIDER_LOCAL) {
            initializeLocalModel()
        }
        
        braveSearchClient = BraveSearchClient(app.secureKeyManager)
        exaSearchClient = ExaSearchClient(app.secureKeyManager)
        langSearchClient = LangSearchClient(app.secureKeyManager)
        openMeteoClient = OpenMeteoClient()
        ttsManager = TTSManager(this)

        // Apply saved TTS language preference
        val savedTtsLang = settingsManager.ttsLanguage
        ttsManager.setLanguage(savedTtsLang)
        Log.i(TAG, "TTS language set to: $savedTtsLang")
        
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
        if (_assistantState.value == AssistantState.Listening) {
            if (speechRecognizerManager.isCurrentlyListening() || audioCaptureManager.isCapturing()) {
                return
            }
            Log.w(TAG, "Resetting stuck listening state")
            resetVoiceCapture()
        } else {
            resetVoiceCapture()
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
    
    private fun resetVoiceCapture() {
        speechRecognitionJob?.cancel()
        speechRecognitionJob = null
        audioCaptureJob?.cancel()
        audioCaptureJob = null
        speechRecognizerManager.stopListening()
        audioCaptureManager.cancelCapture()
        activeVoiceCaptureMode = VoiceCaptureMode.NONE
    }

    private fun startSystemSpeechRecognition() {
        Log.i(TAG, "Starting system speech recognition")
        activeVoiceCaptureMode = VoiceCaptureMode.SYSTEM
        
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
        activeVoiceCaptureMode = VoiceCaptureMode.VOSK
        startAudioCaptureJob("Vosk")
    }
    
    private fun startWhisperCapture() {
        Log.i(TAG, "Starting Whisper cloud capture")
        activeVoiceCaptureMode = VoiceCaptureMode.WHISPER

        whisperTranscriber.provider = when (settingsManager.whisperProvider) {
            SettingsManager.WHISPER_OPENAI -> WhisperTranscriber.Provider.OPENAI
            else -> WhisperTranscriber.Provider.GROQ
        }

        startAudioCaptureJob("Whisper")
    }

    private fun startAudioCaptureJob(label: String) {
        audioCaptureJob?.cancel()
        audioCaptureJob = serviceScope.launch(Dispatchers.IO) {
            try {
                audioCaptureManager.startCapture()
                    .collect { }
            } catch (e: CancellationException) {
                Log.d(TAG, "$label audio capture cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "$label audio capture error", e)
                if (_assistantState.value is AssistantState.Listening) {
                    _assistantState.value = AssistantState.Error(e.message ?: "Audio capture failed")
                }
            } finally {
                if (audioCaptureJob === coroutineContext[Job]) {
                    audioCaptureJob = null
                }
            }
        }
    }

    /**
     * Stop voice capture and process transcription.
     */
    fun stopVoiceCapture() {
        if (_assistantState.value != AssistantState.Listening) return

        val captureMode = activeVoiceCaptureMode

        speechRecognitionJob?.cancel()
        speechRecognitionJob = null
        speechRecognizerManager.stopListening()

        if (captureMode == VoiceCaptureMode.SYSTEM) {
            activeVoiceCaptureMode = VoiceCaptureMode.NONE
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

        if (captureMode != VoiceCaptureMode.VOSK && captureMode != VoiceCaptureMode.WHISPER) {
            audioCaptureJob?.cancel()
            audioCaptureJob = null
            audioCaptureManager.cancelCapture()
            activeVoiceCaptureMode = VoiceCaptureMode.NONE
            _assistantState.value = AssistantState.Idle
            return
        }

        val useWhisper = captureMode == VoiceCaptureMode.WHISPER
        _assistantState.value = AssistantState.Processing

        audioCaptureJob?.cancel()
        audioCaptureJob = null

        serviceScope.launch(Dispatchers.IO) {
            try {
                val audioFile = if (audioCaptureManager.isCapturing()) {
                    audioCaptureManager.stopCapture()
                } else {
                    Log.w(TAG, "No active audio capture on stop, skipping transcription")
                    activeVoiceCaptureMode = VoiceCaptureMode.NONE
                    _assistantState.value = AssistantState.Idle
                    return@launch
                }
                activeVoiceCaptureMode = VoiceCaptureMode.NONE
                
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
            if (imageBase64 != null) "Describe this image" else {
                // Nothing to process — reset state and bail
                _assistantState.value = AssistantState.Idle
                return
            }
        }
        
        stopResponse()
        _assistantState.value = AssistantState.Processing
        
        Log.i(TAG, "Processing query: '$effectiveQuery', hasImage=${imageBase64 != null}, imageLength=${imageBase64?.length}")
        
        // Immediately add user message to chat UI
        addUserMessageToChat(effectiveQuery, imageBase64)
        
        activeResponseJob = serviceScope.launch(Dispatchers.IO) {
            processQueryInternal(effectiveQuery, imageBase64)
        }
    }
    
    /**
     * Helper to add user message to chat and update UI immediately.
     */
    private fun addUserMessageToChat(query: String, imageBase64: String? = null) {
        val provider = settingsManager.apiProvider
        when (provider) {
            SettingsManager.PROVIDER_LOCAL -> {
                llamaCppClient.chatSession.addUserMessage(query, imageBase64)
                _chatMessages.value = llamaCppClient.chatSession.getAllMessages()
                Log.d(TAG, "Added to Local LLM session: '$query'")
            }
            else -> {
                openRouterClient.chatSession.addUserMessage(query, imageBase64)
                _chatMessages.value = openRouterClient.chatSession.getAllMessages()
                Log.d(TAG, "Added to OpenRouter session: '$query', imageLength=${imageBase64?.length}")
            }
        }
    }
    
    /**
     * Process voice query - adds user message and processes.
     * Called from voice capture paths.
     */
    private suspend fun processVoiceQuery(query: String) {
        stopResponse()
        // Add user message to chat (on main thread for UI update)
        withContext(Dispatchers.Main) {
            addUserMessageToChat(query, null)
        }
        // Process the query
        processQueryInternal(query, null)
    }

    /**
     * Main query processing pipeline using OpenAI-compatible function calling:
     * 1. Sends streaming request with web_search tool defined
     * 2. If the model calls web_search → executes search → streams follow-up with results
     * 3. If no tool call → streams response directly
     */
    private suspend fun processQueryInternal(query: String, imageBase64: String? = null) {
        try {
            val sanitizedQuery = sanitizeQuery(query)
            val isLocal = settingsManager.apiProvider == SettingsManager.PROVIDER_LOCAL

            if (_webSearchEnabled.value && imageBase64 == null && !isLocal) {
                when (classifyRetrievalIntent(sanitizedQuery)) {
                    RetrievalIntent.WEATHER -> processWithWeather(sanitizedQuery)
                    RetrievalIntent.WEB_SEARCH -> {
                        if (isSearchConfigured()) {
                            processWithDirectSearch(sanitizedQuery)
                        } else {
                            showSearchNotConfigured()
                        }
                    }
                    RetrievalIntent.NONE -> if (isSearchConfigured()) {
                        processWithFunctionCalling(imageBase64)
                    } else {
                        streamDirect(sanitizedQuery, imageBase64)
                    }
                }
            } else {
                streamDirect(sanitizedQuery, imageBase64)
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "Query processing cancelled")
            if (_assistantState.value !is AssistantState.Complete) {
                _assistantState.value = AssistantState.Idle
            }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Query processing error", e)
            _assistantState.value = AssistantState.Error(e.message ?: "Processing failed")
        }
    }

    private fun isSearchConfigured(): Boolean = when (settingsManager.searchEngine) {
        SettingsManager.SEARCH_EXA -> exaSearchClient.isConfigured()
        SettingsManager.SEARCH_LANGSEARCH -> langSearchClient.isConfigured()
        else -> braveSearchClient.isConfigured()
    }

    private fun showSearchNotConfigured() {
        val providerName = when (settingsManager.searchEngine) {
            SettingsManager.SEARCH_EXA -> "Exa"
            SettingsManager.SEARCH_LANGSEARCH -> "LangSearch"
            else -> "Brave Search"
        }
        _response.value = "Web search is enabled, but $providerName is not configured. Add its API key in Settings → Web Search, or switch to a configured search engine."
        _assistantState.value = AssistantState.Error("Web search is not configured")
    }

    private fun shouldFallbackToSearch(response: String): Boolean {
        val lower = response.lowercase()
        val noAccessPhrases = listOf(
            "don't have access to the internet",
            "do not have access to the internet",
            "don't have web access",
            "do not have web access",
            "can't browse the web",
            "cannot browse the web",
            "не имею доступа к интернету",
            "нет доступа к интернету",
            "не могу искать в интернете",
            "не могу просматривать интернет",
            "нет доступа к веб"
        )
        return noAccessPhrases.any { lower.contains(it) }
    }

    /**
     * Function calling flow: model decides via web_search tool whether to search.
     */
    private suspend fun processWithWeather(query: String) {
        _assistantState.value = AssistantState.Searching
        _response.value = ""

        val location = extractWeatherLocation(query)
        val weatherReport = openMeteoClient.getWeather(location)
        if (weatherReport == null) {
            _response.value = "I couldn't resolve the weather location. Please ask with a city name, for example: \"погода в Варшаве\"."
            _assistantState.value = AssistantState.Error("Weather location not found")
            return
        }

        val fullResponse = StringBuilder()
        val enhancedQuery = buildWeatherEnhancedQuery(query, weatherReport.toPromptContext())

        _assistantState.value = AssistantState.Responding
        openRouterClient.streamCompletionWithSearch(enhancedQuery, null)
            .catch { e ->
                Log.e(TAG, "Weather response streaming error", e)
                _response.value = "Error: ${e.message}"
                _assistantState.value = AssistantState.Error(e.message ?: "LLM error")
            }
            .collect { chunk ->
                fullResponse.append(chunk)
                _response.value = fullResponse.toString()
            }

        finalizeResponse(fullResponse.toString(), listOf("https://open-meteo.com/"))
    }

    private suspend fun processWithDirectSearch(query: String) {
        _assistantState.value = AssistantState.Searching
        _response.value = ""

        val searchResults = executeSearch(query)
        val contextSources = searchResults.map { it.url }
        val enhancedQuery = buildSearchEnhancedQuery(query, searchResults)
        val fullResponse = StringBuilder()

        _assistantState.value = AssistantState.Responding

        openRouterClient.streamCompletionWithSearch(enhancedQuery, null)
            .catch { e ->
                Log.e(TAG, "Direct search streaming error", e)
                _response.value = "Error: ${e.message}"
                _assistantState.value = AssistantState.Error(e.message ?: "LLM error")
            }
            .collect { chunk ->
                fullResponse.append(chunk)
                _response.value = fullResponse.toString()
            }

        finalizeResponse(fullResponse.toString(), contextSources)
    }

    private suspend fun processWithFunctionCalling(imageBase64: String? = null) {
        _assistantState.value = AssistantState.Responding
        _response.value = ""

        val fullResponse = StringBuilder()
        val tools = WebSearchTool.buildToolsArray()
        var contextSources: List<String> = emptyList()
        var detectedCalls: List<ToolCall>? = null
        var assistantToolMsg: JSONObject? = null

        // Phase 1: Stream with web_search tool — model decides if search is needed
        openRouterClient.streamCompletionWithToolSupport(tools, imageBase64)
            .catch { e ->
                Log.e(TAG, "Tool streaming error", e)
                _response.value = "Error: ${e.message}"
                _assistantState.value = AssistantState.Error(e.message ?: "LLM error")
            }
            .collect { event ->
                when (event) {
                    is StreamEvent.Content -> {
                        fullResponse.append(event.text)
                        _response.value = fullResponse.toString()
                    }
                    is StreamEvent.ToolCallsDetected -> {
                        detectedCalls = event.calls
                        assistantToolMsg = event.assistantMessage
                    }
                }
            }

        // Phase 2: Tool was called — execute search and stream follow-up
        if (detectedCalls != null && assistantToolMsg != null) {
            _assistantState.value = AssistantState.Searching
            Log.i(TAG, "Tool call detected: ${detectedCalls!!.size} call(s)")

            val searchResults = executeSearchFromToolCalls(detectedCalls!!)
            if (searchResults.isNotEmpty()) {
                contextSources = searchResults.map { it.url }
            }

            val followUpMessages = buildToolFollowUpMessages(
                assistantToolMsg!!,
                detectedCalls!!,
                searchResults
            )

            _assistantState.value = AssistantState.Responding

            openRouterClient.streamWithMessages(followUpMessages, null)
                .catch { e ->
                    Log.e(TAG, "Follow-up streaming error", e)
                    _response.value = "Error: ${e.message}"
                }
                .collect { chunk ->
                    fullResponse.append(chunk)
                    _response.value = fullResponse.toString()
                }

            if (fullResponse.isNotEmpty()) {
                openRouterClient.chatSession.addAssistantMessage(fullResponse.toString())
            }
        }

        // Phase 2b: DSML-style tool calls detected in content — parse and execute
        if (detectedCalls == null && containsDsmlToolCalls(fullResponse.toString())) {
            val rawText = fullResponse.toString()
            Log.d(TAG, "DSML detected, raw='${rawText.take(300)}'")
            val (dsmlCalls, cleanedText) = parseDsmlToolCalls(rawText)
            Log.d(TAG, "DSML parsed: ${dsmlCalls.size} call(s), cleaned='${cleanedText.take(200)}'")
            if (dsmlCalls.isNotEmpty()) {
                fullResponse.clear()
                fullResponse.append(cleanedText)
                _response.value = cleanedText

                _assistantState.value = AssistantState.Searching
                Log.i(TAG, "DSML tool call detected: ${dsmlCalls.size} call(s)")

                val searchResults = executeSearchFromToolCalls(dsmlCalls)
                if (searchResults.isNotEmpty()) {
                    contextSources = searchResults.map { it.url }
                }

                val resultsText = buildSearchResultsMessage(searchResults)
                val followUpMessages = openRouterClient.chatSession.getMessagesForApi(
                    settingsManager.systemPrompt, false
                )
                // Add previous assistant response (with DSML stripped) and search results
                val userMsg = JSONObject().apply {
                    put("role", "user")
                    put("content", if (cleanedText.isNotBlank()) {
                        "$cleanedText\n\nSearch results:\n$resultsText"
                    } else {
                        "Web search results:\n$resultsText"
                    })
                }
                followUpMessages.put(userMsg)

                _assistantState.value = AssistantState.Responding
                openRouterClient.streamWithMessages(followUpMessages, null)
                    .catch { e ->
                        Log.e(TAG, "DSML follow-up streaming error", e)
                        _response.value = "Error: ${e.message}"
                    }
                    .collect { chunk ->
                        fullResponse.append(chunk)
                        _response.value = fullResponse.toString()
                    }

                if (fullResponse.isNotEmpty()) {
                    openRouterClient.chatSession.addAssistantMessage(fullResponse.toString())
                }
            }
        }

        if (detectedCalls == null && contextSources.isEmpty() && shouldFallbackToSearch(fullResponse.toString())) {
            val originalQuery = openRouterClient.chatSession.getAllMessages()
                .lastOrNull { it.role == "user" }
                ?.content
                .orEmpty()
            if (originalQuery.isNotBlank()) {
                Log.i(TAG, "Model claimed no web access; falling back to direct search")
                processWithDirectSearch(originalQuery)
                return
            }
        }

        // Phase 3: Finalize — sources, URL detection, TTS
        finalizeResponse(fullResponse.toString(), contextSources)
    }

    private suspend fun executeSearchFromToolCalls(toolCalls: List<ToolCall>): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        for (tc in toolCalls) {
            if (tc.function.name == "web_search") {
                val searchQuery = parseSearchQuery(tc.function.arguments)
                if (searchQuery.isNotBlank()) {
                    Log.i(TAG, "Executing web search: '$searchQuery'")
                    val searchResults = executeSearch(searchQuery)
                    Log.i(TAG, "Search returned ${searchResults.size} results")
                    results.addAll(searchResults)
                }
            }
        }
        return results
    }

    private suspend fun executeSearch(query: String): List<SearchResult> = when (settingsManager.searchEngine) {
        SettingsManager.SEARCH_EXA -> exaSearchClient.search(query)
        SettingsManager.SEARCH_LANGSEARCH -> langSearchClient.search(query)
        else -> braveSearchClient.search(query)
    }

    private fun buildToolFollowUpMessages(
        assistantMsg: JSONObject,
        toolCalls: List<ToolCall>,
        searchResults: List<SearchResult>
    ): JSONArray {
        val history = openRouterClient.chatSession.getMessagesForApi(
            settingsManager.systemPrompt, false
        )

        val messages = JSONArray()
        for (i in 0 until history.length()) {
            messages.put(history.getJSONObject(i))
        }

        messages.put(assistantMsg)

        for (tc in toolCalls) {
            val content = if (tc.function.name == "web_search" && searchResults.isNotEmpty()) {
                searchResults.joinToString("\n\n") {
                    "Title: ${it.title}\nURL: ${it.url}\n${it.snippet}"
                }
            } else {
                "No results found."
            }
            messages.put(buildToolResultMessage(tc.id, content))
        }

        return messages
    }

    /**
     * Direct streaming without function calling (local models or search disabled).
     */
    private suspend fun streamDirect(query: String, imageBase64: String? = null) {
        _response.value = ""

        val fullResponse = StringBuilder()
        val useLocal = settingsManager.apiProvider == SettingsManager.PROVIDER_LOCAL

        try {
            if (useLocal) {
                val loadResult = ensureLocalModelReady()
                loadResult.onFailure { error ->
                    val message = error.message ?: "Failed to load local model"
                    _response.value = message
                    _assistantState.value = AssistantState.Error(message)
                    return
                }
            }

            _assistantState.value = AssistantState.Responding

            val effectiveImageBase64 = if (useLocal) null else imageBase64

            val responseFlow = when {
                useLocal -> llamaCppClient.streamCompletion(query, null)
                else -> openRouterClient.streamCompletion(query, effectiveImageBase64)
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

            finalizeResponse(fullResponse.toString(), emptyList())
        } catch (e: CancellationException) {
            Log.i(TAG, "Direct streaming cancelled")
            if (_assistantState.value !is AssistantState.Complete) {
                _assistantState.value = AssistantState.Idle
            }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error in streamDirect", e)
            _response.value = "Error: ${e.message}"
            _assistantState.value = AssistantState.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Common post-processing after any response: sources, URL detection, TTS, UI update.
     */
    private suspend fun finalizeResponse(
        responseText: String,
        sources: List<String>,
        isDsmlRetry: Boolean = false
    ) {
        // If response contains DSML-style tool call tags and we haven't retried yet, handle
        val hasDsml = !isDsmlRetry && responseText.contains("<|") && responseText.contains("DSML", ignoreCase = true)
        if (hasDsml) {
            Log.d(TAG, "DSML detected in response, handling search follow-up")
            handleDsmlResponse(responseText)
            return
        }

        // Strip any remaining DSML/XML-like tags
        val cleaned = responseText.replace(Regex("<\\|[^>]*>"), "").trim()

        if (cleaned.isEmpty()) {
            _response.value = "No response from LLM. Check your OpenRouter API key and internet connection."
            _assistantState.value = AssistantState.Error("Empty response")
            return
        }

        val useLocal = settingsManager.apiProvider == SettingsManager.PROVIDER_LOCAL
        _response.value = cleaned

        // Append sources if web search was used
        if (sources.isNotEmpty() && !useLocal) {
            val sourcesText = "\n\n📚 Sources:\n" + sources.take(3).mapIndexed { i, url ->
                "${i + 1}. $url"
            }.joinToString("\n")
            _response.value = cleaned + sourcesText
        }

        // Update chat messages for UI
        withContext(Dispatchers.Main) {
            _chatMessages.value = when {
                useLocal -> llamaCppClient.chatSession.getAllMessages()
                else -> openRouterClient.chatSession.getAllMessages()
            }
        }

        // URL detection
        if (!useLocal) {
            val urlsToOpen = detectUrlsToOpen(cleaned)
            if (urlsToOpen.isNotEmpty()) {
                _pendingUrls.value = urlsToOpen
                Log.i(TAG, "Detected ${urlsToOpen.size} URLs for user approval")
            }
        }

        // TTS
        if (settingsManager.ttsEnabled) {
            _assistantState.value = AssistantState.Speaking
            ttsManager.speakAndWait(cleaned)
        }

        _assistantState.value = AssistantState.Complete
    }

    /**
     * Handle a response that contains DSML-style tool call tags.
     * Extracts the search query, executes the search, and streams a follow-up.
     */
    private suspend fun handleDsmlResponse(rawText: String) {
        Log.d(TAG, "Handling DSML response: '${rawText.take(200)}'")

        // Extract query using simple string search
        val query = extractDsmlQuery(rawText)

        // Extract function name — look for name="web_search" after "invoke"
        val invokeIdx = rawText.indexOf("invoke", ignoreCase = true)
        val funcName = if (invokeIdx >= 0) {
            val afterInvoke = rawText.substring(invokeIdx)
            val nameIdx = afterInvoke.indexOf("name=\"", ignoreCase = true)
            if (nameIdx >= 0) {
                val start = nameIdx + 6
                val end = afterInvoke.indexOf("\"", start)
                if (end >= 0) afterInvoke.substring(start, end) else ""
            } else ""
        } else ""

        if (query.isNotEmpty() && (funcName.isEmpty() || funcName == "web_search")) {
            Log.i(TAG, "DSML search query: '$query'")

            _assistantState.value = AssistantState.Searching

            val toolCalls = listOf(ToolCall(
                id = "dsml_0",
                type = "function",
                function = ToolFunctionCall("web_search", JSONObject().apply {
                    put("query", query)
                }.toString())
            ))

            val searchResults = executeSearchFromToolCalls(toolCalls)
            val contextSources = searchResults.map { it.url }

            val resultsText = buildSearchResultsMessage(searchResults)
            val followUpMessages = openRouterClient.chatSession.getMessagesForApi(
                settingsManager.systemPrompt, false
            )
            followUpMessages.put(JSONObject().apply {
                put("role", "user")
                put("content", "Web search results for '$query':\n$resultsText")
            })

            _assistantState.value = AssistantState.Responding
            val responseBuilder = StringBuilder()

            openRouterClient.streamWithMessages(followUpMessages, null)
                .catch { e ->
                    Log.e(TAG, "DSML follow-up error", e)
                    _response.value = "Error: ${e.message}"
                }
                .collect { chunk ->
                    responseBuilder.append(chunk)
                    _response.value = responseBuilder.toString()
                }

            val finalText = responseBuilder.toString()
            if (finalText.isNotEmpty()) {
                openRouterClient.chatSession.addAssistantMessage(finalText)
            }

            // Finalize with sources (pass retry guard to prevent infinite loop)
            finalizeResponse(finalText, contextSources, isDsmlRetry = true)
        } else {
            // Can't parse DSML or not a search call — just show empty state
            _response.value = ""
            _assistantState.value = AssistantState.Idle
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
        llamaCppClient.clearSession()
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
        val provider = settingsManager.apiProvider
        val session = when (provider) {
            SettingsManager.PROVIDER_LOCAL -> llamaCppClient.chatSession
            else -> openRouterClient.chatSession
        }
        
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
     * Initialize local LLM model if selected
     */
    private fun initializeLocalModel() {
        serviceScope.launch {
            localModelMutex.withLock {
                if (llamaCppClient.isModelLoaded()) return@launch

                val activeJob = localModelLoadJob
                if (activeJob != null && activeJob.isActive) return@launch

                localModelLoadJob = serviceScope.async(Dispatchers.IO) {
                    loadLocalModelInternal(settingsManager.localModelId)
                }
            }
        }
    }

    private suspend fun loadLocalModelInternal(
        modelId: String,
        updateSelectedId: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val modelPath = localModelManager.getModelPath(modelId)
        if (modelPath == null) {
            Log.w(TAG, "No local model downloaded for: $modelId")
            return@withContext Result.failure(
                Exception("No local model downloaded. Please download a model in Settings.")
            )
        }

        val modelInfo = localModelManager.getModelInfo(modelId)
        val modelName = modelInfo?.name ?: "Local Model"

        Log.i(TAG, "Loading local model: $modelName at $modelPath")

        llamaCppClient.initialize()
        llamaCppClient.unloadModel()
        llamaCppClient.contextSize = modelInfo?.contextSize ?: 2048
        val result = llamaCppClient.loadModel(
            modelPath,
            modelName,
            modelInfo?.promptFormat ?: "chatml"
        )

        result.onSuccess {
            if (updateSelectedId) {
                settingsManager.localModelId = modelId
            }
            Log.i(TAG, "Local model loaded successfully: $modelName")
        }.onFailure { error ->
            Log.e(TAG, "Failed to load local model: ${error.message}")
        }

        result
    }

    private suspend fun ensureLocalModelReady(): Result<Unit> {
        if (llamaCppClient.isModelLoaded()) return Result.success(Unit)

        val job = localModelMutex.withLock {
            if (llamaCppClient.isModelLoaded()) return@withLock null

            val activeJob = localModelLoadJob
            if (activeJob != null && activeJob.isActive) {
                return@withLock activeJob
            }

            serviceScope.async(Dispatchers.IO) {
                loadLocalModelInternal(settingsManager.localModelId)
            }.also { localModelLoadJob = it }
        }

        if (job == null) return Result.success(Unit)
        return job.await()
    }
    
    /**
     * Load a specific local model
     */
    fun loadLocalModel(modelId: String) {
        serviceScope.launch {
            val job = localModelMutex.withLock {
                localModelLoadJob?.takeIf { it.isActive }?.cancel()
                serviceScope.async(Dispatchers.IO) {
                    loadLocalModelInternal(modelId, updateSelectedId = true)
                }.also { localModelLoadJob = it }
            }
            job.await()
        }
    }
    
    /**
     * Check if local LLM is ready
     */
    fun isLocalModelReady(): Boolean = llamaCppClient.isModelLoaded()
    
    /**
     * Reload settings - call this when settings are changed.
     */
    fun reloadSettings() {
        Log.i(TAG, "Reloading settings...")
        
        // Reload model settings
        val effectiveModel = settingsManager.getEffectiveModel()
        openRouterClient.setModel(effectiveModel)
        openRouterClient.setSystemPrompt(settingsManager.systemPrompt)
        
        // Update local model system prompt
        llamaCppClient.setSystemPrompt(settingsManager.systemPrompt)
        
        // If switched to local provider, initialize local model
        if (settingsManager.apiProvider == SettingsManager.PROVIDER_LOCAL) {
            if (!llamaCppClient.isModelLoaded()) {
                initializeLocalModel()
            }
        }
        
        Log.i(TAG, "Model set to: $effectiveModel")
        
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

        // Reload TTS language
        val savedTtsLang = settingsManager.ttsLanguage
        if (ttsManager.getCurrentLanguageTag() != savedTtsLang) {
            Log.i(TAG, "Switching TTS language to: $savedTtsLang")
            ttsManager.setLanguage(savedTtsLang)
        }
    }

    /**
     * Remove device identifiers and PII from query before sending to cloud.
     * Note: For local models, sanitization is optional but we keep it for consistency
     */
    private fun sanitizeQuery(query: String): String {
        // Skip sanitization for local models (they run offline anyway)
        if (settingsManager.apiProvider == SettingsManager.PROVIDER_LOCAL) {
            return query.trim()
        }
        
        var sanitized = query
        sanitized = sanitized.replace(
            Regex("(?i)(device\\s*id|android\\s*id|id\\s*is)\\s*[:=]?\\s*\\b[a-f0-9]{16}\\b"),
            "$1 [ID]"
        )
        sanitized = sanitized.replace(Regex("\\+?\\d{10,15}"), "[PHONE]")
        sanitized = sanitized.replace(
            Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"),
            "[EMAIL]"
        )
        return sanitized.trim()
    }

    fun cancelOperation() {
        stopResponse()
        resetVoiceCapture()
        ttsManager.stop()
        // Also stop local generation if running
        llamaCppClient.stopGeneration()
        _assistantState.value = AssistantState.Idle
    }

    fun stopResponse() {
        val currentState = _assistantState.value
        val wasSpeakingOnly = currentState is AssistantState.Speaking
        val wasActive = currentState is AssistantState.Processing ||
            currentState is AssistantState.Searching ||
            currentState is AssistantState.Responding ||
            wasSpeakingOnly

        activeResponseJob?.cancel()
        activeResponseJob = null
        ttsManager.stop()
        llamaCppClient.stopGeneration()

        if (wasSpeakingOnly) {
            // TTS was in progress — stop speech silently, don't modify the response
            _assistantState.value = AssistantState.Complete
            return
        }

        val hadPartialResponse = wasActive && _response.value.isNotBlank()
        if (hadPartialResponse) {
            val partial = _response.value.trim() + "\n\n[Stopped]"
            if (settingsManager.apiProvider == SettingsManager.PROVIDER_LOCAL) {
                llamaCppClient.chatSession.addAssistantMessage(partial)
                _chatMessages.value = llamaCppClient.chatSession.getAllMessages()
            } else {
                openRouterClient.chatSession.addAssistantMessage(partial)
                _chatMessages.value = openRouterClient.chatSession.getAllMessages()
            }
            _response.value = ""
        }

        if (wasActive) {
            _assistantState.value = if (hadPartialResponse) AssistantState.Complete else AssistantState.Idle
        }
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
