package org.example.project.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.example.project.config.AssistantSettings
import org.example.project.dto.AssistantActionDto
import org.example.project.dto.AssistantActionKind
import org.example.project.dto.AssistantChatRequestDto
import org.example.project.dto.AssistantChatResponseDto
import org.example.project.dto.AssistantConfirmDraftRequestDto
import org.example.project.dto.AssistantConfirmDraftResponseDto
import org.example.project.dto.AssistantConversationDetailDto
import org.example.project.dto.AssistantConversationSummaryDto
import org.example.project.dto.AssistantDraftDto
import org.example.project.dto.AssistantDraftLineDto
import org.example.project.dto.AssistantDraftSupplierDto
import org.example.project.dto.AssistantMessageDto
import org.example.project.dto.AssistantRole
import org.example.project.dto.AssistantStatusDto
import org.example.project.repository.AssistantConversationRepository
import org.example.project.dto.CreateDemandRequest
import org.example.project.dto.CreateNomenclatureRequest
import org.example.project.dto.CreateProjectRequest
import org.example.project.dto.CreateReservationRequest
import org.example.project.dto.CreateStorageLocationRequest
import org.example.project.dto.CreateSupplierRequest
import org.example.project.dto.CreateTaskRequest
import org.example.project.dto.CreateWarehouseRequest
import org.example.project.dto.DemandLineInput
import org.example.project.dto.DemandPriority
import org.example.project.dto.DemandSourceType
import org.example.project.dto.ErpEventDomain
import org.example.project.dto.NomenclatureType
import org.example.project.dto.ProjectPriority
import org.example.project.dto.ProjectType
import org.example.project.dto.ReservationReasonType
import org.example.project.dto.StorageLocationKind
import org.example.project.dto.StorageLocationPurpose
import org.example.project.dto.SupplierKind
import org.example.project.dto.TaskArea
import org.example.project.dto.TaskPriority
import org.example.project.dto.TaskStatus
import org.example.project.dto.TaskWorkType
import org.example.project.exception.ForbiddenException
import org.example.project.exception.ValidationException
import org.example.project.model.CurrentUser
import org.example.project.realtime.ErpEventBus
import java.util.UUID

private const val MAX_HISTORY_MESSAGES = 12
private const val MAX_TOOL_ROUNDS = 5

