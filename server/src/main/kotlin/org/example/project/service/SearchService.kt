package org.example.project.service

import org.example.project.dto.SearchGroupDto
import org.example.project.dto.SearchResponse
import org.example.project.dto.SearchResultItemDto
import org.example.project.dto.SearchResultType
import org.example.project.exception.ValidationException
import org.example.project.model.CurrentUser
import org.example.project.repository.SearchRepository
import org.example.project.repository.UserRepository

class SearchService(
    private val searchRepository: SearchRepository,
    private val userRepository: UserRepository
) {

    fun search(query: String, limit: Int, currentUser: CurrentUser): SearchResponse {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            throw ValidationException("Search query is required", mapOf("q" to "required"))
        }
        if (trimmed.length > 200) {
            throw ValidationException("Search query is too long", mapOf("q" to "too_long"))
        }
        val effectiveLimit = limit.coerceIn(1, 50)

        val user = userRepository.findById(currentUser.userId)
        val groups = mutableListOf<SearchGroupDto>()

        val projectRows = searchRepository.searchProjects(
            query = trimmed,
            userId = currentUser.userId,
            canReadAny = currentUser.hasPermission("projects", "read_any"),
            limit = effectiveLimit
        )
        groups.add(
            SearchGroupDto(
                type = SearchResultType.PROJECT,
                items = projectRows.take(effectiveLimit).map {
                    SearchResultItemDto(
                        type = SearchResultType.PROJECT,
                        id = it.id,
                        number = it.number,
                        title = it.name,
                        subtitle = truncate(it.description),
                        status = it.status
                    )
                },
                hasMore = projectRows.size > effectiveLimit
            )
        )

        val taskRows = searchRepository.searchTasks(
            query = trimmed,
            userId = currentUser.userId,
            departmentId = user?.departmentId,
            canReadAny = currentUser.hasPermission("tasks", "read_any"),
            limit = effectiveLimit
        )
        groups.add(
            SearchGroupDto(
                type = SearchResultType.TASK,
                items = taskRows.take(effectiveLimit).map {
                    SearchResultItemDto(
                        type = SearchResultType.TASK,
                        id = it.id,
                        number = it.number,
                        title = it.title,
                        subtitle = truncate(it.description),
                        status = it.status
                    )
                },
                hasMore = taskRows.size > effectiveLimit
            )
        )

        if (currentUser.hasPermission("users", "read_any")) {
            val userRows = searchRepository.searchUsers(trimmed, effectiveLimit)
            groups.add(
                SearchGroupDto(
                    type = SearchResultType.USER,
                    items = userRows.take(effectiveLimit).map {
                        SearchResultItemDto(
                            type = SearchResultType.USER,
                            id = it.id,
                            title = it.fullName,
                            subtitle = it.login
                        )
                    },
                    hasMore = userRows.size > effectiveLimit
                )
            )
        }

        if (currentUser.hasPermission("documents", "read") || currentUser.hasPermission("documents", "read_any")) {
            val docRows = searchRepository.searchDocuments(
                query = trimmed,
                userId = currentUser.userId,
                canReadAny = currentUser.hasPermission("documents", "read_any"),
                limit = effectiveLimit
            )
            groups.add(
                SearchGroupDto(
                    type = SearchResultType.DOCUMENT,
                    items = docRows.take(effectiveLimit).map {
                        SearchResultItemDto(
                            type = SearchResultType.DOCUMENT,
                            id = it.id,
                            number = it.number,
                            title = it.title,
                            subtitle = it.docType,
                            status = it.confidentiality
                        )
                    },
                    hasMore = docRows.size > effectiveLimit
                )
            )
        }

        if (currentUser.hasPermission("wms", "read") || currentUser.hasPermission("bom", "read_any")) {
            val nomRows = searchRepository.searchNomenclature(trimmed, effectiveLimit)
            groups.add(
                SearchGroupDto(
                    type = SearchResultType.NOMENCLATURE,
                    items = nomRows.take(effectiveLimit).map {
                        SearchResultItemDto(
                            type = SearchResultType.NOMENCLATURE,
                            id = it.id,
                            number = it.code,
                            title = it.name,
                            subtitle = it.kind
                        )
                    },
                    hasMore = nomRows.size > effectiveLimit
                )
            )
        }

        val messageRows = searchRepository.searchTaskMessages(
            query = trimmed,
            userId = currentUser.userId,
            departmentId = user?.departmentId,
            canReadAny = currentUser.hasPermission("tasks", "read_any"),
            limit = effectiveLimit
        )
        groups.add(
            SearchGroupDto(
                type = SearchResultType.MESSAGE,
                items = messageRows.take(effectiveLimit).map {
                    SearchResultItemDto(
                        type = SearchResultType.MESSAGE,
                        id = it.id,
                        number = it.taskNumber,
                        title = truncate(it.content) ?: "",
                        subtitle = it.taskTitle,
                        parentId = it.taskId
                    )
                },
                hasMore = messageRows.size > effectiveLimit
            )
        )

        if (currentUser.hasPermission("suppliers", "read")) {
            val poRows = searchRepository.searchPurchaseOrders(trimmed, effectiveLimit)
            groups.add(
                SearchGroupDto(
                    type = SearchResultType.PURCHASE_ORDER,
                    items = poRows.take(effectiveLimit).map {
                        SearchResultItemDto(SearchResultType.PURCHASE_ORDER, it.id, it.number, it.title, it.subtitle, it.status)
                    },
                    hasMore = poRows.size > effectiveLimit
                )
            )
            val supRows = searchRepository.searchSuppliers(trimmed, effectiveLimit)
            groups.add(
                SearchGroupDto(
                    type = SearchResultType.SUPPLIER,
                    items = supRows.take(effectiveLimit).map {
                        SearchResultItemDto(SearchResultType.SUPPLIER, it.id, it.number, it.title, it.subtitle, it.status)
                    },
                    hasMore = supRows.size > effectiveLimit
                )
            )
        }

        if (currentUser.hasPermission("demands", "read")) {
            val demandRows = searchRepository.searchDemands(trimmed, effectiveLimit)
            groups.add(
                SearchGroupDto(
                    type = SearchResultType.DEMAND,
                    items = demandRows.take(effectiveLimit).map {
                        SearchResultItemDto(SearchResultType.DEMAND, it.id, it.number, it.title, it.subtitle, it.status)
                    },
                    hasMore = demandRows.size > effectiveLimit
                )
            )
        }

        if (currentUser.hasPermission("wms", "read")) {
            val receiptRows = searchRepository.searchReceipts(trimmed, effectiveLimit)
            groups.add(
                SearchGroupDto(
                    type = SearchResultType.RECEIPT,
                    items = receiptRows.take(effectiveLimit).map {
                        SearchResultItemDto(SearchResultType.RECEIPT, it.id, it.number, it.title, it.subtitle, it.status)
                    },
                    hasMore = receiptRows.size > effectiveLimit
                )
            )
        }

        if (currentUser.hasPermission("production", "read")) {
            val prodRows = searchRepository.searchProductionOrders(trimmed, effectiveLimit)
            groups.add(
                SearchGroupDto(
                    type = SearchResultType.PRODUCTION_ORDER,
                    items = prodRows.take(effectiveLimit).map {
                        SearchResultItemDto(SearchResultType.PRODUCTION_ORDER, it.id, it.number, it.title, it.subtitle, it.status)
                    },
                    hasMore = prodRows.size > effectiveLimit
                )
            )
        }

        return SearchResponse(query = trimmed, limit = effectiveLimit, groups = groups)
    }

    private fun truncate(value: String?): String? {
        if (value == null) return null
        return if (value.length > 200) value.substring(0, 200) else value
    }
}
