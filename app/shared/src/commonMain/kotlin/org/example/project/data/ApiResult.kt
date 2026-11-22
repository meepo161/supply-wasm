package org.example.project.data

import org.example.project.dto.ApiError

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val error: ApiError, val statusCode: Int? = null) : ApiResult<Nothing>() {
        val localizedMessage: String get() = error.toRussianMessage()
    }
}

fun ApiError.toRussianMessage(): String {
    val raw = message?.takeIf { it.isNotBlank() }
    return when (code.uppercase()) {
        "FORBIDDEN" -> "Недостаточно прав для выполнения действия${raw?.let { " ($it)" } ?: ""}"
        "UNAUTHORIZED" -> "Сессия истекла или не выполнена авторизация. Войдите снова."
        "CONFLICT" -> "Конфликт данных или устаревшая версия документа. Обновите страницу.${raw?.let { " ($it)" } ?: ""}"
        "NOT_FOUND" -> "Запрошенный объект не найден${raw?.let { " ($it)" } ?: ""}"
        "VALIDATION_ERROR" -> "Проверьте корректность введённых данных${raw?.let { ": $it" } ?: ""}"
        "RATE_LIMITED", "TOO_MANY_REQUESTS" -> "Слишком много запросов. Повторите позже."
        "NETWORK_ERROR" -> "Ошибка сети или сервер недоступен${raw?.let { ": $it" } ?: ""}"
        "INTERNAL_ERROR" -> "Внутренняя ошибка сервера. Повторите позже."
        else -> raw ?: "Неизвестная ошибка ($code)"
    }
}
