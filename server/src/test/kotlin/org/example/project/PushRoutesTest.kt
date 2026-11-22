package org.example.project

import com.typesafe.config.ConfigFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.serializer
import org.example.project.dto.ApiResponse
import org.example.project.dto.BootstrapRequest
import org.example.project.dto.CreateUserRequest
import org.example.project.dto.LoginRequest
import org.example.project.dto.NotificationPageDto
import org.example.project.dto.SendChatMessageRequest
import org.example.project.dto.UserDto
import org.example.project.plugins.configureHTTP
import org.example.project.plugins.configureStatusPages
import org.example.project.repository.PushSubscription
import org.example.project.route.DeletePushSubscriptionRequest
import org.example.project.route.PushSubscriptionRequest
import org.example.project.service.PushTransport
import org.jooq.DSLContext
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.koin.ktor.ext.getKoin
import java.sql.Types
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PushRoutesTest {

    @Before
    fun reset() {
        TestDatabase.reset()
    }

    @Test
    fun `public key endpoint reports disabled without VAPID`() = testApp(FakeTransport(), vapid = false) {
        val client = loginAdmin()

        val response = client.get("/api/push/public-key")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val data = body.getValue("data").jsonObject
        assertFalse(data.getValue("enabled").jsonPrimitive.content.toBoolean())
        assertTrue(data.getValue("publicKey").jsonPrimitive.content == "null")
    }

    @Test
    fun `subscriptions require authentication and valid browser endpoint`() = testApp(FakeTransport()) {
        val anonymous = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, anonymous.post("/api/push/subscriptions") {
            contentType(ContentType.Application.Json)
            setBody(stringify(PushSubscriptionRequest(VALID_ENDPOINT, "key", "auth")))
        }.status)

        val client = loginAdmin()
        listOf("https://localhost/push", "https://127.0.0.1/push", "https://example.com/push").forEach { endpoint ->
            val response = client.post("/api/push/subscriptions") {
                contentType(ContentType.Application.Json)
                setBody(stringify(PushSubscriptionRequest(endpoint, "key", "auth")))
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        }
        assertEquals(0, subscriptionCount())

        val created = client.post("/api/push/subscriptions") {
            contentType(ContentType.Application.Json)
            setBody(stringify(PushSubscriptionRequest(VALID_ENDPOINT, "key", "auth")))
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        assertEquals(1, subscriptionCount())
    }

    @Test
    fun `unsubscribe is owner only by id and endpoint`() = testApp(FakeTransport()) {
        val owner = loginAdmin()
        val ownerId = owner.get("/api/auth/me").let {
            Json.parseToJsonElement(it.bodyAsText()).jsonObject.getValue("data").jsonObject
                .getValue("user").jsonObject.getValue("id").jsonPrimitive.long
        }
        val otherId = createUser(owner, "other")
        val created = owner.post("/api/push/subscriptions") {
            contentType(ContentType.Application.Json)
            setBody(stringify(PushSubscriptionRequest(VALID_ENDPOINT, "key", "auth")))
        }
        val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject
            .getValue("data").jsonObject.getValue("id").jsonPrimitive.long

        val other = login("other")
        assertEquals(HttpStatusCode.NotFound, other.delete("/api/push/subscriptions") {
            contentType(ContentType.Application.Json)
            setBody(stringify(DeletePushSubscriptionRequest(id = id)))
        }.status)
        assertEquals(HttpStatusCode.NotFound, other.delete("/api/push/subscriptions") {
            contentType(ContentType.Application.Json)
            setBody(stringify(DeletePushSubscriptionRequest(endpoint = VALID_ENDPOINT)))
        }.status)
        assertEquals(1, subscriptionCount())

        assertEquals(HttpStatusCode.OK, owner.delete("/api/push/subscriptions") {
            contentType(ContentType.Application.Json)
            setBody(stringify(DeletePushSubscriptionRequest(endpoint = VALID_ENDPOINT)))
        }.status)
        assertEquals(0, subscriptionCount())
        assertTrue(ownerId != otherId)
    }

    @Test
    fun `notification survives push transport failure`() = testApp(FakeTransport(status = 500)) {
        val admin = loginAdmin()
        val adminId = currentUserId(admin)
        createUser(admin, "member")
        val taskId = createTask(adminId)
        addParticipant(taskId, adminId, "INITIATOR")
        addParticipant(taskId, currentUserId(login("member")), "ASSIGNEE")
        subscribe(admin)

        val member = login("member")
        assertEquals(HttpStatusCode.Created, member.post("/api/tasks/$taskId/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Private message", "push-failure-key")))
        }.status)

        val notifications = awaitNotification(admin)
        assertEquals(1, notifications.notifications.size)
    }

    @Test
    fun `404 from provider removes subscription after notification commit`() = testApp(FakeTransport(status = 404)) { transport ->
        val admin = loginAdmin()
        val adminId = currentUserId(admin)
        createUser(admin, "member")
        val taskId = createTask(adminId)
        addParticipant(taskId, adminId, "INITIATOR")
        addParticipant(taskId, currentUserId(login("member")), "ASSIGNEE")
        subscribe(admin)

        val member = login("member")
        member.post("/api/tasks/$taskId/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Private message", "push-gone-key")))
        }

        withTimeout(5_000) {
            while (transport.sent.isEmpty()) delay(20)
        }
        awaitNotification(admin)
        assertEquals(0, subscriptionCount())
    }

    private class FakeTransport(private val status: Int = 201) : PushTransport {
        val sent = mutableListOf<Pair<PushSubscription, String>>()

        override fun send(subscription: PushSubscription, payload: String): Int {
            sent += subscription to payload
            return status
        }
    }

    private fun testApp(
        transport: FakeTransport,
        vapid: Boolean = true,
        block: suspend ApplicationTestBuilder.(FakeTransport) -> Unit
    ) = testApplication {
        environment {
            config = HoconApplicationConfig(
                ConfigFactory.parseString(
                    """
                    ktor { deployment { port = 0 } }
                    database {
                      url = "${TestDatabase.TEST_URL}"
                      user = "erp"
                      password = "erp"
                    }
                    session { ttlMinutes = 60, cookieName = "erp_session" }
                    security { allowedOrigins = "http://localhost:8080,http://localhost:8081", cookieSecure = false }
                    storage { path = "./.local/test-storage" }
                    push {
                      ${if (vapid) "vapidPublicKey = \"test-public\"" else ""}
                      ${if (vapid) "vapidPrivateKey = \"test-private\"" else ""}
                      ${if (vapid) "vapidSubject = \"mailto:test@example.com\"" else ""}
                    }
                    """.trimIndent()
                )
            )
        }
        application { module(transport) }
        block(transport)
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(HttpCookies) }

    private suspend fun ApplicationTestBuilder.loginAdmin(): HttpClient {
        val client = jsonClient()
        client.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin", "admin123", "Admin")))
        }
        return client.apply {
            val response = post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(stringify(LoginRequest("admin", "admin123")))
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        }
    }

    private suspend fun ApplicationTestBuilder.login(login: String): HttpClient = jsonClient().apply {
        val response = post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest(login, "password")))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    private suspend fun createUser(admin: HttpClient, login: String): Long {
        val response = admin.post("/api/users") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateUserRequest(login, "password", login)))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<UserDto>>().data!!.id
    }

    private suspend fun currentUserId(client: HttpClient): Long {
        val body = Json.parseToJsonElement(client.get("/api/auth/me").bodyAsText()).jsonObject
        return body.getValue("data").jsonObject.getValue("user").jsonObject.getValue("id").jsonPrimitive.long
    }

    private suspend fun subscribe(client: HttpClient) {
        val response = client.post("/api/push/subscriptions") {
            contentType(ContentType.Application.Json)
            setBody(stringify(PushSubscriptionRequest(VALID_ENDPOINT, "key", "auth")))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    }

    private fun ApplicationTestBuilder.createTask(userId: Long): Long =
        application.getKoin().get<DSLContext>().connectionResult { conn ->
            conn.prepareStatement(
                "INSERT INTO tasks (number, area, status, type, title, initiator_id, assignee_id, created_by) " +
                    "VALUES (?, 'PERSONAL', 'IN_PROGRESS', 'GENERAL', ?, ?, ?, ?) RETURNING id"
            ).use { statement ->
                statement.setString(1, "PUSH-${System.nanoTime()}")
                statement.setString(2, "Secret task")
                statement.setLong(3, userId)
                statement.setLong(4, userId)
                statement.setLong(5, userId)
                statement.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
            }
        }

    private fun ApplicationTestBuilder.addParticipant(taskId: Long, userId: Long, role: String) {
        application.getKoin().get<DSLContext>().execute(
            "INSERT INTO task_participants (task_id, user_id, role) VALUES (?, ?, ?)", taskId, userId, role
        )
    }

    private fun ApplicationTestBuilder.subscriptionCount(): Int =
        application.getKoin().get<DSLContext>().fetchOne("SELECT COUNT(*) FROM chat_push_subscriptions")!!
            .get(0, Int::class.java)

    private suspend fun awaitNotification(client: HttpClient): NotificationPageDto = withTimeout(5_000) {
        while (true) {
            val page = client.get("/api/notifications").bodyAs<ApiResponse<NotificationPageDto>>().data!!
            if (page.notifications.isNotEmpty()) return@withTimeout page
            delay(20)
        }
        error("unreachable")
    }

    private suspend inline fun <reified T> HttpResponse.bodyAs(): T = json.decodeFromString(serializer<T>(), bodyAsText())

    private inline fun <reified T> stringify(value: T): String = json.encodeToString(serializer<T>(), value)

    private companion object {
        const val VALID_ENDPOINT = "https://fcm.googleapis.com/fcm/send/test-subscription"
        val json = Json { ignoreUnknownKeys = true }

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
