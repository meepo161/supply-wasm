package org.example.project.ui.wms.card

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.dto.AttachmentEntityType
import org.example.project.dto.BomWhereUsedItemDto
import org.example.project.dto.DemandStatus
import org.example.project.dto.NomenclatureAnalogDto
import org.example.project.dto.NomenclatureCardDto
import org.example.project.dto.NomenclatureStockPointDto
import org.example.project.dto.ReservationStatus
import org.example.project.dto.StockMovementDto
import org.example.project.dto.StockMovementType
import org.example.project.dto.StorageLocationPurpose
import org.example.project.ui.attachments.AttachmentsSection
import org.example.project.ui.bom.localizedName
import org.example.project.ui.demand.localizedName
import org.example.project.ui.documents.localizedName
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.EmptyState
import org.example.project.ui.theme.ErpFilterChip
import org.example.project.ui.theme.ErpFormat
import org.example.project.ui.theme.ErpIconButton
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.GlassCard
import org.example.project.ui.theme.PillBadge
import org.example.project.ui.theme.SoftButton
import org.example.project.ui.theme.TextButton
import org.example.project.ui.wms.localizedName

@Composable
internal fun CardTabContent(
    state: NomenclatureCardUiState,
    card: NomenclatureCardDto,
    canManage: Boolean,
    viewModel: NomenclatureCardViewModel,
    onOpenDialog: (CardDialog) -> Unit
) {
    when (state.tab) {
        NomenclatureCardTab.OVERVIEW -> OverviewTab(card, canManage, state.busy, viewModel)
        NomenclatureCardTab.STOCK -> StockTab(state, card, viewModel)
        NomenclatureCardTab.SUPPLY -> SupplyTab(card, canManage, state.busy, viewModel, onOpenDialog)
        NomenclatureCardTab.RESERVATIONS -> ReservationsTab(state, card)
        NomenclatureCardTab.MOVEMENTS -> MovementsTab(state, card, viewModel)
        NomenclatureCardTab.USAGE -> UsageTab(state, card)
        NomenclatureCardTab.PRODUCT -> ProductTab(card)
        NomenclatureCardTab.FILES -> FilesTab(state, card, canManage, viewModel)
        NomenclatureCardTab.HISTORY -> HistoryTab(state)
    }
}

// --- Обзор ---

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverviewTab(card: NomenclatureCardDto, canManage: Boolean, busy: Boolean, viewModel: NomenclatureCardViewModel) {
    val item = card.item
    TwoColumns(
        left = {
            CardSection("Основные сведения") {
                Field("Код", item.code)
                Field("Наименование", item.name)
                Field("Тип", item.type.localizedName())
                Field("Категория", card.category?.path)
                Field("Базовая единица", item.unit)
                Field(
                    "Учёт",
                    listOfNotNull(
                        "по партиям".takeIf { item.lotTracked },
                        "по серийным номерам".takeIf { item.serialTracked }
                    ).joinToString(", ").ifEmpty { "количественный" }
                )
                Field("Описание", item.description)
                Field("Создана", listOfNotNull(ErpFormat.dateRu(item.createdAt), card.createdByName).joinToString(" · "))
            }
            CardSection("Производитель и параметры") {
                Field("Производитель", item.manufacturer)
                Field("Модель производителя", item.manufacturerModel)
                Field("Артикул", item.article)
                Field("Масса", item.weightKg?.let { "${ErpFormat.qty(it)} кг" })
                val dims = listOf(item.lengthMm, item.widthMm, item.heightMm)
                Field("Габариты (Д × Ш × В)", if (dims.all { it == null }) null else dims.joinToString(" × ") { it?.let(ErpFormat::qty) ?: "—" } + " мм")
                if (card.attributes.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    card.attributes.forEach { attr ->
                        Field(attr.name, attr.value + (attr.unit?.let { " $it" } ?: ""))
                    }
                } else {
                    Hint(if (canManage) "Характеристики не заданы. Добавьте их через «Редактировать»." else "Характеристики не заданы.")
                }
            }
        },
        right = {
            BarcodesSection(card, canManage, busy, viewModel)
            UnitsSection(card, canManage, busy, viewModel)
        }
    )
}

