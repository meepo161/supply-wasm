package org.example.project.ui.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import org.example.project.ui.theme.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.example.project.data.AnalyticsRepository
import org.example.project.data.ApiResult
import org.example.project.data.ApprovalRepository
import org.example.project.data.DemandRepository
import org.example.project.data.ErpExtrasRepository
import org.example.project.data.WmsRepository
import org.example.project.dto.AnalyticsSummaryDto
import org.example.project.dto.ApiError
import org.example.project.dto.ApprovalRouteDto
import org.example.project.dto.CalendarEventDto
import org.example.project.dto.CounterpartyDto
import org.example.project.dto.CounterpartyKind
import org.example.project.dto.CreateCounterpartyRequest
import org.example.project.dto.CreateDemandRequest
import org.example.project.dto.CsvImportRequest
import org.example.project.dto.DemandLineInput
import org.example.project.dto.DemandSourceType
import org.example.project.dto.LowStockItemDto
import org.example.project.dto.ProcurementPlanItemDto
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.projects.localizedName
import org.example.project.ui.approvals.localizedName
import org.example.project.ui.tasks.localizedName
import org.example.project.ui.theme.DetailRow
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.GlassCard
import org.example.project.ui.theme.Gradients
import org.example.project.ui.theme.PageHeader
import org.example.project.ui.theme.PillBadge
import org.example.project.ui.theme.SoftButton
import org.koin.compose.koinInject

private enum class AnalyticsTab(val label: String) {
    SUMMARY("Сводка и экспорт"),
    CALENDAR("Календарь сроков"),
    LOW_STOCK("Дефицит склада"),
    PROCUREMENT("План закупок"),
    CRM("Контрагенты"),
    APPROVALS("Мои согласования")
}

