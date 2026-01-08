package com.satory.graphenosai.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileInputStream

/**
 * Vosk-based offline speech recognition with multi-language support.
 * Works without Google Services - perfect for GrapheneOS.
 */
class VoskTranscriber(private val context: Context) {

    companion object {
        private const val TAG = "VoskTranscriber"
        private const val SAMPLE_RATE = 16000f
        
        // Available language models - Full-size for better accuracy
        // Small models (~40-50MB) for quick download, Full models (~1-2GB) for best accuracy
        val AVAILABLE_LANGUAGES = listOf(
            // English models
            LanguageModel("en", "English (Small)", "vosk-model-small-en-us-0.15", 
                "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", 40_000_000L, false),
            LanguageModel("en-full", "English (Full - Best)", "vosk-model-en-us-0.22-lgraph",
                "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22-lgraph.zip", 128_000_000L, true),
            // Russian models
            LanguageModel("ru", "Русский (Small)", "vosk-model-small-ru-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip", 45_000_000L, false),
            LanguageModel("ru-full", "Русский (Full - Best)", "vosk-model-ru-0.42",
                "https://alphacephei.com/vosk/models/vosk-model-ru-0.42.zip", 1_800_000_000L, true),
            // German
            LanguageModel("de", "Deutsch (Small)", "vosk-model-small-de-0.15",
                "https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip", 45_000_000L, false),
            LanguageModel("de-full", "Deutsch (Full)", "vosk-model-de-0.21",
                "https://alphacephei.com/vosk/models/vosk-model-de-0.21.zip", 1_900_000_000L, true),
            // Spanish
            LanguageModel("es", "Español (Small)", "vosk-model-small-es-0.42",
                "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip", 39_000_000L, false),
            LanguageModel("es-full", "Español (Full)", "vosk-model-es-0.42",
                "https://alphacephei.com/vosk/models/vosk-model-es-0.42.zip", 1_400_000_000L, true),
            // French
            LanguageModel("fr", "Français (Small)", "vosk-model-small-fr-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip", 41_000_000L, false),
            LanguageModel("fr-full", "Français (Full)", "vosk-model-fr-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-fr-0.22.zip", 1_400_000_000L, true),
            // Italian
            LanguageModel("it", "Italiano (Small)", "vosk-model-small-it-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip", 48_000_000L, false),
            LanguageModel("it-full", "Italiano (Full)", "vosk-model-it-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-it-0.22.zip", 1_200_000_000L, true),
            // Portuguese
            LanguageModel("pt", "Português (Small)", "vosk-model-small-pt-0.3",
                "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip", 31_000_000L, false),
            LanguageModel("pt-full", "Português (Full)", "vosk-model-pt-fb-v0.1.1-20220516_2113",
                "https://alphacephei.com/vosk/models/vosk-model-pt-fb-v0.1.1-20220516_2113.zip", 1_600_000_000L, true),
            // Chinese
            LanguageModel("zh", "中文 (Small)", "vosk-model-small-cn-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip", 42_000_000L, false),
            LanguageModel("zh-full", "中文 (Full)", "vosk-model-cn-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-cn-0.22.zip", 1_300_000_000L, true),
            // Japanese
            LanguageModel("ja", "日本語 (Small)", "vosk-model-small-ja-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip", 48_000_000L, false),
            LanguageModel("ja-full", "日本語 (Full)", "vosk-model-ja-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-ja-0.22.zip", 1_000_000_000L, true),
            // Ukrainian
            LanguageModel("uk", "Українська (Nano)", "vosk-model-small-uk-v3-nano",
                "https://alphacephei.com/vosk/models/vosk-model-small-uk-v3-nano.zip", 73_000_000L, false),
            LanguageModel("uk-full", "Українська (Full)", "vosk-model-uk-v3",
                "https://alphacephei.com/vosk/models/vosk-model-uk-v3.zip", 343_000_000L, true),
            // Polish
            LanguageModel("pl", "Polski (Small)", "vosk-model-small-pl-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-small-pl-0.22.zip", 50_000_000L, false),
            // Hindi  
            LanguageModel("hi", "हिन्दी (Small)", "vosk-model-small-hi-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip", 42_000_000L, false),
            LanguageModel("hi-full", "हिन्दी (Full)", "vosk-model-hi-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-hi-0.22.zip", 1_500_000_000L, true),
            // Korean
            LanguageModel("ko", "한국어 (Small)", "vosk-model-small-ko-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip", 82_000_000L, false),
            // Turkish
            LanguageModel("tr", "Türkçe (Small)", "vosk-model-small-tr-0.3",
                "https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip", 35_000_000L, false),
            // Vietnamese
            LanguageModel("vi", "Tiếng Việt (Small)", "vosk-model-small-vn-0.4",
                "https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip", 32_000_000L, false),
            // Dutch
            LanguageModel("nl", "Nederlands (Small)", "vosk-model-small-nl-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-small-nl-0.22.zip", 39_000_000L, false),
            // Catalan
            LanguageModel("ca", "Català (Small)", "vosk-model-small-ca-0.4",
                "https://alphacephei.com/vosk/models/vosk-model-small-ca-0.4.zip", 42_000_000L, false),
            // Farsi/Persian
            LanguageModel("fa", "فارسی (Small)", "vosk-model-small-fa-0.5",
                "https://alphacephei.com/vosk/models/vosk-model-small-fa-0.5.zip", 47_000_000L, false),
            LanguageModel("fa-full", "فارسی (Full)", "vosk-model-fa-0.5",
                "https://alphacephei.com/vosk/models/vosk-model-fa-0.5.zip", 1_000_000_000L, true),
            // Kazakh
            LanguageModel("kz", "Қазақша (Small)", "vosk-model-small-kz-0.15",
                "https://alphacephei.com/vosk/models/vosk-model-small-kz-0.15.zip", 42_000_000L, false),
            // Swedish
            LanguageModel("sv", "Svenska (Small)", "vosk-model-small-sv-rhasspy-0.15",
                "https://alphacephei.com/vosk/models/vosk-model-small-sv-rhasspy-0.15.zip", 35_000_000L, false),
            // Czech
            LanguageModel("cs", "Čeština (Small)", "vosk-model-small-cs-0.4-rhasspy",
                "https://alphacephei.com/vosk/models/vosk-model-small-cs-0.4-rhasspy.zip", 44_000_000L, false),
            // Greek
            LanguageModel("el", "Ελληνικά (Small)", "vosk-model-el-gr-0.7",
                "https://alphacephei.com/vosk/models/vosk-model-el-gr-0.7.zip", 54_000_000L, false),
            // Indonesian
            LanguageModel("id", "Bahasa Indonesia", "vosk-model-small-id-0.22",
                "https://alphacephei.com/vosk/models/vosk-model-small-id-0.22.zip", 42_000_000L, false),
        )
        
        // Small models only (for dropdown showing primary languages)
        val SMALL_MODELS = AVAILABLE_LANGUAGES.filter { !it.isFullSize }
        
        // Full-size models for better accuracy
        val FULL_MODELS = AVAILABLE_LANGUAGES.filter { it.isFullSize }
        
        fun getLanguageByCode(code: String): LanguageModel = 
            AVAILABLE_LANGUAGES.find { it.code == code } ?: AVAILABLE_LANGUAGES.first()
            
        fun getBaseLanguageCode(code: String): String = 
            code.split("-").first()
    }

