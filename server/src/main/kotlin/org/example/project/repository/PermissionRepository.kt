package org.example.project.repository

import org.example.project.exception.ConflictException
import org.example.project.exception.NotFoundException
import org.example.project.generated.jooq.Tables.PERMISSIONS
import org.example.project.generated.jooq.Tables.USER_ROLES
import org.example.project.generated.jooq.tables.records.PermissionsRecord
import org.jooq.DSLContext

class PermissionRepository(private val dsl: DSLContext) {

    fun create(roleId: Long, resource: String, action: String, scope: String?): PermissionsRecord {
        if (dsl.fetchExists(
                dsl.selectOne()
                    .from(PERMISSIONS)
                    .where(PERMISSIONS.ROLE_ID.eq(roleId))
                    .and(PERMISSIONS.RESOURCE.eq(resource))
                    .and(PERMISSIONS.ACTION.eq(action))
                    .and(scope?.let { PERMISSIONS.SCOPE.eq(it) } ?: PERMISSIONS.SCOPE.isNull)
            )
        ) {
            throw ConflictException("Permission already exists for role")
        }
        val record = dsl.newRecord(PERMISSIONS)
        record.roleId = roleId
        record.resource = resource
        record.action = action
        record.scope = scope
        record.store()
        return record
    }

    fun delete(id: Long) {
        val deleted = dsl.deleteFrom(PERMISSIONS)
            .where(PERMISSIONS.ID.eq(id))
            .execute()
        if (deleted == 0) {
            throw NotFoundException("permission", id)
        }
    }

    fun findById(id: Long): PermissionsRecord? {
        return dsl.selectFrom(PERMISSIONS)
            .where(PERMISSIONS.ID.eq(id))
            .fetchOne()
    }

    fun listByRoleId(roleId: Long): List<PermissionsRecord> {
        return dsl.selectFrom(PERMISSIONS)
            .where(PERMISSIONS.ROLE_ID.eq(roleId))
            .fetch()
    }

    fun listByUserId(userId: Long): List<PermissionsRecord> {
        return dsl.select(PERMISSIONS.asterisk())
            .from(PERMISSIONS)
            .join(USER_ROLES).on(USER_ROLES.ROLE_ID.eq(PERMISSIONS.ROLE_ID))
            .where(USER_ROLES.USER_ID.eq(userId))
            .fetchInto(PERMISSIONS)
    }

    fun listAll(): List<PermissionsRecord> {
        return dsl.selectFrom(PERMISSIONS)
            .fetch()
    }
}
