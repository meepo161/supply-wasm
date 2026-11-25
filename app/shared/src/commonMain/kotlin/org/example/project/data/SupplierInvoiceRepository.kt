package org.example.project.data

import org.example.project.dto.CancelSupplierInvoiceRequest
import org.example.project.dto.ConfirmSupplierInvoiceCheckRequest
import org.example.project.dto.CreateReceiptFromInvoiceRequest
import org.example.project.dto.ReceiptDto
import org.example.project.dto.SupplierInvoiceActionRequest
import org.example.project.dto.SupplierInvoiceAutofillDto
import org.example.project.dto.SupplierInvoiceCheckDto
import org.example.project.dto.SupplierInvoiceDto
import org.example.project.dto.SupplierInvoiceHistoryDto
import org.example.project.dto.SupplierInvoiceInput
import org.example.project.dto.SupplierInvoicePreviewDto
import org.example.project.dto.SupplierInvoiceStatus
import org.example.project.dto.UpdateSupplierInvoiceRequest

/** Счёт поставщика (D-61, D-62): ведёт снабжение, склад только принимает. */
interface SupplierInvoiceRepository {
    suspend fun list(
        status: SupplierInvoiceStatus? = null,
        supplierId: Long? = null,
        purchaseOrderId: Long? = null
    ): ApiResult<List<SupplierInvoiceDto>>
    suspend fun get(id: Long): ApiResult<SupplierInvoiceDto>
    suspend fun create(input: SupplierInvoiceInput): ApiResult<SupplierInvoiceDto>
    suspend fun update(id: Long, request: UpdateSupplierInvoiceRequest): ApiResult<SupplierInvoiceDto>
    suspend fun check(id: Long): ApiResult<SupplierInvoiceCheckDto>
    suspend fun confirmCheck(id: Long, request: ConfirmSupplierInvoiceCheckRequest): ApiResult<SupplierInvoiceDto>
    suspend fun reopen(id: Long, request: SupplierInvoiceActionRequest): ApiResult<SupplierInvoiceDto>
    suspend fun cancel(id: Long, request: CancelSupplierInvoiceRequest): ApiResult<SupplierInvoiceDto>
    suspend fun createReceipt(id: Long, request: CreateReceiptFromInvoiceRequest): ApiResult<ReceiptDto>
    suspend fun history(id: Long): ApiResult<List<SupplierInvoiceHistoryDto>>
    suspend fun downloadFile(id: Long): ApiResult<DownloadedAttachment>
    suspend fun parseDocumentPdf(documentId: Long): ApiResult<SupplierInvoiceAutofillDto>
    suspend fun parsePdfBytes(fileName: String, bytes: ByteArray): ApiResult<SupplierInvoiceAutofillDto>
    suspend fun previewHtml(id: Long): ApiResult<SupplierInvoicePreviewDto>
}
