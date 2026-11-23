package org.example.project.ui.admin

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.ui.tables.PersistentDataGrid
import com.unchil.un7datagrid.Un7KCMPDataGridConfig
import org.example.project.dto.DepartmentDto
import org.example.project.dto.RoleDto
import org.example.project.dto.UserDto

private val gridConfig = Un7KCMPDataGridConfig(
    isUsableHaptic = false,
    pageSizeList = listOf("20", "50", "100"),
    defaultPageSizeListIndex = 0
)

@Composable
fun UsersGrid(
    users: List<UserDto>,
    departments: List<DepartmentDto>,
    roles: List<RoleDto> = emptyList(),
    onClick: (UserDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val roleNamesById = roles.associate { it.id to it.name }
    val data = mapOf(
        "Логин" to users.map { it.login },
        "ФИО" to users.map { it.fullName },
        "Email" to users.map { it.email?.takeIf { e -> e.isNotBlank() } ?: "Не указан" },
        "Подразделение" to users.map { user ->
            departments.find { it.id == user.departmentId }?.name ?: "—"
        },
        "Роли" to users.map { user ->
            user.roleIds.mapNotNull { roleNamesById[it] }.joinToString(", ").ifBlank { "—" }
        },
        "Активен" to users.map { if (it.active) "Да" else "Нет" }
    )
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "admin-users",
        config = gridConfig,
        onClick = { selected ->
            selected.firstOrNull()?.first?.let { idx ->
                users.getOrNull(idx)?.let(onClick)
            }
        }
    )
}

@Composable
fun DepartmentsGrid(
    departments: List<DepartmentDto>,
    users: List<UserDto>,
    onClick: (DepartmentDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "Код" to departments.map { it.code },
        "Название" to departments.map { it.name },
        "Родитель" to departments.map { dept ->
            dept.parentId?.let { parentId ->
                departments.find { it.id == parentId }?.name ?: "Подразделение #$parentId"
            } ?: "—"
        },
        "Руководитель" to departments.map { dept ->
            dept.headId?.let { headId ->
                users.find { it.id == headId }?.fullName ?: "Сотрудник #$headId"
            } ?: "—"
        }
    )
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "admin-departments",
        config = gridConfig,
        onClick = { selected ->
            selected.firstOrNull()?.first?.let { idx ->
                departments.getOrNull(idx)?.let(onClick)
            }
        }
    )
}

@Composable
fun RolesGrid(
    roles: List<RoleDto>,
    onClick: (RoleDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "Код" to roles.map { it.code },
        "Название" to roles.map { it.name },
        "Описание" to roles.map { it.description ?: "—" }
    )
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "admin-roles",
        config = gridConfig,
        onClick = { selected ->
            selected.firstOrNull()?.first?.let { idx ->
                roles.getOrNull(idx)?.let(onClick)
            }
        }
    )
}
