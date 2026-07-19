package com.satory.graphenosai.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.satory.graphenosai.llm.LocalModelManager
import com.satory.graphenosai.service.AssistantService
import com.satory.graphenosai.service.AssistantState
import com.satory.graphenosai.ui.theme.AiintegratedintoandroidTheme
import com.satory.graphenosai.util.PdfExtractor
import com.satory.graphenosai.util.PdfResult
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class CompactAssistantActivity : ComponentActivity() {

    private var assistantService: AssistantService? = null
    private var bound = false
    private val serviceState = mutableStateOf<AssistantService?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AssistantService.AssistantBinder
            assistantService = binder.getService()
            serviceState.value = assistantService
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            assistantService = null
            serviceState.value = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AiintegratedintoandroidTheme {
                val service by serviceState
                var isExpanded by remember { mutableStateOf(false) }
                var showHistory by remember { mutableStateOf(false) }

                if (service != null) {
                    val state by service!!.assistantState.collectAsStateWithLifecycle()
                    val response by service!!.response.collectAsStateWithLifecycle()
                    val messages by service!!.chatMessages.collectAsStateWithLifecycle()
                    val transcription by service!!.transcription.collectAsStateWithLifecycle()
                    val pendingUrls by service!!.pendingUrls.collectAsStateWithLifecycle()
                    val webSearchEnabled by service!!.webSearchEnabled.collectAsStateWithLifecycle()

                    if (pendingUrls.isNotEmpty()) {
                        UrlSelectionDialog(
                            urls = pendingUrls,
                            onUrlSelected = { url -> service!!.openUrl(url) },
                            onDismiss = { service!!.clearPendingUrls() }
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                if (isExpanded || showHistory) MaterialTheme.colorScheme.background
                                else Color.Black.copy(alpha = 0.4f)
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = !isExpanded && !showHistory,
                                onClick = { finish() }
                            )
                    ) {
                        when {
                            showHistory -> {
                                ChatHistoryScreen(
                                    service = service!!,
                                    onChatSelected = { chatId ->
                                        service!!.loadChatFromHistory(chatId)
                                        showHistory = false
                                        isExpanded = true
                                    },
                                    onBack = { showHistory = false },
                                    onDismiss = { finish() }
                                )
                            }
                            isExpanded -> {
                                FullChatScreen(
                                    service = service!!,
                                    state = state,
                                    response = response,
                                    transcription = transcription,
                                    messages = messages,
                                    webSearchEnabled = webSearchEnabled,
                                    onToggleWebSearch = { service!!.toggleWebSearch() },
                                    onCollapse = { isExpanded = false },
                                    onDismiss = { finish() },
                                    onShowHistory = { showHistory = true }
                                )
                            }
                            else -> {
                                CompactAssistantView(
                                    service = service!!,
                                    state = state,
                                    transcription = transcription,
                                    response = response,
                                    onExpand = { isExpanded = true },
                                    onDismiss = { finish() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!bound) {
            Intent(this, AssistantService::class.java).also { intent ->
                bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
    }
}

@Composable
fun CompactAssistantView(
    service: AssistantService,
    state: AssistantState,
    transcription: String,
    response: String,
    onExpand: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()
    var textInput by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    var selectedImageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedImageBase64 by remember { mutableStateOf<String?>(null) }

    var pdfText by remember { mutableStateOf<String?>(null) }
    var pdfSummary by remember { mutableStateOf<String?>(null) }
    var pdfLoading by remember { mutableStateOf(false) }
    var pdfError by remember { mutableStateOf<String?>(null) }

    val webSearchEnabled by service.webSearchEnabled.collectAsStateWithLifecycle()
    val isLocalProvider = service.settingsManager.apiProvider == SettingsManager.PROVIDER_LOCAL

    val autoStartVoice = settingsManager.autoStartVoice
    LaunchedEffect(Unit) {
        if (autoStartVoice && state is AssistantState.Idle) {
            service.startVoiceCapture()
        }
    }

    val currentModel = if (isLocalProvider) service.settingsManager.localModelId else service.settingsManager.selectedModel
    val currentModelInfo = if (isLocalProvider) null else {
        SettingsManager.AVAILABLE_MODELS.find { it.id == currentModel }
    }
    val supportsVision = when {
        isLocalProvider -> false
        else -> service.isVisionCapable()
    }
    val isGenerating = state is AssistantState.Processing ||
        state is AssistantState.Searching ||
        state is AssistantState.Responding ||
        state is AssistantState.Speaking

    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            pdfLoading = true
            pdfError = null
            scope.launch {
                when (val result = PdfExtractor.extractText(context, it)) {
                    is PdfResult.Success -> {
                        pdfText = result.text
                        pdfSummary = result.summary
                        pdfError = null
                    }
                    is PdfResult.Error -> {
                        pdfError = result.message
                        pdfText = null
                        pdfSummary = null
                    }
                }
                pdfLoading = false
            }
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (bitmap == null) return@let

                val maxSize = 1024
                val scaledBitmap = if (bitmap.width > maxSize || bitmap.height > maxSize) {
                    val ratio = minOf(maxSize.toFloat() / bitmap.width, maxSize.toFloat() / bitmap.height)
                    Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
                } else bitmap

                selectedImageBitmap = scaledBitmap
                val outputStream = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                val base64Data = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                selectedImageBase64 = base64Data
            } catch (e: Exception) { }
        }
    }

    val displayTranscription = if (state is AssistantState.Listening && transcription.isNotEmpty()) {
        "You: $transcription"
    } else if (transcription.isNotEmpty() && (state is AssistantState.Processing || state is AssistantState.Searching)) {
        "You: $transcription"
    } else ""

    val displayResponse = when {
        response.isNotEmpty() -> response
        state is AssistantState.Error -> state.message
        else -> ""
    }

    val statusText = when (state) {
        is AssistantState.Listening -> if (transcription.isEmpty()) "Listening..." else ""
        is AssistantState.Processing -> {
            if (isLocalProvider && !service.isLocalModelReady()) "Loading model..." else "Thinking..."
        }
        is AssistantState.Searching -> "Searching web..."
        is AssistantState.Responding -> ""
        is AssistantState.Speaking -> ""
        else -> ""
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        val sheetMaxHeight = maxHeight * 0.92f
        val responseMaxHeight = if (maxHeight < 420.dp) 120.dp else 200.dp

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .imePadding()
                .navigationBarsPadding()
                .heightIn(max = sheetMaxHeight)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
            shape = RoundedCornerShape(36.dp),
            shadowElevation = 24.dp,
            tonalElevation = 3.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Surface(
                        modifier = Modifier.width(36.dp).height(4.dp),
                        shape = RoundedCornerShape(2.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    ) {}
                }

                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.size(34.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            tonalElevation = 0.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            "AI Assistant",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    Row {
                        IconButton(onClick = onExpand) {
                            Icon(Icons.Default.OpenInFull, "Expand",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, "Close",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                if (displayTranscription.isNotEmpty() || displayResponse.isNotEmpty() || statusText.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .heightIn(max = responseMaxHeight)
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(14.dp)
                            .verticalScroll(scrollState)
                    ) {
                        if (displayTranscription.isNotEmpty()) {
                            Text(
                                displayTranscription,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (displayResponse.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }

                        if (statusText.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    statusText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (displayResponse.isNotEmpty()) {
                            if (state is AssistantState.Error) {
                                Text(
                                    displayResponse,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else {
                                MarkdownText(
                                    text = displayResponse,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    linkColor = MaterialTheme.colorScheme.primary,
                                    onLinkClick = { url ->
                                        try {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                            context.startActivity(intent)
                                        } catch (e: Exception) { }
                                    }
                                )
                            }
                        }
                    }

                    LaunchedEffect(displayResponse, displayTranscription) {
                        scrollState.animateScrollTo(scrollState.maxValue)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                selectedImageBitmap?.let { bitmap ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Selected image",
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Image attached",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            selectedImageBitmap = null
                            selectedImageBase64 = null
                        }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, "Remove", modifier = Modifier.size(16.dp))
                        }
                    }
                }

                if (pdfLoading) {
                    Row(
                        modifier = Modifier.padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Loading PDF...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                pdfError?.let { error ->
                    Text(error, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp))
                }

                pdfSummary?.let { summary ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = "PDF",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(summary, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            pdfText = null; pdfSummary = null; pdfError = null
                        }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, "Remove", modifier = Modifier.size(16.dp))
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (supportsVision) {
                        IconButton(
                            onClick = { imagePickerLauncher.launch("image/*") },
                            enabled = !isGenerating,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.Image, "Attach image",
                                tint = if (selectedImageBase64 != null) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    IconButton(
                        onClick = { pdfPickerLauncher.launch("application/pdf") },
                        enabled = !isGenerating,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.PictureAsPdf, "Attach PDF",
                            tint = if (pdfText != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    if (!isLocalProvider) {
                        IconButton(
                            onClick = { service.toggleWebSearch() },
                            enabled = !isGenerating,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                if (webSearchEnabled) Icons.Default.TravelExplore else Icons.Default.Public,
                                "Toggle web search",
                                tint = if (webSearchEnabled) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        }
                    }

                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Ask anything...") },
                        shape = RoundedCornerShape(28.dp),
                        singleLine = true,
                        enabled = !isGenerating,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                        )
                    )

                    FilledIconButton(
                        onClick = {
                            when {
                                isGenerating -> service.stopResponse()
                                state is AssistantState.Listening -> service.stopVoiceCapture()
                                else -> service.startVoiceCapture()
                            }
                        },
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (isGenerating || state is AssistantState.Listening)
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            when {
                                isGenerating || state is AssistantState.Listening -> Icons.Default.Stop
                                else -> Icons.Default.Mic
                            },
                            if (isGenerating) "Stop response" else "Voice",
                            tint = if (isGenerating || state is AssistantState.Listening)
                                MaterialTheme.colorScheme.onError
                            else MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    AnimatedVisibility(visible = !isGenerating && (textInput.isNotEmpty() || selectedImageBase64 != null || pdfText != null)) {
                        FilledIconButton(
                            onClick = {
                                val currentText = textInput
                                val currentImageBase64 = selectedImageBase64
                                val currentPdfText = pdfText

                                val queryText = if (currentPdfText != null) {
                                    val userQuery = currentText.ifBlank { "Summarize this document" }
                                    "Document content:\n---\n$currentPdfText\n---\n\nUser question: $userQuery"
                                } else {
                                    currentText.ifBlank { "What's in this image?" }
                                }

                                service.processTextQuery(queryText, currentImageBase64)
                                textInput = ""
                                selectedImageBitmap = null
                                selectedImageBase64 = null
                                pdfText = null
                                pdfSummary = null
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Send",
                                tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullChatScreen(
    service: AssistantService,
    state: AssistantState,
    response: String,
    transcription: String,
    messages: List<com.satory.graphenosai.llm.ChatSession.Message>,
    webSearchEnabled: Boolean,
    onToggleWebSearch: () -> Unit,
    onCollapse: () -> Unit,
    onDismiss: () -> Unit,
    onShowHistory: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var textInput by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    var selectedImageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedImageBase64 by remember { mutableStateOf<String?>(null) }

    var pdfText by remember { mutableStateOf<String?>(null) }
    var pdfSummary by remember { mutableStateOf<String?>(null) }
    var pdfLoading by remember { mutableStateOf(false) }
    var pdfError by remember { mutableStateOf<String?>(null) }

    var showModelDialog by remember { mutableStateOf(false) }
    var currentModel by remember {
        mutableStateOf(
            if (service.settingsManager.apiProvider == SettingsManager.PROVIDER_LOCAL)
                service.settingsManager.localModelId
            else service.settingsManager.getEffectiveModel()
        )
    }
    val isLocalProvider = service.settingsManager.apiProvider == SettingsManager.PROVIDER_LOCAL
    val currentModelInfo = if (isLocalProvider) null else {
        SettingsManager.AVAILABLE_MODELS.find { it.id == currentModel }
    }
    val currentModelDisplayName = if (isLocalProvider) {
        LocalModelManager.AVAILABLE_MODELS.find { it.id == currentModel }?.name ?: currentModel
    } else {
        currentModelInfo?.name ?: currentModel
    }
    val supportsVision = when {
        isLocalProvider -> false
        else -> service.isVisionCapable()
    }
    val isGenerating = state is AssistantState.Processing ||
        state is AssistantState.Searching ||
        state is AssistantState.Responding ||
        state is AssistantState.Speaking

    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            pdfLoading = true
            pdfError = null
            scope.launch {
                when (val result = PdfExtractor.extractText(context, it)) {
                    is PdfResult.Success -> {
                        pdfText = result.text
                        pdfSummary = result.summary
                        pdfError = null
                    }
                    is PdfResult.Error -> {
                        pdfError = result.message
                        pdfText = null
                        pdfSummary = null
                    }
                }
                pdfLoading = false
            }
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                if (bitmap == null) return@let

                val maxSize = 1024
                val scaledBitmap = if (bitmap.width > maxSize || bitmap.height > maxSize) {
                    val ratio = minOf(maxSize.toFloat() / bitmap.width, maxSize.toFloat() / bitmap.height)
                    Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
                } else bitmap

                selectedImageBitmap = scaledBitmap
                val outputStream = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                val base64Data = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                selectedImageBase64 = base64Data
            } catch (e: Exception) { }
        }
    }

    val currentUserInput = if (transcription.isNotEmpty() &&
        (state is AssistantState.Listening || state is AssistantState.Processing || state is AssistantState.Searching)) {
        transcription
    } else null

    if (showModelDialog) {
        if (isLocalProvider) {
            val localModelManager = remember { LocalModelManager(context) }
            AlertDialog(
                onDismissRequest = { showModelDialog = false },
                title = { Text("Select Local Model") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        LocalModelManager.AVAILABLE_MODELS.forEach { model ->
                            val isDownloaded = localModelManager.isModelDownloaded(model.id)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = isDownloaded) {
                                        currentModel = model.id
                                        service.settingsManager.localModelId = model.id
                                        service.reloadSettings()
                                        showModelDialog = false
                                    }
                                    .padding(vertical = 12.dp)
                                    .alpha(if (isDownloaded) 1f else 0.5f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = currentModel == model.id,
                                    onClick = {
                                        if (isDownloaded) {
                                            currentModel = model.id
                                            service.settingsManager.localModelId = model.id
                                            service.reloadSettings()
                                            showModelDialog = false
                                        }
                                    },
                                    enabled = isDownloaded
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(model.name, style = MaterialTheme.typography.bodyLarge)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Icon(
                                            imageVector = if (isDownloaded) Icons.Default.CheckCircle
                                                else Icons.Default.CloudDownload,
                                            contentDescription = if (isDownloaded) "Downloaded" else "Not downloaded",
                                            modifier = Modifier.size(16.dp),
                                            tint = if (isDownloaded) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        if (isDownloaded) model.description else "${model.description} (Not downloaded)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showModelDialog = false }) { Text("Cancel") }
                }
            )
        } else {
            var showCustomInput by remember { mutableStateOf(false) }
            val cloudModelList = SettingsManager.AVAILABLE_MODELS

            if (showCustomInput) {
                var customModelText by remember { mutableStateOf(service.settingsManager.customModelId) }
                AlertDialog(
                    onDismissRequest = { showCustomInput = false },
                    title = { Text("Custom Model ID") },
                    text = {
                        Column {
                            Text("Enter any OpenRouter model ID",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = customModelText,
                                onValueChange = { customModelText = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Model ID") },
                                placeholder = { Text("provider/model-name") },
                                singleLine = true
                            )
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            val trimmed = customModelText.trim()
                            if (trimmed.isNotBlank()) {
                                currentModel = trimmed
                                service.settingsManager.customModelId = trimmed
                                service.reloadSettings()
                            }
                            showCustomInput = false
                            showModelDialog = false
                        }) { Text("Save") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showCustomInput = false }) { Text("Cancel") }
                    }
                )
            } else {
                AlertDialog(
                    onDismissRequest = { showModelDialog = false },
                    title = { Text("Select Model") },
                    text = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            cloudModelList.forEach { model ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            currentModel = model.id
                                            service.settingsManager.selectedModel = model.id
                                            service.settingsManager.customModelId = ""
                                            service.reloadSettings()
                                            showModelDialog = false
                                        }
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = currentModel == model.id,
                                        onClick = {
                                            currentModel = model.id
                                            service.settingsManager.selectedModel = model.id
                                            service.settingsManager.customModelId = ""
                                            service.reloadSettings()
                                            showModelDialog = false
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(model.name, style = MaterialTheme.typography.bodyLarge)
                                            if (model.supportsVision) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Icon(Icons.Default.Image, contentDescription = "Supports images",
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                        Text(model.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { showCustomInput = true }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = service.settingsManager.customModelId.isNotBlank(),
                                    onClick = { showCustomInput = true }
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("Custom model...",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.primary)
                                    Text(
                                        if (service.settingsManager.customModelId.isNotBlank())
                                            service.settingsManager.customModelId
                                        else "Enter any OpenRouter model ID",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showModelDialog = false }) { Text("Cancel") }
                    }
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.clickable { showModelDialog = true }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(currentModelDisplayName,
                                style = MaterialTheme.typography.titleMedium)
                            Icon(Icons.Default.ArrowDropDown, "Change model",
                                modifier = Modifier.size(20.dp))
                        }
                        Text(
                            when (state) {
                                AssistantState.Idle -> "Ready"
                                AssistantState.Listening -> "Listening..."
                                AssistantState.Processing -> "Thinking..."
                                AssistantState.Searching -> "Searching..."
                                AssistantState.Responding -> "Typing..."
                                AssistantState.Speaking -> "Speaking..."
                                AssistantState.Complete -> "Done"
                                is AssistantState.Error -> "Error"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onCollapse) {
                        Icon(Icons.Default.KeyboardArrowDown, "Collapse")
                    }
                },
                actions = {
                    if (isGenerating) {
                        FilledTonalIconButton(onClick = { service.stopResponse() }) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = "Stop response",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    IconButton(onClick = onShowHistory) {
                        Icon(Icons.Default.History, "Chat History")
                    }
                    IconButton(onClick = { service.clearSession() }) {
                        Icon(Icons.Default.Refresh, "New Chat")
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .imePadding()
                        .navigationBarsPadding()
                ) {
                    selectedImageBitmap?.let { bitmap ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Selected image",
                                modifier = Modifier.size(60.dp).clip(RoundedCornerShape(10.dp))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Image attached",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(onClick = {
                                selectedImageBitmap = null
                                selectedImageBase64 = null
                            }) {
                                Icon(Icons.Default.Close, "Remove image", modifier = Modifier.size(20.dp))
                            }
                        }
                    }

                    if (pdfLoading) {
                        Row(modifier = Modifier.padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Loading PDF...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    pdfError?.let { error ->
                        Text(error, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp))
                    }

                    pdfSummary?.let { summary ->
                        Row(modifier = Modifier.padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PictureAsPdf, "PDF",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(summary, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(onClick = {
                                pdfText = null; pdfSummary = null; pdfError = null
                            }) {
                                Icon(Icons.Default.Close, "Remove PDF", modifier = Modifier.size(20.dp))
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (supportsVision) {
                            IconButton(
                                onClick = { imagePickerLauncher.launch("image/*") },
                                enabled = !isGenerating
                            ) {
                                Icon(Icons.Default.Image, "Attach image",
                                    tint = if (selectedImageBase64 != null) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        IconButton(
                            onClick = { pdfPickerLauncher.launch("application/pdf") },
                            enabled = !isGenerating
                        ) {
                            Icon(Icons.Default.PictureAsPdf, "Attach PDF",
                                tint = if (pdfText != null) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        if (!isLocalProvider) {
                            IconButton(
                                onClick = onToggleWebSearch,
                                enabled = !isGenerating
                            ) {
                                Icon(
                                    if (webSearchEnabled) Icons.Default.TravelExplore else Icons.Default.Public,
                                    "Toggle web search",
                                    tint = if (webSearchEnabled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                            }
                        }

                        OutlinedTextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text(if (isGenerating) "Generating..." else "Ask anything...") },
                            shape = RoundedCornerShape(24.dp),
                            singleLine = true,
                            enabled = !isGenerating
                        )

                        FilledIconButton(
                            onClick = {
                                when {
                                    isGenerating -> service.stopResponse()
                                    state is AssistantState.Listening -> service.stopVoiceCapture()
                                    else -> service.startVoiceCapture()
                                }
                            },
                            modifier = Modifier.size(48.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = if (isGenerating || state is AssistantState.Listening)
                                    MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                if (isGenerating || state is AssistantState.Listening) Icons.Default.Stop else Icons.Default.Mic,
                                if (isGenerating) "Stop response" else "Voice",
                                tint = if (isGenerating || state is AssistantState.Listening)
                                    MaterialTheme.colorScheme.onError
                                else MaterialTheme.colorScheme.onPrimary)
                        }

                        AnimatedVisibility(visible = !isGenerating && (textInput.isNotEmpty() || selectedImageBase64 != null || pdfText != null)) {
                            FilledIconButton(
                                onClick = {
                                    val currentText = textInput
                                    val currentImageBase64 = selectedImageBase64
                                    val currentPdfText = pdfText

                                    val queryText = if (currentPdfText != null) {
                                        val userQuery = currentText.ifBlank { "Summarize this document" }
                                        "Document content:\n---\n$currentPdfText\n---\n\nUser question: $userQuery"
                                    } else {
                                        currentText.ifBlank { "What's in this image?" }
                                    }

                                    service.processTextQuery(queryText, currentImageBase64)
                                    textInput = ""
                                    selectedImageBitmap = null
                                    selectedImageBase64 = null
                                    pdfText = null
                                    pdfSummary = null
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, "Send",
                                    tint = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (messages.isEmpty() && response.isEmpty() && currentUserInput == null) {
                WelcomeMessage()
            }

            messages.forEach { message ->
                MessageBubble(
                    isUser = message.role == "user",
                    content = message.content,
                    imageBase64 = message.imageBase64
                )
            }

            if (currentUserInput != null) {
                MessageBubble(isUser = true, content = currentUserInput)
            }

            if (state is AssistantState.Processing || state is AssistantState.Searching) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        tonalElevation = 1.dp,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                if (state is AssistantState.Searching) "Searching..." else "Thinking...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            if (response.isNotEmpty() && state is AssistantState.Responding) {
                MessageBubble(isUser = false, content = response, isStreaming = true)
            }
        }

        LaunchedEffect(messages.size, response, currentUserInput) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }
}

@Composable
fun WelcomeMessage() {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 1.dp,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    "How can I help you?",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Ask anything or use voice input",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun MessageBubble(
    isUser: Boolean,
    content: String,
    isStreaming: Boolean = false,
    imageBase64: String? = null
) {
    val context = LocalContext.current

    val imageBitmap = remember(imageBase64) {
        imageBase64?.let {
            try {
                val bytes = Base64.decode(it, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) { null }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = RoundedCornerShape(
                topStart = if (isUser) 20.dp else 4.dp,
                topEnd = if (isUser) 4.dp else 20.dp,
                bottomStart = 20.dp,
                bottomEnd = 20.dp
            ),
            tonalElevation = if (isUser) 0.dp else 1.dp,
            color = if (isUser) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                imageBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Attached image",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                    if (content.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                Row(verticalAlignment = Alignment.Top) {
                    if (isUser) {
                        Text(
                            text = content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        MarkdownText(
                            text = content,
                            color = MaterialTheme.colorScheme.onSurface,
                            linkColor = MaterialTheme.colorScheme.primary,
                            onLinkClick = { url ->
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    context.startActivity(intent)
                                } catch (e: Exception) { }
                            }
                        )
                    }
                    if (isStreaming) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("▌", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun UrlSelectionDialog(
    urls: List<String>,
    onUrlSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open Link") },
        text = {
            Column {
                Text("Which link would you like to open?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(16.dp))

                urls.forEachIndexed { index, url ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { onUrlSelected(url) },
                        shape = RoundedCornerShape(12.dp),
                        tonalElevation = 1.dp,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = url.take(50) + if (url.length > 50) "..." else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHistoryScreen(
    service: AssistantService,
    onChatSelected: (String) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit
) {
    var chats by remember { mutableStateOf(service.chatHistoryManager.getSavedChats()) }
    var chatToDelete by remember { mutableStateOf<String?>(null) }

    chatToDelete?.let { chatId ->
        AlertDialog(
            onDismissRequest = { chatToDelete = null },
            title = { Text("Delete Chat?") },
            text = { Text("This chat will be permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        service.chatHistoryManager.deleteChat(chatId)
                        chats = service.chatHistoryManager.getSavedChats()
                        chatToDelete = null
                    }
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { chatToDelete = null }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chat History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        if (chats.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.History, contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("No saved chats",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Your chat history will appear here",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                chats.forEach { chat ->
                    ChatHistoryItem(
                        chat = chat,
                        onClick = { onChatSelected(chat.id) },
                        onDelete = { chatToDelete = chat.id }
                    )
                }
            }
        }
    }
}

@Composable
fun ChatHistoryItem(
    chat: com.satory.graphenosai.storage.ChatHistoryManager.ChatSummary,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(chat.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1)
                Spacer(modifier = Modifier.height(4.dp))
                Text(chat.preview.ifEmpty { "No preview available" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${chat.messageCount} messages",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(chat.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, "Delete",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp))
            }
        }
    }
}
