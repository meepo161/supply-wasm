package org.example.project.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import org.example.project.ui.theme.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** В каком виде дата уходит на сервер. */
enum class DateValueFormat {
    /** `ГГГГ-ММ-ДД` — поля типа LocalDate. */
    DATE,

    /** `ГГГГ-ММ-ДДT00:00:00Z` — поля типа OffsetDateTime «на дату». */
    START_OF_DAY_UTC
}

/**
 * Поле даты (аудит UX-02): показывает `дд.мм.гггг`, принимает ручной ввод
 * (`дд.мм.гггг` или `ГГГГ-ММ-ДД`) и выбор в календаре, наружу отдаёт значение
 * в формате сервера. Пустое поле — пустая строка.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ErpDateField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    format: DateValueFormat = DateValueFormat.DATE,
    enabled: Boolean = true
) {
    var text by remember(value) { mutableStateOf(ErpDates.display(value)) }
    var pickerOpen by remember { mutableStateOf(false) }
    val invalid = text.isNotBlank() && ErpDates.parse(text) == null

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ErpTextField(
            value = text,
            onValueChange = { input ->
                text = input
                when {
                    input.isBlank() -> onValueChange("")
                    else -> ErpDates.parse(input)?.let { onValueChange(ErpDates.format(it, format)) }
                }
            },
            label = label,
            placeholder = "дд.мм.гггг",
            supportingText = if (invalid) "Дата в формате дд.мм.гггг" else null,
            enabled = enabled,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { pickerOpen = true }, enabled = enabled) { Text("Календарь") }
    }

    if (pickerOpen) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = ErpDates.parse(text)?.let { ErpDates.toEpochMillis(it) }
        )
        DatePickerDialog(
            onDismissRequest = { pickerOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val date = ErpDates.fromEpochMillis(millis)
                        text = ErpDates.display(date)
                        onValueChange(ErpDates.format(date, format))
                    }
                    pickerOpen = false
                }) { Text("Выбрать") }
            },
            dismissButton = { TextButton(onClick = { pickerOpen = false }) { Text("Отмена") } }
        ) {
            DatePicker(state = state)
        }
    }
}

/** Календарная дата без часового пояса. */
data class ErpDate(val year: Int, val month: Int, val day: Int)

/** Преобразования дат без внешних библиотек (алгоритм «days from civil»). */
object ErpDates {
    private const val MILLIS_PER_DAY = 86_400_000L

    /** Разбирает `дд.мм.гггг`, `ГГГГ-ММ-ДД` и ISO-дату-время (берётся дата). */
    fun parse(raw: String): ErpDate? {
        val s = raw.trim()
        val (y, m, d) = when {
            Regex("""\d{1,2}\.\d{1,2}\.\d{4}""").matches(s) -> s.split('.').let { Triple(it[2].toInt(), it[1].toInt(), it[0].toInt()) }
            Regex("""\d{4}-\d{2}-\d{2}.*""").matches(s) -> s.take(10).split('-').let { Triple(it[0].toInt(), it[1].toInt(), it[2].toInt()) }
            else -> return null
        }
        if (m !in 1..12 || d < 1 || d > daysInMonth(y, m)) return null
        return ErpDate(y, m, d)
    }

    fun display(value: String): String = parse(value)?.let { display(it) } ?: value

    fun display(date: ErpDate): String = "${date.day.pad()}.${date.month.pad()}.${date.year}"

    fun format(date: ErpDate, format: DateValueFormat): String {
        val iso = "${date.year}-${date.month.pad()}-${date.day.pad()}"
        return when (format) {
            DateValueFormat.DATE -> iso
            DateValueFormat.START_OF_DAY_UTC -> "${iso}T00:00:00Z"
        }
    }

    fun toEpochMillis(date: ErpDate): Long = daysFromCivil(date.year, date.month, date.day) * MILLIS_PER_DAY

    fun fromEpochMillis(millis: Long): ErpDate {
        val days = millis / MILLIS_PER_DAY - if (millis % MILLIS_PER_DAY < 0) 1 else 0
        return civilFromDays(days)
    }

    private fun daysInMonth(y: Int, m: Int): Int = when (m) {
        2 -> if ((y % 4 == 0 && y % 100 != 0) || y % 400 == 0) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    private fun civilFromDays(daysSinceEpoch: Long): ErpDate {
        val z = daysSinceEpoch + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
        return ErpDate(year, month, day)
    }

    private fun Int.pad(): String = toString().padStart(2, '0')
}
