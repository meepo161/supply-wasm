@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package org.example.project.ui.demand

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.example.project.data.ApiResult
import org.example.project.data.ErpExtrasRepository
import org.example.project.dto.DemandDetailDto
import org.example.project.dto.DemandHeaderDto
import org.example.project.dto.DemandLineStatus
import org.example.project.dto.DemandSourceType
import org.example.project.dto.DemandStatus
import org.example.project.dto.MergeDemandsRequest
import org.example.project.dto.ProjectDto
import org.example.project.dto.SupplierOfferDto
import org.example.project.dto.SupplierOfferStatus
import org.example.project.dto.UserDto
import org.example.project.dto.hasPermission
import org.example.project.ui.components.ListViewMode
import org.example.project.ui.components.ViewModeToggle
import org.example.project.ui.home.currentIsoDate
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.ErpDateField
import org.example.project.ui.theme.ErpDialog
import org.example.project.ui.theme.ErpFilterChip
import org.example.project.ui.theme.ErpFormat
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.ErpTextField
import org.example.project.ui.theme.GlassCard
import org.example.project.ui.theme.GradientButton
import org.example.project.ui.theme.GradientSpinner
import org.example.project.ui.theme.Gradients
import org.example.project.ui.theme.PageHeader
import org.example.project.ui.theme.PillBadge
import org.example.project.ui.theme.SectionHeader
import org.example.project.ui.theme.SoftButton
import org.example.project.ui.userText
import org.koin.compose.koinInject

@Composable
fun DemandScreen(
    viewModel: DemandViewModel,
    currentUser: UserDto,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(
            title = if (state.currentPage == DemandPage.DETAIL && state.selectedDemand != null) {
                "Заявка ${state.selectedDemand?.header?.number.orEmpty()}"
            } else {
                state.currentPage.title
            },
            glyph = ErpIconGlyph.Cart,
            gradient = Gradients.Sunset,
            subtitle = if (state.currentPage == DemandPage.DETAIL) {
                "Единый пульт заявки: позиции, предложения со счетами, выбор, оплата и склад"
            } else {
                "Пульт заявок, предложения со счетами, оплата и поступление на склад"
            },
            onBack = if (state.currentPage != DemandPage.LIST) viewModel::goBack else null
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(bottom = 20.dp)
        ) {
            when (state.currentPage) {
                DemandPage.LIST -> DemandListPage(state, viewModel)
                DemandPage.DETAIL -> DemandDetailPage(state, viewModel)
            }
        }
    }

    when (state.activeDialog) {
        DemandDialog.CreateDemand -> CreateDemandDialog(
            form = state.createDemandForm,
            projects = state.projects,
            models = state.models,
            revisions = state.revisions,
            nomenclature = state.nomenclature,
            canCreateNomenclature = currentUser.hasPermission("wms", "manage"),
            isLoading = state.isLoading,
            error = state.operationError,
            onFormChange = viewModel::updateCreateDemandForm,
            onSourceTypeChange = viewModel::onSourceTypeChanged,
            onToggleProjectBomMode = viewModel::toggleProjectBomMode,
            onProjectSelected = viewModel::onProjectSelected,
            onModelSelected = viewModel::onModelSelected,
            onRevisionSelected = viewModel::onRevisionSelected,
            onAddLine = viewModel::addDemandLine,
            onUpdateLine = viewModel::updateDemandLine,
            onRemoveLine = viewModel::removeDemandLine,
            onNomenclatureCreated = viewModel::appendNomenclature,
            onCreate = viewModel::createDemand,
            onDismiss = viewModel::dismissDialog
        )
        DemandDialog.CancelDemand -> CancelDemandDialog(
            reason = state.cancelReason,
            error = state.operationError,
            onReasonChange = viewModel::updateCancelReason,
            onCancel = viewModel::cancelDemand,
            onDismiss = viewModel::dismissDialog
        )
        DemandDialog.CreateOffer -> DemandCreateOfferDialog(state, viewModel)
        null -> { }
    }
}

/* ---------------------------------------------------------------------------
 * Список заявок на снабжение
 * --------------------------------------------------------------------------- */

