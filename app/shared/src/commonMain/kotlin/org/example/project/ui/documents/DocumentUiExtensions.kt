package org.example.project.ui.documents

import org.example.project.dto.DocumentConfidentiality
import org.example.project.dto.DocumentLinkType
import org.example.project.dto.DocumentStatus
import org.example.project.dto.DocumentType
import org.example.project.dto.DocumentVersionStatus

fun DocumentType.localizedName(): String = when (this) {
    DocumentType.TECHNICAL_SPECIFICATION -> "Техническое задание"
    DocumentType.CONTRACT -> "Договор"
    DocumentType.INVOICE -> "Счёт"
    DocumentType.COMMERCIAL_OFFER -> "Коммерческое предложение"
    DocumentType.ORDER -> "Заказ"
    DocumentType.WAYBILL -> "Накладная"
    DocumentType.ACT -> "Акт"
    DocumentType.PRODUCT_PASSPORT -> "Паспорт изделия"
    DocumentType.MANUAL -> "Руководство"
    DocumentType.DRAWING -> "Чертёж"
    DocumentType.SPECIFICATION -> "Спецификация"
    DocumentType.TEST_PROTOCOL -> "Протокол испытаний"
    DocumentType.MEMO -> "Служебная записка"
    DocumentType.ORDER_DOC -> "Приказ"
    DocumentType.REPORT -> "Отчёт"
    DocumentType.CERTIFICATE -> "Сертификат"
    DocumentType.PHOTO -> "Фотография"
    DocumentType.OTHER -> "Прочее"
}

fun DocumentStatus.localizedName(): String = when (this) {
    DocumentStatus.DRAFT -> "Черновик"
    DocumentStatus.ON_APPROVAL -> "На согласовании"
    DocumentStatus.APPROVED -> "Согласован"
    DocumentStatus.ACTIVE -> "Действующий"
    DocumentStatus.REPLACED -> "Заменён"
    DocumentStatus.ARCHIVED -> "В архиве"
    DocumentStatus.REJECTED -> "Отклонён"
}

fun DocumentConfidentiality.localizedName(): String = when (this) {
    DocumentConfidentiality.PUBLIC -> "Публичный"
    DocumentConfidentiality.INTERNAL -> "Внутренний"
    DocumentConfidentiality.CONFIDENTIAL -> "Конфиденциальный"
    DocumentConfidentiality.SECRET -> "Секретный"
}

fun DocumentVersionStatus.localizedName(): String = when (this) {
    DocumentVersionStatus.DRAFT -> "Черновик"
    DocumentVersionStatus.APPROVED -> "Утверждена"
    DocumentVersionStatus.ACTIVE -> "Действующая"
    DocumentVersionStatus.REPLACED -> "Заменена"
    DocumentVersionStatus.CANCELLED -> "Отменена"
}

fun DocumentLinkType.localizedName(): String = when (this) {
    DocumentLinkType.PROJECT -> "Проект"
    DocumentLinkType.TASK -> "Задача"
    DocumentLinkType.PRODUCT_MODEL -> "Модель изделия"
    DocumentLinkType.BOM_REVISION -> "Ревизия BOM"
    DocumentLinkType.DEMAND -> "Потребность"
}
