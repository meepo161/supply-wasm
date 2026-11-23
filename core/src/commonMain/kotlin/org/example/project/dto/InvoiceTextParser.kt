package org.example.project.dto

data class ParsedSupplierInvoiceLine(
    val lineNo: Int,
    val supplierItemCode: String = "",
    val supplierItemName: String,
    val quantity: String,
    val unit: String,
    val unitPrice: String,
    val vatRate: String = "20",
    val vatAmount: String = "",
    val lineTotal: String = ""
)

data class ParsedSupplierInvoice(
    val number: String? = null,
    val invoiceDate: String? = null,
    val validUntil: String? = null,
    val supplierInn: String? = null,
    val supplierKpp: String? = null,
    val paymentTerms: String? = null,
    val prepaymentPct: String? = null,
    val leadTimeDays: String? = null,
    val leadTimeWorking: Boolean? = null,
    val deliveryTerms: String? = null,
    val supplierManager: String? = null,
    val pricesIncludeVat: Boolean? = null,
    val lines: List<ParsedSupplierInvoiceLine> = emptyList()
)

object InvoiceTextParser {
    private const val BUYER_INN = "6150045308"
    private const val BUYER_KPP = "615001001"

    private val russianMonths = mapOf(
        "января" to "01",
        "февраля" to "02",
        "марта" to "03",
        "апреля" to "04",
        "мая" to "05",
        "июня" to "06",
        "июля" to "07",
        "августа" to "08",
        "сентября" to "09",
        "октября" to "10",
        "ноября" to "11",
        "декабря" to "12"
    )

