package org.example.project.ui.wms

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import org.example.project.dto.NomenclatureDto
import org.example.project.dto.ReceiptDto
import org.example.project.dto.ReceiptStatus
import org.example.project.dto.WarehouseDto
import org.example.project.dto.ReservationDto
import org.example.project.dto.ReservationStatus
import org.example.project.dto.StorageLocationPurpose
import org.example.project.ui.components.SearchableDropdown
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.ErpIcon
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.GlassCard
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
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val isWide = maxWidth >= 780.dp
            if (isWide) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    WmsVerticalNav(
                        state = state,
                        onSelectTab = viewModel::selectTab,
                        modifier = Modifier
                            .width(256.dp)
                            .fillMaxHeight()
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        WmsTabContent(state, viewModel)
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    ScrollableTabRow(
                        selectedTabIndex = WmsTab.entries.indexOf(state.selectedTab),
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                        edgePadding = 12.dp
                    ) {
                        WmsTab.entries.forEach { tab ->
                            val count = wmsTabCount(tab, state)
                            val label = if (count > 0) "${tab.title} ($count)" else tab.title
                            Tab(
                                selected = state.selectedTab == tab,
                                onClick = { viewModel.selectTab(tab) },
                                text = { Text(label) }
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        WmsTabContent(state, viewModel)
                    }
                }
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
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCreate) { Text(createLabel) }
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
            EmptyState("Нет складов. Создайте первый склад.")
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
            EmptyState("Нет ячеек для выбранного склада.")
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
    val wmsRepo: org.example.project.data.WmsRepository = koinInject()
    val scope = rememberCoroutineScope()
    var minStock by remember(item.id) { mutableStateOf(item.minStock) }
    var safetyStock by remember(item.id) { mutableStateOf(item.safetyStock) }
    var leadTimeDays by remember(item.id) { mutableStateOf(item.leadTimeDays.toString()) }
    var newBarcode by remember(item.id) { mutableStateOf("") }
    var localStatus by remember(item.id) { mutableStateOf<String?>(null) }

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
                localStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                HorizontalDivider()
                Text("Пороги запаса и срок поставки", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = minStock,
                        onValueChange = { minStock = it },
                        label = { Text("Мин. запас") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = safetyStock,
                        onValueChange = { safetyStock = it },
                        label = { Text("Страх. запас") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = leadTimeDays,
                        onValueChange = { leadTimeDays = it },
                        label = { Text("Срок (дн)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Button(onClick = {
                    scope.launch {
                        val res = wmsRepo.updateNomenclatureThresholds(
                            item.id,
                            org.example.project.dto.UpdateNomenclatureThresholdsRequest(
                                minStock = minStock.trim().ifEmpty { "0" },
                                safetyStock = safetyStock.trim().ifEmpty { "0" },
                                leadTimeDays = leadTimeDays.trim().toIntOrNull() ?: 0,
                                version = item.version
                            )
                        )
                        localStatus = when (res) {
                            is org.example.project.data.ApiResult.Success -> "Пороги обновлены"
                            is org.example.project.data.ApiResult.Error -> "Ошибка: ${res.error.message}"
                        }
                    }
                }) { Text("Сохранить пороги") }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newBarcode,
                        onValueChange = { newBarcode = it },
                        label = { Text("Новый штрихкод") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = {
                        val code = newBarcode.trim()
                        if (code.isNotEmpty()) {
                            scope.launch {
                                val res = wmsRepo.addBarcode(
                                    item.id,
                                    org.example.project.dto.CreateNomenclatureBarcodeRequest(barcode = code)
                                )
                                localStatus = when (res) {
                                    is org.example.project.data.ApiResult.Success -> {
                                        newBarcode = ""
                                        "Штрихкод $code добавлен"
                                    }
                                    is org.example.project.data.ApiResult.Error -> "Ошибка: ${res.error.message}"
                                }
                            }
                        }
                    }) { Text("Добавить") }
                }
                if (item.barcodes.isNotEmpty()) {
                    Text("Штрихкоды", style = MaterialTheme.typography.titleSmall)
                    item.barcodes.forEach { barcode ->
                        Text(
                            text = "${barcode.barcode} · ${barcode.packageType.localizedName()} · ${barcode.quantityInPackage} ${item.unit}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                var analogs by remember(item.id) {
                    mutableStateOf<List<org.example.project.dto.NomenclatureAnalogDto>>(emptyList())
                }
                var analogIdInput by remember(item.id) { mutableStateOf("") }
                androidx.compose.runtime.LaunchedEffect(item.id) {
                    val res = wmsRepo.listAnalogs(item.id)
                    if (res is org.example.project.data.ApiResult.Success<*>) {
                        analogs = (res as org.example.project.data.ApiResult.Success<List<org.example.project.dto.NomenclatureAnalogDto>>).data
                    }
                }
                HorizontalDivider()
                Text("Аналоги (${analogs.size})", style = MaterialTheme.typography.titleSmall)
                analogs.forEach { a ->
                    Text(
                        text = "${a.analogCode} — ${a.analogName} (коэф. ${a.conversionFactor})",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = analogIdInput,
                        onValueChange = { analogIdInput = it },
                        label = { Text("ID аналога") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = {
                        val aId = analogIdInput.trim().toLongOrNull()
                        if (aId != null) {
                            scope.launch {
                                val res = wmsRepo.createAnalog(
                                    item.id,
                                    org.example.project.dto.CreateNomenclatureAnalogRequest(analogNomenclatureId = aId)
                                )
                                when (res) {
                                    is org.example.project.data.ApiResult.Success<*> -> {
                                        analogs = (res as org.example.project.data.ApiResult.Success<List<org.example.project.dto.NomenclatureAnalogDto>>).data
                                        analogIdInput = ""
                                        localStatus = "Аналог #$aId добавлен"
                                    }
                                    is org.example.project.data.ApiResult.Error -> localStatus = "Ошибка: ${res.error.message}"
                                }
                            }
                        }
                    }) { Text("Аналог") }
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Остатки на складе", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

        // Сводка по остаткам (KPI)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StockKpiCard("Позиций", "${filteredStock.size}", Brand.Blue, Modifier.weight(1f))
            StockKpiCard("Физически", formatQtyShort(totalPhysical), Brand.Indigo, Modifier.weight(1f))
            StockKpiCard("В резерве", formatQtyShort(totalReserved), Brand.Amber, Modifier.weight(1f))
            StockKpiCard("Доступно", formatQtyShort(totalAvailable), Brand.Teal, Modifier.weight(1f))
            StockKpiCard("Карантин / брак", "$quarantineCount", if (quarantineCount > 0) Brand.Rose else Brand.Graphite, Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WarehouseDropdown(
                warehouses = state.warehouses,
                selected = state.warehouses.find { it.id == state.stockFilters.warehouseId },
                onSelect = {
                    viewModel.updateStockFilters(state.stockFilters.copy(warehouseId = it.id))
                    viewModel.loadStock()
                },
                label = "Склад",
                modifier = Modifier.weight(1f)
            )
            NomenclatureDropdown(
                items = state.nomenclature,
                selected = state.nomenclature.find { it.id == state.stockFilters.nomenclatureId },
                onSelect = {
                    viewModel.updateStockFilters(state.stockFilters.copy(nomenclatureId = it.id))
                    viewModel.loadStock()
                },
                label = "Номенклатура",
                modifier = Modifier.weight(1f)
            )
            Button(onClick = viewModel::loadStock) { Text("Обновить") }
            OutlinedButton(onClick = {
                filterOnlyAvailable = false
                filterQuarantineOnly = false
                viewModel.updateStockFilters(StockFilters())
                viewModel.loadStock()
            }) { Text("Сбросить") }
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
                    Text("Ниже минимума: ${state.lowStock.size}", color = Brand.Rose)
                }
            }
        }

        state.operationError?.let { ErrorMessageCard(error = it) }
        if (state.isLoading && state.stock.isEmpty()) {
            LoadingOverlay()
        } else if (filteredStock.isEmpty()) {
            EmptyState("Остатки не найдены. Выберите фильтры или сбросьте ограничения.")
        } else {
            StockGrid(
                stock = filteredStock,
                modifier = Modifier.fillMaxWidth().weight(1f)
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
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = accent.copy(alpha = 0.08f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(text = title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = value, style = MaterialTheme.typography.titleMedium, color = accent)
        }
    }
}

private fun formatQtyShort(value: Double): String {
    val rounded = kotlin.math.round(value * 100.0) / 100.0
    val asLong = rounded.toLong()
    return if (rounded == asLong.toDouble()) asLong.toString() else rounded.toString()
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
            val wmsRepoForQc: org.example.project.data.WmsRepository = koinInject()
            val qcScope = rememberCoroutineScope()
            var showQcDialog by remember(receipt.id) { mutableStateOf(false) }
            var qcInfo by remember(receipt.id) { mutableStateOf<org.example.project.dto.ReceiptQcDto?>(null) }
            var qcStatusMsg by remember(receipt.id) { mutableStateOf<String?>(null) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (receipt.status == ReceiptStatus.DRAFT || receipt.status == ReceiptStatus.IN_PROGRESS) {
                    Button(onClick = onPost) { Text("Провести") }
                }
                if (receipt.status == ReceiptStatus.POSTED) {
                    Button(onClick = {
                        showQcDialog = true
                        qcScope.launch {
                            when (val res = wmsRepoForQc.getReceiptQc(receipt.id)) {
                                is org.example.project.data.ApiResult.Success -> qcInfo = res.data
                                is org.example.project.data.ApiResult.Error -> qcStatusMsg = res.error.message
                            }
                        }
                    }) { Text("Контроль ОТК") }
                }
                TextButton(onClick = onClose) { Text("К списку") }
            }
            if (showQcDialog) {
                var qcComment by remember { mutableStateOf("") }
                AlertDialog(
                    onDismissRequest = { showQcDialog = false },
                    title = { Text("Входной контроль ОТК (${receipt.number})") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            qcInfo?.let { qc ->
                                Text("Статус ОТК: ${qc.status.name}")
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
                    },
                    confirmButton = {
                        Button(onClick = {
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
                                        qcStatusMsg = "ОТК проведён: ${res.data.status.name}"
                                    }
                                    is org.example.project.data.ApiResult.Error -> qcStatusMsg = res.error.message
                                }
                            }
                        }) { Text("Принять всё (ОТК)") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showQcDialog = false }) { Text("Закрыть") }
                    }
                )
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
        GlassCard(modifier = Modifier.padding(24.dp)) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                org.example.project.ui.theme.ErpIcon(
                    glyph = ErpIconGlyph.Warehouse,
                    tint = Brand.Teal
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MovementsAndReportsTab(state: WmsUiState, viewModel: WmsViewModel) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.operationError?.let { ErrorMessageCard(it) }
        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 12.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.barcodeQuery,
                    onValueChange = viewModel::updateBarcodeQuery,
                    label = { Text("Поиск по штрихкоду / артикулу") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = viewModel::lookupBarcode) { Text("Найти") }
                TextButton(onClick = viewModel::loadMovementsAndLowStock) { Text("Обновить") }
            }
            state.barcodeResult?.let { nom ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Найдено: ${nom.code} — ${nom.name} (${nom.unit})",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassCard(modifier = Modifier.weight(1f).fillMaxHeight(), contentPadding = 12.dp) {
                Text("Журнал движений (${state.movements.size})", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                if (state.movements.isEmpty()) {
                    Text("Движений пока нет.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(state.movements.size) { i ->
                            val m = state.movements[i]
                            Text(
                                text = "${m.type.name} • ном. #${m.nomenclatureId} • кол-во ${m.quantity} (${m.documentType} #${m.documentId})",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
            GlassCard(modifier = Modifier.weight(1f).fillMaxHeight(), contentPadding = 12.dp) {
                Text("Ниже минимума / дефицит (${state.lowStock.size})", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                if (state.lowStock.isEmpty()) {
                    Text("Дефицита не выявлено.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(state.lowStock.size) { i ->
                            val l = state.lowStock[i]
                            Text(
                                text = "${l.code} ${l.name}: остаток ${l.availableQty} ${l.unit}, мин. ${l.minStock}, дефицит ${l.deficitQty}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
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
    WmsTab.LOCATIONS -> state.locations.size
    WmsTab.NOMENCLATURE -> state.nomenclature.size
    WmsTab.STOCK -> state.stock.size
    WmsTab.RECEIPTS -> state.receipts.size
    WmsTab.RESERVATIONS -> state.reservations.size
    WmsTab.TRANSFERS -> state.transfers.size
    WmsTab.ISSUES -> state.issues.size
    WmsTab.RETURNS -> state.returns.size
    WmsTab.WRITE_OFFS -> state.writeOffs.size
    WmsTab.INVENTORIES -> state.inventories.size
    WmsTab.MOVEMENTS -> state.lowStock.size
}

private data class WmsNavEntry(
    val tab: WmsTab,
    val glyph: ErpIconGlyph,
    val accent: Color
)

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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            WmsNavSection(
                title = "Учёт и остатки",
                items = listOf(
                    WmsNavEntry(WmsTab.STOCK, ErpIconGlyph.CheckCircle, Brand.Teal),
                    WmsNavEntry(WmsTab.RESERVATIONS, ErpIconGlyph.Clock, Brand.Amber),
                    WmsNavEntry(WmsTab.MOVEMENTS, ErpIconGlyph.TrendUp, Brand.Indigo)
                ),
                selectedTab = state.selectedTab,
                state = state,
                onSelect = onSelectTab
            )
            WmsNavSection(
                title = "Операции",
                items = listOf(
                    WmsNavEntry(WmsTab.RECEIPTS, ErpIconGlyph.Plus, Brand.Blue),
                    WmsNavEntry(WmsTab.TRANSFERS, ErpIconGlyph.Send, Brand.Indigo),
                    WmsNavEntry(WmsTab.ISSUES, ErpIconGlyph.ChevronRight, Brand.Teal),
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
                    WmsNavEntry(WmsTab.LOCATIONS, ErpIconGlyph.Grid, Brand.Blue),
                    WmsNavEntry(WmsTab.NOMENCLATURE, ErpIconGlyph.Layers, Brand.Amber)
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
    val shape = RoundedCornerShape(12.dp)

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
