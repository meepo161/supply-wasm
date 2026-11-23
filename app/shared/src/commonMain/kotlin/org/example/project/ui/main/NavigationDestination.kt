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
    TASKS("Задачи", glyph = ErpIconGlyph.Tasks, group = NavGroup.WORK,
        gradient = Gradients.Ocean, description = "Задачи и приёмка"),
    PROJECTS("Проекты", glyph = ErpIconGlyph.Projects, group = NavGroup.WORK,
        gradient = Gradients.Aurora, description = "Портфель проектов"),
    CHAT("Чат", glyph = ErpIconGlyph.Send, group = NavGroup.WORK,
        gradient = Gradients.Ocean, description = "Личные и групповые разговоры"),
    WMS("Склад", listOf("wms:read"), glyph = ErpIconGlyph.Warehouse, group = NavGroup.SUPPLY,
        gradient = Gradients.Emerald, description = "Складской учёт"),
    PRODUCTION("Производство", listOf("production:read"), glyph = ErpIconGlyph.TrendUp,
        group = NavGroup.SUPPLY, gradient = Gradients.Aurora, description = "Заказы и выпуск"),
    BOM("Спецификации (BOM)", listOf("bom:read_any"), glyph = ErpIconGlyph.Layers, group = NavGroup.SUPPLY,
        gradient = Gradients.Berry, description = "Состав изделий и ревизии"),
    SUPPLIERS("Поставщики", listOf("suppliers:read"), glyph = ErpIconGlyph.Cart,
        group = NavGroup.SUPPLY, gradient = Gradients.Sunset, description = "Контрагенты и предложения"),
    PURCHASE_ORDERS("Заказы поставщикам", listOf("suppliers:read"), glyph = ErpIconGlyph.Documents,
        group = NavGroup.SUPPLY, gradient = Gradients.Berry, description = "Заказы и поставки"),
    DEMANDS("Потребности", listOf("demands:create", "demands:read_any"), glyph = ErpIconGlyph.Cart,
        group = NavGroup.SUPPLY, gradient = Gradients.Sunset, description = "Заявки и закупки"),
    DOCUMENTS("Документы", glyph = ErpIconGlyph.Documents, group = NavGroup.SUPPLY,
        gradient = Gradients.AmberGlow, description = "Файлы и версии"),
    ANALYTICS("Аналитика и календарь", glyph = ErpIconGlyph.TrendUp, group = NavGroup.SUPPLY,
        gradient = Gradients.Ocean, description = "Показатели, календарь и экспорт"),
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
