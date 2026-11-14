package org.example.project

import org.example.project.dto.UserDto
import org.example.project.ui.main.NavigationDestination
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationDestinationTest {

    @Test
    fun homeIsAlwaysVisible() {
        assertTrue(NavigationDestination.HOME.isVisibleTo(plainUser()))
        assertTrue(NavigationDestination.HOME.isVisibleTo(adminUser()))
    }

    @Test
    fun tasksProjectsNotificationsAndProfileAreAlwaysVisible() {
        val user = plainUser()
        assertTrue(NavigationDestination.TASKS.isVisibleTo(user))
        assertTrue(NavigationDestination.PROJECTS.isVisibleTo(user))
        assertTrue(NavigationDestination.NOTIFICATIONS.isVisibleTo(user))
        assertTrue(NavigationDestination.PROFILE.isVisibleTo(user))
    }

    @Test
    fun wmsRequiresWmsRead() {
        assertFalse(NavigationDestination.WMS.isVisibleTo(plainUser()))
        assertTrue(NavigationDestination.WMS.isVisibleTo(user("wms:read")))
        assertTrue(NavigationDestination.WMS.isVisibleTo(user("wms:*")))
    }

    @Test
    fun adminRequiresAnyAdminReadPermission() {
        assertFalse(NavigationDestination.ADMIN.isVisibleTo(plainUser()))
        assertFalse(NavigationDestination.ADMIN.isVisibleTo(user("users:create")))
        assertTrue(NavigationDestination.ADMIN.isVisibleTo(user("users:read_any")))
        assertTrue(NavigationDestination.ADMIN.isVisibleTo(user("departments:read")))
        assertTrue(NavigationDestination.ADMIN.isVisibleTo(user("roles:read")))
        assertTrue(NavigationDestination.ADMIN.isVisibleTo(user("permissions:read")))
        assertTrue(NavigationDestination.ADMIN.isVisibleTo(user("users:*")))
        assertTrue(NavigationDestination.ADMIN.isVisibleTo(user("*:*")))
    }

    private fun plainUser() = user()

    private fun adminUser() = user("*:*")

    private fun user(vararg permissions: String) = UserDto(
        id = 1,
        login = "user",
        fullName = "User",
        permissions = permissions.toList()
    )
}
