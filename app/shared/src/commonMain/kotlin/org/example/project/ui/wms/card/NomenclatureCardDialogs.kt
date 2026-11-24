package org.example.project.ui.wms.card

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.data.ApiResult
import org.example.project.data.WmsRepository
import org.example.project.dto.ApiError
import org.example.project.dto.CreateNomenclatureAnalogRequest
import org.example.project.dto.DemandPriority
import org.example.project.dto.DemandSourceType
import org.example.project.dto.NomenclatureAttributeInput
import org.example.project.dto.NomenclatureCardDto
import org.example.project.dto.NomenclatureCategoryDto
import org.example.project.dto.NomenclatureDto
import org.example.project.dto.UpdateNomenclatureRequest
import org.example.project.ui.components.SearchableDropdown
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.theme.ErpDateField
import org.example.project.ui.theme.ErpDialog
import org.example.project.ui.theme.ErpFilterChip
import org.example.project.ui.theme.ErpFormat
import org.example.project.ui.theme.ErpIconButton
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.Gradients
import org.example.project.ui.theme.SoftButton
import org.example.project.ui.theme.TextButton
import org.example.project.ui.wms.localizedName
import org.koin.compose.koinInject

private data class AttributeDraft(val name: String, val value: String, val unit: String)

/**
 * Изменение карточки. Код, тип, единица и признаки учёта показаны, но не
 * редактируются (D-63): от них зависят остатки и проведённые документы.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditCardDialog(
    card: NomenclatureCardDto,
    state: NomenclatureCardUiState,
    onCreateCategory: (String, String, Long?, (NomenclatureCategoryDto) -> Unit) -> Unit,
    onSave: (UpdateNomenclatureRequest) -> Unit,
    onDismiss: () -> Unit
) {
    val item = card.item
    var name by remember { mutableStateOf(item.name) }
    var description by remember { mutableStateOf(item.description.orEmpty()) }
    var categoryId by remember { mutableStateOf(item.categoryId) }
    var article by remember { mutableStateOf(item.article.orEmpty()) }
    var manufacturer by remember { mutableStateOf(item.manufacturer.orEmpty()) }
    var manufacturerModel by remember { mutableStateOf(item.manufacturerModel.orEmpty()) }
    var weight by remember { mutableStateOf(item.weightKg.orEmpty()) }
    var length by remember { mutableStateOf(item.lengthMm.orEmpty()) }
    var width by remember { mutableStateOf(item.widthMm.orEmpty()) }
    var height by remember { mutableStateOf(item.heightMm.orEmpty()) }
    var storage by remember { mutableStateOf(item.storageConditions.orEmpty()) }
    var productModelId by remember { mutableStateOf(item.productModelId) }
    var minStock by remember { mutableStateOf(item.minStock) }
    var safetyStock by remember { mutableStateOf(item.safetyStock) }
    var leadTime by remember { mutableStateOf(item.leadTimeDays.toString()) }
    var minOrder by remember { mutableStateOf(item.minOrderQty) }
    var multiple by remember { mutableStateOf(item.orderMultiple) }
    val attributes = remember { mutableStateListOf<AttributeDraft>().apply { addAll(card.attributes.map { AttributeDraft(it.name, it.value, it.unit.orEmpty()) }) } }
    val warehouseIds = remember { mutableStateListOf<Long>().apply { addAll(card.allowedWarehouses.map { it.id }) } }
    var newCategoryOpen by remember { mutableStateOf(false) }

    val leadTimeValue = leadTime.trim().ifEmpty { "0" }.toIntOrNull()
    val valid = name.isNotBlank() && leadTimeValue != null && leadTimeValue >= 0

    ErpDialog(
        title = "Редактирование · ${item.code}",
        subtitle = "Код, тип, единица и признаки учёта не меняются: от них зависят остатки и проведённые документы.",
        glyph = ErpIconGlyph.Layers,
        gradient = Gradients.Emerald,
        width = 820.dp,
        maxBodyHeight = 640.dp,
        confirmText = if (state.busy) "Сохранение…" else "Сохранить",
        confirmEnabled = valid && !state.busy,
        onConfirm = {
            onSave(
                UpdateNomenclatureRequest(
                    version = item.version,
                    name = name,
                    description = description,
                    categoryId = categoryId,
                    article = article,
                    manufacturer = manufacturer,
                    manufacturerModel = manufacturerModel,
                    weightKg = weight,
                    lengthMm = length,
                    widthMm = width,
                    heightMm = height,
                    storageConditions = storage,
                    productModelId = productModelId,
                    minStock = minStock,
                    safetyStock = safetyStock,
                    leadTimeDays = leadTimeValue ?: 0,
                    minOrderQty = minOrder,
                    orderMultiple = multiple,
                    attributes = attributes.map { NomenclatureAttributeInput(it.name, it.value, it.unit) },
                    allowedWarehouseIds = warehouseIds.toList()
                )
            )
        },
        onDismiss = onDismiss
    ) {
        state.actionError?.let { ErrorMessageCard(error = it) }
        Text(
            "${item.type.localizedName()} · ед. ${item.unit}" +
                (if (item.lotTracked) " · партии" else "") + (if (item.serialTracked) " · серийные номера" else ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Section("Основное")
        OutlinedTextField(name, { name = it }, label = { Text("Наименование *") }, singleLine = true, isError = name.isBlank(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(description, { description = it }, label = { Text("Описание") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchableDropdown(
                items = state.categories.filter { !it.archived || it.id == categoryId },
                selected = state.categories.firstOrNull { it.id == categoryId },
                onSelect = { categoryId = it.id },
                label = "Категория",
                itemText = { it.path },
                emptyText = "Категорий пока нет",
                onClear = { categoryId = null },
                clearText = "Без категории",
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { newCategoryOpen = !newCategoryOpen }) { Text(if (newCategoryOpen) "Скрыть" else "Новая категория") }
        }
        if (newCategoryOpen) {
            NewCategoryRow(
                categories = state.categories,
                busy = state.busy,
                onCreate = { code, catName, parent ->
                    onCreateCategory(code, catName, parent) { created ->
                        categoryId = created.id
                        newCategoryOpen = false
                    }
                }
            )
        }
        Section("Производитель и параметры")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(manufacturer, { manufacturer = it }, label = { Text("Производитель") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(manufacturerModel, { manufacturerModel = it }, label = { Text("Модель производителя") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(article, { article = it }, label = { Text("Артикул") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(weight, { weight = it }, "Масса, кг", Modifier.weight(1f))
            NumberField(length, { length = it }, "Длина, мм", Modifier.weight(1f))
            NumberField(width, { width = it }, "Ширина, мм", Modifier.weight(1f))
            NumberField(height, { height = it }, "Высота, мм", Modifier.weight(1f))
        }
        Section("Характеристики")
        attributes.forEachIndexed { index, attr ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Правка берёт текущую строку в момент ввода, а не снимок композиции:
                // иначе быстрый ввод в соседнее поле затирает уже введённое.
                OutlinedTextField(attr.name, { v -> attributes[index] = attributes[index].copy(name = v) }, label = { Text("Название") }, singleLine = true, modifier = Modifier.weight(1.2f))
                OutlinedTextField(attr.value, { v -> attributes[index] = attributes[index].copy(value = v) }, label = { Text("Значение") }, singleLine = true, modifier = Modifier.weight(1.2f))
                OutlinedTextField(attr.unit, { v -> attributes[index] = attributes[index].copy(unit = v) }, label = { Text("Ед.") }, singleLine = true, modifier = Modifier.weight(0.6f))
                ErpIconButton(ErpIconGlyph.Close, "Удалить характеристику", onClick = { attributes.removeAt(index) })
            }
        }
        TextButton(onClick = { attributes.add(AttributeDraft("", "", "")) }) { Text("+ Добавить характеристику") }
        Section("Запас и закупка")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(minStock, { minStock = it }, "Мин. запас, ${item.unit}", Modifier.weight(1f))
            NumberField(safetyStock, { safetyStock = it }, "Страховой запас, ${item.unit}", Modifier.weight(1f))
            OutlinedTextField(
                leadTime, { leadTime = it.filter(Char::isDigit) },
                label = { Text("Срок поставки, дн.") }, singleLine = true, isError = leadTimeValue == null, modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(minOrder, { minOrder = it }, "Мин. партия, ${item.unit}", Modifier.weight(1f))
            NumberField(multiple, { multiple = it }, "Кратность заказа, ${item.unit}", Modifier.weight(1f))
        }
        Section("Хранение")
        OutlinedTextField(storage, { storage = it }, label = { Text("Условия хранения") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Text("Допустимые склады (справочно; пусто — без ограничений)", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            state.warehouses.forEach { wh ->
                val selected = wh.id in warehouseIds
                ErpFilterChip(
                    selected = selected,
                    onClick = { if (selected) warehouseIds.remove(wh.id) else warehouseIds.add(wh.id) },
                    label = { Text("${wh.code} · ${wh.name}") }
                )
            }
        }
        Section("Изделие")
        if (state.productModels.isEmpty()) {
            Text(
                "Список моделей недоступен: нужно право просмотра НСИ и BOM либо моделей ещё нет.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            SearchableDropdown(
                items = state.productModels,
                selected = state.productModels.firstOrNull { it.id == productModelId },
                onSelect = { productModelId = it.id },
                label = "Модель изделия",
                itemText = { "${it.code} · ${it.name}" },
                onClear = { productModelId = null },
                clearText = "Не связана",
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun NewCategoryRow(categories: List<NomenclatureCategoryDto>, busy: Boolean, onCreate: (String, String, Long?) -> Unit) {
    var code by remember { mutableStateOf("") }
    var catName by remember { mutableStateOf("") }
    var parent by remember { mutableStateOf<NomenclatureCategoryDto?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(code, { code = it }, label = { Text("Код") }, singleLine = true, modifier = Modifier.weight(0.7f))
            OutlinedTextField(catName, { catName = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.weight(1.3f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchableDropdown(
                items = categories.filter { !it.archived },
                selected = parent,
                onSelect = { parent = it },
                label = "Родительская категория",
                itemText = { it.path },
                onClear = { parent = null },
                clearText = "Верхний уровень",
                modifier = Modifier.weight(1f)
            )
            SoftButton(text = "Создать", onClick = { onCreate(code.trim(), catName.trim(), parent?.id) }, enabled = code.isNotBlank() && catName.isNotBlank() && !busy)
        }
    }
}

/** Потребность из карточки (D-63): ручная или на задел, количество предложено, но редактируемо. */
@Composable
internal fun DemandDialog(
    card: NomenclatureCardDto,
    busy: Boolean,
    error: ApiError?,
    idempotencyKey: String,
    onCreate: (DemandSourceType, String, String?, DemandPriority, String?, String) -> Unit,
    onDismiss: () -> Unit
) {
    val item = card.item
    val suggestion = card.demandSuggestion
    var source by remember { mutableStateOf(DemandSourceType.MANUAL) }
    var quantity by remember { mutableStateOf(suggestion?.suggestedQty ?: item.minOrderQty) }
    var requiredDate by remember { mutableStateOf(suggestion?.requiredDate.orEmpty()) }
    var priority by remember { mutableStateOf(DemandPriority.NORMAL) }
    var note by remember { mutableStateOf("") }
    val qtyValue = quantity.trim().replace(',', '.').toDoubleOrNull()
    val valid = qtyValue != null && qtyValue > 0 && (source != DemandSourceType.STOCKPILE || note.isNotBlank())

    ErpDialog(
        title = "Сформировать потребность",
        subtitle = "${item.code} · ${item.name}",
        glyph = ErpIconGlyph.Cart,
        gradient = Gradients.Primary,
        width = 600.dp,
        confirmText = if (busy) "Создание…" else "Создать черновик",
        confirmEnabled = valid && !busy,
        onConfirm = { onCreate(source, quantity, requiredDate, priority, note, idempotencyKey) },
        onDismiss = onDismiss
    ) {
        error?.let { ErrorMessageCard(error = it) }
        if (suggestion != null) {
            Text(
                "Предложено ${ErpFormat.qty(suggestion.suggestedQty)} ${item.unit}: до цели ${ErpFormat.qty(suggestion.targetQty)} " +
                    "не хватает ${ErpFormat.qty(suggestion.deficitQty)} с учётом доступного и ожидаемого; " +
                    "округлено до кратности ${ErpFormat.qty(item.orderMultiple)}, не меньше партии ${ErpFormat.qty(item.minOrderQty)}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text("Дефицита по порогам нет — укажите количество сами.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val openDemands = card.openDemands.ifEmpty { suggestion?.openDemands.orEmpty() }
        if (openDemands.isNotEmpty()) {
            Text(
                "По позиции уже есть открытые потребности: ${openDemands.joinToString(", ")}. " +
                    "Они не считаются поставкой, поэтому в расчёт не входят — проверьте, не дублируете ли вы их.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ErpFilterChip(selected = source == DemandSourceType.MANUAL, onClick = { source = DemandSourceType.MANUAL }, label = { Text("Ручная") })
            ErpFilterChip(selected = source == DemandSourceType.STOCKPILE, onClick = { source = DemandSourceType.STOCKPILE }, label = { Text("На задел") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                quantity, { quantity = it },
                label = { Text("Количество, ${item.unit}") }, singleLine = true, isError = qtyValue == null || qtyValue <= 0,
                modifier = Modifier.weight(1f)
            )
            ErpDateField(value = requiredDate, onValueChange = { requiredDate = it }, label = "Нужно к", modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Приоритет", style = MaterialTheme.typography.labelMedium)
            DemandPriority.entries.forEach { p ->
                ErpFilterChip(selected = priority == p, onClick = { priority = p }, label = { Text(ErpFormat.priorityRu(p.name)) })
            }
        }
        if (source == DemandSourceType.STOCKPILE) {
            OutlinedTextField(
                note, { note = it },
                label = { Text("Обоснование задела *") }, minLines = 2, isError = note.isBlank(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Text(
            "Потребность создаётся черновиком. Отправить её на согласование можно в разделе «Снабжение».",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun ArchiveDialog(
    card: NomenclatureCardDto,
    busy: Boolean,
    error: ApiError?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    ErpDialog(
        title = "Перевести позицию в архив?",
        glyph = ErpIconGlyph.Alert,
        gradient = Gradients.Danger,
        width = 520.dp,
        confirmText = if (busy) "Архивирование…" else "В архив",
        confirmGradient = Gradients.Danger,
        confirmEnabled = !busy,
        onConfirm = onConfirm,
        onDismiss = onDismiss
    ) {
        error?.let { ErrorMessageCard(error = it) }
        Text("${card.item.code} · ${card.item.name} перестанет предлагаться в новых документах. История, движения и связи сохранятся.")
        Text(
            "Сервер не даст архивировать позицию с остатком, резервами или в действующих BOM, открытых потребностях и заказах — список причин на вкладке «Применяемость».",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun AnalogDialog(
    card: NomenclatureCardDto,
    busy: Boolean,
    error: ApiError?,
    onCreate: (CreateNomenclatureAnalogRequest) -> Unit,
    onDismiss: () -> Unit
) {
    val wms: WmsRepository = koinInject()
    var candidates by remember { mutableStateOf<List<NomenclatureDto>>(emptyList()) }
    LaunchedEffect(card.item.id) {
        val existing = card.analogs.map { it.analogNomenclatureId }.toSet()
        (wms.listNomenclature() as? ApiResult.Success)?.let { result ->
            candidates = result.data.filter { !it.archived && it.id != card.item.id && it.id !in existing }
        }
    }
    var selected by remember { mutableStateOf<NomenclatureDto?>(null) }
    var factor by remember { mutableStateOf("1") }
    var condition by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("1") }
    var requiresApproval by remember { mutableStateOf(false) }
    val factorValue = factor.trim().replace(',', '.').toDoubleOrNull()
    val priorityValue = priority.trim().toIntOrNull()

    ErpDialog(
        title = "Добавить аналог",
        subtitle = "${card.item.code} · ${card.item.name}",
        glyph = ErpIconGlyph.Layers,
        gradient = Gradients.Emerald,
        width = 600.dp,
        confirmText = if (busy) "Добавление…" else "Добавить",
        confirmEnabled = selected != null && factorValue != null && factorValue > 0 && priorityValue != null && priorityValue >= 1 && !busy,
        onConfirm = {
            onCreate(
                CreateNomenclatureAnalogRequest(
                    analogNomenclatureId = selected!!.id,
                    conversionFactor = factor.trim().replace(',', '.'),
                    applicabilityCondition = condition,
                    priority = priorityValue ?: 1,
                    requiresApproval = requiresApproval
                )
            )
        },
        onDismiss = onDismiss
    ) {
        error?.let { ErrorMessageCard(error = it) }
        SearchableDropdown(
            items = candidates,
            selected = selected,
            onSelect = { selected = it },
            label = "Позиция-аналог",
            itemText = { "${it.code} · ${it.name}" },
            emptyText = "Нет подходящих активных позиций",
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                factor, { factor = it },
                label = { Text("Коэффициент: 1 ${card.item.unit} = ? аналога") }, singleLine = true,
                isError = factorValue == null || factorValue <= 0, modifier = Modifier.weight(1.4f)
            )
            OutlinedTextField(
                priority, { priority = it.filter(Char::isDigit) },
                label = { Text("Приоритет") }, singleLine = true, isError = priorityValue == null || priorityValue < 1,
                modifier = Modifier.weight(0.6f)
            )
        }
        OutlinedTextField(condition, { condition = it }, label = { Text("Условия применимости") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = requiresApproval, onCheckedChange = { requiresApproval = it })
            Text("Замена требует согласования", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(modifier = Modifier.fillMaxWidth())
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier) {
    val normalized = value.trim().replace(',', '.')
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = normalized.isNotEmpty() && normalized.toDoubleOrNull() == null,
        modifier = modifier
    )
}
