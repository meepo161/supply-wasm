package org.example.project.ui.search

import org.example.project.ui.userText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.dto.SearchGroupDto
import org.example.project.dto.SearchResultItemDto
import org.example.project.dto.SearchResultType
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.ErpIcon
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.Gradients
import org.example.project.ui.theme.LocalBrandPalette
import org.example.project.ui.theme.PillBadge
import org.example.project.ui.theme.SkeletonLine

/**
 * Глобальный поиск в верхней панели (ТЗ §4.3, M.9).
 *
 * Требования, которые соблюдаются:
 * - поиск постоянно доступен из верхней панели;
 * - результаты группируются по типам и ведут к первичным записям;
 * - сервер сам ограничивает выборку правами, клиент не запрашивает закрытые типы;
 * - нереализованные типы объектов не показываются как категории.
 */
@Composable
fun GlobalSearchBox(
    viewModel: SearchViewModel,
    onOpenTask: (Long) -> Unit,
    onOpenProject: (Long) -> Unit,
    onOpenDocument: (Long) -> Unit = {},
    onOpenNomenclature: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    val palette = LocalBrandPalette.current
    val focusRequester = remember { FocusRequester() }

    val flat = viewModel.flatResults()

    Box(modifier = modifier.widthIn(max = 460.dp)) {
        // Поле ввода
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(palette.glassOverlay.copy(alpha = 0.7f))
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        listOf(
                            if (expanded) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                            else palette.glassBorder[0],
                            palette.glassBorder[1]
                        )
                    ),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ErpIcon(
                glyph = ErpIconGlyph.Search,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp)
            )
            Spacer(Modifier.width(9.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (state.query.isEmpty()) {
                    Text(
                        text = "Поиск: задачи, проекты, заказы, склад, сотрудники",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = state.query,
                    onValueChange = {
                        viewModel.updateQuery(it)
                        expanded = true
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.submitImmediately() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) expanded = true
                        }
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> {
                                    viewModel.moveHighlight(1)
                                    true
                                }
                                Key.DirectionUp -> {
                                    viewModel.moveHighlight(-1)
                                    true
                                }
                                Key.Escape -> {
                                    expanded = false
                                    viewModel.clear()
                                    true
                                }
                                Key.Enter -> {
                                    val target = viewModel.highlighted()
                                    if (target != null) {
                                        expanded = false
                                        openResult(target, onOpenTask, onOpenProject, onOpenDocument, onOpenNomenclature)
                                        true
                                    } else {
                                        viewModel.submitImmediately()
                                        false
                                    }
                                }
                                else -> false
                            }
                        }
                )
            }
            if (state.query.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f))
                        .clickable {
                            viewModel.clear()
                            expanded = false
                        },
                    contentAlignment = Alignment.Center
                ) {
                    ErpIcon(
                        glyph = ErpIconGlyph.Close,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = expanded && state.query.isNotEmpty(),
            enter = fadeIn() + scaleIn(initialScale = 0.97f),
            exit = fadeOut() + scaleOut(targetScale = 0.97f)
        ) {
            SearchDropdown(
                state = state,
                highlightedIndex = state.highlightedIndex,
                onHover = { index -> viewModel.setHighlighted(index) },
                onSelect = { ref ->
                    expanded = false
                    openResult(ref, onOpenTask, onOpenProject, onOpenDocument, onOpenNomenclature)
                },
                modifier = Modifier.padding(top = 46.dp)
            )
        }
    }

    // Панель закрывается по Esc, по клику на крестик и при переходе в результат.
    // Клик вне панели в Compose/Wasm без overlay ловить нецелесообразно:
    // панель перекрывает только собственную область и не блокирует остальной UI.
    LaunchedEffect(state.query) {
        if (state.query.isEmpty()) expanded = false
    }
}

private fun openResult(
    ref: SearchResultRef,
    onOpenTask: (Long) -> Unit,
    onOpenProject: (Long) -> Unit,
    onOpenDocument: (Long) -> Unit,
    onOpenNomenclature: (Long) -> Unit
) {
    when (ref.type) {
        SearchResultType.TASK -> onOpenTask(ref.id)
        SearchResultType.PROJECT -> onOpenProject(ref.id)
        SearchResultType.DOCUMENT -> onOpenDocument(ref.id)
        SearchResultType.NOMENCLATURE -> onOpenNomenclature(ref.id)
        SearchResultType.MESSAGE -> (ref.parentId ?: ref.id).let(onOpenTask)
        SearchResultType.USER,
        SearchResultType.PURCHASE_ORDER,
        SearchResultType.DEMAND,
        SearchResultType.SUPPLIER,
        SearchResultType.RECEIPT,
        SearchResultType.PRODUCTION_ORDER -> Unit
    }
}

