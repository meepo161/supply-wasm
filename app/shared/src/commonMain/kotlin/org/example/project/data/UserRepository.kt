package org.example.project.data

import org.example.project.dto.UserDto

interface UserRepository {
    suspend fun list(): ApiResult<List<UserDto>>
}
