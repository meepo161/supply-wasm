package org.example.project.repository

import org.example.project.generated.jooq.tables.Demands.DEMANDS
import org.example.project.generated.jooq.tables.ProductModels.PRODUCT_MODELS
import org.example.project.generated.jooq.tables.ProductRevisions.PRODUCT_REVISIONS
import org.example.project.generated.jooq.tables.Projects.PROJECTS
import org.example.project.generated.jooq.tables.Tasks.TASKS
import org.jooq.DSLContext
import org.jooq.Record
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * DMS-таблицы (V14) пока не входят в сгенерированный jOOQ-код,
 * поэтому репозиторий работает через plain SQL поверх DSLContext.
 * Проверки существования связанных сущностей используют сгенерированные таблицы.
 */
class DocumentRepository(private val dsl: DSLContext) {

    data class DocumentRow(
        val id: Long,
        val number: String,
        val name: String,
        val docType: String,
        val description: String?,
        val authorId: Long?,
        val ownerId: Long,
        val status: String,
        val confidentiality: String,
        val retentionUntil: LocalDate?,
        val currentVersionId: Long?,
        val createdBy: Long,
        val createdAt: OffsetDateTime,
        val updatedAt: OffsetDateTime,
        val version: Long
    )

    data class DocumentVersionRow(
        val id: Long,
        val documentId: Long,
        val versionNumber: Int,
        val status: String,
        val changeReason: String?,
        val fileId: Long?,
        val fileName: String,
        val mimeType: String?,
        val sizeBytes: Long?,
        val sha256: String?,
        val approvedBy: Long?,
        val approvedAt: OffsetDateTime?,
        val createdBy: Long,
        val createdAt: OffsetDateTime,
        val version: Long
    )

    data class DocumentLinkRow(
        val id: Long,
        val documentId: Long,
        val linkType: String,
        val targetId: Long,
        val createdBy: Long,
        val createdAt: OffsetDateTime
    )

    fun generateNumber(): String {
        val year = OffsetDateTime.now().year
        val lockKey = year * 10000L + 3L
        dsl.fetch("SELECT pg_advisory_xact_lock(?)", lockKey)
        val prefix = "D-$year-"
        val maxNumber = dsl.fetch("SELECT number FROM documents WHERE number LIKE ? ORDER BY number DESC LIMIT 1", "$prefix%")
            .map { it.get("number", String::class.java) }
            .firstOrNull()
        val nextIndex = maxNumber?.removePrefix(prefix)?.toIntOrNull()?.plus(1) ?: 1
        return String.format("$prefix%05d", nextIndex)
    }

    fun insertDocument(
        number: String,
        name: String,
        docType: String,
        description: String?,
        authorId: Long,
        ownerId: Long,
        confidentiality: String,
        retentionUntil: LocalDate?,
        createdBy: Long
    ): Long {
        return dsl.fetch(
            """
            INSERT INTO documents (number, name, doc_type, description, author_id, owner_id, status, confidentiality, retention_until, created_by)
            VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?)
            RETURNING id
            """.trimIndent(),
            number, name, docType, description, authorId, ownerId, confidentiality, retentionUntil, createdBy
        ).single().get("id", Long::class.java)
    }

    fun findDocumentById(id: Long): DocumentRow? {
        return dsl.fetch("SELECT * FROM documents WHERE id = ?", id)
            .map { mapDocument(it) }
            .firstOrNull()
    }

    fun listVisible(userId: Long, hasReadAny: Boolean): List<DocumentRow> {
        return if (hasReadAny) {
            dsl.fetch("SELECT * FROM documents ORDER BY created_at DESC")
        } else {
            dsl.fetch(
                "SELECT * FROM documents WHERE confidentiality IN ('PUBLIC', 'INTERNAL') OR owner_id = ? ORDER BY created_at DESC",
                userId
            )
        }.map { mapDocument(it) }
    }

