package org.example.project.route

import io.ktor.server.application.*
import org.example.project.exception.ValidationException

/**
 * Общие разборы параметров маршрутов проекта.
 *
 * Функции вынесены на файл-уровень (а не объявлены локально внутри `Route.*`),
 * потому что локальные функции-расширения в Kotlin не компилируются.
 */

/** Числовой параметр пути; текст ошибки совпадает с остальными маршрутами проекта. */
internal fun ApplicationCall.longParamOrThrow(name: String): Long =
    parameters[name]?.toLongOrNull() ?: throw ValidationException("Invalid $name")

/**
 * Версия записи для DELETE. Передаётся в query, а не в теле: тело у DELETE
 * поддерживается не всеми клиентами, а версия нужна для оптимистичной проверки.
 */
internal fun ApplicationCall.requiredVersion(): Long {
    val raw = request.queryParameters["version"]
        ?: throw ValidationException("Version is required", mapOf("version" to "required"))
    return raw.toLongOrNull()
        ?: throw ValidationException("Invalid version", mapOf("version" to "invalid"))
}
