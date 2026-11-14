package org.example.project

import com.typesafe.config.ConfigFactory
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.example.project.dto.*
import org.example.project.route.SetActiveRequest
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApplicationTest {

    @Before
    fun reset() {
        TestDatabase.reset()
    }

    @Test
    fun `bootstrap login and me`() = testApp {
        val client = jsonClient()

        val bootstrapResponse = client.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin", "admin123", "Admin User")))
        }
        assertEquals(HttpStatusCode.Created, bootstrapResponse.status)

        val loginResponse = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("admin", "admin123")))
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status)
        val loginBody = loginResponse.bodyAs<ApiResponse<LoginResponse>>()
        assertTrue(loginBody.success)
        assertEquals("admin", loginBody.data!!.user.login)

        val meResponse = client.get("/api/auth/me")
        assertEquals(HttpStatusCode.OK, meResponse.status)
        val meBody = meResponse.bodyAs<ApiResponse<MeResponse>>()
        assertTrue(meBody.success)
        assertEquals("admin", meBody.data!!.user.login)
    }

    @Test
    fun `login error returns 401`() = testApp {
        val client = jsonClient()
        client.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin", "admin123", "Admin User")))
        }

        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("admin", "wrong")))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `access without session is rejected`() = testApp {
        val client = jsonClient()
        val response = client.get("/api/users")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `admin creates another user`() = testApp {
        val client = jsonClient()
        client.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin", "admin123", "Admin User")))
        }
        client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("admin", "admin123")))
        }

        val response = client.post("/api/users") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateUserRequest("ivan", "password", "Ivan Ivanov")))
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.bodyAs<ApiResponse<UserDto>>()
        assertTrue(body.success)
        assertEquals("ivan", body.data!!.login)
    }

    @Test
    fun `direct access to another user is forbidden`() = testApp {
        val client = jsonClient()
        client.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin", "admin123", "Admin User")))
        }
        val adminClient = jsonClient()
        adminClient.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("admin", "admin123")))
        }
        adminClient.post("/api/users") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateUserRequest("ivan", "password", "Ivan Ivanov")))
        }

        client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("ivan", "password")))
        }

        val response = client.get("/api/users/1")
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `login sets secure session cookie attributes`() = testApp(cookieSecure = true) {
        val client = jsonClient()
        client.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin", "admin123", "Admin User")))
        }

        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("admin", "admin123")))
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val setCookie = response.headers[HttpHeaders.SetCookie]
        assertNotNull(setCookie)
        val cookie = parseServerSetCookieHeader(setCookie)
        assertEquals("erp_session", cookie.name)
        assertTrue(cookie.httpOnly)
        assertTrue(cookie.secure)
        assertEquals("Lax", cookie.extensions["SameSite"])
        assertEquals("/", cookie.path)
    }

    @Test
    fun `login sets lax non secure session cookie by default`() = testApp {
        val client = jsonClient()
        client.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin", "admin123", "Admin User")))
        }

        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("admin", "admin123")))
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val setCookie = response.headers[HttpHeaders.SetCookie]
        assertNotNull(setCookie)
        val cookie = parseServerSetCookieHeader(setCookie)
        assertTrue(cookie.httpOnly)
        assertEquals(false, cookie.secure)
        assertEquals("Lax", cookie.extensions["SameSite"])
    }

    @Test
    fun `logout clears cookie with same attributes`() = testApp(cookieSecure = true) {
        val client = jsonClient()
        client.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin", "admin123", "Admin User")))
        }
        val loginResponse = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("admin", "admin123")))
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status)
        val sessionToken = loginResponse.headers[HttpHeaders.SetCookie]
            ?.let { parseServerSetCookieHeader(it).value }
        assertNotNull(sessionToken)

        val response = client.post("/api/auth/logout") {
            header(HttpHeaders.Cookie, "erp_session=$sessionToken")
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val setCookie = response.headers[HttpHeaders.SetCookie]
        assertNotNull(setCookie)
        val cookie = parseServerSetCookieHeader(setCookie)
        assertEquals("erp_session", cookie.name)
        assertTrue(cookie.httpOnly)
        assertTrue(cookie.secure)
        assertEquals("Lax", cookie.extensions["SameSite"])
        assertEquals("/", cookie.path)
        assertEquals(0, cookie.maxAge)
    }

    @Test
    fun `logout with malformed cookie returns 200 and clears cookie`() = testApp(cookieSecure = true) {
        val client = jsonClient()
        val response = client.post("/api/auth/logout") {
            header(HttpHeaders.Cookie, "erp_session=not-a-uuid")
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val setCookie = response.headers[HttpHeaders.SetCookie]
        assertNotNull(setCookie)
        val cookie = parseServerSetCookieHeader(setCookie)
        assertEquals("erp_session", cookie.name)
        assertEquals("", cookie.value)
        assertEquals(0, cookie.maxAge)
        assertTrue(cookie.httpOnly)
        assertTrue(cookie.secure)
        assertEquals("Lax", cookie.extensions["SameSite"])
        assertEquals("/", cookie.path)
    }

    @Test
    fun `bootstrap is atomic and second bootstrap fails`() = testApp {
        val client = jsonClient()

        val first = client.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin", "admin123", "Admin User")))
        }
        assertEquals(HttpStatusCode.Created, first.status)

        val second = client.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin2", "admin456", "Other Admin")))
        }
        assertEquals(HttpStatusCode.Unauthorized, second.status)

        val secondLogin = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("admin2", "admin456")))
        }
        assertEquals(HttpStatusCode.Unauthorized, secondLogin.status)

        val firstLogin = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("admin", "admin123")))
        }
        assertEquals(HttpStatusCode.OK, firstLogin.status)
    }

    @Test
    fun `cors allows configured origins and rejects others`() = testApp {
        val client = jsonClient()

        val allowed = client.get("/") {
            header(HttpHeaders.Origin, "http://localhost:8081")
        }
        assertEquals("http://localhost:8081", allowed.headers[HttpHeaders.AccessControlAllowOrigin])
        assertEquals("true", allowed.headers[HttpHeaders.AccessControlAllowCredentials])

        val disallowed = client.get("/") {
            header(HttpHeaders.Origin, "http://evil.example")
        }
        assertNull(disallowed.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun `cors allows json post from configured origin`() = testApp {
        val client = jsonClient()
        client.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin", "admin123", "Admin User")))
        }

        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Origin, "http://localhost:8081")
            setBody(stringify(LoginRequest("admin", "admin123")))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("http://localhost:8081", response.headers[HttpHeaders.AccessControlAllowOrigin])
        assertEquals("true", response.headers[HttpHeaders.AccessControlAllowCredentials])
    }

    @Test
    fun `change password rejects wrong current password`() = testApp {
        val client = jsonClient()
        client.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin", "admin123", "Admin User")))
        }
        client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("admin", "admin123")))
        }

        val response = client.post("/api/auth/password") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ChangePasswordRequest("wrong", "newpass123")))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `change password invalidates old password and enables new password`() = testApp {
        val client = jsonClient()
        client.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin", "admin123", "Admin User")))
        }
        client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("admin", "admin123")))
        }

        val changeResponse = client.post("/api/auth/password") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ChangePasswordRequest("admin123", "newpass123")))
        }
        assertEquals(HttpStatusCode.OK, changeResponse.status)
        val changeBody = changeResponse.bodyAs<ApiResponse<UserDto>>()
        assertTrue(changeBody.success)
        assertEquals("admin", changeBody.data!!.login)

        val oldSessionResponse = client.get("/api/auth/me")
        assertEquals(HttpStatusCode.Unauthorized, oldSessionResponse.status)

        val oldLoginResponse = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("admin", "admin123")))
        }
        assertEquals(HttpStatusCode.Unauthorized, oldLoginResponse.status)

        val newLoginResponse = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("admin", "newpass123")))
        }
        assertEquals(HttpStatusCode.OK, newLoginResponse.status)
    }

    @Test
    fun `login and me return actual permissions`() = testApp {
        val client = jsonClient()
        client.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin", "admin123", "Admin User")))
        }

        val loginResponse = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("admin", "admin123")))
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status)
        val loginBody = loginResponse.bodyAs<ApiResponse<LoginResponse>>()
        assertTrue(loginBody.success)
        assertTrue(loginBody.data!!.user.permissions.contains("*:*"))

        val meResponse = client.get("/api/auth/me")
        assertEquals(HttpStatusCode.OK, meResponse.status)
        val meBody = meResponse.bodyAs<ApiResponse<MeResponse>>()
        assertTrue(meBody.success)
        assertTrue(meBody.data!!.user.permissions.contains("*:*"))
    }

    @Test
    fun `blank login or fullName rejected`() = testApp {
        val client = jsonClient()

        val blankLogin = client.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("", "admin123", "Admin User")))
        }
        assertEquals(HttpStatusCode.BadRequest, blankLogin.status)

        val blankFullName = client.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin", "admin123", "   ")))
        }
        assertEquals(HttpStatusCode.BadRequest, blankFullName.status)

        client.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin", "admin123", "Admin User")))
        }

        val blankLoginAuth = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("   ", "admin123")))
        }
        assertEquals(HttpStatusCode.BadRequest, blankLoginAuth.status)
    }

    @Test
    fun `login throttling blocks after threshold`() = testApp {
        val client = jsonClient()
        client.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin", "admin123", "Admin User")))
        }

        repeat(5) {
            val response = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(stringify(LoginRequest("admin", "wrong")))
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        val blocked = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("admin", "wrong")))
        }
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status)

        val retryAfter = blocked.headers[HttpHeaders.RetryAfter]
        assertNotNull(retryAfter)
        assertTrue(retryAfter.toLong() > 0)
    }

    @Test
    fun `successful login clears failed attempts`() = testApp {
        val client = jsonClient()
        client.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin", "admin123", "Admin User")))
        }

        repeat(4) {
            val response = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(stringify(LoginRequest("admin", "wrong")))
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        val success = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("admin", "admin123")))
        }
        assertEquals(HttpStatusCode.OK, success.status)

        repeat(5) {
            val response = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(stringify(LoginRequest("admin", "wrong")))
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        val blocked = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("admin", "wrong")))
        }
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
    }

    @Test
    fun `deactivate user revokes session`() = testApp {
        val adminClient = jsonClient()
        adminClient.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin", "admin123", "Admin User")))
        }
        adminClient.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("admin", "admin123")))
        }

        val createResponse = adminClient.post("/api/users") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateUserRequest("ivan", "password", "Ivan Ivanov")))
        }
        assertEquals(HttpStatusCode.Created, createResponse.status)
        val created = createResponse.bodyAs<ApiResponse<UserDto>>().data!!

        val ivanClient = jsonClient()
        val ivanLogin = ivanClient.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("ivan", "password")))
        }
        assertEquals(HttpStatusCode.OK, ivanLogin.status)

        val before = ivanClient.get("/api/auth/me")
        assertEquals(HttpStatusCode.OK, before.status)

        val deactivate = adminClient.put("/api/users/${created.id}/active") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SetActiveRequest(false, created.version)))
        }
        assertEquals(HttpStatusCode.OK, deactivate.status)

        val after = ivanClient.get("/api/auth/me")
        assertEquals(HttpStatusCode.Unauthorized, after.status)
    }

    @Test
    fun `role change revokes session`() = testApp {
        val adminClient = jsonClient()
        adminClient.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin", "admin123", "Admin User")))
        }
        adminClient.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("admin", "admin123")))
        }

        val createResponse = adminClient.post("/api/users") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateUserRequest("ivan", "password", "Ivan Ivanov")))
        }
        assertEquals(HttpStatusCode.Created, createResponse.status)
        val created = createResponse.bodyAs<ApiResponse<UserDto>>().data!!

        val ivanClient = jsonClient()
        ivanClient.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("ivan", "password")))
        }

        val before = ivanClient.get("/api/auth/me")
        assertEquals(HttpStatusCode.OK, before.status)

        val update = adminClient.put("/api/users/${created.id}") {
            contentType(ContentType.Application.Json)
            setBody(stringify(UpdateUserRequest(roleIds = emptyList(), version = created.version)))
        }
        assertEquals(HttpStatusCode.OK, update.status)

        val after = ivanClient.get("/api/auth/me")
        assertEquals(HttpStatusCode.Unauthorized, after.status)
    }

    @Test
    fun `user options are available without users read_any and contain only id and fullName`() = testApp {
        val adminClient = jsonClient()
        adminClient.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin", "admin123", "Admin User")))
        }
        adminClient.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("admin", "admin123")))
        }
        val createResponse = adminClient.post("/api/users") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateUserRequest("ivan", "password", "Ivan Ivanov")))
        }
        assertEquals(HttpStatusCode.Created, createResponse.status)

        val ivanClient = jsonClient()
        val loginResponse = ivanClient.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("ivan", "password")))
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status)

        val response = ivanClient.get("/api/users/options")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val raw = response.bodyAsText()
        assertFalse(raw.contains("\"login\""))
        assertFalse(raw.contains("\"email\""))
        assertFalse(raw.contains("\"permissions\""))
        val options = response.bodyAs<ApiResponse<List<UserOptionDto>>>().data!!
        assertEquals(setOf("Admin User", "Ivan Ivanov"), options.map { it.fullName }.toSet())
        assertEquals(options.map { it.fullName }.sorted(), options.map { it.fullName })
    }

    @Test
    fun `user options exclude inactive users`() = testApp {
        val adminClient = jsonClient()
        adminClient.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin", "admin123", "Admin User")))
        }
        adminClient.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("admin", "admin123")))
        }
        val createResponse = adminClient.post("/api/users") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateUserRequest("ivan", "password", "Ivan Ivanov")))
        }
        assertEquals(HttpStatusCode.Created, createResponse.status)
        val created = createResponse.bodyAs<ApiResponse<UserDto>>().data!!

        val before = adminClient.get("/api/users/options")
            .bodyAs<ApiResponse<List<UserOptionDto>>>().data!!
        assertTrue(before.any { it.id == created.id })

        val deactivate = adminClient.put("/api/users/${created.id}/active") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SetActiveRequest(false, created.version)))
        }
        assertEquals(HttpStatusCode.OK, deactivate.status)

        val after = adminClient.get("/api/users/options")
            .bodyAs<ApiResponse<List<UserOptionDto>>>().data!!
        assertTrue(after.none { it.id == created.id })
        assertTrue(after.any { it.fullName == "Admin User" })
    }

    @Test
    fun `user options require authentication`() = testApp {
        val response = jsonClient().get("/api/users/options")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    private fun testApp(
        cookieSecure: Boolean = false,
        allowedOrigins: String = "http://localhost:8080,http://localhost:8081",
        block: suspend ApplicationTestBuilder.() -> Unit
    ) {
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
                          url = "jdbc:postgresql://localhost:5433/erp_test"
                          user = "erp"
                          password = "erp"
                        }
                        session {
                          ttlMinutes = 60
                          cookieName = "erp_session"
                        }
                        security {
                          allowedOrigins = "$allowedOrigins"
                          cookieSecure = $cookieSecure
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

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(HttpCookies)
    }

    private suspend inline fun <reified T> HttpResponse.bodyAs(): T {
        return json.decodeFromString(serializer<T>(), bodyAsText())
    }

    private inline fun <reified T> stringify(value: T): String {
        return json.encodeToString(serializer<T>(), value)
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
        }

        @JvmStatic
        @BeforeClass
        fun setup() {
            TestDatabase.init()
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            TestDatabase.destroy()
        }
    }
}
