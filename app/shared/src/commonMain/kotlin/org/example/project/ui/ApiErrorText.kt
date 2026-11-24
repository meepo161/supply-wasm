package org.example.project.ui

import org.example.project.dto.ApiError

/**
 * Понятные пользователю тексты ошибок API (аудит UX-01).
 *
 * Сервер отдаёт технический код и английское сообщение. Интерфейс показывает
 * заголовок и пояснение на русском по коду ошибки, а исходный текст сервера —
 * отдельно, как подробности для поддержки. Сообщения сервера на русском
 * (например, замечания сверки счёта) показываются как есть.
 */
fun ApiError.userTitle(): String = when (code) {
    "FORBIDDEN" -> "Недостаточно прав"
    "UNAUTHORIZED" -> if (message.startsWith("Invalid login")) "Вход не выполнен" else "Нужно войти в систему"
    "NOT_FOUND" -> "Объект не найден"
    "CONFLICT" -> when {
        isVersionConflict() -> "Данные изменил другой пользователь"
        message.startsWith("Insufficient available quantity") -> "Недостаточно остатка"
        message.contains("already exists") -> "Уже существует"
        else -> "Действие сейчас недоступно"
    }
    "VALIDATION_ERROR" -> "Проверьте заполнение"
    "TOO_MANY_REQUESTS" -> "Слишком много попыток"
    "NETWORK_ERROR" -> "Нет связи с сервером"
    "INTERNAL_ERROR" -> "Сбой на сервере"
    "UNSUPPORTED_MEDIA_TYPE" -> "Неподдерживаемый формат"
    "PAYLOAD_TOO_LARGE" -> "Слишком большой файл"
    "NOT_IMPLEMENTED" -> "Функция недоступна"
    else -> "Ошибка"
}

fun ApiError.userText(): String {
    if (message.hasCyrillic()) return message
    return when (code) {
        "FORBIDDEN" -> missingPermission()?.let { "Для этого действия нужно право «${it.first}» (${it.second}). Обратитесь к администратору." }
            ?: "У вас нет прав на это действие. Обратитесь к администратору."
        "UNAUTHORIZED" -> knownMessage() ?: "Сессия истекла или вход не выполнен. Войдите снова."
        "NOT_FOUND" -> "Объект удалён или у вас нет к нему доступа. Обновите список."
        "CONFLICT" -> if (isVersionConflict()) {
            "Пока карточка была открыта, документ изменили. Обновите карточку и повторите действие: ваши данные не сохранены."
        } else {
            knownMessage() ?: "Статус документа не позволяет выполнить действие. Обновите карточку и проверьте статус."
        }
        "VALIDATION_ERROR" -> fieldHints().takeIf { it.isNotEmpty() }?.let { "Исправьте: ${it.joinToString("; ")}." }
            ?: knownMessage() ?: "Некоторые поля заполнены неверно или не заполнены."
        "TOO_MANY_REQUESTS" -> "Подождите немного и повторите попытку."
        "NETWORK_ERROR" -> "Проверьте подключение к сети и повторите действие."
        "INTERNAL_ERROR" -> "Действие не выполнено. Повторите позже; если ошибка повторяется, сообщите администратору."
        "UNSUPPORTED_MEDIA_TYPE" -> "Сервер не принимает этот тип данных."
        "PAYLOAD_TOO_LARGE" -> "Размер файла превышает допустимый."
        else -> knownMessage() ?: message
    }
}

/** Исходный текст сервера, если он не совпадает с показанным пользователю. */
fun ApiError.technicalDetail(): String? =
    message.takeIf { it.isNotBlank() && !it.hasCyrillic() && it != userText() }

private fun ApiError.isVersionConflict(): Boolean {
    val m = message.lowercase()
    return "concurrent" in m || "version" in m || "modified" in m
}

private fun String.hasCyrillic(): Boolean = any { it in 'Ѐ'..'ӿ' }

private val resourceNames = mapOf(
    "wms" to "Склад", "suppliers" to "Поставщики", "invoices" to "Счета поставщиков",
    "purchase_orders" to "Заказы поставщикам", "tasks" to "Задачи", "projects" to "Проекты",
    "production" to "Производство", "bom" to "НСИ и BOM", "demands" to "Снабжение",
    "documents" to "Документы", "users" to "Пользователи", "roles" to "Роли",
    "permissions" to "Права", "departments" to "Подразделения", "audit" to "Журнал аудита",
    "approvals" to "Согласования", "attachments" to "Вложения"
)

private val actionNames = mapOf(
    "read" to "просмотр", "read_any" to "просмотр всех", "create" to "создание",
    "update" to "изменение", "update_any" to "изменение любых", "manage" to "управление",
    "post" to "проведение", "approve" to "согласование", "approve_order" to "согласование заказов",
    "activate" to "активация", "delete" to "удаление", "explode" to "разузлование",
    "order" to "оформление заказов", "offer" to "предложения", "select" to "выбор предложения",
    "receive" to "приёмка поставок", "verify" to "проверка", "assign_any" to "назначение",
    "download" to "скачивание", "reset_password" to "сброс пароля", "issue_without_reservation" to "выдача без резерва",
    "write_off_over_limit" to "списание сверх лимита", "inspect_returns" to "проверка возвратов"
)

