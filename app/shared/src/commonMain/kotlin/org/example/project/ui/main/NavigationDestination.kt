package org.example.project.ui.main

import org.example.project.dto.UserDto
import org.example.project.dto.hasPermission
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.Gradients
import androidx.compose.ui.graphics.Color

/** Смысловые группы навигации в боковом меню. */
enum class NavGroup(val label: String) {
    WORK("Работа"),
    SUPPLY("Ресурсы"),
    SYSTEM("Служебное")
}

enum class NavigationDestination(
    val label: String,
    private val requiredPermissions: List<String> = emptyList(),
    val glyph: ErpIconGlyph = ErpIconGlyph.Home,
    val group: NavGroup = NavGroup.WORK,
    val gradient: List<Color> = Gradients.Primary,
    val description: String = ""
) {
    HOME("Главная", glyph = ErpIconGlyph.Home, group = NavGroup.WORK,
        gradient = Gradients.Primary, description = "Сводка дня"),
    TASKS("Мои задачи", glyph = ErpIconGlyph.Tasks, group = NavGroup.WORK,
        gradient = Gradients.Ocean, description = "Задачи и приёмка"),
    PROJECTS("Проекты", glyph = ErpIconGlyph.Projects, group = NavGroup.WORK,
        gradient = Gradients.Aurora, description = "Портфель проектов"),
    WMS("Склад", listOf("wms:read"), glyph = ErpIconGlyph.Warehouse, group = NavGroup.SUPPLY,
        gradient = Gradients.Emerald, description = "Складской учёт"),
    BOM("Номенклатура и BOM", listOf("bom:read_any"), glyph = ErpIconGlyph.Layers, group = NavGroup.SUPPLY,
        gradient = Gradients.Berry, description = "Состав изделий"),
    DEMANDS("Снабжение", listOf("demands:create", "demands:read_any"), glyph = ErpIconGlyph.Cart,
        group = NavGroup.SUPPLY, gradient = Gradients.Sunset, description = "Заявки и закупки"),
    DOCUMENTS("Документы", glyph = ErpIconGlyph.Documents, group = NavGroup.SUPPLY,
        gradient = Gradients.AmberGlow, description = "Файлы и версии"),
    NOTIFICATIONS("Уведомления", glyph = ErpIconGlyph.Bell, group = NavGroup.SYSTEM,
        gradient = Gradients.Sunset, description = "Центр событий"),
    ADMIN(
        "Администрирование",
        listOf("users:read_any", "departments:read", "roles:read", "permissions:read"),
        glyph = ErpIconGlyph.Shield, group = NavGroup.SYSTEM,
        gradient = Gradients.Slate, description = "Пользователи и роли"
    ),
    PROFILE("Профиль", glyph = ErpIconGlyph.User, group = NavGroup.SYSTEM,
        gradient = Gradients.Ocean, description = "Настройки аккаунта");

    fun isVisibleTo(user: UserDto): Boolean = requiredPermissions.any { permission ->
        user.hasPermission(permission.substringBefore(':'), permission.substringAfter(':'))
    } || requiredPermissions.isEmpty()
}