    fun updateDocument(
        id: Long,
        name: String?,
        description: String?,
        confidentiality: String?,
        retentionUntil: LocalDate?,
        expectedVersion: Long
    ): Int {
        return dsl.query(
            """
            UPDATE documents
            SET name = COALESCE(?, name),
                description = COALESCE(?, description),
                confidentiality = COALESCE(?, confidentiality),
                retention_until = COALESCE(?, retention_until),
                updated_at = NOW(),
                version = version + 1
            WHERE id = ? AND version = ?
            """.trimIndent(),
            name, description, confidentiality, retentionUntil, id, expectedVersion
        ).execute()
    }

    fun touchDocument(id: Long): Int {
        return dsl.query("UPDATE documents SET updated_at = NOW(), version = version + 1 WHERE id = ?", id).execute()
    }

    fun setDocumentStatus(id: Long, status: String): Int {
        return dsl.query(
            "UPDATE documents SET status = ?, updated_at = NOW(), version = version + 1 WHERE id = ?",
            status, id
        ).execute()
    }

    fun setCurrentVersion(id: Long, currentVersionId: Long, status: String): Int {
        return dsl.query(
            "UPDATE documents SET current_version_id = ?, status = ?, updated_at = NOW(), version = version + 1 WHERE id = ?",
            currentVersionId, status, id
        ).execute()
    }

    fun nextVersionNumber(documentId: Long): Int {
        return dsl.fetch(
            "SELECT COALESCE(MAX(version_number), 0) + 1 AS next_number FROM document_versions WHERE document_id = ?",
            documentId
        ).single().get("next_number", Int::class.java)
    }

    fun countVersions(documentId: Long): Int {
        return dsl.fetch("SELECT COUNT(*) AS cnt FROM document_versions WHERE document_id = ?", documentId)
            .single().get("cnt", Long::class.java).toInt()
    }

    fun insertFile(
        storageKey: String,
        fileName: String,
        mimeType: String?,
        sizeBytes: Long,
        sha256: String?,
        uploadedBy: Long
    ): Long {
        return dsl.fetch(
            """
            INSERT INTO document_files (storage_key, file_name, mime_type, size_bytes, sha256, uploaded_by)
            VALUES (?, ?, ?, ?, ?, ?)
            RETURNING id
            """.trimIndent(),
            storageKey, fileName, mimeType, sizeBytes, sha256, uploadedBy
        ).single().get("id", Long::class.java)
    }

    fun findFileStorageKey(fileId: Long): String? {
        return dsl.fetch("SELECT storage_key FROM document_files WHERE id = ?", fileId)
            .map { it.get("storage_key", String::class.java) }
            .firstOrNull()
    }

    fun insertVersion(
        documentId: Long,
        versionNumber: Int,
        changeReason: String?,
        fileId: Long,
        fileName: String,
        mimeType: String?,
        sizeBytes: Long,
        sha256: String?,
        createdBy: Long
    ): Long {
        return dsl.fetch(
            """
            INSERT INTO document_versions (document_id, version_number, status, change_reason, file_id, file_name, mime_type, size_bytes, sha256, created_by)
            VALUES (?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """.trimIndent(),
            documentId, versionNumber, changeReason, fileId, fileName, mimeType, sizeBytes, sha256, createdBy
        ).single().get("id", Long::class.java)
    }

    fun findVersionById(versionId: Long): DocumentVersionRow? {
        return dsl.fetch("SELECT * FROM document_versions WHERE id = ?", versionId)
            .map { mapVersion(it) }
            .firstOrNull()
    }

    fun findVersion(documentId: Long, versionId: Long): DocumentVersionRow? {
        return dsl.fetch("SELECT * FROM document_versions WHERE id = ? AND document_id = ?", versionId, documentId)
            .map { mapVersion(it) }
            .firstOrNull()
    }

