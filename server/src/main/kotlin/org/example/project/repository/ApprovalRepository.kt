package org.example.project.repository

import org.jooq.DSLContext
import java.time.OffsetDateTime

/**
 * Таблицы согласований (V18) пока не входят в сгенерированный jOOQ-код,
 * поэтому репозиторий работает через plain SQL поверх DSLContext
 * (конвенция DocumentRepository).
 */
class ApprovalRepository(private val dsl: DSLContext) {

    data class TemplateRow(
        val id: Long,
        val code: String,
        val name: String,
        val targetType: String,
        val active: Boolean,
        val createdBy: Long,
        val createdAt: OffsetDateTime?,
        val version: Long
    )

    data class TemplateStageRow(
        val id: Long,
        val templateId: Long,
        val stageNo: Int,
        val name: String,
        val decisionPolicy: String
    )

    data class AssigneeRow(
        val id: Long,
        val stageId: Long,
        val assigneeType: String,
        val userId: Long?,
        val userFullName: String?,
        val roleId: Long?,
        val roleName: String?
    )

    data class RouteRow(
        val id: Long,
        val templateId: Long,
        val targetType: String,
        val targetId: Long,
        val targetVersion: Long,
        val status: String,
        val currentStageNo: Int,
        val idempotencyKey: String?,
        val idempotencyRequestHash: String?,
        val startedBy: Long,
        val startedAt: OffsetDateTime?,
        val completedAt: OffsetDateTime?,
        val version: Long
    )

    data class RouteStageRow(
        val id: Long,
        val routeId: Long,
        val stageNo: Int,
        val name: String,
        val decisionPolicy: String,
        val status: String,
        val startedAt: OffsetDateTime?,
        val completedAt: OffsetDateTime?
    )

    data class DecisionRow(
        val id: Long,
        val routeId: Long,
        val routeStageId: Long,
        val assigneeId: Long?,
        val decidedBy: Long,
        val decidedByFullName: String?,
        val decision: String,
        val comment: String?,
        val createdAt: OffsetDateTime?
    )

    fun insertTemplate(code: String, name: String, targetType: String, createdBy: Long): Long {
        return dsl.fetch(
            """
            INSERT INTO approval_templates (code, name, target_type, created_by)
            VALUES (?, ?, ?, ?)
            RETURNING id
            """.trimIndent(),
            code, name, targetType, createdBy
        ).first().get("id", Long::class.java)!!
    }

    fun findTemplateById(id: Long): TemplateRow? {
        return dsl.fetch(
            """
            SELECT id, code, name, target_type, active, created_by, created_at, version
            FROM approval_templates WHERE id = ?
            """.trimIndent(),
            id
        ).map { mapTemplate(it) }.firstOrNull()
    }

    fun listTemplates(targetType: String?): List<TemplateRow> {
        val sql = StringBuilder(
            """
            SELECT id, code, name, target_type, active, created_by, created_at, version
            FROM approval_templates
            """.trimIndent()
        )
        val bindings = mutableListOf<Any>()
        if (targetType != null) {
            sql.append(" WHERE target_type = ?")
            bindings.add(targetType)
        }
        sql.append(" ORDER BY code")
        return dsl.fetch(sql.toString(), *bindings.toTypedArray()).map { mapTemplate(it) }
    }

    fun insertTemplateStage(templateId: Long, stageNo: Int, name: String, decisionPolicy: String): Long {
        return dsl.fetch(
            """
            INSERT INTO approval_template_stages (template_id, stage_no, name, decision_policy)
            VALUES (?, ?, ?, ?)
            RETURNING id
            """.trimIndent(),
            templateId, stageNo, name, decisionPolicy
        ).first().get("id", Long::class.java)!!
    }

