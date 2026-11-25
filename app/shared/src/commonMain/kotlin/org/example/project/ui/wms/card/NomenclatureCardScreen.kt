package org.example.project.ui.wms.card

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.data.AttachmentRepository
import org.example.project.data.BomRepository
import org.example.project.data.DemandRepository
import org.example.project.data.NomenclatureCardRepository
import org.example.project.data.WmsRepository
import org.example.project.dto.NomenclatureCardDto
import org.example.project.dto.UserDto
import org.example.project.dto.hasPermission
import org.example.project.ui.conversations.DecodedImagePreview
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.ErpFormat
import org.example.project.ui.theme.ErpIconButton
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.GradientButton
import org.example.project.ui.theme.Gradients
import org.example.project.ui.theme.IconBadge
import org.example.project.ui.theme.PillBadge
import org.example.project.ui.theme.SkeletonLine
import org.example.project.ui.theme.SoftButton
import org.example.project.ui.theme.erpInteractive
import org.example.project.ui.theme.TextButton
import org.example.project.ui.userText
import org.example.project.ui.wms.localizedName
import org.koin.compose.koinInject

/**
 * Карточка номенклатуры (NSI-001, D-63). Одна карточка для всех типов:
 * вкладка «Изделие» появляется только у позиции, связанной с моделью.
 * Все вкладки видит держатель `wms:read`; кнопки изменения — только при
 * `wms:manage`, потребность — при `demands:create`. Сервер проверяет права повторно.
 */
@Composable
fun NomenclatureCardScreen(
    nomenclatureId: Long,
    currentUser: UserDto?,
    onBack: () -> Unit,
    onArchived: () -> Unit,
    modifier: Modifier = Modifier
) {
    val repository: NomenclatureCardRepository = koinInject()
    val attachments: AttachmentRepository = koinInject()
    val demands: DemandRepository = koinInject()
    val bom: BomRepository = koinInject()
    val wms: WmsRepository = koinInject()
    val documentRegistry: org.example.project.data.DocumentRepository = koinInject()
    val viewModel = remember(nomenclatureId) {
        NomenclatureCardViewModel(nomenclatureId, repository, attachments, demands, bom, wms, documentRegistry)
    }
    // ViewModel создаётся вне ViewModelStore: корутины отменяются при уходе с карточки.
    DisposableEffect(viewModel) { onDispose { viewModel.dispose() } }
    val state by viewModel.state.collectAsState()

    val canManage = currentUser?.hasPermission("wms", "manage") == true
    val canCreateDemand = currentUser?.hasPermission("demands", "create") == true

    var dialog by remember { mutableStateOf<CardDialog?>(null) }
    // Успешная команда закрывает открытый диалог.
    LaunchedEffect(state.successTick) {
        if (state.successTick > 0) {
            if (dialog == CardDialog.Archive) onArchived()
            dialog = null
        }
    }

    // Прокручивается вся карточка: на невысоком экране шапка и сводка остатков
    // не должны съедать место у содержимого вкладки.
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val card = state.card
        when {
            card == null && state.loading -> CardSkeleton(onBack)
            card == null -> {
                TextButton(onClick = onBack) { Text("← Назад") }
                state.loadError?.let { ErrorMessageCard(error = it) }
                SoftButton(text = "Повторить", onClick = viewModel::reload, leadingIcon = ErpIconGlyph.Refresh)
            }
            else -> {
                CardHeader(
                    card = card,
                    photo = state.photo,
                    canManage = canManage,
                    canCreateDemand = canCreateDemand,
                    busy = state.busy,
                    onBack = onBack,
                    onRefresh = viewModel::reload,
                    onEdit = { dialog = CardDialog.Edit },
                    onDemand = { dialog = CardDialog.Demand },
                    onArchive = { dialog = CardDialog.Archive }
                )
                StockStrip(card)
                state.notice?.let { NoticeBanner(it, onClose = viewModel::dismissMessages) }
                if (dialog == null) {
                    state.actionError?.let { ErrorMessageCard(error = it) }
                }
                CardTabs(state = state, onSelect = viewModel::selectTab)
                CardTabContent(
                    state = state,
                    card = card,
                    canManage = canManage,
                    viewModel = viewModel,
                    onOpenDialog = { dialog = it }
                )
            }
        }
    }

    val card = state.card ?: return
    when (val current = dialog) {
        CardDialog.Edit -> EditCardDialog(
            card = card,
            state = state,
            onCreateCategory = viewModel::createCategory,
            onSave = viewModel::save,
            onDismiss = { dialog = null; viewModel.dismissMessages() }
        )
        CardDialog.Demand -> DemandDialog(
            card = card,
            busy = state.busy,
            error = state.actionError,
            idempotencyKey = remember { viewModel.newIdempotencyKey() },
            onCreate = viewModel::createDemand,
            onDismiss = { dialog = null; viewModel.dismissMessages() }
        )
        CardDialog.Archive -> ArchiveDialog(
            card = card,
            busy = state.busy,
            error = state.actionError,
            onConfirm = viewModel::archive,
            onDismiss = { dialog = null; viewModel.dismissMessages() }
        )
        CardDialog.Analog -> AnalogDialog(
            card = card,
            busy = state.busy,
            error = state.actionError,
            onCreate = viewModel::addAnalog,
            onDismiss = { dialog = null; viewModel.dismissMessages() }
        )
        null -> Unit
    }
}

