package org.example.project.ui.production

import androidx.compose.ui.graphics.Color
import org.example.project.dto.ProductionOrderStatus
import org.example.project.dto.ProductionStageStatus
import org.example.project.dto.ProductionStageType
import org.example.project.ui.theme.Brand

fun ProductionOrderStatus.localizedName(): String = when (this) {
    ProductionOrderStatus.DRAFT -> "Черновик"
    ProductionOrderStatus.PLANNED -> "Запланирован"
    ProductionOrderStatus.IN_PROGRESS -> "В производстве"
    ProductionOrderStatus.ON_HOLD -> "Приостановлен"
    ProductionOrderStatus.COMPLETED -> "Выпущен"
    ProductionOrderStatus.CLOSED -> "Закрыт"
    ProductionOrderStatus.CANCELLED -> "Отменён"
}

fun ProductionOrderStatus.accentColor(): Color = when (this) {
    ProductionOrderStatus.DRAFT -> Brand.Slate
    ProductionOrderStatus.PLANNED -> Brand.Blue
    ProductionOrderStatus.IN_PROGRESS -> Brand.Teal
    ProductionOrderStatus.ON_HOLD -> Brand.Amber
    ProductionOrderStatus.COMPLETED -> Brand.Mint
    ProductionOrderStatus.CLOSED -> Brand.Indigo
    ProductionOrderStatus.CANCELLED -> Brand.Rose
}

fun ProductionStageStatus.localizedName(): String = when (this) {
    ProductionStageStatus.PENDING -> "Ожидает"
    ProductionStageStatus.IN_PROGRESS -> "В работе"
    ProductionStageStatus.COMPLETED -> "Выполнен"
    ProductionStageStatus.SKIPPED -> "Пропущен"
}

fun ProductionStageStatus.accentColor(): Color = when (this) {
    ProductionStageStatus.PENDING -> Brand.Slate
    ProductionStageStatus.IN_PROGRESS -> Brand.Blue
    ProductionStageStatus.COMPLETED -> Brand.Mint
    ProductionStageStatus.SKIPPED -> Brand.Amber
}

fun ProductionStageType.localizedName(): String = when (this) {
    ProductionStageType.PREPARATION -> "Подготовка"
    ProductionStageType.KITTING -> "Комплектация"
    ProductionStageType.ASSEMBLY -> "Сборка"
    ProductionStageType.MOUNTING -> "Монтаж"
    ProductionStageType.SETUP -> "Настройка"
    ProductionStageType.TESTING -> "Испытания"
    ProductionStageType.CONTROL -> "Контроль"
    ProductionStageType.REMARK_FIX -> "Устранение замечаний"
    ProductionStageType.ACCEPTANCE -> "Приёмка"
    ProductionStageType.COMPLETION -> "Завершение"
}

/** «2026-11-20T09:00+03:00» или «2026-11-20T09:00:00Z». */
fun String.isIsoDateTime(): Boolean {
    val regex = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?(\.\d+)?(Z|[+-]\d{2}:\d{2})$""")
    return regex.matches(trim())
}

/** Короткая дата-время для карточек: «20.11.2026 09:00». */
fun String?.toDisplayDateTime(): String {
    if (this.isNullOrBlank()) return "—"
    val date = substringBefore('T')
    val time = substringAfter('T', "").take(5)
    val parts = date.split('-')
    if (parts.size != 3) return this
    return "${parts[2]}.${parts[1]}.${parts[0]}" + if (time.isNotEmpty()) " $time" else ""
}
