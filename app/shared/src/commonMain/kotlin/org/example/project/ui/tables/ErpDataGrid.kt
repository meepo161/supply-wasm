package org.example.project.ui.tables

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.unchil.un7datagrid.Un7KCMPDataGridConfig
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.ErpIcon
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.LocalBrandPalette

private enum class SortDirection {
    NONE, ASC, DESC
}

private enum class GridDensity(val label: String, val rowHeight: Dp, val cellVerticalPadding: Dp) {
    COMPACT("Компакт", 36.dp, 6.dp),
    STANDARD("Стандарт", 44.dp, 9.dp),
    COMFORTABLE("Свободно", 54.dp, 13.dp)
}

private data class GridRow(
    val originalIndex: Int,
    val values: List<Any?>
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ErpDataGrid(
    data: Map<String, List<Any?>>,
    modifier: Modifier = Modifier,
    config: Un7KCMPDataGridConfig = Un7KCMPDataGridConfig(),
    compact: Boolean = false,
    showSearch: Boolean = true,
    onClick: ((List<Pair<Int, List<Any?>>>) -> Unit)? = null,
    onLongClick: ((List<Pair<Int, List<Any?>>>) -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current

    val columnKeys = remember(data) { data.keys.toList() }
    val rowCount = remember(data) { data.values.maxOfOrNull { it.size } ?: 0 }
    val allRows = remember(data, columnKeys, rowCount) {
        List(rowCount) { rowIdx ->
            GridRow(
                originalIndex = rowIdx,
                values = columnKeys.map { key -> data[key]?.getOrNull(rowIdx) }
            )
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    var sortColumnIndex by remember { mutableStateOf<Int?>(null) }
    var sortDirection by remember { mutableStateOf(SortDirection.NONE) }
    var selectedOriginalIndex by remember { mutableStateOf<Int?>(null) }
    var gridDensity by remember(compact) { mutableStateOf(if (compact) GridDensity.COMPACT else GridDensity.STANDARD) }
    var columnsMenuExpanded by remember { mutableStateOf(false) }

    val pageSizes = remember(config.pageSizeList) {
        val parsed = config.pageSizeList.mapNotNull { it.toIntOrNull() }.filter { it > 0 }.distinct().take(4)
        if (parsed.isNotEmpty()) parsed else listOf(20, 50, 100)
    }
    var pageSize by remember(config.pageSizeList, config.defaultPageSizeListIndex) {
        val idx = config.defaultPageSizeListIndex.coerceIn(0, (pageSizes.size - 1).coerceAtLeast(0))
        mutableStateOf(pageSizes.getOrElse(idx) { 20 })
    }
    var showAllRows by remember { mutableStateOf(true) }
    var currentPage by remember { mutableStateOf(0) }

    val hiddenColumns = remember(columnKeys) { mutableStateMapOf<String, Boolean>() }
    val visibleColumnIndices = remember(columnKeys, hiddenColumns.toMap()) {
        columnKeys.indices.filter { hiddenColumns[columnKeys[it]] != true }
    }

    // Ширины колонок в dp (инициализируются из сохранённых весов или умных значений по заголовку)
    // Пересчитываются, когда появляются первые данные: ширина по умолчанию учитывает длину значений.
    val hasRows = rowCount > 0
    val columnWidthsDp = remember(columnKeys, config.initialColumnWeights, hasRows) {
        mutableStateMapOf<String, Float>().apply {
            columnKeys.forEach { key ->
                val saved = config.initialColumnWeights[key]
                this[key] = if (saved != null && saved > 40f) saved else defaultColumnWidthDp(key, data[key])
            }
        }
    }

    // Фильтрация
    val filteredRows = remember(allRows, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) allRows
        else allRows.filter { row ->
            row.values.any { cell ->
                cell?.toString()?.lowercase()?.contains(q) == true
            }
        }
    }

    // Сортировка
    val sortedRows = remember(filteredRows, sortColumnIndex, sortDirection) {
        val col = sortColumnIndex
        if (col == null || sortDirection == SortDirection.NONE) {
            filteredRows
        } else {
            val cmp = Comparator<GridRow> { a, b ->
                compareCellValues(a.values.getOrNull(col), b.values.getOrNull(col))
            }
            if (sortDirection == SortDirection.ASC) filteredRows.sortedWith(cmp)
            else filteredRows.sortedWith(cmp.reversed())
        }
    }

    val totalFiltered = sortedRows.size
    val totalPages = ((totalFiltered + pageSize - 1) / pageSize).coerceAtLeast(1)
    val safePage = currentPage.coerceIn(0, totalPages - 1)
    if (safePage != currentPage) currentPage = safePage

    val pageRows = remember(sortedRows, safePage, pageSize, showAllRows) {
        if (showAllRows) sortedRows else paginateList(sortedRows, safePage, pageSize)
    }

    val outerShape = RoundedCornerShape(14.dp)
    val isDark = LocalBrandPalette.current.isDark

    Surface(
        shape = outerShape,
        color = if (isDark) scheme.surfaceContainerLow.copy(alpha = 0.96f) else scheme.surface.copy(alpha = 0.92f),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, scheme.outlineVariant.copy(alpha = if (isDark) 0.85f else 0.6f), outerShape)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!compact) {
            // 1. Верхняя панель инструментов (адаптивная для узких master-панелей)
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                val isNarrow = maxWidth < 540.dp
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (showSearch) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            currentPage = 0
                        },
                        placeholder = {
                            Text(
                                if (isNarrow) "Поиск..." else "Быстрый поиск по таблице...",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = {
                            ErpIcon(
                                glyph = ErpIconGlyph.Search,
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp)
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable {
                                            searchQuery = ""
                                            currentPage = 0
                                        }
                                        .padding(4.dp)
                                ) {
                                    ErpIcon(
                                        glyph = ErpIconGlyph.Close,
                                        tint = scheme.onSurfaceVariant,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = scheme.surface,
                            unfocusedContainerColor = scheme.surface.copy(alpha = 0.7f),
                            focusedBorderColor = Brand.Indigo.copy(alpha = 0.7f),
                            unfocusedBorderColor = scheme.outlineVariant.copy(alpha = 0.55f)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).height(44.dp)
                    )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }

                    if (!isNarrow) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = Brand.Indigo.copy(alpha = 0.12f)
                        ) {
                            val countLabel = if (searchQuery.isNotBlank()) {
                                "Найдено: $totalFiltered из ${allRows.size}"
                            } else {
                                "Всего: ${allRows.size}"
                            }
                            Text(
                                text = countLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Brand.Indigo,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = scheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    val entries = GridDensity.entries
                                    gridDensity = entries[(gridDensity.ordinal + 1) % entries.size]
                                }
                        ) {
                            Text(
                                text = gridDensity.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = scheme.onSurface,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }

                    // Меню колонок
                    Box {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = scheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { columnsMenuExpanded = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                ErpIcon(
                                    glyph = ErpIconGlyph.Grid,
                                    tint = scheme.onSurface,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = if (isNarrow) "${visibleColumnIndices.size}/${columnKeys.size}" else "Колонки (${visibleColumnIndices.size}/${columnKeys.size})",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = scheme.onSurface
                                )
                            }
                        }
                    DropdownMenu(
                        expanded = columnsMenuExpanded,
                        onDismissRequest = { columnsMenuExpanded = false }
                    ) {
                        columnKeys.forEach { key ->
                            val isVisible = hiddenColumns[key] != true
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Checkbox(
                                            checked = isVisible,
                                            onCheckedChange = null
                                        )
                                        Text(key, style = MaterialTheme.typography.bodySmall)
                                    }
                                },
                                onClick = {
                                    if (isVisible && visibleColumnIndices.size <= 1) return@DropdownMenuItem
                                    hiddenColumns[key] = isVisible
                                }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Сбросить ширину колонок", style = MaterialTheme.typography.bodySmall, color = Brand.Indigo) },
                            onClick = {
                                columnKeys.forEach { key ->
                                    columnWidthsDp[key] = defaultColumnWidthDp(key, data[key])
                                }
                                config.onColumnWeightsChange(columnWidthsDp.toMap())
                                columnsMenuExpanded = false
                            }
                        )
                    }
                }
                }
            }

            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.55f))
            }

            // 2. Таблица (Sticky Header + LazyColumn строк + горизонтальный скролл)
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val containerWidthDp = maxWidth
                val rowNumColWidth = if (config.isVisibilityRowNumber) 48.dp else 0.dp
                val rawColumnsTotalDp = visibleColumnIndices.sumOf {
                    (columnWidthsDp[columnKeys[it]] ?: 140f).toDouble()
                }.toFloat().dp
                val separatorWidth = if (config.isVisibilityRowNumber) 1.dp else 0.dp
                val availableForColumns = (containerWidthDp - rowNumColWidth - separatorWidth).coerceAtLeast(0.dp)
                // Таблица всегда вписывается в ширину контейнера: колонки пропорционально
                // растягиваются или сжимаются, горизонтальной прокрутки нет. Длинные значения
                // обрезаются с многоточием; лишние колонки скрываются через «Колонки».
                val scaleFactor = if (rawColumnsTotalDp > 0.dp) availableForColumns / rawColumnsTotalDp else 1f

                Column(modifier = Modifier.fillMaxSize()) {
                    val effectiveTableWidth = containerWidthDp

                    // Заголовок таблицы
                    Row(
                        modifier = Modifier
                            .width(effectiveTableWidth)
                            .background(
                                config.headerRowBackgroundColor
                                    ?: scheme.surfaceVariant.copy(alpha = 0.55f)
                            )
                            .height(40.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (config.isVisibilityRowNumber) {
                            Box(
                                modifier = Modifier
                                    .width(rowNumColWidth)
                                    .fillMaxHeight()
                                    .padding(horizontal = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "#",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = config.headerRowContentColor ?: scheme.onSurfaceVariant
                                )
                            }
                            VerticalSeparator(scheme.outlineVariant.copy(alpha = 0.45f))
                        }

                        visibleColumnIndices.forEachIndexed { visIdx, colIdx ->
                            val key = columnKeys[colIdx]
                            val colWidth = ((columnWidthsDp[key] ?: 140f) * scaleFactor).dp
                            val isSorted = sortColumnIndex == colIdx && sortDirection != SortDirection.NONE

                            Box(
                                modifier = Modifier
                                    .width(colWidth)
                                    .fillMaxHeight()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clickable {
                                            if (sortColumnIndex != colIdx) {
                                                sortColumnIndex = colIdx
                                                sortDirection = SortDirection.ASC
                                            } else {
                                                sortDirection = when (sortDirection) {
                                                    SortDirection.NONE -> SortDirection.ASC
                                                    SortDirection.ASC -> SortDirection.DESC
                                                    SortDirection.DESC -> SortDirection.NONE
                                                }
                                                if (sortDirection == SortDirection.NONE) {
                                                    sortColumnIndex = null
                                                }
                                            }
                                            currentPage = 0
                                        }
                                        .padding(start = 10.dp, end = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = key,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isSorted) FontWeight.Bold else FontWeight.SemiBold,
                                        color = if (isSorted) Brand.Indigo else (config.headerRowContentColor ?: scheme.onSurface),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isSorted) {
                                        Text(
                                            text = if (sortDirection == SortDirection.ASC) "▲" else "▼",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Brand.Indigo
                                        )
                                    }
                                }

                                // Drag-handle для изменения ширины колонки
                                if (visIdx < visibleColumnIndices.lastIndex) {
                                    var dragging by remember { mutableStateOf(false) }
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.CenterEnd)
                                            .width(8.dp)
                                            .fillMaxHeight()
                                            .pointerInput(key) {
                                                detectHorizontalDragGestures(
                                                    onDragStart = { dragging = true },
                                                    onDragEnd = {
                                                        dragging = false
                                                        config.onColumnWeightsChange(columnWidthsDp.toMap())
                                                    },
                                                    onDragCancel = { dragging = false },
                                                    onHorizontalDrag = { change, dragAmount ->
                                                        change.consume()
                                                        val deltaDp = with(density) { dragAmount.toDp().value } / scaleFactor
                                                        val current = columnWidthsDp[key] ?: 140f
                                                        columnWidthsDp[key] = (current + deltaDp).coerceIn(56f, 680f)
                                                    }
                                                )
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(if (dragging) 2.5.dp else 1.dp)
                                                .height(if (dragging) 26.dp else 18.dp)
                                                .background(
                                                    if (dragging) Brand.Indigo else scheme.outlineVariant.copy(alpha = 0.6f)
                                                )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.7f))

                    // Тело таблицы
                    if (pageRows.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .width(effectiveTableWidth)
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(24.dp)
                            ) {
                                ErpIcon(
                                    glyph = ErpIconGlyph.Layers,
                                    tint = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(28.dp)
                                )
                                Text(
                                    text = if (allRows.isEmpty()) "Нет данных для отображения" else "По запросу «$searchQuery» ничего не найдено",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = scheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .width(effectiveTableWidth)
                                .weight(1f)
                        ) {
                            itemsIndexed(
                                items = pageRows,
                                key = { _, item -> item.originalIndex }
                            ) { idxOnPage, row ->
                                val interaction = remember { MutableInteractionSource() }
                                val hovered by interaction.collectIsHoveredAsState()
                                val isSelected = selectedOriginalIndex == row.originalIndex

                                val baseRowBg = when {
                                    isSelected -> Brand.Indigo.copy(alpha = 0.12f)
                                    hovered -> scheme.onSurface.copy(alpha = 0.045f)
                                    idxOnPage % 2 == 1 -> config.oddDataRowBackgroundColor ?: scheme.surfaceVariant.copy(alpha = 0.18f)
                                    else -> config.evenDataRowBackgroundColor ?: config.dataRowBackgroundColor ?: Color.Transparent
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(gridDensity.rowHeight)
                                        .background(baseRowBg)
                                        .combinedClickable(
                                            interactionSource = interaction,
                                            indication = null,
                                            onClick = {
                                                selectedOriginalIndex = row.originalIndex
                                                onClick?.invoke(listOf(row.originalIndex to row.values))
                                            },
                                            onLongClick = {
                                                selectedOriginalIndex = row.originalIndex
                                                onLongClick?.invoke(listOf(row.originalIndex to row.values))
                                            }
                                        ),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .width(3.dp)
                                                .fillMaxHeight()
                                                .background(Brand.Indigo)
                                        )
                                    }

                                    if (config.isVisibilityRowNumber) {
                                        val displayNo = if (showAllRows) idxOnPage + 1 else safePage * pageSize + idxOnPage + 1
                                        Box(
                                            modifier = Modifier
                                                .width(if (isSelected) rowNumColWidth - 3.dp else rowNumColWidth)
                                                .fillMaxHeight()
                                                .padding(horizontal = 6.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "$displayNo",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = scheme.onSurfaceVariant
                                            )
                                        }
                                        VerticalSeparator(scheme.outlineVariant.copy(alpha = 0.25f))
                                    }

                                    visibleColumnIndices.forEachIndexed { visIdx, colIdx ->
                                        val key = columnKeys[colIdx]
                                        val colWidth = ((columnWidthsDp[key] ?: 140f) * scaleFactor).dp
                                        val cellVal = row.values.getOrNull(colIdx)?.toString()?.trim().orEmpty()
                                        val isEmpty = cellVal.isEmpty() || cellVal == "—" || cellVal == "null"

                                        Box(
                                            modifier = Modifier
                                                .width(if (isSelected && !config.isVisibilityRowNumber && visIdx == 0) colWidth - 3.dp else colWidth)
                                                .fillMaxHeight()
                                                .padding(horizontal = 10.dp, vertical = gridDensity.cellVerticalPadding),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            val badgeColor = statusCellColorOrNull(key, cellVal)
                                            val isOverdueText = (key.contains("срок", ignoreCase = true) ||
                                                key.contains("действует до", ignoreCase = true) ||
                                                key.contains("дата", ignoreCase = true)) &&
                                                (cellVal.contains("просроч", ignoreCase = true) ||
                                                    cellVal.contains("истёк", ignoreCase = true) ||
                                                    cellVal.contains("истек", ignoreCase = true))
                                            if (badgeColor != null) {
                                                Surface(
                                                    shape = RoundedCornerShape(999.dp),
                                                    color = badgeColor.copy(alpha = 0.14f)
                                                ) {
                                                    Text(
                                                        text = cellVal,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = badgeColor,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                    )
                                                }
                                            } else {
                                                Text(
                                                    text = if (isEmpty) "—" else cellVal,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = if (isOverdueText || visIdx == 1 || key == "Код" || key == "Номер") FontWeight.Medium else FontWeight.Normal,
                                                    color = when {
                                                        isOverdueText -> Brand.Rose
                                                        isEmpty -> scheme.onSurfaceVariant.copy(alpha = 0.6f)
                                                        else -> config.dataRowContentColor ?: scheme.onSurface
                                                    },
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.28f))
                            }
                        }
                    }
                }
            }

            if (!compact) {
                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.55f))

                // 3. Нижняя панель пагинации
                ErpPaginationBar(
                    totalItems = totalFiltered,
                    currentPage = safePage,
                    pageSize = pageSize,
                    pageSizes = pageSizes,
                    showAll = showAllRows,
                    onPageChange = { currentPage = it },
                    onPageSizeChange = {
                        showAllRows = false
                        pageSize = it
                        currentPage = 0
                    },
                    onShowAllChange = {
                        showAllRows = true
                        currentPage = 0
                    }
                )
            }
        }
    }
}