    fun insertTemplateAssignee(stageId: Long, assigneeType: String, userId: Long?, roleId: Long?): Long {
        return dsl.fetch(
            """
            INSERT INTO approval_template_assignees (stage_id, assignee_type, user_id, role_id)
            VALUES (?, ?, ?, ?)
            RETURNING id
            """.trimIndent(),
            stageId, assigneeType, userId, roleId
        ).first().get("id", Long::class.java)!!
    }

    fun listTemplateStages(templateId: Long): List<TemplateStageRow> {
        return dsl.fetch(
            """
            SELECT id, template_id, stage_no, name, decision_policy
            FROM approval_template_stages
            WHERE template_id = ?
            ORDER BY stage_no
            """.trimIndent(),
            templateId
        ).map { mapTemplateStage(it) }
    }

    fun listTemplateAssignees(templateId: Long): List<AssigneeRow> {
        return dsl.fetch(
            """
            SELECT a.id, a.stage_id, a.assignee_type, a.user_id, u.full_name AS user_full_name,
                   a.role_id, r.name AS role_name
            FROM approval_template_assignees a
            JOIN approval_template_stages s ON s.id = a.stage_id
            LEFT JOIN users u ON u.id = a.user_id
            LEFT JOIN roles r ON r.id = a.role_id
            WHERE s.template_id = ?
            ORDER BY a.id
            """.trimIndent(),
            templateId
        ).map { mapAssignee(it) }
    }

    fun insertRoute(
        templateId: Long,
        targetType: String,
        targetId: Long,
        targetVersion: Long,
        currentStageNo: Int,
        idempotencyKey: String?,
        idempotencyRequestHash: String?,
        startedBy: Long
    ): Long {
        return dsl.fetch(
            """
            INSERT INTO approval_routes
                (template_id, target_type, target_id, target_version, status, current_stage_no,
                 idempotency_key, idempotency_request_hash, started_by, created_by)
            VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?)
            RETURNING id
            """.trimIndent(),
            templateId, targetType, targetId, targetVersion, currentStageNo,
            idempotencyKey, idempotencyRequestHash, startedBy, startedBy
        ).first().get("id", Long::class.java)!!
    }

    fun findRouteById(id: Long): RouteRow? {
        return dsl.fetch("$ROUTE_SELECT WHERE id = ?", id).map { mapRoute(it) }.firstOrNull()
    }

    fun lockRouteById(id: Long): RouteRow? {
        return dsl.fetch("$ROUTE_SELECT WHERE id = ? FOR UPDATE", id).map { mapRoute(it) }.firstOrNull()
    }

    fun findRouteByIdempotencyKey(key: String, userId: Long): RouteRow? {
        return dsl.fetch(
            "$ROUTE_SELECT WHERE idempotency_key = ? AND started_by = ?",
            key, userId
        ).map { mapRoute(it) }.firstOrNull()
    }

    fun hasActiveRoute(targetType: String, targetId: Long): Boolean {
        return dsl.fetch(
            """
            SELECT 1 AS present FROM approval_routes
            WHERE target_type = ? AND target_id = ? AND status = 'ACTIVE'
            LIMIT 1
            """.trimIndent(),
            targetType, targetId
        ).isNotEmpty
    }

    fun listRoutesByTarget(targetType: String, targetId: Long): List<RouteRow> {
        return dsl.fetch(
            "$ROUTE_SELECT WHERE target_type = ? AND target_id = ? ORDER BY id DESC",
            targetType, targetId
        ).map { mapRoute(it) }
    }

    fun updateRouteState(
        id: Long,
        expectedVersion: Long,
        status: String,
        currentStageNo: Int,
        completed: Boolean
    ): Boolean {
        val sql = if (completed) {
            """
            UPDATE approval_routes
            SET status = ?, current_stage_no = ?, completed_at = NOW(), version = version + 1
            WHERE id = ? AND version = ? AND status = 'ACTIVE'
            """.trimIndent()
        } else {
            """
            UPDATE approval_routes
            SET status = ?, current_stage_no = ?, version = version + 1
            WHERE id = ? AND version = ? AND status = 'ACTIVE'
            """.trimIndent()
        }
        return dsl.execute(sql, status, currentStageNo, id, expectedVersion) == 1
    }

