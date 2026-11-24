package org.example.project.ui.main

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import org.example.project.ui.theme.ErpIconButton
import org.example.project.ui.theme.erpInteractive
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.data.AdminRepository
import org.example.project.data.AttachmentRepository
import org.example.project.data.BomRepository
import org.example.project.data.ChatRepository
import org.example.project.data.ConversationRepository
import org.example.project.data.DemandRepository
import org.example.project.data.DocumentRepository
import org.example.project.data.NotificationRepository
import org.example.project.data.ProductionRepository
import org.example.project.data.ProjectRepository
import org.example.project.data.PurchaseOrderRepository
import org.example.project.data.SupplierRepository
import org.example.project.data.SearchRepository
import org.example.project.data.TaskRepository
import org.example.project.data.UserRepository
import org.example.project.data.WmsRepository
import org.example.project.dto.ApiError
import org.example.project.dto.UserDto
import org.example.project.ui.admin.AdminScreen
import org.example.project.ui.admin.AdminViewModel
import org.example.project.ui.bom.BomScreen
import org.example.project.ui.bom.BomViewModel
import org.example.project.ui.conversations.ConversationViewModel
import org.example.project.ui.conversations.ConversationsScreen
import org.example.project.ui.demand.DemandScreen
import org.example.project.ui.demand.DemandViewModel
import org.example.project.ui.documents.DocumentViewModel
import org.example.project.ui.documents.DocumentsScreen
import org.example.project.ui.home.HomeScreen
import org.example.project.ui.home.HomeViewModel
import org.example.project.ui.notifications.NotificationScreen
import org.example.project.ui.notifications.NotificationViewModel
import org.example.project.ui.projects.ProjectViewModel
import org.example.project.ui.projects.ProjectTab
import org.example.project.ui.projects.ProjectsScreen
import org.example.project.ui.search.GlobalSearchBox
import org.example.project.ui.search.SearchViewModel
import org.example.project.ui.tasks.TaskViewModel
import org.example.project.ui.tasks.TasksScreen
import org.example.project.ui.theme.Avatar
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.ErpIcon
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.LocalBrandPalette
import org.example.project.ui.theme.LocalThemeController
import org.example.project.ui.production.ProductionScreen
import org.example.project.ui.production.ProductionViewModel
import org.example.project.ui.supplier.PurchaseOrderScreen
import org.example.project.ui.supplier.PurchaseOrderViewModel
import org.example.project.ui.supplier.SupplierScreen
import org.example.project.ui.supplier.SupplierViewModel
import org.example.project.ui.wms.WmsScreen
import org.example.project.ui.wms.WmsViewModel
import org.koin.compose.koinInject

