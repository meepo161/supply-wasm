package org.example.project.ui.main

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import org.example.project.data.BomRepository
import org.example.project.data.ChatRepository
import org.example.project.data.DemandRepository
import org.example.project.data.DocumentRepository
import org.example.project.data.NotificationRepository
import org.example.project.data.ProjectRepository
import org.example.project.data.TaskRepository
import org.example.project.data.UserRepository
import org.example.project.data.WmsRepository
import org.example.project.dto.UserDto
import org.example.project.ui.admin.AdminScreen
import org.example.project.ui.admin.AdminViewModel
import org.example.project.ui.bom.BomScreen
import org.example.project.ui.bom.BomViewModel
import org.example.project.ui.demand.DemandScreen
import org.example.project.ui.demand.DemandViewModel
import org.example.project.ui.documents.DocumentViewModel
import org.example.project.ui.documents.DocumentsScreen
import org.example.project.ui.home.HomeScreen
import org.example.project.ui.home.HomeViewModel
import org.example.project.ui.notifications.NotificationScreen
import org.example.project.ui.notifications.NotificationViewModel
import org.example.project.ui.projects.ProjectViewModel
import org.example.project.ui.projects.ProjectsScreen
import org.example.project.ui.tasks.TaskViewModel
import org.example.project.ui.tasks.TasksScreen
import org.example.project.ui.theme.Avatar
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.ErpIcon
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.LocalBrandPalette
import org.example.project.ui.theme.LocalThemeController
import org.example.project.ui.wms.WmsScreen
import org.example.project.ui.wms.WmsViewModel
import org.koin.compose.koinInject

