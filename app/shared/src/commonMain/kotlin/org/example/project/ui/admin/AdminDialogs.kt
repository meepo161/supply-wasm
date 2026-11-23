package org.example.project.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.example.project.dto.ApiError
import org.example.project.dto.DepartmentDto
import org.example.project.dto.RoleDto
import org.example.project.dto.UserDto
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.projects.UserDropdown
import org.example.project.ui.theme.ErpDialog
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.Gradients

@Composable
fun CreateUserDialog(
    form: CreateUserForm,
    departments: List<DepartmentDto>,
    roles: List<RoleDto>,
    error: ApiError?,
    onFormChange: (CreateUserForm) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    ErpDialog(
        title = "Новый пользователь",
        glyph = ErpIconGlyph.User,
        gradient = Gradients.Primary,
        width = 660.dp,
        confirmText = "Создать",
        onConfirm = onCreate,
        onDismiss = onDismiss
    ) {
        error?.let { ErrorMessageCard(error = it) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = form.login,
                onValueChange = { onFormChange(form.copy(login = it)) },
                label = { Text("Логин *") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = form.password,
                onValueChange = { onFormChange(form.copy(password = it)) },
                label = { Text("Пароль * (от 6 символов)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f)
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = form.fullName,
                onValueChange = { onFormChange(form.copy(fullName = it)) },
                label = { Text("ФИО *") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = form.email,
                onValueChange = { onFormChange(form.copy(email = it)) },
                label = { Text("Email") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        DepartmentDropdown(
            departments = departments,
            selectedId = form.departmentId,
            onSelect = { onFormChange(form.copy(departmentId = it)) },
            modifier = Modifier.fillMaxWidth()
        )
        RoleChecklist(
            roles = roles,
            selected = form.roleIds,
            onToggle = { roleId ->
                val updated = if (roleId in form.roleIds) {
                    form.roleIds - roleId
                } else {
                    form.roleIds + roleId
                }
                onFormChange(form.copy(roleIds = updated))
            }
        )
    }
}

@Composable
fun EditUserDialog(
    form: EditUserForm,
    departments: List<DepartmentDto>,
    roles: List<RoleDto>,
    error: ApiError?,
    onFormChange: (EditUserForm) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    ErpDialog(
        title = "Редактирование пользователя",
        glyph = ErpIconGlyph.User,
        gradient = Gradients.Primary,
        width = 660.dp,
        confirmText = "Сохранить",
        onConfirm = onSave,
        onDismiss = onDismiss
    ) {
        error?.let { ErrorMessageCard(error = it) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = form.fullName,
                onValueChange = { onFormChange(form.copy(fullName = it)) },
                label = { Text("ФИО *") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = form.email,
                onValueChange = { onFormChange(form.copy(email = it)) },
                label = { Text("Email") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        DepartmentDropdown(
            departments = departments,
            selectedId = form.departmentId,
            onSelect = { onFormChange(form.copy(departmentId = it)) },
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = form.changeRoles,
                onCheckedChange = { onFormChange(form.copy(changeRoles = it)) }
            )
            Text(
                text = "Изменить роли (заменяет текущий набор)",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (form.changeRoles) {
            RoleChecklist(
                roles = roles,
                selected = form.roleIds,
                onToggle = { roleId ->
                    val updated = if (roleId in form.roleIds) {
                        form.roleIds - roleId
                    } else {
                        form.roleIds + roleId
                    }
                    onFormChange(form.copy(roleIds = updated))
                }
            )
        }
    }
}

@Composable
fun ResetPasswordDialog(
    form: ResetPasswordForm,
    error: ApiError?,
    onFormChange: (ResetPasswordForm) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    ErpDialog(
        title = "Сброс пароля",
        glyph = ErpIconGlyph.Shield,
        gradient = Gradients.Sunset,
        width = 520.dp,
        confirmText = "Сбросить",
        onConfirm = onConfirm,
        onDismiss = onDismiss
    ) {
        Text(
            text = "Пользователь: ${form.userName}. После сброса все активные сеансы пользователя будут завершены.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        error?.let { ErrorMessageCard(error = it) }
        OutlinedTextField(
            value = form.newPassword,
            onValueChange = { onFormChange(form.copy(newPassword = it)) },
            label = { Text("Новый пароль * (от 6 символов)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = form.confirmPassword,
            onValueChange = { onFormChange(form.copy(confirmPassword = it)) },
            label = { Text("Подтверждение пароля *") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun DepartmentDialog(
    form: DepartmentForm,
    departments: List<DepartmentDto>,
    users: List<UserDto>,
    isEdit: Boolean,
    error: ApiError?,
    onFormChange: (DepartmentForm) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    ErpDialog(
        title = if (isEdit) "Редактирование подразделения" else "Новое подразделение",
        glyph = ErpIconGlyph.Shield,
        gradient = Gradients.Primary,
        width = 600.dp,
        confirmText = if (isEdit) "Сохранить" else "Создать",
        onConfirm = onSave,
        onDismiss = onDismiss
    ) {
        error?.let { ErrorMessageCard(error = it) }
        if (!isEdit) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = form.code,
                    onValueChange = { onFormChange(form.copy(code = it)) },
                    label = { Text("Код *") },
                    singleLine = true,
                    modifier = Modifier.weight(0.4f)
                )
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { onFormChange(form.copy(name = it)) },
                    label = { Text("Название *") },
                    singleLine = true,
                    modifier = Modifier.weight(0.6f)
                )
            }
        } else {
            OutlinedTextField(
                value = form.name,
                onValueChange = { onFormChange(form.copy(name = it)) },
                label = { Text("Название *") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        DepartmentDropdown(
            departments = departments.filter { it.id != form.id },
            selectedId = form.parentId,
            onSelect = { onFormChange(form.copy(parentId = it)) },
            label = "Родительское подразделение",
            modifier = Modifier.fillMaxWidth()
        )
        if (users.isNotEmpty()) {
            UserDropdown(
                users = users,
                selected = users.find { it.id == form.headId },
                onSelect = { onFormChange(form.copy(headId = it.id)) },
                label = "Руководитель",
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun CreateRoleDialog(
    form: CreateRoleForm,
    error: ApiError?,
    onFormChange: (CreateRoleForm) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    ErpDialog(
        title = "Новая роль",
        glyph = ErpIconGlyph.Shield,
        gradient = Gradients.Primary,
        width = 660.dp,
        confirmText = "Создать",
        onConfirm = onCreate,
        onDismiss = onDismiss
    ) {
        error?.let { ErrorMessageCard(error = it) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = form.code,
                onValueChange = { onFormChange(form.copy(code = it)) },
                label = { Text("Код *") },
                singleLine = true,
                modifier = Modifier.weight(0.45f)
            )
            OutlinedTextField(
                value = form.name,
                onValueChange = { onFormChange(form.copy(name = it)) },
                label = { Text("Название *") },
                singleLine = true,
                modifier = Modifier.weight(0.55f)
            )
        }
        OutlinedTextField(
            value = form.description,
            onValueChange = { onFormChange(form.copy(description = it)) },
            label = { Text("Описание") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "Права роли (resource/action/scope)",
            style = MaterialTheme.typography.titleSmall
        )
        form.permissions.forEachIndexed { index, permission ->
            PermissionRow(
                permission = permission,
                onChange = { updated ->
                    val list = form.permissions.toMutableList()
                    list[index] = updated
                    onFormChange(form.copy(permissions = list))
                },
                onRemove = {
                    val list = form.permissions.toMutableList()
                    list.removeAt(index)
                    onFormChange(form.copy(permissions = list))
                }
            )
        }
        TextButton(
            onClick = {
                onFormChange(form.copy(permissions = form.permissions + PermissionForm()))
            }
        ) {
            Text("Добавить право")
        }
    }
}

@Composable
fun AddPermissionDialog(
    form: PermissionForm,
    error: ApiError?,
    onFormChange: (PermissionForm) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit
) {
    ErpDialog(
        title = "Новое право",
        glyph = ErpIconGlyph.Shield,
        gradient = Gradients.Primary,
        width = 540.dp,
        confirmText = "Добавить",
        onConfirm = onAdd,
        onDismiss = onDismiss
    ) {
        error?.let { ErrorMessageCard(error = it) }
        OutlinedTextField(
            value = form.resource,
            onValueChange = { onFormChange(form.copy(resource = it)) },
            label = { Text("Resource *") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = form.action,
            onValueChange = { onFormChange(form.copy(action = it)) },
            label = { Text("Action *") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = form.scope,
            onValueChange = { onFormChange(form.copy(scope = it)) },
            label = { Text("Scope") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun PermissionRow(
    permission: PermissionForm,
    onChange: (PermissionForm) -> Unit,
    onRemove: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = permission.resource,
                onValueChange = { onChange(permission.copy(resource = it)) },
                label = { Text("Resource") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = permission.action,
                onValueChange = { onChange(permission.copy(action = it)) },
                label = { Text("Action") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRemove) {
                Text("Убрать", color = MaterialTheme.colorScheme.error)
            }
        }
        OutlinedTextField(
            value = permission.scope,
            onValueChange = { onChange(permission.copy(scope = it)) },
            label = { Text("Scope") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun RoleChecklist(
    roles: List<RoleDto>,
    selected: Set<Long>,
    onToggle: (Long) -> Unit
) {
    Column {
        Text(
            text = "Роли",
            style = MaterialTheme.typography.titleSmall
        )
        if (roles.isEmpty()) {
            Text(
                text = "Список ролей недоступен.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        roles.forEach { role ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = role.id in selected,
                    onCheckedChange = { onToggle(role.id) }
                )
                Text(
                    text = "${role.name} (${role.code})",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DepartmentDropdown(
    departments: List<DepartmentDto>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Подразделение"
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = departments.find { it.id == selectedId }?.name ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("—") },
                onClick = {
                    onSelect(null)
                    expanded = false
                }
            )
            departments.forEach { department ->
                DropdownMenuItem(
                    text = { Text("${department.code} — ${department.name}") },
                    onClick = {
                        onSelect(department.id)
                        expanded = false
                    }
                )
            }
        }
    }
}
