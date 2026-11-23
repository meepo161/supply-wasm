package org.example.project.ui.wms

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.example.project.data.ApiResult
import org.example.project.data.ProjectRepository
import org.example.project.data.TaskRepository
import org.example.project.data.UserRepository
import org.example.project.dto.ProjectDto
import org.example.project.dto.TaskDto
import org.example.project.dto.UserOptionDto
import org.example.project.ui.components.SearchableDropdown
import org.koin.compose.koinInject

/**
 * Поля выбора основания для складских документов. Формы хранят идентификатор строкой,
 * поэтому пикеры принимают и возвращают ту же строку, а пользователю показывают
 * номер и название вместо внутреннего ID.
 */
@Composable
internal fun ProjectPicker(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Проект"
) {
    val repository: ProjectRepository = koinInject()
    var projects by remember { mutableStateOf<List<ProjectDto>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        when (val result = repository.list()) {
            is ApiResult.Success -> projects = result.data
            is ApiResult.Error -> loadError = "Не удалось загрузить проекты"
        }
    }
    SearchableDropdown(
        items = projects,
        selected = projects.find { it.id.toString() == value.trim() },
        onSelect = { onChange(it.id.toString()) },
        label = label,
        itemText = { "${it.number} — ${it.name}" },
        modifier = modifier,
        emptyText = loadError ?: "Проектов нет",
        onClear = { onChange("") },
        clearText = "Без проекта"
    )
}

@Composable
internal fun TaskPicker(
    value: String,
    onChange: (String) -> Unit,
    projectId: String = "",
    modifier: Modifier = Modifier,
    label: String = "Задача"
) {
    val repository: TaskRepository = koinInject()
    var tasks by remember { mutableStateOf<List<TaskDto>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        when (val result = repository.list()) {
            is ApiResult.Success -> tasks = result.data
            is ApiResult.Error -> loadError = "Не удалось загрузить задачи"
        }
    }
    val project = projectId.trim().toLongOrNull()
    val visible = if (project == null) tasks else tasks.filter { it.projectId == project }
    SearchableDropdown(
        items = visible,
        selected = tasks.find { it.id.toString() == value.trim() },
        onSelect = { onChange(it.id.toString()) },
        label = label,
        itemText = { "${it.number} — ${it.title}" },
        modifier = modifier,
        emptyText = loadError ?: if (project != null) "В проекте нет задач" else "Задач нет",
        onClear = { onChange("") },
        clearText = "Без задачи"
    )
}

@Composable
internal fun UserPicker(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Получатель",
    clearText: String = "Не выбран"
) {
    val repository: UserRepository = koinInject()
    var users by remember { mutableStateOf<List<UserOptionDto>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        when (val result = repository.listOptions()) {
            is ApiResult.Success -> users = result.data
            is ApiResult.Error -> loadError = "Не удалось загрузить сотрудников"
        }
    }
    SearchableDropdown(
        items = users,
        selected = users.find { it.id.toString() == value.trim() },
        onSelect = { onChange(it.id.toString()) },
        label = label,
        itemText = { it.fullName },
        modifier = modifier,
        emptyText = loadError ?: "Сотрудников нет",
        onClear = { onChange("") },
        clearText = clearText
    )
}

/** Заказы поставщику, по которым склад может принимать товар. */
private val receivableOrderStatuses = setOf(
    org.example.project.dto.PurchaseOrderStatus.SENT,
    org.example.project.dto.PurchaseOrderStatus.PARTIALLY_DELIVERED,
    org.example.project.dto.PurchaseOrderStatus.DELIVERED
)

@Composable
internal fun PurchaseOrderPicker(
    value: Long?,
    onChange: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Заказ поставщику"
) {
    val repository: org.example.project.data.PurchaseOrderRepository = koinInject()
    var orders by remember { mutableStateOf<List<org.example.project.dto.PurchaseOrderDto>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        when (val result = repository.listOrders()) {
            is ApiResult.Success -> orders = result.data.filter { it.status in receivableOrderStatuses }
            is ApiResult.Error -> loadError = "Не удалось загрузить заказы поставщикам"
        }
    }
    SearchableDropdown(
        items = orders,
        selected = orders.find { it.id == value },
        onSelect = { onChange(it.id) },
        label = label,
        itemText = { listOfNotNull(it.number, it.supplierName).joinToString(" · ") },
        modifier = modifier,
        emptyText = loadError ?: "Нет отправленных заказов поставщикам",
        onClear = { onChange(null) },
        clearText = "Без заказа"
    )
}

/** Позиции выбранного заказа поставщику (для выбора строки заказа в приёмке). */
@Composable
internal fun rememberPurchaseOrderLines(orderId: Long?): List<org.example.project.dto.PurchaseOrderLineDto> {
    val repository: org.example.project.data.PurchaseOrderRepository = koinInject()
    var lines by remember(orderId) { mutableStateOf<List<org.example.project.dto.PurchaseOrderLineDto>>(emptyList()) }
    LaunchedEffect(orderId) {
        if (orderId == null) return@LaunchedEffect
        when (val result = repository.getOrder(orderId)) {
            is ApiResult.Success -> lines = result.data.lines
            is ApiResult.Error -> lines = emptyList()
        }
    }
    return lines
}