@Composable
fun MainScaffold(
    user: UserDto,
    onLogout: () -> Unit,
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
    val demandRepository: DemandRepository = koinInject()
    val documentRepository: DocumentRepository = koinInject()
    val notificationRepository: NotificationRepository = koinInject()
    val adminRepository: AdminRepository = koinInject()

    val projectViewModel = remember(user.id) {
        ProjectViewModel(projectRepository, userRepository)
    }
    val taskViewModel = remember(user.id) {
        TaskViewModel(taskRepository, chatRepository, userRepository, projectRepository)
    }
    val homeViewModel = remember(user.id) {
        HomeViewModel(taskRepository, notificationRepository, user)
    }
    val notificationViewModel = remember(user.id) {
        NotificationViewModel(notificationRepository, taskRepository, user)
    }
    val wmsViewModel = if (NavigationDestination.WMS in destinations) {
        remember(user.id) { WmsViewModel(wmsRepository, user.id) }
    } else {
        null
    }
    val bomViewModel = if (NavigationDestination.BOM in destinations) {
        remember(user) { BomViewModel(bomRepository, wmsRepository, user) }
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
                    onOpenProfile = { selected = NavigationDestination.PROFILE }
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
                        shape = RoundedCornerShape(14.dp),
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
                        onLogout = onLogout
                    )
                }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    MainContent(
                        destination = selected,
                        user = user,
                        onLogout = onLogout,
                        homeViewModel = homeViewModel,
                        projectViewModel = projectViewModel,
                        taskViewModel = taskViewModel,
                        notificationViewModel = notificationViewModel,
                        wmsViewModel = wmsViewModel,
                        bomViewModel = bomViewModel,
                        demandViewModel = demandViewModel,
                        documentViewModel = documentViewModel,
                        adminViewModel = adminViewModel,
                        onOpenTask = onOpenTask,
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
    onOpenProfile: () -> Unit
) {
    val palette = LocalBrandPalette.current
    val theme = LocalThemeController.current
    var menuExpanded by remember { mutableStateOf(false) }
    var userMenuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showMenu) {
            Box {
                RoundIconButton(glyph = ErpIconGlyph.Menu) { menuExpanded = true }
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
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(
                glyph = ErpIconGlyph.Bell,
                onClick = onOpenNotifications
            )
            Spacer(Modifier.width(8.dp))
            RoundIconButton(
                glyph = if (theme.isDark) ErpIconGlyph.Sun else ErpIconGlyph.Moon,
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

@Composable
private fun RoundIconButton(
    glyph: ErpIconGlyph,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = LocalBrandPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val alpha by animateFloatAsState(
        targetValue = if (hovered) 1f else 0.75f,
        animationSpec = tween(160),
        label = "roundBtn"
    )
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(palette.glassOverlay.copy(alpha = 0.7f * alpha))
            .border(1.dp, palette.glassBorder.first(), RoundedCornerShape(13.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        ErpIcon(
            glyph = glyph,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(19.dp)
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
    onLogout: () -> Unit
) {
    val palette = LocalBrandPalette.current
    Column(
        modifier = Modifier
            .width(268.dp)
            .fillMaxHeight()
            .padding(start = 16.dp, top = 0.dp, bottom = 16.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.verticalGradient(palette.sidebarGradient))
            .border(
                1.dp,
                Color.White.copy(alpha = 0.10f),
                RoundedCornerShape(26.dp)
            )
    ) {
        // Логотип
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(Brand.BlueBright, Brand.Violet))),
                contentAlignment = Alignment.Center
            ) {
                ErpIcon(
                    glyph = ErpIconGlyph.Sparkle,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "Авиаагрегат",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Text(
                    text = "ERP · WMS · MES",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.55f)
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
        ) {
            NavGroup.entries.forEach { group ->
                val items = destinations.filter { it.group == group }
                if (items.isNotEmpty()) {
                    Text(
                        text = group.label.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.38f),
                        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 8.dp)
                    )
                    items.forEach { dest ->
                        SidebarItem(
                            destination = dest,
                            selected = dest == selected,
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
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = 0.07f))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
                .padding(12.dp)
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
                            color = Color.White.copy(alpha = 0.5f),
                            maxLines = 1
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(11.dp))
                        .clickable { onLogout() }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ErpIcon(
                        glyph = ErpIconGlyph.Logout,
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Выйти из системы",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.7f)
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
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val backgroundAlpha by animateFloatAsState(
        targetValue = when {
            selected -> 1f
            hovered -> 0.12f
            else -> 0.05f
        },
        animationSpec = tween(180),
        label = "sidebarItem"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) {
                    Brush.linearGradient(
                        listOf(Brand.Blue.copy(alpha = 0.95f), Brand.Indigo.copy(alpha = 0.9f))
                    )
                } else {
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = backgroundAlpha),
                            Color.White.copy(alpha = backgroundAlpha * 0.5f)
                        )
                    )
                }
            )
            .then(
                if (selected) {
                    Modifier.border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
                } else {
                    Modifier
                }
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(
                    if (selected) Color.White.copy(alpha = 0.2f)
                    else Color.White.copy(alpha = 0.08f)
                ),
            contentAlignment = Alignment.Center
        ) {
            ErpIcon(
                glyph = destination.glyph,
                tint = if (selected) Color.White else Color.White.copy(alpha = 0.72f),
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.width(11.dp))
        Text(
            text = destination.label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.78f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
            .shadow(12.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .background(palette.glassOverlay.copy(alpha = 0.95f))
            .border(1.dp, palette.glassBorder.first(), RoundedCornerShape(22.dp))
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
                        .clip(RoundedCornerShape(14.dp))
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
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(width = 38.dp, height = 28.dp)
                .clip(RoundedCornerShape(10.dp))
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
    homeViewModel: HomeViewModel,
    projectViewModel: ProjectViewModel,
    taskViewModel: TaskViewModel,
    notificationViewModel: NotificationViewModel,
    wmsViewModel: WmsViewModel?,
    bomViewModel: BomViewModel?,
    demandViewModel: DemandViewModel?,
    documentViewModel: DocumentViewModel,
    adminViewModel: AdminViewModel?,
    onOpenTask: (Long) -> Unit,
    onOpenNotifications: () -> Unit,
    onBackFromNotifications: () -> Unit
) {
    when (destination) {
        NavigationDestination.HOME -> HomeScreen(
            viewModel = homeViewModel,
            onOpenTask = onOpenTask,
            onOpenNotifications = onOpenNotifications
        )

        NavigationDestination.TASKS -> TasksScreen(
            currentUser = user,
            viewModel = taskViewModel
        )

        NavigationDestination.PROJECTS -> ProjectsScreen(
            currentUser = user,
            viewModel = projectViewModel
        )

        NavigationDestination.WMS -> wmsViewModel?.let {
            WmsScreen(viewModel = it)
        }

        NavigationDestination.BOM -> bomViewModel?.let {
            BomScreen(viewModel = it)
        }

        NavigationDestination.DEMANDS -> demandViewModel?.let {
            DemandScreen(viewModel = it, currentUser = user)
        }

        NavigationDestination.DOCUMENTS -> DocumentsScreen(viewModel = documentViewModel)

        NavigationDestination.NOTIFICATIONS -> NotificationScreen(
            viewModel = notificationViewModel,
            onOpenTask = onOpenTask,
            onBack = onBackFromNotifications
        )

        NavigationDestination.ADMIN -> adminViewModel?.let {
            AdminScreen(viewModel = it)
        }

        NavigationDestination.PROFILE -> ProfileScreen(
            user = user,
            onLogout = onLogout
        )
    }
}
