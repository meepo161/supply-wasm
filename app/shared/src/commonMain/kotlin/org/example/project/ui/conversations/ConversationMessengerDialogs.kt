package org.example.project.ui.conversations

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.dto.ConversationAiSummaryDto
import org.example.project.dto.ConversationMessageDto
import org.example.project.dto.ConversationPollDto
import org.example.project.dto.PinnedMessageDto
import org.example.project.dto.UserPresenceStatus
import org.example.project.ui.theme.Avatar
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.Button
import org.example.project.ui.theme.ErpDialog
import org.example.project.ui.theme.ErpFormat
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.Gradients
import org.example.project.ui.theme.OutlinedTextField
import org.example.project.ui.theme.PillBadge
import org.example.project.ui.theme.SoftButton
import org.example.project.ui.theme.TextButton

@Composable
fun PinnedMessagesBanner(
    pins: List<PinnedMessageDto>,
    canUnpin: Boolean,
    onJump: (Long) -> Unit,
    onUnpin: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (pins.isEmpty()) return
    var index by remember(pins.size) { mutableIntStateOf(0) }
    val safeIndex = index.coerceIn(0, pins.lastIndex)
    val pin = pins[safeIndex]
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onJump(pin.messageId) },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(28.dp)
                    .background(Brand.Blue, RoundedCornerShape(2.dp))
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (pins.size > 1) "Закреплено (${safeIndex + 1}/${pins.size})" else "Закреплённое сообщение",
                    style = MaterialTheme.typography.labelSmall,
                    color = Brand.Blue,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = listOfNotNull(pin.authorName, pin.contentPreview).joinToString(": "),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (pins.size > 1) {
                TextButton(onClick = { index = (safeIndex + 1) % pins.size }) {
                    Text("След.")
                }
            }
            if (canUnpin) {
                TextButton(onClick = { onUnpin(pin.messageId) }) {
                    Text("Открепить")
                }
            }
        }
    }
}

@Composable
fun PollMessageCard(
    poll: ConversationPollDto,
    contentColor: Color,
    onVote: (List<Long>) -> Unit
) {
    val total = poll.totalVotes.coerceAtLeast(1)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = poll.question,
            style = MaterialTheme.typography.titleSmall,
            color = contentColor,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = buildString {
                append(if (poll.isAnonymous) "Анонимный опрос" else "Открытый опрос")
                if (poll.isMultiple) append(" · несколько вариантов")
                append(" · голосов: ${poll.totalVotes}")
            },
            style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = 0.72f)
        )
        poll.options.forEach { option ->
            val fraction = if (poll.totalVotes == 0) 0f else option.votesCount.toFloat() / total.toFloat()
            val percent = (fraction * 100).toInt()
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !poll.closed) {
                        val nextIds = if (poll.isMultiple) {
                            val current = poll.options.filter { it.votedByMe }.map { it.id }
                            if (option.id in current) current - option.id else current + option.id
                        } else {
                            listOf(option.id)
                        }
                        if (nextIds.isNotEmpty()) onVote(nextIds)
                    },
                shape = RoundedCornerShape(8.dp),
                color = if (option.votedByMe) contentColor.copy(alpha = 0.2f) else contentColor.copy(alpha = 0.08f)
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = (if (option.votedByMe) "✓ " else "") + option.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor,
                            fontWeight = if (option.votedByMe) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "$percent% (${option.votesCount})",
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.85f)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                        color = if (option.votedByMe) Brand.Teal else contentColor.copy(alpha = 0.65f),
                        trackColor = contentColor.copy(alpha = 0.14f)
                    )
                }
            }
        }
    }
}

