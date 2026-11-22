package org.example.project

import com.typesafe.config.ConfigFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.testing.*
import kotlinx.coroutines.async
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.example.project.dto.*
import org.jooq.DSLContext
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.koin.ktor.ext.getKoin
import java.net.URLEncoder
import kotlin.test.*

class UnifiedChatRoutesTest {

    @Before
    fun reset() {
        TestDatabase.reset()
    }

    @Test
    fun `workspace combines all kinds with metadata previews counters and deterministic order`() = testApp {
        val adminClient = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, adminClient.get("/api/chat/workspace").status)
        val admin = ensureAdmin(adminClient)
        val author = createUser("uwaauthor", "Workspace Author")
        val directId = createConversation("DIRECT", null, null, admin.id, author.id)
        val groupId = createConversation("GROUP", "Unified Group", null, admin.id, author.id)
        val projectId = createProject("Unified Project", admin.id, author.id)
        val projectConversationId = createConversation("PROJECT", null, projectId, admin.id, author.id)
        val taskId = createTask("T-UNIFIED-1", "IN_PROGRESS", author.id, admin.id)
        addTaskParticipant(taskId, admin.id, "ASSIGNEE")

        val directMessageId = insertConversationMessage(
            directId, author.id, "direct latest marker", "u-d-1", "2026-01-01T10:00:00Z"
        )
        insertConversationMessage(groupId, author.id, "group marker", "u-g-1", "2026-01-01T11:00:00Z")
        insertConversationMessage(projectConversationId, author.id, "project marker", "u-p-1", "2026-01-01T12:00:00Z")
        insertTaskMessage(taskId, author.id, "task marker", "u-t-1", "2026-01-01T13:00:00Z")

        val page = adminClient.get("/api/chat/workspace")
            .bodyAs<ApiResponse<ConversationListPageDto>>().data!!
        assertEquals(4, page.items.size)
        assertEquals(listOf("TASK", "PROJECT", "GROUP", "DIRECT"), page.items.map { it.kind.name })
        assertEquals(page.items.map { "${it.kind}:${it.id}" }, page.items.map { "${it.kind}:${it.id}" }.distinct())
        val direct = page.items.single { it.kind == ConversationKind.DIRECT }
        assertEquals(directId, direct.id)
        assertEquals("Workspace Author", direct.title)
        assertEquals("direct latest marker", direct.lastMessagePreview)
        assertEquals("Workspace Author", direct.lastMessageAuthor?.fullName)
        assertTrue(direct.lastMessageAt?.startsWith("2026-01-01T10:00") == true)
        assertEquals(1, direct.unreadCount)
        assertTrue(direct.canWrite)
        assertEquals(setOf(admin.id, author.id), direct.members.map { it.userId }.toSet())
        val project = page.items.single { it.kind == ConversationKind.PROJECT }
        assertEquals(projectId, project.projectId)
        assertEquals("Unified Project", project.title)
        assertEquals(setOf(admin.id, author.id), project.members.map { it.userId }.toSet())
        val task = page.items.single { it.kind == ConversationKind.TASK }
        assertEquals(taskId, task.taskId)
        assertTrue(task.title.orEmpty().contains("T-UNIFIED-1"))
        assertTrue(task.canWrite)
        assertEquals(1, task.unreadCount)