    data class LanguageModel(
        val code: String,
        val displayName: String,
        val modelName: String,
        val downloadUrl: String,
        val sizeBytes: Long,
        val isFullSize: Boolean = false
    )

    private var model: Model? = null
    private var isModelLoaded = false
    private var currentLanguage: String = "en"
    
    // Secondary model for multilingual recognition (e.g., English when primary is Russian)
    private var secondaryModel: Model? = null
    private var secondaryLanguage: String? = null
    private var isMultilingualEnabled = false
    
    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress

    fun isReady(): Boolean = isModelLoaded && model != null

    fun getCurrentLanguage(): String = currentLanguage
    
    fun isMultilingual(): Boolean = isMultilingualEnabled && secondaryModel != null

    fun needsModelDownload(languageCode: String = currentLanguage): Boolean {
        val modelDir = getModelDirectory(languageCode)
        return !modelDir.exists() || !File(modelDir, "am/final.mdl").exists()
    }
    
    // For backward compatibility
    fun needsModelDownload(): Boolean = needsModelDownload(currentLanguage)

    fun getDownloadedLanguages(): List<String> {
        return AVAILABLE_LANGUAGES
            .filter { !needsModelDownload(it.code) }
            .map { it.code }
    }
    
    /**
     * Delete a downloaded language model to free up storage.
     */
    fun deleteModel(languageCode: String): Boolean {
        val modelDir = getModelDirectory(languageCode)
        
        // Don't delete currently loaded model
        if (languageCode == currentLanguage && isModelLoaded) {
            releaseModels()
        }
        
        // Also release secondary if it matches
        if (languageCode == secondaryLanguage && secondaryModel != null) {
            secondaryModel?.close()
            secondaryModel = null
            secondaryLanguage = null
            isMultilingualEnabled = false
        }
        
        return try {
            if (modelDir.exists()) {
                modelDir.deleteRecursively()
                Log.i(TAG, "Deleted model for $languageCode")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete model $languageCode", e)
            false
        }
    }
    
    /**
     * Get the size of a downloaded model in bytes.
     */
    fun getModelSize(languageCode: String): Long {
        val modelDir = getModelDirectory(languageCode)
        return if (modelDir.exists()) {
            modelDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        } else 0L
    }

    suspend fun initialize(languageCode: String = currentLanguage): Boolean = withContext(Dispatchers.IO) {
        if (isModelLoaded && model != null && currentLanguage == languageCode) {
            return@withContext true
        }
        
        // Release old model to free memory
        releaseModels()
        
        // Force garbage collection before loading large model
        System.gc()
        
        try {
            val modelDir = getModelDirectory(languageCode)
            
            if (!modelDir.exists() || !File(modelDir, "am/final.mdl").exists()) {
                Log.w(TAG, "Vosk model for $languageCode not found at ${modelDir.absolutePath}")
                return@withContext false
            }
            
            Log.i(TAG, "Loading Vosk model for $languageCode from ${modelDir.absolutePath}")
            
            // Load model with low priority to reduce UI impact
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            
            model = Model(modelDir.absolutePath)
            isModelLoaded = true
            currentLanguage = languageCode
            
            // Restore normal priority
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
            
            Log.i(TAG, "Vosk model loaded for $languageCode")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Vosk model for $languageCode", e)
            isModelLoaded = false
            return@withContext false
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory loading Vosk model for $languageCode", e)
            isModelLoaded = false
            // Try to recover
            System.gc()
            return@withContext false
        }
    }
    
    private fun releaseModels() {
        model?.close()
        model = null
        secondaryModel?.close()
        secondaryModel = null
        isModelLoaded = false
        secondaryLanguage = null
        isMultilingualEnabled = false
    }
    
    // For backward compatibility
    suspend fun initialize(): Boolean = initialize(currentLanguage)
    
    /**
     * Initialize multilingual mode with primary and secondary language.
     * Useful for recognizing mixed speech (e.g., Russian with English words).
     * Note: For memory optimization, consider using small models for multilingual mode.
     */
    suspend fun initializeMultilingual(
        primaryLanguage: String,
        secondaryLanguageCode: String
    ): Boolean = withContext(Dispatchers.IO) {
        // First initialize primary
        val primaryResult = initialize(primaryLanguage)
        if (!primaryResult) return@withContext false
        
        // Check if we have enough memory for second model
        val runtime = Runtime.getRuntime()
        val freeMemory = runtime.freeMemory()
        val totalMemory = runtime.totalMemory()
        val maxMemory = runtime.maxMemory()
        val availableMemory = maxMemory - totalMemory + freeMemory
        
        // Secondary model needs ~200MB minimum for small models, more for full
        val minRequiredMemory = 200 * 1024 * 1024L // 200MB
        
        if (availableMemory < minRequiredMemory) {
            Log.w(TAG, "Not enough memory for multilingual mode (available: ${availableMemory / 1024 / 1024}MB)")
            isMultilingualEnabled = false
            return@withContext true // Primary is still loaded
        }
        
        // Try to load secondary model with background priority
        try {
            val secondaryModelDir = getModelDirectory(secondaryLanguageCode)
            if (secondaryModelDir.exists() && File(secondaryModelDir, "am/final.mdl").exists()) {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                
                secondaryModel?.close()
                secondaryModel = Model(secondaryModelDir.absolutePath)
                secondaryLanguage = secondaryLanguageCode
                isMultilingualEnabled = true
                
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
                
                Log.i(TAG, "Multilingual mode enabled: $primaryLanguage + $secondaryLanguageCode")
                return@withContext true
            } else {
                Log.w(TAG, "Secondary model not available for $secondaryLanguageCode")
                isMultilingualEnabled = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load secondary model", e)
            isMultilingualEnabled = false
        }
        return@withContext primaryResult
    }
    
    fun setMultilingualEnabled(enabled: Boolean) {
        isMultilingualEnabled = enabled
    }

    fun downloadModel(languageCode: String = "en"): Flow<DownloadState> = callbackFlow {
        val langModel = getLanguageByCode(languageCode)
        trySend(DownloadState.Downloading(0, 0, langModel.sizeBytes))
        
        withContext(Dispatchers.IO) {
            try {
                val modelDir = getModelDirectory(languageCode)
                val parentDir = modelDir.parentFile ?: context.filesDir
                
                if (!parentDir.exists()) {
                    parentDir.mkdirs()
                }
                
                Log.i(TAG, "Downloading Vosk model for $languageCode (${langModel.sizeBytes / 1_000_000}MB)")
                
                val url = java.net.URL(langModel.downloadUrl)
                val connection = url.openConnection() as java.net.HttpURLConnection
                // Longer timeouts for large models (1.8GB can take time)
                connection.connectTimeout = 60000
                connection.readTimeout = 300000  // 5 minutes
                connection.setRequestProperty("User-Agent", "GrapheneOS-AI-Assistant/1.0")
                connection.connect()
                
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    trySend(DownloadState.Error("HTTP error: $responseCode"))
                    close()
                    return@withContext
                }
                
                val totalBytes = connection.contentLengthLong.let { 
                    if (it > 0) it else langModel.sizeBytes 
                }
                
                val zipFile = File(parentDir, "${langModel.modelName}.zip")
                var bytesDownloaded = 0L
                connection.inputStream.use { input ->
                    zipFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            bytesDownloaded += bytesRead
                            
                            val progress = ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                            _downloadProgress.value = progress
                            trySend(DownloadState.Downloading(progress, bytesDownloaded, totalBytes))
                        }
                    }
                }
                
                Log.i(TAG, "Download complete (${bytesDownloaded / 1_000_000}MB), extracting...")
                trySend(DownloadState.Extracting(0))
                _downloadProgress.value = 0
                
                // Extract with progress updates - using non-suspend callback now
                extractZipWithProgress(zipFile, parentDir) { progress ->
                    trySend(DownloadState.Extracting(progress))
                    _downloadProgress.value = progress
                }
                
                trySend(DownloadState.Extracting(95))
                _downloadProgress.value = 95
                
                val extractedDir = File(parentDir, langModel.modelName)
                if (extractedDir.exists() && extractedDir != modelDir) {
                    if (modelDir.exists()) {
                        modelDir.deleteRecursively()
                    }
                    val renamed = extractedDir.renameTo(modelDir)
                    if (!renamed) {
                        Log.w(TAG, "Failed to rename, copying instead...")
                        extractedDir.copyRecursively(modelDir, overwrite = true)
                        extractedDir.deleteRecursively()
                    }
                }
                
                // Cleanup
                if (zipFile.exists()) {
                    zipFile.delete()
                }
                
                Log.i(TAG, "Extraction complete, initializing model...")
                if (initialize(languageCode)) {
                    Log.i(TAG, "Model $languageCode ready!")
                    trySend(DownloadState.Complete(modelDir))
                } else {
                    trySend(DownloadState.Error("Failed to initialize model"))
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for $languageCode", e)
                val errorMsg = when {
                    e is java.net.UnknownHostException -> "No internet connection"
                    e is java.net.SocketTimeoutException -> "Connection timed out"
                    e is javax.net.ssl.SSLException -> "SSL/TLS error - check network settings"
                    e is java.io.IOException -> "Network error: ${e.localizedMessage ?: "IO error"}"
                    e.message.isNullOrBlank() -> "Unknown error (${e.javaClass.simpleName})"
                    else -> e.message!!
                }
                trySend(DownloadState.Error("Download failed: $errorMsg"))
            }
        }
        
        close()
        awaitClose { }
    }

