package org.example.project.repository

import org.example.project.generated.jooq.tables.Demands.DEMANDS
import org.example.project.generated.jooq.tables.Documents.DOCUMENTS
import org.example.project.generated.jooq.tables.Nomenclature.NOMENCLATURE
import org.example.project.generated.jooq.tables.ProductionOrders.PRODUCTION_ORDERS
import org.example.project.generated.jooq.tables.ProjectMembers.PROJECT_MEMBERS
import org.example.project.generated.jooq.tables.Projects.PROJECTS
import org.example.project.generated.jooq.tables.PurchaseOrders.PURCHASE_ORDERS
import org.example.project.generated.jooq.tables.Receipts.RECEIPTS
import org.example.project.generated.jooq.tables.Suppliers.SUPPLIERS
import org.example.project.generated.jooq.tables.TaskChatMessages.TASK_CHAT_MESSAGES
import org.example.project.generated.jooq.tables.TaskParticipants.TASK_PARTICIPANTS
import org.example.project.generated.jooq.tables.Tasks.TASKS
import org.example.project.generated.jooq.tables.Users.USERS
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.impl.DSL

class SearchRepository(private val dsl: DSLContext) {

    data class ProjectSearchRow(
        val id: Long,
        val number: String,
        val name: String,
        val description: String?,
        val status: String
    )

    data class TaskSearchRow(
        val id: Long,
        val number: String,
        val title: String,
        val description: String?,
        val status: String,
        val area: String
    )

    data class UserSearchRow(
        val id: Long,
        val login: String,
        val fullName: String
    )

    data class DocumentSearchRow(
        val id: Long,
        val number: String,
        val title: String,
        val docType: String,
        val confidentiality: String
    )

    data class NomenclatureSearchRow(
        val id: Long,
        val code: String,
        val name: String,
        val kind: String
    )

    data class MessageSearchRow(
        val id: Long,
        val taskId: Long,
        val taskNumber: String,
        val taskTitle: String,
        val content: String
    )

    fun searchProjects(query: String, userId: Long, canReadAny: Boolean, limit: Int): List<ProjectSearchRow> {
        val visibility = if (canReadAny) {
            DSL.trueCondition()
        } else {
            PROJECTS.OWNER_ID.eq(userId).or(
                DSL.exists(
                    dsl.selectOne()
                        .from(PROJECT_MEMBERS)
                        .where(PROJECT_MEMBERS.PROJECT_ID.eq(PROJECTS.ID))
                        .and(PROJECT_MEMBERS.USER_ID.eq(userId))
                )
            )
        }
        return dsl.select(PROJECTS.ID, PROJECTS.NUMBER, PROJECTS.NAME, PROJECTS.DESCRIPTION, PROJECTS.STATUS)
            .from(PROJECTS)
            .where(anyContains(query, PROJECTS.NUMBER, PROJECTS.NAME, PROJECTS.FULL_NAME, PROJECTS.DESCRIPTION))
            .and(visibility)
            .orderBy(PROJECTS.ID.desc())
            .limit(limit + 1)
            .fetch { record: Record ->
                ProjectSearchRow(
                    id = record.get(PROJECTS.ID)!!,
                    number = record.get(PROJECTS.NUMBER)!!,
                    name = record.get(PROJECTS.NAME)!!,
                    description = record.get(PROJECTS.DESCRIPTION),
                    status = record.get(PROJECTS.STATUS)!!
                )
            }
    }

