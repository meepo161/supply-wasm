package org.example.project.ui.bom

import org.example.project.dto.BomRevisionStatus
import org.example.project.dto.ProductModelStatus

fun ProductModelStatus.localizedName(): String = when (this) {
    ProductModelStatus.DEVELOPMENT -> "Разработка"
    ProductModelStatus.PILOT_PRODUCTION -> "Опытное производство"
    ProductModelStatus.SERIAL_PRODUCTION -> "Серийное производство"
    ProductModelStatus.DISCONTINUED -> "Снято с производства"
}

fun BomRevisionStatus.localizedName(): String = when (this) {
    BomRevisionStatus.DRAFT -> "Черновик"
    BomRevisionStatus.APPROVED -> "Утверждена"
    BomRevisionStatus.ACTIVE -> "Активна"
    BomRevisionStatus.REPLACED -> "Заменена"
    BomRevisionStatus.CANCELLED -> "Отменена"
}
