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

    if (state.activeDialog == ProductionDialog.CreateNonconformity) {
        var category by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("PRODUCTION") }
        var description by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = { Text("Новое несоответствие (NCR)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    org.example.project.ui.theme.ErpTextField(
                        value = category,
                        onValueChange = { category = it },
                        label = "Категория"
                    )
                    org.example.project.ui.theme.ErpTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = "Описание несоответствия"
                    )
                }
            },
            confirmButton = {
                GradientButton(
                    text = "Создать",
                    onClick = { viewModel.createNonconformity(category, description) },
                    gradient = Gradients.Aurora,
                    compact = true
                )
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = viewModel::dismissDialog) { Text("Отмена") }
            }
        )
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
        state.successMessage?.let { message ->
            SuccessNote(text = message, onDismiss = viewModel::dismissSuccess)
            Spacer(Modifier.height(8.dp))
        }
        state.operationError?.let { error ->
            ErrorMessageCard(error = error)
            Spacer(Modifier.height(8.dp))
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
                    StageActions(order = order, viewModel = viewModel, canManage = canManage)
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
                    action = if (canManage) {
                        { SoftButton(text = "Из BOM", onClick = viewModel::initMaterialsFromBom) }
                    } else null
                )
                Spacer(Modifier.height(6.dp))
                if (state.materials.isEmpty()) {
                    Text(
                        "Материалы не сформированы. Нажмите «Из BOM».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    state.materials.forEach { m ->
                        Text(
                            text = "${m.nomenclatureCode} ${m.nomenclatureName}: план ${m.plannedQty} / резерв ${m.allocatedQty} / факт ${m.consumedQty} ${m.unit}",
                            style = MaterialTheme.typography.bodySmall
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
                            Text(
                                text = "${nc.number} [${nc.status.name}] ${nc.description}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            if (canManage && nc.status != org.example.project.dto.NonconformityStatus.CLOSED) {
                                val next = when (nc.status) {
                                    org.example.project.dto.NonconformityStatus.REGISTERED -> org.example.project.dto.NonconformityStatus.EVALUATION
                                    org.example.project.dto.NonconformityStatus.EVALUATION -> org.example.project.dto.NonconformityStatus.CORRECTIVE_ACTION
                                    org.example.project.dto.NonconformityStatus.ASSIGNED -> org.example.project.dto.NonconformityStatus.CORRECTIVE_ACTION
                                    org.example.project.dto.NonconformityStatus.ROOT_CAUSE_ANALYSIS -> org.example.project.dto.NonconformityStatus.CORRECTIVE_ACTION
                                    org.example.project.dto.NonconformityStatus.CORRECTIVE_ACTION -> org.example.project.dto.NonconformityStatus.EFFECTIVENESS_CHECK
                                    org.example.project.dto.NonconformityStatus.EFFECTIVENESS_CHECK -> org.example.project.dto.NonconformityStatus.CLOSED
                                    org.example.project.dto.NonconformityStatus.CLOSED -> org.example.project.dto.NonconformityStatus.CLOSED
                                }
                                SoftButton(
                                    text = "Далее",
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
                            text = "#${ec.id} [${ec.status.name}] ${ec.impactSummary} (${ec.materialDecision.name})",
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
    DetailRow("Проект", project?.let { "${it.number} — ${it.name}" } ?: order.projectId.toString())
    DetailRow("Изделие", model?.let { "${it.code} — ${it.name}" } ?: order.productModelId.toString())
    DetailRow("Ревизия BOM", order.bomRevisionId.toString())
    DetailRow("Ответственный", responsible?.fullName ?: order.responsibleId.toString())
    DetailRow("Приоритет", order.priority)
    DetailRow("План: начало", order.plannedStartAt.toDisplayDateTime())
    DetailRow("План: окончание", order.plannedEndAt.toDisplayDateTime())
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
    canManage: Boolean
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        running.firstOrNull()?.let { stage ->
            GradientButton(
                text = "Завершить «${stage.name}»",
                onClick = { viewModel.completeStage(stage.id) },
                gradient = Gradients.Emerald,
                compact = true
            )
        }
        pending.firstOrNull()?.let { stage ->
            GradientButton(
                text = "Запустить «${stage.name}»",
                onClick = { viewModel.startStage(stage.id) },
                gradient = Gradients.Ocean,
                compact = true
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
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
            ProductionViewModel.canHold(order.status) -> SoftButton(
                text = "Приостановить",
                onClick = { viewModel.showDialog(ProductionDialog.HoldOrder) }
            )
            ProductionViewModel.canResume(order.status) -> GradientButton(
                text = "Возобновить",
                onClick = viewModel::resumeOrder,
                gradient = Gradients.Ocean,
                compact = true
            )
            ProductionViewModel.canComplete(order.status) -> GradientButton(
                text = "Завершить выпуск",
                onClick = { viewModel.prepareComplete() },
                gradient = Gradients.Emerald,
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
            onClick = { printUriHandler.openUri("http://localhost:8080/api/analytics/print/production-order/${order.id}") }
        )
    }
}
