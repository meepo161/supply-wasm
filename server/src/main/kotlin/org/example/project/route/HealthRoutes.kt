package org.example.project.route

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.dto.ApiResponse
import org.example.project.dto.HealthDto
import org.jooq.DSLContext
import java.time.OffsetDateTime

fun Route.healthRoutes(dsl: DSLContext) {
    val handler: suspend RoutingContext.() -> Unit = {
        val dbOk = runCatching {
            dsl.fetchOne("SELECT 1") != null
        }.getOrDefault(false)
        val status = if (dbOk) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
        val dto = HealthDto(
            status = if (dbOk) "UP" else "DEGRADED",
            database = if (dbOk) "UP" else "DOWN",
            timestamp = OffsetDateTime.now().toString()
        )
        call.respond(status, ApiResponse.success(dto))
    }

    get("/health", handler)
    get("/api/health", handler)

    get("/api/openapi.json") {
        val spec = """
            {
              "openapi": "3.0.3",
              "info": {
                "title": "ERP Авиаагрегат-Н API",
                "version": "1.0.0",
                "description": "REST API системы управления предприятием (IAM, Задачи, Чаты, Проекты, BOM, WMS, Снабжение, Производство, Согласования, Документы, Аналитика, CRM)"
              },
              "paths": {
                "/api/health": { "get": { "summary": "Health check" } },
                "/api/auth/login": { "post": { "summary": "Вход" } },
                "/api/users/me": { "put": { "summary": "Обновление своего профиля" } },
                "/api/audit": { "get": { "summary": "Журнал аудита" } },
                "/api/search": { "get": { "summary": "Глобальный поиск" } },
                "/api/projects": { "get": { "summary": "Проекты" }, "post": { "summary": "Создать проект" } },
                "/api/tasks": { "get": { "summary": "Задачи" }, "post": { "summary": "Создать задачу" } },
                "/api/tasks/dependencies": { "post": { "summary": "Добавить зависимость задачи" } },
                "/api/conversations": { "get": { "summary": "Чаты" } },
                "/api/bom/models": { "get": { "summary": "Модели изделий" } },
                "/api/bom/revisions/{id}/diff": { "get": { "summary": "Сравнение ревизий BOM" } },
                "/api/bom/nomenclature/{id}/where-used": { "get": { "summary": "Применяемость компонента" } },
                "/api/wms/stock": { "get": { "summary": "Остатки склада" } },
                "/api/wms/receipts/{id}/qc": { "get": { "summary": "Статус ОТК приёмки" }, "post": { "summary": "Провести ОТК приёмки" } },
                "/api/wms/movements": { "get": { "summary": "Журнал движений" } },
                "/api/wms/low-stock": { "get": { "summary": "Позиции ниже минимума" } },
                "/api/demands": { "get": { "summary": "Потребности" }, "post": { "summary": "Создать потребность" } },
                "/api/demands/merge": { "post": { "summary": "Объединить потребности" } },
                "/api/procurement/plan": { "get": { "summary": "План закупок" } },
                "/api/suppliers": { "get": { "summary": "Поставщики" } },
                "/api/supplier-claims": { "get": { "summary": "Рекламации поставщикам" }, "post": { "summary": "Создать рекламацию" } },
                "/api/purchase-orders": { "get": { "summary": "Заказы поставщикам" } },
                "/api/production/orders": { "get": { "summary": "Производственные заказы" } },
                "/api/production/nonconformities": { "get": { "summary": "Несоответствия (NCR)" } },
                "/api/approvals/my-queue": { "get": { "summary": "Моя очередь согласований" } },
                "/api/crm/counterparties": { "get": { "summary": "Контрагенты (CRM)" }, "post": { "summary": "Создать контрагента" } },
                "/api/analytics/summary": { "get": { "summary": "Сводная аналитика" } },
                "/api/analytics/calendar": { "get": { "summary": "Календарь сроков" } },
                "/api/analytics/import/nomenclature": { "post": { "summary": "Импорт номенклатуры из CSV" } }
              }
            }
        """.trimIndent()
        call.respondText(spec, ContentType.Application.Json, HttpStatusCode.OK)
    }
}
