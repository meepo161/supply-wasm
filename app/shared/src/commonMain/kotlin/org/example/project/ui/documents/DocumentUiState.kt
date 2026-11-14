package org.example.project.ui.documents

import org.example.project.dto.ApiError
import org.example.project.dto.DocumentConfidentiality
import org.example.project.dto.DocumentDto
import org.example.project.dto.DocumentType
import org.example.project.dto.ProjectDto
import org.example.project.dto.UserDto

data class DocumentUiState(
    val documents: List<DocumentDto> = emptyList(),
    val selectedDocument: DocumentDto? = null,
    val projects: List<ProjectDto> = emptyList(),
    val users: List<UserDto> = emptyList(),
    val currentPage: DocumentPage = DocumentPage.LIST,
    val isLoading: Boolean = false,
    val isUploading: Boolean = false,
    val busyVersionId: Long? = null,
    val operationError: ApiError? = null,
    val activeDialog: DocumentDialog? = null,
    val createForm: CreateDocumentForm = CreateDocumentForm(),
    val editForm: EditDocumentForm = EditDocumentForm(),
    val uploadChangeReason: String = "",
    val linkProjectId: Long? = null
)

enum class DocumentPage(val title: String) {
    LIST("Документы"),
    DETAIL("Карточка документа")
}

sealed class DocumentDialog {
    data object CreateDocument : DocumentDialog()
    data object EditDocument : DocumentDialog()
    data object UploadVersion : DocumentDialog()
}

data class CreateDocumentForm(
    val name: String = "",
    val docType: DocumentType = DocumentType.OTHER,
    val description: String = "",
    val ownerId: Long? = null,
    val confidentiality: DocumentConfidentiality = DocumentConfidentiality.INTERNAL,
    val retentionUntil: String = ""
)

data class EditDocumentForm(
    val name: String = "",
    val description: String = "",
    val confidentiality: DocumentConfidentiality = DocumentConfidentiality.INTERNAL,
    val retentionUntil: String = ""
)
