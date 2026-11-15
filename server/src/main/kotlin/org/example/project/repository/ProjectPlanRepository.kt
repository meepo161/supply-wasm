package org.example.project.repository

import org.example.project.generated.jooq.tables.ProjectMilestones.PROJECT_MILESTONES
import org.example.project.generated.jooq.tables.ProjectRisks.PROJECT_RISKS
import org.example.project.generated.jooq.tables.ProjectStages.PROJECT_STAGES
import org.example.project.generated.jooq.tables.Tasks.TASKS
import org.example.project.generated.jooq.tables.records.ProjectMilestonesRecord
import org.example.project.generated.jooq.tables.records.ProjectRisksRecord
import org.example.project.generated.jooq.tables.records.ProjectStagesRecord
import org.jooq.Condition
import org.jooq.DSLContext
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Этапы, контрольные точки и риски проекта (5.3, 5.5 ТЗ; PRJ-08/09/10).
 *
 * Репозиторий не проверяет права: это делает сервис, у которого есть доступ к
 * участию в проекте и к полномочиям пользователя.
 */
class ProjectPlanRepository(private val dsl: DSLContext) {

    // --- Этапы -------------------------------------------------------------

    fun findStageById(id: Long): ProjectStagesRecord? =
        dsl.selectFrom(PROJECT_STAGES).where(PROJECT_STAGES.ID.eq(id)).fetchOne()

    fun lockStageById(id: Long): ProjectStagesRecord? =
        dsl.selectFrom(PROJECT_STAGES).where(PROJECT_STAGES.ID.eq(id)).forUpdate().fetchOne()

    fun listStages(projectId: Long, status: String? = null): List<ProjectStagesRecord> {
        val conditions = mutableListOf(PROJECT_STAGES.PROJECT_ID.eq(projectId))
        status?.let { conditions += PROJECT_STAGES.STATUS.eq(it) }
        return dsl.selectFrom(PROJECT_STAGES)
            .where(conditions)
            .orderBy(PROJECT_STAGES.SEQUENCE.asc(), PROJECT_STAGES.ID.asc())
            .fetch()
    }

    fun countStages(projectId: Long): Int =
        dsl.fetchCount(PROJECT_STAGES, PROJECT_STAGES.PROJECT_ID.eq(projectId))

    fun countStagesByStatus(projectId: Long, status: String): Int =
        dsl.fetchCount(
            PROJECT_STAGES,
            PROJECT_STAGES.PROJECT_ID.eq(projectId).and(PROJECT_STAGES.STATUS.eq(status))
        )

    /** Этап, работа по которому идёт сейчас; при нескольких берётся первый по порядку. */
    fun currentStage(projectId: Long): ProjectStagesRecord? =
        dsl.selectFrom(PROJECT_STAGES)
            .where(PROJECT_STAGES.PROJECT_ID.eq(projectId))
            .and(PROJECT_STAGES.STATUS.eq("IN_PROGRESS"))
            .orderBy(PROJECT_STAGES.SEQUENCE.asc())
            .limit(1)
            .fetchOne()

    fun nextStageSequence(projectId: Long): Int =
        dsl.select(org.jooq.impl.DSL.max(PROJECT_STAGES.SEQUENCE))
            .from(PROJECT_STAGES)
            .where(PROJECT_STAGES.PROJECT_ID.eq(projectId))
            .fetchOne(0, Int::class.javaObjectType) ?: 0

    fun insertStage(
        projectId: Long,
        name: String,
        description: String?,
        sequence: Int,
        plannedStartAt: OffsetDateTime?,
        plannedEndAt: OffsetDateTime?,
        responsibleId: Long?,
        completionCriteria: String?,
        createdBy: Long
    ): ProjectStagesRecord {
        val record = dsl.newRecord(PROJECT_STAGES)
        record.projectId = projectId
        record.name = name
        record.description = description
        record.sequence = sequence
        record.status = "PLANNED"
        record.plannedStartAt = plannedStartAt
        record.plannedEndAt = plannedEndAt
        record.responsibleId = responsibleId
        record.completionCriteria = completionCriteria
        record.createdBy = createdBy
        record.store()
        return findStageById(record.id)!!
    }

