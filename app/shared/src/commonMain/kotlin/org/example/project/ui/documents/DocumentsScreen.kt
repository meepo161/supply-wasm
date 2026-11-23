@file:OptIn(ExperimentalMaterial3Api::class)

package org.example.project.ui.documents

import org.example.project.ui.userText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.dto.DocumentDto
import org.example.project.dto.DocumentLinkDto
import org.example.project.dto.DocumentLinkType
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.GlassCard
import org.example.project.ui.theme.GradientButton
import org.example.project.ui.theme.GradientSpinner
import org.example.project.ui.theme.SoftButton
import org.example.project.dto.DocumentVersionDto
import org.example.project.dto.DocumentVersionStatus
import org.example.project.ui.demand.ProjectDropdown
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.tasks.toShortDateTime
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.Gradients
import org.example.project.ui.theme.PageHeader

@Composable
fun DocumentsScreen(
    viewModel: DocumentViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(
            title = state.currentPage.title,
            glyph = ErpIconGlyph.Documents,
            gradient = Gradients.AmberGlow,
            subtitle = "Файлы, версии и связи с объектами",
            onBack = if (state.currentPage != DocumentPage.LIST) viewModel::goBack else null
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(bottom = 20.dp)
        ) {
            when (state.currentPage) {
                DocumentPage.LIST -> DocumentListPage(state, viewModel)
                DocumentPage.DETAIL -> DocumentDetailPage(state, viewModel)
            }
        }
    }

    when (state.activeDialog) {
        DocumentDialog.CreateDocument -> CreateDocumentDialog(
            form = state.createForm,
            users = state.users,
            canAssignOwner = viewModel.hasPermission("manage"),
            error = state.operationError,
            onFormChange = viewModel::updateCreateForm,
            onCreate = viewModel::createDocument,
            onDismiss = viewModel::dismissDialog
        )
        DocumentDialog.EditDocument -> EditDocumentDialog(
            form = state.editForm,
            error = state.operationError,
            onFormChange = viewModel::updateEditForm,
            onSave = viewModel::updateDocument,
            onDismiss = viewModel::dismissDialog
        )
        DocumentDialog.UploadVersion -> UploadVersionDialog(
            changeReason = state.uploadChangeReason,
            isReplacement = state.selectedDocument?.versions?.isNotEmpty() == true,
            isUploading = state.isUploading,
            error = state.operationError,
            onReasonChange = viewModel::updateUploadChangeReason,
            onPickAndUpload = viewModel::uploadNewVersion,
            onDismiss = viewModel::dismissDialog
        )
        null -> { }
    }
}

