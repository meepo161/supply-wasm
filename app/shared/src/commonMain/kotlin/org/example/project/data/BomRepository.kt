package org.example.project.data

import org.example.project.dto.ApproveBomRevisionRequest
import org.example.project.dto.BomExplodeReportDto
import org.example.project.dto.BomExplodeRequest
import org.example.project.dto.BomLineDto
import org.example.project.dto.BomRevisionDto
import org.example.project.dto.BomRevisionSummaryDto
import org.example.project.dto.CopyBomRevisionRequest
import org.example.project.dto.CreateBomLineRequest
import org.example.project.dto.CreateBomRevisionRequest
import org.example.project.dto.CreateProductModelRequest
import org.example.project.dto.ProductModelDto
import org.example.project.dto.TransitionBomRevisionRequest
import org.example.project.dto.UpdateBomLineRequest
import org.example.project.dto.UpdateProductModelRequest

interface BomRepository {
    suspend fun listModels(): ApiResult<List<ProductModelDto>>
    suspend fun createModel(request: CreateProductModelRequest): ApiResult<ProductModelDto>
    suspend fun getModel(id: Long): ApiResult<ProductModelDto>
    suspend fun updateModel(id: Long, request: UpdateProductModelRequest): ApiResult<ProductModelDto>

    suspend fun listRevisions(modelId: Long): ApiResult<List<BomRevisionSummaryDto>>
    suspend fun createRevision(modelId: Long, request: CreateBomRevisionRequest): ApiResult<BomRevisionDto>
    suspend fun getRevision(id: Long): ApiResult<BomRevisionDto>
    suspend fun copyRevision(id: Long, request: CopyBomRevisionRequest): ApiResult<BomRevisionDto>
    suspend fun approveRevision(id: Long, request: ApproveBomRevisionRequest): ApiResult<BomRevisionDto>
    suspend fun activateRevision(id: Long, request: TransitionBomRevisionRequest): ApiResult<BomRevisionDto>
    suspend fun replaceRevision(id: Long, request: TransitionBomRevisionRequest): ApiResult<BomRevisionDto>
    suspend fun cancelRevision(id: Long, request: TransitionBomRevisionRequest): ApiResult<BomRevisionDto>

    suspend fun listLines(revisionId: Long): ApiResult<List<BomLineDto>>
    suspend fun createLine(revisionId: Long, request: CreateBomLineRequest): ApiResult<BomLineDto>
    suspend fun updateLine(revisionId: Long, lineId: Long, request: UpdateBomLineRequest): ApiResult<BomLineDto>
    suspend fun deleteLine(revisionId: Long, lineId: Long): ApiResult<Unit>

    suspend fun explodeRevision(id: Long, request: BomExplodeRequest): ApiResult<BomExplodeReportDto>
}