    fun searchTasks(
        query: String,
        userId: Long,
        departmentId: Long?,
        canReadAny: Boolean,
        limit: Int
    ): List<TaskSearchRow> {
        val directRoleCondition = TASKS.INITIATOR_ID.eq(userId)
            .or(TASKS.ASSIGNEE_ID.eq(userId))
            .or(TASKS.REVIEWER_ID.eq(userId))

        val participantCondition = DSL.exists(
            dsl.selectOne()
                .from(TASK_PARTICIPANTS)
                .where(TASK_PARTICIPANTS.TASK_ID.eq(TASKS.ID))
                .and(TASK_PARTICIPANTS.USER_ID.eq(userId))
        )

        val projectCondition = TASKS.AREA.eq("PROJECT").and(
            DSL.exists(
                dsl.selectOne()
                    .from(PROJECT_MEMBERS)
                    .where(PROJECT_MEMBERS.PROJECT_ID.eq(TASKS.PROJECT_ID))
                    .and(PROJECT_MEMBERS.USER_ID.eq(userId))
            ).or(
                DSL.exists(
                    dsl.selectOne()
                        .from(PROJECTS)
                        .where(PROJECTS.ID.eq(TASKS.PROJECT_ID))
                        .and(PROJECTS.OWNER_ID.eq(userId))
                )
            )
        )

        val departmentCondition = if (departmentId != null) {
            TASKS.AREA.eq("DEPARTMENT").and(TASKS.DEPARTMENT_ID.eq(departmentId))
        } else {
            DSL.falseCondition()
        }

        val visibility = if (canReadAny) {
            DSL.trueCondition()
        } else {
            directRoleCondition
                .or(participantCondition)
                .or(projectCondition)
                .or(departmentCondition)
        }

        return dsl.select(TASKS.ID, TASKS.NUMBER, TASKS.TITLE, TASKS.DESCRIPTION, TASKS.STATUS, TASKS.AREA)
            .from(TASKS)
            .where(anyContains(query, TASKS.NUMBER, TASKS.TITLE, TASKS.DESCRIPTION))
            .and(visibility)
            .orderBy(TASKS.ID.desc())
            .limit(limit + 1)
            .fetch { record: Record ->
                TaskSearchRow(
                    id = record.get(TASKS.ID)!!,
                    number = record.get(TASKS.NUMBER)!!,
                    title = record.get(TASKS.TITLE)!!,
                    description = record.get(TASKS.DESCRIPTION),
                    status = record.get(TASKS.STATUS)!!,
                    area = record.get(TASKS.AREA)!!
                )
            }
    }

    fun searchUsers(query: String, limit: Int): List<UserSearchRow> {
        return dsl.select(USERS.ID, USERS.LOGIN, USERS.FULL_NAME)
            .from(USERS)
            .where(anyContains(query, USERS.LOGIN, USERS.FULL_NAME))
            .and(USERS.ACTIVE.eq(true))
            .orderBy(USERS.ID.asc())
            .limit(limit + 1)
            .fetch { record: Record ->
                UserSearchRow(
                    id = record.get(USERS.ID)!!,
                    login = record.get(USERS.LOGIN)!!,
                    fullName = record.get(USERS.FULL_NAME)!!
                )
            }
    }

    fun searchDocuments(
        query: String,
        userId: Long,
        canReadAny: Boolean,
        limit: Int
    ): List<DocumentSearchRow> {
        val visibility = if (canReadAny) {
            DSL.trueCondition()
        } else {
            DOCUMENTS.CONFIDENTIALITY.`in`("PUBLIC", "INTERNAL").or(DOCUMENTS.CREATED_BY.eq(userId))
        }
        return dsl.select(DOCUMENTS.ID, DOCUMENTS.NUMBER, DOCUMENTS.NAME, DOCUMENTS.DOC_TYPE, DOCUMENTS.CONFIDENTIALITY)
            .from(DOCUMENTS)
            .where(anyContains(query, DOCUMENTS.NUMBER, DOCUMENTS.NAME, DOCUMENTS.DESCRIPTION))
            .and(visibility)
            .orderBy(DOCUMENTS.ID.desc())
            .limit(limit + 1)
            .fetch { record: Record ->
                DocumentSearchRow(
                    id = record.get(DOCUMENTS.ID)!!,
                    number = record.get(DOCUMENTS.NUMBER)!!,
                    title = record.get(DOCUMENTS.NAME)!!,
                    docType = record.get(DOCUMENTS.DOC_TYPE)!!,
                    confidentiality = record.get(DOCUMENTS.CONFIDENTIALITY)!!
                )
            }
    }

    fun searchNomenclature(query: String, limit: Int): List<NomenclatureSearchRow> {
        return dsl.select(NOMENCLATURE.ID, NOMENCLATURE.CODE, NOMENCLATURE.NAME, NOMENCLATURE.TYPE)
            .from(NOMENCLATURE)
            .where(anyContains(query, NOMENCLATURE.CODE, NOMENCLATURE.NAME, NOMENCLATURE.DESCRIPTION))
            .and(NOMENCLATURE.ARCHIVED.eq(false))
            .orderBy(NOMENCLATURE.CODE.asc())
            .limit(limit + 1)
            .fetch { record: Record ->
                NomenclatureSearchRow(
                    id = record.get(NOMENCLATURE.ID)!!,
                    code = record.get(NOMENCLATURE.CODE)!!,
                    name = record.get(NOMENCLATURE.NAME)!!,
                    kind = record.get(NOMENCLATURE.TYPE)!!
                )
            }
    }

