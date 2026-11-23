package org.example.project.ui.production

import org.example.project.ui.userText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import org.example.project.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.DetailRow
import org.example.project.ui.theme.ErpDialog
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.ErpTextField
import org.example.project.ui.theme.GradientButton
import org.example.project.ui.theme.GradientSpinner
import org.example.project.ui.theme.Gradients
import org.example.project.ui.theme.PillBadge
import org.example.project.ui.theme.SectionHeader
import org.example.project.ui.theme.SoftButton

@Composable
fun ProductionDialogs(
    state: ProductionUiState,
    viewModel: ProductionViewModel
) {
    val dialog = state.activeDialog ?: return
    when (dialog) {
        ProductionDialog.CreateOrder -> CreateOrderDialog(state, viewModel)
        ProductionDialog.CancelOrder -> CancelOrderDialog(state, viewModel)
        ProductionDialog.CompleteOrder -> CompleteOrderDialog(state, viewModel)
        ProductionDialog.HoldOrder -> HoldOrderDialog(state, viewModel)
        is ProductionDialog.SkipStage -> SkipStageDialog(dialog, state, viewModel)
        is ProductionDialog.InstanceDetail -> InstanceDetailDialog(dialog, state, viewModel)
        // Эти окна рисуются в ProductionScreen (NCR и диалоги материалов).
        ProductionDialog.CreateEngineeringChange,
        ProductionDialog.CreateNonconformity,
        is ProductionDialog.AllocateMaterial,
        is ProductionDialog.ConsumeMaterial -> { }
    }
}

@Composable
private fun CreateOrderDialog(state: ProductionUiState, viewModel: ProductionViewModel) {
    val form = state.createForm
    ErpDialog(
        title = "Новый производственный заказ",
        subtitle = "Ревизия BOM должна быть утверждена или действующей",
        glyph = ErpIconGlyph.Plus,
        gradient = Gradients.Aurora,
        onDismiss = viewModel::dismissDialog,
        confirmText = "Создать",
        onConfirm = viewModel::createOrder,
        confirmEnabled = !state.isActionRunning && state.models.isNotEmpty(),
        width = 620.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            state.operationError?.let { ErrorMessageCard(error = it) }
            if (state.modelsAccessDenied || state.models.isEmpty()) {
                Text(
                    text = "Справочник изделий недоступен (нужно право bom:read_any). " +
                        "Создать заказ нельзя, обратитесь к администратору.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.Rose
                )
            }
            ProjectDropdown(
                projects = state.projects,
                selectedId = form.projectId,
                onSelect = { viewModel.updateCreateForm(form.copy(projectId = it)) },
                allowEmpty = false,
                modifier = Modifier.fillMaxWidth()
            )
            ProductModelDropdown(
                models = state.models,
                selectedId = form.modelId,
                onSelect = viewModel::onModelChanged,
                modifier = Modifier.fillMaxWidth()
            )
            BomRevisionDropdown(
                revisions = state.revisions,
                selectedId = form.revisionId,
                onSelect = { viewModel.updateCreateForm(form.copy(revisionId = it)) },
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ErpTextField(
                    value = form.plannedQuantity,
                    onValueChange = { viewModel.updateCreateForm(form.copy(plannedQuantity = it)) },
                    label = "Количество к выпуску",
                    modifier = Modifier.weight(1f)
                )
                PriorityDropdown(
                    selected = form.priority,
                    onSelect = { viewModel.updateCreateForm(form.copy(priority = it)) },
                    modifier = Modifier.weight(1f)
                )
            }
            ResponsibleDropdown(
                users = state.users,
                selectedId = form.responsibleId,
                onSelect = { viewModel.updateCreateForm(form.copy(responsibleId = it)) },
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                org.example.project.ui.theme.ErpDateField(
                    value = form.plannedStartAt,
                    onValueChange = { viewModel.updateCreateForm(form.copy(plannedStartAt = it)) },
                    label = "План: начало",
                    format = org.example.project.ui.theme.DateValueFormat.START_OF_DAY_UTC,
                    modifier = Modifier.weight(1f)
                )
                org.example.project.ui.theme.ErpDateField(
                    value = form.plannedEndAt,
                    onValueChange = { viewModel.updateCreateForm(form.copy(plannedEndAt = it)) },
                    label = "План: окончание",
                    format = org.example.project.ui.theme.DateValueFormat.START_OF_DAY_UTC,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = "Этапы производства (по порядку)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            form.stageTypes.forEachIndexed { index, type ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${index + 1}.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(end = 2.dp)
                    )
                    StageTypeDropdown(
                        selected = type,
                        onSelect = { viewModel.updateStageType(index, it) },
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { viewModel.removeStage(index) },
                        enabled = form.stageTypes.size > 1
                    ) {
                        Text("Удалить")
                    }
                }
            }
            SoftButton(text = "Добавить этап", onClick = viewModel::addStage)
        }
    }
}

