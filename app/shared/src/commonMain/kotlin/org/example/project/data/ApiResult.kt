package org.example.project.data

import org.example.project.dto.ApiError

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val error: ApiError, val statusCode: Int? = null) : ApiResult<Nothing>()
}
