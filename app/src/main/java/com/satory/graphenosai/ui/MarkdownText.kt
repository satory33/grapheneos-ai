package com.satory.graphenosai.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * Simple Markdown renderer for chat messages.
 * Supports: **bold**, *italic*, `code`, [links](url), ```code blocks```, headers, lists
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    linkColor: Color = MaterialTheme.colorScheme.primary,
    codeBackground: Color = MaterialTheme.colorScheme.surfaceVariant,
    onLinkClick: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val baseStyle = LocalTextStyle.current.copy(color = color)
    
    val annotatedString = remember(text, color, linkColor) {
        parseMarkdown(text, baseStyle, linkColor, codeBackground)
    }
    
    @Suppress("DEPRECATION")
    ClickableText(
        text = annotatedString,
        modifier = modifier,
        style = baseStyle,
        onClick = { offset ->
            annotatedString.getStringAnnotations("URL", offset, offset)
                .firstOrNull()?.let { annotation ->
                    if (onLinkClick != null) {
                        onLinkClick(annotation.item)
                    } else {
                        // Default: open in browser
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(annotation.item))
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            // Ignore invalid URLs
                        }
                    }
                }
        }
    )
}

private fun parseMarkdown(
    text: String,
    baseStyle: TextStyle,
    linkColor: Color,
    codeBackground: Color
): AnnotatedString {
    return buildAnnotatedString {
        var currentIndex = 0
        val input = text
        
        // Process line by line for headers and lists
        val lines = input.split("\n")
        
        for ((lineIndex, line) in lines.withIndex()) {
            if (lineIndex > 0) append("\n")
            
            // Check for headers
            val headerMatch = Regex("^(#{1,6})\\s+(.+)$").find(line)
            if (headerMatch != null) {
                val level = headerMatch.groupValues[1].length
                val content = headerMatch.groupValues[2]
                val headerStyle = when (level) {
                    1 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = baseStyle.fontSize * 1.5f)
                    2 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = baseStyle.fontSize * 1.3f)
                    3 -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = baseStyle.fontSize * 1.1f)
                    else -> SpanStyle(fontWeight = FontWeight.Bold)
                }
                withStyle(headerStyle) {
                    appendInline(content, linkColor, codeBackground)
                }
                continue
            }
            
            // Check for list items
            val listMatch = Regex("^\\s*[-*+]\\s+(.+)$").find(line)
            if (listMatch != null) {
                append("• ")
                appendInline(listMatch.groupValues[1], linkColor, codeBackground)
                continue
            }
            
            // Check for numbered list
            val numberedMatch = Regex("^\\s*(\\d+)\\.\\s+(.+)$").find(line)
            if (numberedMatch != null) {
                append("${numberedMatch.groupValues[1]}. ")
                appendInline(numberedMatch.groupValues[2], linkColor, codeBackground)
                continue
            }
            
            // Regular line - process inline formatting
            appendInline(line, linkColor, codeBackground)
        }
    }
}

private fun AnnotatedString.Builder.appendInline(
    text: String,
    linkColor: Color,
    codeBackground: Color
) {
    var i = 0
    while (i < text.length) {
        // Code block (```)
        if (text.startsWith("```", i)) {
            val endIndex = text.indexOf("```", i + 3)
            if (endIndex != -1) {
                val code = text.substring(i + 3, endIndex).trimStart('\n').trimEnd()
                withStyle(SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = codeBackground
                )) {
                    append(code)
                }
                i = endIndex + 3
                continue
            }
        }
        
        // Inline code (`)
        if (text[i] == '`') {
            val endIndex = text.indexOf('`', i + 1)
            if (endIndex != -1) {
                val code = text.substring(i + 1, endIndex)
                withStyle(SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = codeBackground
                )) {
                    append(code)
                }
                i = endIndex + 1
                continue
            }
        }
        
        // Links [text](url)
        if (text[i] == '[') {
            val linkMatch = Regex("^\\[([^]]+)]\\(([^)]+)\\)").find(text.substring(i))
            if (linkMatch != null) {
                val linkText = linkMatch.groupValues[1]
                val url = linkMatch.groupValues[2]
                pushStringAnnotation("URL", url)
                withStyle(SpanStyle(
                    color = linkColor,
                    textDecoration = TextDecoration.Underline
                )) {
                    append(linkText)
                }
                pop()
                i += linkMatch.value.length
                continue
            }
        }
        
        // Auto-detect URLs
        val urlMatch = Regex("^https?://[^\\s]+").find(text.substring(i))
        if (urlMatch != null) {
            val url = urlMatch.value
            pushStringAnnotation("URL", url)
            withStyle(SpanStyle(
                color = linkColor,
                textDecoration = TextDecoration.Underline
            )) {
                append(url)
            }
            pop()
            i += url.length
            continue
        }
        
        // Bold (**text** or __text__)
        if (text.startsWith("**", i) || text.startsWith("__", i)) {
            val marker = text.substring(i, i + 2)
            val endIndex = text.indexOf(marker, i + 2)
            if (endIndex != -1) {
                val boldText = text.substring(i + 2, endIndex)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(boldText)
                }
                i = endIndex + 2
                continue
            }
        }
        
        // Italic (*text* or _text_) - must check after bold
        if ((text[i] == '*' || text[i] == '_') && 
            (i + 1 < text.length && text[i + 1] != text[i])) {
            val marker = text[i]
            val endIndex = text.indexOf(marker, i + 1)
            if (endIndex != -1 && endIndex > i + 1) {
                val italicText = text.substring(i + 1, endIndex)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(italicText)
                }
                i = endIndex + 1
                continue
            }
        }
        
        // Strikethrough (~~text~~)
        if (text.startsWith("~~", i)) {
            val endIndex = text.indexOf("~~", i + 2)
            if (endIndex != -1) {
                val strikeText = text.substring(i + 2, endIndex)
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    append(strikeText)
                }
                i = endIndex + 2
                continue
            }
        }
        
        // Regular character
        append(text[i])
        i++
    }
}
