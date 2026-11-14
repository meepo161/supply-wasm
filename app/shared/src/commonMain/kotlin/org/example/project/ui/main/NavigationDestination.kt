package org.example.project.ui.main

import org.example.project.dto.UserDto
import org.example.project.dto.hasPermission

enum class NavigationDestination(
    val label: String,
    private val requiredPermissions: List<String> = emptyList()
) {
    HOME("Главная"),
    TASKS("Мои задачи"),
    PROJECTS("Проекты"),
    WMS("Склад", listOf("wms:read")),
    BOM("Номенклатура и BOM", listOf("bom:read_any")),
    DEMANDS("Снабжение", listOf("demands:create", "demands:read_any")),
    DOCUMENTS("Документы"),
    NOTIFICATIONS("Уведомления"),
    ADMIN(
        "Администрирование",
        listOf("users:read_any", "departments:read", "roles:read", "permissions:read")
    ),
    PROFILE("Профиль");

    fun isVisibleTo(user: UserDto): Boolean = requiredPermissions.any { permission ->
        user.hasPermission(permission.substringBefore(':'), permission.substringAfter(':'))
    } || requiredPermissions.isEmpty()
}
