package org.example.project.repository

import org.example.project.generated.jooq.tables.ConversationMessages.CONVERSATION_MESSAGES
import org.example.project.generated.jooq.tables.ConversationParticipants.CONVERSATION_PARTICIPANTS
import org.example.project.generated.jooq.tables.Conversations.CONVERSATIONS
import org.example.project.generated.jooq.tables.ProjectMembers.PROJECT_MEMBERS
import org.example.project.generated.jooq.tables.Projects.PROJECTS
import org.example.project.generated.jooq.tables.Users.USERS
import org.example.project.generated.jooq.tables.records.ConversationMessagesRecord
import org.jooq.DSLContext
import org.jooq.Record
import java.time.OffsetDateTime

class ConversationRepository(private val dsl: DSLContext) {

    data class ConversationRow(
        val id: Long,
        val kind: String,
        val title: String?,
        val projectId: Long?,
        val directKey: String?
    )

    data class MessageRow(
        val id: Long,
        val conversationId: Long,
        val authorId: Long?,
        val authorFullName: String?,
        val content: String,
        val createdAt: OffsetDateTime
    )

    fun findById(id: Long): ConversationRow? =
        dsl.selectFrom(CONVERSATIONS).where(CONVERSATIONS.ID.eq(id)).fetchOne { mapConversationRow(it) }

    fun findByDirectKey(directKey: String): ConversationRow? =
        dsl.selectFrom(CONVERSATIONS).where(CONVERSATIONS.DIRECT_KEY.eq(directKey)).fetchOne { mapConversationRow(it) }

    fun findByProjectId(projectId: Long): ConversationRow? =
        dsl.selectFrom(CONVERSATIONS).where(CONVERSATIONS.PROJECT_ID.eq(projectId)).fetchOne { mapConversationRow(it) }

    fun listForUser(userId: Long): List<ConversationRow> {
        val directGroup = dsl.select(CONVERSATIONS.fields().toList())
            .from(CONVERSATIONS)
            .join(CONVERSATION_PARTICIPANTS)
            .on(CONVERSATION_PARTICIPANTS.CONVERSATION_ID.eq(CONVERSATIONS.ID))
            .where(CONVERSATION_PARTICIPANTS.USER_ID.eq(userId))
            .and(CONVERSATIONS.KIND.`in`("DIRECT", "GROUP"))
            .fetch { mapConversationRow(it) }
        val projectChats = dsl.select(CONVERSATIONS.fields().toList())
            .from(CONVERSATIONS)
            .join(PROJECTS).on(PROJECTS.ID.eq(CONVERSATIONS.PROJECT_ID))
            .leftJoin(PROJECT_MEMBERS)
            .on(PROJECT_MEMBERS.PROJECT_ID.eq(PROJECTS.ID).and(PROJECT_MEMBERS.USER_ID.eq(userId)))
            .where(CONVERSATIONS.KIND.eq("PROJECT"))
            .and(PROJECTS.OWNER_ID.eq(userId).or(PROJECT_MEMBERS.USER_ID.isNotNull))
            .fetch { mapConversationRow(it) }
        return (directGroup + projectChats).sortedByDescending { it.id }
    }

    fun isParticipant(conversationId: Long, userId: Long): Boolean =
        dsl.fetchExists(
            dsl.selectOne()
                .from(CONVERSATION_PARTICIPANTS)
                .where(CONVERSATION_PARTICIPANTS.CONVERSATION_ID.eq(conversationId))
                .and(CONVERSATION_PARTICIPANTS.USER_ID.eq(userId))
        )

    fun isProjectMember(projectId: Long, userId: Long): Boolean =
        dsl.fetchExists(
            dsl.selectOne()
                .from(PROJECT_MEMBERS)
                .where(PROJECT_MEMBERS.PROJECT_ID.eq(projectId))
                .and(PROJECT_MEMBERS.USER_ID.eq(userId))
        )

    fun isProjectOwner(projectId: Long, userId: Long): Boolean =
        dsl.fetchExists(
            dsl.selectOne()
                .from(PROJECTS)
                .where(PROJECTS.ID.eq(projectId))
                .and(PROJECTS.OWNER_ID.eq(userId))
        )

    fun isProjectMemberOrOwner(projectId: Long, userId: Long): Boolean =
        isProjectMember(projectId, userId) || isProjectOwner(projectId, userId)

