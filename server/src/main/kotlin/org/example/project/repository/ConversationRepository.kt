package org.example.project.repository

import org.example.project.dto.ReactionSummaryDto
import org.example.project.generated.jooq.tables.ConversationEventLog.CONVERSATION_EVENT_LOG
import org.example.project.generated.jooq.tables.ConversationMentions.CONVERSATION_MENTIONS
import org.example.project.generated.jooq.tables.ConversationMessageRevisions.CONVERSATION_MESSAGE_REVISIONS
import org.example.project.generated.jooq.tables.ConversationMessages.CONVERSATION_MESSAGES
import org.example.project.generated.jooq.tables.ConversationParticipants.CONVERSATION_PARTICIPANTS
import org.example.project.generated.jooq.tables.ConversationReactions.CONVERSATION_REACTIONS
import org.example.project.generated.jooq.tables.ConversationReadStates.CONVERSATION_READ_STATES
import org.example.project.generated.jooq.tables.Conversations.CONVERSATIONS
import org.example.project.generated.jooq.tables.ProjectMembers.PROJECT_MEMBERS
import org.example.project.generated.jooq.tables.Projects.PROJECTS
import org.example.project.generated.jooq.tables.Users.USERS
import org.example.project.generated.jooq.tables.records.ConversationMessagesRecord
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
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
        val createdAt: OffsetDateTime,
        val replyToId: Long? = null,
        val editedAt: OffsetDateTime? = null,
        val revokedAt: OffsetDateTime? = null,
        val sourceKind: String? = null,
        val sourceMessageId: Long? = null,
        val sourceAuthorId: Long? = null,
        val sourceCreatedAt: OffsetDateTime? = null,
        val version: Long = 0
    )

    data class EventRow(
        val id: Long,
        val conversationId: Long,
        val category: String,
        val messageId: Long?,
        val actorUserId: Long,
        val createdAt: OffsetDateTime
    )

    data class MentionCandidate(
        val userId: Long,
        val login: String,
        val fullName: String
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
        dsl.fetchExists(dsl.selectOne().from(PROJECTS).where(PROJECTS.ID.eq(projectId)))

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
        val base = messageSelect().where(CONVERSATION_MESSAGES.CONVERSATION_ID.eq(conversationId))
        return if (before != null) {
            base.and(CONVERSATION_MESSAGES.ID.lt(before))
                .orderBy(CONVERSATION_MESSAGES.ID.desc())
                .limit(limit + 1)
                .fetch { mapMessageRow(it) }
        } else {
            base.and(CONVERSATION_MESSAGES.ID.gt(after ?: 0L))
                .orderBy(CONVERSATION_MESSAGES.ID.asc())
                .limit(limit + 1)
                .fetch { mapMessageRow(it) }
        }
    }

    fun searchMessages(conversationId: Long, query: String, after: Long?, limit: Int): List<MessageRow> {
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return messageSelect()
            .where(CONVERSATION_MESSAGES.CONVERSATION_ID.eq(conversationId))
            .and(CONVERSATION_MESSAGES.ID.gt(after ?: 0L))
            .and(CONVERSATION_MESSAGES.REVOKED_AT.isNull)
            .and(CONVERSATION_MESSAGES.CONTENT.likeIgnoreCase("%$escaped%", '\\'))
            .orderBy(CONVERSATION_MESSAGES.ID.asc())
            .limit(limit + 1)
            .fetch { mapMessageRow(it) }
    }

    fun findMessageRowById(messageId: Long): MessageRow? =
        messageSelect().where(CONVERSATION_MESSAGES.ID.eq(messageId)).fetchOne { mapMessageRow(it) }

    fun listMessageRowsByIds(messageIds: Collection<Long>): List<MessageRow> {
        if (messageIds.isEmpty()) return emptyList()
        return messageSelect().where(CONVERSATION_MESSAGES.ID.`in`(messageIds)).fetch { mapMessageRow(it) }
    }

    fun findMessageByIdempotencyKey(conversationId: Long, authorId: Long, key: String): MessageRow? =
        messageSelect()
            .where(CONVERSATION_MESSAGES.CONVERSATION_ID.eq(conversationId))
            .and(CONVERSATION_MESSAGES.AUTHOR_ID.eq(authorId))
            .and(CONVERSATION_MESSAGES.IDEMPOTENCY_KEY.eq(key))
            .fetchOne { mapMessageRow(it) }

    fun insertMessage(
        conversationId: Long,
        authorId: Long,
        content: String,
        idempotencyKey: String,
        replyToId: Long? = null,
        source: MessageRow? = null
    ): ConversationMessagesRecord {
        val record = dsl.newRecord(CONVERSATION_MESSAGES)
        record.conversationId = conversationId
        record.authorId = authorId
        record.content = content
        record.idempotencyKey = idempotencyKey
        record.replyToId = replyToId
        record.sourceKind = source?.let { findById(it.conversationId)?.kind }
        record.sourceMessageId = source?.id
        record.sourceAuthorId = source?.authorId
        record.sourceCreatedAt = source?.createdAt
        record.store()
        return record
    }

    fun insertRevision(messageId: Long, previousContent: String, editedBy: Long) {
        dsl.insertInto(CONVERSATION_MESSAGE_REVISIONS)
            .set(CONVERSATION_MESSAGE_REVISIONS.MESSAGE_ID, messageId)
            .set(CONVERSATION_MESSAGE_REVISIONS.PREVIOUS_CONTENT, previousContent)
            .set(CONVERSATION_MESSAGE_REVISIONS.EDITED_BY, editedBy)
            .execute()
    }

    fun updateMessageContent(messageId: Long, expectedVersion: Long, content: String): Boolean {
        val now = OffsetDateTime.now()
        return dsl.update(CONVERSATION_MESSAGES)
            .set(CONVERSATION_MESSAGES.CONTENT, content)
            .set(CONVERSATION_MESSAGES.EDITED_AT, now)
            .set(CONVERSATION_MESSAGES.VERSION, CONVERSATION_MESSAGES.VERSION.plus(1))
            .where(CONVERSATION_MESSAGES.ID.eq(messageId))
            .and(CONVERSATION_MESSAGES.VERSION.eq(expectedVersion))
            .execute() == 1
    }

    fun revokeMessage(messageId: Long, expectedVersion: Long): Boolean =
        dsl.update(CONVERSATION_MESSAGES)
            .set(CONVERSATION_MESSAGES.CONTENT, REVOKED_CONTENT)
            .set(CONVERSATION_MESSAGES.REVOKED_AT, OffsetDateTime.now())
            .set(CONVERSATION_MESSAGES.VERSION, CONVERSATION_MESSAGES.VERSION.plus(1))
            .where(CONVERSATION_MESSAGES.ID.eq(messageId))
            .and(CONVERSATION_MESSAGES.VERSION.eq(expectedVersion))
            .and(CONVERSATION_MESSAGES.REVOKED_AT.isNull)
            .execute() == 1

    fun toggleReaction(messageId: Long, userId: Long, emoji: String): Boolean {
        val removed = dsl.deleteFrom(CONVERSATION_REACTIONS)
            .where(CONVERSATION_REACTIONS.MESSAGE_ID.eq(messageId))
            .and(CONVERSATION_REACTIONS.USER_ID.eq(userId))
            .and(CONVERSATION_REACTIONS.EMOJI.eq(emoji))
            .execute()
        if (removed == 0) {
            dsl.insertInto(CONVERSATION_REACTIONS)
                .set(CONVERSATION_REACTIONS.MESSAGE_ID, messageId)
                .set(CONVERSATION_REACTIONS.USER_ID, userId)
                .set(CONVERSATION_REACTIONS.EMOJI, emoji)
                .execute()
            return true
        }
        return false
    }

    fun reactionsForMessages(messageIds: Collection<Long>, viewerId: Long): Map<Long, List<ReactionSummaryDto>> {
        if (messageIds.isEmpty()) return emptyMap()
        val my = DSL.boolOr(DSL.`when`(CONVERSATION_REACTIONS.USER_ID.eq(viewerId), DSL.inline(true)))
        return dsl.select(CONVERSATION_REACTIONS.MESSAGE_ID, CONVERSATION_REACTIONS.EMOJI, DSL.count(), my)
            .from(CONVERSATION_REACTIONS)
            .where(CONVERSATION_REACTIONS.MESSAGE_ID.`in`(messageIds))
            .groupBy(CONVERSATION_REACTIONS.MESSAGE_ID, CONVERSATION_REACTIONS.EMOJI)
            .orderBy(CONVERSATION_REACTIONS.MESSAGE_ID.asc(), CONVERSATION_REACTIONS.EMOJI.asc())
            .fetch {
                it.get(CONVERSATION_REACTIONS.MESSAGE_ID)!! to ReactionSummaryDto(
                    it.get(CONVERSATION_REACTIONS.EMOJI)!!,
                    it.get(DSL.count()),
                    it.get(my) == true
                )
            }
            .groupBy({ it.first }, { it.second })
    }

    fun insertMentions(messageId: Long, userIds: Collection<Long>) {
        userIds.distinct().forEach { userId ->
            dsl.insertInto(CONVERSATION_MENTIONS)
                .set(CONVERSATION_MENTIONS.MESSAGE_ID, messageId)
                .set(CONVERSATION_MENTIONS.MENTIONED_USER_ID, userId)
                .onConflictDoNothing()
                .execute()
        }
    }

    fun mentionedUserIdsForMessages(messageIds: Collection<Long>): Map<Long, List<Long>> {
        if (messageIds.isEmpty()) return emptyMap()
        return dsl.select(CONVERSATION_MENTIONS.MESSAGE_ID, CONVERSATION_MENTIONS.MENTIONED_USER_ID)
            .from(CONVERSATION_MENTIONS)
            .where(CONVERSATION_MENTIONS.MESSAGE_ID.`in`(messageIds))
            .fetchGroups(
                { it.get(CONVERSATION_MENTIONS.MESSAGE_ID)!! },
                { it.get(CONVERSATION_MENTIONS.MENTIONED_USER_ID)!! }
            )
    }

    fun listMentionCandidates(row: ConversationRow): List<MentionCandidate> {
        val participants = dsl.select(USERS.ID, USERS.LOGIN, USERS.FULL_NAME)
            .from(CONVERSATION_PARTICIPANTS)
            .join(USERS).on(USERS.ID.eq(CONVERSATION_PARTICIPANTS.USER_ID))
            .where(CONVERSATION_PARTICIPANTS.CONVERSATION_ID.eq(row.id))
            .and(USERS.ACTIVE.eq(true))
        val projectMembers = dsl.select(USERS.ID, USERS.LOGIN, USERS.FULL_NAME)
            .from(PROJECT_MEMBERS)
            .join(USERS).on(USERS.ID.eq(PROJECT_MEMBERS.USER_ID))
            .where(PROJECT_MEMBERS.PROJECT_ID.eq(row.projectId))
            .and(USERS.ACTIVE.eq(true))
        val projectOwner = dsl.select(USERS.ID, USERS.LOGIN, USERS.FULL_NAME)
            .from(PROJECTS)
            .join(USERS).on(USERS.ID.eq(PROJECTS.OWNER_ID))
            .where(PROJECTS.ID.eq(row.projectId))
            .and(USERS.ACTIVE.eq(true))
        val records = if (row.kind == "PROJECT") projectMembers.union(projectOwner).fetch() else participants.fetch()
        return records.map {
            MentionCandidate(it.get(USERS.ID)!!, it.get(USERS.LOGIN)!!, it.get(USERS.FULL_NAME)!!)
        }.distinctBy { it.userId }
    }

    fun raiseReadCursor(conversationId: Long, userId: Long, lastReadMessageId: Long): Long {
        dsl.insertInto(CONVERSATION_READ_STATES)
            .set(CONVERSATION_READ_STATES.CONVERSATION_ID, conversationId)
            .set(CONVERSATION_READ_STATES.USER_ID, userId)
            .set(CONVERSATION_READ_STATES.LAST_READ_MESSAGE_ID, lastReadMessageId)
            .onConflict(CONVERSATION_READ_STATES.CONVERSATION_ID, CONVERSATION_READ_STATES.USER_ID)
            .doUpdate()
            .set(
                CONVERSATION_READ_STATES.LAST_READ_MESSAGE_ID,
                DSL.greatest(CONVERSATION_READ_STATES.LAST_READ_MESSAGE_ID, DSL.`value`(lastReadMessageId))
            )
            .set(CONVERSATION_READ_STATES.LAST_READ_AT, OffsetDateTime.now())
            .execute()
        return readCursor(conversationId, userId)
    }

    fun readCursor(conversationId: Long, userId: Long): Long =
        dsl.select(CONVERSATION_READ_STATES.LAST_READ_MESSAGE_ID)
            .from(CONVERSATION_READ_STATES)
            .where(CONVERSATION_READ_STATES.CONVERSATION_ID.eq(conversationId))
            .and(CONVERSATION_READ_STATES.USER_ID.eq(userId))
            .fetchOne(CONVERSATION_READ_STATES.LAST_READ_MESSAGE_ID) ?: 0L

    fun readCursors(conversationId: Long): List<Pair<Long, Long>> =
        dsl.select(CONVERSATION_READ_STATES.USER_ID, CONVERSATION_READ_STATES.LAST_READ_MESSAGE_ID)
            .from(CONVERSATION_READ_STATES)
            .where(CONVERSATION_READ_STATES.CONVERSATION_ID.eq(conversationId))
            .fetch { it.get(CONVERSATION_READ_STATES.USER_ID)!! to it.get(CONVERSATION_READ_STATES.LAST_READ_MESSAGE_ID)!! }

    fun appendEvent(conversationId: Long, category: String, messageId: Long?, actorUserId: Long): Long =
        dsl.insertInto(CONVERSATION_EVENT_LOG)
            .set(CONVERSATION_EVENT_LOG.CONVERSATION_ID, conversationId)
            .set(CONVERSATION_EVENT_LOG.CATEGORY, category)
            .set(CONVERSATION_EVENT_LOG.MESSAGE_ID, messageId)
            .set(CONVERSATION_EVENT_LOG.ACTOR_USER_ID, actorUserId)
            .returning(CONVERSATION_EVENT_LOG.ID)
            .fetchOne()!!.get(CONVERSATION_EVENT_LOG.ID)!!

    fun listEvents(conversationId: Long, afterEvent: Long, limit: Int): List<EventRow> =
        dsl.selectFrom(CONVERSATION_EVENT_LOG)
            .where(CONVERSATION_EVENT_LOG.CONVERSATION_ID.eq(conversationId))
            .and(CONVERSATION_EVENT_LOG.ID.gt(afterEvent))
            .orderBy(CONVERSATION_EVENT_LOG.ID.asc())
            .limit(limit)
            .fetch {
                EventRow(
                    id = it.id!!,
                    conversationId = it.conversationId!!,
                    category = it.category!!,
                    messageId = it.messageId,
                    actorUserId = it.actorUserId!!,
                    createdAt = it.createdAt!!
                )
            }

    fun latestEventId(conversationId: Long): Long =
        dsl.select(DSL.max(CONVERSATION_EVENT_LOG.ID))
            .from(CONVERSATION_EVENT_LOG)
            .where(CONVERSATION_EVENT_LOG.CONVERSATION_ID.eq(conversationId))
            .fetchOne(DSL.max(CONVERSATION_EVENT_LOG.ID)) ?: 0L

    fun userNames(userIds: Collection<Long>): Map<Long, String> {
        if (userIds.isEmpty()) return emptyMap()
        return dsl.select(USERS.ID, USERS.FULL_NAME)
            .from(USERS)
            .where(USERS.ID.`in`(userIds))
            .fetch { it.get(USERS.ID)!! to it.get(USERS.FULL_NAME)!! }
            .toMap()
    }

    private fun messageSelect() = dsl.select(CONVERSATION_MESSAGES.fields().toList() + USERS.FULL_NAME)
        .from(CONVERSATION_MESSAGES)
        .leftJoin(USERS).on(USERS.ID.eq(CONVERSATION_MESSAGES.AUTHOR_ID))

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
        createdAt = record.get(CONVERSATION_MESSAGES.CREATED_AT)!!,
        replyToId = record.get(CONVERSATION_MESSAGES.REPLY_TO_ID),
        editedAt = record.get(CONVERSATION_MESSAGES.EDITED_AT),
        revokedAt = record.get(CONVERSATION_MESSAGES.REVOKED_AT),
        sourceKind = record.get(CONVERSATION_MESSAGES.SOURCE_KIND),
        sourceMessageId = record.get(CONVERSATION_MESSAGES.SOURCE_MESSAGE_ID),
        sourceAuthorId = record.get(CONVERSATION_MESSAGES.SOURCE_AUTHOR_ID),
        sourceCreatedAt = record.get(CONVERSATION_MESSAGES.SOURCE_CREATED_AT),
        version = record.get(CONVERSATION_MESSAGES.VERSION) ?: 0
    )

    fun listWorkspaceConversations(
        userId: Long,
        query: String?,
        unreadOnly: Boolean,
        mentionsOnly: Boolean,
        archivedOnly: Boolean?,
        kind: String?,
        cursor: WorkspaceCursor?,
        limit: Int,
        requiredId: Long? = null
    ): List<WorkspaceCandidate> {
        val conditions = mutableListOf<String>()
        val parameters = mutableListOf<Any?>(userId, userId, userId, userId, userId, userId, userId)
        conditions += """(
            (c.kind IN ('DIRECT', 'GROUP') AND EXISTS (
                SELECT 1 FROM conversation_participants access_cp
                WHERE access_cp.conversation_id = c.id AND access_cp.user_id = ?
            )) OR (c.kind = 'PROJECT' AND (
                p.owner_id = ? OR EXISTS (
                    SELECT 1 FROM project_members access_pm
                    WHERE access_pm.project_id = p.id AND access_pm.user_id = ?
                )
            ))
        )"""
        parameters.addAll(listOf(userId, userId, userId))
        if (kind != null) {
            conditions += "c.kind = ?"
            parameters += kind
        } else {
            conditions += "c.kind IN ('DIRECT', 'GROUP', 'PROJECT')"
        }
        if (requiredId != null) {
            conditions += "c.id = ?"
            parameters += requiredId
        }
        when (archivedOnly) {
            true -> conditions += "base.archived"
            false -> conditions += "NOT base.archived"
            null -> Unit
        }
        if (unreadOnly) conditions += "counters.unread_count > 0"
        if (mentionsOnly) conditions += "counters.mention_count > 0"
        if (!query.isNullOrBlank()) {
            conditions += """(
                base.title ILIKE ? ESCAPE '\' OR lm.search_content ILIKE ? ESCAPE '\' OR EXISTS (
                    SELECT 1 FROM users search_u
                    WHERE search_u.full_name ILIKE ? ESCAPE '\' AND (
                        EXISTS (
                            SELECT 1 FROM conversation_participants search_cp
                            WHERE search_cp.conversation_id = c.id AND search_cp.user_id = search_u.id
                        ) OR (c.kind = 'PROJECT' AND (
                            p.owner_id = search_u.id OR EXISTS (
                                SELECT 1 FROM project_members search_pm
                                WHERE search_pm.project_id = p.id AND search_pm.user_id = search_u.id
                            )
                        ))
                    )
                )
            )"""
            val pattern = "%${escapeLike(query.trim())}%"
            parameters.addAll(listOf(pattern, pattern, pattern))
        }
        cursor?.seen?.forEach { key ->
            conditions += "NOT (c.kind = ? AND c.id = ?)"
            parameters += key.kind
            parameters += key.id
        }
        parameters += limit + 1
        val sql = """
            WITH base AS (
                SELECT c.id, c.kind,
                    CASE
                        WHEN c.kind = 'DIRECT' THEN COALESCE(c.title, (
                            SELECT direct_u.full_name
                            FROM conversation_participants direct_cp
                            JOIN users direct_u ON direct_u.id = direct_cp.user_id
                            WHERE direct_cp.conversation_id = c.id AND direct_cp.user_id <> ?
                            ORDER BY direct_cp.user_id LIMIT 1
                        ), 'DIRECT #' || c.id)
                        WHEN c.kind = 'PROJECT' THEN COALESCE(c.title, p.name)
                        ELSE COALESCE(c.title, c.kind || ' #' || c.id)
                    END AS title,
                    c.project_id, COALESCE(s.pinned, FALSE) AS pinned,
                    COALESCE(s.muted, FALSE) AS muted, COALESCE(s.archived, FALSE) AS archived,
                    COALESCE(lm.created_at, c.created_at) AS activity
                FROM conversations c
                LEFT JOIN projects p ON p.id = c.project_id
                LEFT JOIN conversation_dialog_states s ON s.conversation_id = c.id AND s.user_id = ?
                LEFT JOIN LATERAL (
                    SELECT m.id, m.content, m.revoked_at, m.created_at, m.author_id, author_u.full_name,
                        m.content AS search_content
                    FROM conversation_messages m
                    LEFT JOIN users author_u ON author_u.id = m.author_id
                    WHERE m.conversation_id = c.id ORDER BY m.id DESC LIMIT 1
                ) lm ON TRUE
            ), counters AS (
                SELECT c.id,
                    (SELECT COUNT(*) FROM conversation_messages unread_m
                        WHERE unread_m.conversation_id = c.id
                        AND unread_m.id > COALESCE(rs.last_read_message_id, 0)
                        AND unread_m.author_id IS DISTINCT FROM ?) AS unread_count,
                    (SELECT COUNT(*) FROM conversation_mentions mention
                        JOIN conversation_messages mention_m ON mention_m.id = mention.message_id
                        WHERE mention_m.conversation_id = c.id AND mention.mentioned_user_id = ?
                        AND mention_m.id > COALESCE(rs.last_read_message_id, 0)
                        AND mention_m.author_id IS DISTINCT FROM ?) AS mention_count
                FROM conversations c
                LEFT JOIN conversation_read_states rs ON rs.conversation_id = c.id AND rs.user_id = ?
            )
            SELECT base.id, base.kind, base.title, base.project_id, base.pinned, base.muted, base.archived,
                base.activity, lm.content, lm.revoked_at, lm.created_at AS message_at,
                lm.author_id, lm.full_name AS author_name, counters.unread_count, counters.mention_count
            FROM base
            JOIN conversations c ON c.id = base.id
            JOIN counters ON counters.id = base.id
            LEFT JOIN LATERAL (
                SELECT m.content, CASE WHEN m.revoked_at IS NULL THEN m.content END AS search_content,
                    m.revoked_at, m.created_at, m.author_id, author_u.full_name
                FROM conversation_messages m
                LEFT JOIN users author_u ON author_u.id = m.author_id
                WHERE m.conversation_id = c.id ORDER BY m.id DESC LIMIT 1
            ) lm ON TRUE
            LEFT JOIN projects p ON p.id = c.project_id
            LEFT JOIN conversation_read_states rs ON rs.conversation_id = c.id AND rs.user_id = ?
            WHERE ${conditions.joinToString(" AND ")}
            ORDER BY base.pinned DESC, base.activity DESC, base.kind DESC, c.id DESC
            LIMIT ?
        """.trimIndent()
        return dsl.fetch(sql, *parameters.toTypedArray()).map { mapWorkspaceCandidate(it, task = false) }
    }

    fun workspaceConversationMembers(conversationIds: Collection<Long>): Map<Long, List<Pair<Long, String>>> {
        if (conversationIds.isEmpty()) return emptyMap()
        val placeholders = conversationIds.joinToString(",") { "?" }
        val parameters = conversationIds.toTypedArray<Any?>()
        return dsl.fetch(
            """
            SELECT source.conversation_id, source.user_id, u.full_name
            FROM (
                SELECT cp.conversation_id, cp.user_id
                FROM conversation_participants cp
                WHERE cp.conversation_id IN ($placeholders)
                UNION
                SELECT c.id, p.owner_id FROM conversations c
                JOIN projects p ON p.id = c.project_id
                WHERE c.kind = 'PROJECT' AND c.id IN ($placeholders)
                UNION
                SELECT c.id, pm.user_id FROM conversations c
                JOIN project_members pm ON pm.project_id = c.project_id
                WHERE c.kind = 'PROJECT' AND c.id IN ($placeholders)
            ) source
            JOIN users u ON u.id = source.user_id
            ORDER BY source.conversation_id, source.user_id
            """.trimIndent(),
            *parameters, *parameters, *parameters
        ).map {
            it.get("conversation_id", Long::class.java)!! to
                (it.get("user_id", Long::class.java)!! to it.get("full_name", String::class.java)!!)
        }.groupBy({ it.first }, { it.second })
    }

    fun conversationDialogState(conversationId: Long, userId: Long): DialogState =
        dsl.fetchOne(
            "SELECT pinned, muted, archived FROM conversation_dialog_states WHERE conversation_id = ? AND user_id = ?",
            conversationId, userId
        )?.let {
            DialogState(it.get("pinned", Boolean::class.java)!!, it.get("muted", Boolean::class.java)!!,
                it.get("archived", Boolean::class.java)!!)
        } ?: DialogState()

    fun updateConversationDialogState(
        conversationId: Long,
        userId: Long,
        pinned: Boolean?,
        muted: Boolean?,
        archived: Boolean?
    ) {
        dsl.execute(
            """
            INSERT INTO conversation_dialog_states (conversation_id, user_id, pinned, muted, archived, updated_at)
            VALUES (?, ?, COALESCE(?, FALSE), COALESCE(?, FALSE), COALESCE(?, FALSE), NOW())
            ON CONFLICT (conversation_id, user_id) DO UPDATE SET
                pinned = COALESCE(?, conversation_dialog_states.pinned),
                muted = COALESCE(?, conversation_dialog_states.muted),
                archived = COALESCE(?, conversation_dialog_states.archived),
                updated_at = NOW()
            """.trimIndent(),
            conversationId, userId, pinned, muted, archived, pinned, muted, archived
        )
    }

    companion object {
        const val REVOKED_CONTENT = "Сообщение отозвано"

        internal fun escapeLike(query: String): String =
            query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        internal fun mapWorkspaceCandidate(record: Record, task: Boolean): WorkspaceCandidate = WorkspaceCandidate(
            id = record.get("id", Long::class.java)!!,
            kind = record.get("kind", String::class.java)!!,
            title = record.get("title", String::class.java),
            projectId = record.get("project_id", Long::class.java),
            taskId = if (task) record.get("id", Long::class.java) else null,
            canWrite = if (task) record.get("can_write", Boolean::class.java)!! else true,
            lastMessagePreview = record.get("content", String::class.java)?.let {
                if (record.get("revoked_at") != null) REVOKED_CONTENT else it.take(160)
            },
            lastMessageAuthorId = record.get("author_id", Long::class.java),
            lastMessageAuthor = record.get("author_name", String::class.java),
            lastMessageAt = record.get("message_at", OffsetDateTime::class.java),
            activity = record.get("activity", OffsetDateTime::class.java)!!,
            unreadCount = record.get("unread_count", Number::class.java)!!.toInt(),
            mentionCount = record.get("mention_count", Number::class.java)!!.toInt(),
            isPinned = record.get("pinned", Boolean::class.java)!!,
            isMuted = record.get("muted", Boolean::class.java)!!,
            isArchived = record.get("archived", Boolean::class.java)!!
        )
    }
}

data class WorkspaceCandidate(
    val id: Long,
    val kind: String,
    val title: String?,
    val projectId: Long?,
    val taskId: Long?,
    val canWrite: Boolean,
    val lastMessagePreview: String?,
    val lastMessageAuthorId: Long?,
    val lastMessageAuthor: String?,
    val lastMessageAt: OffsetDateTime?,
    val activity: OffsetDateTime,
    val unreadCount: Int,
    val mentionCount: Int,
    val isPinned: Boolean,
    val isMuted: Boolean,
    val isArchived: Boolean
)

data class WorkspaceCursor(
    val seen: Set<WorkspaceKey>
)

data class WorkspaceKey(
    val kind: String,
    val id: Long
)

data class DialogState(
    val pinned: Boolean = false,
    val muted: Boolean = false,
    val archived: Boolean = false
)