@Composable
fun AnalyticsScreen(modifier: Modifier = Modifier) {
    val analyticsRepository: AnalyticsRepository = koinInject()
    val wmsRepository: WmsRepository = koinInject()
    val approvalRepository: ApprovalRepository = koinInject()
    val extrasRepository: ErpExtrasRepository = koinInject()
    val demandRepository: DemandRepository = koinInject()
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(AnalyticsTab.SUMMARY) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<ApiError?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    var showCsvImport by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<AnalyticsSummaryDto?>(null) }
    var calendar by remember { mutableStateOf<List<CalendarEventDto>>(emptyList()) }
    var lowStock by remember { mutableStateOf<List<LowStockItemDto>>(emptyList()) }
    var procurementPlan by remember { mutableStateOf<List<ProcurementPlanItemDto>>(emptyList()) }
    var counterparties by remember { mutableStateOf<List<CounterpartyDto>>(emptyList()) }
    var showCreateCounterparty by remember { mutableStateOf(false) }
    var approvalQueue by remember { mutableStateOf<List<ApprovalRouteDto>>(emptyList()) }

    fun reload() {
        loading = true
        error = null
        scope.launch {
            when (val res = analyticsRepository.getSummary()) {
                is ApiResult.Success -> summary = res.data
                is ApiResult.Error -> error = res.error
            }
            when (val cal = analyticsRepository.getCalendar()) {
                is ApiResult.Success -> calendar = cal.data
                is ApiResult.Error -> if (error == null) error = cal.error
            }
            when (val ls = wmsRepository.listLowStock()) {
                is ApiResult.Success -> lowStock = ls.data
                is ApiResult.Error -> { }
            }
            when (val pp = extrasRepository.getProcurementPlan()) {
                is ApiResult.Success -> procurementPlan = pp.data
                is ApiResult.Error -> { }
            }
            when (val cp = extrasRepository.listCounterparties()) {
                is ApiResult.Success -> counterparties = cp.data
                is ApiResult.Error -> { }
            }
            when (val aq = approvalRepository.myQueue()) {
                is ApiResult.Success -> approvalQueue = aq.data
                is ApiResult.Error -> { }
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(
            title = "Аналитика и календарь",
            glyph = ErpIconGlyph.TrendUp,
            gradient = Gradients.Ocean,
            subtitle = "Сводные показатели, календарь сроков и экспорт данных"
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PrimaryScrollableTabRow(
                    selectedTabIndex = selectedTab.ordinal,
                    modifier = Modifier.weight(1f)
                ) {
                    AnalyticsTab.entries.forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            text = { Text(tab.label) }
                        )
                    }
                }
                SoftButton(text = "Обновить", onClick = ::reload)
            }

            if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            error?.let { ErrorMessageCard(error = it) }

            when (selectedTab) {
                AnalyticsTab.SUMMARY -> {
                    val s = summary
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (s != null) {
                            Text("Показатели предприятия", style = MaterialTheme.typography.titleMedium)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                AnalyticsKpiCard("Открытых задач", "${s.openTasks}", "Всего задач: ${s.totalTasks}", Modifier.weight(1f))
                                AnalyticsKpiCard("Просрочено задач", "${s.overdueTasks}", "Требуют внимания", Modifier.weight(1f))
                                AnalyticsKpiCard("Проекты в работе", "${s.activeProjects}", "Всего: ${s.totalProjects}", Modifier.weight(1f))
                                AnalyticsKpiCard("Потребности в работе", "${s.pendingDemands}", "Всего: ${s.totalDemands}", Modifier.weight(1f))
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                AnalyticsKpiCard("Заказы поставщикам", "${s.totalPurchaseOrders}", "Активные поставки", Modifier.weight(1f))
                                AnalyticsKpiCard("Производство (акт.)", "${s.activeProductionOrders}", "Заказы в цехе", Modifier.weight(1f))
                                AnalyticsKpiCard("Позиций в дефиците", "${s.lowStockCount}", "Ниже минимума", Modifier.weight(1f))
                            }
                            var execQueues by remember { mutableStateOf<List<org.example.project.dto.DepartmentQueueDto>>(emptyList()) }
                            LaunchedEffect(Unit) {
                                val qRes = extrasRepository.getExecutiveQueues()
                                if (qRes is ApiResult.Success) execQueues = qRes.data
                            }
                            if (execQueues.isNotEmpty()) {
                                GlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Панель руководителя: очереди подразделений", style = MaterialTheme.typography.titleSmall)
                                        execQueues.forEach { q ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(q.departmentName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    PillBadge(text = "Открыто: ${q.openTasks}")
                                                    PillBadge(text = "Просрочено: ${q.overdueTasks}")
                                                    PillBadge(text = "Производство: ${q.inProgressOrders}")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Экспорт таблиц (CSV)", style = MaterialTheme.typography.titleSmall)
                                    Row(
                                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        SoftButton(
                                            text = "Задачи (CSV)",
                                            onClick = { uriHandler.openUri("${org.example.project.data.ApiServer.baseUrl}/api/analytics/export/tasks.csv") }
                                        )
                                        SoftButton(
                                            text = "Проекты (CSV)",
                                            onClick = { uriHandler.openUri("${org.example.project.data.ApiServer.baseUrl}/api/analytics/export/projects.csv") }
                                        )
                                        SoftButton(
                                            text = "Остатки склада (CSV)",
                                            onClick = { uriHandler.openUri("${org.example.project.data.ApiServer.baseUrl}/api/analytics/export/stock.csv") }
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        SoftButton(
                                            text = "Импорт номенклатуры (CSV)",
                                            onClick = { showCsvImport = true }
                                        )
                                        Text(
                                            "Массовое добавление позиций в справочник номенклатуры",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    infoMessage?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                    if (showCsvImport) {
                        var csvText by remember { mutableStateOf("code;name;type;unit\nCMP-NEW-01;Новый резистор;COMPONENT;шт") }
                        org.example.project.ui.theme.ErpDialog(
                            title = "Импорт номенклатуры (CSV)",
                            glyph = ErpIconGlyph.Layers,
                            gradient = Gradients.Primary,
                            width = 620.dp,
                            confirmText = "Импортировать",
                            onConfirm = {
                                scope.launch {
                                    when (val res = extrasRepository.importNomenclatureCsv(CsvImportRequest(csvText))) {
                                        is ApiResult.Success -> {
                                            infoMessage = "Импортировано: ${res.data.importedCount}, пропущено: ${res.data.skippedCount}"
                                            showCsvImport = false
                                        }
                                        is ApiResult.Error -> error = res.error
                                    }
                                }
                            },
                            onDismiss = { showCsvImport = false }
                        ) {
                            org.example.project.ui.theme.ErpTextField(
                                value = csvText,
                                onValueChange = { csvText = it },
                                label = "CSV (code;name;type;unit)",
                                singleLine = false,
                                minLines = 5
                            )
                        }
                    }
                }
                AnalyticsTab.CALENDAR -> {
                    if (calendar.isEmpty()) {
                        Text("Предстоящих сроков нет.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        org.example.project.ui.tables.PaginatedCardList(
                            items = calendar,
                            modifier = Modifier.fillMaxSize()
                        ) { _, item ->
                            // Полоса «Ганта» убрана: она считалась по номеру строки и не отражала реальный прогресс.
                            GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 12.dp) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("${item.number} · ${item.title}", style = MaterialTheme.typography.titleSmall)
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            "${item.kindLabel()} · срок ${item.date}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    PillBadge(text = item.statusLabel())
                                }
                            }
                        }
                    }
                }
                AnalyticsTab.LOW_STOCK -> {
                    if (lowStock.isEmpty()) {
                        Text("Позиций ниже минимального остатка нет.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SoftButton(text = "Создать потребность из дефицита", onClick = {
                                scope.launch {
                                    val lines = lowStock.take(20).mapIndexed { idx, ls ->
                                        DemandLineInput(
                                            lineNo = idx + 1,
                                            nomenclatureId = ls.nomenclatureId,
                                            quantity = ls.deficitQty.ifBlank { "1" },
                                            unit = ls.unit
                                        )
                                    }
                                    when (val res = demandRepository.createDemand(
                                        CreateDemandRequest(
                                            sourceType = DemandSourceType.STOCKPILE,
                                            stockpileNote = "Пополнение дефицита склада",
                                            lines = lines
                                        )
                                    )) {
                                        is ApiResult.Success -> infoMessage = "Создана потребность ${res.data.header.number}"
                                        is ApiResult.Error -> error = res.error
                                    }
                                }
                            })
                            infoMessage?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                            org.example.project.ui.tables.PersistentDataGrid(
                                data = mapOf(
                                    "Код" to lowStock.map { it.code },
                                    "Название" to lowStock.map { it.name },
                                    "Доступно" to lowStock.map { "${it.availableQty} ${it.unit}" },
                                    "Минимум" to lowStock.map { it.minStock },
                                    "Страх. запас" to lowStock.map { it.safetyStock },
                                    "Дефицит" to lowStock.map { "${it.deficitQty} ${it.unit}" }
                                ),
                                tableKey = "analytics-low-stock",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
                AnalyticsTab.PROCUREMENT -> {
                    if (procurementPlan.isEmpty()) {
                        Text("Позиций к плановой закупке нет.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        org.example.project.ui.tables.PersistentDataGrid(
                            data = mapOf(
                                "Код" to procurementPlan.map { it.code },
                                "Название" to procurementPlan.map { it.name },
                                "Потребность" to procurementPlan.map { "${it.requiredQty} ${it.unit}" },
                                "На складе" to procurementPlan.map { it.availableQty },
                                "В пути / Заказ" to procurementPlan.map { "${it.inTransitQty} / ${it.openPoQty}" },
                                "Чистый дефицит" to procurementPlan.map { it.netDeficitQty },
                                "К заказу" to procurementPlan.map { "${it.recommendedOrderQty} ${it.unit}" },
                                "Срок (дн.)" to procurementPlan.map { "${it.leadTimeDays}" }
                            ),
                            tableKey = "analytics-procurement-plan",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                AnalyticsTab.CRM -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoftButton(text = "Новый контрагент", onClick = { showCreateCounterparty = true })
                        if (counterparties.isEmpty()) {
                            Text("Контрагентов пока нет.", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            org.example.project.ui.tables.PaginatedCardList(
                                items = counterparties,
                                modifier = Modifier.fillMaxSize()
                            ) { _, cp ->
                                GlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("${cp.name} (${cp.kind.label()})", style = MaterialTheme.typography.titleSmall)
                                            cp.inn?.let { PillBadge(text = "ИНН $it") }
                                        }
                                        Text(
                                            listOfNotNull(cp.email, cp.phone, cp.address).joinToString(" · ").ifBlank { "Контакты не указаны" },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (showCreateCounterparty) {
                        var cpName by remember { mutableStateOf("") }
                        var cpInn by remember { mutableStateOf("") }
                        var cpPhone by remember { mutableStateOf("") }
                        org.example.project.ui.theme.ErpDialog(
                            title = "Новый контрагент",
                            glyph = ErpIconGlyph.Cart,
                            gradient = Gradients.Primary,
                            width = 580.dp,
                            confirmText = "Создать",
                            onConfirm = {
                                if (cpName.isNotBlank()) {
                                    scope.launch {
                                        when (val res = extrasRepository.createCounterparty(
                                            CreateCounterpartyRequest(
                                                name = cpName.trim(),
                                                kind = CounterpartyKind.CUSTOMER,
                                                inn = cpInn.ifBlank { null },
                                                phone = cpPhone.ifBlank { null }
                                            )
                                        )) {
                                            is ApiResult.Success -> {
                                                counterparties = listOf(res.data) + counterparties
                                                showCreateCounterparty = false
                                            }
                                            is ApiResult.Error -> error = res.error
                                        }
                                    }
                                }
                            },
                            onDismiss = { showCreateCounterparty = false }
                        ) {
                            org.example.project.ui.theme.ErpTextField(
                                value = cpName,
                                onValueChange = { cpName = it },
                                label = "Наименование"
                            )
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                org.example.project.ui.theme.ErpTextField(
                                    value = cpInn,
                                    onValueChange = { cpInn = it },
                                    label = "ИНН",
                                    modifier = Modifier.weight(1f)
                                )
                                org.example.project.ui.theme.ErpTextField(
                                    value = cpPhone,
                                    onValueChange = { cpPhone = it },
                                    label = "Телефон",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
                AnalyticsTab.APPROVALS -> {
                    var templates by remember { mutableStateOf<List<org.example.project.dto.ApprovalTemplateDto>>(emptyList()) }
                    var tplTargetType by remember { mutableStateOf("DEMAND") }
                    LaunchedEffect(tplTargetType) {
                        when (val tRes = approvalRepository.listTemplates(tplTargetType)) {
                            is ApiResult.Success -> templates = tRes.data
                            is ApiResult.Error -> { }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                "DEMAND" to "Потребности",
                                "WRITE_OFF" to "Списания",
                                "PURCHASE_ORDER" to "Заказы поставщикам",
                                "DOCUMENT" to "Документы",
                                "PRODUCTION_ORDER" to "Производство"
                            ).forEach { (tt, label) ->
                                SoftButton(text = label, onClick = { tplTargetType = tt })
                            }
                        }
                        if (templates.isNotEmpty()) {
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Шаблоны согласований ($tplTargetType): ${templates.size}", style = MaterialTheme.typography.titleSmall)
                                    templates.forEach { tpl ->
                                        Text("${tpl.code} · ${tpl.name} (этапов: ${tpl.stages.size})", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        if (approvalQueue.isEmpty()) {
                            Text("Очередь согласований пуста.", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            org.example.project.ui.tables.PaginatedCardList(
                                items = approvalQueue,
                                modifier = Modifier.fillMaxSize()
                            ) { _, route ->
                                GlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                "${route.targetType} #${route.targetId} · маршрут #${route.id}",
                                                style = MaterialTheme.typography.titleSmall
                                            )
                                            Text(
                                                "Этап ${route.currentStageNo} · ${route.status.localizedName()}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        PillBadge(text = route.status.localizedName())
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnalyticsKpiCard(
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    GlassCard(modifier = modifier, contentPadding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Тип события календаря по-русски. */
private fun CalendarEventDto.kindLabel(): String = when (kind) {
    org.example.project.dto.CalendarEventKind.TASK_DUE -> "Задача"
    org.example.project.dto.CalendarEventKind.PROJECT_DEADLINE -> "Проект"
    org.example.project.dto.CalendarEventKind.MILESTONE -> "Контрольная точка"
}

/** Статус события календаря по-русски; неизвестный код показывается как есть. */
private fun CalendarEventDto.statusLabel(): String = when (kind) {
    org.example.project.dto.CalendarEventKind.TASK_DUE ->
        org.example.project.dto.TaskStatus.entries.firstOrNull { it.name == status }?.localizedName()
    org.example.project.dto.CalendarEventKind.PROJECT_DEADLINE ->
        org.example.project.dto.ProjectStatus.entries.firstOrNull { it.name == status }?.localizedName()
    org.example.project.dto.CalendarEventKind.MILESTONE ->
        org.example.project.dto.ProjectMilestoneStatus.entries.firstOrNull { it.name == status }?.localizedName()
} ?: status

/** Вид контрагента по-русски. */
private fun CounterpartyKind.label(): String = when (this) {
    CounterpartyKind.CUSTOMER -> "заказчик"
    CounterpartyKind.PARTNER -> "партнёр"
    CounterpartyKind.SUPPLIER -> "поставщик"
    CounterpartyKind.OTHER -> "прочее"
}
