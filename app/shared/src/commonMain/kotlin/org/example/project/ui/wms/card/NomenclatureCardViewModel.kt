package org.example.project.ui.wms.card

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.project.data.ApiResult
import org.example.project.data.AttachmentRepository
import org.example.project.data.BomRepository
import org.example.project.data.DemandRepository
import org.example.project.data.DocumentRepository
import org.example.project.dto.CreateDocumentLinkRequest
import org.example.project.dto.DocumentLinkType
import org.example.project.data.NomenclatureCardRepository
import org.example.project.data.WmsRepository
import org.example.project.dto.ApiError
import org.example.project.dto.AttachmentEntityType
import org.example.project.dto.CreateDemandRequest
import org.example.project.dto.CreateNomenclatureAnalogRequest
import org.example.project.dto.CreateNomenclatureBarcodeRequest
import org.example.project.dto.CreateNomenclatureCategoryRequest
import org.example.project.dto.CreateUnitConversionRequest
import org.example.project.dto.DemandLineInput
import org.example.project.dto.DemandPriority
import org.example.project.dto.DemandSourceType
import org.example.project.dto.DocumentDto
import org.example.project.dto.NomenclatureCardDto
import org.example.project.dto.NomenclatureCategoryDto
import org.example.project.dto.NomenclatureHistoryEntryDto
import org.example.project.dto.NomenclatureReservationRowDto
import org.example.project.dto.NomenclatureStockPointDto
import org.example.project.dto.NomenclatureUsageDto
import org.example.project.dto.ProductModelDto
import org.example.project.dto.StockMovementDto
import org.example.project.dto.StockMovementType
import org.example.project.dto.UpdateNomenclatureRequest
import org.example.project.dto.WarehouseDto
import kotlin.random.Random

enum class NomenclatureCardTab(val title: String) {
    OVERVIEW("Обзор"),
    STOCK("Остатки"),
    SUPPLY("Снабжение"),
    RESERVATIONS("Резервы"),
    MOVEMENTS("Движения"),
    USAGE("Применяемость"),
    PRODUCT("Изделие"),
    FILES("Документы и файлы"),
    HISTORY("История")
}

/** Состояние вкладки, которая загружается по требованию. */
sealed interface TabData<out T> {
    data object Idle : TabData<Nothing>
    data object Loading : TabData<Nothing>
    data class Ready<T>(val value: T) : TabData<T>
    data class Failed(val error: ApiError) : TabData<Nothing>
}

data class MovementsState(
    val items: List<StockMovementDto> = emptyList(),
    val type: StockMovementType? = null,
    val nextBefore: Long? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: ApiError? = null
)

data class NomenclatureCardUiState(
    val nomenclatureId: Long,
    val card: NomenclatureCardDto? = null,
    val loading: Boolean = true,
    val loadError: ApiError? = null,
    val tab: NomenclatureCardTab = NomenclatureCardTab.OVERVIEW,
    val stockHistoryDays: Int = 90,
    val stockHistory: TabData<List<NomenclatureStockPointDto>> = TabData.Idle,
    val movements: MovementsState = MovementsState(),
    val reservations: TabData<List<NomenclatureReservationRowDto>> = TabData.Idle,
    val usage: TabData<NomenclatureUsageDto> = TabData.Idle,
    val history: TabData<List<NomenclatureHistoryEntryDto>> = TabData.Idle,
    val documents: TabData<List<DocumentDto>> = TabData.Idle,
    /** Документы реестра, доступные текущему пользователю, — для привязки к позиции. */
    val documentCatalog: List<DocumentDto> = emptyList(),
    val photo: ByteArray? = null,
    /** Растёт при каждом перечитывании вложений: секция файлов пересоздаётся и грузит список заново. */
    val attachmentsVersion: Int = 0,
    val categories: List<NomenclatureCategoryDto> = emptyList(),
    val productModels: List<ProductModelDto> = emptyList(),
    val warehouses: List<WarehouseDto> = emptyList(),
    val busy: Boolean = false,
    val actionError: ApiError? = null,
    val notice: String? = null,
    /** Растёт после каждой успешной команды: диалоги закрываются по его смене. */
    val successTick: Int = 0
) {
    val visibleTabs: List<NomenclatureCardTab>
        get() = NomenclatureCardTab.entries.filter { it != NomenclatureCardTab.PRODUCT || card?.productModel != null }
}

