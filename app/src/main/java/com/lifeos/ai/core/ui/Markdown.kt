package com.lifeos.ai.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.lifeos.ai.core.theme.MonoStyle

/** Lightweight Markdown block model (headings, lists, quotes, code blocks, paragraphs). */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String, val marker: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data object Rule : MdBlock
}

object MarkdownParser {
    fun parse(src: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val lines = src.replace("\r\n", "\n").split("\n")
        var i = 0
        val para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) blocks += MdBlock.Paragraph(para.toString().trim())
            para.clear()
        }
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") -> {
                    flush()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) { code.appendLine(lines[i]); i++ }
                    blocks += MdBlock.Code(code.toString().trimEnd())
                }
                trimmed.matches(Regex("^#{1,6}\\s+.*")) -> {
                    flush()
                    val level = trimmed.takeWhile { it == '#' }.length
                    blocks += MdBlock.Heading(level, trimmed.drop(level).trim())
                }
                trimmed.matches(Regex("^[-*+]\\s+.*")) -> { flush(); blocks += MdBlock.Bullet(trimmed.drop(2).trim(), "•") }
                trimmed.matches(Regex("^\\d+[.)]\\s+.*")) -> {
                    flush()
                    val num = trimmed.takeWhile { it.isDigit() }
                    blocks += MdBlock.Bullet(trimmed.substringAfter(' ').trim(), "$num.")
                }
                trimmed.startsWith(">") -> { flush(); blocks += MdBlock.Quote(trimmed.drop(1).trim()) }
                trimmed.matches(Regex("^(-{3,}|\\*{3,}|_{3,})$")) -> { flush(); blocks += MdBlock.Rule }
                trimmed.isEmpty() -> flush()
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(trimmed) }
            }
            i++
        }
        flush()
        return blocks
    }

    /** Inline: **bold**, *italic* / _italic_, `code`. */
    fun inline(text: String, codeBackground: Color): AnnotatedString = buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i + 2) { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }; i = end + 2 }
                    else { append(text[i]); i++ }
                }
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i + 1) {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(text.substring(i + 1, end)) }
                        i = end + 1
                    } else { append(text[i]); i++ }
                }
                (text[i] == '*' || text[i] == '_') && i + 1 < text.length && text[i + 1] != ' ' -> {
                    val marker = text[i]
                    val end = text.indexOf(marker, i + 1)
                    if (end > i + 1 && (marker == '*' || (i == 0 || !text[i - 1].isLetterOrDigit()))) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                        i = end + 1
                    } else { append(text[i]); i++ }
                }
                else -> { append(text[i]); i++ }
            }
        }
    }
}

@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) Spacer(Modifier.height(6.dp))
            when (block) {
                is MdBlock.Heading -> Text(
                    MarkdownParser.inline(block.text, codeBg), color = color,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                )
                is MdBlock.Bullet -> Row {
                    Text(block.marker, color = color, modifier = Modifier.width(22.dp))
                    Text(MarkdownParser.inline(block.text, codeBg), color = color, style = MaterialTheme.typography.bodyMedium)
                }
                is MdBlock.Code -> Text(
                    block.text, style = MonoStyle, color = color,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(codeBg).padding(10.dp),
                )
                is MdBlock.Quote -> Text(
                    MarkdownParser.inline(block.text, codeBg), color = color.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    modifier = Modifier.padding(start = 10.dp),
                )
                is MdBlock.Paragraph -> Text(MarkdownParser.inline(block.text, codeBg), color = color, style = MaterialTheme.typography.bodyMedium)
                MdBlock.Rule -> Spacer(Modifier.fillMaxWidth().height(1.dp).background(color.copy(alpha = 0.2f)))
            }
        }
    }
}
