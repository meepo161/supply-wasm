package org.example.project.realtime

import io.ktor.http.HttpMethod
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import org.example.project.dto.ErpEventDomain

class ErpMutationEventConfig {
    var bus: ErpEventBus? = null
}

/**
 * Автоматически публикует WebSocket-событие в [ErpEventBus] после любой успешной
 * мутирующей HTTP-операции (`POST`, `PUT`, `PATCH`, `DELETE`, статус 2xx),
 * чтобы все открытые клиенты обновляли данные без ручных кнопок «Обновить».
 */
val ErpMutationEventPlugin = createApplicationPlugin("ErpMutationEventPlugin", ::ErpMutationEventConfig) {
    val bus = pluginConfig.bus
    on(ResponseSent) { call ->
        val eventBus = bus ?: return@on
        val method = call.request.httpMethod
        if (method != HttpMethod.Post && method != HttpMethod.Put &&
            method != HttpMethod.Patch && method != HttpMethod.Delete
        ) {
            return@on
        }
        val statusCode = call.response.status()?.value ?: return@on
        if (statusCode !in 200..299) return@on

        val path = call.request.path()
        val domain = resolveErpEventDomain(path) ?: return@on
        val entityId = extractEntityId(path)
        eventBus.publish(domain = domain, action = method.value, entityId = entityId)
    }
}

private fun resolveErpEventDomain(path: String): ErpEventDomain? = when {
    path.startsWith("/api/tasks") -> ErpEventDomain.TASK
    path.startsWith("/api/projects") -> ErpEventDomain.PROJECT
    path.startsWith("/api/wms") || path.startsWith("/api/nomenclature") -> ErpEventDomain.WMS
    path.startsWith("/api/demands") -> ErpEventDomain.DEMAND
    path.startsWith("/api/suppliers") ||
        path.startsWith("/api/supplier-offers") ||
        path.startsWith("/api/supplier-claims") ||
        path.startsWith("/api/supplier-invoices") ||
        path.startsWith("/api/purchase-orders") ||
        path.startsWith("/api/purchase-deliveries") ||
        path.startsWith("/api/counterparties") -> ErpEventDomain.SUPPLIER
    path.startsWith("/api/production") -> ErpEventDomain.PRODUCTION
    path.startsWith("/api/bom") -> ErpEventDomain.BOM
    path.startsWith("/api/documents") -> ErpEventDomain.DOCUMENT
    path.startsWith("/api/approvals") -> ErpEventDomain.APPROVAL
    path.startsWith("/api/users") ||
        path.startsWith("/api/departments") ||
        path.startsWith("/api/roles") ||
        path.startsWith("/api/permissions") ||
        path.startsWith("/api/delegations") -> ErpEventDomain.ADMIN
    else -> null
}

private fun extractEntityId(path: String): Long? = path
    .split('/')
    .firstOrNull { segment -> segment.isNotEmpty() && segment.all(Char::isDigit) }
    ?.toLongOrNull()
