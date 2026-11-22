package org.example.project.data

import org.example.project.dto.UpdateOwnProfileRequest
import org.example.project.dto.UserDto
import org.example.project.dto.UserOptionDto

interface UserRepository {
    suspend fun list(): ApiResult<List<UserDto>>
    suspend fun listOptions(): ApiResult<List<UserOptionDto>>
    suspend fun updateOwnProfile(request: UpdateOwnProfileRequest): ApiResult<UserDto> =
        ApiResult.Error(org.example.project.dto.ApiError("NOT_IMPLEMENTED", "Not implemented"))
}
