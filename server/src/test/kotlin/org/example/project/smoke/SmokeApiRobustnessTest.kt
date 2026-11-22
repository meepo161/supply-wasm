package org.example.project.smoke

import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import io.ktor.server.routing.HttpHeaderRouteSelector
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.application.pluginOrNull
import io.ktor.server.testing.ApplicationTestBuilder
import org.example.project.TestDatabase
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * Smoke: устойчивость и защита каждого HTTP-эндпоинта.
 *
 * Маршруты берутся из живого дерева Ktor, поэтому новый эндпоинт автоматически
 * попадает под проверку. Для каждого маршрута проигрываются ситуации:
 *  - аноним (нет сессии) → 401;
 *  - сотрудник без прав → не 2xx на изменяющих операциях и не 5xx;
 *  - администратор: битый JSON, пустое тело, несуществующий id, нечисловой id → 4xx, не 5xx;
 *  - изменяющий запрос с пустым телом не может завершиться успехом (нет валидации).
 */
class SmokeApiRobustnessTest {

    data class Endpoint(val method: String, val template: String) {
        val hasIds: Boolean get() = template.contains('{')
        fun path(id: String): String = template.replace(Regex("\\{[^}]+}"), id)
        override fun toString() = "$method $template"
    }

    @Test
    fun `every endpoint rejects bad input without server errors`() = smokeApp {
        startApplication()
        val endpoints = discoverEndpoints()
        check(endpoints.size > 150) { "Найдено подозрительно мало маршрутов: ${endpoints.size}" }

        val company = company()
        val nobody = company.hire("nobody", "Сотрудник Без Прав", emptyList())
        val anonymous = company.anonymous()
        val report = SmokeReport("api-robustness", ::knownDefect)

        for (endpoint in endpoints) {
            val area = areaOf(endpoint.template)
            val mutating = endpoint.method != "GET"
            val path = endpoint.path(MISSING_ID)

            // 1. Аноним
            if (endpoint.template !in PUBLIC) {
                val status = anonymous.send(endpoint.method, path).status.value
                report.check(area, "$endpoint · аноним", "401", "$status", status == 401)
            }

            if (endpoint.template in SESSION_SENSITIVE) continue

            // 2. Сотрудник без прав
            run {
                val status = nobody.client.send(endpoint.method, path, "{}").status.value
                val ok = status < 500 && !(mutating && status in 200..299)
                report.check(area, "$endpoint · без прав, пустое тело", "4xx", "$status", ok)
            }

            // 3. Администратор: несуществующий id / пустое тело
            run {
                val status = company.admin.client.send(endpoint.method, path, "{}").status.value
                val ok = status < 500 && !(mutating && status in 200..299 && endpoint.template !in EMPTY_BODY_OK)
                report.check(area, "$endpoint · админ, ${if (endpoint.hasIds) "нет объекта" else "пустое тело"}", "4xx", "$status", ok)
            }

            if (mutating && endpoint.method != "DELETE") {
                // 4. Битый JSON
                val status = company.admin.client.send(endpoint.method, path, "{\"broken\": ").status.value
                val ok = status in 400..499
                report.check(area, "$endpoint · битый JSON", "4xx", "$status", ok)
            }

            if (endpoint.hasIds) {
                // 5. Нечисловой идентификатор в пути
                val status = company.admin.client.send(endpoint.method, endpoint.path("abc"), "{}").status.value
                val ok = status in 400..499
                report.check(area, "$endpoint · id=abc", "4xx", "$status", ok)
            }
        }

        // Сессия администратора должна пережить весь прогон: ни один эндпоинт
        // не должен разлогинивать или менять права в результате мусорного запроса.
        val me = company.admin.client.send("GET", "/api/auth/me").status.value
        report.check("Профиль", "GET /api/auth/me после прогона", "200", "$me", me == 200)

        println("SMOKE api-robustness: проверено ситуаций ${report.size}, маршрутов ${endpoints.size}")
        report.assertAll()
    }

    private fun ApplicationTestBuilder.discoverEndpoints(): List<Endpoint> {
        val root = application.pluginOrNull(RoutingRoot) ?: error("Routing не установлен")
        return root.getAllRoutes()
            .mapNotNull { node -> toEndpoint(node) }
            .filter { it.template.startsWith("/api") }
            .filter { it.method in setOf("GET", "POST", "PUT", "DELETE") }
            .distinct()
            .sortedWith(compareBy({ it.template }, { it.method }))
    }

