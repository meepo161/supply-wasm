package org.example.project

import org.example.project.ui.home.isNeutralNotificationPayload
import org.example.project.ui.home.pendingNotificationEventId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserNotificationContractTest {

    @Test
    fun `neutral payload allows only category and event id`() {
        assertTrue(isNeutralNotificationPayload("""{"category":"NOTIFICATION","eventId":42}"""))
        assertFalse(isNeutralNotificationPayload("""{"category":"NOTIFICATION","eventId":42,"title":"Secret"}"""))
        assertFalse(isNeutralNotificationPayload("""{"category":"CHAT","eventId":42}"""))
        assertFalse(isNeutralNotificationPayload("""{"eventId":42}"""))
        assertFalse(isNeutralNotificationPayload("not json"))
    }

    @Test
    fun `pending deep link extracts only positive event id`() {
        assertEquals(42L, pendingNotificationEventId("#notification=42"))
        assertEquals(43L, pendingNotificationEventId("#eventId=43"))
        assertEquals(44L, pendingNotificationEventId("https://localhost:8081/#notification=44"))
        assertNull(pendingNotificationEventId("#notification=0"))
        assertNull(pendingNotificationEventId("#notification=-1"))
        assertNull(pendingNotificationEventId("#notification=secret"))
        assertNull(pendingNotificationEventId("#task=42"))
        assertNull(pendingNotificationEventId(null))
    }
}
