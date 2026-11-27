package org.example.project.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import org.example.project.ui.theme.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.dto.UserDto
import org.example.project.ui.theme.ErpDialog
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.Gradients

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
    ErpDialog(
        title = "Новый проект",
        subtitle = "Карточка создаётся в статусе «Черновик»",
        glyph = ErpIconGlyph.Projects,
        gradient = Gradients.Primary,
        width = 680.dp,
        confirmText = "Создать черновик",
        onConfirm = onCreate,
        onDismiss = onDismiss
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ProjectTypeDropdown(
                selected = form.projectType,
                onSelect = { onFormChange(form.copy(projectType = it)) },
                modifier = Modifier.weight(1f)
            )
            PriorityDropdown(
                selected = form.priority,
                onSelect = { onFormChange(form.copy(priority = it)) },
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            UserDropdown(
                users = users.ifEmpty { listOf(currentUser.toUserDto()) },
                selected = users.find { it.id == form.ownerId }
                    ?: currentUser.toUserDto().takeIf { form.ownerId == null },
                onSelect = { onFormChange(form.copy(ownerId = it.id)) },
                label = "Руководитель *",
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = form.customer,
                onValueChange = { onFormChange(form.copy(customer = it)) },
                label = { Text("Заказчик") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            org.example.project.ui.theme.ErpDateField(
                value = form.plannedStartAt,
                onValueChange = { onFormChange(form.copy(plannedStartAt = it)) },
                label = "Плановое начало",
                format = org.example.project.ui.theme.DateValueFormat.START_OF_DAY_UTC,
                modifier = Modifier.weight(1f)
            )
            org.example.project.ui.theme.ErpDateField(
                value = form.plannedEndAt,
                onValueChange = { onFormChange(form.copy(plannedEndAt = it)) },
                label = "Плановое окончание",
                format = org.example.project.ui.theme.DateValueFormat.START_OF_DAY_UTC,
                modifier = Modifier.weight(1f)
            )
        }

        OutlinedTextField(
            value = form.description,
            onValueChange = { onFormChange(form.copy(description = it)) },
            label = { Text("Описание") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
    }
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