@Composable
private fun SearchDropdown(
    state: SearchUiState,
    highlightedIndex: Int,
    onHover: (Int) -> Unit,
    onSelect: (SearchResultRef) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalBrandPalette.current
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(palette.glassOverlay)
            .border(1.dp, palette.glassBorder[0].copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .heightIn(max = 460.dp)
    ) {
        when {
            state.isLoading && state.groups.isEmpty() -> Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                repeat(3) { SkeletonLine(height = 34.dp) }
            }

            state.error != null -> Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Поиск недоступен",
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface
                )
                Text(
                    text = state.error.userText(),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }

            state.isEmptyResult -> Column(
                modifier = Modifier.padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ErpIcon(
                    glyph = ErpIconGlyph.Search,
                    tint = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Ничего не найдено",
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface
                )
                Text(
                    text = "Ни проектов, ни задач, ни сотрудников по запросу «${state.query.trim()}»",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }

            else -> Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 6.dp)
            ) {
                var index = 0
                state.groups.forEach { group ->
                    GroupHeader(group)
                    group.items.forEach { item ->
                        val currentIndex = index
                        val highlighted = currentIndex == highlightedIndex
                        SearchResultRow(
                            item = item,
                            highlighted = highlighted,
                            onClick = {
                                onSelect(
                                    SearchResultRef(
                                        type = item.type,
                                        id = item.id,
                                        title = item.title,
                                        number = item.number,
                                        parentId = item.parentId
                                    )
                                )
                            },
                            onHover = { onHover(currentIndex) }
                        )
                        index += 1
                    }
                }
                SizedBoxSeparator()
                Text(
                    text = "Стрелки — навигация · Enter — открыть · Esc — закрыть",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun GroupHeader(group: SearchGroupDto) {
    val (label, accent, glyph) = when (group.type) {
        SearchResultType.PROJECT -> Triple("Проекты", Brand.Indigo, ErpIconGlyph.Projects)
        SearchResultType.TASK -> Triple("Задачи", Brand.Blue, ErpIconGlyph.Tasks)
        SearchResultType.USER -> Triple("Сотрудники", Brand.Teal, ErpIconGlyph.User)
        SearchResultType.DOCUMENT -> Triple("Документы", Brand.Violet, ErpIconGlyph.Documents)
        SearchResultType.NOMENCLATURE -> Triple("Номенклатура", Brand.Amber, ErpIconGlyph.Layers)
        SearchResultType.MESSAGE -> Triple("Сообщения", Brand.Cyan, ErpIconGlyph.Send)
        SearchResultType.PURCHASE_ORDER -> Triple("Заказы поставщикам", Brand.Teal, ErpIconGlyph.Documents)
        SearchResultType.DEMAND -> Triple("Потребности", Brand.Amber, ErpIconGlyph.Documents)
        SearchResultType.SUPPLIER -> Triple("Поставщики", Brand.Indigo, ErpIconGlyph.User)
        SearchResultType.RECEIPT -> Triple("Приёмки", Brand.Blue, ErpIconGlyph.Layers)
        SearchResultType.PRODUCTION_ORDER -> Triple("Производство", Brand.Violet, ErpIconGlyph.Layers)
    }
    Row(
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ErpIcon(
            glyph = glyph,
            tint = accent,
            modifier = Modifier.size(13.dp)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = accent.copy(alpha = 0.9f)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = "${group.items.size}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        if (group.hasMore) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "ещё есть",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun SearchResultRow(
    item: SearchResultItemDto,
    highlighted: Boolean,
    onClick: () -> Unit,
    onHover: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val accent = when (item.type) {
        SearchResultType.PROJECT -> Brand.Indigo
        SearchResultType.TASK -> Brand.Blue
        SearchResultType.USER -> Brand.Teal
        SearchResultType.DOCUMENT -> Brand.Violet
        SearchResultType.NOMENCLATURE -> Brand.Amber
        SearchResultType.MESSAGE -> Brand.Cyan
        SearchResultType.PURCHASE_ORDER -> Brand.Teal
        SearchResultType.DEMAND -> Brand.Amber
        SearchResultType.SUPPLIER -> Brand.Indigo
        SearchResultType.RECEIPT -> Brand.Blue
        SearchResultType.PRODUCTION_ORDER -> Brand.Violet
    }
    val gradient = when (item.type) {
        SearchResultType.PROJECT -> Gradients.Aurora
        SearchResultType.TASK -> Gradients.Ocean
        SearchResultType.USER -> Gradients.Emerald
        SearchResultType.DOCUMENT -> Gradients.Slate
        SearchResultType.NOMENCLATURE -> Gradients.AmberGlow
        SearchResultType.MESSAGE -> Gradients.Ocean
        SearchResultType.PURCHASE_ORDER -> Gradients.Emerald
        SearchResultType.DEMAND -> Gradients.AmberGlow
        SearchResultType.SUPPLIER -> Gradients.Aurora
        SearchResultType.RECEIPT -> Gradients.Ocean
        SearchResultType.PRODUCTION_ORDER -> Gradients.Slate
    }
    val glyph = when (item.type) {
        SearchResultType.PROJECT -> ErpIconGlyph.Projects
        SearchResultType.TASK -> ErpIconGlyph.Tasks
        SearchResultType.USER -> ErpIconGlyph.User
        SearchResultType.DOCUMENT -> ErpIconGlyph.Documents
        SearchResultType.NOMENCLATURE -> ErpIconGlyph.Layers
        SearchResultType.MESSAGE -> ErpIconGlyph.Send
        SearchResultType.PURCHASE_ORDER -> ErpIconGlyph.Documents
        SearchResultType.DEMAND -> ErpIconGlyph.Documents
        SearchResultType.SUPPLIER -> ErpIconGlyph.User
        SearchResultType.RECEIPT -> ErpIconGlyph.Layers
        SearchResultType.PRODUCTION_ORDER -> ErpIconGlyph.Layers
    }

    LaunchedEffect(hovered) {
        if (hovered) onHover()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (highlighted || hovered) accent.copy(alpha = 0.10f) else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Brush.linearGradient(gradient)),
            contentAlignment = Alignment.Center
        ) {
            ErpIcon(glyph = glyph, tint = Color.White, modifier = Modifier.size(13.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val secondary = listOfNotNull(item.number, item.subtitle).joinToString(" · ")
            if (secondary.isNotBlank()) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        item.status?.let { status ->
            Spacer(Modifier.width(8.dp))
            PillBadge(text = status, accent = accent)
        }
    }
}

@Composable
private fun SizedBoxSeparator() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    )
}
