package org.example.project.repository

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.project.dto.AssistantAttachmentDto
import org.example.project.dto.AssistantConversationDetailDto
import org.example.project.dto.AssistantConversationSummaryDto
import org.example.project.dto.AssistantDraftDto
import org.example.project.dto.AssistantMessageDto
import org.example.project.dto.AssistantRole
import org.example.project.exception.NotFoundException
import org.example.project.generated.jooq.tables.AssistantConversations.ASSISTANT_CONVERSATIONS
import org.example.project.generated.jooq.tables.AssistantMessages.ASSISTANT_MESSAGES
import org.jooq.DSLContext
import java.time.OffsetDateTime

class AssistantConversationRepository(private val dsl: DSLContext) {

    private val json = Json { ignoreUnknownKeys = true }

    fun listByUser(userId: Long, limit: Int = 20): List<AssistantConversationSummaryDto> {
        return dsl.selectFrom(ASSISTANT_CONVERSATIONS)
            .where(ASSISTANT_CONVERSATIONS.USER_ID.eq(userId))
            .orderBy(ASSISTANT_CONVERSATIONS.UPDATED_AT.desc(), ASSISTANT_CONVERSATIONS.ID.desc())
            .limit(limit)
            .fetch()
            .map { rec ->
                AssistantConversationSummaryDto(
                    id = rec.id!!,
                    title = rec.title!!,
                    updatedAt = rec.updatedAt?.toString()?.take(16)?.replace('T', ' ') ?: ""
                )
            }
    }

    fun getDetail(conversationId: Long, userId: Long): AssistantConversationDetailDto {
        val conv = dsl.selectFrom(ASSISTANT_CONVERSATIONS)
            .where(ASSISTANT_CONVERSATIONS.ID.eq(conversationId))
            .and(ASSISTANT_CONVERSATIONS.USER_ID.eq(userId))
            .fetchOne()
            ?: throw NotFoundException("AssistantConversation", conversationId)

        val messages = dsl.selectFrom(ASSISTANT_MESSAGES)
            .where(ASSISTANT_MESSAGES.CONVERSATION_ID.eq(conversationId))
            .orderBy(ASSISTANT_MESSAGES.ID.asc())
            .fetch()
            .map { m ->
                val attachments = m.attachmentsJson?.let { raw ->
                    runCatching { json.decodeFromString<List<AssistantAttachmentDto>>(raw) }.getOrElse { emptyList() }
                } ?: emptyList()
                val draft = m.draftJson?.let { raw ->
                    runCatching { json.decodeFromString<AssistantDraftDto>(raw) }.getOrNull()
                }
                AssistantMessageDto(
                    role = runCatching { AssistantRole.valueOf(m.role!!) }.getOrDefault(AssistantRole.ASSISTANT),
                    content = m.content!!,
                    toolUsed = m.toolUsed,
                    attachments = attachments,
                    draft = draft
                )
            }

        return AssistantConversationDetailDto(
            id = conv.id!!,
            title = conv.title!!,
            messages = messages
        )
    }

    fun createConversation(userId: Long, title: String): Long {
        val now = OffsetDateTime.now()
        val rec = dsl.insertInto(ASSISTANT_CONVERSATIONS)
            .set(ASSISTANT_CONVERSATIONS.USER_ID, userId)
            .set(ASSISTANT_CONVERSATIONS.TITLE, title.take(120))
            .set(ASSISTANT_CONVERSATIONS.CREATED_AT, now)
            .set(ASSISTANT_CONVERSATIONS.UPDATED_AT, now)
            .returningResult(ASSISTANT_CONVERSATIONS.ID)
            .fetchOne()!!
        return rec.get(ASSISTANT_CONVERSATIONS.ID)!!
    }

    fun appendMessage(conversationId: Long, userId: Long, message: AssistantMessageDto) {
        val exists = dsl.fetchExists(
            dsl.selectOne()
                .from(ASSISTANT_CONVERSATIONS)
                .where(ASSISTANT_CONVERSATIONS.ID.eq(conversationId))
                .and(ASSISTANT_CONVERSATIONS.USER_ID.eq(userId))
        )
        if (!exists) throw NotFoundException("AssistantConversation", conversationId)

        val now = OffsetDateTime.now()
        dsl.insertInto(ASSISTANT_MESSAGES)
            .set(ASSISTANT_MESSAGES.CONVERSATION_ID, conversationId)
            .set(ASSISTANT_MESSAGES.ROLE, message.role.name)
            .set(ASSISTANT_MESSAGES.CONTENT, message.content)
            .set(ASSISTANT_MESSAGES.TOOL_USED, message.toolUsed)
            .set(
                ASSISTANT_MESSAGES.ATTACHMENTS_JSON,
                message.attachments.takeIf { it.isNotEmpty() }?.let { json.encodeToString(it) }
            )
            .set(
                ASSISTANT_MESSAGES.DRAFT_JSON,
                message.draft?.let { json.encodeToString(it) }
            )
            .set(ASSISTANT_MESSAGES.CREATED_AT, now)
            .execute()

        dsl.update(ASSISTANT_CONVERSATIONS)
            .set(ASSISTANT_CONVERSATIONS.UPDATED_AT, now)
            .where(ASSISTANT_CONVERSATIONS.ID.eq(conversationId))
            .execute()
    }

    fun updateDraftExecuted(conversationId: Long, userId: Long, updatedDraft: AssistantDraftDto) {
        val exists = dsl.fetchExists(
            dsl.selectOne()
                .from(ASSISTANT_CONVERSATIONS)
                .where(ASSISTANT_CONVERSATIONS.ID.eq(conversationId))
                .and(ASSISTANT_CONVERSATIONS.USER_ID.eq(userId))
        )
        if (!exists) return

        val messages = dsl.selectFrom(ASSISTANT_MESSAGES)
            .where(ASSISTANT_MESSAGES.CONVERSATION_ID.eq(conversationId))
            .and(ASSISTANT_MESSAGES.DRAFT_JSON.isNotNull)
            .fetch()

        messages.forEach { m ->
            val parsed = m.draftJson?.let { raw ->
                runCatching { json.decodeFromString<AssistantDraftDto>(raw) }.getOrNull()
            }
            if (parsed?.draftId == updatedDraft.draftId) {
                dsl.update(ASSISTANT_MESSAGES)
                    .set(ASSISTANT_MESSAGES.DRAFT_JSON, json.encodeToString(updatedDraft))
                    .where(ASSISTANT_MESSAGES.ID.eq(m.id))
                    .execute()
            }
        }
    }

    fun deleteConversation(conversationId: Long, userId: Long) {
        val deleted = dsl.deleteFrom(ASSISTANT_CONVERSATIONS)
            .where(ASSISTANT_CONVERSATIONS.ID.eq(conversationId))
            .and(ASSISTANT_CONVERSATIONS.USER_ID.eq(userId))
            .execute()
        if (deleted == 0) throw NotFoundException("AssistantConversation", conversationId)
    }
}
