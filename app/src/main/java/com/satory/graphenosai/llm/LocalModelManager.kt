package com.satory.graphenosai.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Manages downloading and storage of local AI models (GGUF format)
 * Models are optimized for ARM64 Pixel devices running GrapheneOS
 */
class LocalModelManager(private val context: Context) {

    companion object {
        private const val TAG = "LocalModelManager"

        // Directory for storing models
        private const val MODELS_DIR = "local_models"

        // Buffer size for downloads (256KB for better performance)
        private const val DOWNLOAD_BUFFER_SIZE = 256 * 1024
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416

        private val CONTENT_RANGE_REGEX =
            Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)
        private val UNSATISFIED_CONTENT_RANGE_REGEX =
            Regex("""bytes\s+\*/(\d+)""", RegexOption.IGNORE_CASE)

        /**
         * Available models optimized for ARM64/Pixel devices
         * These are quantized models that balance quality and performance
         *
         * Selection criteria:
         * - Q4_K_M quantization: Best quality/size ratio for mobile
         * - 1B-8B parameters: Optimal for Pixel 6/7/8 with 8-12GB RAM
         * - Instruction-tuned: Better at following prompts
         * - ChatML or Gemma format: Standard prompt format support
         */
        val AVAILABLE_MODELS = listOf(
            // Qwen3 4B - Latest generation with thinking/non-thinking modes
            LocalModelInfo(
                id = "qwen3-4b",
                name = "Qwen3 4B",
                description = "Latest Qwen, thinking mode, 32K context",
                sizeBytes = 2_500_000_000L, // ~2.5GB
                downloadUrl = "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf",
                filename = "Qwen3-4B-Q4_K_M.gguf",
                contextSize = 32768,
                recommended = true
            ),

            // Qwen3 1.7B - Fastest Qwen3 for low-memory devices
            LocalModelInfo(
                id = "qwen3-1.7b",
                name = "Qwen3 1.7B",
                description = "Fast Qwen3, thinking mode, multilingual",
                sizeBytes = 1_830_000_000L, // ~1.83GB (Q8_0)
                downloadUrl = "https://huggingface.co/Qwen/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q8_0.gguf",
                filename = "Qwen3-1.7B-Q8_0.gguf",
                contextSize = 32768,
                recommended = false
            ),

            // Gemma 4 E2B - Google's latest, optimized for phones
            LocalModelInfo(
                id = "gemma-4-e2b",
                name = "Gemma 4 E2B",
                description = "Google's latest, thinking mode, 128K context",
                sizeBytes = 3_110_000_000L, // ~3.11GB
                downloadUrl = "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf",
                filename = "gemma-4-E2B-it-Q4_K_M.gguf",
                contextSize = 8192,
                recommended = true,
                promptFormat = "gemma"
            ),

            // Gemma 4 E4B - Larger variant for laptops/tablets
            LocalModelInfo(
                id = "gemma-4-e4b",
                name = "Gemma 4 E4B",
                description = "Google's larger model, thinking mode, 128K context",
                sizeBytes = 4_980_000_000L, // ~4.98GB
                downloadUrl = "https://huggingface.co/unsloth/gemma-4-E4B-it-GGUF/resolve/main/gemma-4-E4B-it-Q4_K_M.gguf",
                filename = "gemma-4-E4B-it-Q4_K_M.gguf",
                contextSize = 8192,
                recommended = false,
                promptFormat = "gemma"
            ),

            // DeepSeek-R1-Distill-Qwen-1.5B - Reasoning distilled from R1
            LocalModelInfo(
                id = "deepseek-r1-distill-qwen-1.5b",
                name = "DeepSeek R1 1.5B",
                description = "R1-distilled reasoning, fast inference",
                sizeBytes = 1_120_000_000L, // ~1.12GB
                downloadUrl = "https://huggingface.co/bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
                filename = "deepseek-r1-distill-qwen-1.5b-q4_k_m.gguf",
                contextSize = 8192,
                recommended = true
            ),

            // SmolLM3 3B - Latest generation, multilingual, 128K context
            LocalModelInfo(
                id = "smollm3-3b",
                name = "SmolLM3 3B",
                description = "Latest SmolLM, multilingual, 128K context",
                sizeBytes = 1_920_000_000L, // ~1.92GB
                downloadUrl = "https://huggingface.co/ggml-org/SmolLM3-3B-GGUF/resolve/main/SmolLM3-Q4_K_M.gguf",
                filename = "smollm3-3b-q4_k_m.gguf",
                contextSize = 8192,
                recommended = true
            ),

            // Phi-4-mini - Microsoft's latest efficient model, 128K context
            LocalModelInfo(
                id = "phi-4-mini-instruct",
                name = "Phi-4 Mini 3.8B",
                description = "Microsoft's latest, excellent reasoning, 128K",
                sizeBytes = 2_490_000_000L, // ~2.49GB
                downloadUrl = "https://huggingface.co/bartowski/microsoft_Phi-4-mini-instruct-GGUF/resolve/main/microsoft_Phi-4-mini-instruct-Q4_K_M.gguf",
                filename = "phi-4-mini-instruct-q4_k_m.gguf",
                contextSize = 8192,
                recommended = true
            ),

            // Gemma 3 4B IT - Google's latest, multilingual, vision-capable
            LocalModelInfo(
                id = "gemma-3-4b-it",
                name = "Gemma 3 4B",
                description = "Google's latest, multilingual, 128K context",
                sizeBytes = 2_490_000_000L, // ~2.49GB
                downloadUrl = "https://huggingface.co/bartowski/google_gemma-3-4b-it-GGUF/resolve/main/google_gemma-3-4b-it-Q4_K_M.gguf",
                filename = "google_gemma-3-4b-it-q4_k_m.gguf",
                contextSize = 8192,
                recommended = false,
                promptFormat = "gemma"
            )
        )
    }

    private data class ContentRange(
        val start: Long,
        val end: Long,
        val total: Long?
    )

    private val modelsDir: File by lazy {
        File(context.filesDir, MODELS_DIR).apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Get the models directory path
     */
    fun getModelsDirectory(): File = modelsDir

    /**
     * Get all downloaded models
     */
    fun getDownloadedModels(): List<LocalModelInfo> {
        return AVAILABLE_MODELS.filter { isModelDownloaded(it.id) }
    }

    /**
     * Check if a specific model is downloaded
     */
    fun isModelDownloaded(modelId: String): Boolean {
        val model = AVAILABLE_MODELS.find { it.id == modelId } ?: return false
        val modelFile = File(modelsDir, model.filename)
        return modelFile.exists() && modelFile.length() > 0
    }

    /**
     * Get the file path for a model
     */
    fun getModelPath(modelId: String): String? {
        val model = AVAILABLE_MODELS.find { it.id == modelId } ?: return null
        val modelFile = File(modelsDir, model.filename)
        return if (modelFile.exists()) modelFile.absolutePath else null
    }

    /**
     * Get model info by ID
     */
    fun getModelInfo(modelId: String): LocalModelInfo? {
        return AVAILABLE_MODELS.find { it.id == modelId }
    }

    /**
     * Download a model with progress updates.
     *
     * Partial downloads are resumed only when the server confirms the exact
     * requested byte offset with HTTP 206 + Content-Range. If a server ignores
     * Range and returns HTTP 200, the temp file is truncated and the response
     * is written from byte zero instead of being appended to stale data.
     */
    fun downloadModel(modelId: String): Flow<DownloadProgress> = flow {
        val model = AVAILABLE_MODELS.find { it.id == modelId }
        if (model == null) {
            emit(DownloadProgress.Error("Model not found: $modelId"))
            return@flow
        }

        val modelFile = File(modelsDir, model.filename)
        val tempFile = File(modelsDir, "${model.filename}.tmp")

        // Check if already downloaded
        if (modelFile.exists() && modelFile.length() == model.sizeBytes) {
            Log.i(TAG, "Model already downloaded: ${model.name}")
            emit(DownloadProgress.Completed(modelFile.absolutePath))
            return@flow
        }

        Log.i(TAG, "Starting download: ${model.name} from ${model.downloadUrl}")
        emit(DownloadProgress.Started(model.name))

        var connection: HttpURLConnection? = null

        try {
            val url = URL(model.downloadUrl)
            var resumeOffset = tempFile.takeIf { it.exists() }?.length() ?: 0L

            if (resumeOffset == 0L && tempFile.exists()) {
                discardTempFile(tempFile)
            }

            connection = openDownloadConnection(url, resumeOffset)
            var responseCode = connection.responseCode

            if (resumeOffset > 0L) {
                when (responseCode) {
                    HttpURLConnection.HTTP_PARTIAL -> {
                        val range = parseContentRange(connection.getHeaderField("Content-Range"))
                        if (range == null || range.start != resumeOffset) {
                            Log.w(
                                TAG,
                                "Server returned an invalid Content-Range while resuming: " +
                                    connection.getHeaderField("Content-Range") +
                                    ". Restarting from byte 0."
                            )
                            connection.disconnect()
                            discardTempFile(tempFile)
                            resumeOffset = 0L
                            connection = openDownloadConnection(url, resumeOffset)
                            responseCode = connection.responseCode
                        }
                    }

                    HttpURLConnection.HTTP_OK -> {
                        // The server ignored Range. Reuse this full response, but make
                        // sure FileOutputStream truncates the existing temp file.
                        Log.w(TAG, "Server ignored Range request; restarting download from byte 0")
                        resumeOffset = 0L
                    }

                    HTTP_RANGE_NOT_SATISFIABLE -> {
                        val remoteSize = parseUnsatisfiedContentRangeTotal(
                            connection.getHeaderField("Content-Range")
                        )

                        if (remoteSize != null && tempFile.length() == remoteSize) {
                            Log.i(TAG, "Partial file is already complete; finalizing download")
                            if (finalizeDownload(tempFile, modelFile)) {
                                emit(DownloadProgress.Completed(modelFile.absolutePath))
                            } else {
                                emit(DownloadProgress.Error("Failed to finalize download"))
                            }
                            return@flow
                        }

                        Log.w(TAG, "Resume offset is no longer valid; restarting download from byte 0")
                        connection.disconnect()
                        discardTempFile(tempFile)
                        resumeOffset = 0L
                        connection = openDownloadConnection(url, resumeOffset)
                        responseCode = connection.responseCode
                    }
                }
            }

            if (responseCode != HttpURLConnection.HTTP_OK &&
                responseCode != HttpURLConnection.HTTP_PARTIAL
            ) {
                emit(DownloadProgress.Error("Download failed: HTTP $responseCode"))
                return@flow
            }

            val contentRange = if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                parseContentRange(connection.getHeaderField("Content-Range"))
            } else {
                null
            }

            if (responseCode == HttpURLConnection.HTTP_PARTIAL &&
                (contentRange == null || contentRange.start != resumeOffset)
            ) {
                emit(DownloadProgress.Error("Download failed: invalid Content-Range response"))
                return@flow
            }

            // Content-Range total is authoritative for resumed downloads. For a
            // full HTTP 200 response, Content-Length is the exact expected size.
            val expectedTotalBytes = when {
                contentRange?.total != null -> contentRange.total
                responseCode == HttpURLConnection.HTTP_OK && connection.contentLengthLong > 0L ->
                    connection.contentLengthLong
                responseCode == HttpURLConnection.HTTP_PARTIAL && connection.contentLengthLong > 0L ->
                    resumeOffset + connection.contentLengthLong
                else -> null
            }

            // model.sizeBytes is display metadata and may be rounded, so use it
            // only for progress when the HTTP response does not expose a total.
            val progressTotalBytes = expectedTotalBytes ?: model.sizeBytes
            val append = responseCode == HttpURLConnection.HTTP_PARTIAL && resumeOffset > 0L
            var totalDownloaded = if (append) resumeOffset else 0L
            var lastProgressUpdate = if (progressTotalBytes > 0L) {
                ((totalDownloaded.toDouble() / progressTotalBytes) * 100)
                    .toInt()
                    .coerceIn(0, 100)
            } else {
                0
            }

            connection.inputStream.use { inputStream ->
                FileOutputStream(tempFile, append).use { outputStream ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    var bytesRead: Int

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalDownloaded += bytesRead

                        if (progressTotalBytes > 0L) {
                            val progress = ((totalDownloaded.toDouble() / progressTotalBytes) * 100)
                                .toInt()
                                .coerceIn(0, 100)
                            if (progress > lastProgressUpdate) {
                                lastProgressUpdate = progress
                                emit(
                                    DownloadProgress.Downloading(
                                        progress,
                                        totalDownloaded,
                                        progressTotalBytes
                                    )
                                )
                            }
                        }
                    }
                    outputStream.flush()
                }
            }

            val downloadedSize = tempFile.length()
            if (downloadedSize <= 0L) {
                emit(DownloadProgress.Error("Download failed: empty model file"))
                return@flow
            }

            if (expectedTotalBytes != null && downloadedSize != expectedTotalBytes) {
                Log.w(
                    TAG,
                    "Downloaded size mismatch: expected $expectedTotalBytes bytes, got $downloadedSize"
                )
                if (downloadedSize > expectedTotalBytes) {
                    // A file larger than the server's advertised total cannot be
                    // resumed safely. Remove it so the next attempt starts clean.
                    discardTempFile(tempFile)
                }
                emit(
                    DownloadProgress.Error(
                        "Download incomplete: expected $expectedTotalBytes bytes, got $downloadedSize"
                    )
                )
                return@flow
            }

            if (finalizeDownload(tempFile, modelFile)) {
                Log.i(TAG, "Download completed: ${model.name} ($downloadedSize bytes)")
                emit(DownloadProgress.Completed(modelFile.absolutePath))
            } else {
                emit(DownloadProgress.Error("Failed to finalize download"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download error", e)
            emit(DownloadProgress.Error("Download failed: ${e.message}"))
        } finally {
            connection?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private fun openDownloadConnection(url: URL, resumeOffset: Long): HttpURLConnection {
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 30000
            readTimeout = 60000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "GrapheneOS-AI-Assistant/1.0")
            setRequestProperty("Accept-Encoding", "identity")

            if (resumeOffset > 0L) {
                setRequestProperty("Range", "bytes=$resumeOffset-")
                Log.i(TAG, "Requesting resume from byte $resumeOffset")
            }

            connect()
        }
    }

    private fun parseContentRange(header: String?): ContentRange? {
        val match = header?.trim()?.let { CONTENT_RANGE_REGEX.matchEntire(it) } ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3]
            .takeUnless { it == "*" }
            ?.toLongOrNull()

        if (end < start) return null
        if (total != null && (total <= 0L || end >= total)) return null

        return ContentRange(start = start, end = end, total = total)
    }

    private fun parseUnsatisfiedContentRangeTotal(header: String?): Long? {
        val match = header?.trim()?.let { UNSATISFIED_CONTENT_RANGE_REGEX.matchEntire(it) }
            ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    private fun discardTempFile(tempFile: File) {
        if (!tempFile.exists()) return

        if (!tempFile.delete()) {
            // If deletion fails, truncate it so a later full response can never
            // append to stale bytes from a previous attempt.
            FileOutputStream(tempFile, false).use { it.flush() }
        }
    }

    private fun finalizeDownload(tempFile: File, modelFile: File): Boolean {
        return try {
            Files.move(
                tempFile.toPath(),
                modelFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            true
        } catch (atomicMoveError: Exception) {
            Log.w(TAG, "Atomic move unavailable, falling back to regular replace", atomicMoveError)
            try {
                Files.move(
                    tempFile.toPath(),
                    modelFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
                true
            } catch (moveError: Exception) {
                Log.e(TAG, "Failed to move completed model into place", moveError)
                false
            }
        }
    }

    /**
     * Delete a downloaded model
     */
    suspend fun deleteModel(modelId: String): Boolean = withContext(Dispatchers.IO) {
        val model = AVAILABLE_MODELS.find { it.id == modelId } ?: return@withContext false
        val modelFile = File(modelsDir, model.filename)
        val tempFile = File(modelsDir, "${model.filename}.tmp")

        var deleted = false
        if (modelFile.exists()) {
            deleted = modelFile.delete()
        }
        if (tempFile.exists()) {
            tempFile.delete()
        }

        Log.i(TAG, "Deleted model $modelId: $deleted")
        deleted
    }

    /**
     * Get total storage used by downloaded models
     */
    fun getTotalStorageUsed(): Long {
        return modelsDir.listFiles()
            ?.filter { it.isFile && it.extension == "gguf" }
            ?.sumOf { it.length() }
            ?: 0L
    }

    /**
     * Get available storage on device
     */
    fun getAvailableStorage(): Long {
        return modelsDir.freeSpace
    }
}

/**
 * Information about a downloadable local model
 */
data class LocalModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val sizeBytes: Long,
    val downloadUrl: String,
    val filename: String,
    val contextSize: Int,
    val recommended: Boolean = false,
    val promptFormat: String = "chatml"
) {
    /**
     * Format size as human-readable string
     */
    fun formattedSize(): String {
        return when {
            sizeBytes >= 1_000_000_000 -> String.format("%.1f GB", sizeBytes / 1_000_000_000.0)
            sizeBytes >= 1_000_000 -> String.format("%.1f MB", sizeBytes / 1_000_000.0)
            else -> String.format("%.1f KB", sizeBytes / 1_000.0)
        }
    }
}

/**
 * Download progress state
 */
sealed class DownloadProgress {
    data class Started(val modelName: String) : DownloadProgress()
    data class Downloading(val percent: Int, val downloadedBytes: Long, val totalBytes: Long) : DownloadProgress()
    data class Completed(val filePath: String) : DownloadProgress()
    data class Error(val message: String) : DownloadProgress()
}
