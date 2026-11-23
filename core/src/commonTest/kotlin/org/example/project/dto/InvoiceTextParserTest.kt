package org.example.project.dto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InvoiceTextParserTest {

    @Test
    fun parsesRealSzttInvoice219658() {
        val raw = """
            Счет на оплату № 219658 от 25 февраля 2025 г.
            Поставщик: ОАО "СЗТТ", ИНН 6658017928, КПП 665801001, г. Екатеринбург
            Покупатель: ООО "АВИААГРЕГАТ-Н", ИНН 6150045308, КПП 615001001, г. Новочеркасск
            1 10000185984 ТОП-0,66-5-0,2S-120/5 У3 3 шт 2 220,00 20% 1 332,00 6 660,00
            2 10000068714 ТОП-0,66-5-0,2S-30/5 У3 3 шт 2 270,00 20% 1 362,00 6 810,00
            3 10000069460 ТШП-0,66-5-0,2S-500/5 У3 3 шт 2 180,00 20% 1 308,00 6 540,00
            4 10000068614 ТОП-0,66-5-0,5-1/5 У3 1 шт 3 350,00 20% 670,00 3 350,00
            Срок изготовления 25 рабочих дней, при 100% предоплате на р/с Поставщика.
            Доставка до склада транспортной компании "Деловые Линии" в г. Новочеркасск.
            Менеджер ОПТ Урьева Р. В.
            Телефон менеджера: +7 (343) 234-31-02, доб. 1249
        """.trimIndent()

        val parsed = InvoiceTextParser.parse(raw)

        assertEquals("219658", parsed.number)
        assertEquals("2025-02-25", parsed.invoiceDate)
        assertEquals("6658017928", parsed.supplierInn)
        assertEquals("665801001", parsed.supplierKpp)
        assertEquals("100", parsed.prepaymentPct)
        assertEquals("при 100% предоплате на р/с Поставщика", parsed.paymentTerms)
        assertEquals("25", parsed.leadTimeDays)
        assertEquals(true, parsed.leadTimeWorking)
        assertEquals("Доставка до склада транспортной компании \"Деловые Линии\" в г. Новочеркасск", parsed.deliveryTerms)
        assertEquals("Урьева Р. В. (+7 (343) 234-31-02, доб. 1249)", parsed.supplierManager)

        assertEquals(4, parsed.lines.size)
        val first = parsed.lines[0]
        assertEquals(1, first.lineNo)
        assertEquals("10000185984", first.supplierItemCode)
        assertEquals("ТОП-0,66-5-0,2S-120/5 У3", first.supplierItemName)
        assertEquals("3", first.quantity)
        assertEquals("шт", first.unit)
        assertEquals("2220.00", first.unitPrice)
        assertEquals("20", first.vatRate)
        assertEquals("1332.00", first.vatAmount)
        assertEquals("6660.00", first.lineTotal)

        val fourth = parsed.lines[3]
        assertEquals(4, fourth.lineNo)
        assertEquals("10000068614", fourth.supplierItemCode)
        assertEquals("3350.00", fourth.unitPrice)
        assertEquals("670.00", fourth.vatAmount)
        assertEquals("3350.00", fourth.lineTotal)
        assertTrue(parsed.lines.all { it.vatRate == "20" })
    }
}
