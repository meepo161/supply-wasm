package org.example.project.ui.supplier

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.ErpDialog
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.ErpTextField
import org.example.project.ui.theme.Gradients
import org.example.project.ui.theme.PillBadge

@Composable
fun PurchaseOrderDialogs(state: PurchaseOrderUiState, viewModel: PurchaseOrderViewModel) {
    val dialog = state.activeDialog ?: return
    when (dialog) {
        PurchaseOrderDialog.Create -> CreateOrderDialog(state, viewModel)
        PurchaseOrderDialog.Edit -> EditOrderDialog(state, viewModel)
        PurchaseOrderDialog.RegisterDelivery -> RegisterDeliveryDialog(state, viewModel)
        is PurchaseOrderDialog.CancelOrder -> CancelOrderDialog(dialog, state, viewModel)
        is PurchaseOrderDialog.CloseOrder -> CloseOrderDialog(dialog, state, viewModel)
        is PurchaseOrderDialog.CancelDelivery -> CancelDeliveryDialog(dialog, state, viewModel)
    }
}

@Composable
private fun CreateOrderDialog(state: PurchaseOrderUiState, viewModel: PurchaseOrderViewModel) {
    val form = state.createForm
    ErpDialog(
        title = "Заказ поставщику",
        subtitle = "Основание: предложение, выбранное инициатором. Заказ создаётся черновиком",
        glyph = ErpIconGlyph.Documents,
        gradient = Gradients.Berry,
        onDismiss = viewModel::dismissDialog,
        confirmText = "Создать заказ",
        onConfirm = viewModel::createOrder,
        confirmEnabled = !state.isActionRunning && form.offerId != null,
        confirmGradient = Gradients.Berry,
        width = 680.dp,
        scrollToTopKey = state.operationError
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            state.operationError?.let { ErrorMessageCard(error = it) }
            val availableOffers = state.selectedOffers.filter { offer ->
                state.orders.none {
                    it.offerId == offer.id && it.status != org.example.project.dto.PurchaseOrderStatus.CANCELLED
                }
            }
            SelectedOfferDropdown(
                offers = availableOffers,
                selectedId = form.offerId,
                onSelect = viewModel::selectOfferForOrder,
                modifier = Modifier.fillMaxWidth(),
                label = "Предложение *"
            )
            Text(
                text = "На согласование заказ автоматически не уходит: после создания откройте его " +
                    "карточку и нажмите «На согласование». Позиции и цены берутся из предложения.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (availableOffers.isEmpty()) {
                Text(
                    text = if (state.selectedOffers.isEmpty()) {
                        "Нет предложений в статусе «выбрано». Сначала выберите предложение в «Снабжение (Заявки)»."
                    } else {
                        "По всем выбранным предложениям заказы уже оформлены (в том числе из «Снабжение (Заявки)»)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Brand.Amber
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                org.example.project.ui.theme.ErpDateField(
                    value = form.expectedAt,
                    onValueChange = { viewModel.updateCreateForm(form.copy(expectedAt = it)) },
                    label = "Ожидаемая поставка",
                    modifier = Modifier.weight(1f)
                )
                OrderResponsibleDropdown(
                    users = state.users,
                    selectedId = form.responsibleId,
                    onSelect = { viewModel.updateCreateForm(form.copy(responsibleId = it)) },
                    modifier = Modifier.weight(1f)
                )
            }
            ErpTextField(
                value = form.paymentTerms,
                onValueChange = { viewModel.updateCreateForm(form.copy(paymentTerms = it)) },
                label = "Условия оплаты",
                modifier = Modifier.fillMaxWidth()
            )
            ErpTextField(
                value = form.deliveryAddress,
                onValueChange = { viewModel.updateCreateForm(form.copy(deliveryAddress = it)) },
                label = "Адрес доставки",
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ErpTextField(
                    value = form.contractNo,
                    onValueChange = { viewModel.updateCreateForm(form.copy(contractNo = it)) },
                    label = "Договор",
                    modifier = Modifier.weight(1f)
                )
                ErpTextField(
                    value = form.invoiceNo,
                    onValueChange = { viewModel.updateCreateForm(form.copy(invoiceNo = it)) },
                    label = "Счёт",
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
            Text(
                text = "Позиции заказа (${form.lines.size}): количество можно только уменьшить",
                style = MaterialTheme.typography.titleSmall
            )
            form.lines.forEachIndexed { index, line ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(line.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "В предложении: ${line.offeredQuantity} ${line.unit} · " +
                                "цена ${line.unitPrice.asMoney(form.currency)} за ${line.unit}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    ErpTextField(
                        value = line.quantity,
                        onValueChange = { viewModel.updateCreateLine(index, it) },
                        label = "Количество, ${line.unit} *",
                        modifier = Modifier.weight(0.5f)
                    )
                }
            }
            state.selectedOffers.firstOrNull { it.id == form.offerId }?.totals?.let { totals ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillBadge(text = "Без НДС: ${totals.netTotal.asMoney(form.currency)}", accent = Brand.Blue)
                    PillBadge(text = "С НДС: ${totals.grossTotal.asMoney(form.currency)}", accent = Brand.Mint, filled = true)
                }
                Text(
                    text = "Итог по предложению целиком; если уменьшите количество, сумму заказа пересчитает сервер.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EditOrderDialog(state: PurchaseOrderUiState, viewModel: PurchaseOrderViewModel) {
    val form = state.editForm ?: return
    ErpDialog(
        title = "Изменение заказа",
        subtitle = "Существенные изменения требуют обоснования и повторного согласования",
        glyph = ErpIconGlyph.Tasks,
        gradient = Gradients.Ocean,
        onDismiss = viewModel::dismissDialog,
        confirmText = "Сохранить",
        onConfirm = viewModel::saveEdit,
        confirmEnabled = !state.isActionRunning,
        width = 680.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            state.operationError?.let { ErrorMessageCard(error = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                org.example.project.ui.theme.ErpDateField(
                    value = form.expectedAt,
                    onValueChange = { viewModel.updateEditForm(form.copy(expectedAt = it)) },
                    label = "Ожидаемая поставка",
                    modifier = Modifier.weight(1f)
                )
                ErpTextField(
                    value = form.paymentTerms,
                    onValueChange = { viewModel.updateEditForm(form.copy(paymentTerms = it)) },
                    label = "Условия оплаты",
                    modifier = Modifier.weight(1f)
                )
            }
            ErpTextField(
                value = form.deliveryAddress,
                onValueChange = { viewModel.updateEditForm(form.copy(deliveryAddress = it)) },
                label = "Адрес доставки",
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ErpTextField(
                    value = form.contractNo,
                    onValueChange = { viewModel.updateEditForm(form.copy(contractNo = it)) },
                    label = "Договор",
                    modifier = Modifier.weight(1f)
                )
                ErpTextField(
                    value = form.invoiceNo,
                    onValueChange = { viewModel.updateEditForm(form.copy(invoiceNo = it)) },
                    label = "Счёт",
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
            Text(
                text = "Позиции: количество не может быть меньше уже поставленного",
                style = MaterialTheme.typography.titleSmall
            )
            form.lines.forEachIndexed { index, line ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(line.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "Заказано ${line.orderedQuantity} ${line.unit} · " +
                                "поставлено ${line.deliveredQuantity}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    ErpTextField(
                        value = line.quantity,
                        onValueChange = { viewModel.updateEditLine(index, it) },
                        label = "Количество, ${line.unit}",
                        modifier = Modifier.weight(0.5f)
                    )
                }
            }
            ErpTextField(
                value = form.changeReason,
                onValueChange = { viewModel.updateEditForm(form.copy(changeReason = it)) },
                label = "Обоснование изменения (если условия существенные)",
                singleLine = false,
                minLines = 2,
                supportingText = "Количество, срок, оплата и адрес доставки: существенные условия",
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun RegisterDeliveryDialog(state: PurchaseOrderUiState, viewModel: PurchaseOrderViewModel) {
    val form = state.deliveryForm ?: return
    val order = state.selectedOrder
    ErpDialog(
        title = "Регистрация поставки",
        subtitle = order?.let { "Заказ ${it.number} · ${it.supplierName ?: "поставщик #${it.supplierId}"}" },
        glyph = ErpIconGlyph.Warehouse,
        gradient = Gradients.Emerald,
        onDismiss = viewModel::dismissDialog,
        confirmText = "Зарегистрировать",
        onConfirm = viewModel::registerDelivery,
        confirmEnabled = !state.isActionRunning,
        confirmGradient = Gradients.Emerald,
        width = 660.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            state.operationError?.let { ErrorMessageCard(error = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                org.example.project.ui.theme.ErpDateField(
                    value = form.deliveredAt,
                    onValueChange = { viewModel.updateDeliveryForm(form.copy(deliveredAt = it)) },
                    label = "Дата поставки",
                    modifier = Modifier.weight(1f)
                )
                ErpTextField(
                    value = form.sourceDocument,
                    onValueChange = { viewModel.updateDeliveryForm(form.copy(sourceDocument = it)) },
                    label = "Документ-основание",
                    modifier = Modifier.weight(1f)
                )
            }
            ErpTextField(
                value = form.deliveryNote,
                onValueChange = { viewModel.updateDeliveryForm(form.copy(deliveryNote = it)) },
                label = "Накладная / УПД",
                modifier = Modifier.fillMaxWidth()
            )
            ErpTextField(
                value = form.note,
                onValueChange = { viewModel.updateDeliveryForm(form.copy(note = it)) },
                label = "Примечание",
                singleLine = false,
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Количество не может превышать остаток по позиции; одна поставка может быть частичной",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            form.lines.forEachIndexed { index, line ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(line.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "Осталось принять: ${line.remaining} ${line.unit}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    ErpTextField(
                        value = line.quantity,
                        onValueChange = { viewModel.updateDeliveryLine(index, it) },
                        label = "Принято, ${line.unit}",
                        modifier = Modifier.weight(0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CancelOrderDialog(
    dialog: PurchaseOrderDialog.CancelOrder,
    state: PurchaseOrderUiState,
    viewModel: PurchaseOrderViewModel
) {
    val form = state.cancelForm ?: CancelOrderForm()
    ErpDialog(
        title = "Отмена заказа ${dialog.number}",
        subtitle = "Причина отмены сохраняется в истории заказа",
        glyph = ErpIconGlyph.Alert,
        gradient = Gradients.Sunset,
        onDismiss = viewModel::dismissDialog,
        confirmText = "Отменить заказ",
        onConfirm = viewModel::cancelOrder,
        confirmEnabled = !state.isActionRunning && form.reason.isNotBlank(),
        confirmGradient = org.example.project.ui.theme.Gradients.Danger,
        dismissText = "Назад"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.operationError?.let { ErrorMessageCard(error = it) }
            ErpTextField(
                value = form.reason,
                onValueChange = { viewModel.updateCancelForm(form.copy(reason = it)) },
                label = "Причина отмены",
                singleLine = false,
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun CancelDeliveryDialog(
    dialog: PurchaseOrderDialog.CancelDelivery,
    state: PurchaseOrderUiState,
    viewModel: PurchaseOrderViewModel
) {
    val form = state.cancelForm ?: CancelOrderForm()
    ErpDialog(
        title = "Отмена поставки ${dialog.number}",
        subtitle = "Остаток по позициям вернётся, статус заказа пересчитается",
        glyph = ErpIconGlyph.Alert,
        gradient = Gradients.Sunset,
        onDismiss = viewModel::dismissDialog,
        confirmText = "Отменить поставку",
        onConfirm = viewModel::cancelDelivery,
        confirmEnabled = !state.isActionRunning && form.reason.isNotBlank(),
        confirmGradient = org.example.project.ui.theme.Gradients.Danger,
        dismissText = "Назад"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.operationError?.let { ErrorMessageCard(error = it) }
            ErpTextField(
                value = form.reason,
                onValueChange = { viewModel.updateCancelForm(form.copy(reason = it)) },
                label = "Причина отмены",
                singleLine = false,
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun CloseOrderDialog(
    dialog: PurchaseOrderDialog.CloseOrder,
    state: PurchaseOrderUiState,
    viewModel: PurchaseOrderViewModel
) {
    ErpDialog(
        title = "Закрыть заказ ${dialog.number}?",
        subtitle = "Закрытие завершает работу с заказом",
        glyph = ErpIconGlyph.CheckCircle,
        gradient = Gradients.Ocean,
        onDismiss = viewModel::dismissDialog,
        confirmText = "Закрыть заказ",
        onConfirm = viewModel::confirmClose,
        confirmEnabled = !state.isActionRunning,
        dismissText = "Назад"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.operationError?.let { ErrorMessageCard(error = it) }
            Text(
                text = "После закрытия заказ нельзя будет изменить, отменить или принять по нему новые " +
                    "поставки; в истории останется запись о закрытии.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Если часть позиций так и не поставлена, проверьте остаток по позициям до закрытия: " +
                    "недопоставленное количество по закрытому заказу не ожидается.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
