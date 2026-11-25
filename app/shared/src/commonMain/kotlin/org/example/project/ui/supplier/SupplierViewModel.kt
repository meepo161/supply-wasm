package org.example.project.ui.supplier

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.project.data.ApiResult
import org.example.project.data.DemandRepository
import org.example.project.data.SupplierRepository
import org.example.project.data.UserRepository
import org.example.project.data.WmsRepository
import org.example.project.dto.AddSupplierAddressRequest
import org.example.project.dto.AddSupplierContactRequest
import org.example.project.dto.AddSupplierDocumentRequest
import org.example.project.dto.ApiError
import org.example.project.dto.CreateSupplierOfferRequest
import org.example.project.dto.CreateSupplierRequest
import org.example.project.dto.SelectSupplierOfferRequest
import org.example.project.dto.SetSupplierCategoriesRequest
import org.example.project.dto.SupplierAddressInput
import org.example.project.dto.SupplierContactInput
import org.example.project.dto.SupplierDocumentInput
import org.example.project.dto.SupplierDocumentKind
import org.example.project.dto.SupplierAddressKind
import org.example.project.dto.SupplierOfferLineInput
import org.example.project.dto.SupplierOfferStatusRequest
import org.example.project.dto.SupplierVerificationRequest
import org.example.project.dto.SupplierVerificationStatus
import org.example.project.dto.UpdateSupplierOfferRequest
import org.example.project.dto.UpdateSupplierRequest
import org.example.project.dto.isValidInn
import org.example.project.dto.UserDto
import org.example.project.dto.hasPermission

