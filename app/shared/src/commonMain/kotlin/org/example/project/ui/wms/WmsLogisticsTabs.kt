package org.example.project.ui.wms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import org.example.project.ui.theme.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import org.example.project.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.dto.IssueOrderDto
import org.example.project.ui.theme.ErpFormat
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.components.SearchableDropdown
import org.example.project.dto.IssueStatus
import org.example.project.dto.NomenclatureDto
import org.example.project.dto.ReturnInspection
import org.example.project.dto.ReturnInspectionDecision
import org.example.project.dto.ReturnOrderDto
import org.example.project.dto.ReturnStatus
import org.example.project.dto.StorageLocationDto
import org.example.project.dto.TransferDto
import org.example.project.dto.TransferStatus
import org.example.project.dto.WarehouseDto
import org.example.project.dto.WriteOffDto
import org.example.project.dto.WriteOffStatus
import org.example.project.dto.InventoryDto
import org.example.project.dto.InventoryScopeType
import org.example.project.dto.InventoryStatus
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.PillBadge

// ---------------------------------------------------------------------------
// Перемещения (ТЗ 8.6)
// ---------------------------------------------------------------------------

@Composable
internal fun TransfersTab(
    state: WmsUiState,
    viewModel: WmsViewModel
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TabHeader(
            title = "Перемещения",
            onCreate = { viewModel.showDialog(WmsDialog.CreateTransfer) },
            onRefresh = viewModel::loadTransfers,
            createLabel = "Создать перемещение"
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TransferStatusDropdown(
                selected = state.transferStatusFilter,
                onSelect = viewModel::updateTransferStatusFilter,
                modifier = Modifier.width(320.dp)
            )
        }
        state.operationError?.let { ErrorMessageCard(error = it) }
        if (state.transfersLoading && state.transfers.isEmpty()) {
            LoadingOverlay()
        } else if (state.transfers.isEmpty()) {
            if (state.transferStatusFilter != null) {
                EmptyState(
                    "Нет перемещений в выбранном статусе.",
                    actionLabel = "Показать все",
                    onAction = { viewModel.updateTransferStatusFilter(null) }
                )
            } else {
                EmptyState(
                    "Перемещений нет. Перемещение переносит остаток между ячейками или складами.",
                    actionLabel = "Создать перемещение",
                    onAction = { viewModel.showDialog(WmsDialog.CreateTransfer) }
                )
            }
        } else {
            TransfersGrid(
                transfers = state.transfers,
                onClick = { viewModel.selectTransfer(it) },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
        state.selectedTransfer?.let { transfer ->
            TransferDetailDialog(
                transfer = transfer,
                receiveQuantity = state.transferReceiveQuantity,
                cancelReason = state.transferCancelReason,
                onReceiveQuantityChange = viewModel::updateTransferReceiveQuantity,
                onCancelReasonChange = viewModel::updateTransferCancelReason,
                onShip = viewModel::shipTransfer,
                onInTransit = viewModel::markTransferInTransit,
                onReceive = viewModel::receiveTransfer,
                onCancel = viewModel::cancelTransfer,
                onClose = { viewModel.selectTransfer(null) }
            )
        }
    }
}

@Composable
private fun TransferDetailDialog(
    transfer: TransferDto,
    receiveQuantity: String,
    cancelReason: String,
    onReceiveQuantityChange: (String) -> Unit,
    onCancelReasonChange: (String) -> Unit,
    onShip: () -> Unit,
    onInTransit: () -> Unit,
    onReceive: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit
) {
    val canShip = transfer.status == TransferStatus.CREATED
    val canInTransit = transfer.status == TransferStatus.SHIPPED
    val canReceive = transfer.status == TransferStatus.SHIPPED || transfer.status == TransferStatus.IN_TRANSIT
    val canCancel = transfer.status == TransferStatus.CREATED || transfer.status == TransferStatus.SHIPPED ||
        transfer.status == TransferStatus.IN_TRANSIT

    org.example.project.ui.theme.ErpDialog(
        title = "Перемещение ${transfer.number}",
        subtitle = transfer.status.localizedName(),
        glyph = ErpIconGlyph.Send,
        gradient = org.example.project.ui.theme.Gradients.Emerald,
        width = 660.dp,
        dismissText = "Закрыть",
        onDismiss = onClose,
        extraActions = {
            if (canShip) Button(onClick = onShip) { Text("Отгрузить") }
            if (canInTransit) Button(onClick = onInTransit) { Text("В путь") }
            if (canReceive) Button(onClick = onReceive) { Text("Принять") }
            if (canCancel) TextButton(onClick = onCancel) { Text("Отменить") }
        }
    ) {
        val names = LocalWmsNames.current
        Text("Вид: ${transfer.kind.localizedName()}", style = MaterialTheme.typography.bodyMedium)
        Text("Позиция: ${names.item(transfer.nomenclatureId)}", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Откуда: ${names.warehouse(transfer.fromWarehouseId)}, ячейка ${names.location(transfer.fromLocationId)}",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "Куда: ${names.warehouse(transfer.toWarehouseId)}, ячейка ${names.location(transfer.toLocationId)}",
            style = MaterialTheme.typography.bodyMedium
        )
        Text("Количество: ${names.qty(transfer.quantity, transfer.nomenclatureId)}", style = MaterialTheme.typography.bodyMedium)
        Text("Принято: ${names.qty(transfer.receivedQuantity, transfer.nomenclatureId)}", style = MaterialTheme.typography.bodyMedium)
        if (transfer.note != null) {
            Text("Примечание: ${transfer.note}", style = MaterialTheme.typography.bodyMedium)
        }

        if (canReceive) {
            HorizontalDivider()
            OutlinedTextField(
                value = receiveQuantity,
                onValueChange = onReceiveQuantityChange,
                label = { Text("Принятое количество *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        if (canCancel) {
            OutlinedTextField(
                value = cancelReason,
                onValueChange = onCancelReasonChange,
                label = { Text("Причина отмены (для отмены)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
        }
        if (transfer.events.isNotEmpty()) {
            HorizontalDivider()
            Text("История", style = MaterialTheme.typography.labelLarge)
            transfer.events.forEach { event ->
                Text(
                    text = "${event.action.name} · ${event.quantity ?: "без количества"} · ${ErpFormat.dateRu(event.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun transferStatusAccent(status: TransferStatus) = when (status) {
    TransferStatus.CREATED -> Brand.Blue
    TransferStatus.SHIPPED, TransferStatus.IN_TRANSIT -> Brand.Indigo
    TransferStatus.RECEIVED -> Brand.Teal
    TransferStatus.DISCREPANCY -> Brand.Amber
    TransferStatus.CANCELLED -> Brand.Rose
}

@Composable
internal fun CreateTransferDialog(
    form: CreateTransferForm,
    warehouses: List<WarehouseDto>,
    locationsFrom: List<StorageLocationDto>,
    locationsTo: List<StorageLocationDto>,
    nomenclature: List<NomenclatureDto>,
    error: org.example.project.dto.ApiError?,
    onFormChange: (CreateTransferForm) -> Unit,
    onFromWarehouseChange: (Long?) -> Unit,
    onToWarehouseChange: (Long?) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    org.example.project.ui.theme.ErpDialog(
        title = "Новое перемещение",
        glyph = ErpIconGlyph.Send,
        gradient = org.example.project.ui.theme.Gradients.Emerald,
        width = 680.dp,
        confirmText = "Создать",
        onConfirm = onCreate,
        confirmEnabled = warehouses.isNotEmpty(),
        onDismiss = onDismiss
    ) {
        error?.let { ErrorMessageCard(error = it) }
        if (warehouses.isEmpty()) {
            Text(
                text = "Нет доступных складов. Сначала создайте склад.",
                color = MaterialTheme.colorScheme.error
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WarehouseDropdown(
                warehouses = warehouses,
                selected = warehouses.find { it.id == form.fromWarehouseId },
                onSelect = { onFromWarehouseChange(it.id) },
                label = "Склад-источник *",
                modifier = Modifier.weight(1f)
            )
            LocationDropdown(
                locations = locationsFrom,
                selected = locationsFrom.find { it.id == form.fromLocationId },
                onSelect = { onFormChange(form.copy(fromLocationId = it.id)) },
                label = "Ячейка-источник *",
                modifier = Modifier.weight(1f)
            )
        }
        val availableTargetLocations = if (form.fromWarehouseId != null && form.fromWarehouseId == form.toWarehouseId) {
            locationsTo.filter { it.id != form.fromLocationId }
        } else {
            locationsTo
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WarehouseDropdown(
                warehouses = warehouses,
                selected = warehouses.find { it.id == form.toWarehouseId },
                onSelect = { onToWarehouseChange(it.id) },
                label = "Склад-получатель *",
                modifier = Modifier.weight(1f)
            )
            LocationDropdown(
                locations = availableTargetLocations,
                selected = availableTargetLocations.find { it.id == form.toLocationId },
                onSelect = { onFormChange(form.copy(toLocationId = it.id)) },
                label = "Ячейка-получатель *",
                modifier = Modifier.weight(1f)
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NomenclatureDropdown(
                items = nomenclature,
                selected = nomenclature.find { it.id == form.nomenclatureId },
                onSelect = { onFormChange(form.copy(nomenclatureId = it.id, lotId = "")) },
                label = "Номенклатура *",
                modifier = Modifier.weight(1.3f)
            )
            OutlinedTextField(
                value = form.quantity,
                onValueChange = { onFormChange(form.copy(quantity = it)) },
                label = { Text("Количество *") },
                modifier = Modifier.weight(0.7f),
                singleLine = true
            )
        }
        LotPicker(
            value = form.lotId,
            onChange = { onFormChange(form.copy(lotId = it)) },
            nomenclatureId = form.nomenclatureId,
            label = "Партия (если партия учитывается)",
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = form.note,
            onValueChange = { onFormChange(form.copy(note = it)) },
            label = { Text("Примечание") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        Text(
            text = "Вид перемещения определится автоматически: внутри склада или между складами.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------------------
// Выдача (ТЗ 8.8)
// ---------------------------------------------------------------------------

@Composable
internal fun IssuesTab(
    state: WmsUiState,
    viewModel: WmsViewModel
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TabHeader(
            title = "Выдача",
            onCreate = { viewModel.showDialog(WmsDialog.CreateIssue) },
            onRefresh = viewModel::loadIssues,
            createLabel = "Создать выдачу"
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IssueStatusDropdown(
                selected = state.issueStatusFilter,
                onSelect = viewModel::updateIssueStatusFilter,
                modifier = Modifier.width(320.dp)
            )
        }
        state.operationError?.let { ErrorMessageCard(error = it) }
        if (state.issuesLoading && state.issues.isEmpty()) {
            LoadingOverlay()
        } else if (state.issues.isEmpty()) {
            if (state.issueStatusFilter != null) {
                EmptyState(
                    "Нет выдач в выбранном статусе.",
                    actionLabel = "Показать все",
                    onAction = { viewModel.updateIssueStatusFilter(null) }
                )
            } else {
                EmptyState(
                    "Заказов на выдачу нет. Выдача списывает товар со склада получателю.",
                    actionLabel = "Создать выдачу",
                    onAction = { viewModel.showDialog(WmsDialog.CreateIssue) }
                )
            }
        } else {
            IssuesGrid(
                issues = state.issues,
                onClick = { viewModel.selectIssue(it) },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
        state.selectedIssue?.let { issue ->
            IssueDetailDialog(
                issue = issue,
                locations = state.issueLocations,
                pickForms = state.issuePickForms,
                refusalReason = state.issueRefusalReason,
                onPickFormChange = viewModel::updateIssuePickForm,
                onPick = viewModel::pickIssueLine,
                onRefusalReasonChange = viewModel::updateIssueRefusalReason,
                onIssue = viewModel::issueSelectedOrder,
                onConfirm = viewModel::confirmSelectedIssue,
                onRefuse = viewModel::refuseSelectedIssue,
                onCancel = viewModel::cancelSelectedIssue,
                onClose = { viewModel.selectIssue(null) }
            )
        }
    }
}

@Composable
private fun IssueDetailDialog(
    issue: IssueOrderDto,
    locations: List<StorageLocationDto>,
    pickForms: Map<Long, IssuePickForm>,
    refusalReason: String,
    onPickFormChange: (Long, IssuePickForm) -> Unit,
    onPick: (Long) -> Unit,
    onRefusalReasonChange: (String) -> Unit,
    onIssue: () -> Unit,
    onConfirm: () -> Unit,
    onRefuse: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit
) {
    val canPick = issue.status == IssueStatus.DRAFT
    val canIssue = issue.status == IssueStatus.DRAFT
    val canConfirm = issue.status == IssueStatus.ISSUED
    val canRefuse = issue.status == IssueStatus.DRAFT || issue.status == IssueStatus.ISSUED
    val canCancel = issue.status == IssueStatus.DRAFT

    org.example.project.ui.theme.ErpDialog(
        title = "Выдача ${issue.number}",
        subtitle = issue.status.localizedName(),
        glyph = ErpIconGlyph.ChevronRight,
        gradient = org.example.project.ui.theme.Gradients.Emerald,
        width = 720.dp,
        dismissText = "Закрыть",
        onDismiss = onClose,
        extraActions = {
            if (canIssue) Button(onClick = onIssue) { Text("Провести выдачу") }
            if (canConfirm) Button(onClick = onConfirm) { Text("Подтвердить получение") }
            if (canRefuse) TextButton(onClick = onRefuse) { Text("Отказаться") }
            if (canCancel) TextButton(onClick = onCancel) { Text("Отменить") }
        }
    ) {
                val names = LocalWmsNames.current
                Text("Основание: ${issue.reasonType.localizedName()}", style = MaterialTheme.typography.bodyMedium)
                issue.reasonNote?.let {
                    Text("Пояснение: $it", style = MaterialTheme.typography.bodyMedium)
                }
                Text("Склад: ${names.warehouse(issue.warehouseId)}", style = MaterialTheme.typography.bodyMedium)
                Text("Получатель: ${names.user(issue.recipientId)}", style = MaterialTheme.typography.bodyMedium)
                issue.refusalReason?.let {
                    Text("Причина отказа: $it", style = MaterialTheme.typography.bodyMedium)
                }

                HorizontalDivider()
                Text("Строки", style = MaterialTheme.typography.labelLarge)
                if (issue.lines.isEmpty()) {
                    Text("Строк нет.", style = MaterialTheme.typography.bodySmall)
                }
                issue.lines.forEach { line ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "Строка ${line.lineNo} · ${names.item(line.nomenclatureId)}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "Запрошено ${names.qty(line.requestedQuantity, line.nomenclatureId)} · " +
                                    "выдано ${names.qty(line.issuedQuantity, line.nomenclatureId)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            line.picks.forEach { pick ->
                                Text(
                                    "Отбор: ячейка ${names.location(pick.locationId)}, ${names.qty(pick.quantity, line.nomenclatureId)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // Сколько ещё осталось отобрать по строке.
                            val remaining = (line.requestedQuantity.toDoubleOrNull() ?: 0.0) -
                                line.picks.sumOf { it.quantity.toDoubleOrNull() ?: 0.0 }
                            if (canPick && remaining <= 0.0) {
                                Text(
                                    "Отобрано полностью",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = org.example.project.ui.theme.readableAccent(Brand.Teal)
                                )
                            }
                            if (canPick && remaining > 0.0) {
                                val form = pickForms[line.id] ?: IssuePickForm(
                                    quantity = if (remaining % 1.0 == 0.0) remaining.toLong().toString() else remaining.toString()
                                )
                                LocationDropdown(
                                    locations = locations,
                                    selected = locations.find { it.id == form.locationId },
                                    onSelect = { onPickFormChange(line.id, form.copy(locationId = it.id)) },
                                    label = "Ячейка отбора",
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = form.quantity,
                                        onValueChange = { onPickFormChange(line.id, form.copy(quantity = it)) },
                                        label = { Text("Количество") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                    Button(onClick = { onPick(line.id) }) { Text("Отобрать") }
                                }
                                LotPicker(
                                    value = form.lotId,
                                    onChange = { onPickFormChange(line.id, form.copy(lotId = it)) },
                                    nomenclatureId = line.nomenclatureId,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                if (canRefuse) {
                    HorizontalDivider()
                    OutlinedTextField(
                        value = refusalReason,
                        onValueChange = onRefusalReasonChange,
                        label = { Text("Причина отказа") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                }
    }
}

private fun issueStatusAccent(status: IssueStatus) = when (status) {
    IssueStatus.DRAFT -> Brand.Blue
    IssueStatus.ISSUED -> Brand.Indigo
    IssueStatus.RECEIVED -> Brand.Teal
    IssueStatus.SHORTAGE -> Brand.Amber
    IssueStatus.REFUSED, IssueStatus.CANCELLED -> Brand.Rose
}

@Composable
internal fun CreateIssueDialog(
    form: CreateIssueForm,
    warehouses: List<WarehouseDto>,
    nomenclature: List<NomenclatureDto>,
    error: org.example.project.dto.ApiError?,
    onFormChange: (CreateIssueForm) -> Unit,
    onWarehouseChange: (Long?) -> Unit,
    onAddLine: () -> Unit,
    onUpdateLine: (Int, CreateIssueLineForm) -> Unit,
    onRemoveLine: (Int) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
    reservations: List<org.example.project.dto.ReservationDto> = emptyList()
) {
    org.example.project.ui.theme.ErpDialog(
        title = "Новый заказ на выдачу",
        glyph = ErpIconGlyph.ChevronRight,
        gradient = org.example.project.ui.theme.Gradients.Emerald,
        width = 700.dp,
        confirmText = "Создать",
        onConfirm = onCreate,
        confirmEnabled = warehouses.isNotEmpty() && form.lines.isNotEmpty(),
        onDismiss = onDismiss
    ) {
                error?.let { ErrorMessageCard(error = it) }
                if (warehouses.isEmpty()) {
                    Text(
                        text = "Нет доступных складов.",
                        color = MaterialTheme.colorScheme.error
                    )
                }
                WarehouseDropdown(
                    warehouses = warehouses,
                    selected = warehouses.find { it.id == form.warehouseId },
                    onSelect = { onWarehouseChange(it.id) },
                    label = "Склад *",
                    modifier = Modifier.fillMaxWidth()
                )
                ReservationReasonTypeDropdown(
                    selected = form.reasonType,
                    onSelect = { onFormChange(form.copy(reasonType = it)) },
                    modifier = Modifier.fillMaxWidth()
                )
                ProjectPicker(
                    value = form.projectId,
                    onChange = { onFormChange(form.copy(projectId = it, taskId = "")) },
                    modifier = Modifier.fillMaxWidth()
                )
                TaskPicker(
                    value = form.taskId,
                    onChange = { onFormChange(form.copy(taskId = it)) },
                    projectId = form.projectId,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = form.reasonNote,
                    onValueChange = { onFormChange(form.copy(reasonNote = it)) },
                    label = { Text("Пояснение основания") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                UserPicker(
                    value = form.recipientId,
                    onChange = { onFormChange(form.copy(recipientId = it)) },
                    label = "Получатель",
                    clearText = "Я (по умолчанию)",
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = form.note,
                    onValueChange = { onFormChange(form.copy(note = it)) },
                    label = { Text("Примечание") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )

                Text("Строки выдачи", style = MaterialTheme.typography.labelLarge)
                form.lines.forEachIndexed { index, line ->
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Строка ${line.lineNo}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(onClick = { onRemoveLine(index) }) { Text("Удалить") }
                        }
                        NomenclatureDropdown(
                            items = nomenclature,
                            selected = nomenclature.find { it.id == line.nomenclatureId },
                            onSelect = { onUpdateLine(index, line.copy(nomenclatureId = it.id)) },
                            label = "Номенклатура *",
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = line.quantity,
                            onValueChange = { onUpdateLine(index, line.copy(quantity = it)) },
                            label = { Text("Запрошенное количество *") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        val lineReservations = reservations.filter {
                            it.status == org.example.project.dto.ReservationStatus.ACTIVE &&
                                (line.nomenclatureId == null || it.nomenclatureId == line.nomenclatureId) &&
                                (form.warehouseId == null || it.warehouseId == form.warehouseId)
                        }
                        SearchableDropdown(
                            items = lineReservations,
                            selected = reservations.find { it.id.toString() == line.reservationId.trim() },
                            onSelect = { onUpdateLine(index, line.copy(reservationId = it.id.toString())) },
                            label = "Резерв (необязательно)",
                            itemText = { r -> "${r.number} · ${ErpFormat.qty(r.quantity)} · ${r.reasonType.localizedName()}" },
                            emptyText = "Активных резервов по позиции нет",
                            onClear = { onUpdateLine(index, line.copy(reservationId = "")) },
                            clearText = "Без резерва",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Button(onClick = onAddLine) { Text("Добавить строку") }
    }
}

// ---------------------------------------------------------------------------
// Возвраты (ТЗ 8.9)
// ---------------------------------------------------------------------------

@Composable
internal fun ReturnsTab(
    state: WmsUiState,
    viewModel: WmsViewModel
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TabHeader(
            title = "Возвраты",
            onCreate = { viewModel.showDialog(WmsDialog.CreateReturn) },
            onRefresh = viewModel::loadReturns,
            createLabel = "Создать возврат"
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ReturnStatusDropdown(
                selected = state.returnStatusFilter,
                onSelect = viewModel::updateReturnStatusFilter,
                modifier = Modifier.width(320.dp)
            )
        }
        state.operationError?.let { ErrorMessageCard(error = it) }
        if (state.returnsLoading && state.returns.isEmpty()) {
            LoadingOverlay()
        } else if (state.returns.isEmpty()) {
            if (state.returnStatusFilter != null) {
                EmptyState(
                    "Нет возвратов в выбранном статусе.",
                    actionLabel = "Показать все",
                    onAction = { viewModel.updateReturnStatusFilter(null) }
                )
            } else {
                EmptyState(
                    "Возвратов нет. Возврат оформляется по выдаче с подтверждённым получением.",
                    actionLabel = "Создать возврат",
                    onAction = { viewModel.showDialog(WmsDialog.CreateReturn) }
                )
            }
        } else {
            ReturnsGrid(
                returns = state.returns,
                onClick = { viewModel.selectReturn(it) },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
        state.selectedReturn?.let { order ->
            ReturnDetailDialog(
                order = order,
                locations = state.returnLocations,
                inspectForms = state.returnInspectForms,
                onInspectFormChange = viewModel::updateReturnInspectForm,
                onInspect = viewModel::inspectReturnLine,
                onReceive = viewModel::receiveSelectedReturn,
                onClose = { viewModel.selectReturn(null) }
            )
        }
    }
}

@Composable
private fun ReturnDetailDialog(
    order: ReturnOrderDto,
    locations: List<StorageLocationDto>,
    inspectForms: Map<Long, ReturnInspectForm>,
    onInspectFormChange: (Long, ReturnInspectForm) -> Unit,
    onInspect: (Long) -> Unit,
    onReceive: () -> Unit,
    onClose: () -> Unit
) {
    val canReceive = order.status == ReturnStatus.DRAFT
    val canInspect = order.status == ReturnStatus.RECEIVED

    org.example.project.ui.theme.ErpDialog(
        title = "Возврат ${order.number}",
        subtitle = order.status.localizedName(),
        glyph = ErpIconGlyph.Refresh,
        gradient = org.example.project.ui.theme.Gradients.Emerald,
        width = 680.dp,
        confirmText = if (canReceive) "Принять на карантин" else null,
        onConfirm = if (canReceive) onReceive else null,
        dismissText = "Закрыть",
        onDismiss = onClose
    ) {
                val names = LocalWmsNames.current
                val issueDetails = rememberIssueDetails(order.issueOrderId)
                Text("Выдача: ${names.issue(order.issueOrderId)}", style = MaterialTheme.typography.bodyMedium)
                Text("Склад: ${names.warehouse(order.warehouseId)}", style = MaterialTheme.typography.bodyMedium)
                Text("Карантинная ячейка: ${names.location(order.quarantineLocationId)}", style = MaterialTheme.typography.bodyMedium)
                Text("Причина: ${order.reason}", style = MaterialTheme.typography.bodyMedium)
                Text("Принято: ${ErpFormat.dateRu(order.receivedAt)}", style = MaterialTheme.typography.bodyMedium)

                HorizontalDivider()
                Text("Строки возврата", style = MaterialTheme.typography.labelLarge)
                order.lines.forEach { line ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    issueDetails?.lines?.find { it.id == line.issueLineId }?.nomenclatureId.let { itemId ->
                                        "${names.item(itemId)} · ${names.qty(line.quantity, itemId)}"
                                    },
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                PillBadge(
                                    text = line.inspection.localizedName(),
                                    accent = if (line.inspection == ReturnInspection.DEFECT) Brand.Rose else Brand.Teal
                                )
                            }
                            line.inspectionNote?.let {
                                Text(
                                    "Заметка: $it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (canInspect) {
                                val form = inspectForms[line.id] ?: ReturnInspectForm()
                                ReturnInspectionDecisionDropdown(
                                    selected = form.decision,
                                    onSelect = { onInspectFormChange(line.id, form.copy(decision = it)) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (form.decision == ReturnInspectionDecision.GOOD) {
                                    LocationDropdown(
                                        locations = locations,
                                        selected = locations.find { it.id == form.targetLocationId },
                                        onSelect = { onInspectFormChange(line.id, form.copy(targetLocationId = it.id)) },
                                        label = "Ячейка размещения *",
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                OutlinedTextField(
                                    value = form.note,
                                    onValueChange = { onInspectFormChange(line.id, form.copy(note = it)) },
                                    label = { Text("Заметка") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                Button(onClick = { onInspect(line.id) }) { Text("Применить решение") }
                            }
                        }
                    }
                }
    }
}

private fun returnStatusAccent(status: ReturnStatus) = when (status) {
    ReturnStatus.DRAFT -> Brand.Blue
    ReturnStatus.RECEIVED -> Brand.Indigo
    ReturnStatus.INSPECTED -> Brand.Teal
}

@Composable
internal fun CreateReturnDialog(
    form: CreateReturnForm,
    warehouses: List<WarehouseDto>,
    locations: List<StorageLocationDto>,
    error: org.example.project.dto.ApiError?,
    onFormChange: (CreateReturnForm) -> Unit,
    onWarehouseChange: (Long?) -> Unit,
    onAddLine: () -> Unit,
    onUpdateLine: (Int, CreateReturnLineForm) -> Unit,
    onRemoveLine: (Int) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
    issues: List<IssueOrderDto> = emptyList(),
    nomenclature: List<NomenclatureDto> = emptyList()
) {
    // Вернуть можно только то, что фактически выдано с выбранного склада: выданное
    // количество фиксируется при подтверждении получения (статусы «Получено» и «Недостача»).
    val returnableIssues = issues.filter {
        (form.warehouseId == null || it.warehouseId == form.warehouseId) &&
            it.status in setOf(IssueStatus.RECEIVED, IssueStatus.SHORTAGE)
    }
    val selectedIssueId = form.issueOrderId.trim().toLongOrNull()
    val selectedIssue = rememberIssueDetails(selectedIssueId) ?: issues.find { it.id == selectedIssueId }
    org.example.project.ui.theme.ErpDialog(
        title = "Новый возврат",
        glyph = ErpIconGlyph.Refresh,
        gradient = org.example.project.ui.theme.Gradients.Emerald,
        width = 700.dp,
        confirmText = "Создать",
        onConfirm = onCreate,
        confirmEnabled = warehouses.isNotEmpty() && form.lines.isNotEmpty(),
        onDismiss = onDismiss
    ) {
                error?.let { ErrorMessageCard(error = it) }
                if (warehouses.isEmpty()) {
                    Text(
                        text = "Нет доступных складов.",
                        color = MaterialTheme.colorScheme.error
                    )
                }
                WarehouseDropdown(
                    warehouses = warehouses,
                    selected = warehouses.find { it.id == form.warehouseId },
                    onSelect = { onWarehouseChange(it.id) },
                    label = "Склад *",
                    modifier = Modifier.fillMaxWidth()
                )
                SearchableDropdown(
                    items = returnableIssues,
                    selected = selectedIssue,
                    onSelect = { issue ->
                        onFormChange(
                            form.copy(
                                issueOrderId = issue.id.toString(),
                                lines = form.lines.map { it.copy(issueLineId = "") }
                            )
                        )
                    },
                    label = "Заказ на выдачу *",
                    itemText = { issue ->
                        "${issue.number} · ${issue.reasonType.localizedName()} · ${ErpFormat.dateRu(issue.issuedAt)}"
                    },
                    emptyText = "Нет выдач с подтверждённым получением по выбранному складу",
                    modifier = Modifier.fillMaxWidth()
                )
                LocationDropdown(
                    locations = locations,
                    selected = locations.find { it.id == form.quarantineLocationId },
                    onSelect = { onFormChange(form.copy(quarantineLocationId = it.id)) },
                    label = "Карантинная ячейка *",
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = form.reason,
                    onValueChange = { onFormChange(form.copy(reason = it)) },
                    label = { Text("Причина возврата *") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )

                Text("Строки возврата", style = MaterialTheme.typography.labelLarge)
                form.lines.forEachIndexed { index, line ->
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Строка ${index + 1}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(onClick = { onRemoveLine(index) }) { Text("Удалить") }
                        }
                        val issueLines = selectedIssue?.lines.orEmpty()
                        SearchableDropdown(
                            items = issueLines,
                            selected = issueLines.find { it.id.toString() == line.issueLineId.trim() },
                            onSelect = { onUpdateLine(index, line.copy(issueLineId = it.id.toString())) },
                            label = "Строка выдачи *",
                            itemText = { issueLine ->
                                val item = nomenclature.find { it.id == issueLine.nomenclatureId }
                                "№${issueLine.lineNo} · ${item?.let { "${it.code} · ${it.name}" } ?: "Поз. #${issueLine.nomenclatureId}"}" +
                                    " · выдано ${ErpFormat.qty(issueLine.issuedQuantity)} ${item?.unit.orEmpty()}"
                            },
                            emptyText = if (selectedIssue == null) "Сначала выберите заказ на выдачу" else "В заказе нет строк",
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = line.quantity,
                            onValueChange = { onUpdateLine(index, line.copy(quantity = it)) },
                            label = { Text("Количество *") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        LotPicker(
                            value = line.lotId,
                            onChange = { onUpdateLine(index, line.copy(lotId = it)) },
                            nomenclatureId = selectedIssue?.lines?.find { it.id.toString() == line.issueLineId.trim() }?.nomenclatureId,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Button(onClick = onAddLine) { Text("Добавить строку") }
    }
}

// ---------------------------------------------------------------------------
// Списание (ТЗ 8.10)
// ---------------------------------------------------------------------------

@Composable
internal fun WriteOffsTab(
    state: WmsUiState,
    viewModel: WmsViewModel
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TabHeader(
            title = "Списания",
            onCreate = { viewModel.showDialog(WmsDialog.CreateWriteOff) },
            onRefresh = viewModel::loadWriteOffs,
            createLabel = "Создать списание"
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WriteOffStatusDropdown(
                selected = state.writeOffStatusFilter,
                onSelect = viewModel::updateWriteOffStatusFilter,
                modifier = Modifier.width(320.dp)
            )
        }
        state.operationError?.let { ErrorMessageCard(error = it) }
        if (state.writeOffsLoading && state.writeOffs.isEmpty()) {
            LoadingOverlay()
        } else if (state.writeOffs.isEmpty()) {
            if (state.writeOffStatusFilter != null) {
                EmptyState(
                    "Нет списаний в выбранном статусе.",
                    actionLabel = "Показать все",
                    onAction = { viewModel.updateWriteOffStatusFilter(null) }
                )
            } else {
                EmptyState(
                    "Списаний нет.",
                    actionLabel = "Создать списание",
                    onAction = { viewModel.showDialog(WmsDialog.CreateWriteOff) }
                )
            }
        } else {
            WriteOffsGrid(
                writeOffs = state.writeOffs,
                onClick = { viewModel.selectWriteOff(it) },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
        state.selectedWriteOff?.let { writeOff ->
            WriteOffDetailDialog(
                writeOff = writeOff,
                cancelReason = state.writeOffCancelReason,
                onCancelReasonChange = viewModel::updateWriteOffCancelReason,
                onPost = viewModel::postSelectedWriteOff,
                onCancel = viewModel::cancelSelectedWriteOff,
                onClose = { viewModel.selectWriteOff(null) }
            )
        }
    }
}

@Composable
private fun WriteOffDetailDialog(
    writeOff: WriteOffDto,
    cancelReason: String,
    onCancelReasonChange: (String) -> Unit,
    onPost: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit
) {
    val canPost = writeOff.status == WriteOffStatus.DRAFT || writeOff.status == WriteOffStatus.APPROVED
    val canCancel = writeOff.status == WriteOffStatus.DRAFT ||
        writeOff.status == WriteOffStatus.PENDING_APPROVAL ||
        writeOff.status == WriteOffStatus.APPROVED

    org.example.project.ui.theme.ErpDialog(
        title = "Списание ${writeOff.number}",
        subtitle = writeOff.status.localizedName(),
        glyph = ErpIconGlyph.Alert,
        gradient = org.example.project.ui.theme.Gradients.Sunset,
        width = 660.dp,
        dismissText = "Закрыть",
        onDismiss = onClose,
        extraActions = {
            if (canPost) Button(onClick = onPost) { Text("Провести") }
            if (canCancel) TextButton(onClick = onCancel) { Text("Отменить") }
        }
    ) {
                Text("Причина: ${writeOff.reasonType.localizedName()}", style = MaterialTheme.typography.bodyMedium)
                Text("Основание: ${writeOff.reasonNote}", style = MaterialTheme.typography.bodyMedium)
                Text("Количество: ${writeOff.totalQuantity}", style = MaterialTheme.typography.bodyMedium)
                if (writeOff.overLimit) {
                    Text(
                        "Сверх лимита склада (${writeOff.limitQuantity ?: "не задан"}): требуется согласование",
                        style = MaterialTheme.typography.bodyMedium,
                        color = org.example.project.ui.theme.readableAccent(Brand.Amber)
                    )
                }
                writeOff.postedAt?.let {
                    Text("Проведено: ${ErpFormat.dateRu(it)}", style = MaterialTheme.typography.bodyMedium)
                }
                writeOff.cancelReason?.let {
                    Text("Причина отмены: $it", style = MaterialTheme.typography.bodyMedium)
                }
                val names = LocalWmsNames.current

                HorizontalDivider()
                Text("Строки", style = MaterialTheme.typography.labelLarge)
                writeOff.lines.forEach { line ->
                    Text(
                        "Строка ${line.lineNo}: ${names.item(line.nomenclatureId)}, " +
                            "ячейка ${names.location(line.locationId)}, ${names.qty(line.quantity, line.nomenclatureId)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (canCancel) {
                    HorizontalDivider()
                    OutlinedTextField(
                        value = cancelReason,
                        onValueChange = onCancelReasonChange,
                        label = { Text("Причина отмены") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                }
    }
}

private fun writeOffStatusAccent(status: WriteOffStatus) = when (status) {
    WriteOffStatus.DRAFT -> Brand.Blue
    WriteOffStatus.PENDING_APPROVAL -> Brand.Amber
    WriteOffStatus.APPROVED -> Brand.Indigo
    WriteOffStatus.POSTED -> Brand.Teal
    WriteOffStatus.CANCELLED -> Brand.Rose
}

@Composable
internal fun CreateWriteOffDialog(
    form: CreateWriteOffForm,
    warehouses: List<WarehouseDto>,
    locations: List<StorageLocationDto>,
    nomenclature: List<NomenclatureDto>,
    limit: String,
    error: org.example.project.dto.ApiError?,
    onFormChange: (CreateWriteOffForm) -> Unit,
    onWarehouseChange: (Long?) -> Unit,
    onLimitChange: (String) -> Unit,
    onSaveLimit: () -> Unit,
    onAddLine: () -> Unit,
    onUpdateLine: (Int, CreateWriteOffLineForm) -> Unit,
    onRemoveLine: (Int) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    org.example.project.ui.theme.ErpDialog(
        title = "Новое списание",
        glyph = ErpIconGlyph.Alert,
        gradient = org.example.project.ui.theme.Gradients.Sunset,
        width = 700.dp,
        confirmText = "Создать",
        onConfirm = onCreate,
        confirmEnabled = warehouses.isNotEmpty() && form.lines.isNotEmpty(),
        confirmGradient = org.example.project.ui.theme.Gradients.Sunset,
        onDismiss = onDismiss
    ) {
                error?.let { ErrorMessageCard(error = it) }
                if (warehouses.isEmpty()) {
                    Text(
                        text = "Нет доступных складов.",
                        color = MaterialTheme.colorScheme.error
                    )
                }
                WarehouseDropdown(
                    warehouses = warehouses,
                    selected = warehouses.find { it.id == form.warehouseId },
                    onSelect = { onWarehouseChange(it.id) },
                    label = "Склад *",
                    modifier = Modifier.fillMaxWidth()
                )
                WriteOffReasonTypeDropdown(
                    selected = form.reasonType,
                    onSelect = { onFormChange(form.copy(reasonType = it)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = form.reasonNote,
                    onValueChange = { onFormChange(form.copy(reasonNote = it)) },
                    label = { Text("Основание списания *") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                OutlinedTextField(
                    value = form.note,
                    onValueChange = { onFormChange(form.copy(note = it)) },
                    label = { Text("Примечание") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )

                HorizontalDivider()
                Text("Лимит без согласования", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = limit,
                        onValueChange = onLimitChange,
                        label = { Text("Лимит склада") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Button(onClick = onSaveLimit, enabled = form.warehouseId != null) { Text("Сохранить") }
                }
                Text(
                    text = "Списание сверх лимита проводится только после согласования " +
                        "или с правом «wms:write_off_over_limit».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()
                Text("Строки списания", style = MaterialTheme.typography.labelLarge)
                form.lines.forEachIndexed { index, line ->
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Строка ${line.lineNo}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(onClick = { onRemoveLine(index) }) { Text("Удалить") }
                        }
                        NomenclatureDropdown(
                            items = nomenclature,
                            selected = nomenclature.find { it.id == line.nomenclatureId },
                            onSelect = { onUpdateLine(index, line.copy(nomenclatureId = it.id, lotId = "")) },
                            label = "Номенклатура *",
                            modifier = Modifier.fillMaxWidth()
                        )
                        LocationDropdown(
                            locations = locations,
                            selected = locations.find { it.id == line.locationId },
                            onSelect = { onUpdateLine(index, line.copy(locationId = it.id)) },
                            label = "Ячейка списания *",
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = line.quantity,
                            onValueChange = { onUpdateLine(index, line.copy(quantity = it)) },
                            label = { Text("Количество *") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        LotPicker(
                            value = line.lotId,
                            onChange = { onUpdateLine(index, line.copy(lotId = it)) },
                            nomenclatureId = line.nomenclatureId,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Button(onClick = onAddLine) { Text("Добавить строку") }
    }
}

// ---------------------------------------------------------------------------
// Инвентаризация (ТЗ 8.11)
// ---------------------------------------------------------------------------

@Composable
internal fun InventoriesTab(
    state: WmsUiState,
    viewModel: WmsViewModel
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TabHeader(
            title = "Инвентаризация",
            onCreate = { viewModel.showDialog(WmsDialog.CreateInventory) },
            onRefresh = viewModel::loadInventories,
            createLabel = "Создать инвентаризацию"
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            InventoryStatusDropdown(
                selected = state.inventoryStatusFilter,
                onSelect = viewModel::updateInventoryStatusFilter,
                modifier = Modifier.width(320.dp)
            )
        }
        state.operationError?.let { ErrorMessageCard(error = it) }
        if (state.inventoriesLoading && state.inventories.isEmpty()) {
            LoadingOverlay()
        } else if (state.inventories.isEmpty()) {
            if (state.inventoryStatusFilter != null) {
                EmptyState(
                    "Нет инвентаризаций в выбранном статусе.",
                    actionLabel = "Показать все",
                    onAction = { viewModel.updateInventoryStatusFilter(null) }
                )
            } else {
                EmptyState(
                    "Инвентаризаций нет. Инвентаризация сверяет учётный остаток с фактическим.",
                    actionLabel = "Создать инвентаризацию",
                    onAction = { viewModel.showDialog(WmsDialog.CreateInventory) }
                )
            }
        } else {
            InventoriesGrid(
                inventories = state.inventories,
                onClick = { viewModel.selectInventory(it) },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
        state.selectedInventory?.let { inventory ->
            InventoryDetailDialog(
                inventory = inventory,
                countInputs = state.inventoryCountInputs,
                cancelReason = state.inventoryCancelReason,
                onCountInputChange = viewModel::updateInventoryCountInput,
                onCancelReasonChange = viewModel::updateInventoryCancelReason,
                onBook = viewModel::bookSelectedInventory,
                onSubmitCounts = viewModel::submitSelectedInventoryCounts,
                onApprove = viewModel::approveSelectedInventory,
                onPost = viewModel::postSelectedInventory,
                onCancel = viewModel::cancelSelectedInventory,
                onClose = { viewModel.selectInventory(null) }
            )
        }
    }
}

@Composable
private fun InventoryDetailDialog(
    inventory: InventoryDto,
    countInputs: Map<Long, String>,
    cancelReason: String,
    onCountInputChange: (Long, String) -> Unit,
    onCancelReasonChange: (String) -> Unit,
    onBook: () -> Unit,
    onSubmitCounts: () -> Unit,
    onApprove: () -> Unit,
    onPost: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit
) {
    val canBook = inventory.status == InventoryStatus.DRAFT
    val canCount = inventory.status in setOf(
        InventoryStatus.BOOKED,
        InventoryStatus.COUNTING,
        InventoryStatus.DISCREPANCY
    )
    val canApprove = inventory.status == InventoryStatus.DISCREPANCY ||
        inventory.status == InventoryStatus.COUNTING
    val canPost = inventory.status == InventoryStatus.DISCREPANCY ||
        inventory.status == InventoryStatus.APPROVED
    val canCancel = inventory.status != InventoryStatus.POSTED && inventory.status != InventoryStatus.CANCELLED
    val discrepancyBySnapshot = inventory.discrepancies.associateBy { it.snapshotId }

    org.example.project.ui.theme.ErpDialog(
        title = "Инвентаризация ${inventory.number}",
        subtitle = inventory.status.localizedName(),
        glyph = ErpIconGlyph.Documents,
        gradient = org.example.project.ui.theme.Gradients.Emerald,
        width = 720.dp,
        dismissText = "Закрыть",
        onDismiss = onClose,
        extraActions = {
            if (canBook) Button(onClick = onBook) { Text("Зафиксировать срез") }
            if (canCount) Button(onClick = onSubmitCounts) { Text("Сохранить подсчёт") }
            if (canApprove) Button(onClick = onApprove) { Text("Согласовать") }
            if (canPost) Button(onClick = onPost) { Text("Провести") }
            if (canCancel) TextButton(onClick = onCancel) { Text("Отменить") }
        }
    ) {
                val names = LocalWmsNames.current
                Text("Область: ${inventory.scopeType.localizedName()}", style = MaterialTheme.typography.bodyMedium)
                inventory.scopeLocationId?.let {
                    Text("Ячейка/зона: ${names.location(it)}", style = MaterialTheme.typography.bodyMedium)
                }
                inventory.scopeNomenclatureId?.let {
                    Text("Позиция: ${names.item(it)}", style = MaterialTheme.typography.bodyMedium)
                }
                Text("Срез: ${ErpFormat.dateRu(inventory.bookedAt)}", style = MaterialTheme.typography.bodyMedium)
                inventory.note?.let { Text("Примечание: $it", style = MaterialTheme.typography.bodyMedium) }
                inventory.cancelReason?.let {
                    Text("Причина отмены: $it", style = MaterialTheme.typography.bodyMedium)
                }

                HorizontalDivider()
                if (inventory.snapshot.isEmpty()) {
                    Text(
                        text = "Учётный срез не зафиксирован: нажмите «Зафиксировать срез», " +
                            "чтобы снять остатки области.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text("Позиции и подсчёт", style = MaterialTheme.typography.labelLarge)
                    inventory.snapshot.forEach { line ->
                        val discrepancy = discrepancyBySnapshot[line.id]
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Ячейка ${names.location(line.locationId)} · ${names.item(line.nomenclatureId)}",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                PillBadge(
                                    text = "Учёт ${ErpFormat.qty(line.bookQuantity)}",
                                    accent = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            val discrepancyKind = discrepancy?.kind
                            if (discrepancyKind != null) {
                                PillBadge(
                                    text = "${discrepancyKind.localizedName()}: ${ErpFormat.qty(discrepancy.discrepancy)}",
                                    accent = if (discrepancyKind ==
                                        org.example.project.dto.InventoryDiscrepancyKind.SURPLUS
                                    ) {
                                        Brand.Teal
                                    } else {
                                        Brand.Rose
                                    }
                                )
                            }
                            if (canCount) {
                                OutlinedTextField(
                                    value = countInputs[line.id] ?: "",
                                    onValueChange = { onCountInputChange(line.id, it) },
                                    label = { Text("Фактическое количество") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }
                        }
                    }
                }

                if (canCancel) {
                    HorizontalDivider()
                    OutlinedTextField(
                        value = cancelReason,
                        onValueChange = onCancelReasonChange,
                        label = { Text("Причина отмены") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                }
    }
}

private fun inventoryStatusAccent(status: InventoryStatus) = when (status) {
    InventoryStatus.DRAFT -> Brand.Blue
    InventoryStatus.BOOKED -> Brand.Indigo
    InventoryStatus.COUNTING -> Brand.Cyan
    InventoryStatus.DISCREPANCY -> Brand.Amber
    InventoryStatus.APPROVED -> Brand.Indigo
    InventoryStatus.POSTED -> Brand.Teal
    InventoryStatus.CANCELLED -> Brand.Rose
}

@Composable
internal fun CreateInventoryDialog(
    form: CreateInventoryForm,
    warehouses: List<WarehouseDto>,
    locations: List<StorageLocationDto>,
    nomenclature: List<NomenclatureDto>,
    error: org.example.project.dto.ApiError?,
    onFormChange: (CreateInventoryForm) -> Unit,
    onWarehouseChange: (Long?) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    org.example.project.ui.theme.ErpDialog(
        title = "Новая инвентаризация",
        glyph = ErpIconGlyph.Documents,
        gradient = org.example.project.ui.theme.Gradients.Emerald,
        width = 640.dp,
        confirmText = "Создать",
        onConfirm = onCreate,
        confirmEnabled = warehouses.isNotEmpty(),
        onDismiss = onDismiss
    ) {
                error?.let { ErrorMessageCard(error = it) }
                if (warehouses.isEmpty()) {
                    Text(
                        text = "Нет доступных складов.",
                        color = MaterialTheme.colorScheme.error
                    )
                }
                WarehouseDropdown(
                    warehouses = warehouses,
                    selected = warehouses.find { it.id == form.warehouseId },
                    onSelect = { onWarehouseChange(it.id) },
                    label = "Склад *",
                    modifier = Modifier.fillMaxWidth()
                )
                InventoryScopeTypeDropdown(
                    selected = form.scopeType,
                    onSelect = {
                        onFormChange(
                            form.copy(
                                scopeType = it,
                                scopeLocationId = null,
                                scopeNomenclatureId = null
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (form.scopeType == InventoryScopeType.ZONE || form.scopeType == InventoryScopeType.CELL) {
                    LocationDropdown(
                        locations = locations,
                        selected = locations.find { it.id == form.scopeLocationId },
                        onSelect = { onFormChange(form.copy(scopeLocationId = it.id)) },
                        label = "Зона или ячейка *",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (form.scopeType == InventoryScopeType.NOMENCLATURE) {
                    NomenclatureDropdown(
                        items = nomenclature,
                        selected = nomenclature.find { it.id == form.scopeNomenclatureId },
                        onSelect = { onFormChange(form.copy(scopeNomenclatureId = it.id)) },
                        label = "Номенклатура *",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = form.note,
                    onValueChange = { onFormChange(form.copy(note = it)) },
                    label = { Text("Примечание") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                Text(
                    text = "На складе может идти только одна активная инвентаризация. " +
                        "Срез фиксируется отдельным шагом перед подсчётом.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
    }
}
