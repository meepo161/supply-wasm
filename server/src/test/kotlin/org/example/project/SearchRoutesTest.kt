package org.example.project

import com.typesafe.config.ConfigFactory
import io.ktor.client.HttpClient
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
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.koin.ktor.ext.getKoin
import java.sql.Types
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchRoutesTest {

    @Before
    fun reset() {
        TestDatabase.reset()
    }

    @Test
    fun `project member finds project and stranger gets no leak`() = testApp {
        val admin = jsonClient()
        val owner = createUserWithRole(
            admin,
            "powner",
            "password",
            "Project Owner",
            listOf(CreatePermissionRequest("projects", "create"))
        )
        val member = createUserWithRole(admin, "pmember", "password", "Project Member", emptyList())
        createUserWithRole(admin, "pstranger", "password", "Stranger User", emptyList())
        val ownerClient = loginClient("powner", "password")
        val project = createProject(ownerClient, owner.id, listOf(member.id), name = "Atlas Complex")

        val memberClient = loginClient("pmember", "password")
        val memberResponse = memberClient.get("/api/search?q=Atlas")
        assertEquals(HttpStatusCode.OK, memberResponse.status)
        val memberResult = memberResponse.bodyAs<ApiResponse<SearchResponse>>().data!!
        val memberProjects = memberResult.groups.single { it.type == SearchResultType.PROJECT }
        assertEquals(1, memberProjects.items.size)
        assertEquals(project.id, memberProjects.items.single().id)
        assertEquals("Atlas Complex", memberProjects.items.single().title)

        val strangerClient = loginClient("pstranger", "password")
        val strangerResponse = strangerClient.get("/api/search?q=Atlas")
        assertEquals(HttpStatusCode.OK, strangerResponse.status)
        val strangerResult = strangerResponse.bodyAs<ApiResponse<SearchResponse>>().data!!
        assertTrue(strangerResult.groups.single { it.type == SearchResultType.PROJECT }.items.isEmpty())
        assertTrue(strangerResult.groups.single { it.type == SearchResultType.TASK }.items.isEmpty())
        assertTrue(strangerResult.groups.none { it.type == SearchResultType.USER })
    }

    @Test
    fun `projects read_any sees any project`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        createUserWithRole(
            admin,
            "readany",
            "password",
            "Read Any",
            listOf(CreatePermissionRequest("projects", "read_any"))
        )
        createProject(admin, adminUser.id, name = "Nebula Restricted")

        val readAnyClient = loginClient("readany", "password")
        val response = readAnyClient.get("/api/search?q=Nebula")
        assertEquals(HttpStatusCode.OK, response.status)
        val result = response.bodyAs<ApiResponse<SearchResponse>>().data!!
        assertEquals(1, result.groups.single { it.type == SearchResultType.PROJECT }.items.size)
    }

    @Test
    fun `task access by direct role and participant, stranger gets nothing`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val assignee = createUserWithRole(admin, "tassignee", "password", "Task Assignee", emptyList())
        val observer = createUserWithRole(admin, "tobserver", "password", "Task Observer", emptyList())
        createUserWithRole(admin, "tstranger", "password", "Task Stranger", emptyList())
        val taskId = createTask(
            number = "T-101",
            area = "PERSONAL",
            title = "Orion personal report",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = assignee.id,
            createdBy = adminUser.id
        )
        addTaskParticipant(taskId, observer.id, "OBSERVER")

        val assigneeClient = loginClient("tassignee", "password")
        val assigneeResult = assigneeClient.get("/api/search?q=Orion")
            .bodyAs<ApiResponse<SearchResponse>>().data!!
        assertEquals(1, assigneeResult.groups.single { it.type == SearchResultType.TASK }.items.size)

        val observerClient = loginClient("tobserver", "password")
        val observerResult = observerClient.get("/api/search?q=Orion")
            .bodyAs<ApiResponse<SearchResponse>>().data!!
        assertEquals(1, observerResult.groups.single { it.type == SearchResultType.TASK }.items.size)

        val strangerClient = loginClient("tstranger", "password")
        val strangerResult = strangerClient.get("/api/search?q=Orion")
            .bodyAs<ApiResponse<SearchResponse>>().data!!
        assertTrue(strangerResult.groups.single { it.type == SearchResultType.TASK }.items.isEmpty())
    }

    @Test
    fun `department employee sees department task, outsider does not`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val dept = createDepartment(admin, "D-SEARCH", "Search Department")
        createUserWithRole(admin, "demployee", "password", "Dept Employee", emptyList(), departmentId = dept.id)
        createUserWithRole(admin, "doutsider", "password", "Dept Outsider", emptyList())
        createTask(
            number = "T-102",
            area = "DEPARTMENT",
            title = "Phoenix department task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = adminUser.id,
            departmentId = dept.id,
            createdBy = adminUser.id
        )

        val employeeClient = loginClient("demployee", "password")
        val employeeResult = employeeClient.get("/api/search?q=Phoenix")
            .bodyAs<ApiResponse<SearchResponse>>().data!!
        assertEquals(1, employeeResult.groups.single { it.type == SearchResultType.TASK }.items.size)

        val outsiderClient = loginClient("doutsider", "password")
        val outsiderResult = outsiderClient.get("/api/search?q=Phoenix")
            .bodyAs<ApiResponse<SearchResponse>>().data!!
        assertTrue(outsiderResult.groups.single { it.type == SearchResultType.TASK }.items.isEmpty())
    }

    @Test
    fun `project member sees project task without participant record`() = testApp {
        val admin = jsonClient()
        val owner = createUserWithRole(
            admin,
            "ptowner",
            "password",
            "Project Task Owner",
            listOf(CreatePermissionRequest("projects", "create"))
        )
        val member = createUserWithRole(admin, "ptmember", "password", "Project Task Member", emptyList())
        val ownerClient = loginClient("ptowner", "password")
        val project = createProject(ownerClient, owner.id, listOf(member.id), name = "Carrier Project")
        createTask(
            number = "T-103",
            area = "PROJECT",
            title = "Mercury project task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = owner.id,
            assigneeId = owner.id,
            projectId = project.id,
            createdBy = owner.id
        )

        val memberClient = loginClient("ptmember", "password")
        val result = memberClient.get("/api/search?q=Mercury")
            .bodyAs<ApiResponse<SearchResponse>>().data!!
        assertEquals(1, result.groups.single { it.type == SearchResultType.TASK }.items.size)
    }

    @Test
    fun `tasks read_any sees any task`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        createUserWithRole(
            admin,
            "taskreadany",
            "password",
            "Task Read Any",
            listOf(CreatePermissionRequest("tasks", "read_any"))
        )
        createTask(
            number = "T-104",
            area = "PERSONAL",
            title = "Vega hidden task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = adminUser.id,
            createdBy = adminUser.id
        )

        val client = loginClient("taskreadany", "password")
        val result = client.get("/api/search?q=Vega")
            .bodyAs<ApiResponse<SearchResponse>>().data!!
        assertEquals(1, result.groups.single { it.type == SearchResultType.TASK }.items.size)
    }

    @Test
    fun `users group requires users read_any`() = testApp {
        val admin = jsonClient()
        ensureAdmin(admin)
        createUserWithRole(admin, "dirtarget", "password", "Directory Target", emptyList())
        createUserWithRole(admin, "plainsearcher", "password", "Plain Searcher", emptyList())

        val plainClient = loginClient("plainsearcher", "password")
        val plainResult = plainClient.get("/api/search?q=dirtarget")
            .bodyAs<ApiResponse<SearchResponse>>().data!!
        assertTrue(plainResult.groups.none { it.type == SearchResultType.USER })

        val adminClient = loginClient("admin", "admin123")
        val adminResult = adminClient.get("/api/search?q=dirtarget")
            .bodyAs<ApiResponse<SearchResponse>>().data!!
        val usersGroup = adminResult.groups.single { it.type == SearchResultType.USER }
        assertEquals(1, usersGroup.items.size)
        assertEquals("Directory Target", usersGroup.items.single().title)
        assertEquals("dirtarget", usersGroup.items.single().subtitle)
    }

    @Test
    fun `grouping and per group limit with hasMore`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        createUserWithRole(
            admin,
            "gammauser",
            "password",
            "Gamma User",
            listOf(
                CreatePermissionRequest("projects", "read_any"),
                CreatePermissionRequest("tasks", "read_any")
            )
        )
        createProject(admin, adminUser.id, name = "Gamma project")
        repeat(3) { index ->
            createTask(
                number = "T-2${index}01",
                area = "PERSONAL",
                title = "Gamma task $index",
                status = "IN_PROGRESS",
                type = "GENERAL",
                initiatorId = adminUser.id,
                assigneeId = adminUser.id,
                createdBy = adminUser.id
            )
        }

        val client = loginClient("gammauser", "password")

        val fullResult = client.get("/api/search?q=Gamma&limit=10")
            .bodyAs<ApiResponse<SearchResponse>>().data!!
        assertEquals(listOf(SearchResultType.PROJECT, SearchResultType.TASK, SearchResultType.MESSAGE), fullResult.groups.map { it.type })
        assertEquals(1, fullResult.groups.single { it.type == SearchResultType.PROJECT }.items.size)
        val fullTasks = fullResult.groups.single { it.type == SearchResultType.TASK }
        assertEquals(3, fullTasks.items.size)
        assertEquals(false, fullTasks.hasMore)

        val limitedResult = client.get("/api/search?q=Gamma&limit=1")
            .bodyAs<ApiResponse<SearchResponse>>().data!!
        val limitedTasks = limitedResult.groups.single { it.type == SearchResultType.TASK }
        assertEquals(1, limitedTasks.items.size)
        assertTrue(limitedTasks.hasMore)
        assertEquals(1, limitedResult.limit)
    }

    @Test
    fun `blank or missing query is rejected`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "qchecker", "password", "Query Checker", emptyList())
        val client = loginClient("qchecker", "password")

        val missingResponse = client.get("/api/search")
        assertEquals(HttpStatusCode.BadRequest, missingResponse.status)

        val blankResponse = client.get("/api/search?q=%20%20")
        assertEquals(HttpStatusCode.BadRequest, blankResponse.status)

        val anonymousResponse = jsonClient().get("/api/search?q=test")
        assertEquals(HttpStatusCode.Unauthorized, anonymousResponse.status)
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

    private suspend fun ApplicationTestBuilder.createDepartment(
        client: HttpClient,
        code: String,
        name: String
    ): DepartmentDto {
        val response = client.post("/api/departments") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateDepartmentRequest(code, name)))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<DepartmentDto>>().data!!
    }

    private suspend fun ApplicationTestBuilder.createProject(
        client: HttpClient,
        ownerId: Long,
        memberIds: List<Long> = emptyList(),
        name: String = "Test Project"
    ): ProjectDto {
        val response = client.post("/api/projects") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateProjectRequest(
                name = name,
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
        description: String? = null,
        projectId: Long? = null,
        departmentId: Long? = null,
        createdBy: Long
    ): Long {
        val dsl = application.getKoin().get<DSLContext>()
        return dsl.connectionResult { conn ->
            conn.prepareStatement(
                """
                INSERT INTO tasks (number, area, status, type, title, description, initiator_id, assignee_id, project_id, department_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, number)
                stmt.setString(2, area)
                stmt.setString(3, status)
                stmt.setString(4, type)
                stmt.setString(5, title)
                if (description != null) stmt.setString(6, description) else stmt.setNull(6, Types.VARCHAR)
                stmt.setLong(7, initiatorId)
                stmt.setLong(8, assigneeId)
                if (projectId != null) stmt.setLong(9, projectId) else stmt.setNull(9, Types.BIGINT)
                if (departmentId != null) stmt.setLong(10, departmentId) else stmt.setNull(10, Types.BIGINT)
                stmt.setLong(11, createdBy)
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
