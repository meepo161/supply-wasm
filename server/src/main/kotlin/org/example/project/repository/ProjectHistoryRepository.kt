package org.example.project.repository

import org.example.project.generated.jooq.tables.AuditLog.AUDIT_LOG
import org.example.project.generated.jooq.tables.Users.USERS
import org.jooq.DSLContext
import java.time.OffsetDateTime

/**
 * История проекта для вкладки «История» (PRJ-13).
 *
 * Отдельной таблицы истории проекта нет: все значимые события уже пишутся в
 * `audit_log` (создание, открытие, закрытие, участники). Дублировать их в
 * новую таблицу значило бы завести второй источник правды, поэтому вкладка
 * читает журнал аудита. Из `payload` (JSONB) берутся только строковые значения:
 * числа и вложенные объекты не изобретаются заново, а показываются как есть.
 */
class ProjectHistoryRepository(private val dsl: DSLContext) {

    data class EntryRow(
        val id: Long,
        val action: String,
        val userId: Long?,
        val userName: String?,
        val payload: Map<String, String>,
        val createdAt: OffsetDateTime
    )

    fun listForProject(projectId: Long, limit: Int = 100): List<EntryRow> {
        // Алиас таблицы не нужен: в запросе одна ссылка на users, поэтому
        // обходимся без `as(...)` и не зависим от особенностей генерации.
        return dsl.select(
            AUDIT_LOG.ID,
            AUDIT_LOG.ACTION,
            AUDIT_LOG.USER_ID,
            USERS.FULL_NAME,
            AUDIT_LOG.PAYLOAD,
            AUDIT_LOG.CREATED_AT
        )
            .from(AUDIT_LOG)
            .leftJoin(USERS).on(USERS.ID.eq(AUDIT_LOG.USER_ID))
            .where(AUDIT_LOG.ENTITY_TYPE.eq("PROJECT"))
            .and(AUDIT_LOG.ENTITY_ID.eq(projectId))
            .orderBy(AUDIT_LOG.CREATED_AT.desc(), AUDIT_LOG.ID.desc())
            .limit(limit)
            .fetch()
            .map { record ->
                EntryRow(
                    id = record.get(AUDIT_LOG.ID),
                    action = record.get(AUDIT_LOG.ACTION),
                    userId = record.get(AUDIT_LOG.USER_ID),
                    userName = record.get(USERS.FULL_NAME),
                    payload = parsePayload(record.get(AUDIT_LOG.PAYLOAD)?.toString()),
                    createdAt = record.get(AUDIT_LOG.CREATED_AT)
                )
            }
    }

    /**
     * Разбор `payload` без сериализатора: нужны только плоские строковые поля.
     * Неизвестные структуры пропускаются, а не превращаются в выдуманный текст.
     */
    internal fun parsePayload(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val body = raw.trim().removePrefix("{").removeSuffix("}")
        if (body.isBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        Regex("\"([^\"]+)\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|(-?\\d+(?:\\.\\d+)?)|true|false|null)")
            .findAll(body)
            .forEach { match ->
                val key = match.groupValues[1]
                val quoted = match.groupValues[3]
                val number = match.groupValues[4]
                when {
                    quoted.isNotEmpty() -> result[key] = quoted
                    number.isNotEmpty() -> result[key] = number
                }
            }
        return result
    }
}