@Composable
private fun VerticalSeparator(color: Color) {
    Box(
        modifier = Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(color)
    )
}

/**
 * Ширина колонки по умолчанию: по длине заголовка и типичной (90-й перцентиль) длине значений,
 * чтобы длинные названия получали место, а короткие числа не растягивались.
 * Без данных используется оценка по названию колонки.
 */
private fun defaultColumnWidthDp(columnName: String, values: List<Any?>?): Float {
    val lengths = values.orEmpty().asSequence().take(500)
        .map { it?.toString()?.length ?: 0 }.sorted().toList()
    if (lengths.isEmpty()) return defaultColumnWidthByName(columnName)
    val typical = lengths[((lengths.size - 1) * 9) / 10]
    val chars = maxOf(columnName.trim().length + 2, typical)
    return (chars * 7.2f + 32f).coerceIn(64f, 380f)
}

private fun defaultColumnWidthByName(columnName: String): Float {
    val lower = columnName.trim().lowercase()
    return when {
        lower == "id" || lower == "№" || lower == "#" -> 60f
        lower in setOf("да/нет", "активен", "архив", "ед.", "ед.изм.", "срок", "приоритет", "участники", "версия") -> 96f
        lower.contains("код") || lower.contains("номер") || lower.contains("статус") || lower.contains("тип") -> 118f
        lower.contains("кол") || lower.contains("остат") || lower.contains("резерв") || lower.contains("сумм") || lower.contains("дата") || lower.contains("обнов") -> 114f
        lower.contains("назван") || lower.contains("наимен") || lower.contains("описан") || lower.contains("адрес") || lower.contains("проект") -> 210f
        else -> 130f
    }
}

