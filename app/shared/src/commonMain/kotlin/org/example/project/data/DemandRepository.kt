package org.example.project.data

import org.example.project.dto.ApproveDemandRequest
import org.example.project.dto.CancelDemandRequest
import org.example.project.dto.CreateDemandFromBomRequest
import org.example.project.dto.CreateDemandRequest
import org.example.project.dto.DemandCoverageDto
import org.example.project.dto.DemandDetailDto
import org.example.project.dto.DemandHeaderDto
import org.example.project.dto.SubmitDemandRequest

interface DemandRepository {
    suspend fun listDemands(): ApiResult<List<DemandHeaderDto>>
    suspend fun createDemand(request: CreateDemandRequest): ApiResult<DemandDetailDto>
    suspend fun createDemandFromBom(request: CreateDemandFromBomRequest): ApiResult<DemandDetailDto>
    suspend fun getDemand(id: Long): ApiResult<DemandDetailDto>
    suspend fun submitDemand(id: Long, request: SubmitDemandRequest): ApiResult<DemandDetailDto>
    suspend fun approveDemand(id: Long, request: ApproveDemandRequest): ApiResult<DemandDetailDto>
    suspend fun cancelDemand(id: Long, request: CancelDemandRequest): ApiResult<DemandDetailDto>
    suspend fun coveragePreview(id: Long): ApiResult<DemandCoverageDto>
}
