package com.satory.graphenosai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.satory.graphenosai.AssistantApplication
import com.satory.graphenosai.audio.VoskTranscriber
import com.satory.graphenosai.llm.LocalModelManager
import com.satory.graphenosai.service.AssistantService
import com.satory.graphenosai.tts.TTSManager
import com.satory.graphenosai.BuildConfig
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("DEPRECATION")
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    assistantService: AssistantService? = null,
    onNavigateToLanguages: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val app = context.applicationContext as AssistantApplication
    val settingsManager = remember { SettingsManager(context) }
    val voskTranscriber = remember { VoskTranscriber(context) }
    val scope = rememberCoroutineScope()

    var selectedModel by remember { mutableStateOf(settingsManager.selectedModel) }
    var customModelId by remember { mutableStateOf(settingsManager.customModelId) }
    var systemPrompt by remember { mutableStateOf(settingsManager.systemPrompt) }
    var voiceInputMethod by remember { mutableStateOf(settingsManager.voiceInputMethod) }
    var ttsEnabled by remember { mutableStateOf(settingsManager.ttsEnabled) }
    var autoSendVoice by remember { mutableStateOf(settingsManager.autoSendVoice) }
    var autoStartVoice by remember { mutableStateOf(settingsManager.autoStartVoice) }
    var apiProvider by remember { mutableStateOf(settingsManager.apiProvider) }
    var multilingualEnabled by remember { mutableStateOf(settingsManager.multilingualEnabled) }
    var secondaryLanguage by remember { mutableStateOf(settingsManager.secondaryVoiceLanguage) }

    var showModelDialog by remember { mutableStateOf(false) }
    var showCustomModelDialog by remember { mutableStateOf(false) }
    var showLocalModelDialog by remember { mutableStateOf(false) }
    var showPromptDialog by remember { mutableStateOf(false) }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var showBraveKeyDialog by remember { mutableStateOf(false) }
    var showExaKeyDialog by remember { mutableStateOf(false) }
    var showLangSearchKeyDialog by remember { mutableStateOf(false) }
    var showSearchEngineDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showSecondaryLanguageDialog by remember { mutableStateOf(false) }
    var hasApiKey by remember { mutableStateOf(app.secureKeyManager.hasOpenRouterApiKey()) }
    var hasBraveApiKey by remember { mutableStateOf(app.secureKeyManager.hasBraveApiKey()) }
    var hasExaApiKey by remember { mutableStateOf(app.secureKeyManager.hasExaApiKey()) }
    var hasLangSearchApiKey by remember { mutableStateOf(app.secureKeyManager.hasLangSearchApiKey()) }
    var searchEngine by remember { mutableStateOf(settingsManager.searchEngine) }

    var selectedLanguage by remember { mutableStateOf(settingsManager.voiceLanguage) }
    var downloadedLanguages by remember { mutableStateOf(voskTranscriber.getDownloadedLanguages()) }

    var isVoskModelDownloaded by remember { mutableStateOf(!voskTranscriber.needsModelDownload(selectedLanguage)) }
    var isDownloading by remember { mutableStateOf(false) }
    var isExtracting by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    val providerLabel = when (apiProvider) {
        SettingsManager.PROVIDER_LOCAL -> "Local AI"
        else -> "OpenRouter"
    }
    val selectedModelLabel = if (apiProvider == SettingsManager.PROVIDER_LOCAL) {
        LocalModelManager.AVAILABLE_MODELS
            .find { it.id == settingsManager.localModelId }?.name ?: "Select local model"
    } else {
        if (customModelId.isNotBlank()) customModelId
        else SettingsManager.AVAILABLE_MODELS.find { it.id == selectedModel }?.name ?: selectedModel
    }
    val voiceLabel = when (voiceInputMethod) {
        SettingsManager.VOICE_INPUT_VOSK -> "Vosk offline"
        SettingsManager.VOICE_INPUT_WHISPER -> "Whisper cloud"
        else -> "Android system"
    }
    val searchLabel = when (searchEngine) {
        SettingsManager.SEARCH_BRAVE -> "Brave"
        SettingsManager.SEARCH_EXA -> "Exa"
        SettingsManager.SEARCH_LANGSEARCH -> "LangSearch"
        else -> "Brave"
    }
    val keyStatus = when (apiProvider) {
        SettingsManager.PROVIDER_LOCAL -> "Offline"
        else -> if (hasApiKey) "API key saved" else "API key needed"
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 20.dp)
            ) {
                SettingsOverviewCard(
                    provider = providerLabel,
                    model = selectedModelLabel,
                    voice = voiceLabel,
                    search = if (apiProvider == SettingsManager.PROVIDER_LOCAL) null else searchLabel,
                    keyStatus = keyStatus
                )

            SettingsSection(title = "API Configuration") {
                var showProviderDialog by remember { mutableStateOf(false) }

                val providerName = when (apiProvider) {
                    SettingsManager.PROVIDER_LOCAL -> "Local AI (Offline)"
                    else -> "OpenRouter"
                }

                SettingsItem(
                    icon = when (apiProvider) {
                        SettingsManager.PROVIDER_LOCAL -> Icons.Default.OfflineBolt
                        else -> Icons.Default.Cloud
                    },
                    title = "AI Provider",
                    subtitle = providerName,
                    onClick = { showProviderDialog = true }
                )

                if (showProviderDialog) {
                    AlertDialog(
                        onDismissRequest = { showProviderDialog = false },
                        title = { Text("Select AI Provider", style = MaterialTheme.typography.headlineSmall) },
                        text = {
                            Column {
                                listOf(
                                    Triple(SettingsManager.PROVIDER_OPENROUTER, "OpenRouter", "Cloud AI via API key"),
                                    Triple(SettingsManager.PROVIDER_LOCAL, "Local AI (Offline)", "Runs on device, no internet needed")
                                ).forEach { (provider, name, description) ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                            .clickable {
                                                apiProvider = provider
                                                settingsManager.apiProvider = provider
                                                assistantService?.reloadSettings()
                                                showProviderDialog = false
                                            }
                                            .padding(vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = apiProvider == provider,
                                            onClick = {
                                                apiProvider = provider
                                                settingsManager.apiProvider = provider
                                                assistantService?.reloadSettings()
                                                showProviderDialog = false
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(name, style = MaterialTheme.typography.bodyLarge)
                                            Text(description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showProviderDialog = false }) { Text("Cancel") }
                        }
                    )
                }

                if (apiProvider == SettingsManager.PROVIDER_OPENROUTER) {
                    SettingsItem(
                        icon = Icons.Default.Key,
                        title = "OpenRouter API Key",
                        subtitle = if (hasApiKey) "Configured ✓" else "Not configured",
                        onClick = { showApiKeyDialog = true }
                    )
                }
            }

            if (apiProvider == SettingsManager.PROVIDER_LOCAL) {
                LocalModelsSection(
                    assistantService = assistantService,
                    settingsManager = settingsManager
                )
            }

            if (apiProvider != SettingsManager.PROVIDER_LOCAL) {
                SettingsSection(title = "Web Search") {
                    val searchEngineName = when (searchEngine) {
                        SettingsManager.SEARCH_BRAVE -> "Brave Search"
                        SettingsManager.SEARCH_EXA -> "Exa AI"
                        SettingsManager.SEARCH_LANGSEARCH -> "LangSearch"
                        else -> "Brave Search"
                    }
                    SettingsItem(
                        icon = Icons.Default.Search,
                        title = "Search Engine",
                        subtitle = searchEngineName,
                        onClick = { showSearchEngineDialog = true }
                    )

                    when (searchEngine) {
                        SettingsManager.SEARCH_BRAVE -> {
                            SettingsItem(
                                icon = Icons.Default.Key,
                                title = "Brave Search API Key",
                                subtitle = if (hasBraveApiKey) "Configured ✓" else "Required • Free at brave.com/search/api",
                                onClick = { showBraveKeyDialog = true }
                            )
                        }
                        SettingsManager.SEARCH_EXA -> {
                            SettingsItem(
                                icon = Icons.Default.Key,
                                title = "Exa AI API Key",
                                subtitle = if (hasExaApiKey) "Configured ✓" else "Required • Get at dashboard.exa.ai",
                                onClick = { showExaKeyDialog = true }
                            )
                        }
                        SettingsManager.SEARCH_LANGSEARCH -> {
                            SettingsItem(
                                icon = Icons.Default.Key,
                                title = "LangSearch API Key",
                                subtitle = if (hasLangSearchApiKey) "Configured ✓" else "Required • Free at langsearch.com",
                                onClick = { showLangSearchKeyDialog = true }
                            )
                        }
                    }

                    val searchDescription = when (searchEngine) {
                        SettingsManager.SEARCH_BRAVE -> "Brave Search: Privacy-focused, 2000 free queries/month"
                        SettingsManager.SEARCH_EXA -> "Exa AI: Semantic search with AI-powered results"
                        SettingsManager.SEARCH_LANGSEARCH -> "LangSearch: Free web search API for LLM applications"
                        else -> ""
                    }
                    Text(
                        searchDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            if (apiProvider != SettingsManager.PROVIDER_LOCAL) {
                SettingsSection(title = "AI Model") {
                    val modelName = SettingsManager.AVAILABLE_MODELS
                        .find { it.id == selectedModel }?.name ?: selectedModel
                    SettingsItem(
                        icon = Icons.Default.SmartToy,
                        title = "Model",
                        subtitle = if (customModelId.isNotBlank()) "Custom: $customModelId" else modelName,
                        onClick = { showModelDialog = true }
                    )

                    SettingsItem(
                        icon = Icons.Default.Edit,
                        title = "Custom Model ID",
                        subtitle = if (customModelId.isNotBlank()) customModelId else "Not set (use list selection)",
                        onClick = { showCustomModelDialog = true }
                    )

                    SettingsItem(
                        icon = Icons.Default.Description,
                        title = "System Prompt",
                        subtitle = systemPrompt.take(50) + if (systemPrompt.length > 50) "..." else "",
                        onClick = { showPromptDialog = true }
                    )
                }
            }

            if (apiProvider == SettingsManager.PROVIDER_LOCAL) {
                SettingsSection(title = "Downloaded Models") {
                    SettingsItem(
                        icon = Icons.Default.SmartToy,
                        title = "Local Model",
                        subtitle = LocalModelManager.AVAILABLE_MODELS
                            .find { it.id == settingsManager.localModelId }?.name ?: "Select a model",
                        onClick = { showLocalModelDialog = true }
                    )
                }
            }

            if (apiProvider == SettingsManager.PROVIDER_LOCAL) {
                SettingsSection(title = "AI Configuration") {
                    SettingsItem(
                        icon = Icons.Default.Description,
                        title = "System Prompt",
                        subtitle = systemPrompt.take(50) + if (systemPrompt.length > 50) "..." else "",
                        onClick = { showPromptDialog = true }
                    )

                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.OfflineBolt, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Local AI works completely offline. No data is sent to the internet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            SettingsSection(title = "Voice Input") {
                var showVoiceMethodDialog by remember { mutableStateOf(false) }
                var whisperProvider by remember { mutableStateOf(settingsManager.whisperProvider) }
                var showWhisperProviderDialog by remember { mutableStateOf(false) }
                var hasGroqKey by remember { mutableStateOf(app.secureKeyManager.hasGroqApiKey()) }
                var showGroqKeyDialog by remember { mutableStateOf(false) }

                val voiceMethodName = when (voiceInputMethod) {
                    SettingsManager.VOICE_INPUT_VOSK -> "Vosk (Offline)"
                    SettingsManager.VOICE_INPUT_WHISPER -> "Whisper (Cloud)"
                    else -> "System"
                }
                SettingsItem(
                    icon = Icons.Default.RecordVoiceOver,
                    title = "Voice Recognition Method",
                    subtitle = voiceMethodName,
                    onClick = { showVoiceMethodDialog = true }
                )

                if (voiceInputMethod == SettingsManager.VOICE_INPUT_WHISPER) {
                    val providerName = when (whisperProvider) {
                        SettingsManager.WHISPER_OPENAI -> "OpenAI (requires API key)"
                        else -> "Groq (free, fast)"
                    }
                    SettingsItem(
                        icon = Icons.Default.Cloud,
                        title = "Whisper Provider",
                        subtitle = providerName,
                        onClick = { showWhisperProviderDialog = true }
                    )

                    if (whisperProvider == SettingsManager.WHISPER_GROQ) {
                        SettingsItem(
                            icon = Icons.Default.Key,
                            title = "Groq API Key",
                            subtitle = if (hasGroqKey) "Configured ✓" else "Free at console.groq.com",
                            onClick = { showGroqKeyDialog = true }
                        )
                    }
                }

                if (showVoiceMethodDialog) {
                    AlertDialog(
                        onDismissRequest = { showVoiceMethodDialog = false },
                        title = { Text("Voice Recognition Method", style = MaterialTheme.typography.headlineSmall) },
                        text = {
                            Column {
                                listOf(
                                    Triple(SettingsManager.VOICE_INPUT_SYSTEM, "System", "Uses Android's built-in speech recognition"),
                                    Triple(SettingsManager.VOICE_INPUT_VOSK, "Vosk (Offline)", "Fully private, works without internet"),
                                    Triple(SettingsManager.VOICE_INPUT_WHISPER, "Whisper (Cloud)", "Best accuracy, requires internet")
                                ).forEach { (method, name, description) ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                            .clickable {
                                                voiceInputMethod = method
                                                settingsManager.voiceInputMethod = method
                                                showVoiceMethodDialog = false
                                            }
                                            .padding(vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = voiceInputMethod == method,
                                            onClick = {
                                                voiceInputMethod = method
                                                settingsManager.voiceInputMethod = method
                                                showVoiceMethodDialog = false
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(name, style = MaterialTheme.typography.bodyLarge)
                                            Text(description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showVoiceMethodDialog = false }) { Text("Cancel") }
                        }
                    )
                }

                if (showWhisperProviderDialog) {
                    AlertDialog(
                        onDismissRequest = { showWhisperProviderDialog = false },
                        title = { Text("Whisper Provider", style = MaterialTheme.typography.headlineSmall) },
                        text = {
                            Column {
                                listOf(
                                    Triple(SettingsManager.WHISPER_GROQ, "Groq", "Free tier, very fast"),
                                    Triple(SettingsManager.WHISPER_OPENAI, "OpenAI", "Original, uses OpenRouter key")
                                ).forEach { (provider, name, description) ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                            .clickable {
                                                whisperProvider = provider
                                                settingsManager.whisperProvider = provider
                                                showWhisperProviderDialog = false
                                            }
                                            .padding(vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = whisperProvider == provider,
                                            onClick = {
                                                whisperProvider = provider
                                                settingsManager.whisperProvider = provider
                                                showWhisperProviderDialog = false
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(name, style = MaterialTheme.typography.bodyLarge)
                                            Text(description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showWhisperProviderDialog = false }) { Text("Cancel") }
                        }
                    )
                }

                if (showGroqKeyDialog) {
                    var groqKey by remember { mutableStateOf("") }
                    var showKey by remember { mutableStateOf(false) }

                    AlertDialog(
                        onDismissRequest = { showGroqKeyDialog = false },
                        title = { Text("Groq API Key") },
                        text = {
                            Column {
                                Text("Get a free API key at console.groq.com",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.height(16.dp))
                                OutlinedTextField(
                                    value = groqKey,
                                    onValueChange = { groqKey = it },
                                    label = { Text("API Key") },
                                    singleLine = true,
                                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = { showKey = !showKey }) {
                                            Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Toggle")
                                        }
                                    }
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                if (groqKey.isNotBlank()) {
                                    app.secureKeyManager.setGroqApiKey(groqKey)
                                    hasGroqKey = true
                                }
                                showGroqKeyDialog = false
                            }) { Text("Save") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showGroqKeyDialog = false }) { Text("Cancel") }
                        }
                    )
                }

                SettingsItem(
                    icon = Icons.Default.Language,
                    title = "Voice Languages",
                    subtitle = "${downloadedLanguages.size} downloaded • Manage Vosk models",
                    onClick = {
                        if (onNavigateToLanguages != null) {
                            onNavigateToLanguages()
                        } else {
                            showLanguageDialog = true
                        }
                    }
                )

                if (voiceInputMethod == SettingsManager.VOICE_INPUT_VOSK) {
                    val currentLang = VoskTranscriber.getLanguageByCode(selectedLanguage)
                    val isLangDownloaded = downloadedLanguages.contains(selectedLanguage)
                    SettingsItem(
                        icon = Icons.Default.Language,
                        title = "Recognition Language",
                        subtitle = "${currentLang.displayName}" + if (isLangDownloaded) " ✓" else " (download required)",
                        onClick = { showLanguageDialog = true }
                    )

                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(16.dp),
                        tonalElevation = 1.dp,
                        color = if (isLangDownloaded) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isLangDownloaded) Icons.Default.CheckCircle else Icons.Default.Download,
                                    contentDescription = null,
                                    tint = if (isLangDownloaded) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${currentLang.displayName} Model",
                                        style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        if (isLangDownloaded) "Downloaded ✓ (~${currentLang.sizeBytes / 1_000_000}MB)"
                                        else "Required for offline voice (~${currentLang.sizeBytes / 1_000_000}MB)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }

                                if (!isLangDownloaded && !isDownloading && !isExtracting) {
                                    FilledTonalButton(onClick = {
                                        isDownloading = true
                                        isExtracting = false
                                        downloadError = null
                                        scope.launch {
                                            voskTranscriber.downloadModel(selectedLanguage).collect { state ->
                                                when (state) {
                                                    is VoskTranscriber.DownloadState.Downloading -> {
                                                        isDownloading = true; isExtracting = false
                                                        downloadProgress = state.progress
                                                    }
                                                    is VoskTranscriber.DownloadState.Extracting -> {
                                                        isDownloading = false; isExtracting = true
                                                        downloadProgress = state.progress
                                                    }
                                                    is VoskTranscriber.DownloadState.Complete -> {
                                                        isDownloading = false; isExtracting = false
                                                        isVoskModelDownloaded = true
                                                        downloadedLanguages = voskTranscriber.getDownloadedLanguages()
                                                        assistantService?.reloadSettings()
                                                    }
                                                    is VoskTranscriber.DownloadState.Error -> {
                                                        isDownloading = false; isExtracting = false
                                                        downloadError = state.message
                                                    }
                                                    else -> {}
                                                }
                                            }
                                        }
                                    }) { Text("Download") }
                                }
                            }

                            if (isDownloading || isExtracting) {
                                Spacer(modifier = Modifier.height(12.dp))
                                LinearProgressIndicator(progress = { downloadProgress / 100f },
                                    modifier = Modifier.fillMaxWidth())
                                Text(
                                    if (isExtracting) "Extracting... $downloadProgress%"
                                    else "Downloading... $downloadProgress%",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp))
                                if (isExtracting) {
                                    Text("This may take a while for large models...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 2.dp))
                                }
                            }

                            downloadError?.let { error ->
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(error, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    SettingsItemWithSwitch(
                        icon = Icons.Default.Translate,
                        title = "Multilingual Mode",
                        subtitle = "Recognize mixed language speech (e.g., Russian + English)",
                        checked = multilingualEnabled,
                        onCheckedChange = {
                            multilingualEnabled = it
                            settingsManager.multilingualEnabled = it
                            assistantService?.reloadSettings()
                        }
                    )

                    if (multilingualEnabled) {
                        val secondaryLang = VoskTranscriber.getLanguageByCode(secondaryLanguage)
                        val isSecondaryDownloaded = downloadedLanguages.contains(secondaryLanguage)
                        SettingsItem(
                            icon = Icons.Default.Language,
                            title = "Secondary Language",
                            subtitle = "${secondaryLang.displayName}" + if (isSecondaryDownloaded) " ✓" else " (download required)",
                            onClick = { showSecondaryLanguageDialog = true }
                        )
                    }
                }

                SettingsItemWithSwitch(
                    icon = Icons.AutoMirrored.Default.Send,
                    title = "Auto-send after speech",
                    subtitle = "Automatically send query when speech ends",
                    checked = autoSendVoice,
                    onCheckedChange = {
                        autoSendVoice = it
                        settingsManager.autoSendVoice = it
                    }
                )

                SettingsItemWithSwitch(
                    icon = Icons.Default.Mic,
                    title = "Auto-start voice input",
                    subtitle = "Start listening when assistant opens",
                    checked = autoStartVoice,
                    onCheckedChange = {
                        autoStartVoice = it
                        settingsManager.autoStartVoice = it
                    }
                )
            }

            SettingsSection(title = "Output") {
                val ttsAvailable = remember { com.satory.graphenosai.tts.TTSManager.isTTSAvailable(context) }

                SettingsItemWithSwitch(
                    icon = Icons.Filled.VolumeUp,
                    title = "Text-to-Speech",
                    subtitle = if (ttsAvailable) "Read responses aloud" else "Not available on this device",
                    checked = ttsEnabled && ttsAvailable,
                    onCheckedChange = {
                        if (ttsAvailable) {
                            ttsEnabled = it
                            settingsManager.ttsEnabled = it
                        }
                    },
                    enabled = ttsAvailable
                )

                if (!ttsAvailable) {
                    Text(
                        "Text-to-speech is not available on this device. Install a TTS engine from the Play Store to enable this feature.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                if (ttsAvailable) {
                    var ttsLanguage by remember { mutableStateOf(settingsManager.ttsLanguage) }
                    var showTtsLanguageDialog by remember { mutableStateOf(false) }

                    val ttsLocaleInfo = com.satory.graphenosai.tts.TTSManager.getLocaleByTag(ttsLanguage)

                    SettingsItem(
                        icon = Icons.Default.Language,
                        title = "TTS Language",
                        subtitle = ttsLocaleInfo.displayName,
                        onClick = {
                            if (ttsAvailable) {
                                showTtsLanguageDialog = true
                            }
                        }
                    )

                    if (showTtsLanguageDialog) {
                        TtsLanguageSelectionDialog(
                            currentLanguage = ttsLanguage,
                            ttsManager = assistantService?.ttsManager,
                            onLanguageSelected = { tag ->
                                ttsLanguage = tag
                                settingsManager.ttsLanguage = tag
                                com.satory.graphenosai.tts.TTSManager.getLocaleByTag(tag).let { info ->
                                    Log.d("TTS", "Language selected: ${info.displayName} ($tag)")
                                }
                                assistantService?.let { service ->
                                    service.ttsManager.setLanguage(tag)
                                }
                                showTtsLanguageDialog = false
                            },
                            onDismiss = { showTtsLanguageDialog = false }
                        )
                    }
                }
            }

            SettingsSection(title = "Advanced") {
                SettingsItem(
                    icon = Icons.Default.Refresh,
                    title = "Reset to Defaults",
                    subtitle = "Reset all settings to default values",
                    onClick = {
                        settingsManager.resetToDefaults()
                        selectedModel = SettingsManager.DEFAULT_MODEL
                        systemPrompt = SettingsManager.DEFAULT_SYSTEM_PROMPT
                        voiceInputMethod = SettingsManager.VOICE_INPUT_SYSTEM
                        ttsEnabled = true
                        autoSendVoice = true
                        autoStartVoice = false
                    }
                )
            }

            SettingsSection(title = "About") {
                SettingsItem(
                    icon = Icons.Default.Info,
                    title = "AI Assistant for Android",
                    subtitle = "v${BuildConfig.VERSION_NAME}", // Dynamic version with build.gradle.kts
                    onClick = {}
                )

                SettingsItem(
                    icon = Icons.Default.Person,
                    title = "Developer",
                    subtitle = "Max",
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse("https://github.com/mx37")
                        }
                        context.startActivity(intent)
                    }
                )

                SettingsItem(
                    icon = Icons.Default.Link,
                    title = "Repository",
                    subtitle = "View on GitHub",
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse("https://github.com/mx37/grapheneos-ai")
                        }
                        context.startActivity(intent)
                    }
                )

                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Privacy-first AI assistant with OpenRouter support. All conversations are processed securely with on-device encryption.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "This is an independent project and is not affiliated with, endorsed by, or associated with GrapheneOS.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                    }
                }
            }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    if (showModelDialog) {
        ModelSelectionDialog(
            currentModel = if (customModelId.isNotBlank()) customModelId else selectedModel,
            customModelId = customModelId,
            models = SettingsManager.AVAILABLE_MODELS,
            onModelSelected = { model ->
                selectedModel = model
                settingsManager.selectedModel = model
                settingsManager.customModelId = ""
                assistantService?.reloadSettings()
                showModelDialog = false
            },
            onCustomModelSelected = { modelId ->
                customModelId = modelId
                settingsManager.customModelId = modelId
                assistantService?.reloadSettings()
                showModelDialog = false
            },
            onDismiss = { showModelDialog = false }
        )
    }

    if (showCustomModelDialog) {
        CustomModelDialog(
            currentCustomModel = customModelId,
            onModelSaved = { modelId ->
                customModelId = modelId
                settingsManager.customModelId = modelId
                assistantService?.reloadSettings()
                showCustomModelDialog = false
            },
            onDismiss = { showCustomModelDialog = false }
        )
    }

    if (showLocalModelDialog) {
        LocalModelSelectionDialog(
            currentModel = settingsManager.localModelId,
            onModelSelected = { modelId ->
                settingsManager.localModelId = modelId
                assistantService?.reloadSettings()
                showLocalModelDialog = false
            },
            onDismiss = { showLocalModelDialog = false }
        )
    }

    if (showPromptDialog) {
        SystemPromptDialog(
            currentPrompt = systemPrompt,
            onPromptSaved = { prompt ->
                systemPrompt = prompt
                settingsManager.systemPrompt = prompt
                assistantService?.reloadSettings()
                showPromptDialog = false
            },
            onDismiss = { showPromptDialog = false }
        )
    }

    if (showApiKeyDialog) {
        ApiKeyDialog(
            hasExistingKey = hasApiKey,
            onApiKeySaved = { key ->
                app.secureKeyManager.setOpenRouterApiKey(key)
                hasApiKey = true
                showApiKeyDialog = false
            },
            onDismiss = { showApiKeyDialog = false }
        )
    }

    if (showBraveKeyDialog) {
        BraveApiKeyDialog(
            hasExistingKey = hasBraveApiKey,
            onApiKeySaved = { key ->
                app.secureKeyManager.setBraveApiKey(key)
                hasBraveApiKey = true
                showBraveKeyDialog = false
            },
            onDismiss = { showBraveKeyDialog = false }
        )
    }

    if (showExaKeyDialog) {
        ExaApiKeyDialog(
            hasExistingKey = hasExaApiKey,
            onApiKeySaved = { key ->
                app.secureKeyManager.setExaApiKey(key)
                hasExaApiKey = true
                showExaKeyDialog = false
            },
            onDismiss = { showExaKeyDialog = false }
        )
    }

    if (showLangSearchKeyDialog) {
        LangSearchApiKeyDialog(
            hasExistingKey = hasLangSearchApiKey,
            onApiKeySaved = { key ->
                app.secureKeyManager.setLangSearchApiKey(key)
                hasLangSearchApiKey = true
                showLangSearchKeyDialog = false
            },
            onDismiss = { showLangSearchKeyDialog = false }
        )
    }

    if (showSearchEngineDialog) {
        SearchEngineDialog(
            currentEngine = searchEngine,
            onEngineSelected = { engine ->
                searchEngine = engine
                settingsManager.searchEngine = engine
                showSearchEngineDialog = false
            },
            onDismiss = { showSearchEngineDialog = false }
        )
    }

    if (showLanguageDialog) {
        LanguageSelectionDialog(
            currentLanguage = selectedLanguage,
            downloadedLanguages = downloadedLanguages,
            onLanguageSelected = { langCode ->
                selectedLanguage = langCode
                settingsManager.voiceLanguage = langCode
                isVoskModelDownloaded = downloadedLanguages.contains(langCode)
                assistantService?.reloadSettings()
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false }
        )
    }

    if (showSecondaryLanguageDialog) {
        LanguageSelectionDialog(
            currentLanguage = secondaryLanguage,
            downloadedLanguages = downloadedLanguages,
            onLanguageSelected = { langCode ->
                secondaryLanguage = langCode
                settingsManager.secondaryVoiceLanguage = langCode
                assistantService?.reloadSettings()
                showSecondaryLanguageDialog = false
            },
            onDismiss = { showSecondaryLanguageDialog = false }
        )
    }
}

@Composable
fun SettingsOverviewCard(
    provider: String,
    model: String,
    voice: String,
    search: String?,
    keyStatus: String
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 2.dp, end = 16.dp, bottom = 4.dp),
        shape = MaterialTheme.shapes.extraLarge,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Assistant setup",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        keyStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SettingsStatusChip(
                    icon = Icons.Default.Cloud,
                    label = provider,
                    modifier = Modifier.weight(1f)
                )
                SettingsStatusChip(
                    icon = Icons.Default.RecordVoiceOver,
                    label = voice,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            SettingsStatusChip(
                icon = Icons.Default.SmartToy,
                label = model,
                modifier = Modifier.fillMaxWidth()
            )
            if (search != null) {
                Spacer(modifier = Modifier.height(8.dp))
                SettingsStatusChip(
                    icon = Icons.Default.Search,
                    label = search,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun SettingsStatusChip(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.defaultMinSize(minHeight = 40.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.08f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp)
        )
        Surface(
            modifier = Modifier.padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 0.dp
        ) {
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                content()
            }
        }
    }
}

@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
        },
        trailingContent = {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(MaterialTheme.shapes.large)
            .defaultMinSize(minHeight = 72.dp)
            .clickable(onClick = onClick)
    )
}

@Composable
fun SettingsItemWithSwitch(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    ListItem(
        headlineContent = {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
        },
        supportingContent = {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(
                        if (enabled) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer
                          else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                    modifier = Modifier.size(20.dp)
                )
            }
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(MaterialTheme.shapes.large)
            .defaultMinSize(minHeight = 72.dp)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectionDialog(
    currentModel: String,
    customModelId: String = "",
    models: List<SettingsManager.ModelInfo> = SettingsManager.AVAILABLE_MODELS,
    onModelSelected: (String) -> Unit,
    onCustomModelSelected: (String) -> Unit = {},
    onDismiss: () -> Unit
) {
    var showCustomInput by remember { mutableStateOf(false) }

    if (showCustomInput) {
        CustomModelDialog(
            currentCustomModel = customModelId,
            onModelSaved = { modelId ->
                if (modelId.isNotBlank()) {
                    onCustomModelSelected(modelId)
                }
                showCustomInput = false
                onDismiss()
            },
            onDismiss = { showCustomInput = false }
        )
        return
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.8f),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp
        ) {
            Column {
                TopAppBar(
                    title = { Text("Select Model") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, "Close")
                        }
                    }
                )

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    val freeModels = models.filter { it.id.contains(":free") }
                    if (freeModels.isNotEmpty()) {
                        item {
                                Text(
                                    "Free Models",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(16.dp, top = 8.dp))
                            }
                            items(freeModels) { model ->
                                ModelItem(model = model, isSelected = model.id == currentModel,
                                    onClick = { onModelSelected(model.id) })
                            }
                        }

                        val premiumModels = models.filter { !it.id.contains(":free") }
                        if (premiumModels.isNotEmpty()) {
                            item {
                                Text(
                                    if (freeModels.isNotEmpty()) "Premium Models" else "Available Models",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(16.dp, top = 8.dp))
                        }
                        items(premiumModels) { model ->
                            ModelItem(model = model, isSelected = model.id == currentModel,
                                onClick = { onModelSelected(model.id) })
                        }
                    }

                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }
                    item {
                        ListItem(
                            headlineContent = {
                                Text("Custom model...",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary)
                            },
                            supportingContent = {
                                Text(if (customModelId.isNotBlank()) "Current: $customModelId"
                                    else "Enter any OpenRouter model ID")
                            },
                            leadingContent = {
                                RadioButton(
                                    selected = customModelId.isNotBlank() && currentModel == customModelId,
                                    onClick = { showCustomInput = true })
                            },
                            modifier = Modifier.clickable { showCustomInput = true }.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ModelItem(
    model: SettingsManager.ModelInfo,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(model.name, style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(model.description)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (model.supportsVision) {
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = { Text("Vision") },
                            leadingIcon = {
                                Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        )
                    }
                    if (model.id.contains(":free")) {
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = { Text("Free") },
                            leadingIcon = {
                                Icon(Icons.Default.Savings, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        )
                    }
                }
            }
        },
        leadingContent = {
            RadioButton(selected = isSelected, onClick = onClick)
        },
        colors = ListItemDefaults.colors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
    )
}

@Composable
fun SystemPromptDialog(
    currentPrompt: String,
    onPromptSaved: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var prompt by remember { mutableStateOf(currentPrompt) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.7f),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                Text("System Prompt",
                    style = MaterialTheme.typography.titleLarge)

                Spacer(modifier = Modifier.height(8.dp))

                Text("This prompt defines how the AI assistant behaves.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    label = { Text("System Prompt") },
                    placeholder = { Text("Enter instructions for the AI...") },
                    shape = RoundedCornerShape(16.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { prompt = SettingsManager.DEFAULT_SYSTEM_PROMPT },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Reset") }

                    Button(
                        onClick = { onPromptSaved(prompt) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Save") }
                }
            }
        }
    }
}

@Composable
fun ApiKeyDialog(
    hasExistingKey: Boolean,
    onApiKeySaved: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var apiKey by remember { mutableStateOf("") }
    var isVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("OpenRouter API Key") },
        text = {
            Column {
                if (hasExistingKey) {
                    Text("An API key is already configured. Enter a new key to replace it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text("Get your API key from openrouter.ai",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API Key") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    visualTransformation = if (isVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { isVisible = !isVisible }) {
                            Icon(if (isVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Toggle")
                        }
                    }
                )
            }
        },
        confirmButton = {
            Button(onClick = { onApiKeySaved(apiKey) }, enabled = apiKey.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun BraveApiKeyDialog(
    hasExistingKey: Boolean,
    onApiKeySaved: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var apiKey by remember { mutableStateOf("") }
    var isVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Brave Search API Key") },
        text = {
            Column {
                if (hasExistingKey) {
                    Text("An API key is already configured. Enter a new key to replace it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text("Brave Search API provides privacy-focused web search.\n\n• Free tier: 2,000 queries/month\n• Fast and accurate results\n• No tracking, no ads",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://brave.com/search/api/"))
                    context.startActivity(intent)
                }) {
                    Icon(Icons.AutoMirrored.Default.OpenInNew, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Get API Key at brave.com/search/api")
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API Key") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    visualTransformation = if (isVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { isVisible = !isVisible }) {
                            Icon(if (isVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Toggle")
                        }
                    }
                )
            }
        },
        confirmButton = {
            Button(onClick = { onApiKeySaved(apiKey) }, enabled = apiKey.isNotBlank()) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun CustomModelDialog(
    currentCustomModel: String,
    onModelSaved: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var modelId by remember { mutableStateOf(currentCustomModel) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom Model ID") },
        text = {
            Column {
                Text("Enter the model ID from OpenRouter (e.g., openai/gpt-5.4-mini)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Model ID") },
                    placeholder = { Text("provider/model-name") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("Leave empty to use selected model from list",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = { onModelSaved(modelId.trim()) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun LanguageSelectionDialog(
    currentLanguage: String,
    downloadedLanguages: List<String>,
    onLanguageSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.9f).padding(16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Select Voice Language",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
                ) {
                    items(VoskTranscriber.AVAILABLE_LANGUAGES) { language ->
                        val isDownloaded = downloadedLanguages.contains(language.code)
                        val isSelected = currentLanguage == language.code

                        ListItem(
                            modifier = Modifier.clickable { onLanguageSelected(language.code) }.padding(vertical = 2.dp),
                            headlineContent = {
                                Text(language.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
                            },
                            supportingContent = {
                                Text(
                                    if (isDownloaded) "Downloaded ✓" else "~${language.sizeBytes / 1_000_000}MB download",
                                    style = MaterialTheme.typography.bodySmall)
                            },
                            leadingContent = {
                                RadioButton(selected = isSelected,
                                    onClick = { onLanguageSelected(language.code) })
                            },
                            trailingContent = {
                                if (isDownloaded) {
                                    Icon(Icons.Default.CheckCircle, "Downloaded",
                                        tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
fun TtsLanguageSelectionDialog(
    currentLanguage: String,
    ttsManager: TTSManager? = null,
    onLanguageSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.9f).padding(16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Select TTS Language",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp))

                Text("Availability depends on your device's TTS engine (e.g., Google TTS).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
                ) {
                    items(TTSManager.AVAILABLE_TTS_LOCALES) { localeInfo ->
                        val isSelected = currentLanguage == localeInfo.localeTag()
                        val isAvailable = ttsManager?.isLanguageAvailable(localeInfo.locale) == true

                        ListItem(
                            modifier = Modifier
                                .clickable(enabled = isAvailable) {
                                    if (isAvailable) {
                                        onLanguageSelected(localeInfo.localeTag())
                                    }
                                }
                                .padding(vertical = 2.dp),
                            headlineContent = {
                                Text(localeInfo.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isAvailable) MaterialTheme.colorScheme.onSurface
                                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                            },
                            supportingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(localeInfo.localeTag(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isAvailable) MaterialTheme.colorScheme.onSurfaceVariant
                                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f))
                                    if (!isAvailable) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("unavailable",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
                                    }
                                }
                            },
                            leadingContent = {
                                RadioButton(
                                    selected = isSelected,
                                    enabled = isAvailable,
                                    onClick = {
                                        if (isAvailable) {
                                            onLanguageSelected(localeInfo.localeTag())
                                        }
                                    }
                                )
                            },
                            trailingContent = {
                                if (isAvailable) {
                                    Icon(Icons.Default.CheckCircle, "Available",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp))
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
fun ExaApiKeyDialog(
    hasExistingKey: Boolean,
    onApiKeySaved: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var apiKey by remember { mutableStateOf("") }
    var isVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Exa AI API Key") },
        text = {
            Column {
                if (hasExistingKey) {
                    Text("An API key is already configured. Enter a new key to replace it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text("Exa AI provides semantic search with AI-powered results.\n\n• Neural & keyword search\n• High-quality content extraction\n• Great for research queries",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://dashboard.exa.ai/api-keys"))
                    context.startActivity(intent)
                }) {
                    Icon(Icons.AutoMirrored.Default.OpenInNew, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Get API Key at dashboard.exa.ai")
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API Key") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    visualTransformation = if (isVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { isVisible = !isVisible }) {
                            Icon(if (isVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Toggle")
                        }
                    }
                )
            }
        },
        confirmButton = {
            Button(onClick = { onApiKeySaved(apiKey) }, enabled = apiKey.isNotBlank()) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun LangSearchApiKeyDialog(
    hasExistingKey: Boolean,
    onApiKeySaved: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var apiKey by remember { mutableStateOf("") }
    var isVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("LangSearch API Key") },
        text = {
            Column {
                if (hasExistingKey) {
                    Text("An API key is already configured. Enter a new key to replace it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text("LangSearch provides a free web search API for LLM applications.\n\n• Free tier available\n• Hybrid keyword + vector search\n• No credit card required",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://langsearch.com"))
                    context.startActivity(intent)
                }) {
                    Icon(Icons.AutoMirrored.Default.OpenInNew, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Get API Key at langsearch.com")
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API Key") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    visualTransformation = if (isVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { isVisible = !isVisible }) {
                            Icon(if (isVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Toggle")
                        }
                    }
                )
            }
        },
        confirmButton = {
            Button(onClick = { onApiKeySaved(apiKey) }, enabled = apiKey.isNotBlank()) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun SearchEngineDialog(
    currentEngine: String,
    onEngineSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Search Engine") },
        text = {
            Column {
                listOf(
                    Triple(SettingsManager.SEARCH_BRAVE, "Brave Search", "Privacy-focused, 2000 free queries/month"),
                    Triple(SettingsManager.SEARCH_EXA, "Exa AI", "Semantic search with AI-powered results"),
                    Triple(SettingsManager.SEARCH_LANGSEARCH, "LangSearch", "Free web search API for LLM applications")
                ).forEachIndexed { index, (engine, name, desc) ->
                    if (index > 0) HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onEngineSelected(engine) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentEngine == engine,
                            onClick = { onEngineSelected(engine) }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(name, style = MaterialTheme.typography.bodyLarge)
                            Text(desc, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun LocalModelsSection(
    assistantService: com.satory.graphenosai.service.AssistantService?,
    settingsManager: SettingsManager
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val localModelManager = remember { LocalModelManager(context) }

    var downloadedModels by remember { mutableStateOf(localModelManager.getDownloadedModels()) }
    var selectedLocalModel by remember { mutableStateOf(settingsManager.localModelId) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0) }
    var downloadingModelId by remember { mutableStateOf<String?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var showModelListDialog by remember { mutableStateOf(false) }

    fun refreshModels() {
        downloadedModels = localModelManager.getDownloadedModels()
    }

    SettingsSection(title = "Local AI Models") {
        val currentModelInfo = LocalModelManager.AVAILABLE_MODELS.find { it.id == selectedLocalModel }
        val isCurrentModelDownloaded = localModelManager.isModelDownloaded(selectedLocalModel)

        SettingsItem(
            icon = Icons.Default.SmartToy,
            title = "Active Model",
            subtitle = if (isCurrentModelDownloaded) {
                "${currentModelInfo?.name ?: selectedLocalModel} ✓"
            } else {
                "${currentModelInfo?.name ?: selectedLocalModel} (Not downloaded)"
            },
            onClick = { showModelListDialog = true }
        )

        if (isDownloading) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                val downloadingModel = LocalModelManager.AVAILABLE_MODELS.find { it.id == downloadingModelId }
                Text("Downloading ${downloadingModel?.name ?: "model"}...",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(progress = { downloadProgress / 100f },
                    modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(4.dp))
                Text("$downloadProgress%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (downloadError != null) {
            Text("$downloadError",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            downloadError = null
        }

        val totalStorage = localModelManager.getTotalStorageUsed()
        val storageText = when {
            totalStorage >= 1_000_000_000 -> String.format("%.1f GB", totalStorage / 1_000_000_000.0)
            totalStorage >= 1_000_000 -> String.format("%.1f MB", totalStorage / 1_000_000.0)
            else -> "0 MB"
        }

        Text("${downloadedModels.size} model(s) downloaded • $storageText used",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Text("Models run entirely on your device. Recommended: 8GB+ RAM for best performance.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp))
        }
    }

    if (showModelListDialog) {
        AlertDialog(
            onDismissRequest = { showModelListDialog = false },
            title = { Text("Local AI Models") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(LocalModelManager.AVAILABLE_MODELS) { model ->
                        val isDownloaded = localModelManager.isModelDownloaded(model.id)
                        val isSelected = selectedLocalModel == model.id
                        val isThisDownloading = downloadingModelId == model.id && isDownloading

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable(enabled = !isThisDownloading) {
                                    if (isDownloaded) {
                                        selectedLocalModel = model.id
                                        settingsManager.localModelId = model.id
                                        assistantService?.loadLocalModel(model.id)
                                        showModelListDialog = false
                                    } else {
                                        isDownloading = true
                                        downloadingModelId = model.id
                                        downloadError = null
                                        downloadProgress = 0

                                        scope.launch {
                                            localModelManager.downloadModel(model.id).collect { progress ->
                                                when (progress) {
                                                    is com.satory.graphenosai.llm.DownloadProgress.Downloading -> {
                                                        downloadProgress = progress.percent
                                                    }
                                                    is com.satory.graphenosai.llm.DownloadProgress.Completed -> {
                                                        isDownloading = false
                                                        downloadingModelId = null
                                                        refreshModels()
                                                        selectedLocalModel = model.id
                                                        settingsManager.localModelId = model.id
                                                        assistantService?.loadLocalModel(model.id)
                                                    }
                                                    is com.satory.graphenosai.llm.DownloadProgress.Error -> {
                                                        isDownloading = false
                                                        downloadingModelId = null
                                                        downloadError = progress.message
                                                    }
                                                    else -> {}
                                                }
                                            }
                                        }
                                    }
                                },
                            shape = RoundedCornerShape(16.dp),
                            tonalElevation = if (isSelected && isDownloaded) 2.dp else 0.dp,
                            color = if (isSelected && isDownloaded) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(model.name, style = MaterialTheme.typography.bodyLarge)
                                        if (model.recommended) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = MaterialTheme.colorScheme.tertiaryContainer
                                            ) {
                                                Text("Recommended",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                            }
                                        }
                                    }

                                    if (isDownloaded) {
                                        if (isSelected) {
                                            Icon(Icons.Default.CheckCircle, "Selected",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp))
                                        } else {
                                            Icon(Icons.Default.Download, "Downloaded",
                                                tint = MaterialTheme.colorScheme.tertiary,
                                                modifier = Modifier.size(20.dp))
                                        }
                                    } else if (isThisDownloading) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    } else {
                                        Text(model.formattedSize(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))
                                Text(model.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                                if (!isDownloaded && !isThisDownloading) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Tap to download",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModelListDialog = false }) { Text("Close") }
            },
            dismissButton = {
                if (downloadedModels.isNotEmpty()) {
                    TextButton(onClick = {
                        scope.launch {
                            downloadedModels.forEach { model ->
                                localModelManager.deleteModel(model.id)
                            }
                            refreshModels()
                        }
                    }) { Text("Delete All", color = MaterialTheme.colorScheme.error) }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalModelSelectionDialog(
    currentModel: String,
    onModelSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.8f),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp
        ) {
            Column {
                TopAppBar(
                    title = { Text("Select Local Model") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, "Close")
                        }
                    }
                )

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(LocalModelManager.AVAILABLE_MODELS) { model ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onModelSelected(model.id) }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = model.id == currentModel,
                                onClick = { onModelSelected(model.id) }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(model.name,
                                    style = MaterialTheme.typography.bodyMedium)
                                Text(model.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
