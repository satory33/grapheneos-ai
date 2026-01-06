package com.satory.graphenosai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullscreenResponseScreen(
    response: String,
    query: String,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Response", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        copyToClipboard(context, response)
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                    }
                    IconButton(onClick = {
                        shareText(context, response)
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Share")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {
            // Original query
            if (query.isNotBlank()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Your question:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            query,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            // Response with Markdown rendering
            SelectionContainer {
                MarkdownText(
                    markdown = response,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    
    val annotatedString = remember(markdown, colorScheme) {
        parseMarkdown(markdown, colorScheme)
    }
    
    Text(
        text = annotatedString,
        style = MaterialTheme.typography.bodyLarge.copy(
            lineHeight = 26.sp
        ),
        modifier = modifier
    )
}

private fun parseMarkdown(
    markdown: String,
    colorScheme: androidx.compose.material3.ColorScheme
): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        val text = markdown
        
        while (i < text.length) {
            when {
                // Headers
                text.startsWith("### ", i) -> {
                    val endOfLine = text.indexOf('\n', i).let { if (it == -1) text.length else it }
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp)) {
                        append(text.substring(i + 4, endOfLine))
                    }
                    append("\n")
                    i = endOfLine + 1
                }
                text.startsWith("## ", i) -> {
                    val endOfLine = text.indexOf('\n', i).let { if (it == -1) text.length else it }
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp)) {
                        append(text.substring(i + 3, endOfLine))
                    }
                    append("\n")
                    i = endOfLine + 1
                }
                text.startsWith("# ", i) -> {
                    val endOfLine = text.indexOf('\n', i).let { if (it == -1) text.length else it }
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp)) {
                        append(text.substring(i + 2, endOfLine))
                    }
                    append("\n")
                    i = endOfLine + 1
                }
                
                // Code block
                text.startsWith("```", i) -> {
                    val endOfFirstLine = text.indexOf('\n', i)
                    val codeBlockEnd = text.indexOf("```", endOfFirstLine + 1)
                    if (codeBlockEnd != -1) {
                        val codeContent = text.substring(endOfFirstLine + 1, codeBlockEnd)
                        withStyle(SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = colorScheme.surfaceVariant,
                            fontSize = 14.sp
                        )) {
                            append(codeContent.trimEnd())
                        }
                        append("\n")
                        i = codeBlockEnd + 3
                        // Skip trailing newline
                        if (i < text.length && text[i] == '\n') i++
                    } else {
                        append(text[i])
                        i++
                    }
                }
                
                // Inline code
                text[i] == '`' && !text.startsWith("```", i) -> {
                    val endOfCode = text.indexOf('`', i + 1)
                    if (endOfCode != -1) {
                        withStyle(SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = colorScheme.surfaceVariant,
                            fontSize = 14.sp
                        )) {
                            append(text.substring(i + 1, endOfCode))
                        }
                        i = endOfCode + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                
                // Bold **text**
                text.startsWith("**", i) -> {
                    val endOfBold = text.indexOf("**", i + 2)
                    if (endOfBold != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, endOfBold))
                        }
                        i = endOfBold + 2
                    } else {
                        append(text[i])
                        i++
                    }
                }
                
                // Bold __text__
                text.startsWith("__", i) -> {
                    val endOfBold = text.indexOf("__", i + 2)
                    if (endOfBold != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, endOfBold))
                        }
                        i = endOfBold + 2
                    } else {
                        append(text[i])
                        i++
                    }
                }
                
                // Italic *text* (not bold)
                text[i] == '*' && !text.startsWith("**", i) -> {
                    val endOfItalic = text.indexOf('*', i + 1)
                    if (endOfItalic != -1 && !text.startsWith("**", endOfItalic)) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, endOfItalic))
                        }
                        i = endOfItalic + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                
                // Italic _text_ (not bold)
                text[i] == '_' && !text.startsWith("__", i) && (i == 0 || text[i-1].isWhitespace()) -> {
                    val endOfItalic = text.indexOf('_', i + 1)
                    if (endOfItalic != -1 && !text.startsWith("__", endOfItalic)) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, endOfItalic))
                        }
                        i = endOfItalic + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                
                // Bullet points
                (text.startsWith("- ", i) || text.startsWith("* ", i)) && 
                (i == 0 || text[i-1] == '\n') -> {
                    append("  •  ")
                    i += 2
                }
                
                // Numbered lists
                text[i].isDigit() && (i == 0 || text[i-1] == '\n') -> {
                    val dotIndex = text.indexOf(". ", i)
                    if (dotIndex != -1 && dotIndex - i <= 3) {
                        val number = text.substring(i, dotIndex)
                        if (number.all { it.isDigit() }) {
                            withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
                                append("  $number.  ")
                            }
                            i = dotIndex + 2
                        } else {
                            append(text[i])
                            i++
                        }
                    } else {
                        append(text[i])
                        i++
                    }
                }
                
                // Blockquote
                text.startsWith("> ", i) && (i == 0 || text[i-1] == '\n') -> {
                    val endOfLine = text.indexOf('\n', i).let { if (it == -1) text.length else it }
                    withStyle(SpanStyle(
                        color = colorScheme.onSurfaceVariant,
                        fontStyle = FontStyle.Italic
                    )) {
                        append("│ ")
                        append(text.substring(i + 2, endOfLine))
                    }
                    append("\n")
                    i = endOfLine + 1
                }
                
                // Horizontal rule
                text.startsWith("---", i) && (i == 0 || text[i-1] == '\n') -> {
                    append("───────────────────────")
                    i += 3
                    // Skip trailing newline
                    if (i < text.length && text[i] == '\n') {
                        append("\n")
                        i++
                    }
                }
                
                // Regular character
                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("AI Response", text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}

private fun shareText(context: Context, text: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "Share response"))
}