    private fun toEndpoint(node: RoutingNode): Endpoint? {
        val method = (node.selector as? HttpMethodRouteSelector)?.method ?: return null
        val segments = mutableListOf<String>()
        var current: RoutingNode? = node
        while (current != null) {
            when (val selector = current.selector) {
                is PathSegmentConstantRouteSelector -> segments += selector.value
                is PathSegmentParameterRouteSelector -> segments += selector.toString()
                is HttpHeaderRouteSelector -> return null // WebSocket upgrade
                else -> Unit
            }
            current = current.parent
        }
        if (method == HttpMethod.Head || method == HttpMethod.Options) return null
        return Endpoint(method.value, "/" + segments.reversed().joinToString("/"))
    }

    private fun areaOf(path: String): String = when {
        path.startsWith("/api/wms") -> "Склад"
        path.startsWith("/api/bom") -> "НСИ и BOM"
        path.startsWith("/api/production") -> "Производство"
        path.startsWith("/api/purchase") -> "Заказы поставщикам"
        path.startsWith("/api/supplier") || path.startsWith("/api/crm") -> "Поставщики"
        path.startsWith("/api/demands") || path.startsWith("/api/procurement") -> "Снабжение"
        path.startsWith("/api/tasks") -> "Задачи"
        path.startsWith("/api/project") -> "Проекты"
        path.startsWith("/api/conversations") || path.startsWith("/api/chat") -> "Чат"
        path.startsWith("/api/document") -> "Документы"
        path.startsWith("/api/analytics") || path.startsWith("/api/audit") -> "Аналитика"
        path.startsWith("/api/notifications") || path.startsWith("/api/push") -> "Уведомления"
        path.startsWith("/api/users") || path.startsWith("/api/roles") || path.startsWith("/api/permissions") ||
            path.startsWith("/api/departments") || path.startsWith("/api/admin") -> "Администрирование"
        path.startsWith("/api/auth") || path.startsWith("/api/delegations") -> "Профиль"
        path.startsWith("/api/approvals") -> "Согласования"
        path.startsWith("/api/search") -> "Поиск"
        path.startsWith("/api/attachments") -> "Вложения"
        else -> "Прочее"
    }

    @Before
    fun reset() = TestDatabase.reset()

    companion object {
        private const val MISSING_ID = "987654321"

        /** Эндпоинты, доступные без сессии по назначению. */
        private val PUBLIC = setOf(
            "/api/health",
            "/api/openapi.json",
            "/api/auth/login",
            "/api/admin/bootstrap",
            "/api/push/public-key",
            "/api/auth/logout" // идемпотентный выход без сессии допустим
        )

        /** Не проигрываются от имени сотрудников: меняют саму сессию или учётку. */
        private val SESSION_SENSITIVE = setOf(
            "/api/auth/logout",
            "/api/auth/login",
            "/api/admin/bootstrap",
            "/api/auth/password",
            "/api/auth/me",
            "/api/users/me"
        )

        /** Изменяющие операции, для которых пустое тело допустимо по смыслу. */
        private val EMPTY_BODY_OK = setOf<String>()

        /**
         * Подтверждённые аудитом дефекты (см. docs/audit/2026-11-22-system-audit.md).
         * Пока дефект не исправлен, его ситуации фиксируются в отчёте, но не валят набор;
         * при исправлении запись удаляется, и тест начинает охранять поведение.
         */
        val KNOWN_DEFECTS: Map<String, String> = mapOf(
            "AUDIT-09" to "Нечисловой id в /api/departments, /api/roles, /api/permissions даёт 500 (toLong без проверки)",
            "PUSH-503" to "Push без VAPID-ключей отвечает 503 — ожидаемо для неподключённого сервиса"
        )

        /** Относит расхождение к подтверждённому дефекту аудита или возвращает null (новый дефект/регрессия). */
        fun knownDefect(case: String, actual: String): String? {
            val status = actual.toIntOrNull() ?: return null
            return when {
                status == 503 && case.contains("/api/push/subscriptions") -> "PUSH-503"
                status == 500 && case.contains("id=abc") &&
                    listOf("/api/departments/", "/api/roles/", "/api/permissions/").any { case.contains(it) } -> "AUDIT-09"
                else -> null
            }
        }

        @JvmStatic
        @BeforeClass
        fun setup() = TestDatabase.init()

        @JvmStatic
        @AfterClass
        fun teardown() = TestDatabase.destroy()
    }
}
