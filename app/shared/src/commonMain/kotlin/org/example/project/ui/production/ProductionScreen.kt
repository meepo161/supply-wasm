package org.example.project.ui.production

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.dto.ProductionOrderDto
import org.example.project.dto.ProductionOrderStatus
import org.example.project.dto.ProductionStageStatus
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.DetailRow
import org.example.project.ui.theme.EmptyState
import org.example.project.ui.theme.ErpIcon
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.GlassCard
import org.example.project.ui.theme.GradientButton
import org.example.project.ui.theme.GradientSpinner
import org.example.project.ui.theme.Gradients
import org.example.project.ui.theme.PageHeader
import org.example.project.ui.theme.PillBadge
import org.example.project.ui.theme.SectionHeader
import org.example.project.ui.theme.SoftButton

@Composable
fun ProductionScreen(
    viewModel: ProductionViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val canManage = viewModel.hasPermission()

    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(
            title = "Производство",
            glyph = ErpIconGlyph.Layers,
            gradient = Gradients.Aurora,
            subtitle = "Производственные заказы, этапы и выпущенные изделия",
            actions = {
                if (canManage) {
                    GradientButton(
                        text = "Новый заказ",
                        onClick = { viewModel.showDialog(ProductionDialog.CreateOrder) },
                        gradient = Gradients.Aurora,
                        leadingIcon = ErpIconGlyph.Plus,
                        compact = true
                    )
                }
            }
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OrdersPane(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.weight(0.42f).fillMaxHeight()
            )
            OrderDetailPane(
                state = state,
                viewModel = viewModel,
                canManage = canManage,
                modifier = Modifier.weight(0.58f).fillMaxHeight()
            )
        }
    }

    ProductionDialogs(state = state, viewModel = viewModel)
    MaterialDialogs(state = state, viewModel = viewModel)

    if (state.activeDialog == ProductionDialog.CreateNonconformity) {
        var category by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("PRODUCTION") }
        var severity by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(org.example.project.dto.NonconformitySeverity.MINOR)
        }
        var description by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
        var measures by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
        org.example.project.ui.theme.ErpDialog(
            title = "Новое несоответствие (NCR)",
            glyph = ErpIconGlyph.Layers,
            gradient = Gradients.Aurora,
            width = 640.dp,
            confirmText = "Зарегистрировать",
            onConfirm = { viewModel.createNonconformity(category, description, severity, measures) },
            confirmEnabled = description.isNotBlank() && !state.isActionRunning,
            confirmGradient = Gradients.Aurora,
            onDismiss = viewModel::dismissDialog
        ) {
            state.operationError?.let { ErrorMessageCard(error = it) }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                org.example.project.ui.components.SearchableDropdown(
                    items = nonconformityCategories,
                    selected = nonconformityCategories.firstOrNull { it.first == category },
                    onSelect = { category = it.first },
                    label = "Объект несоответствия",
                    itemText = { it.second },
                    modifier = Modifier.weight(1f)
                )
                org.example.project.ui.components.SearchableDropdown(
                    items = org.example.project.dto.NonconformitySeverity.entries,
                    selected = severity,
                    onSelect = { severity = it },
                    label = "Критичность",
                    itemText = { it.localizedName() },
                    modifier = Modifier.weight(1f)
                )
            }
            org.example.project.ui.theme.ErpTextField(
                value = description,
                onValueChange = { description = it },
                label = "Что обнаружено *",
                singleLine = false,
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            org.example.project.ui.theme.ErpTextField(
                value = measures,
                onValueChange = { measures = it },
                label = "Временные меры",
                singleLine = false,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun OrdersPane(
    state: ProductionUiState,
    viewModel: ProductionViewModel,
    modifier: Modifier = Modifier
) {
    GlassCard(modifier = modifier, contentPadding = 14.dp) {
        SectionHeader(
            title = "Заказы",
            count = state.orders.size,
            subtitle = "Всего в выборке",
            accent = Brand.Teal,
            action = { SoftButton(text = "Обновить", onClick = viewModel::loadOrders) }
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProductionStatusDropdown(
                selected = state.statusFilter,
                onSelect = viewModel::updateStatusFilter,
                modifier = Modifier.weight(1f)
            )
            ProjectDropdown(
                projects = state.projects,
                selectedId = state.projectFilter,
                onSelect = viewModel::updateProjectFilter,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(10.dp))
        if (state.selectedOrder == null) {
            state.successMessage?.let { message ->
                SuccessNote(text = message, onDismiss = viewModel::dismissSuccess)
                Spacer(Modifier.height(8.dp))
            }
            state.operationError?.let { error ->
                ErrorMessageCard(error = error)
                Spacer(Modifier.height(8.dp))
            }
        }
        when {
            state.isOrdersLoading && state.orders.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                GradientSpinner()
            }
            state.orders.isEmpty() -> EmptyState(
                title = "Заказов нет",
                text = "Создайте производственный заказ по утверждённой ревизии BOM",
                glyph = ErpIconGlyph.Layers,
                accent = Brand.Teal
            )
            else -> ProductionOrdersGrid(
                orders = state.orders,
                onClick = viewModel::selectOrder,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
    }
}

@Composable
private fun OrderDetailPane(
    state: ProductionUiState,
    viewModel: ProductionViewModel,
    canManage: Boolean,
    modifier: Modifier = Modifier
) {
    val order = state.selectedOrder
    GlassCard(modifier = modifier, contentPadding = 16.dp) {
        if (order == null) {
            EmptyState(
                title = "Выберите заказ",
                text = "Карточка покажет этапы, сроки и выпущенные изделия",
                glyph = ErpIconGlyph.Tasks,
                accent = Brand.Blue
            )
        } else {
            OrderHeader(order = order)
            Spacer(Modifier.height(12.dp))
            state.successMessage?.let { message ->
                SuccessNote(text = message, onDismiss = viewModel::dismissSuccess)
                Spacer(Modifier.height(8.dp))
            }
            if (state.activeDialog == null) {
                state.operationError?.let { error ->
                    ErrorMessageCard(error = error)
                    Spacer(Modifier.height(8.dp))
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
            ) {
                OrderFacts(order = order, state = state)
                OrderTotals(order = order)
                Spacer(Modifier.height(14.dp))
                SectionHeader(
                    title = "Этапы",
                    count = order.stages.size,
                    accent = Brand.Blue,
                    subtitle = "Порядок выполнения"
                )
                Spacer(Modifier.height(8.dp))
                if (order.stages.isEmpty()) {
                    EmptyState(
                        title = "Этапов нет",
                        text = "Список этапов заказа пуст",
                        glyph = ErpIconGlyph.Alert,
                        accent = Brand.Amber
                    )
                } else {
                    ProductionStagesGrid(
                        stages = order.stages,
                        onClick = { },
                        modifier = Modifier.fillMaxWidth().height(220.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    StageActions(order = order, viewModel = viewModel, canManage = canManage, state = state)
                }
                Spacer(Modifier.height(14.dp))
                SectionHeader(
                    title = "Выпущенные изделия",
                    count = state.instances.size,
                    accent = Brand.Mint,
                    subtitle = "Серийные номера"
                )
                Spacer(Modifier.height(8.dp))
                if (state.instances.isEmpty()) {
                    EmptyState(
                        title = "Изделий пока нет",
                        text = "Экземпляры создаются при завершении выпуска",
                        glyph = ErpIconGlyph.CheckCircle,
                        accent = Brand.Mint
                    )
                } else {
                    ProductInstancesGrid(
                        instances = state.instances,
                        onClick = { viewModel.openInstanceDetail(it.id) },
                        modifier = Modifier.fillMaxWidth().height(180.dp)
                    )
                }
                Spacer(Modifier.height(14.dp))
                SectionHeader(
                    title = "Материалы заказа (BOM)",
                    count = state.materials.size,
                    accent = Brand.Blue,
                    subtitle = "План, резерв и факт списания",
                    action = if (canManage && state.materials.isEmpty()) {
                        { SoftButton(text = "Сформировать из BOM", onClick = viewModel::initMaterialsFromBom) }
                    } else null
                )
                Spacer(Modifier.height(6.dp))
                if (state.materials.isEmpty()) {
                    Text(
                        "Материалы не сформированы. Нажмите «Сформировать из BOM»: норма возьмётся из ревизии заказа.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val materialsEditable = viewModel.canMoveStock() && order.status !in setOf(
                        ProductionOrderStatus.COMPLETED,
                        ProductionOrderStatus.CLOSED,
                        ProductionOrderStatus.CANCELLED
                    )
                    if (canManage && !viewModel.canMoveStock()) {
                        Text(
                            "Резерв и списание материалов выполняет сотрудник с правом «Склад: проведение».",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    state.materials.forEach { m ->
                        MaterialRow(
                            material = m,
                            editable = materialsEditable,
                            onAllocate = { viewModel.showDialog(ProductionDialog.AllocateMaterial(m.id)) },
                            onConsume = { viewModel.showDialog(ProductionDialog.ConsumeMaterial(m.id)) }
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                SectionHeader(
                    title = "Несоответствия (NCR)",
                    count = state.nonconformities.size,
                    accent = Brand.Rose,
                    subtitle = "Регистрация и корректирующие действия",
                    action = if (canManage) {
                        { SoftButton(text = "Создать NCR", onClick = { viewModel.showDialog(ProductionDialog.CreateNonconformity) }) }
                    } else null
                )
                Spacer(Modifier.height(6.dp))
                if (state.nonconformities.isEmpty()) {
                    Text(
                        "Несоответствий по заказу не зарегистрировано.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    state.nonconformities.forEach { nc ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${nc.number} · ${nc.description}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "${nc.category.nonconformityCategoryLabel()} · ${nc.severity.localizedName()} · ${nc.status.localizedName()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            val next = nc.status.nextStep()
                            if (canManage && next != null) {
                                SoftButton(
                                    text = if (next == org.example.project.dto.NonconformityStatus.CLOSED) "Закрыть" else "→ ${next.localizedName()}",
                                    onClick = { viewModel.transitionNonconformity(nc, next) }
                                )
                            }
                        }
                    }
                }
                if (state.engineeringChanges.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    SectionHeader(
                        title = "Инженерные изменения (ECN)",
                        count = state.engineeringChanges.size,
                        accent = Brand.Indigo,
                        subtitle = "Влияние смены ревизии"
                    )
                    state.engineeringChanges.forEach { ec ->
                        Text(
                            text = "${ec.status.localizedName()}: ${ec.impactSummary} (материалы: ${ec.materialDecision.localizedName()})",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            if (canManage) {
                Spacer(Modifier.height(10.dp))
                OrderActionRow(order = order, viewModel = viewModel)
            }
        }
    }
}

/** Ненавязчивое подтверждение выполненной команды. */
@Composable
private fun SuccessNote(text: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Brand.Mint.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Brand.Teal,
            modifier = Modifier.weight(1f)
        )
        SoftButton(text = "Ок", onClick = onDismiss)
    }
}

@Composable
private fun OrderHeader(order: ProductionOrderDto) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(Brush.linearGradient(Gradients.Aurora)),
            contentAlignment = Alignment.Center
        ) {
            ErpIcon(
                glyph = ErpIconGlyph.Layers,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = order.number,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "План выпуска: ${order.plannedQuantity}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        PillBadge(
            text = order.status.localizedName(),
            accent = order.status.accentColor(),
            filled = order.status == ProductionOrderStatus.IN_PROGRESS
        )
    }
}

@Composable
private fun OrderFacts(order: ProductionOrderDto, state: ProductionUiState) {
    val project = state.projects.firstOrNull { it.id == order.projectId }
    val model = state.models.firstOrNull { it.id == order.productModelId }
    val responsible = state.users.firstOrNull { it.id == order.responsibleId }
    DetailRow("Проект", project?.let { "${it.number} · ${it.name}" } ?: "Проект #${order.projectId}")
    DetailRow("Изделие", model?.let { "${it.code} · ${it.name}" } ?: "Изделие #${order.productModelId}")
    DetailRow("Ревизия BOM", state.orderRevisionLabel ?: "загрузка…")
    DetailRow("Ответственный", responsible?.fullName ?: "Сотрудник #${order.responsibleId}")
    DetailRow("Приоритет", order.priority.priorityLabel())
    DetailRow("План: начало", org.example.project.ui.theme.ErpFormat.dateRu(order.plannedStartAt))
    DetailRow("План: окончание", org.example.project.ui.theme.ErpFormat.dateRu(order.plannedEndAt))
}

@Composable
private fun OrderTotals(order: ProductionOrderDto) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TotalChip(
            label = "План",
            value = order.plannedQuantity.toString(),
            accent = Brand.Blue,
            modifier = Modifier.weight(1f)
        )
        TotalChip(
            label = "Выпущено",
            value = order.actualQuantity.toString(),
            accent = Brand.Mint,
            modifier = Modifier.weight(1f)
        )
        TotalChip(
            label = "Этапы закрыты",
            value = "${order.stages.count { it.status == ProductionStageStatus.COMPLETED || it.status == ProductionStageStatus.SKIPPED }} / ${order.stages.size}",
            accent = Brand.Indigo,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TotalChip(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(accent.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = accent
        )
    }
}

@Composable
private fun StageActions(
    order: ProductionOrderDto,
    viewModel: ProductionViewModel,
    canManage: Boolean,
    state: ProductionUiState
) {
    if (!canManage || !ProductionViewModel.canEditStages(order.status)) {
        Text(
            text = "Этапы меняются только в заказе «В производстве» или «Приостановлен»",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    val pending = order.stages.filter { it.status == ProductionStageStatus.PENDING }.sortedBy { it.sequence }
    val running = order.stages.filter { it.status == ProductionStageStatus.IN_PROGRESS }.sortedBy { it.sequence }
    if (pending.isEmpty() && running.isEmpty()) {
        Text(
            text = "Все этапы закрыты. Можно завершать выпуск.",
            style = MaterialTheme.typography.bodySmall,
            color = org.example.project.ui.theme.readableAccent(Brand.Teal)
        )
        return
    }
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        running.forEach { stage ->
            GradientButton(
                text = "Завершить «${stage.name}»",
                onClick = { viewModel.completeStage(stage.id) },
                gradient = Gradients.Emerald,
                compact = true,
                enabled = !state.isActionRunning
            )
        }
        pending.firstOrNull()?.let { stage ->
            GradientButton(
                text = "Запустить «${stage.name}»",
                onClick = { viewModel.startStage(stage.id) },
                gradient = Gradients.Ocean,
                compact = true,
                enabled = !state.isActionRunning
            )
            SoftButton(
                text = "Пропустить",
                onClick = { viewModel.openSkipStage(stage.id, stage.name) }
            )
        }
    }
}

@Composable
private fun OrderActionRow(order: ProductionOrderDto, viewModel: ProductionViewModel) {
    if (ProductionViewModel.canComplete(order.status) && order.stages.any {
            it.status == ProductionStageStatus.PENDING || it.status == ProductionStageStatus.IN_PROGRESS
        }
    ) {
        Text(
            text = "Завершить выпуск можно после закрытия всех этапов.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
    }
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when {
            ProductionViewModel.canPlan(order.status) -> GradientButton(
                text = "Запланировать",
                onClick = viewModel::planOrder,
                gradient = Gradients.Ocean,
                compact = true
            )
            ProductionViewModel.canStart(order.status) -> GradientButton(
                text = "Запустить производство",
                onClick = viewModel::startOrder,
                gradient = Gradients.Emerald,
                compact = true
            )
            ProductionViewModel.canComplete(order.status) -> {
                val stagesClosed = order.stages.all {
                    it.status == ProductionStageStatus.COMPLETED || it.status == ProductionStageStatus.SKIPPED
                }
                GradientButton(
                    text = "Завершить выпуск",
                    onClick = { viewModel.prepareComplete() },
                    gradient = Gradients.Emerald,
                    compact = true,
                    enabled = stagesClosed
                )
                SoftButton(
                    text = "Приостановить",
                    onClick = { viewModel.showDialog(ProductionDialog.HoldOrder) }
                )
            }
            ProductionViewModel.canResume(order.status) -> GradientButton(
                text = "Возобновить",
                onClick = viewModel::resumeOrder,
                gradient = Gradients.Ocean,
                compact = true
            )
            ProductionViewModel.canClose(order.status) -> GradientButton(
                text = "Закрыть заказ",
                onClick = viewModel::closeOrder,
                gradient = Gradients.Slate,
                compact = true
            )
        }
        if (ProductionViewModel.canCancel(order.status)) {
            SoftButton(
                text = "Отменить",
                onClick = { viewModel.showDialog(ProductionDialog.CancelOrder) },
                tint = Brand.Rose
            )
        }
        val printUriHandler = androidx.compose.ui.platform.LocalUriHandler.current
        SoftButton(
            text = "Печать наряда",
            onClick = { printUriHandler.openUri("${org.example.project.data.ApiServer.baseUrl}/api/analytics/print/production-order/${order.id}") }
        )
    }
}


/** Строка материала заказа: план, резерв, факт и действия оператора. */
@Composable
private fun MaterialRow(
    material: org.example.project.dto.ProductionOrderMaterialDto,
    editable: Boolean,
    onAllocate: () -> Unit,
    onConsume: () -> Unit
) {
    val qty = org.example.project.ui.theme.ErpFormat
    val planned = material.plannedQty.toDoubleOrNull() ?: 0.0
    val allocated = material.allocatedQty.toDoubleOrNull() ?: 0.0
    val consumed = material.consumedQty.toDoubleOrNull() ?: 0.0
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${material.nomenclatureCode} · ${material.nomenclatureName}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "план ${qty.qty(material.plannedQty)} · резерв ${qty.qty(material.allocatedQty)} · " +
                    "списано ${qty.qty(material.consumedQty)} ${material.unit}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (editable && allocated < planned) {
            SoftButton(text = "Зарезервировать", onClick = onAllocate)
        }
        if (editable && consumed < allocated) {
            SoftButton(text = "Списать", onClick = onConsume)
        }
    }
}

/** «40.0000» → «40»: количество для поля ввода. */
private fun plainQty(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

@Composable
private fun MaterialDialogs(state: ProductionUiState, viewModel: ProductionViewModel) {
    when (val dialog = state.activeDialog) {
        is ProductionDialog.AllocateMaterial -> {
            val material = state.materials.firstOrNull { it.id == dialog.materialId } ?: return
            val remaining = (material.plannedQty.toDoubleOrNull() ?: 0.0) - (material.allocatedQty.toDoubleOrNull() ?: 0.0)
            val wmsRepository: org.example.project.data.WmsRepository = org.koin.compose.koinInject()
            var warehouses by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf<List<org.example.project.dto.WarehouseDto>>(emptyList())
            }
            var warehouseId by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Long?>(null) }
            var quantity by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(plainQty(remaining.coerceAtLeast(0.0))) }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                val list = (wmsRepository.listWarehouses() as? org.example.project.data.ApiResult.Success)?.data.orEmpty()
                    .filter { it.active }
                warehouses = list
                if (warehouseId == null && list.size == 1) warehouseId = list.first().id
            }
            org.example.project.ui.theme.ErpDialog(
                title = "Резерв материала",
                subtitle = "${material.nomenclatureCode} · ${material.nomenclatureName}",
                glyph = ErpIconGlyph.Layers,
                gradient = Gradients.Ocean,
                onDismiss = viewModel::dismissDialog,
                confirmText = "Зарезервировать",
                onConfirm = { viewModel.allocateMaterial(material.id, warehouseId, quantity) },
                confirmGradient = Gradients.Ocean,
                confirmEnabled = !state.isActionRunning && warehouseId != null && quantity.isNotBlank()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.operationError?.let { ErrorMessageCard(error = it) }
                    Text(
                        text = "Осталось зарезервировать ${plainQty(remaining)} ${material.unit}. " +
                            "Резерв берётся со свободного остатка выбранного склада.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    org.example.project.ui.components.SearchableDropdown(
                        items = warehouses,
                        selected = warehouses.firstOrNull { it.id == warehouseId },
                        onSelect = { warehouseId = it.id },
                        label = "Склад *",
                        itemText = { "${it.code} · ${it.name}" },
                        emptyText = "Нет доступных складов",
                        modifier = Modifier.fillMaxWidth()
                    )
                    org.example.project.ui.theme.ErpTextField(
                        value = quantity,
                        onValueChange = { quantity = it },
                        label = "Количество, ${material.unit} *",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        is ProductionDialog.ConsumeMaterial -> {
            val material = state.materials.firstOrNull { it.id == dialog.materialId } ?: return
            val available = (material.allocatedQty.toDoubleOrNull() ?: 0.0) - (material.consumedQty.toDoubleOrNull() ?: 0.0)
            var quantity by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(plainQty(available.coerceAtLeast(0.0))) }
            org.example.project.ui.theme.ErpDialog(
                title = "Списание в производство",
                subtitle = "${material.nomenclatureCode} · ${material.nomenclatureName}",
                glyph = ErpIconGlyph.Layers,
                gradient = Gradients.Emerald,
                onDismiss = viewModel::dismissDialog,
                confirmText = "Списать",
                onConfirm = { viewModel.consumeMaterial(material.id, quantity) },
                confirmGradient = Gradients.Emerald,
                confirmEnabled = !state.isActionRunning && quantity.isNotBlank()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.operationError?.let { ErrorMessageCard(error = it) }
                    Text(
                        text = "Списать можно не больше зарезервированного: доступно ${plainQty(available)} ${material.unit}. " +
                            "Списание уменьшает складской остаток и резерв.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    org.example.project.ui.theme.ErpTextField(
                        value = quantity,
                        onValueChange = { quantity = it },
                        label = "Количество, ${material.unit} *",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        else -> Unit
    }
}
