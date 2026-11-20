package org.example.project.data

import org.example.project.dto.UserDto
import org.example.project.dto.UserOptionDto

interface UserRepository {
    suspend fun list(): ApiResult<List<UserDto>>
    suspend fun listOptions(): ApiResult<List<UserOptionDto>>
}
