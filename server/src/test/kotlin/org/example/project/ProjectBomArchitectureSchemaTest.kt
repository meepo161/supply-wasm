package org.example.project

import org.junit.BeforeClass
import org.junit.Test
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProjectBomArchitectureSchemaTest {

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
    }

    @Test
    fun `project can link a reusable model and its approved BOM revision only`() {
        TestDatabase.reset()
        ds.connection.use { conn ->
            val userId = createUser(conn)
            val projectId = createProject(conn, userId)
            val modelId = createModel(conn, userId)
            val otherModelId = createModel(conn, userId)
            val approvedRevisionId = createRevision(conn, modelId, userId, "APPROVED")
            val draftRevisionId = createRevision(conn, modelId, userId, "DRAFT")
            val otherRevisionId = createRevision(conn, otherModelId, userId, "ACTIVE")

            val projectProductId = conn.prepareStatement(
                """
                INSERT INTO project_products (project_id, product_model_id, created_by)
                VALUES (?, ?, ?)
                RETURNING id
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, projectId)
                statement.setLong(2, modelId)
                statement.setLong(3, userId)
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    result.getLong(1)
                }
            }

            conn.prepareStatement(
                """
                INSERT INTO project_bom_revisions (project_product_id, bom_revision_id, created_by)
                VALUES (?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, projectProductId)
                statement.setLong(2, approvedRevisionId)
                statement.setLong(3, userId)
                assertEquals(1, statement.executeUpdate())
            }

            assertFailsWith<Exception> {
                insertProjectBomRevision(conn, projectProductId, draftRevisionId, userId)
            }
            assertFailsWith<Exception> {
                insertProjectBomRevision(conn, projectProductId, otherRevisionId, userId)
            }
        }
    }

    private fun insertProjectBomRevision(
        connection: Connection,
        projectProductId: Long,
        revisionId: Long,
        createdBy: Long
    ) {
        connection.prepareStatement(
            """
            INSERT INTO project_bom_revisions (project_product_id, bom_revision_id, created_by)
            VALUES (?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, projectProductId)
            statement.setLong(2, revisionId)
            statement.setLong(3, createdBy)
            statement.executeUpdate()
        }
    }

    private fun createUser(connection: Connection): Long = connection.prepareStatement(
        """
        INSERT INTO users (login, password_hash, full_name, active, created_at, version)
        VALUES (?, 'hash', 'Project BOM tester', TRUE, NOW(), 0)
        RETURNING id
        """.trimIndent()
    ).use { statement ->
        statement.setString(1, "project-bom-${UUID.randomUUID().toString().take(8)}")
        statement.executeQuery().use { result ->
            assertTrue(result.next())
            result.getLong(1)
        }
    }

    private fun createProject(connection: Connection, userId: Long): Long = connection.prepareStatement(
        """
        INSERT INTO projects (number, name, status, owner_id, created_at, created_by, version)
        VALUES (?, 'Project BOM test', 'PLANNED', ?, NOW(), ?, 0)
        RETURNING id
        """.trimIndent()
    ).use { statement ->
        statement.setString(1, "P-${UUID.randomUUID()}")
        statement.setLong(2, userId)
        statement.setLong(3, userId)
        statement.executeQuery().use { result ->
            assertTrue(result.next())
            result.getLong(1)
        }
    }

    private fun createModel(connection: Connection, userId: Long): Long = connection.prepareStatement(
        """
        INSERT INTO product_models (code, name, development_status, created_by, created_at, version)
        VALUES (?, 'Project BOM model', 'DEVELOPMENT', ?, NOW(), 0)
        RETURNING id
        """.trimIndent()
    ).use { statement ->
        statement.setString(1, "MODEL-${UUID.randomUUID()}")
        statement.setLong(2, userId)
        statement.executeQuery().use { result ->
            assertTrue(result.next())
            result.getLong(1)
        }
    }

    private fun createRevision(connection: Connection, modelId: Long, userId: Long, status: String): Long {
        val approved = status in setOf("APPROVED", "ACTIVE")
        return connection.prepareStatement(
            """
            INSERT INTO product_revisions (
                product_model_id, revision_number, status, approved_by, approved_at, created_by, created_at, version
            ) VALUES (?, ?, ?, ?, ${if (approved) "NOW()" else "NULL"}, ?, NOW(), 0)
            RETURNING id
            """.trimIndent()
        ).use { statement ->
            statement.setLong(1, modelId)
            statement.setString(2, "REV-${UUID.randomUUID()}")
            statement.setString(3, status)
            if (approved) statement.setLong(4, userId) else statement.setNull(4, java.sql.Types.BIGINT)
            statement.setLong(5, userId)
            statement.executeQuery().use { result ->
                assertTrue(result.next())
                result.getLong(1)
            }
        }
    }
}
