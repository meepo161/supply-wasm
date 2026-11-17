package org.example.project

import org.example.project.data.conversationKey
import org.example.project.data.conversationMessagePath
import org.example.project.data.dispositionSafe
import org.example.project.data.isSameConversation
import org.example.project.data.isVisibleTo
import org.example.project.data.toConversationMessage
import org.example.project.data.toVirtualConversation
import org.example.project.dto.ChatMessageDto
import org.example.project.dto.ChatMessageType
import org.example.project.dto.ConversationDto
import org.example.project.dto.ConversationKind
import org.example.project.dto.ProjectDto
import org.example.project.dto.ProjectMemberDto
import org.example.project.dto.TaskArea
import org.example.project.dto.TaskDto
import org.example.project.dto.TaskStatus
import org.example.project.dto.TaskWorkType
import org.example.project.dto.UserDto
import org.example.project.dto.UserOptionDto
import org.example.project.ui.conversations.formatAttachmentSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationUnifiedListTest {

    private fun user(id: Long, vararg permissions: String) = UserDto(
        id = id,
        login = "user$id",
        fullName = "User $id",
        permissions = permissions.toList()
    )

    private fun task(
        id: Long,
        status: TaskStatus = TaskStatus.IN_PROGRESS,
        initiatorId: Long = 1,
        assigneeId: Long? = null,
        observerIds: List<Long> = emptyList()
    ) = TaskDto(
        id = id,
        number = "T-2026-$id",
        area = TaskArea.PERSONAL,
        status = status,
        type = TaskWorkType.GENERAL,
        title = "Задача $id",
        initiatorId = initiatorId,
        assigneeId = assigneeId,
        observerIds = observerIds,
        createdBy = initiatorId
    )

    private val options = listOf(
        UserOptionDto(1, "Иван Петров"),
        UserOptionDto(2, "Анна Смирнова")
    )

    @Test
    fun `virtual task conversation uses negative id and task chat coordinates`() {
        val conversation = task(id = 5, assigneeId = 2).toVirtualConversation(user(2), options)
        assertEquals(-5L, conversation.id)
        assertEquals(ConversationKind.TASK, conversation.kind)
        assertEquals(5L, conversation.taskId)
        assertEquals("T-2026-5 · Задача 5", conversation.title)
        assertEquals(listOf(1L, 2L), conversation.members.map { it.userId })
        assertEquals("Анна Смирнова", conversation.members[1].fullName)
        assertTrue(conversation.canWrite)
    }

    @Test
    fun `closed task chat is read only even for participant`() {
        val closed = task(id = 6, status = TaskStatus.CLOSED, assigneeId = 2)
        assertFalse(closed.toVirtualConversation(user(2), options).canWrite)
        assertFalse(closed.toVirtualConversation(user(9, "tasks:write_any"), options).canWrite)
    }

    @Test
    fun `observer cannot write but write_any can`() {
        val observed = task(id = 6, observerIds = listOf(3))
        assertFalse(observed.toVirtualConversation(user(3), options).canWrite)
        assertTrue(observed.toVirtualConversation(user(4, "tasks:write_any"), options).canWrite)
    }

    @Test
    fun `virtual project conversation carries project id and members`() {
        val project = ProjectDto(
            id = 7,
            number = "PRJ-2026-7",
            name = "Проект 7",
            ownerId = 1,
            createdBy = 1,
            members = listOf(ProjectMemberDto(1, "Иван Петров"), ProjectMemberDto(2, "Анна Смирнова"))
        )
        val conversation = project.toVirtualConversation()
        assertEquals(-7L, conversation.id)
        assertEquals(ConversationKind.PROJECT, conversation.kind)
        assertEquals(7L, conversation.projectId)
        assertEquals("PRJ-2026-7 · Проект 7", conversation.title)
        assertEquals(2, conversation.members.size)
        assertTrue(conversation.canWrite)
        assertTrue(project.isVisibleTo(user(1)))
        assertTrue(project.isVisibleTo(user(2)))
        assertFalse(project.isVisibleTo(user(9)))
    }

    @Test
    fun `task chat message maps to conversation message with negative conversation id`() {
        val chatMessage = ChatMessageDto(
            id = 10,
            taskId = 5,
            author = null,
            type = ChatMessageType.USER,
            content = "привет",
            createdAt = "2026-11-17T10:00:00Z"
        )
        val mapped = chatMessage.toConversationMessage()
        assertEquals(10L, mapped.id)
        assertEquals(-5L, mapped.conversationId)
        assertEquals("привет", mapped.content)
        assertTrue(mapped.attachments.isEmpty())
    }

    @Test
    fun `conversation keys do not collide across kinds`() {
        assertEquals("TASK:-5", conversationKey(ConversationKind.TASK, -5))
        assertEquals("PROJECT:-5", conversationKey(ConversationKind.PROJECT, -5))
        assertFalse(conversationKey(ConversationKind.TASK, -5) == conversationKey(ConversationKind.PROJECT, -5))
    }

    @Test
    fun `late response guard checks id and kind`() {
        val selected = ConversationDto(id = 3, kind = ConversationKind.GROUP)
        assertTrue(isSameConversation(selected, 3, ConversationKind.GROUP))
        assertFalse(isSameConversation(selected, 3, ConversationKind.DIRECT))
        assertFalse(isSameConversation(selected, 4, ConversationKind.GROUP))
        assertFalse(isSameConversation(null, 3, ConversationKind.GROUP))
    }

    @Test
    fun `disposition safe file name strips quotes slashes and newlines`() {
        assertEquals("repo_rt.pdf", "repo\"rt.pdf".dispositionSafe())
        assertEquals("folder_file.txt", "folder\\file.txt".dispositionSafe())
        assertEquals("a_b.txt", "a\nb.txt".dispositionSafe())
    }

    @Test
    fun `message path keeps virtual task id sign handling in one place`() {
        assertEquals("/api/tasks/3/chat/messages", conversationMessagePath(-3))
        assertEquals("/api/conversations/3/messages", conversationMessagePath(3))
    }

    @Test
    fun `attachment size formatting`() {
        assertEquals("512 Б", formatAttachmentSize(512))
        assertEquals("2 КБ", formatAttachmentSize(2048))
        assertEquals("5 МБ", formatAttachmentSize(5L * 1024 * 1024))
    }
}
