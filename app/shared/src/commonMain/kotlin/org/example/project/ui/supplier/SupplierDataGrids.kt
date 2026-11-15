package org.example.project.ui.supplier

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.unchil.un7datagrid.Un7KCMPDataGrid
import com.unchil.un7datagrid.Un7KCMPDataGridConfig
import org.example.project.dto.SupplierCategoryDto
import org.example.project.dto.SupplierContactDto
import org.example.project.dto.SupplierDocumentDto
import org.example.project.dto.SupplierDto
import org.example.project.dto.SupplierOfferDto

private val gridConfig = Un7KCMPDataGridConfig(
    isUsableHaptic = false,
    pageSizeList = listOf("10", "20", "50", "100"),
    defaultPageSizeListIndex = 0
)

@Composable
fun SuppliersGrid(
    suppliers: List<SupplierDto>,
    onClick: (SupplierDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "ID" to suppliers.map { it.id.toString() },
        "Номер" to suppliers.map { it.number },
        "Наименование" to suppliers.map { it.name },
        "ИНН" to suppliers.map { it.inn ?: "—" },
        "Проверка" to suppliers.map { it.verificationStatus.localizedName() },
        "Город/адрес" to suppliers.map { it.legalAddress ?: "—" }
    )
    Un7KCMPDataGrid(
        data = data,
        modifier = modifier,
        config = gridConfig,
        onClick = { selected ->
            val id = selected.firstOrNull()?.second?.firstOrNull() as? String
            suppliers.firstOrNull { it.id.toString() == id }?.let(onClick)
        }
    )
}

@Composable
fun SupplierContactsGrid(
    contacts: List<SupplierContactDto>,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "ФИО" to contacts.map { it.fullName },
        "Должность" to contacts.map { it.position ?: "—" },
        "Телефон" to contacts.map { it.phone ?: "—" },
        "Почта" to contacts.map { it.email ?: "—" },
        "Основной" to contacts.map { if (it.isPrimary) "Да" else "Нет" }
    )
    Un7KCMPDataGrid(data = data, modifier = modifier, config = gridConfig, onClick = { })
}

@Composable
fun SupplierDocumentsGrid(
    documents: List<SupplierDocumentDto>,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "Вид" to documents.map { it.kind.name },
        "Название" to documents.map { it.title },
        "Номер" to documents.map { it.documentNo ?: "—" },
        "Действует с" to documents.map { it.validFrom.asDate() },
        "Действует до" to documents.map { it.validTo.asDate() }
    )
    Un7KCMPDataGrid(data = data, modifier = modifier, config = gridConfig, onClick = { })
}

@Composable
fun SupplierOffersGrid(
    offers: List<SupplierOfferDto>,
    onClick: (SupplierOfferDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "ID" to offers.map { it.id.toString() },
        "Номер" to offers.map { it.number },
        "Поставщик" to offers.map { it.supplierName ?: it.supplierId.toString() },
        "Статус" to offers.map { it.status.localizedName() },
        "Соответствие" to offers.map { it.compliance.localizedName() },
        "Срок" to offers.map { (it.deliveryDays?.toString() ?: "—") + " дн." },
        "Сумма" to offers.map { it.totals?.grossTotal.asMoney(it.currency) },
        "Действует до" to offers.map { it.validUntil.asDate() }
    )
    Un7KCMPDataGrid(
        data = data,
        modifier = modifier,
        config = gridConfig,
        onClick = { selected ->
            val id = selected.firstOrNull()?.second?.firstOrNull() as? String
            offers.firstOrNull { it.id.toString() == id }?.let(onClick)
        }
    )
}

/** Категории — компактный список, а не таблица: их немного и они короткие. */
@Composable
fun SupplierCategoriesSummary(
    categories: List<SupplierCategoryDto>,
    modifier: Modifier = Modifier
) {
    if (categories.isEmpty()) return
    val data = mapOf(
        "Код" to categories.map { it.code },
        "Категория" to categories.map { it.name }
    )
    Un7KCMPDataGrid(data = data, modifier = modifier, config = gridConfig, onClick = { })
}
