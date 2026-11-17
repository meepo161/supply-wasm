package org.example.project.data

import org.example.project.dto.ChatMessageDto
import org.example.project.dto.ConversationDto
import org.example.project.dto.ConversationKind
import org.example.project.dto.ConversationMemberDto
import org.example.project.dto.ConversationMessageDto
import org.example.project.dto.ProjectDto
import org.example.project.dto.TaskDto
import org.example.project.dto.TaskStatus
import org.example.project.dto.UserDto
import org.example.project.dto.UserOptionDto
import org.example.project.dto.hasPermission

/**
 * Виртуальные записи единого списка Chat: задачные и проектные обсуждения
 * показываются рядом с DIRECT/GROUP, но живут в своих API. Отрицательный id
 * помечает виртуальную запись: для TASK это -task.id (сообщения идут через
 * Task Chat API), для PROJECT — -project.id до get-or-create на сервере.
 */
fun ChatMessageDto.toConversationMessage(): ConversationMessageDto =
    ConversationMessageDto(
        id = id,
        conversationId = -taskId,
        author = author,
        content = content,
        createdAt = createdAt,
        attachments = attachments
    )

fun isVirtualTaskConversationId(id: Long): Boolean = id < 0

internal fun conversationMessagePath(conversationId: Long): String =
    if (conversationId < 0) "/api/tasks/${-conversationId}/chat/messages"
    else "/api/conversations/$conversationId/messages"

fun TaskDto.isClosedForChat(): Boolean =
    status == TaskStatus.CLOSED || status == TaskStatus.CANCELLED

/** Право писать в задачный чат: активная задача и (tasks:write_any или участник с рабочей ролью). */
fun TaskDto.canWriteChat(user: UserDto): Boolean =
    !isClosedForChat() && (
        user.hasPermission("tasks", "write_any") ||
            initiatorId == user.id ||
            assigneeId == user.id ||
            reviewerId == user.id ||
            user.id in coexecutorIds
        )

fun TaskDto.chatMemberIds(): List<Long> =
    (listOf(initiatorId) + listOfNotNull(assigneeId, reviewerId) + coexecutorIds + observerIds).distinct()

fun TaskDto.toVirtualConversation(user: UserDto, users: List<UserOptionDto>): ConversationDto =
    ConversationDto(
        id = -id,
        kind = ConversationKind.TASK,
        title = "$number · $title",
        taskId = id,
        members = chatMemberIds().map { memberId ->
            ConversationMemberDto(
                userId = memberId,
                fullName = users.find { it.id == memberId }?.fullName ?: "ID $memberId"
            )
        },
        canWrite = canWriteChat(user)
    )

fun ProjectDto.toVirtualConversation(): ConversationDto =
    ConversationDto(
        id = -id,
        kind = ConversationKind.PROJECT,
        title = "$number · $name",
        projectId = id,
        members = members.map { ConversationMemberDto(userId = it.userId, fullName = it.fullName) },
        canWrite = true
    )

fun ProjectDto.isVisibleTo(user: UserDto): Boolean =
    ownerId == user.id || members.any { it.userId == user.id }

/**
 * Стабильный ключ записи списка: виртуальные id задач/проектов отрицательные
 * и могут совпадать между kind, поэтому ключ включает вид.
 */
fun conversationKey(kind: ConversationKind, id: Long): String = "$kind:$id"

/** Защита от поздних ответов: ответ применяется только к всё ещё выбранному разговору. */
fun isSameConversation(selected: ConversationDto?, id: Long, kind: ConversationKind): Boolean =
    selected != null && selected.id == id && selected.kind == kind