    suspend fun transcribe(audioFile: File): String = withContext(Dispatchers.IO) {
        if (!isReady()) {
            if (!initialize()) {
                return@withContext "[Voice recognition unavailable - download model in settings]"
            }
        }
        
        val currentModel = model ?: return@withContext "[Model not loaded]"
        
        try {
            Log.i(TAG, "Transcribing with language: $currentLanguage" + 
                if (isMultilingualEnabled && secondaryModel != null) " + $secondaryLanguage (multilingual)" else "" +
                ", file: ${audioFile.name}")
            
            val audioBytes = readWavFile(audioFile)
            
            // Primary recognition
            val primaryResult = recognizeWithModel(currentModel, audioBytes, "primary")
            
            // If multilingual mode is enabled and primary result is poor, try secondary
            if (isMultilingualEnabled && secondaryModel != null) {
                val secondaryResult = recognizeWithModel(secondaryModel!!, audioBytes, "secondary")
                
                // Combine results - use the one with more confidence/words
                // or merge if both have content
                val finalResult = mergeMultilingualResults(primaryResult, secondaryResult)
                Log.i(TAG, "Multilingual transcription: $finalResult")
                return@withContext finalResult.ifBlank { 
                    "[Could not transcribe audio - speak louder or closer to mic]" 
                }
            }
            
            if (primaryResult.isBlank()) {
                return@withContext "[Could not transcribe audio - speak louder or closer to mic]"
            }
            
            Log.i(TAG, "Transcription ($currentLanguage): $primaryResult")
            return@withContext primaryResult.trim()
            
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed for $currentLanguage", e)
            return@withContext "[Transcription error: ${e.message}]"
        }
    }
    