@Composable
fun MainScaffold(
    user: UserDto,
    onLogout: () -> Unit,
    passwordChangeLoading: Boolean = false,
    passwordChangeError: ApiError? = null,
    onChangePassword: (String, String, String) -> Unit = { _, _, _ -> },
    onClearPasswordError: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selected by remember { mutableStateOf(NavigationDestination.HOME) }
    val snackbarHostState = remember { SnackbarHostState() }
    val destinations = NavigationDestination.entries.filter { it.isVisibleTo(user) }

    val projectRepository: ProjectRepository = koinInject()
    val userRepository: UserRepository = koinInject()
    val taskRepository: TaskRepository = koinInject()
    val chatRepository: ChatRepository = koinInject()
    val wmsRepository: WmsRepository = koinInject()
    val bomRepository: BomRepository = koinInject()
    val productionRepository: ProductionRepository = koinInject()
    val supplierRepository: SupplierRepository = koinInject()
    val purchaseOrderRepository: PurchaseOrderRepository = koinInject()
    val demandRepository: DemandRepository = koinInject()
    val documentRepository: DocumentRepository = koinInject()
    val notificationRepository: NotificationRepository = koinInject()
    val conversationRepository: ConversationRepository = koinInject()
    val attachmentRepository: AttachmentRepository = koinInject()
    val adminRepository: AdminRepository = koinInject()
    val searchRepository: SearchRepository = koinInject()

    val projectViewModel = remember(user.id) {
        ProjectViewModel(projectRepository, userRepository, taskRepository)
    }
    val taskViewModel = remember(user.id) {
        TaskViewModel(
            taskRepository, chatRepository, userRepository, projectRepository,
            attachmentRepository, documentRepository, user.id,
            conversationRepository = conversationRepository
        )
    }
    val homeViewModel = remember(user.id) {
        HomeViewModel(taskRepository, notificationRepository, conversationRepository, user)
    }
    val searchViewModel = remember(user.id) { SearchViewModel(searchRepository) }
    val notificationViewModel = remember(user.id) {
        NotificationViewModel(notificationRepository, taskRepository, conversationRepository, user)
    }
    val conversationViewModel = remember(user.id) {
        ConversationViewModel(
            conversationRepository, taskRepository, projectRepository,
            attachmentRepository, userRepository, user
        )
    }
    val projectUiState by projectViewModel.uiState.collectAsState()
    val projectConversationViewModel = remember(
        user.id,
        projectUiState.selectedProject?.id,
        selected == NavigationDestination.PROJECTS
    ) {
        ConversationViewModel(
            conversationRepository, taskRepository, projectRepository,
            attachmentRepository, userRepository, user
        )
    }
    DisposableEffect(conversationViewModel) {
        onDispose { conversationViewModel.close() }
    }
    DisposableEffect(projectConversationViewModel) {
        onDispose { projectConversationViewModel.close() }
    }
    androidx.compose.runtime.LaunchedEffect(notificationViewModel) {
        notificationViewModel.uiState.collect { state ->
            if (!state.isLoading) {
                homeViewModel.onNotificationsUpdated(state.notifications)
                taskViewModel.onNotificationsUpdated(state.notifications)
            }
        }
    }
    val wmsViewModel = if (NavigationDestination.WMS in destinations) {
        remember(user.id) { WmsViewModel(wmsRepository, user.id) }
    } else {
        null
    }
    val bomViewModel = if (NavigationDestination.BOM in destinations) {
        remember(user) { BomViewModel(bomRepository, wmsRepository, userRepository, user) }
    } else {
        null
    }
    val productionViewModel = if (NavigationDestination.PRODUCTION in destinations) {
        remember(user) {
            ProductionViewModel(
                productionRepository,
                projectRepository,
                bomRepository,
                userRepository,
                user
            )
        }
    } else {
        null
    }
    val supplierViewModel = if (NavigationDestination.SUPPLIERS in destinations) {
        remember(user) {
            SupplierViewModel(
                supplierRepository,
                demandRepository,
                wmsRepository,
                userRepository,
                user
            )
        }
    } else {
        null
    }
    val purchaseOrderViewModel = if (NavigationDestination.PURCHASE_ORDERS in destinations) {
        remember(user) {
            PurchaseOrderViewModel(
                purchaseOrderRepository,
                supplierRepository,
                userRepository,
                user
            )
        }
    } else {
        null
    }
    val supplierInvoiceRepository: org.example.project.data.SupplierInvoiceRepository = koinInject()
    val supplierInvoiceViewModel = if (NavigationDestination.PURCHASE_ORDERS in destinations) {
        remember(user) {
            org.example.project.ui.supplier.SupplierInvoiceViewModel(
                supplierInvoiceRepository,
                documentRepository,
                wmsRepository,
                user
            )
        }
    } else {
        null
    }
    val demandViewModel = if (NavigationDestination.DEMANDS in destinations) {
        remember(user) { DemandViewModel(demandRepository, wmsRepository, projectRepository, bomRepository, user) }
    } else {
        null
    }
    val documentViewModel = remember(user) {
        DocumentViewModel(documentRepository, projectRepository, userRepository, user)
    }
    val adminViewModel = if (NavigationDestination.ADMIN in destinations) {
        remember(user.id) { AdminViewModel(adminRepository, user) }
    } else {
        null
    }

    val onOpenTask: (Long) -> Unit = { taskId ->
        val cached = taskViewModel.uiState.value.tasks.find { it.id == taskId }
        if (cached != null) {
            taskViewModel.selectTask(cached)
        } else {
            taskViewModel.openTaskById(taskId)
        }
        selected = NavigationDestination.TASKS
    }
    val onOpenProject: (Long) -> Unit = { projectId ->
        projectViewModel.openProjectById(projectId)
        selected = NavigationDestination.PROJECTS
    }
    val onOpenConversation: (Long) -> Unit = { conversationId ->
        conversationViewModel.openConversationById(conversationId)
        selected = NavigationDestination.CHAT
    }
    val onOpenProjectChat: (Long) -> Unit = { projectId ->
        projectViewModel.openProjectById(projectId)
        projectViewModel.selectDetailTab(ProjectTab.DISCUSSION)
        selected = NavigationDestination.PROJECTS
    }
    val onOpenDocument: (Long) -> Unit = { _ ->
        selected = NavigationDestination.DOCUMENTS
    }
    val onOpenNomenclature: (Long) -> Unit = { _ ->
        if (NavigationDestination.WMS in destinations) {
            selected = NavigationDestination.WMS
        } else if (NavigationDestination.BOM in destinations) {
            selected = NavigationDestination.BOM
        }
    }
    var sidebarCollapsed by remember { mutableStateOf(false) }
    var favoriteDestinations by remember { mutableStateOf(setOf(NavigationDestination.TASKS, NavigationDestination.PROJECTS)) }
    val onToggleFavorite: (NavigationDestination) -> Unit = { dest ->
        favoriteDestinations = if (dest in favoriteDestinations) {
            favoriteDestinations - dest
        } else {
            favoriteDestinations + dest
        }
    }
    val onOpenNotifications: () -> Unit = {
        selected = NavigationDestination.NOTIFICATIONS
    }
    val onBackFromNotifications: () -> Unit = {
        selected = NavigationDestination.HOME
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWide = maxWidth >= 900.dp
        val isCompact = maxWidth < 640.dp

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopBar(
                    destination = selected,
                    user = user,
                    destinations = destinations,
                    showMenu = !isWide,
                    onSelect = { selected = it },
                    onLogout = onLogout,
                    onOpenNotifications = onOpenNotifications,
                    onOpenProfile = { selected = NavigationDestination.PROFILE },
                    searchViewModel = searchViewModel,
                    showSearch = !isCompact,
                    onOpenTask = onOpenTask,
                    onOpenProject = onOpenProject,
                    onOpenDocument = onOpenDocument,
                    onOpenNomenclature = onOpenNomenclature,
                    isFavorite = selected in favoriteDestinations,
                    onToggleFavorite = { onToggleFavorite(selected) }
                )
            },
            bottomBar = {
                if (isCompact) {
                    BottomNavigationBar(
                        destinations = destinations,
                        selected = selected,
                        onSelect = { selected = it }
                    )
                }
            },
            snackbarHost = {
                SnackbarHost(snackbarHostState) { data ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.inverseSurface,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = data.visuals.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                }
            }
        ) { padding ->
            Row(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                if (isWide) {
                    Sidebar(
                        destinations = destinations,
                        selected = selected,
                        onSelect = { selected = it },
                        user = user,
                        onLogout = onLogout,
                        collapsed = sidebarCollapsed,
                        onToggleCollapse = { sidebarCollapsed = !sidebarCollapsed },
                        favorites = favoriteDestinations.filter { it in destinations }
                    )
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    MainContent(
                        destination = selected,
                        user = user,
                        onLogout = onLogout,
                        passwordChangeLoading = passwordChangeLoading,
                        passwordChangeError = passwordChangeError,
                        onChangePassword = onChangePassword,
                        onClearPasswordError = onClearPasswordError,
                        homeViewModel = homeViewModel,
                        projectViewModel = projectViewModel,
                        taskViewModel = taskViewModel,
                        notificationViewModel = notificationViewModel,
                        conversationViewModel = conversationViewModel,
                        projectConversationViewModel = projectConversationViewModel,
                        wmsViewModel = wmsViewModel,
                        bomViewModel = bomViewModel,
                        productionViewModel = productionViewModel,
                        demandViewModel = demandViewModel,
                        supplierViewModel = supplierViewModel,
                        purchaseOrderViewModel = purchaseOrderViewModel,
                        supplierInvoiceViewModel = supplierInvoiceViewModel,
                        documentViewModel = documentViewModel,
                        adminViewModel = adminViewModel,
                        onOpenTask = onOpenTask,
                        onOpenConversation = onOpenConversation,
                        onOpenProjectChat = onOpenProjectChat,
                        onOpenNotifications = onOpenNotifications,
                        onBackFromNotifications = onBackFromNotifications
                    )
                }
            }
        }
    }
}