enum class CardDialog { Edit, Demand, Archive, Analog }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardHeader(
    card: NomenclatureCardDto,
    photo: ByteArray?,
    canManage: Boolean,
    canCreateDemand: Boolean,
    busy: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onDemand: () -> Unit,
    onArchive: () -> Unit
) {
    val item = card.item
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = scheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Text("← Назад")
                }
                ErpIconButton(glyph = ErpIconGlyph.Refresh, contentDescription = "Обновить карточку", onClick = onRefresh, enabled = !busy)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (photo != null) {
                        DecodedImagePreview(bytes = photo, modifier = Modifier.size(96.dp))
                    } else {
                        IconBadge(glyph = ErpIconGlyph.Layers, gradient = Gradients.Slate, size = 96.dp, iconSize = 36.dp)
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PillBadge(text = item.code, accent = Brand.Indigo, filled = true)
                        PillBadge(text = item.type.localizedName(), accent = Brand.Slate)
                        if (item.archived) PillBadge(text = "Архив", accent = Brand.Slate, filled = true)
                        if (item.lotTracked) PillBadge(text = "Учёт по партиям", accent = Brand.Teal)
                        if (item.serialTracked) PillBadge(text = "Серийный учёт", accent = Brand.Teal)
                        when {
                            card.stock.belowMinStock -> PillBadge(text = "Ниже минимального запаса", accent = Brand.Rose, filled = true)
                            card.stock.belowSafetyStock -> PillBadge(text = "Ниже страхового запаса", accent = Brand.Amber)
                        }
                        card.productModel?.let { PillBadge(text = "Изделие ${it.code}", accent = Brand.Violet) }
                    }
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subtitle = listOfNotNull(
                        card.category?.path,
                        item.article?.let { "арт. $it" },
                        item.manufacturer,
                        "ед. ${item.unit}"
                    ).joinToString(" · ")
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val suggestion = card.demandSuggestion
                if (canCreateDemand && !item.archived) {
                    if (suggestion != null) {
                        GradientButton(
                            text = "Сформировать потребность · ${ErpFormat.qty(suggestion.suggestedQty)} ${item.unit}",
                            onClick = onDemand,
                            enabled = !busy,
                            leadingIcon = ErpIconGlyph.Cart
                        )
                    } else {
                        SoftButton(text = "Сформировать потребность", onClick = onDemand, enabled = !busy, leadingIcon = ErpIconGlyph.Cart)
                    }
                }
                if (canManage && !item.archived) {
                    SoftButton(text = "Редактировать", onClick = onEdit, enabled = !busy)
                    // Вторичная кнопка; подтверждение — в диалоге «Перевести позицию в архив?».
                    SoftButton(text = "Архивировать", onClick = onArchive, enabled = !busy)
                }
            }
        }
    }
}

