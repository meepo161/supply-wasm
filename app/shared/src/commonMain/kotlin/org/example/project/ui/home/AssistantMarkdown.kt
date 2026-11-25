package org.example.project.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.readableAccent

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class BulletItem(val marker: String, val text: String) : MdBlock
    data class CodeBlock(val lang: String, val code: String) : MdBlock
    data class TableBlock(val headers: List<String>, val rows: List<List<String>>) : MdBlock
}

@Composable
fun AssistantMarkdown(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.titleMedium
                        2 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.bodyMedium
                    }
                    Text(
                        text = parseInlineStyles(block.text),
                        style = style.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.onSurface,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                is MdBlock.Paragraph -> {
                    Text(
                        text = parseInlineStyles(block.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface
                    )
                }

                is MdBlock.BulletItem -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = block.marker,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = readableAccent(Brand.Blue),
                            modifier = Modifier.width(if (block.marker == "•") 12.dp else 22.dp)
                        )
                        Text(
                            text = parseInlineStyles(block.text),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                is MdBlock.CodeBlock -> {
                    MarkdownCodeBlock(lang = block.lang, code = block.code)
                }

                is MdBlock.TableBlock -> {
                    MarkdownTable(headers = block.headers, rows = block.rows)
                }
            }
        }
    }
}

@Composable
private fun MarkdownCodeBlock(lang: String, code: String) {
    val scheme = MaterialTheme.colorScheme
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surfaceContainerHighest.copy(alpha = 0.7f))
            .border(1.dp, scheme.outlineVariant, shape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.surfaceVariant.copy(alpha = 0.6f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = lang.ifBlank { "текст" },
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant
            )
            Text(
                text = if (copied) "Скопировано ✓" else "Копировать",
                style = MaterialTheme.typography.labelSmall,
                color = if (copied) readableAccent(Brand.Teal) else readableAccent(Brand.Blue),
                modifier = Modifier.clickable {
                    clipboard.setText(AnnotatedString(code))
                    copied = true
                }
            )
        }
        Text(
            text = code,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = scheme.onSurface,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
private fun MarkdownTable(headers: List<String>, rows: List<List<String>>) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    val colCount = maxOf(headers.size, rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, scheme.outlineVariant, shape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.surfaceVariant.copy(alpha = 0.55f))
                .height(IntrinsicSize.Min)
        ) {
            for (c in 0 until colCount) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = parseInlineStyles(headers.getOrElse(c) { "" }),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface
                    )
                }
            }
        }
        rows.forEach { row ->
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.6f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
            ) {
                for (c in 0 until colCount) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = parseInlineStyles(row.getOrElse(c) { "" }),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun parseInlineStyles(text: String): AnnotatedString {
    val scheme = MaterialTheme.colorScheme
    val codeBg = scheme.surfaceVariant.copy(alpha = 0.75f)
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append("**")
                        i += 2
                    }
                }
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end != -1) {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = codeBg
                            )
                        ) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append('`')
                        i++
                    }
                }
                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }
}

private fun parseMarkdownBlocks(raw: String): List<MdBlock> {
    val lines = raw.replace("\r\n", "\n").lines()
    val result = mutableListOf<MdBlock>()
    var i = 0
    val paragraphBuf = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraphBuf.isNotEmpty()) {
            result.add(MdBlock.Paragraph(paragraphBuf.joinToString("\n")))
            paragraphBuf.clear()
        }
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        if (trimmed.startsWith("```")) {
            flushParagraph()
            val lang = trimmed.removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            result.add(MdBlock.CodeBlock(lang, codeLines.joinToString("\n")))
            i++
            continue
        }

        if (trimmed.isEmpty()) {
            flushParagraph()
            i++
            continue
        }

        if (trimmed.startsWith("|") && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
            flushParagraph()
            val headers = splitTableRow(trimmed)
            i += 2
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && lines[i].trim().startsWith("|")) {
                rows.add(splitTableRow(lines[i].trim()))
                i++
            }
            result.add(MdBlock.TableBlock(headers, rows))
            continue
        }

        val headingMatch = Regex("^(#{1,3})\\s+(.+)$").matchEntire(trimmed)
        if (headingMatch != null) {
            flushParagraph()
            result.add(MdBlock.Heading(headingMatch.groupValues[1].length, headingMatch.groupValues[2]))
            i++
            continue
        }

        val bulletMatch = Regex("^[-*]\\s+(.+)$").matchEntire(trimmed)
        if (bulletMatch != null) {
            flushParagraph()
            result.add(MdBlock.BulletItem("•", bulletMatch.groupValues[1]))
            i++
            continue
        }

        val numberedMatch = Regex("^(\\d+\\.)\\s+(.+)$").matchEntire(trimmed)
        if (numberedMatch != null) {
            flushParagraph()
            result.add(MdBlock.BulletItem(numberedMatch.groupValues[1], numberedMatch.groupValues[2]))
            i++
            continue
        }

        paragraphBuf.add(trimmed)
        i++
    }
    flushParagraph()
    return result
}

private fun isTableSeparator(line: String): Boolean {
    val t = line.trim()
    return t.startsWith("|") && t.replace("|", "").replace("-", "").replace(":", "").isBlank()
}

private fun splitTableRow(line: String): List<String> =
    line.trim().trim('|').split('|').map { it.trim() }
