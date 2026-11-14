package org.example.project.repository

import org.example.project.generated.jooq.tables.Attachments.ATTACHMENTS
import org.example.project.generated.jooq.tables.Users.USERS
import org.jooq.DSLContext
import org.jooq.Record
import java.time.OffsetDateTime

class AttachmentRepository(private val dsl: DSLContext) {

    data class AttachmentRow(
        val id: Long,
        val entityType: String,
        val entityId: Long,
        val fileName: String,
        val storagePath: String,
        val sizeBytes: Long,
        val mimeType: String?,
        val sha256: String?,
        val uploadedBy: Long,
        val uploadedByName: String?,
        val uploadedAt: OffsetDateTime
    )

    fun findById(id: Long): AttachmentRow? {
        return baseQuery()
            .where(ATTACHMENTS.ID.eq(id))
            .fetchOne { mapRow(it) }
    }

    fun listForEntity(entityType: String, entityId: Long): List<AttachmentRow> {
        return baseQuery()
            .where(ATTACHMENTS.ENTITY_TYPE.eq(entityType))
            .and(ATTACHMENTS.ENTITY_ID.eq(entityId))
            .orderBy(ATTACHMENTS.ID.asc())
            .fetch { mapRow(it) }
    }

    fun insert(
        entityType: String,
        entityId: Long,
        fileName: String,
        storagePath: String,
        sizeBytes: Long,
        mimeType: String?,
        sha256: String?,
        uploadedBy: Long
    ): Long {
        val record = dsl.newRecord(ATTACHMENTS)
        record.entityType = entityType
        record.entityId = entityId
        record.fileName = fileName
        record.storagePath = storagePath
        record.sizeBytes = sizeBytes
        record.mimeType = mimeType
        record.sha256 = sha256
        record.uploadedBy = uploadedBy
        record.store()
        return record.id!!
    }

    fun delete(id: Long): Boolean {
        return dsl.deleteFrom(ATTACHMENTS)
            .where(ATTACHMENTS.ID.eq(id))
            .execute() > 0
    }

    private fun baseQuery() = dsl.select(
        ATTACHMENTS.ID,
        ATTACHMENTS.ENTITY_TYPE,
        ATTACHMENTS.ENTITY_ID,
        ATTACHMENTS.FILE_NAME,
        ATTACHMENTS.STORAGE_PATH,
        ATTACHMENTS.SIZE_BYTES,
        ATTACHMENTS.MIME_TYPE,
        ATTACHMENTS.SHA256,
        ATTACHMENTS.UPLOADED_BY,
        USERS.FULL_NAME,
        ATTACHMENTS.UPLOADED_AT
    )
        .from(ATTACHMENTS)
        .leftJoin(USERS).on(USERS.ID.eq(ATTACHMENTS.UPLOADED_BY))

    private fun mapRow(record: Record): AttachmentRow = AttachmentRow(
        id = record.get(ATTACHMENTS.ID)!!,
        entityType = record.get(ATTACHMENTS.ENTITY_TYPE)!!,
        entityId = record.get(ATTACHMENTS.ENTITY_ID)!!,
        fileName = record.get(ATTACHMENTS.FILE_NAME)!!,
        storagePath = record.get(ATTACHMENTS.STORAGE_PATH)!!,
        sizeBytes = record.get(ATTACHMENTS.SIZE_BYTES) ?: 0L,
        mimeType = record.get(ATTACHMENTS.MIME_TYPE),
        sha256 = record.get(ATTACHMENTS.SHA256),
        uploadedBy = record.get(ATTACHMENTS.UPLOADED_BY)!!,
        uploadedByName = record.get(USERS.FULL_NAME),
        uploadedAt = record.get(ATTACHMENTS.UPLOADED_AT)!!
    )
}
