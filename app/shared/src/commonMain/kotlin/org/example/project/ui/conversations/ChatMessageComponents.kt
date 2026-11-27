package org.example.project.ui.conversations

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import org.example.project.ui.theme.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
fun DecodedImagePreview(
    bytes: ByteArray,
    modifier: Modifier = Modifier,
    fileName: String? = null,
    onDownload: (() -> Unit)? = null
) {
    var lightboxOpen by remember(bytes) { mutableStateOf(false) }
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
                .clickable { lightboxOpen = true }
        )
        if (lightboxOpen) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { lightboxOpen = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.85f))
                        .clickable { lightboxOpen = false }
                        .padding(24.dp)
                ) {
                    Image(
                        bitmap = decodedBitmap,
                        contentDescription = fileName ?: "Изображение",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxSize(0.9f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = false) {}
                    )
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (onDownload != null) {
                            org.example.project.ui.theme.SoftButton(
                                text = "Скачать",
                                onClick = onDownload,
                                leadingIcon = ErpIconGlyph.Documents
                            )
                        }
                        org.example.project.ui.theme.SoftButton(
                            text = "Закрыть",
                            onClick = { lightboxOpen = false },
                            leadingIcon = ErpIconGlyph.Close
                        )
                    }
                    fileName?.let {
                        Text(
                            text = it,
                            color = Color.White.copy(alpha = 0.9f),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatReactionRow(
    reactions: List<ReactionSummaryDto>,
    canWrite: Boolean,
    pickerOpen: Boolean,
    onPickerToggle: () -> Unit,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
    actionColor: Color = MaterialTheme.colorScheme.primary,
    showAddButton: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            reactions.filter { it.emoji in CHAT_REACTION_EMOJIS }.forEach { reaction ->
                val chip: @Composable () -> Unit = {
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
                if (reaction.reactedBy.isEmpty()) {
                    chip()
                } else {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                        tooltip = {
                            PlainTooltip {
                                Column {
                                    reaction.reactedBy.forEach { author ->
                                        Text(author.fullName, maxLines = 1)
                                    }
                                }
                            }
                        },
                        state = rememberTooltipState()
                    ) {
                        chip()
                    }
                }
            }
            if (canWrite && showAddButton) {
                IconButton(
                    onClick = onPickerToggle,
                    modifier = Modifier.size(24.dp),
                    interactionSource = interactionSource
                ) {
                    Text(
                        text = "☺",
                        color = actionColor.copy(alpha = if (hovered || pickerOpen) 0.95f else 0.45f),
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
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
                if (!showAddButton) {
                    TextButton(onClick = onPickerToggle) {
                        Text("Отмена", color = actionColor, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
