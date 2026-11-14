package org.example.project.repository

import org.example.project.generated.jooq.tables.AuditLog.AUDIT_LOG
import org.jooq.DSLContext
import org.jooq.JSONB

class AuditRepository(private val dsl: DSLContext) {

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