class AssistantService(
    private val settings: AssistantSettings,
    private val llmProvider: LlmProvider,
    private val searchService: SearchService,
    private val taskService: TaskService,
    private val wmsService: WmsService,
    private val demandService: DemandService,
    private val purchaseOrderService: PurchaseOrderService,
    private val supplierService: SupplierService,
    private val projectService: ProjectService,
    private val productionService: ProductionService,
    private val conversationRepository: AssistantConversationRepository,
    private val erpEventBus: ErpEventBus? = null
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun status(): AssistantStatusDto = llmProvider.status()

    fun listConversations(currentUser: CurrentUser): List<AssistantConversationSummaryDto> =
        conversationRepository.listByUser(currentUser.userId)

    fun getConversation(id: Long, currentUser: CurrentUser): AssistantConversationDetailDto =
        conversationRepository.getDetail(id, currentUser.userId)

    fun deleteConversation(id: Long, currentUser: CurrentUser) =
        conversationRepository.deleteConversation(id, currentUser.userId)

    fun chat(request: AssistantChatRequestDto, currentUser: CurrentUser): AssistantChatResponseDto {
        val cleaned = request.messages
            .map { it.copy(content = it.content.trim()) }
            .filter { it.content.isNotEmpty() || request.attachments.isNotEmpty() }
            .takeLast(MAX_HISTORY_MESSAGES)

        if (cleaned.isEmpty() || cleaned.last().role != AssistantRole.USER) {
            throw ValidationException("Введите сообщение или прикрепите файл для помощника")
        }

        val extracted = AssistantFileExtractor.extract(request.attachments)
        if (cleaned.last().content.isEmpty() && extracted.textBlocks.isEmpty() && extracted.imagesBase64.isEmpty()) {
            throw ValidationException("Введите сообщение или прикрепите файл для помощника")
        }

        val tools = buildToolsForUser(currentUser)
        val draftToolNames = tools.map { it.name }.filter { it.startsWith("prepare_") }
        val systemPrompt = AssistantPlaybooks.buildSystemPrompt(currentUser, draftToolNames)

        val conversation = mutableListOf(
            LlmChatMessage(role = "system", content = systemPrompt)
        )
        cleaned.forEachIndexed { index, msg ->
            val isLastUser = index == cleaned.lastIndex && msg.role == AssistantRole.USER
            val contentText = if (isLastUser && (extracted.textBlocks.isNotEmpty() || extracted.imagesBase64.isNotEmpty())) {
                val basePrompt = msg.content.ifEmpty {
                    "Проанализируй приложенный файл(ы). Если это счёт или документ поставки, выдели ключевые реквизиты и позиции."
                }
                if (extracted.textBlocks.isNotEmpty()) {
                    basePrompt + "\n\n" + extracted.textBlocks.joinToString("\n\n")
                } else {
                    basePrompt
                }
            } else {
                msg.content
            }
            conversation.add(
                LlmChatMessage(
                    role = if (msg.role == AssistantRole.USER) "user" else "assistant",
                    content = contentText,
                    images = if (isLastUser) extracted.imagesBase64 else emptyList()
                )
            )
        }

        val usedTools = linkedSetOf<String>()
        var preparedDraft: AssistantDraftDto? = null
        var finalReplyText: String? = null
        var round = 0
        while (round < MAX_TOOL_ROUNDS) {
            round++
            val result = llmProvider.chat(conversation, tools)
            if (result.toolCalls.isEmpty()) {
                finalReplyText = result.content.trim().ifEmpty {
                    "Не удалось сформировать ответ. Попробуйте переформулировать запрос."
                }
                break
            }

            conversation.add(
                LlmChatMessage(
                    role = "assistant",
                    content = result.content,
                    toolCalls = result.toolCalls
                )
            )

            result.toolCalls.forEach { call ->
                usedTools.add(call.name)
                val (toolOutput, draft) = executeTool(call.name, call.arguments, currentUser)
                if (draft != null) preparedDraft = draft
                conversation.add(
                    LlmChatMessage(
                        role = "tool",
                        content = toolOutput
                    )
                )
            }
        }

        val replyText = finalReplyText ?: llmProvider.chat(conversation, emptyList()).content.trim().ifEmpty {
            "Запрос выполнен, проверьте подготовленный черновик."
        }
        val toolUsedStr = usedTools.takeIf { it.isNotEmpty() }?.joinToString(", ")

        val lastUserMsg = cleaned.last()
        val convId = request.conversationId ?: conversationRepository.createConversation(
            userId = currentUser.userId,
            title = buildConversationTitle(lastUserMsg, request.attachments)
        )
        conversationRepository.appendMessage(convId, currentUser.userId, lastUserMsg)
        val assistantMsg = AssistantMessageDto(
            role = AssistantRole.ASSISTANT,
            content = replyText,
            toolUsed = toolUsedStr,
            draft = preparedDraft
        )
        conversationRepository.appendMessage(convId, currentUser.userId, assistantMsg)

        return AssistantChatResponseDto(
            reply = replyText,
            model = settings.model,
            toolUsed = toolUsedStr,
            draft = preparedDraft,
            conversationId = convId
        )
    }

    private fun buildConversationTitle(
        lastUserMsg: AssistantMessageDto,
        attachments: List<org.example.project.dto.AssistantUploadDto>
    ): String {
        val firstFile = attachments.firstOrNull()?.fileName?.trim()?.takeIf { it.isNotEmpty() }
        val text = lastUserMsg.content.trim()
        val raw = when {
            firstFile != null && text.isNotEmpty() -> "$firstFile — $text"
            firstFile != null -> firstFile
            else -> text
        }
        return raw.replace('\n', ' ').take(80).ifEmpty { "Диалог с помощником" }
    }

    fun confirmDraft(
        request: AssistantConfirmDraftRequestDto,
        currentUser: CurrentUser
    ): AssistantConfirmDraftResponseDto {
        val draft = request.draft
        if (draft.executed) {
            throw ValidationException("Этот черновик уже выполнен")
        }

        val actionsDone = mutableListOf<String>()
        var createdSupplierId: Long? = null
        var createdNomenclatureCount = 0
        var createdDemandNumber: String? = null

        // 1. Поставщик
        val supplierDraft = draft.supplier
        if (supplierDraft != null && supplierDraft.existingSupplierId == null && supplierDraft.createIfMissing) {
            currentUser.requirePermission("suppliers", "create")
            val existing = findSupplierMatch(supplierDraft.inn, supplierDraft.name, currentUser)
            if (existing != null) {
                actionsDone.add("Поставщик уже есть в базе: ${existing.name}")
            } else {
                supplierService.createSupplier(
                    CreateSupplierRequest(
                        name = supplierDraft.name.trim(),
                        fullName = supplierDraft.name.trim(),
                        kind = SupplierKind.SUPPLIER,
                        inn = supplierDraft.inn?.trim()?.takeIf { it.isNotEmpty() },
                        kpp = supplierDraft.kpp?.trim()?.takeIf { it.isNotEmpty() },
                        idempotencyKey = request.idempotencyKey ?: "assistant-supplier-${draft.draftId}"
                    ),
                    currentUser
                )
                createdSupplierId = findSupplierMatch(supplierDraft.inn, supplierDraft.name, currentUser)?.id
                erpEventBus?.publish(ErpEventDomain.SUPPLIER, "CREATED", createdSupplierId)
                actionsDone.add("Создан поставщик: ${supplierDraft.name.trim()}")
            }
        }

        // 2. Номенклатура
        val resolvedLines = mutableListOf<Pair<Long, AssistantDraftLineDto>>()
        val existingNom = if (draft.lines.isNotEmpty() && currentUser.hasPermission("wms", "read")) {
            wmsService.listNomenclature(currentUser)
        } else {
            emptyList()
        }

        draft.lines.forEach { line ->
            val cleanCode = line.code.trim()
            val cleanName = line.name.trim()
            if (cleanName.isEmpty()) return@forEach

            var nomId = line.existingNomenclatureId
            if (nomId == null) {
                val matched = existingNom.firstOrNull { nom ->
                    (cleanCode.isNotEmpty() && nom.code.equals(cleanCode, ignoreCase = true)) ||
                        nom.name.equals(cleanName, ignoreCase = true)
                }
                if (matched != null) {
                    nomId = matched.id
                } else if (line.createIfMissing) {
                    currentUser.requirePermission("wms", "create")
                    val generatedCode = cleanCode.ifEmpty {
                        "AUTO-${UUID.randomUUID().toString().take(8).uppercase()}"
                    }
                    val createdNom = wmsService.createNomenclature(
                        CreateNomenclatureRequest(
                            code = generatedCode,
                            name = cleanName,
                            type = NomenclatureType.PURCHASED_COMPONENT,
                            unit = line.unit.trim().ifEmpty { "шт" }
                        ),
                        currentUser
                    )
                    nomId = createdNom.id
                    createdNomenclatureCount++
                }
            }
            if (nomId != null) {
                resolvedLines.add(nomId to line)
            }
        }
        if (createdNomenclatureCount > 0) {
            erpEventBus?.publish(ErpEventDomain.WMS, "NOMENCLATURE_CREATED")
            actionsDone.add("Создано позиций номенклатуры: $createdNomenclatureCount")
        }

        // 3. Потребность в материалах
        if (draft.createDemand && resolvedLines.isNotEmpty()) {
            currentUser.requirePermission("demands", "create")
            val noteParts = listOfNotNull(
                draft.demandNote?.trim()?.takeIf { it.isNotEmpty() },
                draft.invoiceNumber?.trim()?.takeIf { it.isNotEmpty() }?.let { "По счёту № $it" },
                draft.supplier?.name?.trim()?.takeIf { it.isNotEmpty() }?.let { "Поставщик: $it" }
            ).distinct()
            val demand = demandService.create(
                CreateDemandRequest(
                    sourceType = DemandSourceType.MANUAL,
                    stockpileNote = noteParts.joinToString(" · ").ifEmpty { draft.title },
                    priority = DemandPriority.NORMAL,
                    idempotencyKey = request.idempotencyKey ?: "assistant-${draft.draftId}",
                    lines = resolvedLines.mapIndexed { idx, (nomId, line) ->
                        DemandLineInput(
                            lineNo = idx + 1,
                            nomenclatureId = nomId,
                            quantity = normalizeQty(line.quantity),
                            unit = line.unit.trim().ifEmpty { "шт" }
                        )
                    }
                ),
                currentUser
            )
            createdDemandNumber = demand.header.number
            erpEventBus?.publish(ErpEventDomain.DEMAND, "CREATED", demand.header.id)
            actionsDone.add("Создана потребность ${demand.header.number} (${resolvedLines.size} поз.)")
        }

        // 4. Универсальные действия по модулям ERP (задачи, проекты, склад, резервы)
        draft.actions.forEach { action ->
            actionsDone.add(executeAction(action, currentUser))
        }

        val summary = if (actionsDone.isEmpty()) {
            "Новых изменений не потребовалось — позиции уже присутствуют в системе."
        } else {
            actionsDone.joinToString("\n")
        }

        request.conversationId?.let { convId ->
            conversationRepository.updateDraftExecuted(
                convId,
                currentUser.userId,
                draft.copy(executed = true, executionSummary = summary)
            )
            conversationRepository.appendMessage(
                convId,
                currentUser.userId,
                AssistantMessageDto(role = AssistantRole.ASSISTANT, content = "Готово:\n$summary")
            )
        }

        return AssistantConfirmDraftResponseDto(
            summary = summary,
            createdSupplierId = createdSupplierId,
            createdNomenclatureCount = createdNomenclatureCount,
            createdDemandNumber = createdDemandNumber
        )
    }

    private fun executeAction(action: AssistantActionDto, currentUser: CurrentUser): String {
        val p = action.params
        return when (action.kind) {
            AssistantActionKind.CREATE_TASK -> {
                val title = p["title"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw ValidationException("Не указано название задачи")
                val projectId = p["projectId"]?.toLongOrNull()
                val area = if (projectId != null) TaskArea.PROJECT else TaskArea.PERSONAL
                val priority = p["priority"]?.let { raw ->
                    runCatching { TaskPriority.valueOf(raw.uppercase()) }.getOrNull()
                } ?: TaskPriority.NORMAL
                val task = taskService.create(
                    CreateTaskRequest(
                        title = title,
                        description = p["description"]?.trim()?.takeIf { it.isNotEmpty() },
                        area = area,
                        type = TaskWorkType.GENERAL,
                        projectId = projectId,
                        assigneeId = p["assigneeId"]?.toLongOrNull() ?: currentUser.userId,
                        dueDate = p["dueDate"]?.trim()?.takeIf { it.isNotEmpty() },
                        priority = priority
                    ),
                    currentUser
                )
                erpEventBus?.publish(ErpEventDomain.TASK, "CREATED", task.id)
                "Создана задача ${task.number}: ${task.title}"
            }

            AssistantActionKind.CREATE_PROJECT -> {
                val name = p["name"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw ValidationException("Не указано название проекта")
                val priority = p["priority"]?.let { raw ->
                    runCatching { ProjectPriority.valueOf(raw.uppercase()) }.getOrNull()
                } ?: ProjectPriority.NORMAL
                val projectType = p["projectType"]?.let { raw ->
                    runCatching { ProjectType.valueOf(raw.uppercase()) }.getOrNull()
                } ?: ProjectType.INTERNAL
                val project = projectService.create(
                    CreateProjectRequest(
                        name = name,
                        projectType = projectType,
                        description = p["description"]?.trim()?.takeIf { it.isNotEmpty() },
                        customer = p["customer"]?.trim()?.takeIf { it.isNotEmpty() },
                        ownerId = currentUser.userId,
                        priority = priority
                    ),
                    currentUser
                )
                erpEventBus?.publish(ErpEventDomain.PROJECT, "CREATED", project.id)
                "Создан проект ${project.number}: ${project.name}"
            }

            AssistantActionKind.CREATE_WAREHOUSE -> {
                val code = p["code"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw ValidationException("Не указан код склада")
                val name = p["name"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw ValidationException("Не указано название склада")
                val wh = wmsService.createWarehouse(
                    CreateWarehouseRequest(code = code, name = name),
                    currentUser
                )
                erpEventBus?.publish(ErpEventDomain.WMS, "WAREHOUSE_CREATED", wh.id)
                "Создан склад ${wh.code}: ${wh.name}"
            }

            AssistantActionKind.CREATE_LOCATION -> {
                val warehouseId = p["warehouseId"]?.toLongOrNull()
                    ?: throw ValidationException("Не указан warehouseId для ячейки")
                val code = p["code"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw ValidationException("Не указан код ячейки")
                val loc = wmsService.createLocation(
                    CreateStorageLocationRequest(
                        warehouseId = warehouseId,
                        code = code,
                        name = p["name"]?.trim()?.takeIf { it.isNotEmpty() } ?: code,
                        kind = StorageLocationKind.CELL,
                        purpose = StorageLocationPurpose.STORAGE
                    ),
                    currentUser
                )
                erpEventBus?.publish(ErpEventDomain.WMS, "LOCATION_CREATED", loc.id)
                "Создана ячейка ${loc.code}"
            }

            AssistantActionKind.CREATE_RESERVATION -> {
                val nomId = p["nomenclatureId"]?.toLongOrNull()
                    ?: throw ValidationException("Не удалось определить номенклатуру для резерва")
                val whId = p["warehouseId"]?.toLongOrNull()
                    ?: throw ValidationException("Не удалось определить склад для резерва")
                val locId = p["locationId"]?.toLongOrNull()
                    ?: throw ValidationException("Не удалось определить ячейку для резерва")
                val qty = normalizeQty(p["quantity"] ?: "1")
                val projectId = p["projectId"]?.toLongOrNull()
                val reasonType = if (projectId != null) ReservationReasonType.PROJECT else ReservationReasonType.STOCKPILE
                val res = wmsService.createReservation(
                    CreateReservationRequest(
                        reasonType = reasonType,
                        projectId = projectId,
                        reasonNote = p["note"]?.trim()?.takeIf { it.isNotEmpty() },
                        nomenclatureId = nomId,
                        warehouseId = whId,
                        locationId = locId,
                        quantity = qty,
                        ownerId = currentUser.userId
                    ),
                    currentUser
                )
                erpEventBus?.publish(ErpEventDomain.WMS, "RESERVATION_CREATED", res.id)
                "Создан резерв #${res.id} (${res.quantity} шт.)"
            }
        }
    }

    private fun buildToolsForUser(currentUser: CurrentUser): List<LlmToolDefinition> {
        val list = mutableListOf(
            toolDef(
                name = "search_erp",
                description = "Поиск по всей ERP (проекты, задачи, номенклатура, заказы, потребности, документы, поставщики).",
                props = mapOf("query" to "Поисковый запрос: название, номер, артикул или ключевое слово"),
                required = listOf("query")
            ),
            toolDef(
                name = "my_tasks",
                description = "Открытые и просроченные задачи текущего пользователя.",
                props = emptyMap(),
                required = emptyList()
            ),
            toolDef(
                name = "projects_list",
                description = "Список проектов предприятия (ID, номер, название, статус).",
                props = emptyMap(),
                required = emptyList()
            )
        )

        if (currentUser.hasPermission("wms", "read")) {
            list.add(
                toolDef(
                    name = "wms_stock",
                    description = "Номенклатура, остатки, склады и ячейки хранения по названию или коду.",
                    props = mapOf("query" to "Название или код номенклатуры (пустая строка — общий список)"),
                    required = listOf("query")
                )
            )
            list.add(
                toolDef(
                    name = "wms_low_stock",
                    description = "Позиции с критически низким остатком (ниже минимума/страхового запаса).",
                    props = emptyMap(),
                    required = emptyList()
                )
            )
        }

        if (currentUser.hasPermission("suppliers", "read")) {
            list.add(
                toolDef(
                    name = "check_supplier",
                    description = "Проверить поставщика в базе ERP по ИНН или названию.",
                    props = mapOf("inn" to "ИНН поставщика", "name" to "Название поставщика"),
                    required = emptyList()
                )
            )
        }

        if (currentUser.hasPermission("demands", "read") || currentUser.hasPermission("demands", "read_any")) {
            list.add(
                toolDef(
                    name = "demands_list",
                    description = "Список последних потребностей в материалах и их статусы.",
                    props = emptyMap(),
                    required = emptyList()
                )
            )
        }

        if (currentUser.hasPermission("suppliers", "read") || currentUser.hasPermission("purchase_orders", "read")) {
            list.add(
                toolDef(
                    name = "purchase_orders",
                    description = "Список последних заказов поставщикам и их статусы.",
                    props = emptyMap(),
                    required = emptyList()
                )
            )
        }

        if (currentUser.hasPermission("production", "read")) {
            list.add(
                toolDef(
                    name = "production_orders",
                    description = "Список производственных заказов и их статусы.",
                    props = emptyMap(),
                    required = emptyList()
                )
            )
        }

        if (currentUser.hasPermission("suppliers", "create") ||
            currentUser.hasPermission("wms", "create") ||
            currentUser.hasPermission("demands", "create")
        ) {
            list.add(
                LlmToolDefinition(
                    name = "prepare_supply_draft",
                    description = "Подготовить проверяемый черновик для создания поставщика, недостающей номенклатуры и потребности.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("title", buildJsonObject { put("type", "string"); put("description", "Заголовок черновика") })
                            put("invoiceNumber", buildJsonObject { put("type", "string"); put("description", "Номер счёта") })
                            put("invoiceDate", buildJsonObject { put("type", "string"); put("description", "Дата счёта") })
                            put("supplierName", buildJsonObject { put("type", "string"); put("description", "Наименование поставщика") })
                            put("supplierInn", buildJsonObject { put("type", "string"); put("description", "ИНН поставщика") })
                            put("supplierKpp", buildJsonObject { put("type", "string"); put("description", "КПП поставщика") })
                            put("createDemand", buildJsonObject { put("type", "boolean"); put("description", "Создать потребность по позициям") })
                            put("items", buildJsonObject {
                                put("type", "array")
                                put("description", "Позиции счёта/запроса")
                                put("items", buildJsonObject {
                                    put("type", "object")
                                    put("properties", buildJsonObject {
                                        put("code", buildJsonObject { put("type", "string"); put("description", "Код или артикул") })
                                        put("name", buildJsonObject { put("type", "string"); put("description", "Наименование") })
                                        put("unit", buildJsonObject { put("type", "string"); put("description", "Ед. изм.") })
                                        put("quantity", buildJsonObject { put("type", "string"); put("description", "Количество") })
                                        put("unitPrice", buildJsonObject { put("type", "string"); put("description", "Цена за единицу") })
                                    })
                                    put("required", buildJsonArray { add(JsonPrimitive("name")) })
                                })
                            })
                        })
                        put("required", buildJsonArray { add(JsonPrimitive("title")); add(JsonPrimitive("items")) })
                    }
                )
            )
        }

        if (currentUser.hasPermission("tasks", "create")) {
            list.add(
                toolDef(
                    name = "prepare_task_draft",
                    description = "Подготовить черновик создания новой задачи в ERP.",
                    props = mapOf(
                        "title" to "Название задачи",
                        "description" to "Подробное описание задачи",
                        "projectId" to "ID проекта (необязательно)",
                        "dueDate" to "Срок в формате YYYY-MM-DD (необязательно)",
                        "priority" to "Приоритет: LOW, NORMAL, HIGH, CRITICAL"
                    ),
                    required = listOf("title")
                )
            )
        }

        if (currentUser.hasPermission("projects", "create")) {
            list.add(
                toolDef(
                    name = "prepare_project_draft",
                    description = "Подготовить черновик создания нового проекта в ERP.",
                    props = mapOf(
                        "name" to "Название проекта",
                        "projectType" to "Тип: INTERNAL, ORDER_DEVELOPMENT, REPEAT_PRODUCTION, MODERNIZATION",
                        "customer" to "Заказчик (необязательно)",
                        "description" to "Описание проекта (необязательно)",
                        "priority" to "Приоритет: LOW, NORMAL, HIGH, CRITICAL"
                    ),
                    required = listOf("name")
                )
            )
        }

        if (currentUser.hasPermission("wms", "create") || currentUser.hasPermission("wms", "reserve")) {
            list.add(
                toolDef(
                    name = "prepare_wms_action_draft",
                    description = "Подготовить складской черновик: создать склад (CREATE_WAREHOUSE), ячейку (CREATE_LOCATION) или резерв материала (CREATE_RESERVATION).",
                    props = mapOf(
                        "actionKind" to "CREATE_WAREHOUSE, CREATE_LOCATION или CREATE_RESERVATION",
                        "code" to "Код склада/ячейки или код номенклатуры для резерва",
                        "name" to "Название склада/ячейки или название номенклатуры",
                        "warehouseCode" to "Код склада (для ячейки)",
                        "quantity" to "Количество для резерва",
                        "projectId" to "ID проекта для резерва (необязательно)",
                        "note" to "Комментарий/основание"
                    ),
                    required = listOf("actionKind")
                )
            )
        }

        return list
    }

    private fun executeTool(
        name: String,
        args: JsonObject,
        currentUser: CurrentUser
    ): Pair<String, AssistantDraftDto?> {
        return try {
            when (name) {
                "search_erp" -> {
                    val q = args["query"]?.jsonPrimitive?.content?.trim().orEmpty()
                    if (q.isEmpty()) return """{"error":"Пустой поисковый запрос"}""" to null
                    val res = searchService.search(q, 8, currentUser)
                    buildJsonObject {
                        put("query", res.query)
                        put("groups", buildJsonArray {
                            res.groups.filter { it.items.isNotEmpty() }.forEach { group ->
                                add(buildJsonObject {
                                    put("type", group.type.name)
                                    put("items", buildJsonArray {
                                        group.items.take(6).forEach { item ->
                                            add(buildJsonObject {
                                                put("id", item.id)
                                                item.number?.let { put("number", it) }
                                                put("title", item.title)
                                                item.status?.let { put("status", it) }
                                                item.subtitle?.let { put("subtitle", it) }
                                            })
                                        }
                                    })
                                })
                            }
                        })
                    }.toString() to null
                }
                "my_tasks" -> {
                    val tasks = taskService.list(currentUser)
                        .filter {
                            it.assigneeId == currentUser.userId ||
                                it.reviewerId == currentUser.userId ||
                                currentUser.userId in it.coexecutorIds
                        }
                        .filter { it.status != TaskStatus.CLOSED && it.status != TaskStatus.CANCELLED }
                        .take(15)
                    buildJsonObject {
                        put("count", tasks.size)
                        put("tasks", buildJsonArray {
                            tasks.forEach { t ->
                                add(buildJsonObject {
                                    put("number", t.number)
                                    put("title", t.title)
                                    put("status", t.status.name)
                                    t.dueDate?.let { put("dueDate", it) }
                                })
                            }
                        })
                    }.toString() to null
                }
                "projects_list" -> {
                    val projects = projectService.list(currentUser).take(15)
                    buildJsonObject {
                        put("projects", buildJsonArray {
                            projects.forEach { p ->
                                add(buildJsonObject {
                                    put("id", p.id)
                                    put("number", p.number)
                                    put("name", p.name)
                                    put("status", p.status.name)
                                    p.customer?.let { put("customer", it) }
                                })
                            }
                        })
                    }.toString() to null
                }
                "wms_stock" -> {
                    val q = args["query"]?.jsonPrimitive?.content?.trim()?.lowercase().orEmpty()
                    val nomenclature = wmsService.listNomenclature(currentUser)
                        .filter { !it.archived }
                        .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.code.lowercase().contains(q) }
                        .take(10)
                    val stockByNom = wmsService.getStock(warehouseId = null, nomenclatureId = null, currentUser = currentUser)
                        .groupBy { it.nomenclatureId }
                    buildJsonObject {
                        put("items", buildJsonArray {
                            nomenclature.forEach { nom ->
                                val balances = stockByNom[nom.id].orEmpty()
                                add(buildJsonObject {
                                    put("code", nom.code)
                                    put("name", nom.name)
                                    put("unit", nom.unit)
                                    put("balances", buildJsonArray {
                                        balances.take(5).forEach { b ->
                                            add(buildJsonObject {
                                                put("cell", b.locationCode)
                                                put("physicalQty", b.physicalQty)
                                                put("reservedQty", b.reservedQty)
                                                put("availableQty", b.availableQty)
                                            })
                                        }
                                    })
                                })
                            }
                        })
                    }.toString() to null
                }
                "wms_low_stock" -> {
                    val low = wmsService.listLowStock(currentUser).take(15)
                    buildJsonObject {
                        put("lowStock", buildJsonArray {
                            low.forEach { item ->
                                add(buildJsonObject {
                                    put("code", item.code)
                                    put("name", item.name)
                                    put("availableQty", item.availableQty)
                                    put("minStock", item.minStock)
                                })
                            }
                        })
                    }.toString() to null
                }
                "check_supplier" -> {
                    val inn = args["inn"]?.jsonPrimitive?.content?.trim()
                    val sName = args["name"]?.jsonPrimitive?.content?.trim()
                    val match = findSupplierMatch(inn, sName, currentUser)
                    if (match != null) {
                        buildJsonObject {
                            put("found", true)
                            put("id", match.id)
                            put("name", match.name)
                            match.inn?.let { put("inn", it) }
                            match.kpp?.let { put("kpp", it) }
                        }.toString() to null
                    } else {
                        """{"found":false}""" to null
                    }
                }
                "demands_list" -> {
                    val demands = demandService.list(currentUser).take(10)
                    buildJsonObject {
                        put("demands", buildJsonArray {
                            demands.forEach { d ->
                                add(buildJsonObject {
                                    put("number", d.number)
                                    put("status", d.status.name)
                                    put("sourceType", d.sourceType.name)
                                    d.requiredDate?.let { put("requiredDate", it) }
                                })
                            }
                        })
                    }.toString() to null
                }
                "purchase_orders" -> {
                    val orders = purchaseOrderService.listOrders(null, null, null, currentUser).take(10)
                    buildJsonObject {
                        put("orders", buildJsonArray {
                            orders.forEach { o ->
                                add(buildJsonObject {
                                    put("number", o.number)
                                    o.supplierName?.let { put("supplier", it) }
                                    put("status", o.status.name)
                                })
                            }
                        })
                    }.toString() to null
                }
                "production_orders" -> {
                    val orders = productionService.list(null, null, null, currentUser).take(10)
                    buildJsonObject {
                        put("orders", buildJsonArray {
                            orders.forEach { o ->
                                add(buildJsonObject {
                                    put("number", o.number)
                                    put("status", o.status.name)
                                    put("plannedQuantity", o.plannedQuantity)
                                    put("actualQuantity", o.actualQuantity)
                                })
                            }
                        })
                    }.toString() to null
                }
                "prepare_supply_draft" -> buildSupplyDraft(args, currentUser)
                "prepare_task_draft" -> buildTaskDraft(args)
                "prepare_project_draft" -> buildProjectDraft(args)
                "prepare_wms_action_draft" -> buildWmsActionDraft(args, currentUser)
                else -> """{"error":"Неизвестный инструмент: $name"}""" to null
            }
        } catch (e: ForbiddenException) {
            """{"error":"Недостаточно прав для этого запроса"}""" to null
        } catch (e: Exception) {
            """{"error":"Ошибка выполнения инструмента: ${e.message ?: "неизвестная ошибка"}"}""" to null
        }
    }

    private fun buildTaskDraft(args: JsonObject): Pair<String, AssistantDraftDto> {
        val title = args["title"]?.jsonPrimitive?.content?.trim()?.ifEmpty { null } ?: "Новая задача"
        val params = buildMap {
            put("title", title)
            args["description"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }?.let { put("description", it) }
            args["projectId"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }?.let { put("projectId", it) }
            args["dueDate"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }?.let { put("dueDate", it) }
            args["priority"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }?.let { put("priority", it) }
        }
        val draft = AssistantDraftDto(
            draftId = UUID.randomUUID().toString().take(8),
            title = "Новая задача",
            summary = title,
            createDemand = false,
            actions = listOf(
                AssistantActionDto(
                    kind = AssistantActionKind.CREATE_TASK,
                    label = "Создать задачу: $title",
                    params = params
                )
            )
        )
        return """{"status":"draft_ready","draftId":"${draft.draftId}"}""" to draft
    }

    private fun buildProjectDraft(args: JsonObject): Pair<String, AssistantDraftDto> {
        val name = args["name"]?.jsonPrimitive?.content?.trim()?.ifEmpty { null } ?: "Новый проект"
        val params = buildMap {
            put("name", name)
            args["projectType"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }?.let { put("projectType", it) }
            args["customer"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }?.let { put("customer", it) }
            args["description"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }?.let { put("description", it) }
            args["priority"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }?.let { put("priority", it) }
        }
        val draft = AssistantDraftDto(
            draftId = UUID.randomUUID().toString().take(8),
            title = "Новый проект",
            summary = name,
            createDemand = false,
            actions = listOf(
                AssistantActionDto(
                    kind = AssistantActionKind.CREATE_PROJECT,
                    label = "Создать проект: $name",
                    params = params
                )
            )
        )
        return """{"status":"draft_ready","draftId":"${draft.draftId}"}""" to draft
    }

    private fun buildWmsActionDraft(args: JsonObject, currentUser: CurrentUser): Pair<String, AssistantDraftDto?> {
        val kindStr = args["actionKind"]?.jsonPrimitive?.content?.trim()?.uppercase().orEmpty()
        val kind = runCatching { AssistantActionKind.valueOf(kindStr) }.getOrNull()
            ?: return """{"error":"Неизвестный тип складского действия: $kindStr"}""" to null

        val params = mutableMapOf<String, String>()
        args["code"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }?.let { params["code"] = it }
        args["name"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }?.let { params["name"] = it }
        args["quantity"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }?.let { params["quantity"] = normalizeQty(it) }
        args["projectId"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }?.let { params["projectId"] = it }
        args["note"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }?.let { params["note"] = it }

        if (kind == AssistantActionKind.CREATE_LOCATION) {
            val whCode = args["warehouseCode"]?.jsonPrimitive?.content?.trim()
            val warehouses = wmsService.listWarehouses(currentUser)
            val wh = warehouses.firstOrNull { w ->
                whCode.isNullOrEmpty() || w.code.equals(whCode, ignoreCase = true) || w.name.contains(whCode, ignoreCase = true)
            }
            wh?.let { params["warehouseId"] = it.id.toString() }
        }

        if (kind == AssistantActionKind.CREATE_RESERVATION) {
            val q = (params["code"] ?: params["name"]).orEmpty().lowercase()
            val nom = wmsService.listNomenclature(currentUser).firstOrNull {
                !it.archived && (it.code.lowercase() == q || it.name.lowercase().contains(q))
            }
            if (nom != null) {
                params["nomenclatureId"] = nom.id.toString()
                params["name"] = nom.name
                val bal = wmsService.getStock(null, nom.id, currentUser).firstOrNull {
                    (it.availableQty.toDoubleOrNull() ?: 0.0) > 0.0
                }
                if (bal != null) {
                    params["warehouseId"] = bal.warehouseId.toString()
                    params["locationId"] = bal.locationId.toString()
                    params["cell"] = bal.locationCode
                }
            }
        }

        val label = when (kind) {
            AssistantActionKind.CREATE_WAREHOUSE -> "Создать склад ${params["code"].orEmpty()} ${params["name"].orEmpty()}".trim()
            AssistantActionKind.CREATE_LOCATION -> "Создать ячейку ${params["code"].orEmpty()}".trim()
            AssistantActionKind.CREATE_RESERVATION -> "Зарезервировать ${params["name"] ?: params["code"] ?: "материал"} (${params["quantity"] ?: "1"} шт.)"
            else -> kind.name
        }

        val draft = AssistantDraftDto(
            draftId = UUID.randomUUID().toString().take(8),
            title = "Складская операция",
            summary = label,
            createDemand = false,
            actions = listOf(AssistantActionDto(kind = kind, label = label, params = params))
        )
        return """{"status":"draft_ready","draftId":"${draft.draftId}"}""" to draft
    }

    private fun buildSupplyDraft(
        args: JsonObject,
        currentUser: CurrentUser
    ): Pair<String, AssistantDraftDto> {
        val title = args["title"]?.jsonPrimitive?.content?.trim()?.ifEmpty { null }
            ?: "Черновик поступления / потребности"
        val invoiceNumber = args["invoiceNumber"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val invoiceDate = args["invoiceDate"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val supplierName = args["supplierName"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val supplierInn = args["supplierInn"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val supplierKpp = args["supplierKpp"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val createDemand = args["createDemand"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true

        val supplierDto = if (supplierName != null) {
            val matched = if (currentUser.hasPermission("suppliers", "read")) {
                findSupplierMatch(supplierInn, supplierName, currentUser)
            } else null
            AssistantDraftSupplierDto(
                name = supplierName,
                inn = supplierInn,
                kpp = supplierKpp,
                existingSupplierId = matched?.id,
                createIfMissing = currentUser.hasPermission("suppliers", "create")
            )
        } else null

        val itemsArray: JsonArray = when (val el = args["items"]) {
            is JsonArray -> el
            is JsonPrimitive -> runCatching { json.parseToJsonElement(el.content).jsonArray }.getOrElse { JsonArray(emptyList()) }
            else -> JsonArray(emptyList())
        }

        val allNomenclature = if (currentUser.hasPermission("wms", "read")) {
            wmsService.listNomenclature(currentUser).filter { !it.archived }
        } else emptyList()
        val stockByNom = if (currentUser.hasPermission("wms", "read")) {
            wmsService.getStock(null, null, currentUser).groupBy { it.nomenclatureId }
        } else emptyMap()

        val draftLines = itemsArray.mapNotNull { itemEl ->
            val obj = itemEl as? JsonObject ?: return@mapNotNull null
            val itemName = obj["name"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (itemName.isEmpty()) return@mapNotNull null
            val itemCode = obj["code"]?.jsonPrimitive?.content?.trim().orEmpty()
            val itemUnit = obj["unit"]?.jsonPrimitive?.content?.trim()?.ifEmpty { null } ?: "шт"
            val itemQty = normalizeQty(obj["quantity"]?.jsonPrimitive?.content ?: "1")
            val itemPrice = obj["unitPrice"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }

            val matchedNom = allNomenclature.firstOrNull { nom ->
                (itemCode.isNotEmpty() && nom.code.equals(itemCode, ignoreCase = true)) ||
                    nom.name.equals(itemName, ignoreCase = true)
            }
            val stockTotal = matchedNom?.let { nom ->
                stockByNom[nom.id]?.sumOf { it.availableQty.toDoubleOrNull() ?: 0.0 }
            }?.let { qty -> if (qty % 1.0 == 0.0) qty.toInt().toString() else qty.toString() }

            AssistantDraftLineDto(
                code = itemCode.ifEmpty { matchedNom?.code ?: "" },
                name = itemName,
                unit = itemUnit,
                quantity = itemQty,
                unitPrice = itemPrice,
                existingNomenclatureId = matchedNom?.id,
                inStockQty = stockTotal,
                createIfMissing = currentUser.hasPermission("wms", "create")
            )
        }

        val missingNomCount = draftLines.count { it.existingNomenclatureId == null }
        val summaryParts = buildList {
            supplierDto?.let {
                if (it.existingSupplierId != null) add("Поставщик найден в базе: ${it.name}")
                else add("Новый поставщик: ${it.name}")
            }
            add("Позиций: ${draftLines.size} (новых в справочнике: $missingNomCount)")
            if (createDemand && currentUser.hasPermission("demands", "create")) {
                add("Будет создана потребность в материалах")
            }
        }

        val draft = AssistantDraftDto(
            draftId = UUID.randomUUID().toString().take(8),
            title = title,
            summary = summaryParts.joinToString(" · "),
            invoiceNumber = invoiceNumber,
            invoiceDate = invoiceDate,
            supplier = supplierDto,
            lines = draftLines,
            createDemand = createDemand && currentUser.hasPermission("demands", "create"),
            demandNote = invoiceNumber?.let { "По счёту № $it" }
        )

        val resultJson = buildJsonObject {
            put("status", "draft_ready")
            put("draftId", draft.draftId)
            put("summary", draft.summary)
            put("newSupplier", supplierDto != null && supplierDto.existingSupplierId == null)
            put("newNomenclatureCount", missingNomCount)
            put("totalLines", draftLines.size)
        }.toString()

        return resultJson to draft
    }

    private fun findSupplierMatch(
        inn: String?,
        name: String?,
        currentUser: CurrentUser
    ) = try {
        val all = supplierService.listSuppliers(null, null, false, currentUser)
        val cleanInn = inn?.trim()?.takeIf { it.isNotEmpty() }
        val cleanName = name?.trim()?.takeIf { it.isNotEmpty() }
        all.firstOrNull { s ->
            (cleanInn != null && s.inn == cleanInn) ||
                (cleanName != null && (s.name.equals(cleanName, ignoreCase = true) ||
                    s.name.contains(cleanName, ignoreCase = true)))
        }
    } catch (_: Exception) {
        null
    }

    private fun normalizeQty(raw: String): String {
        val cleaned = raw.trim().replace(',', '.')
        val num = cleaned.toDoubleOrNull() ?: return "1"
        if (num <= 0.0) return "1"
        return if (num % 1.0 == 0.0) num.toInt().toString() else cleaned
    }

    private fun toolDef(
        name: String,
        description: String,
        props: Map<String, String>,
        required: List<String>
    ): LlmToolDefinition = LlmToolDefinition(
        name = name,
        description = description,
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                props.forEach { (key, desc) ->
                    put(key, buildJsonObject {
                        put("type", "string")
                        put("description", desc)
                    })
                }
            })
            put("required", JsonArray(required.map { JsonPrimitive(it) }))
        }
    )
}
