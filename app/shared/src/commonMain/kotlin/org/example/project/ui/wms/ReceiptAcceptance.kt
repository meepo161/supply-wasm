package org.example.project.ui.wms

import org.example.project.ui.userText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import org.example.project.ui.theme.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import org.example.project.ui.theme.OutlinedButton
import org.example.project.ui.theme.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.example.project.data.ApiResult
import org.example.project.data.SupplierInvoiceRepository
import org.example.project.data.WmsRepository
import org.example.project.data.safeAttachmentFileName
import org.example.project.data.saveAttachmentFile
import org.example.project.dto.AcceptReceiptLineInput
import org.example.project.dto.CreateStorageLocationRequest
import org.example.project.dto.NomenclatureDto
import org.example.project.dto.ReceiptDto
import org.example.project.dto.StockBalanceDto
import org.example.project.dto.StorageLocationDto
import org.example.project.dto.StorageLocationKind
import org.example.project.ui.theme.ErpDialog
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.Gradients
import org.koin.compose.koinInject

private data class AcceptanceRow(
    val lineId: Long,
    val received: String,
    val locationId: Long?,
    val lotNumber: String,
    val reason: String
)

/**
 * Приёмка складом (D-61): поступление по счёту приходит от снабжения без ячеек.
 * Кладовщик вносит фактическое количество, ячейку, партию и причину расхождения,
 * сохраняет и затем проводит документ. Файл счёта можно открыть здесь же.
 */
