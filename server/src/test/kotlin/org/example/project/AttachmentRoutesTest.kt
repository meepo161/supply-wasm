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
import java.sql.Types
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AttachmentRoutesTest {

    @Before
    fun reset() {
        TestDatabase.reset()
    }

    @Test
    fun `anonymous cannot read task attachments`() = testApp {
        val response = jsonClient().get("/api/tasks/1/attachments")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `assignee can upload list and download task attachment`() = testApp {
        val admin = jsonClient()
        val member = createUserWithRole(admin, "att_member", "password", "Attach Member", emptyList())
        val adminUser = ensureAdmin(admin)
        val taskId = createTask(
            number = "T-A01",
            area = "PERSONAL",
            title = "Attachment task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = member.id,
            createdBy = adminUser.id
        )
        addTaskParticipant(taskId, member.id, "ASSIGNEE")

        val memberClient = loginClient("att_member", "password")
        val content = "Hello attachment".toByteArray()

        val uploadResponse = memberClient.uploadFile(
            "/api/tasks/$taskId/attachments",
            "note.txt",
            content,
            "text/plain"
        )
        assertEquals(HttpStatusCode.Created, uploadResponse.status, uploadResponse.bodyAsText())
        val uploaded = uploadResponse.bodyAs<ApiResponse<AttachmentDto>>().data!!
        assertEquals(AttachmentEntityType.TASK, uploaded.entityType)
        assertEquals(taskId, uploaded.entityId)
        assertEquals("note.txt", uploaded.fileName)
        assertEquals(content.size.toLong(), uploaded.sizeBytes)
        assertEquals("text/plain", uploaded.mimeType)
        assertEquals(member.id, uploaded.uploadedBy)
        assertEquals("Attach Member", uploaded.uploadedByName)
        val sha256 = assertNotNull(uploaded.sha256)
        assertEquals(64, sha256.length)

        val listResponse = memberClient.get("/api/tasks/$taskId/attachments")
        assertEquals(HttpStatusCode.OK, listResponse.status)
        val list = listResponse.bodyAs<ApiResponse<List<AttachmentDto>>>().data!!
        assertEquals(1, list.size)
        assertEquals(uploaded.id, list.single().id)

        val downloadResponse = memberClient.get("/api/attachments/${uploaded.id}")
        assertEquals(HttpStatusCode.OK, downloadResponse.status)
        assertEquals(ContentType.Text.Plain, downloadResponse.contentType()?.withoutParameters())
        val disposition = assertNotNull(downloadResponse.headers[HttpHeaders.ContentDisposition])
        assertTrue(disposition.startsWith("attachment"))
        assertTrue(disposition.contains("filename"))
        assertTrue(content.contentEquals(downloadResponse.bodyAsBytes()))
    }

    @Test
    fun `observer has read only access to task attachments`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val observer = createUserWithRole(admin, "att_observer", "password", "Observer", emptyList())
        val taskId = createTask(
            number = "T-A02",
            area = "PERSONAL",
            title = "Observed task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = adminUser.id,
            createdBy = adminUser.id
        )
        addTaskParticipant(taskId, observer.id, "OBSERVER")

        val adminClient = loginClient("admin", "admin123")
        val uploadResponse = adminClient.uploadFile(
            "/api/tasks/$taskId/attachments",
            "spec.pdf",
            "pdf-bytes".toByteArray(),
            "application/pdf"
        )
        assertEquals(HttpStatusCode.Created, uploadResponse.status, uploadResponse.bodyAsText())
        val uploaded = uploadResponse.bodyAs<ApiResponse<AttachmentDto>>().data!!

        val observerClient = loginClient("att_observer", "password")
        val listResponse = observerClient.get("/api/tasks/$taskId/attachments")
        assertEquals(HttpStatusCode.OK, listResponse.status)

        val downloadResponse = observerClient.get("/api/attachments/${uploaded.id}")
        assertEquals(HttpStatusCode.OK, downloadResponse.status)

        val uploadForbidden = observerClient.uploadFile(
            "/api/tasks/$taskId/attachments",
            "evil.txt",
            "nope".toByteArray(),
            "text/plain"
        )
        assertEquals(HttpStatusCode.Forbidden, uploadForbidden.status)

        val deleteForbidden = observerClient.delete("/api/attachments/${uploaded.id}")
        assertEquals(HttpStatusCode.Forbidden, deleteForbidden.status)
    }

    @Test
    fun `stranger cannot list download or delete attachment`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        createUserWithRole(admin, "att_stranger", "password", "Stranger", emptyList())
        val taskId = createTask(
            number = "T-A03",
            area = "PERSONAL",
            title = "Private task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = adminUser.id,
            createdBy = adminUser.id
        )

        val adminClient = loginClient("admin", "admin123")
        val uploadResponse = adminClient.uploadFile(
            "/api/tasks/$taskId/attachments",
            "note.txt",
            "secret".toByteArray(),
            "text/plain"
        )
        assertEquals(HttpStatusCode.Created, uploadResponse.status, uploadResponse.bodyAsText())
        val uploaded = uploadResponse.bodyAs<ApiResponse<AttachmentDto>>().data!!

        val strangerClient = loginClient("att_stranger", "password")
        val listResponse = strangerClient.get("/api/tasks/$taskId/attachments")
        assertEquals(HttpStatusCode.Forbidden, listResponse.status)

        val downloadResponse = strangerClient.get("/api/attachments/${uploaded.id}")
        assertEquals(HttpStatusCode.Forbidden, downloadResponse.status)

        val deleteResponse = strangerClient.delete("/api/attachments/${uploaded.id}")
        assertEquals(HttpStatusCode.Forbidden, deleteResponse.status)
    }

    @Test
    fun `unsupported extension is rejected`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val taskId = createTask(
            number = "T-A04",
            area = "PERSONAL",
            title = "Validation task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = adminUser.id,
            createdBy = adminUser.id
        )

        val adminClient = loginClient("admin", "admin123")
        val response = adminClient.uploadFile(
            "/api/tasks/$taskId/attachments",
            "virus.exe",
            "MZ".toByteArray(),
            "application/octet-stream"
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `mismatched content type is rejected`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val taskId = createTask(
            number = "T-A05",
            area = "PERSONAL",
            title = "Mime task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = adminUser.id,
            createdBy = adminUser.id
        )

        val adminClient = loginClient("admin", "admin123")
        val response = adminClient.uploadFile(
            "/api/tasks/$taskId/attachments",
            "report.pdf",
            "plain text".toByteArray(),
            "text/plain"
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `empty file is rejected`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val taskId = createTask(
            number = "T-A06",
            area = "PERSONAL",
            title = "Empty file task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = adminUser.id,
            createdBy = adminUser.id
        )

        val adminClient = loginClient("admin", "admin123")
        val response = adminClient.uploadFile(
            "/api/tasks/$taskId/attachments",
            "empty.txt",
            ByteArray(0),
            "text/plain"
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `file larger than 25 MB is rejected by actual size`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val taskId = createTask(
            number = "T-A07",
            area = "PERSONAL",
            title = "Big file task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = adminUser.id,
            createdBy = adminUser.id
        )

        val adminClient = loginClient("admin", "admin123")
        val tooLarge = ByteArray(25 * 1024 * 1024 + 1)
        val response = adminClient.uploadFile(
            "/api/tasks/$taskId/attachments",
            "big.zip",
            tooLarge,
            "application/zip"
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)

        val listResponse = adminClient.get("/api/tasks/$taskId/attachments")
        assertEquals(HttpStatusCode.OK, listResponse.status)
        assertEquals(0, listResponse.bodyAs<ApiResponse<List<AttachmentDto>>>().data!!.size)
    }

    @Test
    fun `closed task attachments are read only`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val taskId = createTask(
            number = "T-A08",
            area = "PERSONAL",
            title = "Closing task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = adminUser.id,
            createdBy = adminUser.id
        )

        val adminClient = loginClient("admin", "admin123")
        val uploadResponse = adminClient.uploadFile(
            "/api/tasks/$taskId/attachments",
            "result.docx",
            "docx-bytes".toByteArray(),
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        )
        assertEquals(HttpStatusCode.Created, uploadResponse.status, uploadResponse.bodyAsText())
        val uploaded = uploadResponse.bodyAs<ApiResponse<AttachmentDto>>().data!!

        setTaskStatus(taskId, "CLOSED")

        val uploadForbidden = adminClient.uploadFile(
            "/api/tasks/$taskId/attachments",
            "late.txt",
            "late".toByteArray(),
            "text/plain"
        )
        assertEquals(HttpStatusCode.Forbidden, uploadForbidden.status)

        val deleteForbidden = adminClient.delete("/api/attachments/${uploaded.id}")
        assertEquals(HttpStatusCode.Forbidden, deleteForbidden.status)

        val listResponse = adminClient.get("/api/tasks/$taskId/attachments")
        assertEquals(HttpStatusCode.OK, listResponse.status)
        assertEquals(1, listResponse.bodyAs<ApiResponse<List<AttachmentDto>>>().data!!.size)

        val downloadResponse = adminClient.get("/api/attachments/${uploaded.id}")
        assertEquals(HttpStatusCode.OK, downloadResponse.status)
    }

    @Test
    fun `project owner manages attachments, member has read only`() = testApp {
        val admin = jsonClient()
        val owner = createUserWithRole(
            admin,
            "att_owner",
            "password",
            "Project Owner",
            listOf(CreatePermissionRequest("projects", "create"))
        )
        val member = createUserWithRole(admin, "att_pmember", "password", "Project Member", emptyList())
        val ownerClient = loginClient("att_owner", "password")
        val project = createProject(ownerClient, owner.id, listOf(member.id))

        val uploadResponse = ownerClient.uploadFile(
            "/api/projects/${project.id}/attachments",
            "charter.txt",
            "charter".toByteArray(),
            "text/plain"
        )
        assertEquals(HttpStatusCode.Created, uploadResponse.status, uploadResponse.bodyAsText())
        val uploaded = uploadResponse.bodyAs<ApiResponse<AttachmentDto>>().data!!
        assertEquals(AttachmentEntityType.PROJECT, uploaded.entityType)

        val memberClient = loginClient("att_pmember", "password")
        val listResponse = memberClient.get("/api/projects/${project.id}/attachments")
        assertEquals(HttpStatusCode.OK, listResponse.status)
        assertEquals(1, listResponse.bodyAs<ApiResponse<List<AttachmentDto>>>().data!!.size)

        val downloadResponse = memberClient.get("/api/attachments/${uploaded.id}")
        assertEquals(HttpStatusCode.OK, downloadResponse.status)

        val memberUpload = memberClient.uploadFile(
            "/api/projects/${project.id}/attachments",
            "member.txt",
            "member".toByteArray(),
            "text/plain"
        )
        assertEquals(HttpStatusCode.Forbidden, memberUpload.status)

        val memberDelete = memberClient.delete("/api/attachments/${uploaded.id}")
        assertEquals(HttpStatusCode.Forbidden, memberDelete.status)

        val ownerDelete = ownerClient.delete("/api/attachments/${uploaded.id}")
        assertEquals(HttpStatusCode.OK, ownerDelete.status, ownerDelete.bodyAsText())
    }

    @Test
    fun `delete removes attachment and writes audit`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val taskId = createTask(
            number = "T-A09",
            area = "PERSONAL",
            title = "Delete task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = adminUser.id,
            createdBy = adminUser.id
        )

        val adminClient = loginClient("admin", "admin123")
        val uploadResponse = adminClient.uploadFile(
            "/api/tasks/$taskId/attachments",
            "temp.txt",
            "temp".toByteArray(),
            "text/plain"
        )
        assertEquals(HttpStatusCode.Created, uploadResponse.status, uploadResponse.bodyAsText())
        val uploaded = uploadResponse.bodyAs<ApiResponse<AttachmentDto>>().data!!

        val deleteResponse = adminClient.delete("/api/attachments/${uploaded.id}")
        assertEquals(HttpStatusCode.OK, deleteResponse.status, deleteResponse.bodyAsText())

        val listResponse = adminClient.get("/api/tasks/$taskId/attachments")
        assertEquals(HttpStatusCode.OK, listResponse.status)
        assertEquals(0, listResponse.bodyAs<ApiResponse<List<AttachmentDto>>>().data!!.size)

        val downloadResponse = adminClient.get("/api/attachments/${uploaded.id}")
        assertEquals(HttpStatusCode.NotFound, downloadResponse.status)

        assertTrue(auditLogged("ATTACHMENT_DELETE", uploaded.id))
        assertTrue(auditLogged("ATTACHMENT_UPLOAD", uploaded.id))
    }

    private suspend fun HttpClient.uploadFile(
        url: String,
        fileName: String,
        content: ByteArray,
        contentType: String
    ): HttpResponse = submitFormWithBinaryData(
        url = url,
        formData = formData {
            append("file", content, Headers.build {
                append(HttpHeaders.ContentType, contentType)
                append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
            })
        }
    )

    private fun ApplicationTestBuilder.setTaskStatus(taskId: Long, status: String) {
        val dsl = application.getKoin().get<DSLContext>()
        dsl.update(org.example.project.generated.jooq.tables.Tasks.TASKS)
            .set(org.example.project.generated.jooq.tables.Tasks.TASKS.STATUS, status)
            .where(org.example.project.generated.jooq.tables.Tasks.TASKS.ID.eq(taskId))
            .execute()
    }

    private fun ApplicationTestBuilder.auditLogged(action: String, entityId: Long): Boolean {
        val dsl = application.getKoin().get<DSLContext>()
        return dsl.fetchExists(
            dsl.selectOne()
                .from(org.example.project.generated.jooq.tables.AuditLog.AUDIT_LOG)
                .where(org.example.project.generated.jooq.tables.AuditLog.AUDIT_LOG.ACTION.eq(action))
                .and(org.example.project.generated.jooq.tables.AuditLog.AUDIT_LOG.ENTITY_TYPE.eq("ATTACHMENT"))
                .and(org.example.project.generated.jooq.tables.AuditLog.AUDIT_LOG.ENTITY_ID.eq(entityId))
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

    private suspend fun ApplicationTestBuilder.createProject(
        client: HttpClient,
        ownerId: Long,
        memberIds: List<Long> = emptyList()
    ): ProjectDto {
        val response = client.post("/api/projects") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateProjectRequest(
                name = "Test Project",
                projectType = ProjectType.ORDER_DEVELOPMENT,
                ownerId = ownerId
            )))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val project = response.bodyAs<ApiResponse<ProjectDto>>().data!!
        memberIds.forEach { userId ->
            val addResponse = client.post("/api/projects/${project.id}/members") {
                contentType(ContentType.Application.Json)
                setBody(stringify(AddProjectMemberRequest(userId)))
            }
            assertEquals(HttpStatusCode.OK, addResponse.status, addResponse.bodyAsText())
        }
        return project
    }

    private fun ApplicationTestBuilder.createTask(
        number: String,
        area: String,
        title: String,
        status: String,
        type: String,
        initiatorId: Long,
        assigneeId: Long,
        projectId: Long? = null,
        departmentId: Long? = null,
        createdBy: Long
    ): Long {
        val dsl = application.getKoin().get<DSLContext>()
        return dsl.connectionResult { conn ->
            conn.prepareStatement(
                """
                INSERT INTO tasks (number, area, status, type, title, initiator_id, assignee_id, project_id, department_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, number)
                stmt.setString(2, area)
                stmt.setString(3, status)
                stmt.setString(4, type)
                stmt.setString(5, title)
                stmt.setLong(6, initiatorId)
                stmt.setLong(7, assigneeId)
                if (projectId != null) stmt.setLong(8, projectId) else stmt.setNull(8, Types.BIGINT)
                if (departmentId != null) stmt.setLong(9, departmentId) else stmt.setNull(9, Types.BIGINT)
                stmt.setLong(10, createdBy)
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
            try {
                TestDatabase.init()
            } catch (e: Exception) {
                e.printStackTrace()
                throw e
            }
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            TestDatabase.destroy()
        }
    }
}