    fun insertRouteStage(
        routeId: Long,
        stageNo: Int,
        name: String,
        decisionPolicy: String,
        status: String
    ): Long {
        val started = status == "ACTIVE"
        return dsl.fetch(
            """
            INSERT INTO approval_route_stages (route_id, stage_no, name, decision_policy, status, started_at)
            VALUES (?, ?, ?, ?, ?, CASE WHEN ? THEN NOW() ELSE NULL END)
            RETURNING id
            """.trimIndent(),
            routeId, stageNo, name, decisionPolicy, status, started
        ).first().get("id", Long::class.java)!!
    }

    fun insertRouteAssignee(routeStageId: Long, assigneeType: String, userId: Long?, roleId: Long?, createdBy: Long): Long {
        return dsl.fetch(
            """
            INSERT INTO approval_route_assignees (route_stage_id, assignee_type, user_id, role_id, created_by)
            VALUES (?, ?, ?, ?, ?)
            RETURNING id
            """.trimIndent(),
            routeStageId, assigneeType, userId, roleId, createdBy
        ).first().get("id", Long::class.java)!!
    }

    fun findRouteStageById(stageId: Long): RouteStageRow? {
        return dsl.fetch(
            """
            SELECT id, route_id, stage_no, name, decision_policy, status, started_at, completed_at
            FROM approval_route_stages WHERE id = ?
            """.trimIndent(),
            stageId
        ).map { mapRouteStage(it) }.firstOrNull()
    }

    fun findRouteStage(routeId: Long, stageNo: Int): RouteStageRow? {
        return dsl.fetch(
            """
            SELECT id, route_id, stage_no, name, decision_policy, status, started_at, completed_at
            FROM approval_route_stages WHERE route_id = ? AND stage_no = ?
            """.trimIndent(),
            routeId, stageNo
        ).map { mapRouteStage(it) }.firstOrNull()
    }

    fun listRouteStages(routeId: Long): List<RouteStageRow> {
        return dsl.fetch(
            """
            SELECT id, route_id, stage_no, name, decision_policy, status, started_at, completed_at
            FROM approval_route_stages WHERE route_id = ?
            ORDER BY stage_no
            """.trimIndent(),
            routeId
        ).map { mapRouteStage(it) }
    }

    fun activateRouteStage(stageId: Long): Boolean {
        return dsl.execute(
            """
            UPDATE approval_route_stages
            SET status = 'ACTIVE', started_at = NOW()
            WHERE id = ? AND status = 'PENDING'
            """.trimIndent(),
            stageId
        ) == 1
    }

    fun completeRouteStage(stageId: Long, toStatus: String): Boolean {
        return dsl.execute(
            """
            UPDATE approval_route_stages
            SET status = ?, completed_at = NOW()
            WHERE id = ? AND status IN ('PENDING', 'ACTIVE')
            """.trimIndent(),
            toStatus, stageId
        ) == 1
    }

    fun cancelOpenStages(routeId: Long) {
        dsl.execute(
            """
            UPDATE approval_route_stages
            SET status = 'CANCELLED', completed_at = NOW()
            WHERE route_id = ? AND status IN ('PENDING', 'ACTIVE')
            """.trimIndent(),
            routeId
        )
    }

    fun listRouteAssignees(routeId: Long): List<AssigneeRow> {
        return dsl.fetch(
            """
            SELECT a.id, a.route_stage_id AS stage_id, a.assignee_type, a.user_id,
                   u.full_name AS user_full_name, a.role_id, r.name AS role_name
            FROM approval_route_assignees a
            JOIN approval_route_stages s ON s.id = a.route_stage_id
            LEFT JOIN users u ON u.id = a.user_id
            LEFT JOIN roles r ON r.id = a.role_id
            WHERE s.route_id = ?
            ORDER BY a.id
            """.trimIndent(),
            routeId
        ).map { mapAssignee(it) }
    }

