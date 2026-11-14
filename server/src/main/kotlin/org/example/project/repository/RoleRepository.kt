package org.example.project.repository

import org.example.project.exception.ConflictException
import org.example.project.exception.NotFoundException
import org.example.project.generated.jooq.tables.Roles.ROLES
import org.example.project.generated.jooq.tables.records.RolesRecord
import org.jooq.DSLContext

class RoleRepository(private val dsl: DSLContext) {

    fun findById(id: Long): RolesRecord? {
        return dsl.selectFrom(ROLES)
            .where(ROLES.ID.eq(id))
            .fetchOne()
    }

    fun findByCode(code: String): RolesRecord? {
        return dsl.selectFrom(ROLES)
            .where(ROLES.CODE.eq(code))
            .fetchOne()
    }

    fun existsByCode(code: String): Boolean {
        return dsl.fetchExists(
            dsl.selectOne().from(ROLES).where(ROLES.CODE.eq(code))
        )
    }

    fun list(): List<RolesRecord> {
        return dsl.selectFrom(ROLES)
            .orderBy(ROLES.CODE)
            .fetch()
    }

    fun create(code: String, name: String, description: String?): RolesRecord {
        val record = dsl.newRecord(ROLES)
        record.code = code
        record.name = name
        record.description = description
        record.store()
        return record
    }

}
