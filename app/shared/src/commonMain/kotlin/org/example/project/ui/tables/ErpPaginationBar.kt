package org.example.project.ui.tables

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.ui.theme.Brand

@Composable
fun ErpPaginationBar(
    totalItems: Int,
    currentPage: Int,
    pageSize: Int,
    pageSizes: List<Int> = listOf(10, 20, 50, 100),
    showAll: Boolean = false,
    onPageChange: (Int) -> Unit,
    onPageSizeChange: (Int) -> Unit,
    onShowAllChange: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val safeSize = pageSize.coerceAtLeast(1)
    val totalPages = ((totalItems + safeSize - 1) / safeSize).coerceAtLeast(1)
    val safePage = currentPage.coerceIn(0, totalPages - 1)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val fromItem = if (totalItems == 0) 0 else safePage * safeSize + 1
        val toItem = ((safePage + 1) * safeSize).coerceAtMost(totalItems)
        Text(
            text = if (showAll) "Показаны все $totalItems" else "Показано $fromItem–$toItem из $totalItems",
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant
        )

        Spacer(Modifier.weight(1f))

        if (!showAll && totalPages > 1) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ErpPageNavButton("‹", enabled = safePage > 0) {
                    onPageChange((safePage - 1).coerceAtLeast(0))
                }
                val pageItems = remember(safePage, totalPages) {
                    buildPaginationItems(safePage, totalPages)
                }
                pageItems.forEach { pageIdx ->
                    if (pageIdx == null) {
                        Text(
                            text = "…",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    } else {
                        ErpPageNumberButton(
                            pageNumber = pageIdx + 1,
                            selected = pageIdx == safePage,
                            onClick = { onPageChange(pageIdx) }
                        )
                    }
                }
                ErpPageNavButton("›", enabled = safePage < totalPages - 1) {
                    onPageChange((safePage + 1).coerceAtMost(totalPages - 1))
                }
            }
            Spacer(Modifier.width(8.dp))
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            pageSizes.forEach { sz ->
                ErpPageSizeChip(
                    label = "$sz",
                    selected = !showAll && safeSize == sz,
                    onClick = { onPageSizeChange(sz) }
                )
            }
            if (onShowAllChange != null) {
                ErpPageSizeChip(
                    label = "Все",
                    selected = showAll,
                    onClick = onShowAllChange
                )
            }
        }
    }
}

fun <T> paginateList(items: List<T>, currentPage: Int, pageSize: Int): List<T> {
    if (items.isEmpty()) return emptyList()
    val safeSize = pageSize.coerceAtLeast(1)
    val totalPages = ((items.size + safeSize - 1) / safeSize).coerceAtLeast(1)
    val safePage = currentPage.coerceIn(0, totalPages - 1)
    val from = safePage * safeSize
    val to = (from + safeSize).coerceAtMost(items.size)
    return if (from >= to) emptyList() else items.subList(from, to)
}

@Composable
fun <T> PaginatedCardList(
    items: List<T>,
    modifier: Modifier = Modifier,
    pageSizes: List<Int> = listOf(10, 20, 50),
    defaultPageSize: Int = 10,
    defaultShowAll: Boolean = true,
    itemSpacing: Dp = 8.dp,
    itemContent: @Composable (globalIndex: Int, item: T) -> Unit
) {
    var currentPage by remember { mutableStateOf(0) }
    var pageSize by remember { mutableStateOf(defaultPageSize) }
    var showAll by remember { mutableStateOf(defaultShowAll) }
    val safeSize = pageSize.coerceAtLeast(1)
    val totalPages = ((items.size + safeSize - 1) / safeSize).coerceAtLeast(1)
    val safePage = currentPage.coerceIn(0, totalPages - 1)
    if (safePage != currentPage) currentPage = safePage
    val pageItems = remember(items, safePage, safeSize, showAll) {
        if (showAll) items else paginateList(items, safePage, safeSize)
    }

    Column(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(itemSpacing)
        ) {
            items(pageItems.size) { idx ->
                val globalIdx = if (showAll) idx else safePage * safeSize + idx
                itemContent(globalIdx, pageItems[idx])
            }
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
        ErpPaginationBar(
            totalItems = items.size,
            currentPage = safePage,
            pageSize = safeSize,
            pageSizes = pageSizes,
            showAll = showAll,
            onPageChange = { currentPage = it },
            onPageSizeChange = {
                showAll = false
                pageSize = it
                currentPage = 0
            },
            onShowAllChange = {
                showAll = true
                currentPage = 0
            }
        )
    }
}

@Composable
private fun ErpPageNavButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (enabled) scheme.surfaceVariant.copy(alpha = 0.55f) else Color.Transparent,
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (enabled) scheme.onSurface else scheme.onSurfaceVariant.copy(alpha = 0.35f)
            )
        }
    }
}

@Composable
private fun ErpPageNumberButton(pageNumber: Int, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) Brand.Indigo else scheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 9.dp)
        ) {
            Text(
                text = "$pageNumber",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) Color.White else scheme.onSurface
            )
        }
    }
}

@Composable
private fun ErpPageSizeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) Brand.Indigo.copy(alpha = 0.16f) else scheme.surfaceVariant.copy(alpha = 0.38f),
        modifier = Modifier
            .height(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) Brand.Indigo else scheme.onSurfaceVariant
            )
        }
    }
}

private fun buildPaginationItems(currentPage: Int, totalPages: Int): List<Int?> {
    if (totalPages <= 7) return (0 until totalPages).toList()
    val result = mutableListOf<Int?>()
    result.add(0)
    val left = (currentPage - 1).coerceAtLeast(1)
    val right = (currentPage + 1).coerceAtMost(totalPages - 2)
    if (left > 1) result.add(null)
    for (p in left..right) result.add(p)
    if (right < totalPages - 2) result.add(null)
    result.add(totalPages - 1)
    return result
}