    fun updateStage(
        id: Long,
        name: String,
        description: String?,
        sequence: Int,
        plannedStartAt: OffsetDateTime?,
        plannedEndAt: OffsetDateTime?,
        responsibleId: Long?,
        completionCriteria: String?,
        expectedVersion: Long
    ): ProjectStagesRecord? =
        dsl.update(PROJECT_STAGES)
            .set(PROJECT_STAGES.NAME, name)
            .set(PROJECT_STAGES.DESCRIPTION, description)
            .set(PROJECT_STAGES.SEQUENCE, sequence)
            .set(PROJECT_STAGES.PLANNED_START_AT, plannedStartAt)
            .set(PROJECT_STAGES.PLANNED_END_AT, plannedEndAt)
            .set(PROJECT_STAGES.RESPONSIBLE_ID, responsibleId)
            .set(PROJECT_STAGES.COMPLETION_CRITERIA, completionCriteria)
            .set(PROJECT_STAGES.VERSION, PROJECT_STAGES.VERSION.plus(1))
            .where(PROJECT_STAGES.ID.eq(id))
            .and(PROJECT_STAGES.VERSION.eq(expectedVersion))
            .returning()
            .fetchOne()

    fun updateStageStatus(
        id: Long,
        status: String,
        actualStartAt: OffsetDateTime?,
        actualEndAt: OffsetDateTime?,
        cancelReason: String?,
        expectedVersion: Long
    ): ProjectStagesRecord? =
        dsl.update(PROJECT_STAGES)
            .set(PROJECT_STAGES.STATUS, status)
            .set(PROJECT_STAGES.ACTUAL_START_AT, actualStartAt)
            .set(PROJECT_STAGES.ACTUAL_END_AT, actualEndAt)
            .set(PROJECT_STAGES.CANCEL_REASON, cancelReason)
            .set(PROJECT_STAGES.VERSION, PROJECT_STAGES.VERSION.plus(1))
            .where(PROJECT_STAGES.ID.eq(id))
            .and(PROJECT_STAGES.VERSION.eq(expectedVersion))
            .returning()
            .fetchOne()

    fun deleteStage(id: Long, expectedVersion: Long): Int =
        dsl.deleteFrom(PROJECT_STAGES)
            .where(PROJECT_STAGES.ID.eq(id))
            .and(PROJECT_STAGES.VERSION.eq(expectedVersion))
            .execute()

    fun countMilestonesByStage(stageId: Long): Int =
        dsl.fetchCount(PROJECT_MILESTONES, PROJECT_MILESTONES.STAGE_ID.eq(stageId))

    fun countRisksByStage(stageId: Long): Int =
        dsl.fetchCount(PROJECT_RISKS, PROJECT_RISKS.STAGE_ID.eq(stageId))

    // --- Контрольные точки -------------------------------------------------

    fun findMilestoneById(id: Long): ProjectMilestonesRecord? =
        dsl.selectFrom(PROJECT_MILESTONES).where(PROJECT_MILESTONES.ID.eq(id)).fetchOne()

    fun listMilestones(
        projectId: Long,
        stageId: Long? = null,
        status: String? = null
    ): List<ProjectMilestonesRecord> {
        val conditions = mutableListOf<Condition>(PROJECT_MILESTONES.PROJECT_ID.eq(projectId))
        stageId?.let { conditions += PROJECT_MILESTONES.STAGE_ID.eq(it) }
        status?.let { conditions += PROJECT_MILESTONES.STATUS.eq(it) }
        return dsl.selectFrom(PROJECT_MILESTONES)
            .where(conditions)
            .orderBy(PROJECT_MILESTONES.PLANNED_AT.asc().nullsLast(), PROJECT_MILESTONES.ID.asc())
            .fetch()
    }

    fun countMilestones(projectId: Long): Int =
        dsl.fetchCount(PROJECT_MILESTONES, PROJECT_MILESTONES.PROJECT_ID.eq(projectId))

    fun countMilestonesByStatus(projectId: Long, status: String): Int =
        dsl.fetchCount(
            PROJECT_MILESTONES,
            PROJECT_MILESTONES.PROJECT_ID.eq(projectId).and(PROJECT_MILESTONES.STATUS.eq(status))
        )

