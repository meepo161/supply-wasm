package org.example.project

import org.example.project.dto.UserDto
import org.example.project.dto.hasPermission
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserPermissionsTest {

    @Test
    fun permissionChecksMatchServerWildcardRules() {
        val admin = user("*:*")
        val warehouseReader = user("wms:read")
        val warehouseManager = user("wms:*")
        val projectManager = user("projects:manage_members")

        assertTrue(admin.hasPermission("wms", "manage"))
        assertTrue(warehouseReader.hasPermission("wms", "read"))
        assertFalse(warehouseReader.hasPermission("wms", "manage"))
        assertTrue(warehouseManager.hasPermission("wms", "manage"))
        assertTrue(projectManager.hasPermission("projects", "manage_members"))
        assertFalse(projectManager.hasPermission("projects", "read_any"))
    }

    private fun user(vararg permissions: String) = UserDto(
        id = 1,
        login = "user",
        fullName = "User",
        permissions = permissions.toList()
    )
}
