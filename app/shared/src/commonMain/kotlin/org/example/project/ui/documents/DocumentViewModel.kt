package org.example.project.ui.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.project.data.ApiResult
import org.example.project.data.DocumentRepository
import org.example.project.data.ProjectRepository
import org.example.project.data.UserRepository
import org.example.project.data.pickAttachmentFile
import org.example.project.data.safeAttachmentFileName
import org.example.project.data.saveAttachmentFile
import org.example.project.dto.ApiError
import org.example.project.dto.CreateDocumentLinkRequest
import org.example.project.dto.CreateDocumentRequest
import org.example.project.dto.DocumentDto
import org.example.project.dto.DocumentLinkType
import org.example.project.dto.DocumentVersionDto
import org.example.project.dto.DocumentVersionStatus
import org.example.project.dto.UpdateDocumentRequest
import org.example.project.dto.UserDto
import org.example.project.dto.VersionTransitionRequest
import org.example.project.dto.hasPermission

class DocumentViewModel(
    private val documentRepository: DocumentRepository,
    private val projectRepository: ProjectRepository,
    private val userRepository: UserRepository,
    private val currentUser: UserDto
) : ViewModel() {

    private val _uiState = MutableStateFlow(DocumentUiState())
    val uiState: StateFlow<DocumentUiState> = _uiState.asStateFlow()

    init {
        loadDocuments()
        loadProjects()
        loadUsers()
    }

    fun hasPermission(action: String): Boolean = currentUser.hasPermission("documents", action)

    fun canManage(document: DocumentDto): Boolean =
        document.ownerId == currentUser.id || hasPermission("manage")

    fun canDownload(document: DocumentDto): Boolean =
        document.ownerId == currentUser.id ||
            hasPermission("download") ||
            hasPermission("read_any")

    fun dismissError() {
        _uiState.value = _uiState.value.copy(operationError = null)
    }

    fun showDialog(dialog: DocumentDialog) {
        val document = _uiState.value.selectedDocument
        val editForm = if (dialog == DocumentDialog.EditDocument && document != null) {
            EditDocumentForm(
                name = document.name,
                description = document.description ?: "",
                confidentiality = document.confidentiality,
                retentionUntil = document.retentionUntil ?: ""
            )
        } else {
            _uiState.value.editForm
        }
        _uiState.value = _uiState.value.copy(
            activeDialog = dialog,
            operationError = null,
            editForm = editForm
        )
    }

    fun dismissDialog() {
        _uiState.value = _uiState.value.copy(activeDialog = null, operationError = null)
    }

    fun navigateTo(page: DocumentPage) {
        _uiState.value = _uiState.value.copy(currentPage = page, operationError = null)
    }

    fun goBack() {
        when (_uiState.value.currentPage) {
            DocumentPage.LIST -> { }
            DocumentPage.DETAIL -> navigateTo(DocumentPage.LIST)
        }
    }

    fun loadDocuments() {
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = documentRepository.list()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    documents = result.data,
                    isLoading = false,
                    operationError = null
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    private fun loadProjects() {
        viewModelScope.launch {
            when (val result = projectRepository.list()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(projects = result.data)
                is ApiResult.Error -> { }
            }
        }
    }

    private fun loadUsers() {
        viewModelScope.launch {
            when (val result = userRepository.list()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(users = result.data)
                is ApiResult.Error -> { }
            }
        }
    }

    fun selectDocument(id: Long) {
        _uiState.value = _uiState.value.copy(
            selectedDocument = null,
            operationError = null,
            uploadChangeReason = "",
            linkProjectId = null
        )
        viewModelScope.launch {
            when (val result = documentRepository.get(id)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(selectedDocument = result.data)
                    navigateTo(DocumentPage.DETAIL)
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(operationError = result.error)
            }
        }
    }

    fun refreshSelectedDocument() {
        val id = _uiState.value.selectedDocument?.id ?: return
        selectDocument(id)
    }

    fun createDocument() {
        val form = _uiState.value.createForm
        if (form.name.isBlank()) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Заполните обязательное поле: название")
            )
            return
        }
        val retentionError = validateRetention(form.retentionUntil)
        if (retentionError != null) {
            _uiState.value = _uiState.value.copy(operationError = retentionError)
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            val request = CreateDocumentRequest(
                name = form.name.trim(),
                docType = form.docType,
                description = form.description.takeIf { it.isNotBlank() }?.trim(),
                ownerId = form.ownerId,
                confidentiality = form.confidentiality,
                retentionUntil = form.retentionUntil.trim().takeIf { it.isNotEmpty() }
            )
            when (val result = documentRepository.create(request)) {
                is ApiResult.Success -> {
                    val createdId = result.data.id
                    var uploadError: ApiError? = null
                    if (form.initialFile != null) {
                        when (val up = documentRepository.uploadVersion(createdId, form.initialFile, "Первая версия при создании")) {
                            is ApiResult.Error -> uploadError = up.error
                            is ApiResult.Success -> Unit
                        }
                    }
                    _uiState.value = _uiState.value.copy(
                        createForm = CreateDocumentForm(),
                        activeDialog = null,
                        isLoading = false,
                        operationError = uploadError
                    )
                    loadDocuments()
                    selectDocument(createdId)
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun updateDocument() {
        val document = _uiState.value.selectedDocument ?: return
        val form = _uiState.value.editForm
        if (form.name.isBlank()) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Название не должно быть пустым")
            )
            return
        }
        val retentionError = validateRetention(form.retentionUntil)
        if (retentionError != null) {
            _uiState.value = _uiState.value.copy(operationError = retentionError)
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            val request = UpdateDocumentRequest(
                version = document.version,
                name = form.name.trim(),
                description = form.description.takeIf { it.isNotBlank() }?.trim(),
                confidentiality = form.confidentiality,
                retentionUntil = form.retentionUntil.trim().takeIf { it.isNotEmpty() }
            )
            when (val result = documentRepository.update(document.id, request)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        selectedDocument = result.data,
                        activeDialog = null,
                        isLoading = false
                    )
                    loadDocuments()
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        operationError = result.error
                    )
                    if (result.statusCode == 409) refreshSelectedDocument()
                }
            }
        }
    }

    fun uploadNewVersion() {
        val document = _uiState.value.selectedDocument ?: return
        val reason = _uiState.value.uploadChangeReason.trim()
        if (document.versions.isNotEmpty() && reason.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError(
                    "VALIDATION_ERROR",
                    "Укажите причину изменения для новой версии"
                )
            )
            return
        }
        pickAttachmentFile(onPicked = { file ->
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isUploading = true, operationError = null)
                when (val result = documentRepository.uploadVersion(document.id, file, reason.ifEmpty { null })) {
                    is ApiResult.Success -> {
                        _uiState.value = _uiState.value.copy(
                            isUploading = false,
                            activeDialog = null,
                            uploadChangeReason = ""
                        )
                        refreshSelectedDocument()
                        loadDocuments()
                    }
                    is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                        isUploading = false,
                        operationError = result.error
                    )
                }
            }
        })
    }

    fun approveVersion(version: DocumentVersionDto) {
        if (!hasPermission("approve")) return
        if (version.status != DocumentVersionStatus.DRAFT) return
        transition(version) { documentId ->
            documentRepository.approveVersion(documentId, version.id, VersionTransitionRequest(version.version))
        }
    }

    fun activateVersion(version: DocumentVersionDto) {
        if (!hasPermission("activate")) return
        if (version.status != DocumentVersionStatus.APPROVED) return
        transition(version) { documentId ->
            documentRepository.activateVersion(documentId, version.id, VersionTransitionRequest(version.version))
        }
    }

    fun cancelVersion(version: DocumentVersionDto) {
        val document = _uiState.value.selectedDocument ?: return
        if (!canManage(document)) return
        if (version.status != DocumentVersionStatus.DRAFT) return
        transition(version) { documentId ->
            documentRepository.cancelVersion(documentId, version.id, VersionTransitionRequest(version.version))
        }
    }

    private fun transition(
        version: DocumentVersionDto,
        action: suspend (Long) -> ApiResult<DocumentVersionDto>
    ) {
        val document = _uiState.value.selectedDocument ?: return
        _uiState.value = _uiState.value.copy(busyVersionId = version.id, operationError = null)
        viewModelScope.launch {
            when (val result = action(document.id)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(busyVersionId = null)
                    refreshSelectedDocument()
                    loadDocuments()
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        busyVersionId = null,
                        operationError = result.error
                    )
                    if (result.statusCode == 409) refreshSelectedDocument()
                }
            }
        }
    }

    fun downloadVersion(version: DocumentVersionDto) {
        val document = _uiState.value.selectedDocument ?: return
        if (!canDownload(document)) return
        _uiState.value = _uiState.value.copy(busyVersionId = version.id, operationError = null)
        viewModelScope.launch {
            when (val result = documentRepository.downloadVersion(version.id)) {
                is ApiResult.Success -> {
                    saveAttachmentFile(
                        safeAttachmentFileName(version.fileName, version.id),
                        result.data.mimeType ?: version.mimeType,
                        result.data.bytes
                    )
                    _uiState.value = _uiState.value.copy(busyVersionId = null)
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    busyVersionId = null,
                    operationError = result.error
                )
            }
        }
    }

    fun previewVersion(version: DocumentVersionDto) {
        val document = _uiState.value.selectedDocument ?: return
        if (!canDownload(document)) return
        _uiState.value = _uiState.value.copy(busyVersionId = version.id, operationError = null)
        viewModelScope.launch {
            when (val result = documentRepository.downloadVersion(version.id)) {
                is ApiResult.Success -> {
                    org.example.project.data.previewAttachmentFile(
                        safeAttachmentFileName(version.fileName, version.id),
                        result.data.mimeType ?: version.mimeType,
                        result.data.bytes
                    )
                    _uiState.value = _uiState.value.copy(busyVersionId = null)
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    busyVersionId = null,
                    operationError = result.error
                )
            }
        }
    }

    fun addProjectLink() {
        val document = _uiState.value.selectedDocument ?: return
        if (!canManage(document)) return
        val projectId = _uiState.value.linkProjectId
        if (projectId == null) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Выберите проект для связи")
            )
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = documentRepository.addLink(
                document.id,
                CreateDocumentLinkRequest(DocumentLinkType.PROJECT, projectId)
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(isLoading = false, linkProjectId = null)
                    refreshSelectedDocument()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun removeLink(linkId: Long) {
        val document = _uiState.value.selectedDocument ?: return
        if (!canManage(document)) return
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = documentRepository.removeLink(document.id, linkId)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    refreshSelectedDocument()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun updateCreateForm(form: CreateDocumentForm) {
        _uiState.value = _uiState.value.copy(createForm = form)
    }

    fun updateEditForm(form: EditDocumentForm) {
        _uiState.value = _uiState.value.copy(editForm = form)
    }

    fun updateUploadChangeReason(value: String) {
        _uiState.value = _uiState.value.copy(uploadChangeReason = value)
    }

    fun updateLinkProject(projectId: Long?) {
        _uiState.value = _uiState.value.copy(linkProjectId = projectId)
    }

    private fun validateRetention(value: String): ApiError? {
        val trimmed = value.trim()
        if (trimmed.isNotEmpty() && !trimmed.matches(DATE_PATTERN)) {
            return ApiError("VALIDATION_ERROR", "Срок хранения укажите в формате ГГГГ-ММ-ДД")
        }
        return null
    }

    private companion object {
        val DATE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}")
    }
}
