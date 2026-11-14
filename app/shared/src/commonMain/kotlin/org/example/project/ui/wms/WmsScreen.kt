package org.example.project.ui.wms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.dto.ReceiptDto
import org.example.project.dto.ReceiptStatus
import org.example.project.dto.ReservationDto
import org.example.project.dto.ReservationStatus
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.Gradients
import org.example.project.ui.theme.PageHeader

@Composable
fun WmsScreen(
    viewModel: WmsViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(
            title = "Склад",
            glyph = ErpIconGlyph.Warehouse,
            gradient = Gradients.Emerald,
            subtitle = "Склады, ячейки, остатки, приёмка, резервы, перемещения, выдача, возвраты и списания"
        )
        Spacer(Modifier.height(10.dp))
        ScrollableTabRow(
            selectedTabIndex = WmsTab.entries.indexOf(state.selectedTab)
        ) {
            WmsTab.entries.forEach { tab ->
                Tab(
                    selected = state.selectedTab == tab,
                    onClick = { viewModel.selectTab(tab) },
                    text = { Text(tab.title) }
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            when (state.selectedTab) {
                WmsTab.WAREHOUSES -> WarehousesTab(state, viewModel)
                WmsTab.LOCATIONS -> LocationsTab(state, viewModel)
                WmsTab.NOMENCLATURE -> NomenclatureTab(state, viewModel)
                WmsTab.STOCK -> StockTab(state, viewModel)
                WmsTab.RECEIPTS -> ReceiptsTab(state, viewModel)
                WmsTab.RESERVATIONS -> ReservationsTab(state, viewModel)
                WmsTab.TRANSFERS -> TransfersTab(state, viewModel)
                WmsTab.ISSUES -> IssuesTab(state, viewModel)
                WmsTab.RETURNS -> ReturnsTab(state, viewModel)
                WmsTab.WRITE_OFFS -> WriteOffsTab(state, viewModel)
            }
        }
    }

    when (state.activeDialog) {
        WmsDialog.CreateWarehouse -> CreateWarehouseDialog(
            form = state.createWarehouseForm,
            error = state.operationError,
            onFormChange = viewModel::updateCreateWarehouseForm,
            onCreate = viewModel::createWarehouse,
            onDismiss = viewModel::dismissDialog
        )
        WmsDialog.CreateLocation -> CreateLocationDialog(
            form = state.createLocationForm,
            warehouses = state.warehouses,
            error = state.operationError,
            onFormChange = viewModel::updateCreateLocationForm,
            onCreate = viewModel::createLocation,
            onDismiss = viewModel::dismissDialog
        )
        WmsDialog.CreateNomenclature -> CreateNomenclatureDialog(
            form = state.createNomenclatureForm,
            error = state.operationError,
            onFormChange = viewModel::updateCreateNomenclatureForm,
            onCreate = viewModel::createNomenclature,
            onDismiss = viewModel::dismissDialog
        )
        WmsDialog.CreateReceipt -> CreateReceiptDialog(
            form = state.createReceiptForm,
            warehouses = state.warehouses,
            locations = state.receiptLocations,
            nomenclature = state.nomenclature,
            error = state.operationError,
            onFormChange = viewModel::updateCreateReceiptForm,
            onWarehouseChange = viewModel::onReceiptWarehouseChanged,
            onAddLine = viewModel::addReceiptLine,
            onUpdateLine = viewModel::updateReceiptLine,
            onRemoveLine = viewModel::removeReceiptLine,
            onCreate = viewModel::createReceipt,
            onDismiss = viewModel::dismissDialog
        )
        WmsDialog.CreateReservation -> CreateReservationDialog(
            form = state.createReservationForm,
            warehouses = state.warehouses,
            locations = state.reservationLocations,
            nomenclature = state.nomenclature,
            error = state.operationError,
            onFormChange = viewModel::updateCreateReservationForm,
            onWarehouseChange = viewModel::onReservationWarehouseChanged,
            onCreate = viewModel::createReservation,
            onDismiss = viewModel::dismissDialog
        )
        WmsDialog.CreateTransfer -> CreateTransferDialog(
            form = state.createTransferForm,
            warehouses = state.warehouses,
            locationsFrom = state.transferLocationsFrom,
            locationsTo = state.transferLocationsTo,
            nomenclature = state.nomenclature,
            error = state.operationError,
            onFormChange = viewModel::updateCreateTransferForm,
            onFromWarehouseChange = viewModel::onTransferFromWarehouseChanged,
            onToWarehouseChange = viewModel::onTransferToWarehouseChanged,
            onCreate = viewModel::createTransfer,
            onDismiss = viewModel::dismissDialog
        )
        WmsDialog.CreateIssue -> CreateIssueDialog(
            form = state.createIssueForm,
            warehouses = state.warehouses,
            nomenclature = state.nomenclature,
            error = state.operationError,
            onFormChange = viewModel::updateCreateIssueForm,
            onWarehouseChange = viewModel::onIssueWarehouseChanged,
            onAddLine = viewModel::addIssueLine,
            onUpdateLine = viewModel::updateIssueLine,
            onRemoveLine = viewModel::removeIssueLine,
            onCreate = viewModel::createIssue,
            onDismiss = viewModel::dismissDialog
        )
        WmsDialog.CreateWriteOff -> CreateWriteOffDialog(
            form = state.createWriteOffForm,
            warehouses = state.warehouses,
            locations = state.writeOffLocations,
            nomenclature = state.nomenclature,
            limit = state.writeOffLimit,
            error = state.operationError,
            onFormChange = viewModel::updateCreateWriteOffForm,
            onWarehouseChange = viewModel::onWriteOffWarehouseChanged,
            onLimitChange = viewModel::updateWriteOffLimit,
            onSaveLimit = viewModel::saveWriteOffLimit,
            onAddLine = viewModel::addWriteOffLine,
            onUpdateLine = viewModel::updateWriteOffLine,
            onRemoveLine = viewModel::removeWriteOffLine,
            onCreate = viewModel::createWriteOff,
            onDismiss = viewModel::dismissDialog
        )
        WmsDialog.CreateReturn -> CreateReturnDialog(
            form = state.createReturnForm,
            warehouses = state.warehouses,
            locations = state.returnLocations,
            error = state.operationError,
            onFormChange = viewModel::updateCreateReturnForm,
            onWarehouseChange = viewModel::onReturnWarehouseChanged,
            onAddLine = viewModel::addReturnLine,
            onUpdateLine = viewModel::updateReturnLine,
            onRemoveLine = viewModel::removeReturnLine,
            onCreate = viewModel::createReturn,
            onDismiss = viewModel::dismissDialog
        )
        null -> { }
    }
}

@Composable
internal fun TabHeader(
    title: String,
    onCreate: () -> Unit,
    onRefresh: () -> Unit,
    createLabel: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        Row {
            Button(onClick = onCreate) { Text(createLabel) }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = onRefresh) { Text("Обновить") }
        }
    }
}

