package com.satory.graphenosai

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.satory.graphenosai.service.AssistantAccessibilityService
import com.satory.graphenosai.service.AssistantService
import com.satory.graphenosai.ui.SettingsManager
import com.satory.graphenosai.ui.SettingsScreen
import com.satory.graphenosai.ui.VoskLanguageManagerScreen
import com.satory.graphenosai.ui.theme.AiintegratedintoandroidTheme
import com.satory.graphenosai.audio.VoskTranscriber
import kotlinx.coroutines.launch

/**
 * Main launcher activity - setup and configuration.
 */
class MainActivity : ComponentActivity() {

    private var assistantService: AssistantService? = null
    private var serviceBound = mutableStateOf(false)
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? AssistantService.AssistantBinder
            assistantService = localBinder?.getService()
            serviceBound.value = true
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            assistantService = null
            serviceBound.value = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (audioGranted) {
            // Permission granted, UI will update
        }
    }
    
    override fun onStart() {
        super.onStart()
        // Try to bind to AssistantService if running
        Intent(this, AssistantService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }
    
    override fun onStop() {
        super.onStop()
        if (serviceBound.value) {
            unbindService(serviceConnection)
            serviceBound.value = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request permissions on launch
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        )

        setContent {
            AiintegratedintoandroidTheme {
                val navController = rememberNavController()
                val bound by serviceBound
                
                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(
                            onOpenAccessibilitySettings = ::openAccessibilitySettings,
                            onOpenAssistantSettings = ::openAssistantSettings,
                            onLaunchAssistant = ::launchAssistant,
                            onOpenApiKeySettings = { navController.navigate("settings") },
                            onOpenSettings = { navController.navigate("settings") }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            onNavigateBack = { navController.popBackStack() },
                            assistantService = if (bound) assistantService else null,
                            onNavigateToLanguages = { navController.navigate("voice_languages") }
                        )
                    }
                    composable("voice_languages") {
                        VoskLanguageManagerScreen(
                            onNavigateBack = { navController.popBackStack() },
                            assistantService = if (bound) assistantService else null
                        )
                    }
                }
            }
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openAssistantSettings() {
        try {
            startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun launchAssistant() {
        val intent = Intent(this, AssistantService::class.java).apply {
            action = AssistantService.ACTION_ACTIVATE
        }
        startForegroundService(intent)
    }

    private fun openApiKeySettings() {
        // Handled by navigation
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAssistantSettings: () -> Unit,
    onLaunchAssistant: () -> Unit,
    onOpenApiKeySettings: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as AssistantApplication
    val settingsManager = remember { SettingsManager(context) }
    
    // Check API based on current provider
    var hasApiKeyOpenRouter by remember { mutableStateOf(app.secureKeyManager.hasOpenRouterApiKey()) }
    var hasApiKeyCopilot by remember { mutableStateOf(app.secureKeyManager.hasCopilotToken()) }
    
    val hasApiKey = if (settingsManager.apiProvider == SettingsManager.PROVIDER_COPILOT) {
        hasApiKeyCopilot
    } else {
        hasApiKeyOpenRouter
    }
    
    val apiKeyDescription = if (settingsManager.apiProvider == SettingsManager.PROVIDER_COPILOT) {
        if (hasApiKeyCopilot) "GitHub Copilot configured" else "GitHub Copilot not configured"
    } else {
        if (hasApiKeyOpenRouter) "OpenRouter configured" else "OpenRouter not configured"
    }
    
    val isAccessibilityEnabled = AssistantAccessibilityService.isServiceRunning
    
    // Check if app is default assistant
    val isDefaultAssistant = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
            roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) ?: false
        } else {
            // For older versions, can't easily check
            false
        }
    }
    
    val voskTranscriber = remember { VoskTranscriber(context) }
    var isModelDownloaded by remember { mutableStateOf(!voskTranscriber.needsModelDownload()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Assistant") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onLaunchAssistant,
                icon = { Icon(Icons.Default.Assistant, contentDescription = null) },
                text = { Text("Launch Assistant") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status Card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Setup Status",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    SetupItem(
                        title = "API Key",
                        description = apiKeyDescription,
                        isComplete = hasApiKey,
                        action = "Configure",
                        onAction = onOpenApiKeySettings
                    )
                    
                    SetupItem(
                        title = "Vosk Model",
                        description = if (isModelDownloaded) "Downloaded (~40 MB)" else "Required for voice input",
                        isComplete = isModelDownloaded,
                        action = "Download",
                        onAction = { } // Handled by separate card
                    )
                    
                    SetupItem(
                        title = "Accessibility Service",
                        description = if (isAccessibilityEnabled) "Enabled" else "Required for activation shortcuts",
                        isComplete = isAccessibilityEnabled,
                        action = "Enable",
                        onAction = onOpenAccessibilitySettings
                    )
                    
                    SetupItem(
                        title = "Default Assistant",
                        description = if (isDefaultAssistant) "Set as default ✓" else "Set as device assistant for home button activation",
                        isComplete = isDefaultAssistant,
                        action = "Set",
                        onAction = onOpenAssistantSettings
                    )
                }
            }
            
            // Model Download Card (if not downloaded)
            if (!isModelDownloaded) {
                ModelDownloadCard(
                    voskTranscriber = voskTranscriber,
                    onDownloadComplete = { isModelDownloaded = true }
                )
            }

            // Quick Actions Card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Activation Methods",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    ActivationMethod(
                        icon = Icons.Default.Home,
                        title = "Long-press Home",
                        description = "Hold home button or swipe from corner"
                    )
                    @Suppress("DEPRECATION")
                    ActivationMethod(
                        icon = Icons.Filled.VolumeUp,
                        title = "Volume Keys",
                        description = "Hold Volume Up + Down together"
                    )
                    ActivationMethod(
                        icon = Icons.Default.Dashboard,
                        title = "Quick Settings Tile",
                        description = "Add tile to notification panel"
                    )
                }
            }

            // Privacy Info Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Privacy-First Design",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "• On-device speech recognition (Vosk)\n" +
                        "• Encrypted API key storage\n" +
                        "• No device identifiers sent to cloud\n" +
                        "• Anonymized web searches via proxy\n" +
                        "• Minimal permissions required\n" +
                        "• TLS 1.3 for all network requests",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            // API Key Input (if not configured)
            if (!hasApiKey) {
                ApiKeyInputCard(
                    onApiKeySaved = { hasApiKeyOpenRouter = true }
                )
            }
        }
    }
}

