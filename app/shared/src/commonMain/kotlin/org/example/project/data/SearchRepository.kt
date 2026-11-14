package org.example.project.data

import org.example.project.dto.SearchResponse

interface SearchRepository {
    suspend fun search(query: String, limit: Int = 5): ApiResult<SearchResponse>
}
