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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentRoutesTest {

    @Before
    fun reset() {
        TestDatabase.reset()
        val conn = org.postgresql.ds.PGSimpleDataSource().apply {
            setUrl("jdbc:postgresql://localhost:5433/erp_test")
            user = "erp"
            password = "erp"
        }
        conn.connection.use { c ->
            c.createStatement().execute(
                """
                TRUNCATE TABLE document_download_log, document_version_history,
                  document_links, documents, document_versions, document_files
                RESTART IDENTITY CASCADE
                """.trimIndent()
            )
        }
    }

    @Test
    fun `owner creates document and uploads first version`() = testApp {
        val admin = jsonClient()
        val owner = createUserWithRole(admin, "doc_owner1", "password", "Doc Owner", emptyList())
        val ownerClient = loginClient("doc_owner1", "password")

        val createResponse = ownerClient.post("/api/documents") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateDocumentRequest(name = "ТЗ на изделие", docType = DocumentType.TECHNICAL_SPECIFICATION)))
        }
        assertEquals(HttpStatusCode.Created, createResponse.status, createResponse.bodyAsText())
        val document = createResponse.bodyAs<ApiResponse<DocumentDto>>().data!!
        assertEquals(DocumentStatus.DRAFT, document.status)
        assertEquals(owner.id, document.ownerId)
        assertEquals(owner.id, document.authorId)
        assertTrue(document.number.startsWith("D-"))
        assertNull(document.currentVersionId)

        val uploadResponse = ownerClient.uploadVersionResponse(document.id, "spec.pdf", "v1-bytes".toByteArray(), "application/pdf")
        assertEquals(HttpStatusCode.Created, uploadResponse.status, uploadResponse.bodyAsText())
        val version = uploadResponse.bodyAs<ApiResponse<DocumentVersionDto>>().data!!
        assertEquals(1, version.versionNumber)
        assertEquals(DocumentVersionStatus.DRAFT, version.status)
        assertEquals("spec.pdf", version.fileName)
        assertEquals("application/pdf", version.mimeType)
        assertEquals(64, assertNotNull(version.sha256).length)
        assertNotNull(version.fileId)

        val cardResponse = ownerClient.get("/api/documents/${document.id}")
        assertEquals(HttpStatusCode.OK, cardResponse.status)
        val card = cardResponse.bodyAs<ApiResponse<DocumentDto>>().data!!
        assertEquals(1, card.versions.size)
        assertEquals(version.id, card.versions.single().id)
    }

    @Test
    fun `confidential document does not leak to stranger`() = testApp {
        val admin = jsonClient()
        val owner = createUserWithRole(admin, "doc_owner2", "password", "Doc Owner Two", emptyList())
        createUserWithRole(admin, "doc_stranger", "password", "Doc Stranger", emptyList())
        val ownerClient = loginClient("doc_owner2", "password")

        val document = createDocument(ownerClient, "Секретный договор", DocumentConfidentiality.CONFIDENTIAL)
        val uploadResponse = ownerClient.uploadVersionResponse(document.id, "contract.pdf", "secret".toByteArray(), "application/pdf")
        assertEquals(HttpStatusCode.Created, uploadResponse.status, uploadResponse.bodyAsText())
        val version = uploadResponse.bodyAs<ApiResponse<DocumentVersionDto>>().data!!

        val strangerClient = loginClient("doc_stranger", "password")
        val cardResponse = strangerClient.get("/api/documents/${document.id}")
        assertEquals(HttpStatusCode.NotFound, cardResponse.status)

        val listResponse = strangerClient.get("/api/documents")
        assertEquals(HttpStatusCode.OK, listResponse.status)
        val list = listResponse.bodyAs<ApiResponse<List<DocumentDto>>>().data!!
        assertTrue(list.none { it.id == document.id })

        val downloadResponse = strangerClient.get("/api/document-versions/${version.id}/download")
        assertEquals(HttpStatusCode.NotFound, downloadResponse.status)
    }

    @Test
    fun `download requires independent download right and is audited`() = testApp {
        val admin = jsonClient()
        val owner = createUserWithRole(admin, "doc_owner3", "password", "Doc Owner Three", emptyList())
        val reader = createUserWithRole(admin, "doc_reader", "password", "Doc Reader", emptyList())
        val downloader = createUserWithRole(
            admin,
            "doc_downloader",
            "password",
            "Doc Downloader",
            listOf(CreatePermissionRequest("documents", "download"))
        )
        val ownerClient = loginClient("doc_owner3", "password")
        val content = "public content".toByteArray()

        val document = createDocument(ownerClient, "Публичный отчёт", DocumentConfidentiality.PUBLIC)
        val uploadResponse = ownerClient.uploadVersionResponse(document.id, "report.txt", content, "text/plain")
        assertEquals(HttpStatusCode.Created, uploadResponse.status, uploadResponse.bodyAsText())
        val version = uploadResponse.bodyAs<ApiResponse<DocumentVersionDto>>().data!!

        // Публичная карточка читается, но скачивание требует отдельного права.
        val readerClient = loginClient("doc_reader", "password")
        val cardResponse = readerClient.get("/api/documents/${document.id}")
        assertEquals(HttpStatusCode.OK, cardResponse.status)
        val forbiddenDownload = readerClient.get("/api/document-versions/${version.id}/download")
        assertEquals(HttpStatusCode.Forbidden, forbiddenDownload.status)

        val downloaderClient = loginClient("doc_downloader", "password")
        val downloadResponse = downloaderClient.get("/api/document-versions/${version.id}/download")
        assertEquals(HttpStatusCode.OK, downloadResponse.status)
        assertEquals(ContentType.Text.Plain, downloadResponse.contentType()?.withoutParameters())
        assertTrue(content.contentEquals(downloadResponse.bodyAsBytes()))
        val disposition = assertNotNull(downloadResponse.headers[HttpHeaders.ContentDisposition])
        assertTrue(disposition.startsWith("attachment"))

        assertTrue(downloadLogged(version.id, downloader.id))
        assertTrue(auditLogged("DOCUMENT_DOWNLOAD", "DOCUMENT_VERSION", version.id))
    }

    @Test
    fun `replacement requires reason and old version stays downloadable`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "doc_owner4", "password", "Doc Owner Four", emptyList())
        val ownerClient = loginClient("doc_owner4", "password")
        val document = createDocument(ownerClient, "Чертёж", DocumentConfidentiality.INTERNAL)

        val v1Response = ownerClient.uploadVersionResponse(document.id, "drawing.pdf", "AAA".toByteArray(), "application/pdf")
        assertEquals(HttpStatusCode.Created, v1Response.status, v1Response.bodyAsText())
        val v1 = v1Response.bodyAs<ApiResponse<DocumentVersionDto>>().data!!

        val noReason = ownerClient.uploadVersionResponse(document.id, "drawing.pdf", "BBB".toByteArray(), "application/pdf")
        assertEquals(HttpStatusCode.BadRequest, noReason.status)

        val v2Response = ownerClient.uploadVersionResponse(document.id, "drawing.pdf", "BBB".toByteArray(), "application/pdf", "Исправление размера")
        assertEquals(HttpStatusCode.Created, v2Response.status, v2Response.bodyAsText())
        val v2 = v2Response.bodyAs<ApiResponse<DocumentVersionDto>>().data!!
        assertEquals(2, v2.versionNumber)
        assertEquals("Исправление размера", v2.changeReason)

        val d1 = ownerClient.get("/api/document-versions/${v1.id}/download")
        assertEquals(HttpStatusCode.OK, d1.status)
        assertTrue("AAA".toByteArray().contentEquals(d1.bodyAsBytes()))
        val d2 = ownerClient.get("/api/document-versions/${v2.id}/download")
        assertEquals(HttpStatusCode.OK, d2.status)
        assertTrue("BBB".toByteArray().contentEquals(d2.bodyAsBytes()))
    }

    @Test
    fun `approve requires documents approve permission and optimistic version`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "doc_owner5", "password", "Doc Owner Five", emptyList())
        val approver = createUserWithRole(
            admin,
            "doc_approver",
            "password",
            "Doc Approver",
            listOf(CreatePermissionRequest("documents", "approve"))
        )
        val ownerClient = loginClient("doc_owner5", "password")
        val document = createDocument(ownerClient, "Протокол", DocumentConfidentiality.INTERNAL)
        val v1 = uploadVersion(ownerClient, document.id, "protocol.pdf", "p1".toByteArray())

        val noPermission = ownerClient.post("/api/documents/${document.id}/versions/${v1.id}/approve") {
            contentType(ContentType.Application.Json)
            setBody(stringify(VersionTransitionRequest(version = v1.version)))
        }
        assertEquals(HttpStatusCode.Forbidden, noPermission.status)

        val approverClient = loginClient("doc_approver", "password")
        val stale = approverClient.post("/api/documents/${document.id}/versions/${v1.id}/approve") {
            contentType(ContentType.Application.Json)
            setBody(stringify(VersionTransitionRequest(version = v1.version + 5)))
        }
        assertEquals(HttpStatusCode.Conflict, stale.status)

        val approved = approverClient.post("/api/documents/${document.id}/versions/${v1.id}/approve") {
            contentType(ContentType.Application.Json)
            setBody(stringify(VersionTransitionRequest(version = v1.version)))
        }
        assertEquals(HttpStatusCode.OK, approved.status, approved.bodyAsText())
        val approvedVersion = approved.bodyAs<ApiResponse<DocumentVersionDto>>().data!!
        assertEquals(DocumentVersionStatus.APPROVED, approvedVersion.status)
        assertEquals(approver.id, approvedVersion.approvedBy)
        assertNotNull(approvedVersion.approvedAt)

        // Повторное утверждение запрещено переходом.
        val reapprove = approverClient.post("/api/documents/${document.id}/versions/${v1.id}/approve") {
            contentType(ContentType.Application.Json)
            setBody(stringify(VersionTransitionRequest(version = approvedVersion.version)))
        }
        assertEquals(HttpStatusCode.Conflict, reapprove.status)
    }

    @Test
    fun `activation replaces previous active version atomically`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "doc_owner6", "password", "Doc Owner Six", emptyList())
        createUserWithRole(
            admin,
            "doc_manager",
            "password",
            "Doc Manager",
            listOf(
                CreatePermissionRequest("documents", "approve"),
                CreatePermissionRequest("documents", "activate")
            )
        )
        val ownerClient = loginClient("doc_owner6", "password")
        val managerClient = loginClient("doc_manager", "password")
        val document = createDocument(ownerClient, "Паспорт изделия", DocumentConfidentiality.INTERNAL)

        // DRAFT нельзя активировать.
        val v1 = uploadVersion(ownerClient, document.id, "passport.pdf", "p1".toByteArray())
        val earlyActivate = managerClient.post("/api/documents/${document.id}/versions/${v1.id}/activate") {
            contentType(ContentType.Application.Json)
            setBody(stringify(VersionTransitionRequest(version = v1.version)))
        }
        assertEquals(HttpStatusCode.Conflict, earlyActivate.status)

        val v1Approved = approveVersion(managerClient, document.id, v1)
        val v1Activated = managerClient.post("/api/documents/${document.id}/versions/${v1.id}/activate") {
            contentType(ContentType.Application.Json)
            setBody(stringify(VersionTransitionRequest(version = v1Approved.version)))
        }
        assertEquals(HttpStatusCode.OK, v1Activated.status, v1Activated.bodyAsText())

        var card = getDocument(ownerClient, document.id)
        assertEquals(DocumentStatus.ACTIVE, card.status)
        assertEquals(v1.id, card.currentVersionId)

        val v2 = uploadVersion(ownerClient, document.id, "passport.pdf", "p2".toByteArray(), changeReason = "Новая ревизия")
        val v2Approved = approveVersion(managerClient, document.id, v2)
        val v2Activated = managerClient.post("/api/documents/${document.id}/versions/${v2.id}/activate") {
            contentType(ContentType.Application.Json)
            setBody(stringify(VersionTransitionRequest(version = v2Approved.version, reason = "Замена ревизии")))
        }
        assertEquals(HttpStatusCode.OK, v2Activated.status, v2Activated.bodyAsText())

        card = getDocument(ownerClient, document.id)
        assertEquals(v2.id, card.currentVersionId)
        val byId = card.versions.associateBy { it.id }
        assertEquals(DocumentVersionStatus.REPLACED, byId.getValue(v1.id).status)
        assertEquals(DocumentVersionStatus.ACTIVE, byId.getValue(v2.id).status)
        assertTrue(card.versions.none { it.status == DocumentVersionStatus.ACTIVE && it.id != v2.id })
    }

    @Test
    fun `cancelled draft cannot be approved`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "doc_owner7", "password", "Doc Owner Seven", emptyList())
        createUserWithRole(
            admin,
            "doc_approver7",
            "password",
            "Approver Seven",
            listOf(CreatePermissionRequest("documents", "approve"))
        )
        val ownerClient = loginClient("doc_owner7", "password")
        val document = createDocument(ownerClient, "Служебная записка", DocumentConfidentiality.INTERNAL)
        val v1 = uploadVersion(ownerClient, document.id, "memo.txt", "m1".toByteArray(), "text/plain")

        val cancelled = ownerClient.post("/api/documents/${document.id}/versions/${v1.id}/cancel") {
            contentType(ContentType.Application.Json)
            setBody(stringify(VersionTransitionRequest(version = v1.version, reason = "Отменено автором")))
        }
        assertEquals(HttpStatusCode.OK, cancelled.status, cancelled.bodyAsText())
        assertEquals(DocumentVersionStatus.CANCELLED, cancelled.bodyAs<ApiResponse<DocumentVersionDto>>().data!!.status)

        val approverClient = loginClient("doc_approver7", "password")
        val approveResponse = approverClient.post("/api/documents/${document.id}/versions/${v1.id}/approve") {
            contentType(ContentType.Application.Json)
            setBody(stringify(VersionTransitionRequest(version = v1.version + 1)))
        }
        assertEquals(HttpStatusCode.Conflict, approveResponse.status)
    }

    @Test
    fun `links validate target existence and access`() = testApp {
        val admin = jsonClient()
        val owner = createUserWithRole(
            admin,
            "doc_owner8",
            "password",
            "Doc Owner Eight",
            listOf(CreatePermissionRequest("projects", "create"))
        )
        val other = createUserWithRole(
            admin,
            "doc_other8",
            "password",
            "Other Eight",
            listOf(CreatePermissionRequest("projects", "create"))
        )
        val ownerClient = loginClient("doc_owner8", "password")
        val document = createDocument(ownerClient, "Связанный документ", DocumentConfidentiality.INTERNAL)

        val ownProject = createProject(ownerClient, owner.id)
        val linked = ownerClient.post("/api/documents/${document.id}/links") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateDocumentLinkRequest(DocumentLinkType.PROJECT, ownProject.id)))
        }
        assertEquals(HttpStatusCode.Created, linked.status, linked.bodyAsText())
        val link = linked.bodyAs<ApiResponse<DocumentLinkDto>>().data!!
        assertEquals(DocumentLinkType.PROJECT, link.linkType)
        assertEquals(ownProject.id, link.targetId)

        val duplicate = ownerClient.post("/api/documents/${document.id}/links") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateDocumentLinkRequest(DocumentLinkType.PROJECT, ownProject.id)))
        }
        assertEquals(HttpStatusCode.Conflict, duplicate.status)

        val missing = ownerClient.post("/api/documents/${document.id}/links") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateDocumentLinkRequest(DocumentLinkType.PROJECT, 999999L)))
        }
        assertEquals(HttpStatusCode.NotFound, missing.status)

        // Чужой проект: существует, но доступа нет.
        val otherClient = loginClient("doc_other8", "password")
        val foreignProject = createProject(otherClient, other.id)
        val forbidden = ownerClient.post("/api/documents/${document.id}/links") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateDocumentLinkRequest(DocumentLinkType.PROJECT, foreignProject.id)))
        }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)

        val card = getDocument(ownerClient, document.id)
        assertEquals(1, card.links.size)

        val removed = ownerClient.delete("/api/documents/${document.id}/links/${link.id}")
        assertEquals(HttpStatusCode.OK, removed.status, removed.bodyAsText())
        assertTrue(getDocument(ownerClient, document.id).links.isEmpty())
    }

    @Test
    fun `update card requires manage right and optimistic version`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "doc_owner9", "password", "Doc Owner Nine", emptyList())
        createUserWithRole(admin, "doc_reader9", "password", "Doc Reader Nine", emptyList())
        val ownerClient = loginClient("doc_owner9", "password")
        val document = createDocument(ownerClient, "Исходное название", DocumentConfidentiality.INTERNAL)

        val readerClient = loginClient("doc_reader9", "password")
        val forbidden = readerClient.post("/api/documents/${document.id}/update") {
            contentType(ContentType.Application.Json)
            setBody(stringify(UpdateDocumentRequest(version = document.version, name = "Чужое изменение")))
        }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)

        val stale = ownerClient.post("/api/documents/${document.id}/update") {
            contentType(ContentType.Application.Json)
            setBody(stringify(UpdateDocumentRequest(version = document.version + 5, name = "Конфликт")))
        }
        assertEquals(HttpStatusCode.Conflict, stale.status)

        val updated = ownerClient.post("/api/documents/${document.id}/update") {
            contentType(ContentType.Application.Json)
            setBody(stringify(UpdateDocumentRequest(
                version = document.version,
                name = "Новое название",
                confidentiality = DocumentConfidentiality.CONFIDENTIAL,
                retentionUntil = "2030-01-01"
            )))
        }
        assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())
        val updatedDoc = updated.bodyAs<ApiResponse<DocumentDto>>().data!!
        assertEquals("Новое название", updatedDoc.name)
        assertEquals(DocumentConfidentiality.CONFIDENTIAL, updatedDoc.confidentiality)
        assertEquals("2030-01-01", updatedDoc.retentionUntil)
        assertEquals(document.version + 1, updatedDoc.version)
    }

    @Test
    fun `unsupported extension is rejected for document upload`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "doc_owner10", "password", "Doc Owner Ten", emptyList())
        val ownerClient = loginClient("doc_owner10", "password")
        val document = createDocument(ownerClient, "Проверка типов", DocumentConfidentiality.INTERNAL)

        val response = ownerClient.uploadVersionResponse(document.id, "script.exe", "MZ".toByteArray(), "application/octet-stream")
        assertEquals(HttpStatusCode.BadRequest, response.status)

        val card = getDocument(ownerClient, document.id)
        assertTrue(card.versions.isEmpty())
    }

    private suspend fun HttpClient.uploadVersionResponse(
        documentId: Long,
        fileName: String,
        content: ByteArray,
        contentType: String,
        changeReason: String? = null
    ): HttpResponse = submitFormWithBinaryData(
        url = "/api/documents/$documentId/versions",
        formData = formData {
            append("file", content, Headers.build {
                append(HttpHeaders.ContentType, contentType)
                append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
            })
            if (changeReason != null) {
                append("changeReason", changeReason)
            }
        }
    )

    private suspend fun createDocument(
        client: HttpClient,
        name: String,
        confidentiality: DocumentConfidentiality
    ): DocumentDto {
        val response = client.post("/api/documents") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateDocumentRequest(name = name, docType = DocumentType.OTHER, confidentiality = confidentiality)))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<DocumentDto>>().data!!
    }

    private suspend fun uploadVersion(
        client: HttpClient,
        documentId: Long,
        fileName: String,
        content: ByteArray,
        contentType: String = "application/pdf",
        changeReason: String? = null
    ): DocumentVersionDto {
        val response = client.uploadVersionResponse(documentId, fileName, content, contentType, changeReason)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<DocumentVersionDto>>().data!!
    }

    private suspend fun approveVersion(
        client: HttpClient,
        documentId: Long,
        version: DocumentVersionDto
    ): DocumentVersionDto {
        val response = client.post("/api/documents/$documentId/versions/${version.id}/approve") {
            contentType(ContentType.Application.Json)
            setBody(stringify(VersionTransitionRequest(version = version.version)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<DocumentVersionDto>>().data!!
    }

    private suspend fun getDocument(client: HttpClient, documentId: Long): DocumentDto {
        val response = client.get("/api/documents/$documentId")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<DocumentDto>>().data!!
    }

    private fun ApplicationTestBuilder.downloadLogged(versionId: Long, userId: Long): Boolean {
        val dsl = application.getKoin().get<DSLContext>()
        return dsl.fetchOne(
            "SELECT 1 FROM document_download_log WHERE version_id = ? AND user_id = ?",
            versionId,
            userId
        ) != null
    }

    private fun ApplicationTestBuilder.auditLogged(action: String, entityType: String, entityId: Long): Boolean {
        val dsl = application.getKoin().get<DSLContext>()
        return dsl.fetchOne(
            "SELECT 1 FROM audit_log WHERE action = ? AND entity_type = ? AND entity_id = ?",
            action,
            entityType,
            entityId
        ) != null
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
