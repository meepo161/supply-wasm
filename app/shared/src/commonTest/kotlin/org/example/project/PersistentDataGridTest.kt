package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.example.project.ui.tables.decodeColumnWidths
import org.example.project.ui.tables.encodeColumnWidths

class PersistentDataGridTest {
    @Test
    fun `column widths survive serialization round trip`() {
        val widths = mapOf("Название" to 0.45f, "Статус" to 0.2f, "Срок" to 0.35f)

        assertEquals(widths, decodeColumnWidths(encodeColumnWidths(widths)))
    }

    @Test
    fun `invalid stored widths are ignored`() {
        assertTrue(decodeColumnWidths(null).isEmpty())
        assertTrue(decodeColumnWidths("not json").isEmpty())
        assertEquals(mapOf("Название" to 0.5f), decodeColumnWidths("""{"Название":0.5,"Статус":-1}"""))
    }
}
