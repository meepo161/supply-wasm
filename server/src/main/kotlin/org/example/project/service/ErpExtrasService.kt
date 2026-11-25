package org.example.project.service

import org.example.project.dto.*
import org.example.project.exception.ConflictException
import org.example.project.exception.ForbiddenException
import org.example.project.exception.NotFoundException
import org.example.project.exception.ValidationException
import org.example.project.generated.jooq.tables.Counterparties.COUNTERPARTIES
import org.example.project.generated.jooq.tables.CounterpartyContacts.COUNTERPARTY_CONTACTS
import org.example.project.generated.jooq.tables.Delegations.DELEGATIONS
import org.example.project.generated.jooq.tables.DemandHistory.DEMAND_HISTORY
import org.example.project.generated.jooq.tables.DemandLines.DEMAND_LINES
import org.example.project.generated.jooq.tables.Demands.DEMANDS
import org.example.project.generated.jooq.tables.Departments.DEPARTMENTS
import org.example.project.generated.jooq.tables.DocumentTemplates.DOCUMENT_TEMPLATES
import org.example.project.generated.jooq.tables.DocumentVersions.DOCUMENT_VERSIONS
import org.example.project.generated.jooq.tables.Documents.DOCUMENTS
import org.example.project.generated.jooq.tables.InstanceDocuments.INSTANCE_DOCUMENTS
import org.example.project.generated.jooq.tables.Nomenclature.NOMENCLATURE
import org.example.project.generated.jooq.tables.ProductInstances.PRODUCT_INSTANCES
import org.example.project.generated.jooq.tables.ProductionOrderMaterials.PRODUCTION_ORDER_MATERIALS
import org.example.project.generated.jooq.tables.ProductionOrders.PRODUCTION_ORDERS
import org.example.project.generated.jooq.tables.ProjectChangeRequests.PROJECT_CHANGE_REQUESTS
import org.example.project.generated.jooq.tables.ProjectMembers.PROJECT_MEMBERS
import org.example.project.generated.jooq.tables.Projects.PROJECTS
import org.example.project.generated.jooq.tables.PurchaseOrderDemands.PURCHASE_ORDER_DEMANDS
import org.example.project.generated.jooq.tables.PurchaseOrderLines.PURCHASE_ORDER_LINES
import org.example.project.generated.jooq.tables.PurchaseOrders.PURCHASE_ORDERS
import org.example.project.generated.jooq.tables.StockBalance.STOCK_BALANCE
import org.example.project.generated.jooq.tables.StorageLocations.STORAGE_LOCATIONS
import org.example.project.generated.jooq.tables.TaskDependencies.TASK_DEPENDENCIES
import org.example.project.generated.jooq.tables.TaskObjectLinks.TASK_OBJECT_LINKS
import org.example.project.generated.jooq.tables.TaskParticipants.TASK_PARTICIPANTS
import org.example.project.generated.jooq.tables.Tasks.TASKS
import org.example.project.generated.jooq.tables.Transfers.TRANSFERS
import org.example.project.generated.jooq.tables.Users.USERS
import org.example.project.model.CurrentUser
import org.jooq.DSLContext
import org.jooq.TransactionalCallable
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.Year
import java.util.UUID

/**
 * Сервис дополнительных модулей ERP:
 * - PRJ-12: зависимости задач с проверкой циклов
 * - CRM-01 / CRM-02: справочник контрагентов (заказчики/партнёры) и контакты
 * - SUP-10 / SUP-20 / SUP-21: объединение потребностей и расчёт плана закупок
 * - DMS-07 / DMS-08: документы экземпляров изделий и шаблоны документов
 */
