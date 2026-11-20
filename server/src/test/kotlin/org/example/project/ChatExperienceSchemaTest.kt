package org.example.project

import org.junit.BeforeClass
import org.junit.Test
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatExperienceSchemaTest {

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
    fun `conversation experience migration creates separate tables and constraints`() {
        TestDatabase.reset()
        ds.connection.use { conn ->
            assertMigrationApplied(conn)
            assertColumns(
                conn,
                "conversation_messages",
                setOf(
                    "reply_to_id", "edited_at", "revoked_at", "source_kind", "source_message_id",
                    "source_author_id", "source_created_at", "version"
                )
            )
            assertEquals("0", columnDefault(conn, "conversation_messages", "version"))
            assertTables(
                conn,
                setOf(
                    "conversation_message_revisions", "conversation_reactions", "conversation_mentions",
                    "conversation_read_states", "conversation_dialog_states", "task_chat_message_revisions",
                    "chat_push_subscriptions", "conversation_event_log", "task_chat_event_log"
                )
            )

            assertColumns(
                conn,
                "conversation_event_log",
                setOf("id", "conversation_id", "category", "message_id", "actor_user_id", "created_at")
            )
            assertMigrationApplied(conn, "34")

            val userId = createUser(conn)
            val conversationId = createConversation(conn, userId)
            val messageId = createConversationMessage(conn, conversationId, userId)
            val replyId = createConversationMessage(conn, conversationId, userId, messageId)
            assertTrue(replyId > messageId)

            conn.prepareStatement(
                "INSERT INTO conversation_reactions (message_id, user_id, emoji) VALUES (?, ?, 'ok')"
            ).use { statement ->
                statement.setLong(1, messageId)
                statement.setLong(2, userId)
                assertEquals(1, statement.executeUpdate())
            }
            assertFailsWith<Exception> {
                conn.prepareStatement(
                    "INSERT INTO conversation_reactions (message_id, user_id, emoji) VALUES (?, ?, 'ok')"
                ).use { statement ->
                    statement.setLong(1, messageId)
                    statement.setLong(2, userId)
                    statement.executeUpdate()
                }
            }

            insertConversationUserState(conn, "conversation_mentions", "message_id, mentioned_user_id", messageId, userId)
            assertFailsWith<Exception> {
                insertConversationUserState(conn, "conversation_mentions", "message_id, mentioned_user_id", messageId, userId)
            }
            insertConversationUserState(conn, "conversation_read_states", "conversation_id, user_id", conversationId, userId)
            insertConversationUserState(conn, "conversation_dialog_states", "conversation_id, user_id", conversationId, userId)
            assertFailsWith<Exception> {
                insertConversationUserState(conn, "conversation_dialog_states", "conversation_id, user_id", conversationId, userId)
            }

            conn.prepareStatement(
                """
                INSERT INTO conversation_message_revisions (message_id, previous_content, edited_by)
                VALUES (?, 'previous text', ?)
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, messageId)
                statement.setLong(2, userId)
                assertEquals(1, statement.executeUpdate())
            }

            assertFailsWith<Exception> {
                conn.prepareStatement(
                    "UPDATE conversation_messages SET source_kind = 'DIRECT', source_message_id = NULL WHERE id = ?"
                ).use { statement ->
                    statement.setLong(1, messageId)
                    statement.executeUpdate()
                }
            }

            val pushUserId = createUser(conn)
            conn.prepareStatement(
                "INSERT INTO chat_push_subscriptions (user_id, endpoint, p256dh, auth) VALUES (?, ?, 'key', 'auth')"
            ).use { statement ->
                statement.setLong(1, pushUserId)
                statement.setString(2, "https://push.example/${UUID.randomUUID()}")
                assertEquals(1, statement.executeUpdate())
            }
            conn.prepareStatement("DELETE FROM users WHERE id = ?").use { statement ->
                statement.setLong(1, pushUserId)
                statement.executeUpdate()
            }
            conn.prepareStatement("SELECT 1 FROM chat_push_subscriptions WHERE user_id = ?").use { statement ->
                statement.setLong(1, pushUserId)
                statement.executeQuery().use { result -> assertFalse(result.next()) }
            }
        }
    }

    @Test
    fun `task chat revisions extend existing messages without another reply column`() {
        TestDatabase.reset()
        ds.connection.use { conn ->
            assertColumns(
                conn,
                "task_chat_messages",
                setOf(
                    "reply_to_id", "edited_at", "revoked_at", "source_kind", "source_message_id",
                    "source_author_id", "source_created_at", "version"
                )
            )
            assertEquals(1, columnCount(conn, "task_chat_messages", "reply_to_id"))
            assertEquals("0", columnDefault(conn, "task_chat_messages", "version"))

            val userId = createUser(conn)
            val taskId = createTask(conn, userId)
            val messageId = conn.prepareStatement(
                "INSERT INTO task_chat_messages (task_id, author_id, content) VALUES (?, ?, 'text') RETURNING id"
            ).use { statement ->
                statement.setLong(1, taskId)
                statement.setLong(2, userId)
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    result.getLong(1)
                }
            }
            conn.prepareStatement(
                "INSERT INTO task_chat_message_revisions (message_id, previous_content, edited_by) VALUES (?, 'old', ?)"
            ).use { statement ->
                statement.setLong(1, messageId)
                statement.setLong(2, userId)
                assertEquals(1, statement.executeUpdate())
            }
            assertMigrationApplied(conn, "35")
            assertMigrationApplied(conn, "36")
            assertTables(conn, setOf("task_chat_dialog_states"))
            assertColumns(
                conn,
                "task_chat_dialog_states",
                setOf("task_id", "user_id", "muted", "archived", "updated_at")
            )
            conn.prepareStatement(
                "INSERT INTO task_chat_dialog_states (task_id, user_id, muted, archived) VALUES (?, ?, TRUE, TRUE)"
            ).use { statement ->
                statement.setLong(1, taskId)
                statement.setLong(2, userId)
                assertEquals(1, statement.executeUpdate())
            }
            assertFailsWith<Exception> {
                conn.prepareStatement(
                    "INSERT INTO task_chat_dialog_states (task_id, user_id) VALUES (?, ?)"
                ).use { statement ->
                    statement.setLong(1, taskId)
                    statement.setLong(2, userId)
                    statement.executeUpdate()
                }
            }
            assertColumns(
                conn,
                "task_chat_event_log",
                setOf("id", "task_id", "category", "message_id", "actor_user_id", "is_pinned", "created_at")
            )
            conn.prepareStatement(
                "INSERT INTO task_chat_event_log (task_id, category, message_id, actor_user_id, is_pinned) VALUES (?, 'PIN', NULL, ?, TRUE)"
            ).use { statement ->
                statement.setLong(1, taskId)
                statement.setLong(2, userId)
                assertEquals(1, statement.executeUpdate())
            }
            assertFailsWith<Exception> {
                conn.prepareStatement(
                    "INSERT INTO task_chat_event_log (task_id, category, actor_user_id) VALUES (?, 'TYPING', ?)"
                ).use { statement ->
                    statement.setLong(1, taskId)
                    statement.setLong(2, userId)
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun assertMigrationApplied(connection: Connection, version: String = "33") {
        connection.prepareStatement(
            "SELECT success FROM flyway_schema_history WHERE version = ? ORDER BY installed_rank DESC LIMIT 1"
        ).use { statement ->
            statement.setString(1, version)
            statement.executeQuery().use { result ->
                assertTrue(result.next())
                assertTrue(result.getBoolean(1))
            }
        }
    }

    private fun assertTables(connection: Connection, tables: Set<String>) {
        tables.forEach { table ->
            connection.prepareStatement("SELECT to_regclass('public.$table')").use { statement ->
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    assertEquals(table, result.getString(1))
                }
            }
        }
    }

    private fun assertColumns(connection: Connection, table: String, columns: Set<String>) {
        columns.forEach { column -> assertEquals(1, columnCount(connection, table, column), "$table.$column") }
    }

    private fun columnCount(connection: Connection, table: String, column: String): Int =
        connection.prepareStatement(
            """
            SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, table)
            statement.setString(2, column)
            statement.executeQuery().use { result ->
                assertTrue(result.next())
                result.getInt(1)
            }
        }

    private fun columnDefault(connection: Connection, table: String, column: String): String =
        connection.prepareStatement(
            """
            SELECT column_default FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, table)
            statement.setString(2, column)
            statement.executeQuery().use { result ->
                assertTrue(result.next())
                result.getString(1)
            }
        }

    private fun insertConversationUserState(
        connection: Connection,
        table: String,
        columns: String,
        firstId: Long,
        userId: Long
    ) {
        connection.prepareStatement("INSERT INTO $table ($columns) VALUES (?, ?)").use { statement ->
            statement.setLong(1, firstId)
            statement.setLong(2, userId)
            statement.executeUpdate()
        }
    }

    private fun createUser(connection: Connection): Long = connection.prepareStatement(
        """
        INSERT INTO users (login, password_hash, full_name, active, created_at, version)
        VALUES (?, 'hash', 'Chat experience tester', TRUE, NOW(), 0)
        RETURNING id
        """.trimIndent()
    ).use { statement ->
        statement.setString(1, "chat-experience-${UUID.randomUUID().toString().take(8)}")
        statement.executeQuery().use { result ->
            assertTrue(result.next())
            result.getLong(1)
        }
    }

    private fun createConversation(connection: Connection, userId: Long): Long = connection.prepareStatement(
        "INSERT INTO conversations (kind, title, created_by) VALUES ('GROUP', 'Chat experience', ?) RETURNING id"
    ).use { statement ->
        statement.setLong(1, userId)
        statement.executeQuery().use { result ->
            assertTrue(result.next())
            result.getLong(1)
        }
    }

    private fun createConversationMessage(
        connection: Connection,
        conversationId: Long,
        userId: Long,
        replyToId: Long? = null
    ): Long = connection.prepareStatement(
        """
        INSERT INTO conversation_messages (conversation_id, author_id, content, idempotency_key, reply_to_id)
        VALUES (?, ?, 'text', ?, ?)
        RETURNING id
        """.trimIndent()
    ).use { statement ->
        statement.setLong(1, conversationId)
        statement.setLong(2, userId)
        statement.setString(3, UUID.randomUUID().toString())
        if (replyToId == null) statement.setNull(4, java.sql.Types.BIGINT) else statement.setLong(4, replyToId)
        statement.executeQuery().use { result ->
            assertTrue(result.next())
            result.getLong(1)
        }
    }

    private fun createTask(connection: Connection, userId: Long): Long = connection.prepareStatement(
        """
        INSERT INTO tasks (number, area, status, type, title, initiator_id, created_at, created_by, version)
        VALUES (?, 'PERSONAL', 'NEW', 'WORK', 'Chat experience task', ?, NOW(), ?, 0)
        RETURNING id
        """.trimIndent()
    ).use { statement ->
        statement.setString(1, "T-${UUID.randomUUID()}")
        statement.setLong(2, userId)
        statement.setLong(3, userId)
        statement.executeQuery().use { result ->
            assertTrue(result.next())
            result.getLong(1)
        }
    }
}