/** «Missing permission wms:post» → («Склад — проведение», «wms:post»). */
private fun ApiError.missingPermission(): Pair<String, String>? {
    val code = Regex("""([a-z_]+):([a-z_*]+)""").find(message)?.value ?: return null
    val resource = code.substringBefore(':')
    val action = code.substringAfter(':')
    val title = listOfNotNull(resourceNames[resource] ?: resource, actionNames[action] ?: action).joinToString(": ")
    return title to code
}

private val fieldReasons = mapOf(
    "required" to "обязательно", "invalid" to "неверное значение", "positive" to "должно быть больше нуля",
    "negative" to "не может быть отрицательным", "duplicate_line_no" to "номера строк повторяются",
    "other_warehouse" to "ячейка другого склада", "inactive" to "неактивно", "location_required" to "укажите ячейку",
    "not_found" to "не найдено", "no_file" to "не загружен файл", "not_invoice" to "документ не является счётом",
    "scale" to "слишком много знаков после запятой", "range" to "вне допустимого диапазона",
    "format" to "неверный формат", "not_positive" to "должно быть больше нуля",
    "not_quarantine" to "выберите ячейку карантина", "duplicate" to "уже существует"
)

/** Подписи полей вместо технических имён из ответа сервера. */
private val fieldLabels = mapOf(
    "quantity" to "Количество", "expectedQuantity" to "Ожидаемое количество", "receivedQuantity" to "Принято",
    "unitPrice" to "Цена", "vatRate" to "НДС", "deliveryDays" to "Срок поставки", "email" to "Email",
    "note" to "Примечание", "discrepancyReason" to "Причина расхождения", "capacity" to "Ёмкость",
    "quarantineLocationId" to "Карантинная ячейка", "locationId" to "Ячейка", "warehouseId" to "Склад",
    "nomenclatureId" to "Номенклатура", "inn" to "ИНН", "kpp" to "КПП", "ogrn" to "ОГРН", "code" to "Код",
    "name" to "Наименование", "currency" to "Валюта", "validUntil" to "Действует до", "reason" to "Причина",
    "requiredDate" to "Требуемая дата", "lotNumber" to "Партия", "parentId" to "Родительская ячейка"
)

private fun ApiError.fieldHints(): List<String> =
    details.orEmpty().map { (field, reason) ->
        val key = field.substringAfterLast('.')
        "${fieldLabels[key] ?: key}: ${fieldReasons[reason] ?: reason}"
    }

/** «9.0000» → «9», «19.5000» → «19,5». */
private fun String.humanQty(): String {
    val trimmed = if ('.' in this) trimEnd('0').trimEnd('.') else this
    return trimmed.replace('.', ',')
}

/** Частые сообщения сервера, у которых есть точный перевод. */
private fun ApiError.knownMessage(): String? {
    val m = message
    return when {
        m.startsWith("Discrepancy requires a reason") -> "Укажите причину расхождения «ожидалось / принято»."
        m.startsWith("Storage location is required") -> "Укажите ячейку для каждой принятой строки."
        m.contains("exceeds the order line remainder") -> "Количество больше остатка по заказу поставщику."
        m.startsWith("Receipt is already posted") -> "Приёмка уже проведена."
        m.startsWith("Idempotency key is required") -> "Повторите действие: запрос не был обработан."
        m.contains("Bootstrap is only allowed") -> "Первый администратор уже создан. Войдите под своей учётной записью."
        m.startsWith("Invalid credentials") || m.startsWith("Invalid login") -> "Неверный логин или пароль."
        m.startsWith("Receipt by invoice is created by procurement") -> "Приёмку по счёту оформляет снабжение из карточки счёта."
        m.startsWith("Insufficient available quantity") -> {
            val nums = Regex("""requested ([\d.]+), available ([\d.]+)""").find(m)?.groupValues
            if (nums != null) "Недостаточно доступного остатка: запрошено ${nums[1].humanQty()}, доступно ${nums[2].humanQty()}."
            else "Недостаточно доступного остатка."
        }
        m.contains("already exists") -> {
            val value = m.substringAfter(": ", "").trim()
            val subject = when {
                m.contains("barcode", ignoreCase = true) -> "Штрихкод"
                m.startsWith("Supplier", ignoreCase = true) -> "Поставщик с такими реквизитами"
                m.contains("code", ignoreCase = true) -> "Код"
                else -> "Запись"
            }
            if (value.isNotEmpty()) "$subject «$value» уже существует." else "$subject уже существует."
        }
        m.startsWith("Delivery time is unknown") ->
            "У предложения не указан срок поставки: без него нельзя проверить требуемую дату. Внесите предложение со сроком."
        m.startsWith("Nothing picked") -> "Сначала отберите товар по строкам (кнопка «Отобрать»)."
        m.startsWith("Quantity must be positive") || m.startsWith("Quantity is invalid") -> "Количество должно быть больше нуля."
        m.startsWith("Invalid quantity") || m.startsWith("Invalid decimal value") ->
            "Неверное число: используйте цифры, дробную часть — через запятую."
        m.startsWith("Email format is invalid") -> "Неверный формат email."
        else -> null
    }
}