    fun searchTaskMessages(
        query: String,
        userId: Long,
        departmentId: Long?,
        canReadAny: Boolean,
        limit: Int
    ): List<MessageSearchRow> {
        val directRoleCondition = TASKS.INITIATOR_ID.eq(userId)
            .or(TASKS.ASSIGNEE_ID.eq(userId))
            .or(TASKS.REVIEWER_ID.eq(userId))
        val participantCondition = DSL.exists(
            dsl.selectOne()
                .from(TASK_PARTICIPANTS)
                .where(TASK_PARTICIPANTS.TASK_ID.eq(TASKS.ID))
                .and(TASK_PARTICIPANTS.USER_ID.eq(userId))
        )
        val projectCondition = TASKS.AREA.eq("PROJECT").and(
            DSL.exists(
                dsl.selectOne()
                    .from(PROJECT_MEMBERS)
                    .where(PROJECT_MEMBERS.PROJECT_ID.eq(TASKS.PROJECT_ID))
                    .and(PROJECT_MEMBERS.USER_ID.eq(userId))
            ).or(
                DSL.exists(
                    dsl.selectOne()
                        .from(PROJECTS)
                        .where(PROJECTS.ID.eq(TASKS.PROJECT_ID))
                        .and(PROJECTS.OWNER_ID.eq(userId))
                )
            )
        )
        val departmentCondition = if (departmentId != null) {
            TASKS.AREA.eq("DEPARTMENT").and(TASKS.DEPARTMENT_ID.eq(departmentId))
        } else {
            DSL.falseCondition()
        }
        val visibility = if (canReadAny) {
            DSL.trueCondition()
        } else {
            directRoleCondition.or(participantCondition).or(projectCondition).or(departmentCondition)
        }
        return dsl.select(
            TASK_CHAT_MESSAGES.ID,
            TASKS.ID,
            TASKS.NUMBER,
            TASKS.TITLE,
            TASK_CHAT_MESSAGES.CONTENT
        )
            .from(TASK_CHAT_MESSAGES)
            .join(TASKS).on(TASKS.ID.eq(TASK_CHAT_MESSAGES.TASK_ID))
            .where(anyContains(query, TASK_CHAT_MESSAGES.CONTENT))
            .and(TASK_CHAT_MESSAGES.REVOKED_AT.isNull)
            .and(visibility)
            .orderBy(TASK_CHAT_MESSAGES.ID.desc())
            .limit(limit + 1)
            .fetch { record: Record ->
                MessageSearchRow(
                    id = record.get(TASK_CHAT_MESSAGES.ID)!!,
                    taskId = record.get(TASKS.ID)!!,
                    taskNumber = record.get(TASKS.NUMBER)!!,
                    taskTitle = record.get(TASKS.TITLE)!!,
                    content = record.get(TASK_CHAT_MESSAGES.CONTENT)!!
                )
            }
    }

    data class SimpleEntitySearchRow(
        val id: Long,
        val number: String,
        val title: String,
        val subtitle: String?,
        val status: String?
    )

    fun searchPurchaseOrders(query: String, limit: Int): List<SimpleEntitySearchRow> =
        dsl.select(PURCHASE_ORDERS.ID, PURCHASE_ORDERS.NUMBER, SUPPLIERS.NAME, PURCHASE_ORDERS.STATUS)
            .from(PURCHASE_ORDERS)
            .leftJoin(SUPPLIERS).on(SUPPLIERS.ID.eq(PURCHASE_ORDERS.SUPPLIER_ID))
            .where(anyContains(query, PURCHASE_ORDERS.NUMBER, SUPPLIERS.NAME))
            .orderBy(PURCHASE_ORDERS.ID.desc())
            .limit(limit + 1)
            .fetch { r ->
                SimpleEntitySearchRow(
                    id = r.get(PURCHASE_ORDERS.ID)!!,
                    number = r.get(PURCHASE_ORDERS.NUMBER)!!,
                    title = r.get(PURCHASE_ORDERS.NUMBER)!!,
                    subtitle = r.get(SUPPLIERS.NAME),
                    status = r.get(PURCHASE_ORDERS.STATUS)
                )
            }

