package org.example.project.repository

import org.example.project.generated.jooq.tables.Sessions.SESSIONS
import org.example.project.generated.jooq.tables.records.SessionsRecord
import org.jooq.DSLContext
import java.time.OffsetDateTime
import java.util.*

class SessionRepository(private val dsl: DSLContext) {

    fun create(userId: Long, expiresAt: OffsetDateTime): UUID {
        val id = UUID.randomUUID()
        dsl.insertInto(SESSIONS)
            .set(SESSIONS.ID, id)
            .set(SESSIONS.USER_ID, userId)
            .set(SESSIONS.EXPIRES_AT, expiresAt)
            .execute()
        return id
    }

    fun findValid(id: UUID): SessionsRecord? {
        return dsl.selectFrom(SESSIONS)
            .where(SESSIONS.ID.eq(id))
            .and(SESSIONS.EXPIRES_AT.greaterThan(OffsetDateTime.now()))
            .fetchOne()
    }

    fun delete(id: UUID) {
        dsl.deleteFrom(SESSIONS)
            .where(SESSIONS.ID.eq(id))
            .execute()
    }

    fun deleteByUserId(userId: Long) {
        dsl.deleteFrom(SESSIONS)
            .where(SESSIONS.USER_ID.eq(userId))
            .execute()
    }

    fun deleteExpired() {
        dsl.deleteFrom(SESSIONS)
            .where(SESSIONS.EXPIRES_AT.lessOrEqual(OffsetDateTime.now()))
            .execute()
    }
}
