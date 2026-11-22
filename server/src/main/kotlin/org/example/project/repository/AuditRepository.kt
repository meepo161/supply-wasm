package org.example.project.repository

import org.example.project.dto.AuditLogEntryDto
import org.example.project.generated.jooq.tables.AuditLog.AUDIT_LOG
import org.example.project.generated.jooq.tables.Users.USERS
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL

class AuditRepository(private val dsl: DSLContext) {

    fun list(
        entityType: String? = null,
        action: String? = null,
        userId: Long? = null,
        limit: Int = 100,
        offset: Int = 0
    ): List<AuditLogEntryDto> {
        var condition: Condition = DSL.trueCondition()
        entityType?.trim()?.takeIf { it.isNotEmpty() }?.let {
            condition = condition.and(AUDIT_LOG.ENTITY_TYPE.eq(it))
        }
        action?.trim()?.takeIf { it.isNotEmpty() }?.let {
            condition = condition.and(AUDIT_LOG.ACTION.eq(it))
        }
        if (userId != null) {
            condition = condition.and(AUDIT_LOG.USER_ID.eq(userId))
        }
        return dsl.select(
            AUDIT_LOG.ID,
            AUDIT_LOG.USER_ID,
            USERS.FULL_NAME,
            AUDIT_LOG.ACTION,
            AUDIT_LOG.ENTITY_TYPE,
            AUDIT_LOG.ENTITY_ID,
            AUDIT_LOG.PAYLOAD,
            AUDIT_LOG.CREATED_AT
        )
            .from(AUDIT_LOG)
            .leftJoin(USERS).on(USERS.ID.eq(AUDIT_LOG.USER_ID))
            .where(condition)
            .orderBy(AUDIT_LOG.ID.desc())
            .limit(limit.coerceIn(1, 500))
            .offset(offset.coerceAtLeast(0))
            .fetch { row ->
                AuditLogEntryDto(
                    id = row.get(AUDIT_LOG.ID)!!,
                    userId = row.get(AUDIT_LOG.USER_ID),
                    userName = row.get(USERS.FULL_NAME),
                    action = row.get(AUDIT_LOG.ACTION)!!,
                    entityType = row.get(AUDIT_LOG.ENTITY_TYPE)!!,
                    entityId = row.get(AUDIT_LOG.ENTITY_ID)!!,
                    payloadJson = row.get(AUDIT_LOG.PAYLOAD)?.data() ?: "{}",
                    createdAt = row.get(AUDIT_LOG.CREATED_AT)?.toString() ?: ""
                )
            }
    }

    fun log(
        userId: Long?,
        action: String,
        entityType: String,
        entityId: Long,
        payload: Map<String, Any?> = emptyMap()
    ) {
        dsl.insertInto(AUDIT_LOG)
            .set(AUDIT_LOG.USER_ID, userId)
            .set(AUDIT_LOG.ACTION, action)
            .set(AUDIT_LOG.ENTITY_TYPE, entityType)
            .set(AUDIT_LOG.ENTITY_ID, entityId)
            .set(AUDIT_LOG.PAYLOAD, JSONB.valueOf(toJson(payload)))
            .execute()
    }

    private fun toJson(map: Map<String, Any?>): String {
        val entries = map.entries.joinToString(",") { (key, value) ->
            val formattedValue = when (value) {
                null -> "null"
                is String -> "\"${escape(value)}\""
                is Number, is Boolean -> value.toString()
                else -> "\"${escape(value.toString())}\""
            }
            "\"$key\":$formattedValue"
        }
        return "{$entries}"
    }

    private fun escape(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
    }
}