    fun listStageAssignees(stageId: Long): List<AssigneeRow> {
        return dsl.fetch(
            """
            SELECT a.id, a.route_stage_id AS stage_id, a.assignee_type, a.user_id,
                   u.full_name AS user_full_name, a.role_id, r.name AS role_name
            FROM approval_route_assignees a
            LEFT JOIN users u ON u.id = a.user_id
            LEFT JOIN roles r ON r.id = a.role_id
            WHERE a.route_stage_id = ?
            ORDER BY a.id
            """.trimIndent(),
            stageId
        ).map { mapAssignee(it) }
    }

    fun findRouteAssignee(assigneeId: Long): AssigneeRow? {
        return dsl.fetch(
            """
            SELECT a.id, a.route_stage_id AS stage_id, a.assignee_type, a.user_id,
                   u.full_name AS user_full_name, a.role_id, r.name AS role_name
            FROM approval_route_assignees a
            LEFT JOIN users u ON u.id = a.user_id
            LEFT JOIN roles r ON r.id = a.role_id
            WHERE a.id = ?
            """.trimIndent(),
            assigneeId
        ).map { mapAssignee(it) }.firstOrNull()
    }

    fun deleteRouteAssignee(assigneeId: Long): Int {
        return dsl.execute("DELETE FROM approval_route_assignees WHERE id = ?", assigneeId)
    }

    fun insertDecision(
        routeId: Long,
        routeStageId: Long,
        assigneeId: Long?,
        decidedBy: Long,
        decision: String,
        comment: String?
    ): Long {
        return dsl.fetch(
            """
            INSERT INTO approval_decisions (route_id, route_stage_id, assignee_id, decided_by, decision, comment)
            VALUES (?, ?, ?, ?, ?, ?)
            RETURNING id
            """.trimIndent(),
            routeId, routeStageId, assigneeId, decidedBy, decision, comment
        ).first().get("id", Long::class.java)!!
    }

    fun listDecisions(routeId: Long): List<DecisionRow> {
        return dsl.fetch(
            """
            SELECT d.id, d.route_id, d.route_stage_id, d.assignee_id, d.decided_by,
                   u.full_name AS decided_by_full_name, d.decision, d.comment, d.created_at
            FROM approval_decisions d
            LEFT JOIN users u ON u.id = d.decided_by
            WHERE d.route_id = ?
            ORDER BY d.id
            """.trimIndent(),
            routeId
        ).map { mapDecision(it) }
    }

    fun existsDecisionByUser(stageId: Long, userId: Long): Boolean {
        return dsl.fetch(
            "SELECT 1 AS present FROM approval_decisions WHERE route_stage_id = ? AND decided_by = ? LIMIT 1",
            stageId, userId
        ).isNotEmpty
    }

    fun existsDecisionForAssignee(assigneeId: Long): Boolean {
        return dsl.fetch(
            "SELECT 1 AS present FROM approval_decisions WHERE assignee_id = ? LIMIT 1",
            assigneeId
        ).isNotEmpty
    }

    fun countStageAssigneesWithoutApproval(stageId: Long): Long {
        return dsl.fetch(
            """
            SELECT count(*) AS missing
            FROM approval_route_assignees a
            WHERE a.route_stage_id = ?
              AND NOT EXISTS (
                  SELECT 1 FROM approval_decisions d
                  WHERE d.assignee_id = a.id AND d.decision = 'APPROVE'
              )
            """.trimIndent(),
            stageId
        ).first().get("missing", Long::class.java)!!
    }

    fun userHasRole(userId: Long, roleId: Long): Boolean {
        return dsl.fetch(
            "SELECT 1 AS present FROM user_roles WHERE user_id = ? AND role_id = ? LIMIT 1",
            userId, roleId
        ).isNotEmpty
    }

