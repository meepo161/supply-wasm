package org.example.project

import org.example.project.dto.ChatAuthorDto
import org.example.project.dto.ConversationDto
import org.example.project.dto.ConversationKind
import org.example.project.dto.ConversationListFilter
import org.example.project.dto.ConversationListItemDto
import org.example.project.dto.ConversationMemberDto
import org.example.project.dto.UserDto
import org.example.project.dto.UserOptionDto
import org.example.project.ui.conversations.ConversationEmptyState
import org.example.project.ui.conversations.ConversationWorkspaceMode
import org.example.project.ui.conversations.ConversationsUiState
import org.example.project.ui.conversations.applyConversationDialogState
import org.example.project.ui.conversations.conversationEmptyState
import org.example.project.ui.conversations.conversationTimeLabel
import org.example.project.ui.conversations.conversationWorkspaceMode
import org.example.project.ui.conversations.displayTitle
import org.example.project.ui.conversations.filterEmployeeOptions
import org.example.project.ui.conversations.localizedName
import org.example.project.ui.conversations.mentionBadgeLabel
import org.example.project.ui.conversations.previewText
import org.example.project.ui.conversations.showConversationList
import org.example.project.ui.conversations.unreadBadgeLabel
import org.example.project.ui.conversations.withMergedEntries
import org.example.project.ui.conversations.ConversationLocalStateStore
import org.example.project.ui.conversations.conversationCommandSignature
import org.example.project.data.AttachmentFileData
import org.example.project.ui.main.NavigationDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationUiLogicTest {

    @Test
    fun `wide chat workspace keeps the conversation list beside the detail pane`() {
        assertEquals(ConversationWorkspaceMode.SPLIT, conversationWorkspaceMode(840))
        assertEquals(ConversationWorkspaceMode.SPLIT, conversationWorkspaceMode(1440))
        assertTrue(showConversationList(ConversationWorkspaceMode.SPLIT, hasSelection = true))
    }

    @Test
    fun `narrow chat workspace switches between list and detail`() {
        assertEquals(ConversationWorkspaceMode.SINGLE_PANE, conversationWorkspaceMode(839))
        assertEquals(ConversationWorkspaceMode.SINGLE_PANE, conversationWorkspaceMode(640))
        assertTrue(showConversationList(ConversationWorkspaceMode.SINGLE_PANE, hasSelection = false))
        assertFalse(showConversationList(ConversationWorkspaceMode.SINGLE_PANE, hasSelection = true))
    }

    @Test
    fun `all server list filters have visible russian labels`() {
        assertEquals("Все", ConversationListFilter.ALL.localizedName())
        assertEquals("Непрочитанные", ConversationListFilter.UNREAD.localizedName())
        assertEquals("Упоминания", ConversationListFilter.MENTIONS.localizedName())
        assertEquals("Архив", ConversationListFilter.ARCHIVED.localizedName())
        assertEquals("Личные", ConversationKind.DIRECT.localizedName())
        assertEquals("Групповые", ConversationKind.GROUP.localizedName())
        assertEquals("Проекты", ConversationKind.PROJECT.localizedName())
        assertEquals("Задачи", ConversationKind.TASK.localizedName())
    }

    @Test
    fun `preview contains author and readable fallback`() {
        val item = ConversationListItemDto(
            kind = ConversationKind.GROUP,
            id = 3,
            lastMessagePreview = "Проверьте склад",
            lastMessageAuthor = ChatAuthorDto(userId = 2, fullName = "Анна Смирнова")
        )
        assertEquals("Анна Смирнова: Проверьте склад", item.previewText(currentUserId = 1))
        assertEquals("Вы: Проверьте склад", item.previewText(currentUserId = 2))
        assertEquals("Сообщений пока нет", item.copy(lastMessagePreview = null).previewText(1))
    }

    @Test
    fun `badges and time are textual and not colour only`() {
        assertEquals("Непрочитанные: 4", unreadBadgeLabel(4))
        assertEquals("Упоминания: 2", mentionBadgeLabel(2))
        assertEquals("19.11 12:05", conversationTimeLabel("2026-11-19T12:05:43Z"))
        assertEquals("", conversationTimeLabel(null))
    }

    @Test
    fun `empty filtered list differs from no chats`() {
        assertEquals(ConversationEmptyState.NO_CHATS, conversationEmptyState("", null, ConversationListFilter.ALL))
        assertEquals(
            ConversationEmptyState.NO_MATCHES,
            conversationEmptyState("склад", null, ConversationListFilter.ALL)
        )
        assertEquals(
            ConversationEmptyState.NO_MATCHES,
            conversationEmptyState("", ConversationKind.TASK, ConversationListFilter.ALL)
        )
        assertEquals(
            ConversationEmptyState.NO_MATCHES,
            conversationEmptyState("", null, ConversationListFilter.UNREAD)
        )
    }

    @Test
    fun `employee search uses full name and does not mutate selection`() {
        val users = listOf(
            UserOptionDto(1, "Иван Петров"),
            UserOptionDto(2, "Анна Смирнова"),
            UserOptionDto(3, "Пётр Иванов")
        )
        assertEquals(listOf(2L), filterEmployeeOptions(users, "сМИР").map { it.id })
        assertEquals(listOf(1L, 3L), filterEmployeeOptions(users, "пет").map { it.id })
        assertEquals(users, filterEmployeeOptions(users, "  "))
    }

    @Test
    fun `dialog state success updates only matching record and preserves server order`() {
        val items = listOf(
            ConversationListItemDto(ConversationKind.TASK, 7, title = "first"),
            ConversationListItemDto(ConversationKind.GROUP, 7, title = "second")
        )
        val updated = items[1].copy(isMuted = true)
        val result = applyConversationDialogState(items, updated)
        assertEquals(listOf(ConversationKind.TASK, ConversationKind.GROUP), result.map { it.kind })
        assertFalse(result[0].isMuted)
        assertTrue(result[1].isMuted)
    }

    @Test
    fun `server ordering is not replaced by local kind sorting`() {
        val items = listOf(
            ConversationListItemDto(ConversationKind.TASK, 7, title = "pinned task"),
            ConversationListItemDto(ConversationKind.DIRECT, 2, title = "later direct"),
            ConversationListItemDto(ConversationKind.GROUP, 9, title = "older group")
        )
        val user = UserDto(id = 1, login = "user", fullName = "User", permissions = emptyList())
        val state = ConversationsUiState(items = items).withMergedEntries(user)
        assertEquals(items.map { it.kind }, state.entries.map { it.kind })
        assertEquals(items.map { it.id }, state.items.map { it.id })
    }

    private fun conversation(
        kind: ConversationKind,
        title: String? = null,
        members: List<ConversationMemberDto> = emptyList(),
        projectId: Long? = null
    ) = ConversationDto(id = 1, kind = kind, title = title, projectId = projectId, members = members)

    @Test
    fun `direct chat without title shows the other member name`() {
        val chat = conversation(
            ConversationKind.DIRECT,
            members = listOf(
                ConversationMemberDto(1, "Иван Петров"),
                ConversationMemberDto(2, "Анна Смирнова")
            )
        )
        assertEquals("Анна Смирнова", chat.displayTitle(currentUserId = 1))
        assertEquals("Иван Петров", chat.displayTitle(currentUserId = 2))
    }

    @Test
    fun `explicit title wins over members`() {
        val chat = conversation(ConversationKind.GROUP, title = "Снабжение")
        assertEquals("Снабжение", chat.displayTitle(currentUserId = 1))
    }

    @Test
    fun `fallback titles for empty direct group and project chats`() {
        assertEquals("Личный чат", conversation(ConversationKind.DIRECT).displayTitle(1))
        assertEquals("Групповой чат", conversation(ConversationKind.GROUP).displayTitle(1))
        assertEquals("Обсуждение проекта", conversation(ConversationKind.PROJECT).displayTitle(1))
    }

    @Test
    fun `chat destination is visible to every user`() {
        val plainUser = UserDto(id = 1, login = "user", fullName = "User", permissions = emptyList())
        assertTrue(NavigationDestination.CHAT.isVisibleTo(plainUser))
    }

    @Test
    fun `multi file draft keeps all selections and removes only requested file`() {
        val store = ConversationLocalStateStore()
        val first = AttachmentFileData("a.txt", "text/plain", byteArrayOf(1))
        val second = AttachmentFileData("b.png", "image/png", byteArrayOf(2))
        store.setFiles("chat", listOf(first))
        store.setFiles("chat", store.files("chat") + second)
        assertEquals(listOf("a.txt", "b.png"), store.files("chat").map { it.fileName })
        store.setFiles("chat", store.files("chat").filterNot { it === first })
        assertEquals(listOf("b.png"), store.files("chat").map { it.fileName })
        store.setDraft("chat", "draft")
        assertEquals("draft", store.draft("chat"))
    }

    @Test
    fun `success of first task command does not clear second signature key`() {
        val store = ConversationLocalStateStore()
        val file = AttachmentFileData("a.txt", "text/plain", byteArrayOf(1))
        val firstSignature = conversationCommandSignature("A1", null, listOf(file))
        val secondSignature = conversationCommandSignature("A2", null, emptyList())
        val firstKey = store.commandKey("task-1", firstSignature) { "key-A1" }
        val secondKey = store.commandKey("task-1", secondSignature) { "key-A2" }

        store.clearComposerAfterSuccess("task-1", firstSignature)

        assertEquals(secondKey, store.commandKeyOrNull("task-1"))
        assertEquals(secondKey, store.commandKey("task-1", secondSignature) { "replacement" })
        store.setDraft("task-1", "A2")
        store.setFiles("task-1", emptyList())
        store.clearComposerAfterSuccess("task-1", secondSignature)
        assertEquals(null, store.commandKeyOrNull("task-1"))
        assertTrue(firstKey != secondKey)
    }
}