/** Семь видов остатка §8.3. Доступное выделено; цвет тревоги — только при нехватке. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StockStrip(card: NomenclatureCardDto) {
    val stock = card.stock
    val unit = card.item.unit
    val alarm = stock.belowMinStock || stock.belowSafetyStock
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        StockMetric("Доступно", stock.availableQty, unit, emphasized = true, alarm = alarm,
            hint = if (alarm) "страховой ${ErpFormat.qty(card.item.safetyStock)}, мин. ${ErpFormat.qty(card.item.minStock)}" else null)
        StockMetric("Физически", stock.physicalQty, unit, hint = stock.temporaryQty.takeIf { it.toDoubleOrNull() ?: 0.0 > 0.0 }?.let { "в т. ч. временное ${ErpFormat.qty(it)}" })
        StockMetric("В резерве", stock.reservedQty, unit)
        StockMetric("Ожидается", stock.expectedQty, unit, hint = card.expectedSupplies.size.takeIf { it > 0 }?.let { "по $it заказ${plural(it, "у", "ам", "ам")}" })
        StockMetric("В пути", stock.inTransitQty, unit)
        StockMetric("Карантин", stock.quarantineQty, unit)
        StockMetric("Брак", stock.defectQty, unit)
    }
}

@Composable
private fun StockMetric(
    label: String,
    value: String,
    unit: String,
    emphasized: Boolean = false,
    alarm: Boolean = false,
    hint: String? = null
) {
    val scheme = MaterialTheme.colorScheme
    val accent = if (alarm) scheme.error else if (emphasized) scheme.primary else scheme.outlineVariant
    Surface(
        modifier = Modifier.widthIn(min = 128.dp),
        shape = RoundedCornerShape(8.dp),
        color = scheme.surfaceContainerLowest,
        border = BorderStroke(if (emphasized) 1.5.dp else 1.dp, accent.copy(alpha = if (emphasized || alarm) 0.8f else 0.6f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    ErpFormat.qty(value),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (alarm) scheme.error else scheme.onSurface
                )
                Text(" $unit", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            hint?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = if (alarm) scheme.error else scheme.onSurfaceVariant) }
        }
    }
}

/**
 * Вкладки переносятся на новую строку, а не прячутся за прокруткой: при ширине
 * около 1024 px все разделы карточки остаются видны (N-14).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CardTabs(state: NomenclatureCardUiState, onSelect: (NomenclatureCardTab) -> Unit) {
    val tabs = state.visibleTabs
    val counts = state.card?.counts
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            tabs.forEach { tab ->
                val count = when (tab) {
                    NomenclatureCardTab.RESERVATIONS -> counts?.activeReservations
                    NomenclatureCardTab.MOVEMENTS -> counts?.movements
                    NomenclatureCardTab.USAGE -> counts?.usage
                    NomenclatureCardTab.FILES -> counts?.let { it.documents + it.attachments }
                    NomenclatureCardTab.HISTORY -> counts?.history
                    else -> null
                }
                val selected = state.tab == tab
                val shape = RoundedCornerShape(6.dp)
                val interaction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .clip(shape)
                        .background(if (selected) scheme.primary.copy(alpha = 0.10f) else Color.Transparent)
                        .erpInteractive(interaction, shape)
                        .clickable(interactionSource = interaction, indication = null, onClick = { onSelect(tab) })
                        .semantics { role = Role.Tab; this.selected = selected }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = if (count != null && count > 0) "${tab.title} · $count" else tab.title,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        color = if (selected) scheme.primary else scheme.onSurfaceVariant,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.6f))
    }
}

@Composable
private fun NoticeBanner(text: String, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = scheme.primary.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.3f))
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            ErpIconButton(glyph = ErpIconGlyph.Close, contentDescription = "Скрыть сообщение", onClick = onClose)
        }
    }
}

@Composable
private fun CardSkeleton(onBack: () -> Unit) {
    TextButton(onClick = onBack) { Text("← Назад") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SkeletonLine(widthFraction = 0.3f)
        SkeletonLine(widthFraction = 0.6f, height = 22.dp)
        SkeletonLine(widthFraction = 0.9f)
        SkeletonLine(widthFraction = 0.8f)
    }
}

internal fun plural(n: Int, one: String, few: String, many: String): String {
    val mod10 = n % 10
    val mod100 = n % 100
    return when {
        mod10 == 1 && mod100 != 11 -> one
        mod10 in 2..4 && mod100 !in 12..14 -> few
        else -> many
    }
}

internal val NeutralGrey = Color(0xFF55637A)