@Composable
private fun DemandListPage(
    state: DemandUiState,
    viewModel: DemandViewModel
) {
    val extrasRepo: ErpExtrasRepository = koinInject()
    val mergeScope = rememberCoroutineScope()
    var showMergeDialog by remember { mutableStateOf(false) }
    var selectedToMerge by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var mergeTitle by remember { mutableStateOf("") }
    var mergeError by remember { mutableStateOf<String?>(null) }
    var viewMode by remember { mutableStateOf(ListViewMode.CARDS) }

    val stageCounts = remember(state.demands, state.offersCountByDemand, state.offersCountLoaded) {
        val counts = mutableMapOf<DemandStageFilter, Int>()
        DemandStageFilter.entries.forEach { counts[it] = 0 }
        counts[DemandStageFilter.ALL] = state.demands.size
        state.demands.forEach { d ->
            val offersCount = if (state.offersCountLoaded) state.offersCountByDemand[d.id] ?: 0 else null
            val stage = demandStage(d, offersCount)
            counts[stage] = (counts[stage] ?: 0) + 1
        }
        counts
    }

    val filteredDemands = remember(
        state.demands,
        state.stageFilter,
        state.searchQuery,
        state.projects,
        state.offersCountByDemand,
        state.offersCountLoaded
    ) {
        val q = state.searchQuery.trim().lowercase()
        state.demands.filter { d ->
            val offersCount = if (state.offersCountLoaded) state.offersCountByDemand[d.id] ?: 0 else null
            val stageMatches = state.stageFilter == DemandStageFilter.ALL || demandStage(d, offersCount) == state.stageFilter
            if (!stageMatches) return@filter false
            if (q.isEmpty()) return@filter true
            val project = d.projectId?.let { id -> state.projects.firstOrNull { it.id == id } }
            listOfNotNull(
                d.number,
                d.itemsSummary,
                d.quantitySummary,
                d.initiatorName,
                d.selectedSupplierName,
                d.stockpileNote,
                project?.number,
                project?.name
            ).any { it.lowercase().contains(q) }
        }
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
            Text(
                text = "Показано: ${filteredDemands.size} из ${state.demands.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ViewModeToggle(
                    current = viewMode,
                    supported = setOf(ListViewMode.CARDS, ListViewMode.TABLE),
                    onChange = { viewMode = it }
                )
                if (viewModel.hasPermission("create") && state.demands.size >= 2) {
                    SoftButton(text = "Объединить", onClick = {
                        selectedToMerge = emptySet()
                        mergeTitle = ""
                        mergeError = null
                        showMergeDialog = true
                    })
                }
                SoftButton(text = "Обновить", onClick = viewModel::loadDemands, leadingIcon = ErpIconGlyph.Refresh)
                if (viewModel.hasPermission("create")) {
                    GradientButton(
                        text = "Новая потребность",
                        onClick = { viewModel.showDialog(DemandDialog.CreateDemand) },
                        gradient = Gradients.Sunset,
                        leadingIcon = ErpIconGlyph.Plus
                    )
                }
            }
        }

        ErpTextField(
            value = state.searchQuery,
            onValueChange = viewModel::updateSearchQuery,
            label = "Поиск по номеру, составу, проекту или поставщику",
            glyph = ErpIconGlyph.Search,
            modifier = Modifier.fillMaxWidth()
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DemandStageFilter.entries.forEach { filter ->
                val count = stageCounts[filter] ?: 0
                ErpFilterChip(
                    selected = state.stageFilter == filter,
                    onClick = { viewModel.updateStageFilter(filter) },
                    label = { Text("${filter.label} ($count)") }
                )
            }
        }

        state.successMessage?.let { msg ->
            SuccessBanner(text = msg, onDismiss = viewModel::dismissSuccess)
        }
        if (state.activeDialog == null) {
            state.operationError?.let { ErrorMessageCard(error = it) }
        }

        when {
            state.isLoading && state.demands.isEmpty() -> LoadingOverlay()
            state.demands.isEmpty() -> EmptyState("Заявок пока нет. Создайте первую потребность.")
            filteredDemands.isEmpty() -> EmptyState("По текущему фильтру заявок не найдено.")
            viewMode == ListViewMode.TABLE -> DemandsGrid(
                demands = filteredDemands,
                projects = state.projects,
                offersCountByDemand = state.offersCountByDemand,
                offersCountLoaded = state.offersCountLoaded,
                onClick = { viewModel.selectDemand(it.id) },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
            else -> {
                val todayIso = currentIsoDate()
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredDemands.size, key = { filteredDemands[it].id }) { idx ->
                        val d = filteredDemands[idx]
                        val offersCount = if (state.offersCountLoaded) state.offersCountByDemand[d.id] ?: 0 else null
                        DemandRegistryCard(
                            demand = d,
                            projects = state.projects,
                            offersCount = offersCount,
                            todayIso = todayIso,
                            onClick = { viewModel.selectDemand(d.id) }
                        )
                    }
                }
            }
        }
    }

    if (showMergeDialog) {
        ErpDialog(
            title = "Объединение потребностей",
            glyph = ErpIconGlyph.Cart,
            gradient = Gradients.Sunset,
            width = 600.dp,
            confirmText = "Объединить",
            onConfirm = {
                if (selectedToMerge.size < 2) {
                    mergeError = "Выберите минимум 2 потребности"
                } else {
                    mergeScope.launch {
                        when (val res = extrasRepo.mergeDemands(
                            MergeDemandsRequest(
                                sourceDemandIds = selectedToMerge.toList(),
                                targetTitle = mergeTitle.ifBlank { null }
                            )
                        )) {
                            is ApiResult.Success -> {
                                showMergeDialog = false
                                viewModel.loadDemands()
                            }
                            is ApiResult.Error -> mergeError = res.error.userText()
                        }
                    }
                }
            },
            confirmGradient = Gradients.Sunset,
            onDismiss = { showMergeDialog = false }
        ) {
            mergeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            ErpTextField(
                value = mergeTitle,
                onValueChange = { mergeTitle = it },
                label = "Название объединённой потребности"
            )
            state.demands.filter {
                it.status == DemandStatus.DRAFT ||
                    it.status == DemandStatus.PENDING_APPROVAL ||
                    it.status == DemandStatus.APPROVED
            }.take(15).forEach { d ->
                val checked = d.id in selectedToMerge
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { isChecked ->
                            selectedToMerge = if (isChecked) selectedToMerge + d.id else selectedToMerge - d.id
                        }
                    )
                    Text(
                        listOfNotNull(d.number, d.status.localizedName(), d.itemsSummary).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun DemandRegistryCard(
    demand: DemandHeaderDto,
    projects: List<ProjectDto>,
    offersCount: Int?,
    todayIso: String,
    onClick: () -> Unit
) {
    val openDays = demandOpenDays(demand.createdAt, todayIso)
    val atRisk = isDemandAtRisk(demand.status, openDays)
    val stage = demandStage(demand, offersCount)
    val stageLabel = demandStageLabel(demand, offersCount)
    val stageAccent = demandStageAccent(demand, offersCount)
    val dateBadge = demandRequiredDateBadge(demand.requiredDate, demand.status, todayIso)
    val projectText = demand.projectId?.let { id ->
        projects.firstOrNull { it.id == id }?.let { "${it.number} · ${it.name}" } ?: "Проект №$id"
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = 14.dp
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = demand.number,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Brand.Blue
                    )
                    Text(
                        text = projectText ?: demand.sourceType.localizedName(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (demand.priority.name != "NORMAL") {
                        PillBadge(
                            text = demand.priority.localizedName(),
                            accent = if (demand.priority.name == "CRITICAL") Brand.Rose else Brand.Amber
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (atRisk) {
                        PillBadge(text = "Открыта $openDays дн.", accent = Brand.Rose)
                    }
                    PillBadge(
                        text = stageLabel,
                        accent = stageAccent,
                        filled = stage == DemandStageFilter.WAITING_PAYMENT || stage == DemandStageFilter.CLOSED
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = demand.itemsSummary ?: "Состав не указан",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                demand.quantitySummary?.let { qty ->
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = qty,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Инициатор: ${demand.initiatorName ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (demand.requiredDate != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Срок: ${ErpFormat.dateRu(demand.requiredDate)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        dateBadge?.let { (badgeText, badgeColor) ->
                            PillBadge(text = badgeText, accent = badgeColor)
                        }
                    }
                }
                demand.stockpileNote?.takeIf { it.isNotBlank() }?.let { note ->
                    Text(
                        text = "Комментарий: $note",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/* ---------------------------------------------------------------------------
 * Единый пульт потребности (всё на одном экране)
 * --------------------------------------------------------------------------- */

@Composable
private fun DemandDetailPage(
    state: DemandUiState,
    viewModel: DemandViewModel
) {
    val demand = state.selectedDemand
    if (demand == null) {
        LoadingOverlay()
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        state.successMessage?.let { msg ->
            SuccessBanner(text = msg, onDismiss = viewModel::dismissSuccess)
        }
        if (state.activeDialog == null) {
            state.operationError?.let { ErrorMessageCard(error = it) }
        }

        DemandCockpitHeader(
            demand = demand,
            state = state,
            viewModel = viewModel
        )

        DemandLinesBlock(
            demand = demand,
            state = state
        )

        DemandOffersBlock(
            demand = demand,
            state = state,
            viewModel = viewModel
        )

        DemandPaymentAndWarehouseBlock(
            demand = demand,
            state = state,
            viewModel = viewModel
        )

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun DemandCockpitHeader(
    demand: DemandDetailDto,
    state: DemandUiState,
    viewModel: DemandViewModel
) {
    val header = demand.header
    val todayIso = currentIsoDate()
    val projectText = header.projectId?.let { id ->
        state.projects.firstOrNull { it.id == id }?.let { "${it.number} · ${it.name}" } ?: "Проект №$id"
    }
    val hasSelectedOffer = demand.offers.any { it.status == SupplierOfferStatus.SELECTED } ||
        header.selectedSupplierName != null
    val isPaid = header.isPaid || demand.orders.any { it.paidAt != null }
    val paidAt = header.paidAt ?: demand.orders.firstNotNullOfOrNull { it.paidAt }
    val canSelect = viewModel.canSelectOfferFor(header.initiatorId)
    val openDays = demandOpenDays(header.createdAt, todayIso)
    val dateBadge = demandRequiredDateBadge(header.requiredDate, header.status, todayIso)
    val activeOffersCount = demand.offers.count {
        it.status != SupplierOfferStatus.WITHDRAWN &&
            it.status != SupplierOfferStatus.REJECTED &&
            it.status != SupplierOfferStatus.EXPIRED
    }

    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = "${header.number} · ${projectText ?: header.sourceType.localizedName()}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = listOfNotNull(
                            "Инициатор: ${header.initiatorName ?: "—"}",
                            "Приоритет: ${header.priority.localizedName()}",
                            header.requiredDate?.let { "Срок: ${ErpFormat.dateRu(it)}" }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    dateBadge?.let { (label, color) -> PillBadge(text = label, accent = color) }
                    if (isDemandAtRisk(header.status, openDays)) {
                        PillBadge(text = "В работе $openDays дн.", accent = Brand.Rose)
                    }
                    PillBadge(
                        text = demandStageLabel(header, activeOffersCount),
                        accent = demandStageAccent(header, activeOffersCount),
                        filled = true
                    )
                }
            }

            if (!header.stockpileNote.isNullOrBlank()) {
                val noteTitle = if (header.sourceType == DemandSourceType.STOCKPILE) {
                    "Обоснование задела"
                } else {
                    "Примечание"
                }
                Text(
                    text = "$noteTitle: ${header.stockpileNote}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            header.sourceBomRevisionId?.let {
                Text(
                    text = "Создана из спецификации (BOM ревизия №$it, выпуск: ${header.bomReleaseQuantity ?: "—"})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Визуальный 5-шаговый степпер
            DemandProgressStepper(
                header = header,
                offersCount = demand.offers.size,
                hasSelectedOffer = hasSelectedOffer,
                isPaid = isPaid
            )

            HorizontalDivider()

            // Контекстная подсказка и главное действие
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    when {
                        header.status == DemandStatus.CANCELLED -> {
                            StatusHintBanner(
                                text = "Заявка отменена",
                                accent = Brand.Rose
                            )
                        }
                        header.status == DemandStatus.COVERED -> {
                            StatusHintBanner(
                                text = "Все позиции приняты на склад ✓",
                                accent = Brand.Mint
                            )
                        }
                        isPaid -> {
                            StatusHintBanner(
                                text = "Оплачено (${ErpFormat.dateTimeRu(paidAt)}) · Ожидает приёмки на складе",
                                accent = Brand.Teal
                            )
                        }
                        hasSelectedOffer -> {
                            val info = listOfNotNull(header.selectedSupplierName, header.selectedGrossTotal?.let { ErpFormat.money(it) })
                                .joinToString(", ")
                            StatusHintBanner(
                                text = if (viewModel.canOrder()) {
                                    "Вариант выбран ($info) — оплатите счёт и отметьте оплату для передачи на склад"
                                } else {
                                    "Вариант выбран ($info) — ожидает оплаты снабжением"
                                },
                                accent = Brand.Orange
                            )
                        }
                        demand.offers.isNotEmpty() -> {
                            StatusHintBanner(
                                text = if (canSelect) {
                                    "Снабжение загрузило предложения со счетами — выберите подходящий вариант ниже в 1 клик"
                                } else {
                                    "Предложения загружены (${demand.offers.size}) — ожидается выбор ${header.initiatorName ?: "инициатора"}"
                                },
                                accent = Brand.Amber
                            )
                        }
                        else -> {
                            StatusHintBanner(
                                text = if (viewModel.canOffer()) {
                                    "Заявка ждёт предложений от поставщиков — прикрепите вариант со счётом"
                                } else {
                                    "Заявка передана снабжению — ожидается загрузка предложений со счетами"
                                },
                                accent = Brand.Blue
                            )
                        }
                    }
                }

                Spacer(Modifier.width(12.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Исторические статусы на случай старых заявок
                    if (header.status.canSubmit() && viewModel.hasPermission("update")) {
                        GradientButton(
                            text = "Отправить",
                            onClick = viewModel::submitDemand,
                            gradient = Gradients.Ocean,
                            compact = true
                        )
                    }
                    if (header.status.canApprove() && viewModel.hasPermission("approve")) {
                        GradientButton(
                            text = "Согласовать",
                            onClick = viewModel::approveDemand,
                            gradient = Gradients.Emerald,
                            compact = true
                        )
                    }
                    if (header.status.canCancel() && demand.offers.isEmpty() && viewModel.hasPermission("update")) {
                        SoftButton(
                            text = "Отменить",
                            onClick = { viewModel.showDialog(DemandDialog.CancelDemand) },
                            tint = Brand.Rose
                        )
                    }

                    // Главное действие по текущему шагу
                    if (header.status != DemandStatus.CANCELLED && header.status != DemandStatus.COVERED) {
                        when {
                            demand.offers.isEmpty() && viewModel.canOffer() -> {
                                GradientButton(
                                    text = "+ Внести предложение со счётом",
                                    onClick = viewModel::openCreateOfferDialog,
                                    gradient = Gradients.Primary,
                                    enabled = !state.isActionRunning
                                )
                            }
                            hasSelectedOffer && !isPaid && viewModel.canOrder() -> {
                                GradientButton(
                                    text = if (state.isActionRunning) "Сохранение…" else "✓ Отметить «Оплачено»",
                                    onClick = viewModel::markDemandPaid,
                                    gradient = Gradients.Emerald,
                                    enabled = !state.isActionRunning
                                )
                            }
                        }
                    }
                    SoftButton(
                        text = "Обновить",
                        onClick = viewModel::refreshSelectedDemand,
                        leadingIcon = ErpIconGlyph.Refresh
                    )
                }
            }
        }
    }
}

private enum class StepState { DONE, ACTIVE, PENDING }

@Composable
private fun DemandProgressStepper(
    header: DemandHeaderDto,
    offersCount: Int,
    hasSelectedOffer: Boolean,
    isPaid: Boolean
) {
    if (header.status == DemandStatus.CANCELLED) {
        StatusHintBanner(text = "Заявка отменена — цепочка остановлена", accent = Brand.Rose)
        return
    }
    val step1 = if (header.status == DemandStatus.DRAFT || header.status == DemandStatus.PENDING_APPROVAL) {
        StepState.ACTIVE
    } else {
        StepState.DONE
    }
    val step2 = when {
        step1 != StepState.DONE -> StepState.PENDING
        offersCount > 0 || hasSelectedOffer || isPaid || header.status == DemandStatus.COVERED -> StepState.DONE
        else -> StepState.ACTIVE
    }
    val step3 = when {
        hasSelectedOffer || isPaid || header.status == DemandStatus.COVERED -> StepState.DONE
        offersCount > 0 -> StepState.ACTIVE
        else -> StepState.PENDING
    }
    val step4 = when {
        isPaid || header.status == DemandStatus.COVERED -> StepState.DONE
        hasSelectedOffer -> StepState.ACTIVE
        else -> StepState.PENDING
    }
    val step5 = when {
        header.status == DemandStatus.COVERED -> StepState.DONE
        header.status == DemandStatus.PARTIALLY_COVERED || isPaid -> StepState.ACTIVE
        else -> StepState.PENDING
    }
    val step5Label = if (header.status == DemandStatus.PARTIALLY_COVERED) {
        "5. Частично на складе"
    } else {
        "5. Принято на склад"
    }

    val steps = listOf(
        "1. Заявка создана" to step1,
        "2. Предложения ($offersCount)" to step2,
        "3. Выбор ведущего" to step3,
        "4. Оплачено" to step4,
        step5Label to step5
    )

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        steps.forEach { (title, stepState) ->
            val icon = when (stepState) {
                StepState.DONE -> "✓"
                StepState.ACTIVE -> "◉"
                StepState.PENDING -> "○"
            }
            val accent = when (stepState) {
                StepState.DONE -> Brand.Mint
                StepState.ACTIVE -> Brand.Blue
                StepState.PENDING -> Brand.Slate
            }
            val bg = when (stepState) {
                StepState.DONE -> Brand.Mint.copy(alpha = 0.14f)
                StepState.ACTIVE -> Brand.Blue.copy(alpha = 0.14f)
                StepState.PENDING -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            }
            val border = when (stepState) {
                StepState.DONE -> Brand.Mint.copy(alpha = 0.45f)
                StepState.ACTIVE -> Brand.Blue.copy(alpha = 0.7f)
                StepState.PENDING -> MaterialTheme.colorScheme.outlineVariant
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(bg)
                    .border(1.dp, border, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = icon,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (stepState == StepState.ACTIVE) FontWeight.Bold else FontWeight.Medium,
                    color = if (stepState == StepState.PENDING) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        }
    }
}

@Composable
private fun StatusHintBanner(text: String, accent: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.10f))
            .border(1.dp, accent.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/* ---------------------------------------------------------------------------
 * Блок 1: Что нужно купить (позиции + свободный остаток на складе)
 * --------------------------------------------------------------------------- */

@Composable
private fun DemandLinesBlock(
    demand: DemandDetailDto,
    state: DemandUiState
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 16.dp) {
        SectionHeader(
            title = "Что нужно купить",
            count = demand.lines.size,
            accent = Brand.Blue,
            subtitle = "Потребность по позициям и свободный остаток на складе"
        )
        Spacer(Modifier.height(10.dp))
        if (demand.lines.isEmpty()) {
            Text(
                text = "Позиции в заявке не указаны.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@GlassCard
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            demand.lines.forEach { line ->
                val nomCode = line.nomenclatureCode?.takeIf { it.isNotBlank() }
                val nomName = line.nomenclatureName?.takeIf { it.isNotBlank() }
                val nom = state.nomenclature.firstOrNull { it.id == line.nomenclatureId }
                val nomTitle = when {
                    nomCode != null && nomName != null -> "$nomCode · $nomName"
                    nomName != null -> nomName
                    nomCode != null -> nomCode
                    nom != null -> "${nom.code} · ${nom.name}"
                    else -> "Номенклатура №${line.nomenclatureId}"
                }
                val freeStock = demand.stockByNomenclature[line.nomenclatureId] ?: "0"
                val hasStock = freeStock.toDoubleSafe() > 0.0
                val remaining = line.deficitQuantity ?: run {
                    val rem = (line.quantity.toDoubleSafe() - line.coveredQuantity.toDoubleSafe()).coerceAtLeast(0.0)
                    rem.toString()
                }
                val lineAccent = when (line.status) {
                    DemandLineStatus.COVERED -> Brand.Mint
                    DemandLineStatus.PARTIALLY_COVERED -> Brand.Amber
                    DemandLineStatus.CANCELLED -> Brand.Slate
                    DemandLineStatus.OPEN -> Brand.Blue
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "${line.lineNo}. $nomTitle",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Нужно: ${ErpFormat.qty(line.quantity)} ${line.unit}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Свободно на складе: ${ErpFormat.qty(freeStock)} ${line.unit}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (hasStock) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (hasStock) Brand.Teal else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Принято по заявке: ${ErpFormat.qty(line.coveredQuantity)} ${line.unit}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Осталось получить: ${ErpFormat.qty(remaining)} ${line.unit}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = if (remaining.toDoubleSafe() > 0.0) Brand.Orange else Brand.Mint
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    PillBadge(
                        text = line.status.localizedName(),
                        accent = lineAccent,
                        filled = line.status == DemandLineStatus.COVERED
                    )
                }
            }
        }
    }
}

/* ---------------------------------------------------------------------------
 * Блок 2: Предложения поставщиков и счета (внутри карточки потребности)
 * --------------------------------------------------------------------------- */

@Composable
private fun DemandOffersBlock(
    demand: DemandDetailDto,
    state: DemandUiState,
    viewModel: DemandViewModel
) {
    val header = demand.header
    val notClosed = header.status != DemandStatus.COVERED && header.status != DemandStatus.CANCELLED
    val isPaid = header.isPaid || demand.orders.any { it.paidAt != null }
    val anySelected = demand.offers.any { it.status == SupplierOfferStatus.SELECTED }
    val canSelect = viewModel.canSelectOfferFor(header.initiatorId)

    val cheapestOfferId = remember(demand.offers) {
        demand.offers
            .filter {
                it.status == SupplierOfferStatus.RECEIVED ||
                    it.status == SupplierOfferStatus.SHORTLISTED ||
                    it.status == SupplierOfferStatus.SELECTED
            }
            .mapNotNull { offer ->
                val total = offer.totals?.grossTotal?.toDoubleSafe() ?: return@mapNotNull null
                offer.id to total
            }
            .minByOrNull { it.second }
            ?.first
    }

    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 16.dp) {
        SectionHeader(
            title = "Предложения поставщиков и счета",
            count = demand.offers.size,
            accent = Brand.Indigo,
            subtitle = "Сравнивайте варианты, открывайте прикреплённые счета и выбирайте в 1 клик",
            action = {
                if (viewModel.canOffer() && notClosed) {
                    SoftButton(
                        text = "+ Внести предложение",
                        onClick = viewModel::openCreateOfferDialog,
                        leadingIcon = ErpIconGlyph.Plus,
                        tint = Brand.Blue,
                        enabled = !state.isActionRunning
                    )
                }
            }
        )
        Spacer(Modifier.height(10.dp))

        if (demand.offers.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Предложений пока нет. Снабжение прикрепит сюда варианты со счетами поставщиков.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (viewModel.canOffer() && notClosed) {
                    GradientButton(
                        text = "+ Внести предложение со счётом",
                        onClick = viewModel::openCreateOfferDialog,
                        compact = true
                    )
                }
            }
            return@GlassCard
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val twoColumns = maxWidth >= 820.dp && demand.offers.size >= 2
            if (twoColumns) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    demand.offers.chunked(2).forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            pair.forEach { offer ->
                                Box(modifier = Modifier.weight(1f)) {
                                    OfferCockpitCard(
                                        offer = offer,
                                        state = state,
                                        isCheapest = offer.id == cheapestOfferId,
                                        anySelected = anySelected,
                                        canSelect = canSelect && notClosed,
                                        isPaid = isPaid,
                                        onSelectOffer = { viewModel.selectOffer(offer) },
                                        onClearSelection = { viewModel.clearOfferSelection(offer) },
                                        onPreviewInvoice = { viewModel.openOfferInvoice(offer, preview = true) },
                                        onDownloadInvoice = { viewModel.openOfferInvoice(offer, preview = false) }
                                    )
                                }
                            }
                            if (pair.size == 1) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    demand.offers.forEach { offer ->
                        OfferCockpitCard(
                            offer = offer,
                            state = state,
                            isCheapest = offer.id == cheapestOfferId,
                            anySelected = anySelected,
                            canSelect = canSelect && notClosed,
                            isPaid = isPaid,
                            onSelectOffer = { viewModel.selectOffer(offer) },
                            onClearSelection = { viewModel.clearOfferSelection(offer) },
                            onPreviewInvoice = { viewModel.openOfferInvoice(offer, preview = true) },
                            onDownloadInvoice = { viewModel.openOfferInvoice(offer, preview = false) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OfferCockpitCard(
    offer: SupplierOfferDto,
    state: DemandUiState,
    isCheapest: Boolean,
    anySelected: Boolean,
    canSelect: Boolean,
    isPaid: Boolean,
    onSelectOffer: () -> Unit,
    onClearSelection: () -> Unit,
    onPreviewInvoice: () -> Unit,
    onDownloadInvoice: () -> Unit
) {
    val isSelected = offer.status == SupplierOfferStatus.SELECTED
    val borderColor = if (isSelected) Brand.Mint else MaterialTheme.colorScheme.outlineVariant
    val bgColor = if (isSelected) {
        Brand.Mint.copy(alpha = 0.08f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
    }
    val selectable = !anySelected &&
        (offer.status == SupplierOfferStatus.RECEIVED || offer.status == SupplierOfferStatus.SHORTLISTED)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(if (isSelected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = offer.supplierName ?: "Поставщик №${offer.supplierId}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = offer.number,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isSelected) {
                    PillBadge(text = "✓ Выбрано ведущим", accent = Brand.Mint, filled = true)
                }
                if (isCheapest) {
                    PillBadge(text = "Минимальная цена", accent = Brand.Blue)
                }
                if (offer.status in setOf(
                        SupplierOfferStatus.REJECTED,
                        SupplierOfferStatus.WITHDRAWN,
                        SupplierOfferStatus.EXPIRED
                    )
                ) {
                    PillBadge(
                        text = when (offer.status) {
                            SupplierOfferStatus.REJECTED -> "Отклонено"
                            SupplierOfferStatus.WITHDRAWN -> "Отозвано"
                            SupplierOfferStatus.EXPIRED -> "Истекло"
                            else -> offer.status.name
                        },
                        accent = Brand.Rose
                    )
                }
            }
        }

        // Крупно итог с НДС
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = ErpFormat.money(offer.totals?.grossTotal, offer.currency),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) Brand.Teal else MaterialTheme.colorScheme.onSurface
            )
            offer.totals?.let { t ->
                Text(
                    text = "без НДС: ${ErpFormat.money(t.netTotal, offer.currency)} · НДС: ${ErpFormat.money(t.vatTotal, offer.currency)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Срок поставки: ${offer.deliveryDays?.let { "$it дн." } ?: "не указан"}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Оплата: ${offer.paymentTerms ?: "—"}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Действует до: ${ErpFormat.dateRu(offer.validUntil)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (offer.validUntilPassed) Brand.Rose else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        offer.note?.takeIf { it.isNotBlank() }?.let { note ->
            Text(
                text = "Комментарий: $note",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (offer.lines.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                offer.lines.forEach { line ->
                    val lineLabel = when {
                        !line.nomenclatureName.isNullOrBlank() && !line.nomenclatureCode.isNullOrBlank() ->
                            "${line.nomenclatureCode} · ${line.nomenclatureName}"
                        !line.nomenclatureName.isNullOrBlank() -> line.nomenclatureName.orEmpty()
                        else -> nomenclatureLabel(state.nomenclature, line.nomenclatureId)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = lineLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${ErpFormat.qty(line.quantity)} ${line.unit} × ${ErpFormat.money(line.unitPrice, offer.currency)} = ${ErpFormat.money(line.lineTotal, offer.currency)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Кнопки: Открыть счёт + Выбрать в 1 клик
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (offer.invoiceDocumentId != null || !offer.invoiceFileName.isNullOrBlank() || !offer.invoiceNumber.isNullOrBlank()) {
                val invoiceTitle = offer.invoiceNumber?.takeIf { it.isNotBlank() }
                    ?: offer.invoiceFileName?.takeIf { it.isNotBlank() }
                    ?: "PDF"
                SoftButton(
                    text = "📄 Открыть счёт ($invoiceTitle)",
                    onClick = onPreviewInvoice,
                    tint = Brand.Blue,
                    enabled = !state.isActionRunning
                )
                SoftButton(
                    text = "Скачать",
                    onClick = onDownloadInvoice,
                    enabled = !state.isActionRunning
                )
            }
            if (selectable && canSelect) {
                GradientButton(
                    text = "✓ Выбрать это предложение",
                    onClick = onSelectOffer,
                    gradient = Gradients.Emerald,
                    compact = true,
                    enabled = !state.isActionRunning
                )
            }
            if (isSelected && !isPaid && canSelect) {
                SoftButton(
                    text = "Снять выбор",
                    onClick = onClearSelection,
                    tint = Brand.Rose,
                    enabled = !state.isActionRunning
                )
            }
        }
    }
}

/* ---------------------------------------------------------------------------
 * Блок 3: Оплата и поступление на склад
 * --------------------------------------------------------------------------- */

@Composable
private fun DemandPaymentAndWarehouseBlock(
    demand: DemandDetailDto,
    state: DemandUiState,
    viewModel: DemandViewModel
) {
    val header = demand.header
    val selectedOffer = demand.offers.firstOrNull { it.status == SupplierOfferStatus.SELECTED }
    val hasSelectedOffer = selectedOffer != null || header.selectedSupplierName != null
    val isPaid = header.isPaid || demand.orders.any { it.paidAt != null }
    if (!hasSelectedOffer && !isPaid && demand.orders.isEmpty() && demand.receipts.isEmpty()) {
        return
    }

    val order = demand.orders.firstOrNull()
    val paidAt = header.paidAt ?: order?.paidAt
    val paidByName = order?.paidByName
    val supplierName = header.selectedSupplierName ?: selectedOffer?.supplierName ?: order?.supplierName ?: "—"
    val grossTotal = header.selectedGrossTotal ?: selectedOffer?.totals?.grossTotal ?: order?.totals?.grossTotal

    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 16.dp) {
        SectionHeader(
            title = "Оплата и поступление на склад",
            accent = Brand.Mint,
            subtitle = "Статус расчёта с поставщиком и приёмки товара складом"
        )
        Spacer(Modifier.height(10.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Поставщик: $supplierName",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        grossTotal?.let {
                            Text(
                                text = "Сумма: ${ErpFormat.money(it, selectedOffer?.currency ?: order?.currency ?: "RUB")}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Text(
                            text = "Заказ: ${order?.number ?: "будет оформлен при оплате"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (selectedOffer != null && (selectedOffer.invoiceDocumentId != null || !selectedOffer.invoiceFileName.isNullOrBlank())) {
                    SoftButton(
                        text = "📄 Открыть счёт",
                        onClick = { viewModel.openOfferInvoice(selectedOffer, preview = true) },
                        tint = Brand.Blue
                    )
                }
            }

            if (isPaid) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Brand.Mint.copy(alpha = 0.12f))
                        .border(1.dp, Brand.Mint.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Оплачено ${ErpFormat.dateTimeRu(paidAt)}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Brand.Teal
                        )
                        paidByName?.let {
                            Text(
                                text = "Отметил: $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    PillBadge(text = "Оплачено ✓", accent = Brand.Mint, filled = true)
                }
            } else if (viewModel.canOrder() && header.status != DemandStatus.CANCELLED) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Brand.Orange.copy(alpha = 0.08f))
                        .border(1.dp, Brand.Orange.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Ожидается оплата выбранного счёта",
                        style = MaterialTheme.typography.labelLarge,
                        color = Brand.Orange
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ErpDateField(
                            value = state.paymentExpectedAt,
                            onValueChange = viewModel::updatePaymentExpectedAt,
                            label = "Плановая дата прихода (необяз.)",
                            modifier = Modifier.weight(1f)
                        )
                        ErpTextField(
                            value = state.paymentNote,
                            onValueChange = viewModel::updatePaymentNote,
                            label = "Примечание к оплате (необяз.)",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        GradientButton(
                            text = if (state.isActionRunning) "Сохранение…" else "✓ Отметить «Оплачено»",
                            onClick = viewModel::markDemandPaid,
                            gradient = Gradients.Emerald,
                            enabled = !state.isActionRunning
                        )
                    }
                }
            }

            // Связанные приёмки склада
            Text(
                text = "Поступление на склад",
                style = MaterialTheme.typography.labelLarge
            )
            if (demand.receipts.isEmpty()) {
                StatusHintBanner(
                    text = if (isPaid) {
                        "Ждёт прихода на складе — кладовщик увидит оплаченный заказ в очереди приёмки"
                    } else {
                        "Ждёт прихода на складе после оплаты"
                    },
                    accent = Brand.Slate
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    demand.receipts.forEach { receipt ->
                        val (statusText, statusAccent) = when (receipt.status.uppercase()) {
                            "POSTED" -> "Принято на склад ✓" to Brand.Mint
                            "CANCELLED" -> "Отменена" to Brand.Rose
                            else -> "Ожидает размещения" to Brand.Amber
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "${receipt.number} · ${receipt.warehouseName ?: "Склад №${receipt.warehouseId}"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                val dateInfo = receipt.postedAt?.let { "проведено ${ErpFormat.dateTimeRu(it)}" }
                                    ?: receipt.expectedAt?.let { "ожидается ${ErpFormat.dateRu(it)}" }
                                if (dateInfo != null) {
                                    Text(
                                        text = dateInfo,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            PillBadge(
                                text = statusText,
                                accent = statusAccent,
                                filled = receipt.status.equals("POSTED", ignoreCase = true)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuccessBanner(text: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Brand.Mint.copy(alpha = 0.14f))
            .border(1.dp, Brand.Mint.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
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
private fun LoadingOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        GradientSpinner()
    }
}

@Composable
private fun EmptyState(text: String, modifier: Modifier = Modifier) {
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
