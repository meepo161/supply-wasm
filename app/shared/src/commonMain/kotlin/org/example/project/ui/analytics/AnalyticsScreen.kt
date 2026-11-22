package org.example.project.ui.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
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
                    if (s != null) {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Показатели предприятия", style = MaterialTheme.typography.titleMedium)
                                DetailRow("Задачи (всего / открыто / просрочено)", "${s.totalTasks} / ${s.openTasks} / ${s.overdueTasks}")
                                DetailRow("Проекты (всего / в работе)", "${s.totalProjects} / ${s.activeProjects}")
                                DetailRow("Потребности (всего / в работе)", "${s.totalDemands} / ${s.pendingDemands}")
                                DetailRow("Заказы поставщикам", "${s.totalPurchaseOrders}")
                                DetailRow("Производственные заказы (активные)", "${s.activeProductionOrders}")
                                DetailRow("Позиций ниже минимального остатка", "${s.lowStockCount}")
                            }
                        }
                        var execQueues by remember { mutableStateOf<List<org.example.project.dto.DepartmentQueueDto>>(emptyList()) }
                        LaunchedEffect(Unit) {
                            val qRes = extrasRepository.getExecutiveQueues()
                            if (qRes is ApiResult.Success) execQueues = qRes.data
                        }
                        if (execQueues.isNotEmpty()) {
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Панель руководителя: очереди подразделений", style = MaterialTheme.typography.titleSmall)
                                    execQueues.forEach { q ->
                                        DetailRow(
                                            q.departmentName,
                                            "Открыто: ${q.openTasks} · Просрочено: ${q.overdueTasks} · Производство: ${q.inProgressOrders}"
                                        )
                                    }
                                }
                            }
                        }
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Экспорт таблиц (CSV)", style = MaterialTheme.typography.titleSmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SoftButton(
                                        text = "Задачи (CSV)",
                                        onClick = { uriHandler.openUri("http://localhost:8080/api/analytics/export/tasks.csv") }
                                    )
                                    SoftButton(
                                        text = "Проекты (CSV)",
                                        onClick = { uriHandler.openUri("http://localhost:8080/api/analytics/export/projects.csv") }
                                    )
                                    SoftButton(
                                        text = "Остатки склада (CSV)",
                                        onClick = { uriHandler.openUri("http://localhost:8080/api/analytics/export/stock.csv") }
                                    )
                                    SoftButton(
                                        text = "Импорт CSV",
                                        onClick = { showCsvImport = true }
                                    )
                                    SoftButton(
                                        text = "OpenAPI (JSON)",
                                        onClick = { uriHandler.openUri("http://localhost:8080/api/openapi.json") }
                                    )
                                }
                                infoMessage?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                    if (showCsvImport) {
                        var csvText by remember { mutableStateOf("code;name;type;unit\nCMP-NEW-01;Новый резистор;COMPONENT;шт") }
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { showCsvImport = false },
                            title = { Text("Импорт номенклатуры (CSV)") },
                            text = {
                                org.example.project.ui.theme.ErpTextField(
                                    value = csvText,
                                    onValueChange = { csvText = it },
                                    label = "CSV (code;name;type;unit)"
                                )
                            },
                            confirmButton = {
                                SoftButton(text = "Импортировать", onClick = {
                                    scope.launch {
                                        when (val res = extrasRepository.importNomenclatureCsv(CsvImportRequest(csvText))) {
                                            is ApiResult.Success -> {
                                                infoMessage = "Импортировано: ${res.data.importedCount}, пропущено: ${res.data.skippedCount}"
                                                showCsvImport = false
                                            }
                                            is ApiResult.Error -> error = res.error
                                        }
                                    }
                                })
                            },
                            dismissButton = {
                                SoftButton(text = "Отмена", onClick = { showCsvImport = false })
                            }
                        )
                    }
                }
                AnalyticsTab.CALENDAR -> {
                    if (calendar.isEmpty()) {
                        Text("Предстоящих сроков нет.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(calendar.size) { index ->
                                val item = calendar[index]
                                val progress = ((index + 1).toFloat() / calendar.size.coerceAtLeast(1).toFloat()).coerceIn(0.15f, 1f)
                                GlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("${item.number} · ${item.title}", style = MaterialTheme.typography.titleSmall)
                                                Spacer(Modifier.height(2.dp))
                                                Text("Срок: ${item.date}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            PillBadge(text = item.status)
                                        }
                                        // Шкала Ганта по последовательности сроков
                                        LinearProgressIndicator(
                                            progress = { progress },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
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
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(lowStock.size) { index ->
                                    val item = lowStock[index]
                                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text("${item.code} · ${item.name}", style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                "Доступно: ${item.availableQty} ${item.unit} · Минимум: ${item.minStock} · Дефицит: ${item.deficitQty}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                AnalyticsTab.PROCUREMENT -> {
                    if (procurementPlan.isEmpty()) {
                        Text("Позиций к плановой закупке нет.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(procurementPlan.size) { index ->
                                val item = procurementPlan[index]
                                GlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("${item.code} · ${item.name}", style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            "Потребность: ${item.requiredQty} · Склад: ${item.availableQty} · В пути: ${item.inTransitQty} · Заказано: ${item.openPoQty}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            "Чистый дефицит: ${item.netDeficitQty} ${item.unit} · Рекомендуем заказать: ${item.recommendedOrderQty} ${item.unit} (кратность ${item.orderMultiple}, срок ${item.leadTimeDays} дн.)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                AnalyticsTab.CRM -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoftButton(text = "Новый контрагент", onClick = { showCreateCounterparty = true })
                        if (counterparties.isEmpty()) {
                            Text("Контрагентов пока нет.", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(counterparties.size) { index ->
                                    val cp = counterparties[index]
                                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text("${cp.name} (${cp.kind.name})", style = MaterialTheme.typography.titleSmall)
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
                    }
                    if (showCreateCounterparty) {
                        var cpName by remember { mutableStateOf("") }
                        var cpInn by remember { mutableStateOf("") }
                        var cpPhone by remember { mutableStateOf("") }
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { showCreateCounterparty = false },
                            title = { Text("Новый контрагент") },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    org.example.project.ui.theme.ErpTextField(
                                        value = cpName,
                                        onValueChange = { cpName = it },
                                        label = "Наименование"
                                    )
                                    org.example.project.ui.theme.ErpTextField(
                                        value = cpInn,
                                        onValueChange = { cpInn = it },
                                        label = "ИНН"
                                    )
                                    org.example.project.ui.theme.ErpTextField(
                                        value = cpPhone,
                                        onValueChange = { cpPhone = it },
                                        label = "Телефон"
                                    )
                                }
                            },
                            confirmButton = {
                                SoftButton(text = "Создать", onClick = {
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
                                })
                            },
                            dismissButton = {
                                SoftButton(text = "Отмена", onClick = { showCreateCounterparty = false })
                            }
                        )
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
                            listOf("DEMAND", "WRITE_OFF", "PURCHASE_ORDER", "DOCUMENT", "PRODUCTION_ORDER").forEach { tt ->
                                SoftButton(text = tt, onClick = { tplTargetType = tt })
                            }
                        }
                        if (templates.isNotEmpty()) {
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Шаблоны согласований ($tplTargetType): ${templates.size}", style = MaterialTheme.typography.titleSmall)
                                    templates.forEach { tpl ->
                                        Text("${tpl.code} — ${tpl.name} (этапов: ${tpl.stages.size})", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        if (approvalQueue.isEmpty()) {
                            Text("Очередь согласований пуста.", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(approvalQueue.size) { index ->
                                    val route = approvalQueue[index]
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
                                                    "Этап ${route.currentStageNo} · ${route.status.name}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            PillBadge(text = route.status.name)
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
}
