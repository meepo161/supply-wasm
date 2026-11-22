package org.example.project.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.project.data.ApiResult
import org.example.project.data.SearchRepository
import org.example.project.dto.ApiError
import org.example.project.dto.SearchGroupDto
import org.example.project.dto.SearchResultType

/**
 * Глобальный поиск (ТЗ §4.3, M.9). Соблюдает правило: результаты
 * ограничены правами сервера, нереализованные типы не показываются.
 */
class SearchViewModel(
    private val searchRepository: SearchRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    /** Минимальная длина запроса: короткие строки дают шум и лишнюю нагрузку. */
    val minQueryLength: Int = 2

    fun updateQuery(value: String) {
        _uiState.value = _uiState.value.copy(
            query = value,
            error = null,
            highlightedIndex = 0
        )
        searchJob?.cancel()
        val trimmed = value.trim()
        if (trimmed.length < minQueryLength) {
            _uiState.value = _uiState.value.copy(
                groups = emptyList(),
                isLoading = false,
                hasSearched = false,
                highlightedIndex = 0
            )
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true)
        searchJob = viewModelScope.launch {
            delay(debounceMillis)
            when (val result = searchRepository.search(trimmed)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    groups = result.data.groups.filter { it.items.isNotEmpty() },
                    isLoading = false,
                    error = null,
                    hasSearched = true,
                    highlightedIndex = 0
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = result.error,
                    hasSearched = true
                )
            }
        }
    }

    fun submitImmediately() {
        searchJob?.cancel()
        val trimmed = _uiState.value.query.trim()
        if (trimmed.length < minQueryLength) return
        _uiState.value = _uiState.value.copy(isLoading = true)
        searchJob = viewModelScope.launch {
            when (val result = searchRepository.search(trimmed)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    groups = result.data.groups.filter { it.items.isNotEmpty() },
                    isLoading = false,
                    error = null,
                    hasSearched = true,
                    highlightedIndex = 0
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = result.error,
                    hasSearched = true
                )
            }
        }
    }

    fun clear() {
        searchJob?.cancel()
        _uiState.value = SearchUiState()
    }

    /** Плоский список результатов в порядке отображения — для клавиатурной навигации. */
    fun flatResults(): List<SearchResultRef> =
        _uiState.value.groups.flatMap { group ->
            group.items.map { item ->
                SearchResultRef(
                    type = item.type,
                    id = item.id,
                    title = item.title,
                    number = item.number,
                    parentId = item.parentId
                )
            }
        }

    fun moveHighlight(delta: Int) {
        val count = flatResults().size
        if (count == 0) return
        val next = (_uiState.value.highlightedIndex + delta).coerceIn(0, count - 1)
        _uiState.value = _uiState.value.copy(highlightedIndex = next)
    }

    fun setHighlighted(index: Int) {
        _uiState.value = _uiState.value.copy(highlightedIndex = index)
    }

    fun highlighted(): SearchResultRef? = flatResults().getOrNull(_uiState.value.highlightedIndex)

    fun totalCount(): Int = flatResults().size

    private companion object {
        const val debounceMillis = 250L
    }
}

data class SearchUiState(
    val query: String = "",
    val groups: List<SearchGroupDto> = emptyList(),
    val isLoading: Boolean = false,
    val hasSearched: Boolean = false,
    val error: ApiError? = null,
    val highlightedIndex: Int = 0
) {
    val isEmptyResult: Boolean
        get() = hasSearched && groups.isEmpty() && !isLoading && error == null
}

/** Минимальная ссылка на результат поиска без зависимости от DTO в навигации. */
data class SearchResultRef(
    val type: SearchResultType,
    val id: Long,
    val title: String,
    val number: String?,
    val parentId: Long? = null
)
