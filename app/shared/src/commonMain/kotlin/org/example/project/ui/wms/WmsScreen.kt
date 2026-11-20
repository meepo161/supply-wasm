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
import org.example.project.dto.NomenclatureDto
import org.example.project.dto.ReceiptDto
import org.example.project.dto.ReceiptStatus
import org.example.project.dto.WarehouseDto
import org.example.project.dto.ReservationDto
import org.example.project.dto.ReservationStatus
import org.example.project.ui.components.SearchableDropdown
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
            subtitle = "Склады, ячейки, остатки, приёмка, резервы, перемещения, выдача, возвраты, списания и инвентаризация"
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
                WmsTab.INVENTORIES -> InventoriesTab(state, viewModel)
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
        WmsDialog.CreateInventory -> CreateInventoryDialog(
            form = state.createInventoryForm,
            warehouses = state.warehouses,
            locations = state.inventoryLocations,
            nomenclature = state.nomenclature,
            error = state.operationError,
            onFormChange = viewModel::updateCreateInventoryForm,
            onWarehouseChange = viewModel::onInventoryWarehouseChanged,
            onCreate = viewModel::createInventory,
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
    val visibleItems = filterNomenclature(state.nomenclature, state.nomenclatureSearch)
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
        OutlinedTextField(
            value = state.nomenclatureSearch,
            onValueChange = viewModel::updateNomenclatureSearch,
            label = { Text("Поиск по коду, названию, описанию или штрихкоду") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        state.operationError?.let { ErrorMessageCard(error = it) }
        if (state.isLoading && state.nomenclature.isEmpty()) {
            LoadingOverlay()
        } else if (state.nomenclature.isEmpty()) {
            EmptyState("Нет номенклатуры. Создайте первую позицию.")
        } else if (visibleItems.isEmpty()) {
            EmptyState("По запросу ничего не найдено.")
        } else {
            NomenclatureGrid(
                items = visibleItems,
                onClick = { viewModel.selectNomenclature(it) },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
        if (state.selectedNomenclature != null) {
            NomenclatureDetailDialog(
                item = state.selectedNomenclature!!,
                details = state.nomenclatureDetails,
                loading = state.nomenclatureDetailsLoading,
                error = state.operationError,
                warehouses = state.warehouses,
                onArchive = { viewModel.archiveNomenclature(state.selectedNomenclature!!) },
                onRetry = { viewModel.selectNomenclature(state.selectedNomenclature!!) },
                onClose = { viewModel.selectNomenclature(null) }
            )
        }
    }
}

@Composable
private fun NomenclatureDetailDialog(
    item: org.example.project.dto.NomenclatureDto,
    details: org.example.project.dto.NomenclatureDetailsDto?,
    loading: Boolean,
    error: org.example.project.dto.ApiError?,
    warehouses: List<WarehouseDto>,
    onArchive: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("${item.code} — ${item.name}") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(text = "Тип: ${item.type.localizedName()}", style = MaterialTheme.typography.bodyMedium)
                Text(text = "Единица измерения: ${item.unit}", style = MaterialTheme.typography.bodyMedium)
                Text(text = "Статус: ${if (item.archived) "Архив" else "Активна"}")
                item.description?.takeIf { it.isNotBlank() }?.let { Text(it) }
                if (item.barcodes.isNotEmpty()) {
                    Text("Штрихкоды", style = MaterialTheme.typography.titleSmall)
                    item.barcodes.forEach { barcode ->
                        Text(
                            text = "${barcode.barcode} · ${barcode.packageType.localizedName()} · ${barcode.quantityInPackage} ${item.unit}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                } else if (details == null) {
                    error?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
                    Button(onClick = onRetry) { Text("Загрузить данные") }
                } else {
                    HorizontalDivider()
                    Text("Остатки", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Физически", style = MaterialTheme.typography.labelMedium)
                            Text("${details.physicalQty} ${item.unit}", style = MaterialTheme.typography.titleSmall)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Резерв", style = MaterialTheme.typography.labelMedium)
                            Text("${details.reservedQty} ${item.unit}", style = MaterialTheme.typography.titleSmall)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Доступно", style = MaterialTheme.typography.labelMedium)
                            Text("${details.availableQty} ${item.unit}", style = MaterialTheme.typography.titleSmall)
                        }
                    }
                    if (details.stock.isEmpty()) {
                        Text("На складах остатка нет.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        details.stock.forEach { stock ->
                            val warehouse = warehouses.find { it.id == stock.warehouseId }
                            Text(
                                text = "${warehouse?.name ?: "Склад ${stock.warehouseId}"} · ${stock.locationCode} · ${stock.locationPurpose.localizedName()}\n" +
                                    "Физ. ${stock.physicalQty}, резерв ${stock.reservedQty}, доступно ${stock.availableQty} ${item.unit}" +
                                    (stock.lotNumber?.let { "\nПартия: $it" } ?: "") +
                                    (stock.serialNumber?.let { "\nСерия: $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    HorizontalDivider()
                    Text("Партии (${details.lots.size})", style = MaterialTheme.typography.titleMedium)
                    details.lots.forEach { lot ->
                        Text(
                            text = lot.lotNumber +
                                (lot.manufactureDate?.let { " · изготовлена $it" } ?: "") +
                                (lot.expiryDate?.let { " · годна до $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (item.lotTracked && details.lots.isEmpty()) {
                        Text("Партии ещё не зарегистрированы.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (item.serialTracked) {
                        Text("Серийные номера (${details.serials.size})", style = MaterialTheme.typography.titleMedium)
                        if (details.serials.isEmpty()) {
                            Text("Серийные номера ещё не зарегистрированы.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            details.serials.forEach { serial ->
                                Text(serial.serialNumber, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    HorizontalDivider()
                    Text("Журнал движений (${details.movements.size})", style = MaterialTheme.typography.titleMedium)
                    if (details.movements.isEmpty()) {
                        Text("Движений пока нет.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        details.movements.forEach { movement ->
                            val direction = when {
                                movement.fromLocationCode != null && movement.toLocationCode != null ->
                                    "${movement.fromLocationCode} → ${movement.toLocationCode}"
                                movement.fromLocationCode != null -> "из ${movement.fromLocationCode}"
                                movement.toLocationCode != null -> "в ${movement.toLocationCode}"
                                else -> ""
                            }
                            Text(
                                text = "${movement.createdAt.toShortDate()} · ${movement.type.localizedName()} · ${movement.quantity} ${item.unit}\n" +
                                    listOf(
                                        direction,
                                        "${movement.documentType} #${movement.documentId}",
                                        movement.createdByName,
                                        movement.note.orEmpty()
                                    ).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!item.archived) {
                Button(onClick = onArchive, enabled = !loading) { Text("Архивировать") }
            }
        },
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
        TabHeader(
            title = "Приёмка",
            onCreate = { viewModel.showDialog(WmsDialog.CreateReceipt) },
            onRefresh = viewModel::loadReceipts,
            createLabel = "Новая приёмка"
        )
        // Основание приёмки не свободная строка: заказ, счёт, накладная/УПД,
        // возврат, перемещение, начальный остаток, прочий документ.
        Text(
            text = "Основание: заказ поставщику, счёт, накладная/УПД, возврат, перемещение, " +
                "начальный остаток или прочий документ. Один документ проводится один раз.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        state.successMessage?.let { message ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = viewModel::dismissSuccess) { Text("Ок") }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SearchableDropdown(
                items = state.warehouses,
                selected = state.warehouses.find { it.id == state.receiptFilters.warehouseId },
                onSelect = { viewModel.updateReceiptWarehouseFilter(it.id) },
                onClear = { viewModel.updateReceiptWarehouseFilter(null) },
                clearText = "Все склады",
                label = "Склад",
                itemText = { "${it.code} — ${it.name}" },
                modifier = Modifier.weight(1f)
            )
            ReceiptStatusDropdown(
                selected = state.receiptFilters.status,
                onSelect = viewModel::updateReceiptStatusFilter,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ReceiptDropdown(
                receipts = state.receipts,
                selected = null,
                onSelect = viewModel::selectReceipt,
                label = "Открыть приёмку",
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = viewModel::loadReceipts) { Text("Обновить") }
        }
        if (state.isLoading && state.receipts.isEmpty()) {
            CircularProgressIndicator()
        } else if (state.receipts.isEmpty()) {
            Text(
                text = "Приёмки по выбранным фильтрам не найдены.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        state.operationError?.let { ErrorMessageCard(error = it) }

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
            TextButton(onClick = { viewModel.loadExpectedReceipts() }) {
                Text("Ожидаемые поставки")
            }
        }

        if (state.expectedReceipts.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Ожидается к приёмке", style = MaterialTheme.typography.titleSmall)
                    state.expectedReceipts.take(20).forEach { line ->
                        Text(
                            text = "${line.orderNumber} · позиция ${line.lineNo}: " +
                                "заказано ${line.orderedQuantity}, поставлено ${line.deliveredQuantity}, " +
                                "принято ${line.acceptedQuantity}, осталось ${line.notAcceptedQuantity}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        if (state.currentReceipt == null) {
            if (state.receipts.isEmpty()) {
                EmptyState("Приёмок ещё не было. Создайте документ с основанием.")
            } else {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        state.receipts.forEach { receipt ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${receipt.number} · ${receipt.sourceType.localizedName()}",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = listOfNotNull(
                                            receipt.sourceDocumentNo,
                                            receipt.purchaseOrderNumber?.let { "заказ $it" },
                                            if (receipt.hasDiscrepancies) "есть расхождения" else null
                                        ).joinToString(" · ").ifBlank { "без документа" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = receipt.status.localizedName(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                TextButton(onClick = { viewModel.openReceipt(receipt.id) }) {
                                    Text("Открыть")
                                }
                            }
                        }
                    }
                }
            }
        } else {
            ReceiptDetailCard(
                receipt = state.currentReceipt,
                linkOrderId = state.receiptLinkOrderId,
                linkDocumentNo = state.receiptLinkDocumentNo,
                linkReason = state.receiptLinkReason,
                onLinkOrderIdChange = viewModel::updateReceiptLinkOrderId,
                onLinkDocumentNoChange = viewModel::updateReceiptLinkDocumentNo,
                onLinkReasonChange = viewModel::updateReceiptLinkReason,
                onLink = viewModel::linkReceipt,
                warehouse = state.warehouses.find { it.id == state.currentReceipt.warehouseId },
                nomenclature = state.nomenclature,
                onPost = viewModel::postReceipt,
                onClose = viewModel::clearCurrentReceipt
            )
        }
    }
}

@Composable
private fun ReceiptDetailCard(
    receipt: ReceiptDto,
    linkOrderId: String,
    linkDocumentNo: String,
    linkReason: String,
    onLinkOrderIdChange: (String) -> Unit,
    onLinkDocumentNoChange: (String) -> Unit,
    onLinkReasonChange: (String) -> Unit,
    onLink: () -> Unit,
    warehouse: WarehouseDto?,
    nomenclature: List<NomenclatureDto>,
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
            Text(
                text = "Основание: ${receipt.sourceType.localizedName()}" +
                    (receipt.sourceDocumentNo?.let { " · документ $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium
            )
            warehouse?.let { Text(text = "Склад: ${it.code} — ${it.name}", style = MaterialTheme.typography.bodyMedium) }
            receipt.sourceRef?.let { Text(text = "Основание: $it", style = MaterialTheme.typography.bodyMedium) }
            receipt.expectedAt?.let { Text(text = "Ожидается: $it", style = MaterialTheme.typography.bodyMedium) }
            receipt.purchaseOrderNumber?.let { orderNumber ->
                Text(text = "Заказ поставщику: $orderNumber", style = MaterialTheme.typography.bodyMedium)
            }
            receipt.purchaseDeliveryNumber?.let { deliveryNumber ->
                Text(text = "Поставка: $deliveryNumber", style = MaterialTheme.typography.bodyMedium)
            }
            Text(text = "Версия: ${receipt.version}", style = MaterialTheme.typography.bodyMedium)
            if (receipt.hasDiscrepancies) {
                Text(
                    text = "По документу есть расхождения «ожидалось / принято»",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            HorizontalDivider()
            Text("Строки:", style = MaterialTheme.typography.labelLarge)
            receipt.lines.forEach { line ->
                val item = nomenclature.find { it.id == line.nomenclatureId }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "№${line.lineNo}: ${item?.let { "${it.code} — ${it.name}" } ?: (line.nomenclatureCode ?: "Номенклатура ${line.nomenclatureId}")}\n" +
                            "Ожидалось ${line.expectedQuantity} ${item?.unit.orEmpty()}, принято ${line.receivedQuantity} ${item?.unit.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    line.discrepancyKind?.let { kind ->
                        Text(
                            text = (if (kind.name == "SHORTAGE") "Недопоставка " else "Излишек ") +
                                "${line.discrepancyQuantity}: ${line.discrepancyReason ?: "без обоснования"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    line.note?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (receipt.links.isNotEmpty()) {
                HorizontalDivider()
                Text("Связи, добавленные позже:", style = MaterialTheme.typography.labelLarge)
                receipt.links.forEach { link ->
                    Text(
                        text = listOfNotNull(
                            link.purchaseOrderId?.let { "заказ #$it" },
                            link.purchaseDeliveryId?.let { "поставка #$it" },
                            link.sourceDocumentNo?.let { "документ $it" },
                            link.reason
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (receipt.status == ReceiptStatus.DRAFT || receipt.status == ReceiptStatus.IN_PROGRESS) {
                    Button(onClick = onPost) { Text("Провести") }
                }
                TextButton(onClick = onClose) { Text("К списку") }
            }
            // Поздняя связь: заказ и накладная могут появиться после приёмки.
            // К заказу поставщику привязываются только приёмки по заказу, счёту
            // и накладной/УПД — начальный остаток, возврат и перемещение к нему
            // не относятся (сервер отвечает 400).
            if (receipt.status == ReceiptStatus.POSTED) {
                HorizontalDivider()
                Text(
                    text = "Связать с документами, появившимися позже (остаток не пересоздаётся)",
                    style = MaterialTheme.typography.labelLarge
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = linkOrderId,
                        onValueChange = onLinkOrderIdChange,
                        label = { Text("ID заказа") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = linkDocumentNo,
                        onValueChange = onLinkDocumentNoChange,
                        label = { Text("Номер накладной") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                OutlinedTextField(
                    value = linkReason,
                    onValueChange = onLinkReasonChange,
                    label = { Text("Обоснование связи") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                Button(onClick = onLink) { Text("Связать") }
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
