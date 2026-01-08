package com.satory.graphenosai.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Utility class for extracting text from PDF files.
 * Uses PDFBox Android for reliable PDF text extraction.
 */
object PdfExtractor {
    private const val TAG = "PdfExtractor"
    private const val MAX_TEXT_LENGTH = 50000 // ~12500 tokens, reasonable limit
    private const val MAX_PAGES = 50 // Limit pages for very large documents
    
    private var initialized = false
    
    /**
     * Initialize PDFBox. Call this once on app startup.
     */
    fun initialize(context: Context) {
        if (!initialized) {
            PDFBoxResourceLoader.init(context.applicationContext)
            initialized = true
            Log.d(TAG, "PDFBox initialized")
        }
    }
    
    /**
     * Extract text from a PDF file.
     * 
     * @param context Application context
     * @param uri URI of the PDF file
     * @return Extracted text or error message
     */
    suspend fun extractText(context: Context, uri: Uri): PdfResult = withContext(Dispatchers.IO) {
        try {
            initialize(context)
            
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext PdfResult.Error("Cannot open PDF file")
            
            inputStream.use { stream ->
                val document = PDDocument.load(stream)
                document.use { doc ->
                    val pageCount = doc.numberOfPages
                    Log.d(TAG, "PDF loaded: $pageCount pages")
                    
                    if (pageCount == 0) {
                        return@withContext PdfResult.Error("PDF has no pages")
                    }
                    
                    val stripper = PDFTextStripper()
                    
                    // Limit pages for large documents
                    val pagesToProcess = minOf(pageCount, MAX_PAGES)
                    if (pagesToProcess < pageCount) {
                        stripper.endPage = pagesToProcess
                    }
                    
                    val text = stripper.getText(doc)
                    
                    if (text.isBlank()) {
                        return@withContext PdfResult.Error("PDF contains no extractable text (might be scanned/image-based)")
                    }
                    
                    // Truncate if too long
                    val finalText = if (text.length > MAX_TEXT_LENGTH) {
                        val truncated = text.take(MAX_TEXT_LENGTH)
                        "$truncated\n\n[... Document truncated. Showing first $MAX_TEXT_LENGTH characters of ${text.length} total ...]"
                    } else {
                        text
                    }
                    
                    val summary = buildString {
                        append("📄 PDF Document")
                        if (pagesToProcess < pageCount) {
                            append(" (showing $pagesToProcess of $pageCount pages)")
                        } else {
                            append(" ($pageCount pages)")
                        }
                    }
                    
                    Log.d(TAG, "Extracted ${finalText.length} characters from PDF")
                    
                    PdfResult.Success(
                        text = finalText,
                        pageCount = pageCount,
                        summary = summary
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting PDF text", e)
            PdfResult.Error("Failed to read PDF: ${e.message}")
        }
    }
    
    /**
     * Check if a URI points to a PDF file.
     */
    fun isPdf(context: Context, uri: Uri): Boolean {
        val mimeType = context.contentResolver.getType(uri)
        return mimeType == "application/pdf"
    }
    
    /**
     * Get file name from URI.
     */
    fun getFileName(context: Context, uri: Uri): String? {
        return try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        it.getString(nameIndex)
                    } else null
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file name", e)
            null
        }
    }
}

/**
 * Result of PDF extraction.
 */
sealed class PdfResult {
    data class Success(
        val text: String,
        val pageCount: Int,
        val summary: String
    ) : PdfResult()
    
    data class Error(val message: String) : PdfResult()
}
