package org.example.project.repository

import org.example.project.exception.ConflictException
import org.example.project.exception.NotFoundException
import org.example.project.generated.jooq.tables.Users.USERS
import org.example.project.generated.jooq.tables.records.UsersRecord
import org.jooq.DSLContext
import org.jooq.impl.DSL

class UserRepository(private val dsl: DSLContext) {

    fun findByLogin(login: String): UsersRecord? {
        return dsl.selectFrom(USERS)
            .where(USERS.LOGIN.eq(login))
            .fetchOne()
    }

    fun findById(id: Long): UsersRecord? {
        return dsl.selectFrom(USERS)
            .where(USERS.ID.eq(id))
            .fetchOne()
    }

    fun existsByLogin(login: String): Boolean {
        return dsl.fetchExists(
            dsl.selectOne().from(USERS).where(USERS.LOGIN.eq(login))
        )
    }

    fun count(): Int {
        return dsl.fetchCount(USERS)
    }

    fun <T> transaction(block: (DSLContext) -> T): T {
        return dsl.transactionResult { configuration -> block(DSL.using(configuration)) }
    }

    fun list(): List<UsersRecord> {
        return dsl.selectFrom(USERS)
            .orderBy(USERS.ID.desc())
            .fetch()
    }

    fun listActive(): List<UsersRecord> {
        return dsl.selectFrom(USERS)
            .where(USERS.ACTIVE.eq(true))
            .orderBy(USERS.FULL_NAME.asc(), USERS.ID.asc())
            .fetch()
    }

    fun create(
        login: String,
        passwordHash: String,
        fullName: String,
        email: String?,
        departmentId: Long?,
        active: Boolean,
        createdBy: Long?
    ): UsersRecord {
        val record = dsl.newRecord(USERS)
        record.login = login.trim()
        record.passwordHash = passwordHash
        record.fullName = fullName.trim()
        record.email = email
        record.departmentId = departmentId
        record.active = active
        record.createdBy = createdBy
        record.store()
        return record
    }

    fun update(
        id: Long,
        fullName: String?,
        email: String?,
        departmentId: Long?,
        expectedVersion: Long
    ): UsersRecord {
        val current = findById(id) ?: throw NotFoundException("user", id)
        if (current.version != expectedVersion) {
            throw ConflictException("User was modified concurrently")
        }
        dsl.update(USERS)
            .set(USERS.FULL_NAME, fullName ?: current.fullName)
            .set(USERS.EMAIL, if (email !== null) email else current.email)
            .set(USERS.DEPARTMENT_ID, if (departmentId !== null) departmentId else current.departmentId)
            .set(USERS.VERSION, USERS.VERSION.plus(1))
            .where(USERS.ID.eq(id))
            .execute()
        return findById(id)!!
    }

    fun setActive(id: Long, active: Boolean, expectedVersion: Long): UsersRecord {
        val current = findById(id) ?: throw NotFoundException("user", id)
        if (current.version != expectedVersion) {
            throw ConflictException("User was modified concurrently")
        }
        dsl.update(USERS)
            .set(USERS.ACTIVE, active)
            .set(USERS.VERSION, USERS.VERSION.plus(1))
            .where(USERS.ID.eq(id))
            .execute()
        return findById(id)!!
    }

    fun updatePassword(id: Long, passwordHash: String, expectedVersion: Long): UsersRecord {
        val current = findById(id) ?: throw NotFoundException("user", id)
        if (current.version != expectedVersion) {
            throw ConflictException("User was modified concurrently")
        }
        dsl.update(USERS)
            .set(USERS.PASSWORD_HASH, passwordHash)
            .set(USERS.VERSION, USERS.VERSION.plus(1))
            .where(USERS.ID.eq(id))
            .execute()
        return findById(id)!!
    }

    fun exists(id: Long): Boolean {
        return dsl.fetchExists(
            dsl.selectOne().from(USERS).where(USERS.ID.eq(id))
        )
    }
}