    /** Ближайшая ещё не наступившая контрольная точка по плановой дате. */
    fun nextMilestone(projectId: Long): ProjectMilestonesRecord? =
        dsl.selectFrom(PROJECT_MILESTONES)
            .where(PROJECT_MILESTONES.PROJECT_ID.eq(projectId))
            .and(PROJECT_MILESTONES.STATUS.eq("PLANNED"))
            .and(PROJECT_MILESTONES.PLANNED_AT.isNotNull)
            .orderBy(PROJECT_MILESTONES.PLANNED_AT.asc())
            .limit(1)
            .fetchOne()

    fun insertMilestone(
        projectId: Long,
        stageId: Long?,
        name: String,
        description: String?,
        plannedAt: OffsetDateTime?,
        responsibleId: Long?,
        createdBy: Long
    ): ProjectMilestonesRecord {
        val record = dsl.newRecord(PROJECT_MILESTONES)
        record.projectId = projectId
        record.stageId = stageId
        record.name = name
        record.description = description
        record.plannedAt = plannedAt
        record.responsibleId = responsibleId
        record.status = "PLANNED"
        record.createdBy = createdBy
        record.store()
        return findMilestoneById(record.id)!!
    }

    fun updateMilestone(
        id: Long,
        name: String,
        stageId: Long?,
        description: String?,
        plannedAt: OffsetDateTime?,
        responsibleId: Long?,
        note: String?,
        expectedVersion: Long
    ): ProjectMilestonesRecord? =
        dsl.update(PROJECT_MILESTONES)
            .set(PROJECT_MILESTONES.NAME, name)
            .set(PROJECT_MILESTONES.STAGE_ID, stageId)
            .set(PROJECT_MILESTONES.DESCRIPTION, description)
            .set(PROJECT_MILESTONES.PLANNED_AT, plannedAt)
            .set(PROJECT_MILESTONES.RESPONSIBLE_ID, responsibleId)
            .set(PROJECT_MILESTONES.NOTE, note)
            .set(PROJECT_MILESTONES.VERSION, PROJECT_MILESTONES.VERSION.plus(1))
            .where(PROJECT_MILESTONES.ID.eq(id))
            .and(PROJECT_MILESTONES.VERSION.eq(expectedVersion))
            .returning()
            .fetchOne()

    fun updateMilestoneStatus(
        id: Long,
        status: String,
        actualAt: OffsetDateTime?,
        note: String?,
        expectedVersion: Long
    ): ProjectMilestonesRecord? =
        dsl.update(PROJECT_MILESTONES)
            .set(PROJECT_MILESTONES.STATUS, status)
            .set(PROJECT_MILESTONES.ACTUAL_AT, actualAt)
            .set(PROJECT_MILESTONES.NOTE, note)
            .set(PROJECT_MILESTONES.VERSION, PROJECT_MILESTONES.VERSION.plus(1))
            .where(PROJECT_MILESTONES.ID.eq(id))
            .and(PROJECT_MILESTONES.VERSION.eq(expectedVersion))
            .returning()
            .fetchOne()

    fun deleteMilestone(id: Long, expectedVersion: Long): Int =
        dsl.deleteFrom(PROJECT_MILESTONES)
            .where(PROJECT_MILESTONES.ID.eq(id))
            .and(PROJECT_MILESTONES.VERSION.eq(expectedVersion))
            .execute()

    // --- Риски -------------------------------------------------------------

    fun findRiskById(id: Long): ProjectRisksRecord? =
        dsl.selectFrom(PROJECT_RISKS).where(PROJECT_RISKS.ID.eq(id)).fetchOne()

    fun listRisks(projectId: Long, status: String? = null): List<ProjectRisksRecord> {
        val conditions = mutableListOf(PROJECT_RISKS.PROJECT_ID.eq(projectId))
        status?.let { conditions += PROJECT_RISKS.STATUS.eq(it) }
        return dsl.selectFrom(PROJECT_RISKS)
            .where(conditions)
            .orderBy(PROJECT_RISKS.STATUS.asc(), PROJECT_RISKS.ID.desc())
            .fetch()
    }

    fun countOpenRisks(projectId: Long): Int =
        dsl.fetchCount(
            PROJECT_RISKS,
            PROJECT_RISKS.PROJECT_ID.eq(projectId).and(PROJECT_RISKS.STATUS.in("OPEN", "MITIGATING"))
        )

