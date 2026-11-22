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
import org.jooq.DSLContext
import org.koin.ktor.ext.getKoin
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProjectRoutesTest {

    @Before
    fun reset() {
        TestDatabase.reset()
    }

    @Test
    fun `create project draft and open it`() = testApp {
        val admin = jsonClient()
        val opener = createUserWithRole(
            admin,
            "opener",
            "password",
            "Opener User",
            listOf(CreatePermissionRequest("projects", "create"), CreatePermissionRequest("projects", "open"))
        )
        val openerClient = loginClient("opener", "password")

        val createRequest = CreateProjectRequest(
            name = "New Project",
            projectType = ProjectType.ORDER_DEVELOPMENT,
            ownerId = opener.id,
            priority = ProjectPriority.HIGH,
            plannedStartAt = "2026-01-01T00:00:00Z",
            plannedEndAt = "2026-12-31T00:00:00Z"
        )
        val createResponse = openerClient.post("/api/projects") {
            contentType(ContentType.Application.Json)
            setBody(stringify(createRequest))
        }
        assertEquals(HttpStatusCode.Created, createResponse.status)
        val created = createResponse.bodyAs<ApiResponse<ProjectDto>>().data!!
        assertTrue(created.number.startsWith("P-"))
        assertEquals(ProjectStatus.DRAFT, created.status)
        assertEquals(0, created.version)
        assertEquals(opener.id, created.createdBy)
        assertEquals(1, created.members.size)

        val openResponse = openerClient.post("/api/projects/${created.id}/open") {
            contentType(ContentType.Application.Json)
            setBody(stringify(OpenProjectRequest("Contract signed", created.version)))
        }
        assertEquals(HttpStatusCode.OK, openResponse.status)
        val opened = openResponse.bodyAs<ApiResponse<ProjectDto>>().data!!
        assertEquals(ProjectStatus.PLANNED, opened.status)
        assertEquals("Contract signed", opened.openingReason)
        assertEquals(opener.id, opened.openedBy)
        assertNotNull(opened.openedAt)
        assertNotEquals(created.version, opened.version)
    }


    @Test
    fun `open rejects missing required fields`() = testApp {
        val admin = jsonClient()
        val opener = createUserWithRole(
            admin,
            "opener",
            "password",
            "Opener User",
            listOf(CreatePermissionRequest("projects", "create"), CreatePermissionRequest("projects", "open"))
        )
        val openerClient = loginClient("opener", "password")

        val createRequest = CreateProjectRequest(
            name = "Incomplete Project",
            projectType = ProjectType.INTERNAL,
            ownerId = opener.id
        )
        val createResponse = openerClient.post("/api/projects") {
            contentType(ContentType.Application.Json)
            setBody(stringify(createRequest))
        }
        assertEquals(HttpStatusCode.Created, createResponse.status)
        val created = createResponse.bodyAs<ApiResponse<ProjectDto>>().data!!

        val openResponse = openerClient.post("/api/projects/${created.id}/open") {
            contentType(ContentType.Application.Json)
            setBody(stringify(OpenProjectRequest("Reason", created.version)))
        }
        assertEquals(HttpStatusCode.BadRequest, openResponse.status)
    }

    @Test
    fun `non member cannot see project`() = testApp {
        val admin = jsonClient()
        val opener = createUserWithRole(
            admin,
            "opener",
            "password",
            "Opener User",
            listOf(CreatePermissionRequest("projects", "create"))
        )
        createUserWithRole(
            admin,
            "stranger",
            "password",
            "Stranger User",
            emptyList()
        )
        val openerClient = loginClient("opener", "password")
        val strangerClient = loginClient("stranger", "password")

        val project = createProject(openerClient, opener.id)

        val response = strangerClient.get("/api/projects/${project.id}")
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `adding member grants read access`() = testApp {
        val admin = jsonClient()
        val opener = createUserWithRole(
            admin,
            "opener",
            "password",
            "Opener User",
            listOf(CreatePermissionRequest("projects", "create"))
        )
        val member = createUserWithRole(
            admin,
            "member",
            "password",
            "Member User",
            emptyList()
        )
        val openerClient = loginClient("opener", "password")
        val memberClient = loginClient("member", "password")

        val project = createProject(openerClient, opener.id)

        val beforeAdd = memberClient.get("/api/projects/${project.id}")
        assertEquals(HttpStatusCode.Forbidden, beforeAdd.status)

        val addResponse = openerClient.post("/api/projects/${project.id}/members") {
            contentType(ContentType.Application.Json)
            setBody(stringify(AddProjectMemberRequest(member.id)))
        }
        assertEquals(HttpStatusCode.OK, addResponse.status)

        val afterAdd = memberClient.get("/api/projects/${project.id}")
        assertEquals(HttpStatusCode.OK, afterAdd.status)
    }

    @Test
    fun `removing member revokes read access`() = testApp {
        val admin = jsonClient()
        val opener = createUserWithRole(
            admin,
            "opener",
            "password",
            "Opener User",
            listOf(CreatePermissionRequest("projects", "create"))
        )
        val member = createUserWithRole(
            admin,
            "member",
            "password",
            "Member User",
            emptyList()
        )
        val openerClient = loginClient("opener", "password")
        val memberClient = loginClient("member", "password")

        val project = createProject(openerClient, opener.id)
        openerClient.post("/api/projects/${project.id}/members") {
            contentType(ContentType.Application.Json)
            setBody(stringify(AddProjectMemberRequest(member.id)))
        }

        val asMember = memberClient.get("/api/projects/${project.id}")
        assertEquals(HttpStatusCode.OK, asMember.status)

        val removeResponse = openerClient.delete("/api/projects/${project.id}/members/${member.id}")
        assertEquals(HttpStatusCode.OK, removeResponse.status)

        val afterRemove = memberClient.get("/api/projects/${project.id}")
        assertEquals(HttpStatusCode.Forbidden, afterRemove.status)
    }

    @Test
    fun `regular member cannot open project`() = testApp {
        val admin = jsonClient()
        val opener = createUserWithRole(
            admin,
            "opener",
            "password",
            "Opener User",
            listOf(CreatePermissionRequest("projects", "create"))
        )
        val member = createUserWithRole(
            admin,
            "member",
            "password",
            "Member User",
            emptyList()
        )
        val openerClient = loginClient("opener", "password")
        val memberClient = loginClient("member", "password")

        val project = createProject(
            openerClient,
            opener.id,
            plannedStartAt = "2026-01-01T00:00:00Z",
            plannedEndAt = "2026-12-31T00:00:00Z"
        )
        openerClient.post("/api/projects/${project.id}/members") {
            contentType(ContentType.Application.Json)
            setBody(stringify(AddProjectMemberRequest(member.id)))
        }

        val openResponse = memberClient.post("/api/projects/${project.id}/open") {
            contentType(ContentType.Application.Json)
            setBody(stringify(OpenProjectRequest("Reason", project.version)))
        }
        assertEquals(HttpStatusCode.Forbidden, openResponse.status)
    }

    @Test
    fun `close checks version writes audit and forbids new project tasks`() = testApp {
        val admin = jsonClient()
        val closer = createUserWithRole(
            admin,
            "closer",
            "password",
            "Closer User",
            listOf(
                CreatePermissionRequest("projects", "create"),
                CreatePermissionRequest("projects", "open"),
                CreatePermissionRequest("projects", "close"),
                CreatePermissionRequest("tasks", "create")
            )
        )
        val client = loginClient("closer", "password")
        val project = createProject(
            client,
            closer.id,
            plannedStartAt = "2026-01-01T00:00:00Z",
            plannedEndAt = "2026-12-31T00:00:00Z"
        )
        val openedResponse = client.post("/api/projects/${project.id}/open") {
            contentType(ContentType.Application.Json)
            setBody(stringify(OpenProjectRequest("Ready to execute", project.version)))
        }
        assertEquals(HttpStatusCode.OK, openedResponse.status, openedResponse.bodyAsText())
        val opened = openedResponse.bodyAs<ApiResponse<ProjectDto>>().data!!

        val stale = client.post("/api/projects/${project.id}/close") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CloseProjectRequest(version = -1, reason = "Stale version")))
        }
        assertEquals(HttpStatusCode.Conflict, stale.status)

        val closedResponse = client.post("/api/projects/${project.id}/close") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CloseProjectRequest(version = opened.version, reason = "Work completed")))
        }
        assertEquals(HttpStatusCode.OK, closedResponse.status, closedResponse.bodyAsText())
        val closed = closedResponse.bodyAs<ApiResponse<ProjectDto>>().data!!
        assertEquals(ProjectStatus.CLOSED, closed.status)
        assertNotEquals(opened.version, closed.version)
        val audit = application.getKoin().get<DSLContext>().fetchOne(
            "SELECT id FROM audit_log WHERE action = 'CLOSE' AND entity_type = 'PROJECT' AND entity_id = ?",
            project.id
        )
        assertNotNull(audit)

        val newTask = client.post("/api/tasks") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateTaskRequest(
                title = "Work after close",
                area = TaskArea.PROJECT,
                type = TaskWorkType.GENERAL,
                projectId = project.id,
                assigneeId = closer.id
            )))
        }
        assertEquals(HttpStatusCode.Conflict, newTask.status, newTask.bodyAsText())
    }

    @Test
    fun `project with unfinished task cannot be closed`() = testApp {
        val admin = jsonClient()
        val closer = createUserWithRole(
            admin,
            "closer",
            "password",
            "Closer User",
            listOf(
                CreatePermissionRequest("projects", "create"),
                CreatePermissionRequest("projects", "open"),
                CreatePermissionRequest("projects", "close")
            )
        )
        val client = loginClient("closer", "password")
        val project = createProject(
            client,
            closer.id,
            plannedStartAt = "2026-01-01T00:00:00Z",
            plannedEndAt = "2026-12-31T00:00:00Z"
        )
        client.post("/api/projects/${project.id}/open") {
            contentType(ContentType.Application.Json)
            setBody(stringify(OpenProjectRequest("Ready to execute", project.version)))
        }
        application.getKoin().get<DSLContext>().execute(
            """
            INSERT INTO tasks (number, area, status, type, title, initiator_id, project_id, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            "T-CLOSE-1", "PROJECT", "IN_PROGRESS", "GENERAL", "Unfinished task", closer.id, project.id, closer.id
        )

        val response = client.post("/api/projects/${project.id}/close") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CloseProjectRequest(version = 1)))
        }
        assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
        assertTrue(response.bodyAsText().contains("TASK"))
        val reread = client.get("/api/projects/${project.id}")
            .bodyAs<ApiResponse<ProjectDto>>().data!!
        assertEquals(ProjectStatus.PLANNED, reread.status)
    }

    private suspend fun ApplicationTestBuilder.createUserWithRole(
        adminClient: io.ktor.client.HttpClient,
        login: String,
        password: String,
        fullName: String,
        permissions: List<CreatePermissionRequest>
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
            setBody(stringify(CreateUserRequest(login, password, fullName, roleIds = listOf(role.id))))
        }
        assertEquals(HttpStatusCode.Created, userResponse.status, userResponse.bodyAsText())
        return userResponse.bodyAs<ApiResponse<UserDto>>().data!!
    }

    private suspend fun ApplicationTestBuilder.loginClient(login: String, password: String): io.ktor.client.HttpClient {
        val client = jsonClient()
        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest(login, password)))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return client
    }

    private suspend fun ApplicationTestBuilder.createProject(
        client: io.ktor.client.HttpClient,
        ownerId: Long,
        plannedStartAt: String? = null,
        plannedEndAt: String? = null
    ): ProjectDto {
        val request = CreateProjectRequest(
            name = "Test Project",
            projectType = ProjectType.ORDER_DEVELOPMENT,
            ownerId = ownerId,
            plannedStartAt = plannedStartAt,
            plannedEndAt = plannedEndAt
        )
        val response = client.post("/api/projects") {
            contentType(ContentType.Application.Json)
            setBody(stringify(request))
        }
        assertEquals(HttpStatusCode.Created, response.status)
        return response.bodyAs<ApiResponse<ProjectDto>>().data!!
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
