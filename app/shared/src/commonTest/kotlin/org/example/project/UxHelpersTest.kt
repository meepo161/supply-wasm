package org.example.project

import org.example.project.dto.ApiError
import org.example.project.ui.technicalDetail
import org.example.project.ui.theme.DateValueFormat
import org.example.project.ui.theme.ErpDate
import org.example.project.ui.theme.ErpDates
import org.example.project.ui.userText
import org.example.project.ui.userTitle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Вспомогательные функции UX-аудита: даты (UX-02) и тексты ошибок (UX-01). */
class UxHelpersTest {

    @Test
    fun datesParseBothFormatsAndRejectImpossibleDays() {
        assertEquals(ErpDate(2026, 11, 22), ErpDates.parse("22.11.2026"))
        assertEquals(ErpDate(2026, 11, 22), ErpDates.parse("2026-11-22"))
        assertEquals(ErpDate(2026, 1, 1), ErpDates.parse("2026-01-01T00:00:00Z"))
        assertEquals(ErpDate(2028, 2, 29), ErpDates.parse("29.02.2028"))
        assertNull(ErpDates.parse("29.02.2027"))
        assertNull(ErpDates.parse("31.04.2026"))
        assertNull(ErpDates.parse("завтра"))
    }

    @Test
    fun datesFormatForTheServerAndRoundTripThroughEpochMillis() {
        val date = ErpDate(2026, 12, 31)
        assertEquals("2026-12-31", ErpDates.format(date, DateValueFormat.DATE))
        assertEquals("2026-12-31T00:00:00Z", ErpDates.format(date, DateValueFormat.START_OF_DAY_UTC))
        assertEquals("31.12.2026", ErpDates.display("2026-12-31"))
        assertEquals(0L, ErpDates.toEpochMillis(ErpDate(1970, 1, 1)))
        for (d in listOf(ErpDate(2000, 2, 29), ErpDate(2026, 11, 22), ErpDate(1969, 12, 31), ErpDate(2100, 3, 1))) {
            assertEquals(d, ErpDates.fromEpochMillis(ErpDates.toEpochMillis(d)))
        }
        // Середина дня по UTC относится к той же дате
        assertEquals(ErpDate(2026, 11, 22), ErpDates.fromEpochMillis(ErpDates.toEpochMillis(ErpDate(2026, 11, 22)) + 43_200_000L))
    }

    @Test
    fun errorsAreExplainedInRussianWithTechnicalDetailKept() {
        val forbidden = ApiError("FORBIDDEN", "Missing permission wms:post")
        assertEquals("Недостаточно прав", forbidden.userTitle())
        assertTrue(forbidden.userText().contains("Склад: проведение"))
        assertEquals("Missing permission wms:post", forbidden.technicalDetail())

        val conflict = ApiError("CONFLICT", "Receipt was modified concurrently")
        assertEquals("Данные изменил другой пользователь", conflict.userTitle())
        assertTrue(conflict.userText().contains("Обновите карточку"))

        val validation = ApiError("VALIDATION_ERROR", "Discrepancy requires a reason", mapOf("lines[0].discrepancyReason" to "required"))
        assertTrue(validation.userText().contains("Причина расхождения: обязательно"))

        val shortage = ApiError("CONFLICT", "Insufficient available quantity: requested 25, available 19.5000")
        assertEquals("Недостаточно остатка", shortage.userTitle())
        assertEquals("Недостаточно доступного остатка: запрошено 25, доступно 19,5.", shortage.userText())

        val duplicate = ApiError("CONFLICT", "Nomenclature code already exists: TACH-NC-01")
        assertEquals("Уже существует", duplicate.userTitle())
        assertEquals("Код «TACH-NC-01» уже существует.", duplicate.userText())

        val badNumber = ApiError("VALIDATION_ERROR", "Invalid decimal value", mapOf("unitPrice" to "format"))
        assertEquals("Исправьте: Цена: неверный формат.", badNumber.userText())

        val russian = ApiError("CONFLICT", "Сверка не пройдена: ИНН не совпадает")
        assertEquals("Сверка не пройдена: ИНН не совпадает", russian.userText())
        assertNull(russian.technicalDetail())

        val login = ApiError("UNAUTHORIZED", "Invalid login or password")
        assertEquals("Вход не выполнен", login.userTitle())
        assertEquals("Неверный логин или пароль.", login.userText())
    }
}