class ErpExtrasService(
    private val auditService: AuditService,
    private val taskService: TaskService,
    private val dsl: DSLContext
) {

    private fun requireTaskReadable(taskId: Long, currentUser: CurrentUser) {
        val task = dsl.selectFrom(TASKS).where(TASKS.ID.eq(taskId)).fetchOne()
            ?: throw NotFoundException("task", taskId)
        if (currentUser.hasPermission("tasks", "read_any")) return
        if (task.initiatorId == currentUser.userId || task.assigneeId == currentUser.userId || task.reviewerId == currentUser.userId) return
        val isParticipant = dsl.fetchExists(
            dsl.selectOne().from(TASK_PARTICIPANTS)
                .where(TASK_PARTICIPANTS.TASK_ID.eq(taskId))
                .and(TASK_PARTICIPANTS.USER_ID.eq(currentUser.userId))
        )
        if (isParticipant) return
        if (task.area == "PROJECT" && task.projectId != null) {
            if (currentUser.hasPermission("projects", "read_any")) return
            val projOwner = dsl.select(PROJECTS.OWNER_ID).from(PROJECTS).where(PROJECTS.ID.eq(task.projectId)).fetchOne(PROJECTS.OWNER_ID)
            val isMember = dsl.fetchExists(
                dsl.selectOne().from(PROJECT_MEMBERS)
                    .where(PROJECT_MEMBERS.PROJECT_ID.eq(task.projectId))
                    .and(PROJECT_MEMBERS.USER_ID.eq(currentUser.userId))
            )
            if (projOwner == currentUser.userId || isMember) return
        }
        if (task.area == "DEPARTMENT" && task.departmentId != null) {
            val userDept = dsl.select(USERS.DEPARTMENT_ID).from(USERS).where(USERS.ID.eq(currentUser.userId)).fetchOne(USERS.DEPARTMENT_ID)
            if (userDept == task.departmentId) return
        }
        throw ForbiddenException("Access to task $taskId denied")
    }

    // --- PRJ-12: Зависимости задач ---

    fun listTaskDependencies(taskId: Long, currentUser: CurrentUser): List<TaskDependencyDto> {
        currentUser.requirePermission("tasks", "read")
        requireTaskReadable(taskId, currentUser)
        val tPred = TASKS.`as`("tp")
        val tSucc = TASKS.`as`("ts")
        return dsl.select(
            TASK_DEPENDENCIES.ID,
            TASK_DEPENDENCIES.PREDECESSOR_TASK_ID,
            tPred.TITLE.`as`("pred_title"),
            TASK_DEPENDENCIES.SUCCESSOR_TASK_ID,
            tSucc.TITLE.`as`("succ_title"),
            TASK_DEPENDENCIES.DEPENDENCY_TYPE,
            TASK_DEPENDENCIES.CREATED_BY,
            TASK_DEPENDENCIES.CREATED_AT
        )
            .from(TASK_DEPENDENCIES)
            .join(tPred).on(tPred.ID.eq(TASK_DEPENDENCIES.PREDECESSOR_TASK_ID))
            .join(tSucc).on(tSucc.ID.eq(TASK_DEPENDENCIES.SUCCESSOR_TASK_ID))
            .where(
                TASK_DEPENDENCIES.PREDECESSOR_TASK_ID.eq(taskId)
                    .or(TASK_DEPENDENCIES.SUCCESSOR_TASK_ID.eq(taskId))
            )
            .orderBy(TASK_DEPENDENCIES.ID.asc())
            .fetch { r ->
                val predId = r.get(TASK_DEPENDENCIES.PREDECESSOR_TASK_ID)!!
                val succId = r.get(TASK_DEPENDENCIES.SUCCESSOR_TASK_ID)!!
                val canSeePred = runCatching { requireTaskReadable(predId, currentUser) }.isSuccess
                val canSeeSucc = runCatching { requireTaskReadable(succId, currentUser) }.isSuccess
                TaskDependencyDto(
                    id = r.get(TASK_DEPENDENCIES.ID)!!,
                    predecessorTaskId = predId,
                    predecessorTitle = if (canSeePred) r.get("pred_title", String::class.java) ?: "" else "Недоступная задача #$predId",
                    successorTaskId = succId,
                    successorTitle = if (canSeeSucc) r.get("succ_title", String::class.java) ?: "" else "Недоступная задача #$succId",
                    dependencyType = TaskDependencyType.valueOf(r.get(TASK_DEPENDENCIES.DEPENDENCY_TYPE)!!),
                    createdBy = r.get(TASK_DEPENDENCIES.CREATED_BY)!!,
                    createdAt = r.get(TASK_DEPENDENCIES.CREATED_AT)?.toString()
                )
            }
    }

    fun addTaskDependency(request: CreateTaskDependencyRequest, currentUser: CurrentUser): TaskDependencyDto {
        currentUser.requirePermission("tasks", "update_any")
        if (request.predecessorTaskId == request.successorTaskId) {
            throw ValidationException("Task cannot depend on itself")
        }
        requireTaskReadable(request.predecessorTaskId, currentUser)
        requireTaskReadable(request.successorTaskId, currentUser)

        // Проверка отсутствия цикла: если от successor уже можно дойти до predecessor, добавление создаст цикл.
        if (hasPath(startTaskId = request.successorTaskId, targetTaskId = request.predecessorTaskId)) {
            throw ConflictException("Adding dependency would create a cycle")
        }

        val id = dsl.transactionResult(TransactionalCallable {
            val rec = dsl.insertInto(TASK_DEPENDENCIES)
                .set(TASK_DEPENDENCIES.PREDECESSOR_TASK_ID, request.predecessorTaskId)
                .set(TASK_DEPENDENCIES.SUCCESSOR_TASK_ID, request.successorTaskId)
                .set(TASK_DEPENDENCIES.DEPENDENCY_TYPE, request.dependencyType.name)
                .set(TASK_DEPENDENCIES.CREATED_BY, currentUser.userId)
                .returningResult(TASK_DEPENDENCIES.ID)
                .fetchOne()!!
                .get(TASK_DEPENDENCIES.ID)!!
            auditService.log(
                currentUser.userId,
                "TASK_DEPENDENCY_ADD",
                "TASK",
                request.successorTaskId,
                mapOf("predecessorTaskId" to request.predecessorTaskId, "type" to request.dependencyType.name)
            )
            rec
        })
        return listTaskDependencies(request.successorTaskId, currentUser).first { it.id == id }
    }

    fun deleteTaskDependency(depId: Long, currentUser: CurrentUser) {
        currentUser.requirePermission("tasks", "update_any")
        val dep = dsl.selectFrom(TASK_DEPENDENCIES).where(TASK_DEPENDENCIES.ID.eq(depId)).fetchOne()
            ?: throw NotFoundException("task_dependency", depId)
        requireTaskReadable(dep.successorTaskId, currentUser)
        requireTaskReadable(dep.predecessorTaskId, currentUser)
        dsl.deleteFrom(TASK_DEPENDENCIES).where(TASK_DEPENDENCIES.ID.eq(depId)).execute()
    }

    private fun hasPath(startTaskId: Long, targetTaskId: Long): Boolean {
        val visited = HashSet<Long>()
        val queue = ArrayDeque<Long>()
        queue.add(startTaskId)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur == targetTaskId) return true
            if (!visited.add(cur)) continue
            val next = dsl.select(TASK_DEPENDENCIES.SUCCESSOR_TASK_ID)
                .from(TASK_DEPENDENCIES)
                .where(TASK_DEPENDENCIES.PREDECESSOR_TASK_ID.eq(cur))
                .fetch(TASK_DEPENDENCIES.SUCCESSOR_TASK_ID)
            for (n in next) {
                if (n != null && n !in visited) queue.addLast(n)
            }
        }
        return false
    }

    // --- CRM-01 / CRM-02: Контрагенты и контакты ---

    fun listCounterparties(kind: CounterpartyKind?, search: String?, currentUser: CurrentUser): List<CounterpartyDto> {
        currentUser.requirePermission("suppliers", "read")
        var cond: org.jooq.Condition = DSL.trueCondition()
        if (kind != null) cond = cond.and(COUNTERPARTIES.KIND.eq(kind.name))
        if (!search.isNullOrBlank()) {
            val q = "%${search.trim()}%"
            cond = cond.and(COUNTERPARTIES.NAME.likeIgnoreCase(q).or(COUNTERPARTIES.INN.likeIgnoreCase(q)))
        }
        val contactsMap = dsl.selectFrom(COUNTERPARTY_CONTACTS)
            .orderBy(COUNTERPARTY_CONTACTS.IS_PRIMARY.desc(), COUNTERPARTY_CONTACTS.ID.asc())
            .fetch()
            .groupBy { it.counterpartyId }
            .mapValues { (_, rows) ->
                rows.map { c ->
                    CounterpartyContactDto(
                        id = c.id!!,
                        counterpartyId = c.counterpartyId,
                        fullName = c.fullName,
                        position = c.position,
                        email = c.email,
                        phone = c.phone,
                        isPrimary = c.isPrimary ?: false,
                        notes = c.notes
                    )
                }
            }
        return dsl.selectFrom(COUNTERPARTIES)
            .where(cond)
            .orderBy(COUNTERPARTIES.ID.desc())
            .fetch { r ->
                CounterpartyDto(
                    id = r.id!!,
                    name = r.name,
                    kind = CounterpartyKind.valueOf(r.kind ?: "CUSTOMER"),
                    inn = r.inn,
                    kpp = r.kpp,
                    ogrn = r.ogrn,
                    email = r.email,
                    phone = r.phone,
                    address = r.address,
                    notes = r.notes,
                    archived = r.archived ?: false,
                    createdBy = r.createdBy,
                    createdAt = r.createdAt?.toString(),
                    version = r.version ?: 0L,
                    contacts = contactsMap[r.id] ?: emptyList()
                )
            }
    }

    fun createCounterparty(request: CreateCounterpartyRequest, currentUser: CurrentUser): CounterpartyDto {
        currentUser.requirePermission("suppliers", "manage")
        if (request.name.isBlank()) throw ValidationException("Name is required")
        val id = dsl.transactionResult(TransactionalCallable {
            val rec = dsl.insertInto(COUNTERPARTIES)
                .set(COUNTERPARTIES.NAME, request.name.trim())
                .set(COUNTERPARTIES.KIND, request.kind.name)
                .set(COUNTERPARTIES.INN, request.inn?.trim())
                .set(COUNTERPARTIES.KPP, request.kpp?.trim())
                .set(COUNTERPARTIES.OGRN, request.ogrn?.trim())
                .set(COUNTERPARTIES.EMAIL, request.email?.trim())
                .set(COUNTERPARTIES.PHONE, request.phone?.trim())
                .set(COUNTERPARTIES.ADDRESS, request.address?.trim())
                .set(COUNTERPARTIES.NOTES, request.notes?.trim())
                .set(COUNTERPARTIES.CREATED_BY, currentUser.userId)
                .returningResult(COUNTERPARTIES.ID)
                .fetchOne()!!
                .get(COUNTERPARTIES.ID)!!
            auditService.log(currentUser.userId, "CRM_COUNTERPARTY_CREATE", "COUNTERPARTY", rec)
            rec
        })
        return listCounterparties(null, null, currentUser).first { it.id == id }
    }

    fun updateCounterparty(id: Long, request: UpdateCounterpartyRequest, currentUser: CurrentUser): CounterpartyDto {
        currentUser.requirePermission("suppliers", "manage")
        if (request.name.isBlank()) throw ValidationException("Name is required")
        dsl.transaction { _ ->
            val updated = dsl.update(COUNTERPARTIES)
                .set(COUNTERPARTIES.NAME, request.name.trim())
                .set(COUNTERPARTIES.KIND, request.kind.name)
                .set(COUNTERPARTIES.INN, request.inn?.trim())
                .set(COUNTERPARTIES.KPP, request.kpp?.trim())
                .set(COUNTERPARTIES.OGRN, request.ogrn?.trim())
                .set(COUNTERPARTIES.EMAIL, request.email?.trim())
                .set(COUNTERPARTIES.PHONE, request.phone?.trim())
                .set(COUNTERPARTIES.ADDRESS, request.address?.trim())
                .set(COUNTERPARTIES.NOTES, request.notes?.trim())
                .set(COUNTERPARTIES.ARCHIVED, request.archived)
                .set(COUNTERPARTIES.UPDATED_AT, OffsetDateTime.now())
                .set(COUNTERPARTIES.VERSION, COUNTERPARTIES.VERSION.plus(1))
                .where(COUNTERPARTIES.ID.eq(id))
                .and(COUNTERPARTIES.VERSION.eq(request.version))
                .execute()
            if (updated == 0) throw ConflictException("Counterparty version conflict or not found")
            auditService.log(currentUser.userId, "CRM_COUNTERPARTY_UPDATE", "COUNTERPARTY", id)
        }
        return listCounterparties(null, null, currentUser).first { it.id == id }
    }

    fun addCounterpartyContact(
        counterpartyId: Long,
        request: CreateCounterpartyContactRequest,
        currentUser: CurrentUser
    ): CounterpartyContactDto {
        currentUser.requirePermission("suppliers", "manage")
        if (request.fullName.isBlank()) throw ValidationException("Contact name is required")
        val exists = dsl.fetchExists(dsl.selectOne().from(COUNTERPARTIES).where(COUNTERPARTIES.ID.eq(counterpartyId)))
        if (!exists) throw NotFoundException("counterparty", counterpartyId)
        val id = dsl.insertInto(COUNTERPARTY_CONTACTS)
            .set(COUNTERPARTY_CONTACTS.COUNTERPARTY_ID, counterpartyId)
            .set(COUNTERPARTY_CONTACTS.FULL_NAME, request.fullName.trim())
            .set(COUNTERPARTY_CONTACTS.POSITION, request.position?.trim())
            .set(COUNTERPARTY_CONTACTS.EMAIL, request.email?.trim())
            .set(COUNTERPARTY_CONTACTS.PHONE, request.phone?.trim())
            .set(COUNTERPARTY_CONTACTS.IS_PRIMARY, request.isPrimary)
            .set(COUNTERPARTY_CONTACTS.NOTES, request.notes?.trim())
            .returningResult(COUNTERPARTY_CONTACTS.ID)
            .fetchOne()!!
            .get(COUNTERPARTY_CONTACTS.ID)!!
        return CounterpartyContactDto(
            id = id,
            counterpartyId = counterpartyId,
            fullName = request.fullName.trim(),
            position = request.position?.trim(),
            email = request.email?.trim(),
            phone = request.phone?.trim(),
            isPrimary = request.isPrimary,
            notes = request.notes?.trim()
        )
    }

    // --- SUP-10: Объединение потребностей ---

    fun mergeDemands(request: MergeDemandsRequest, currentUser: CurrentUser): Long {
        currentUser.requirePermission("demands", "create")
        val ids = request.sourceDemandIds.distinct()
        if (ids.size < 2) throw ValidationException("At least 2 demands are required to merge")

        return dsl.transactionResult(TransactionalCallable {
            val sources = dsl.selectFrom(DEMANDS).where(DEMANDS.ID.`in`(ids)).forUpdate().fetch()
            if (sources.size != ids.size) throw NotFoundException("demand", ids.first())
            val invalid = sources.firstOrNull { it.status !in listOf("DRAFT", "PENDING_APPROVAL", "APPROVED") }
            if (invalid != null) {
                throw ConflictException("Demand ${invalid.number} in status ${invalid.status} cannot be merged")
            }
            val orderedDemandNumber = dsl.select(DEMANDS.NUMBER)
                .from(PURCHASE_ORDER_DEMANDS)
                .join(PURCHASE_ORDERS).on(PURCHASE_ORDERS.ID.eq(PURCHASE_ORDER_DEMANDS.PURCHASE_ORDER_ID))
                .join(DEMANDS).on(DEMANDS.ID.eq(PURCHASE_ORDER_DEMANDS.DEMAND_ID))
                .where(PURCHASE_ORDER_DEMANDS.DEMAND_ID.`in`(ids))
                .and(PURCHASE_ORDERS.STATUS.ne("CANCELLED"))
                .limit(1)
                .fetchOne(DEMANDS.NUMBER)
            if (orderedDemandNumber != null) {
                throw ConflictException(
                    "Нельзя объединить потребность $orderedDemandNumber: по ней уже оформлен заказ поставщику"
                )
            }
            val distinctProjects = sources.map { it.projectId }.distinct()
            if (distinctProjects.size > 1) {
                throw ValidationException("Cannot merge demands from different projects")
            }
            val first = sources.first()
            val number = "DMD-${Year.now().value}-${UUID.randomUUID().toString().take(8).uppercase()}"
            val now = OffsetDateTime.now()
            val mergedId = dsl.insertInto(DEMANDS)
                .set(DEMANDS.NUMBER, number)
                .set(DEMANDS.SOURCE_TYPE, first.sourceType)
                .set(DEMANDS.PROJECT_ID, first.projectId)
                .set(DEMANDS.PRIORITY, first.priority)
                .set(DEMANDS.STATUS, "APPROVED")
                .set(DEMANDS.APPROVED_BY, currentUser.userId)
                .set(DEMANDS.APPROVED_AT, now)
                .set(DEMANDS.STOCKPILE_NOTE, request.targetTitle?.trim()?.takeIf { it.isNotEmpty() } ?: request.reason?.trim() ?: "Объединённая потребность (${sources.joinToString(", ") { it.number }})")
                .set(DEMANDS.INITIATOR_ID, currentUser.userId)
                .set(DEMANDS.CREATED_BY, currentUser.userId)
                .returningResult(DEMANDS.ID)
                .fetchOne()!!
                .get(DEMANDS.ID)!!

            val sourceLines = dsl.selectFrom(DEMAND_LINES).where(DEMAND_LINES.DEMAND_ID.`in`(ids)).fetch()
            val grouped = sourceLines.groupBy { it.nomenclatureId to it.unit }
            var lineNo = 1
            for ((key, lines) in grouped) {
                val (nomId, unit) = key
                val totalQty = lines.fold(BigDecimal.ZERO) { acc, l -> acc + l.quantity }
                dsl.insertInto(DEMAND_LINES)
                    .set(DEMAND_LINES.DEMAND_ID, mergedId)
                    .set(DEMAND_LINES.LINE_NO, lineNo++)
                    .set(DEMAND_LINES.NOMENCLATURE_ID, nomId)
                    .set(DEMAND_LINES.QUANTITY, totalQty)
                    .set(DEMAND_LINES.UNIT, unit)
                    .set(DEMAND_LINES.STATUS, "OPEN")
                    .set(DEMAND_LINES.CREATED_BY, currentUser.userId)
                    .execute()
            }

            dsl.insertInto(DEMAND_HISTORY)
                .set(DEMAND_HISTORY.DEMAND_ID, mergedId)
                .set(DEMAND_HISTORY.ACTION, "MERGED_CREATED")
                .set(DEMAND_HISTORY.TO_STATUS, "APPROVED")
                .set(DEMAND_HISTORY.REASON, "Объединение: ${sources.joinToString(", ") { it.number }}")
                .set(DEMAND_HISTORY.CHANGED_BY, currentUser.userId)
                .execute()

            for (src in sources) {
                dsl.update(DEMANDS)
                    .set(DEMANDS.STATUS, "CANCELLED")
                    .set(DEMANDS.VERSION, (src.version ?: 0L) + 1L)
                    .where(DEMANDS.ID.eq(src.id))
                    .execute()
                dsl.insertInto(DEMAND_HISTORY)
                    .set(DEMAND_HISTORY.DEMAND_ID, src.id)
                    .set(DEMAND_HISTORY.ACTION, "MERGED_INTO")
                    .set(DEMAND_HISTORY.FROM_STATUS, src.status)
                    .set(DEMAND_HISTORY.TO_STATUS, "CANCELLED")
                    .set(DEMAND_HISTORY.REASON, "Объединена в $number")
                    .set(DEMAND_HISTORY.CHANGED_BY, currentUser.userId)
                    .execute()
            }

            auditService.log(
                currentUser.userId,
                "DEMAND_MERGE",
                "DEMAND",
                mergedId,
                mapOf("sourceDemandIds" to ids)
            )
            mergedId
        })
    }

    // --- SUP-20 / SUP-21: Планирование обеспечения и рекомендуемая закупка ---

    fun getProcurementPlan(currentUser: CurrentUser): List<ProcurementPlanItemDto> {
        currentUser.requirePermission("demands", "read")
        val requiredByNom = dsl.select(
            DEMAND_LINES.NOMENCLATURE_ID,
            DSL.sum(DEMAND_LINES.QUANTITY).`as`("qty")
        )
            .from(DEMAND_LINES)
            .join(DEMANDS).on(DEMANDS.ID.eq(DEMAND_LINES.DEMAND_ID))
            .where(DEMANDS.STATUS.`in`("PENDING_APPROVAL", "APPROVED", "IN_PROCUREMENT", "PARTIALLY_COVERED"))
            .groupBy(DEMAND_LINES.NOMENCLATURE_ID)
            .fetchMap(DEMAND_LINES.NOMENCLATURE_ID) {
                it.get("qty", BigDecimal::class.java) ?: BigDecimal.ZERO
            }

        val availableByNom = dsl.select(
            STOCK_BALANCE.NOMENCLATURE_ID,
            DSL.sum(STOCK_BALANCE.PHYSICAL_QTY.minus(STOCK_BALANCE.RESERVED_QTY)).`as`("avail")
        )
            .from(STOCK_BALANCE)
            .groupBy(STOCK_BALANCE.NOMENCLATURE_ID)
            .fetchMap(STOCK_BALANCE.NOMENCLATURE_ID) {
                (it.get("avail", BigDecimal::class.java) ?: BigDecimal.ZERO).max(BigDecimal.ZERO)
            }

        val inTransitByNom = dsl.select(
            TRANSFERS.NOMENCLATURE_ID,
            DSL.sum(TRANSFERS.QUANTITY).`as`("qty")
        )
            .from(TRANSFERS)
            .where(TRANSFERS.STATUS.eq("IN_TRANSIT"))
            .groupBy(TRANSFERS.NOMENCLATURE_ID)
            .fetchMap(TRANSFERS.NOMENCLATURE_ID) {
                it.get("qty", BigDecimal::class.java) ?: BigDecimal.ZERO
            }

        val openPoByNom = dsl.select(
            PURCHASE_ORDER_LINES.NOMENCLATURE_ID,
            DSL.sum(PURCHASE_ORDER_LINES.QUANTITY).`as`("qty")
        )
            .from(PURCHASE_ORDER_LINES)
            .join(PURCHASE_ORDERS).on(PURCHASE_ORDERS.ID.eq(PURCHASE_ORDER_LINES.ORDER_ID))
            .where(PURCHASE_ORDERS.STATUS.`in`("APPROVED", "SENT", "CONFIRMED", "PARTIALLY_DELIVERED"))
            .groupBy(PURCHASE_ORDER_LINES.NOMENCLATURE_ID)
            .fetchMap(PURCHASE_ORDER_LINES.NOMENCLATURE_ID) {
                (it.get("qty", BigDecimal::class.java) ?: BigDecimal.ZERO).max(BigDecimal.ZERO)
            }

        val items = dsl.selectFrom(NOMENCLATURE)
            .where(NOMENCLATURE.ARCHIVED.isFalse)
            .orderBy(NOMENCLATURE.CODE.asc())
            .fetch()

        val result = mutableListOf<ProcurementPlanItemDto>()
        for (nom in items) {
            val id = nom.id ?: continue
            val req = requiredByNom[id] ?: BigDecimal.ZERO
            val avail = availableByNom[id] ?: BigDecimal.ZERO
            val transit = inTransitByNom[id] ?: BigDecimal.ZERO
            val openPo = openPoByNom[id] ?: BigDecimal.ZERO
            val minStock = nom.minStock ?: BigDecimal.ZERO
            val safetyStock = nom.safetyStock ?: BigDecimal.ZERO
            val targetStock = req + minStock + safetyStock
            val supply = avail + transit + openPo
            val netDeficit = (targetStock - supply).max(BigDecimal.ZERO)
            if (netDeficit <= BigDecimal.ZERO && req <= BigDecimal.ZERO) continue

            val minOrder = (nom.minOrderQty ?: BigDecimal.ONE).max(BigDecimal.ONE)
            val multiple = (nom.orderMultiple ?: BigDecimal.ONE).max(BigDecimal.ONE)
            val recommended = if (netDeficit > BigDecimal.ZERO) {
                val atLeastMin = netDeficit.max(minOrder)
                val steps = atLeastMin.divide(multiple, 0, RoundingMode.CEILING)
                steps.multiply(multiple)
            } else {
                BigDecimal.ZERO
            }

            result += ProcurementPlanItemDto(
                nomenclatureId = id,
                code = nom.code,
                name = nom.name,
                unit = nom.unit,
                requiredQty = req.stripTrailingZeros().toPlainString(),
                availableQty = avail.stripTrailingZeros().toPlainString(),
                inTransitQty = transit.stripTrailingZeros().toPlainString(),
                openPoQty = openPo.stripTrailingZeros().toPlainString(),
                minStock = minStock.stripTrailingZeros().toPlainString(),
                safetyStock = safetyStock.stripTrailingZeros().toPlainString(),
                minOrderQty = minOrder.stripTrailingZeros().toPlainString(),
                orderMultiple = multiple.stripTrailingZeros().toPlainString(),
                leadTimeDays = nom.leadTimeDays ?: 0,
                netDeficitQty = netDeficit.stripTrailingZeros().toPlainString(),
                recommendedOrderQty = recommended.stripTrailingZeros().toPlainString()
            )
        }
        return result
    }

    // --- DMS-07: Привязка версий документов к экземпляру изделия ---

    fun listInstanceDocuments(instanceId: Long, currentUser: CurrentUser): List<InstanceDocumentDto> {
        currentUser.requirePermission("production", "read")
        return dsl.select(
            INSTANCE_DOCUMENTS.ID,
            INSTANCE_DOCUMENTS.INSTANCE_ID,
            INSTANCE_DOCUMENTS.DOCUMENT_VERSION_ID,
            DOCUMENT_VERSIONS.DOCUMENT_ID,
            DOCUMENTS.NAME,
            DOCUMENT_VERSIONS.VERSION_NUMBER,
            INSTANCE_DOCUMENTS.DOC_KIND,
            INSTANCE_DOCUMENTS.CREATED_BY,
            INSTANCE_DOCUMENTS.CREATED_AT,
            INSTANCE_DOCUMENTS.VERSION
        )
            .from(INSTANCE_DOCUMENTS)
            .join(DOCUMENT_VERSIONS).on(DOCUMENT_VERSIONS.ID.eq(INSTANCE_DOCUMENTS.DOCUMENT_VERSION_ID))
            .join(DOCUMENTS).on(DOCUMENTS.ID.eq(DOCUMENT_VERSIONS.DOCUMENT_ID))
            .where(INSTANCE_DOCUMENTS.INSTANCE_ID.eq(instanceId))
            .orderBy(INSTANCE_DOCUMENTS.ID.desc())
            .fetch { r ->
                InstanceDocumentDto(
                    id = r.get(INSTANCE_DOCUMENTS.ID)!!,
                    instanceId = r.get(INSTANCE_DOCUMENTS.INSTANCE_ID)!!,
                    documentVersionId = r.get(INSTANCE_DOCUMENTS.DOCUMENT_VERSION_ID)!!,
                    documentId = r.get(DOCUMENT_VERSIONS.DOCUMENT_ID)!!,
                    documentTitle = r.get(DOCUMENTS.NAME) ?: "",
                    versionNumber = r.get(DOCUMENT_VERSIONS.VERSION_NUMBER) ?: 1,
                    docKind = r.get(INSTANCE_DOCUMENTS.DOC_KIND)!!,
                    createdBy = r.get(INSTANCE_DOCUMENTS.CREATED_BY)!!,
                    createdAt = r.get(INSTANCE_DOCUMENTS.CREATED_AT)?.toString(),
                    version = r.get(INSTANCE_DOCUMENTS.VERSION) ?: 0L
                )
            }
    }

    fun attachInstanceDocument(
        instanceId: Long,
        request: AttachInstanceDocumentRequest,
        currentUser: CurrentUser
    ): InstanceDocumentDto {
        currentUser.requirePermission("production", "manage")
        val existsInst = dsl.fetchExists(dsl.selectOne().from(PRODUCT_INSTANCES).where(PRODUCT_INSTANCES.ID.eq(instanceId)))
        if (!existsInst) throw NotFoundException("product_instance", instanceId)
        val existsVer = dsl.fetchExists(dsl.selectOne().from(DOCUMENT_VERSIONS).where(DOCUMENT_VERSIONS.ID.eq(request.documentVersionId)))
        if (!existsVer) throw NotFoundException("document_version", request.documentVersionId)

        val kind = request.docKind.trim().uppercase()
        if (kind !in setOf("MANUAL", "DRAWING", "SPECIFICATION", "TEST_PROTOCOL", "CERTIFICATE", "OTHER")) {
            throw ValidationException("Invalid docKind")
        }

        val id = dsl.insertInto(INSTANCE_DOCUMENTS)
            .set(INSTANCE_DOCUMENTS.INSTANCE_ID, instanceId)
            .set(INSTANCE_DOCUMENTS.DOCUMENT_VERSION_ID, request.documentVersionId)
            .set(INSTANCE_DOCUMENTS.DOC_KIND, kind)
            .set(INSTANCE_DOCUMENTS.CREATED_BY, currentUser.userId)
            .returningResult(INSTANCE_DOCUMENTS.ID)
            .fetchOne()!!
            .get(INSTANCE_DOCUMENTS.ID)!!
        auditService.log(currentUser.userId, "INSTANCE_DOC_ATTACH", "PRODUCT_INSTANCE", instanceId)
        return listInstanceDocuments(instanceId, currentUser).first { it.id == id }
    }

    // --- DMS-08: Шаблоны документов ---

    fun listDocumentTemplates(currentUser: CurrentUser): List<DocumentTemplateDto> {
        currentUser.requirePermission("documents", "read")
        return dsl.selectFrom(DOCUMENT_TEMPLATES)
            .orderBy(DOCUMENT_TEMPLATES.ID.desc())
            .fetch { r ->
                DocumentTemplateDto(
                    id = r.id!!,
                    code = r.code,
                    name = r.name,
                    docType = r.docType,
                    confidentiality = r.confidentiality ?: "INTERNAL",
                    description = r.description,
                    active = r.active ?: true
                )
            }
    }

    fun createDocumentTemplate(
        request: CreateDocumentTemplateRequest,
        currentUser: CurrentUser
    ): DocumentTemplateDto {
        currentUser.requirePermission("documents", "create")
        if (request.code.isBlank() || request.name.isBlank()) {
            throw ValidationException("Code and name are required")
        }
        val id = dsl.insertInto(DOCUMENT_TEMPLATES)
            .set(DOCUMENT_TEMPLATES.CODE, request.code.trim().uppercase())
            .set(DOCUMENT_TEMPLATES.NAME, request.name.trim())
            .set(DOCUMENT_TEMPLATES.DOC_TYPE, request.docType.trim().uppercase())
            .set(DOCUMENT_TEMPLATES.CONFIDENTIALITY, request.confidentiality.trim().uppercase())
            .set(DOCUMENT_TEMPLATES.DESCRIPTION, request.description)
            .set(DOCUMENT_TEMPLATES.CREATED_BY, currentUser.userId)
            .returningResult(DOCUMENT_TEMPLATES.ID)
            .fetchOne()!!
            .get(DOCUMENT_TEMPLATES.ID)!!
        auditService.log(currentUser.userId, "DOC_TEMPLATE_CREATE", "DOCUMENT_TEMPLATE", id)
        return listDocumentTemplates(currentUser).first { it.id == id }
    }

    // --- BOM-14: Замена материала в заказе без изменения ревизии BOM ---

    fun substituteOrderMaterial(
        orderId: Long,
        materialId: Long,
        request: SubstituteOrderMaterialRequest,
        currentUser: CurrentUser
    ): ProductionOrderMaterialDto {
        currentUser.requirePermission("production", "manage")
        val nom = dsl.selectFrom(NOMENCLATURE).where(NOMENCLATURE.ID.eq(request.newNomenclatureId)).fetchOne()
            ?: throw NotFoundException("nomenclature", request.newNomenclatureId)
        val updated = dsl.update(PRODUCTION_ORDER_MATERIALS)
            .set(PRODUCTION_ORDER_MATERIALS.NOMENCLATURE_ID, request.newNomenclatureId)
            .set(PRODUCTION_ORDER_MATERIALS.UPDATED_AT, OffsetDateTime.now())
            .set(PRODUCTION_ORDER_MATERIALS.VERSION, PRODUCTION_ORDER_MATERIALS.VERSION.plus(1))
            .where(PRODUCTION_ORDER_MATERIALS.ID.eq(materialId))
            .and(PRODUCTION_ORDER_MATERIALS.PRODUCTION_ORDER_ID.eq(orderId))
            .and(PRODUCTION_ORDER_MATERIALS.VERSION.eq(request.version))
            .execute()
        if (updated == 0) throw ConflictException("Material version conflict or not found")
        auditService.log(
            currentUser.userId,
            "PROD_MATERIAL_SUBSTITUTE",
            "PRODUCTION_ORDER",
            orderId,
            mapOf("materialId" to materialId, "newNomenclatureId" to request.newNomenclatureId, "reason" to request.reason)
        )
        val row = dsl.selectFrom(PRODUCTION_ORDER_MATERIALS).where(PRODUCTION_ORDER_MATERIALS.ID.eq(materialId)).fetchOne()!!
        return ProductionOrderMaterialDto(
            id = row.id!!,
            productionOrderId = row.productionOrderId,
            nomenclatureId = row.nomenclatureId,
            nomenclatureCode = nom.code,
            nomenclatureName = nom.name,
            plannedQty = row.plannedQty.stripTrailingZeros().toPlainString(),
            allocatedQty = row.allocatedQty.stripTrailingZeros().toPlainString(),
            consumedQty = row.consumedQty.stripTrailingZeros().toPlainString(),
            unit = row.unit,
            warehouseId = row.warehouseId,
            reservationId = row.reservationId,
            issueOrderId = row.issueOrderId,
            version = row.version ?: 0L
        )
    }

    // --- ANA-07: Печатная форма производственного заказа (HTML) ---

    fun renderProductionOrderPrintHtml(orderId: Long, currentUser: CurrentUser): String {
        currentUser.requirePermission("production", "read")
        val order = dsl.selectFrom(PRODUCTION_ORDERS).where(PRODUCTION_ORDERS.ID.eq(orderId)).fetchOne()
            ?: throw NotFoundException("production_order", orderId)
        val mats = dsl.select(
            PRODUCTION_ORDER_MATERIALS.PLANNED_QTY,
            PRODUCTION_ORDER_MATERIALS.ALLOCATED_QTY,
            PRODUCTION_ORDER_MATERIALS.CONSUMED_QTY,
            PRODUCTION_ORDER_MATERIALS.UNIT,
            NOMENCLATURE.CODE,
            NOMENCLATURE.NAME
        )
            .from(PRODUCTION_ORDER_MATERIALS)
            .join(NOMENCLATURE).on(NOMENCLATURE.ID.eq(PRODUCTION_ORDER_MATERIALS.NOMENCLATURE_ID))
            .where(PRODUCTION_ORDER_MATERIALS.PRODUCTION_ORDER_ID.eq(orderId))
            .fetch()
        val rowsHtml = mats.joinToString("\n") { m ->
            "<tr><td>${m.get(NOMENCLATURE.CODE)}</td><td>${m.get(NOMENCLATURE.NAME)}</td><td>${m.get(PRODUCTION_ORDER_MATERIALS.PLANNED_QTY)}</td><td>${m.get(PRODUCTION_ORDER_MATERIALS.CONSUMED_QTY)}</td><td>${m.get(PRODUCTION_ORDER_MATERIALS.UNIT)}</td></tr>"
        }
        return """
            <!DOCTYPE html>
            <html lang="ru"><head><meta charset="utf-8"><title>Заказ ${order.number}</title>
            <style>body{font-family:sans-serif;padding:24px}table{border-collapse:collapse;width:100%}th,td{border:1px solid #ccc;padding:6px;text-align:left}</style>
            </head><body>
            <h1>Производственный заказ № ${order.number}</h1>
            <p>Статус: ${order.status} | План: ${order.plannedQuantity} | Факт: ${order.actualQuantity ?: 0}</p>
            <table><thead><tr><th>Код</th><th>Наименование</th><th>План</th><th>Факт</th><th>Ед.</th></tr></thead>
            <tbody>$rowsHtml</tbody></table>
            <script>window.print && setTimeout(()=>window.print(), 300);</script>
            </body></html>
        """.trimIndent()
    }

    // --- ANA-08: Импорт номенклатуры из CSV ---

    private fun parseCsvRow(line: String, delimiter: Char): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                ch == delimiter && !inQuotes -> {
                    result += current.toString().trim()
                    current.clear()
                }
                else -> current.append(ch)
            }
            i++
        }
        result += current.toString().trim()
        return result
    }

    fun importNomenclatureCsv(request: CsvImportRequest, currentUser: CurrentUser): CsvImportResultDto {
        currentUser.requirePermission("wms", "manage")
        val lines = request.csvContent.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return CsvImportResultDto(0, 0, emptyList())
        val validTypes = NomenclatureType.entries.map { it.name }.toSet()
        val sample = lines.first()
        val delimiter = when {
            sample.contains(';') -> ';'
            sample.contains('\t') -> '\t'
            else -> ','
        }
        return dsl.transactionResult(TransactionalCallable {
            var imported = 0
            var skipped = 0
            val errors = mutableListOf<String>()
            for ((idx, line) in lines.withIndex()) {
                if (idx == 0 && line.lowercase().startsWith("code")) continue
                val parts = parseCsvRow(line, delimiter)
                if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) {
                    errors += "Строка ${idx + 1}: ожидается минимум code${delimiter}name"
                    skipped++
                    continue
                }
                val code = parts[0]
                val name = parts[1]
                val rawType = parts.getOrNull(2)?.uppercase()?.takeIf { it.isNotBlank() }
                val type = when {
                    rawType == null -> "MATERIAL"
                    rawType in validTypes -> rawType
                    else -> {
                        errors += "Строка ${idx + 1}: неизвестный тип '$rawType', использован MATERIAL"
                        "MATERIAL"
                    }
                }
                val unit = parts.getOrNull(3)?.ifBlank { "шт" } ?: "шт"
                val exists = dsl.fetchExists(dsl.selectOne().from(NOMENCLATURE).where(NOMENCLATURE.CODE.eq(code)))
                if (exists) {
                    skipped++
                    continue
                }
                dsl.insertInto(NOMENCLATURE)
                    .set(NOMENCLATURE.CODE, code)
                    .set(NOMENCLATURE.NAME, name)
                    .set(NOMENCLATURE.TYPE, type)
                    .set(NOMENCLATURE.UNIT, unit)
                    .set(NOMENCLATURE.CREATED_BY, currentUser.userId)
                    .execute()
                imported++
            }
            auditService.log(currentUser.userId, "NOMENCLATURE_CSV_IMPORT", "NOMENCLATURE", 0L, mapOf("imported" to imported, "skipped" to skipped))
            CsvImportResultDto(importedCount = imported, skippedCount = skipped, errors = errors)
        })
    }

    // --- IAM-13: Делегирование полномочий ---

    fun listDelegations(currentUser: CurrentUser): List<DelegationDto> {
        val u1 = USERS.`as`("u1")
        val u2 = USERS.`as`("u2")
        return dsl.select(
            DELEGATIONS.ID, DELEGATIONS.DELEGATOR_ID, u1.FULL_NAME.`as`("delegator_name"),
            DELEGATIONS.DELEGATE_ID, u2.FULL_NAME.`as`("delegate_name"),
            DELEGATIONS.STARTS_AT, DELEGATIONS.ENDS_AT, DELEGATIONS.REASON, DELEGATIONS.ACTIVE
        )
            .from(DELEGATIONS)
            .join(u1).on(u1.ID.eq(DELEGATIONS.DELEGATOR_ID))
            .join(u2).on(u2.ID.eq(DELEGATIONS.DELEGATE_ID))
            .where(DELEGATIONS.DELEGATOR_ID.eq(currentUser.userId).or(DELEGATIONS.DELEGATE_ID.eq(currentUser.userId)))
            .orderBy(DELEGATIONS.ID.desc())
            .fetch { r ->
                DelegationDto(
                    id = r.get(DELEGATIONS.ID)!!,
                    delegatorId = r.get(DELEGATIONS.DELEGATOR_ID)!!,
                    delegatorName = r.get("delegator_name", String::class.java) ?: "",
                    delegateId = r.get(DELEGATIONS.DELEGATE_ID)!!,
                    delegateName = r.get("delegate_name", String::class.java) ?: "",
                    startsAt = r.get(DELEGATIONS.STARTS_AT).toString(),
                    endsAt = r.get(DELEGATIONS.ENDS_AT).toString(),
                    reason = r.get(DELEGATIONS.REASON),
                    active = r.get(DELEGATIONS.ACTIVE) ?: true
                )
            }
    }

    fun createDelegation(request: CreateDelegationRequest, currentUser: CurrentUser): DelegationDto {
        if (request.delegateId == currentUser.userId) throw ValidationException("Cannot delegate to yourself")
        val delegate = dsl.selectFrom(USERS).where(USERS.ID.eq(request.delegateId)).fetchOne()
            ?: throw NotFoundException("user", request.delegateId)
        if (delegate.active != true) throw ValidationException("Delegate user must be active")
        val endsAt = runCatching { OffsetDateTime.parse(request.endsAt) }
            .getOrElse { throw ValidationException("Invalid endsAt ISO timestamp") }
        if (!endsAt.isAfter(OffsetDateTime.now())) {
            throw ValidationException("endsAt must be in the future", mapOf("endsAt" to "in_past"))
        }
        val id = dsl.insertInto(DELEGATIONS)
            .set(DELEGATIONS.DELEGATOR_ID, currentUser.userId)
            .set(DELEGATIONS.DELEGATE_ID, request.delegateId)
            .set(DELEGATIONS.ENDS_AT, endsAt)
            .set(DELEGATIONS.REASON, request.reason?.trim())
            .returningResult(DELEGATIONS.ID)
            .fetchOne()!!.get(DELEGATIONS.ID)!!
        auditService.log(currentUser.userId, "DELEGATION_CREATE", "DELEGATION", id, mapOf("delegateId" to request.delegateId))
        return listDelegations(currentUser).first { it.id == id }
    }

    fun revokeDelegation(id: Long, currentUser: CurrentUser) {
        val updated = dsl.update(DELEGATIONS)
            .set(DELEGATIONS.ACTIVE, false)
            .where(DELEGATIONS.ID.eq(id))
            .and(DELEGATIONS.DELEGATOR_ID.eq(currentUser.userId))
            .execute()
        if (updated == 0) throw NotFoundException("delegation", id)
        auditService.log(currentUser.userId, "DELEGATION_REVOKE", "DELEGATION", id)
    }

    // --- TASK-33: Связи задачи с объектами ERP ---

    fun listTaskObjectLinks(taskId: Long, currentUser: CurrentUser): List<TaskObjectLinkDto> {
        currentUser.requirePermission("tasks", "read")
        requireTaskReadable(taskId, currentUser)
        return dsl.selectFrom(TASK_OBJECT_LINKS)
            .where(TASK_OBJECT_LINKS.TASK_ID.eq(taskId))
            .orderBy(TASK_OBJECT_LINKS.ID.desc())
            .fetch { r ->
                TaskObjectLinkDto(
                    id = r.id!!,
                    taskId = r.taskId,
                    objectType = r.objectType,
                    objectId = r.objectId,
                    note = r.note,
                    createdBy = r.createdBy,
                    createdAt = r.createdAt?.toString()
                )
            }
    }

    fun addTaskObjectLink(taskId: Long, request: CreateTaskObjectLinkRequest, currentUser: CurrentUser): TaskObjectLinkDto {
        currentUser.requirePermission("tasks", "update_any")
        requireTaskReadable(taskId, currentUser)
        val objectType = request.objectType.trim().uppercase()
        val allowedTypes = setOf(
            "PROJECT", "TASK", "DEMAND", "SUPPLIER", "PURCHASE_ORDER",
            "RECEIPT", "PRODUCTION_ORDER", "DOCUMENT", "NOMENCLATURE", "BOM_REVISION"
        )
        if (objectType !in allowedTypes) {
            throw ValidationException("Unsupported objectType: $objectType", mapOf("objectType" to "invalid"))
        }
        if (request.objectId <= 0) {
            throw ValidationException("Invalid objectId", mapOf("objectId" to "invalid"))
        }
        val id = dsl.insertInto(TASK_OBJECT_LINKS)
            .set(TASK_OBJECT_LINKS.TASK_ID, taskId)
            .set(TASK_OBJECT_LINKS.OBJECT_TYPE, objectType)
            .set(TASK_OBJECT_LINKS.OBJECT_ID, request.objectId)
            .set(TASK_OBJECT_LINKS.NOTE, request.note?.trim())
            .set(TASK_OBJECT_LINKS.CREATED_BY, currentUser.userId)
            .returningResult(TASK_OBJECT_LINKS.ID)
            .fetchOne()!!.get(TASK_OBJECT_LINKS.ID)!!
        auditService.log(currentUser.userId, "TASK_OBJECT_LINK", "TASK", taskId, mapOf("objectType" to objectType, "objectId" to request.objectId))
        return listTaskObjectLinks(taskId, currentUser).first { it.id == id }
    }

    // --- PRJ-11: Запросы на изменение проекта ---

    private fun requireProjectAccess(projectId: Long, currentUser: CurrentUser, forUpdate: Boolean) {
        val project = dsl.selectFrom(PROJECTS).where(PROJECTS.ID.eq(projectId)).fetchOne()
            ?: throw NotFoundException("project", projectId)
        if (forUpdate && currentUser.hasPermission("projects", "update_any")) return
        if (!forUpdate && currentUser.hasPermission("projects", "read_any")) return
        if (project.ownerId == currentUser.userId) return
        val isMember = dsl.fetchExists(
            dsl.selectOne().from(PROJECT_MEMBERS)
                .where(PROJECT_MEMBERS.PROJECT_ID.eq(projectId))
                .and(PROJECT_MEMBERS.USER_ID.eq(currentUser.userId))
        )
        if (!isMember) throw ForbiddenException("Access to project $projectId denied")
    }

    fun listProjectChangeRequests(projectId: Long, currentUser: CurrentUser): List<ProjectChangeRequestDto> {
        currentUser.requirePermission("projects", "read")
        requireProjectAccess(projectId, currentUser, forUpdate = false)
        return dsl.selectFrom(PROJECT_CHANGE_REQUESTS)
            .where(PROJECT_CHANGE_REQUESTS.PROJECT_ID.eq(projectId))
            .orderBy(PROJECT_CHANGE_REQUESTS.ID.desc())
            .fetch { r ->
                ProjectChangeRequestDto(
                    id = r.id!!,
                    projectId = r.projectId,
                    title = r.title,
                    reason = r.reason,
                    newEndDate = r.newEndDate?.toString(),
                    status = r.status ?: "OPEN",
                    requestedBy = r.requestedBy,
                    createdAt = r.createdAt?.toString()
                )
            }
    }

    fun createProjectChangeRequest(projectId: Long, request: CreateProjectChangeRequest, currentUser: CurrentUser): ProjectChangeRequestDto {
        currentUser.requirePermission("projects", "update")
        if (request.title.isBlank() || request.reason.isBlank()) throw ValidationException("Title and reason are required")
        val endDate = request.newEndDate?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { LocalDate.parse(raw.trim()) }.getOrElse {
                throw ValidationException("Invalid newEndDate format (expected YYYY-MM-DD)", mapOf("newEndDate" to "format"))
            }
        }
        requireProjectAccess(projectId, currentUser, forUpdate = true)
        val id = dsl.insertInto(PROJECT_CHANGE_REQUESTS)
            .set(PROJECT_CHANGE_REQUESTS.PROJECT_ID, projectId)
            .set(PROJECT_CHANGE_REQUESTS.TITLE, request.title.trim())
            .set(PROJECT_CHANGE_REQUESTS.REASON, request.reason.trim())
            .set(PROJECT_CHANGE_REQUESTS.NEW_END_DATE, endDate)
            .set(PROJECT_CHANGE_REQUESTS.REQUESTED_BY, currentUser.userId)
            .returningResult(PROJECT_CHANGE_REQUESTS.ID)
            .fetchOne()!!.get(PROJECT_CHANGE_REQUESTS.ID)!!
        auditService.log(currentUser.userId, "PROJECT_CHANGE_REQUEST", "PROJECT", projectId, mapOf("pcrId" to id))
        return listProjectChangeRequests(projectId, currentUser).first { it.id == id }
    }

    // --- TASK-27: Массовые действия над задачами ---

    fun bulkTaskAction(request: BulkTaskActionRequest, currentUser: CurrentUser): Int {
        currentUser.requirePermission("tasks", "update_any")
        val ids = request.taskIds.distinct()
        if (ids.isEmpty()) return 0
        val action = request.action.trim().uppercase()
        if (action !in setOf("CLOSE", "CANCEL")) {
            throw ValidationException("Unsupported bulk action: ${request.action}")
        }
        if (action == "CANCEL" && request.reason.isNullOrBlank()) {
            throw ValidationException("Cancel reason is required", mapOf("reason" to "required"))
        }
        var count = 0
        for (taskId in ids) {
            requireTaskReadable(taskId, currentUser)
            val task = dsl.selectFrom(TASKS).where(TASKS.ID.eq(taskId)).fetchOne()
                ?: throw NotFoundException("task", taskId)
            val ver = task.version ?: 0L
            when (action) {
                "CLOSE" -> {
                    if (task.type == "REVIEWED") {
                        taskService.review(taskId, ReviewTaskRequest(ReviewDecision.ACCEPT, request.reason, ver), currentUser)
                    } else {
                        taskService.closeWithoutReview(taskId, TaskCommandRequest(ver), currentUser)
                    }
                }
                "CANCEL" -> {
                    taskService.cancel(taskId, CancelTaskRequest(request.reason!!.trim(), ver), currentUser)
                }
            }
            count++
        }
        auditService.log(currentUser.userId, "TASK_BULK_$action", "TASK", 0L, mapOf("count" to count, "ids" to ids))
        return count
    }

    // --- HOME-06..08 / INV-08: Очереди подразделений для панели руководителя ---

    fun getExecutiveQueues(currentUser: CurrentUser): List<DepartmentQueueDto> {
        currentUser.requirePermission("tasks", "read")
        val depts = dsl.selectFrom(DEPARTMENTS).orderBy(DEPARTMENTS.NAME.asc()).fetch()
        if (depts.isEmpty()) return emptyList()
        val now = OffsetDateTime.now()
        val openByDept = dsl.select(TASKS.DEPARTMENT_ID, DSL.count())
            .from(TASKS)
            .where(TASKS.DEPARTMENT_ID.isNotNull)
            .and(TASKS.STATUS.notIn("CLOSED", "CANCELLED"))
            .groupBy(TASKS.DEPARTMENT_ID)
            .fetchMap({ it.value1()!! }, { it.value2() })
        val overdueByDept = dsl.select(TASKS.DEPARTMENT_ID, DSL.count())
            .from(TASKS)
            .where(TASKS.DEPARTMENT_ID.isNotNull)
            .and(TASKS.STATUS.notIn("CLOSED", "CANCELLED"))
            .and(TASKS.DUE_DATE.lt(now))
            .groupBy(TASKS.DEPARTMENT_ID)
            .fetchMap({ it.value1()!! }, { it.value2() })
        val ordersByDept = dsl.select(PRODUCTION_ORDERS.DEPARTMENT_ID, DSL.count())
            .from(PRODUCTION_ORDERS)
            .where(PRODUCTION_ORDERS.DEPARTMENT_ID.isNotNull)
            .and(PRODUCTION_ORDERS.STATUS.eq("IN_PROGRESS"))
            .groupBy(PRODUCTION_ORDERS.DEPARTMENT_ID)
            .fetchMap({ it.value1()!! }, { it.value2() })
        return depts.map { d ->
            val deptId = d.id!!
            DepartmentQueueDto(
                departmentId = deptId,
                departmentName = d.name,
                openTasks = openByDept[deptId] ?: 0,
                overdueTasks = overdueByDept[deptId] ?: 0,
                inProgressOrders = ordersByDept[deptId] ?: 0
            )
        }
    }

    // --- WMS-03: Подбор ячейки с учётом вместимости и совместимости ---

    fun suggestStorageLocation(warehouseId: Long, nomenclatureId: Long, currentUser: CurrentUser): Long? {
        currentUser.requirePermission("wms", "read")
        val nomType = dsl.select(NOMENCLATURE.TYPE)
            .from(NOMENCLATURE)
            .where(NOMENCLATURE.ID.eq(nomenclatureId))
            .fetchOne(NOMENCLATURE.TYPE)
        val locations = dsl.selectFrom(STORAGE_LOCATIONS)
            .where(STORAGE_LOCATIONS.WAREHOUSE_ID.eq(warehouseId))
            .and(STORAGE_LOCATIONS.ACTIVE.isTrue)
            .orderBy(STORAGE_LOCATIONS.ID.asc())
            .fetch()
        for (loc in locations) {
            val allowed = loc.allowedType
            if (allowed != null && nomType != null && allowed != nomType) continue
            val maxCap = loc.maxCapacity
            if (maxCap != null) {
                val currentQty = dsl.select(DSL.coalesce(DSL.sum(STOCK_BALANCE.PHYSICAL_QTY), BigDecimal.ZERO))
                    .from(STOCK_BALANCE)
                    .where(STOCK_BALANCE.LOCATION_ID.eq(loc.id!!))
                    .fetchOne(0, BigDecimal::class.java) ?: BigDecimal.ZERO
                if (currentQty >= maxCap) continue
            }
            return loc.id
        }
        return locations.firstOrNull()?.id
    }
}
