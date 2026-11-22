package org.example.project.dto

import kotlinx.serialization.Serializable

@Serializable
data class DocumentTemplateDto(
    val id: Long,
    val code: String,
    val name: String,
    val docType: String,
    val confidentiality: String = "INTERNAL",
    val description: String? = null,
    val active: Boolean = true
)

@Serializable
data class CreateDocumentTemplateRequest(
    val code: String,
    val name: String,
    val docType: String,
    val confidentiality: String = "INTERNAL",
    val description: String? = null
)

@Serializable
enum class CalendarEventKind {
    TASK_DUE,
    PROJECT_DEADLINE,
    MILESTONE
}

@Serializable
data class CalendarEventDto(
    val kind: CalendarEventKind,
    val entityId: Long,
    val number: String,
    val title: String,
    val date: String,
    val status: String
)

@Serializable
data class AnalyticsSummaryDto(
    val totalTasks: Int = 0,
    val openTasks: Int = 0,
    val overdueTasks: Int = 0,
    val totalProjects: Int = 0,
    val activeProjects: Int = 0,
    val totalDemands: Int = 0,
    val pendingDemands: Int = 0,
    val totalPurchaseOrders: Int = 0,
    val activeProductionOrders: Int = 0,
    val lowStockCount: Int = 0
)
