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
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import org.example.project.dto.ApiError
import org.example.project.dto.ConversationKind
import org.example.project.dto.UserOptionDto
import org.example.project.ui.projects.ErrorMessageCard

@Composable
fun ConversationCreateDialog(
    userOptions: List<UserOptionDto>,
    currentUserId: Long,
    error: ApiError?,
    onCreateDirect: (Long) -> Unit,
    onCreateGroup: (String, List<Long>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var kind by remember { mutableStateOf(ConversationKind.DIRECT) }
    var title by remember { mutableStateOf("") }
    var selectedMembers by remember { mutableStateOf(setOf<Long>()) }

    val candidates = userOptions.filter { it.id != currentUserId }
    val canCreate = when (kind) {
        ConversationKind.DIRECT -> selectedMembers.isNotEmpty()
        ConversationKind.GROUP -> title.isNotBlank() && selectedMembers.isNotEmpty()
        else -> false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text("Новый чат") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = kind == ConversationKind.DIRECT,
                        onClick = { kind = ConversationKind.DIRECT },
                        label = { Text("Личный") }
                    )
                    FilterChip(
                        selected = kind == ConversationKind.GROUP,
                        onClick = { kind = ConversationKind.GROUP },
                        label = { Text("Групповой") }
                    )
                }

                if (kind == ConversationKind.GROUP) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Название чата *") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                error?.let { ErrorMessageCard(error = it) }

                if (candidates.isEmpty()) {
                    Text(
                        text = "Справочник сотрудников недоступен или пуст — собеседника выбрать нельзя.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = if (kind == ConversationKind.DIRECT) "Собеседник" else "Участники",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        candidates.forEach { user ->
                            val checked = user.id in selectedMembers
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedMembers = when {
                                            kind == ConversationKind.DIRECT -> setOf(user.id)
                                            checked -> selectedMembers - user.id
                                            else -> selectedMembers + user.id
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        selectedMembers = when {
                                            kind == ConversationKind.DIRECT -> setOf(user.id)
                                            checked -> selectedMembers - user.id
                                            else -> selectedMembers + user.id
                                        }
                                    }
                                )
                                Text(
                                    text = user.fullName,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
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
                Text("Создать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}
