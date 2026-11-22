package org.example.project.ui.documents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.dto.ApiError
import org.example.project.dto.UserDto
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.projects.UserDropdown

@Composable
fun CreateDocumentDialog(
    form: CreateDocumentForm,
    users: List<UserDto>,
    canAssignOwner: Boolean,
    error: ApiError?,
    onFormChange: (CreateDocumentForm) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый документ") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                error?.let { ErrorMessageCard(error = it) }
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { onFormChange(form.copy(name = it)) },
                    label = { Text("Название *") },
                    modifier = Modifier.fillMaxWidth()
                )
                DocumentTypeDropdown(
                    selected = form.docType,
                    onSelect = { onFormChange(form.copy(docType = it)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = form.description,
                    onValueChange = { onFormChange(form.copy(description = it)) },
                    label = { Text("Описание") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                if (canAssignOwner && users.isNotEmpty()) {
                    UserDropdown(
                        users = users,
                        selected = users.find { it.id == form.ownerId },
                        onSelect = { onFormChange(form.copy(ownerId = it.id)) },
                        label = "Владелец (по умолчанию — вы)",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                ConfidentialityDropdown(
                    selected = form.confidentiality,
                    onSelect = { onFormChange(form.copy(confidentiality = it)) },
                    modifier = Modifier.fillMaxWidth()
                )
                org.example.project.ui.theme.ErpDateField(
                    value = form.retentionUntil,
                    onValueChange = { onFormChange(form.copy(retentionUntil = it)) },
                    label = "Срок хранения до",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = onCreate) { Text("Создать") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
fun EditDocumentDialog(
    form: EditDocumentForm,
    error: ApiError?,
    onFormChange: (EditDocumentForm) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Реквизиты документа") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                error?.let { ErrorMessageCard(error = it) }
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { onFormChange(form.copy(name = it)) },
                    label = { Text("Название *") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = form.description,
                    onValueChange = { onFormChange(form.copy(description = it)) },
                    label = { Text("Описание") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                ConfidentialityDropdown(
                    selected = form.confidentiality,
                    onSelect = { onFormChange(form.copy(confidentiality = it)) },
                    modifier = Modifier.fillMaxWidth()
                )
                org.example.project.ui.theme.ErpDateField(
                    value = form.retentionUntil,
                    onValueChange = { onFormChange(form.copy(retentionUntil = it)) },
                    label = "Срок хранения до",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = onSave) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
fun UploadVersionDialog(
    changeReason: String,
    isReplacement: Boolean,
    isUploading: Boolean,
    error: ApiError?,
    onReasonChange: (String) -> Unit,
    onPickAndUpload: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая версия документа") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                error?.let { ErrorMessageCard(error = it) }
                if (isReplacement) {
                    Text(
                        text = "Замена файла создаёт новую версию; прежняя версия сохраняется и остаётся неизменяемой.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                OutlinedTextField(
                    value = changeReason,
                    onValueChange = onReasonChange,
                    label = { Text(if (isReplacement) "Причина изменения *" else "Причина изменения") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = onPickAndUpload, enabled = !isUploading) {
                Text(if (isUploading) "Загрузка…" else "Выбрать файл и загрузить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}
