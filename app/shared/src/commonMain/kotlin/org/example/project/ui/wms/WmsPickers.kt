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

/**
 * Подписи для карточек складских документов: документы хранят внутренние ID склада,
 * ячейки, позиции и сотрудника, а пользователю нужны код и название.
 */
internal class WmsNames(
    private val warehouses: List<org.example.project.dto.WarehouseDto> = emptyList(),
    private val locations: List<org.example.project.dto.StorageLocationDto> = emptyList(),
    private val nomenclature: List<org.example.project.dto.NomenclatureDto> = emptyList(),
    private val users: List<UserOptionDto> = emptyList(),
    private val issues: List<org.example.project.dto.IssueOrderDto> = emptyList()
) {
    fun issue(id: Long?): String = when (id) {
        null -> "—"
        else -> issues.find { it.id == id }?.number ?: "Выдача #$id"
    }

    fun warehouse(id: Long?): String = when (id) {
        null -> "—"
        else -> warehouses.find { it.id == id }?.let { "${it.code} — ${it.name}" } ?: "Склад #$id"
    }

    fun location(id: Long?): String = when (id) {
        null -> "—"
        else -> locations.find { it.id == id }?.let { "${it.code} — ${it.name}" } ?: "Ячейка #$id"
    }

    fun item(id: Long?): String = when (id) {
        null -> "—"
        else -> nomenclature.find { it.id == id }?.let { "${it.code} — ${it.name}" } ?: "Позиция #$id"
    }

    fun unit(id: Long?): String = nomenclature.find { it.id == id }?.unit.orEmpty()

    /** Количество с единицей позиции: «3 шт». */
    fun qty(quantity: String?, nomenclatureId: Long?): String =
        if (quantity.isNullOrBlank()) "—"
        else listOf(org.example.project.ui.theme.ErpFormat.qty(quantity), unit(nomenclatureId))
            .filter { it.isNotBlank() }.joinToString(" ")

    fun user(id: Long?): String = when (id) {
        null -> "—"
        else -> users.find { it.id == id }?.fullName ?: "Сотрудник #$id"
    }
}

internal val LocalWmsNames = androidx.compose.runtime.staticCompositionLocalOf { WmsNames() }

/** Собирает подписи: ячейки всех складов и список сотрудников подгружаются один раз. */
@Composable
internal fun rememberWmsNames(
    warehouses: List<org.example.project.dto.WarehouseDto>,
    nomenclature: List<org.example.project.dto.NomenclatureDto>,
    issues: List<org.example.project.dto.IssueOrderDto> = emptyList()
): WmsNames {
    val wmsRepository: org.example.project.data.WmsRepository = koinInject()
    val userRepository: UserRepository = koinInject()
    var locations by remember { mutableStateOf<List<org.example.project.dto.StorageLocationDto>>(emptyList()) }
    var users by remember { mutableStateOf<List<UserOptionDto>>(emptyList()) }
    LaunchedEffect(warehouses.map { it.id }) {
        locations = warehouses.flatMap { warehouse ->
            (wmsRepository.listLocations(warehouse.id) as? ApiResult.Success)?.data.orEmpty()
        }
    }
    LaunchedEffect(Unit) {
        users = (userRepository.listOptions() as? ApiResult.Success)?.data.orEmpty()
    }
    return remember(warehouses, locations, nomenclature, users, issues) {
        WmsNames(warehouses, locations, nomenclature, users, issues)
    }
}

/** Партии выбранной позиции. Поле хранит ID партии строкой, как и формы документов. */
@Composable
internal fun LotPicker(
    value: String,
    onChange: (String) -> Unit,
    nomenclatureId: Long?,
    modifier: Modifier = Modifier,
    label: String = "Партия"
) {
    val repository: org.example.project.data.WmsRepository = koinInject()
    var lots by remember(nomenclatureId) { mutableStateOf<List<org.example.project.dto.LotDto>>(emptyList()) }
    var loadError by remember(nomenclatureId) { mutableStateOf<String?>(null) }
    LaunchedEffect(nomenclatureId) {
        if (nomenclatureId == null) return@LaunchedEffect
        when (val result = repository.listLots(nomenclatureId)) {
            is ApiResult.Success -> lots = result.data
            is ApiResult.Error -> loadError = "Не удалось загрузить партии"
        }
    }
    SearchableDropdown(
        items = lots,
        selected = lots.find { it.id.toString() == value.trim() },
        onSelect = { onChange(it.id.toString()) },
        label = label,
        itemText = { lot ->
            listOfNotNull(
                lot.lotNumber,
                lot.manufactureDate?.let { "изг. ${org.example.project.ui.theme.ErpFormat.dateRu(it)}" },
                lot.expiryDate?.let { "годен до ${org.example.project.ui.theme.ErpFormat.dateRu(it)}" }
            ).joinToString(" · ")
        },
        modifier = modifier,
        emptyText = loadError ?: if (nomenclatureId == null) "Сначала выберите позицию" else "У позиции нет партий",
        onClear = { onChange("") },
        clearText = "Без партии"
    )
}

/** Заказ на выдачу со строками: список выдач приходит без строк. */
@Composable
internal fun rememberIssueDetails(issueId: Long?): org.example.project.dto.IssueOrderDto? {
    val repository: org.example.project.data.WmsRepository = koinInject()
    var issue by remember(issueId) { mutableStateOf<org.example.project.dto.IssueOrderDto?>(null) }
    LaunchedEffect(issueId) {
        if (issueId == null) return@LaunchedEffect
        issue = (repository.getIssue(issueId) as? ApiResult.Success)?.data
    }
    return issue
}

/** Серийные номера позиции (при выбранной партии — только этой партии). */
@Composable
internal fun SerialPicker(
    value: String,
    onChange: (String) -> Unit,
    nomenclatureId: Long?,
    lotId: Long? = null,
    modifier: Modifier = Modifier,
    label: String = "Серийный номер"
) {
    val repository: org.example.project.data.WmsRepository = koinInject()
    var serials by remember(nomenclatureId) { mutableStateOf<List<org.example.project.dto.SerialItemDto>>(emptyList()) }
    var loadError by remember(nomenclatureId) { mutableStateOf<String?>(null) }
    LaunchedEffect(nomenclatureId) {
        if (nomenclatureId == null) return@LaunchedEffect
        when (val result = repository.listSerials(nomenclatureId)) {
            is ApiResult.Success -> serials = result.data
            is ApiResult.Error -> loadError = "Не удалось загрузить серийные номера"
        }
    }
    val visible = if (lotId == null) serials else serials.filter { it.lotId == lotId }
    SearchableDropdown(
        items = visible,
        selected = serials.find { it.id.toString() == value.trim() },
        onSelect = { onChange(it.id.toString()) },
        label = label,
        itemText = { it.serialNumber },
        modifier = modifier,
        emptyText = loadError ?: if (nomenclatureId == null) "Сначала выберите позицию" else "Серийных номеров нет",
        onClear = { onChange("") },
        clearText = "Без серийного номера"
    )
}
