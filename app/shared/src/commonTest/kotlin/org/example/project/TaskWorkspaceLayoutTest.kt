package org.example.project

import org.example.project.ui.tasks.TaskWorkspaceLayout
import org.example.project.ui.tasks.taskWorkspaceLayout
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskWorkspaceLayoutTest {

    @Test
    fun `shows three columns for a selected task on a wide desktop`() {
        assertEquals(TaskWorkspaceLayout.THREE_COLUMNS, taskWorkspaceLayout(1280, hasSelectedTask = true))
    }

    @Test
    fun `keeps task list only until a task is selected`() {
        assertEquals(TaskWorkspaceLayout.LIST_ONLY, taskWorkspaceLayout(1280, hasSelectedTask = false))
    }

    @Test
    fun `uses tabbed detail on a narrow viewport`() {
        assertEquals(TaskWorkspaceLayout.TABBED_DETAIL, taskWorkspaceLayout(839, hasSelectedTask = true))
    }
}
