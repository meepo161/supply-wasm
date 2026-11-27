package org.example.project.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import org.example.project.ui.theme.ErpFilterChip as FilterChip
import androidx.compose.material3.MaterialTheme
import org.example.project.ui.theme.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.dto.ProjectDto
import org.example.project.dto.TaskArea
import org.example.project.dto.TaskPriority
import org.example.project.dto.TaskWorkType
import org.example.project.dto.UserDto
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.theme.ErpDialog
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.Gradients
import org.example.project.ui.projects.UserDropdown

@Composable
fun TaskCreateDialog(
    form: TaskCreateForm,
    users: List<UserDto>,
    projects: List<ProjectDto>,
    currentUser: UserDto,
    error: org.example.project.dto.ApiError?,
    onFormChange: (TaskCreateForm) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    ErpDialog(
        title = "Новая задача",
        subtitle = "Опишите работу, назначьте исполнителя и срок",
        glyph = ErpIconGlyph.Tasks,
        gradient = Gradients.Ocean,
        width = 660.dp,
        maxBodyHeight = 680.dp,
        onDismiss = onDismiss,
        confirmText = "Создать задачу",
        onConfirm = onCreate,
        confirmGradient = Gradients.Ocean
    ) {
        error?.let {
            ErrorMessageCard(error = it)
        }

        Text(
            text = "Область задачи",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = form.area == TaskArea.PERSONAL,
                onClick = { onFormChange(form.copy(area = TaskArea.PERSONAL, projectId = null)) },
                label = { Text("Личная") }
            )
            FilterChip(
                selected = form.area == TaskArea.PROJECT,
                onClick = { onFormChange(form.copy(area = TaskArea.PROJECT)) },
                label = { Text("Проектная") }
            )
        }

        OutlinedTextField(
            value = form.title,
            onValueChange = { onFormChange(form.copy(title = it)) },
            label = { Text("Название *") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = form.description,
            onValueChange = { onFormChange(form.copy(description = it)) },
            label = {
                val required = form.area == TaskArea.PROJECT &&
                    form.assigneeId != null && form.assigneeId != currentUser.id
                Text(if (required) "Описание *" else "Описание")
            },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )

        TaskWorkTypeDropdown(
            selected = form.type,
            onSelect = { onFormChange(form.copy(type = it)) },
            modifier = Modifier.fillMaxWidth()
        )

        if (form.area == TaskArea.PROJECT) {
            if (projects.isEmpty()) {
                Text(
                    text = "Нет доступных проектов для привязки задачи.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                ProjectDropdown(
                    projects = projects,
                    selected = projects.find { it.id == form.projectId },
                    onSelect = { onFormChange(form.copy(projectId = it.id)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (users.isEmpty()) {
                Text(
                    text = "Справочник пользователей недоступен, исполнителя выбрать нельзя.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                UserDropdown(
                    users = users,
                    selected = users.find { it.id == form.assigneeId },
                    onSelect = { onFormChange(form.copy(assigneeId = it.id)) },
                    label = "Исполнитель *",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (form.type == TaskWorkType.REVIEWED) {
            if (users.isNotEmpty()) {
                UserDropdown(
                    users = users.filter { it.id != form.assigneeId },
                    selected = users.find { it.id == form.reviewerId },
                    onSelect = { onFormChange(form.copy(reviewerId = it.id)) },
                    label = "Проверяющий *",
                    modifier = Modifier.fillMaxWidth()
                )
            }
            OutlinedTextField(
                value = form.acceptanceCriteria,
                onValueChange = { onFormChange(form.copy(acceptanceCriteria = it)) },
                label = { Text("Критерии приёмки *") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
        }

        org.example.project.ui.theme.ErpDateField(
            value = form.dueDate,
            onValueChange = { onFormChange(form.copy(dueDate = it)) },
            label = "Срок выполнения",
            modifier = Modifier.fillMaxWidth()
        )

        TaskPriorityDropdown(
            selected = form.priority,
            onSelect = { onFormChange(form.copy(priority = it)) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectDropdown(
    projects: List<ProjectDto>,
    selected: ProjectDto?,
    onSelect: (ProjectDto) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected?.let { "${it.number} ${it.name}" } ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text("Проект *") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            projects.forEach { project ->
                DropdownMenuItem(
                    text = { Text("${project.number} ${project.name}") },
                    onClick = {
                        onSelect(project)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskWorkTypeDropdown(
    selected: TaskWorkType,
    onSelect: (TaskWorkType) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected.localizedName(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Тип работы *") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            TaskWorkType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.localizedName()) },
                    onClick = {
                        onSelect(type)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskPriorityDropdown(
    selected: TaskPriority,
    onSelect: (TaskPriority) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected.localizedName(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Приоритет") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            TaskPriority.entries.forEach { priority ->
                DropdownMenuItem(
                    text = { Text(priority.localizedName()) },
                    onClick = {
                        onSelect(priority)
                        expanded = false
                    }
                )
            }
        }
    }
}