class SupplierViewModel(
    private val supplierRepository: SupplierRepository,
    private val demandRepository: DemandRepository,
    private val wmsRepository: WmsRepository,
    private val userRepository: UserRepository,
    private val currentUser: UserDto
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SupplierUiState(users = listOf(currentUser), createForm = CreateSupplierForm(responsibleId = currentUser.id))
    )
    val uiState: StateFlow<SupplierUiState> = _uiState.asStateFlow()

    init {
        loadSuppliers()
        loadCategories()
        loadDemands()
        loadNomenclature()
        loadUsers()
    }

    fun canManage(): Boolean = currentUser.hasPermission("suppliers", "create")
    fun canVerify(): Boolean = currentUser.hasPermission("suppliers", "verify")
    fun canOffer(): Boolean = currentUser.hasPermission("suppliers", "offer")
    fun canSelect(): Boolean = currentUser.hasPermission("suppliers", "select")

    fun dismissError() = update { it.copy(operationError = null) }
    fun dismissSuccess() = update { it.copy(successMessage = null) }

    fun selectTab(tab: SupplierTab) {
        update { it.copy(selectedTab = tab, operationError = null, successMessage = null) }
        if (tab == SupplierTab.OFFERS && _uiState.value.offers.isEmpty()) loadOffers()
        if (tab == SupplierTab.CLAIMS && _uiState.value.claims.isEmpty()) loadClaims()
    }

    // --- Загрузка ---

    fun loadSuppliers() {
        update { it.copy(isSuppliersLoading = true, operationError = null) }
        viewModelScope.launch {
            val state = _uiState.value
            when (val result = supplierRepository.listSuppliers(
                search = state.search.takeIf { it.isNotBlank() },
                verificationStatus = state.statusFilter,
                archived = state.showArchived
            )) {
                is ApiResult.Success -> {
                    update { it.copy(suppliers = result.data, isSuppliersLoading = false) }
                    if (_uiState.value.selectedCard == null && result.data.isNotEmpty()) {
                        selectSupplier(result.data.first().id)
                    }
                }
                is ApiResult.Error -> update {
                    it.copy(isSuppliersLoading = false, operationError = result.error)
                }
            }
        }
    }

    private var searchJob: kotlinx.coroutines.Job? = null

    /** Поиск применяется сразу после паузы в наборе, отдельная кнопка «Найти» не нужна. */
    fun updateSearch(value: String) {
        update { it.copy(search = value) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(350)
            loadSuppliers()
        }
    }

    fun updateStatusFilter(status: SupplierVerificationStatus?) {
        update { it.copy(statusFilter = status) }
        loadSuppliers()
    }

    fun toggleArchived(show: Boolean) {
        update { it.copy(showArchived = show) }
        loadSuppliers()
    }

    fun selectSupplier(id: Long?) {
        if (id == null) {
            update { it.copy(selectedCard = null, categorySelection = emptySet()) }
            return
        }
        update { it.copy(isCardLoading = true, operationError = null) }
        viewModelScope.launch {
            when (val result = supplierRepository.getSupplier(id)) {
                is ApiResult.Success -> update {
                    it.copy(
                        selectedCard = result.data,
                        isCardLoading = false,
                        categorySelection = result.data.categories.map { category -> category.id }.toSet()
                    )
                }
                is ApiResult.Error -> update {
                    it.copy(isCardLoading = false, operationError = result.error)
                }
            }
        }
    }

    private fun loadCategories() {
        viewModelScope.launch {
            when (val result = supplierRepository.listCategories()) {
                is ApiResult.Success -> update { it.copy(categories = result.data) }
                is ApiResult.Error -> { }
            }
        }
    }

    private fun loadDemands() {
        viewModelScope.launch {
            when (val result = demandRepository.listDemands()) {
                is ApiResult.Success -> update { it.copy(demands = result.data) }
                is ApiResult.Error -> { }
            }
        }
    }

    private fun loadNomenclature() {
        viewModelScope.launch {
            when (val result = wmsRepository.listNomenclature()) {
                is ApiResult.Success -> update { it.copy(nomenclature = result.data) }
                is ApiResult.Error -> { }
            }
        }
    }

    private fun loadUsers() {
        viewModelScope.launch {
            when (val result = userRepository.list()) {
                is ApiResult.Success -> update {
                    it.copy(users = (result.data + currentUser).distinctBy { user -> user.id })
                }
                is ApiResult.Error -> { }
            }
        }
    }

    fun loadOffers() {
        update { it.copy(isOffersLoading = true, operationError = null) }
        viewModelScope.launch {
            when (val result = supplierRepository.listOffers()) {
                is ApiResult.Success -> update {
                    it.copy(offers = result.data, isOffersLoading = false)
                }
                is ApiResult.Error -> update {
                    it.copy(isOffersLoading = false, operationError = result.error)
                }
            }
        }
    }

    /** Фильтр списка предложений по потребности; заодно показывает её сравнение. */
    fun filterOffersByDemand(demandId: Long?) {
        update { it.copy(offersDemandFilter = demandId) }
        loadComparison(demandId)
    }

    fun loadComparison(demandId: Long?) {
        if (demandId == null) {
            update { it.copy(comparison = null, comparisonDemandId = null) }
            return
        }
        update { it.copy(comparisonDemandId = demandId, isOffersLoading = true, operationError = null) }
        viewModelScope.launch {
            when (val result = supplierRepository.compareOffers(demandId)) {
                is ApiResult.Success -> update {
                    it.copy(comparison = result.data, isOffersLoading = false)
                }
                is ApiResult.Error -> update {
                    it.copy(isOffersLoading = false, operationError = result.error)
                }
            }
        }
    }

    fun refreshComparison() {
        loadDemands()
        loadOffers()
        loadComparison(_uiState.value.comparisonDemandId)
    }

    // --- Диалоги ---

    fun showDialog(dialog: SupplierDialog) {
        // Потребности могли согласовать после входа — в форме предложения нужен свежий список.
        if (dialog == SupplierDialog.CreateOffer) {
            loadDemands()
            loadOffers()
        }
        val card = _uiState.value.selectedCard
        update {
            when (dialog) {
                SupplierDialog.EditSupplier -> it.copy(
                    activeDialog = dialog,
                    editForm = card?.let { c ->
                        EditSupplierForm(
                            supplierId = c.supplier.id,
                            version = c.supplier.version,
                            name = c.supplier.name,
                            fullName = c.supplier.fullName ?: "",
                            kind = c.supplier.kind,
                            inn = c.supplier.inn ?: "",
                            kpp = c.supplier.kpp ?: "",
                            ogrn = c.supplier.ogrn ?: "",
                            country = c.supplier.country ?: "",
                            legalAddress = c.supplier.legalAddress ?: "",
                            site = c.supplier.site ?: "",
                            email = c.supplier.email ?: "",
                            phone = c.supplier.phone ?: "",
                            note = c.supplier.note ?: ""
                        )
                    },
                    operationError = null
                )
                else -> it.copy(activeDialog = dialog, operationError = null)
            }
        }
    }

    fun dismissDialog() {
        update {
            it.copy(
                activeDialog = null,
                operationError = null,
                successMessage = null,
                contactForm = ContactForm(),
                addressForm = AddressForm(),
                documentForm = DocumentForm(),
                verificationNote = "",
                selectionReason = "",
                pendingSelectionOfferId = null
            )
        }
    }

    // --- Создание и правка поставщика ---

    fun updateCreateForm(form: CreateSupplierForm) = update { it.copy(createForm = form) }

    fun createSupplier() {
        if (!canManage()) return fail("Нет права создавать поставщиков")
        val form = _uiState.value.createForm
        if (form.name.isBlank()) return fail("Укажите наименование поставщика")
        innProblem(form.inn)?.let { return fail(it) }
        update { it.copy(isActionRunning = true, operationError = null) }
        viewModelScope.launch {
            val request = CreateSupplierRequest(
                name = form.name.trim(),
                fullName = form.fullName.trim().ifEmpty { null },
                kind = form.kind,
                inn = form.inn.trim().ifEmpty { null },
                kpp = form.kpp.trim().ifEmpty { null },
                ogrn = form.ogrn.trim().ifEmpty { null },
                country = form.country.trim().ifEmpty { null },
                legalAddress = form.legalAddress.trim().ifEmpty { null },
                site = form.site.trim().ifEmpty { null },
                email = form.email.trim().ifEmpty { null },
                phone = form.phone.trim().ifEmpty { null },
                responsibleId = form.responsibleId,
                note = form.note.trim().ifEmpty { null },
                contacts = if (form.contactName.isBlank()) emptyList() else listOf(
                    SupplierContactInput(
                        fullName = form.contactName.trim(),
                        position = form.contactPosition.trim().ifEmpty { null },
                        email = form.contactEmail.trim().ifEmpty { null },
                        phone = form.contactPhone.trim().ifEmpty { null },
                        isPrimary = true
                    )
                ),
                idempotencyKey = newKey("supplier-create")
            )
            when (val result = supplierRepository.createSupplier(request)) {
                is ApiResult.Success -> {
                    update {
                        it.copy(
                            isActionRunning = false,
                            activeDialog = null,
                            createForm = CreateSupplierForm(responsibleId = currentUser.id),
                            selectedCard = result.data,
                            successMessage = "Поставщик ${result.data.supplier.number} создан"
                        )
                    }
                    loadSuppliers()
                }
                is ApiResult.Error -> failWith(result.error)
            }
        }
    }

    /** Быстрое создание поставщика прямо из формы коммерческого предложения. */
    fun quickCreateSupplierForOffer(
        name: String,
        inn: String,
        email: String,
        verifyNow: Boolean,
        onDone: () -> Unit
    ) {
        if (!canManage()) return fail("Нет права создавать поставщиков")
        if (name.isBlank()) return fail("Укажите наименование поставщика")
        innProblem(inn)?.let { return fail(it) }
        update { it.copy(isActionRunning = true, operationError = null) }
        viewModelScope.launch {
            val request = CreateSupplierRequest(
                name = name.trim(),
                kind = CreateSupplierForm().kind,
                inn = inn.trim().ifEmpty { null },
                email = email.trim().ifEmpty { null },
                responsibleId = currentUser.id,
                idempotencyKey = newKey("supplier-quick-create")
            )
            when (val result = supplierRepository.createSupplier(request)) {
                is ApiResult.Success -> {
                    var created = result.data.supplier
                    if (verifyNow && canVerify()) {
                        when (val v = supplierRepository.verifySupplier(
                            created.id,
                            SupplierVerificationRequest(
                                status = SupplierVerificationStatus.VERIFIED,
                                note = "Быстрая проверка при внесении КП",
                                version = created.version,
                                idempotencyKey = newKey("supplier-quick-verify")
                            )
                        )) {
                            is ApiResult.Success -> created = v.data
                            is ApiResult.Error -> { /* оставляем созданным, ошибку не блокируем */ }
                        }
                    }
                    update { state ->
                        val updatedSuppliers = listOf(created) + state.suppliers.filterNot { it.id == created.id }
                        state.copy(
                            suppliers = updatedSuppliers,
                            offerForm = state.offerForm.copy(supplierId = created.id),
                            isActionRunning = false
                        )
                    }
                    onDone()
                }
                is ApiResult.Error -> failWith(result.error)
            }
        }
    }

    fun updateEditForm(form: EditSupplierForm) = update { it.copy(editForm = form) }

    fun saveSupplier() {
        val form = _uiState.value.editForm ?: return
        if (form.name.isBlank()) return fail("Наименование обязательно")
        innProblem(form.inn)?.let { return fail(it) }
        update { it.copy(isActionRunning = true, operationError = null) }
        viewModelScope.launch {
            val request = UpdateSupplierRequest(
                name = form.name.trim(),
                fullName = form.fullName.trim(),
                kind = form.kind,
                inn = form.inn.trim(),
                kpp = form.kpp.trim(),
                ogrn = form.ogrn.trim(),
                country = form.country.trim(),
                legalAddress = form.legalAddress.trim(),
                site = form.site.trim(),
                email = form.email.trim(),
                phone = form.phone.trim(),
                note = form.note.trim(),
                version = form.version
            )
            when (val result = supplierRepository.updateSupplier(form.supplierId, request)) {
                is ApiResult.Success -> {
                    update { it.copy(isActionRunning = false, activeDialog = null, successMessage = "Карточка обновлена") }
                    selectSupplier(form.supplierId)
                    loadSuppliers()
                }
                is ApiResult.Error -> failWith(result.error)
            }
        }
    }


    // --- Проверка, архив ---

    fun updateVerificationNote(value: String) = update { it.copy(verificationNote = value) }

    fun verify(status: SupplierVerificationStatus) {
        if (!canVerify()) return fail("Нет права проверять контрагентов")
        val card = _uiState.value.selectedCard ?: return
        update { it.copy(isActionRunning = true, operationError = null) }
        viewModelScope.launch {
            val request = SupplierVerificationRequest(
                status = status,
                note = _uiState.value.verificationNote.trim().ifEmpty { null },
                version = card.supplier.version,
                idempotencyKey = newKey("supplier-verify")
            )
            when (val result = supplierRepository.verifySupplier(card.supplier.id, request)) {
                is ApiResult.Success -> {
                    update {
                        it.copy(
                            isActionRunning = false,
                            activeDialog = null,
                            successMessage = "Статус проверки: ${result.data.verificationStatus.localizedName()}"
                        )
                    }
                    selectSupplier(card.supplier.id)
                    loadSuppliers()
                }
                is ApiResult.Error -> failWith(result.error)
            }
        }
    }


    fun toggleArchive(archived: Boolean) {
        val card = _uiState.value.selectedCard ?: return
        update { it.copy(isActionRunning = true, operationError = null) }
        viewModelScope.launch {
            when (val result = supplierRepository.archiveSupplier(card.supplier.id, card.supplier.version, archived)) {
                is ApiResult.Success -> {
                    update {
                        it.copy(
                            isActionRunning = false,
                            successMessage = if (archived) "Поставщик в архиве" else "Поставщик восстановлен"
                        )
                    }
                    selectSupplier(card.supplier.id)
                    loadSuppliers()
                }
                is ApiResult.Error -> failWith(result.error)
            }
        }
    }


    // --- Контакты, адреса, документы, категории ---

    fun updateContactForm(form: ContactForm) = update { it.copy(contactForm = form) }

    fun addContact() {
        val card = _uiState.value.selectedCard ?: return
        val form = _uiState.value.contactForm
        if (form.fullName.isBlank()) return fail("Укажите имя контакта")
        update { it.copy(isActionRunning = true, operationError = null) }
        viewModelScope.launch {
            when (val result = supplierRepository.addContact(
                card.supplier.id,
                AddSupplierContactRequest(
                    contact = SupplierContactInput(
                        fullName = form.fullName.trim(),
                        position = form.position.trim().ifEmpty { null },
                        email = form.email.trim().ifEmpty { null },
                        phone = form.phone.trim().ifEmpty { null },
                        messenger = form.messenger.trim().ifEmpty { null },
                        isPrimary = form.isPrimary
                    ),
                    version = card.supplier.version
                )
            )) {
                is ApiResult.Success -> applyCardUpdate(result.data, "Контакт добавлен")
                is ApiResult.Error -> failWith(result.error)
            }
        }
    }


    fun updateAddressForm(form: AddressForm) = update { it.copy(addressForm = form) }

    fun addAddress() {
        val card = _uiState.value.selectedCard ?: return
        val form = _uiState.value.addressForm
        if (form.address.isBlank()) return fail("Укажите адрес")
        update { it.copy(isActionRunning = true, operationError = null) }
        viewModelScope.launch {
            val kind = SupplierAddressKind.entries.firstOrNull { it.name == form.kind }
                ?: SupplierAddressKind.LEGAL
            when (val result = supplierRepository.addAddress(
                card.supplier.id,
                AddSupplierAddressRequest(
                    address = SupplierAddressInput(kind = kind, address = form.address.trim(), note = form.note.trim().ifEmpty { null }),
                    version = card.supplier.version
                )
            )) {
                is ApiResult.Success -> applyCardUpdate(result.data, "Адрес добавлен")
                is ApiResult.Error -> failWith(result.error)
            }
        }
    }


    fun updateDocumentForm(form: DocumentForm) = update { it.copy(documentForm = form) }

    fun addDocument() {
        val card = _uiState.value.selectedCard ?: return
        val form = _uiState.value.documentForm
        if (form.title.isBlank()) return fail("Укажите название документа")
        if (form.validFrom.isNotBlank() && !form.validFrom.isIsoDate()) return fail("Дата начала в формате ГГГГ-ММ-ДД")
        if (form.validTo.isNotBlank() && !form.validTo.isIsoDate()) return fail("Дата окончания в формате ГГГГ-ММ-ДД")
        update { it.copy(isActionRunning = true, operationError = null) }
        viewModelScope.launch {
            val kind = SupplierDocumentKind.entries.firstOrNull { it.name == form.kind }
                ?: SupplierDocumentKind.OTHER
            when (val result = supplierRepository.addDocument(
                card.supplier.id,
                AddSupplierDocumentRequest(
                    document = SupplierDocumentInput(
                        kind = kind,
                        title = form.title.trim(),
                        documentNo = form.documentNo.trim().ifEmpty { null },
                        validFrom = form.validFrom.trim().ifEmpty { null },
                        validTo = form.validTo.trim().ifEmpty { null },
                        note = form.note.trim().ifEmpty { null }
                    ),
                    version = card.supplier.version
                )
            )) {
                is ApiResult.Success -> applyCardUpdate(result.data, "Документ добавлен")
                is ApiResult.Error -> failWith(result.error)
            }
        }
    }


    fun toggleCategory(categoryId: Long) {
        val current = _uiState.value.categorySelection
        update {
            it.copy(
                categorySelection = if (categoryId in current) current - categoryId else current + categoryId
            )
        }
    }

    fun saveCategories() {
        val card = _uiState.value.selectedCard ?: return
        update { it.copy(isActionRunning = true, operationError = null) }
        viewModelScope.launch {
            when (val result = supplierRepository.setCategories(
                card.supplier.id,
                SetSupplierCategoriesRequest(
                    categoryIds = _uiState.value.categorySelection.toList(),
                    version = card.supplier.version
                )
            )) {
                is ApiResult.Success -> applyCardUpdate(result.data, "Категории сохранены")
                is ApiResult.Error -> failWith(result.error)
            }
        }
    }


    // --- Предложения ---

    fun updateOfferForm(form: CreateOfferForm) =
        update { it.copy(offerForm = form) }

    fun updateOfferLine(index: Int, line: OfferLineForm) {
        val form = _uiState.value.offerForm
        update {
            it.copy(
                offerForm = form.copy(
                    lines = form.lines.mapIndexed { i, current -> if (i == index) line else current }
                )
            )
        }
    }

    fun addOfferLine() {
        val form = _uiState.value.offerForm
        update { it.copy(offerForm = form.copy(lines = form.lines + OfferLineForm())) }
    }

    fun removeOfferLine(index: Int) {
        val form = _uiState.value.offerForm
        if (form.lines.size <= 1) return
        update {
            it.copy(offerForm = form.copy(lines = form.lines.filterIndexed { i, _ -> i != index }))
        }
    }

    /**
     * Позиции потребности берутся из её карточки: интерфейс не выдумывает
     * позиции и не позволяет ввести количество больше открытого дефицита.
     */
    fun onOfferDemandChanged(demandId: Long?) {
        val form = _uiState.value.offerForm
        update {
            it.copy(
                offerForm = form.copy(
                    demandId = demandId,
                    demandLines = emptyList(),
                    lines = listOf(OfferLineForm())
                )
            )
        }
        demandId ?: return
        viewModelScope.launch {
            when (val result = demandRepository.getDemand(demandId)) {
                is ApiResult.Success -> {
                    val options = result.data.lines.map { line ->
                        val nomenclature = _uiState.value.nomenclature.firstOrNull { it.id == line.nomenclatureId }
                        DemandLineOption(
                            demandLineId = line.id,
                            nomenclatureId = line.nomenclatureId,
                            label = nomenclature?.let { "${it.code} · ${it.name}" }
                                ?: "Позиция ${line.lineNo}",
                            deficit = line.deficitQuantity ?: line.quantity
                        )
                    }
                    update {
                        it.copy(
                            offerForm = _uiState.value.offerForm.copy(
                                demandLines = options,
                                lines = options.map { option ->
                                    OfferLineForm(
                                        demandLineId = option.demandLineId,
                                        quantity = option.deficit
                                    )
                                }.ifEmpty { listOf(OfferLineForm()) }
                            )
                        )
                    }
                }
                is ApiResult.Error -> failWith(result.error)
            }
        }
    }


    fun createOffer() {
        if (!canOffer()) return fail("Нет права вносить предложения поставщиков")
        val form = _uiState.value.offerForm
        if (form.demandId == null) return fail("Выберите потребность")
        if (form.supplierId == null) return fail("Выберите поставщика")
        if (!form.validUntil.isIsoDate()) return fail("Срок действия в формате ГГГГ-ММ-ДД")
        val lines = form.lines.mapNotNull { line ->
            val demandLineId = line.demandLineId ?: return@mapNotNull null
            if (line.quantity.isBlank() || line.unitPrice.isBlank()) return@mapNotNull null
            SupplierOfferLineInput(
                demandLineId = demandLineId,
                quantity = line.quantity.trim(),
                unitPrice = line.unitPrice.trim(),
                vatRate = line.vatRate.trim().ifEmpty { "20" }
            )
        }
        if (lines.isEmpty()) return fail("Заполните хотя бы одну позицию: количество и цену")
        update { it.copy(isActionRunning = true, operationError = null) }
        viewModelScope.launch {
            val request = CreateSupplierOfferRequest(
                demandId = form.demandId,
                supplierId = form.supplierId,
                currency = form.currency.trim().uppercase().ifEmpty { "RUB" },
                compliance = form.compliance,
                complianceNote = form.complianceNote.trim().ifEmpty { null },
                documentsComplete = form.documentsComplete,
                deliveryDays = form.deliveryDays.trim().toIntOrNull(),
                deliveryTerms = form.deliveryTerms.trim().ifEmpty { null },
                deliveryCost = form.deliveryCost.trim().ifEmpty { null },
                paymentTerms = form.paymentTerms.trim().ifEmpty { null },
                warrantyMonths = form.warrantyMonths.trim().toIntOrNull(),
                validUntil = form.validUntil.trim(),
                supplierContactName = form.supplierContactName.trim().ifEmpty { null },
                supplierContactChannel = form.supplierContactChannel.trim().ifEmpty { null },
                note = form.note.trim().ifEmpty { null },
                lines = lines,
                idempotencyKey = newKey("offer-create")
            )
            when (val result = supplierRepository.createOffer(request)) {
                is ApiResult.Success -> {
                    update {
                        it.copy(
                            isActionRunning = false,
                            activeDialog = null,
                            offerForm = CreateOfferForm(),
                            successMessage = "Предложение ${result.data.number} добавлено"
                        )
                    }
                    loadOffers()
                    loadComparison(form.demandId)
                }
                is ApiResult.Error -> failWith(result.error)
            }
        }
    }


    fun updateSelectionReason(value: String) = update { it.copy(selectionReason = value) }

    fun submitSelection() {
        if (!canSelect()) return fail("Нет права выбирать предложение")
        val offerId = _uiState.value.pendingSelectionOfferId ?: return
        val reason = _uiState.value.selectionReason.trim()
        if (reason.length < 10) return fail("Обоснование выбора: не короче 10 символов")
        update { it.copy(isActionRunning = true, operationError = null) }
        viewModelScope.launch {
            val offer = _uiState.value.comparison?.columns?.firstOrNull { it.offerId == offerId }
            when (val result = supplierRepository.selectOffer(
                offerId,
                SelectSupplierOfferRequest(
                    reason = reason,
                    version = _uiState.value.offers.firstOrNull { it.id == offerId }?.version ?: 0,
                    idempotencyKey = newKey("offer-select")
                )
            )) {
                is ApiResult.Success -> {
                    update {
                        it.copy(
                            isActionRunning = false,
                            activeDialog = null,
                            selectionReason = "",
                            pendingSelectionOfferId = null,
                            successMessage = "Выбрано предложение ${offer?.number ?: result.data.number}"
                        )
                    }
                    loadOffers()
                    refreshComparison()
                }
                is ApiResult.Error -> failWith(result.error)
            }
        }
    }


    fun openSelection(offerId: Long, offerNumber: String) {
        update {
            it.copy(
                activeDialog = SupplierDialog.SelectOffer(offerId, offerNumber),
                pendingSelectionOfferId = offerId,
                selectionReason = "",
                operationError = null
            )
        }
    }

    fun changeOfferStatus(offerId: Long, status: String, reason: String?) {
        val offer = _uiState.value.offers.firstOrNull { it.id == offerId } ?: return
        update { it.copy(isActionRunning = true, operationError = null) }
        viewModelScope.launch {
            val target = org.example.project.dto.SupplierOfferStatus.entries
                .firstOrNull { it.name == status } ?: return@launch
            when (val result = supplierRepository.changeOfferStatus(
                offerId,
                SupplierOfferStatusRequest(
                    status = target,
                    reason = reason,
                    version = offer.version,
                    idempotencyKey = newKey("offer-status")
                )
            )) {
                is ApiResult.Success -> {
                    update { it.copy(isActionRunning = false, successMessage = "Статус предложения обновлён") }
                    loadOffers()
                    refreshComparison()
                }
                is ApiResult.Error -> failWith(result.error)
            }
        }
    }


    fun clearSelection(offerId: Long) {
        val offer = _uiState.value.offers.firstOrNull { it.id == offerId } ?: return
        update { it.copy(isActionRunning = true, operationError = null) }
        viewModelScope.launch {
            when (val result = supplierRepository.clearSelection(offerId, offer.version)) {
                is ApiResult.Success -> {
                    update { it.copy(isActionRunning = false, successMessage = "Выбор снят") }
                    loadOffers()
                    refreshComparison()
                }
                is ApiResult.Error -> failWith(result.error)
            }
        }
    }


    // --- Правка предложения (S-04) ---

    /** Предложение можно править, пока оно не выбрано, не отозвано и не истекло. */
    fun canEditOffer(status: org.example.project.dto.SupplierOfferStatus): Boolean =
        canOffer() && status !in setOf(
            org.example.project.dto.SupplierOfferStatus.SELECTED,
            org.example.project.dto.SupplierOfferStatus.WITHDRAWN,
            org.example.project.dto.SupplierOfferStatus.EXPIRED
        )

    fun openEditOffer(offerId: Long) {
        update { it.copy(isActionRunning = true, operationError = null) }
        viewModelScope.launch {
            when (val result = supplierRepository.getOffer(offerId)) {
                is ApiResult.Success -> {
                    val offer = result.data
                    val nomenclature = _uiState.value.nomenclature
                    update {
                        it.copy(
                            isActionRunning = false,
                            activeDialog = SupplierDialog.EditOffer,
                            editOfferForm = EditOfferForm(
                                offerId = offer.id,
                                version = offer.version,
                                number = offer.number,
                                supplierName = offer.supplierName ?: "Поставщик №${offer.supplierId}",
                                compliance = offer.compliance,
                                documentsComplete = offer.documentsComplete,
                                deliveryDays = offer.deliveryDays?.toString() ?: "",
                                deliveryTerms = offer.deliveryTerms ?: "",
                                paymentTerms = offer.paymentTerms ?: "",
                                warrantyMonths = offer.warrantyMonths?.toString() ?: "",
                                validUntil = offer.validUntil,
                                note = offer.note ?: "",
                                lines = offer.lines.map { line ->
                                    val item = nomenclature.firstOrNull { n -> n.id == line.nomenclatureId }
                                    EditOfferLineForm(
                                        demandLineId = line.demandLineId,
                                        label = item?.let { n -> "${n.code} — ${n.name}" } ?: "Позиция ${line.lineNo}",
                                        unit = line.unit,
                                        quantity = line.quantity,
                                        unitPrice = line.unitPrice,
                                        vatRate = line.vatRate
                                    )
                                }
                            )
                        )
                    }
                }
                is ApiResult.Error -> failWith(result.error)
            }
        }
    }


    fun updateEditOfferForm(form: EditOfferForm) = update { it.copy(editOfferForm = form) }

    fun updateEditOfferLine(index: Int, line: EditOfferLineForm) {
        val form = _uiState.value.editOfferForm ?: return
        update {
            it.copy(editOfferForm = form.copy(lines = form.lines.mapIndexed { i, current -> if (i == index) line else current }))
        }
    }

    fun saveOffer() {
        if (!canOffer()) return fail("Нет права вносить предложения поставщиков")
        val form = _uiState.value.editOfferForm ?: return
        if (!form.validUntil.isIsoDate()) return fail("Укажите, до какого числа действует предложение")
        if (form.lines.any { it.quantity.isBlank() || it.unitPrice.isBlank() }) {
            return fail("Заполните количество и цену во всех позициях")
        }
        update { it.copy(isActionRunning = true, operationError = null) }
        viewModelScope.launch {
            val request = UpdateSupplierOfferRequest(
                compliance = form.compliance,
                documentsComplete = form.documentsComplete,
                deliveryDays = form.deliveryDays.trim().toIntOrNull(),
                deliveryTerms = form.deliveryTerms.trim(),
                paymentTerms = form.paymentTerms.trim(),
                warrantyMonths = form.warrantyMonths.trim().toIntOrNull(),
                validUntil = form.validUntil.trim(),
                note = form.note.trim(),
                lines = form.lines.map { line ->
                    SupplierOfferLineInput(
                        demandLineId = line.demandLineId,
                        quantity = line.quantity.trim(),
                        unitPrice = line.unitPrice.trim(),
                        vatRate = line.vatRate.trim().ifEmpty { "20" }
                    )
                },
                version = form.version
            )
            when (val result = supplierRepository.updateOffer(form.offerId, request)) {
                is ApiResult.Success -> {
                    update {
                        it.copy(
                            isActionRunning = false,
                            activeDialog = null,
                            editOfferForm = null,
                            successMessage = "Предложение ${result.data.number} обновлено"
                        )
                    }
                    loadOffers()
                    refreshComparison()
                }
                is ApiResult.Error -> failWith(result.error)
            }
        }
    }


    /** Предложение того же поставщика по той же потребности, которое ещё в работе. */
    fun existingOfferFor(demandId: Long?, supplierId: Long?): org.example.project.dto.SupplierOfferDto? {
        if (demandId == null || supplierId == null) return null
        return _uiState.value.offers.firstOrNull {
            it.demandId == demandId && it.supplierId == supplierId && it.status !in inactiveOfferStatuses
        }
    }

    private val inactiveOfferStatuses = setOf(
        org.example.project.dto.SupplierOfferStatus.WITHDRAWN,
        org.example.project.dto.SupplierOfferStatus.REJECTED,
        org.example.project.dto.SupplierOfferStatus.EXPIRED
    )

    /** Проверка ИНН на клиенте: те же правила, что на сервере; пустой ИНН допустим. */
    private fun innProblem(inn: String): String? {
        val value = inn.trim()
        if (value.isEmpty()) return null
        if (!value.matches(Regex("^([0-9]{10}|[0-9]{12})$"))) return "ИНН должен содержать 10 цифр (организация) или 12 (ИП)"
        if (!isValidInn(value)) return "ИНН указан неверно: не сходится контрольная цифра, проверьте номер"
        return null
    }

    // --- Служебное ---

    private fun applyCardUpdate(card: org.example.project.dto.SupplierCardDto, message: String) {
        update {
            it.copy(
                isActionRunning = false,
                activeDialog = null,
                selectedCard = card,
                contactForm = ContactForm(),
                addressForm = AddressForm(),
                documentForm = DocumentForm(),
                successMessage = message
            )
        }
    }

    private fun update(block: (SupplierUiState) -> SupplierUiState) {
        _uiState.value = block(_uiState.value)
    }

    private fun fail(message: String) {
        update { it.copy(operationError = ApiError("VALIDATION_ERROR", message), isActionRunning = false) }
    }

    private fun failWith(error: ApiError) {
        update { it.copy(operationError = error, isActionRunning = false) }
    }

    /** Ключ идемпотентности на попытку команды: повтор защищён сервером. */
    private fun newKey(prefix: String): String =
        "$prefix-${currentUser.id}-${kotlin.random.Random.nextLong().toString(16)}"

    // --- Рекламации ---

    fun loadClaims() {
        update { it.copy(isClaimsLoading = true, operationError = null) }
        viewModelScope.launch {
            when (val result = supplierRepository.listClaims()) {
                is ApiResult.Success -> update { it.copy(claims = result.data, isClaimsLoading = false) }
                is ApiResult.Error -> update { it.copy(isClaimsLoading = false, operationError = result.error) }
            }
        }
    }

    fun updateClaimForm(form: CreateClaimForm) = update { it.copy(claimForm = form) }

    fun createClaim() {
        val form = _uiState.value.claimForm
        val supplierId = form.supplierId ?: _uiState.value.suppliers.firstOrNull()?.id
            ?: return fail("Выберите поставщика")
        if (form.description.isBlank()) return fail("Укажите описание рекламации")
        update { it.copy(isActionRunning = true, operationError = null) }
        viewModelScope.launch {
            when (val result = supplierRepository.createClaim(
                org.example.project.dto.CreateSupplierClaimRequest(
                    supplierId = supplierId,
                    claimType = form.claimType,
                    description = form.description.trim(),
                    claimAmount = form.claimAmount.trim().ifEmpty { "0" }
                )
            )) {
                is ApiResult.Success -> {
                    update {
                        it.copy(
                            isActionRunning = false,
                            activeDialog = null,
                            claimForm = CreateClaimForm(),
                            claims = listOf(result.data) + it.claims,
                            successMessage = "Рекламация ${result.data.number} создана"
                        )
                    }
                }
                is ApiResult.Error -> failWith(result.error)
            }
        }
    }


    fun transitionClaim(
        claim: org.example.project.dto.SupplierClaimDto,
        targetStatus: org.example.project.dto.SupplierClaimStatus
    ) {
        update { it.copy(isActionRunning = true, operationError = null) }
        viewModelScope.launch {
            when (val result = supplierRepository.transitionClaim(
                claim.id,
                org.example.project.dto.TransitionSupplierClaimRequest(
                    targetStatus = targetStatus,
                    resolution = if (targetStatus == org.example.project.dto.SupplierClaimStatus.RESOLVED) "Урегулировано" else null,
                    version = claim.version
                )
            )) {
                is ApiResult.Success -> {
                    update {
                        it.copy(
                            isActionRunning = false,
                            claims = it.claims.map { c -> if (c.id == result.data.id) result.data else c },
                            successMessage = "Статус рекламации ${result.data.number}: ${result.data.status.localizedName()}"
                        )
                    }
                }
                is ApiResult.Error -> failWith(result.error)
            }
        }
    }
}

private fun String.isIsoDate(): Boolean = Regex("""\d{4}-\d{2}-\d{2}""").matches(trim())