class NomenclatureCardViewModel(
    private val nomenclatureId: Long,
    private val repository: NomenclatureCardRepository,
    private val attachments: AttachmentRepository,
    private val demands: DemandRepository,
    private val bom: BomRepository,
    private val wms: WmsRepository,
    private val documentRegistry: DocumentRepository
) : ViewModel() {

    private val _state = MutableStateFlow(NomenclatureCardUiState(nomenclatureId))
    val state: StateFlow<NomenclatureCardUiState> = _state.asStateFlow()

    // Поколения запросов: ответ, пришедший после сброса списка, смены фильтра или
    // повторной загрузки фото, отбрасывается и не смешивается с новыми данными.
    private var movementsJob: Job? = null
    private var movementsGeneration = 0
    private var photoJob: Job? = null

    init {
        reload()
        loadReferenceData()
    }

    fun reload() {
        cancelMovements()
        _state.value = _state.value.copy(
            loading = _state.value.card == null,
            loadError = null,
            stockHistory = TabData.Idle,
            movements = MovementsState(type = _state.value.movements.type),
            reservations = TabData.Idle,
            usage = TabData.Idle,
            history = TabData.Idle,
            documents = TabData.Idle
        )
        viewModelScope.launch {
            when (val result = repository.getCard(nomenclatureId)) {
                is ApiResult.Success -> {
                    _state.value = _state.value.copy(card = result.data, loading = false)
                    ensureTabVisible()
                    loadTab(_state.value.tab)
                }
                is ApiResult.Error -> _state.value = _state.value.copy(loading = false, loadError = result.error)
            }
        }
        loadPhoto()
    }

    fun selectTab(tab: NomenclatureCardTab) {
        _state.value = _state.value.copy(tab = tab)
        loadTab(tab)
    }

    fun dismissMessages() {
        _state.value = _state.value.copy(actionError = null, notice = null)
    }

    fun setStockHistoryDays(days: Int) {
        if (days == _state.value.stockHistoryDays) return
        _state.value = _state.value.copy(stockHistoryDays = days, stockHistory = TabData.Idle)
        loadStockHistory()
    }

    fun filterMovements(type: StockMovementType?) {
        cancelMovements()
        _state.value = _state.value.copy(movements = MovementsState(type = type))
        loadMovements(reset = true)
    }

    fun loadMoreMovements() = loadMovements(reset = false)

    fun refreshPhoto() = loadPhoto()

    fun dispose() = viewModelScope.cancel()

    // --- Команды ---

    fun save(request: UpdateNomenclatureRequest) = command(
        success = "Карточка сохранена",
        reloadAfter = false
    ) {
        when (val result = repository.updateCard(nomenclatureId, request)) {
            is ApiResult.Success -> {
                _state.value = _state.value.copy(
                    card = result.data,
                    history = TabData.Idle,
                    usage = TabData.Idle
                )
                ensureTabVisible()
                loadTab(_state.value.tab)
                null
            }
            is ApiResult.Error -> result.error
        }
    }

    fun archive() = command(success = "Позиция переведена в архив") {
        (repository.archive(nomenclatureId) as? ApiResult.Error)?.error
    }

    /** [onSuccess] вызывается только после успеха: при ошибке сервера введённое значение остаётся (N-11). */
    fun addBarcode(code: String, onSuccess: () -> Unit = {}) = command(success = "Штрихкод $code добавлен", onSuccess = onSuccess) {
        (repository.addBarcode(nomenclatureId, CreateNomenclatureBarcodeRequest(code)) as? ApiResult.Error)?.error
    }

    fun deleteBarcode(barcodeId: Long) = command(success = "Штрихкод удалён") {
        (repository.deleteBarcode(nomenclatureId, barcodeId) as? ApiResult.Error)?.error
    }

    fun addUnitConversion(fromUnit: String, factor: String, onSuccess: () -> Unit = {}) =
        command(success = "Пересчёт добавлен", onSuccess = onSuccess) {
        val unit = _state.value.card?.item?.unit ?: return@command ApiError("NO_CARD", "Карточка не загружена")
        (repository.addUnitConversion(
            nomenclatureId,
            CreateUnitConversionRequest(fromUnit = fromUnit, toUnit = unit, factor = factor.trim().replace(',', '.'))
        ) as? ApiResult.Error)?.error
    }

    fun deleteUnitConversion(id: Long) = command(success = "Пересчёт удалён") {
        (repository.deleteUnitConversion(nomenclatureId, id) as? ApiResult.Error)?.error
    }

    fun addAnalog(request: CreateNomenclatureAnalogRequest) = command(success = "Аналог добавлен") {
        (repository.addAnalog(nomenclatureId, request) as? ApiResult.Error)?.error
    }

    fun deleteAnalog(id: Long) = command(success = "Аналог удалён") {
        (repository.deleteAnalog(nomenclatureId, id) as? ApiResult.Error)?.error
    }

    fun linkDocument(documentId: Long) = command(success = "Документ привязан к позиции") {
        (documentRegistry.addLink(
            documentId,
            CreateDocumentLinkRequest(DocumentLinkType.NOMENCLATURE, nomenclatureId)
        ) as? ApiResult.Error)?.error
    }

    fun createCategory(code: String, name: String, parentId: Long?, onCreated: (NomenclatureCategoryDto) -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, actionError = null)
            when (val result = repository.createCategory(CreateNomenclatureCategoryRequest(code, name, parentId))) {
                is ApiResult.Success -> {
                    _state.value = _state.value.copy(busy = false, notice = "Категория «${result.data.path}» создана")
                    loadCategories()
                    onCreated(result.data)
                }
                is ApiResult.Error -> _state.value = _state.value.copy(busy = false, actionError = result.error)
            }
        }
    }

    /**
     * Ручная потребность или потребность на задел (D-63). Ключ идемпотентности
     * генерируется на каждое открытие диалога, поэтому двойное нажатие не
     * создаёт вторую потребность.
     */
    fun createDemand(
        sourceType: DemandSourceType,
        quantity: String,
        requiredDate: String?,
        priority: DemandPriority,
        stockpileNote: String?,
        idempotencyKey: String
    ) {
        val card = _state.value.card ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, actionError = null, notice = null)
            val result = demands.createDemand(
                CreateDemandRequest(
                    sourceType = sourceType,
                    stockpileNote = stockpileNote?.trim()?.takeIf { it.isNotEmpty() },
                    requiredDate = requiredDate?.trim()?.takeIf { it.isNotEmpty() },
                    priority = priority,
                    idempotencyKey = idempotencyKey,
                    lines = listOf(DemandLineInput(1, card.item.id, quantity.trim().replace(',', '.'), card.item.unit))
                )
            )
            when (result) {
                is ApiResult.Success -> _state.value = _state.value.copy(
                    busy = false,
                    notice = "Создана потребность ${result.data.header.number}. Она в черновике — отправьте её на согласование в разделе «Снабжение».",
                    successTick = _state.value.successTick + 1,
                    usage = TabData.Idle
                )
                is ApiResult.Error -> _state.value = _state.value.copy(busy = false, actionError = result.error)
            }
        }
    }

    fun newIdempotencyKey(): String =
        "card-$nomenclatureId-" + List(16) { Random.nextInt(0, 36).toString(36) }.joinToString("")

    // --- Внутреннее ---

    private fun command(
        success: String,
        reloadAfter: Boolean = true,
        onSuccess: () -> Unit = {},
        block: suspend () -> ApiError?
    ) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, actionError = null, notice = null)
            val error = block()
            if (error == null) {
                _state.value = _state.value.copy(
                    busy = false,
                    notice = success,
                    successTick = _state.value.successTick + 1
                )
                onSuccess()
                if (reloadAfter) reload()
            } else {
                _state.value = _state.value.copy(busy = false, actionError = error)
            }
        }
    }

    private fun ensureTabVisible() {
        val current = _state.value
        if (current.tab !in current.visibleTabs) {
            _state.value = current.copy(tab = NomenclatureCardTab.OVERVIEW)
        }
    }

    private fun loadTab(tab: NomenclatureCardTab) {
        when (tab) {
            NomenclatureCardTab.STOCK -> if (_state.value.stockHistory is TabData.Idle) loadStockHistory()
            NomenclatureCardTab.MOVEMENTS -> if (!_state.value.movements.loaded) loadMovements(reset = true)
            NomenclatureCardTab.RESERVATIONS -> if (_state.value.reservations is TabData.Idle) {
                load({ repository.reservations(nomenclatureId) }) { _state.value = _state.value.copy(reservations = it) }
            }
            NomenclatureCardTab.USAGE -> if (_state.value.usage is TabData.Idle) {
                load({ repository.usage(nomenclatureId) }) { _state.value = _state.value.copy(usage = it) }
            }
            NomenclatureCardTab.HISTORY -> if (_state.value.history is TabData.Idle) {
                load({ repository.history(nomenclatureId) }) { _state.value = _state.value.copy(history = it) }
            }
            NomenclatureCardTab.FILES -> if (_state.value.documents is TabData.Idle) {
                load({ repository.documents(nomenclatureId) }) { _state.value = _state.value.copy(documents = it) }
                viewModelScope.launch {
                    (documentRegistry.list() as? ApiResult.Success)?.let {
                        _state.value = _state.value.copy(documentCatalog = it.data)
                    }
                }
            }
            NomenclatureCardTab.OVERVIEW, NomenclatureCardTab.SUPPLY, NomenclatureCardTab.PRODUCT -> Unit
        }
    }

    private fun <T> load(fetch: suspend () -> ApiResult<T>, apply: (TabData<T>) -> Unit) {
        apply(TabData.Loading)
        viewModelScope.launch {
            when (val result = fetch()) {
                is ApiResult.Success -> apply(TabData.Ready(result.data))
                is ApiResult.Error -> apply(TabData.Failed(result.error))
            }
        }
    }

    private fun loadStockHistory() {
        val days = _state.value.stockHistoryDays
        load({ repository.stockHistory(nomenclatureId, days) }) { data ->
            if (_state.value.stockHistoryDays == days) _state.value = _state.value.copy(stockHistory = data)
        }
    }

    private fun cancelMovements() {
        movementsJob?.cancel()
        movementsJob = null
        movementsGeneration++
    }

    private fun loadMovements(reset: Boolean) {
        val current = _state.value.movements
        if (current.loading) return
        val before = if (reset) null else current.nextBefore ?: return
        val generation = movementsGeneration
        _state.value = _state.value.copy(movements = current.copy(loading = true, error = null))
        movementsJob = viewModelScope.launch {
            val result = repository.movements(nomenclatureId, current.type, before, limit = 50)
            if (generation != movementsGeneration) return@launch
            when (result) {
                is ApiResult.Success -> {
                    val latest = _state.value.movements
                    _state.value = _state.value.copy(
                        movements = latest.copy(
                            items = if (reset) result.data.items else latest.items + result.data.items,
                            nextBefore = result.data.nextBefore,
                            loading = false,
                            loaded = true
                        )
                    )
                }
                is ApiResult.Error -> _state.value = _state.value.copy(
                    movements = _state.value.movements.copy(loading = false, loaded = true, error = result.error)
                )
            }
        }
    }

    /** Фото — первое изображение среди вложений позиции. */
    private fun loadPhoto() {
        _state.value = _state.value.copy(attachmentsVersion = _state.value.attachmentsVersion + 1)
        photoJob?.cancel()
        photoJob = viewModelScope.launch {
            val list = (attachments.list(AttachmentEntityType.NOMENCLATURE, nomenclatureId) as? ApiResult.Success)?.data
            val image = list?.firstOrNull { it.mimeType?.startsWith("image/") == true }
            if (image == null) {
                _state.value = _state.value.copy(photo = null)
                return@launch
            }
            val bytes = (attachments.download(image.id) as? ApiResult.Success)?.data?.bytes
            _state.value = _state.value.copy(photo = bytes)
        }
    }

    private fun loadReferenceData() {
        loadCategories()
        viewModelScope.launch {
            (bom.listModels() as? ApiResult.Success)?.let { _state.value = _state.value.copy(productModels = it.data) }
        }
        viewModelScope.launch {
            (wms.listWarehouses() as? ApiResult.Success)?.let { _state.value = _state.value.copy(warehouses = it.data) }
        }
    }

    private fun loadCategories() {
        viewModelScope.launch {
            (repository.listCategories() as? ApiResult.Success)?.let { _state.value = _state.value.copy(categories = it.data) }
        }
    }
}
