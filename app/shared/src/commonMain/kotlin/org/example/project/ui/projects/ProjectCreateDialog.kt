package org.example.project.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.dto.UserDto

@Composable
fun ProjectCreateDialog(
    form: ProjectCreateForm,
    users: List<UserDto>,
    currentUser: org.example.project.dto.UserDto,
    error: org.example.project.dto.ApiError?,
    onFormChange: (ProjectCreateForm) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый проект") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                error?.let {
                    ErrorMessageCard(error = it, modifier = Modifier.padding(bottom = 4.dp))
                }

                OutlinedTextField(
                    value = form.name,
                    onValueChange = { onFormChange(form.copy(name = it)) },
                    label = { Text("Название *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = form.fullName,
                    onValueChange = { onFormChange(form.copy(fullName = it)) },
                    label = { Text("Полное название") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )

                OutlinedTextField(
                    value = form.description,
                    onValueChange = { onFormChange(form.copy(description = it)) },
                    label = { Text("Описание") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )

                ProjectTypeDropdown(
                    selected = form.projectType,
                    onSelect = { onFormChange(form.copy(projectType = it)) },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = form.customer,
                    onValueChange = { onFormChange(form.copy(customer = it)) },
                    label = { Text("Заказчик") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                UserDropdown(
                    users = users.ifEmpty { listOf(currentUser.toUserDto()) },
                    selected = users.find { it.id == form.ownerId }
                        ?: currentUser.toUserDto().takeIf { form.ownerId == null },
                    onSelect = { onFormChange(form.copy(ownerId = it.id)) },
                    label = "Руководитель *",
                    modifier = Modifier.fillMaxWidth()
                )

                PriorityDropdown(
                    selected = form.priority,
                    onSelect = { onFormChange(form.copy(priority = it)) },
                    modifier = Modifier.fillMaxWidth()
                )

                org.example.project.ui.theme.ErpDateField(
                    value = form.plannedStartAt,
                    onValueChange = { onFormChange(form.copy(plannedStartAt = it)) },
                    label = "Плановое начало",
                    format = org.example.project.ui.theme.DateValueFormat.START_OF_DAY_UTC,
                    modifier = Modifier.fillMaxWidth()
                )

                org.example.project.ui.theme.ErpDateField(
                    value = form.plannedEndAt,
                    onValueChange = { onFormChange(form.copy(plannedEndAt = it)) },
                    label = "Плановое окончание",
                    format = org.example.project.ui.theme.DateValueFormat.START_OF_DAY_UTC,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = onCreate) {
                Text("Создать черновик")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

private fun org.example.project.dto.UserDto.toUserDto(): UserDto = UserDto(
    id = id,
    login = login,
    fullName = fullName,
    email = email,
    departmentId = departmentId,
    active = active,
    createdAt = createdAt,
    version = version
)
