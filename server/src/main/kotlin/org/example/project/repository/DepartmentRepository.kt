package org.example.project.repository

import org.example.project.exception.ConflictException
import org.example.project.exception.NotFoundException
import org.example.project.generated.jooq.tables.Departments.DEPARTMENTS
import org.example.project.generated.jooq.tables.records.DepartmentsRecord
import org.jooq.DSLContext

class DepartmentRepository(private val dsl: DSLContext) {

    fun findById(id: Long): DepartmentsRecord? {
        return dsl.selectFrom(DEPARTMENTS)
            .where(DEPARTMENTS.ID.eq(id))
            .fetchOne()
    }

    fun findByCode(code: String): DepartmentsRecord? {
        return dsl.selectFrom(DEPARTMENTS)
            .where(DEPARTMENTS.CODE.eq(code))
            .fetchOne()
    }

    fun existsByCode(code: String): Boolean {
        return dsl.fetchExists(
            dsl.selectOne().from(DEPARTMENTS).where(DEPARTMENTS.CODE.eq(code))
        )
    }

    fun list(): List<DepartmentsRecord> {
        return dsl.selectFrom(DEPARTMENTS)
            .orderBy(DEPARTMENTS.CODE)
            .fetch()
    }

    fun create(code: String, name: String, parentId: Long?, headId: Long?): DepartmentsRecord {
        val record = dsl.newRecord(DEPARTMENTS)
        record.code = code
        record.name = name
        record.parentId = parentId
        record.headId = headId
        record.store()
        return record
    }

    fun update(id: Long, name: String?, parentId: Long?, headId: Long?): DepartmentsRecord {
        val current = findById(id) ?: throw NotFoundException("department", id)
        dsl.update(DEPARTMENTS)
            .set(DEPARTMENTS.NAME, name ?: current.name)
            .set(DEPARTMENTS.PARENT_ID, if (parentId !== null) parentId else current.parentId)
            .set(DEPARTMENTS.HEAD_ID, if (headId !== null) headId else current.headId)
            .where(DEPARTMENTS.ID.eq(id))
            .execute()
        return findById(id)!!
    }
}
