@file:OptIn(ExperimentalMaterial3Api::class)

package org.example.project.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.dto.UserDto
import org.example.project.ui.projects.ErrorMessageCard

@Composable
fun AdminScreen(
    viewModel: AdminViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Администрирование") })
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PrimaryTabRow(selectedTabIndex = state.selectedTab.ordinal) {
                AdminTab.entries.forEach { tab ->
                    Tab(
                        selected = state.selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = { Text(tab.label) }
                    )
                }
            }
            state.operationError?.let { ErrorMessageCard(error = it) }
            if (state.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            when (state.selectedTab) {
                AdminTab.USERS -> UsersTab(state, viewModel)
                AdminTab.DEPARTMENTS -> DepartmentsTab(state, viewModel)
                AdminTab.ROLES -> RolesTab(state, viewModel)
            }
        }
    }

    when (state.activeDialog) {
        AdminDialog.CreateUser -> CreateUserDialog(
            form = state.createUserForm,
            departments = state.departments,
            roles = state.roles,
            error = state.operationError,
            onFormChange = viewModel::updateCreateUserForm,
            onCreate = viewModel::createUser,
            onDismiss = viewModel::dismissDialog
        )
        AdminDialog.EditUser -> EditUserDialog(
            form = state.editUserForm,
            departments = state.departments,
            roles = state.roles,
            error = state.operationError,
            onFormChange = viewModel::updateEditUserForm,
            onSave = viewModel::updateUser,
            onDismiss = viewModel::dismissDialog
        )
        AdminDialog.CreateDepartment, AdminDialog.EditDepartment -> DepartmentDialog(
            form = state.departmentForm,
            departments = state.departments,
            users = state.users,
            isEdit = state.activeDialog == AdminDialog.EditDepartment,
            error = state.operationError,
            onFormChange = viewModel::updateDepartmentForm,
            onSave = viewModel::saveDepartment,
            onDismiss = viewModel::dismissDialog
        )
        AdminDialog.CreateRole -> CreateRoleDialog(
            form = state.createRoleForm,
            error = state.operationError,
            onFormChange = viewModel::updateCreateRoleForm,
            onCreate = viewModel::createRole,
            onDismiss = viewModel::dismissDialog
        )
        AdminDialog.AddPermission -> AddPermissionDialog(
            form = state.permissionForm,
            error = state.operationError,
            onFormChange = viewModel::updatePermissionForm,
            onAdd = viewModel::addPermission,
            onDismiss = viewModel::dismissDialog
        )
        null -> { }
    }
}

@Composable
private fun UsersTab(state: AdminUiState, viewModel: AdminViewModel) {
    if (!viewModel.can("users", "read_any")) {
        NoAccess("Недостаточно прав для просмотра пользователей (users:read_any).")
        return
    }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (viewModel.can("users", "create")) {
                Button(onClick = { viewModel.showDialog(AdminDialog.CreateUser) }) {
                    Text("Новый пользователь")
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            TextButton(onClick = viewModel::loadUsers) { Text("Обновить") }
        }
        if (state.users.isEmpty()) {
            EmptyState("Нет пользователей.")
            return@Column
        }
        UsersGrid(
            users = state.users,
            departments = state.departments,
            onClick = { viewModel.selectUser(it.id) },
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
        val selected = state.users.find { it.id == state.selectedUserId }
        if (selected != null) {
            UserActionsCard(selected, viewModel)
        }
    }
}

@Composable
private fun UserActionsCard(user: UserDto, viewModel: AdminViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "${user.fullName} (${user.login})",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Статус: ${if (user.active) "активен" else "деактивирован"} · Версия записи: ${user.version}",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (viewModel.can("users", "update_any")) {
                    OutlinedButton(onClick = { viewModel.showDialog(AdminDialog.EditUser) }) {
                        Text("Изменить")
                    }
                }
                if (viewModel.can("users", "activate") && !viewModel.isSelf(user.id)) {
                    if (user.active) {
                        OutlinedButton(onClick = { viewModel.setUserActive(user, false) }) {
                            Text("Деактивировать", color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        OutlinedButton(onClick = { viewModel.setUserActive(user, true) }) {
                            Text("Активировать")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DepartmentsTab(state: AdminUiState, viewModel: AdminViewModel) {
    if (!viewModel.can("departments", "read")) {
        NoAccess("Недостаточно прав для просмотра подразделений (departments:read).")
        return
    }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (viewModel.can("departments", "create")) {
                Button(onClick = {
                    viewModel.updateDepartmentForm(DepartmentForm())
                    viewModel.showDialog(AdminDialog.CreateDepartment)
                }) {
                    Text("Новое подразделение")
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            TextButton(onClick = viewModel::loadDepartments) { Text("Обновить") }
        }
        if (state.departments.isEmpty()) {
            EmptyState("Нет подразделений.")
            return@Column
        }
        DepartmentsGrid(
            departments = state.departments,
            users = state.users,
            onClick = { viewModel.selectDepartment(it.id) },
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
        val selected = state.departments.find { it.id == state.selectedDepartmentId }
        if (selected != null && viewModel.can("departments", "update")) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${selected.code} — ${selected.name}",
                        style = MaterialTheme.typography.titleMedium
                    )
                    OutlinedButton(onClick = { viewModel.showDialog(AdminDialog.EditDepartment) }) {
                        Text("Изменить")
                    }
                }
            }
        }
    }
}

@Composable
private fun RolesTab(state: AdminUiState, viewModel: AdminViewModel) {
    if (!viewModel.can("roles", "read")) {
        NoAccess("Недостаточно прав для просмотра ролей (roles:read).")
        return
    }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (viewModel.can("roles", "create")) {
                Button(onClick = { viewModel.showDialog(AdminDialog.CreateRole) }) {
                    Text("Новая роль")
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            TextButton(onClick = {
                viewModel.loadRoles()
                viewModel.loadPermissions()
            }) { Text("Обновить") }
        }
        if (state.roles.isEmpty()) {
            EmptyState("Нет ролей.")
            return@Column
        }
        RolesGrid(
            roles = state.roles,
            onClick = { viewModel.selectRole(it.id) },
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
        val selectedRole = state.roles.find { it.id == state.selectedRoleId }
        if (selectedRole != null) {
            RolePermissionsCard(selectedRole.id, selectedRole.name, selectedRole.code, state, viewModel)
        }
    }
}

@Composable
private fun RolePermissionsCard(
    roleId: Long,
    roleName: String,
    roleCode: String,
    state: AdminUiState,
    viewModel: AdminViewModel
) {
    val rolePermissions = state.permissions.filter { it.roleId == roleId }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Права роли $roleName ($roleCode)",
                style = MaterialTheme.typography.titleMedium
            )
            if (!viewModel.can("permissions", "read")) {
                Text(
                    text = "Недостаточно прав для просмотра прав роли (permissions:read).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (rolePermissions.isEmpty()) {
                Text(
                    text = "Прав нет.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                rolePermissions.forEach { permission ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${permission.resource}:${permission.action}" +
                                (permission.scope?.let { " [$it]" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (viewModel.can("permissions", "delete")) {
                            TextButton(onClick = { viewModel.deletePermission(permission.id) }) {
                                Text("Удалить", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            if (viewModel.can("permissions", "create")) {
                OutlinedButton(onClick = { viewModel.showDialog(AdminDialog.AddPermission) }) {
                    Text("Добавить право")
                }
            }
        }
    }
}

@Composable
private fun NoAccess(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