@Composable
fun SetupItem(
    title: String,
    description: String,
    isComplete: Boolean,
    action: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (isComplete) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (isComplete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!isComplete) {
            TextButton(onClick = onAction) {
                Text(action)
            }
        }
    }
}

@Composable
fun ActivationMethod(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ApiKeyInputCard(onApiKeySaved: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as AssistantApplication
    
    var apiKey by remember { mutableStateOf("") }
    var isVisible by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "OpenRouter API Key",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Get your API key from openrouter.ai",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = if (isVisible) {
                    androidx.compose.ui.text.input.VisualTransformation.None
                } else {
                    androidx.compose.ui.text.input.PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { isVisible = !isVisible }) {
                        Icon(
                            if (isVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle visibility"
                        )
                    }
                }
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Button(
                onClick = {
                    if (apiKey.isNotBlank()) {
                        app.secureKeyManager.setOpenRouterApiKey(apiKey)
                        apiKey = ""
                        onApiKeySaved()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = apiKey.isNotBlank()
            ) {
                Text("Save Securely")
            }
        }
    }
}

@Composable
fun ModelDownloadCard(
    voskTranscriber: VoskTranscriber,
    onDownloadComplete: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var downloadState by remember { mutableStateOf<VoskTranscriber.DownloadState>(VoskTranscriber.DownloadState.NotStarted) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Download Vosk Model",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                "Required for on-device voice recognition. The model (~40 MB) will be downloaded once and stored locally.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            when (val state = downloadState) {
                is VoskTranscriber.DownloadState.NotStarted -> {
                    Button(
                        onClick = {
                            scope.launch {
                                voskTranscriber.downloadModel().collect { newState ->
                                    downloadState = newState
                                    if (newState is VoskTranscriber.DownloadState.Complete) {
                                        onDownloadComplete()
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Download Model (~40 MB)")
                    }
                }
                
                is VoskTranscriber.DownloadState.Downloading -> {
                    Column {
                        LinearProgressIndicator(
                            progress = { state.progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Downloading: ${state.progress}% (${state.bytesDownloaded / 1_000_000} MB / ${state.totalBytes / 1_000_000} MB)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                
                is VoskTranscriber.DownloadState.Extracting -> {
                    Column {
                        LinearProgressIndicator(
                            progress = { state.progress / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Extracting: ${state.progress}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                
                is VoskTranscriber.DownloadState.Complete -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Download complete!",
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                
                is VoskTranscriber.DownloadState.Error -> {
                    Column {
                        Text(
                            "Error: ${state.message}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                downloadState = VoskTranscriber.DownloadState.NotStarted
                            }
                        ) {
                            Text("Retry")
                        }
                    }
                }
            }
        }
    }
}