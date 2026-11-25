package org.example.project.ui.wms

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import org.example.project.ui.userText
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import org.example.project.ui.theme.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import org.example.project.ui.theme.ErpFilterChip as FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import org.example.project.ui.theme.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import org.example.project.ui.theme.Tab
import androidx.compose.material3.Text
import org.example.project.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.dto.LowStockItemDto
import org.example.project.dto.NomenclatureDto
import org.example.project.dto.ReceiptDto
import org.example.project.dto.ReceiptStatus
import org.example.project.dto.ReceiptSourceType
import org.example.project.dto.StockMovementDto
import org.example.project.dto.StockMovementType
import org.example.project.dto.WarehouseDto
import org.example.project.dto.ReservationDto
import org.example.project.dto.ReservationStatus
import org.example.project.dto.StorageLocationPurpose
import org.example.project.ui.components.SearchableDropdown
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.ErpFormat
import org.example.project.ui.theme.ErpIcon
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.GlassCard
import org.example.project.ui.theme.Gradients
import org.example.project.ui.theme.PageHeader

@Composable
fun WmsScreen(
    viewModel: WmsViewModel,
    modifier: Modifier = Modifier,
    currentUser: org.example.project.dto.UserDto? = null
) {
    val state by viewModel.uiState.collectAsState()
    val names = rememberWmsNames(state.warehouses, state.nomenclature, state.issues)
    // Карточка номенклатуры открывается на месте раздела (NSI-001, D-63).
    val openedNomenclature = state.selectedNomenclature
    if (openedNomenclature != null) {
        org.example.project.ui.wms.card.NomenclatureCardScreen(
            nomenclatureId = openedNomenclature.id,
            currentUser = currentUser,
            onBack = {
                viewModel.selectNomenclature(null)
                viewModel.loadNomenclature()
            },
            onArchived = {
                viewModel.selectNomenclature(null)
                viewModel.loadNomenclature()
            },
            modifier = modifier
        )
        return
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalWmsNames provides names) {

        Column(modifier = modifier.fillMaxSize()) {
            PageHeader(
                title = "Склад",
                glyph = ErpIconGlyph.Warehouse,
                gradient = Gradients.Emerald,
                subtitle = "Склады, ячейки, остатки, приёмка, резервы, перемещения, выдача, возвраты, списания и инвентаризация"
            )
            Spacer(Modifier.height(6.dp))
            WmsTopNav(
                state = state,
                onSelectTab = viewModel::selectTab,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                WmsTabContent(state, viewModel)
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
                onDismiss = viewModel::dismissDialog,
                parentCandidates = state.locations,
                onWarehouseChange = viewModel::onCreateLocationWarehouseChanged
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
                onDismiss = viewModel::dismissDialog,
                reservations = state.reservations
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
                onDismiss = viewModel::dismissDialog,
                issues = state.issues,
                nomenclature = state.nomenclature
            )
            null -> { }
        }
    }
}

@Composable
internal fun TabHeader(
    title: String,
    onCreate: () -> Unit,
    onRefresh: () -> Unit,
    createLabel: String,
    modifier: Modifier = Modifier,
    /** false — создание сейчас невозможно (например, нет документов-оснований). */
    createEnabled: Boolean = true
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCreate, enabled = createEnabled) { Text(createLabel) }
            OutlinedButton(onClick = onRefresh) { Text("Обновить") }
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
            EmptyState(
                "Складов нет. Без склада нельзя завести ячейки и принять товар.",
                actionLabel = "Создать склад",
                onAction = { viewModel.showDialog(WmsDialog.CreateWarehouse) }
            )
        } else {
            WarehousesGrid(
                warehouses = state.warehouses,
                onClick = { warehouse ->
                    viewModel.updateLocationFilterWarehouse(warehouse.id)
                    viewModel.selectTab(WmsTab.LOCATIONS)
                },
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
            EmptyState(
                "У выбранного склада нет ячеек. Ячейка нужна, чтобы разместить принятый товар.",
                actionLabel = "Создать ячейку",
                onAction = { viewModel.showDialog(WmsDialog.CreateLocation) }
            )
        } else {
            LocationsGrid(
                locations = state.locations,
                onClick = { loc ->
                    viewModel.updateStockFilters(state.stockFilters.copy(warehouseId = loc.warehouseId))
                    viewModel.selectTab(WmsTab.STOCK)
                    viewModel.loadStock()
                },
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
            EmptyState(
                "Номенклатуры нет. Позиция нужна для приёмки, резерва и выдачи.",
                actionLabel = "Создать номенклатуру",
                onAction = { viewModel.showDialog(WmsDialog.CreateNomenclature) }
            )
        } else if (visibleItems.isEmpty()) {
            EmptyState(
                "По запросу «${state.nomenclatureSearch.trim()}» ничего не найдено.",
                actionLabel = "Очистить поиск",
                onAction = { viewModel.updateNomenclatureSearch("") }
            )
        } else {
            NomenclatureGrid(
                items = visibleItems,
                onClick = { viewModel.selectNomenclature(it) },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
    }
}

@Composable
private fun StockTab(
    state: WmsUiState,
    viewModel: WmsViewModel
) {
    var filterOnlyAvailable by remember { mutableStateOf(false) }
    var filterQuarantineOnly by remember { mutableStateOf(false) }
    val filteredStock = remember(state.stock, filterOnlyAvailable, filterQuarantineOnly) {
        state.stock.filter { item ->
            val availOk = !filterOnlyAvailable || (item.availableQty.toDoubleOrNull() ?: 0.0) > 0.0
            val quarantineOk = !filterQuarantineOnly || item.locationPurpose in setOf(
                StorageLocationPurpose.QUARANTINE,
                StorageLocationPurpose.DEFECT,
                StorageLocationPurpose.TEMPORARY
            )
            availOk && quarantineOk
        }
    }
    val totalPhysical = remember(filteredStock) { filteredStock.sumOf { it.physicalQty.toDoubleOrNull() ?: 0.0 } }
    val totalReserved = remember(filteredStock) { filteredStock.sumOf { it.reservedQty.toDoubleOrNull() ?: 0.0 } }
    val totalAvailable = remember(filteredStock) { filteredStock.sumOf { it.availableQty.toDoubleOrNull() ?: 0.0 } }
    val quarantineCount = remember(state.stock) {
        state.stock.count { it.locationPurpose == StorageLocationPurpose.QUARANTINE || it.locationPurpose == StorageLocationPurpose.DEFECT }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = "Остатки на складе", style = MaterialTheme.typography.headlineSmall)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    viewModel.selectTab(WmsTab.RESERVATIONS)
                    viewModel.showDialog(WmsDialog.CreateReservation)
                }) { Text("Резерв") }
                OutlinedButton(onClick = {
                    viewModel.selectTab(WmsTab.TRANSFERS)
                    viewModel.showDialog(WmsDialog.CreateTransfer)
                }) { Text("Перемещение") }
                OutlinedButton(onClick = {
                    viewModel.selectTab(WmsTab.ISSUES)
                    viewModel.showDialog(WmsDialog.CreateIssue)
                }) { Text("Выдача") }
            }
        }

        // Сводка по остаткам: плитки «Позиций», «Доступно» и «Карантин» включают быструю фильтрацию.
        // На узком экране плитки идут по две в ряд, а не сжимаются в столбики.
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            maxItemsInEachRow = if (maxWidth < 600.dp) 2 else 5
        ) {
            StockKpiCard("Позиций", "${filteredStock.size}", Brand.Blue, Modifier.weight(1f).clickable {
                filterOnlyAvailable = false
                filterQuarantineOnly = false
            })
            StockKpiCard("Физически", formatQtyShort(totalPhysical), Brand.Indigo, Modifier.weight(1f))
            StockKpiCard("В резерве", formatQtyShort(totalReserved), Brand.Amber, Modifier.weight(1f))
            StockKpiCard("Доступно", formatQtyShort(totalAvailable), Brand.Teal, Modifier.weight(1f).clickable {
                filterOnlyAvailable = !filterOnlyAvailable
            })
            StockKpiCard("Карантин / брак", "$quarantineCount", if (quarantineCount > 0) Brand.Rose else Brand.Graphite, Modifier.weight(1f).clickable {
                filterQuarantineOnly = !filterQuarantineOnly
            })
        }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = filterOnlyAvailable,
                onClick = { filterOnlyAvailable = !filterOnlyAvailable },
                label = { Text("Только доступные к резерву") }
            )
            FilterChip(
                selected = filterQuarantineOnly,
                onClick = { filterQuarantineOnly = !filterQuarantineOnly },
                label = { Text("Карантин / брак ($quarantineCount)") }
            )
            if (state.lowStock.isNotEmpty()) {
                TextButton(onClick = { viewModel.selectTab(WmsTab.MOVEMENTS) }) {
                    Text("Ниже минимума: ${state.lowStock.size}", color = org.example.project.ui.theme.readableAccent(Brand.Rose))
                }
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = viewModel::loadStock) { Text("Обновить", softWrap = false) }
            TextButton(onClick = {
                filterOnlyAvailable = false
                filterQuarantineOnly = false
                viewModel.updateStockFilters(StockFilters())
                viewModel.loadStock()
            }) { Text("Сбросить", softWrap = false) }
        }

        state.operationError?.let { ErrorMessageCard(error = it) }
        if (state.isLoading && state.stock.isEmpty()) {
            LoadingOverlay()
        } else if (filteredStock.isEmpty()) {
            EmptyState("Остатки не найдены. Сбросьте фильтры или воспользуйтесь поиском по таблице.")
        } else {
            StockGrid(
                stock = filteredStock,
                warehouses = state.warehouses,
                nomenclature = state.nomenclature,
                modifier = Modifier.fillMaxWidth().weight(1f),
                onClick = { viewModel.openNomenclature(it.nomenclatureId) }
            )
        }
    }
}

