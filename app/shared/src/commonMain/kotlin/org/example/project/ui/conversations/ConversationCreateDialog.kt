package org.example.project.ui.conversations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import org.example.project.ui.theme.ErpFilterChip as FilterChip
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.example.project.dto.ApiError
import org.example.project.dto.ConversationKind
import org.example.project.dto.UserOptionDto
import org.example.project.ui.projects.ErrorMessageCard

@Composable
fun ConversationCreateDialog(
    userOptions: List<UserOptionDto>,
    currentUserId: Long,
    isLoadingDirectory: Boolean,
    directoryError: ApiError?,
    isCreating: Boolean,
    error: ApiError?,
    onCreateDirect: (Long) -> Unit,
    onCreateGroup: (String, List<Long>) -> Unit,
    onRetryDirectory: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var kind by remember { mutableStateOf(ConversationKind.DIRECT) }
    var title by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var selectedMembers by remember { mutableStateOf(setOf<Long>()) }

    val candidates = userOptions.filter { it.id != currentUserId }
    val filteredCandidates = filterEmployeeOptions(candidates, query)
    val selectedUsers = candidates.filter { it.id in selectedMembers }
    val canCreate = !isCreating && when (kind) {
        ConversationKind.DIRECT -> selectedMembers.size == 1
        ConversationKind.GROUP -> title.isNotBlank() && selectedMembers.isNotEmpty()
        else -> false
    }

    AlertDialog(
        onDismissRequest = { if (!isCreating) onDismiss() },
        modifier = modifier,
        title = { Text("Новый чат") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = kind == ConversationKind.DIRECT,
                        onClick = {
                            kind = ConversationKind.DIRECT
                            selectedMembers = selectedMembers.take(1).toSet()
                        },
                        enabled = !isCreating,
                        label = { Text("Личный") }
                    )
                    FilterChip(
                        selected = kind == ConversationKind.GROUP,
                        onClick = { kind = ConversationKind.GROUP },
                        enabled = !isCreating,
                        label = { Text("Групповой") }
                    )
                }

                if (kind == ConversationKind.GROUP) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Название чата *") },
                        singleLine = true,
                        enabled = !isCreating,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Поиск сотрудника по ФИО") },
                    singleLine = true,
                    enabled = !isCreating,
                    modifier = Modifier.fillMaxWidth()
                )

                error?.let { ErrorMessageCard(error = it) }
                directoryError?.let {
                    ErrorMessageCard(error = it)
                    TextButton(onClick = onRetryDirectory, enabled = !isLoadingDirectory && !isCreating) {
                        Text("Повторить загрузку сотрудников")
                    }
                }

                when {
                    isLoadingDirectory -> Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator()
                        Text("Загрузка справочника сотрудников…")
                    }
                    candidates.isEmpty() && directoryError == null -> Text(
                        text = "В справочнике нет доступных сотрудников, собеседника выбрать нельзя.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    filteredCandidates.isEmpty() -> Text(
                        text = "Сотрудники по запросу не найдены. Измените ФИО в строке поиска.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    ) {
                        Text(
                            text = if (kind == ConversationKind.DIRECT) "Собеседник" else "Участники",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        filteredCandidates.forEach { user ->
                            val checked = user.id in selectedMembers
                            val toggle = {
                                if (!isCreating) {
                                    selectedMembers = when {
                                        kind == ConversationKind.DIRECT -> setOf(user.id)
                                        checked -> selectedMembers - user.id
                                        else -> selectedMembers + user.id
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !isCreating, onClick = toggle)
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Собеседник личного чата один, поэтому переключатель, а не флажок.
                                if (kind == ConversationKind.DIRECT) {
                                    androidx.compose.material3.RadioButton(
                                        selected = checked,
                                        onClick = { toggle() },
                                        enabled = !isCreating
                                    )
                                } else {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { toggle() },
                                        enabled = !isCreating
                                    )
                                }
                                Text(user.fullName, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                if (kind != ConversationKind.DIRECT && selectedUsers.isNotEmpty()) {
                    Text("Выбранные участники (${selectedUsers.size})", style = MaterialTheme.typography.labelLarge)
                    selectedUsers.forEach { user ->
                        FilterChip(
                            selected = true,
                            onClick = { selectedMembers = selectedMembers - user.id },
                            enabled = !isCreating,
                            label = { Text("${user.fullName} · убрать") },
                            modifier = Modifier.semantics {
                                contentDescription = "Убрать участника ${user.fullName}"
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (kind) {
                        ConversationKind.DIRECT -> selectedMembers.firstOrNull()?.let(onCreateDirect)
                        ConversationKind.GROUP -> onCreateGroup(title.trim(), selectedMembers.toList())
                        else -> Unit
                    }
                },
                enabled = canCreate
            ) {
                Text(if (isCreating) "Создание…" else "Создать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isCreating) {
                Text("Отмена")
            }
        }
    )
}
