package org.example.project.ui.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.data.AttachmentFileData
import org.example.project.dto.AssistantConversationSummaryDto
import org.example.project.dto.AssistantDraftDto
import org.example.project.dto.AssistantMessageDto
import org.example.project.dto.AssistantRole
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.ErpIcon
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.GlassCard
import org.example.project.ui.theme.GradientButton
import org.example.project.ui.theme.GradientSpinner
import org.example.project.ui.theme.Gradients
import org.example.project.ui.theme.IconBadge
import org.example.project.ui.theme.PillBadge
import org.example.project.ui.theme.SoftButton
import org.example.project.ui.theme.TextButton
import org.example.project.ui.theme.readableAccent

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssistantHomeCard(
    available: Boolean,
    model: String,
    statusReason: String?,
    conversations: List<AssistantConversationSummaryDto>,
    activeConversationId: Long?,
    messages: List<AssistantMessageDto>,
    pendingFiles: List<AttachmentFileData>,
    input: String,
    isLoading: Boolean,
    error: String?,
    onInputChange: (String) -> Unit,
    onPickFile: () -> Unit,
    onRemovePendingFile: (Int) -> Unit,
    onSelectConversation: (Long) -> Unit,
    onDeleteConversation: (Long) -> Unit,
    onSend: (String?) -> Unit,
    onConfirmDraft: (String) -> Unit,
    onDismissDraft: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    var expanded by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    LaunchedEffect(messages.size, isLoading) {
        scroll.animateScrollTo(scroll.maxValue)
    }

    GlassCard(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        contentPadding = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconBadge(
                    glyph = ErpIconGlyph.Sparkle,
                    gradient = Gradients.Primary,
                    size = 36.dp
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Помощник оператора",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onSurface
                        )
                        PillBadge(
                            text = if (available) "$model · Онлайн" else "Оффлайн",
                            accent = if (available) Brand.Teal else Brand.Amber
                        )
                    }
                    Text(
                        text = if (available) {
                            "Поиск по ERP и разбор документов (PDF, фото, MD, TXT) по вашим правам"
                        } else {
                            statusReason ?: "Модель $model не подключена"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }

                if (conversations.isNotEmpty()) {
                    SoftButton(
                        text = if (showHistory) "Скрыть историю" else "История (${conversations.size})",
                        onClick = { showHistory = !showHistory }
                    )
                }
                SoftButton(
                    text = if (expanded) "Свернуть" else "Развернуть",
                    onClick = { expanded = !expanded }
                )
                if (messages.isNotEmpty()) {
                    TextButton(onClick = onClear) {
                        Text("Новый диалог")
                    }
                }
            }

            if (showHistory && conversations.isNotEmpty()) {
                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(scheme.surfaceVariant.copy(alpha = 0.25f))
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    conversations.forEach { conv ->
                        val isActive = conv.id == activeConversationId
                        val chipShape = RoundedCornerShape(10.dp)
                        Row(
                            modifier = Modifier
                                .clip(chipShape)
                                .background(
                                    if (isActive) Brand.Blue.copy(alpha = 0.14f)
                                    else scheme.surface.copy(alpha = 0.8f)
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isActive) Brand.Blue.copy(alpha = 0.45f) else scheme.outlineVariant,
                                    shape = chipShape
                                )
                                .clickable {
                                    onSelectConversation(conv.id)
                                    showHistory = false
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column {
                                Text(
                                    text = conv.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                    color = scheme.onSurface,
                                    maxLines = 1
                                )
                                if (conv.updatedAt.isNotBlank()) {
                                    Text(
                                        text = conv.updatedAt,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(
                                text = "✕",
                                style = MaterialTheme.typography.labelSmall,
                                color = readableAccent(Brand.Rose),
                                modifier = Modifier.clickable { onDeleteConversation(conv.id) }
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))

            // Main conversation or empty welcome state
            if (messages.isEmpty()) {
                WelcomeStarterSection(
                    isLoading = isLoading,
                    onSelectPrompt = { prompt -> onSend(prompt) },
                    onPickFile = onPickFile
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = if (expanded) 560.dp else 300.dp)
                        .verticalScroll(scroll)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    messages.forEach { msg ->
                        if (msg.role == AssistantRole.USER) {
                            UserMessageRow(msg)
                        } else {
                            AssistantMessageRow(
                                message = msg,
                                isLoading = isLoading,
                                onConfirmDraft = onConfirmDraft,
                                onDismissDraft = onDismissDraft
                            )
                        }
                    }

                    if (isLoading) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            GradientSpinner(size = 18.dp)
                            Text(
                                text = "Помощник анализирует запрос и проверяет данные ERP…",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            error?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = readableAccent(Brand.Rose),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }

            // Unified desktop composer
            ComposerBox(
                input = input,
                pendingFiles = pendingFiles,
                isLoading = isLoading,
                onInputChange = onInputChange,
                onPickFile = onPickFile,
                onRemovePendingFile = onRemovePendingFile,
                onSend = { onSend(null) },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WelcomeStarterSection(
    isLoading: Boolean,
    onSelectPrompt: (String) -> Unit,
    onPickFile: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Чем помочь по ERP сегодня?",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            StarterCard(
                title = "Мои задачи",
                subtitle = "Открытые и просроченные назначения",
                glyph = ErpIconGlyph.Tasks,
                accent = Brand.Blue,
                enabled = !isLoading,
                onClick = { onSelectPrompt("Какие у меня открытые и просроченные задачи?") }
            )
            StarterCard(
                title = "Остатки на складе",
                subtitle = "Поиск номенклатуры, ячеек и резервов",
                glyph = ErpIconGlyph.Warehouse,
                accent = Brand.Teal,
                enabled = !isLoading,
                onClick = { onSelectPrompt("Что сейчас лежит на складе?") }
            )
            StarterCard(
                title = "Снабжение",
                subtitle = "Последние потребности и заказы поставщикам",
                glyph = ErpIconGlyph.Cart,
                accent = Brand.Indigo,
                enabled = !isLoading,
                onClick = { onSelectPrompt("Покажи последние потребности и заказы поставщикам") }
            )
            StarterCard(
                title = "Разобрать счёт или файл",
                subtitle = "Прикрепите PDF, фото, MD или TXT",
                glyph = ErpIconGlyph.Attachment,
                accent = Brand.Amber,
                enabled = !isLoading,
                onClick = onPickFile
            )
        }
    }
}

@Composable
private fun StarterCard(
    title: String,
    subtitle: String,
    glyph: ErpIconGlyph,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .widthIn(min = 220.dp, max = 320.dp)
            .clip(shape)
            .background(scheme.surfaceVariant.copy(alpha = 0.42f))
            .border(1.dp, scheme.outlineVariant.copy(alpha = 0.8f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            ErpIcon(glyph = glyph, tint = readableAccent(accent), modifier = Modifier.size(16.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserMessageRow(message: AssistantMessageDto) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 680.dp)
                .clip(shape)
                .background(Brand.Blue.copy(alpha = 0.12f))
                .border(1.dp, Brand.Blue.copy(alpha = 0.28f), shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (message.attachments.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    message.attachments.forEach { att ->
                        PillBadge(text = att.fileName, accent = Brand.Blue)
                    }
                }
            }
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssistantMessageRow(
    message: AssistantMessageDto,
    isLoading: Boolean,
    onConfirmDraft: (String) -> Unit,
    onDismissDraft: (String) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var copied by remember(message.content) { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        IconBadge(
            glyph = ErpIconGlyph.Sparkle,
            gradient = Gradients.Primary,
            size = 28.dp
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistantMarkdown(markdown = message.content)

            message.draft?.let { draft ->
                DraftConfirmationCard(
                    draft = draft,
                    isLoading = isLoading,
                    onConfirm = { onConfirmDraft(draft.draftId) },
                    onDismiss = { onDismissDraft(draft.draftId) }
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 2.dp)
            ) {
                val toolUsed = message.toolUsed
                if (!toolUsed.isNullOrBlank()) {
                    val labels = toolUsed.split(",").map { localizeToolName(it.trim()) }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        labels.forEach { label ->
                            PillBadge(text = label, accent = Brand.Teal)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (copied) "Скопировано ✓" else "Копировать ответ",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (copied) readableAccent(Brand.Teal) else scheme.onSurfaceVariant,
                    modifier = Modifier.clickable {
                        clipboard.setText(AnnotatedString(message.content))
                        copied = true
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComposerBox(
    input: String,
    pendingFiles: List<AttachmentFileData>,
    isLoading: Boolean,
    onInputChange: (String) -> Unit,
    onPickFile: () -> Unit,
    onRemovePendingFile: (Int) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val canSend = !isLoading && (input.isNotBlank() || pendingFiles.isNotEmpty())

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surface.copy(alpha = 0.85f))
            .border(1.dp, scheme.outlineVariant, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (pendingFiles.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pendingFiles.forEachIndexed { index, file ->
                    val chipShape = RoundedCornerShape(10.dp)
                    val ext = file.fileName.substringAfterLast('.', "").uppercase().ifBlank { "FILE" }
                    Row(
                        modifier = Modifier
                            .clip(chipShape)
                            .background(Brand.Blue.copy(alpha = 0.10f))
                            .border(1.dp, Brand.Blue.copy(alpha = 0.3f), chipShape)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = ext,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = readableAccent(Brand.Blue)
                        )
                        Text(
                            text = "${file.fileName} · ${formatBytesShort(file.bytes.size)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurface
                        )
                        Text(
                            text = "✕",
                            style = MaterialTheme.typography.labelMedium,
                            color = readableAccent(Brand.Rose),
                            modifier = Modifier.clickable(enabled = !isLoading) {
                                onRemovePendingFile(index)
                            }
                        )
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            if (input.isEmpty()) {
                Text(
                    text = "Спросите про ERP или прикрепите счёт/документ (PDF, фото, MD, TXT)…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
            }
            BasicTextField(
                value = input,
                onValueChange = onInputChange,
                enabled = !isLoading,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                cursorBrush = SolidColor(Brand.Blue),
                maxLines = 6,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 24.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            (event.key == Key.Enter || event.key == Key.NumPadEnter) &&
                            !event.isShiftPressed
                        ) {
                            if (canSend) onSend()
                            true
                        } else {
                            false
                        }
                    }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(scheme.surfaceVariant.copy(alpha = 0.55f))
                    .clickable(enabled = !isLoading, onClick = onPickFile)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ErpIcon(glyph = ErpIconGlyph.Attachment, tint = scheme.onSurface, modifier = Modifier.size(14.dp))
                Text(
                    text = "Прикрепить файл",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurface
                )
            }

            Text(
                text = "Enter — отправить · Shift+Enter — перенос",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.weight(1f)
            )

            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(
                        if (canSend) Brush.linearGradient(Gradients.Primary)
                        else Brush.linearGradient(listOf(scheme.surfaceVariant, scheme.surfaceVariant))
                    )
                    .clickable(enabled = canSend, onClick = onSend),
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    GradientSpinner(size = 18.dp)
                } else {
                    ErpIcon(
                        glyph = ErpIconGlyph.Send,
                        tint = if (canSend) Color.White else scheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DraftConfirmationCard(
    draft: AssistantDraftDto,
    isLoading: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val accent = if (draft.executed) Brand.Teal else Brand.Blue

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(accent.copy(alpha = 0.06f))
            .border(1.dp, accent.copy(alpha = 0.30f), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = draft.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface
                )
                Text(
                    text = draft.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
            PillBadge(
                text = if (draft.executed) "Выполнено" else "Черновик",
                accent = if (draft.executed) Brand.Teal else Brand.Amber
            )
        }

        draft.supplier?.let { sup ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PillBadge(
                    text = if (sup.existingSupplierId != null) "Поставщик в базе" else "Новый поставщик",
                    accent = if (sup.existingSupplierId != null) Brand.Teal else Brand.Blue
                )
                Text(
                    text = buildString {
                        append(sup.name)
                        sup.inn?.let { append(" · ИНН $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurface
                )
            }
        }

        if (draft.lines.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, scheme.outlineVariant.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            ) {
                draft.lines.forEachIndexed { idx, line ->
                    if (idx > 0) HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.45f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = line.name,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = scheme.onSurface
                            )
                            Text(
                                text = buildString {
                                    if (line.code.isNotBlank()) append("Код: ${line.code} · ")
                                    append("${line.quantity} ${line.unit}")
                                    line.unitPrice?.let { append(" · $it") }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant
                            )
                        }
                        val statusText = when {
                            line.existingNomenclatureId != null ->
                                "В базе (остаток: ${line.inStockQty ?: "0"})"
                            line.createIfMissing -> "Создать позицию"
                            else -> "Не найдено"
                        }
                        PillBadge(
                            text = statusText,
                            accent = if (line.existingNomenclatureId != null) Brand.Teal else Brand.Blue
                        )
                    }
                }
            }
        }

        if (draft.actions.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                draft.actions.forEach { action ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(scheme.surface.copy(alpha = 0.6f))
                            .border(1.dp, scheme.outlineVariant.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = action.label,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = scheme.onSurface
                        )
                        val details = action.params.entries
                            .filter { it.value.isNotBlank() }
                            .joinToString(" · ") { "${it.key}: ${it.value}" }
                        if (details.isNotBlank()) {
                            Text(
                                text = details,
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        if (draft.executed) {
            draft.executionSummary?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = readableAccent(Brand.Teal)
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GradientButton(
                    text = if (isLoading) "Выполняю…" else "Подтвердить и создать",
                    onClick = onConfirm,
                    enabled = !isLoading
                )
                SoftButton(
                    text = "Отменить",
                    onClick = onDismiss,
                    enabled = !isLoading
                )
            }
        }
    }
}

private fun localizeToolName(name: String): String = when (name) {
    "search_erp" -> "Поиск по ERP"
    "my_tasks" -> "Мои задачи"
    "projects_list" -> "Проекты"
    "wms_stock" -> "Складские остатки"
    "wms_low_stock" -> "Дефицит на складе"
    "check_supplier" -> "Проверка поставщика"
    "demands_list" -> "Потребности"
    "purchase_orders" -> "Заказы поставщикам"
    "production_orders" -> "Производство"
    "prepare_supply_draft" -> "Черновик снабжения"
    "prepare_task_draft" -> "Черновик задачи"
    "prepare_project_draft" -> "Черновик проекта"
    "prepare_wms_action_draft" -> "Складской черновик"
    else -> name
}

private fun formatBytesShort(bytes: Int): String = when {
    bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} МБ"
    bytes >= 1024 -> "${bytes / 1024} КБ"
    else -> "$bytes Б"
}