@Composable
private fun StockKpiCard(
    title: String,
    value: String,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    // Нейтральная плитка: цвет появляется только у тревожного значения (карантин / брак).
    val alarming = accent == Brand.Rose
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = if (alarming) accent.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (alarming) accent.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(text = title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = if (alarming) org.example.project.ui.theme.readableAccent(accent) else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** Тот же формат, что в таблицах (запятая, группы разрядов), — иначе «397.5» рядом с «19,5». */
private fun formatQtyShort(value: Double): String = ErpFormat.qty(value)

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
            text = "Основание: заказ поставщику, накладная/УПД, возврат, перемещение, " +
                "начальный остаток или прочий документ. Приёмку по счёту оформляет снабжение. " +
                "Один документ проводится один раз.",
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
                itemText = { "${it.code} · ${it.name}" },
                modifier = Modifier.weight(1f)
            )
            ReceiptStatusDropdown(
                selected = state.receiptFilters.status,
                onSelect = viewModel::updateReceiptStatusFilter,
                modifier = Modifier.weight(1f)
            )
        }
        if (state.isLoading && state.receipts.isEmpty()) {
            CircularProgressIndicator()
        }
        state.operationError?.let { ErrorMessageCard(error = it) }

        TextButton(
            onClick = {
                if (state.expectedReceiptsLoaded) viewModel.hideExpectedReceipts() else viewModel.loadExpectedReceipts()
            }
        ) {
            Text(if (state.expectedReceiptsLoaded) "Скрыть ожидаемые поставки" else "Показать ожидаемые поставки по заказам")
        }

        if (state.expectedReceiptsLoaded) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Ожидается к приёмке по заказам поставщикам", style = MaterialTheme.typography.titleSmall)
                    // Ждём только отправленные поставщику заказы и только то, что склад ещё не принял.
                    val names = LocalWmsNames.current
                    val pending = state.expectedReceipts.filter {
                        it.orderStatus in setOf("SENT", "PARTIALLY_DELIVERED", "DELIVERED") &&
                            (it.notAcceptedQuantity.toDoubleOrNull() ?: 0.0) > 0.0
                    }
                    if (pending.isEmpty()) {
                        Text(
                            "Непринятых позиций по заказам нет.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    pending.take(20).forEach { line ->
                        Text(
                            text = "${line.orderNumber} · ${names.item(line.nomenclatureId)}: " +
                                "заказано ${names.qty(line.orderedQuantity, line.nomenclatureId)}, " +
                                "поставлено ${ErpFormat.qty(line.deliveredQuantity)}, " +
                                "принято ${ErpFormat.qty(line.acceptedQuantity)}, осталось ${ErpFormat.qty(line.notAcceptedQuantity)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (pending.size > 20) {
                        Text(
                            "Показаны первые 20 из ${pending.size}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (state.currentReceipt == null) {
            if (state.receipts.isEmpty()) {
                if (!state.isLoading) {
                    val filtered = state.receiptFilters.warehouseId != null || state.receiptFilters.status != null
                    if (filtered) {
                        EmptyState(
                            "Нет приёмок, подходящих под фильтры.",
                            actionLabel = "Сбросить фильтры",
                            onAction = { viewModel.updateReceiptFilters(ReceiptFilters()) }
                        )
                    } else {
                        EmptyState(
                            "Приёмок ещё не было. Приёмка ставит товар на остаток по накладной, счёту или заказу.",
                            actionLabel = "Новая приёмка",
                            onAction = { viewModel.showDialog(WmsDialog.CreateReceipt) }
                        )
                    }
                }
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
                        val pendingFromProcurement = state.receipts.filter {
                            it.supplierInvoiceId != null &&
                                (it.status == ReceiptStatus.DRAFT || it.status == ReceiptStatus.IN_PROGRESS)
                        }
                        if (pendingFromProcurement.isNotEmpty()) {
                            Text(
                                "Ожидается к приёмке по счетам от снабжения: ${pendingFromProcurement.size}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        (pendingFromProcurement + (state.receipts - pendingFromProcurement.toSet())).forEach { receipt ->
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
                                            receipt.supplierInvoiceId?.let { "по счёту от снабжения" },
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
                                    fontWeight = FontWeight.SemiBold,
                                    color = when (receipt.status) {
                                        ReceiptStatus.DRAFT -> org.example.project.ui.theme.readableAccent(Brand.Amber)
                                        ReceiptStatus.POSTED -> org.example.project.ui.theme.readableAccent(Brand.Teal)
                                        ReceiptStatus.IN_PROGRESS -> org.example.project.ui.theme.readableAccent(Brand.Blue)
                                        ReceiptStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
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
                onClose = viewModel::clearCurrentReceipt,
                onAccept = viewModel::acceptReceiptLines
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
    onAccept: (List<org.example.project.dto.AcceptReceiptLineInput>) -> Unit = {},
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
            warehouse?.let { Text(text = "Склад: ${it.code} · ${it.name}", style = MaterialTheme.typography.bodyMedium) }
            receipt.sourceRef?.let { Text(text = "Основание: $it", style = MaterialTheme.typography.bodyMedium) }
            receipt.expectedAt?.let { Text(text = "Ожидается: ${ErpFormat.dateRu(it)}", style = MaterialTheme.typography.bodyMedium) }
            receipt.purchaseOrderNumber?.let { orderNumber ->
                Text(text = "Заказ поставщику: $orderNumber", style = MaterialTheme.typography.bodyMedium)
            }
            receipt.purchaseDeliveryNumber?.let { deliveryNumber ->
                Text(text = "Поставка: $deliveryNumber", style = MaterialTheme.typography.bodyMedium)
            }
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
                // Пока приёмка не проведена, «принято» равно ожидаемому по умолчанию — это не факт.
                // Факт показываем, когда документ проведён или кладовщик уже ввёл иное количество.
                val factEntered = (receipt.status != ReceiptStatus.DRAFT && receipt.status != ReceiptStatus.IN_PROGRESS) ||
                    line.receivedQuantity.toDoubleOrNull() != line.expectedQuantity.toDoubleOrNull()
                val unitSuffix = item?.unit?.let { " $it" }.orEmpty()
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "№${line.lineNo}: ${item?.let { "${it.code} · ${it.name}" } ?: (line.nomenclatureCode ?: "Номенклатура ${line.nomenclatureId}")}\n" +
                            if (factEntered) {
                                "Ожидалось ${ErpFormat.qty(line.expectedQuantity)}$unitSuffix, принято ${ErpFormat.qty(line.receivedQuantity)}$unitSuffix"
                            } else {
                                "Ожидается ${ErpFormat.qty(line.expectedQuantity)}$unitSuffix"
                            },
                        style = MaterialTheme.typography.bodySmall
                    )
                    line.discrepancyKind?.let { kind ->
                        Text(
                            text = (if (kind.name == "SHORTAGE") "Недопоставка " else "Излишек ") +
                                "${ErpFormat.qty(line.discrepancyQuantity)}: ${line.discrepancyReason ?: "без обоснования"}",
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
                            link.purchaseOrderNumber?.let { "заказ $it" } ?: link.purchaseOrderId?.let { "заказ #$it" },
                            link.purchaseDeliveryNumber?.let { "поставка $it" } ?: link.purchaseDeliveryId?.let { "поставка #$it" },
                            link.sourceDocumentNo?.let { "документ $it" },
                            link.reason
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            var acceptanceDirty by remember(receipt.id, receipt.version) { mutableStateOf(false) }
            if (receipt.status == ReceiptStatus.DRAFT || receipt.status == ReceiptStatus.IN_PROGRESS) {
                ReceiptAcceptance(
                    receipt = receipt,
                    nomenclature = nomenclature,
                    onSave = onAccept,
                    onDirtyChange = { acceptanceDirty = it }
                )
            }
            val wmsRepoForQc: org.example.project.data.WmsRepository = koinInject()
            val qcScope = rememberCoroutineScope()
            var showQcDialog by remember(receipt.id) { mutableStateOf(false) }
            var qcInfo by remember(receipt.id) { mutableStateOf<org.example.project.dto.ReceiptQcDto?>(null) }
            var qcStatusMsg by remember(receipt.id) { mutableStateOf<String?>(null) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (receipt.status == ReceiptStatus.DRAFT || receipt.status == ReceiptStatus.IN_PROGRESS) {
                    Button(
                        onClick = onPost,
                        // Несохранённый факт приёмки проводить нельзя: сервер проведёт старые значения.
                        enabled = receipt.lines.none { it.locationId == null } && !acceptanceDirty
                    ) { Text("Провести") }
                }
                if (receipt.status == ReceiptStatus.POSTED) {
                    Button(onClick = {
                        showQcDialog = true
                        qcScope.launch {
                            when (val res = wmsRepoForQc.getReceiptQc(receipt.id)) {
                                is org.example.project.data.ApiResult.Success -> qcInfo = res.data
                                is org.example.project.data.ApiResult.Error -> qcStatusMsg = res.error.userText()
                            }
                        }
                    }) { Text("Контроль ОТК") }
                }
                TextButton(onClick = onClose) { Text("К списку") }
            }
            if ((receipt.status == ReceiptStatus.DRAFT || receipt.status == ReceiptStatus.IN_PROGRESS) &&
                receipt.lines.any { it.locationId == null }
            ) {
                Text(
                    "Провести можно после сохранения факта приёмки с ячейкой для каждой строки.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (showQcDialog) {
                var qcComment by remember { mutableStateOf("") }
                org.example.project.ui.theme.ErpDialog(
                    title = "Входной контроль ОТК (${receipt.number})",
                    glyph = ErpIconGlyph.CheckCircle,
                    gradient = Gradients.Emerald,
                    width = 560.dp,
                    confirmText = "Принять всё (ОТК)",
                    // Повторный ОТК сервер отклоняет — после проверки кнопка неактивна.
                    confirmEnabled = qcInfo?.status.let { it == null || it == org.example.project.dto.ReceiptQcStatus.PENDING },
                    onConfirm = {
                        qcScope.launch {
                            val lines = receipt.lines.map { line ->
                                org.example.project.dto.ReceiptQcLineInput(
                                    receiptLineId = line.id,
                                    acceptedQty = line.receivedQuantity
                                )
                            }
                            val res = wmsRepoForQc.inspectReceiptQc(
                                receipt.id,
                                org.example.project.dto.InspectReceiptQcRequest(
                                    comment = qcComment.ifBlank { null },
                                    lines = lines
                                )
                            )
                            when (res) {
                                is org.example.project.data.ApiResult.Success -> {
                                    qcInfo = res.data
                                    qcStatusMsg = "ОТК проведён: ${res.data.status.qcLabel()}"
                                }
                                is org.example.project.data.ApiResult.Error -> qcStatusMsg = res.error.userText()
                            }
                        }
                    },
                    confirmGradient = Gradients.Emerald,
                    dismissText = "Закрыть",
                    onDismiss = { showQcDialog = false }
                ) {
                    qcInfo?.let { qc ->
                        Text("Статус ОТК: ${qc.status.qcLabel()}")
                    }
                    qcStatusMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    OutlinedTextField(
                        value = qcComment,
                        onValueChange = { qcComment = it },
                        label = { Text("Комментарий ОТК") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            // Поздняя связь: заказ и накладная могут появиться после приёмки.
            // К заказу поставщику привязываются только приёмки по заказу, счёту
            // и накладной/УПД — начальный остаток, возврат и перемещение к нему
            // не относятся (сервер отвечает 400).
            // Сервер пишет связь один раз: заказ — если его ещё нет, номер документа — если
            // он не был указан. Когда привязывать нечего, блок не показывается.
            val orderLinkAllowed = receipt.purchaseOrderId == null && receipt.sourceType in setOf(
                ReceiptSourceType.PURCHASE_ORDER,
                ReceiptSourceType.INVOICE,
                ReceiptSourceType.DELIVERY_NOTE
            )
            val documentLinkAllowed = receipt.sourceDocumentNo.isNullOrBlank()
            if (receipt.status == ReceiptStatus.POSTED && (orderLinkAllowed || documentLinkAllowed)) {
                HorizontalDivider()
                Text(
                    text = "Связать с документами, появившимися позже (остаток не пересоздаётся)",
                    style = MaterialTheme.typography.labelLarge
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (orderLinkAllowed) {
                        PurchaseOrderPicker(
                            value = linkOrderId.trim().toLongOrNull(),
                            onChange = { onLinkOrderIdChange(it?.toString().orEmpty()) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (documentLinkAllowed) {
                        OutlinedTextField(
                            value = linkDocumentNo,
                            onValueChange = onLinkDocumentNoChange,
                            label = { Text("Номер накладной / УПД") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                }
                OutlinedTextField(
                    value = linkReason,
                    onValueChange = onLinkReasonChange,
                    label = { Text("Обоснование связи *") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                val hasTarget = (orderLinkAllowed && linkOrderId.isNotBlank()) ||
                    (documentLinkAllowed && linkDocumentNo.isNotBlank())
                val canLink = hasTarget && linkReason.isNotBlank()
                if (!canLink) {
                    Text(
                        text = when {
                            orderLinkAllowed && documentLinkAllowed ->
                                "Укажите заказ поставщику или номер накладной и обоснование связи."
                            orderLinkAllowed -> "Укажите заказ поставщику и обоснование связи."
                            else -> "Укажите номер документа и обоснование связи."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(onClick = onLink, enabled = canLink) { Text("Связать") }
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
                placeholder = "Все склады",
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
            OutlinedButton(
                onClick = { viewModel.updateReservationFilters(ReservationFilters()) },
                enabled = state.reservationFilters != ReservationFilters()
            ) { Text("Сбросить") }
        }
        state.operationError?.let { ErrorMessageCard(error = it) }
        if (state.isLoading && state.reservations.isEmpty()) {
            LoadingOverlay()
        } else if (state.reservations.isEmpty()) {
            if (state.reservationFilters != ReservationFilters()) {
                EmptyState(
                    "Нет резервов, подходящих под фильтры.",
                    actionLabel = "Сбросить фильтры",
                    onAction = { viewModel.updateReservationFilters(ReservationFilters()) }
                )
            } else {
                EmptyState(
                    "Резервов нет. Резерв закрепляет остаток за проектом, заказом или задачей.",
                    actionLabel = "Создать резерв",
                    onAction = { viewModel.showDialog(WmsDialog.CreateReservation) }
                )
            }
        } else {
            ReservationsGrid(
                reservations = state.reservations,
                warehouses = state.warehouses,
                nomenclature = state.nomenclature,
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
                onClose = viewModel::clearCurrentReservation,
                nomenclature = state.nomenclature.find { it.id == state.currentReservation!!.nomenclatureId },
                warehouse = state.warehouses.find { it.id == state.currentReservation!!.warehouseId },
                error = state.operationError,
                busy = state.isLoading
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
    onClose: () -> Unit,
    nomenclature: NomenclatureDto? = null,
    warehouse: WarehouseDto? = null,
    error: org.example.project.dto.ApiError? = null,
    busy: Boolean = false
) {
    val unit = nomenclature?.unit?.let { " $it" }.orEmpty()
    val names = LocalWmsNames.current
    org.example.project.ui.theme.ErpDialog(
        title = "Резерв ${reservation.number}",
        glyph = ErpIconGlyph.Clock,
        gradient = Gradients.Emerald,
        width = 620.dp,
        confirmText = if (reservation.status == ReservationStatus.ACTIVE) "Снять резерв" else null,
        onConfirm = if (reservation.status == ReservationStatus.ACTIVE) onRelease else null,
        confirmEnabled = !busy,
        confirmGradient = Gradients.Sunset,
        dismissText = "Закрыть",
        onDismiss = onClose
    ) {
        Text(
            text = "Позиция: " + (nomenclature?.let { "${it.code} · ${it.name}" } ?: "#${reservation.nomenclatureId}"),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = "Склад: " + (warehouse?.let { "${it.code} · ${it.name}" } ?: "#${reservation.warehouseId}"),
            style = MaterialTheme.typography.bodyMedium
        )
        reservation.locationId?.let {
            Text(text = "Ячейка: ${names.location(it)}", style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            text = "Основание: ${names.basis(reservation.reasonType, reservation.projectId, reservation.taskId)}" +
                (reservation.reasonNote?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(text = "Количество: ${ErpFormat.qty(reservation.quantity)}$unit", style = MaterialTheme.typography.bodyMedium)
        Text(text = "Выдано по резерву: ${ErpFormat.qty(reservation.issuedQuantity)}$unit", style = MaterialTheme.typography.bodyMedium)
        Text(text = "Статус: ${reservation.status.localizedName()}", style = MaterialTheme.typography.bodyMedium)
        reservation.expiresAt?.let {
            Text(text = "Действует до: ${ErpFormat.dateRu(it)}", style = MaterialTheme.typography.bodyMedium)
        }
        reservation.createdAt?.let {
            Text(text = "Создан: ${ErpFormat.dateTimeRu(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        error?.let { ErrorMessageCard(error = it) }
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
}

@Composable
internal fun EmptyState(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        GlassCard(modifier = Modifier.padding(24.dp)) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                org.example.project.ui.theme.ErpIcon(
                    glyph = ErpIconGlyph.Warehouse,
                    tint = Brand.Teal,
                    modifier = Modifier.size(32.dp)
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                if (actionLabel != null && onAction != null) {
                    OutlinedButton(onClick = onAction) { Text(actionLabel) }
                }
            }
        }
    }
}

@Composable
private fun MovementsAndReportsTab(state: WmsUiState, viewModel: WmsViewModel) {
    var selectedType by remember { mutableStateOf<StockMovementType?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var lowStockQuery by remember { mutableStateOf("") }

    val nomById = remember(state.nomenclature) { state.nomenclature.associateBy { it.id } }

    val inboundCount = remember(state.movements) {
        state.movements.count {
            it.type == StockMovementType.RECEIPT ||
                it.type == StockMovementType.RETURN ||
                it.type == StockMovementType.ADJUSTMENT_IN
        }
    }
    val outboundCount = remember(state.movements) {
        state.movements.count {
            it.type == StockMovementType.ISSUE ||
                it.type == StockMovementType.WRITE_OFF ||
                it.type == StockMovementType.ADJUSTMENT_OUT
        }
    }
    val transferCount = remember(state.movements) {
        state.movements.count { it.type == StockMovementType.TRANSFER }
    }

    val filteredMovements = remember(state.movements, selectedType, searchQuery, nomById) {
        val q = searchQuery.trim().lowercase()
        state.movements.filter { m ->
            val typeOk = selectedType == null || m.type == selectedType
            if (!typeOk) return@filter false
            if (q.isEmpty()) return@filter true
            val nom = nomById[m.nomenclatureId]
            (nom?.name?.lowercase()?.contains(q) == true) ||
                (nom?.code?.lowercase()?.contains(q) == true) ||
                (m.documentNumber?.lowercase()?.contains(q) == true) ||
                movementDocTypeLabel(m.documentType).lowercase().contains(q) ||
                (m.lotNumber?.lowercase()?.contains(q) == true) ||
                (m.serialNumber?.lowercase()?.contains(q) == true) ||
                (m.fromLocationCode?.lowercase()?.contains(q) == true) ||
                (m.toLocationCode?.lowercase()?.contains(q) == true) ||
                m.createdByName.lowercase().contains(q) ||
                (m.note?.lowercase()?.contains(q) == true)
        }
    }

    val filteredLowStock = remember(state.lowStock, lowStockQuery) {
        val q = lowStockQuery.trim().lowercase()
        if (q.isEmpty()) state.lowStock
        else state.lowStock.filter {
            it.code.lowercase().contains(q) || it.name.lowercase().contains(q)
        }
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.operationError?.let { ErrorMessageCard(it) }

        // Верхняя строка KPI
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MovementKpiTile(
                title = "Всего движений",
                value = "${state.movements.size}",
                subtitle = "Перемещений: $transferCount",
                accent = Brand.Indigo,
                glyph = ErpIconGlyph.TrendUp,
                modifier = Modifier.weight(1f)
            )
            MovementKpiTile(
                title = "Приход и возврат",
                value = "$inboundCount",
                subtitle = "Пополнения остатков",
                accent = Brand.Teal,
                glyph = ErpIconGlyph.CheckCircle,
                modifier = Modifier.weight(1f)
            )
            MovementKpiTile(
                title = "Расход и списание",
                value = "$outboundCount",
                subtitle = "Выдачи и списания",
                accent = Brand.Amber,
                glyph = ErpIconGlyph.Layers,
                modifier = Modifier.weight(1f)
            )
            MovementKpiTile(
                title = "Дефицит позиций",
                value = "${state.lowStock.size}",
                subtitle = if (state.lowStock.isEmpty()) "Запас в норме" else "Ниже минимума",
                accent = if (state.lowStock.isEmpty()) Brand.Mint else Brand.Rose,
                glyph = if (state.lowStock.isEmpty()) ErpIconGlyph.CheckCircle else ErpIconGlyph.Alert,
                modifier = Modifier.weight(1f)
            )
        }

        // Панель поиска по штрихкоду / артикулу
        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 12.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.barcodeQuery,
                    onValueChange = viewModel::updateBarcodeQuery,
                    label = { Text("Определить номенклатуру по штрихкоду или артикулу") },
                    leadingIcon = {
                        ErpIcon(
                            glyph = ErpIconGlyph.Search,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = viewModel::lookupBarcode) { Text("Определить") }
                OutlinedButton(onClick = viewModel::loadMovementsAndLowStock) {
                    ErpIcon(
                        glyph = ErpIconGlyph.Refresh,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Обновить")
                }
            }
            state.barcodeResult?.let { nom ->
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Brand.Teal.copy(alpha = 0.10f),
                    modifier = Modifier.fillMaxWidth().border(1.dp, Brand.Teal.copy(alpha = 0.30f), RoundedCornerShape(6.dp))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ErpIcon(ErpIconGlyph.CheckCircle, tint = Brand.Teal, modifier = Modifier.size(16.dp))
                        Text(
                            text = "${nom.code} · ${nom.name}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        // Результат поиска по штрихкоду сразу отбирает движения этой позиции в журнале.
                        TextButton(onClick = { searchQuery = nom.code }) { Text("Показать в журнале") }
                        Surface(shape = RoundedCornerShape(999.dp), color = Brand.Teal.copy(alpha = 0.16f)) {
                            Text(
                                text = nom.unit,
                                style = MaterialTheme.typography.labelSmall,
                                color = org.example.project.ui.theme.readableAccent(Brand.Teal),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        // Основная рабочая область: Журнал движений + Дефицит
        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassCard(modifier = Modifier.weight(1.35f).fillMaxHeight(), contentPadding = 14.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ErpIcon(ErpIconGlyph.TrendUp, tint = Brand.Indigo, modifier = Modifier.size(18.dp))
                    Text(
                        text = "Журнал движений",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Surface(shape = RoundedCornerShape(999.dp), color = Brand.Indigo.copy(alpha = 0.14f)) {
                        Text(
                            text = "${filteredMovements.size}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = org.example.project.ui.theme.readableAccent(Brand.Indigo),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (state.movementsLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Фильтры по типу и быстрый поиск
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Фильтр журнала", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    placeholder = { Text("номенклатура, ячейка, номер документа, партия", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = selectedType == null,
                        onClick = { selectedType = null },
                        label = { Text("Все (${state.movements.size})") }
                    )
                    StockMovementType.entries.forEach { t ->
                        val (label, _, _) = movementTypeMeta(t)
                        val count = state.movements.count { it.type == t }
                        if (count > 0 || selectedType == t) {
                            FilterChip(
                                selected = selectedType == t,
                                onClick = { selectedType = if (selectedType == t) null else t },
                                label = { Text("$label ($count)") }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                Spacer(Modifier.height(10.dp))

                if (filteredMovements.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ErpIcon(ErpIconGlyph.TrendUp, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
                            Text(
                                text = if (state.movements.isEmpty()) "Движений пока нет." else "По выбранным фильтрам движений не найдено.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    org.example.project.ui.tables.PaginatedCardList(
                        items = filteredMovements,
                        modifier = Modifier.fillMaxSize()
                    ) { _, m ->
                        MovementLogCard(m = m, nomenclature = nomById[m.nomenclatureId])
                    }
                }
            }

            GlassCard(modifier = Modifier.weight(1f).fillMaxHeight(), contentPadding = 14.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val alertColor = if (state.lowStock.isEmpty()) Brand.Teal else Brand.Rose
                    ErpIcon(
                        glyph = if (state.lowStock.isEmpty()) ErpIconGlyph.CheckCircle else ErpIconGlyph.Alert,
                        tint = alertColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Ниже минимума / дефицит",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Surface(shape = RoundedCornerShape(999.dp), color = alertColor.copy(alpha = 0.14f)) {
                        Text(
                            text = "${filteredLowStock.size}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = alertColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = lowStockQuery,
                    onValueChange = { lowStockQuery = it },
                    placeholder = { Text("Поиск по коду или названию...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                Spacer(Modifier.height(10.dp))

                if (filteredLowStock.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ErpIcon(ErpIconGlyph.CheckCircle, tint = Brand.Teal, modifier = Modifier.size(28.dp))
                            Text(
                                text = if (state.lowStock.isEmpty()) "Дефицита не выявлено." else "Позиции по фильтру не найдены.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    org.example.project.ui.tables.PaginatedCardList(
                        items = filteredLowStock,
                        modifier = Modifier.fillMaxSize()
                    ) { _, item ->
                        LowStockItemCard(item = item)
                    }
                }
            }
        }
    }
}

@Composable
private fun MovementKpiTile(
    title: String,
    value: String,
    subtitle: String,
    accent: Color,
    glyph: ErpIconGlyph,
    modifier: Modifier = Modifier
) {
    GlassCard(modifier = modifier, contentPadding = 12.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                ErpIcon(glyph = glyph, tint = accent, modifier = Modifier.size(18.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun MovementLogCard(m: StockMovementDto, nomenclature: NomenclatureDto?) {
    val scheme = MaterialTheme.colorScheme
    val (typeLabel, typeColor, signPrefix) = movementTypeMeta(m.type)
    val docLabel = movementDocTypeLabel(m.documentType)
    // Старые записи несут служебное примечание вида «Receipt RCP-2026-00006»: номер документа
    // переносим в подпись, а английский дубль не показываем.
    val serviceNote = m.note?.trim()?.let { Regex("^(Receipt|Issue|Transfer|Return|Write[- ]?off|Inventory|Initial stock) +([^ ]+)$", RegexOption.IGNORE_CASE).find(it) }
    val docNumber = m.documentNumber ?: serviceNote?.groupValues?.get(2)
    val visibleNote = if (serviceNote != null) null else m.note
    val shape = RoundedCornerShape(8.dp)

    Surface(
        shape = shape,
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, scheme.outlineVariant.copy(alpha = 0.50f), shape)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(shape = RoundedCornerShape(999.dp), color = typeColor.copy(alpha = 0.16f)) {
                    Text(
                        text = typeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = typeColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = nomenclature?.name ?: "Номенклатура #${m.nomenclatureId}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (nomenclature != null) {
                        Text(
                            text = nomenclature.code,
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                }
                Surface(shape = RoundedCornerShape(8.dp), color = typeColor.copy(alpha = 0.12f)) {
                    val unitSuffix = nomenclature?.unit?.let { " $it" } ?: ""
                    Text(
                        text = "$signPrefix${ErpFormat.qty(m.quantity)}$unitSuffix" +
                            if (m.type == StockMovementType.TRANSFER) " · перемещение" else "",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = typeColor,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                }
            }

            // Маршрут, документ, партия и автор
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // «→» рисуем, только когда есть и откуда, и куда; иначе просто подпись ячейки.
                val routeText = when {
                    !m.fromLocationCode.isNullOrBlank() && !m.toLocationCode.isNullOrBlank() ->
                        "Ячейка: ${m.fromLocationCode} → ${m.toLocationCode}"
                    !m.toLocationCode.isNullOrBlank() -> "В ячейку: ${m.toLocationCode}"
                    !m.fromLocationCode.isNullOrBlank() -> "Из ячейки: ${m.fromLocationCode}"
                    else -> null
                }
                if (routeText != null) {
                    Text(
                        text = routeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurface
                    )
                }
                Text(
                    text = "$docLabel ${docNumber ?: "#${m.documentId}"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
                )
                if (!m.lotNumber.isNullOrBlank()) {
                    Text(
                        text = "Партия: ${m.lotNumber}",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant
                    )
                }
                if (!m.serialNumber.isNullOrBlank()) {
                    Text(
                        text = "S/N: ${m.serialNumber}",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                val dateText = m.createdAt?.let { ErpFormat.dateTimeRu(it) }?.takeIf { it != "—" } ?: ""
                Text(
                    text = if (dateText.isNotEmpty()) "${m.createdByName} • $dateText" else m.createdByName,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
                )
            }

            if (!visibleNote.isNullOrBlank()) {
                Text(
                    text = visibleNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun LowStockItemCard(item: LowStockItemDto) {
    val scheme = MaterialTheme.colorScheme
    val avail = item.availableQty.toDoubleOrNull() ?: 0.0
    val minS = item.minStock.toDoubleOrNull() ?: 0.0
    val ratio = if (minS > 0.0) (avail / minS).toFloat().coerceIn(0f, 1f) else 0f
    val barColor = if (ratio < 0.35f) Brand.Rose else Brand.Amber
    val shape = RoundedCornerShape(8.dp)

    Surface(
        shape = shape,
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, barColor.copy(alpha = 0.35f), shape)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.code,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant
                    )
                }
                Surface(shape = RoundedCornerShape(999.dp), color = Brand.Rose.copy(alpha = 0.15f)) {
                    Text(
                        text = "Дефицит ${ErpFormat.qty(item.deficitQty)} ${item.unit}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Brand.Rose,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            // Полоса обеспеченности минимального запаса
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(scheme.onSurface.copy(alpha = 0.08f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(ratio.coerceAtLeast(0.04f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(999.dp))
                        .background(barColor)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Доступно: ${ErpFormat.qty(item.availableQty)} ${item.unit}",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurface
                )
                Text(
                    text = "Мин.: ${ErpFormat.qty(item.minStock)} • Страх.: ${ErpFormat.qty(item.safetyStock)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun movementTypeMeta(type: StockMovementType): Triple<String, Color, String> = when (type) {
    StockMovementType.RECEIPT -> Triple("Приход", Brand.Teal, type.signPrefix())
    StockMovementType.RETURN -> Triple("Возврат", Brand.Cyan, type.signPrefix())
    StockMovementType.ADJUSTMENT_IN -> Triple("Корр. +", Brand.Mint, type.signPrefix())
    StockMovementType.ISSUE -> Triple("Выдача", Brand.Amber, type.signPrefix())
    StockMovementType.WRITE_OFF -> Triple("Списание", Brand.Rose, type.signPrefix())
    StockMovementType.ADJUSTMENT_OUT -> Triple("Корр. \u2212", Brand.Orange, type.signPrefix())
    StockMovementType.TRANSFER -> Triple("Перемещение", Brand.Indigo, type.signPrefix())
}

private fun movementDocTypeLabel(docType: String): String = when (docType.uppercase()) {
    "INITIAL_STOCK" -> "Начальные остатки"
    "RECEIPT" -> "Приёмка"
    "ISSUE" -> "Выдача"
    "TRANSFER" -> "Перемещение"
    "RETURN" -> "Возврат"
    "WRITE_OFF" -> "Списание"
    "INVENTORY" -> "Инвентаризация"
    else -> docType
}

@Composable
private fun WmsTabContent(state: WmsUiState, viewModel: WmsViewModel) {
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
        WmsTab.MOVEMENTS -> MovementsAndReportsTab(state, viewModel)
    }
}

private fun wmsTabCount(tab: WmsTab, state: WmsUiState): Int = when (tab) {
    WmsTab.WAREHOUSES -> state.warehouses.size
    // Ячейки загружены только для одного склада из фильтра — общий счётчик был бы обманчивым.
    WmsTab.LOCATIONS -> 0
    WmsTab.NOMENCLATURE -> state.nomenclature.size
    WmsTab.STOCK -> state.stock.size
    WmsTab.RECEIPTS -> state.receipts.size
    WmsTab.RESERVATIONS -> state.reservations.size
    WmsTab.TRANSFERS -> state.transfers.size
    WmsTab.ISSUES -> state.issues.size
    WmsTab.RETURNS -> state.returns.size
    WmsTab.WRITE_OFFS -> state.writeOffs.size
    WmsTab.INVENTORIES -> state.inventories.size
    // Журнал считает записи движений; дефицит показывается на самой странице журнала и в остатках.
    WmsTab.MOVEMENTS -> state.movements.size
}

private data class WmsNavEntry(
    val tab: WmsTab,
    val glyph: ErpIconGlyph,
    val accent: Color
)

@Composable
private fun WmsTopNav(
    state: WmsUiState,
    onSelectTab: (WmsTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryItems = remember {
        listOf(
            WmsNavEntry(WmsTab.STOCK, ErpIconGlyph.CheckCircle, Brand.Teal),
            WmsNavEntry(WmsTab.RECEIPTS, ErpIconGlyph.Plus, Brand.Blue),
            WmsNavEntry(WmsTab.ISSUES, ErpIconGlyph.ChevronRight, Brand.Teal),
            WmsNavEntry(WmsTab.MOVEMENTS, ErpIconGlyph.TrendUp, Brand.Indigo),
            WmsNavEntry(WmsTab.NOMENCLATURE, ErpIconGlyph.Layers, Brand.Amber)
        )
    }
    val operationsItems = remember {
        listOf(
            WmsNavEntry(WmsTab.RESERVATIONS, ErpIconGlyph.Clock, Brand.Amber),
            WmsNavEntry(WmsTab.TRANSFERS, ErpIconGlyph.Send, Brand.Indigo),
            WmsNavEntry(WmsTab.RETURNS, ErpIconGlyph.Refresh, Brand.Violet),
            // Значок «Закрыть» вместо предупреждения: списание — обычная операция, а не тревога.
            WmsNavEntry(WmsTab.WRITE_OFFS, ErpIconGlyph.Close, Brand.Rose),
            WmsNavEntry(WmsTab.INVENTORIES, ErpIconGlyph.Documents, Brand.Blue)
        )
    }
    val dictionaryItems = remember {
        listOf(
            WmsNavEntry(WmsTab.WAREHOUSES, ErpIconGlyph.Warehouse, Brand.Teal),
            WmsNavEntry(WmsTab.LOCATIONS, ErpIconGlyph.Grid, Brand.Blue)
        )
    }
    var opsExpanded by remember { mutableStateOf(false) }
    var dictExpanded by remember { mutableStateOf(false) }

    val activeOperation = operationsItems.firstOrNull { it.tab == state.selectedTab }
    val activeDict = dictionaryItems.firstOrNull { it.tab == state.selectedTab }

    Surface(
        modifier = modifier,

        shape = RoundedCornerShape(8.dp),

        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            primaryItems.forEach { entry ->
                val count = wmsTabCount(entry.tab, state)
                val selected = state.selectedTab == entry.tab
                val label = if (count > 0) "${entry.tab.title} ($count)" else entry.tab.title
                org.example.project.ui.theme.SoftButton(
                    text = label,
                    onClick = { onSelectTab(entry.tab) },
                    tint = if (selected) entry.accent else Brand.Slate,
                    leadingIcon = entry.glyph
                )
            }

            Box {
                org.example.project.ui.theme.SoftButton(
                    text = activeOperation?.let { "Операции: ${it.tab.title} ▾" } ?: "Операции ▾",
                    onClick = { opsExpanded = true },
                    tint = activeOperation?.accent ?: Brand.Slate,
                    leadingIcon = activeOperation?.glyph ?: ErpIconGlyph.Clock
                )
                DropdownMenu(expanded = opsExpanded, onDismissRequest = { opsExpanded = false }) {
                    operationsItems.forEach { entry ->
                        val count = wmsTabCount(entry.tab, state)
                        val label = if (count > 0) "${entry.tab.title} ($count)" else entry.tab.title
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                opsExpanded = false
                                onSelectTab(entry.tab)
                            }
                        )
                    }
                }
            }

            Box {
                org.example.project.ui.theme.SoftButton(
                    text = activeDict?.let { "Справочники: ${it.tab.title} ▾" } ?: "Справочники ▾",
                    onClick = { dictExpanded = true },
                    tint = activeDict?.accent ?: Brand.Slate,
                    leadingIcon = activeDict?.glyph ?: ErpIconGlyph.Warehouse
                )
                DropdownMenu(expanded = dictExpanded, onDismissRequest = { dictExpanded = false }) {
                    dictionaryItems.forEach { entry ->
                        val count = wmsTabCount(entry.tab, state)
                        val label = if (count > 0) "${entry.tab.title} ($count)" else entry.tab.title
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                dictExpanded = false
                                onSelectTab(entry.tab)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WmsVerticalNav(
    state: WmsUiState,
    onSelectTab: (WmsTab) -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(modifier = modifier, contentPadding = 12.dp) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            WmsNavSection(
                title = "Основное",
                items = listOf(
                    WmsNavEntry(WmsTab.STOCK, ErpIconGlyph.CheckCircle, Brand.Teal),
                    WmsNavEntry(WmsTab.RECEIPTS, ErpIconGlyph.Plus, Brand.Blue),
                    WmsNavEntry(WmsTab.ISSUES, ErpIconGlyph.ChevronRight, Brand.Teal),
                    WmsNavEntry(WmsTab.MOVEMENTS, ErpIconGlyph.TrendUp, Brand.Indigo),
                    WmsNavEntry(WmsTab.NOMENCLATURE, ErpIconGlyph.Layers, Brand.Amber)
                ),
                selectedTab = state.selectedTab,
                state = state,
                onSelect = onSelectTab
            )

            // Раньше раздел был свёрнут и раскрывался по клику; раскрытие не срабатывало,
            // а семь операций оставались скрытыми. Места в панели достаточно — показываем всегда.
            WmsNavSection(
                title = "Прочие операции",
                items = listOf(
                    WmsNavEntry(WmsTab.RESERVATIONS, ErpIconGlyph.Clock, Brand.Amber),
                    WmsNavEntry(WmsTab.TRANSFERS, ErpIconGlyph.Send, Brand.Indigo),
                    WmsNavEntry(WmsTab.RETURNS, ErpIconGlyph.Refresh, Brand.Violet),
                    WmsNavEntry(WmsTab.WRITE_OFFS, ErpIconGlyph.Alert, Brand.Rose),
                    WmsNavEntry(WmsTab.INVENTORIES, ErpIconGlyph.Documents, Brand.Blue)
                ),
                selectedTab = state.selectedTab,
                state = state,
                onSelect = onSelectTab
            )
            WmsNavSection(
                title = "Справочники",
                items = listOf(
                    WmsNavEntry(WmsTab.WAREHOUSES, ErpIconGlyph.Warehouse, Brand.Teal),
                    WmsNavEntry(WmsTab.LOCATIONS, ErpIconGlyph.Grid, Brand.Blue)
                ),
                selectedTab = state.selectedTab,
                state = state,
                onSelect = onSelectTab
            )
        }
    }
}

@Composable
private fun WmsNavSection(
    title: String,
    items: List<WmsNavEntry>,
    selectedTab: WmsTab,
    state: WmsUiState,
    onSelect: (WmsTab) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
        )
        items.forEach { entry ->
            WmsNavItemRow(
                entry = entry,
                selected = selectedTab == entry.tab,
                count = wmsTabCount(entry.tab, state),
                alertCount = entry.tab == WmsTab.MOVEMENTS && state.lowStock.isNotEmpty(),
                onClick = { onSelect(entry.tab) }
            )
        }
    }
}

@Composable
private fun WmsNavItemRow(
    entry: WmsNavEntry,
    selected: Boolean,
    count: Int,
    alertCount: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)

    val bgModifier = when {
        selected -> Modifier.background(
            Brush.linearGradient(
                listOf(
                    entry.accent.copy(alpha = 0.16f),
                    entry.accent.copy(alpha = 0.08f)
                )
            )
        ).border(1.dp, entry.accent.copy(alpha = 0.35f), shape)
        hovered -> Modifier.background(scheme.onSurface.copy(alpha = 0.06f))
        else -> Modifier
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(bgModifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (selected) entry.accent.copy(alpha = 0.22f)
                    else scheme.onSurface.copy(alpha = 0.06f)
                ),
            contentAlignment = Alignment.Center
        ) {
            ErpIcon(
                glyph = entry.glyph,
                tint = if (selected) entry.accent else scheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            text = entry.tab.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) entry.accent else scheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (count > 0) {
            val badgeColor = if (alertCount) Brand.Rose else if (selected) entry.accent else scheme.onSurfaceVariant
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = badgeColor.copy(alpha = if (selected || alertCount) 0.16f else 0.10f)
            ) {
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = badgeColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}

private fun org.example.project.dto.ReceiptQcStatus.qcLabel(): String = when (this) {
    org.example.project.dto.ReceiptQcStatus.PENDING -> "ожидает контроля"
    org.example.project.dto.ReceiptQcStatus.PASSED -> "принято"
    org.example.project.dto.ReceiptQcStatus.PARTIAL -> "принято частично"
    org.example.project.dto.ReceiptQcStatus.FAILED -> "не принято"
}
