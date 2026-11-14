package org.example.project.ui.admin

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.unchil.un7datagrid.Un7KCMPDataGrid
import com.unchil.un7datagrid.Un7KCMPDataGridConfig
import org.example.project.dto.DepartmentDto
import org.example.project.dto.RoleDto
import org.example.project.dto.UserDto

private val gridConfig = Un7KCMPDataGridConfig(
    isUsableHaptic = false,
    pageSizeList = listOf("10", "20", "50", "100"),
    defaultPageSizeListIndex = 0
)

@Composable
fun UsersGrid(
    users: List<UserDto>,
    departments: List<DepartmentDto>,
    onClick: (UserDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "ID" to users.map { it.id.toString() },
        "Логин" to users.map { it.login },
        "ФИО" to users.map { it.fullName },
        "Email" to users.map { it.email ?: "—" },
        "Подразделение" to users.map { user ->
            departments.find { it.id == user.departmentId }?.name ?: "—"
        },
        "Активен" to users.map { if (it.active) "Да" else "Нет" }
    )
    Un7KCMPDataGrid(
        data = data,
        modifier = modifier,
        config = gridConfig,
        onClick = { selected ->
            val id = selected.firstOrNull()?.second?.firstOrNull() as? String
            users.firstOrNull { it.id.toString() == id }?.let(onClick)
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
        "ID" to departments.map { it.id.toString() },
        "Код" to departments.map { it.code },
        "Название" to departments.map { it.name },
        "Родитель" to departments.map { dept ->
            dept.parentId?.let { parentId ->
                departments.find { it.id == parentId }?.name ?: "ID $parentId"
            } ?: "—"
        },
        "Руководитель" to departments.map { dept ->
            dept.headId?.let { headId ->
                users.find { it.id == headId }?.fullName ?: "ID $headId"
            } ?: "—"
        }
    )
    Un7KCMPDataGrid(
        data = data,
        modifier = modifier,
        config = gridConfig,
        onClick = { selected ->
            val id = selected.firstOrNull()?.second?.firstOrNull() as? String
            departments.firstOrNull { it.id.toString() == id }?.let(onClick)
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
        "ID" to roles.map { it.id.toString() },
        "Код" to roles.map { it.code },
        "Название" to roles.map { it.name },
        "Описание" to roles.map { it.description ?: "—" }
    )
    Un7KCMPDataGrid(
        data = data,
        modifier = modifier,
        config = gridConfig,
        onClick = { selected ->
            val id = selected.firstOrNull()?.second?.firstOrNull() as? String
            roles.firstOrNull { it.id.toString() == id }?.let(onClick)
        }
    )
}