    private fun recognizeWithModel(model: Model, audioBytes: ByteArray, label: String): String {
        val recognizer = Recognizer(model, SAMPLE_RATE)
        recognizer.setMaxAlternatives(0)
        recognizer.setWords(true)
        
        // Process in larger chunks for better recognition
        val chunkSize = 8000
        var offset = 0
        var lastPartial = ""
        
        while (offset < audioBytes.size) {
            val end = minOf(offset + chunkSize, audioBytes.size)
            val chunk = audioBytes.copyOfRange(offset, end)
            
            if (recognizer.acceptWaveForm(chunk, chunk.size)) {
                val partial = recognizer.partialResult
                val partialText = JSONObject(partial).optString("partial", "")
                if (partialText.isNotBlank()) {
                    lastPartial = partialText
                    Log.d(TAG, "$label partial: $partialText")
                }
            }
            offset = end
        }
        
        val resultJson = recognizer.finalResult
        Log.d(TAG, "$label final result JSON: $resultJson")
        
        val result = JSONObject(resultJson).optString("text", "")
        recognizer.close()
        
        // Fallback to last partial result if final is empty
        return if (result.isBlank() && lastPartial.isNotBlank()) {
            Log.i(TAG, "Using $label partial result: $lastPartial")
            lastPartial.trim()
        } else {
            result.trim()
        }
    }
    