    fun listParticipantMembers(conversationId: Long): List<Pair<Long, String>> =
        dsl.select(CONVERSATION_PARTICIPANTS.USER_ID, USERS.FULL_NAME)
            .from(CONVERSATION_PARTICIPANTS)
            .join(USERS).on(USERS.ID.eq(CONVERSATION_PARTICIPANTS.USER_ID))
            .where(CONVERSATION_PARTICIPANTS.CONVERSATION_ID.eq(conversationId))
            .orderBy(CONVERSATION_PARTICIPANTS.USER_ID.asc())
            .fetch { it.get(CONVERSATION_PARTICIPANTS.USER_ID)!! to it.get(USERS.FULL_NAME)!! }

    fun listProjectMembers(projectId: Long): List<Pair<Long, String>> =
        dsl.select(PROJECT_MEMBERS.USER_ID, USERS.FULL_NAME)
            .from(PROJECT_MEMBERS)
            .join(USERS).on(USERS.ID.eq(PROJECT_MEMBERS.USER_ID))
            .where(PROJECT_MEMBERS.PROJECT_ID.eq(projectId))
            .orderBy(PROJECT_MEMBERS.USER_ID.asc())
            .fetch { it.get(PROJECT_MEMBERS.USER_ID)!! to it.get(USERS.FULL_NAME)!! }

    fun listProjectMembersWithOwner(projectId: Long): List<Pair<Long, String>> {
        val members = listProjectMembers(projectId)
        val owner = dsl.select(USERS.ID, USERS.FULL_NAME)
            .from(USERS)
            .join(PROJECTS).on(PROJECTS.OWNER_ID.eq(USERS.ID))
            .where(PROJECTS.ID.eq(projectId))
            .fetchOne { it.get(USERS.ID)!! to it.get(USERS.FULL_NAME)!! }
        return (members + listOfNotNull(owner)).distinctBy { it.first }
    }

    fun listProjectMemberIds(projectId: Long, exceptUserId: Long): List<Long> =
        dsl.select(PROJECT_MEMBERS.USER_ID)
            .from(PROJECT_MEMBERS)
            .where(PROJECT_MEMBERS.PROJECT_ID.eq(projectId))
            .and(PROJECT_MEMBERS.USER_ID.ne(exceptUserId))
            .fetch(PROJECT_MEMBERS.USER_ID)
            .filterNotNull()

    fun listProjectRecipientIds(projectId: Long, exceptUserId: Long): List<Long> {
        val ownerId = dsl.select(PROJECTS.OWNER_ID)
            .from(PROJECTS)
            .where(PROJECTS.ID.eq(projectId))
            .fetchOne(PROJECTS.OWNER_ID)
        return (listProjectMemberIds(projectId, exceptUserId) + listOfNotNull(ownerId))
            .distinct()
            .filter { it != exceptUserId }
    }

    fun listParticipantIds(conversationId: Long, exceptUserId: Long): List<Long> =
        dsl.select(CONVERSATION_PARTICIPANTS.USER_ID)
            .from(CONVERSATION_PARTICIPANTS)
            .where(CONVERSATION_PARTICIPANTS.CONVERSATION_ID.eq(conversationId))
            .and(CONVERSATION_PARTICIPANTS.USER_ID.ne(exceptUserId))
            .fetch(CONVERSATION_PARTICIPANTS.USER_ID)
            .filterNotNull()

    fun isActiveUser(userId: Long): Boolean =
        dsl.fetchExists(
            dsl.selectOne()
                .from(USERS)
                .where(USERS.ID.eq(userId))
                .and(USERS.ACTIVE.eq(true))
        )

    fun projectExists(projectId: Long): Boolean =
        dsl.fetchExists(
            dsl.selectOne()
                .from(PROJECTS)
                .where(PROJECTS.ID.eq(projectId))
        )

    fun insertConversation(kind: String, title: String?, projectId: Long?, directKey: String?, createdBy: Long): Long =
        dsl.insertInto(CONVERSATIONS)
            .set(CONVERSATIONS.KIND, kind)
            .set(CONVERSATIONS.TITLE, title)
            .set(CONVERSATIONS.PROJECT_ID, projectId)
            .set(CONVERSATIONS.DIRECT_KEY, directKey)
            .set(CONVERSATIONS.CREATED_BY, createdBy)
            .returning(CONVERSATIONS.ID)
            .fetchOne()!!.get(CONVERSATIONS.ID)!!

    fun insertParticipant(conversationId: Long, userId: Long) {
        dsl.insertInto(CONVERSATION_PARTICIPANTS)
            .set(CONVERSATION_PARTICIPANTS.CONVERSATION_ID, conversationId)
            .set(CONVERSATION_PARTICIPANTS.USER_ID, userId)
            .onConflictDoNothing()
            .execute()
    }