        insertConversationMention(directMessageId, admin.id)
        val mentioned = adminClient.get("/api/chat/workspace?filter=MENTIONS")
            .bodyAs<ApiResponse<ConversationListPageDto>>().data!!.items.single()
        assertEquals(directId, mentioned.id)
        assertEquals(1, mentioned.mentionCount)
        val unread = adminClient.get("/api/chat/workspace?filter=UNREAD")
            .bodyAs<ApiResponse<ConversationListPageDto>>().data!!.items
        assertEquals(4, unread.size)
        assertEquals(listOf("DIRECT"), adminClient.get("/api/chat/workspace?kind=DIRECT")
            .bodyAs<ApiResponse<ConversationListPageDto>>().data!!.items.map { it.kind.name })
        assertEquals(listOf(groupId), workspace(adminClient, "query=Unified Group").items.map { it.id })
        assertEquals(
            setOf(directId, groupId, projectConversationId, taskId),
            workspace(adminClient, "query=Workspace Author").items.map { it.id }.toSet()
        )
        assertEquals(listOf(taskId), workspace(adminClient, "query=task marker").items.map { it.id })
        assertTrue(workspace(adminClient, "query=not-present-anywhere").items.isEmpty())
        assertEquals(HttpStatusCode.OK, adminClient.post("/api/chat/workspace/DIRECT/$directId/state") {
            jsonBody(ConversationDialogStateRequest(pinned = true))
        }.status)
        assertEquals(directId, workspace(adminClient).items.first().id)
    }

    @Test
    fun `workspace cursor pages have no duplicates or omissions and limits are bounded`() = testApp {
        val adminClient = jsonClient()
        val admin = ensureAdmin(adminClient)
        val author = createUser("uwcauthor", "Cursor Author")
        val ids = (1..55).map { index ->
            createConversation("GROUP", "Cursor $index", null, admin.id, author.id).also { id ->
                insertConversationMessage(id, author.id, "same activity", "u-c-$index", "2026-01-02T10:00:00Z")
            }
        }
        val first = adminClient.get("/api/chat/workspace?limit=2")
            .bodyAs<ApiResponse<ConversationListPageDto>>().data!!
        assertEquals(ids.sortedDescending().take(2), first.items.map { it.id })
        assertTrue(first.hasMore)
        assertNotNull(first.nextCursor)
        val collected = first.items.toMutableList()
        var cursor = first.nextCursor
        while (cursor != null) {
            val page = adminClient.get("/api/chat/workspace?limit=2&cursor=${URLEncoder.encode(cursor, "UTF-8")}")
                .bodyAs<ApiResponse<ConversationListPageDto>>().data!!
            collected += page.items
            cursor = page.nextCursor
            assertEquals(page.items.isNotEmpty(), page.hasMore || cursor == null)
        }
        assertEquals(ids.sortedDescending(), collected.map { it.id })
        assertEquals(collected.size, collected.distinctBy { it.kind to it.id }.size)
        assertEquals(HttpStatusCode.BadRequest, adminClient.get("/api/chat/workspace?limit=0").status)
        assertEquals(HttpStatusCode.BadRequest, adminClient.get("/api/chat/workspace?cursor=broken").status)

        val defaultPage = adminClient.get("/api/chat/workspace")
            .bodyAs<ApiResponse<ConversationListPageDto>>().data!!
        assertEquals(50, defaultPage.items.size)
        val bounded = adminClient.get("/api/chat/workspace?limit=1000")
            .bodyAs<ApiResponse<ConversationListPageDto>>().data!!
        assertEquals(55, bounded.items.size)
    }

    @Test
    fun `dialog state is per user archive is opt in and no op creates no audit or event`() = testApp {
        val adminClient = jsonClient()
        val admin = ensureAdmin(adminClient)
        val other = createUser("uwsother", "State Other")
        val groupId = createConversation("GROUP", "State Group", null, admin.id, other.id)
        val taskId = createTask("T-STATE-1", "IN_PROGRESS", admin.id, admin.id)
        addTaskParticipant(taskId, admin.id, "INITIATOR")

        val changed = adminClient.post("/api/chat/workspace/GROUP/$groupId/state") {
            jsonBody(ConversationDialogStateRequest(pinned = true, muted = true, archived = true))
        }
        assertEquals(HttpStatusCode.OK, changed.status, changed.bodyAsText())
        val state = changed.bodyAs<ApiResponse<ConversationListItemDto>>().data!!
        assertTrue(state.isPinned && state.isMuted && state.isArchived)
        assertTrue(workspace(adminClient).items.none { it.kind == ConversationKind.GROUP && it.id == groupId })
        assertEquals(groupId, workspace(adminClient, "filter=ARCHIVED").items.single { it.kind == ConversationKind.GROUP }.id)
        val otherItem = workspace(login("uwsother"), "filter=ARCHIVED").items
        assertTrue(otherItem.isEmpty())

        val auditCount = count("SELECT * FROM audit_log")
        val eventCount = count("SELECT * FROM conversation_event_log")
        val noOp = adminClient.post("/api/chat/workspace/GROUP/$groupId/state") {
            jsonBody(ConversationDialogStateRequest(pinned = true, muted = true, archived = true))
        }
        assertEquals(HttpStatusCode.OK, noOp.status)
        assertEquals(auditCount, count("SELECT * FROM audit_log"))
        assertEquals(eventCount, count("SELECT * FROM conversation_event_log"))

        val pin = adminClient.post("/api/chat/workspace/TASK/$taskId/state") {
            jsonBody(ConversationDialogStateRequest(pinned = true))
        }
        assertEquals(HttpStatusCode.OK, pin.status, pin.bodyAsText())
        assertTrue(pin.bodyAs<ApiResponse<ConversationListItemDto>>().data!!.isPinned)
        assertEquals(1, count("SELECT * FROM task_chat_event_log WHERE task_id = ? AND category = 'PIN'", taskId))
        val taskAudit = count("SELECT * FROM audit_log")
        assertEquals(HttpStatusCode.OK, adminClient.post("/api/chat/workspace/TASK/$taskId/state") {
            jsonBody(ConversationDialogStateRequest(pinned = true))
        }.status)
        assertEquals(taskAudit, count("SELECT * FROM audit_log"))
        assertEquals(1, count("SELECT * FROM task_chat_event_log WHERE task_id = ? AND category = 'PIN'", taskId))
    }

    @Test
    fun `mixed workspace pagination is stable across pin phase and equal activity ties`() = testApp {
        val adminClient = jsonClient()
        val admin = ensureAdmin(adminClient)
        val author = createUser("uwmpauthor", "Mixed Page Author")
        val directId = createConversation("DIRECT", null, null, admin.id, author.id)
        val groupId = createConversation("GROUP", "Mixed Page Group", null, admin.id, author.id)
        val projectId = createProject("Mixed Page Project", admin.id, author.id)
        val projectChatId = createConversation("PROJECT", null, projectId, admin.id, author.id)
        val taskId = createTask("T-MIXED-PAGE", "IN_PROGRESS", author.id, admin.id)
        addTaskParticipant(taskId, admin.id, "ASSIGNEE")
        val timestamp = "2026-02-01T10:00:00Z"
        insertConversationMessage(directId, author.id, "direct", "u-m-d", timestamp)
        insertConversationMessage(groupId, author.id, "group", "u-m-g", timestamp)
        insertConversationMessage(projectChatId, author.id, "project", "u-m-p", timestamp)
        insertTaskMessage(taskId, author.id, "task", "u-m-t", timestamp)
        assertEquals(HttpStatusCode.OK, adminClient.post("/api/chat/workspace/GROUP/$groupId/state") {
            jsonBody(ConversationDialogStateRequest(pinned = true))
        }.status)

        val expected = listOf(
            ConversationKind.GROUP to groupId,
            ConversationKind.TASK to taskId,
            ConversationKind.PROJECT to projectChatId,
            ConversationKind.DIRECT to directId
        )
        val pinnedPage = workspace(adminClient, "limit=1")
        assertEquals(listOf(expected[0]), pinnedPage.items.map { it.kind to it.id })
        val firstUnpinnedPage = workspace(
            adminClient,
            "limit=1&cursor=${URLEncoder.encode(requireNotNull(pinnedPage.nextCursor), "UTF-8")}"
        )
        assertEquals(listOf(expected[1]), firstUnpinnedPage.items.map { it.kind to it.id })

        listOf(1, 2).forEach { pageSize ->
            val collected = mutableListOf<Pair<ConversationKind, Long>>()
            var cursor: String? = null
            do {
                val cursorQuery = cursor?.let { "&cursor=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
                val page = workspace(adminClient, "limit=$pageSize$cursorQuery")
                assertTrue(page.items.size <= pageSize)
                collected += page.items.map { it.kind to it.id }
                cursor = page.nextCursor
                assertEquals(cursor != null, page.hasMore)
            } while (cursor != null)
            assertEquals(expected, collected, "page size $pageSize")
            assertEquals(expected.size, collected.distinct().size)
            assertEquals(expected.toSet(), collected.toSet())
        }
    }

    @Test
    fun `task mute archive is per user and observer cannot pin without write`() = testApp {
        val adminClient = jsonClient()
        val admin = ensureAdmin(adminClient)
        val observer = createUser("uwsobserver", "State Observer")
        val taskId = createTask("T-OBSERVER-STATE", "IN_PROGRESS", admin.id, admin.id)
        addTaskParticipant(taskId, admin.id, "INITIATOR")
        addTaskParticipant(taskId, observer.id, "OBSERVER")
        val observerClient = login("uwsobserver")
        assertFalse(workspace(observerClient).items.single { it.taskId == taskId }.canWrite)
        val auditBeforePin = count("SELECT * FROM audit_log")
        assertEquals(HttpStatusCode.Forbidden, observerClient.post("/api/chat/workspace/TASK/$taskId/state") {
            jsonBody(ConversationDialogStateRequest(pinned = true))
        }.status)
        assertEquals(auditBeforePin, count("SELECT * FROM audit_log"))

        val changed = observerClient.post("/api/chat/workspace/TASK/$taskId/state") {
            jsonBody(ConversationDialogStateRequest(muted = true, archived = true))
        }
        assertEquals(HttpStatusCode.OK, changed.status, changed.bodyAsText())
        val state = changed.bodyAs<ApiResponse<ConversationListItemDto>>().data!!
        assertTrue(state.isMuted)
        assertTrue(state.isArchived)
        assertTrue(workspace(observerClient).items.none { it.taskId == taskId })
        assertEquals(taskId, workspace(observerClient, "filter=ARCHIVED").items.single().taskId)
        assertTrue(workspace(adminClient).items.any { it.taskId == taskId })
        assertTrue(workspace(adminClient, "filter=ARCHIVED").items.none { it.taskId == taskId })
        assertEquals(1, count(
            "SELECT * FROM audit_log WHERE action = 'CHAT_DIALOG_STATE' AND entity_id = ?",
            taskId
        ))
        assertEquals(0, count("SELECT * FROM task_chat_event_log WHERE task_id = ?", taskId))

        val auditBeforeNoOp = count("SELECT * FROM audit_log")
        assertEquals(HttpStatusCode.OK, observerClient.post("/api/chat/workspace/TASK/$taskId/state") {
            jsonBody(ConversationDialogStateRequest(muted = true, archived = true))
        }.status)
        assertEquals(auditBeforeNoOp, count("SELECT * FROM audit_log"))
        assertEquals(0, count("SELECT * FROM task_chat_event_log WHERE task_id = ?", taskId))
    }

    @Test
    fun `task revoked preview is neutral and counters include only incoming after read cursor`() = testApp {
        val adminClient = jsonClient()
        val admin = ensureAdmin(adminClient)
        val author = createUser("uwtpauthor", "Task Preview Author")
        val taskId = createTask("T-REVOKED-PREVIEW", "IN_PROGRESS", author.id, admin.id)
        addTaskParticipant(taskId, admin.id, "ASSIGNEE")
        val first = insertTaskMessage(taskId, author.id, "first incoming", "u-t-v-1", "2026-01-01T10:00:00Z")
        insertTaskMessage(taskId, admin.id, "own does not count", "u-t-v-2", "2026-01-01T11:00:00Z")
        val second = insertTaskMessage(
            taskId, author.id, "secret task revoked preview", "u-t-v-3", "2026-01-01T12:00:00Z"
        )
        dsl().execute("INSERT INTO task_chat_mentions (message_id, mentioned_user_id) VALUES (?, ?)", second, admin.id)
        dsl().execute(
            "INSERT INTO task_chat_read_states (task_id, user_id, last_read_message_id) VALUES (?, ?, ?)",
            taskId, admin.id, first
        )
        dsl().execute(
            "UPDATE task_chat_messages SET revoked_at = NOW(), content = 'Сообщение отозвано' WHERE id = ?",
            second
        )
        val item = workspace(adminClient).items.single { it.taskId == taskId }
        assertEquals("Сообщение отозвано", item.lastMessagePreview)
        assertEquals(1, item.unreadCount)
        assertEquals(1, item.mentionCount)
        assertFalse(adminClient.get("/api/chat/workspace").bodyAsText().contains("secret task revoked preview"))
    }

    @Test
    fun `project owner without membership cannot see project task chat but keeps project chat`() = testApp {
        val adminClient = jsonClient()
        ensureAdmin(adminClient)
        val owner = createUser("uwpoowner", "Project Owner")
        val author = createUser("uwpoauthor", "Project Task Author")
        val projectId = createProject("Owner Task Visibility", owner.id, author.id)
        val projectChatId = createConversation("PROJECT", null, projectId, owner.id, author.id)
        val taskId = createTask("T-OWNER-NO-MEMBERSHIP", "IN_PROGRESS", author.id, author.id)
        addTaskParticipant(taskId, author.id, "ASSIGNEE")
        dsl().execute("UPDATE tasks SET area = 'PROJECT', project_id = ? WHERE id = ?", projectId, taskId)
        insertTaskMessage(taskId, author.id, "owner must not see task preview", "u-owner-task", null)

        val ownerClient = login("uwpoowner")
        val body = ownerClient.get("/api/chat/workspace").bodyAsText()
        val page = json.decodeFromString(
            serializer<ApiResponse<ConversationListPageDto>>(), body
        ).data!!
        assertTrue(page.items.any { it.kind == ConversationKind.PROJECT && it.id == projectChatId })
        assertTrue(page.items.none { it.kind == ConversationKind.TASK && it.taskId == taskId })
        assertFalse(body.contains("Owner Task Visibility") && body.contains("T-OWNER-NO-MEMBERSHIP"))
        assertFalse(body.contains("owner must not see task preview"))
        assertNotNull(owner)
    }

    @Test
    fun `live cursor excludes seen after pin unpin and activity changes`() = testApp {
        val adminClient = jsonClient()
        val admin = ensureAdmin(adminClient)
        val author = createUser("uwlauthor", "Live Cursor Author")
        val ids = (1..4).map { index ->
            createConversation("GROUP", "Live $index", null, admin.id, author.id).also { id ->
                insertConversationMessage(id, author.id, "initial $index", "u-live-$index", "2026-03-01T10:00:00Z")
            }
        }
        val (a, b, c, d) = ids
        suspend fun state(id: Long, pinned: Boolean) {
            assertEquals(HttpStatusCode.OK, adminClient.post("/api/chat/workspace/GROUP/$id/state") {
                jsonBody(ConversationDialogStateRequest(pinned = pinned))
            }.status)
        }
        suspend fun next(cursor: String?): ConversationListPageDto = workspace(
            adminClient,
            "limit=1" + cursor?.let { "&cursor=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
        )

        state(d, true)
        val first = next(null)
        assertEquals(d, first.items.single().id)
        state(b, true)
        state(d, false)
        val second = next(first.nextCursor)
        assertEquals(b, second.items.single().id)
        insertConversationMessage(a, author.id, "unseen moves up", "u-live-a-new", "2026-03-01T12:00:00Z")
        val third = next(second.nextCursor)
        assertEquals(a, third.items.single().id)
        insertConversationMessage(b, author.id, "seen moves up", "u-live-b-new", "2026-03-01T13:00:00Z")
        val fourth = next(third.nextCursor)
        assertEquals(c, fourth.items.single().id)
        assertNull(fourth.nextCursor)
        assertEquals(listOf(d, b, a, c), listOf(first, second, third, fourth).flatMap { page ->
            page.items.map { it.id }
        })
    }

    @Test
    fun `partial state updates survive concurrency and task composite request is rejected`() = testApp {
        val adminClient = jsonClient()
        val admin = ensureAdmin(adminClient)
        val other = createUser("uwcstate", "Concurrent State Author")
        val groupId = createConversation("GROUP", "Concurrent State", null, admin.id, other.id)
        val taskId = createTask("T-COMPOSITE-STATE", "IN_PROGRESS", admin.id, admin.id)
        addTaskParticipant(taskId, admin.id, "INITIATOR")

        kotlinx.coroutines.coroutineScope {
            val pin = async {
                adminClient.post("/api/chat/workspace/GROUP/$groupId/state") {
                    jsonBody(ConversationDialogStateRequest(pinned = true))
                }
            }
            val mute = async {
                adminClient.post("/api/chat/workspace/GROUP/$groupId/state") {
                    jsonBody(ConversationDialogStateRequest(muted = true))
                }
            }
            assertEquals(HttpStatusCode.OK, pin.await().status)
            assertEquals(HttpStatusCode.OK, mute.await().status)
        }
        var groupState = workspace(adminClient).items.single { it.kind == ConversationKind.GROUP && it.id == groupId }
        assertTrue(groupState.isPinned)
        assertTrue(groupState.isMuted)
        kotlinx.coroutines.coroutineScope {
            val mute = async {
                adminClient.post("/api/chat/workspace/GROUP/$groupId/state") {
                    jsonBody(ConversationDialogStateRequest(muted = false))
                }
            }
            val archive = async {
                adminClient.post("/api/chat/workspace/GROUP/$groupId/state") {
                    jsonBody(ConversationDialogStateRequest(archived = true))
                }
            }
            assertEquals(HttpStatusCode.OK, mute.await().status)
            assertEquals(HttpStatusCode.OK, archive.await().status)
        }
        groupState = workspace(adminClient, "filter=ARCHIVED").items.single { it.kind == ConversationKind.GROUP && it.id == groupId }
        assertTrue(groupState.isPinned)
        assertFalse(groupState.isMuted)
        assertTrue(groupState.isArchived)

        val auditBeforeComposite = count("SELECT * FROM audit_log")
        val composite = adminClient.post("/api/chat/workspace/TASK/$taskId/state") {
            jsonBody(ConversationDialogStateRequest(pinned = true, muted = true))
        }
        assertEquals(HttpStatusCode.BadRequest, composite.status, composite.bodyAsText())
        assertEquals(auditBeforeComposite, count("SELECT * FROM audit_log"))
        assertEquals(0, count("SELECT * FROM task_chat_dialog_pins WHERE task_id = ?", taskId))
        assertEquals(0, count("SELECT * FROM task_chat_dialog_states WHERE task_id = ?", taskId))

        kotlinx.coroutines.coroutineScope {
            val mute = async {
                adminClient.post("/api/chat/workspace/TASK/$taskId/state") {
                    jsonBody(ConversationDialogStateRequest(muted = true))
                }
            }
            val archive = async {
                adminClient.post("/api/chat/workspace/TASK/$taskId/state") {
                    jsonBody(ConversationDialogStateRequest(archived = true))
                }
            }
            assertEquals(HttpStatusCode.OK, mute.await().status)
            assertEquals(HttpStatusCode.OK, archive.await().status)
        }
        val taskState = workspace(adminClient, "filter=ARCHIVED").items.single { it.taskId == taskId }
        assertTrue(taskState.isMuted)
        assertTrue(taskState.isArchived)
    }

    @Test
    fun `revoked access hides every projection and closed task stays readable but not pinnable`() = testApp {
        val adminClient = jsonClient()
        val admin = ensureAdmin(adminClient)
        val member = createUser("uwrmember", "Revoked Member")
        val stranger = createUser("uwrstranger", "Workspace Stranger")
        val projectId = createProject("Secret Project", admin.id, member.id)
        val projectChat = createConversation("PROJECT", null, projectId, admin.id, member.id)
        insertConversationMessage(projectChat, admin.id, "secret project preview", "u-r-p", null)
        val taskId = createTask("T-REMOVED", "IN_PROGRESS", admin.id, admin.id)
        addTaskParticipant(taskId, member.id, "OBSERVER")
        insertTaskMessage(taskId, admin.id, "secret task preview", "u-r-t", null)
        val closedTask = createTask("T-CLOSED-WORKSPACE", "CLOSED", member.id, member.id)
        addTaskParticipant(closedTask, member.id, "ASSIGNEE")
        insertTaskMessage(closedTask, admin.id, "closed remains visible", "u-r-c", null)
        val memberClient = login("uwrmember")
        assertTrue(workspace(memberClient).items.any { it.id == projectChat })
        assertTrue(workspace(memberClient).items.any { it.taskId == taskId })
        val closed = workspace(memberClient).items.single { it.taskId == closedTask }
        assertFalse(closed.canWrite)
        assertEquals(HttpStatusCode.Forbidden, memberClient.post("/api/chat/workspace/TASK/$closedTask/state") {
            jsonBody(ConversationDialogStateRequest(pinned = true))
        }.status)
        assertEquals(HttpStatusCode.OK, memberClient.post("/api/chat/workspace/TASK/$closedTask/state") {
            jsonBody(ConversationDialogStateRequest(archived = true))
        }.status)

        dsl().execute("DELETE FROM project_members WHERE project_id = ? AND user_id = ?", projectId, member.id)
        dsl().execute("DELETE FROM task_participants WHERE task_id = ? AND user_id = ?", taskId, member.id)
        val revokedBody = memberClient.get("/api/chat/workspace").bodyAsText()
        assertFalse(revokedBody.contains("Secret Project"))
        assertFalse(revokedBody.contains("secret project preview"))
        assertFalse(revokedBody.contains("T-REMOVED"))
        assertFalse(revokedBody.contains("secret task preview"))
        assertEquals(HttpStatusCode.Forbidden, memberClient.post("/api/chat/workspace/PROJECT/$projectChat/state") {
            jsonBody(ConversationDialogStateRequest(muted = true))
        }.status)
        val strangerClient = login("uwrstranger")
        assertTrue(workspace(strangerClient).items.none { it.id == projectChat || it.taskId == taskId })
        assertNotNull(stranger)
    }

    @Test
    fun `revoked message preview is neutral and counters start after read cursor`() = testApp {
        val adminClient = jsonClient()
        val admin = ensureAdmin(adminClient)
        val author = createUser("uwvauthor", "Preview Author")
        val groupId = createConversation("GROUP", "Preview Group", null, admin.id, author.id)
        val first = insertConversationMessage(groupId, author.id, "first incoming", "u-v-1", "2026-01-01T10:00:00Z")
        insertConversationMessage(groupId, admin.id, "own does not count", "u-v-2", "2026-01-01T11:00:00Z")
        val second = insertConversationMessage(groupId, author.id, "secret revoked preview", "u-v-3", "2026-01-01T12:00:00Z")
        insertConversationMention(second, admin.id)
        dsl().execute(
            "INSERT INTO conversation_read_states (conversation_id, user_id, last_read_message_id) VALUES (?, ?, ?)",
            groupId, admin.id, first
        )
        dsl().execute("UPDATE conversation_messages SET revoked_at = NOW(), content = 'Сообщение отозвано' WHERE id = ?", second)
        val item = workspace(adminClient).items.single()
        assertEquals("Сообщение отозвано", item.lastMessagePreview)
        assertFalse(adminClient.get("/api/chat/workspace").bodyAsText().contains("secret revoked preview"))
        assertEquals(1, item.unreadCount)
        assertEquals(1, item.mentionCount)
    }

    private suspend fun ApplicationTestBuilder.workspace(client: HttpClient, query: String = ""): ConversationListPageDto {
        val suffix = if (query.isEmpty()) "" else "?$query"
        val response = client.get("/api/chat/workspace$suffix")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<ConversationListPageDto>>().data!!
    }

    private fun ApplicationTestBuilder.dsl(): DSLContext = application.getKoin().get()

    private fun ApplicationTestBuilder.count(query: String, vararg parameters: Any?): Int =
        dsl().fetchCountSql(query, *parameters)

    private fun ApplicationTestBuilder.createConversation(
        kind: String,
        title: String?,
        projectId: Long?,
        vararg memberIds: Long
    ): Long {
        val id = dsl().connectionResult { connection ->
            val directKey = if (kind == "DIRECT") memberIds.sorted().joinToString(":") else null
            connection.prepareStatement(
                "INSERT INTO conversations (kind, title, project_id, direct_key, created_by) VALUES (?, ?, ?, ?, ?) RETURNING id"
            ).use { statement ->
                statement.setString(1, kind)
                statement.setString(2, title)
                if (projectId == null) statement.setNull(3, java.sql.Types.BIGINT) else statement.setLong(3, projectId)
                statement.setString(4, directKey)
                statement.setLong(5, memberIds.first())
                statement.executeQuery().use { result -> result.next(); result.getLong(1) }
            }
        }
        memberIds.forEach { memberId ->
            dsl().execute("INSERT INTO conversation_participants (conversation_id, user_id) VALUES (?, ?)", id, memberId)
        }
        return id
    }

    private fun ApplicationTestBuilder.createProject(title: String, ownerId: Long, memberId: Long): Long {
        val id = dsl().connectionResult { connection ->
            connection.prepareStatement(
                """
                INSERT INTO projects (number, name, status, owner_id, created_by)
                VALUES (?, ?, 'IN_PROGRESS', ?, ?) RETURNING id
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, "P-${java.util.UUID.randomUUID()}")
                statement.setString(2, title)
                statement.setLong(3, ownerId)
                statement.setLong(4, ownerId)
                statement.executeQuery().use { result -> result.next(); result.getLong(1) }
            }
        }
        dsl().execute(
            "INSERT INTO project_members (project_id, user_id, added_by) VALUES (?, ?, ?)",
            id, memberId, ownerId
        )
        return id
    }

    private fun ApplicationTestBuilder.createTask(number: String, status: String, initiatorId: Long, assigneeId: Long): Long =
        dsl().connectionResult { connection ->
            connection.prepareStatement(
                """
                INSERT INTO tasks (number, area, status, type, title, initiator_id, assignee_id, created_by)
                VALUES (?, 'PERSONAL', ?, 'GENERAL', ?, ?, ?, ?) RETURNING id
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, number)
                statement.setString(2, status)
                statement.setString(3, "Workspace $number")
                statement.setLong(4, initiatorId)
                statement.setLong(5, assigneeId)
                statement.setLong(6, initiatorId)
                statement.executeQuery().use { result -> result.next(); result.getLong(1) }
            }
        }

    private fun ApplicationTestBuilder.addTaskParticipant(taskId: Long, userId: Long, role: String) {
        dsl().execute("INSERT INTO task_participants (task_id, user_id, role) VALUES (?, ?, ?)", taskId, userId, role)
    }

    private fun ApplicationTestBuilder.insertConversationMessage(
        conversationId: Long,
        authorId: Long,
        content: String,
        key: String,
        createdAt: String?
    ): Long = dsl().connectionResult { connection ->
        connection.prepareStatement(
            """
            INSERT INTO conversation_messages (conversation_id, author_id, content, idempotency_key, created_at)
            VALUES (?, ?, ?, ?, COALESCE(?::timestamptz, NOW())) RETURNING id
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, conversationId)
            statement.setLong(2, authorId)
            statement.setString(3, content)
            statement.setString(4, key)
            statement.setString(5, createdAt)
            statement.executeQuery().use { result -> result.next(); result.getLong(1) }
        }
    }

    private fun ApplicationTestBuilder.insertTaskMessage(
        taskId: Long,
        authorId: Long,
        content: String,
        key: String,
        createdAt: String?
    ): Long = dsl().connectionResult { connection ->
        connection.prepareStatement(
            """
            INSERT INTO task_chat_messages (task_id, author_id, type, content, idempotency_key, created_at)
            VALUES (?, ?, 'USER', ?, ?, COALESCE(?::timestamptz, NOW())) RETURNING id
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, taskId)
            statement.setLong(2, authorId)
            statement.setString(3, content)
            statement.setString(4, key)
            statement.setString(5, createdAt)
            statement.executeQuery().use { result -> result.next(); result.getLong(1) }
        }
    }

    private fun ApplicationTestBuilder.insertConversationMention(messageId: Long, userId: Long) {
        dsl().execute("INSERT INTO conversation_mentions (message_id, mentioned_user_id) VALUES (?, ?)", messageId, userId)
    }

    private suspend fun ApplicationTestBuilder.ensureAdmin(client: HttpClient): UserDto {
        client.post("/api/admin/bootstrap") { jsonBody(BootstrapRequest("admin", "admin123", "Admin User")) }
        return login("admin", "admin123", client).get("/api/auth/me")
            .bodyAs<ApiResponse<MeResponse>>().data!!.user
    }

    private suspend fun ApplicationTestBuilder.createUser(login: String, fullName: String): UserDto {
        val admin = login("admin", "admin123")
        val role = admin.post("/api/roles") {
            jsonBody(CreateRoleRequest(login.uppercase(), fullName))
        }.bodyAs<ApiResponse<RoleDto>>().data!!
        return admin.post("/api/users") {
            jsonBody(CreateUserRequest(login, "password", fullName, roleIds = listOf(role.id)))
        }.bodyAs<ApiResponse<UserDto>>().data!!
    }

    private suspend fun ApplicationTestBuilder.login(
        login: String,
        password: String = "password",
        existing: HttpClient? = null
    ): HttpClient {
        val client = existing ?: jsonClient()
        val response = client.post("/api/auth/login") { jsonBody(LoginRequest(login, password)) }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return client
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(HttpCookies) }

    private suspend inline fun <reified T> HttpResponse.bodyAs(): T = json.decodeFromString(serializer<T>(), bodyAsText())

    private inline fun <reified T> io.ktor.client.request.HttpRequestBuilder.jsonBody(value: T) {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(serializer<T>(), value))
    }

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) {
        testApplication {
            environment {
                config = HoconApplicationConfig(ConfigFactory.parseString(
                    """
                    ktor { deployment { port = 0 }, application { modules = [org.example.project.ApplicationKt.module] } }
                    database { url = "${TestDatabase.TEST_URL}", user = "erp", password = "erp" }
                    session { ttlMinutes = 60, cookieName = "erp_session" }
                    security { allowedOrigins = "http://localhost:8080,http://localhost:8081", cookieSecure = false }
                    storage { path = "./.local/test-storage" }
                    """.trimIndent()
                ))
            }
            block()
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

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
