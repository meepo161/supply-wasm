package org.example.project.repository

import org.example.project.generated.jooq.tables.UserRoles.USER_ROLES
import org.jooq.DSLContext

class UserRolesRepository(private val dsl: DSLContext) {

    fun setRoles(userId: Long, roleIds: List<Long>) {
        dsl.deleteFrom(USER_ROLES)
            .where(USER_ROLES.USER_ID.eq(userId))
            .execute()
        if (roleIds.isNotEmpty()) {
            val insert = dsl.insertInto(USER_ROLES, USER_ROLES.USER_ID, USER_ROLES.ROLE_ID)
            roleIds.distinct().forEach { roleId ->
                insert.values(userId, roleId)
            }
            insert.execute()
        }
    }

    fun listRoleIdsByUserId(userId: Long): List<Long> {
        return dsl.select(USER_ROLES.ROLE_ID)
            .from(USER_ROLES)
            .where(USER_ROLES.USER_ID.eq(userId))
            .fetch(USER_ROLES.ROLE_ID)
    }
}
