package org.example.project

import org.example.project.dto.ConversationDto
import org.example.project.dto.ConversationKind
import org.example.project.dto.ConversationMemberDto
import org.example.project.dto.UserDto
import org.example.project.ui.conversations.displayTitle
import org.example.project.ui.conversations.ConversationWorkspaceMode
import org.example.project.ui.conversations.conversationWorkspaceMode
import org.example.project.ui.conversations.localizedName
import org.example.project.ui.main.NavigationDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationUiLogicTest {

    @Test
    fun `wide chat workspace keeps the conversation list beside the detail pane`() {
        assertEquals(ConversationWorkspaceMode.SPLIT, conversationWorkspaceMode(840))
        assertEquals(ConversationWorkspaceMode.SPLIT, conversationWorkspaceMode(1440))
    }

    @Test
    fun `narrow chat workspace switches between list and detail`() {
        assertEquals(ConversationWorkspaceMode.SINGLE_PANE, conversationWorkspaceMode(839))
        assertEquals(ConversationWorkspaceMode.SINGLE_PANE, conversationWorkspaceMode(640))
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
    fun `kind labels are localized`() {
        assertEquals("Личный", ConversationKind.DIRECT.localizedName())
        assertEquals("Групповой", ConversationKind.GROUP.localizedName())
        assertEquals("Проектный", ConversationKind.PROJECT.localizedName())
    }

    @Test
    fun `chat destination is visible to every user`() {
        val plainUser = UserDto(id = 1, login = "user", fullName = "User", permissions = emptyList())
        assertTrue(NavigationDestination.CHAT.isVisibleTo(plainUser))
    }
}
