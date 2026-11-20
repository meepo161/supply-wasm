package org.example.project.ui.attachments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.project.data.ApiResult
import org.example.project.data.AttachmentRepository
import org.example.project.data.pickAttachmentFile
import org.example.project.data.safeAttachmentFileName
import org.example.project.data.saveAttachmentFile
import org.example.project.dto.ApiError
import org.example.project.dto.AttachmentDto
import org.example.project.dto.AttachmentEntityType

class AttachmentsViewModel(
    private val repository: AttachmentRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AttachmentsUiState())
    val uiState: StateFlow<AttachmentsUiState> = _uiState.asStateFlow()

    private var entityType: AttachmentEntityType? = null
    private var entityId: Long = 0

    fun load(entityType: AttachmentEntityType, entityId: Long) {
        this.entityType = entityType
        this.entityId = entityId
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            when (val result = repository.list(entityType, entityId)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    items = result.data,
                    isLoading = false,
                    error = null
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = result.error,
                    readDenied = result.statusCode == 403
                )
            }
        }
    }

    fun reload() {
        entityType?.let { load(it, entityId) }
    }

    fun upload() {
        val type = entityType ?: return
        val id = entityId
        pickAttachmentFile(onPicked = { file ->
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isUploading = true, error = null)
                when (val result = repository.upload(type, id, file)) {
                    is ApiResult.Success -> {
                        _uiState.value = _uiState.value.copy(isUploading = false)
                        reload()
                    }
                    is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                        isUploading = false,
                        error = result.error,
                        writeDenied = _uiState.value.writeDenied || result.statusCode == 403
                    )
                }
            }
        })
    }

    fun download(attachment: AttachmentDto) {
        _uiState.value = _uiState.value.copy(busyId = attachment.id, error = null)
        viewModelScope.launch {
            when (val result = repository.download(attachment.id)) {
                is ApiResult.Success -> {
                    saveAttachmentFile(
                        safeAttachmentFileName(attachment.fileName, attachment.id),
                        result.data.mimeType ?: attachment.mimeType,
                        result.data.bytes
                    )
                    _uiState.value = _uiState.value.copy(busyId = null)
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    busyId = null,
                    error = result.error
                )
            }
        }
    }

    fun delete(attachment: AttachmentDto) {
        _uiState.value = _uiState.value.copy(busyId = attachment.id, error = null)
        viewModelScope.launch {
            when (val result = repository.delete(attachment.id)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    busyId = null,
                    items = _uiState.value.items.filter { it.id != attachment.id }
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    busyId = null,
                    error = result.error,
                    writeDenied = _uiState.value.writeDenied || result.statusCode == 403
                )
            }
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}

data class AttachmentsUiState(
    val items: List<AttachmentDto> = emptyList(),
    val isLoading: Boolean = false,
    val isUploading: Boolean = false,
    val busyId: Long? = null,
    val error: ApiError? = null,
    val readDenied: Boolean = false,
    val writeDenied: Boolean = false
)
