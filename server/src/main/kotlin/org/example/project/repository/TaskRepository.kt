package org.example.project.repository

import org.example.project.generated.jooq.Tables.PROJECT_MEMBERS
import org.example.project.generated.jooq.Tables.TASKS
import org.example.project.generated.jooq.Tables.TASK_PARTICIPANTS
import org.example.project.generated.jooq.Tables.TASK_STATUS_HISTORY
import org.example.project.generated.jooq.tables.records.TasksRecord
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.OffsetDateTime

class TaskRepository(private val dsl: DSLContext) {

    data class ParticipantRow(
        val userId: Long,
        val role: String
    )

    fun findById(id: Long): TasksRecord? {
        return dsl.selectFrom(TASKS)
            .where(TASKS.ID.eq(id))
            .fetchOne()
    }

    fun listAll(): List<TasksRecord> {
        return dsl.selectFrom(TASKS)
            .orderBy(TASKS.CREATED_AT.desc())
            .fetch()
    }

    fun listVisible(userId: Long, departmentId: Long?): List<TasksRecord> {
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
            )
        )

        val departmentCondition = if (departmentId != null) {
            TASKS.AREA.eq("DEPARTMENT").and(TASKS.DEPARTMENT_ID.eq(departmentId))
        } else {
            DSL.falseCondition()
        }

        return dsl.selectFrom(TASKS)
            .where(directRoleCondition.or(participantCondition).or(projectCondition).or(departmentCondition))
            .orderBy(TASKS.CREATED_AT.desc())
            .fetch()
    }

    /**
     * Задачи проекта с серверной фильтрацией (PRJ-14). Видимость повторяет
     * общие правила задач: `read_any` видит всё, участник проекта — задачи
     * своего проекта, остальным доступ закрыт проверкой в сервисе.
     */
    fun listByProject(projectId: Long, status: String? = null): List<TasksRecord> {
        val conditions = mutableListOf(TASKS.PROJECT_ID.eq(projectId))
        status?.let { conditions += TASKS.STATUS.eq(it) }
        return dsl.selectFrom(TASKS)
            .where(conditions)
            .orderBy(TASKS.CREATED_AT.desc())
            .fetch()
    }

    fun countByProject(projectId: Long): Int =
        dsl.fetchCount(TASKS, TASKS.PROJECT_ID.eq(projectId))

    fun countOpenByProject(projectId: Long, closedStatuses: List<String>): Int =
        dsl.fetchCount(
            TASKS,
            TASKS.PROJECT_ID.eq(projectId).and(TASKS.STATUS.notIn(closedStatuses))
        )

    fun create(
        number: String,
        area: String,
        status: String,
        type: String,
        title: String,
        description: String?,
        acceptanceCriteria: String?,
        initiatorId: Long,
        assigneeId: Long?,
        reviewerId: Long?,
        projectId: Long?,
        departmentId: Long?,
        dueDate: OffsetDateTime?,
        plannedStartAt: OffsetDateTime?,
        priority: String,
        createdBy: Long
    ): TasksRecord {
        val record = dsl.newRecord(TASKS)
        record.number = number
        record.area = area
        record.status = status
        record.type = type
        record.title = title
        record.description = description
        record.acceptanceCriteria = acceptanceCriteria
        record.initiatorId = initiatorId
        record.assigneeId = assigneeId
        record.reviewerId = reviewerId
        record.projectId = projectId
        record.departmentId = departmentId
        record.dueDate = dueDate
        record.plannedStartAt = plannedStartAt
        record.priority = priority
        record.createdBy = createdBy
        record.store()
        return record
    }

    fun assign(id: Long, assigneeId: Long, expectedVersion: Long): TasksRecord? {
        return dsl.update(TASKS)
            .set(TASKS.ASSIGNEE_ID, assigneeId)
            .set(TASKS.STATUS, "ASSIGNED")
            .set(TASKS.VERSION, TASKS.VERSION.plus(1))
            .where(TASKS.ID.eq(id))
            .and(TASKS.VERSION.eq(expectedVersion))
            .and(TASKS.STATUS.eq("NEW"))
            .returning()
            .fetchOne()
    }

    fun accept(id: Long, expectedVersion: Long): TasksRecord? {
        return dsl.update(TASKS)
            .set(TASKS.STATUS, "IN_PROGRESS")
            .set(TASKS.VERSION, TASKS.VERSION.plus(1))
            .where(TASKS.ID.eq(id))
            .and(TASKS.VERSION.eq(expectedVersion))
            .and(TASKS.STATUS.eq("ASSIGNED"))
            .returning()
            .fetchOne()
    }

    fun submitResult(id: Long, result: String, expectedVersion: Long): TasksRecord? {
        return dsl.update(TASKS)
            .set(TASKS.RESULT, result)
            .set(TASKS.VERSION, TASKS.VERSION.plus(1))
            .where(TASKS.ID.eq(id))
            .and(TASKS.VERSION.eq(expectedVersion))
            .and(TASKS.STATUS.`in`(listOf("IN_PROGRESS", "REWORK")))
            .returning()
            .fetchOne()
    }

    fun sendToReview(id: Long, expectedVersion: Long): TasksRecord? {
        return dsl.update(TASKS)
            .set(TASKS.STATUS, "ON_REVIEW")
            .set(TASKS.VERSION, TASKS.VERSION.plus(1))
            .where(TASKS.ID.eq(id))
            .and(TASKS.VERSION.eq(expectedVersion))
            .and(TASKS.STATUS.`in`(listOf("IN_PROGRESS", "REWORK")))
            .returning()
            .fetchOne()
    }

    fun reviewDecision(id: Long, decision: String, reason: String?, expectedVersion: Long): TasksRecord? {
        return if (decision == "ACCEPT") {
            dsl.update(TASKS)
                .set(TASKS.STATUS, "CLOSED")
                .set(TASKS.CLOSED_AT, OffsetDateTime.now())
                .set(TASKS.VERSION, TASKS.VERSION.plus(1))
                .where(TASKS.ID.eq(id))
                .and(TASKS.VERSION.eq(expectedVersion))
                .and(TASKS.STATUS.eq("ON_REVIEW"))
                .returning()
                .fetchOne()
        } else {
            dsl.update(TASKS)
                .set(TASKS.STATUS, "REWORK")
                .set(TASKS.VERSION, TASKS.VERSION.plus(1))
                .where(TASKS.ID.eq(id))
                .and(TASKS.VERSION.eq(expectedVersion))
                .and(TASKS.STATUS.eq("ON_REVIEW"))
                .returning()
                .fetchOne()
        }
    }

    fun closeWithoutReview(id: Long, expectedVersion: Long): TasksRecord? {
        return dsl.update(TASKS)
            .set(TASKS.STATUS, "CLOSED")
            .set(TASKS.CLOSED_AT, OffsetDateTime.now())
            .set(TASKS.VERSION, TASKS.VERSION.plus(1))
            .where(TASKS.ID.eq(id))
            .and(TASKS.VERSION.eq(expectedVersion))
            .and(TASKS.STATUS.eq("IN_PROGRESS"))
            .returning()
            .fetchOne()
    }

    fun block(
        id: Long,
        reason: String,
        unblockResponsibleId: Long?,
        blockedBy: Long,
        expectedVersion: Long
    ): TasksRecord? {
        return dsl.update(TASKS)
            .set(TASKS.STATUS, "BLOCKED")
            .set(TASKS.BLOCKED_REASON, reason)
            .set(TASKS.BLOCKED_BY, blockedBy)
            .set(TASKS.BLOCKED_AT, OffsetDateTime.now())
            .set(TASKS.UNBLOCK_RESPONSIBLE_ID, unblockResponsibleId)
            .set(TASKS.VERSION, TASKS.VERSION.plus(1))
            .where(TASKS.ID.eq(id))
            .and(TASKS.VERSION.eq(expectedVersion))
            .and(TASKS.STATUS.notIn("CLOSED", "CANCELLED", "BLOCKED"))
            .returning()
            .fetchOne()
    }

    fun findStatusBeforeLastBlock(taskId: Long): String? {
        return dsl.select(TASK_STATUS_HISTORY.STATUS)
            .from(TASK_STATUS_HISTORY)
            .where(TASK_STATUS_HISTORY.TASK_ID.eq(taskId))
            .and(TASK_STATUS_HISTORY.STATUS.ne("BLOCKED"))
            .orderBy(TASK_STATUS_HISTORY.ID.desc())
            .limit(1)
            .fetchOne(TASK_STATUS_HISTORY.STATUS)
    }

    fun unblock(id: Long, restoredStatus: String, expectedVersion: Long): TasksRecord? {
        return dsl.update(TASKS)
            .set(TASKS.STATUS, restoredStatus)
            .set(TASKS.BLOCKED_REASON, null as String?)
            .set(TASKS.BLOCKED_BY, null as Long?)
            .set(TASKS.BLOCKED_AT, null as OffsetDateTime?)
            .set(TASKS.UNBLOCK_RESPONSIBLE_ID, null as Long?)
            .set(TASKS.VERSION, TASKS.VERSION.plus(1))
            .where(TASKS.ID.eq(id))
            .and(TASKS.VERSION.eq(expectedVersion))
            .and(TASKS.STATUS.eq("BLOCKED"))
            .returning()
            .fetchOne()
    }

    fun reopen(id: Long, expectedVersion: Long): TasksRecord? {
        return dsl.update(TASKS)
            .set(TASKS.STATUS, "NEW")
            .set(TASKS.CLOSED_AT, null as OffsetDateTime?)
            .set(TASKS.VERSION, TASKS.VERSION.plus(1))
            .where(TASKS.ID.eq(id))
            .and(TASKS.VERSION.eq(expectedVersion))
            .and(TASKS.STATUS.`in`(listOf("CLOSED", "CANCELLED")))
            .returning()
            .fetchOne()
    }

    fun cancel(id: Long, expectedVersion: Long): TasksRecord? {
        return dsl.update(TASKS)
            .set(TASKS.STATUS, "CANCELLED")
            .set(TASKS.VERSION, TASKS.VERSION.plus(1))
            .where(TASKS.ID.eq(id))
            .and(TASKS.VERSION.eq(expectedVersion))
            .and(TASKS.STATUS.notIn("CLOSED", "CANCELLED"))
            .returning()
            .fetchOne()
    }

    fun addStatusHistory(taskId: Long, status: String, changedBy: Long, reason: String?) {
        dsl.insertInto(TASK_STATUS_HISTORY)
            .set(TASK_STATUS_HISTORY.TASK_ID, taskId)
            .set(TASK_STATUS_HISTORY.STATUS, status)
            .set(TASK_STATUS_HISTORY.CHANGED_BY, changedBy)
            .set(TASK_STATUS_HISTORY.REASON, reason)
            .execute()
    }

    fun addParticipant(taskId: Long, userId: Long, role: String) {
        dsl.insertInto(TASK_PARTICIPANTS)
            .set(TASK_PARTICIPANTS.TASK_ID, taskId)
            .set(TASK_PARTICIPANTS.USER_ID, userId)
            .set(TASK_PARTICIPANTS.ROLE, role)
            .onConflict(TASK_PARTICIPANTS.TASK_ID, TASK_PARTICIPANTS.USER_ID, TASK_PARTICIPANTS.ROLE)
            .doNothing()
            .execute()
    }

    fun removeParticipant(taskId: Long, userId: Long) {
        dsl.deleteFrom(TASK_PARTICIPANTS)
            .where(TASK_PARTICIPANTS.TASK_ID.eq(taskId))
            .and(TASK_PARTICIPANTS.USER_ID.eq(userId))
            .execute()
    }

    fun removeParticipantsByRole(taskId: Long, role: String) {
        dsl.deleteFrom(TASK_PARTICIPANTS)
            .where(TASK_PARTICIPANTS.TASK_ID.eq(taskId))
            .and(TASK_PARTICIPANTS.ROLE.eq(role))
            .execute()
    }

    fun listParticipants(taskId: Long): List<ParticipantRow> {
        return dsl.select(TASK_PARTICIPANTS.USER_ID, TASK_PARTICIPANTS.ROLE)
            .from(TASK_PARTICIPANTS)
            .where(TASK_PARTICIPANTS.TASK_ID.eq(taskId))
            .fetch { record ->
                ParticipantRow(
                    userId = record.get(TASK_PARTICIPANTS.USER_ID)!!,
                    role = record.get(TASK_PARTICIPANTS.ROLE)!!
                )
            }
    }

    fun generateNumber(): String {
        val now = OffsetDateTime.now()
        val year = now.year
        val lockKey = year * 10000L + 2L
        dsl.fetch("SELECT pg_advisory_xact_lock(?)", lockKey)
        val prefix = "T-$year-"
        val maxNumber = dsl.select(TASKS.NUMBER)
            .from(TASKS)
            .where(TASKS.NUMBER.like("$prefix%"))
            .orderBy(TASKS.NUMBER.desc())
            .limit(1)
            .fetchOne(TASKS.NUMBER)
        val nextIndex = if (maxNumber == null) {
            1
        } else {
            val suffix = maxNumber.removePrefix(prefix)
            suffix.toIntOrNull()?.plus(1) ?: 1
        }
        return String.format("$prefix%05d", nextIndex)
    }
}
