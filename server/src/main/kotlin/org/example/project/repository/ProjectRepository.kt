package org.example.project.repository

import org.example.project.exception.ConflictException
import org.example.project.exception.NotFoundException
import org.example.project.generated.jooq.Tables
import org.example.project.generated.jooq.tables.ProjectMembers.PROJECT_MEMBERS
import org.example.project.generated.jooq.tables.Projects.PROJECTS
import org.example.project.generated.jooq.tables.Users.USERS
import org.example.project.generated.jooq.tables.records.ProjectsRecord
import org.jooq.DSLContext
import org.jooq.Record
import java.time.OffsetDateTime

class ProjectRepository(private val dsl: DSLContext) {

    data class MemberRow(
        val userId: Long,
        val fullName: String,
        val addedAt: OffsetDateTime?,
        val addedBy: Long?
    )

    fun findById(id: Long): ProjectsRecord? {
        return dsl.selectFrom(PROJECTS)
            .where(PROJECTS.ID.eq(id))
            .fetchOne()
    }

    fun userExists(userId: Long): Boolean =
        dsl.fetchExists(dsl.selectOne().from(USERS).where(USERS.ID.eq(userId)))

    fun listForUser(userId: Long): List<ProjectsRecord> {
        return dsl.select(PROJECTS.asterisk())
            .from(PROJECTS)
            .join(PROJECT_MEMBERS).on(PROJECT_MEMBERS.PROJECT_ID.eq(PROJECTS.ID))
            .where(PROJECT_MEMBERS.USER_ID.eq(userId))
            .orderBy(PROJECTS.CREATED_AT.desc())
            .fetchInto(PROJECTS)
    }

    fun listAll(): List<ProjectsRecord> {
        return dsl.selectFrom(PROJECTS)
            .orderBy(PROJECTS.CREATED_AT.desc())
            .fetch()
    }

    fun existsByNumber(number: String): Boolean {
        return dsl.fetchExists(
            dsl.selectOne().from(PROJECTS).where(PROJECTS.NUMBER.eq(number))
        )
    }

    fun create(
        number: String,
        name: String,
        fullName: String?,
        description: String?,
        projectType: String?,
        customer: String?,
        ownerId: Long,
        departmentId: Long?,
        priority: String,
        plannedStartAt: OffsetDateTime?,
        plannedEndAt: OffsetDateTime?,
        createdBy: Long
    ): ProjectsRecord {
        val record = dsl.newRecord(PROJECTS)
        record.number = number
        record.name = name
        record.fullName = fullName
        record.description = description
        record.projectType = projectType
        record.customer = customer
        record.ownerId = ownerId
        record.departmentId = departmentId
        record.priority = priority
        record.plannedStartAt = plannedStartAt
        record.plannedEndAt = plannedEndAt
        record.createdBy = createdBy
        record.store()
        return findById(record.id) ?: throw NotFoundException("project", record.id)
    }

    fun open(
        id: Long,
        openingReason: String,
        openedBy: Long,
        expectedVersion: Long
    ): ProjectsRecord {
        val current = findById(id) ?: throw NotFoundException("project", id)
        if (current.version != expectedVersion) {
            throw ConflictException("Project was modified concurrently")
        }
        if (current.status != "DRAFT") {
            throw ConflictException("Project can only be opened from DRAFT status")
        }
        val updated = dsl.update(PROJECTS)
            .set(PROJECTS.STATUS, "PLANNED")
            .set(PROJECTS.OPENING_REASON, openingReason)
            .set(PROJECTS.OPENED_BY, openedBy)
            .set(PROJECTS.OPENED_AT, OffsetDateTime.now())
            .set(PROJECTS.VERSION, PROJECTS.VERSION.plus(1))
            .where(PROJECTS.ID.eq(id))
            .and(PROJECTS.VERSION.eq(expectedVersion))
            .and(PROJECTS.STATUS.eq("DRAFT"))
            .returning()
            .fetchOne() ?: throw ConflictException("Project was modified concurrently")
        return updated
    }

    fun addMember(projectId: Long, userId: Long, addedBy: Long) {
        dsl.insertInto(PROJECT_MEMBERS)
            .set(PROJECT_MEMBERS.PROJECT_ID, projectId)
            .set(PROJECT_MEMBERS.USER_ID, userId)
            .set(PROJECT_MEMBERS.ADDED_BY, addedBy)
            .onConflict(PROJECT_MEMBERS.PROJECT_ID, PROJECT_MEMBERS.USER_ID)
            .doNothing()
            .execute()
    }

