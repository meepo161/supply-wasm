package org.example.project.ui.home

internal fun homeTaskSummary(
    companyScope: Boolean,
    companyOverdueCount: Int,
    openCount: Int,
    overdueCount: Int,
    reviewCount: Int
): String = when {
    overdueCount > 0 -> "просрочено $overdueCount, открыто $openCount, на приёмке $reviewCount"
    openCount > 0 -> "открыто $openCount, на приёмке $reviewCount, просрочек нет"
    companyOverdueCount > 0 && !companyScope ->
        "у вас просрочек нет, по компании просрочено $companyOverdueCount"
    companyScope -> "по компании просроченных задач нет"
    else -> "просроченных задач нет"
}

internal fun actionQueueContentDescription(item: HomeActionQueueItem): String =
    "${item.title}. ${item.subtitle}. Количество: ${item.count}."
