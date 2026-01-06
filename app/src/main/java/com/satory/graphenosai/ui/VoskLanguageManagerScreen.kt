package com.satory.graphenosai.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.satory.graphenosai.audio.VoskTranscriber
import com.satory.graphenosai.service.AssistantService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoskLanguageManagerScreen(
    onNavigateBack: () -> Unit,
    assistantService: AssistantService? = null
) {
    val context = LocalContext.current
    val voskTranscriber = remember { VoskTranscriber(context) }
    val settingsManager = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()
    
    var downloadedLanguages by remember { mutableStateOf(voskTranscriber.getDownloadedLanguages()) }
    var downloadingLanguage by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf(0) }
    var isExtracting by remember { mutableStateOf(false) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }
    
    // Calculate total downloaded size
    val totalDownloadedSize = remember(downloadedLanguages) {
        downloadedLanguages.sumOf { voskTranscriber.getModelSize(it) }
    }
    
    // Group languages by base code
    val languageGroups = remember {
        VoskTranscriber.AVAILABLE_LANGUAGES.groupBy { 
            VoskTranscriber.getBaseLanguageCode(it.code) 
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("Voice Languages")
                        Text(
                            "${downloadedLanguages.size} downloaded • ${formatSize(totalDownloadedSize)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Downloaded models section
            if (downloadedLanguages.isNotEmpty()) {
                item {
                    Text(
                        "Downloaded Models",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                
                items(
                    VoskTranscriber.AVAILABLE_LANGUAGES.filter { it.code in downloadedLanguages },
                    key = { "downloaded_${it.code}" }
                ) { language ->
                    DownloadedLanguageCard(
                        language = language,
                        modelSize = voskTranscriber.getModelSize(language.code),
                        isPrimary = language.code == settingsManager.voiceLanguage,
                        isSecondary = language.code == settingsManager.secondaryVoiceLanguage,
                        onDelete = { showDeleteDialog = language.code },
                        onSetPrimary = {
                            settingsManager.voiceLanguage = language.code
                            assistantService?.reloadSettings()
                        },
                        onSetSecondary = {
                            settingsManager.secondaryVoiceLanguage = language.code
                            assistantService?.reloadSettings()
                        }
                    )
                }
                
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
            
            // Available for download section
            item {
                Text(
                    "Available for Download",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            
            // Group by language family
            languageGroups.forEach { (_, languages) ->
                items(
                    languages.filter { it.code !in downloadedLanguages },
                    key = { "available_${it.code}" }
                ) { language ->
                    AvailableLanguageCard(
                        language = language,
                        isDownloading = downloadingLanguage == language.code,
                        downloadProgress = if (downloadingLanguage == language.code) downloadProgress else 0,
                        isExtracting = isExtracting && downloadingLanguage == language.code,
                        error = if (downloadingLanguage == language.code) downloadError else null,
                        onDownload = {
                            downloadingLanguage = language.code
                            downloadError = null
                            downloadProgress = 0
                            isExtracting = false
                            
                            scope.launch {
                                voskTranscriber.downloadModel(language.code).collect { state ->
                                    when (state) {
                                        is VoskTranscriber.DownloadState.Downloading -> {
                                            downloadProgress = state.progress
                                            isExtracting = false
                                        }
                                        is VoskTranscriber.DownloadState.Extracting -> {
                                            downloadProgress = state.progress
                                            isExtracting = true
                                        }
                                        is VoskTranscriber.DownloadState.Complete -> {
                                            downloadingLanguage = null
                                            downloadedLanguages = voskTranscriber.getDownloadedLanguages()
                                            assistantService?.reloadSettings()
                                        }
                                        is VoskTranscriber.DownloadState.Error -> {
                                            downloadError = state.message
                                        }
                                        else -> {}
                                    }
                                }
                            }
                        },
                        onCancel = {
                            downloadingLanguage = null
                            downloadError = null
                        }
                    )
                }
            }
        }
    }
    
    // Delete confirmation dialog
    showDeleteDialog?.let { langCode ->
        val language = VoskTranscriber.getLanguageByCode(langCode)
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("Delete ${language.displayName}?") },
            text = { 
                Text("This will free up ${formatSize(voskTranscriber.getModelSize(langCode))} of storage. You can re-download it later.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        voskTranscriber.deleteModel(langCode)
                        downloadedLanguages = voskTranscriber.getDownloadedLanguages()
                        showDeleteDialog = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun DownloadedLanguageCard(
    language: VoskTranscriber.LanguageModel,
    modelSize: Long,
    isPrimary: Boolean,
    isSecondary: Boolean,
    onDelete: () -> Unit,
    onSetPrimary: () -> Unit,
    onSetSecondary: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isPrimary -> MaterialTheme.colorScheme.primaryContainer
                isSecondary -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            language.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (isPrimary) {
                            Spacer(modifier = Modifier.width(8.dp))
                            AssistChip(
                                onClick = {},
                                label = { Text("Primary", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(24.dp)
                            )
                        }
                        if (isSecondary) {
                            Spacer(modifier = Modifier.width(8.dp))
                            AssistChip(
                                onClick = {},
                                label = { Text("Secondary", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(24.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        formatSize(modelSize) + if (language.isFullSize) " • Full quality" else " • Compact",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            
            if (!isPrimary || !isSecondary) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!isPrimary) {
                        OutlinedButton(
                            onClick = onSetPrimary,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Set Primary")
                        }
                    }
                    if (!isSecondary) {
                        OutlinedButton(
                            onClick = onSetSecondary,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Set Secondary")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AvailableLanguageCard(
    language: VoskTranscriber.LanguageModel,
    isDownloading: Boolean,
    downloadProgress: Int,
    isExtracting: Boolean,
    error: String?,
    onDownload: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        language.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        formatSize(language.sizeBytes) + if (language.isFullSize) " • Full quality" else " • Compact",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                if (!isDownloading) {
                    Button(onClick = onDownload) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Download")
                    }
                }
            }
            
            if (isDownloading) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { downloadProgress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (isExtracting) "Extracting... $downloadProgress%"
                        else "Downloading... $downloadProgress%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onCancel) {
                        Text("Cancel")
                    }
                }
            }
            
            error?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }
}
