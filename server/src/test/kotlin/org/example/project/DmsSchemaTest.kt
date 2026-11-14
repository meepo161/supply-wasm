package org.example.project

import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DmsSchemaTest {

    companion object {
        private val ds = PGSimpleDataSource().apply {
            setUrl("jdbc:postgresql://localhost:5433/erp_test")
            user = "erp"
            password = "erp"
        }

        @BeforeClass
        @JvmStatic
        fun init() {
            TestDatabase.init()
        }

        @AfterClass
        @JvmStatic
        fun destroy() {
            TestDatabase.destroy()
        }
    }

    @Before
    fun reset() {
        TestDatabase.reset()
        ds.connection.use { conn ->
            conn.createStatement().execute(
                """
                TRUNCATE TABLE document_download_log, document_version_history,
                  document_links, documents, document_versions, document_files
                RESTART IDENTITY CASCADE
                """.trimIndent()
            )
        }
    }

    @Test
    fun `document version is unique within card and current version belongs to the same card`() {
        ds.connection.use { conn ->
            val userId = createUser(conn)
            val docId = createDocument(conn, userId)
            val fileId = createFile(conn, userId)
            val versionId = createVersion(conn, docId, fileId, userId, 1)

            // Valid composite FK: current version belongs to the document.
            conn.prepareStatement("UPDATE documents SET current_version_id = ? WHERE id = ?").use { ps ->
                ps.setLong(1, versionId)
                ps.setLong(2, docId)
                assertEquals(1, ps.executeUpdate())
            }

            // Composite FK rejects a version from another document.
            val otherDocId = createDocument(conn, userId, "DOC-OTHER-${UUID.randomUUID()}")
            assertFailsWith<Exception> {
                conn.prepareStatement("UPDATE documents SET current_version_id = ? WHERE id = ?").use { ps ->
                    ps.setLong(1, versionId)
                    ps.setLong(2, otherDocId)
                    ps.executeUpdate()
                }
            }

            // Duplicate version number within the same document is rejected.
            assertFailsWith<Exception> {
                createVersion(conn, docId, fileId, userId, 1)
            }
        }
    }

    @Test
    fun `approved version content is immutable and file storage is append-only`() {
        ds.connection.use { conn ->
            val userId = createUser(conn)
            val docId = createDocument(conn, userId)
            val fileId = createFile(conn, userId)
            val versionId = createVersion(conn, docId, fileId, userId, 1)

            conn.prepareStatement(
                "UPDATE document_versions SET status = 'APPROVED', approved_by = ?, approved_at = NOW() WHERE id = ?"
            ).use { ps ->
                ps.setLong(1, userId)
                ps.setLong(2, versionId)
                assertEquals(1, ps.executeUpdate())
            }

            // Content change on approved version is forbidden.
            assertFailsWith<Exception> {
                conn.prepareStatement("UPDATE document_versions SET file_name = 'hacked.txt' WHERE id = ?").use { ps ->
                    ps.setLong(1, versionId)
                    ps.executeUpdate()
                }
            }

            // Illegal status transition is forbidden.
            assertFailsWith<Exception> {
                conn.prepareStatement("UPDATE document_versions SET status = 'CANCELLED' WHERE id = ?").use { ps ->
                    ps.setLong(1, versionId)
                    ps.executeUpdate()
                }
            }

            // Allowed lifecycle transition: APPROVED -> ACTIVE.
            conn.prepareStatement("UPDATE document_versions SET status = 'ACTIVE' WHERE id = ?").use { ps ->
                ps.setLong(1, versionId)
                assertEquals(1, ps.executeUpdate())
            }

            // File in immutable storage cannot be updated or deleted.
            assertFailsWith<Exception> {
                conn.prepareStatement("UPDATE document_files SET file_name = 'hacked.txt' WHERE id = ?").use { ps ->
                    ps.setLong(1, fileId)
                    ps.executeUpdate()
                }
            }
            assertFailsWith<Exception> {
                conn.prepareStatement("DELETE FROM document_files WHERE id = ?").use { ps ->
                    ps.setLong(1, fileId)
                    ps.executeUpdate()
                }
            }
        }
    }

    @Test
    fun `document link must reference exactly one existing entity`() {
        ds.connection.use { conn ->
            val userId = createUser(conn)
            val docId = createDocument(conn, userId)

            // No target — rejected.
            assertFailsWith<Exception> {
                insertLink(conn, docId, userId, null, null, null, null, null)
            }

            // Two targets — rejected.
            val projectId = createProject(conn, userId)
            val taskId = createTask(conn, userId)
            assertFailsWith<Exception> {
                insertLink(conn, docId, userId, projectId, taskId, null, null, null)
            }

            // Single valid target — accepted.
            insertLink(conn, docId, userId, projectId, null, null, null, null)
        }
    }

    private fun createUser(conn: Connection): Long {
        val login = "dms-" + UUID.randomUUID().toString().take(8)
        conn.prepareStatement(
            """
            INSERT INTO users (login, password_hash, full_name, active, created_at, version)
            VALUES (?, 'hash', 'DMS Tester', true, NOW(), 0)
            RETURNING id
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, login)
            ps.executeQuery().use { rs ->
                assertTrue(rs.next())
                return rs.getLong(1)
            }
        }
    }

    private fun createDocument(conn: Connection, ownerId: Long, number: String? = null): Long {
        val docNumber = number ?: "DOC-${UUID.randomUUID()}"
        conn.prepareStatement(
            """
            INSERT INTO documents (number, name, doc_type, owner_id, status, created_by, created_at, updated_at, version)
            VALUES (?, 'Test Document', 'OTHER', ?, 'DRAFT', ?, NOW(), NOW(), 0)
            RETURNING id
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, docNumber)
            ps.setLong(2, ownerId)
            ps.setLong(3, ownerId)
            ps.executeQuery().use { rs ->
                assertTrue(rs.next())
                return rs.getLong(1)
            }
        }
    }

    private fun createFile(conn: Connection, uploadedBy: Long): Long {
        conn.prepareStatement(
            """
            INSERT INTO document_files (storage_key, file_name, mime_type, size_bytes, sha256, uploaded_by, uploaded_at)
            VALUES (?, 'test.txt', 'text/plain', 4, 'sha256', ?, NOW())
            RETURNING id
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, "dms/test-${UUID.randomUUID()}.txt")
            ps.setLong(2, uploadedBy)
            ps.executeQuery().use { rs ->
                assertTrue(rs.next())
                return rs.getLong(1)
            }
        }
    }

    private fun createVersion(
        conn: Connection,
        documentId: Long,
        fileId: Long,
        createdBy: Long,
        versionNumber: Int
    ): Long {
        conn.prepareStatement(
            """
            INSERT INTO document_versions (
                document_id, version_number, status, file_id, file_name,
                mime_type, size_bytes, sha256, created_by, created_at, version
            )
            VALUES (?, ?, 'DRAFT', ?, 'test.txt', 'text/plain', 4, 'sha256', ?, NOW(), 0)
            RETURNING id
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, documentId)
            ps.setInt(2, versionNumber)
            ps.setLong(3, fileId)
            ps.setLong(4, createdBy)
            ps.executeQuery().use { rs ->
                assertTrue(rs.next())
                return rs.getLong(1)
            }
        }
    }

    private fun createProject(conn: Connection, ownerId: Long): Long {
        conn.prepareStatement(
            """
            INSERT INTO projects (number, name, status, owner_id, created_at, created_by, version)
            VALUES (?, 'DMS Test Project', 'DRAFT', ?, NOW(), ?, 0)
            RETURNING id
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, "P-${UUID.randomUUID()}")
            ps.setLong(2, ownerId)
            ps.setLong(3, ownerId)
            ps.executeQuery().use { rs ->
                assertTrue(rs.next())
                return rs.getLong(1)
            }
        }
    }

    private fun createTask(conn: Connection, ownerId: Long): Long {
        conn.prepareStatement(
            """
            INSERT INTO tasks (number, area, status, type, title, initiator_id, created_at, created_by, version)
            VALUES (?, 'PERSONAL', 'NEW', 'GENERAL', 'DMS Test Task', ?, NOW(), ?, 0)
            RETURNING id
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, "T-${UUID.randomUUID()}")
            ps.setLong(2, ownerId)
            ps.setLong(3, ownerId)
            ps.executeQuery().use { rs ->
                assertTrue(rs.next())
                return rs.getLong(1)
            }
        }
    }

    private fun insertLink(
        conn: Connection,
        documentId: Long,
        createdBy: Long,
        projectId: Long?,
        taskId: Long?,
        productModelId: Long?,
        bomRevisionId: Long?,
        demandId: Long?
    ) {
        conn.prepareStatement(
            """
            INSERT INTO document_links (document_id, project_id, task_id, product_model_id, bom_revision_id, demand_id, created_by, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, NOW())
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, documentId)
            ps.setObject(2, projectId, java.sql.Types.BIGINT)
            ps.setObject(3, taskId, java.sql.Types.BIGINT)
            ps.setObject(4, productModelId, java.sql.Types.BIGINT)
            ps.setObject(5, bomRevisionId, java.sql.Types.BIGINT)
            ps.setObject(6, demandId, java.sql.Types.BIGINT)
            ps.setLong(7, createdBy)
            ps.executeUpdate()
        }
    }
}