    private fun mapTemplate(record: org.jooq.Record): TemplateRow = TemplateRow(
        id = record.get("id", Long::class.java)!!,
        code = record.get("code", String::class.java)!!,
        name = record.get("name", String::class.java)!!,
        targetType = record.get("target_type", String::class.java)!!,
        active = record.get("active", Boolean::class.java)!!,
        createdBy = record.get("created_by", Long::class.java)!!,
        createdAt = record.get("created_at", OffsetDateTime::class.java),
        version = record.get("version", Long::class.java)!!
    )

    private fun mapTemplateStage(record: org.jooq.Record): TemplateStageRow = TemplateStageRow(
        id = record.get("id", Long::class.java)!!,
        templateId = record.get("template_id", Long::class.java)!!,
        stageNo = record.get("stage_no", Int::class.java)!!,
        name = record.get("name", String::class.java)!!,
        decisionPolicy = record.get("decision_policy", String::class.java)!!
    )

    private fun mapAssignee(record: org.jooq.Record): AssigneeRow = AssigneeRow(
        id = record.get("id", Long::class.java)!!,
        stageId = record.get("stage_id", Long::class.java)!!,
        assigneeType = record.get("assignee_type", String::class.java)!!,
        userId = record.get("user_id", Long::class.javaObjectType),
        userFullName = record.get("user_full_name", String::class.java),
        roleId = record.get("role_id", Long::class.javaObjectType),
        roleName = record.get("role_name", String::class.java)
    )

    private fun mapRoute(record: org.jooq.Record): RouteRow = RouteRow(
        id = record.get("id", Long::class.java)!!,
        templateId = record.get("template_id", Long::class.java)!!,
        targetType = record.get("target_type", String::class.java)!!,
        targetId = record.get("target_id", Long::class.java)!!,
        targetVersion = record.get("target_version", Long::class.java)!!,
        status = record.get("status", String::class.java)!!,
        currentStageNo = record.get("current_stage_no", Int::class.java)!!,
        idempotencyKey = record.get("idempotency_key", String::class.java),
        idempotencyRequestHash = record.get("idempotency_request_hash", String::class.java),
        startedBy = record.get("started_by", Long::class.java)!!,
        startedAt = record.get("started_at", OffsetDateTime::class.java),
        completedAt = record.get("completed_at", OffsetDateTime::class.java),
        version = record.get("version", Long::class.java)!!
    )

    private fun mapRouteStage(record: org.jooq.Record): RouteStageRow = RouteStageRow(
        id = record.get("id", Long::class.java)!!,
        routeId = record.get("route_id", Long::class.java)!!,
        stageNo = record.get("stage_no", Int::class.java)!!,
        name = record.get("name", String::class.java)!!,
        decisionPolicy = record.get("decision_policy", String::class.java)!!,
        status = record.get("status", String::class.java)!!,
        startedAt = record.get("started_at", OffsetDateTime::class.java),
        completedAt = record.get("completed_at", OffsetDateTime::class.java)
    )

    private fun mapDecision(record: org.jooq.Record): DecisionRow = DecisionRow(
        id = record.get("id", Long::class.java)!!,
        routeId = record.get("route_id", Long::class.java)!!,
        routeStageId = record.get("route_stage_id", Long::class.java)!!,
        assigneeId = record.get("assignee_id", Long::class.javaObjectType),
        decidedBy = record.get("decided_by", Long::class.java)!!,
        decidedByFullName = record.get("decided_by_full_name", String::class.java),
        decision = record.get("decision", String::class.java)!!,
        comment = record.get("comment", String::class.java),
        createdAt = record.get("created_at", OffsetDateTime::class.java)
    )

    private companion object {
        const val ROUTE_SELECT = """
            SELECT id, template_id, target_type, target_id, target_version, status,
                   current_stage_no, idempotency_key, idempotency_request_hash,
                   started_by, started_at, completed_at, version
            FROM approval_routes
        """
    }
}