private fun compareCellValues(a: Any?, b: Any?): Int {
    val sa = a?.toString()?.trim().orEmpty()
    val sb = b?.toString()?.trim().orEmpty()
    if (sa == sb) return 0
    if (sa.isEmpty() || sa == "—") return 1
    if (sb.isEmpty() || sb == "—") return -1
    val da = sa.replace(',', '.').toDoubleOrNull()
    val db = sb.replace(',', '.').toDoubleOrNull()
    if (da != null && db != null) return da.compareTo(db)
    return sa.lowercase().compareTo(sb.lowercase())
}

private fun statusCellColorOrNull(columnKey: String, value: String): Color? {
    if (value.isEmpty() || value == "—") return null
    val key = columnKey.lowercase()
    val v = value.lowercase()
    val looksLikeStatusCol = key.contains("статус") || key.contains("актив") || key.contains("архив") || key.contains("приоритет") || key.contains("критич")
    if (!looksLikeStatusCol) return null
    return when {
        v in setOf(
            "да", "активен", "активна", "действующий", "действующая", "серийное производство",
            "проведена", "проведен", "принято", "принят", "принята", "принят полностью",
            "выполнена", "выполнен", "завершён", "завершен", "завершена", "пройден",
            "утверждён", "утвержден", "утверждена", "проверен", "проверено", "сверен",
            "оплачен", "оплачена", "согласовано", "готов", "готова", "исполнен",
            "passed", "active", "completed", "approved", "received", "posted", "verified"
        ) -> Brand.Teal
        v in setOf(
            "в работе", "в производстве", "в закупке", "в пути", "собирается",
            "на согласовании", "на проверке", "отправлен", "отправлена", "опытное производство",
            "к приёмке", "к приемке", "открыт", "открыта", "частично оплачен",
            "in_progress", "sent", "testing", "high", "высокий"
        ) -> Brand.Blue
        v in setOf(
            "черновик", "разработка", "план", "запланирован", "новая", "назначена",
            "оформлен", "draft", "planned", "new", "assigned", "normal", "обычный", "development"
        ) -> Brand.Indigo
        v in setOf(
            "на удержании", "ожидание", "ожидает", "доработка", "неполный", "частично",
            "частично принят", "с расхождениями", "не проверен", "ниже минимума",
            "shortage", "rework", "on_hold", "pending_approval"
        ) -> Brand.Amber
        v in setOf(
            "критический", "критичный", "срочный", "заблокирована", "заблокирован",
            "отклонена", "отклонен", "отменена", "отменён", "отменен", "отказ", "брак",
            "не пройден", "снято с производства", "просрочено", "истёк", "истек",
            "critical", "urgent", "blocked", "rejected", "cancelled", "failed"
        ) -> Brand.Rose
        v in setOf("нет", "закрыта", "закрыт", "архив", "заменена", "заменён", "closed") -> Brand.Violet
        else -> null
    }
}