    fun listVersions(documentId: Long): List<DocumentVersionRow> {
        return dsl.fetch("SELECT * FROM document_versions WHERE document_id = ? ORDER BY version_number", documentId)
            .map { mapVersion(it) }
    }

    fun approveVersion(documentId: Long, versionId: Long, approvedBy: Long, expectedVersion: Long): Int {
        return dsl.query(
            """
            UPDATE document_versions
            SET status = 'APPROVED', approved_by = ?, approved_at = NOW(), version = version + 1
            WHERE id = ? AND document_id = ? AND status = 'DRAFT' AND version = ?
            """.trimIndent(),
            approvedBy, versionId, documentId, expectedVersion
        ).execute()
    }

    fun activateVersion(documentId: Long, versionId: Long, expectedVersion: Long): Int {
        return dsl.query(
            """
            UPDATE document_versions
            SET status = 'ACTIVE', version = version + 1
            WHERE id = ? AND document_id = ? AND status = 'APPROVED' AND version = ?
            """.trimIndent(),
            versionId, documentId, expectedVersion
        ).execute()
    }

    fun replaceActiveVersions(documentId: Long, exceptVersionId: Long): List<Long> {
        return dsl.fetch(
            """
            UPDATE document_versions
            SET status = 'REPLACED', version = version + 1
            WHERE document_id = ? AND status = 'ACTIVE' AND id <> ?
            RETURNING id
            """.trimIndent(),
            documentId, exceptVersionId
        ).map { it.get("id", Long::class.java) }
    }

    fun cancelVersion(documentId: Long, versionId: Long, expectedVersion: Long): Int {
        return dsl.query(
            """
            UPDATE document_versions
            SET status = 'CANCELLED', version = version + 1
            WHERE id = ? AND document_id = ? AND status = 'DRAFT' AND version = ?
            """.trimIndent(),
            versionId, documentId, expectedVersion
        ).execute()
    }

    fun addHistory(
        versionId: Long,
        action: String,
        fromStatus: String?,
        toStatus: String?,
        reason: String?,
        changedBy: Long
    ) {
        dsl.query(
            """
            INSERT INTO document_version_history (version_id, action, from_status, to_status, reason, changed_by)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            versionId, action, fromStatus, toStatus, reason, changedBy
        ).execute()
    }

    fun insertLink(documentId: Long, linkType: String, targetId: Long, createdBy: Long): Long {
        val column = when (linkType) {
            "PROJECT" -> "project_id"
            "TASK" -> "task_id"
            "PRODUCT_MODEL" -> "product_model_id"
            "BOM_REVISION" -> "bom_revision_id"
            "DEMAND" -> "demand_id"
            else -> throw IllegalArgumentException("Unsupported link type: $linkType")
        }
        return dsl.fetch(
            "INSERT INTO document_links (document_id, $column, created_by) VALUES (?, ?, ?) RETURNING id",
            documentId, targetId, createdBy
        ).single().get("id", Long::class.java)
    }

    fun findLink(documentId: Long, linkId: Long): DocumentLinkRow? {
        return dsl.fetch("$LINK_SELECT WHERE document_id = ? AND id = ?", documentId, linkId)
            .map { mapLink(it) }
            .firstOrNull()
    }

    fun listLinks(documentId: Long): List<DocumentLinkRow> {
        return dsl.fetch("$LINK_SELECT WHERE document_id = ? ORDER BY id", documentId)
            .map { mapLink(it) }
    }

    fun deleteLink(documentId: Long, linkId: Long): Int {
        return dsl.query("DELETE FROM document_links WHERE document_id = ? AND id = ?", documentId, linkId).execute()
    }

    fun linkTargetExists(linkType: String, targetId: Long): Boolean {
        return when (linkType) {
            "PROJECT" -> dsl.fetchExists(dsl.selectOne().from(PROJECTS).where(PROJECTS.ID.eq(targetId)))
            "TASK" -> dsl.fetchExists(dsl.selectOne().from(TASKS).where(TASKS.ID.eq(targetId)))
            "PRODUCT_MODEL" -> dsl.fetchExists(dsl.selectOne().from(PRODUCT_MODELS).where(PRODUCT_MODELS.ID.eq(targetId)))
            "BOM_REVISION" -> dsl.fetchExists(dsl.selectOne().from(PRODUCT_REVISIONS).where(PRODUCT_REVISIONS.ID.eq(targetId)))
            "DEMAND" -> dsl.fetchExists(dsl.selectOne().from(DEMANDS).where(DEMANDS.ID.eq(targetId)))
            else -> false
        }
    }

    fun logDownload(versionId: Long, userId: Long, ipAddress: String?, userAgent: String?) {
        dsl.query(
            """
            INSERT INTO document_download_log (version_id, user_id, ip_address, user_agent)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            versionId, userId, ipAddress, userAgent
        ).execute()
    }

