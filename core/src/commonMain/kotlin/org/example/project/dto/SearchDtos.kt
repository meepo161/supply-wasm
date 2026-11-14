package org.example.project.dto

import kotlinx.serialization.Serializable

@Serializable
enum class SearchResultType {
    PROJECT,
    TASK,
    USER
}

@Serializable
data class SearchResultItemDto(
    val type: SearchResultType,
    val id: Long,
    val number: String? = null,
    val title: String,
    val subtitle: String? = null,
    val status: String? = null
)

@Serializable
data class SearchGroupDto(
    val type: SearchResultType,
    val items: List<SearchResultItemDto>,
    val hasMore: Boolean = false
)

@Serializable
data class SearchResponse(
    val query: String,
    val limit: Int,
    val groups: List<SearchGroupDto>
)
