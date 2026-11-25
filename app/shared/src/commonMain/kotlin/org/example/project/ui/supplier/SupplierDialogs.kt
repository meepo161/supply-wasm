package org.example.project.ui.supplier

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import org.example.project.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.dto.SupplierVerificationStatus
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.ErpDateField
import org.example.project.ui.theme.ErpDialog
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.ErpTextField
import org.example.project.ui.theme.Gradients
import org.example.project.ui.theme.SoftButton

@Composable
fun SupplierDialogs(state: SupplierUiState, viewModel: SupplierViewModel) {
    val dialog = state.activeDialog ?: return
    when (dialog) {
        SupplierDialog.CreateSupplier -> CreateSupplierDialog(state, viewModel)
        SupplierDialog.EditSupplier -> EditSupplierDialog(state, viewModel)
        SupplierDialog.AddContact -> AddContactDialog(state, viewModel)
        SupplierDialog.AddAddress -> AddAddressDialog(state, viewModel)
        SupplierDialog.AddDocument -> AddDocumentDialog(state, viewModel)
        SupplierDialog.EditCategories -> CategoriesDialog(state, viewModel)
        SupplierDialog.Verify -> VerifyDialog(state, viewModel)
        SupplierDialog.CreateOffer -> CreateOfferDialog(state, viewModel)
        SupplierDialog.EditOffer -> EditOfferDialog(state, viewModel)
        is SupplierDialog.SelectOffer -> SelectOfferDialog(dialog, state, viewModel)
        SupplierDialog.CreateClaim -> { }
    }
}

