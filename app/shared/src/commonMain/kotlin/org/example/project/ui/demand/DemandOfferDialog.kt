package org.example.project.ui.demand

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.dto.SupplierVerificationStatus
import org.example.project.ui.components.SearchableDropdown
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.ErpDateField
import org.example.project.ui.theme.ErpDialog
import org.example.project.ui.theme.ErpFilterChip
import org.example.project.ui.theme.ErpFormat
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.ErpTextField
import org.example.project.ui.theme.Gradients
import org.example.project.ui.theme.SoftButton
import kotlin.math.roundToLong

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DemandCreateOfferDialog(
    state: DemandUiState,
    viewModel: DemandViewModel
) {
    val form = state.createOfferForm
    var showQuickSupplier by remember { mutableStateOf(false) }
    var quickName by remember { mutableStateOf("") }
    var quickInn by remember { mutableStateOf("") }
    var quickVerify by remember { mutableStateOf(true) }

    val activeSuppliers = remember(state.suppliers) {
        state.suppliers.filter { !it.archived && it.verificationStatus != SupplierVerificationStatus.REJECTED }
    }

    val liveTotals = remember(form.lines) {
        var netSum = 0.0
        var vatSum = 0.0
        form.lines.filter { it.included }.forEach { line ->
            val qty = line.quantity.toDoubleSafe()
            val price = line.unitPrice.toDoubleSafe()
            val vatRate = line.vatRate.trim().replace(',', '.').toDoubleOrNull() ?: 20.0
            if (qty > 0.0 && price >= 0.0) {
                val lineNet = qty * price
                val lineVat = lineNet * vatRate / 100.0
                netSum += lineNet
                vatSum += lineVat
            }
        }
        val netRounded = (netSum * 100).roundToLong() / 100.0
        val vatRounded = (vatSum * 100).roundToLong() / 100.0
        val grossRounded = ((netSum + vatSum) * 100).roundToLong() / 100.0
        Triple(netRounded.formatMoneyPlain(), vatRounded.formatMoneyPlain(), grossRounded.formatMoneyPlain())
    }

    ErpDialog(
        title = "Внести предложение со счётом",
        subtitle = "Заявка ${form.demandNumber}: выберите поставщика, прикрепите счёт и укажите цены",
        glyph = ErpIconGlyph.Documents,
        gradient = Gradients.Ocean,
        width = 760.dp,
        confirmText = if (state.isActionRunning) "Сохранение…" else "Сохранить предложение",
        confirmEnabled = !state.isActionRunning && form.hasInvoiceFile && form.supplierId != null,
        onConfirm = viewModel::submitCreateOffer,
        onDismiss = { if (!state.isActionRunning) viewModel.dismissDialog() },
        scrollToTopKey = state.operationError
    ) {
        state.operationError?.let { ErrorMessageCard(error = it) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SearchableDropdown(
                items = activeSuppliers,
                selected = activeSuppliers.firstOrNull { it.id == form.supplierId },
                onSelect = { viewModel.updateCreateOfferForm(form.copy(supplierId = it.id)) },
                label = "Поставщик *",
                itemText = { "${it.number} · ${it.name}" },
                emptyText = "Нет поставщиков — добавьте справа",
                modifier = Modifier.weight(1f)
            )
            if (viewModel.canManageSuppliers()) {
                SoftButton(
                    text = "+ Быстро добавить",
                    onClick = {
                        quickName = ""
                        quickInn = ""
                        quickVerify = true
                        showQuickSupplier = true
                    }
                )
            }
        }

        // Обязательный блок счёта поставщика
        val invoiceAccent = if (form.hasInvoiceFile) Brand.Mint else Brand.Amber
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(invoiceAccent.copy(alpha = 0.08f))
                .border(1.dp, invoiceAccent.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Счёт поставщика (файл PDF/скан) *",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = form.invoiceFileLabel?.let { "Прикреплён файл: $it" }
                            ?: "Сначала прикрепите файл счёта от поставщика",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (form.hasInvoiceFile) Brand.Teal else Brand.Amber
                    )
                }
                Spacer(Modifier.width(8.dp))
                SoftButton(
                    text = if (form.hasInvoiceFile) "Заменить файл" else "Прикрепить файл счёта *",
                    onClick = viewModel::pickOfferInvoiceFile,
                    leadingIcon = ErpIconGlyph.Attachment,
                    tint = if (form.hasInvoiceFile) Brand.Teal else Brand.Blue
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ErpTextField(
                    value = form.invoiceNumber,
                    onValueChange = { viewModel.updateCreateOfferForm(form.copy(invoiceNumber = it)) },
                    label = "Номер счёта",
                    placeholder = "Например: СЧ-145",
                    modifier = Modifier.weight(1f)
                )
                ErpDateField(
                    value = form.invoiceDate,
                    onValueChange = { viewModel.updateCreateOfferForm(form.copy(invoiceDate = it)) },
                    label = "Дата счёта",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ErpTextField(
                value = form.deliveryDays,
                onValueChange = { viewModel.updateCreateOfferForm(form.copy(deliveryDays = it)) },
                label = "Срок поставки (дн.)",
                placeholder = "Например: 5",
                modifier = Modifier.weight(1f)
            )
            ErpDateField(
                value = form.validUntil,
                onValueChange = { viewModel.updateCreateOfferForm(form.copy(validUntil = it)) },
                label = "Действует до",
                modifier = Modifier.weight(1f)
            )
        }

        ErpTextField(
            value = form.paymentTerms,
            onValueChange = { viewModel.updateCreateOfferForm(form.copy(paymentTerms = it)) },
            label = "Условия оплаты",
            modifier = Modifier.fillMaxWidth()
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("100% предоплата", "50/50", "Постоплата").forEach { preset ->
                ErpFilterChip(
                    selected = form.paymentTerms == preset,
                    onClick = { viewModel.updateCreateOfferForm(form.copy(paymentTerms = preset)) },
                    label = { Text(preset) }
                )
            }
        }

        HorizontalDivider()
        Text("Позиции предложения", style = MaterialTheme.typography.labelLarge)
        form.lines.forEachIndexed { index, line ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        if (form.lines.size > 1) {
                            Checkbox(
                                checked = line.included,
                                onCheckedChange = { checked ->
                                    viewModel.updateCreateOfferLine(index) { it.copy(included = checked) }
                                }
                            )
                        }
                        Column {
                            Text(line.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                text = "По заявке: ${ErpFormat.qty(line.deficit)} ${line.unit}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (line.included && line.unitPrice.toDoubleSafe() >= 0.0 && line.unitPrice.isNotBlank()) {
                        val qty = line.quantity.toDoubleSafe()
                        val price = line.unitPrice.toDoubleSafe()
                        val vat = line.vatRate.trim().replace(',', '.').toDoubleOrNull() ?: 20.0
                        val gross = ((qty * price * (1.0 + vat / 100.0)) * 100).roundToLong() / 100.0
                        Text(
                            text = ErpFormat.money(gross.formatMoneyPlain(), "RUB"),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = Brand.Blue
                        )
                    }
                }
                if (line.included) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ErpTextField(
                            value = line.quantity,
                            onValueChange = { v -> viewModel.updateCreateOfferLine(index) { it.copy(quantity = v) } },
                            label = "Кол-во (${line.unit}) *",
                            modifier = Modifier.weight(1f)
                        )
                        ErpTextField(
                            value = line.unitPrice,
                            onValueChange = { v -> viewModel.updateCreateOfferLine(index) { it.copy(unitPrice = v) } },
                            label = "Цена за ед., ₽ *",
                            modifier = Modifier.weight(1.1f)
                        )
                        ErpTextField(
                            value = line.vatRate,
                            onValueChange = { v -> viewModel.updateCreateOfferLine(index) { it.copy(vatRate = v) } },
                            label = "НДС %",
                            modifier = Modifier.weight(0.7f)
                        )
                    }
                }
            }
        }

        // Живой подсчёт суммы
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Brand.Blue.copy(alpha = 0.08f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Без НДС: ${ErpFormat.money(liveTotals.first, "RUB")} · НДС: ${ErpFormat.money(liveTotals.second, "RUB")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Итого с НДС: ${ErpFormat.money(liveTotals.third, "RUB")}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Brand.Blue
            )
        }

        ErpTextField(
            value = form.note,
            onValueChange = { viewModel.updateCreateOfferForm(form.copy(note = it)) },
            label = "Комментарий",
            singleLine = false,
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
    }

    if (showQuickSupplier) {
        ErpDialog(
            title = "Быстро добавить поставщика",
            glyph = ErpIconGlyph.Cart,
            gradient = Gradients.Sunset,
            width = 480.dp,
            confirmText = if (state.isActionRunning) "Создание…" else "Добавить",
            confirmEnabled = quickName.isNotBlank() && !state.isActionRunning,
            onConfirm = {
                viewModel.quickCreateSupplier(
                    name = quickName,
                    inn = quickInn,
                    verifyNow = quickVerify,
                    onDone = { showQuickSupplier = false }
                )
            },
            onDismiss = { if (!state.isActionRunning) showQuickSupplier = false }
        ) {
            state.operationError?.let { ErrorMessageCard(error = it) }
            ErpTextField(
                value = quickName,
                onValueChange = { quickName = it },
                label = "Наименование *",
                modifier = Modifier.fillMaxWidth()
            )
            ErpTextField(
                value = quickInn,
                onValueChange = { quickInn = it },
                label = "ИНН (10 или 12 цифр)",
                modifier = Modifier.fillMaxWidth()
            )
            if (viewModel.canVerifySuppliers()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = quickVerify,
                        onCheckedChange = { quickVerify = it }
                    )
                    Text("Сразу отметить поставщика как проверенного", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun Double.formatMoneyPlain(): String {
    val cents = (this * 100).roundToLong()
    val sign = if (cents < 0) "-" else ""
    val abs = kotlin.math.abs(cents)
    return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
}