@Composable
private fun DocumentListPage(
    state: DocumentUiState,
    viewModel: DocumentViewModel
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val extrasRepo: org.example.project.data.ErpExtrasRepository = koinInject()
            val tplScope = rememberCoroutineScope()
            var showTemplates by remember { mutableStateOf(false) }
            var templates by remember { mutableStateOf<List<org.example.project.dto.DocumentTemplateDto>>(emptyList()) }
            var tplCode by remember { mutableStateOf("") }
            var tplName by remember { mutableStateOf("") }
            var tplError by remember { mutableStateOf<String?>(null) }

            Text(
                text = "Всего документов: ${state.documents.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                GradientButton(
                    text = "Новый документ",
                    onClick = { viewModel.showDialog(DocumentDialog.CreateDocument) },
                    gradient = Gradients.Aurora,
                    compact = true
                )
                SoftButton(text = "Шаблоны", onClick = {
                    showTemplates = true
                    tplScope.launch {
                        when (val res = extrasRepo.listDocumentTemplates()) {
                            is org.example.project.data.ApiResult.Success -> templates = res.data
                            is org.example.project.data.ApiResult.Error -> tplError = res.error.userText()
                        }
                    }
                })
                SoftButton(text = "Обновить", onClick = viewModel::loadDocuments)
            }

            if (showTemplates) {
                org.example.project.ui.theme.ErpDialog(
                    title = "Шаблоны документов",
                    glyph = ErpIconGlyph.Documents,
                    gradient = Gradients.Primary,
                    width = 600.dp,
                    confirmText = "Создать шаблон",
                    onConfirm = {
                        if (tplCode.isNotBlank() && tplName.isNotBlank()) {
                            tplScope.launch {
                                when (val res = extrasRepo.createDocumentTemplate(
                                    org.example.project.dto.CreateDocumentTemplateRequest(
                                        code = tplCode.trim(),
                                        name = tplName.trim(),
                                        docType = "INSTRUCTION"
                                    )
                                )) {
                                    is org.example.project.data.ApiResult.Success -> {
                                        templates = listOf(res.data) + templates
                                        tplCode = ""
                                        tplName = ""
                                        tplError = null
                                    }
                                    is org.example.project.data.ApiResult.Error -> tplError = res.error.userText()
                                }
                            }
                        }
                    },
                    dismissText = "Закрыть",
                    onDismiss = { showTemplates = false }
                ) {
                    tplError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    templates.forEach { t ->
                        Text("${t.code} · ${t.name} (${t.docType})", style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        org.example.project.ui.theme.ErpTextField(
                            value = tplCode,
                            onValueChange = { tplCode = it },
                            label = "Код шаблона",
                            modifier = Modifier.weight(0.4f)
                        )
                        org.example.project.ui.theme.ErpTextField(
                            value = tplName,
                            onValueChange = { tplName = it },
                            label = "Название шаблона",
                            modifier = Modifier.weight(0.6f)
                        )
                    }
                }
            }
        }
        state.operationError?.let { ErrorMessageCard(error = it) }
        if (state.isLoading && state.documents.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                GradientSpinner()
            }
        } else if (state.documents.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Нет доступных документов. Создайте первый.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            DocumentsGrid(
                documents = state.documents,
                users = state.users,
                onClick = { viewModel.selectDocument(it.id) },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }
    }
}

@Composable
private fun DocumentDetailPage(
    state: DocumentUiState,
    viewModel: DocumentViewModel
) {
    val document = state.selectedDocument
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (document == null) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                GradientSpinner()
            }
            return@Column
        }
        state.operationError?.let { ErrorMessageCard(error = it) }
        DocumentHeaderCard(document, state, viewModel)
        VersionsSection(document, state, viewModel)
        LinksSection(document, state, viewModel)
    }
}

@Composable
private fun DocumentHeaderCard(
    document: DocumentDto,
    state: DocumentUiState,
    viewModel: DocumentViewModel
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = 16.dp
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "${document.number} · ${document.name}",
                style = MaterialTheme.typography.titleMedium
            )
            DetailRow("Тип", document.docType.localizedName())
            DetailRow("Статус", document.status.localizedName())
            DetailRow("Конфиденциальность", document.confidentiality.localizedName())
            DetailRow("Владелец", userName(state, document.ownerId))
            document.authorId?.let { DetailRow("Автор", userName(state, it)) }
            DetailRow("Срок хранения до", document.retentionUntil ?: "—")
            DetailRow("Создан", document.createdAt.toShortDateTime())
            DetailRow("Обновлён", document.updatedAt.toShortDateTime())
            DetailRow("Версия записи", document.version.toString())
            document.description?.takeIf { it.isNotBlank() }?.let {
                DetailRow("Описание", it)
            }
            if (viewModel.canManage(document)) {
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton(
                        text = "Изменить реквизиты",
                        onClick = { viewModel.showDialog(DocumentDialog.EditDocument) },
                        tint = Brand.Indigo
                    )
                    GradientButton(
                        text = "Загрузить новую версию",
                        onClick = { viewModel.showDialog(DocumentDialog.UploadVersion) },
                        gradient = Gradients.Ocean,
                        compact = true
                    )
                }
            }
        }
    }
}

