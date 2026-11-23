package org.example.project.ui.theme

import kotlin.math.abs

/**
 * Единые форматтеры и переводы отображаемых значений ERP (раздел 0 обзора).
 */
object ErpFormat {

    /**
     * Форматирует ISO-дату (`2026-11-19` или `2026-11-19T12:00:00Z`) в `19.11.2026`.
     * Если значение пустое — возвращает `"—"`.
     */
    fun dateRu(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "—") return "—"
        val datePart = trimmed.substringBefore('T').substringBefore(' ')
        val parts = datePart.split('-')
        if (parts.size == 3 && parts[0].length == 4 && parts[1].length == 2 && parts[2].length == 2) {
            return "${parts[2]}.${parts[1]}.${parts[0]}"
        }
        return trimmed
    }

    /**
     * Форматирует дату и время (`2026-11-19T14:30:00Z` -> `19.11.2026 14:30`),
     * а полночь `00:00` / `23:59` скрывает как чистую дату.
     */
    fun dateTimeRu(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "—") return "—"
        val dateStr = dateRu(trimmed)
        if (!trimmed.contains('T') && !trimmed.contains(' ')) return dateStr
        val timeRaw = trimmed.substringAfter('T', trimmed.substringAfter(' ', ""))
            .removeSuffix("Z")
            .substringBefore('+')
            .substringBefore('.')
        if (timeRaw.length >= 5) {
            val hhmm = timeRaw.take(5)
            if (hhmm == "00:00" || hhmm == "23:59" || hhmm == "01:00") return dateStr
            return "$dateStr $hhmm"
        }
        return dateStr
    }

    /** Убирает лишние нули после запятой (`1200.0000` -> `1 200`, `12.5000` -> `12,5`). */
    fun qty(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "—") return "—"
        val normalized = trimmed.replace(',', '.')
        val num = normalized.toDoubleOrNull() ?: return trimmed
        val clean = if (normalized.contains('.')) normalized.trimEnd('0').trimEnd('.') else normalized
        val intPart = clean.substringBefore('.')
        val fracPart = clean.substringAfter('.', "")
        val groupedInt = groupThousands(intPart)
        return if (fracPart.isEmpty()) groupedInt else "$groupedInt,$fracPart"
    }

    fun qty(value: Int): String = groupThousands(value.toString())

    fun qty(value: Double): String {
        val rounded = kotlin.math.round(value * 1000.0) / 1000.0
        val asLong = rounded.toLong()
        return if (rounded == asLong.toDouble()) {
            groupThousands(asLong.toString())
        } else {
            val s = rounded.toString()
            val intPart = s.substringBefore('.')
            val fracPart = s.substringAfter('.', "").trimEnd('0')
            if (fracPart.isEmpty()) groupThousands(intPart) else "${groupThousands(intPart)},$fracPart"
        }
    }

    /** Форматирует сумму с символом валюты (`RUB` -> `₽`). */
    fun money(raw: String?, currency: String = "RUB"): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "—") return "—"
        val normalized = trimmed.replace(" ", "").replace("\u00A0", "").replace(',', '.')
        val intPart = normalized.substringBefore('.')
        val fracPart = normalized.substringAfter('.', "")
        val groupedInt = groupThousands(intPart)
        val amountText = if (fracPart.isEmpty() || fracPart == "00") {
            groupedInt
        } else {
            "$groupedInt,${fracPart.padEnd(2, '0').take(2)}"
        }
        return "$amountText ${currencySymbol(currency)}"
    }

    fun currencySymbol(currency: String?): String = when (currency?.uppercase()) {
        "RUB", "RUR", null, "" -> "₽"
        "USD" -> "$"
        "EUR" -> "€"
        "CNY" -> "¥"
        else -> currency
    }

    /** Форматирует процент (`100.00` -> `100%`). */
    fun percent(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "—") return "—"
        val clean = if (trimmed.contains('.')) trimmed.trimEnd('0').trimEnd('.') else trimmed
        return "$clean%"
    }

    fun priorityRu(raw: String?): String = when (raw?.trim()?.uppercase()) {
        "LOW" -> "Низкий"
        "NORMAL" -> "Обычный"
        "HIGH" -> "Высокий"
        "URGENT" -> "Срочный"
        "CRITICAL" -> "Критический"
        null, "" -> "—"
        else -> raw
    }

    fun counterpartyTypeRu(raw: String?): String = when (raw?.trim()?.uppercase()) {
        "SUPPLIER" -> "Поставщик"
        "CUSTOMER" -> "Заказчик"
        "CARRIER" -> "Перевозчик"
        "CONTRACTOR" -> "Подрядчик"
        "PARTNER" -> "Партнёр"
        null, "" -> "—"
        else -> raw
    }

    /**
     * Разница в днях между `targetIso` и `todayIso` (`target - today`).
     * Отрицательное значение означает просрочку на `-diff` дней.
     */
    fun daysDiff(targetIso: String?, todayIso: String): Int? {
        val t = parseIsoDays(targetIso) ?: return null
        val cur = parseIsoDays(todayIso) ?: return null
        return t - cur
    }

    fun deadlineLabel(targetIso: String?, todayIso: String): String {
        val base = dateRu(targetIso)
        if (base == "—") return base
        val diff = daysDiff(targetIso, todayIso) ?: return base
        return when {
            diff < 0 -> "$base (просрочено ${abs(diff)} дн.)"
            diff == 0 -> "$base (сегодня)"
            diff <= 3 -> "$base (через $diff дн.)"
            else -> base
        }
    }

    private fun parseIsoDays(raw: String?): Int? {
        val s = raw?.trim()?.substringBefore('T')?.substringBefore(' ') ?: return null
        val parts = s.split('-')
        if (parts.size != 3) return null
        val y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val d = parts[2].toIntOrNull() ?: return null
        // Алгоритм Ховард Хиннант (days since 1970-01-01)
        val yy = if (m <= 2) y - 1 else y
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    private fun groupThousands(digits: String): String {
        val negative = digits.startsWith("-")
        val body = if (negative) digits.drop(1) else digits
        if (body.length <= 3 || !body.all { it.isDigit() }) return digits
        val sb = StringBuilder()
        for (i in body.indices) {
            if (i > 0 && (body.length - i) % 3 == 0) sb.append(' ')
            sb.append(body[i])
        }
        return if (negative) "-$sb" else sb.toString()
    }
}
