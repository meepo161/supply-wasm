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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.example.project.dto.*
import org.jooq.DSLContext
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.koin.ktor.ext.getKoin
import java.nio.file.Files
import java.nio.file.Path
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

    @Test
    fun `task chat accepts three files and idempotent retry reuses all attachments`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val author = createUserWithRole(admin, "attmulti1", "password", "Att Multi One", emptyList())
        val taskId = createTask("T-ATTM1", "IN_PROGRESS", adminUser.id, author.id, adminUser.id)
        addTaskParticipant(taskId, author.id, "ASSIGNEE")
        val client = loginClient("attmulti1", "password")
        val files = listOf("one.txt" to "one", "two.txt" to "two", "three.txt" to "three")

        val first = client.postMessageWithFiles(
            "/api/tasks/$taskId/chat/messages", "three files", "att-multi-key-1", files
        )
        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
        val message = first.bodyAs<ApiResponse<ChatMessageDto>>().data!!
        assertEquals(files.map { it.first }, message.attachments.map { it.fileName })

        val repeat = client.postMessageWithFiles(
            "/api/tasks/$taskId/chat/messages", "three files", "att-multi-key-1", files
        )
        assertEquals(message.id, repeat.bodyAs<ApiResponse<ChatMessageDto>>().data!!.id)
        val history = client.get("/api/tasks/$taskId/chat/messages")
            .bodyAs<ApiResponse<ChatMessagePageDto>>().data!!
        assertEquals(1, history.messages.size)
        assertEquals(3, history.messages.single().attachments.size)
    }

    @Test
    fun `conversation accepts two files and repeat does not duplicate stored files`() = testApp {
        val admin = jsonClient()
        ensureAdmin(admin)
        val author = createUserWithRole(admin, "attmulti2", "password", "Att Multi Two", emptyList())
        val recipient = createUserWithRole(admin, "attmulti3", "password", "Att Multi Three", emptyList())
        val client = loginClient("attmulti2", "password")
        val conversation = client.post("/api/conversations") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateConversationRequest(ConversationKind.DIRECT, memberIds = listOf(recipient.id))))
        }.bodyAs<ApiResponse<ConversationDto>>().data!!
        val path = "/api/conversations/${conversation.id}/messages"
        val before = messageFilePaths()
        val files = listOf("a.txt" to "alpha", "b.txt" to "beta")

        val first = client.postMessageWithFiles(path, "two files", "att-multi-key-2", files)
        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
        assertEquals(2, first.bodyAs<ApiResponse<ConversationMessageDto>>().data!!.attachments.size)
        val afterFirst = messageFilePaths()
        val repeat = client.postMessageWithFiles(path, "two files", "att-multi-key-2", files)
        assertEquals(HttpStatusCode.Created, repeat.status, repeat.bodyAsText())
        assertEquals(afterFirst, messageFilePaths())
        assertEquals(2, (messageFilePaths() - before).size)
        val history = loginClient("attmulti3", "password").get("$path?limit=10")
            .bodyAs<ApiResponse<ConversationMessagePageDto>>().data!!
        assertEquals(2, history.messages.single().attachments.size)
    }

    @Test
    fun `multipart rejects count per-file and aggregate limits without leftovers`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val author = createUserWithRole(admin, "attlimits", "password", "Att Limits", emptyList())
        val taskId = createTask("T-ATTLIM", "IN_PROGRESS", adminUser.id, author.id, adminUser.id)
        addTaskParticipant(taskId, author.id, "ASSIGNEE")
        val client = loginClient("attlimits", "password")
        val path = "/api/tasks/$taskId/chat/messages"
        val before = messageFilePaths()

        val tooMany = client.postMessageWithFiles(
            path, "six", "att-limit-count", (1..6).map { "$it.txt" to "x" }
        )
        assertEquals(HttpStatusCode.BadRequest, tooMany.status, tooMany.bodyAsText())
        val tooLarge = client.postMessageWithFiles(
            path, "large", "att-limit-file", listOf("large.txt" to "x")
        ) { ByteArray(25 * 1024 * 1024 + 1) }
        assertEquals(HttpStatusCode.BadRequest, tooLarge.status)
        val aggregate = client.postMessageWithFiles(
            path, "aggregate", "att-limit-total", listOf("a.txt" to "a", "b.txt" to "b")
        ) { ByteArray(26 * 1024 * 1024) }
        assertEquals(HttpStatusCode.BadRequest, aggregate.status)
        assertEquals(before, messageFilePaths())
        assertEquals(0, client.get(path).bodyAs<ApiResponse<ChatMessagePageDto>>().data!!.messages.size)
    }

    @Test
    fun `validation failure removes every prepared file`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val author = createUserWithRole(admin, "attcleanup", "password", "Att Cleanup", emptyList())
        val taskId = createTask("T-ATTCLEAN", "IN_PROGRESS", adminUser.id, author.id, adminUser.id)
        addTaskParticipant(taskId, author.id, "ASSIGNEE")
        val client = loginClient("attcleanup", "password")
        val before = messageFilePaths()

        val response = client.post("/api/tasks/$taskId/chat/messages") {
            setBody(MultiPartFormDataContent(formData {
                append("content", "cleanup")
                append("idempotencyKey", "att-cleanup-key")
                append("files", "valid".toByteArray(), fileHeaders("valid.txt", "text/plain"))
                append("files", "invalid".toByteArray(), fileHeaders("invalid.txt", "image/png"))
            }))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(before, messageFilePaths())
    }

    @Test
    fun `task transaction rollback deletes both prepared files and all database rows`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val author = createUserWithRole(admin, "attrollback1", "password", "Att Rollback One", emptyList())
        val taskId = createTask("T-ATTRB1", "IN_PROGRESS", adminUser.id, author.id, adminUser.id)
        addTaskParticipant(taskId, author.id, "ASSIGNEE")
        val client = loginClient("attrollback1", "password")
        val before = messageFilePaths()

        withFailingAttachmentTrigger("rollback-task-second.txt") {
            val response = client.postMessageWithFiles(
                "/api/tasks/$taskId/chat/messages",
                "rollback",
                "att-rollback-task",
                listOf("rollback-task-first.txt" to "first", "rollback-task-second.txt" to "second")
            )
            assertEquals(HttpStatusCode.InternalServerError, response.status)
        }

        assertEquals(before, messageFilePaths())
        assertEquals(0, countRows("SELECT COUNT(*) FROM task_chat_messages WHERE task_id = ?", taskId))
        assertEquals(
            0,
            countRows(
                "SELECT COUNT(*) FROM attachments WHERE file_name IN (?, ?)",
                "rollback-task-first.txt", "rollback-task-second.txt"
            )
        )
    }

    @Test
    fun `conversation transaction rollback deletes both prepared files and all database rows`() = testApp {
        val admin = jsonClient()
        ensureAdmin(admin)
        val author = createUserWithRole(admin, "attrollback2", "password", "Att Rollback Two", emptyList())
        val recipient = createUserWithRole(admin, "attrollback3", "password", "Att Rollback Three", emptyList())
        val client = loginClient("attrollback2", "password")
        val conversation = client.post("/api/conversations") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateConversationRequest(ConversationKind.DIRECT, memberIds = listOf(recipient.id))))
        }.bodyAs<ApiResponse<ConversationDto>>().data!!
        val before = messageFilePaths()

        withFailingAttachmentTrigger("rollback-conversation-second.txt") {
            val response = client.postMessageWithFiles(
                "/api/conversations/${conversation.id}/messages",
                "rollback",
                "att-rollback-conversation",
                listOf(
                    "rollback-conversation-first.txt" to "first",
                    "rollback-conversation-second.txt" to "second"
                )
            )
            assertEquals(HttpStatusCode.InternalServerError, response.status)
        }

        assertEquals(before, messageFilePaths())
        assertEquals(
            0,
            countRows("SELECT COUNT(*) FROM conversation_messages WHERE conversation_id = ?", conversation.id)
        )
        assertEquals(
            0,
            countRows(
                "SELECT COUNT(*) FROM attachments WHERE file_name IN (?, ?)",
                "rollback-conversation-first.txt", "rollback-conversation-second.txt"
            )
        )
    }

    @Test
    fun `concurrent multipart retries keep one message and discard losing prepared files`() = testApp {
        val admin = jsonClient()
        ensureAdmin(admin)
        val author = createUserWithRole(admin, "attrace1", "password", "Att Race One", emptyList())
        val recipient = createUserWithRole(admin, "attrace2", "password", "Att Race Two", emptyList())
        val client = loginClient("attrace1", "password")
        val conversation = client.post("/api/conversations") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateConversationRequest(ConversationKind.DIRECT, memberIds = listOf(recipient.id))))
        }.bodyAs<ApiResponse<ConversationDto>>().data!!
        val path = "/api/conversations/${conversation.id}/messages"
        val before = messageFilePaths()
        val files = listOf("race-first.txt" to "first", "race-second.txt" to "second")

        val responses = coroutineScope {
            listOf(
                async { client.postMessageWithFiles(path, "race", "att-race-key", files) },
                async { client.postMessageWithFiles(path, "race", "att-race-key", files) }
            ).map { it.await() }
        }
        val messages = responses.map {
            assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText())
            it.bodyAs<ApiResponse<ConversationMessageDto>>().data!!
        }
        assertEquals(messages[0].id, messages[1].id)
        messages.forEach { assertEquals(2, it.attachments.size) }
        assertEquals(2, (messageFilePaths() - before).size)
        assertEquals(
            1,
            countRows("SELECT COUNT(*) FROM conversation_messages WHERE conversation_id = ?", conversation.id)
        )
        assertEquals(2, countRows("SELECT COUNT(*) FROM attachments WHERE file_name IN (?, ?)", files[0].first, files[1].first))
    }

    @Test
    fun `message send returns stable 429 while history remains readable`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val author = createUserWithRole(admin, "attrate", "password", "Att Rate", emptyList())
        val taskId = createTask("T-ATTRATE", "IN_PROGRESS", adminUser.id, author.id, adminUser.id)
        addTaskParticipant(taskId, author.id, "ASSIGNEE")
        val client = loginClient("attrate", "password")
        val path = "/api/tasks/$taskId/chat/messages"

        repeat(20) { index ->
            val response = client.post(path) {
                contentType(ContentType.Application.Json)
                setBody(stringify(SendChatMessageRequest("message $index", "att-rate-$index")))
            }
            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        }
        val rejected = client.post(path) {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("too many", "att-rate-overflow")))
        }
        assertEquals(HttpStatusCode.TooManyRequests, rejected.status)
        assertEquals("TOO_MANY_REQUESTS", rejected.bodyAs<ApiResponse<ChatMessageDto>>().error?.code)
        assertTrue(rejected.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.let { it > 0 } == true)
        assertEquals(HttpStatusCode.OK, client.get(path).status)
    }

    private suspend fun ApplicationTestBuilder.withFailingAttachmentTrigger(
        rejectedFileName: String,
        block: suspend () -> Unit
    ) {
        val dsl = application.getKoin().get<DSLContext>()
        val escapedName = rejectedFileName.replace("'", "''")
        dsl.execute(
            """
            CREATE OR REPLACE FUNCTION test_fail_second_attachment() RETURNS trigger AS ${'$'}${'$'}
            BEGIN
                IF NEW.file_name = '$escapedName' THEN
                    RAISE EXCEPTION 'forced second attachment failure';
                END IF;
                RETURN NEW;
            END;
            ${'$'}${'$'} LANGUAGE plpgsql
            """.trimIndent()
        )
        dsl.execute("DROP TRIGGER IF EXISTS test_fail_second_attachment ON attachments")
        dsl.execute(
            """
            CREATE TRIGGER test_fail_second_attachment
            BEFORE INSERT ON attachments
            FOR EACH ROW EXECUTE FUNCTION test_fail_second_attachment()
            """.trimIndent()
        )
        try {
            block()
        } finally {
            dsl.execute("DROP TRIGGER IF EXISTS test_fail_second_attachment ON attachments")
            dsl.execute("DROP FUNCTION IF EXISTS test_fail_second_attachment()")
        }
    }

    private fun ApplicationTestBuilder.countRows(sql: String, vararg bindings: Any): Int =
        application.getKoin().get<DSLContext>()
            .fetchSingle(sql, *bindings)
            .get(0, Int::class.java)

    private fun fileHeaders(fileName: String, mime: String): Headers = Headers.build {
        append(HttpHeaders.ContentType, mime)
        append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
    }

    private suspend fun HttpClient.postMessageWithFiles(
        path: String,
        content: String,
        idempotencyKey: String,
        files: List<Pair<String, String>>,
        bytes: (Pair<String, String>) -> ByteArray = { it.second.toByteArray() }
    ): HttpResponse = post(path) {
        setBody(MultiPartFormDataContent(formData {
            append("content", content)
            append("idempotencyKey", idempotencyKey)
            files.forEach { (name, _) ->
                append("files", bytes(name to ""), fileHeaders(name, "text/plain"))
            }
        }))
    }

    private fun messageFilePaths(): Set<Path> {
        val root = Path.of(".local/test-storage/message")
        if (!Files.exists(root)) return emptySet()
        Files.walk(root).use { paths -> return paths.filter(Files::isRegularFile).toList().toSet() }
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