@Composable
private fun VersionsSection(
    document: DocumentDto,
    state: DocumentUiState,
    viewModel: DocumentViewModel
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = 16.dp
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = "Версии", style = MaterialTheme.typography.titleMedium)
            if (document.versions.isEmpty()) {
                Text(
                    text = "Версий пока нет.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                document.versions.forEach { version ->
                    VersionRow(
                        version = version,
                        isCurrent = version.id == document.currentVersionId,
                        busy = state.busyVersionId == version.id,
                        state = state,
                        viewModel = viewModel,
                        canManage = viewModel.canManage(document),
                        canDownload = viewModel.canDownload(document)
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun VersionRow(
    version: DocumentVersionDto,
    isCurrent: Boolean,
    busy: Boolean,
    state: DocumentUiState,
    viewModel: DocumentViewModel,
    canManage: Boolean,
    canDownload: Boolean
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Версия ${version.versionNumber}" +
                    (if (isCurrent) " (действующая)" else "") +
                    ": ${version.status.localizedName()}",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = version.createdAt.toShortDateTime(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = version.fileName + (version.sizeBytes?.let { " (${formatSize(it)})" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        version.changeReason?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = "Причина изменения: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = "Создал: ${userName(state, version.createdBy)}" +
                (version.approvedBy?.let { " · Утвердил: ${userName(state, it)} ${version.approvedAt.toShortDateTime()}" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canDownload && version.fileId != null) {
                TextButton(onClick = { viewModel.previewVersion(version) }, enabled = !busy) {
                    Text("Просмотр")
                }
                TextButton(onClick = { viewModel.downloadVersion(version) }, enabled = !busy) {
                    Text("Скачать")
                }
            }
            if (version.status == DocumentVersionStatus.DRAFT && viewModel.hasPermission("approve")) {
                TextButton(onClick = { viewModel.approveVersion(version) }, enabled = !busy) {
                    Text("Утвердить")
                }
            }
            if (version.status == DocumentVersionStatus.APPROVED && viewModel.hasPermission("activate")) {
                TextButton(onClick = { viewModel.activateVersion(version) }, enabled = !busy) {
                    Text("Сделать действующей")
                }
            }
            if (version.status == DocumentVersionStatus.DRAFT && canManage) {
                TextButton(onClick = { viewModel.cancelVersion(version) }, enabled = !busy) {
                    Text("Отменить версию", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun LinksSection(
    document: DocumentDto,
    state: DocumentUiState,
    viewModel: DocumentViewModel
) {
    val canManage = viewModel.canManage(document)
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = 16.dp
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = "Связи", style = MaterialTheme.typography.titleMedium)
            if (document.links.isEmpty()) {
                Text(
                    text = "Связей пока нет.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                document.links.forEach { link ->
                    LinkRow(
                        link = link,
                        state = state,
                        canRemove = canManage && link.linkType == DocumentLinkType.PROJECT,
                        onRemove = { viewModel.removeLink(link.id) }
                    )
                }
            }
            if (canManage && state.projects.isNotEmpty()) {
                HorizontalDivider()
                ProjectDropdown(
                    projects = state.projects,
                    selected = state.projects.find { it.id == state.linkProjectId },
                    onSelect = { viewModel.updateLinkProject(it.id) },
                    label = "Связать с проектом",
                    modifier = Modifier.fillMaxWidth()
                )
                GradientButton(
                    text = "Добавить связь",
                    onClick = viewModel::addProjectLink,
                    enabled = state.linkProjectId != null,
                    gradient = Gradients.Ocean,
                    compact = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun LinkRow(
    link: DocumentLinkDto,
    state: DocumentUiState,
    canRemove: Boolean,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = when (link.linkType) {
                DocumentLinkType.PROJECT -> state.projects.find { it.id == link.targetId }
                    ?.let { "Проект: ${it.number} · ${it.name}" }
                    ?: "Проект: ID ${link.targetId}"
                else -> "${link.linkType.localizedName()}: ID ${link.targetId}"
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (canRemove) {
            TextButton(onClick = onRemove) {
                Text("Удалить", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun userName(state: DocumentUiState, userId: Long): String =
    state.users.find { it.id == userId }?.fullName ?: "ID $userId"

private fun formatSize(sizeBytes: Long): String = when {
    sizeBytes >= 1024 * 1024 -> "${sizeBytes / (1024 * 1024)} МБ"
    sizeBytes >= 1024 -> "${sizeBytes / 1024} КБ"
    else -> "$sizeBytes Б"
}
