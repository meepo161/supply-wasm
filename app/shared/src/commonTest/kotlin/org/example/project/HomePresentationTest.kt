package org.example.project

import org.example.project.ui.home.HomeActionQueueItem
import org.example.project.ui.home.actionQueueContentDescription
import org.example.project.ui.home.homeTaskSummary
import kotlin.test.Test
import kotlin.test.assertEquals

class HomePresentationTest {
    @Test
    fun overdueSummaryHasPriorityAndKeepsRealCounts() {
        assertEquals(
            "просрочено 2, открыто 7, на приёмке 3",
            homeTaskSummary(false, 5, 7, 2, 3)
        )
    }

    @Test
    fun personalEmptySummaryCanShowCompanyOverdue() {
        assertEquals(
            "у вас просрочек нет, по компании просрочено 4",
            homeTaskSummary(false, 4, 0, 0, 0)
        )
    }

    @Test
    fun actionQueueItemHasCompleteAccessibleName() {
        val item = HomeActionQueueItem("Приёмки", "Укажите ячейки", 2, "WMS")
        assertEquals("Приёмки. Укажите ячейки. Количество: 2.", actionQueueContentDescription(item))
    }
}
