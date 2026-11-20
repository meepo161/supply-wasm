package org.example.project.ui.tables

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.unchil.un7datagrid.Un7KCMPDataGrid
import com.unchil.un7datagrid.Un7KCMPDataGridConfig
import kotlinx.serialization.json.Json

internal expect object TableColumnWidthStorage {
    fun read(key: String): String?
    fun write(key: String, value: String)
}

internal fun encodeColumnWidths(widths: Map<String, Float>): String = Json.encodeToString(widths)

internal fun decodeColumnWidths(value: String?): Map<String, Float> {
    if (value.isNullOrBlank()) return emptyMap()
    return runCatching { Json.decodeFromString<Map<String, Float>>(value) }
        .getOrDefault(emptyMap())
        .filterValues { it.isFinite() && it > 0f }
}

@Composable
fun PersistentDataGrid(
    data: Map<String, List<Any?>>,
    modifier: Modifier = Modifier,
    config: Un7KCMPDataGridConfig = Un7KCMPDataGridConfig(),
    tableKey: String = data.keys.joinToString("|"),
    onClick: ((List<Pair<Int, List<Any?>>>) -> Unit)? = null,
    onLongClick: ((List<Pair<Int, List<Any?>>>) -> Unit)? = null
) {
    val persistentConfig = remember(config, tableKey, data.keys) {
        val storageKey = "erp.table-column-widths.$tableKey"
        config.copy(
            initialColumnWeights = decodeColumnWidths(TableColumnWidthStorage.read(storageKey)),
            onColumnWeightsChange = { widths ->
                TableColumnWidthStorage.write(storageKey, encodeColumnWidths(widths))
            }
        )
    }
    Un7KCMPDataGrid(
        data = data,
        modifier = modifier,
        config = persistentConfig,
        onClick = onClick,
        onLongClick = onLongClick
    )
}
