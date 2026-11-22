package org.example.project

import com.typesafe.config.ConfigFactory
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Сквозные интеграционные сценарии §28.1–28.10 (TST-15..TST-26).
 */
class ErpFullScenariosTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setupDb() {
            TestDatabase.init()
        }

        @JvmStatic
        @AfterClass
        fun teardownDb() {
            TestDatabase.destroy()
        }
    }

    @Before
    fun reset() {
        TestDatabase.reset()
    }

    @Test
    fun `health openapi executive queues and crm flow work end to end`() = testApplication {
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
        val client = createClient { expectSuccess = false }

        // 1. Health & OpenAPI (NFR-01, DOC-10)
        val health = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, health.status)
        assertTrue(health.bodyAsText().contains("\"status\":\"UP\""))

        val openapi = client.get("/api/openapi.json")
        assertEquals(HttpStatusCode.OK, openapi.status)
        assertTrue(openapi.bodyAsText().contains("3.0.3"))

        // 2. Bootstrap & login as admin
        client.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody("""{"login":"admin","password":"admin123","fullName":"Admin User"}""")
        }
        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"login":"admin","password":"admin123"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val cookie = login.headers[HttpHeaders.SetCookie]!!.substringBefore(";")

        // 3. Executive queues & analytics summary (HOME-06..08, ANA-01..06)
        val summary = client.get("/api/analytics/summary") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.OK, summary.status)

        val queues = client.get("/api/analytics/executive-queues") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.OK, queues.status)

        // 4. CRM counterparty & contact (CRM-01, CRM-02)
        val cpRes = client.post("/api/crm/counterparties") {
            header(HttpHeaders.Cookie, cookie)
            contentType(ContentType.Application.Json)
            setBody("""{"name":"АвиаЗаказчик","kind":"CUSTOMER","inn":"7701234567"}""")
        }
        assertEquals(HttpStatusCode.Created, cpRes.status)
        val cpJson = Json.parseToJsonElement(cpRes.bodyAsText()).jsonObject["data"]!!.jsonObject
        val cpId = cpJson["id"]!!.jsonPrimitive.content.toLong()

        val contactRes = client.post("/api/crm/counterparties/$cpId/contacts") {
            header(HttpHeaders.Cookie, cookie)
            contentType(ContentType.Application.Json)
            setBody("""{"fullName":"Петров П.П.","position":"Директор","isPrimary":true}""")
        }
        assertEquals(HttpStatusCode.Created, contactRes.status)

        // 5. Procurement plan (SUP-20, SUP-21) & approval queue (APR-11)
        val planRes = client.get("/api/procurement/plan") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.OK, planRes.status)

        val queueRes = client.get("/api/approvals/my-queue") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.OK, queueRes.status)

        // 6. Audit log (SEC-06)
        val auditRes = client.get("/api/audit?limit=10") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.OK, auditRes.status)
    }
}