/* ------------------------------- Верхняя панель ------------------------------ */

@Composable
private fun TopBar(
    destination: NavigationDestination,
    user: UserDto,
    destinations: List<NavigationDestination>,
    showMenu: Boolean,
    onSelect: (NavigationDestination) -> Unit,
    onLogout: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenProfile: () -> Unit,
    searchViewModel: SearchViewModel,
    showSearch: Boolean,
    onOpenTask: (Long) -> Unit,
    onOpenProject: (Long) -> Unit,
    onOpenDocument: (Long) -> Unit,
    onOpenNomenclature: (Long) -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit
) {
    val palette = LocalBrandPalette.current
    val theme = LocalThemeController.current
    var menuExpanded by remember { mutableStateOf(false) }
    var userMenuExpanded by remember { mutableStateOf(false) }
    var quickActionsExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showMenu) {
            Box {
                RoundIconButton(glyph = ErpIconGlyph.Menu, contentDescription = "Меню разделов") { menuExpanded = true }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    destinations.forEach { dest ->
                        DropdownMenuItem(
                            text = { Text(dest.label) },
                            onClick = {
                                menuExpanded = false
                                onSelect(dest)
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
        }

        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "ERP",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
            Spacer(Modifier.width(8.dp))
            ErpIcon(
                glyph = ErpIconGlyph.ChevronRight,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(12.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = destination.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (isFavorite) "★" else "☆",
                style = MaterialTheme.typography.titleMedium,
                color = if (isFavorite) Brand.Amber else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onToggleFavorite)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }

        if (showSearch) {
            GlobalSearchBox(
                viewModel = searchViewModel,
                onOpenTask = onOpenTask,
                onOpenProject = onOpenProject,
                onOpenDocument = onOpenDocument,
                onOpenNomenclature = onOpenNomenclature,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            Spacer(Modifier.width(6.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                RoundIconButton(
                    glyph = ErpIconGlyph.Plus,
                    contentDescription = "Быстрые действия",
                    onClick = { quickActionsExpanded = true }
                )
                DropdownMenu(
                    expanded = quickActionsExpanded,
                    onDismissRequest = { quickActionsExpanded = false }
                ) {
                    if (NavigationDestination.TASKS in destinations) {
                        DropdownMenuItem(
                            text = { Text("Перейти к задачам") },
                            onClick = {
                                quickActionsExpanded = false
                                onSelect(NavigationDestination.TASKS)
                            }
                        )
                    }
                    if (NavigationDestination.PROJECTS in destinations) {
                        DropdownMenuItem(
                            text = { Text("Перейти к проектам") },
                            onClick = {
                                quickActionsExpanded = false
                                onSelect(NavigationDestination.PROJECTS)
                            }
                        )
                    }
                    if (NavigationDestination.DEMANDS in destinations) {
                        DropdownMenuItem(
                            text = { Text("Создать потребность (Снабжение)") },
                            onClick = {
                                quickActionsExpanded = false
                                onSelect(NavigationDestination.DEMANDS)
                            }
                        )
                    }
                    if (NavigationDestination.DOCUMENTS in destinations) {
                        DropdownMenuItem(
                            text = { Text("Документы") },
                            onClick = {
                                quickActionsExpanded = false
                                onSelect(NavigationDestination.DOCUMENTS)
                            }
                        )
                    }
                    if (NavigationDestination.WMS in destinations) {
                        DropdownMenuItem(
                            text = { Text("Склад (WMS)") },
                            onClick = {
                                quickActionsExpanded = false
                                onSelect(NavigationDestination.WMS)
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            RoundIconButton(
                glyph = ErpIconGlyph.Bell,
                contentDescription = "Уведомления",
                onClick = onOpenNotifications
            )
            Spacer(Modifier.width(8.dp))
            RoundIconButton(
                glyph = if (theme.isDark) ErpIconGlyph.Sun else ErpIconGlyph.Moon,
                contentDescription = if (theme.isDark) "Светлая тема" else "Тёмная тема",
                onClick = { theme.toggle() }
            )
            Spacer(Modifier.width(14.dp))
            Box {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(palette.glassOverlay.copy(alpha = 0.75f))
                        .border(
                            1.dp,
                            palette.glassBorder.first(),
                            RoundedCornerShape(50)
                        )
                        .clickable { userMenuExpanded = true }
                        .padding(start = 5.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Avatar(name = user.fullName, size = 30.dp)
                    Spacer(Modifier.width(9.dp))
                    Column {
                        Text(
                            text = user.fullName,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "@${user.login}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                DropdownMenu(
                    expanded = userMenuExpanded,
                    onDismissRequest = { userMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Профиль") },
                        onClick = {
                            userMenuExpanded = false
                            onOpenProfile()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Выйти") },
                        onClick = {
                            userMenuExpanded = false
                            onLogout()
                        }
                    )
                }
            }
        }
    }
}

/** Кнопка-иконка верхней панели: контурная, с доступным именем и рамкой фокуса. */
@Composable
private fun RoundIconButton(
    glyph: ErpIconGlyph,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = LocalBrandPalette.current
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(shape)
            .background(
                when {
                    pressed -> scheme.surfaceContainerHighest
                    hovered -> scheme.surfaceContainerHigh
                    else -> palette.glassOverlay
                }
            )
            .border(1.dp, palette.glassBorder.first(), shape)
            .erpInteractive(interaction, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            },
        contentAlignment = Alignment.Center
    ) {
        ErpIcon(
            glyph = glyph,
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

/* --------------------------------- Сайдбар ---------------------------------- */

@Composable
private fun Sidebar(
    destinations: List<NavigationDestination>,
    selected: NavigationDestination,
    onSelect: (NavigationDestination) -> Unit,
    user: UserDto,
    onLogout: () -> Unit,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    favorites: List<NavigationDestination>
) {
    val palette = LocalBrandPalette.current
    Column(
        modifier = Modifier
            .width(if (collapsed) 76.dp else 256.dp)
            .fillMaxHeight()
            .padding(start = 12.dp, top = 0.dp, bottom = 12.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.sidebarGradient.first())
    ) {
        // Название предприятия текстом (логотип не утверждён) и кнопка сворачивания.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = if (collapsed) 0.dp else 18.dp, end = if (collapsed) 0.dp else 8.dp, top = 16.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.SpaceBetween
        ) {
            if (!collapsed) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Авиаагрегат-Н",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1
                    )
                    Text(
                        text = "ERP · WMS · MES",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.62f)
                    )
                }
            }
            ErpIconButton(
                glyph = if (collapsed) ErpIconGlyph.Menu else ErpIconGlyph.ChevronLeft,
                contentDescription = if (collapsed) "Развернуть меню" else "Свернуть меню",
                onClick = onToggleCollapse,
                tint = Color.White.copy(alpha = 0.8f)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
        ) {
            if (favorites.isNotEmpty()) {
                if (!collapsed) {
                    Text(
                        text = "Избранное",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.62f),
                        modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp)
                    )
                }
                favorites.forEach { dest ->
                    SidebarItem(
                        destination = dest,
                        selected = dest == selected,
                        collapsed = collapsed,
                        onClick = { onSelect(dest) }
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
            NavGroup.entries.forEach { group ->
                val items = destinations.filter { it.group == group && it !in favorites }
                if (items.isNotEmpty()) {
                    if (!collapsed) {
                        Text(
                            text = group.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.62f),
                            modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp)
                        )
                    } else {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                            color = Color.White.copy(alpha = 0.10f)
                        )
                    }
                    items.forEach { dest ->
                        SidebarItem(
                            destination = dest,
                            selected = dest == selected,
                            collapsed = collapsed,
                            onClick = { onSelect(dest) }
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }

        // Карточка пользователя
        Box(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .padding(10.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(name = user.fullName, size = 36.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = user.fullName,
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "@${user.login}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.62f),
                            maxLines = 1
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                val logoutInteraction = remember { MutableInteractionSource() }
                val logoutHovered by logoutInteraction.collectIsHoveredAsState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 36.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (logoutHovered) Color.White.copy(alpha = 0.08f) else Color.Transparent)
                        .erpInteractive(logoutInteraction, RoundedCornerShape(6.dp), focusColor = Brand.Signal)
                        .clickable(interactionSource = logoutInteraction, indication = null) { onLogout() }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ErpIcon(
                        glyph = ErpIconGlyph.Logout,
                        tint = Color.White.copy(alpha = 0.78f),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Выйти из системы",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.78f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SidebarItem(
    destination: NavigationDestination,
    selected: Boolean,
    collapsed: Boolean = false,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(6.dp)
    // Текущий раздел: светлая подложка, полужирная подпись и янтарная метка слева.
    // Метка остаётся и в свёрнутом меню, поэтому текущее место видно не только по цвету.
    val background = when {
        selected -> Color.White.copy(alpha = 0.12f)
        pressed -> Color.White.copy(alpha = 0.10f)
        hovered -> Color.White.copy(alpha = 0.06f)
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 38.dp)
            .clip(shape)
            .background(background)
            .erpInteractive(interaction, shape, focusColor = Brand.Signal)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { if (collapsed) contentDescription = destination.label },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(20.dp)
                .background(if (selected) Brand.Signal else Color.Transparent)
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(start = if (collapsed) 0.dp else 9.dp, end = 10.dp),
            horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ErpIcon(
                glyph = destination.glyph,
                tint = if (selected) Color.White else Color.White.copy(alpha = 0.72f),
                modifier = Modifier.size(17.dp)
            )
            if (!collapsed) {
                Spacer(Modifier.width(11.dp))
                Text(
                    text = destination.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) Color.White else Color.White.copy(alpha = 0.80f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/* ---------------------------- Мобильная навигация ---------------------------- */

@Composable
private fun BottomNavigationBar(
    destinations: List<NavigationDestination>,
    selected: NavigationDestination,
    onSelect: (NavigationDestination) -> Unit
) {
    val palette = LocalBrandPalette.current
    val primary = remember(destinations) {
        listOf(
            NavigationDestination.HOME,
            NavigationDestination.TASKS,
            NavigationDestination.PROJECTS,
            NavigationDestination.NOTIFICATIONS
        ).filter { it in destinations }
    }
    val overflow = remember(destinations) { destinations - primary.toSet() }
    var menuExpanded by remember { mutableStateOf(false) }
    val overflowSelected = selected in overflow

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .shadow(12.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(palette.glassOverlay.copy(alpha = 0.95f))
            .border(1.dp, palette.glassBorder.first(), RoundedCornerShape(10.dp))
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        primary.forEach { destination ->
            BottomItem(
                destination = destination,
                selected = destination == selected,
                onClick = { onSelect(destination) },
                modifier = Modifier.weight(1f)
            )
        }
        if (overflow.isNotEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { menuExpanded = true }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    ErpIcon(
                        glyph = ErpIconGlyph.Grid,
                        tint = if (overflowSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Ещё",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (overflowSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    overflow.forEach { destination ->
                        DropdownMenuItem(
                            text = { Text(destination.label) },
                            onClick = {
                                menuExpanded = false
                                onSelect(destination)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomItem(
    destination: NavigationDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(width = 38.dp, height = 28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (selected) {
                        Brush.linearGradient(destination.gradient)
                    } else {
                        Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            ErpIcon(
                glyph = destination.glyph,
                tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = destination.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/* --------------------------------- Контент ---------------------------------- */

@Composable
private fun MainContent(
    destination: NavigationDestination,
    user: UserDto,
    onLogout: () -> Unit,
    passwordChangeLoading: Boolean,
    passwordChangeError: ApiError?,
    onChangePassword: (String, String, String) -> Unit,
    onClearPasswordError: () -> Unit,
    homeViewModel: HomeViewModel,
    projectViewModel: ProjectViewModel,
    taskViewModel: TaskViewModel,
    notificationViewModel: NotificationViewModel,
    conversationViewModel: ConversationViewModel,
    projectConversationViewModel: ConversationViewModel,
    wmsViewModel: WmsViewModel?,
    bomViewModel: BomViewModel?,
    productionViewModel: ProductionViewModel?,
    demandViewModel: DemandViewModel?,
    supplierViewModel: SupplierViewModel?,
    purchaseOrderViewModel: PurchaseOrderViewModel?,
    supplierInvoiceViewModel: org.example.project.ui.supplier.SupplierInvoiceViewModel? = null,
    documentViewModel: DocumentViewModel,
    adminViewModel: AdminViewModel?,
    onOpenTask: (Long) -> Unit,
    onOpenConversation: (Long) -> Unit,
    onOpenProjectChat: (Long) -> Unit,
    onOpenNotifications: () -> Unit,
    onBackFromNotifications: () -> Unit
) {
    when (destination) {
        NavigationDestination.HOME -> HomeScreen(
            viewModel = homeViewModel,
            onOpenTask = onOpenTask,
            onOpenConversation = onOpenConversation,
            onOpenProjectChat = onOpenProjectChat,
            onOpenNotifications = onOpenNotifications
        )

        NavigationDestination.TASKS -> TasksScreen(
            currentUser = user,
            viewModel = taskViewModel
        )

        NavigationDestination.PROJECTS -> ProjectsScreen(
            currentUser = user,
            viewModel = projectViewModel,
            // Задача из вкладки «Задачи» проекта открывается в разделе задач.
            onOpenTask = onOpenTask,
            conversationViewModel = projectConversationViewModel
        )

        NavigationDestination.CHAT -> ConversationsScreen(
            viewModel = conversationViewModel,
            taskViewModel = taskViewModel,
            currentUser = user,
            onOpenTask = onOpenTask
        )

        NavigationDestination.ANALYTICS -> org.example.project.ui.analytics.AnalyticsScreen()

        NavigationDestination.WMS -> wmsViewModel?.let {
            WmsScreen(viewModel = it, currentUser = user)
        }

        NavigationDestination.BOM -> bomViewModel?.let {
            BomScreen(viewModel = it)
        }

        NavigationDestination.PRODUCTION -> productionViewModel?.let {
            ProductionScreen(viewModel = it)
        }

        NavigationDestination.SUPPLIERS -> supplierViewModel?.let {
            SupplierScreen(viewModel = it)
        }

        NavigationDestination.PURCHASE_ORDERS -> purchaseOrderViewModel?.let {
            PurchaseOrderScreen(viewModel = it, invoiceViewModel = supplierInvoiceViewModel)
        }

        NavigationDestination.DEMANDS -> demandViewModel?.let {
            DemandScreen(viewModel = it, currentUser = user)
        }

        NavigationDestination.DOCUMENTS -> DocumentsScreen(viewModel = documentViewModel)

        NavigationDestination.NOTIFICATIONS -> NotificationScreen(
            viewModel = notificationViewModel,
            onOpenTask = onOpenTask,
            onOpenConversation = onOpenConversation,
            onOpenProjectChat = onOpenProjectChat,
            onBack = onBackFromNotifications
        )

        NavigationDestination.ADMIN -> adminViewModel?.let {
            AdminScreen(viewModel = it)
        }

        NavigationDestination.PROFILE -> ProfileScreen(
            user = user,
            onLogout = onLogout,
            passwordChangeLoading = passwordChangeLoading,
            passwordChangeError = passwordChangeError,
            onChangePassword = onChangePassword,
            onClearPasswordError = onClearPasswordError
        )
    }
}
