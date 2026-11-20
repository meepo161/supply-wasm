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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationRoutesTest {

    @Before
    fun reset() {
        TestDatabase.reset()
    }

    @Test
    fun `direct conversation is created once per user pair`() = testApp {
        val admin = jsonClient()
        ensureAdmin(admin)
        val userA = createUserWithRole(admin, "cnvusera", "password", "User Alpha", emptyList())
        val userB = createUserWithRole(admin, "cnvuserb", "password", "User Beta", emptyList())
        val stranger = createUserWithRole(admin, "cnvstranger1", "password", "Stranger One", emptyList())

        val clientA = loginClient("cnvusera", "password")
        val createResponse = clientA.post("/api/conversations") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateConversationRequest(ConversationKind.DIRECT, memberIds = listOf(userB.id))))
        }
        assertEquals(HttpStatusCode.Created, createResponse.status, createResponse.bodyAsText())
        val created = createResponse.bodyAs<ApiResponse<ConversationDto>>().data!!
        assertEquals(ConversationKind.DIRECT, created.kind)
        assertEquals(null, created.title)
        assertEquals(null, created.projectId)
        assertEquals(setOf(userA.id, userB.id), created.members.map { it.userId }.toSet())

        val clientB = loginClient("cnvuserb", "password")
        val repeatResponse = clientB.post("/api/conversations") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateConversationRequest(ConversationKind.DIRECT, memberIds = listOf(userA.id))))
        }
        assertEquals(HttpStatusCode.OK, repeatResponse.status)
        assertEquals(created.id, repeatResponse.bodyAs<ApiResponse<ConversationDto>>().data!!.id)

        val listA = clientA.get("/api/conversations").bodyAs<ApiResponse<List<ConversationDto>>>().data!!
        assertEquals(listOf(created.id), listA.map { it.id })
        val listB = clientB.get("/api/conversations").bodyAs<ApiResponse<List<ConversationDto>>>().data!!
        assertEquals(listOf(created.id), listB.map { it.id })
        val listStranger = loginClient("cnvstranger1", "password")
            .get("/api/conversations").bodyAs<ApiResponse<List<ConversationDto>>>().data!!
        assertEquals(0, listStranger.size)
    }

    @Test
    fun `direct conversation validates members`() = testApp {
        val admin = jsonClient()
        ensureAdmin(admin)
        val userA = createUserWithRole(admin, "cnvuserc", "password", "User Gamma", emptyList())
        val clientA = loginClient("cnvuserc", "password")

        val noMembers = clientA.post("/api/conversations") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateConversationRequest(ConversationKind.DIRECT, memberIds = emptyList())))
        }
        assertEquals(HttpStatusCode.BadRequest, noMembers.status)

        val selfOnly = clientA.post("/api/conversations") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateConversationRequest(ConversationKind.DIRECT, memberIds = listOf(userA.id))))
        }
        assertEquals(HttpStatusCode.BadRequest, selfOnly.status)

        val missing = clientA.post("/api/conversations") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateConversationRequest(ConversationKind.DIRECT, memberIds = listOf(999_999L))))
        }
        assertEquals(HttpStatusCode.BadRequest, missing.status)
    }

    @Test
    fun `group conversation requires title and other active members`() = testApp {
        val admin = jsonClient()
        ensureAdmin(admin)
        val userA = createUserWithRole(admin, "cnvuserd", "password", "User Delta", emptyList())
        val userB = createUserWithRole(admin, "cnvusere", "password", "User Epsilon", emptyList())
        val clientA = loginClient("cnvuserd", "password")

        val noTitle = clientA.post("/api/conversations") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateConversationRequest(ConversationKind.GROUP, memberIds = listOf(userB.id))))
        }
        assertEquals(HttpStatusCode.BadRequest, noTitle.status)

        val noMembers = clientA.post("/api/conversations") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateConversationRequest(ConversationKind.GROUP, title = "Пустая", memberIds = emptyList())))
        }
        assertEquals(HttpStatusCode.BadRequest, noMembers.status)

        val created = clientA.post("/api/conversations") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateConversationRequest(ConversationKind.GROUP, title = "Команда", memberIds = listOf(userB.id))))
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val group = created.bodyAs<ApiResponse<ConversationDto>>().data!!
        assertEquals(ConversationKind.GROUP, group.kind)
        assertEquals("Команда", group.title)
        assertEquals(setOf(userA.id, userB.id), group.members.map { it.userId }.toSet())

        val projectKind = clientA.post("/api/conversations") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateConversationRequest(ConversationKind.PROJECT, memberIds = listOf(userB.id))))
        }
        assertEquals(HttpStatusCode.BadRequest, projectKind.status)
    }

    @Test
    fun `foreign conversation access is denied without data`() = testApp {
        val admin = jsonClient()
        ensureAdmin(admin)
        val userA = createUserWithRole(admin, "cnvuserf", "password", "User Zeta", emptyList())
        val userB = createUserWithRole(admin, "cnvuserg", "password", "User Eta", emptyList())
        createUserWithRole(admin, "cnvstranger2", "password", "Stranger Two", emptyList())

        val clientA = loginClient("cnvuserf", "password")
        val conversation = clientA.post("/api/conversations") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateConversationRequest(ConversationKind.DIRECT, memberIds = listOf(userB.id))))
        }.bodyAs<ApiResponse<ConversationDto>>().data!!

        val stranger = loginClient("cnvstranger2", "password")
        val getResponse = stranger.get("/api/conversations/${conversation.id}")
        assertEquals(HttpStatusCode.Forbidden, getResponse.status)
        assertFalse(getResponse.bodyAsText().contains("User Zeta"))
        val listResponse = stranger.get("/api/conversations/${conversation.id}/messages")
        assertEquals(HttpStatusCode.Forbidden, listResponse.status)
        val sendResponse = stranger.post("/api/conversations/${conversation.id}/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("hack", "cnv-hack-1")))
        }
        assertEquals(HttpStatusCode.Forbidden, sendResponse.status)
    }

    @Test
    fun `message send is idempotent and visible to participants`() = testApp {
        val admin = jsonClient()
        ensureAdmin(admin)
        val userA = createUserWithRole(admin, "cnvuserh", "password", "User Theta", emptyList())
        val userB = createUserWithRole(admin, "cnvuseri", "password", "User Iota", emptyList())

        val clientA = loginClient("cnvuserh", "password")
        val conversation = clientA.post("/api/conversations") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateConversationRequest(ConversationKind.DIRECT, memberIds = listOf(userB.id))))
        }.bodyAs<ApiResponse<ConversationDto>>().data!!

        val first = clientA.post("/api/conversations/${conversation.id}/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Привет", "cnv-key-1")))
        }
        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
        val firstMessage = first.bodyAs<ApiResponse<ConversationMessageDto>>().data!!
        assertEquals(conversation.id, firstMessage.conversationId)
        assertEquals(userA.id, firstMessage.author?.userId)
        assertEquals("User Theta", firstMessage.author?.fullName)

        val repeat = clientA.post("/api/conversations/${conversation.id}/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Привет", "cnv-key-1")))
        }
        assertEquals(HttpStatusCode.Created, repeat.status)
        assertEquals(firstMessage.id, repeat.bodyAs<ApiResponse<ConversationMessageDto>>().data!!.id)

        val clientB = loginClient("cnvuseri", "password")
        val page = clientB.get("/api/conversations/${conversation.id}/messages")
            .bodyAs<ApiResponse<ConversationMessagePageDto>>().data!!
        assertEquals(1, page.messages.size)
        assertEquals("Привет", page.messages.single().content)
    }

    @Test
    fun `project chat ensure is idempotent and restricted to members`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val member = createUserWithRole(admin, "cnvuserj", "password", "User Kappa", emptyList())
        createUserWithRole(admin, "cnvstranger3", "password", "Stranger Three", emptyList())
        val project = createProject(admin, adminUser.id, listOf(member.id))

        val memberClient = loginClient("cnvuserj", "password")
        val ensureResponse = memberClient.post("/api/projects/${project.id}/chat")
        assertEquals(HttpStatusCode.OK, ensureResponse.status, ensureResponse.bodyAsText())
        val chat = ensureResponse.bodyAs<ApiResponse<ConversationDto>>().data!!
        assertEquals(ConversationKind.PROJECT, chat.kind)
        assertEquals(project.id, chat.projectId)

        val repeatEnsure = memberClient.post("/api/projects/${project.id}/chat")
        assertEquals(HttpStatusCode.OK, repeatEnsure.status)
        assertEquals(chat.id, repeatEnsure.bodyAs<ApiResponse<ConversationDto>>().data!!.id)

        val strangerClient = loginClient("cnvstranger3", "password")
        val denied = strangerClient.post("/api/projects/${project.id}/chat")
        assertEquals(HttpStatusCode.Forbidden, denied.status)
        val deniedGet = strangerClient.get("/api/conversations/${chat.id}")
        assertEquals(HttpStatusCode.Forbidden, deniedGet.status)

        val missingProject = memberClient.post("/api/projects/999999/chat")
        assertEquals(HttpStatusCode.NotFound, missingProject.status)

        val memberList = memberClient.get("/api/conversations")
            .bodyAs<ApiResponse<List<ConversationDto>>>().data!!
        assertEquals(listOf(chat.id), memberList.filter { it.kind == ConversationKind.PROJECT }.map { it.id })
        val strangerList = strangerClient.get("/api/conversations")
            .bodyAs<ApiResponse<List<ConversationDto>>>().data!!
        assertEquals(0, strangerList.count { it.kind == ConversationKind.PROJECT })
    }

    @Test
    fun `removed project member loses project chat access`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val member = createUserWithRole(admin, "cnvuserk", "password", "User Lambda", emptyList())
        val project = createProject(admin, adminUser.id, listOf(member.id))

        val memberClient = loginClient("cnvuserk", "password")
        val chat = memberClient.post("/api/projects/${project.id}/chat")
            .bodyAs<ApiResponse<ConversationDto>>().data!!
        val send = memberClient.post("/api/conversations/${chat.id}/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Пока я участник", "cnv-key-pm1")))
        }
        assertEquals(HttpStatusCode.Created, send.status)

        val removeResponse = admin.delete("/api/projects/${project.id}/members/${member.id}")
        assertEquals(HttpStatusCode.OK, removeResponse.status, removeResponse.bodyAsText())

        val readDenied = memberClient.get("/api/conversations/${chat.id}/messages")
        assertEquals(HttpStatusCode.Forbidden, readDenied.status)
        val sendDenied = memberClient.post("/api/conversations/${chat.id}/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Уже нет доступа", "cnv-key-pm2")))
        }
        assertEquals(HttpStatusCode.Forbidden, sendDenied.status)
    }

    @Test
    fun `conversation message creates neutral notifications for recipients only`() = testApp {
        val admin = jsonClient()
        ensureAdmin(admin)
        val author = createUserWithRole(admin, "cnvuserl", "password", "User Mu", emptyList())
        val member1 = createUserWithRole(admin, "cnvuserm", "password", "User Nu", emptyList())
        val member2 = createUserWithRole(admin, "cnvusern", "password", "User Xi", emptyList())
        createUserWithRole(admin, "cnvstranger4", "password", "Stranger Four", emptyList())

        val authorClient = loginClient("cnvuserl", "password")
        val group = authorClient.post("/api/conversations") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateConversationRequest(
                        ConversationKind.GROUP,
                        title = "Три",
                        memberIds = listOf(member1.id, member2.id)
                    )
                )
            )
        }.bodyAs<ApiResponse<ConversationDto>>().data!!

        val send = authorClient.post("/api/conversations/${group.id}/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Секретное содержимое", "cnv-key-n1")))
        }
        assertEquals(HttpStatusCode.Created, send.status)

        for (login in listOf("cnvuserm", "cnvusern")) {
            val page = loginClient(login, "password").get("/api/notifications")
                .bodyAs<ApiResponse<NotificationPageDto>>().data!!
            assertEquals(1, page.notifications.size)
            val notification = page.notifications.single()
            assertEquals("NEW_CONVERSATION_MESSAGE", notification.type)
            assertEquals("CONVERSATION", notification.entityType)
            assertEquals(group.id, notification.entityId)
            assertFalse(notification.isRead)
            assertFalse(notification.title.contains("Секретное содержимое"))
            assertFalse(notification.title.contains("Три"))
        }
        val authorPage = authorClient.get("/api/notifications")
            .bodyAs<ApiResponse<NotificationPageDto>>().data!!
        assertEquals(0, authorPage.notifications.size)
        val strangerPage = loginClient("cnvstranger4", "password").get("/api/notifications")
            .bodyAs<ApiResponse<NotificationPageDto>>().data!!
        assertEquals(0, strangerPage.notifications.size)
    }

    @Test
    fun `project chat message notifies current project members except author`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val author = createUserWithRole(admin, "cnvusero", "password", "User Omicron", emptyList())
        val member = createUserWithRole(admin, "cnvuserp", "password", "User Pi", emptyList())
        val leaving = createUserWithRole(admin, "cnvuserr", "password", "User Rho", emptyList())
        val project = createProject(admin, adminUser.id, listOf(author.id, member.id, leaving.id))

        val authorClient = loginClient("cnvusero", "password")
        val chat = authorClient.post("/api/projects/${project.id}/chat")
            .bodyAs<ApiResponse<ConversationDto>>().data!!

        authorClient.post("/api/conversations/${chat.id}/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Первое", "cnv-key-pn1")))
        }

        val adminPage = loginClient("admin", "admin123").get("/api/notifications")
            .bodyAs<ApiResponse<NotificationPageDto>>().data!!
        assertEquals(1, adminPage.notifications.size)
        assertEquals("NEW_CONVERSATION_MESSAGE", adminPage.notifications.single().type)
        assertEquals(chat.id, adminPage.notifications.single().entityId)
        val memberPage = loginClient("cnvuserp", "password").get("/api/notifications")
            .bodyAs<ApiResponse<NotificationPageDto>>().data!!
        assertEquals(1, memberPage.notifications.size)
        val leavingClient = loginClient("cnvuserr", "password")
        val leavingPage = leavingClient.get("/api/notifications")
            .bodyAs<ApiResponse<NotificationPageDto>>().data!!
        assertEquals(1, leavingPage.notifications.size)
        val authorPage = authorClient.get("/api/notifications")
            .bodyAs<ApiResponse<NotificationPageDto>>().data!!
        assertEquals(0, authorPage.notifications.size)

        val removeResponse = admin.delete("/api/projects/${project.id}/members/${leaving.id}")
        assertEquals(HttpStatusCode.OK, removeResponse.status, removeResponse.bodyAsText())

        authorClient.post("/api/conversations/${chat.id}/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Второе", "cnv-key-pn2")))
        }
        val leavingAfter = leavingClient.get("/api/notifications")
            .bodyAs<ApiResponse<NotificationPageDto>>().data!!
        assertEquals(1, leavingAfter.notifications.size)
    }

    @Test
    fun `project owner keeps chat access without project_members row`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val member = createUserWithRole(admin, "cnvowner1m", "password", "Owner Member", emptyList())
        val project = createProject(admin, adminUser.id, listOf(member.id))

        removeProjectMemberRow(project.id, adminUser.id)

        val ownerClient = loginClient("admin", "admin123")
        val ensure = ownerClient.post("/api/projects/${project.id}/chat")
        assertEquals(HttpStatusCode.OK, ensure.status, ensure.bodyAsText())
        val chat = ensure.bodyAs<ApiResponse<ConversationDto>>().data!!
        assertTrue(chat.members.map { it.userId }.contains(adminUser.id))

        val memberClient = loginClient("cnvowner1m", "password")
        val send = memberClient.post("/api/conversations/${chat.id}/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Для владельца", "cnv-key-own1")))
        }
        assertEquals(HttpStatusCode.Created, send.status, send.bodyAsText())

        val ownerNotifications = ownerClient.get("/api/notifications")
            .bodyAs<ApiResponse<NotificationPageDto>>().data!!
        assertEquals(1, ownerNotifications.notifications.size)
        assertEquals("NEW_CONVERSATION_MESSAGE", ownerNotifications.notifications.single().type)
        assertEquals(chat.id, ownerNotifications.notifications.single().entityId)

        val messages = ownerClient.get("/api/conversations/${chat.id}/messages")
        assertEquals(HttpStatusCode.OK, messages.status)
        val ownerSend = ownerClient.post("/api/conversations/${chat.id}/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Ответ владельца", "cnv-key-own2")))
        }
        assertEquals(HttpStatusCode.Created, ownerSend.status, ownerSend.bodyAsText())

        val ownerList = ownerClient.get("/api/conversations")
            .bodyAs<ApiResponse<List<ConversationDto>>>().data!!
        assertEquals(listOf(chat.id), ownerList.filter { it.kind == ConversationKind.PROJECT }.map { it.id })
    }

    private fun ApplicationTestBuilder.removeProjectMemberRow(projectId: Long, userId: Long) {
        val dsl = application.getKoin().get<DSLContext>()
        dsl.execute(
            "DELETE FROM project_members WHERE project_id = ? AND user_id = ?",
            projectId,
            userId
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
                name = "Chat Project",
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
            TestDatabase.init()
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            TestDatabase.destroy()
        }
    }
}
