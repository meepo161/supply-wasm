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

    /**
     * Проверка доступа к проекту для вкладки «Документы» (PRJ-13):
     * неучастник получает 403, несуществующий проект — 404.
     */
    fun requireReadProject(projectId: Long, currentUser: CurrentUser) {
        val project = projectRepository.findById(projectId)
            ?: throw org.example.project.exception.NotFoundException("project", projectId)
        if (!currentUser.hasPermission("projects", "read_any") &&
            project.ownerId != currentUser.userId &&
            !projectRepository.isMember(projectId, currentUser.userId)
        ) {
            throw org.example.project.exception.ForbiddenException("Access to project documents denied")
        }
    }

    fun canReadTask(taskId: Long, currentUser: CurrentUser): Boolean {
        chatRepository.findTaskById(taskId) ?: return false
        return chatRepository.canRead(taskId, currentUser.userId, currentUser.hasPermission("tasks", "read_any"))
    }
}
