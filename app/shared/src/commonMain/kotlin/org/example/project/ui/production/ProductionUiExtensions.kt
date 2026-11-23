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
    if (this.isNullOrBlank()) return "не задано"
    val date = substringBefore('T')
    val time = substringAfter('T', "").take(5)
    val parts = date.split('-')
    if (parts.size != 3) return this
    return "${parts[2]}.${parts[1]}.${parts[0]}" + if (time.isNotEmpty()) " $time" else ""
}

fun String.priorityLabel(): String = when (this) {
    "LOW" -> "Низкий"
    "NORMAL" -> "Обычный"
    "HIGH" -> "Высокий"
    "CRITICAL" -> "Критический"
    else -> this
}

fun org.example.project.dto.NonconformityStatus.localizedName(): String = when (this) {
    org.example.project.dto.NonconformityStatus.REGISTERED -> "Зарегистрировано"
    org.example.project.dto.NonconformityStatus.EVALUATION -> "Оценка"
    org.example.project.dto.NonconformityStatus.ASSIGNED -> "Назначен ответственный"
    org.example.project.dto.NonconformityStatus.ROOT_CAUSE_ANALYSIS -> "Анализ причины"
    org.example.project.dto.NonconformityStatus.CORRECTIVE_ACTION -> "Корректирующие действия"
    org.example.project.dto.NonconformityStatus.EFFECTIVENESS_CHECK -> "Проверка эффективности"
    org.example.project.dto.NonconformityStatus.CLOSED -> "Закрыто"
}

/** Следующий шаг жизненного цикла несоответствия по ТЗ (раздел 16); null для закрытого. */
fun org.example.project.dto.NonconformityStatus.nextStep(): org.example.project.dto.NonconformityStatus? = when (this) {
    org.example.project.dto.NonconformityStatus.REGISTERED -> org.example.project.dto.NonconformityStatus.EVALUATION
    org.example.project.dto.NonconformityStatus.EVALUATION -> org.example.project.dto.NonconformityStatus.ASSIGNED
    org.example.project.dto.NonconformityStatus.ASSIGNED -> org.example.project.dto.NonconformityStatus.ROOT_CAUSE_ANALYSIS
    org.example.project.dto.NonconformityStatus.ROOT_CAUSE_ANALYSIS -> org.example.project.dto.NonconformityStatus.CORRECTIVE_ACTION
    org.example.project.dto.NonconformityStatus.CORRECTIVE_ACTION -> org.example.project.dto.NonconformityStatus.EFFECTIVENESS_CHECK
    org.example.project.dto.NonconformityStatus.EFFECTIVENESS_CHECK -> org.example.project.dto.NonconformityStatus.CLOSED
    org.example.project.dto.NonconformityStatus.CLOSED -> null
}

fun org.example.project.dto.NonconformitySeverity.localizedName(): String = when (this) {
    org.example.project.dto.NonconformitySeverity.MINOR -> "Незначительное"
    org.example.project.dto.NonconformitySeverity.MAJOR -> "Значительное"
    org.example.project.dto.NonconformitySeverity.CRITICAL -> "Критическое"
}

/** Объекты несоответствия из ТЗ (раздел 16): код хранится на сервере, пользователю показывается название. */
val nonconformityCategories: List<Pair<String, String>> = listOf(
    "PRODUCTION" to "Производство",
    "MATERIAL" to "Материал",
    "SUPPLY" to "Поставка",
    "PRODUCT" to "Изделие",
    "DOCUMENT" to "Документ",
    "PROCESS" to "Процесс"
)

fun String.nonconformityCategoryLabel(): String =
    nonconformityCategories.firstOrNull { it.first == this }?.second ?: this

fun org.example.project.dto.EngineeringChangeStatus.localizedName(): String = when (this) {
    org.example.project.dto.EngineeringChangeStatus.OPEN -> "Открыто"
    org.example.project.dto.EngineeringChangeStatus.ASSESSED -> "Оценено"
    org.example.project.dto.EngineeringChangeStatus.APPROVED -> "Утверждено"
    org.example.project.dto.EngineeringChangeStatus.APPLIED -> "Применено"
    org.example.project.dto.EngineeringChangeStatus.REJECTED -> "Отклонено"
}

fun org.example.project.dto.EngineeringChangeDecision.localizedName(): String = when (this) {
    org.example.project.dto.EngineeringChangeDecision.PENDING -> "решение не принято"
    org.example.project.dto.EngineeringChangeDecision.RETURN_EXCESS -> "вернуть излишки"
    org.example.project.dto.EngineeringChangeDecision.RESERVE_ADDITIONAL -> "дозарезервировать"
    org.example.project.dto.EngineeringChangeDecision.REPLACE_ON_FLOOR -> "заменить в цеху"
    org.example.project.dto.EngineeringChangeDecision.NO_CHANGE -> "без изменений"
}

/** Коды статусов изделия, испытаний и замечаний, которые сервер отдаёт строкой. */
fun String.productionCodeLabel(): String = when (this) {
    "MANUFACTURED" -> "Изготовлено"
    "ACCEPTED" -> "Принято ОТК"
    "SHIPPED" -> "Отгружено"
    "GOOD" -> "Годно"
    "NOT_GOOD" -> "Не годно"
    "PENDING" -> "Решение не принято"
    "OPEN" -> "Открыто"
    "RESOLVED" -> "Снято"
    "MINOR" -> "Незначительное"
    "MAJOR" -> "Значительное"
    "CRITICAL" -> "Критическое"
    else -> this
}
