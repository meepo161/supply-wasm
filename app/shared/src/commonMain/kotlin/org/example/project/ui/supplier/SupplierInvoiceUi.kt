package org.example.project.ui.supplier

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.dto.InvoiceIssueSeverity
import org.example.project.dto.PurchaseOrderDto
import org.example.project.dto.ReceiptStatus
import org.example.project.dto.SupplierInvoiceDto
import org.example.project.dto.SupplierInvoiceStatus
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.ErpSplitRow
import org.example.project.ui.theme.DetailRow
import org.example.project.ui.theme.EmptyState
import org.example.project.ui.theme.ErpDialog
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.ErpTextField
import org.example.project.ui.theme.GlassCard
import org.example.project.ui.theme.GradientButton
import org.example.project.ui.theme.GradientSpinner
import org.example.project.ui.theme.Gradients
import org.example.project.ui.theme.PillBadge
import org.example.project.ui.theme.SectionHeader
import org.example.project.ui.theme.SoftButton
import org.example.project.ui.wms.WarehouseDropdown

fun SupplierInvoiceStatus.localizedName(): String = when (this) {
    SupplierInvoiceStatus.DRAFT -> "Черновик"
    SupplierInvoiceStatus.CHECKED -> "Сверен"
    SupplierInvoiceStatus.CANCELLED -> "Отменён"
}

fun SupplierInvoiceStatus.accentColor(): Color = when (this) {
    SupplierInvoiceStatus.DRAFT -> Brand.Amber
    SupplierInvoiceStatus.CHECKED -> Brand.Mint
    SupplierInvoiceStatus.CANCELLED -> Brand.Rose
}

private fun InvoiceIssueSeverity.accent(): Color = when (this) {
    InvoiceIssueSeverity.ERROR -> Brand.Rose
    InvoiceIssueSeverity.REQUIRES_REASON -> Brand.Amber
    InvoiceIssueSeverity.WARNING -> Brand.Blue
}

private fun receiptStatusTitle(status: ReceiptStatus?): String = when (status) {
    ReceiptStatus.DRAFT -> "ожидает склада"
    ReceiptStatus.IN_PROGRESS -> "склад принимает"
    ReceiptStatus.POSTED -> "проведено"
    ReceiptStatus.CANCELLED -> "отменено"
    null -> "—"
}

private fun historyTitle(action: String): String = when (action) {
    "CREATED" -> "Зарегистрирован"
    "UPDATED" -> "Изменён"
    "CHECKED" -> "Сверка подтверждена"
    "REOPENED" -> "Возвращён в черновик"
    "CANCELLED" -> "Отменён"
    "RECEIPT_CREATED" -> "Оформлено поступление на склад"
    else -> action
}

// ---------------------------------------------------------------------------
// Вкладка «Счета»
// ---------------------------------------------------------------------------

