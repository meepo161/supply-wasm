package org.example.project.ui.conversations

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.project.data.AttachmentFileData
import org.example.project.dto.ReactionSummaryDto
import org.example.project.ui.tasks.ChatTextKind
import org.example.project.ui.tasks.parseChatFormatting
import org.example.project.ui.theme.ErpIcon
import org.example.project.ui.theme.ErpIconGlyph
import org.jetbrains.skia.Image as SkiaImage
import androidx.compose.ui.graphics.toComposeImageBitmap

val CHAT_REACTION_EMOJIS = listOf("👍", "❤️", "✅", "👀", "🎉")

@Composable
fun formattedChatText(content: String, highlight: String? = null): AnnotatedString = buildAnnotatedString {
    val query = highlight?.trim().orEmpty()
    parseChatFormatting(content).forEach { segment ->
        val style = when (segment.kind) {
            ChatTextKind.PLAIN -> SpanStyle()
            ChatTextKind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
            ChatTextKind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
            ChatTextKind.CODE -> SpanStyle(
                fontFamily = FontFamily.Monospace,
                background = MaterialTheme.colorScheme.surfaceVariant
            )
        }
        withStyle(style) {
            if (query.isEmpty()) {
                append(segment.text)
            } else {
                var start = 0
                while (start < segment.text.length) {
                    val match = segment.text.indexOf(query, start, ignoreCase = true)
                    if (match < 0) {
                        append(segment.text.substring(start))
                        break
                    }
                    append(segment.text.substring(start, match))
                    withStyle(SpanStyle(background = MaterialTheme.colorScheme.tertiaryContainer)) {
                        append(segment.text.substring(match, match + query.length))
                    }
                    start = match + query.length
                }
            }
        }
    }
}

@Composable
fun PendingAttachmentRow(
    file: AttachmentFileData,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            if (org.example.project.ui.tasks.isPreviewableImage(file.mimeType, file.bytes.size.toLong())) {
                DecodedImagePreview(file.bytes)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ErpIcon(ErpIconGlyph.Documents, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(file.fileName, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(6.dp))
                Text(formatAttachmentSize(file.bytes.size.toLong()), style = MaterialTheme.typography.labelSmall)
            }
        }
        TextButton(onClick = onRemove) { Text("Удалить") }
    }
}

@Composable
fun DecodedImagePreview(bytes: ByteArray, modifier: Modifier = Modifier) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, key1 = bytes) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                withGuardedEncodedImage(bytes) {
                    val decoded = SkiaImage.makeFromEncoded(bytes)
                    if (!areImageDimensionsSafe(decoded.width, decoded.height)) {
                        null
                    } else {
                        decoded.toComposeImageBitmap()
                    }
                }
            }.getOrNull()
        }
    }
    bitmap?.let { decodedBitmap ->
        Image(
            bitmap = decodedBitmap,
            contentDescription = "Предпросмотр изображения",
            contentScale = ContentScale.Fit,
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .clip(RoundedCornerShape(8.dp))
        )
    }
}

@Composable
fun ChatReactionRow(
    reactions: List<ReactionSummaryDto>,
    canWrite: Boolean,
    pickerOpen: Boolean,
    onPickerToggle: () -> Unit,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            reactions.filter { it.emoji in CHAT_REACTION_EMOJIS }.forEach { reaction ->
                Surface(
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable(enabled = canWrite) { onToggle(reaction.emoji) },
                    shape = RoundedCornerShape(50),
                    color = if (reaction.reactedByMe) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                ) {
                    Text(
                        "${reaction.emoji} ${reaction.count}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
            if (canWrite) TextButton(onClick = onPickerToggle) { Text("☺") }
        }
        if (canWrite && pickerOpen) {
            Row {
                CHAT_REACTION_EMOJIS.forEach { emoji ->
                    Text(
                        emoji,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onToggle(emoji) }
                            .padding(4.dp)
                    )
                }
            }
        }
    }
}
