package org.example.project.ui.wms

import org.example.project.ui.userText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.example.project.data.ApiResult
import org.example.project.data.SupplierInvoiceRepository
import org.example.project.data.WmsRepository
import org.example.project.data.safeAttachmentFileName
import org.example.project.data.saveAttachmentFile
import org.example.project.dto.AcceptReceiptLineInput
import org.example.project.dto.NomenclatureDto
import org.example.project.dto.ReceiptDto
import org.example.project.dto.StorageLocationDto
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
    onSave: (List<AcceptReceiptLineInput>) -> Unit,
    modifier: Modifier = Modifier
) {
    val wmsRepository: WmsRepository = koinInject()
    val invoiceRepository: SupplierInvoiceRepository = koinInject()
    val scope = rememberCoroutineScope()
    var locations by remember(receipt.warehouseId) { mutableStateOf<List<StorageLocationDto>>(emptyList()) }
    var message by remember(receipt.id) { mutableStateOf<String?>(null) }
    val rows = remember(receipt.id, receipt.version) {
        mutableStateListOf<AcceptanceRow>().apply {
            receipt.lines.forEach {
                add(
                    AcceptanceRow(
                        lineId = it.id,
                        received = it.receivedQuantity.trimZeros(),
                        locationId = it.locationId,
                        lotNumber = "",
                        reason = it.discrepancyReason.orEmpty()
                    )
                )
            }
        }
    }
    LaunchedEffect(receipt.warehouseId) {
        when (val r = wmsRepository.listLocations(receipt.warehouseId)) {
            is ApiResult.Success -> locations = r.data.filter { it.active }
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
        receipt.lines.forEachIndexed { index, line ->
            val row = rows.getOrNull(index) ?: return@forEachIndexed
            val differs = row.received.replace(',', '.').toDoubleOrNull()?.let { it != line.expectedQuantity.toDoubleOrNull() } ?: false
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val item = nomenclature.firstOrNull { it.id == line.nomenclatureId }
                val code = line.nomenclatureCode ?: item?.code
                val name = line.nomenclatureName ?: item?.name
                val title = listOfNotNull(code, name).joinToString(" — ").ifBlank { "Позиция ${line.nomenclatureId}" }
                Text(
                    "№${line.lineNo}: $title — ожидается ${line.expectedQuantity.trimZeros()} ${item?.unit.orEmpty()}".trimEnd(),
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = row.received,
                        onValueChange = { rows[index] = row.copy(received = it) },
                        label = { Text("Принято") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    LocationDropdown(
                        locations = locations,
                        selected = locations.firstOrNull { it.id == row.locationId },
                        onSelect = { rows[index] = row.copy(locationId = it.id) },
                        modifier = Modifier.weight(1.5f)
                    )
                    OutlinedTextField(
                        value = row.lotNumber,
                        onValueChange = { rows[index] = row.copy(lotNumber = it) },
                        label = { Text("Партия") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (differs) {
                    OutlinedTextField(
                        value = row.reason,
                        onValueChange = { rows[index] = row.copy(reason = it) },
                        label = { Text("Причина расхождения (обязательно)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        val ready = rows.all { it.locationId != null && it.received.isNotBlank() }
        Button(
            enabled = ready,
            onClick = {
                onSave(
                    rows.map {
                        AcceptReceiptLineInput(
                            lineId = it.lineId,
                            receivedQuantity = it.received.trim().replace(',', '.'),
                            locationId = it.locationId!!,
                            lotNumber = it.lotNumber.trim().ifEmpty { null },
                            discrepancyReason = it.reason.trim().ifEmpty { null }
                        )
                    }
                )
            }
        ) { Text("Сохранить факт приёмки") }
        if (!ready) {
            Text("Укажите ячейку и количество для каждой строки — без этого документ не проводится", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun String.trimZeros(): String = if (contains('.')) trimEnd('0').trimEnd('.') else this