@Composable
fun ReceiptAcceptance(
    receipt: ReceiptDto,
    nomenclature: List<NomenclatureDto> = emptyList(),
    stock: List<StockBalanceDto> = emptyList(),
    onSave: (List<AcceptReceiptLineInput>) -> Unit,
    onSaveAndPost: ((List<AcceptReceiptLineInput>) -> Unit)? = null,
    isBusy: Boolean = false,
    modifier: Modifier = Modifier,
    /** true — в форме есть несохранённые правки: проводить документ нельзя, они потеряются. */
    onDirtyChange: (Boolean) -> Unit = {}
) {
    val wmsRepository: WmsRepository = koinInject()
    val invoiceRepository: SupplierInvoiceRepository = koinInject()
    val scope = rememberCoroutineScope()
    var locations by remember(receipt.warehouseId) { mutableStateOf<List<StorageLocationDto>>(emptyList()) }
    var message by remember(receipt.id) { mutableStateOf<String?>(null) }
    var createLocationForRow by remember(receipt.id) { mutableStateOf<Int?>(null) }
    var newLocationCode by remember { mutableStateOf("") }
    var newLocationName by remember { mutableStateOf("") }
    var creatingLocation by remember { mutableStateOf(false) }
    val savedRows = remember(receipt.id, receipt.version) {
        receipt.lines.map { line ->
            val rawReceived = line.receivedQuantity.trim()
            val hasPositiveReceived = (rawReceived.replace(',', '.').toDoubleOrNull() ?: 0.0) > 0.0
            AcceptanceRow(
                lineId = line.id,
                // Если факт ещё 0 или пуст — предзаполняем ожидаемым количеством из счёта/заказа.
                received = if (hasPositiveReceived) rawReceived.trimZeros() else line.expectedQuantity.trimZeros(),
                locationId = line.locationId,
                lotNumber = "",
                reason = line.discrepancyReason.orEmpty()
            )
        }
    }
    val rows = remember(receipt.id, receipt.version) {
        mutableStateListOf<AcceptanceRow>().apply { addAll(savedRows) }
    }
    val dirty = rows.toList() != savedRows
    LaunchedEffect(dirty) { onDirtyChange(dirty) }
    LaunchedEffect(receipt.warehouseId) {
        when (val r = wmsRepository.listLocations(receipt.warehouseId)) {
            is ApiResult.Success -> {
                val active = r.data.filter { it.active }
                locations = active
                val selectable = active.filterNot { it.code == TRANSIT_LOCATION_CODE }
                if (selectable.isNotEmpty()) {
                    val warehouseStock = stock.filter { it.warehouseId == receipt.warehouseId }
                    val fallbackLocId = defaultQuickReceiveLocationId(selectable)
                    rows.forEachIndexed { idx, row ->
                        if (row.locationId == null) {
                            val line = receipt.lines.getOrNull(idx)
                            val existingLocId = line?.let { l ->
                                warehouseStock
                                    .filter { it.nomenclatureId == l.nomenclatureId && (it.physicalQty.toDoubleOrNull() ?: 0.0) > 0.0 }
                                    .maxByOrNull { it.physicalQty.toDoubleOrNull() ?: 0.0 }
                                    ?.locationId
                                    ?.takeIf { id -> selectable.any { it.id == id } }
                            }
                            val suggestedLocId: Long? = existingLocId ?: if (line != null) {
                                when (val s = wmsRepository.suggestLocation(receipt.warehouseId, line.nomenclatureId)) {
                                    is ApiResult.Success -> s.data.locationId?.takeIf { id -> selectable.any { it.id == id } }
                                    is ApiResult.Error -> null
                                }
                            } else {
                                null
                            }
                            val autoLocId = suggestedLocId ?: fallbackLocId
                            if (autoLocId != null) {
                                rows[idx] = row.copy(locationId = autoLocId)
                            }
                        }
                    }
                }
            }
            is ApiResult.Error -> message = r.error.userText()
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("Приёмка на складе: факт, ячейка, расхождения", style = MaterialTheme.typography.labelLarge)
        receipt.supplierInvoiceId?.let { invoiceId ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Поступление по счёту № ${receipt.supplierInvoiceNumber ?: receipt.sourceDocumentNo} оформлено снабжением",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = {
                    scope.launch {
                        when (val r = invoiceRepository.downloadFile(invoiceId)) {
                            is ApiResult.Success -> saveAttachmentFile(
                                safeAttachmentFileName("invoice-${receipt.sourceDocumentNo ?: invoiceId}.pdf", invoiceId),
                                r.data.mimeType,
                                r.data.bytes
                            )
                            is ApiResult.Error -> message = r.error.userText()
                        }
                    }
                }) { Text("Открыть счёт") }
            }
        }
        val selectableLocations = remember(locations) {
            locations.filter { it.active && it.code != TRANSIT_LOCATION_CODE }
        }
        var scanCode by remember(receipt.id) { mutableStateOf("") }
        var scanHint by remember(receipt.id) { mutableStateOf<String?>(null) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = scanCode,
                onValueChange = { scanCode = it },
                label = { Text("Штрихкод / код позиции (+1 к приёмке)") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = {
                    val q = scanCode.trim()
                    if (q.isNotEmpty()) {
                        val matchIdx = receipt.lines.indexOfFirst { line ->
                            val item = nomenclature.firstOrNull { it.id == line.nomenclatureId }
                            line.nomenclatureCode.equals(q, ignoreCase = true) ||
                                item?.code.equals(q, ignoreCase = true) ||
                                item?.barcodes?.any { it.barcode.equals(q, ignoreCase = true) } == true
                        }
                        if (matchIdx >= 0) {
                            val current = rows[matchIdx]
                            val nextQty = (current.received.trim().replace(',', '.').toDoubleOrNull() ?: 0.0) + 1.0
                            val rounded = kotlin.math.round(nextQty * 1000.0) / 1000.0
                            rows[matchIdx] = current.copy(
                                received = if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
                            )
                            val matchedLine = receipt.lines[matchIdx]
                            scanHint = "Строка №${matchedLine.lineNo}: +1 (итого ${rows[matchIdx].received})"
                            scanCode = ""
                        } else {
                            scanHint = "Позиция с кодом «$q» не найдена в приёмке"
                        }
                    }
                },
                enabled = scanCode.isNotBlank()
            ) { Text("+1") }
        }
        scanHint?.let { hint ->
            Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        val commonLocationId = rows.map { it.locationId }.distinct().singleOrNull()
        val firstSelectedLocation = rows.firstNotNullOfOrNull { r ->
            selectableLocations.firstOrNull { it.id == r.locationId }
        }
        if (selectableLocations.isNotEmpty() && (rows.size > 1 || rows.any { it.locationId == null })) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LocationDropdown(
                    locations = selectableLocations,
                    selected = selectableLocations.firstOrNull { it.id == commonLocationId },
                    onSelect = { loc ->
                        rows.indices.forEach { idx ->
                            rows[idx] = rows[idx].copy(locationId = loc.id)
                        }
                    },
                    label = "Ячейка для всех строк",
                    showWarehouse = false,
                    onCreateLocation = {
                        newLocationCode = ""
                        newLocationName = ""
                        createLocationForRow = -1
                    },
                    modifier = Modifier.weight(1f)
                )
                if (firstSelectedLocation != null && rows.any { it.locationId != firstSelectedLocation.id }) {
                    OutlinedButton(
                        onClick = {
                            rows.indices.forEach { idx ->
                                rows[idx] = rows[idx].copy(locationId = firstSelectedLocation.id)
                            }
                        }
                    ) {
                        Text("Применить ${firstSelectedLocation.code} ко всем")
                    }
                }
            }
        }
        receipt.lines.forEachIndexed { index, line ->
            val row = rows.getOrNull(index) ?: return@forEachIndexed
            val parsedReceived = row.received.trim().replace(',', '.').toDoubleOrNull()
            val parsedExpected = line.expectedQuantity.trim().replace(',', '.').toDoubleOrNull()
            val differs = parsedReceived != null && parsedExpected != null && parsedReceived != parsedExpected
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val item = nomenclature.firstOrNull { it.id == line.nomenclatureId }
                val code = line.nomenclatureCode ?: item?.code
                val name = line.nomenclatureName ?: item?.name
                val title = listOfNotNull(code, name).joinToString(" · ").ifBlank { "Позиция ${line.nomenclatureId}" }
                Text(
                    "№${line.lineNo}: $title, ожидается ${line.expectedQuantity.trimZeros()} ${item?.unit.orEmpty()}".trimEnd(),
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val unitSuffix = item?.unit?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                    OutlinedTextField(
                        value = row.received,
                        onValueChange = { rows[index] = row.copy(received = it) },
                        label = { Text("Принято$unitSuffix *") },
                        singleLine = true,
                        isError = row.received.isNotBlank() && parsedReceived == null,
                        modifier = Modifier.weight(1f)
                    )
                    LocationDropdown(
                        locations = locations,
                        selected = locations.firstOrNull { it.id == row.locationId },
                        onSelect = { rows[index] = row.copy(locationId = it.id) },
                        label = "Ячейка *",
                        onCreateLocation = {
                            newLocationCode = ""
                            newLocationName = ""
                            createLocationForRow = index
                        },
                        modifier = Modifier.weight(1.5f)
                    )
                    if (item?.lotTracked == true) {
                        OutlinedTextField(
                            value = row.lotNumber,
                            onValueChange = { rows[index] = row.copy(lotNumber = it) },
                            label = { Text("Партия *") },
                            singleLine = true,
                            isError = (parsedReceived ?: 0.0) > 0.0 && row.lotNumber.isBlank(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (differs) {
                    OutlinedTextField(
                        value = row.reason,
                        onValueChange = { rows[index] = row.copy(reason = it) },
                        label = { Text("Причина расхождения *") },
                        singleLine = true,
                        isError = row.reason.isBlank(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        var missingLocOrQty = false
        var missingReason = false
        var missingLot = false
        receipt.lines.forEachIndexed { idx, line ->
            val row = rows.getOrNull(idx)
            val parsedReceived = row?.received?.trim()?.replace(',', '.')?.toDoubleOrNull()
            val parsedExpected = line.expectedQuantity.trim().replace(',', '.').toDoubleOrNull()
            val differs = parsedReceived != null && parsedExpected != null && parsedReceived != parsedExpected
            val item = nomenclature.firstOrNull { it.id == line.nomenclatureId }
            if (row == null || row.locationId == null || parsedReceived == null || parsedReceived < 0.0) {
                missingLocOrQty = true
            } else if (differs && row.reason.isBlank()) {
                missingReason = true
            } else if (item?.lotTracked == true && parsedReceived > 0.0 && row.lotNumber.isBlank()) {
                missingLot = true
            }
        }
        val ready = !missingLocOrQty && !missingReason && !missingLot
        val payload = rows.map {
            AcceptReceiptLineInput(
                lineId = it.lineId,
                receivedQuantity = it.received.trim().replace(',', '.'),
                locationId = it.locationId ?: 0L,
                lotNumber = it.lotNumber.trim().ifEmpty { null },
                discrepancyReason = it.reason.trim().ifEmpty { null }
            )
        }
        // Подсказка стоит рядом с кнопкой, а не под формой: именно здесь выясняется, почему действие недоступно.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onSaveAndPost != null) {
                Button(
                    enabled = ready && !isBusy,
                    onClick = { onSaveAndPost(payload) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Gradients.Emerald.first(),
                        contentColor = Color.White
                    )
                ) { Text(if (isBusy) "Проведение…" else "✓ Принять и провести") }
                OutlinedButton(
                    enabled = ready && !isBusy,
                    onClick = { onSave(payload) }
                ) { Text("Сохранить черновик") }
            } else {
                Button(
                    enabled = ready && !isBusy,
                    onClick = { onSave(payload) }
                ) { Text("Сохранить факт приёмки") }
            }
            if (!ready) {
                Text(
                    when {
                        missingLocOrQty -> "Укажите ячейку и количество для каждой строки: без этого документ не проводится"
                        missingReason -> "Укажите причину расхождения для строк, где факт отличается от ожидания"
                        else -> "Укажите номер партии для позиций с партионным учётом"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
            } else if (dirty && onSaveAndPost == null) {
                Text(
                    "Есть несохранённые изменения — нажмите «Сохранить факт приёмки», иначе проводить нельзя",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        createLocationForRow?.let { targetRow ->
            ErpDialog(
                title = "Новая ячейка хранения",
                glyph = ErpIconGlyph.Warehouse,
                gradient = Gradients.Emerald,
                width = 440.dp,
                confirmText = if (creatingLocation) "Создание…" else "Создать",
                confirmEnabled = newLocationCode.isNotBlank() && !creatingLocation,
                onConfirm = {
                    val code = newLocationCode.trim()
                    val name = newLocationName.trim().ifEmpty { "Ячейка $code" }
                    creatingLocation = true
                    scope.launch {
                        when (val r = wmsRepository.createLocation(
                            CreateStorageLocationRequest(
                                warehouseId = receipt.warehouseId,
                                code = code,
                                name = name,
                                kind = StorageLocationKind.CELL
                            )
                        )) {
                            is ApiResult.Success -> {
                                locations = locations + r.data
                                if (targetRow < 0) {
                                    rows.indices.forEach { idx ->
                                        rows[idx] = rows[idx].copy(locationId = r.data.id)
                                    }
                                } else {
                                    rows.getOrNull(targetRow)?.let { current ->
                                        rows[targetRow] = current.copy(locationId = r.data.id)
                                    }
                                }
                                createLocationForRow = null
                            }
                            is ApiResult.Error -> message = r.error.userText()
                        }
                        creatingLocation = false
                    }
                },
                onDismiss = { if (!creatingLocation) createLocationForRow = null }
            ) {
                OutlinedTextField(
                    value = newLocationCode,
                    onValueChange = { newLocationCode = it },
                    label = { Text("Код ячейки * (например, H-11)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newLocationName,
                    onValueChange = { newLocationName = it },
                    label = { Text("Название (необязательно)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private fun String.trimZeros(): String = if (contains('.')) trimEnd('0').trimEnd('.') else this