@Composable
fun SupplierInvoicesPane(viewModel: SupplierInvoiceViewModel) {
    val state by viewModel.uiState.collectAsState()
    if (!viewModel.canRead()) {
        EmptyState(
            title = "Нет доступа к счетам",
            text = "Счета поставщиков ведёт снабжение; нужно право «invoices:read» или «invoices:manage»",
            glyph = ErpIconGlyph.Shield,
            accent = Brand.Slate
        )
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        state.message?.let { message ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(8.dp))
                    .background(Brand.Mint.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(message, style = MaterialTheme.typography.bodySmall, color = Brand.Teal, modifier = Modifier.weight(1f))
                SoftButton(text = "Ок", onClick = viewModel::dismissMessage)
            }
        }
        if (state.dialog == null) state.error?.let { ErrorMessageCard(error = it, modifier = Modifier.padding(bottom = 8.dp)) }
        ErpSplitRow {
            GlassCard(modifier = Modifier.pane(0.4f), contentPadding = 14.dp) {
                SectionHeader(
                    title = "Счета поставщиков",
                    count = state.invoices.size,
                    accent = Brand.Violet,
                    subtitle = state.statusFilter?.localizedName() ?: "Все статусы"
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip("Все", state.statusFilter == null) { viewModel.updateStatusFilter(null) }
                    SupplierInvoiceStatus.entries.forEach { status ->
                        FilterChip(status.localizedName(), state.statusFilter == status) { viewModel.updateStatusFilter(status) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                when {
                    state.isLoading && state.invoices.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { GradientSpinner() }
                    state.invoices.isEmpty() -> EmptyState(
                        title = "Счетов нет",
                        text = "Счёт загружается из карточки заказа поставщику кнопкой «Загрузить счёт»",
                        glyph = ErpIconGlyph.Documents,
                        accent = Brand.Violet
                    )
                    else -> Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                        state.invoices.forEach { invoice ->
                            InvoiceRow(invoice, selected = state.selected?.id == invoice.id) { viewModel.select(invoice.id) }
                        }
                    }
                }
            }
            InvoiceCard(state, viewModel, Modifier.pane(0.6f, narrowHeight = 640.dp))
        }
    }
}

@Composable
private fun FilterChip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (active) Brand.Violet.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (active) Brand.Violet else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InvoiceRow(invoice: SupplierInvoiceDto, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(8.dp))
            .background(if (selected) Brand.Violet.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("№ ${invoice.number} от ${invoice.invoiceDate.asDate()}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                listOfNotNull(invoice.supplierName, invoice.purchaseOrderNumber?.let { "заказ $it" }, invoice.grossTotal.asMoney(invoice.currency))
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (invoice.expired && invoice.status != SupplierInvoiceStatus.CANCELLED) {
            PillBadge(text = "Срок истёк", accent = Brand.Rose)
            Spacer(Modifier.width(4.dp))
        }
        invoice.receiptNumber?.let {
            PillBadge(text = "Склад: ${receiptStatusTitle(invoice.receiptStatus)}", accent = Brand.Teal)
            Spacer(Modifier.width(4.dp))
        }
        PillBadge(text = invoice.status.localizedName(), accent = invoice.status.accentColor())
    }
}

@Composable
private fun InvoiceCard(state: SupplierInvoiceUiState, viewModel: SupplierInvoiceViewModel, modifier: Modifier) {
    val invoice = state.selected
    GlassCard(modifier = modifier, contentPadding = 16.dp) {
        if (invoice == null) {
            EmptyState(
                title = "Выберите счёт",
                text = "Карточка покажет файл счёта, строки, результат сверки с заказом и поступление на склад",
                glyph = ErpIconGlyph.Documents,
                accent = Brand.Violet
            )
            return@GlassCard
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Счёт № ${invoice.number} от ${org.example.project.ui.theme.ErpFormat.dateRu(invoice.invoiceDate)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    invoice.supplierName ?: "поставщик #${invoice.supplierId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            state.check?.let { chk ->
                PillBadge(
                    text = when {
                        chk.issues.isEmpty() -> "Расхождений нет"
                        chk.canConfirm -> "Предупреждения (${chk.issues.size})"
                        else -> "Есть ошибки"
                    },
                    accent = when {
                        chk.issues.isEmpty() -> Brand.Teal
                        chk.canConfirm -> Brand.Amber
                        else -> Brand.Rose
                    }
                )
            }
            PillBadge(text = invoice.status.localizedName(), accent = invoice.status.accentColor(), filled = invoice.status == SupplierInvoiceStatus.CHECKED)
        }
        Spacer(Modifier.height(8.dp))
        InvoiceActions(invoice, state, viewModel)
        Spacer(Modifier.height(10.dp))
        Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            SectionHeader(title = "Реквизиты", accent = Brand.Violet)
            DetailRow("Файл счёта", invoice.fileName ?: "—")
            DetailRow("Заказ поставщику", invoice.purchaseOrderNumber ?: "не привязан", valueColor = if (invoice.purchaseOrderId == null) Brand.Amber else MaterialTheme.colorScheme.onSurface)
            DetailRow("ИНН / КПП поставщика", listOfNotNull(invoice.supplierInn, invoice.supplierKpp).joinToString(" / ").ifEmpty { "—" })
            DetailRow(
                "Действует до",
                org.example.project.ui.theme.ErpFormat.deadlineLabel(invoice.validUntil, org.example.project.ui.home.currentIsoDate()),
                valueColor = if (invoice.expired) Brand.Rose else MaterialTheme.colorScheme.onSurface
            )
            listOfNotNull(invoice.paymentTerms, invoice.prepaymentPct?.let { "предоплата ${org.example.project.ui.theme.ErpFormat.percent(it)}" })
                .joinToString(", ")
                .takeIf { it.isNotEmpty() }
                ?.let { DetailRow("Условия оплаты", it) }
            invoice.leadTimeDays?.let {
                DetailRow("Срок изготовления", "$it ${if (invoice.leadTimeKind == org.example.project.dto.LeadTimeKind.CALENDAR) "календ." else "рабочих"} дн.")
            }
            invoice.deliveryTerms?.takeIf { it.isNotBlank() }?.let { DetailRow("Доставка", it) }
            invoice.paymentPurpose?.takeIf { it.isNotBlank() }?.let { DetailRow("Назначение платежа", it) }
            invoice.supplierManager?.let { DetailRow("Менеджер поставщика", it) }
            invoice.acceptedDiscrepancyReason?.let { DetailRow("Принятые расхождения", it, valueColor = Brand.Amber) }
            invoice.cancelReason?.let { DetailRow("Причина отмены", it, valueColor = Brand.Rose) }
            DetailRow(
                "Поступление на склад",
                invoice.receiptNumber?.let { "$it · ${receiptStatusTitle(invoice.receiptStatus)}" } ?: "не оформлено"
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Что дальше: " + nextStepText(invoice),
                style = MaterialTheme.typography.bodySmall,
                color = Brand.Teal
            )

            Spacer(Modifier.height(12.dp))
            SectionHeader(title = "Строки счёта", count = invoice.lines.size, accent = Brand.Blue)
            invoice.lines.forEach { line ->
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)).padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        "${line.lineNo}. ${line.supplierItemName}" + (line.supplierItemCode?.let { "  ($it)" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "${line.quantity.trimZeros()} ${line.unit} × ${line.unitPrice.asMoney(invoice.currency)} · " +
                            "НДС ${line.vatRate?.trimZeros()?.let { "$it %" } ?: "без НДС"} ${line.vatAmount.asMoney(invoice.currency)} · сумма ${line.lineTotal.asMoney(invoice.currency)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        line.nomenclatureCode?.let { "Номенклатура: $it ${line.nomenclatureName.orEmpty()}" } ?: "Номенклатура не сопоставлена",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (line.nomenclatureId == null) Brand.Rose else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TotalChip("Без НДС", invoice.netTotal.asMoney(invoice.currency), Brand.Blue, Modifier.weight(1f))
                TotalChip("НДС", invoice.vatTotal.asMoney(invoice.currency), Brand.Amber, Modifier.weight(1f))
                TotalChip("С НДС", invoice.grossTotal.asMoney(invoice.currency), Brand.Mint, Modifier.weight(1f))
            }

            Spacer(Modifier.height(12.dp))
            val issues = state.check?.issues.orEmpty()
            SectionHeader(
                title = "Сверка с заказом",
                count = issues.size,
                accent = if (issues.any { it.severity == InvoiceIssueSeverity.ERROR }) Brand.Rose else Brand.Mint,
                subtitle = when {
                    state.check == null -> "не выполнена"
                    issues.isEmpty() -> "расхождений нет"
                    state.check.canConfirm -> "есть предупреждения (проверьте суммы)"
                    else -> "есть ошибки, счёт нужно исправить"
                },
                action = if (invoice.status == SupplierInvoiceStatus.DRAFT &&
                    state.check?.canConfirm == true && issues.isNotEmpty() && viewModel.canManage()
                ) {
                    {
                        SoftButton(
                            text = "Подтвердить с обоснованием",
                            onClick = viewModel::openConfirm,
                            tint = Brand.Teal,
                            compact = true,
                            enabled = !state.isBusy
                        )
                    }
                } else null
            )
            issues.forEach { issue ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp))
                        .background(issue.severity.accent().copy(alpha = 0.10f)).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PillBadge(text = SupplierInvoiceViewModel.severityTitle(issue.severity), accent = issue.severity.accent())
                    Spacer(Modifier.width(8.dp))
                    Text(
                        (issue.lineNo?.let { "Строка $it: " } ?: "") + issue.message,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionHeader(title = "История", count = state.history.size, accent = Brand.Amber)
            val userNames = rememberUserNames()
            state.history.forEach { h ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(historyTitle(h.action) + (h.reason?.let { ": $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                    Text(
                        "${org.example.project.ui.theme.ErpFormat.dateTimeRu(h.changedAt)} · " +
                            (userNames[h.changedBy] ?: "пользователь №${h.changedBy}"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun InvoiceActions(invoice: SupplierInvoiceDto, state: SupplierInvoiceUiState, viewModel: SupplierInvoiceViewModel) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SoftButton(text = "Открыть файл", onClick = { viewModel.downloadFile(invoice) }, leadingIcon = ErpIconGlyph.Documents)
        SoftButton(text = "Предпросмотр HTML", onClick = { viewModel.previewHtml(invoice) }, leadingIcon = ErpIconGlyph.Documents)
        if (!viewModel.canManage()) return@FlowRow
        when (invoice.status) {
            SupplierInvoiceStatus.DRAFT -> {
                SoftButton(text = "Править", onClick = viewModel::openEdit, enabled = !state.isBusy)
                SoftButton(
                    text = "Подтвердить сверку",
                    onClick = viewModel::openConfirm,
                    tint = Brand.Teal,
                    enabled = !state.isBusy && state.check?.canConfirm == true
                )
            }
            SupplierInvoiceStatus.CHECKED -> if (invoice.receiptId == null) {
                GradientButton(
                    text = "Оформить поступление на склад",
                    onClick = viewModel::openReceipt,
                    gradient = Gradients.Emerald,
                    compact = true,
                    enabled = !state.isBusy
                )
                SoftButton(text = "Вернуть в черновик", onClick = viewModel::reopen, enabled = !state.isBusy)
            }
            SupplierInvoiceStatus.CANCELLED -> Unit
        }
        if (invoice.status != SupplierInvoiceStatus.CANCELLED && invoice.receiptId == null) {
            SoftButton(text = "Отменить счёт", onClick = viewModel::openCancel, tint = Brand.Rose, enabled = !state.isBusy)
        }
    }
}

@Composable
private fun TotalChip(label: String, value: String, accent: Color, modifier: Modifier) {
    Column(modifier = modifier.clip(RoundedCornerShape(8.dp)).background(accent.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = accent)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

private fun String.trimZeros(): String = if (contains('.')) trimEnd('0').trimEnd('.') else this

// ---------------------------------------------------------------------------
// Блок «Счета по заказу» в карточке заказа
// ---------------------------------------------------------------------------

@Composable
fun OrderInvoicesSection(
    order: PurchaseOrderDto,
    viewModel: SupplierInvoiceViewModel,
    onOpenInvoices: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    if (!viewModel.canRead()) return
    val invoices = state.invoices.filter { it.purchaseOrderId == order.id }
    SectionHeader(
        title = "Счета по заказу",
        count = invoices.size,
        accent = Brand.Violet,
        subtitle = "Файл счёта обязателен; поступление на склад оформляется из счёта"
    )
    if (viewModel.canManage() && order.status in invoiceAllowedOrderStatuses) {
        Spacer(Modifier.height(6.dp))
        SoftButton(
            text = "Загрузить счёт",
            onClick = { viewModel.openCreateFromOrder(order) },
            tint = Brand.Violet,
            leadingIcon = ErpIconGlyph.Plus
        )
    }
    if (invoices.isEmpty()) {
        Text(
            if (order.status in invoiceAllowedOrderStatuses) "Счёт ещё не загружен" else "Счёт принимается по согласованному заказу",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        invoices.forEach { invoice ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                InvoiceRow(invoice, selected = false) {
                    viewModel.select(invoice.id)
                    onOpenInvoices()
                }
                if (invoice.status == SupplierInvoiceStatus.CHECKED && invoice.receiptId == null && viewModel.canManage()) {
                    SoftButton(
                        text = if (state.isBusy) "Передача…" else "Передать на склад",
                        onClick = { viewModel.sendToWarehouse(invoice) },
                        tint = Brand.Mint,
                        enabled = !state.isBusy
                    )
                }
            }
        }
    }
}

private val invoiceAllowedOrderStatuses = setOf(
    org.example.project.dto.PurchaseOrderStatus.APPROVED,
    org.example.project.dto.PurchaseOrderStatus.SENT,
    org.example.project.dto.PurchaseOrderStatus.PARTIALLY_DELIVERED,
    org.example.project.dto.PurchaseOrderStatus.DELIVERED
)

// ---------------------------------------------------------------------------
// Диалоги
// ---------------------------------------------------------------------------

@Composable
fun SupplierInvoiceDialogs(viewModel: SupplierInvoiceViewModel) {
    val state by viewModel.uiState.collectAsState()
    when (state.dialog) {
        SupplierInvoiceDialog.Edit -> InvoiceFormDialog(state, viewModel)
        SupplierInvoiceDialog.Confirm -> ConfirmCheckDialog(state, viewModel)
        SupplierInvoiceDialog.Cancel -> CancelInvoiceDialog(state, viewModel)
        SupplierInvoiceDialog.Receipt -> ReceiptDialog(state, viewModel)
        null -> Unit
    }
}

@Composable
private fun InvoiceFormDialog(state: SupplierInvoiceUiState, viewModel: SupplierInvoiceViewModel) {
    val form = state.form ?: return
    ErpDialog(
        title = if (form.invoiceId == null) "Загрузка счёта поставщика" else "Счёт № ${form.number}",
        subtitle = "${form.supplierName}" + (form.purchaseOrderNumber?.let { " · заказ $it" } ?: "") +
            ": перенесите реквизиты из файла счёта",
        glyph = ErpIconGlyph.Documents,
        gradient = Gradients.Berry,
        onDismiss = viewModel::dismissDialog,
        confirmText = if (state.isBusy) "Сохранение…" else "Сохранить счёт",
        onConfirm = viewModel::saveInvoice,
        confirmEnabled = !state.isBusy && form.hasFile && form.number.isNotBlank() && form.lines.isNotEmpty(),
        confirmGradient = Gradients.Berry,
        width = 780.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            state.error?.let { ErrorMessageCard(error = it) }
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background((if (form.hasFile) Brand.Mint else Brand.Rose).copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    form.fileLabel?.let { "Файл счёта: $it" } ?: "Файл счёта не выбран: без файла счёт не регистрируется",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (form.hasFile) Brand.Teal else Brand.Rose,
                    modifier = Modifier.weight(1f)
                )
                if (form.hasFile) {
                    SoftButton(
                        text = "Заполнить из PDF",
                        onClick = viewModel::autofillFromPdf,
                        enabled = !state.isBusy,
                        tint = Brand.Teal
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (form.documentId == null) {
                    SoftButton(text = if (form.pendingFile == null) "Выбрать файл" else "Другой файл", onClick = viewModel::pickInvoiceFile, leadingIcon = ErpIconGlyph.Plus)
                }
            }
            val showTextParser = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            val rawInvoiceText = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SoftButton(
                    text = if (showTextParser.value) "Скрыть автозаполнение из текста" else "Заполнить из текста счёта",
                    onClick = { showTextParser.value = !showTextParser.value }
                )
                Text(
                    "Скопируйте текст из PDF или письма поставщика: номер, дата, ИНН/КПП и строки подставятся автоматически",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
            if (showTextParser.value) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ErpTextField(
                        value = rawInvoiceText.value,
                        onValueChange = { rawInvoiceText.value = it },
                        label = "Текст счёта (из PDF или письма)",
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GradientButton(
                            text = "Распознать и заполнить",
                            onClick = {
                                viewModel.applyParsedText(rawInvoiceText.value)
                                if (rawInvoiceText.value.isNotBlank()) showTextParser.value = false
                            },
                            enabled = rawInvoiceText.value.isNotBlank(),
                            gradient = Gradients.Ocean
                        )
                        SoftButton(text = "Очистить", onClick = { rawInvoiceText.value = "" })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ErpTextField(form.number, { v -> viewModel.updateForm { it.copy(number = v) } }, "Номер счёта", Modifier.weight(1f))
                org.example.project.ui.theme.ErpDateField(form.invoiceDate, { v -> viewModel.updateForm { it.copy(invoiceDate = v) } }, "Дата счёта", Modifier.weight(1f))
                org.example.project.ui.theme.ErpDateField(form.validUntil, { v -> viewModel.updateForm { it.copy(validUntil = v) } }, "Действует до", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ErpTextField(form.supplierInn, { v -> viewModel.updateForm { it.copy(supplierInn = v) } }, "ИНН поставщика из счёта", Modifier.weight(1f))
                ErpTextField(form.supplierKpp, { v -> viewModel.updateForm { it.copy(supplierKpp = v) } }, "КПП поставщика из счёта", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ErpTextField(form.paymentTerms, { v -> viewModel.updateForm { it.copy(paymentTerms = v) } }, "Условия оплаты", Modifier.weight(2f))
                ErpTextField(form.prepaymentPct, { v -> viewModel.updateForm { it.copy(prepaymentPct = v) } }, "Предоплата, %", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                ErpTextField(form.leadTimeDays, { v -> viewModel.updateForm { it.copy(leadTimeDays = v) } }, "Срок изготовления, дн.", Modifier.weight(1f))
                Checkbox(checked = form.leadTimeWorking, onCheckedChange = { v -> viewModel.updateForm { it.copy(leadTimeWorking = v) } })
                Text("рабочих дней", style = MaterialTheme.typography.bodySmall)
            }
            ErpTextField(form.deliveryTerms, { v -> viewModel.updateForm { it.copy(deliveryTerms = v) } }, "Условия доставки", Modifier.fillMaxWidth())
            ErpTextField(form.paymentPurpose, { v -> viewModel.updateForm { it.copy(paymentPurpose = v) } }, "Назначение платежа", Modifier.fillMaxWidth())
            ErpTextField(form.supplierManager, { v -> viewModel.updateForm { it.copy(supplierManager = v) } }, "Менеджер поставщика, телефон", Modifier.fillMaxWidth())

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Строки счёта", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Checkbox(checked = form.pricesIncludeVat, onCheckedChange = viewModel::setPricesIncludeVat)
                Text("цены включают НДС", style = MaterialTheme.typography.bodySmall)
            }
            form.lines.forEachIndexed { index, line ->
                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "${line.lineNo}. " + line.nomenclatureLabel.ifEmpty { "номенклатура не сопоставлена" },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (line.nomenclatureId == null) Brand.Rose else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ErpTextField(line.supplierItemCode, { v -> viewModel.updateLine(index, { it.copy(supplierItemCode = v) }, false) }, "Код поставщика", Modifier.weight(1f))
                        ErpTextField(line.supplierItemName, { v -> viewModel.updateLine(index, { it.copy(supplierItemName = v) }, false) }, "Наименование в счёте", Modifier.weight(2f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ErpTextField(line.quantity, { v -> viewModel.updateLine(index, { it.copy(quantity = v) }, true) }, "Кол-во, ${line.unit}", Modifier.weight(1f))
                        ErpTextField(line.unitPrice, { v -> viewModel.updateLine(index, { it.copy(unitPrice = v) }, true) }, "Цена", Modifier.weight(1f))
                        ErpTextField(line.vatRate, { v -> viewModel.updateLine(index, { it.copy(vatRate = v) }, true) }, "НДС, % (пусто: без НДС)", Modifier.weight(1f))
                        ErpTextField(line.vatAmount, { v -> viewModel.updateLine(index, { it.copy(vatAmount = v) }, false) }, "Сумма НДС", Modifier.weight(1f))
                        ErpTextField(line.lineTotal, { v -> viewModel.updateLine(index, { it.copy(lineTotal = v) }, false) }, "Сумма", Modifier.weight(1f))
                    }
                }
            }
            Text(
                "Итоги подставляются по строкам; если в файле счёта они другие, введите как в документе, расхождение покажет сверка",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ErpTextField(form.netTotal, { v -> viewModel.updateForm { it.copy(netTotal = v) } }, "Итого без НДС", Modifier.weight(1f))
                ErpTextField(form.vatTotal, { v -> viewModel.updateForm { it.copy(vatTotal = v) } }, "НДС", Modifier.weight(1f))
                ErpTextField(form.grossTotal, { v -> viewModel.updateForm { it.copy(grossTotal = v) } }, "Итого с НДС", Modifier.weight(1f))
            }
            ErpTextField(form.note, { v -> viewModel.updateForm { it.copy(note = v) } }, "Примечание", Modifier.fillMaxWidth(), singleLine = false, minLines = 2)
        }
    }
}

@Composable
private fun ConfirmCheckDialog(state: SupplierInvoiceUiState, viewModel: SupplierInvoiceViewModel) {
    val invoice = state.selected ?: return
    val needsReason = state.check?.requiresReason == true
    ErpDialog(
        title = "Подтвердить сверку счёта № ${invoice.number}",
        subtitle = if (needsReason) "Есть расхождения, которые принимаются только с обоснованием" else "Расхождений, требующих решения, нет",
        glyph = ErpIconGlyph.CheckCircle,
        gradient = Gradients.Emerald,
        onDismiss = viewModel::dismissDialog,
        confirmText = "Подтвердить",
        onConfirm = viewModel::confirmCheck,
        confirmEnabled = !state.isBusy && (!needsReason || state.acceptReason.isNotBlank()),
        confirmGradient = Gradients.Emerald,
        dismissText = "Назад"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.error?.let { ErrorMessageCard(error = it) }
            Text(
                text = listOfNotNull(
                    invoice.supplierName,
                    invoice.purchaseOrderNumber?.let { "заказ $it" },
                    "сумма ${invoice.grossTotal.asMoney(invoice.currency)}"
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            val issues = state.check?.issues.orEmpty()
            if (issues.isEmpty()) {
                Text(
                    "Расхождений со сверкой нет: счёт совпадает с заказом по поставщику, позициям, количеству и ценам.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.Teal
                )
            } else {
                Text("Результат сверки:", style = MaterialTheme.typography.labelLarge)
                issues.forEach {
                    Text(
                        "• " + SupplierInvoiceViewModel.severityTitle(it.severity) + ": " +
                            (it.lineNo?.let { n -> "строка $n, " } ?: "") + it.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = it.severity.accent()
                    )
                }
            }
            Text(
                "После подтверждения счёт нельзя править. Дальше можно оформить поступление на склад " +
                    "или вернуть счёт в черновик.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (needsReason) {
                ErpTextField(
                    value = state.acceptReason,
                    onValueChange = viewModel::updateAcceptReason,
                    label = "Обоснование (кто и почему согласовал расхождение)",
                    singleLine = false,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun CancelInvoiceDialog(state: SupplierInvoiceUiState, viewModel: SupplierInvoiceViewModel) {
    val invoice = state.selected ?: return
    ErpDialog(
        title = "Отмена счёта № ${invoice.number}",
        subtitle = "Причина сохраняется в истории счёта",
        glyph = ErpIconGlyph.Alert,
        gradient = Gradients.Sunset,
        onDismiss = viewModel::dismissDialog,
        confirmText = "Отменить счёт",
        onConfirm = viewModel::cancel,
        confirmEnabled = !state.isBusy && state.cancelReason.isNotBlank(),
        confirmGradient = org.example.project.ui.theme.Gradients.Danger,
        dismissText = "Назад"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.error?.let { ErrorMessageCard(error = it) }
            ErpTextField(state.cancelReason, viewModel::updateCancelReason, "Причина отмены", Modifier.fillMaxWidth(), singleLine = false, minLines = 2)
        }
    }
}

@Composable
private fun ReceiptDialog(state: SupplierInvoiceUiState, viewModel: SupplierInvoiceViewModel) {
    val invoice = state.selected ?: return
    ErpDialog(
        title = "Поступление по счёту № ${invoice.number}",
        subtitle = "Склад увидит поступление в очереди «Ожидается к приёмке», внесёт факт и ячейки и проведёт его",
        glyph = ErpIconGlyph.Warehouse,
        gradient = Gradients.Emerald,
        onDismiss = viewModel::dismissDialog,
        confirmText = "Передать складу",
        onConfirm = viewModel::createReceipt,
        confirmEnabled = !state.isBusy && state.receiptWarehouseId != null,
        confirmGradient = Gradients.Emerald,
        dismissText = "Назад"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.error?.let { ErrorMessageCard(error = it) }
            WarehouseDropdown(
                warehouses = state.warehouses,
                selected = state.warehouses.firstOrNull { it.id == state.receiptWarehouseId },
                onSelect = { viewModel.selectReceiptWarehouse(it.id) },
                modifier = Modifier.fillMaxWidth(),
                label = "Склад приёмки"
            )
            if (state.warehouses.isEmpty()) {
                Text("Нет доступных складов (нужно право «wms:read»)", style = MaterialTheme.typography.bodySmall, color = Brand.Amber)
            }
            Text(
                "Ожидается: " + invoice.lines.joinToString(", ") { "${it.supplierItemName} · ${it.quantity.trimZeros()} ${it.unit}" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Следующий шаг по счёту простыми словами: куда идти и что нажать. */
private fun nextStepText(invoice: SupplierInvoiceDto): String = when {
    invoice.status == SupplierInvoiceStatus.CANCELLED -> "счёт отменён, дальнейших действий не требуется."
    invoice.receiptNumber != null ->
        "поступление ${invoice.receiptNumber} (${receiptStatusTitle(invoice.receiptStatus)}) принимает склад: " +
            "в разделе «Склад» откройте очередь «Ожидается к приёмке», внесите фактическое количество и проведите приёмку."
    invoice.status == SupplierInvoiceStatus.CHECKED ->
        "нажмите «Оформить поступление на склад», чтобы передать счёт на приёмку."
    else -> "проверьте результат сверки с заказом и нажмите «Подтвердить сверку»; при ошибках исправьте счёт."
}

/** Имена сотрудников для истории; список подгружается один раз и не мешает показу карточки. */
@Composable
internal fun rememberUserNames(): Map<Long, String> {
    val repository: org.example.project.data.UserRepository = org.koin.compose.koinInject()
    var names by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Map<Long, String>>(emptyMap()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        when (val result = repository.listOptions()) {
            is org.example.project.data.ApiResult.Success -> names = result.data.associate { it.id to it.fullName }
            is org.example.project.data.ApiResult.Error -> Unit
        }
    }
    return names
}
