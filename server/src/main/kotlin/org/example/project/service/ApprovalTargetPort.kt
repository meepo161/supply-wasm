package org.example.project.service

import org.example.project.model.CurrentUser
import org.example.project.repository.ApprovalRepository

const val APPROVAL_TARGET_DEMAND = "DEMAND"

/**
 * Порт цели согласования. Реальные адаптеры реализуют доступ, фиксацию версии
 * и применение результата к конкретному типу объекта. Старт маршрута для типа
 * без зарегистрированного адаптера отклоняется сервером.
 */
interface ApprovalTargetPort {

    val targetType: String

    /** Проверяет существование, доступ и стартовые условия; возвращает текущую версию объекта. */
    fun ensureStartAllowed(targetId: Long, currentUser: CurrentUser): Long

    /** Проверяет право чтения маршрута по доступу к объекту; маршрут не даёт лишних прав. */
    fun ensureReadAllowed(targetId: Long, currentUser: CurrentUser)

    /** Текущая версия объекта для отказа по устаревшей версии (M.6). */
    fun currentTargetVersion(targetId: Long): Long

    /**
     * Применяет одобрение к объекту при завершении последнего этапа.
     * Обязан быть атомарным и отклонять устаревшую expectedVersion,
     * не переписывая более новое состояние объекта.
     */
    fun completeApproval(targetId: Long, expectedVersion: Long, decidedBy: Long)
}

/** Узкий guard для целевых сервисов: блокирует прямые переходы при активном маршруте. */
fun interface ApprovalGuard {
    fun hasActiveRoute(targetType: String, targetId: Long): Boolean
}

class ApprovalRouteGuard(private val approvalRepository: ApprovalRepository) : ApprovalGuard {
    override fun hasActiveRoute(targetType: String, targetId: Long): Boolean {
        return approvalRepository.hasActiveRoute(targetType, targetId)
    }
}