@Composable
fun CreatePollDialog(
    onDismiss: () -> Unit,
    onCreate: (question: String, options: List<String>, isAnonymous: Boolean, isMultiple: Boolean) -> Unit
) {
    var question by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    var isAnonymous by remember { mutableStateOf(false) }
    var isMultiple by remember { mutableStateOf(false) }
    val cleanOptions = options.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    val valid = question.isNotBlank() && cleanOptions.size >= 2

    ErpDialog(
        title = "Создать опрос",
        glyph = ErpIconGlyph.Sparkle,
        gradient = Gradients.Ocean,
        width = 520.dp,
        confirmText = "Опубликовать",
        confirmEnabled = valid,
        onConfirm = {
            onCreate(question.trim(), cleanOptions, isAnonymous, isMultiple)
            onDismiss()
        },
        onDismiss = onDismiss
    ) {
        OutlinedTextField(
            value = question,
            onValueChange = { question = it },
            label = { Text("Вопрос *") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Text("Варианты ответа", style = MaterialTheme.typography.labelMedium)
        options.forEachIndexed { idx, value ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { options[idx] = it },
                    label = { Text("Вариант ${idx + 1}") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                if (options.size > 2) {
                    TextButton(onClick = { options.removeAt(idx) }) { Text("✕") }
                }
            }
        }
        if (options.size < 8) {
            TextButton(onClick = { options.add("") }, modifier = Modifier.padding(top = 4.dp)) {
                Text("+ Добавить вариант")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = isAnonymous, onCheckedChange = { isAnonymous = it })
            Text("Анонимное голосование", style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = isMultiple, onCheckedChange = { isMultiple = it })
            Text("Разрешить несколько вариантов", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun ThreadDrawerDialog(
    rootMessage: ConversationMessageDto,
    threadMessages: List<ConversationMessageDto>,
    isLoading: Boolean,
    canReply: Boolean,
    onSendReply: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(rootMessage.id) { mutableStateOf("") }
    ErpDialog(
        title = "Ветка обсуждения",
        subtitle = "${rootMessage.author?.fullName ?: "Сообщение"} · ${ErpFormat.dateTimeRu(rootMessage.createdAt)}",
        glyph = ErpIconGlyph.Send,
        gradient = Gradients.Primary,
        width = 620.dp,
        onDismiss = onDismiss
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Text(
                text = rootMessage.content,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp)
            )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
        if (isLoading) {
            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val replies = threadMessages.filter { it.id != rootMessage.id }
            if (replies.isEmpty()) {
                Text(
                    "Ответов в ветке пока нет. Начните обсуждение!",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    replies.forEach { reply ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Avatar(name = reply.author?.fullName ?: "?", size = 26.dp)
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        reply.author?.fullName ?: "Автор",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        reply.createdAt.substringAfter('T').take(5),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(reply.content, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        if (canReply) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Ответить в ветке…") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .onPreviewKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown && ev.key == Key.Escape) {
                                onDismiss()
                                true
                            } else if (ev.type == KeyEventType.KeyDown && ev.key == Key.Enter && draft.isNotBlank()) {
                                onSendReply(draft)
                                draft = ""
                                true
                            } else false
                        }
                )
                Button(
                    onClick = {
                        if (draft.isNotBlank()) {
                            onSendReply(draft)
                            draft = ""
                        }
                    },
                    enabled = draft.isNotBlank()
                ) {
                    Text("Отправить")
                }
            }
        }
    }
}

@Composable
fun ScheduleMessageDialog(
    initialContent: String,
    scheduledMessages: List<ConversationMessageDto>,
    onSchedule: (content: String, scheduledAtIso: String?, isSilent: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var content by remember { mutableStateOf(initialContent) }
    var scheduledAt by remember { mutableStateOf("") }
    var isSilent by remember { mutableStateOf(false) }

    ErpDialog(
        title = "Отправка и планирование",
        subtitle = "Отправка без звука или по расписанию (ISO-8601)",
        glyph = ErpIconGlyph.Clock,
        gradient = Gradients.Ocean,
        width = 560.dp,
        confirmText = if (scheduledAt.isBlank()) "Отправить без звука" else "Запланировать",
        confirmEnabled = content.isNotBlank(),
        onConfirm = {
            onSchedule(content, scheduledAt.trim().takeIf { it.isNotBlank() }, isSilent || scheduledAt.isBlank())
            onDismiss()
        },
        onDismiss = onDismiss
    ) {
        OutlinedTextField(
            value = content,
            onValueChange = { content = it },
            label = { Text("Текст сообщения *") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = scheduledAt,
            onValueChange = { scheduledAt = it },
            label = { Text("Время отправки (например, 2026-11-27T09:00:00+03:00, пусто = сразу)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Checkbox(checked = isSilent, onCheckedChange = { isSilent = it })
            Text("Отправить тихо (без звукового уведомления)", style = MaterialTheme.typography.bodySmall)
        }
        if (scheduledMessages.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text("Запланированные сообщения (${scheduledMessages.size})", style = MaterialTheme.typography.labelMedium)
            scheduledMessages.forEach { msg ->
                Text(
                    text = "• ${msg.scheduledAt ?: "—"} — ${msg.content.take(80)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
fun ConversationAiSummaryDialog(
    summary: ConversationAiSummaryDto?,
    isLoading: Boolean,
    onCreateTaskFromAction: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ErpDialog(
        title = "ИИ-сводка обсуждения",
        subtitle = "Ключевые тезисы и выявленные поручения",
        glyph = ErpIconGlyph.Sparkle,
        gradient = Gradients.Aurora,
        width = 580.dp,
        onDismiss = onDismiss
    ) {
        if (isLoading) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (summary != null) {
            Text(summary.summary, style = MaterialTheme.typography.bodyMedium)
            if (summary.actionItems.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                Text(
                    "Потенциальные задачи из переписки (${summary.actionItems.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    summary.actionItems.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(item, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            SoftButton(
                                text = "В задачу",
                                onClick = {
                                    onCreateTaskFromAction(item)
                                    onDismiss()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

fun UserPresenceStatus.localizedLabel(): String = when (this) {
    UserPresenceStatus.ONLINE -> "В сети"
    UserPresenceStatus.AWAY -> "Отошёл"
    UserPresenceStatus.DND -> "Не беспокоить"
    UserPresenceStatus.OFFLINE -> "Не в сети"
}

fun UserPresenceStatus.dotColor(): Color = when (this) {
    UserPresenceStatus.ONLINE -> Brand.Teal
    UserPresenceStatus.AWAY -> Brand.Amber
    UserPresenceStatus.DND -> Brand.Rose
    UserPresenceStatus.OFFLINE -> Color.Gray
}
