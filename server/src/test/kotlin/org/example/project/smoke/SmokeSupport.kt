package org.example.project.smoke

import com.typesafe.config.ConfigFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.example.project.TestDatabase
import org.example.project.dto.ApiResponse
import org.example.project.dto.BootstrapRequest
import org.example.project.dto.CreatePermissionRequest
import org.example.project.dto.CreateRoleRequest
import org.example.project.dto.CreateUserRequest
import org.example.project.dto.LoginRequest
import org.example.project.dto.MeResponse
import org.example.project.dto.RoleDto
import org.example.project.dto.UserDto
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Общая инфраструктура smoke-набора (аудит 2026-11-22).
 *
 * Smoke-тесты работают через реальный HTTP API и PostgreSQL-миграции так же,
 * как пользователь работает через веб-клиент: вход по cookie-сессии, права из ролей,
 * версии и ключи идемпотентности в теле запросов.
 */
internal val smokeJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

internal fun smokeApp(block: suspend ApplicationTestBuilder.() -> Unit) {
    testApplication {
        environment {
            config = HoconApplicationConfig(
                ConfigFactory.parseString(
                    """
                    ktor {
                      deployment { port = 0 }
                      application {
                        modules = [org.example.project.ApplicationKt.module]
                      }
                    }
                    database {
                      url = "${TestDatabase.TEST_URL}"
                      user = "erp"
                      password = "erp"
                    }
                    session {
                      ttlMinutes = 60
                      cookieName = "erp_session"
                    }
                    security {
                      allowedOrigins = "http://localhost:8080,http://localhost:8081"
                      cookieSecure = false
                    }
                    storage {
                      path = "./.local/test-storage"
                    }
                    """.trimIndent()
                )
            )
        }
        block()
    }
}

internal fun ApplicationTestBuilder.cookieClient(): HttpClient = createClient {
    install(HttpCookies)
    expectSuccess = false
}

internal fun newKey(prefix: String = "smoke"): String = "$prefix-${UUID.randomUUID()}"

internal inline fun <reified T> encode(value: T): String = smokeJson.encodeToString(serializer<T>(), value)

internal suspend inline fun <reified T> HttpResponse.data(): T {
    val text = bodyAsText()
    return smokeJson.decodeFromString(serializer<ApiResponse<T>>(), text).data
        ?: fail("Ответ без data: $status $text")
}

internal suspend fun HttpResponse.errorCode(): String? = runCatching {
    smokeJson.decodeFromString(serializer<ApiResponse<Unit>>(), bodyAsText()).error?.code
}.getOrNull()

internal suspend fun HttpClient.postJson(path: String, body: String): HttpResponse = post(path) {
    contentType(ContentType.Application.Json)
    setBody(body)
}

internal suspend fun HttpClient.putJson(path: String, body: String): HttpResponse = put(path) {
    contentType(ContentType.Application.Json)
    setBody(body)
}

internal suspend inline fun <reified Req, reified Res> HttpClient.postOk(
    path: String,
    request: Req,
    expected: HttpStatusCode = HttpStatusCode.OK
): Res {
    val response = postJson(path, encode(request))
    assertEquals(expected, response.status, "POST $path -> ${response.bodyAsText()}")
    return response.data()
}

internal suspend inline fun <reified Req, reified Res> HttpClient.putOk(path: String, request: Req): Res {
    val response = putJson(path, encode(request))
    assertEquals(HttpStatusCode.OK, response.status, "PUT $path -> ${response.bodyAsText()}")
    return response.data()
}

internal suspend inline fun <reified Res> HttpClient.getOk(path: String): Res {
    val response = get(path)
    assertEquals(HttpStatusCode.OK, response.status, "GET $path -> ${response.bodyAsText()}")
    return response.data()
}

/** Пользователь предприятия: HTTP-клиент с собственной cookie-сессией и профиль. */
internal class Actor(val client: HttpClient, val user: UserDto)

internal fun perms(vararg pairs: String): List<CreatePermissionRequest> = pairs.map {
    CreatePermissionRequest(it.substringBefore(':'), it.substringAfter(':'))
}

