package org.example.project.ui.wms

import org.example.project.dto.BarcodePackageType
import org.example.project.dto.NomenclatureType
import org.example.project.dto.ReceiptSourceType
import org.example.project.dto.ReceiptStatus
import org.example.project.dto.ReservationReasonType
import org.example.project.dto.ReservationStatus
import org.example.project.dto.StorageLocationKind
import org.example.project.dto.StorageLocationPurpose
import org.example.project.dto.IssueStatus
import org.example.project.dto.ReturnInspection
import org.example.project.dto.ReturnInspectionDecision
import org.example.project.dto.ReturnStatus
import org.example.project.dto.TransferKind
import org.example.project.dto.TransferStatus

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

fun TransferKind.localizedName(): String = when (this) {
    TransferKind.INTERNAL -> "Внутри склада"
    TransferKind.INTER_WAREHOUSE -> "Между складами"
}

fun TransferStatus.localizedName(): String = when (this) {
    TransferStatus.CREATED -> "Создано"
    TransferStatus.SHIPPED -> "Отгружено"
    TransferStatus.IN_TRANSIT -> "В пути"
    TransferStatus.RECEIVED -> "Принято"
    TransferStatus.DISCREPANCY -> "Расхождение"
    TransferStatus.CANCELLED -> "Отменено"
}

fun IssueStatus.localizedName(): String = when (this) {
    IssueStatus.DRAFT -> "Черновик"
    IssueStatus.ISSUED -> "Выдано"
    IssueStatus.RECEIVED -> "Получено"
    IssueStatus.SHORTAGE -> "Недостача"
    IssueStatus.REFUSED -> "Отказ"
    IssueStatus.CANCELLED -> "Отменено"
}

fun ReturnStatus.localizedName(): String = when (this) {
    ReturnStatus.DRAFT -> "Черновик"
    ReturnStatus.RECEIVED -> "Принято на карантин"
    ReturnStatus.INSPECTED -> "Проверено"
}

fun ReturnInspection.localizedName(): String = when (this) {
    ReturnInspection.PENDING -> "Не проверено"
    ReturnInspection.GOOD -> "Годно"
    ReturnInspection.DEFECT -> "Брак"
}

fun ReturnInspectionDecision.localizedName(): String = when (this) {
    ReturnInspectionDecision.GOOD -> "Годно — на хранение"
    ReturnInspectionDecision.DEFECT -> "Брак — в брак"
}

fun String?.toShortDate(): String {
    if (this.isNullOrBlank()) return "—"
    return substringBefore("T")
}