    fun searchDemands(query: String, limit: Int): List<SimpleEntitySearchRow> =
        dsl.select(DEMANDS.ID, DEMANDS.NUMBER, DEMANDS.STOCKPILE_NOTE, DEMANDS.SOURCE_TYPE, DEMANDS.STATUS)
            .from(DEMANDS)
            .where(anyContains(query, DEMANDS.NUMBER, DEMANDS.STOCKPILE_NOTE))
            .orderBy(DEMANDS.ID.desc())
            .limit(limit + 1)
            .fetch { r ->
                SimpleEntitySearchRow(
                    id = r.get(DEMANDS.ID)!!,
                    number = r.get(DEMANDS.NUMBER)!!,
                    title = r.get(DEMANDS.STOCKPILE_NOTE) ?: r.get(DEMANDS.NUMBER)!!,
                    subtitle = r.get(DEMANDS.SOURCE_TYPE),
                    status = r.get(DEMANDS.STATUS)
                )
            }

    fun searchSuppliers(query: String, limit: Int): List<SimpleEntitySearchRow> =
        dsl.select(SUPPLIERS.ID, SUPPLIERS.NUMBER, SUPPLIERS.NAME, SUPPLIERS.INN, SUPPLIERS.VERIFICATION_STATUS)
            .from(SUPPLIERS)
            .where(anyContains(query, SUPPLIERS.NUMBER, SUPPLIERS.NAME, SUPPLIERS.INN))
            .orderBy(SUPPLIERS.ID.desc())
            .limit(limit + 1)
            .fetch { r ->
                SimpleEntitySearchRow(
                    id = r.get(SUPPLIERS.ID)!!,
                    number = r.get(SUPPLIERS.NUMBER)!!,
                    title = r.get(SUPPLIERS.NAME)!!,
                    subtitle = r.get(SUPPLIERS.INN),
                    status = r.get(SUPPLIERS.VERIFICATION_STATUS)
                )
            }

    fun searchReceipts(query: String, limit: Int): List<SimpleEntitySearchRow> =
        dsl.select(RECEIPTS.ID, RECEIPTS.NUMBER, RECEIPTS.SOURCE_TYPE, RECEIPTS.STATUS)
            .from(RECEIPTS)
            .where(anyContains(query, RECEIPTS.NUMBER, RECEIPTS.SOURCE_DOCUMENT_NO))
            .orderBy(RECEIPTS.ID.desc())
            .limit(limit + 1)
            .fetch { r ->
                SimpleEntitySearchRow(
                    id = r.get(RECEIPTS.ID)!!,
                    number = r.get(RECEIPTS.NUMBER)!!,
                    title = r.get(RECEIPTS.NUMBER)!!,
                    subtitle = r.get(RECEIPTS.SOURCE_TYPE),
                    status = r.get(RECEIPTS.STATUS)
                )
            }

    fun searchProductionOrders(query: String, limit: Int): List<SimpleEntitySearchRow> =
        dsl.select(PRODUCTION_ORDERS.ID, PRODUCTION_ORDERS.NUMBER, PRODUCTION_ORDERS.STATUS)
            .from(PRODUCTION_ORDERS)
            .where(anyContains(query, PRODUCTION_ORDERS.NUMBER))
            .orderBy(PRODUCTION_ORDERS.ID.desc())
            .limit(limit + 1)
            .fetch { r ->
                SimpleEntitySearchRow(
                    id = r.get(PRODUCTION_ORDERS.ID)!!,
                    number = r.get(PRODUCTION_ORDERS.NUMBER)!!,
                    title = r.get(PRODUCTION_ORDERS.NUMBER)!!,
                    subtitle = "Производство",
                    status = r.get(PRODUCTION_ORDERS.STATUS)
                )
            }

    private fun anyContains(query: String, vararg fields: Field<String>): Condition {
        val pattern = "%" + escapeLike(query.lowercase()) + "%"
        return fields
            .map { DSL.lower(it).like(pattern, '\\') }
            .reduce { left, right -> left.or(right) }
    }

    private fun escapeLike(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
    }
}
