package org.example.project

import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Schema-level checks for V20/V21 production core.
 *
 * These tests were written but NOT executed (no running PostgreSQL/Flyway was available
 * in this database-agent session). They must pass before PROD-ORDER/INSTANCE/QUALITY
 * schema work is considered done.
 */
class ProductionSchemaTest {

    companion object {
        private val ds = PGSimpleDataSource().apply {
            setUrl("${TestDatabase.TEST_URL}")
            user = "erp"
            password = "erp"
        }

        @BeforeClass
        @JvmStatic
        fun init() {
            TestDatabase.init()
        }
    }

    @Before
    fun reset() {
        TestDatabase.reset()
        ds.connection.use { conn ->
            conn.createStatement().execute(
                """
                TRUNCATE TABLE nonconformity_history, nonconformities,
                  instance_remark_history, instance_remarks, instance_tests,
                  instance_software, instance_documents, instance_components,
                  product_instances, production_stage_assignees,
                  production_order_stages, production_orders
                RESTART IDENTITY CASCADE
                """.trimIndent()
            )
        }
    }

    @Test
    fun `production order requires APPROVED or ACTIVE BOM revision`() {
        ds.connection.use { conn ->
            val userId = createUser(conn)
            val projectId = createProject(conn, userId)
            val modelId = createProductModel(conn, userId)
            val draftRevId = createProductRevision(conn, modelId, userId, "DRAFT")
            val approvedRevId = createProductRevision(conn, modelId, userId, "APPROVED", "rev-2")

            assertFailsWith<Exception> {
                createProductionOrder(conn, projectId, modelId, draftRevId, userId)
            }

            val orderId = createProductionOrder(conn, projectId, modelId, approvedRevId, userId)
            assertTrue(orderId > 0)
        }
    }

    @Test
    fun `instance serial number is unique and test result is immutable`() {
        ds.connection.use { conn ->
            val userId = createUser(conn)
            val projectId = createProject(conn, userId)
            val modelId = createProductModel(conn, userId)
            val revId = createProductRevision(conn, modelId, userId, "APPROVED")
            val orderId = createProductionOrder(conn, projectId, modelId, revId, userId)

            val instanceId1 = createInstance(conn, orderId, projectId, modelId, revId, userId, "SN-001")
            assertFailsWith<Exception> {
                createInstance(conn, orderId, projectId, modelId, revId, userId, "SN-001")
            }

            val testId = createTest(conn, instanceId1, orderId, userId, "GOOD")
            assertFailsWith<Exception> {
                conn.prepareStatement("UPDATE instance_tests SET result = 'NOT_GOOD' WHERE id = ?").use { ps ->
                    ps.setLong(1, testId)
                    ps.executeUpdate()
                }
            }

            // Superseding test is allowed.
            val instanceId2 = createInstance(conn, orderId, projectId, modelId, revId, userId, "SN-002")
            createTest(conn, instanceId2, orderId, userId, "NOT_GOOD")
        }
    }

