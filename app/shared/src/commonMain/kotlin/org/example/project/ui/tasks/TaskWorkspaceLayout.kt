package org.example.project.ui.tasks

/** Layout policy kept independent from Compose so it is covered by common tests. */
enum class TaskWorkspaceLayout {
    LIST_ONLY,
    TABBED_DETAIL,
    THREE_COLUMNS
}

fun taskWorkspaceLayout(widthDp: Int, hasSelectedTask: Boolean): TaskWorkspaceLayout = when {
    !hasSelectedTask -> TaskWorkspaceLayout.LIST_ONLY
    widthDp >= 1100 -> TaskWorkspaceLayout.THREE_COLUMNS
    else -> TaskWorkspaceLayout.TABBED_DETAIL
}
