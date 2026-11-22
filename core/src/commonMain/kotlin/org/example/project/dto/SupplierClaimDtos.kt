package org.example.project.dto

import kotlinx.serialization.Serializable

@Serializable
enum class SupplierClaimType {
    SHORTAGE,
    DEFECT,
    DELAY,
    PRICE_MISMATCH,
    OTHER
}

@Serializable
enum class SupplierClaimStatus {
    OPEN,
    SENT,
    IN_REVIEW,
    RESOLVED,
    CLOSED,
    CANCELLED
}

@Serializable
data class SupplierClaimDto(
    val id: Long,
    val number: String,
    val supplierId: Long,
    val supplierName: String = "",
    val purchaseOrderId: Long? = null,
    val purchaseOrderNumber: String? = null,
    val purchaseDeliveryId: Long? = null,
    val receiptId: Long? = null,
    val claimType: SupplierClaimType,
    val status: SupplierClaimStatus,
    val description: String,
    val claimAmount: String = "0",
    val currency: String = "RUB",
    val resolution: String? = null,
    val createdBy: Long,
    val createdAt: String? = null,
    val resolvedBy: Long? = null,
    val resolvedAt: String? = null,
    val version: Long = 0
)

@Serializable
data class CreateSupplierClaimRequest(
    val supplierId: Long,
    val purchaseOrderId: Long? = null,
    val purchaseDeliveryId: Long? = null,
    val receiptId: Long? = null,
    val claimType: SupplierClaimType,
    val description: String,
    val claimAmount: String = "0",
    val currency: String = "RUB"
)

@Serializable
data class TransitionSupplierClaimRequest(
    val targetStatus: SupplierClaimStatus,
    val resolution: String? = null,
    val version: Long
)
