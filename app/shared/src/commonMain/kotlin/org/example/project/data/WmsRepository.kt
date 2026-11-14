package org.example.project.data

import org.example.project.dto.CreateNomenclatureRequest
import org.example.project.dto.CreateReceiptRequest
import org.example.project.dto.CreateReservationRequest
import org.example.project.dto.CreateStorageLocationRequest
import org.example.project.dto.CreateWarehouseRequest
import org.example.project.dto.NomenclatureDto
import org.example.project.dto.PostReceiptRequest
import org.example.project.dto.ReceiptDto
import org.example.project.dto.ReleaseReservationRequest
import org.example.project.dto.ReservationDto
import org.example.project.dto.ReservationStatus
import org.example.project.dto.StockBalanceDto
import org.example.project.dto.StorageLocationDto
import org.example.project.dto.WarehouseDto

interface WmsRepository {
    suspend fun listWarehouses(): ApiResult<List<WarehouseDto>>
    suspend fun createWarehouse(request: CreateWarehouseRequest): ApiResult<WarehouseDto>

    suspend fun listLocations(warehouseId: Long): ApiResult<List<StorageLocationDto>>
    suspend fun createLocation(request: CreateStorageLocationRequest): ApiResult<StorageLocationDto>

    suspend fun listNomenclature(): ApiResult<List<NomenclatureDto>>
    suspend fun createNomenclature(request: CreateNomenclatureRequest): ApiResult<NomenclatureDto>
    suspend fun archiveNomenclature(id: Long): ApiResult<NomenclatureDto>

    suspend fun getStock(warehouseId: Long?, nomenclatureId: Long?): ApiResult<List<StockBalanceDto>>

    suspend fun createReceipt(request: CreateReceiptRequest): ApiResult<ReceiptDto>
    suspend fun getReceipt(id: Long): ApiResult<ReceiptDto>
    suspend fun postReceipt(id: Long, request: PostReceiptRequest): ApiResult<ReceiptDto>

    suspend fun createReservation(request: CreateReservationRequest): ApiResult<ReservationDto>
    suspend fun listReservations(
        warehouseId: Long?,
        nomenclatureId: Long?,
        status: ReservationStatus?
    ): ApiResult<List<ReservationDto>>

    suspend fun getReservation(id: Long): ApiResult<ReservationDto>
    suspend fun releaseReservation(id: Long, request: ReleaseReservationRequest): ApiResult<ReservationDto>
}