@Composable
internal fun LoadingOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun WarehousesTab(
    state: WmsUiState,
    viewModel: WmsViewModel
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TabHeader(
            title = "Склады",
            onCreate = { viewModel.showDialog(WmsDialog.CreateWarehouse) },
            onRefresh = viewModel::loadWarehouses,
            createLabel = "Создать склад"
        )
        state.operationError?.let { ErrorMessageCard(error = it) }
        if (state.isLoading && state.warehouses.isEmpty()) {
            LoadingOverlay()
        } else if (state.warehouses.isEmpty()) {
            EmptyState("Нет складов. Создайте первый склад.")
        } else {
            WarehousesGrid(
                warehouses = state.warehouses,
                onClick = { },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
    }
}

@Composable
private fun LocationsTab(
    state: WmsUiState,
    viewModel: WmsViewModel
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TabHeader(
            title = "Ячейки",
            onCreate = { viewModel.showDialog(WmsDialog.CreateLocation) },
            onRefresh = { state.locationFilterWarehouseId?.let(viewModel::loadLocations) },
            createLabel = "Создать ячейку"
        )
        if (state.warehouses.isEmpty()) {
            Text("Сначала создайте склад.", color = MaterialTheme.colorScheme.error)
        } else {
            WarehouseDropdown(
                warehouses = state.warehouses,
                selected = state.warehouses.find { it.id == state.locationFilterWarehouseId },
                onSelect = { viewModel.updateLocationFilterWarehouse(it.id) },
                label = "Склад",
                modifier = Modifier.fillMaxWidth()
            )
        }
        state.operationError?.let { ErrorMessageCard(error = it) }
        if (state.isLoading && state.locations.isEmpty()) {
            LoadingOverlay()
        } else if (state.locations.isEmpty()) {
            EmptyState("Нет ячеек для выбранного склада.")
        } else {
            LocationsGrid(
                locations = state.locations,
                onClick = { },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
    }
}

@Composable
private fun NomenclatureTab(
    state: WmsUiState,
    viewModel: WmsViewModel
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TabHeader(
            title = "Номенклатура",
            onCreate = { viewModel.showDialog(WmsDialog.CreateNomenclature) },
            onRefresh = viewModel::loadNomenclature,
            createLabel = "Создать номенклатуру"
        )
        state.operationError?.let { ErrorMessageCard(error = it) }
        if (state.isLoading && state.nomenclature.isEmpty()) {
            LoadingOverlay()
        } else if (state.nomenclature.isEmpty()) {
            EmptyState("Нет номенклатуры. Создайте первую позицию.")
        } else {
            NomenclatureGrid(
                items = state.nomenclature,
                onClick = { viewModel.selectNomenclature(it) },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
        if (state.selectedNomenclature != null) {
            NomenclatureDetailDialog(
                item = state.selectedNomenclature!!,
                onArchive = { viewModel.archiveNomenclature(state.selectedNomenclature!!) },
                onClose = { viewModel.selectNomenclature(null) }
            )
        }
    }
}

@Composable
private fun NomenclatureDetailDialog(
    item: org.example.project.dto.NomenclatureDto,
    onArchive: () -> Unit,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(item.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Код: ${item.code}", style = MaterialTheme.typography.bodyMedium)
                Text(text = "Тип: ${item.type.localizedName()}", style = MaterialTheme.typography.bodyMedium)
                Text(text = "Ед.изм.: ${item.unit}", style = MaterialTheme.typography.bodyMedium)
                if (item.barcodes.isNotEmpty()) {
                    Text("Штрихкоды:", style = MaterialTheme.typography.labelLarge)
                    item.barcodes.forEach { barcode ->
                        Text(
                            text = "${barcode.barcode} (${barcode.packageType.localizedName()}, ${barcode.quantityInPackage})",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onArchive) { Text("Архивировать") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Закрыть") } }
    )
}

@Composable
private fun StockTab(
    state: WmsUiState,
    viewModel: WmsViewModel
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "Остатки", style = MaterialTheme.typography.headlineSmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WarehouseDropdown(
                warehouses = state.warehouses,
                selected = state.warehouses.find { it.id == state.stockFilters.warehouseId },
                onSelect = { viewModel.updateStockFilters(state.stockFilters.copy(warehouseId = it.id)) },
                label = "Склад",
                modifier = Modifier.weight(1f)
            )
            NomenclatureDropdown(
                items = state.nomenclature,
                selected = state.nomenclature.find { it.id == state.stockFilters.nomenclatureId },
                onSelect = { viewModel.updateStockFilters(state.stockFilters.copy(nomenclatureId = it.id)) },
                label = "Номенклатура",
                modifier = Modifier.weight(1f)
            )
            Button(onClick = viewModel::loadStock) { Text("Применить") }
        }
        state.operationError?.let { ErrorMessageCard(error = it) }
        if (state.isLoading && state.stock.isEmpty()) {
            LoadingOverlay()
        } else if (state.stock.isEmpty()) {
            EmptyState("Остатки не найдены. Выберите фильтры и нажмите «Применить».")
        } else {
            StockGrid(
                stock = state.stock,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
    }
}

@Composable
private fun ReceiptsTab(
    state: WmsUiState,
    viewModel: WmsViewModel
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "Приёмка", style = MaterialTheme.typography.headlineSmall)
        state.operationError?.let { ErrorMessageCard(error = it) }

        if (state.currentReceipt == null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.loadReceiptId,
                    onValueChange = viewModel::updateLoadReceiptId,
                    label = { Text("ID приёмки") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Button(onClick = viewModel::loadReceiptById) { Text("Открыть") }
            }
            Button(onClick = { viewModel.showDialog(WmsDialog.CreateReceipt) }) {
                Text("Создать приёмку")
            }
        } else {
            ReceiptDetailCard(
                receipt = state.currentReceipt,
                onPost = viewModel::postReceipt,
                onClose = viewModel::clearCurrentReceipt
            )
        }
    }
}

@Composable
private fun ReceiptDetailCard(
    receipt: ReceiptDto,
    onPost: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Приёмка ${receipt.number}", style = MaterialTheme.typography.titleMedium)
            Text(text = "Статус: ${receipt.status.localizedName()}", style = MaterialTheme.typography.bodyMedium)
            Text(text = "Источник: ${receipt.sourceType.localizedName()}", style = MaterialTheme.typography.bodyMedium)
            Text(text = "Версия: ${receipt.version}", style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
            Text("Строки:", style = MaterialTheme.typography.labelLarge)
            receipt.lines.forEach { line ->
                Text(
                    text = "№${line.lineNo}: номенклатура ${line.nomenclatureId}, ожидалось ${line.expectedQuantity}, принято ${line.receivedQuantity}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (receipt.status == ReceiptStatus.DRAFT) {
                    Button(onClick = onPost) { Text("Провести") }
                }
                TextButton(onClick = onClose) { Text("Закрыть") }
            }
        }
    }
}

@Composable
private fun ReservationsTab(
    state: WmsUiState,
    viewModel: WmsViewModel
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TabHeader(
            title = "Резервы",
            onCreate = { viewModel.showDialog(WmsDialog.CreateReservation) },
            onRefresh = viewModel::loadReservations,
            createLabel = "Создать резерв"
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WarehouseDropdown(
                warehouses = state.warehouses,
                selected = state.warehouses.find { it.id == state.reservationFilters.warehouseId },
                onSelect = { viewModel.updateReservationFilters(state.reservationFilters.copy(warehouseId = it.id)) },
                label = "Склад",
                modifier = Modifier.weight(1f)
            )
            NomenclatureDropdown(
                items = state.nomenclature,
                selected = state.nomenclature.find { it.id == state.reservationFilters.nomenclatureId },
                onSelect = { viewModel.updateReservationFilters(state.reservationFilters.copy(nomenclatureId = it.id)) },
                label = "Номенклатура",
                modifier = Modifier.weight(1f)
            )
            ReservationStatusDropdown(
                selected = state.reservationFilters.status,
                onSelect = { viewModel.updateReservationFilters(state.reservationFilters.copy(status = it)) },
                modifier = Modifier.weight(1f)
            )
            Button(onClick = viewModel::loadReservations) { Text("Применить") }
        }
        state.operationError?.let { ErrorMessageCard(error = it) }
        if (state.isLoading && state.reservations.isEmpty()) {
            LoadingOverlay()
        } else if (state.reservations.isEmpty()) {
            EmptyState("Резервы не найдены.")
        } else {
            ReservationsGrid(
                reservations = state.reservations,
                onClick = { viewModel.selectReservation(it) },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
        if (state.currentReservation != null) {
            ReservationDetailDialog(
                reservation = state.currentReservation!!,
                releaseReason = state.releaseReason,
                onReasonChange = viewModel::updateReleaseReason,
                onRelease = viewModel::releaseReservation,
                onClose = viewModel::clearCurrentReservation
            )
        }
    }
}

@Composable
private fun ReservationDetailDialog(
    reservation: ReservationDto,
    releaseReason: String,
    onReasonChange: (String) -> Unit,
    onRelease: () -> Unit,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Резерв ${reservation.number}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Основание: ${reservation.reasonType.localizedName()}", style = MaterialTheme.typography.bodyMedium)
                Text(text = "Количество: ${reservation.quantity}", style = MaterialTheme.typography.bodyMedium)
                Text(text = "Списано: ${reservation.issuedQuantity}", style = MaterialTheme.typography.bodyMedium)
                Text(text = "Статус: ${reservation.status.localizedName()}", style = MaterialTheme.typography.bodyMedium)
                Text(text = "Версия: ${reservation.version}", style = MaterialTheme.typography.bodyMedium)
                if (reservation.status == ReservationStatus.ACTIVE) {
                    HorizontalDivider()
                    OutlinedTextField(
                        value = releaseReason,
                        onValueChange = onReasonChange,
                        label = { Text("Причина снятия") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                }
            }
        },
        confirmButton = {
            if (reservation.status == ReservationStatus.ACTIVE) {
                Button(onClick = onRelease) { Text("Снять резерв") }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Закрыть") } }
    )
}

@Composable
internal fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