    /**
     * Merge results from two language models.
     * Useful when speaking mixed languages (e.g., Russian with English words).
     */
    private fun mergeMultilingualResults(primary: String, secondary: String): String {
        if (primary.isBlank()) return secondary
        if (secondary.isBlank()) return primary
        
        // If one result is significantly longer (more than 2x words), prefer it
        val primaryWords = primary.split(" ").filter { it.isNotBlank() }
        val secondaryWords = secondary.split(" ").filter { it.isNotBlank() }
        
        if (primaryWords.size > secondaryWords.size * 2) return primary
        if (secondaryWords.size > primaryWords.size * 2) return secondary
        
        // If similar length, prefer primary (user's selected language)
        // But note: in real use, the user might be mixing languages,
        // so we should return the result that looks more complete
        return if (primaryWords.size >= secondaryWords.size) primary else secondary
    }

    private fun readWavFile(file: File): ByteArray {
        FileInputStream(file).use { fis ->
            val header = ByteArray(44)
            fis.read(header)
            return fis.readBytes()
        }
    }

    private fun extractZipWithProgress(
        zipFile: File, 
        destDir: File,
        onProgress: (Int) -> Unit = {}
    ) {
        // First pass: count total entries for progress
        var totalEntries = 0
        java.util.zip.ZipInputStream(zipFile.inputStream()).use { zis ->
            while (zis.nextEntry != null) {
                totalEntries++
                zis.closeEntry()
            }
        }
        
        Log.i(TAG, "Extracting $totalEntries files from ${zipFile.name}")
        
        // Second pass: extract with progress
        var extractedCount = 0
        java.util.zip.ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val newFile = File(destDir, entry.name)
                
                try {
                    if (entry.isDirectory) {
                        newFile.mkdirs()
                    } else {
                        newFile.parentFile?.mkdirs()
                        newFile.outputStream().use { fos ->
                            val buffer = ByteArray(8192)
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) {
                                fos.write(buffer, 0, len)
                            }
                        }
                    }
                    
                    extractedCount++
                    
                    // Update progress every 10 files or for large milestones
                    if (extractedCount % 10 == 0 || extractedCount == totalEntries) {
                        val progress = ((extractedCount * 90) / totalEntries).coerceIn(0, 90)
                        onProgress(progress)
                        Log.d(TAG, "Extraction progress: $extractedCount/$totalEntries files ($progress%)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to extract ${entry.name}", e)
                    throw e
                }
                
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        
        Log.i(TAG, "Extracted $extractedCount files successfully")
    }

    private fun getModelDirectory(languageCode: String = currentLanguage): File {
        return File(context.filesDir, "vosk/model-$languageCode")
    }

    fun release() {
        releaseModels()
        // Help garbage collector reclaim memory
        System.gc()
    }

    sealed class DownloadState {
        object NotStarted : DownloadState()
        data class Downloading(val progress: Int, val bytesDownloaded: Long, val totalBytes: Long) : DownloadState()
        data class Extracting(val progress: Int) : DownloadState()
        data class Complete(val file: File) : DownloadState()
        data class Error(val message: String) : DownloadState()
    }
}
