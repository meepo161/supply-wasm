package org.example.project.ui.wms

import org.example.project.dto.BarcodePackageType
import org.example.project.dto.NomenclatureType
import org.example.project.dto.ReceiptSourceType
import org.example.project.dto.ReceiptStatus
import org.example.project.dto.ReservationReasonType
import org.example.project.dto.ReservationStatus
import org.example.project.dto.StorageLocationKind
import org.example.project.dto.StorageLocationPurpose

fun StorageLocationKind.localizedName(): String = when (this) {
    StorageLocationKind.ZONE -> "Зона"
    StorageLocationKind.CELL -> "Ячейка"
}

fun StorageLocationPurpose.localizedName(): String = when (this) {
    StorageLocationPurpose.STORAGE -> "Хранение"
    StorageLocationPurpose.RECEIVING -> "Приёмка"
    StorageLocationPurpose.SHIPPING -> "Отгрузка"
    StorageLocationPurpose.QUARANTINE -> "Карантин"
    StorageLocationPurpose.DEFECT -> "Брак"
    StorageLocationPurpose.TEMPORARY -> "Временное"
}

fun NomenclatureType.localizedName(): String = when (this) {
    NomenclatureType.RAW_MATERIAL -> "Сырьё"
    NomenclatureType.MATERIAL -> "Материал"
    NomenclatureType.PURCHASED_COMPONENT -> "Покупной компонент"
    NomenclatureType.STANDARD_PRODUCT -> "Стандартное изделие"
    NomenclatureType.OWN_UNIT -> "Собственный узел"
    NomenclatureType.ASSEMBLY -> "Сборочная единица"
    NomenclatureType.FINISHED_PRODUCT -> "Готовое изделие"
    NomenclatureType.TOOL -> "Инструмент"
    NomenclatureType.EQUIPMENT -> "Оборудование"
    NomenclatureType.CONSUMABLE -> "Расходник"
    NomenclatureType.SERVICE -> "Услуга"
    NomenclatureType.KIT -> "Комплект"
}

fun BarcodePackageType.localizedName(): String = when (this) {
    BarcodePackageType.UNIT -> "Единица"
    BarcodePackageType.PACKAGE -> "Упаковка"
}

fun ReceiptSourceType.localizedName(): String = when (this) {
    ReceiptSourceType.PURCHASE_ORDER -> "Заказ поставщику"
    ReceiptSourceType.RETURN -> "Возврат"
    ReceiptSourceType.TRANSFER -> "Перемещение"
    ReceiptSourceType.INITIAL_STOCK -> "Начальный остаток"
    ReceiptSourceType.OTHER -> "Прочее"
}

fun ReceiptStatus.localizedName(): String = when (this) {
    ReceiptStatus.DRAFT -> "Черновик"
    ReceiptStatus.IN_PROGRESS -> "В работе"
    ReceiptStatus.POSTED -> "Проведена"
    ReceiptStatus.CANCELLED -> "Отменена"
}

fun ReservationReasonType.localizedName(): String = when (this) {
    ReservationReasonType.PROJECT -> "Проект"
    ReservationReasonType.PRODUCTION_ORDER -> "Производственный заказ"
    ReservationReasonType.STOCKPILE -> "Задел"
    ReservationReasonType.REPAIR -> "Ремонт"
    ReservationReasonType.DEPARTMENT_TASK -> "Задача подразделения"
    ReservationReasonType.OTHER -> "Прочее"
}

fun ReservationStatus.localizedName(): String = when (this) {
    ReservationStatus.ACTIVE -> "Активен"
    ReservationStatus.FULFILLED -> "Выполнен"
    ReservationStatus.RELEASED -> "Снят"
    ReservationStatus.CANCELLED -> "Отменён"
}

fun String?.toShortDate(): String {
    if (this.isNullOrBlank()) return "—"
    return substringBefore("T")
}