    fun countCriticalRisks(projectId: Long): Int =
        dsl.fetchCount(
            PROJECT_RISKS,
            PROJECT_RISKS.PROJECT_ID.eq(projectId)
                .and(PROJECT_RISKS.STATUS.in("OPEN", "MITIGATING"))
                .and(PROJECT_RISKS.CRITICALITY.eq("CRITICAL"))
        )

    fun insertRisk(
        projectId: Long,
        title: String,
        description: String?,
        category: String,
        probability: String,
        impact: String,
        criticality: String,
        responsibleId: Long?,
        responsePlan: String?,
        dueDate: LocalDate?,
        stageId: Long?,
        taskId: Long?,
        createdBy: Long
    ): ProjectRisksRecord {
        val record = dsl.newRecord(PROJECT_RISKS)
        record.projectId = projectId
        record.title = title
        record.description = description
        record.category = category
        record.probability = probability
        record.impact = impact
        record.criticality = criticality
        record.status = "OPEN"
        record.responsibleId = responsibleId
        record.responsePlan = responsePlan
        record.dueDate = dueDate
        record.stageId = stageId
        record.taskId = taskId
        record.createdBy = createdBy
        record.store()
        return findRiskById(record.id)!!
    }

    fun updateRisk(
        id: Long,
        title: String,
        description: String?,
        category: String,
        probability: String,
        impact: String,
        criticality: String,
        responsibleId: Long?,
        responsePlan: String?,
        dueDate: LocalDate?,
        stageId: Long?,
        taskId: Long?,
        expectedVersion: Long
    ): ProjectRisksRecord? =
        dsl.update(PROJECT_RISKS)
            .set(PROJECT_RISKS.TITLE, title)
            .set(PROJECT_RISKS.DESCRIPTION, description)
            .set(PROJECT_RISKS.CATEGORY, category)
            .set(PROJECT_RISKS.PROBABILITY, probability)
            .set(PROJECT_RISKS.IMPACT, impact)
            .set(PROJECT_RISKS.CRITICALITY, criticality)
            .set(PROJECT_RISKS.RESPONSIBLE_ID, responsibleId)
            .set(PROJECT_RISKS.RESPONSE_PLAN, responsePlan)
            .set(PROJECT_RISKS.DUE_DATE, dueDate)
            .set(PROJECT_RISKS.STAGE_ID, stageId)
            .set(PROJECT_RISKS.TASK_ID, taskId)
            .set(PROJECT_RISKS.VERSION, PROJECT_RISKS.VERSION.plus(1))
            .where(PROJECT_RISKS.ID.eq(id))
            .and(PROJECT_RISKS.VERSION.eq(expectedVersion))
            .returning()
            .fetchOne()

    fun updateRiskStatus(
        id: Long,
        status: String,
        outcome: String?,
        closedAt: OffsetDateTime?,
        closedBy: Long?,
        expectedVersion: Long
    ): ProjectRisksRecord? =
        dsl.update(PROJECT_RISKS)
            .set(PROJECT_RISKS.STATUS, status)
            .set(PROJECT_RISKS.OUTCOME, outcome)
            .set(PROJECT_RISKS.CLOSED_AT, closedAt)
            .set(PROJECT_RISKS.CLOSED_BY, closedBy)
            .set(PROJECT_RISKS.VERSION, PROJECT_RISKS.VERSION.plus(1))
            .where(PROJECT_RISKS.ID.eq(id))
            .and(PROJECT_RISKS.VERSION.eq(expectedVersion))
            .returning()
            .fetchOne()

    fun deleteRisk(id: Long, expectedVersion: Long): Int =
        dsl.deleteFrom(PROJECT_RISKS)
            .where(PROJECT_RISKS.ID.eq(id))
            .and(PROJECT_RISKS.VERSION.eq(expectedVersion))
            .execute()

    // --- Проверки существования -------------------------------------------

    /** Задача существует и относится к этому же проекту. */
    fun taskBelongsToProject(taskId: Long, projectId: Long): Boolean =
        dsl.fetchExists(
            dsl.selectOne().from(TASKS)
                .where(TASKS.ID.eq(taskId))
                .and(TASKS.PROJECT_ID.eq(projectId))
        )
}
