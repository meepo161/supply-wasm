package org.example.project.ui.documents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.dto.ApiError
import org.example.project.dto.UserDto
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.projects.UserDropdown
import org.example.project.ui.theme.ErpDialog
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.Gradients

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
    ErpDialog(
        title = "Новый документ",
        glyph = ErpIconGlyph.Documents,
        gradient = Gradients.Primary,
        width = 660.dp,
        confirmText = "Создать",
        onConfirm = onCreate,
        onDismiss = onDismiss
    ) {
        error?.let { ErrorMessageCard(error = it) }
        OutlinedTextField(
            value = form.name,
            onValueChange = { onFormChange(form.copy(name = it)) },
            label = { Text("Название *") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DocumentTypeDropdown(
                selected = form.docType,
                onSelect = { onFormChange(form.copy(docType = it)) },
                modifier = Modifier.weight(1f)
            )
            ConfidentialityDropdown(
                selected = form.confidentiality,
                onSelect = { onFormChange(form.copy(confidentiality = it)) },
                modifier = Modifier.weight(1f)
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (canAssignOwner && users.isNotEmpty()) {
                UserDropdown(
                    users = users,
                    selected = users.find { it.id == form.ownerId },
                    onSelect = { onFormChange(form.copy(ownerId = it.id)) },
                    label = "Владелец (по умолчанию вы)",
                    modifier = Modifier.weight(1f)
                )
            }
            org.example.project.ui.theme.ErpDateField(
                value = form.retentionUntil,
                onValueChange = { onFormChange(form.copy(retentionUntil = it)) },
                label = "Срок хранения до",
                modifier = Modifier.weight(1f)
            )
        }
        OutlinedTextField(
            value = form.description,
            onValueChange = { onFormChange(form.copy(description = it)) },
            label = { Text("Описание") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = {
                    org.example.project.data.pickAttachmentFile(
                        onPicked = { file -> onFormChange(form.copy(initialFile = file)) }
                    )
                }
            ) {
                Text(if (form.initialFile == null) "Прикрепить файл" else "Заменить файл")
            }
            form.initialFile?.let { file ->
                Text(
                    text = file.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onFormChange(form.copy(initialFile = null)) }) {
                    Text("Убрать")
                }
            }
        }
    }
}

@Composable
fun EditDocumentDialog(
    form: EditDocumentForm,
    error: ApiError?,
    onFormChange: (EditDocumentForm) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    ErpDialog(
        title = "Реквизиты документа",
        glyph = ErpIconGlyph.Documents,
        gradient = Gradients.Primary,
        width = 600.dp,
        confirmText = "Сохранить",
        onConfirm = onSave,
        onDismiss = onDismiss
    ) {
        error?.let { ErrorMessageCard(error = it) }
        OutlinedTextField(
            value = form.name,
            onValueChange = { onFormChange(form.copy(name = it)) },
            label = { Text("Название *") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ConfidentialityDropdown(
                selected = form.confidentiality,
                onSelect = { onFormChange(form.copy(confidentiality = it)) },
                modifier = Modifier.weight(1f)
            )
            org.example.project.ui.theme.ErpDateField(
                value = form.retentionUntil,
                onValueChange = { onFormChange(form.copy(retentionUntil = it)) },
                label = "Срок хранения до",
                modifier = Modifier.weight(1f)
            )
        }
        OutlinedTextField(
            value = form.description,
            onValueChange = { onFormChange(form.copy(description = it)) },
            label = { Text("Описание") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
    }
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
    ErpDialog(
        title = "Новая версия документа",
        glyph = ErpIconGlyph.Documents,
        gradient = Gradients.Primary,
        width = 560.dp,
        confirmText = if (isUploading) "Загрузка…" else "Выбрать файл и загрузить",
        onConfirm = onPickAndUpload,
        confirmEnabled = !isUploading,
        onDismiss = onDismiss
    ) {
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
}
