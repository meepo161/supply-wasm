package org.example.project

import org.flywaydb.core.Flyway
import org.postgresql.ds.PGSimpleDataSource

object TestDatabase {

    private const val DB_NAME = "erp_test"
    private const val ADMIN_URL = "jdbc:postgresql://localhost:5433/erp"
    private const val TEST_URL = "jdbc:postgresql://localhost:5433/erp_test"
    private const val USER = "erp"
    private const val PASSWORD = "erp"

    fun init() {
        createDatabase()
        Flyway.configure()
            .dataSource(TEST_URL, USER, PASSWORD)
            .locations("filesystem:src/main/resources/db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate()
    }

    fun reset() {
        PGSimpleDataSource().apply {
            setUrl(TEST_URL)
            user = USER
            password = PASSWORD
        }.connection.use { conn ->
            conn.createStatement().execute(
                """
                SELECT pg_terminate_backend(pid)
                FROM pg_stat_activity
                WHERE datname = '$DB_NAME' AND pid <> pg_backend_pid()
                """.trimIndent()
            )
            conn.createStatement().execute(
                """
                TRUNCATE TABLE
                  wms_issue_idempotency_keys, return_lines, return_orders,
                  issue_picks, issue_lines, issue_orders,
                  receipt_line_serials, receipt_lines, receipts,
                  reservation_history, reservations, stock_movement, stock_balance,
                  serial_items, lots, nomenclature_barcodes, nomenclature, storage_locations, warehouses,
                  wms_idempotency_keys, transfer_events, transfers,
                  document_download_log, document_version_history,
                  document_links, documents, document_versions, document_files,
                  demand_procurements, demand_history, demand_line_history, demand_lines, demands,
                  audit_log, sessions, user_roles, permissions, roles,
                  attachments, notifications, task_chat_messages, task_status_history,
                  task_participants, tasks, project_bom_revisions, project_products,
                  project_members, projects,
                  bom_lines, product_revisions, product_models,
                  users, departments, login_attempts
                RESTART IDENTITY CASCADE
                """.trimIndent()
            )
        }
    }

    fun destroy() {
        PGSimpleDataSource().apply {
            setUrl(ADMIN_URL)
            user = USER
            password = PASSWORD
        }.connection.use { conn ->
            conn.createStatement().execute(
                """
                SELECT pg_terminate_backend(pid)
                FROM pg_stat_activity
                WHERE datname = '$DB_NAME' AND pid <> pg_backend_pid()
                """.trimIndent()
            )
            conn.createStatement().execute("DROP DATABASE IF EXISTS $DB_NAME")
        }
    }

    private fun createDatabase() {
        PGSimpleDataSource().apply {
            setUrl(ADMIN_URL)
            user = USER
            password = PASSWORD
        }.connection.use { conn ->
            conn.createStatement().execute(
                """
                SELECT pg_terminate_backend(pid)
                FROM pg_stat_activity
                WHERE datname = '$DB_NAME' AND pid <> pg_backend_pid()
                """.trimIndent()
            )
            conn.createStatement().execute("DROP DATABASE IF EXISTS $DB_NAME")
            conn.createStatement().execute("CREATE DATABASE $DB_NAME")
        }
    }
}