    @Test
    fun `remark blocks shipment explicitly`() {
        ds.connection.use { conn ->
            val userId = createUser(conn)
            val projectId = createProject(conn, userId)
            val modelId = createProductModel(conn, userId)
            val revId = createProductRevision(conn, modelId, userId, "APPROVED")
            val orderId = createProductionOrder(conn, projectId, modelId, revId, userId)
            val instanceId = createInstance(conn, orderId, projectId, modelId, revId, userId, "SN-003")

            val remarkId = createRemark(conn, instanceId, userId, severity = "MINOR", blocksShipment = true)
            assertTrue(remarkId > 0)

            // A MINOR remark may block shipment because blocks_shipment is explicit.
            conn.prepareStatement(
                "SELECT COUNT(*) FROM instance_remarks WHERE instance_id = ? AND blocks_shipment = TRUE AND status = 'OPEN'"
            ).use { ps ->
                ps.setLong(1, instanceId)
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(1, rs.getLong(1))
                }
            }
        }
    }

    @Test
    fun `nonconformity lifecycle history is append-only`() {
        ds.connection.use { conn ->
            val userId = createUser(conn)
            val ncId = createNonconformity(conn, userId)
            conn.prepareStatement(
                """
                INSERT INTO nonconformity_history (nonconformity_id, action, from_status, to_status, changed_by, created_at)
                VALUES (?, 'STATUS_CHANGED', 'REGISTERED', 'EVALUATION', ?, NOW())
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, ncId)
                ps.setLong(2, userId)
                assertEquals(1, ps.executeUpdate())
            }

            assertFailsWith<Exception> {
                conn.createStatement().execute("UPDATE nonconformity_history SET reason = 'hacked'")
            }
        }
    }

    @Test
    fun `technical decision is separate from execution and immutable after made`() {
        ds.connection.use { conn ->
            val userId = createUser(conn)
            val projectId = createProject(conn, userId)
            val modelId = createProductModel(conn, userId)
            val revId = createProductRevision(conn, modelId, userId, "APPROVED")
            val orderId = createProductionOrder(conn, projectId, modelId, revId, userId)
            val instanceId = createInstance(conn, orderId, projectId, modelId, revId, userId, "SN-TEST-01")

            val testId = createTest(conn, instanceId, orderId, userId, "GOOD")

            // DELETE is forbidden.
            assertFailsWith<Exception> {
                conn.prepareStatement("DELETE FROM instance_tests WHERE id = ?").use { ps ->
                    ps.setLong(1, testId)
                    ps.executeUpdate()
                }
            }

            // PENDING -> GOOD is allowed.
            setTechnicalDecision(conn, testId, userId, "GOOD")

            // Re-decision is forbidden.
            assertFailsWith<Exception> {
                setTechnicalDecision(conn, testId, userId, "NOT_GOOD")
            }

            // Content change after decision is forbidden.
            assertFailsWith<Exception> {
                conn.prepareStatement("UPDATE instance_tests SET notes = 'changed' WHERE id = ?").use { ps ->
                    ps.setLong(1, testId)
                    ps.executeUpdate()
                }
            }

            // Version increment is allowed.
            conn.prepareStatement("UPDATE instance_tests SET version = version + 1 WHERE id = ?").use { ps ->
                ps.setLong(1, testId)
                assertEquals(1, ps.executeUpdate())
            }
        }
    }

    @Test
    fun `only one test can supersede a given test`() {
        ds.connection.use { conn ->
            val userId = createUser(conn)
            val projectId = createProject(conn, userId)
            val modelId = createProductModel(conn, userId)
            val revId = createProductRevision(conn, modelId, userId, "APPROVED")
            val orderId = createProductionOrder(conn, projectId, modelId, revId, userId)
            val instanceId = createInstance(conn, orderId, projectId, modelId, revId, userId, "SN-TEST-02")

            val originalTestId = createTest(conn, instanceId, orderId, userId, "NOT_GOOD")
            createSupersedingTest(conn, originalTestId, instanceId, orderId, userId)

            // A second correction of the same original test is forbidden.
            assertFailsWith<Exception> {
                createSupersedingTest(conn, originalTestId, instanceId, orderId, userId)
            }
        }
    }

    private fun createUser(conn: Connection): Long {
        val login = "prod-" + UUID.randomUUID().toString().take(8)
        conn.prepareStatement(
            """
            INSERT INTO users (login, password_hash, full_name, active, created_at, version)
            VALUES (?, 'hash', 'Production Tester', true, NOW(), 0)
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

    private fun createProject(conn: Connection, ownerId: Long): Long {
        conn.prepareStatement(
            """
            INSERT INTO projects (number, name, status, owner_id, created_at, created_by, version)
            VALUES (?, 'Production Test Project', 'PLANNED', ?, NOW(), ?, 0)
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

    private fun createProductModel(conn: Connection, createdBy: Long): Long {
        conn.prepareStatement(
            """
            INSERT INTO product_models (code, name, development_status, created_by, created_at, version)
            VALUES (?, 'Test Model', 'DEVELOPMENT', ?, NOW(), 0)
            RETURNING id
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, "MOD-${UUID.randomUUID()}")
            ps.setLong(2, createdBy)
            ps.executeQuery().use { rs ->
                assertTrue(rs.next())
                return rs.getLong(1)
            }
        }
    }

    private fun createProductRevision(
        conn: Connection,
        modelId: Long,
        createdBy: Long,
        status: String,
        number: String? = null
    ): Long {
        val revNumber = number ?: "REV-${UUID.randomUUID()}"
        val approvedBy = if (status in listOf("APPROVED", "ACTIVE", "REPLACED")) createdBy else null
        val approvedAt = if (status in listOf("APPROVED", "ACTIVE", "REPLACED")) "NOW()" else "NULL"
        conn.prepareStatement(
            """
            INSERT INTO product_revisions (product_model_id, revision_number, status, approved_by, approved_at, created_by, created_at, version)
            VALUES (?, ?, ?, ?, $approvedAt, ?, NOW(), 0)
            RETURNING id
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, modelId)
            ps.setString(2, revNumber)
            ps.setString(3, status)
            if (approvedBy != null) ps.setLong(4, approvedBy) else ps.setNull(4, java.sql.Types.BIGINT)
            ps.setLong(5, createdBy)
            ps.executeQuery().use { rs ->
                assertTrue(rs.next())
                return rs.getLong(1)
            }
        }
    }

    private fun createProductionOrder(
        conn: Connection,
        projectId: Long,
        modelId: Long,
        revisionId: Long,
        createdBy: Long
    ): Long {
        conn.prepareStatement(
            """
            INSERT INTO production_orders (
                number, project_id, product_model_id, bom_revision_id, status,
                planned_quantity, actual_quantity, responsible_id, created_by, created_at, updated_at, version
            )
            VALUES (?, ?, ?, ?, 'DRAFT', 10, 0, ?, ?, NOW(), NOW(), 0)
            RETURNING id
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, "PO-${UUID.randomUUID()}")
            ps.setLong(2, projectId)
            ps.setLong(3, modelId)
            ps.setLong(4, revisionId)
            ps.setLong(5, createdBy)
            ps.setLong(6, createdBy)
            ps.executeQuery().use { rs ->
                assertTrue(rs.next())
                return rs.getLong(1)
            }
        }
    }

    private fun createInstance(
        conn: Connection,
        orderId: Long,
        projectId: Long,
        modelId: Long,
        revisionId: Long,
        createdBy: Long,
        serialNumber: String
    ): Long {
        conn.prepareStatement(
            """
            INSERT INTO product_instances (
                order_id, project_id, product_model_id, bom_revision_id, serial_number,
                status, manufactured_at, created_by, created_at, version
            )
            VALUES (?, ?, ?, ?, ?, 'MANUFACTURED', NOW(), ?, NOW(), 0)
            RETURNING id
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, orderId)
            ps.setLong(2, projectId)
            ps.setLong(3, modelId)
            ps.setLong(4, revisionId)
            ps.setString(5, serialNumber)
            ps.setLong(6, createdBy)
            ps.executeQuery().use { rs ->
                assertTrue(rs.next())
                return rs.getLong(1)
            }
        }
    }

    private fun createTest(
        conn: Connection,
        instanceId: Long,
        orderId: Long,
        performedBy: Long,
        result: String
    ): Long {
        conn.prepareStatement(
            """
            INSERT INTO instance_tests (
                instance_id, order_id, performed_by, performed_at, result,
                created_by, created_at, version
            )
            VALUES (?, ?, ?, NOW(), ?, ?, NOW(), 0)
            RETURNING id
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, instanceId)
            ps.setLong(2, orderId)
            ps.setLong(3, performedBy)
            ps.setString(4, result)
            ps.setLong(5, performedBy)
            ps.executeQuery().use { rs ->
                assertTrue(rs.next())
                return rs.getLong(1)
            }
        }
    }

    private fun createRemark(
        conn: Connection,
        instanceId: Long,
        createdBy: Long,
        severity: String,
        blocksShipment: Boolean
    ): Long {
        conn.prepareStatement(
            """
            INSERT INTO instance_remarks (
                instance_id, description, severity, blocks_shipment,
                status, created_by, created_at, version
            )
            VALUES (?, 'Test remark', ?, ?, 'OPEN', ?, NOW(), 0)
            RETURNING id
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, instanceId)
            ps.setString(2, severity)
            ps.setBoolean(3, blocksShipment)
            ps.setLong(4, createdBy)
            ps.executeQuery().use { rs ->
                assertTrue(rs.next())
                return rs.getLong(1)
            }
        }
    }

    private fun setTechnicalDecision(
        conn: Connection,
        testId: Long,
        decisionBy: Long,
        decision: String
    ) {
        conn.prepareStatement(
            """
            UPDATE instance_tests
            SET technical_decision = ?, technical_decision_by = ?, technical_decision_at = NOW(), version = version + 1
            WHERE id = ?
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, decision)
            ps.setLong(2, decisionBy)
            ps.setLong(3, testId)
            assertEquals(1, ps.executeUpdate())
        }
    }

    private fun createSupersedingTest(
        conn: Connection,
        supersedesTestId: Long,
        instanceId: Long,
        orderId: Long,
        performedBy: Long
    ): Long {
        conn.prepareStatement(
            """
            INSERT INTO instance_tests (
                instance_id, order_id, performed_by, performed_at, result, supersedes_test_id,
                created_by, created_at, version
            )
            VALUES (?, ?, ?, NOW(), 'NOT_GOOD', ?, ?, NOW(), 0)
            RETURNING id
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, instanceId)
            ps.setLong(2, orderId)
            ps.setLong(3, performedBy)
            ps.setLong(4, supersedesTestId)
            ps.setLong(5, performedBy)
            ps.executeQuery().use { rs ->
                assertTrue(rs.next())
                return rs.getLong(1)
            }
        }
    }

    private fun createNonconformity(conn: Connection, createdBy: Long): Long {
        conn.prepareStatement(
            """
            INSERT INTO nonconformities (
                number, category, description, detected_at, author_id, severity,
                object_type, object_id, status, created_by, created_at, version
            )
            VALUES (?, 'DEFECT', 'Test nonconformity', NOW(), ?, 'MAJOR', 'PROCESS', 0, 'REGISTERED', ?, NOW(), 0)
            RETURNING id
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, "NC-${UUID.randomUUID()}")
            ps.setLong(2, createdBy)
            ps.setLong(3, createdBy)
            ps.executeQuery().use { rs ->
                assertTrue(rs.next())
                return rs.getLong(1)
            }
        }
    }
}
