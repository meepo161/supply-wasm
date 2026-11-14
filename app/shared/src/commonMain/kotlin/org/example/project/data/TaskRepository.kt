package org.example.project.data

import org.example.project.dto.AssignTaskRequest
import org.example.project.dto.BlockTaskRequest
import org.example.project.dto.CancelTaskRequest
import org.example.project.dto.CreateTaskRequest
import org.example.project.dto.ReopenTaskRequest
import org.example.project.dto.ReviewTaskRequest
import org.example.project.dto.SubmitResultRequest
import org.example.project.dto.TaskCommandRequest
import org.example.project.dto.TaskDto

interface TaskRepository {
    suspend fun list(): ApiResult<List<TaskDto>>
    suspend fun get(id: Long): ApiResult<TaskDto>
    suspend fun create(request: CreateTaskRequest): ApiResult<TaskDto>
    suspend fun assign(id: Long, request: AssignTaskRequest): ApiResult<TaskDto>
    suspend fun accept(id: Long, request: TaskCommandRequest): ApiResult<TaskDto>
    suspend fun submitResult(id: Long, request: SubmitResultRequest): ApiResult<TaskDto>
    suspend fun sendToReview(id: Long, request: TaskCommandRequest): ApiResult<TaskDto>
    suspend fun review(id: Long, request: ReviewTaskRequest): ApiResult<TaskDto>
    suspend fun close(id: Long, request: TaskCommandRequest): ApiResult<TaskDto>
    suspend fun block(id: Long, request: BlockTaskRequest): ApiResult<TaskDto>
    suspend fun unblock(id: Long, request: TaskCommandRequest): ApiResult<TaskDto>
    suspend fun reopen(id: Long, request: ReopenTaskRequest): ApiResult<TaskDto>
    suspend fun cancel(id: Long, request: CancelTaskRequest): ApiResult<TaskDto>
}
