package org.example.project.dto

import kotlinx.serialization.Serializable

@Serializable
data class DepartmentDto(
    val id: Long,
    val code: String,
    val name: String,
    val parentId: Long? = null,
    val headId: Long? = null
)

@Serializable
data class CreateDepartmentRequest(
    val code: String,
    val name: String,
    val parentId: Long? = null,
    val headId: Long? = null
)

@Serializable
data class UpdateDepartmentRequest(
    val name: String? = null,
    val parentId: Long? = null,
    val headId: Long? = null
)
