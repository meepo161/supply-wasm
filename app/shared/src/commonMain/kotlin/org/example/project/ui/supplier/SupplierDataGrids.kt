package org.example.project.ui.supplier

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.ui.tables.PersistentDataGrid
import org.example.project.ui.theme.ErpFormat
import com.unchil.un7datagrid.Un7KCMPDataGridConfig
import org.example.project.dto.SupplierCategoryDto
import org.example.project.dto.SupplierContactDto
import org.example.project.dto.SupplierDocumentDto
import org.example.project.dto.SupplierDto
import org.example.project.dto.SupplierOfferDto

private val gridConfig = Un7KCMPDataGridConfig(
    isUsableHaptic = false,
    pageSizeList = listOf("20", "50", "100"),
    defaultPageSizeListIndex = 0
)

@Composable
fun SuppliersGrid(
    suppliers: List<SupplierDto>,
    onClick: (SupplierDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "Номер" to suppliers.map { it.number },
        "Наименование" to suppliers.map { it.name },
        "ИНН" to suppliers.map { it.inn ?: "—" },
        "Проверка" to suppliers.map { it.verificationStatus.localizedName() },
        "Город/адрес" to suppliers.map { it.legalAddress ?: "—" }
    )
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "suppliers-registry",
        showSearch = false,
        config = gridConfig,
        onClick = { selected ->
            selected.firstOrNull()?.first?.let { idx ->
                suppliers.getOrNull(idx)?.let(onClick)
            }
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
    PersistentDataGrid(data = data, modifier = modifier, tableKey = "supplier-contacts", compact = true, config = gridConfig, onClick = { })
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
        "Действует с" to documents.map { ErpFormat.dateRu(it.validFrom) },
        "Действует до" to documents.map { ErpFormat.dateRu(it.validTo) }
    )
    PersistentDataGrid(data = data, modifier = modifier, tableKey = "supplier-documents", compact = true, config = gridConfig, onClick = { })
}

@Composable
fun SupplierOffersGrid(
    offers: List<SupplierOfferDto>,
    onClick: (SupplierOfferDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "Номер" to offers.map { it.number },
        "Поставщик" to offers.map { it.supplierName ?: "Поставщик #${it.supplierId}" },
        "Статус" to offers.map { it.status.localizedName() },
        "Соответствие" to offers.map { it.compliance.localizedName() },
        "Срок" to offers.map { (it.deliveryDays?.toString() ?: "—") + " дн." },
        "Сумма" to offers.map { ErpFormat.money(it.totals?.grossTotal, it.currency) },
        "Действует до" to offers.map { ErpFormat.dateRu(it.validUntil) }
    )
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "supplier-offers",
        config = gridConfig,
        onClick = { selected ->
            selected.firstOrNull()?.first?.let { idx ->
                offers.getOrNull(idx)?.let(onClick)
            }
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
    PersistentDataGrid(data = data, modifier = modifier, tableKey = "supplier-categories", compact = true, config = gridConfig, onClick = { })
}