/** Сотрудники «Авиаагрегат-Н», от лица которых проигрываются сценарии. */
internal suspend fun ApplicationTestBuilder.company(): Company {
    val client = cookieClient()
    client.postJson("/api/admin/bootstrap", encode(BootstrapRequest("admin", "admin123", "Администратор Системы")))
    val login = client.postJson("/api/auth/login", encode(LoginRequest("admin", "admin123")))
    assertEquals(HttpStatusCode.OK, login.status, login.bodyAsText())
    return Company(this, Actor(client, client.getOk<MeResponse>("/api/auth/me").user))
}

internal class Company(private val builder: ApplicationTestBuilder, val admin: Actor) {

    suspend fun hire(
        login: String,
        fullName: String,
        permissions: List<CreatePermissionRequest>,
        departmentId: Long? = null,
        password: String = "Passw0rd!"
    ): Actor {
        val roleIds = if (permissions.isEmpty()) emptyList() else {
            val role = admin.client.postOk<CreateRoleRequest, RoleDto>(
                "/api/roles",
                CreateRoleRequest(code = "R_${login.uppercase()}", name = "Роль $fullName", permissions = permissions),
                HttpStatusCode.Created
            )
            listOf(role.id)
        }
        val user = admin.client.postOk<CreateUserRequest, UserDto>(
            "/api/users",
            CreateUserRequest(login, password, fullName, departmentId = departmentId, roleIds = roleIds),
            HttpStatusCode.Created
        )
        return Actor(login(login, password), user)
    }

    suspend fun login(login: String, password: String): HttpClient {
        val client = builder.cookieClient()
        val response = client.postJson("/api/auth/login", encode(LoginRequest(login, password)))
        assertEquals(HttpStatusCode.OK, response.status, "login $login -> ${response.bodyAsText()}")
        return client
    }

    fun anonymous(): HttpClient = builder.cookieClient()
}

/**
 * Накопитель результатов табличных проверок: каждая ситуация фиксируется отдельно,
 * чтобы один дефект не скрывал остальные. Отчёт пишется в `build/smoke-reports`.
 */
internal class SmokeReport(
    private val suite: String,
    private val classify: (case: String, actual: String) -> String? = { _, _ -> null }
) {
    private data class Row(val area: String, val case: String, val expected: String, val actual: String, val ok: Boolean, val known: String?)

    private val rows = mutableListOf<Row>()

    val size: Int get() = rows.size

    fun check(area: String, case: String, expected: String, actual: String, ok: Boolean) {
        rows += Row(area, case, expected, actual, ok, if (ok) null else classify(case, actual))
    }

    fun failures(): List<String> = rows.filter { !it.ok }.map { "[${it.area}] ${it.case}: ожидалось ${it.expected}, получено ${it.actual}" }

    fun write(): File {
        val dir = File("build/smoke-reports").apply { mkdirs() }
        val file = File(dir, "$suite.md")
        file.writeText(buildString {
            appendLine("# $suite")
            appendLine()
            appendLine("Ситуаций: ${rows.size}, расхождений: ${rows.count { !it.ok }}, из них новых: ${rows.count { !it.ok && it.known == null }}")
            appendLine()
            rows.filter { !it.ok }.groupBy { it.known ?: "НОВОЕ" }.forEach { (k, v) -> appendLine("- $k: ${v.size}") }
            appendLine()
            appendLine("| Вкладка | Ситуация | Ожидание | Факт | Итог |")
            appendLine("| --- | --- | --- | --- | --- |")
            rows.forEach {
                val verdict = when { it.ok -> "OK"; it.known != null -> "известный ${it.known}"; else -> "**FAIL**" }
                appendLine("| ${it.area} | `${it.case}` | ${it.expected} | ${it.actual} | $verdict |")
            }
        })
        return file
    }

    fun assertAll() {
        val file = write()
        val unexpected = rows.filter { !it.ok && it.known == null }
        if (unexpected.isNotEmpty()) {
            fail(
                "$suite: ${unexpected.size} из ${rows.size} ситуаций не прошли (отчёт ${file.path}):\n" +
                    unexpected.joinToString("\n") { "[${it.area}] ${it.case}: ожидалось ${it.expected}, получено ${it.actual}" }
            )
        }
    }
}

internal suspend fun HttpClient.send(method: String, path: String, body: String? = null): HttpResponse = when (method) {
    "GET" -> get(path)
    "POST" -> postJson(path, body ?: "{}")
    "PUT" -> putJson(path, body ?: "{}")
    "DELETE" -> delete(path)
    else -> error("Unsupported method $method")
}