    fun removeMember(projectId: Long, userId: Long) {
        dsl.deleteFrom(PROJECT_MEMBERS)
            .where(PROJECT_MEMBERS.PROJECT_ID.eq(projectId))
            .and(PROJECT_MEMBERS.USER_ID.eq(userId))
            .execute()
    }

    fun isMember(projectId: Long, userId: Long): Boolean {
        return dsl.fetchExists(
            dsl.selectOne()
                .from(PROJECT_MEMBERS)
                .where(PROJECT_MEMBERS.PROJECT_ID.eq(projectId))
                .and(PROJECT_MEMBERS.USER_ID.eq(userId))
        )
    }

    fun listMembers(projectId: Long): List<MemberRow> {
        return dsl.select(PROJECT_MEMBERS.USER_ID, USERS.FULL_NAME, PROJECT_MEMBERS.ADDED_AT, PROJECT_MEMBERS.ADDED_BY)
            .from(PROJECT_MEMBERS)
            .join(USERS).on(USERS.ID.eq(PROJECT_MEMBERS.USER_ID))
            .where(PROJECT_MEMBERS.PROJECT_ID.eq(projectId))
            .orderBy(PROJECT_MEMBERS.ADDED_AT.asc())
            .fetch { record: Record ->
                MemberRow(
                    userId = record.get(PROJECT_MEMBERS.USER_ID)!!,
                    fullName = record.get(USERS.FULL_NAME)!!,
                    addedAt = record.get(PROJECT_MEMBERS.ADDED_AT),
                    addedBy = record.get(PROJECT_MEMBERS.ADDED_BY)
                )
            }
    }

    data class UnfinishedProcess(val type: String, val id: Long, val status: String)

    fun lockById(id: Long): ProjectsRecord? {
        return dsl.selectFrom(PROJECTS)
            .where(PROJECTS.ID.eq(id))
            .forUpdate()
            .fetchOne()
    }

    fun listUnfinishedProcesses(projectId: Long): List<UnfinishedProcess> {
        return dsl.fetch(
            """
            SELECT 'TASK' AS process_type, id AS process_id, status
            FROM tasks
            WHERE project_id = ? AND status NOT IN ('CLOSED', 'CANCELLED')
            UNION ALL
            SELECT 'DEMAND', id, status
            FROM demands
            WHERE project_id = ? AND status NOT IN ('COVERED', 'CANCELLED')
            UNION ALL
            SELECT 'DOCUMENT', d.id, d.status
            FROM document_links AS dl
            JOIN documents AS d ON d.id = dl.document_id
            WHERE dl.project_id = ? AND d.status NOT IN ('ACTIVE', 'ARCHIVED', 'REPLACED')
            ORDER BY process_type, process_id
            """.trimIndent(),
            projectId,
            projectId,
            projectId
        ).map {
            UnfinishedProcess(
                type = it.get("process_type", String::class.java),
                id = it.get("process_id", Long::class.java),
                status = it.get("status", String::class.java)
            )
        }
    }

    fun close(id: Long, expectedVersion: Long): ProjectsRecord {
        return dsl.update(PROJECTS)
            .set(PROJECTS.STATUS, "CLOSED")
            .set(PROJECTS.VERSION, PROJECTS.VERSION.plus(1))
            .where(PROJECTS.ID.eq(id))
            .and(PROJECTS.VERSION.eq(expectedVersion))
            .returning()
            .fetchOne() ?: throw ConflictException("Project was modified concurrently")
    }

    fun requireOpenForRelatedWork(projectId: Long) {
        val project = lockById(projectId) ?: throw NotFoundException("project", projectId)
        if (project.status == "CLOSED" || project.status == "CANCELLED") {
            throw ConflictException("Cannot add work to a closed project")
        }
    }

    fun generateNumber(): String {
        val now = OffsetDateTime.now()
        val year = now.year
        val lockKey = year * 10000L + 1L
        dsl.fetch("SELECT pg_advisory_xact_lock(?)", lockKey)
        val prefix = "P-$year-"
        val maxNumber = dsl.select(PROJECTS.NUMBER)
            .from(PROJECTS)
            .where(PROJECTS.NUMBER.like("$prefix%"))
            .orderBy(PROJECTS.NUMBER.desc())
            .limit(1)
            .fetchOne(PROJECTS.NUMBER)
        val nextIndex = if (maxNumber == null) {
            1
        } else {
            val suffix = maxNumber.removePrefix(prefix)
            suffix.toIntOrNull()?.plus(1) ?: 1
        }
        return String.format("$prefix%05d", nextIndex)
    }
}