@Composable
private fun CancelOrderDialog(state: ProductionUiState, viewModel: ProductionViewModel) {
    ErpDialog(
        title = "Отмена заказа",
        subtitle = "Отмена возможна из черновика, плана и приостановки",
        glyph = ErpIconGlyph.Close,
        gradient = Gradients.Berry,
        onDismiss = viewModel::dismissDialog,
        confirmText = "Отменить заказ",
        onConfirm = viewModel::submitCancel,
        confirmGradient = org.example.project.ui.theme.Gradients.Danger,
        confirmEnabled = !state.isActionRunning,
        dismissText = "Назад"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.operationError?.let { ErrorMessageCard(error = it) }
            ErpTextField(
                value = state.cancelReason,
                onValueChange = viewModel::updateCancelReason,
                label = "Причина отмены",
                singleLine = false,
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun HoldOrderDialog(state: ProductionUiState, viewModel: ProductionViewModel) {
    ErpDialog(
        title = "Приостановка заказа",
        subtitle = "Укажите причину: она попадёт в журнал",
        glyph = ErpIconGlyph.Clock,
        gradient = Gradients.AmberGlow,
        onDismiss = viewModel::dismissDialog,
        confirmText = "Приостановить",
        onConfirm = viewModel::submitHold,
        confirmGradient = Gradients.AmberGlow,
        confirmEnabled = !state.isActionRunning
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.operationError?.let { ErrorMessageCard(error = it) }
            ErpTextField(
                value = state.noteReason,
                onValueChange = viewModel::updateNoteReason,
                label = "Причина",
                singleLine = false,
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun CompleteOrderDialog(state: ProductionUiState, viewModel: ProductionViewModel) {
    val order = state.selectedOrder
    val form = state.completeForm
    val expected = form.actualQuantity.trim().toIntOrNull() ?: 0
    val serialCount = form.serialNumbers.split('\n', ',', ';').count { it.isNotBlank() }
    ErpDialog(
        title = "Завершение выпуска",
        subtitle = order?.let { "Заказ ${it.number}: план ${it.plannedQuantity}" } ?: null,
        glyph = ErpIconGlyph.CheckCircle,
        gradient = Gradients.Emerald,
        onDismiss = viewModel::dismissDialog,
        confirmText = "Завершить выпуск",
        onConfirm = viewModel::submitComplete,
        confirmGradient = Gradients.Emerald,
        confirmEnabled = !state.isActionRunning,
        width = 560.dp
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.operationError?.let { ErrorMessageCard(error = it) }
            ErpTextField(
                value = form.actualQuantity,
                onValueChange = {
                    viewModel.updateCompleteForm(form.copy(actualQuantity = it))
                },
                label = "Фактически выпущено",
                modifier = Modifier.fillMaxWidth()
            )
            ErpTextField(
                value = form.serialNumbers,
                onValueChange = {
                    viewModel.updateCompleteForm(form.copy(serialNumbers = it))
                },
                label = "Серийные номера, по одному в строке",
                singleLine = false,
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Серийных номеров: $serialCount из $expected",
                style = MaterialTheme.typography.bodySmall,
                color = org.example.project.ui.theme.readableAccent(if (serialCount == expected && expected > 0) Brand.Teal else Brand.Amber)
            )
            Text(
                text = "Экземпляры изделий создаются только после завершения всех этапов",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SkipStageDialog(
    dialog: ProductionDialog.SkipStage,
    state: ProductionUiState,
    viewModel: ProductionViewModel
) {
    ErpDialog(
        title = "Пропуск этапа",
        subtitle = dialog.stageName,
        glyph = ErpIconGlyph.Alert,
        gradient = Gradients.AmberGlow,
        onDismiss = viewModel::dismissDialog,
        confirmText = "Пропустить этап",
        onConfirm = viewModel::submitSkip,
        confirmGradient = Gradients.AmberGlow,
        confirmEnabled = !state.isActionRunning
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.operationError?.let { ErrorMessageCard(error = it) }
            ErpTextField(
                value = state.skipReason,
                onValueChange = viewModel::updateSkipReason,
                label = "Основание пропуска",
                singleLine = false,
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun InstanceDetailDialog(
    dialog: ProductionDialog.InstanceDetail,
    state: ProductionUiState,
    viewModel: ProductionViewModel
) {
    val detail = state.selectedInstanceDetail
    ErpDialog(
        title = detail?.let { "Паспорт изделия ${it.serialNumber}" } ?: "Паспорт изделия #${dialog.instanceId}",
        subtitle = detail?.let { listOfNotNull(it.productModelCode, it.productModelName).joinToString(" · ").ifBlank { null } },
        glyph = ErpIconGlyph.Layers,
        gradient = Gradients.Ocean,
        onDismiss = viewModel::dismissDialog,
        dismissText = "Закрыть",
        width = 700.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 540.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            state.operationError?.let { ErrorMessageCard(error = it) }
            if (state.isInstanceDetailLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    GradientSpinner()
                }
            } else if (detail != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PillBadge(text = detail.status.productionCodeLabel(), accent = Brand.Teal)
                    detail.orderNumber?.let { PillBadge(text = "Заказ $it", accent = Brand.Indigo) }
                    if (detail.status != "ACCEPTED" && detail.status != "SHIPPED") {
                        GradientButton(
                            text = "Принять ОТК",
                            onClick = { viewModel.acceptInstance(detail.id) },
                            enabled = !state.isActionRunning,
                            gradient = Gradients.Aurora,
                            compact = true
                        )
                    }
                    if (detail.status == "ACCEPTED") {
                        GradientButton(
                            text = "Отгрузить",
                            onClick = { viewModel.shipInstance(detail.id) },
                            enabled = !state.isActionRunning,
                            gradient = Gradients.Ocean,
                            compact = true
                        )
                    }
                }
                DetailRow("Серийный номер", detail.serialNumber)
                DetailRow("Модель", listOfNotNull(detail.productModelCode, detail.productModelName).joinToString(" · ").ifBlank { "#${detail.productModelId}" })
                DetailRow("Изготовлен", detail.manufacturedAt.toDisplayDateTime())
                detail.acceptedAt?.let { DetailRow("Принят ОТК", it.toDisplayDateTime()) }
                detail.shippedAt?.let { DetailRow("Отгружен", it.toDisplayDateTime()) }

                SectionHeader(title = "Комплектующие", count = detail.components.size)
                if (detail.components.isEmpty()) {
                    Text("Состав комплектующих не зафиксирован.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    detail.components.forEach { comp ->
                        val title = listOfNotNull(comp.nomenclatureCode, comp.nomenclatureName).joinToString(" · ").ifBlank { "#${comp.nomenclatureId}" }
                        DetailRow(title, "${comp.quantity} шт. (${comp.installedAt.toDisplayDateTime()})")
                    }
                }

                SectionHeader(title = "Документация", count = detail.documents.size)
                val extrasRepo: org.example.project.data.ErpExtrasRepository = org.koin.compose.koinInject()
                val docScope = androidx.compose.runtime.rememberCoroutineScope()
                var attachedDocs by androidx.compose.runtime.remember(detail.id) {
                    androidx.compose.runtime.mutableStateOf<List<org.example.project.dto.InstanceDocumentDto>>(emptyList())
                }
                // Документ выбирается из реестра; привязывается его действующая версия.
                val documentRepo: org.example.project.data.DocumentRepository = org.koin.compose.koinInject()
                var documents by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf<List<org.example.project.dto.DocumentDto>>(emptyList())
                }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    val res = documentRepo.list()
                    if (res is org.example.project.data.ApiResult.Success) documents = res.data.filter { it.currentVersionId != null }
                }
                var selectedDoc by androidx.compose.runtime.remember(detail.id) {
                    androidx.compose.runtime.mutableStateOf<org.example.project.dto.DocumentDto?>(null)
                }
                var docAttachMsg by androidx.compose.runtime.remember(detail.id) { androidx.compose.runtime.mutableStateOf<String?>(null) }
                androidx.compose.runtime.LaunchedEffect(detail.id) {
                    val res = extrasRepo.listInstanceDocuments(detail.id)
                    if (res is org.example.project.data.ApiResult.Success) attachedDocs = res.data
                }
                if (detail.documents.isEmpty() && attachedDocs.isEmpty()) {
                    Text("Привязанные документы отсутствуют.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    detail.documents.forEach { doc ->
                        val ver = doc.documentVersionNumber?.let { "v$it" } ?: "#${doc.documentVersionId}"
                        DetailRow("${doc.docKind}: ${doc.documentTitle ?: "Документ"}", ver)
                    }
                    attachedDocs.forEach { doc ->
                        DetailRow("${doc.docKind}: ${doc.documentTitle}", "v${doc.versionNumber}")
                    }
                }
                docAttachMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    org.example.project.ui.components.SearchableDropdown(
                        items = documents,
                        selected = selectedDoc,
                        onSelect = { selectedDoc = it },
                        label = "Документ (действующая версия)",
                        itemText = { "${it.number} · ${it.name}" },
                        emptyText = "Нет документов с действующей версией",
                        modifier = Modifier.weight(1f)
                    )
                    SoftButton(text = "Привязать", onClick = {
                        val verId = selectedDoc?.currentVersionId
                        if (verId != null) {
                            docScope.launch {
                                when (val res = extrasRepo.attachInstanceDocument(
                                    detail.id,
                                    org.example.project.dto.AttachInstanceDocumentRequest(documentVersionId = verId)
                                )) {
                                    is org.example.project.data.ApiResult.Success -> {
                                        attachedDocs = listOf(res.data) + attachedDocs
                                        docAttachMsg = "Документ ${selectedDoc?.number.orEmpty()} привязан"
                                        selectedDoc = null
                                    }
                                    is org.example.project.data.ApiResult.Error -> docAttachMsg = res.error.userText()
                                }
                            }
                        }
                    })
                }

                SectionHeader(title = "Программное обеспечение", count = detail.software.size)
                if (detail.software.isEmpty()) {
                    Text("Сведения о прошивках и ПО отсутствуют.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    detail.software.forEach { sw ->
                        val buildText = sw.build?.let { " (build $it)" } ?: ""
                        DetailRow(sw.name, "${sw.version}$buildText")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ErpTextField(
                        value = state.instanceSoftwareName,
                        onValueChange = viewModel::updateInstanceSoftwareName,
                        label = "ПО / прошивка",
                        modifier = Modifier.weight(1.2f)
                    )
                    ErpTextField(
                        value = state.instanceSoftwareVersion,
                        onValueChange = viewModel::updateInstanceSoftwareVersion,
                        label = "Версия",
                        modifier = Modifier.weight(0.8f)
                    )
                    ErpTextField(
                        value = state.instanceSoftwareBuild,
                        onValueChange = viewModel::updateInstanceSoftwareBuild,
                        label = "Сборка",
                        modifier = Modifier.weight(0.7f)
                    )
                    GradientButton(
                        text = "+ ПО",
                        onClick = { viewModel.submitInstanceSoftware(detail.id) },
                        enabled = !state.isActionRunning && state.instanceSoftwareName.isNotBlank() && state.instanceSoftwareVersion.isNotBlank(),
                        gradient = Gradients.Ocean,
                        compact = true
                    )
                }

                SectionHeader(title = "Испытания", count = detail.tests.size)
                if (detail.tests.isEmpty()) {
                    Text("Протоколы испытаний отсутствуют.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    detail.tests.forEach { t ->
                        DetailRow(
                            "Испытание #${t.id} (${t.performedAt.toDisplayDateTime()})",
                            "${t.result.productionCodeLabel()}, тех. решение: ${t.technicalDecision.productionCodeLabel()}" + (t.notes?.let { ". $it" } ?: "")
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf("GOOD" to "Годно", "NOT_GOOD" to "Не годно").forEach { (code, label) ->
                        SoftButton(
                            text = label,
                            onClick = { viewModel.updateInstanceTestResult(code) },
                            tint = if (state.instanceTestResult == code) Brand.Blue else Brand.BlueDeep
                        )
                    }
                    ErpTextField(
                        value = state.instanceTestNotes,
                        onValueChange = viewModel::updateInstanceTestNotes,
                        label = "Примечание к испытанию",
                        modifier = Modifier.weight(1f)
                    )
                    GradientButton(
                        text = "Зафиксировать",
                        onClick = { viewModel.submitInstanceTest(detail.id) },
                        enabled = !state.isActionRunning,
                        gradient = Gradients.Aurora,
                        compact = true
                    )
                }

                SectionHeader(title = "Замечания", count = detail.remarks.size)
                if (detail.remarks.isEmpty()) {
                    Text("Замечания отсутствуют.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    detail.remarks.forEach { r ->
                        val blockBadge = if (r.blocksShipment) " [Блок отгрузки]" else ""
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                DetailRow("${r.severity.productionCodeLabel()} · ${r.status.productionCodeLabel()}$blockBadge", r.description)
                            }
                            if (r.status == "OPEN") {
                                SoftButton(
                                    text = "Снять",
                                    onClick = { viewModel.resolveInstanceRemark(detail.id, r.id) },
                                    tint = Brand.Teal
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf("MINOR" to "Незначительное", "CRITICAL" to "Критическое").forEach { (code, label) ->
                        SoftButton(
                            text = label,
                            onClick = { viewModel.updateInstanceRemarkSeverity(code) },
                            tint = if (state.instanceRemarkSeverity == code) Brand.Rose else Brand.BlueDeep
                        )
                    }
                    ErpTextField(
                        value = state.instanceRemarkDescription,
                        onValueChange = viewModel::updateInstanceRemarkDescription,
                        label = "Описание замечания",
                        modifier = Modifier.weight(1f)
                    )
                    GradientButton(
                        text = "+ Замечание",
                        onClick = { viewModel.submitInstanceRemark(detail.id) },
                        enabled = !state.isActionRunning && state.instanceRemarkDescription.isNotBlank(),
                        gradient = Gradients.AmberGlow,
                        compact = true
                    )
                }
            }
        }
    }
}
