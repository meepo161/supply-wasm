package org.example.project

import org.example.project.dto.AttachmentDto
import org.example.project.dto.AttachmentEntityType
import org.example.project.dto.ChatMessageDto
import org.example.project.dto.ChatMessageType
import org.example.project.dto.DocumentDto
import org.example.project.dto.DocumentConfidentiality
import org.example.project.dto.DocumentLinkDto
import org.example.project.dto.DocumentLinkType
import org.example.project.dto.DocumentStatus
import org.example.project.dto.DocumentType
import org.example.project.dto.TaskChatListFilter
import org.example.project.dto.TaskChatListItemDto
import org.example.project.dto.UserOptionDto
import org.example.project.ui.tasks.chatSnippet
import org.example.project.ui.tasks.collectChatAttachments
import org.example.project.ui.tasks.defaultTaskChatId
import org.example.project.ui.tasks.filterChatList
import org.example.project.ui.tasks.insertMention
import org.example.project.ui.tasks.linkedTaskDocuments
import org.example.project.ui.tasks.mentionQuery
import org.example.project.ui.tasks.mentionSuggestions
import org.example.project.ui.tasks.parseChatFormatting
import org.example.project.ui.tasks.shouldAutoScrollToNew
import org.example.project.ui.tasks.taskChatContextCollapsed
import org.example.project.ui.tasks.ChatTextSegment
import org.example.project.ui.tasks.ChatTextKind as Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskChatLogicTest {

    private fun item(
        taskId: Long,
        unread: Int = 0,
        mentions: Int = 0,
        pinned: Boolean = false,
        title: String = "Задача $taskId"
    ) = TaskChatListItemDto(
        taskId = taskId,
        number = "T-2026-$taskId",
        title = title,
        status = "IN_PROGRESS",
        priority = "NORMAL",
        assigneeId = null,
        lastMessageId = null,
        lastMessageSnippet = null,
        lastMessageAuthor = null,
        lastMessageAt = null,
        unreadCount = unread,
        mentionCount = mentions,
        isPinned = pinned
    )

    private val items = listOf(
        item(1, unread = 2),
        item(2, mentions = 1),
        item(3, pinned = true),
        item(4)
    )

    @Test
    fun `chat workspace opens the first available task when none is selected`() {
        assertEquals(1L, defaultTaskChatId(selectedTaskId = null, chats = items))
        assertEquals(4L, defaultTaskChatId(selectedTaskId = 4L, chats = items))
        assertNull(defaultTaskChatId(selectedTaskId = null, chats = emptyList()))
    }

    @Test
    fun `list filters by unread mentions and pinned`() {
        assertEquals(listOf(1L), filterChatList(items, TaskChatListFilter.UNREAD, "").map { it.taskId })
        assertEquals(listOf(2L), filterChatList(items, TaskChatListFilter.MENTIONS, "").map { it.taskId })
        assertEquals(listOf(3L), filterChatList(items, TaskChatListFilter.PINNED, "").map { it.taskId })
        assertEquals(4, filterChatList(items, TaskChatListFilter.ALL, "").size)
    }

    @Test
    fun `pinned chats come first and query matches title or number`() {
        val filtered = filterChatList(items, TaskChatListFilter.ALL, "")
        assertEquals(3L, filtered.first().taskId)
        assertEquals(listOf(3L), filterChatList(items, TaskChatListFilter.ALL, "задача 3").map { it.taskId })
        assertEquals(listOf(2L), filterChatList(items, TaskChatListFilter.ALL, "t-2026-2").map { it.taskId })
    }

    @Test
    fun `autoscroll only at bottom or for own message`() {
        assertTrue(shouldAutoScrollToNew(lastVisibleIndex = 8, itemCountBeforeAppend = 10, isOwnMessage = false))
        assertFalse(shouldAutoScrollToNew(lastVisibleIndex = 3, itemCountBeforeAppend = 10, isOwnMessage = false))
        assertTrue(shouldAutoScrollToNew(lastVisibleIndex = 0, itemCountBeforeAppend = 10, isOwnMessage = true))
    }

    @Test
    fun `context panel collapsed below 1440 dp`() {
        assertTrue(taskChatContextCollapsed(1439))
        assertTrue(taskChatContextCollapsed(1100))
        assertFalse(taskChatContextCollapsed(1440))
        assertFalse(taskChatContextCollapsed(1920))
    }

    @Test
    fun `formatting parses bold italic and code`() {
        val segments = parseChatFormatting("Привет **жирный** и *курсив* и `код`!")
        assertEquals(
            listOf(
                ChatTextSegment("Привет ", Kind.PLAIN),
                ChatTextSegment("жирный", Kind.BOLD),
                ChatTextSegment(" и ", Kind.PLAIN),
                ChatTextSegment("курсив", Kind.ITALIC),
                ChatTextSegment(" и ", Kind.PLAIN),
                ChatTextSegment("код", Kind.CODE),
                ChatTextSegment("!", Kind.PLAIN)
            ),
            segments
        )
    }

    @Test
    fun `unmatched markers stay plain text`() {
        assertEquals(
            listOf(ChatTextSegment("это **не жирный", Kind.PLAIN)),
            parseChatFormatting("это **не жирный")
        )
        assertEquals(
            listOf(ChatTextSegment("и `не код", Kind.PLAIN)),
            parseChatFormatting("и `не код")
        )
    }

    @Test
    fun `mention query and insertion`() {
        assertNull(mentionQuery("просто текст"))
        assertNull(mentionQuery("почта user@example"))
        assertEquals("ив", mentionQuery("привет @ив"))
        val participants = listOf(
            UserOptionDto(1, "Иван Петров"),
            UserOptionDto(2, "Анна Смирнова")
        )
        assertEquals(listOf(1L), mentionSuggestions(participants, "@Ив").map { it.id })
        assertEquals("Смотри @Анна Смирнова ", insertMention("Смотри @Ан", "Анна Смирнова"))
    }

    @Test
    fun `snippet is single line and truncated`() {
        assertEquals("первая", chatSnippet("первая\nвторая"))
        assertEquals(80, chatSnippet("x".repeat(200)).length)
    }

    @Test
    fun `chat attachments collected without duplicates`() {
        fun attachment(id: Long) = AttachmentDto(
            id = id,
            entityType = AttachmentEntityType.TASK,
            entityId = 1,
            fileName = "f$id.txt",
            sizeBytes = 10,
            mimeType = null,
            sha256 = null,
            uploadedBy = 1,
            uploadedByName = null,
            uploadedAt = "2026-11-17T10:00:00Z"
        )
        fun message(id: Long, attachments: List<AttachmentDto>) = ChatMessageDto(
            id = id,
            taskId = 1,
            author = null,
            type = ChatMessageType.USER,
            content = "m$id",
            createdAt = "2026-11-17T10:00:00Z",
            attachments = attachments
        )
        val result = collectChatAttachments(
            listOf(message(1, listOf(attachment(1), attachment(2))), message(2, listOf(attachment(2), attachment(3))))
        )
        assertEquals(listOf(1L, 2L, 3L), result.map { it.id })
    }

    @Test
    fun `linked documents filtered by task links`() {
        fun document(id: Long, targetId: Long?) = DocumentDto(
            id = id,
            number = "DOC-$id",
            name = "Документ $id",
            docType = DocumentType.OTHER,
            description = null,
            authorId = null,
            ownerId = 1,
            status = DocumentStatus.ACTIVE,
            confidentiality = DocumentConfidentiality.INTERNAL,
            retentionUntil = null,
            currentVersionId = null,
            createdBy = 1,
            createdAt = "2026-11-17T10:00:00Z",
            updatedAt = "2026-11-17T10:00:00Z",
            version = 1,
            links = if (targetId == null) {
                emptyList()
            } else {
                listOf(
                    DocumentLinkDto(
                        id = id, documentId = id, linkType = DocumentLinkType.TASK,
                        targetId = targetId, createdBy = 1, createdAt = "2026-11-17T10:00:00Z"
                    )
                )
            }
        )
        val docs = listOf(document(1, targetId = 5), document(2, targetId = 6), document(3, targetId = null))
        assertEquals(listOf(1L), linkedTaskDocuments(docs, taskId = 5).map { it.id })
    }
}
