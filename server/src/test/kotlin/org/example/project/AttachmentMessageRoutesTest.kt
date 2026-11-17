package org.example.project

import com.typesafe.config.ConfigFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.example.project.dto.*
import org.jooq.DSLContext
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.koin.ktor.ext.getKoin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AttachmentMessageRoutesTest {

    @Before
    fun reset() {
        TestDatabase.reset()
    }

    @Test
    fun `task chat message with file is listed and downloadable by participants only`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val author = createUserWithRole(admin, "attauthor1", "password", "Att Author One", emptyList())
        val reader = createUserWithRole(admin, "attreader1", "password", "Att Reader One", emptyList())
        createUserWithRole(admin, "attstranger1", "password", "Att Stranger One", emptyList())
        val taskId = createTask(
            number = "T-ATT1",
            status = "IN_PROGRESS",
            initiatorId = adminUser.id,
            assigneeId = author.id,
            createdBy = adminUser.id
        )
        addTaskParticipant(taskId, author.id, "ASSIGNEE")
        addTaskParticipant(taskId, reader.id, "OBSERVER")

        val authorClient = loginClient("attauthor1", "password")
        val sendResponse = authorClient.postMessageWithFile(
            "/api/tasks/$taskId/chat/messages",
            "Смотри файл", "att-key-1", "note.txt", "task file content".toByteArray()
        )
        assertEquals(HttpStatusCode.Created, sendResponse.status, sendResponse.bodyAsText())
        val message = sendResponse.bodyAs<ApiResponse<ChatMessageDto>>().data!!
        assertEquals(1, message.attachments.size)
        val attachment = message.attachments.single()
        assertEquals("note.txt", attachment.fileName)
        assertEquals("task file content".toByteArray().size.toLong(), attachment.sizeBytes)
        assertEquals(author.id, attachment.uploadedBy)
        assertEquals("Att Author One", attachment.uploadedByName)
        assertTrue(attachment.sha256?.isNotBlank() == true)

        val readerClient = loginClient("attreader1", "password")
        val history = readerClient.get("/api/tasks/$taskId/chat/messages")
            .bodyAs<ApiResponse<ChatMessagePageDto>>().data!!
        assertEquals(1, history.messages.size)
        assertEquals(listOf(attachment.id), history.messages.single().attachments.map { it.id })

        val download = readerClient.get("/api/attachments/${attachment.id}")
        assertEquals(HttpStatusCode.OK, download.status)
        assertEquals("task file content", download.bodyAsText())

        val strangerClient = loginClient("attstranger1", "password")
        val deniedDownload = strangerClient.get("/api/attachments/${attachment.id}")
        assertEquals(HttpStatusCode.Forbidden, deniedDownload.status)
        val deniedSend = strangerClient.postMessageWithFile(
            "/api/tasks/$taskId/chat/messages",
            "hack", "att-key-hack", "note.txt", "x".toByteArray()
        )
        assertEquals(HttpStatusCode.Forbidden, deniedSend.status)
    }

    @Test
    fun `idempotent retry of task chat file message creates no duplicates`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val author = createUserWithRole(admin, "attauthor2", "password", "Att Author Two", emptyList())
        val reader = createUserWithRole(admin, "attreader2", "password", "Att Reader Two", emptyList())
        val taskId = createTask(
            number = "T-ATT2",
            status = "IN_PROGRESS",
            initiatorId = adminUser.id,
            assigneeId = author.id,
            createdBy = adminUser.id
        )
        addTaskParticipant(taskId, author.id, "ASSIGNEE")
        addTaskParticipant(taskId, reader.id, "OBSERVER")

        val authorClient = loginClient("attauthor2", "password")
        val first = authorClient.postMessageWithFile(
            "/api/tasks/$taskId/chat/messages",
            "Файл", "att-key-r1", "spec.pdf", "pdf bytes".toByteArray()
        )
        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
        val firstMessage = first.bodyAs<ApiResponse<ChatMessageDto>>().data!!

        val repeat = authorClient.postMessageWithFile(
            "/api/tasks/$taskId/chat/messages",
            "Файл", "att-key-r1", "spec.pdf", "pdf bytes".toByteArray()
        )
        assertEquals(HttpStatusCode.Created, repeat.status, repeat.bodyAsText())
        val repeatMessage = repeat.bodyAs<ApiResponse<ChatMessageDto>>().data!!
        assertEquals(firstMessage.id, repeatMessage.id)
        assertEquals(1, repeatMessage.attachments.size)

        val history = loginClient("attreader2", "password").get("/api/tasks/$taskId/chat/messages")
            .bodyAs<ApiResponse<ChatMessagePageDto>>().data!!
        assertEquals(1, history.messages.size)
        assertEquals(1, history.messages.single().attachments.size)

        val readerNotifications = loginClient("attreader2", "password").get("/api/notifications")
            .bodyAs<ApiResponse<NotificationPageDto>>().data!!
        assertEquals(1, readerNotifications.notifications.size)
    }

    @Test
    fun `closed task rejects message with file`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val author = createUserWithRole(admin, "attauthor3", "password", "Att Author Three", emptyList())
        val taskId = createTask(
            number = "T-ATT3",
            status = "CLOSED",
            initiatorId = adminUser.id,
            assigneeId = author.id,
            createdBy = adminUser.id
        )
        addTaskParticipant(taskId, author.id, "ASSIGNEE")

        val response = loginClient("attauthor3", "password").postMessageWithFile(
            "/api/tasks/$taskId/chat/messages",
            "Поздно", "att-key-c1", "note.txt", "late".toByteArray()
        )
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `conversation message with file is delivered with access checks`() = testApp {
        val admin = jsonClient()
        ensureAdmin(admin)
        val author = createUserWithRole(admin, "attauthor4", "password", "Att Author Four", emptyList())
        val recipient = createUserWithRole(admin, "attreader4", "password", "Att Reader Four", emptyList())
        createUserWithRole(admin, "attstranger4", "password", "Att Stranger Four", emptyList())

        val authorClient = loginClient("attauthor4", "password")
        val conversation = authorClient.post("/api/conversations") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateConversationRequest(ConversationKind.DIRECT, memberIds = listOf(recipient.id))))
        }.bodyAs<ApiResponse<ConversationDto>>().data!!

        val send = authorClient.postMessageWithFile(
            "/api/conversations/${conversation.id}/messages",
            "Отчёт", "att-key-v1", "report.txt", "conversation file".toByteArray()
        )
        assertEquals(HttpStatusCode.Created, send.status, send.bodyAsText())
        val message = send.bodyAs<ApiResponse<ConversationMessageDto>>().data!!
        assertEquals(1, message.attachments.size)
        val attachment = message.attachments.single()

        val recipientClient = loginClient("attreader4", "password")
        val history = recipientClient.get("/api/conversations/${conversation.id}/messages")
            .bodyAs<ApiResponse<ConversationMessagePageDto>>().data!!
        assertEquals(listOf(attachment.id), history.messages.single().attachments.map { it.id })

        val download = recipientClient.get("/api/attachments/${attachment.id}")
        assertEquals(HttpStatusCode.OK, download.status)
        assertEquals("conversation file", download.bodyAsText())

        val strangerClient = loginClient("attstranger4", "password")
        assertEquals(HttpStatusCode.Forbidden, strangerClient.get("/api/attachments/${attachment.id}").status)
        assertEquals(
            HttpStatusCode.Forbidden,
            strangerClient.delete("/api/attachments/${attachment.id}").status
        )

        val delete = authorClient.delete("/api/attachments/${attachment.id}")
        assertEquals(HttpStatusCode.OK, delete.status, delete.bodyAsText())
        val afterDelete = recipientClient.get("/api/attachments/${attachment.id}")
        assertEquals(HttpStatusCode.NotFound, afterDelete.status)
    }

    private suspend fun HttpClient.postMessageWithFile(
        path: String,
        content: String,
        idempotencyKey: String,
        fileName: String,
        fileBytes: ByteArray
    ): HttpResponse = post(path) {
        setBody(
            MultiPartFormDataContent(
                formData {
                    append("content", content)
                    append("idempotencyKey", idempotencyKey)
                    append("file", fileBytes, Headers.build {
                        val mime = if (fileName.endsWith(".pdf")) "application/pdf" else "text/plain"
                        append(HttpHeaders.ContentType, mime)
                        append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                    })
                }
            )
        )
    }

    private suspend fun ApplicationTestBuilder.ensureAdmin(client: HttpClient): UserDto {
        val bootstrapResponse = client.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin", "admin123", "Admin User")))
        }
        if (bootstrapResponse.status != HttpStatusCode.Created && bootstrapResponse.status != HttpStatusCode.Unauthorized) {
            assertEquals(HttpStatusCode.Created, bootstrapResponse.status, bootstrapResponse.bodyAsText())
        }
        val loginResponse = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("admin", "admin123")))
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status, loginResponse.bodyAsText())
        val meResponse = client.get("/api/auth/me")
        assertEquals(HttpStatusCode.OK, meResponse.status)
        return meResponse.bodyAs<ApiResponse<MeResponse>>().data!!.user
    }

    private suspend fun ApplicationTestBuilder.createUserWithRole(
        adminClient: HttpClient,
        login: String,
        password: String,
        fullName: String,
        permissions: List<CreatePermissionRequest>,
        departmentId: Long? = null
    ): UserDto {
        val bootstrapResponse = adminClient.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin", "admin123", "Admin User")))
        }
        if (bootstrapResponse.status != HttpStatusCode.Created && bootstrapResponse.status != HttpStatusCode.Unauthorized) {
            assertEquals(HttpStatusCode.Created, bootstrapResponse.status, bootstrapResponse.bodyAsText())
        }
        val loginResponse = adminClient.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("admin", "admin123")))
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status, loginResponse.bodyAsText())

        val roleResponse = adminClient.post("/api/roles") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateRoleRequest(login.uppercase(), fullName, permissions = permissions)))
        }
        assertEquals(HttpStatusCode.Created, roleResponse.status, roleResponse.bodyAsText())
        val role = roleResponse.bodyAs<ApiResponse<RoleDto>>().data!!

        val userResponse = adminClient.post("/api/users") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateUserRequest(login, password, fullName, departmentId = departmentId, roleIds = listOf(role.id))))
        }
        assertEquals(HttpStatusCode.Created, userResponse.status, userResponse.bodyAsText())
        return userResponse.bodyAs<ApiResponse<UserDto>>().data!!
    }

    private suspend fun ApplicationTestBuilder.loginClient(login: String, password: String): HttpClient {
        val client = jsonClient()
        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest(login, password)))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return client
    }

    private fun ApplicationTestBuilder.createTask(
        number: String,
        status: String,
        initiatorId: Long,
        assigneeId: Long,
        createdBy: Long
    ): Long {
        val dsl = application.getKoin().get<DSLContext>()
        return dsl.connectionResult { conn ->
            conn.prepareStatement(
                """
                INSERT INTO tasks (number, area, status, type, title, initiator_id, assignee_id, created_by)
                VALUES (?, 'PERSONAL', ?, 'GENERAL', ?, ?, ?, ?)
                RETURNING id
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, number)
                stmt.setString(2, status)
                stmt.setString(3, "Attachment task $number")
                stmt.setLong(4, initiatorId)
                stmt.setLong(5, assigneeId)
                stmt.setLong(6, createdBy)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }
    }

    private fun ApplicationTestBuilder.addTaskParticipant(taskId: Long, userId: Long, role: String) {
        val dsl = application.getKoin().get<DSLContext>()
        dsl.execute(
            "INSERT INTO task_participants (task_id, user_id, role) VALUES (?, ?, ?)",
            taskId,
            userId,
            role
        )
    }

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) {
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
