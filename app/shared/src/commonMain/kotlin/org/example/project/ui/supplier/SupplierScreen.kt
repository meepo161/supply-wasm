package org.example.project.ui.supplier

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import org.example.project.ui.theme.Tab
import org.example.project.ui.theme.ErpSplitRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.dto.SupplierOfferComparisonColumnDto
import org.example.project.dto.SupplierOfferComparisonDto
import org.example.project.dto.SupplierVerificationStatus
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.DetailRow
import org.example.project.ui.theme.EmptyState
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.GlassCard
import org.example.project.ui.theme.GradientButton
import org.example.project.ui.theme.GradientSpinner
import org.example.project.ui.theme.Gradients
import org.example.project.ui.theme.PageHeader
import org.example.project.ui.theme.PillBadge
import org.example.project.ui.theme.SectionHeader
import org.example.project.ui.theme.SoftButton

@Composable
fun SupplierScreen(viewModel: SupplierViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(
            title = "Поставщики",
            glyph = ErpIconGlyph.Cart,
            gradient = Gradients.Sunset,
            subtitle = "Карточки контрагентов, предложения и сравнение по потребностям",
            actions = {
                if (state.selectedTab == SupplierTab.SUPPLIERS && viewModel.canManage()) {
                    GradientButton(
                        text = "Новый поставщик",
                        onClick = { viewModel.showDialog(SupplierDialog.CreateSupplier) },
                        gradient = Gradients.Sunset,
                        leadingIcon = ErpIconGlyph.Plus,
                        compact = true
                    )
                }
                if (state.selectedTab == SupplierTab.OFFERS && viewModel.canOffer()) {
                    GradientButton(
                        text = "Внести предложение",
                        onClick = { viewModel.showDialog(SupplierDialog.CreateOffer) },
                        gradient = Gradients.Ocean,
                        leadingIcon = ErpIconGlyph.Plus,
                        compact = true
                    )
                }
                if (state.selectedTab == SupplierTab.CLAIMS && viewModel.canManage()) {
                    GradientButton(
                        text = "Новая рекламация",
                        onClick = { viewModel.showDialog(SupplierDialog.CreateClaim) },
                        gradient = Gradients.Sunset,
                        leadingIcon = ErpIconGlyph.Plus,
                        compact = true
                    )
                }
            }
        )
        Spacer(Modifier.height(8.dp))
        ScrollableTabRow(selectedTabIndex = SupplierTab.entries.indexOf(state.selectedTab)) {
            SupplierTab.entries.forEach { tab ->
                Tab(
                    selected = state.selectedTab == tab,
                    onClick = { viewModel.selectTab(tab) },
                    text = { Text(tab.title) }
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            when (state.selectedTab) {
                SupplierTab.SUPPLIERS -> SuppliersPane(state, viewModel)
                SupplierTab.OFFERS -> OffersPane(state, viewModel)
                SupplierTab.CLAIMS -> ClaimsPane(state, viewModel)
            }
        }
    }

    SupplierDialogs(state = state, viewModel = viewModel)
}

@Composable
private fun SuppliersPane(state: SupplierUiState, viewModel: SupplierViewModel) {
    ErpSplitRow {
        GlassCard(modifier = Modifier.pane(0.6f), contentPadding = 14.dp) {
            SectionHeader(
                title = "Список",
                count = state.suppliers.size,
                accent = Brand.Orange,
                subtitle = "Поиск и фильтры применяются сразу",
                action = { SoftButton(text = "Обновить", onClick = viewModel::loadSuppliers) }
            )
            Spacer(Modifier.height(8.dp))
            org.example.project.ui.theme.ErpTextField(
                value = state.search,
                onValueChange = viewModel::updateSearch,
                label = "Поиск: название, номер, ИНН",
                glyph = ErpIconGlyph.Search,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SupplierStatusDropdown(
                    selected = state.statusFilter,
                    onSelect = viewModel::updateStatusFilter,
                    modifier = Modifier.weight(1f)
                )
                SoftButton(
                    text = if (state.showArchived) "Без архива" else "С архивом",
                    onClick = { viewModel.toggleArchived(!state.showArchived) }
                )
            }
            Spacer(Modifier.height(8.dp))
            state.successMessage?.let { message ->
                SuccessNote(message, viewModel::dismissSuccess)
                Spacer(Modifier.height(6.dp))
            }
            state.operationError?.takeIf { state.activeDialog == null }?.let { error ->
                ErrorMessageCard(error = error)
                Spacer(Modifier.height(6.dp))
            }
            when {
                state.isSuppliersLoading && state.suppliers.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { GradientSpinner() }
                state.suppliers.isEmpty() -> EmptyState(
                    title = "Поставщиков нет",
                    text = "Создайте карточку контрагента или измените фильтр",
                    glyph = ErpIconGlyph.Cart,
                    accent = Brand.Orange
                )
                else -> SuppliersGrid(
                    suppliers = state.suppliers,
                    onClick = { viewModel.selectSupplier(it.id) },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            }
        }
        SupplierCardPane(state, viewModel, Modifier.pane(0.4f, narrowHeight = 640.dp))
    }
}

@Composable
private fun SupplierCardPane(
    state: SupplierUiState,
    viewModel: SupplierViewModel,
    modifier: Modifier = Modifier
) {
    val card = state.selectedCard
    GlassCard(modifier = modifier, contentPadding = 16.dp) {
        if (card == null) {
            EmptyState(
                title = "Выберите поставщика",
                text = "Карточка покажет реквизиты, контакты, адреса, категории и документы",
                glyph = ErpIconGlyph.Cart,
                accent = Brand.Orange
            )
        } else {
            val supplier = card.supplier
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = supplier.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${supplier.number} · ${supplier.kind.localizedName()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                PillBadge(
                    text = supplier.verificationStatus.localizedName(),
                    accent = supplier.verificationStatus.accentColor(),
                    filled = supplier.verificationStatus == SupplierVerificationStatus.VERIFIED
                )
                if (supplier.archived) {
                    Spacer(Modifier.width(6.dp))
                    PillBadge(text = "Архив", accent = Brand.Slate)
                }
            }
            Spacer(Modifier.height(6.dp))
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (viewModel.canManage()) {
                    SoftButton(text = "Править карточку", onClick = { viewModel.showDialog(SupplierDialog.EditSupplier) })
                }
                if (viewModel.canVerify()) {
                    SoftButton(
                        text = "Проверить контрагента",
                        onClick = { viewModel.showDialog(SupplierDialog.Verify) },
                        tint = Brand.Teal
                    )
                }
                if (viewModel.canManage()) {
                    SoftButton(
                        text = if (supplier.archived) "Вернуть из архива" else "Убрать в архив",
                        onClick = { viewModel.toggleArchive(!supplier.archived) },
                        tint = if (supplier.archived) Brand.Mint else Brand.Slate
                    )
                }
            }
            Text(
                text = "Проверенный поставщик допускается к выбору предложения. Архивный поставщик " +
                    "скрыт из списков выбора, но его история сохраняется.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            var cardSection by remember(supplier.id) { mutableStateOf(0) }
            val sectionTitles = listOf("Реквизиты", "Контакты (${card.contacts.size + card.addresses.size})", "Документы (${card.documents.size})")
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                sectionTitles.forEachIndexed { idx, label ->

                    org.example.project.ui.theme.ErpFilterChip(
                        selected = cardSection == idx,
                        onClick = { cardSection = idx },
                        label = { Text(label) }

                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
            ) {
                when (cardSection) {
                    0 -> {
                        SectionHeader(title = "Реквизиты", accent = Brand.Orange)
                        DetailRow("Полное наименование", supplier.fullName ?: "—")
                        DetailRow("ИНН", supplier.inn ?: "—")
                        DetailRow("КПП", supplier.kpp ?: "—")
                        DetailRow("ОГРН", supplier.ogrn ?: "—")
                        DetailRow("Страна", supplier.country ?: "—")
                        DetailRow("Юридический адрес", supplier.legalAddress ?: "—")
                        DetailRow("Телефон", supplier.phone ?: "—")
                        DetailRow("Почта", supplier.email ?: "—")
                        DetailRow("Сайт", supplier.site ?: "—")
                        DetailRow(
                            "Ответственный",
                            state.users.firstOrNull { it.id == supplier.responsibleId }?.fullName
                                ?: supplier.responsibleId?.toString() ?: "—"
                        )
                        supplier.verificationNote?.takeIf { it.isNotBlank() }?.let { note ->
                            DetailRow("Примечание проверки", note)
                        }

                        Spacer(Modifier.height(12.dp))
                        SectionHeader(
                            title = "Категории",
                            count = card.categories.size,
                            accent = Brand.Violet,
                            action = {
                                if (viewModel.canManage()) {
                                    SoftButton(
                                        text = "Изменить",
                                        onClick = { viewModel.showDialog(SupplierDialog.EditCategories) }
                                    )
                                }
                            }
                        )
                        if (card.categories.isEmpty()) {
                            Text(
                                "Категории не назначены",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                card.categories.forEach { category ->
                                    PillBadge(text = category.name, accent = Brand.Violet)
                                }
                            }
                        }
                    }
                    1 -> {
                        SectionHeader(
                            title = "Контакты",
                            count = card.contacts.size,
                            accent = Brand.Blue,
                            action = {
                                if (viewModel.canManage()) {
                                    SoftButton(
                                        text = "Добавить",
                                        onClick = { viewModel.showDialog(SupplierDialog.AddContact) }
                                    )
                                }
                            }
                        )
                        if (card.contacts.isEmpty()) {
                            Text(
                                "Контакты не заведены",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            SupplierContactsGrid(
                                contacts = card.contacts,
                                modifier = Modifier.fillMaxWidth().height(180.dp)
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        SectionHeader(
                            title = "Адреса",
                            count = card.addresses.size,
                            accent = Brand.Indigo,
                            action = {
                                if (viewModel.canManage()) {
                                    SoftButton(
                                        text = "Добавить",
                                        onClick = { viewModel.showDialog(SupplierDialog.AddAddress) }
                                    )
                                }
                            }
                        )
                        if (card.addresses.isEmpty()) {
                            Text(
                                "Адреса не заведены",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            card.addresses.forEach { address ->
                                DetailRow(address.kind.localizedName(), address.address)
                            }
                        }
                    }
                    2 -> {
                        SectionHeader(
                            title = "Документы",
                            count = card.documents.size,
                            accent = Brand.Teal,
                            action = {
                                if (viewModel.canManage()) {
                                    SoftButton(
                                        text = "Добавить",
                                        onClick = { viewModel.showDialog(SupplierDialog.AddDocument) }
                                    )
                                }
                            }
                        )
                        if (card.documents.isEmpty()) {
                            Text(
                                "Договоры и сертификаты не заведены",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            SupplierDocumentsGrid(
                                documents = card.documents,
                                modifier = Modifier.fillMaxWidth().height(240.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun OffersPane(state: SupplierUiState, viewModel: SupplierViewModel) {
    ErpSplitRow {
        GlassCard(modifier = Modifier.pane(0.46f), contentPadding = 14.dp) {
            SectionHeader(
                title = "Предложения",
                count = if (state.offersDemandFilter == null) state.offers.size
                else state.offers.count { it.demandId == state.offersDemandFilter },
                accent = Brand.Blue,
                subtitle = if (state.offersDemandFilter == null) "Все полученные предложения" else "Предложения по выбранной потребности",
                action = { SoftButton(text = "Обновить", onClick = viewModel::loadOffers) }
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                DemandFilterDropdown(
                    demands = state.demands,
                    selectedId = state.offersDemandFilter,
                    onSelect = viewModel::filterOffersByDemand,
                    modifier = Modifier.weight(1f),
                    allowAll = true
                )
                SoftButton(text = "Обновить сравнение", onClick = viewModel::refreshComparison)
            }
            Spacer(Modifier.height(8.dp))
            state.successMessage?.let { message ->
                SuccessNote(message, viewModel::dismissSuccess)
                Spacer(Modifier.height(6.dp))
            }
            state.operationError?.takeIf { state.activeDialog == null }?.let { error ->
                ErrorMessageCard(error = error)
                Spacer(Modifier.height(6.dp))
            }
            when {
                state.isOffersLoading && state.offers.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { GradientSpinner() }
                state.offers.isEmpty() -> EmptyState(
                    title = "Предложений нет",
                    text = "Внесите предложение поставщика по согласованной потребности",
                    glyph = ErpIconGlyph.Documents,
                    accent = Brand.Blue
                )
                else -> {
                    val visibleOffers = state.offersDemandFilter
                        ?.let { demandId -> state.offers.filter { it.demandId == demandId } }
                        ?: state.offers
                    if (visibleOffers.isEmpty()) {
                        EmptyState(
                            title = "По потребности предложений нет",
                            text = "Выберите «Все потребности» или внесите предложение по этой потребности",
                            glyph = ErpIconGlyph.Documents,
                            accent = Brand.Blue
                        )
                    } else {
                        SupplierOffersGrid(
                            offers = visibleOffers,
                            demands = state.demands,
                            onClick = { offer -> viewModel.loadComparison(offer.demandId) },
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        )
                    }
                }
            }
        }
        ComparisonPane(state, viewModel, Modifier.pane(0.54f, narrowHeight = 640.dp))
    }
}

@Composable
private fun ComparisonPane(
    state: SupplierUiState,
    viewModel: SupplierViewModel,
    modifier: Modifier = Modifier
) {
    GlassCard(modifier = modifier, contentPadding = 16.dp) {
        val comparison = state.comparison
        if (comparison == null) {
            EmptyState(
                title = "Выберите потребность",
                text = "Сравнение покажет цену, срок, соответствие, документы и предупреждения",
                glyph = ErpIconGlyph.Filter,
                accent = Brand.Indigo
            )
        } else {
            Text(
                text = "Потребность ${comparison.demandNumber}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Статус: ${localizedDemandStatus(comparison.demandStatus)}" +
                    (comparison.requiredDate?.let { " · требуется до ${it.asDate()}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            if (comparison.columns.isEmpty()) {
                EmptyState(
                    title = "Предложений по потребности нет",
                    text = "Внесите хотя бы два предложения, чтобы сравнить условия",
                    glyph = ErpIconGlyph.Documents,
                    accent = Brand.Blue
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                ) {
                    comparison.columns.forEach { column ->
                        ComparisonCard(
                            column = column,
                            comparison = comparison,
                            isCheapest = comparison.cheapestOfferId == column.offerId,
                            isRecommended = comparison.recommendedOfferId == column.offerId,
                            onSelect = {
                                viewModel.openSelection(column.offerId, column.number)
                            },
                            onClearSelection = { viewModel.clearSelection(column.offerId) },
                            canSelect = viewModel.canSelect(),
                            onEdit = if (viewModel.canEditOffer(column.status)) {
                                { viewModel.openEditOffer(column.offerId) }
                            } else null,
                            onOpenInvoice = if (column.invoiceDocumentId != null || !column.invoiceFileName.isNullOrBlank()) {
                                { viewModel.openOfferInvoice(column.offerId, column.invoiceFileName, preview = true) }
                            } else null
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    if (comparison.warnings.isNotEmpty()) {
                        SectionHeader(
                            title = "Предупреждения",
                            count = comparison.warnings.size,
                            accent = Brand.Amber,
                            subtitle = "То, что нельзя игнорировать при выборе"
                        )
                        comparison.warnings.forEach { warning ->
                            Text(
                                text = "• ${warning.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Brand.Amber,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    comparison.selectionBlockedReason?.let { reason ->
                        if (comparison.selectedOfferId == null) {
                            Text(
                                text = reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComparisonCard(
    column: SupplierOfferComparisonColumnDto,
    comparison: SupplierOfferComparisonDto,
    isCheapest: Boolean,
    isRecommended: Boolean,
    onSelect: () -> Unit,
    onClearSelection: () -> Unit,
    canSelect: Boolean,
    onEdit: (() -> Unit)? = null,
    onOpenInvoice: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = column.supplierName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${column.number} · ${column.compliance.localizedName()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isCheapest) PillBadge(text = "Минимальная цена", accent = Brand.Blue)
            if (isRecommended) {
                Spacer(Modifier.width(4.dp))
                PillBadge(text = "Рекомендуем", accent = Brand.Mint)
            }
            Spacer(Modifier.width(4.dp))
            PillBadge(text = column.status.localizedName(), accent = column.status.accentColor())
        }
        Spacer(Modifier.height(6.dp))
        DetailRow("Сумма без НДС", column.netTotal.asMoney(column.currency))
        DetailRow("Сумма с НДС", column.grossTotal.asMoney(column.currency))
        DetailRow("Срок поставки", column.deliveryDays?.let { "$it дн." } ?: "не указан")
        DetailRow("Условия оплаты", column.paymentTerms ?: "—")
        DetailRow("Гарантия", column.warrantyMonths?.let { "$it мес." } ?: "—")
        DetailRow("Действует до", column.validUntil.asDate())
        DetailRow("Документы", if (column.documentsComplete) "комплект подтверждён" else "не подтверждены")
        DetailRow("Поставщик проверен", if (column.supplierVerified) "да" else "нет")
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (column.status == org.example.project.dto.SupplierOfferStatus.SELECTED) {
                SoftButton(
                    text = "Снять выбор",
                    onClick = onClearSelection,
                    tint = Brand.Rose
                )
            } else if (canSelect) {
                SoftButton(
                    text = "Выбрать",
                    onClick = onSelect,
                    tint = Brand.Mint
                )
            }
            onOpenInvoice?.let {
                val invoiceTitle = column.invoiceNumber?.takeIf { n -> n.isNotBlank() }
                    ?: column.invoiceFileName?.takeIf { f -> f.isNotBlank() }
                    ?: "PDF"
                SoftButton(text = "📄 Открыть счёт ($invoiceTitle)", onClick = it, tint = Brand.Blue)
            }
            onEdit?.let { SoftButton(text = "Править предложение", onClick = it, tint = Brand.Blue) }
        }
    }
}

@Composable
private fun SuccessNote(text: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Brand.Mint.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Brand.Teal,
            modifier = Modifier.weight(1f)
        )
        SoftButton(text = "Ок", onClick = onDismiss)
    }
}

@Composable
private fun ClaimsPane(state: SupplierUiState, viewModel: SupplierViewModel) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.operationError?.takeIf { state.activeDialog == null }?.let { ErrorMessageCard(it) }
        state.successMessage?.let { SuccessNote(it, viewModel::dismissSuccess) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionHeader(
                title = "Рекламации поставщикам",
                count = state.claims.size,
                accent = Brand.Orange,
                subtitle = "Претензионная работа и возвраты",
                action = { SoftButton(text = "Обновить", onClick = viewModel::loadClaims) }
            )
        }
        if (state.isClaimsLoading && state.claims.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { GradientSpinner() }
        } else if (state.claims.isEmpty()) {
            GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 20.dp) {
                Text("Рекламаций пока нет.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.claims.size) { index ->
                    val claim = state.claims[index]
                    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 14.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${claim.number} · ${claim.supplierName.ifBlank { "#${claim.supplierId}" }}",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                PillBadge(text = claim.status.localizedName(), accent = Brand.Orange)
                            }
                            Text(
                                text = "Тип: ${claim.claimType.name} • Сумма: ${claim.claimAmount.asMoney(claim.currency)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(text = claim.description, style = MaterialTheme.typography.bodyMedium)
                            if (viewModel.canManage()) {
                                androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                                    when (claim.status) {
                                        org.example.project.dto.SupplierClaimStatus.OPEN ->
                                            SoftButton(text = "Отправить", onClick = {
                                                viewModel.transitionClaim(claim, org.example.project.dto.SupplierClaimStatus.SENT)
                                            })
                                        org.example.project.dto.SupplierClaimStatus.SENT ->
                                            SoftButton(text = "На рассмотрение", onClick = {
                                                viewModel.transitionClaim(claim, org.example.project.dto.SupplierClaimStatus.IN_REVIEW)
                                            })
                                        org.example.project.dto.SupplierClaimStatus.IN_REVIEW ->
                                            SoftButton(text = "Урегулировать", onClick = {
                                                viewModel.transitionClaim(claim, org.example.project.dto.SupplierClaimStatus.RESOLVED)
                                            })
                                        org.example.project.dto.SupplierClaimStatus.RESOLVED ->
                                            SoftButton(text = "Закрыть", onClick = {
                                                viewModel.transitionClaim(claim, org.example.project.dto.SupplierClaimStatus.CLOSED)
                                            })
                                        else -> {}
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.activeDialog == SupplierDialog.CreateClaim) {
        var supplierExpanded by remember { mutableStateOf(false) }
        val selectedSupplier = state.suppliers.firstOrNull { it.id == state.claimForm.supplierId }
            ?: state.suppliers.firstOrNull()
        org.example.project.ui.theme.ErpDialog(
            title = "Новая рекламация",
            glyph = ErpIconGlyph.Cart,
            gradient = Gradients.Sunset,
            width = 580.dp,
            confirmText = "Создать",
            onConfirm = viewModel::createClaim,
            confirmGradient = Gradients.Sunset,
            onDismiss = viewModel::dismissDialog
        ) {
            Box {

                org.example.project.ui.theme.OutlinedButton(onClick = { supplierExpanded = true }) {

                    Text(selectedSupplier?.name ?: "Выберите поставщика")
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = supplierExpanded,
                    onDismissRequest = { supplierExpanded = false }
                ) {
                    state.suppliers.forEach { s ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(s.name) },
                            onClick = {
                                supplierExpanded = false
                                viewModel.updateClaimForm(state.claimForm.copy(supplierId = s.id))
                            }
                        )
                    }
                }
            }
            org.example.project.ui.theme.ErpTextField(
                value = state.claimForm.description,
                onValueChange = { viewModel.updateClaimForm(state.claimForm.copy(description = it)) },
                label = "Описание претензии",
                singleLine = false
            )
            org.example.project.ui.theme.ErpTextField(
                value = state.claimForm.claimAmount,
                onValueChange = { viewModel.updateClaimForm(state.claimForm.copy(claimAmount = it)) },
                label = "Сумма претензии, ₽"
            )
        }
    }
}