@Composable
private fun BarcodesSection(card: NomenclatureCardDto, canManage: Boolean, busy: Boolean, viewModel: NomenclatureCardViewModel) {
    var code by remember(card.item.id) { mutableStateOf("") }
    CardSection("Штрихкоды", count = card.item.barcodes.size) {
        if (card.item.barcodes.isEmpty()) Hint("Штрихкодов нет: позицию нельзя найти сканером.")
        card.item.barcodes.forEach { barcode ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(barcode.barcode, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(
                    "${barcode.packageType.localizedName()} · ${ErpFormat.qty(barcode.quantityInPackage)} ${card.item.unit}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (canManage) {
                    ErpIconButton(ErpIconGlyph.Close, "Удалить штрихкод ${barcode.barcode}", onClick = { viewModel.deleteBarcode(barcode.id) }, enabled = !busy)
                }
            }
        }
        if (canManage && !card.item.archived) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Новый штрихкод") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                SoftButton(
                    text = "Добавить",
                    onClick = { viewModel.addBarcode(code.trim()); code = "" },
                    enabled = code.isNotBlank() && !busy
                )
            }
        }
    }
}

@Composable
private fun UnitsSection(card: NomenclatureCardDto, canManage: Boolean, busy: Boolean, viewModel: NomenclatureCardViewModel) {
    var fromUnit by remember(card.item.id) { mutableStateOf("") }
    var factor by remember(card.item.id) { mutableStateOf("") }
    CardSection("Единицы измерения") {
        Field("Базовая", card.item.unit)
        card.unitConversions.forEach { conv ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "1 ${conv.fromUnit} = ${ErpFormat.qty(conv.factor)} ${conv.toUnit}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                if (canManage) {
                    ErpIconButton(ErpIconGlyph.Close, "Удалить пересчёт ${conv.fromUnit}", onClick = { viewModel.deleteUnitConversion(conv.id) }, enabled = !busy)
                }
            }
        }
        if (card.unitConversions.isEmpty()) Hint("Пересчётов нет: документы ведутся только в «${card.item.unit}».")
        if (canManage && !card.item.archived) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(fromUnit, { fromUnit = it }, label = { Text("Единица") }, singleLine = true, modifier = Modifier.weight(1f))
                Text("=", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(factor, { factor = it }, label = { Text("Коэффициент") }, singleLine = true, modifier = Modifier.weight(1f))
                Text(card.item.unit, style = MaterialTheme.typography.bodyMedium)
                SoftButton(
                    text = "Добавить",
                    onClick = { viewModel.addUnitConversion(fromUnit.trim(), factor); fromUnit = ""; factor = "" },
                    enabled = fromUnit.isNotBlank() && factor.isNotBlank() && !busy
                )
            }
        }
    }
}

// --- Остатки ---