    private fun mapDocument(record: Record): DocumentRow = DocumentRow(
        id = record.get("id", Long::class.java),
        number = record.get("number", String::class.java),
        name = record.get("name", String::class.java),
        docType = record.get("doc_type", String::class.java),
        description = record.get("description", String::class.java),
        authorId = record.get("author_id", Long::class.javaObjectType),
        ownerId = record.get("owner_id", Long::class.java),
        status = record.get("status", String::class.java),
        confidentiality = record.get("confidentiality", String::class.java),
        retentionUntil = record.get("retention_until", LocalDate::class.java),
        currentVersionId = record.get("current_version_id", Long::class.javaObjectType),
        createdBy = record.get("created_by", Long::class.java),
        createdAt = record.get("created_at", OffsetDateTime::class.java),
        updatedAt = record.get("updated_at", OffsetDateTime::class.java),
        version = record.get("version", Long::class.java)
    )

    private fun mapVersion(record: Record): DocumentVersionRow = DocumentVersionRow(
        id = record.get("id", Long::class.java),
        documentId = record.get("document_id", Long::class.java),
        versionNumber = record.get("version_number", Int::class.java),
        status = record.get("status", String::class.java),
        changeReason = record.get("change_reason", String::class.java),
        fileId = record.get("file_id", Long::class.javaObjectType),
        fileName = record.get("file_name", String::class.java),
        mimeType = record.get("mime_type", String::class.java),
        sizeBytes = record.get("size_bytes", Long::class.javaObjectType),
        sha256 = record.get("sha256", String::class.java),
        approvedBy = record.get("approved_by", Long::class.javaObjectType),
        approvedAt = record.get("approved_at", OffsetDateTime::class.java),
        createdBy = record.get("created_by", Long::class.java),
        createdAt = record.get("created_at", OffsetDateTime::class.java),
        version = record.get("version", Long::class.java)
    )

    private fun mapLink(record: Record): DocumentLinkRow = DocumentLinkRow(
        id = record.get("id", Long::class.java),
        documentId = record.get("document_id", Long::class.java),
        linkType = record.get("link_type", String::class.java),
        targetId = record.get("target_id", Long::class.java),
        createdBy = record.get("created_by", Long::class.java),
        createdAt = record.get("created_at", OffsetDateTime::class.java)
    )

    companion object {
        private const val LINK_SELECT = """
            SELECT id, document_id,
                CASE
                    WHEN project_id IS NOT NULL THEN 'PROJECT'
                    WHEN task_id IS NOT NULL THEN 'TASK'
                    WHEN product_model_id IS NOT NULL THEN 'PRODUCT_MODEL'
                    WHEN bom_revision_id IS NOT NULL THEN 'BOM_REVISION'
                    ELSE 'DEMAND'
                END AS link_type,
                COALESCE(project_id, task_id, product_model_id, bom_revision_id, demand_id) AS target_id,
                created_by, created_at
            FROM document_links
        """
    }
}
