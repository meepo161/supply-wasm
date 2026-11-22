package org.example.project.service

import org.example.project.dto.*
import org.example.project.exception.ConflictException
import org.example.project.exception.ValidationException
import org.example.project.generated.jooq.tables.Demands.DEMANDS
import org.example.project.generated.jooq.tables.DocumentTemplates.DOCUMENT_TEMPLATES
import org.example.project.generated.jooq.tables.Nomenclature.NOMENCLATURE
import org.example.project.generated.jooq.tables.ProductionOrders.PRODUCTION_ORDERS
import org.example.project.generated.jooq.tables.ProjectMilestones.PROJECT_MILESTONES
import org.example.project.generated.jooq.tables.Projects.PROJECTS
import org.example.project.generated.jooq.tables.PurchaseOrders.PURCHASE_ORDERS
import org.example.project.generated.jooq.tables.StockBalance.STOCK_BALANCE
import org.example.project.generated.jooq.tables.Tasks.TASKS
import org.example.project.model.CurrentUser
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * Календарь сроков (CAL-01), аналитика по разделам (ANA-01..05),
 * экспорт CSV (ANA-06) и шаблоны документов (DMS-08).
 */
class AnalyticsService(
    private val auditService: AuditService,
    private val dsl: DSLContext
) {

    fun summary(currentUser: CurrentUser): AnalyticsSummaryDto {
        val now = OffsetDateTime.now()
        val canTasks = currentUser.hasPermission("tasks", "read_any")
        val canProjects = currentUser.hasPermission("projects", "read_any")
        val canDemands = currentUser.hasPermission("demands", "read_any")
        val canSuppliers = currentUser.hasPermission("suppliers", "read_any")
        val canProd = currentUser.hasPermission("production", "read")
        val canWms = currentUser.hasPermission("wms", "read")

        val totalTasks = if (canTasks) dsl.fetchCount(TASKS) else 0
        val openTasks = if (canTasks) {
            dsl.fetchCount(dsl.selectFrom(TASKS).where(TASKS.STATUS.notIn("CLOSED", "CANCELLED")))
        } else 0
        val overdueTasks = if (canTasks) {
            dsl.fetchCount(
                dsl.selectFrom(TASKS)
                    .where(TASKS.STATUS.notIn("CLOSED", "CANCELLED"))
                    .and(TASKS.DUE_DATE.isNotNull)
                    .and(TASKS.DUE_DATE.lt(now))
            )
        } else 0
        val totalProjects = if (canProjects) dsl.fetchCount(PROJECTS) else 0
        val activeProjects = if (canProjects) {
            dsl.fetchCount(dsl.selectFrom(PROJECTS).where(PROJECTS.STATUS.eq("IN_PROGRESS")))
        } else 0
        val totalDemands = if (canDemands) dsl.fetchCount(DEMANDS) else 0
        val pendingDemands = if (canDemands) {
            dsl.fetchCount(dsl.selectFrom(DEMANDS).where(DEMANDS.STATUS.`in`("DRAFT", "PENDING_APPROVAL", "IN_PROCUREMENT")))
        } else 0
        val totalPos = if (canSuppliers) dsl.fetchCount(PURCHASE_ORDERS) else 0
        val activeProd = if (canProd) {
            dsl.fetchCount(dsl.selectFrom(PRODUCTION_ORDERS).where(PRODUCTION_ORDERS.STATUS.`in`("PLANNED", "IN_PROGRESS", "ON_HOLD")))
        } else 0
        val lowStock = if (canWms) {
            val stockMap = dsl.select(STOCK_BALANCE.NOMENCLATURE_ID, DSL.sum(STOCK_BALANCE.PHYSICAL_QTY.minus(STOCK_BALANCE.RESERVED_QTY)))
                .from(STOCK_BALANCE).groupBy(STOCK_BALANCE.NOMENCLATURE_ID).fetch()
                .associate { it.get(STOCK_BALANCE.NOMENCLATURE_ID)!! to (it.get(1, BigDecimal::class.java) ?: BigDecimal.ZERO) }
            dsl.selectFrom(NOMENCLATURE).where(NOMENCLATURE.ARCHIVED.eq(false)).and(NOMENCLATURE.MIN_STOCK.gt(BigDecimal.ZERO)).fetch()
                .count { (stockMap[it.id] ?: BigDecimal.ZERO) < (it.minStock ?: BigDecimal.ZERO) }
        } else 0

        return AnalyticsSummaryDto(
            totalTasks = totalTasks,
            openTasks = openTasks,
            overdueTasks = overdueTasks,
            totalProjects = totalProjects,
            activeProjects = activeProjects,
            totalDemands = totalDemands,
            pendingDemands = pendingDemands,
            totalPurchaseOrders = totalPos,
            activeProductionOrders = activeProd,
            lowStockCount = lowStock
        )
    }

    fun calendar(currentUser: CurrentUser): List<CalendarEventDto> {
        val events = mutableListOf<CalendarEventDto>()
        val tasksQuery = dsl.select(TASKS.ID, TASKS.NUMBER, TASKS.TITLE, TASKS.DUE_DATE, TASKS.STATUS)
            .from(TASKS)
            .where(TASKS.DUE_DATE.isNotNull)
            .and(
                if (currentUser.hasPermission("tasks", "read_any")) DSL.trueCondition()
                else TASKS.INITIATOR_ID.eq(currentUser.userId)
                    .or(TASKS.ASSIGNEE_ID.eq(currentUser.userId))
                    .or(TASKS.REVIEWER_ID.eq(currentUser.userId))
            )
            .orderBy(TASKS.DUE_DATE.asc())
            .limit(200)
            .fetch()
        for (r in tasksQuery) {
            events += CalendarEventDto(
                kind = CalendarEventKind.TASK_DUE,
                entityId = r.get(TASKS.ID)!!,
                number = r.get(TASKS.NUMBER)!!,
                title = r.get(TASKS.TITLE)!!,
                date = r.get(TASKS.DUE_DATE)!!.toString(),
                status = r.get(TASKS.STATUS)!!
            )
        }

        if (currentUser.hasPermission("projects", "read_any")) {
            val projQuery = dsl.select(PROJECTS.ID, PROJECTS.NUMBER, PROJECTS.NAME, PROJECTS.PLANNED_END_AT, PROJECTS.STATUS)
                .from(PROJECTS)
                .where(PROJECTS.PLANNED_END_AT.isNotNull)
                .orderBy(PROJECTS.PLANNED_END_AT.asc())
                .limit(100)
                .fetch()
            for (r in projQuery) {
                events += CalendarEventDto(
                    kind = CalendarEventKind.PROJECT_DEADLINE,
                    entityId = r.get(PROJECTS.ID)!!,
                    number = r.get(PROJECTS.NUMBER)!!,
                    title = r.get(PROJECTS.NAME)!!,
                    date = r.get(PROJECTS.PLANNED_END_AT)!!.toString(),
                    status = r.get(PROJECTS.STATUS)!!
                )
            }
            val msQuery = dsl.select(PROJECT_MILESTONES.ID, PROJECTS.NUMBER, PROJECT_MILESTONES.NAME, PROJECT_MILESTONES.PLANNED_AT, PROJECT_MILESTONES.STATUS)
                .from(PROJECT_MILESTONES)
                .join(PROJECTS).on(PROJECTS.ID.eq(PROJECT_MILESTONES.PROJECT_ID))
                .where(PROJECT_MILESTONES.PLANNED_AT.isNotNull)
                .orderBy(PROJECT_MILESTONES.PLANNED_AT.asc())
                .limit(100)
                .fetch()
            for (r in msQuery) {
                events += CalendarEventDto(
                    kind = CalendarEventKind.MILESTONE,
                    entityId = r.get(PROJECT_MILESTONES.ID)!!,
                    number = r.get(PROJECTS.NUMBER)!!,
                    title = r.get(PROJECT_MILESTONES.NAME)!!,
                    date = r.get(PROJECT_MILESTONES.PLANNED_AT)!!.toString(),
                    status = r.get(PROJECT_MILESTONES.STATUS)!!
                )
            }
        }
        return events.sortedBy { it.date }
    }

    fun exportCsv(domain: String, currentUser: CurrentUser): String {
        val sb = StringBuilder()
        when (domain.lowercase()) {
            "tasks" -> {
                currentUser.requirePermission("tasks", "read_any")
                sb.appendLine("id,number,area,status,priority,title,due_date")
                dsl.select(TASKS.ID, TASKS.NUMBER, TASKS.AREA, TASKS.STATUS, TASKS.PRIORITY, TASKS.TITLE, TASKS.DUE_DATE)
                    .from(TASKS).orderBy(TASKS.ID.desc()).limit(1000).fetch().forEach { r ->
                        sb.appendLine("${r.get(TASKS.ID)},${csv(r.get(TASKS.NUMBER))},${r.get(TASKS.AREA)},${r.get(TASKS.STATUS)},${r.get(TASKS.PRIORITY)},${csv(r.get(TASKS.TITLE))},${r.get(TASKS.DUE_DATE) ?: ""}")
                    }
            }
            "projects" -> {
                currentUser.requirePermission("projects", "read_any")
                sb.appendLine("id,number,status,type,name,planned_start,planned_end")
                dsl.select(PROJECTS.ID, PROJECTS.NUMBER, PROJECTS.STATUS, PROJECTS.PROJECT_TYPE, PROJECTS.NAME, PROJECTS.PLANNED_START_AT, PROJECTS.PLANNED_END_AT)
                    .from(PROJECTS).orderBy(PROJECTS.ID.desc()).limit(1000).fetch().forEach { r ->
                        sb.appendLine("${r.get(PROJECTS.ID)},${csv(r.get(PROJECTS.NUMBER))},${r.get(PROJECTS.STATUS)},${r.get(PROJECTS.PROJECT_TYPE)},${csv(r.get(PROJECTS.NAME))},${r.get(PROJECTS.PLANNED_START_AT) ?: ""},${r.get(PROJECTS.PLANNED_END_AT) ?: ""}")
                    }
            }
            "stock" -> {
                currentUser.requirePermission("wms", "read")
                sb.appendLine("warehouse_id,location_id,nomenclature_id,code,name,physical_qty,reserved_qty")
                dsl.select(STOCK_BALANCE.WAREHOUSE_ID, STOCK_BALANCE.LOCATION_ID, STOCK_BALANCE.NOMENCLATURE_ID, NOMENCLATURE.CODE, NOMENCLATURE.NAME, STOCK_BALANCE.PHYSICAL_QTY, STOCK_BALANCE.RESERVED_QTY)
                    .from(STOCK_BALANCE).join(NOMENCLATURE).on(NOMENCLATURE.ID.eq(STOCK_BALANCE.NOMENCLATURE_ID))
                    .limit(2000).fetch().forEach { r ->
                        sb.appendLine("${r.get(STOCK_BALANCE.WAREHOUSE_ID)},${r.get(STOCK_BALANCE.LOCATION_ID)},${r.get(STOCK_BALANCE.NOMENCLATURE_ID)},${csv(r.get(NOMENCLATURE.CODE))},${csv(r.get(NOMENCLATURE.NAME))},${r.get(STOCK_BALANCE.PHYSICAL_QTY)},${r.get(STOCK_BALANCE.RESERVED_QTY)}")
                    }
            }
            else -> throw ValidationException("Unsupported export domain: $domain")
        }
        auditService.log(currentUser.userId, "EXPORT_CSV", "ANALYTICS", 0, mapOf("domain" to domain))
        return sb.toString()
    }

    fun listDocumentTemplates(currentUser: CurrentUser): List<DocumentTemplateDto> {
        currentUser.requirePermission("documents", "read")
        return dsl.selectFrom(DOCUMENT_TEMPLATES)
            .where(DOCUMENT_TEMPLATES.ACTIVE.eq(true))
            .orderBy(DOCUMENT_TEMPLATES.CODE.asc())
            .fetch { r ->
                DocumentTemplateDto(
                    id = r.id,
                    code = r.code,
                    name = r.name,
                    docType = r.docType,
                    confidentiality = r.confidentiality,
                    description = r.description,
                    active = r.active
                )
            }
    }

    fun createDocumentTemplate(request: CreateDocumentTemplateRequest, currentUser: CurrentUser): DocumentTemplateDto {
        currentUser.requirePermission("documents", "create")
        if (request.code.isBlank() || request.name.isBlank() || request.docType.isBlank()) {
            throw ValidationException("code, name and docType are required")
        }
        val code = request.code.trim()
        if (dsl.fetchExists(dsl.selectOne().from(DOCUMENT_TEMPLATES).where(DOCUMENT_TEMPLATES.CODE.eq(code)))) {
            throw ConflictException("Document template code already exists: $code")
        }
        val rec = dsl.insertInto(DOCUMENT_TEMPLATES)
            .set(DOCUMENT_TEMPLATES.CODE, code)
            .set(DOCUMENT_TEMPLATES.NAME, request.name.trim())
            .set(DOCUMENT_TEMPLATES.DOC_TYPE, request.docType.trim())
            .set(DOCUMENT_TEMPLATES.CONFIDENTIALITY, request.confidentiality.trim().ifEmpty { "INTERNAL" })
            .set(DOCUMENT_TEMPLATES.DESCRIPTION, request.description?.trim())
            .set(DOCUMENT_TEMPLATES.CREATED_BY, currentUser.userId)
            .returning()
            .fetchOne()!!
        return DocumentTemplateDto(
            id = rec.id,
            code = rec.code,
            name = rec.name,
            docType = rec.docType,
            confidentiality = rec.confidentiality,
            description = rec.description,
            active = rec.active
        )
    }

    private fun csv(value: String?): String {
        if (value == null) return ""
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }
}