    private val invoiceHeaderRegex = Regex(
        """Сч[её]т(?:\s+на\s+оплату)?\s*№\s*([^\s,]+)\s+от\s+(\d{1,2}\s+[а-яА-ЯёЁ]+\s+\d{4}|\d{2}\.\d{2}\.\d{4})""",
        RegexOption.IGNORE_CASE
    )
    private val validUntilRegex = Regex(
        """(?:действителен\s+до|оплатить\s+до|срок\s+оплаты\s+до)\s*[:\-]?\s*(\d{1,2}\s+[а-яА-ЯёЁ]+\s+\d{4}|\d{2}\.\d{2}\.\d{4})""",
        RegexOption.IGNORE_CASE
    )
    private val innRegex = Regex("""ИНН\s*[:№]?\s*(\d{10,12})""", RegexOption.IGNORE_CASE)
    private val kppRegex = Regex("""КПП\s*[:№]?\s*(\d{9})""", RegexOption.IGNORE_CASE)
    private val prepaymentRegex = Regex("""(\d{1,3})\s*%\s*предоплат[а-яА-ЯёЁ]*""", RegexOption.IGNORE_CASE)
    private val leadTimeRegex = Regex(
        """Срок\s+изготовления\s*[:\-]?\s*(\d+)\s+(рабоч[а-яА-ЯёЁ]*|календарн[а-яА-ЯёЁ]*)\s+дн""",
        RegexOption.IGNORE_CASE
    )
    private val deliveryRegex = Regex("""^Доставка\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val managerRegex = Regex(
        """Менеджер(?:\s+[А-ЯЁA-Z]+)?\s+([А-ЯЁ][а-яё]+\s+[А-ЯЁ]\.\s*[А-ЯЁ]\.|[А-ЯЁ][а-яё]+(?:\s+[А-ЯЁ][а-яё]+){1,2})"""
    )
    private val managerPhoneRegex = Regex("""Телефон\s+менеджера\s*[:\-]?\s*([^\r\n]+)""", RegexOption.IGNORE_CASE)

    private val lineWithVatAndAmountRegex = Regex(
        """^(\d{1,3})\s+(.+?)\s+(\d+(?:[.,]\d+)?)\s+(шт\.?|компл\.?|м2|м3|п\.м\.?|упак\.?|ед\.?|м|кг|т|л)\s+(\d{1,3}(?:[\s\u00A0]\d{3})*[.,]\d{2})\s+(\d{1,2})\s*%\s+(\d{1,3}(?:[\s\u00A0]\d{3})*[.,]\d{2})\s+(\d{1,3}(?:[\s\u00A0]\d{3})*[.,]\d{2})\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val lineSimpleRegex = Regex(
        """^(\d{1,3})\s+(.+?)\s+(\d+(?:[.,]\d+)?)\s+(шт\.?|компл\.?|м2|м3|п\.м\.?|упак\.?|ед\.?|м|кг|т|л)\s+(\d{1,3}(?:[\s\u00A0]\d{3})*[.,]\d{2})\s+(\d{1,3}(?:[\s\u00A0]\d{3})*[.,]\d{2})\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val leadingCodeRegex = Regex("""^(\d{5,15})\s+(.+)$""")

    fun parse(rawText: String): ParsedSupplierInvoice {
        val normalized = rawText.replace('\u00A0', ' ').replace("\r\n", "\n")
        val lines = normalized.lines().map { it.trim() }.filter { it.isNotEmpty() }

        var number: String? = null
        var invoiceDate: String? = null
        invoiceHeaderRegex.find(normalized)?.let { match ->
            number = match.groupValues[1].trim()
            invoiceDate = parseDateToIso(match.groupValues[2].trim())
        }

        val validUntil = validUntilRegex.find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { parseDateToIso(it.trim()) }

        val supplierLine = lines.firstOrNull { it.contains("Поставщик", ignoreCase = true) }
        val nonBuyerText = lines.filterNot {
            it.contains("Покупатель", ignoreCase = true) || it.contains(BUYER_INN)
        }.joinToString("\n")

        val supplierInn = (supplierLine?.let { innRegex.find(it)?.groupValues?.getOrNull(1) }
            ?: innRegex.findAll(nonBuyerText)
                .map { it.groupValues[1] }
                .firstOrNull { it != BUYER_INN })

        val supplierKpp = (supplierLine?.let { kppRegex.find(it)?.groupValues?.getOrNull(1) }
            ?: kppRegex.findAll(nonBuyerText)
                .map { it.groupValues[1] }
                .firstOrNull { it != BUYER_KPP })

        val prepaymentMatch = prepaymentRegex.find(normalized)
        val prepaymentPct = prepaymentMatch?.groupValues?.getOrNull(1)
        val paymentTerms = lines.firstOrNull {
            it.contains("предоплат", ignoreCase = true) || it.contains("условия оплаты", ignoreCase = true)
        }?.let { line ->
            val idx = line.indexOf("при ", ignoreCase = true)
            if (idx >= 0) line.substring(idx).trim().trimEnd('.') else line.trimEnd('.')
        }

        val leadTimeMatch = leadTimeRegex.find(normalized)
        val leadTimeDays = leadTimeMatch?.groupValues?.getOrNull(1)
        val leadTimeWorking = leadTimeMatch?.groupValues?.getOrNull(2)
            ?.startsWith("рабоч", ignoreCase = true)

        val deliveryTerms = lines.firstNotNullOfOrNull { line ->
            deliveryRegex.find(line)?.let { line.trimEnd('.') }
        }

        val managerName = managerRegex.find(normalized)?.groupValues?.getOrNull(1)?.trim()
        val managerPhone = managerPhoneRegex.find(normalized)?.groupValues?.getOrNull(1)?.trim()
        val supplierManager = when {
            !managerName.isNullOrBlank() && !managerPhone.isNullOrBlank() -> "$managerName ($managerPhone)"
            !managerName.isNullOrBlank() -> managerName
            !managerPhone.isNullOrBlank() -> managerPhone
            else -> null
        }

        val pricesIncludeVat = when {
            normalized.contains("в т.ч. НДС", ignoreCase = true) ||
                normalized.contains("в том числе НДС", ignoreCase = true) ||
                normalized.contains("включая НДС", ignoreCase = true) -> true
            else -> null
        }

        val parsedLines = lines.mapNotNull(::parseLine)

        return ParsedSupplierInvoice(
            number = number,
            invoiceDate = invoiceDate,
            validUntil = validUntil,
            supplierInn = supplierInn,
            supplierKpp = supplierKpp,
            paymentTerms = paymentTerms,
            prepaymentPct = prepaymentPct,
            leadTimeDays = leadTimeDays,
            leadTimeWorking = leadTimeWorking,
            deliveryTerms = deliveryTerms,
            supplierManager = supplierManager,
            pricesIncludeVat = pricesIncludeVat,
            lines = parsedLines
        )
    }

    private fun parseLine(line: String): ParsedSupplierInvoiceLine? {
        lineWithVatAndAmountRegex.matchEntire(line)?.let { m ->
            val (code, name) = splitCodeAndName(m.groupValues[2].trim())
            return ParsedSupplierInvoiceLine(
                lineNo = m.groupValues[1].toInt(),
                supplierItemCode = code,
                supplierItemName = name,
                quantity = normalizeNumber(m.groupValues[3]),
                unit = m.groupValues[4].trimEnd('.'),
                unitPrice = normalizeNumber(m.groupValues[5]),
                vatRate = m.groupValues[6],
                vatAmount = normalizeNumber(m.groupValues[7]),
                lineTotal = normalizeNumber(m.groupValues[8])
            )
        }
        lineSimpleRegex.matchEntire(line)?.let { m ->
            val (code, name) = splitCodeAndName(m.groupValues[2].trim())
            return ParsedSupplierInvoiceLine(
                lineNo = m.groupValues[1].toInt(),
                supplierItemCode = code,
                supplierItemName = name,
                quantity = normalizeNumber(m.groupValues[3]),
                unit = m.groupValues[4].trimEnd('.'),
                unitPrice = normalizeNumber(m.groupValues[5]),
                lineTotal = normalizeNumber(m.groupValues[6])
            )
        }
        return null
    }

    private fun splitCodeAndName(raw: String): Pair<String, String> {
        val codeMatch = leadingCodeRegex.matchEntire(raw) ?: return "" to raw
        return codeMatch.groupValues[1] to codeMatch.groupValues[2].trim()
    }

    private fun normalizeNumber(raw: String): String =
        raw.replace(" ", "").replace("\u00A0", "").replace(',', '.')

    private fun parseDateToIso(raw: String): String? {
        val dotted = Regex("""^(\d{2})\.(\d{2})\.(\d{4})$""").matchEntire(raw)
        if (dotted != null) {
            return "${dotted.groupValues[3]}-${dotted.groupValues[2]}-${dotted.groupValues[1]}"
        }
        val words = Regex("""^(\d{1,2})\s+([а-яА-ЯёЁ]+)\s+(\d{4})$""").matchEntire(raw)
        if (words != null) {
            val day = words.groupValues[1].padStart(2, '0')
            val month = russianMonths[words.groupValues[2].lowercase()] ?: return null
            val year = words.groupValues[3]
            return "$year-$month-$day"
        }
        return null
    }
}
