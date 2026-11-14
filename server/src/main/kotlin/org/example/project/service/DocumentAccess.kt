package org.example.project.service

import org.example.project.model.CurrentUser
import org.example.project.repository.ChatRepository
import org.example.project.repository.ProjectRepository

/**
 * Общие проверки доступа к связываемым с документами объектам.
 * Переиспользует правила Task Chat и проектов без их дублирования.
 */
class DocumentAccess(
    private val chatRepository: ChatRepository,
    private val projectRepository: ProjectRepository
) {

    fun canReadProject(projectId: Long, currentUser: CurrentUser): Boolean {
        val project = projectRepository.findById(projectId) ?: return false
        return currentUser.hasPermission("projects", "read_any") ||
            project.ownerId == currentUser.userId ||
            projectRepository.isMember(projectId, currentUser.userId)
    }

    fun canReadTask(taskId: Long, currentUser: CurrentUser): Boolean {
        chatRepository.findTaskById(taskId) ?: return false
        return chatRepository.canRead(taskId, currentUser.userId, currentUser.hasPermission("tasks", "read_any"))
    }
}
