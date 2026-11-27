package org.example.project.ui.main

/**
 * Маршрут приложения в адресной строке (аудит N-07): раздел и, для склада,
 * открытая карточка номенклатуры. Формат: `#/wms`, `#/wms/nomenclature/5`.
 * Благодаря этому «Назад/Вперёд» браузера переключают разделы, а перезагрузка
 * открывает тот же раздел.
 */
data class AppRoute(
    val destination: NavigationDestination,
    val nomenclatureId: Long? = null
) {
    fun toHash(): String = buildString {
        append("/").append(destination.routeSegment())
        if (destination == NavigationDestination.WMS && nomenclatureId != null) {
            append("/nomenclature/").append(nomenclatureId)
        }
    }
}

fun NavigationDestination.routeSegment(): String = name.lowercase().replace('_', '-')

/** Разбирает hash адреса (с `#` или без). Неизвестный или пустой маршрут даёт null. */
fun parseAppRoute(hash: String): AppRoute? {
    val parts = hash.removePrefix("#").trim('/').split('/').filter { it.isNotEmpty() }
    val segment = parts.firstOrNull() ?: return null
    val destination = when (segment.lowercase()) {
        "purchase-orders", "suppliers" -> NavigationDestination.DEMANDS
        else -> NavigationDestination.entries.firstOrNull { it.routeSegment() == segment.lowercase() }
    } ?: return null
    val nomenclatureId = if (destination == NavigationDestination.WMS && parts.size == 3 && parts[1] == "nomenclature") {
        parts[2].toLongOrNull()?.takeIf { it > 0 }
    } else {
        null
    }
    return AppRoute(destination, nomenclatureId)
}

/** Работа с адресной строкой браузера; подписчики вызываются при «Назад/Вперёд» и ручной правке hash. */
internal object BrowserHash {
    private val listeners = mutableListOf<() -> Unit>()
    private var installed = false

    fun read(): String = browserHashRead().removePrefix("#")

    fun push(hash: String) = browserHashWrite(hash, replace = false)

    fun replace(hash: String) = browserHashWrite(hash, replace = true)

    /** Возвращает функцию отписки. */
    fun subscribe(listener: () -> Unit): () -> Unit {
        listeners += listener
        if (!installed) {
            installed = true
            browserHashListen { listeners.toList().forEach { it() } }
        }
        return { listeners -= listener }
    }
}

internal expect fun browserHashRead(): String

internal expect fun browserHashWrite(hash: String, replace: Boolean)

/** Подписывается на popstate/hashchange один раз за время жизни страницы. */
internal expect fun browserHashListen(onChange: () -> Unit)
