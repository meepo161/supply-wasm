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
import org.example.project.ui.tasks.canEditChatMessage
import org.example.project.ui.tasks.canMutateTaskChat
import org.example.project.ui.tasks.isPreviewableImage
import org.example.project.ui.tasks.restorePrependAnchor
import org.example.project.ui.tasks.firstUnreadMessageId
import org.example.project.ui.tasks.TaskChatScrollAnchor
import org.example.project.ui.tasks.restoreTaskChatPrependAnchor
import org.example.project.ui.tasks.shouldMarkTaskChatRead
import org.example.project.data.mergeTaskChatHistory
import org.example.project.data.validateAttachmentSelection
import org.example.project.ui.conversations.areImageDimensionsSafe
import org.example.project.ui.conversations.withGuardedEncodedImage
import org.example.project.dto.TaskStatus
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

    @Test
    fun `prepend restores the same visible anchor`() {
        assertEquals(7, restorePrependAnchor(firstVisibleIndex = 4, prependedCount = 3))
        assertEquals(3, restorePrependAnchor(firstVisibleIndex = 0, prependedCount = 3))
    }

    @Test
    fun `closed task forbids every chat mutation including own message edit`() {
        assertTrue(canMutateTaskChat(TaskStatus.IN_PROGRESS))
        assertFalse(canMutateTaskChat(TaskStatus.CLOSED))
        assertFalse(canMutateTaskChat(TaskStatus.CANCELLED))
        assertTrue(canEditChatMessage(isOwn = true, canWrite = true, revoked = false))
        assertFalse(canEditChatMessage(isOwn = false, canWrite = true, revoked = false))
        assertFalse(canEditChatMessage(isOwn = true, canWrite = false, revoked = false))
        assertFalse(canEditChatMessage(isOwn = true, canWrite = true, revoked = true))
    }

    @Test
    fun `image preview accepts bounded raster images only`() {
        assertTrue(isPreviewableImage("image/png", 1024))
        assertTrue(isPreviewableImage("image/jpeg", 5 * 1024 * 1024))
        assertFalse(isPreviewableImage("image/svg+xml", 1024))
        assertFalse(isPreviewableImage("text/html", 1024))
        assertFalse(isPreviewableImage("image/png", 11 * 1024 * 1024))
        assertFalse(isPreviewableImage(null, 1024))
    }

    @Test
    fun `formatting merges adjacent segments of the same kind`() {
        assertEquals(
            listOf(ChatTextSegment("onetwo", Kind.ITALIC)),
            parseChatFormatting("*one**two*")
        )
    }

    @Test
    fun `first unread divider starts before first incoming message only when chat had unread`() {
        val messages = listOf(10L to 2L, 11L to 1L, 12L to 2L)
        assertEquals(10L, firstUnreadMessageId(messages, currentUserId = 1, hasUnread = true))
        assertEquals(11L, firstUnreadMessageId(messages, currentUserId = 2, hasUnread = true))
        assertNull(firstUnreadMessageId(messages, currentUserId = 1, hasUnread = false))
    }

    @Test
    fun `task prepend restores first visible message id index and pixel offset`() {
        val anchor = TaskChatScrollAnchor(messageId = 200, index = 0, offset = 24)
        val restored = restoreTaskChatPrependAnchor(
            anchor = anchor,
            oldMessageIds = listOf(200L, 201L),
            newMessageIds = listOf(100L, 101L, 200L, 201L)
        )
        assertEquals(2, restored.index)
        assertEquals(24, restored.offset)
    }

    @Test
    fun `history merge replaces lifecycle version and never duplicates lazy keys`() {
        val old = ChatMessageDto(7, 1, null, ChatMessageType.USER, "old", "2026-11-19T10:00:00Z", version = 1)
        val edited = old.copy(content = "edited", editedAt = "2026-11-19T10:01:00Z", version = 2)
        val realtime = ChatMessageDto(8, 1, null, ChatMessageType.USER, "realtime", "2026-11-19T10:02:00Z")
        val merged = listOf(old, realtime).mergeTaskChatHistory(listOf(edited, realtime))
        assertEquals(listOf(7L, 8L), merged.map { it.id })
        assertEquals("edited", merged.single { it.id == 7L }.content)
        assertEquals(2L, merged.single { it.id == 7L }.version)
        assertEquals(merged.size, merged.map { it.id }.toSet().size)
    }

    @Test
    fun `read advances only for visible latest message`() {
        assertTrue(shouldMarkTaskChatRead(lastVisibleMessageId = 10, latestLoadedMessageId = 10, viewingLatest = true))
        assertFalse(shouldMarkTaskChatRead(lastVisibleMessageId = 9, latestLoadedMessageId = 10, viewingLatest = true))
        assertFalse(shouldMarkTaskChatRead(lastVisibleMessageId = 10, latestLoadedMessageId = 10, viewingLatest = false))
        assertFalse(shouldMarkTaskChatRead(lastVisibleMessageId = null, latestLoadedMessageId = 10, viewingLatest = true))
    }

    @Test
    fun `attachment selection validates count file size and total size before reading`() {
        assertNull(validateAttachmentSelection(List(2) { 25L * 1024 * 1024 }))
        assertEquals("Можно выбрать не более 5 файлов", validateAttachmentSelection(List(6) { 1L }))
        assertEquals("Файл превышает 25 МиБ", validateAttachmentSelection(listOf(25L * 1024 * 1024 + 1)))
        assertEquals("Общий размер файлов превышает 50 МиБ", validateAttachmentSelection(List(3) { 20L * 1024 * 1024 }))
    }

    @Test
    fun `image dimensions reject excessive pixels and overflow`() {
        assertTrue(areImageDimensionsSafe(5000, 5000))
        assertFalse(areImageDimensionsSafe(5001, 5000))
        assertFalse(areImageDimensionsSafe(0, 100))
        assertFalse(areImageDimensionsSafe(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun `repeated picker selections cannot exceed count or total limits`() {
        val firstFive = List(5) { 1L }
        assertEquals("Можно выбрать не более 5 файлов", validateAttachmentSelection(firstFive + 1L))
        val fiftyMiB = List(2) { 25L * 1024 * 1024 }
        assertEquals("Общий размер файлов превышает 50 МиБ", validateAttachmentSelection(fiftyMiB + 1L))
        assertNull(validateAttachmentSelection(List(4) { 1L } + 1L))
    }

    @Test
    fun `encoded image bomb is rejected before decoder invocation`() {
        val bombs = listOf(
            pngHeader(10_000, 10_000),
            jpegSofHeader(10_000, 10_000),
            webpVp8xHeader(6_000, 5_000)
        )
        bombs.forEach { bytes ->
            var decoderCalls = 0
            val result = withGuardedEncodedImage(bytes) {
                decoderCalls++
                "decoded"
            }
            assertNull(result)
            assertEquals(0, decoderCalls)
        }
    }

    @Test
    fun `valid tiny png reaches decoder and malformed header does not`() {
        assertEquals("decoded", withGuardedEncodedImage(pngHeader(1, 1)) { "decoded" })
        var decoderCalls = 0
        assertNull(withGuardedEncodedImage(byteArrayOf(1, 2, 3)) {
            decoderCalls++
            "decoded"
        })
        assertEquals(0, decoderCalls)
    }

    @Test
    fun `vp8l high height bits reject oversized image before decoder`() {
        var decoderCalls = 0
        val result = withGuardedEncodedImage(webpVp8lHeader(width = 2000, height = 13000)) {
            decoderCalls++
            "decoded"
        }
        assertNull(result)
        assertEquals(0, decoderCalls)
        assertEquals("decoded", withGuardedEncodedImage(webpVp8lHeader(2, 3)) { "decoded" })
    }
}

private fun pngHeader(width: Int, height: Int): ByteArray {
    val bytes = ByteArray(24)
    bytes[0] = 0x89.toByte()
    bytes[1] = 0x50
    bytes[2] = 0x4e
    bytes[3] = 0x47
    bytes[4] = 0x0d
    bytes[5] = 0x0a
    bytes[6] = 0x1a
    bytes[7] = 0x0a
    bytes[11] = 13
    bytes[12] = 0x49
    bytes[13] = 0x48
    bytes[14] = 0x44
    bytes[15] = 0x52
    width.toBytesBE(bytes, 16)
    height.toBytesBE(bytes, 20)
    return bytes
}

private fun jpegSofHeader(width: Int, height: Int): ByteArray {
    val bytes = ByteArray(23)
    bytes[0] = 0xff.toByte()
    bytes[1] = 0xd8.toByte()
    bytes[2] = 0xff.toByte()
    bytes[3] = 0xc0.toByte()
    bytes[4] = 0
    bytes[5] = 17
    bytes[6] = 8
    height.toBytesBE(bytes, 7)
    width.toBytesBE(bytes, 9)
    return bytes
}

private fun webpVp8xHeader(width: Int, height: Int): ByteArray {
    val bytes = ByteArray(30)
    "RIFF".encodeToByteArray().copyInto(bytes)
    "WEBP".encodeToByteArray().copyInto(bytes, 8)
    "VP8X".encodeToByteArray().copyInto(bytes, 12)
    bytes[16] = 10
    val w = width - 1
    val h = height - 1
    bytes[24] = w.toByte()
    bytes[25] = (w ushr 8).toByte()
    bytes[26] = (w ushr 16).toByte()
    bytes[27] = h.toByte()
    bytes[28] = (h ushr 8).toByte()
    bytes[29] = (h ushr 16).toByte()
    return bytes
}

private fun webpVp8lHeader(width: Int, height: Int): ByteArray {
    val bytes = ByteArray(30)
    "RIFF".encodeToByteArray().copyInto(bytes)
    "WEBP".encodeToByteArray().copyInto(bytes, 8)
    "VP8L".encodeToByteArray().copyInto(bytes, 12)
    bytes[16] = 5
    bytes[20] = 0x2f
    val bits = (width - 1) or ((height - 1) shl 14)
    bytes[21] = bits.toByte()
    bytes[22] = (bits ushr 8).toByte()
    bytes[23] = (bits ushr 16).toByte()
    bytes[24] = (bits ushr 24).toByte()
    return bytes
}

private fun Int.toBytesBE(target: ByteArray, offset: Int) {
    target[offset] = (this ushr 24).toByte()
    target[offset + 1] = (this ushr 16).toByte()
    target[offset + 2] = (this ushr 8).toByte()
    target[offset + 3] = toByte()
}