@Composable
private fun StockTab(state: NomenclatureCardUiState, card: NomenclatureCardDto, viewModel: NomenclatureCardViewModel) {
    val unit = card.item.unit
    CardSection(
        "Физический остаток по дням",
        action = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(30, 90, 365).forEach { days ->
                    ErpFilterChip(
                        selected = state.stockHistoryDays == days,
                        onClick = { viewModel.setStockHistoryDays(days) },
                        label = { Text(if (days == 365) "Год" else "$days дн.") }
                    )
                }
            }
        }
    ) {
        when (val data = state.stockHistory) {
            is TabData.Ready -> StockChart(points = data.value, unit = unit)
            is TabData.Failed -> ErrorMessageCard(error = data.error)
            else -> Loading()
        }
        Hint("График восстановлен по журналу движений: приход, расход, списание и корректировки. Перемещения общий остаток не меняют.")
    }
    CardSection("По складам", count = card.stock.byWarehouse.size) {
        if (card.stock.byWarehouse.isEmpty()) {
            EmptyState("Остатка нет ни на одном складе", text = "Остаток появится после проведения приёмки.")
        } else {
            CardTable(
                headers = listOf("Склад" to 2f, "Физ." to 1f, "Доступно" to 1f, "Резерв" to 1f, "Карантин" to 1f, "Брак" to 1f, "В пути" to 1f)
            ) {
                card.stock.byWarehouse.forEach { row ->
                    TableRow(
                        listOf(
                            "${row.warehouseCode} · ${row.warehouseName}" to 2f,
                            ErpFormat.qty(row.physicalQty) to 1f,
                            ErpFormat.qty(row.availableQty) to 1f,
                            ErpFormat.qty(row.reservedQty) to 1f,
                            ErpFormat.qty(row.quarantineQty) to 1f,
                            ErpFormat.qty(row.defectQty) to 1f,
                            ErpFormat.qty(row.inTransitQty) to 1f
                        )
                    )
                }
            }
        }
    }
    if (card.stockRows.isNotEmpty()) {
        val warehouseNames = card.stock.byWarehouse.associate { it.warehouseId to it.warehouseCode }
        CardSection("По ячейкам", count = card.stockRows.size) {
            CardTable(headers = listOf("Склад · ячейка" to 2f, "Назначение" to 1.4f, "Партия / серия" to 1.6f, "Физ." to 1f, "Резерв" to 1f, "Доступно" to 1f)) {
                card.stockRows.forEach { row ->
                    TableRow(
                        listOf(
                            "${warehouseNames[row.warehouseId] ?: row.warehouseId} · ${row.locationCode}" to 2f,
                            row.locationPurpose.localizedName() to 1.4f,
                            (listOfNotNull(row.lotNumber, row.serialNumber).joinToString(" / ").ifEmpty { "—" }) to 1.6f,
                            ErpFormat.qty(row.physicalQty) to 1f,
                            ErpFormat.qty(row.reservedQty) to 1f,
                            ErpFormat.qty(row.availableQty) to 1f
                        ),
                        muted = row.locationPurpose != StorageLocationPurpose.STORAGE && row.locationPurpose != StorageLocationPurpose.RECEIVING && row.locationPurpose != StorageLocationPurpose.SHIPPING
                    )
                }
            }
        }
    }
    if (card.item.lotTracked || card.lots.isNotEmpty()) {
        CardSection("Партии", count = card.lots.size) {
            if (card.lots.isEmpty()) Hint("Партии ещё не зарегистрированы.")
            card.lots.forEach { lot ->
                Field(
                    lot.lotNumber,
                    listOfNotNull(
                        lot.manufactureDate?.let { "изготовлена ${ErpFormat.dateRu(it)}" },
                        lot.expiryDate?.let { "годна до ${ErpFormat.dateRu(it)}" }
                    ).joinToString(" · ").ifEmpty { "без дат" }
                )
            }
        }
    }
    if (card.item.serialTracked || card.serials.isNotEmpty()) {
        CardSection("Серийные номера", count = card.serials.size) {
            if (card.serials.isEmpty()) Hint("Серийные номера ещё не зарегистрированы.")
            Text(card.serials.joinToString(", ") { it.serialNumber }, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Линейный график без библиотек: только реальные точки, ось — min/max периода. */
@Composable
private fun StockChart(points: List<NomenclatureStockPointDto>, unit: String) {
    val values = points.map { it.physicalQty.toDoubleOrNull() ?: 0.0 }
    if (values.isEmpty()) {
        Hint("Нет данных за период.")
        return
    }
    val max = values.max()
    val min = 0.0
    val scheme = MaterialTheme.colorScheme
    val lineColor = scheme.primary
    val gridColor = scheme.outlineVariant
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("сейчас ${ErpFormat.qty(points.last().physicalQty)} $unit", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(12.dp))
            Text("максимум за период ${ErpFormat.qty(max)} $unit", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
            val range = (max - min).takeIf { it > 0 } ?: 1.0
            val stepX = if (values.size > 1) size.width / (values.size - 1) else 0f
            fun y(v: Double) = (size.height - ((v - min) / range * size.height * 0.92)).toFloat()
            listOf(0f, 0.5f, 1f).forEach { f ->
                val yy = size.height * (1f - f * 0.92f)
                drawLine(gridColor, Offset(0f, yy), Offset(size.width, yy), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            }
            val path = Path()
            values.forEachIndexed { i, v ->
                // Ступенчатая линия: остаток меняется скачком в день движения.
                val x = i * stepX
                if (i == 0) path.moveTo(x, y(v)) else {
                    path.lineTo(x, y(values[i - 1]))
                    path.lineTo(x, y(v))
                }
            }
            drawPath(path, lineColor, style = Stroke(width = 2.5f))
            drawCircle(lineColor, radius = 4f, center = Offset((values.size - 1) * stepX, y(values.last())))
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(ErpFormat.dateRu(points.first().date), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(ErpFormat.dateRu(points.last().date), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
    }
}

// --- Снабжение ---

@Composable
private fun SupplyTab(
    card: NomenclatureCardDto,
    canManage: Boolean,
    busy: Boolean,
    viewModel: NomenclatureCardViewModel,
    onOpenDialog: (CardDialog) -> Unit
) {
    val item = card.item
    TwoColumns(
        left = {
            CardSection("Пороги и закупка") {
                Field("Минимальный запас", "${ErpFormat.qty(item.minStock)} ${item.unit}")
                Field("Страховой запас", "${ErpFormat.qty(item.safetyStock)} ${item.unit}")
                Field("Срок поставки", if (item.leadTimeDays > 0) "${item.leadTimeDays} дн." else null)
                Field("Минимальная партия", "${ErpFormat.qty(item.minOrderQty)} ${item.unit}")
                Field("Кратность заказа", "${ErpFormat.qty(item.orderMultiple)} ${item.unit}")
                val suggestion = card.demandSuggestion
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                if (suggestion != null) {
                    Text(
                        "Нужно пополнить: до цели ${ErpFormat.qty(suggestion.targetQty)} не хватает ${ErpFormat.qty(suggestion.deficitQty)} ${item.unit} " +
                            "(доступно ${ErpFormat.qty(card.stock.availableQty)}, ожидается ${ErpFormat.qty(card.stock.expectedQty)}). " +
                            "С учётом кратности и минимальной партии к заказу ${ErpFormat.qty(suggestion.suggestedQty)} ${item.unit}" +
                            (suggestion.requiredDate?.let { ", к ${ErpFormat.dateRu(it)}" } ?: "") + ".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    if (suggestion.openDemands.isNotEmpty()) {
                        Hint("Открытые потребности по позиции (в расчёт не входят): ${suggestion.openDemands.joinToString(", ")}.")
                    }
                } else if (item.minStock.toDoubleOrNull() == 0.0 && item.safetyStock.toDoubleOrNull() == 0.0) {
                    Hint("Пороги не заданы, поэтому дефицит не рассчитывается.")
                } else {
                    Hint("Доступного и ожидаемого хватает до страхового запаса.")
                }
            }
            CardSection("Хранение") {
                Field("Условия хранения", item.storageConditions)
                Field(
                    "Допустимые склады",
                    card.allowedWarehouses.joinToString(", ") { "${it.code} · ${it.name}" }.ifEmpty { "без ограничений" }
                )
                Hint("Допустимые склады — справочная информация: приёмку и перемещение они не блокируют.")
            }
        },
        right = {
            CardSection("Ожидаемые поставки", count = card.expectedSupplies.size) {
                if (card.expectedSupplies.isEmpty()) {
                    Hint("Нет отправленных поставщикам заказов с непринятым остатком.")
                } else {
                    CardTable(headers = listOf("Заказ" to 1.4f, "Поставщик" to 1.8f, "Осталось" to 1f, "Срок" to 1f)) {
                        card.expectedSupplies.forEach { s ->
                            TableRow(
                                listOf(
                                    "${s.orderNumber} · стр. ${s.lineNo}" to 1.4f,
                                    (s.supplierName ?: "—") to 1.8f,
                                    "${ErpFormat.qty(s.remainingQty)} из ${ErpFormat.qty(s.orderedQty)}" to 1f,
                                    (s.expectedAt?.let(ErpFormat::dateRu) ?: "—") to 1f
                                )
                            )
                        }
                    }
                }
            }
            CardSection(
                "Аналоги",
                count = card.analogs.size,
                action = if (canManage && !item.archived) {
                    { SoftButton(text = "Добавить аналог", onClick = { onOpenDialog(CardDialog.Analog) }, enabled = !busy) }
                } else null
            ) {
                if (card.analogs.isEmpty()) Hint("Аналоги не указаны.")
                card.analogs.forEach { analog -> AnalogRow(analog, item.unit, canDelete = canManage) { viewModel.deleteAnalog(analog.id) } }
                if (card.analogOf.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("Сама является аналогом для", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    card.analogOf.forEach { AnalogRow(it, item.unit, canDelete = false) {} }
                }
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnalogRow(analog: NomenclatureAnalogDto, unit: String, canDelete: Boolean, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${analog.analogCode} · ${analog.analogName}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (analog.requiresApproval) PillBadge("требует согласования", accent = Brand.Amber)
                if (!analog.active) PillBadge("не действует", accent = Brand.Slate)
            }
            Text(
                listOfNotNull(
                    "1 $unit = ${ErpFormat.qty(analog.conversionFactor)} аналога",
                    "приоритет ${analog.priority}",
                    analog.applicabilityCondition
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (canDelete) ErpIconButton(ErpIconGlyph.Close, "Удалить аналог ${analog.analogCode}", onClick = onDelete)
    }
}

// --- Резервы ---

@Composable
private fun ReservationsTab(state: NomenclatureCardUiState, card: NomenclatureCardDto) {
    when (val data = state.reservations) {
        is TabData.Ready -> {
            val rows = data.value
            if (rows.isEmpty()) {
                EmptyState("Резервов нет", text = "Резерв уменьшает доступное количество, но не является выдачей.")
                return
            }
            val active = rows.filter { it.status == ReservationStatus.ACTIVE }
            CardSection("Резервы", count = rows.size) {
                Hint("Активных: ${active.size}, зарезервировано ${ErpFormat.qty(active.sumOf { (it.quantity.toDoubleOrNull() ?: 0.0) - (it.issuedQuantity.toDoubleOrNull() ?: 0.0) })} ${card.item.unit}.")
                CardTable(headers = listOf("Номер" to 1.1f, "Основание" to 2.4f, "Склад · ячейка" to 1.6f, "Кол-во" to 1f, "Статус" to 1f, "Ответственный" to 1.4f)) {
                    rows.forEach { r ->
                        TableRow(
                            listOf(
                                r.number to 1.1f,
                                (r.reasonLabel + (r.reasonNote?.let { " — $it" } ?: "")) to 2.4f,
                                listOfNotNull(r.warehouseName, r.locationCode, r.lotNumber, r.serialNumber).joinToString(" · ") to 1.6f,
                                (ErpFormat.qty(r.quantity) + if ((r.issuedQuantity.toDoubleOrNull() ?: 0.0) > 0) " (выдано ${ErpFormat.qty(r.issuedQuantity)})" else "") to 1f,
                                r.status.localizedName() to 1f,
                                r.ownerName to 1.4f
                            ),
                            muted = r.status != ReservationStatus.ACTIVE
                        )
                    }
                }
            }
        }
        is TabData.Failed -> ErrorMessageCard(error = data.error)
        else -> Loading()
    }
}

// --- Движения ---

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MovementsTab(state: NomenclatureCardUiState, card: NomenclatureCardDto, viewModel: NomenclatureCardViewModel) {
    val movements = state.movements
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ErpFilterChip(selected = movements.type == null, onClick = { viewModel.filterMovements(null) }, label = { Text("Все") })
        StockMovementType.entries.forEach { type ->
            ErpFilterChip(selected = movements.type == type, onClick = { viewModel.filterMovements(type) }, label = { Text(type.localizedName()) })
        }
    }
    movements.error?.let { ErrorMessageCard(error = it) }
    if (movements.loaded && movements.items.isEmpty()) {
        EmptyState(
            if (movements.type == null) "Движений ещё не было" else "Движений этого типа нет",
            text = "Журнал пополняется только проведёнными документами."
        )
    }
    if (movements.items.isNotEmpty()) {
        CardSection("Журнал движений", count = movements.items.size) {
            movements.items.forEachIndexed { index, m ->
                if (index > 0) HorizontalDivider()
                MovementRow(m, card.item.unit)
            }
        }
    }
    if (movements.loading) Loading()
    if (movements.nextBefore != null && !movements.loading) {
        SoftButton(text = "Показать ещё", onClick = viewModel::loadMoreMovements)
    }
}

@Composable
private fun MovementRow(m: StockMovementDto, unit: String) {
    val (sign, color) = when (m.type) {
        StockMovementType.RECEIPT, StockMovementType.RETURN, StockMovementType.ADJUSTMENT_IN -> "+" to Brand.Mint
        StockMovementType.ISSUE, StockMovementType.WRITE_OFF, StockMovementType.ADJUSTMENT_OUT -> "−" to Brand.Orange
        StockMovementType.TRANSFER -> "" to Brand.Indigo
    }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Column(modifier = Modifier.weight(1f)) {
            // Номер документа, а не внутренний id: «Списание WOF-2026-00001»; id остаётся запасным вариантом.
            val docName = docTypeLabel(m.documentType)
            val movementTitle = if (m.documentNumber != null) {
                val docTitle = "${docName.replaceFirstChar { it.uppercase() }} ${m.documentNumber}"
                if (m.type.localizedName().equals(docName, ignoreCase = true)) docTitle else "${m.type.localizedName()} · $docTitle"
            } else {
                "${m.type.localizedName()} · $docName №${m.documentId}"
            }
            Text(
                movementTitle,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            val route = when {
                m.fromLocationCode != null && m.toLocationCode != null -> "${m.fromLocationCode} → ${m.toLocationCode}"
                m.fromLocationCode != null -> "из ${m.fromLocationCode}"
                m.toLocationCode != null -> "в ${m.toLocationCode}"
                else -> null
            }
            Text(
                listOfNotNull(ErpFormat.dateTimeRu(m.createdAt), m.createdByName, route, m.lotNumber?.let { "партия $it" }, m.serialNumber?.let { "серия $it" }, m.note)
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text("$sign${ErpFormat.qty(m.quantity)} $unit", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = color)
    }
}

// --- Применяемость ---

@Composable
private fun UsageTab(state: NomenclatureCardUiState, card: NomenclatureCardDto) {
    when (val data = state.usage) {
        is TabData.Ready -> {
            val usage = data.value
            if (usage.archiveBlockers.isNotEmpty()) {
                CardSection("Архивирование сейчас невозможно") {
                    usage.archiveBlockers.forEach { Text("• ${it.replaceFirstChar { c -> c.uppercase() }}", style = MaterialTheme.typography.bodyMedium) }
                }
            } else if (!card.item.archived) {
                Hint("Активных ссылок нет — позицию можно архивировать.")
            }
            val empty = usage.bomLines.isEmpty() && usage.parentBomLines.isEmpty() && usage.demands.isEmpty() &&
                usage.purchaseOrders.isEmpty() && usage.productionOrders.isEmpty()
            if (empty) {
                EmptyState("Позиция нигде не используется", text = "Её нет в BOM, потребностях, заказах поставщикам и производственных заказах.")
                return
            }
            if (usage.bomLines.isNotEmpty()) BomUsageSection("Входит в состав изделий (BOM)", usage.bomLines)
            if (usage.parentBomLines.isNotEmpty()) BomUsageSection("Собирается из компонентов (как сборка)", usage.parentBomLines)
            if (usage.demands.isNotEmpty()) {
                CardSection("Потребности", count = usage.demands.size) {
                    CardTable(headers = listOf("Номер" to 1.2f, "Основание" to 2f, "Статус" to 1.4f, "Нужно / покрыто" to 1.4f)) {
                        usage.demands.forEach { d ->
                            val status = runCatching { DemandStatus.valueOf(d.status).localizedName() }.getOrDefault(d.status)
                            TableRow(
                                listOf(
                                    d.number to 1.2f,
                                    (d.projectName?.let { "Проект «$it»" } ?: demandSourceLabel(d.sourceType)) to 2f,
                                    status to 1.4f,
                                    "${ErpFormat.qty(d.quantity)} / ${ErpFormat.qty(d.coveredQuantity)} ${d.unit}" to 1.4f
                                ),
                                muted = d.status in setOf("COVERED", "CANCELLED")
                            )
                        }
                    }
                }
            }
            if (usage.purchaseOrders.isNotEmpty()) {
                CardSection("Заказы поставщикам", count = usage.purchaseOrders.size) {
                    CardTable(headers = listOf("Номер" to 1.2f, "Поставщик" to 2f, "Статус" to 1.4f, "Заказано / поставлено" to 1.4f)) {
                        usage.purchaseOrders.forEach { o ->
                            TableRow(
                                listOf(
                                    o.number to 1.2f,
                                    (o.supplierName ?: "—") to 2f,
                                    purchaseOrderStatusLabel(o.status) to 1.4f,
                                    "${ErpFormat.qty(o.quantity)} / ${ErpFormat.qty(o.deliveredQuantity)} ${o.unit}" to 1.4f
                                ),
                                muted = o.status in setOf("DELIVERED", "CLOSED", "CANCELLED")
                            )
                        }
                    }
                }
            }
            if (usage.productionOrders.isNotEmpty()) {
                CardSection("Производственные заказы", count = usage.productionOrders.size) {
                    CardTable(headers = listOf("Номер" to 1.4f, "Статус" to 1.4f, "План / израсходовано" to 2f)) {
                        usage.productionOrders.forEach { p ->
                            TableRow(
                                listOf(
                                    p.number to 1.4f,
                                    productionStatusLabel(p.status) to 1.4f,
                                    "${ErpFormat.qty(p.plannedQty)} / ${ErpFormat.qty(p.consumedQty)} ${p.unit}" to 2f
                                ),
                                muted = p.status in setOf("COMPLETED", "CLOSED", "CANCELLED")
                            )
                        }
                    }
                }
            }
        }
        is TabData.Failed -> ErrorMessageCard(error = data.error)
        else -> Loading()
    }
}

@Composable
private fun BomUsageSection(title: String, lines: List<BomWhereUsedItemDto>) {
    CardSection(title, count = lines.size) {
        CardTable(headers = listOf("Изделие" to 2.2f, "Ревизия" to 1.4f, "Кол-во" to 1f, "Уровень · поз." to 1f)) {
            lines.forEach { line ->
                TableRow(
                    listOf(
                        "${line.modelCode} · ${line.modelName}" to 2.2f,
                        "${line.revisionNumber} · ${line.revisionStatus.localizedName()}" to 1.4f,
                        "${ErpFormat.qty(line.quantity)} ${line.unit}" to 1f,
                        (listOfNotNull(line.level.toString(), line.position).joinToString(" · ")) to 1f
                    ),
                    muted = line.revisionStatus.name in setOf("REPLACED", "CANCELLED")
                )
            }
        }
    }
}

// --- Изделие ---

@Composable
private fun ProductTab(card: NomenclatureCardDto) {
    val model = card.productModel ?: return
    CardSection("Модель изделия") {
        Field("Код", model.code)
        Field("Наименование", model.name)
        Field("Статус разработки", model.status.localizedName())
        Field("Конструктор", model.designerName)
        Field("Технолог", model.technologistName)
    }
    CardSection("Ревизии состава (BOM)", count = model.revisions.size) {
        if (model.revisions.isEmpty()) Hint("Ревизий ещё нет. Их создают в разделе «НСИ и BOM».")
        model.revisions.forEach { rev ->
            Field(
                "Ревизия ${rev.revisionNumber}",
                rev.status.localizedName() + (rev.approvedAt?.let { " · утверждена ${ErpFormat.dateRu(it)}" } ?: "")
            )
        }
        Hint("Состав и разузлование открываются в разделе «НСИ и BOM».")
    }
}

// --- Документы и файлы ---

@Composable
private fun FilesTab(state: NomenclatureCardUiState, card: NomenclatureCardDto, canManage: Boolean, viewModel: NomenclatureCardViewModel) {
    androidx.compose.runtime.key(state.attachmentsVersion) {
        AttachmentsSection(
            entityType = AttachmentEntityType.NOMENCLATURE,
            entityId = card.item.id,
            canWrite = canManage,
            readOnly = card.item.archived
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Hint("Первое изображение среди вложений показывается в шапке карточки.", modifier = Modifier.weight(1f))
        TextButton(onClick = viewModel::refreshPhoto) { Text("Обновить фото и вложения") }
    }
    CardSection("Документы DMS") {
        when (val data = state.documents) {
            is TabData.Ready -> {
                if (data.value.isEmpty()) {
                    Hint("Связанных документов нет. Привяжите паспорт, сертификат или чертёж из реестра документов.")
                }
                data.value.forEach { doc ->
                    Field(doc.number, "${doc.name} · ${doc.docType.localizedName()} · ${doc.status.localizedName()}")
                }
                if (canManage && !card.item.archived) {
                    val linkedIds = data.value.map { it.id }.toSet()
                    val candidates = state.documentCatalog.filter { it.id !in linkedIds }
                    var selected by remember(card.item.id) { mutableStateOf<org.example.project.dto.DocumentDto?>(null) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        org.example.project.ui.components.SearchableDropdown(
                            items = candidates,
                            selected = selected,
                            onSelect = { selected = it },
                            label = "Документ из реестра",
                            itemText = { "${it.number} · ${it.name}" },
                            emptyText = "Нет доступных документов для привязки",
                            modifier = Modifier.weight(1f)
                        )
                        SoftButton(
                            text = "Привязать",
                            onClick = { selected?.let { viewModel.linkDocument(it.id) }; selected = null },
                            enabled = selected != null && !state.busy
                        )
                    }
                }
            }
            is TabData.Failed -> ErrorMessageCard(error = data.error)
            else -> Loading()
        }
    }
}

// --- История ---

@Composable
private fun HistoryTab(state: NomenclatureCardUiState) {
    when (val data = state.history) {
        is TabData.Ready -> {
            if (data.value.isEmpty()) {
                EmptyState("Изменений не зафиксировано")
                return
            }
            CardSection("История изменений", count = data.value.size) {
                data.value.forEachIndexed { index, entry ->
                    if (index > 0) HorizontalDivider()
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            ErpFormat.dateTimeRu(entry.createdAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(132.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            entry.details?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Text(entry.userName ?: "система", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        is TabData.Failed -> ErrorMessageCard(error = data.error)
        else -> Loading()
    }
}

// --- Общие элементы ---

@Composable
private fun TwoColumns(left: @Composable ColumnScope.() -> Unit, right: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth >= 900.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), content = left)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), content = right)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                left()
                right()
            }
        }
    }
}

@Composable
internal fun CardSection(
    title: String,
    count: Int? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (count != null) {
                Spacer(Modifier.width(8.dp))
                PillBadge(count.toString(), accent = Brand.Slate)
            }
            Spacer(Modifier.weight(1f))
            action?.invoke()
        }
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

/** Строка «параметр — значение»; пустое значение показывается прочерком, а не скрывается. */
@Composable
private fun Field(label: String, value: String?) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(180.dp))
        Text(
            value?.takeIf { it.isNotBlank() } ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            color = if (value.isNullOrBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
private fun Loading() {
    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
    }
}

@Composable
private fun CardTable(headers: List<Pair<String, Float>>, rows: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                headers.forEach { (title, weight) ->
                    Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(weight))
                }
            }
            rows()
        }
    }
}

@Composable
private fun TableRow(cells: List<Pair<String, Float>>, muted: Boolean = false) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
        cells.forEach { (text, weight) ->
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(weight).padding(end = 6.dp)
            )
        }
    }
}

private fun docTypeLabel(docType: String): String = when (docType.uppercase()) {
    "INITIAL_STOCK" -> "начальные остатки"
    "RECEIPT" -> "приёмка"
    "ISSUE" -> "выдача"
    "TRANSFER" -> "перемещение"
    "RETURN" -> "возврат"
    "WRITE_OFF" -> "списание"
    "INVENTORY" -> "инвентаризация"
    else -> docType.lowercase()
}

private fun demandSourceLabel(source: String): String = when (source) {
    "STOCKPILE" -> "Задел"
    "MANUAL" -> "Ручная"
    "PROJECT" -> "Проект"
    else -> source
}

private fun purchaseOrderStatusLabel(status: String): String = when (status) {
    "DRAFT" -> "Черновик"
    "PENDING_APPROVAL" -> "На согласовании"
    "APPROVED" -> "Согласован"
    "SENT" -> "Отправлен поставщику"
    "PARTIALLY_DELIVERED" -> "Частично поставлен"
    "DELIVERED" -> "Поставлен"
    "CLOSED" -> "Закрыт"
    "CANCELLED" -> "Отменён"
    else -> status
}

private fun productionStatusLabel(status: String): String = when (status) {
    "DRAFT" -> "Черновик"
    "PLANNED" -> "Запланирован"
    "IN_PROGRESS" -> "В работе"
    "ON_HOLD" -> "Приостановлен"
    "COMPLETED" -> "Выполнен"
    "CLOSED" -> "Закрыт"
    "CANCELLED" -> "Отменён"
    else -> status
}