@Composable
private fun CreateSupplierDialog(state: SupplierUiState, viewModel: SupplierViewModel) {
    val form = state.createForm
    ErpDialog(
        title = "Новый поставщик",
        subtitle = "Реквизиты можно уточнить позже в карточке",
        glyph = ErpIconGlyph.Plus,
        gradient = Gradients.Sunset,
        onDismiss = viewModel::dismissDialog,
        confirmText = "Создать",
        onConfirm = viewModel::createSupplier,
        confirmEnabled = !state.isActionRunning,
        width = 640.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            state.operationError?.let { ErrorMessageCard(error = it) }
            Text(
                text = "Поля со звёздочкой (*) обязательны",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ErpTextField(
                value = form.name,
                onValueChange = { viewModel.updateCreateForm(form.copy(name = it)) },
                label = "Краткое наименование *",
                modifier = Modifier.fillMaxWidth()
            )
            ErpTextField(
                value = form.fullName,
                onValueChange = { viewModel.updateCreateForm(form.copy(fullName = it)) },
                label = "Полное наименование",
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ErpTextField(
                    value = form.inn,
                    onValueChange = { viewModel.updateCreateForm(form.copy(inn = it)) },
                    label = "ИНН",
                    supportingText = "10 цифр (организация) или 12 (ИП)",
                    modifier = Modifier.weight(1f)
                )
                ErpTextField(
                    value = form.kpp,
                    onValueChange = { viewModel.updateCreateForm(form.copy(kpp = it)) },
                    label = "КПП",
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ErpTextField(
                    value = form.ogrn,
                    onValueChange = { viewModel.updateCreateForm(form.copy(ogrn = it)) },
                    label = "ОГРН",
                    modifier = Modifier.weight(1f)
                )
                ErpTextField(
                    value = form.country,
                    onValueChange = { viewModel.updateCreateForm(form.copy(country = it)) },
                    label = "Страна",
                    modifier = Modifier.weight(1f)
                )
            }
            ErpTextField(
                value = form.legalAddress,
                onValueChange = { viewModel.updateCreateForm(form.copy(legalAddress = it)) },
                label = "Юридический адрес",
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ErpTextField(
                    value = form.phone,
                    onValueChange = { viewModel.updateCreateForm(form.copy(phone = it)) },
                    label = "Телефон",
                    modifier = Modifier.weight(1f)
                )
                ErpTextField(
                    value = form.email,
                    onValueChange = { viewModel.updateCreateForm(form.copy(email = it)) },
                    label = "Почта",
                    modifier = Modifier.weight(1f)
                )
            }
            ResponsibleUserDropdown(
                users = state.users,
                selectedId = form.responsibleId,
                onSelect = { viewModel.updateCreateForm(form.copy(responsibleId = it)) },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Основной контакт (необязательно)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ErpTextField(
                    value = form.contactName,
                    onValueChange = { viewModel.updateCreateForm(form.copy(contactName = it)) },
                    label = "ФИО",
                    modifier = Modifier.weight(1f)
                )
                ErpTextField(
                    value = form.contactPosition,
                    onValueChange = { viewModel.updateCreateForm(form.copy(contactPosition = it)) },
                    label = "Должность",
                    modifier = Modifier.weight(1f)
                )
            }
            ErpTextField(
                value = form.note,
                onValueChange = { viewModel.updateCreateForm(form.copy(note = it)) },
                label = "Примечание",
                singleLine = false,
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun EditSupplierDialog(state: SupplierUiState, viewModel: SupplierViewModel) {
    val form = state.editForm ?: return
    ErpDialog(
        title = "Правка карточки",
        subtitle = "Изменения фиксируются в аудите",
        glyph = ErpIconGlyph.Documents,
        gradient = Gradients.Ocean,
        onDismiss = viewModel::dismissDialog,
        confirmText = "Сохранить",
        onConfirm = viewModel::saveSupplier,
        confirmEnabled = !state.isActionRunning,
        width = 620.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            state.operationError?.let { ErrorMessageCard(error = it) }
            ErpTextField(
                value = form.name,
                onValueChange = { viewModel.updateEditForm(form.copy(name = it)) },
                label = "Краткое наименование *",
                modifier = Modifier.fillMaxWidth()
            )
            ErpTextField(
                value = form.fullName,
                onValueChange = { viewModel.updateEditForm(form.copy(fullName = it)) },
                label = "Полное наименование",
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ErpTextField(
                    value = form.inn,
                    onValueChange = { viewModel.updateEditForm(form.copy(inn = it)) },
                    label = "ИНН",
                    supportingText = "10 цифр (организация) или 12 (ИП)",
                    modifier = Modifier.weight(1f)
                )
                ErpTextField(
                    value = form.kpp,
                    onValueChange = { viewModel.updateEditForm(form.copy(kpp = it)) },
                    label = "КПП",
                    modifier = Modifier.weight(1f)
                )
            }
            ErpTextField(
                value = form.legalAddress,
                onValueChange = { viewModel.updateEditForm(form.copy(legalAddress = it)) },
                label = "Юридический адрес",
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ErpTextField(
                    value = form.phone,
                    onValueChange = { viewModel.updateEditForm(form.copy(phone = it)) },
                    label = "Телефон",
                    modifier = Modifier.weight(1f)
                )
                ErpTextField(
                    value = form.email,
                    onValueChange = { viewModel.updateEditForm(form.copy(email = it)) },
                    label = "Почта",
                    modifier = Modifier.weight(1f)
                )
            }
            ErpTextField(
                value = form.note,
                onValueChange = { viewModel.updateEditForm(form.copy(note = it)) },
                label = "Примечание",
                singleLine = false,
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun AddContactDialog(state: SupplierUiState, viewModel: SupplierViewModel) {
    val form = state.contactForm
    ErpDialog(
        title = "Новый контакт",
        onDismiss = viewModel::dismissDialog,
        confirmText = "Добавить",
        onConfirm = viewModel::addContact,
        confirmEnabled = !state.isActionRunning
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.operationError?.let { ErrorMessageCard(error = it) }
            ErpTextField(
                value = form.fullName,
                onValueChange = { viewModel.updateContactForm(form.copy(fullName = it)) },
                label = "ФИО *",
                modifier = Modifier.fillMaxWidth()
            )
            ErpTextField(
                value = form.position,
                onValueChange = { viewModel.updateContactForm(form.copy(position = it)) },
                label = "Должность",
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ErpTextField(
                    value = form.phone,
                    onValueChange = { viewModel.updateContactForm(form.copy(phone = it)) },
                    label = "Телефон",
                    modifier = Modifier.weight(1f)
                )
                ErpTextField(
                    value = form.email,
                    onValueChange = { viewModel.updateContactForm(form.copy(email = it)) },
                    label = "Почта",
                    modifier = Modifier.weight(1f)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = form.isPrimary,
                    onCheckedChange = { viewModel.updateContactForm(form.copy(isPrimary = it)) }
                )
                Text("Основной контакт", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AddAddressDialog(state: SupplierUiState, viewModel: SupplierViewModel) {
    val form = state.addressForm
    val kinds = listOf("LEGAL" to "Юридический", "ACTUAL" to "Фактический", "POSTAL" to "Почтовый", "WAREHOUSE" to "Склад", "DELIVERY" to "Доставка")
    ErpDialog(
        title = "Новый адрес",
        onDismiss = viewModel::dismissDialog,
        confirmText = "Добавить",
        onConfirm = viewModel::addAddress,
        confirmEnabled = !state.isActionRunning
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.operationError?.let { ErrorMessageCard(error = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                kinds.forEach { (value, title) ->
                    SoftButton(
                        text = title,
                        onClick = { viewModel.updateAddressForm(form.copy(kind = value)) },
                        tint = if (form.kind == value) Brand.Orange else Brand.Slate
                    )
                }
            }
            ErpTextField(
                value = form.address,
                onValueChange = { viewModel.updateAddressForm(form.copy(address = it)) },
                label = "Адрес *",
                singleLine = false,
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            ErpTextField(
                value = form.note,
                onValueChange = { viewModel.updateAddressForm(form.copy(note = it)) },
                label = "Примечание",
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun AddDocumentDialog(state: SupplierUiState, viewModel: SupplierViewModel) {
    val form = state.documentForm
    val kinds = listOf(
        "CONTRACT" to "Договор",
        "CERTIFICATE" to "Сертификат",
        "LICENSE" to "Лицензия",
        "AGREEMENT" to "Соглашение",
        "OTHER" to "Прочее"
    )
    ErpDialog(
        title = "Новый документ",
        subtitle = "Файл прикладывается в разделе «Документы»",
        onDismiss = viewModel::dismissDialog,
        confirmText = "Добавить",
        onConfirm = viewModel::addDocument,
        confirmEnabled = !state.isActionRunning
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.operationError?.let { ErrorMessageCard(error = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                kinds.forEach { (value, title) ->
                    SoftButton(
                        text = title,
                        onClick = { viewModel.updateDocumentForm(form.copy(kind = value)) },
                        tint = if (form.kind == value) Brand.Teal else Brand.Slate
                    )
                }
            }
            ErpTextField(
                value = form.title,
                onValueChange = { viewModel.updateDocumentForm(form.copy(title = it)) },
                label = "Название *",
                modifier = Modifier.fillMaxWidth()
            )
            ErpTextField(
                value = form.documentNo,
                onValueChange = { viewModel.updateDocumentForm(form.copy(documentNo = it)) },
                label = "Номер документа",
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ErpDateField(
                    value = form.validFrom,
                    onValueChange = { viewModel.updateDocumentForm(form.copy(validFrom = it)) },
                    label = "Действует с",
                    modifier = Modifier.weight(1f)
                )
                ErpDateField(
                    value = form.validTo,
                    onValueChange = { viewModel.updateDocumentForm(form.copy(validTo = it)) },
                    label = "Действует до",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun CategoriesDialog(state: SupplierUiState, viewModel: SupplierViewModel) {
    ErpDialog(
        title = "Категории закупок",
        subtitle = "Отметьте категории, по которым работает поставщик",
        glyph = ErpIconGlyph.Filter,
        gradient = Gradients.Berry,
        onDismiss = viewModel::dismissDialog,
        confirmText = "Сохранить",
        onConfirm = viewModel::saveCategories,
        confirmEnabled = !state.isActionRunning,
        width = 560.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            state.operationError?.let { ErrorMessageCard(error = it) }
            if (state.categories.isEmpty()) {
                Text(
                    text = "Справочник категорий пуст. Категории создаются администратором через API " +
                        "/api/supplier-categories.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.Amber
                )
            }
            state.categories.forEach { category ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = category.id in state.categorySelection,
                        onCheckedChange = { viewModel.toggleCategory(category.id) }
                    )
                    Text(
                        text = "${category.code} · ${category.name}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun VerifyDialog(state: SupplierUiState, viewModel: SupplierViewModel) {
    ErpDialog(
        title = "Проверка контрагента",
        subtitle = "Проверенный поставщик допускается к выбору предложения",
        glyph = ErpIconGlyph.Shield,
        gradient = Gradients.Emerald,
        onDismiss = viewModel::dismissDialog,
        dismissText = "Закрыть"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.operationError?.let { ErrorMessageCard(error = it) }
            ErpTextField(
                value = state.verificationNote,
                onValueChange = viewModel::updateVerificationNote,
                label = "Примечание проверки",
                singleLine = false,
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton(
                    text = "На проверке",
                    onClick = { viewModel.verify(SupplierVerificationStatus.IN_REVIEW) },
                    tint = Brand.Amber
                )
                SoftButton(
                    text = "Проверен",
                    onClick = { viewModel.verify(SupplierVerificationStatus.VERIFIED) },
                    tint = Brand.Mint
                )
                SoftButton(
                    text = "Отклонить",
                    onClick = { viewModel.verify(SupplierVerificationStatus.REJECTED) },
                    tint = Brand.Rose
                )
            }
            Text(
                text = "Отклонение требует примечания. Если вы указаны ответственным за этого " +
                    "поставщика, проверить его карточку может только сотрудник с правом " +
                    "«Проверка любых контрагентов» — обратитесь к администратору.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CreateOfferDialog(state: SupplierUiState, viewModel: SupplierViewModel) {
    val form = state.offerForm
    var localLines by remember { mutableStateOf(form.demandLines) }
    var showExtra by remember { mutableStateOf(false) }
    var showQuickSupplier by remember { mutableStateOf(false) }
    var quickName by remember { mutableStateOf("") }
    var quickInn by remember { mutableStateOf("") }
    var quickEmail by remember { mutableStateOf("") }
    var quickVerify by remember { mutableStateOf(true) }
    LaunchedEffect(form.demandId, form.demandLines) {
        localLines = form.demandLines
    }
    ErpDialog(
        title = "Предложение поставщика",
        subtitle = "Позиции берутся из потребности, количество ограничено дефицитом",
        glyph = ErpIconGlyph.Documents,
        gradient = Gradients.Ocean,
        onDismiss = viewModel::dismissDialog,
        confirmText = "Сохранить предложение",
        onConfirm = viewModel::createOffer,
        confirmEnabled = !state.isActionRunning,
        width = 700.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            state.operationError?.let { ErrorMessageCard(error = it) }
            DemandFilterDropdown(
                demands = state.demands,
                selectedId = form.demandId,
                onSelect = viewModel::onOfferDemandChanged,
                modifier = Modifier.fillMaxWidth(),
                label = "Потребность *"
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SupplierDropdown(
                    suppliers = state.suppliers,
                    selectedId = form.supplierId,
                    onSelect = { viewModel.updateOfferForm(form.copy(supplierId = it)) },
                    onlyVerified = true,
                    modifier = Modifier.weight(1f),
                    label = "Поставщик *"
                )
                SoftButton(text = "+ Новый", onClick = {
                    quickName = ""
                    quickInn = ""
                    quickEmail = ""
                    quickVerify = true
                    showQuickSupplier = true
                })
            }
            viewModel.existingOfferFor(form.demandId, form.supplierId)?.let { existing ->
                Text(
                    text = "Внимание: у этого поставщика уже есть предложение ${existing.number} " +
                        "по этой потребности (${existing.status.localizedName()}). Если нужно " +
                        "исправить условия, откройте сравнение и нажмите «Править предложение»; " +
                        "новое вносите, только если это отдельное предложение.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.Amber
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ErpTextField(
                    value = form.deliveryDays,
                    onValueChange = { viewModel.updateOfferForm(form.copy(deliveryDays = it)) },
                    label = "Срок поставки, дн.",
                    modifier = Modifier.weight(1f)
                )
                ErpDateField(
                    value = form.validUntil,
                    onValueChange = { viewModel.updateOfferForm(form.copy(validUntil = it)) },
                    label = "Действует до *",
                    modifier = Modifier.weight(1f)
                )
            }
            ErpTextField(
                value = form.paymentTerms,
                onValueChange = { viewModel.updateOfferForm(form.copy(paymentTerms = it)) },
                label = "Условия оплаты",
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = form.documentsComplete,
                        onCheckedChange = { viewModel.updateOfferForm(form.copy(documentsComplete = it)) }
                    )
                    Text("Комплект документов подтверждён", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { showExtra = !showExtra }) {
                    Text(if (showExtra) "Скрыть доп. условия" else "Дополнительные условия ▾")
                }
            }
            if (showExtra) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ComplianceDropdown(
                        selected = form.compliance,
                        onSelect = { viewModel.updateOfferForm(form.copy(compliance = it)) },
                        modifier = Modifier.weight(1f)
                    )
                    ErpTextField(
                        value = form.warrantyMonths,
                        onValueChange = { viewModel.updateOfferForm(form.copy(warrantyMonths = it)) },
                        label = "Гарантия, мес.",
                        modifier = Modifier.weight(0.8f)
                    )
                    ErpTextField(
                        value = form.currency,
                        onValueChange = { viewModel.updateOfferForm(form.copy(currency = it)) },
                        label = "Валюта",
                        supportingText = "RUB — рубли (₽)",
                        modifier = Modifier.weight(0.7f)
                    )
                }
                ErpTextField(
                    value = form.deliveryTerms,
                    onValueChange = { viewModel.updateOfferForm(form.copy(deliveryTerms = it)) },
                    label = "Доставка",
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (form.demandLines.isEmpty()) {
                Text(
                    text = "Выберите потребность, позиции появятся автоматически",
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.Amber
                )
            }
            form.lines.forEachIndexed { index, line ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val option = localLines.firstOrNull { it.demandLineId == line.demandLineId }
                    Column(modifier = Modifier.weight(1.2f)) {
                        Text(
                            text = option?.label ?: "Позиция ${index + 1}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "дефицит: ${option?.deficit ?: "—"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    ErpTextField(
                        value = line.quantity,
                        onValueChange = { viewModel.updateOfferLine(index, line.copy(quantity = it)) },
                        label = "Кол-во *",
                        modifier = Modifier.weight(0.7f)
                    )
                    ErpTextField(
                        value = line.unitPrice,
                        onValueChange = { viewModel.updateOfferLine(index, line.copy(unitPrice = it)) },
                        label = "Цена, ₽ *",
                        modifier = Modifier.weight(0.7f)
                    )
                    ErpTextField(
                        value = line.vatRate,
                        onValueChange = { viewModel.updateOfferLine(index, line.copy(vatRate = it)) },
                        label = "НДС %",
                        modifier = Modifier.weight(0.5f)
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton(text = "Добавить позицию", onClick = viewModel::addOfferLine)
                TextButton(
                    onClick = { viewModel.removeOfferLine(form.lines.size - 1) },
                    enabled = form.lines.size > 1
                ) { Text("Убрать последнюю") }
            }
        }
    }
    if (showQuickSupplier) {
        ErpDialog(
            title = "Новый поставщик",
            glyph = ErpIconGlyph.Documents,
            gradient = Gradients.Ocean,
            onDismiss = { if (!state.isActionRunning) showQuickSupplier = false },
            confirmText = if (state.isActionRunning) "Создание…" else "Создать",
            onConfirm = {
                viewModel.quickCreateSupplierForOffer(
                    name = quickName,
                    inn = quickInn,
                    email = quickEmail,
                    verifyNow = quickVerify,
                    onDone = { showQuickSupplier = false }
                )
            },
            confirmEnabled = quickName.isNotBlank() && !state.isActionRunning,
            width = 480.dp
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
            ErpTextField(
                value = quickEmail,
                onValueChange = { quickEmail = it },
                label = "Почта / контакт",
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = quickVerify,
                    onCheckedChange = { quickVerify = it }
                )
                Text("Сразу подтвердить проверку контрагента", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SelectOfferDialog(
    dialog: SupplierDialog.SelectOffer,
    state: SupplierUiState,
    viewModel: SupplierViewModel
) {
    ErpDialog(
        title = "Выбор предложения",
        subtitle = dialog.offerNumber,
        glyph = ErpIconGlyph.CheckCircle,
        gradient = Gradients.Emerald,
        onDismiss = viewModel::dismissDialog,
        confirmText = "Выбрать предложение",
        onConfirm = viewModel::submitSelection,
        confirmGradient = Gradients.Emerald,
        confirmEnabled = !state.isActionRunning
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.operationError?.let { ErrorMessageCard(error = it) }
            ErpTextField(
                value = state.selectionReason,
                onValueChange = viewModel::updateSelectionReason,
                label = "Обоснование выбора",
                placeholder = "Почему это предложение лучше остальных",
                singleLine = false,
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Выбор недоступен, если предложение просрочено, не соответствует " +
                    "техническим требованиям, документы не подтверждены, срок поставки " +
                    "превышает требуемую дату или поставщик не проверен.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EditOfferDialog(state: SupplierUiState, viewModel: SupplierViewModel) {
    val form = state.editOfferForm ?: return
    ErpDialog(
        title = "Правка предложения ${form.number}",
        subtitle = "${form.supplierName}. Изменения фиксируются в истории предложения",
        glyph = ErpIconGlyph.Documents,
        gradient = Gradients.Ocean,
        onDismiss = viewModel::dismissDialog,
        confirmText = "Сохранить изменения",
        onConfirm = viewModel::saveOffer,
        confirmEnabled = !state.isActionRunning,
        width = 700.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            state.operationError?.let { ErrorMessageCard(error = it) }
            Text(
                text = "Поля со звёздочкой (*) обязательны. Поставщика и потребность изменить нельзя: " +
                    "для другого поставщика внесите новое предложение.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ComplianceDropdown(
                    selected = form.compliance,
                    onSelect = { viewModel.updateEditOfferForm(form.copy(compliance = it)) },
                    modifier = Modifier.weight(1f)
                )
                ErpDateField(
                    value = form.validUntil,
                    onValueChange = { viewModel.updateEditOfferForm(form.copy(validUntil = it)) },
                    label = "Действует до *",
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ErpTextField(
                    value = form.deliveryDays,
                    onValueChange = { viewModel.updateEditOfferForm(form.copy(deliveryDays = it)) },
                    label = "Срок поставки, дн.",
                    modifier = Modifier.weight(1f)
                )
                ErpTextField(
                    value = form.warrantyMonths,
                    onValueChange = { viewModel.updateEditOfferForm(form.copy(warrantyMonths = it)) },
                    label = "Гарантия, мес.",
                    modifier = Modifier.weight(1f)
                )
            }
            ErpTextField(
                value = form.paymentTerms,
                onValueChange = { viewModel.updateEditOfferForm(form.copy(paymentTerms = it)) },
                label = "Условия оплаты",
                modifier = Modifier.fillMaxWidth()
            )
            ErpTextField(
                value = form.deliveryTerms,
                onValueChange = { viewModel.updateEditOfferForm(form.copy(deliveryTerms = it)) },
                label = "Доставка",
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = form.documentsComplete,
                    onCheckedChange = { viewModel.updateEditOfferForm(form.copy(documentsComplete = it)) }
                )
                Text("Комплект документов подтверждён", style = MaterialTheme.typography.bodySmall)
            }
            form.lines.forEachIndexed { index, line ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1.2f)) {
                        Text(text = line.label, style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = "ед. изм.: ${line.unit}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    ErpTextField(
                        value = line.quantity,
                        onValueChange = { viewModel.updateEditOfferLine(index, line.copy(quantity = it)) },
                        label = "Кол-во *",
                        modifier = Modifier.weight(0.7f)
                    )
                    ErpTextField(
                        value = line.unitPrice,
                        onValueChange = { viewModel.updateEditOfferLine(index, line.copy(unitPrice = it)) },
                        label = "Цена, ₽ *",
                        modifier = Modifier.weight(0.7f)
                    )
                    ErpTextField(
                        value = line.vatRate,
                        onValueChange = { viewModel.updateEditOfferLine(index, line.copy(vatRate = it)) },
                        label = "НДС %",
                        modifier = Modifier.weight(0.5f)
                    )
                }
            }
            ErpTextField(
                value = form.note,
                onValueChange = { viewModel.updateEditOfferForm(form.copy(note = it)) },
                label = "Примечание",
                singleLine = false,
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