    fun listMessages(conversationId: Long, after: Long?, before: Long?, limit: Int): List<MessageRow> {
        val effectiveLimit = limit + 1
        val base = dsl.select(
            CONVERSATION_MESSAGES.ID,
            CONVERSATION_MESSAGES.CONVERSATION_ID,
            CONVERSATION_MESSAGES.AUTHOR_ID,
            USERS.FULL_NAME,
            CONVERSATION_MESSAGES.CONTENT,
            CONVERSATION_MESSAGES.CREATED_AT
        )
            .from(CONVERSATION_MESSAGES)
            .leftJoin(USERS).on(USERS.ID.eq(CONVERSATION_MESSAGES.AUTHOR_ID))
            .where(CONVERSATION_MESSAGES.CONVERSATION_ID.eq(conversationId))

        return if (before != null) {
            base.and(CONVERSATION_MESSAGES.ID.lt(before))
                .orderBy(CONVERSATION_MESSAGES.ID.desc())
                .limit(effectiveLimit)
                .fetch { mapMessageRow(it) }
        } else {
            val afterId = after ?: 0L
            base.and(CONVERSATION_MESSAGES.ID.gt(afterId))
                .orderBy(CONVERSATION_MESSAGES.ID.asc())
                .limit(effectiveLimit)
                .fetch { mapMessageRow(it) }
        }
    }

    fun findMessageRowById(messageId: Long): MessageRow? =
        dsl.select(
            CONVERSATION_MESSAGES.ID,
            CONVERSATION_MESSAGES.CONVERSATION_ID,
            CONVERSATION_MESSAGES.AUTHOR_ID,
            USERS.FULL_NAME,
            CONVERSATION_MESSAGES.CONTENT,
            CONVERSATION_MESSAGES.CREATED_AT
        )
            .from(CONVERSATION_MESSAGES)
            .leftJoin(USERS).on(USERS.ID.eq(CONVERSATION_MESSAGES.AUTHOR_ID))
            .where(CONVERSATION_MESSAGES.ID.eq(messageId))
            .fetchOne { mapMessageRow(it) }

    fun findMessageByIdempotencyKey(conversationId: Long, authorId: Long, key: String): MessageRow? =
        dsl.select(
            CONVERSATION_MESSAGES.ID,
            CONVERSATION_MESSAGES.CONVERSATION_ID,
            CONVERSATION_MESSAGES.AUTHOR_ID,
            USERS.FULL_NAME,
            CONVERSATION_MESSAGES.CONTENT,
            CONVERSATION_MESSAGES.CREATED_AT
        )
            .from(CONVERSATION_MESSAGES)
            .leftJoin(USERS).on(USERS.ID.eq(CONVERSATION_MESSAGES.AUTHOR_ID))
            .where(CONVERSATION_MESSAGES.CONVERSATION_ID.eq(conversationId))
            .and(CONVERSATION_MESSAGES.AUTHOR_ID.eq(authorId))
            .and(CONVERSATION_MESSAGES.IDEMPOTENCY_KEY.eq(key))
            .fetchOne { mapMessageRow(it) }

    fun insertMessage(conversationId: Long, authorId: Long, content: String, idempotencyKey: String): ConversationMessagesRecord {
        val record = dsl.newRecord(CONVERSATION_MESSAGES)
        record.conversationId = conversationId
        record.authorId = authorId
        record.content = content
        record.idempotencyKey = idempotencyKey
        record.store()
        return record
    }

    private fun mapConversationRow(record: Record): ConversationRow = ConversationRow(
        id = record.get(CONVERSATIONS.ID)!!,
        kind = record.get(CONVERSATIONS.KIND)!!,
        title = record.get(CONVERSATIONS.TITLE),
        projectId = record.get(CONVERSATIONS.PROJECT_ID),
        directKey = record.get(CONVERSATIONS.DIRECT_KEY)
    )

    private fun mapMessageRow(record: Record): MessageRow = MessageRow(
        id = record.get(CONVERSATION_MESSAGES.ID)!!,
        conversationId = record.get(CONVERSATION_MESSAGES.CONVERSATION_ID)!!,
        authorId = record.get(CONVERSATION_MESSAGES.AUTHOR_ID),
        authorFullName = record.get(USERS.FULL_NAME),
        content = record.get(CONVERSATION_MESSAGES.CONTENT)!!,
        createdAt = record.get(CONVERSATION_MESSAGES.CREATED_AT)!!
    )
}
