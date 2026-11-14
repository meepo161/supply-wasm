package org.example.project.repository

import org.example.project.generated.jooq.tables.ProjectMembers.PROJECT_MEMBERS
import org.example.project.generated.jooq.tables.Projects.PROJECTS
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
