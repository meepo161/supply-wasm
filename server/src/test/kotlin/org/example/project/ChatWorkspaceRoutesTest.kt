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
import java.net.URLEncoder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatWorkspaceRoutesTest {

    @Before
    fun reset() {
        TestDatabase.reset()
    }

    @Test
    fun `chat list shows unread and mention counts and respects filters`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val member = createUserWithRole(admin, "cwsmember1", "password", "Workspace Member One", emptyList())
        createUserWithRole(admin, "cwsstranger1", "password", "Cws Stranger One", emptyList())
        val taskId = createTask("T-CW1", "IN_PROGRESS", adminUser.id, adminUser.id, adminUser.id)
        addTaskParticipant(taskId, member.id, "ASSIGNEE")

        val memberClient = loginClient("cwsmember1", "password")
        memberClient.post("/api/tasks/$taskId/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Обычное сообщение", "cw-key-1")))
        }
        memberClient.post("/api/tasks/$taskId/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("@admin посмотри задачу", "cw-key-2")))
        }

        val adminClient = loginClient("admin", "admin123")
        val list = adminClient.get("/api/tasks/chat/list")
            .bodyAs<ApiResponse<List<TaskChatListItemDto>>>().data!!
        val item = list.single { it.taskId == taskId }
        assertEquals(2, item.unreadCount)
        assertEquals(1, item.mentionCount)
        assertEquals("Workspace Member One", item.lastMessageAuthor)
        assertTrue(item.lastMessageSnippet?.contains("@admin") == true)
        assertFalse(item.isPinned)
        assertEquals("T-CW1", item.number)

        val unreadFilter = adminClient.get("/api/tasks/chat/list?filter=UNREAD")
            .bodyAs<ApiResponse<List<TaskChatListItemDto>>>().data!!
        assertTrue(unreadFilter.any { it.taskId == taskId })
        val mentionsFilter = adminClient.get("/api/tasks/chat/list?filter=MENTIONS")
            .bodyAs<ApiResponse<List<TaskChatListItemDto>>>().data!!
        assertTrue(mentionsFilter.any { it.taskId == taskId })
        val pinnedFilter = adminClient.get("/api/tasks/chat/list?filter=PINNED")
            .bodyAs<ApiResponse<List<TaskChatListItemDto>>>().data!!
        assertFalse(pinnedFilter.any { it.taskId == taskId })

        val queryFilter = adminClient.get("/api/tasks/chat/list?query=T-CW1")
            .bodyAs<ApiResponse<List<TaskChatListItemDto>>>().data!!
        assertTrue(queryFilter.any { it.taskId == taskId })
        val noMatch = adminClient.get("/api/tasks/chat/list?query=несуществующий-номер")
            .bodyAs<ApiResponse<List<TaskChatListItemDto>>>().data!!
        assertFalse(noMatch.any { it.taskId == taskId })

        val strangerList = loginClient("cwsstranger1", "password").get("/api/tasks/chat/list")
            .bodyAs<ApiResponse<List<TaskChatListItemDto>>>().data!!
        assertFalse(strangerList.any { it.taskId == taskId })

        val messages = adminClient.get("/api/tasks/$taskId/chat/messages")
            .bodyAs<ApiResponse<ChatMessagePageDto>>().data!!
        val lastMessageId = messages.messages.last().id
        val mentionMessage = messages.messages.first { it.content.contains("@admin") }
        assertEquals(listOf(adminUser.id), mentionMessage.mentionedUserIds)

        val readResponse = adminClient.post("/api/tasks/$taskId/chat/read") {
            contentType(ContentType.Application.Json)
            setBody(stringify(MarkChatReadRequest(lastMessageId)))
        }
        assertEquals(HttpStatusCode.OK, readResponse.status, readResponse.bodyAsText())

        val afterRead = adminClient.get("/api/tasks/chat/list")
            .bodyAs<ApiResponse<List<TaskChatListItemDto>>>().data!!
        val readItem = afterRead.single { it.taskId == taskId }
        assertEquals(0, readItem.unreadCount)
        assertEquals(0, readItem.mentionCount)
        val unreadAfterRead = adminClient.get("/api/tasks/chat/list?filter=UNREAD")
            .bodyAs<ApiResponse<List<TaskChatListItemDto>>>().data!!
        assertFalse(unreadAfterRead.any { it.taskId == taskId })

        val historyAfterRead = adminClient.get("/api/tasks/$taskId/chat/messages")
            .bodyAs<ApiResponse<ChatMessagePageDto>>().data!!
        assertEquals(1, historyAfterRead.messages.last().readByCount)
    }

    @Test
    fun `mark read is monotonic and validates message belongs to task`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val member = createUserWithRole(admin, "cwsmember2", "password", "Workspace Member Two", emptyList())
        val firstTask = createTask("T-CW2", "IN_PROGRESS", adminUser.id, adminUser.id, adminUser.id)
        val secondTask = createTask("T-CW3", "IN_PROGRESS", adminUser.id, adminUser.id, adminUser.id)
        addTaskParticipant(firstTask, member.id, "ASSIGNEE")
        addTaskParticipant(secondTask, member.id, "ASSIGNEE")

        val memberClient = loginClient("cwsmember2", "password")
        memberClient.post("/api/tasks/$firstTask/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("m1", "cw-key-m1")))
        }
        memberClient.post("/api/tasks/$firstTask/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("m2", "cw-key-m2")))
        }
        val otherMessage = memberClient.post("/api/tasks/$secondTask/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("other", "cw-key-m3")))
        }.bodyAs<ApiResponse<ChatMessageDto>>().data!!

        val adminClient = loginClient("admin", "admin123")
        val messages = adminClient.get("/api/tasks/$firstTask/chat/messages")
            .bodyAs<ApiResponse<ChatMessagePageDto>>().data!!
        val lastId = messages.messages.last().id
        val firstId = messages.messages.first().id

        val raise = adminClient.post("/api/tasks/$firstTask/chat/read") {
            contentType(ContentType.Application.Json)
            setBody(stringify(MarkChatReadRequest(lastId)))
        }
        assertEquals(HttpStatusCode.OK, raise.status)
        assertEquals(lastId, readCursor(raise))

        val lower = adminClient.post("/api/tasks/$firstTask/chat/read") {
            contentType(ContentType.Application.Json)
            setBody(stringify(MarkChatReadRequest(firstId)))
        }
        assertEquals(HttpStatusCode.OK, lower.status)
        assertEquals(lastId, readCursor(lower))

        val foreign = adminClient.post("/api/tasks/$firstTask/chat/read") {
            contentType(ContentType.Application.Json)
            setBody(stringify(MarkChatReadRequest(otherMessage.id)))
        }
        assertEquals(HttpStatusCode.BadRequest, foreign.status)
    }

    @Test
    fun `mark read is allowed on closed task`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val member = createUserWithRole(admin, "cwsmember3", "password", "Workspace Member Three", emptyList())
        val taskId = createTask("T-CW4", "CLOSED", adminUser.id, adminUser.id, adminUser.id)
        addTaskParticipant(taskId, member.id, "ASSIGNEE")
        dslSendMessage(taskId, member.id, "before close", "cw-key-c1")

        val memberClient = loginClient("cwsmember3", "password")
        val messages = memberClient.get("/api/tasks/$taskId/chat/messages")
            .bodyAs<ApiResponse<ChatMessagePageDto>>().data!!
        val readResponse = memberClient.post("/api/tasks/$taskId/chat/read") {
            contentType(ContentType.Application.Json)
            setBody(stringify(MarkChatReadRequest(messages.messages.last().id)))
        }
        assertEquals(HttpStatusCode.OK, readResponse.status, readResponse.bodyAsText())
    }

    @Test
    fun `reply requires message of the same task`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val member = createUserWithRole(admin, "cwsmember4", "password", "Workspace Member Four", emptyList())
        val firstTask = createTask("T-CW5", "IN_PROGRESS", adminUser.id, adminUser.id, adminUser.id)
        val secondTask = createTask("T-CW6", "IN_PROGRESS", adminUser.id, adminUser.id, adminUser.id)
        addTaskParticipant(firstTask, member.id, "ASSIGNEE")
        addTaskParticipant(secondTask, member.id, "ASSIGNEE")

        val memberClient = loginClient("cwsmember4", "password")
        val original = memberClient.post("/api/tasks/$firstTask/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Исходное сообщение", "cw-key-re1")))
        }.bodyAs<ApiResponse<ChatMessageDto>>().data!!
        val foreignMessage = memberClient.post("/api/tasks/$secondTask/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Чужая задача", "cw-key-re2")))
        }.bodyAs<ApiResponse<ChatMessageDto>>().data!!

        val reply = memberClient.post("/api/tasks/$firstTask/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Ответ", "cw-key-re3", replyToMessageId = original.id)))
        }
        assertEquals(HttpStatusCode.Created, reply.status, reply.bodyAsText())
        val replyMessage = reply.bodyAs<ApiResponse<ChatMessageDto>>().data!!
        assertEquals(original.id, replyMessage.replyTo?.id)
        assertEquals("Исходное сообщение", replyMessage.replyTo?.contentPreview)
        assertEquals(member.id, replyMessage.replyTo?.author?.userId)

        val foreignReply = memberClient.post("/api/tasks/$firstTask/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Невалидный ответ", "cw-key-re4", replyToMessageId = foreignMessage.id)))
        }
        assertEquals(HttpStatusCode.BadRequest, foreignReply.status)
    }

    @Test
    fun `reply to revoked message exposes only tombstone preview`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val taskId = createTask("T-CW-REVOKED-REPLY", "IN_PROGRESS", adminUser.id, adminUser.id, adminUser.id)
        val client = loginClient("admin", "admin123")
        val original = client.post("/api/tasks/$taskId/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Secret revoked reply source", "cw-revoked-reply-1")))
        }.bodyAs<ApiResponse<ChatMessageDto>>().data!!
        val reply = client.post("/api/tasks/$taskId/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest(
                "Reply remains", "cw-revoked-reply-2", replyToMessageId = original.id
            )))
        }.bodyAs<ApiResponse<ChatMessageDto>>().data!!
        val revoked = client.post("/api/tasks/$taskId/chat/messages/${original.id}/revoke") {
            contentType(ContentType.Application.Json)
            setBody(stringify(RevokeMessageRequest(0)))
        }
        assertEquals(HttpStatusCode.OK, revoked.status, revoked.bodyAsText())

        val historyResponse = client.get("/api/tasks/$taskId/chat/messages")
        val historyText = historyResponse.bodyAsText()
        assertFalse(historyText.contains("Secret revoked reply source"))
        val history = historyResponse.bodyAs<ApiResponse<ChatMessagePageDto>>().data!!
        val hydratedReply = history.messages.single { it.id == reply.id }
        assertEquals("Сообщение отозвано", hydratedReply.replyTo?.contentPreview)
        assertEquals("Reply remains", hydratedReply.content)
    }

    @Test
    fun `removed participant cannot edit after access revocation and no event is written`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val member = createUserWithRole(admin, "cwremovedwriter", "password", "Removed Writer", emptyList())
        val taskId = createTask("T-CW-REMOVED", "IN_PROGRESS", adminUser.id, adminUser.id, adminUser.id)
        addTaskParticipant(taskId, member.id, "ASSIGNEE")
        val client = loginClient("cwremovedwriter", "password")
        val message = client.post("/api/tasks/$taskId/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Before removal", "cw-removed-1")))
        }.bodyAs<ApiResponse<ChatMessageDto>>().data!!
        application.getKoin().get<DSLContext>().execute(
            "DELETE FROM task_participants WHERE task_id = ? AND user_id = ?", taskId, member.id
        )

        val denied = client.post("/api/tasks/$taskId/chat/messages/${message.id}/edit") {
            contentType(ContentType.Application.Json)
            setBody(stringify(EditMessageRequest("After removal", message.version)))
        }
        assertEquals(HttpStatusCode.Forbidden, denied.status)
        val current = application.getKoin().get<DSLContext>().fetchOne(
            "SELECT content, version FROM task_chat_messages WHERE id = ?", message.id
        )!!
        assertEquals("Before removal", current.get("content"))
        assertEquals(0L, current.get("version"))
        assertEquals(1, application.getKoin().get<DSLContext>().fetchCountSql(
            "SELECT * FROM task_chat_event_log WHERE task_id = ?", taskId
        ))
    }

    @Test
    fun `reactions toggle idempotently and respect rights`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val member = createUserWithRole(admin, "cwsmember5", "password", "Workspace Member Five", emptyList())
        createUserWithRole(admin, "cwsstranger5", "password", "Cws Stranger Five", emptyList())
        val taskId = createTask("T-CW7", "IN_PROGRESS", adminUser.id, adminUser.id, adminUser.id)
        val closedTask = createTask("T-CW8", "CLOSED", adminUser.id, adminUser.id, adminUser.id)
        addTaskParticipant(taskId, member.id, "ASSIGNEE")
        addTaskParticipant(closedTask, member.id, "ASSIGNEE")
        val closedMessageId = dslSendMessage(closedTask, member.id, "closed message", "cw-key-x0")

        val memberClient = loginClient("cwsmember5", "password")
        val message = memberClient.post("/api/tasks/$taskId/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("За реакцию", "cw-key-x1")))
        }.bodyAs<ApiResponse<ChatMessageDto>>().data!!

        val emoji = URLEncoder.encode("👍", "UTF-8")
        val add = memberClient.put("/api/tasks/$taskId/chat/messages/${message.id}/reactions/$emoji")
        assertEquals(HttpStatusCode.OK, add.status, add.bodyAsText())
        val summaries = add.bodyAs<ApiResponse<List<ReactionSummaryDto>>>().data!!
        assertEquals(1, summaries.size)
        assertEquals("👍", summaries.single().emoji)
        assertEquals(1, summaries.single().count)
        assertTrue(summaries.single().reactedByMe)

        val repeat = memberClient.put("/api/tasks/$taskId/chat/messages/${message.id}/reactions/$emoji")
        assertEquals(HttpStatusCode.OK, repeat.status)
        assertEquals(1, repeat.bodyAs<ApiResponse<List<ReactionSummaryDto>>>().data!!.single().count)

        val history = loginClient("admin", "admin123").get("/api/tasks/$taskId/chat/messages")
            .bodyAs<ApiResponse<ChatMessagePageDto>>().data!!
        val historyReactions = history.messages.single().reactions.single()
        assertEquals(1, historyReactions.count)
        assertFalse(historyReactions.reactedByMe)

        val remove = memberClient.delete("/api/tasks/$taskId/chat/messages/${message.id}/reactions/$emoji")
        assertEquals(HttpStatusCode.OK, remove.status)
        assertEquals(0, remove.bodyAs<ApiResponse<List<ReactionSummaryDto>>>().data!!.size)
        val removeAgain = memberClient.delete("/api/tasks/$taskId/chat/messages/${message.id}/reactions/$emoji")
        assertEquals(HttpStatusCode.OK, removeAgain.status)

        val tooLong = memberClient.put("/api/tasks/$taskId/chat/messages/${message.id}/reactions/0123456789abcdef0")
        assertEquals(HttpStatusCode.BadRequest, tooLong.status)

        val strangerClient = loginClient("cwsstranger5", "password")
        val denied = strangerClient.put("/api/tasks/$taskId/chat/messages/${message.id}/reactions/$emoji")
        assertEquals(HttpStatusCode.Forbidden, denied.status)

        val closed = memberClient.put("/api/tasks/$closedTask/chat/messages/$closedMessageId/reactions/$emoji")
        assertEquals(HttpStatusCode.Forbidden, closed.status)
    }

    @Test
    fun `dialog pin orders list and filters pinned`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val member = createUserWithRole(admin, "cwsmember6", "password", "Workspace Member Six", emptyList())
        val olderTask = createTask("T-CW9", "IN_PROGRESS", adminUser.id, adminUser.id, adminUser.id)
        val newerTask = createTask("T-CW10", "IN_PROGRESS", adminUser.id, adminUser.id, adminUser.id)
        addTaskParticipant(olderTask, member.id, "ASSIGNEE")
        addTaskParticipant(newerTask, member.id, "ASSIGNEE")

        val memberClient = loginClient("cwsmember6", "password")
        memberClient.post("/api/tasks/$olderTask/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Старое", "cw-key-p1")))
        }
        memberClient.post("/api/tasks/$newerTask/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Новое", "cw-key-p2")))
        }

        val beforePin = memberClient.get("/api/tasks/chat/list")
            .bodyAs<ApiResponse<List<TaskChatListItemDto>>>().data!!
        assertEquals(newerTask, beforePin.first().taskId)

        val pin = memberClient.put("/api/tasks/$olderTask/chat/pin")
        assertEquals(HttpStatusCode.OK, pin.status, pin.bodyAsText())

        val afterPin = memberClient.get("/api/tasks/chat/list")
            .bodyAs<ApiResponse<List<TaskChatListItemDto>>>().data!!
        assertEquals(olderTask, afterPin.first().taskId)
        assertTrue(afterPin.first().isPinned)

        val pinnedOnly = memberClient.get("/api/tasks/chat/list?filter=PINNED")
            .bodyAs<ApiResponse<List<TaskChatListItemDto>>>().data!!
        assertEquals(listOf(olderTask), pinnedOnly.map { it.taskId })

        val unpin = memberClient.delete("/api/tasks/$olderTask/chat/pin")
        assertEquals(HttpStatusCode.OK, unpin.status)
        val afterUnpin = memberClient.get("/api/tasks/chat/list")
            .bodyAs<ApiResponse<List<TaskChatListItemDto>>>().data!!
        assertEquals(newerTask, afterUnpin.first().taskId)
    }

    @Test
    fun `chat search finds content only for readers`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val member = createUserWithRole(admin, "cwsmember7", "password", "Workspace Member Seven", emptyList())
        createUserWithRole(admin, "cwsstranger7", "password", "Cws Stranger Seven", emptyList())
        val taskId = createTask("T-CW11", "IN_PROGRESS", adminUser.id, adminUser.id, adminUser.id)
        addTaskParticipant(taskId, member.id, "ASSIGNEE")

        val memberClient = loginClient("cwsmember7", "password")
        memberClient.post("/api/tasks/$taskId/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Уникальный маркер поиска", "cw-key-s1")))
        }
        memberClient.post("/api/tasks/$taskId/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Другой текст", "cw-key-s2")))
        }

        val results = memberClient.get("/api/tasks/$taskId/chat/search?query=маркер")
            .bodyAs<ApiResponse<List<ChatSearchResultDto>>>().data!!
        assertEquals(1, results.size)
        assertEquals("Уникальный маркер поиска", results.single().content)
        assertEquals(taskId, results.single().taskId)
        assertEquals(member.id, results.single().author?.userId)

        val blank = memberClient.get("/api/tasks/$taskId/chat/search?query=")
            .bodyAs<ApiResponse<List<ChatSearchResultDto>>>().data!!
        assertEquals(0, blank.size)

        val denied = loginClient("cwsstranger7", "password").get("/api/tasks/$taskId/chat/search?query=маркер")
        assertEquals(HttpStatusCode.Forbidden, denied.status)
    }

    @Test
    fun `task message edit and revoke require author version and hide revoked content`() = testApp {
        val admin = jsonClient()
        ensureAdmin(admin)
        val author = createUserWithRole(admin, "cwedita", "password", "Task Edit Author", emptyList())
        val member = createUserWithRole(admin, "cweditb", "password", "Task Edit Member", emptyList())
        val taskId = createTask("T-CW-EDIT", "IN_PROGRESS", author.id, author.id, author.id)
        addTaskParticipant(taskId, author.id, "INITIATOR")
        addTaskParticipant(taskId, member.id, "ASSIGNEE")
        val authorClient = loginClient("cwedita", "password")
        val memberClient = loginClient("cweditb", "password")
        val message = authorClient.post("/api/tasks/$taskId/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Secret original", "cw-edit-1")))
        }.bodyAs<ApiResponse<ChatMessageDto>>().data!!
        val systemId = dslSendMessage(taskId, author.id, "System lifecycle", "cw-edit-system")
        application.getKoin().get<DSLContext>().execute(
            "UPDATE task_chat_messages SET type = 'SYSTEM' WHERE id = ?", systemId
        )

        val missingVersion = authorClient.post("/api/tasks/$taskId/chat/messages/${message.id}/edit") {
            contentType(ContentType.Application.Json)
            setBody(stringify(EditMessageRequest("No version")))
        }
        assertEquals(HttpStatusCode.BadRequest, missingVersion.status)
        assertEquals(HttpStatusCode.Forbidden, memberClient.post(
            "/api/tasks/$taskId/chat/messages/${message.id}/edit"
        ) {
            contentType(ContentType.Application.Json)
            setBody(stringify(EditMessageRequest("Stolen", 0)))
        }.status)
        assertEquals(HttpStatusCode.Forbidden, authorClient.post(
            "/api/tasks/$taskId/chat/messages/$systemId/edit"
        ) {
            contentType(ContentType.Application.Json)
            setBody(stringify(EditMessageRequest("Changed system", 0)))
        }.status)

        val editedResponse = authorClient.post("/api/tasks/$taskId/chat/messages/${message.id}/edit") {
            contentType(ContentType.Application.Json)
            setBody(stringify(EditMessageRequest("Edited", 0)))
        }
        assertEquals(HttpStatusCode.OK, editedResponse.status, editedResponse.bodyAsText())
        val edited = editedResponse.bodyAs<ApiResponse<ChatMessageDto>>().data!!
        assertEquals("Edited", edited.content)
        assertEquals(1L, edited.version)
        assertTrue(edited.editedAt != null)
        assertEquals(HttpStatusCode.Conflict, authorClient.post(
            "/api/tasks/$taskId/chat/messages/${message.id}/edit"
        ) {
            contentType(ContentType.Application.Json)
            setBody(stringify(EditMessageRequest("Stale", 0)))
        }.status)
        val revision = application.getKoin().get<DSLContext>().fetchOne(
            "SELECT previous_content FROM task_chat_message_revisions WHERE message_id = ?", message.id
        )!!
        assertEquals("Secret original", revision.get("previous_content"))

        assertEquals(HttpStatusCode.BadRequest, authorClient.post(
            "/api/tasks/$taskId/chat/messages/${message.id}/revoke"
        ) {
            contentType(ContentType.Application.Json)
            setBody(stringify(RevokeMessageRequest()))
        }.status)
        assertEquals(HttpStatusCode.Forbidden, memberClient.post(
            "/api/tasks/$taskId/chat/messages/${message.id}/revoke"
        ) {
            contentType(ContentType.Application.Json)
            setBody(stringify(RevokeMessageRequest(1)))
        }.status)
        assertEquals(HttpStatusCode.Conflict, authorClient.post(
            "/api/tasks/$taskId/chat/messages/${message.id}/revoke"
        ) {
            contentType(ContentType.Application.Json)
            setBody(stringify(RevokeMessageRequest(0)))
        }.status)
        assertEquals(HttpStatusCode.Forbidden, authorClient.post(
            "/api/tasks/$taskId/chat/messages/$systemId/revoke"
        ) {
            contentType(ContentType.Application.Json)
            setBody(stringify(RevokeMessageRequest(0)))
        }.status)
        val revokedResponse = authorClient.post("/api/tasks/$taskId/chat/messages/${message.id}/revoke") {
            contentType(ContentType.Application.Json)
            setBody(stringify(RevokeMessageRequest(1)))
        }
        assertEquals(HttpStatusCode.OK, revokedResponse.status, revokedResponse.bodyAsText())
        val revoked = revokedResponse.bodyAs<ApiResponse<ChatMessageDto>>().data!!
        assertEquals("Сообщение отозвано", revoked.content)
        assertEquals(2L, revoked.version)
        assertTrue(revoked.revokedAt != null)
        val historyText = memberClient.get("/api/tasks/$taskId/chat/messages").bodyAsText()
        assertTrue(historyText.contains("Сообщение отозвано"))
        assertFalse(historyText.contains("Secret original"))
        assertFalse(historyText.contains("Edited"))
        assertFalse(memberClient.get("/api/tasks/$taskId/chat/search?query=Secret").bodyAsText().contains("Secret original"))
        assertEquals(2, application.getKoin().get<DSLContext>().fetchCountSql(
            "SELECT * FROM audit_log WHERE action IN ('CHAT_MESSAGE_EDIT', 'CHAT_MESSAGE_REVOKE') AND payload->>'messageId' = ?",
            message.id.toString()
        ))
        val categories = application.getKoin().get<DSLContext>().fetch(
            "SELECT category FROM task_chat_event_log WHERE task_id = ? ORDER BY id", taskId
        ).map { it.get("category") }
        assertEquals(listOf("MESSAGE", "EDITED", "REVOKED"), categories)
    }

    @Test
    fun `task forward checks both tasks preserves source and is idempotent`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val author = createUserWithRole(admin, "cwfwda", "password", "Task Forward Author", emptyList())
        createUserWithRole(admin, "cwfwdstranger", "password", "Task Forward Stranger", emptyList())
        val sourceTask = createTask("T-CW-FWD-S", "IN_PROGRESS", author.id, author.id, author.id)
        val targetTask = createTask("T-CW-FWD-T", "IN_PROGRESS", author.id, author.id, author.id)
        val deniedTask = createTask("T-CW-FWD-D", "IN_PROGRESS", adminUser.id, adminUser.id, adminUser.id)
        addTaskParticipant(sourceTask, author.id, "INITIATOR")
        addTaskParticipant(targetTask, author.id, "INITIATOR")
        addTaskParticipant(deniedTask, author.id, "WATCHER")
        val client = loginClient("cwfwda", "password")
        val source = client.post("/api/tasks/$sourceTask/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Forward task text", "cw-fwd-source")))
        }.bodyAs<ApiResponse<ChatMessageDto>>().data!!
        val systemId = dslSendMessage(sourceTask, author.id, "System forward", "cw-fwd-system")
        application.getKoin().get<DSLContext>().execute(
            "UPDATE task_chat_messages SET type = 'SYSTEM' WHERE id = ?", systemId
        )

        val deniedSource = loginClient("cwfwdstranger", "password").post(
            "/api/tasks/$sourceTask/chat/messages/${source.id}/forward"
        ) {
            contentType(ContentType.Application.Json)
            setBody(stringify(ForwardMessageRequest(ConversationKind.TASK, targetTask, "cw-fwd-source-denied")))
        }
        assertEquals(HttpStatusCode.Forbidden, deniedSource.status)
        assertFalse(deniedSource.bodyAsText().contains("Forward task text"))
        assertEquals(HttpStatusCode.BadRequest, client.post(
            "/api/tasks/$sourceTask/chat/messages/${source.id}/forward"
        ) {
            contentType(ContentType.Application.Json)
            setBody(stringify(ForwardMessageRequest(ConversationKind.GROUP, targetTask, "cw-fwd-kind")))
        }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post(
            "/api/tasks/$sourceTask/chat/messages/${source.id}/forward"
        ) {
            contentType(ContentType.Application.Json)
            setBody(stringify(ForwardMessageRequest(ConversationKind.TASK, deniedTask, "cw-fwd-denied")))
        }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post(
            "/api/tasks/$sourceTask/chat/messages/$systemId/forward"
        ) {
            contentType(ContentType.Application.Json)
            setBody(stringify(ForwardMessageRequest(ConversationKind.TASK, targetTask, "cw-fwd-system-target")))
        }.status)
        val forwardedResponse = client.post("/api/tasks/$sourceTask/chat/messages/${source.id}/forward") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ForwardMessageRequest(ConversationKind.TASK, targetTask, "cw-fwd-1")))
        }
        assertEquals(HttpStatusCode.Created, forwardedResponse.status, forwardedResponse.bodyAsText())
        val forwarded = forwardedResponse.bodyAs<ApiResponse<ChatMessageDto>>().data!!
        assertEquals("Forward task text", forwarded.content)
        assertEquals(ConversationKind.TASK, forwarded.source?.kind)
        assertEquals(source.id, forwarded.source?.messageId)
        assertEquals(author.id, forwarded.source?.author?.userId)
        val repeat = client.post("/api/tasks/$sourceTask/chat/messages/${source.id}/forward") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ForwardMessageRequest(ConversationKind.TASK, targetTask, "cw-fwd-1")))
        }
        assertEquals(forwarded.id, repeat.bodyAs<ApiResponse<ChatMessageDto>>().data!!.id)
        assertEquals(1, client.get("/api/tasks/$targetTask/chat/messages")
            .bodyAs<ApiResponse<ChatMessagePageDto>>().data!!.messages.size)
        assertEquals(1, application.getKoin().get<DSLContext>().fetchCountSql(
            "SELECT * FROM task_chat_event_log WHERE task_id = ?", targetTask
        ))
        assertEquals(1, application.getKoin().get<DSLContext>().fetchCountSql(
            "SELECT * FROM audit_log WHERE action = 'CHAT_MESSAGE_FORWARD' AND payload->>'forwardedMessageId' = ?",
            forwarded.id.toString()
        ))
    }

    @Test
    fun `closed and cancelled tasks reject every mutating task chat command but allow reads`() = testApp {
        val admin = jsonClient()
        ensureAdmin(admin)
        val member = createUserWithRole(admin, "cwclosedall", "password", "Closed Task Member", emptyList())
        val client = loginClient("cwclosedall", "password")
        listOf("CLOSED", "CANCELLED").forEachIndexed { index, status ->
            val taskId = createTask("T-CW-RO-$index", status, member.id, member.id, member.id)
            addTaskParticipant(taskId, member.id, "ASSIGNEE")
            val messageId = dslSendMessage(taskId, member.id, "Read only $status", "cw-ro-$index")
            assertEquals(HttpStatusCode.OK, client.get("/api/tasks/$taskId/chat/messages").status)
            assertEquals(HttpStatusCode.Forbidden, client.post("/api/tasks/$taskId/chat/messages") {
                contentType(ContentType.Application.Json)
                setBody(stringify(SendChatMessageRequest("no", "cw-ro-send-$index")))
            }.status)
            assertEquals(HttpStatusCode.Forbidden, client.post(
                "/api/tasks/$taskId/chat/messages/$messageId/edit"
            ) {
                contentType(ContentType.Application.Json)
                setBody(stringify(EditMessageRequest("no", 0)))
            }.status)
            assertEquals(HttpStatusCode.Forbidden, client.post(
                "/api/tasks/$taskId/chat/messages/$messageId/revoke"
            ) {
                contentType(ContentType.Application.Json)
                setBody(stringify(RevokeMessageRequest(0)))
            }.status)
            assertEquals(HttpStatusCode.Forbidden, client.post(
                "/api/tasks/$taskId/chat/messages/$messageId/forward"
            ) {
                contentType(ContentType.Application.Json)
                setBody(stringify(ForwardMessageRequest(ConversationKind.TASK, taskId, "cw-ro-fwd-$index")))
            }.status)
            assertEquals(HttpStatusCode.Forbidden, client.put(
                "/api/tasks/$taskId/chat/messages/$messageId/reactions/ok"
            ).status)
            assertEquals(HttpStatusCode.Forbidden, client.put("/api/tasks/$taskId/chat/pin").status)
            assertEquals(HttpStatusCode.OK, client.post("/api/tasks/$taskId/chat/read") {
                contentType(ContentType.Application.Json)
                setBody(stringify(MarkChatReadRequest(messageId)))
            }.status)
        }
    }

    private suspend fun readCursor(response: HttpResponse): Long {
        val body = response.bodyAs<ApiResponse<Map<String, Long>>>().data!!
        return body.getValue("lastReadMessageId")
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
                stmt.setString(3, "Workspace task $number")
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

    private fun ApplicationTestBuilder.dslSendMessage(
        taskId: Long,
        authorId: Long,
        content: String,
        key: String
    ): Long {
        val dsl = application.getKoin().get<DSLContext>()
        return dsl.connectionResult { conn ->
            conn.prepareStatement(
                """
                INSERT INTO task_chat_messages (task_id, author_id, type, content, idempotency_key)
                VALUES (?, ?, 'USER', ?, ?)
                RETURNING id
                """.trimIndent()
            ).use { stmt ->
                stmt.setLong(1, taskId)
                stmt.setLong(2, authorId)
                stmt.setString(3, content)
                stmt.setString(4, key)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }
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
