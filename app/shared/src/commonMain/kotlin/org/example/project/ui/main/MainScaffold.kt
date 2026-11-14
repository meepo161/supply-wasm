package org.example.project.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        val isWide = maxWidth >= 600.dp

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(selected.label) },
                    actions = {
                        Text(
                            text = user.fullName,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .align(Alignment.CenterVertically)
                        )
                        TextButton(onClick = onLogout) {
                            Text("Выйти")
                        }
                    }
                )
            },
            bottomBar = {
                if (!isWide) {
                    BottomNavigationBar(
                        destinations = destinations,
                        selected = selected,
                        onSelect = { selected = it }
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            Row(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                if (isWide) {
                    SideNavigationRail(
                        destinations = destinations,
                        selected = selected,
                        onSelect = { selected = it }
                    )
                }
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

@Composable
private fun BottomNavigationBar(
    destinations: List<NavigationDestination>,
    selected: NavigationDestination,
    onSelect: (NavigationDestination) -> Unit
) {
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

    NavigationBar {
        primary.forEach { destination ->
            NavigationBarItem(
                selected = destination == selected,
                onClick = { onSelect(destination) },
                icon = { NavigationIcon(destination.label) },
                label = { Text(destination.label) }
            )
        }
        if (overflow.isNotEmpty()) {
            NavigationBarItem(
                selected = overflowSelected,
                onClick = { menuExpanded = true },
                icon = {
                    Box {
                        NavigationIcon("Ещё")
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
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
                },
                label = { Text("Ещё") }
            )
        }
    }
}

@Composable
private fun SideNavigationRail(
    destinations: List<NavigationDestination>,
    selected: NavigationDestination,
    onSelect: (NavigationDestination) -> Unit
) {
    val scrollState = rememberScrollState()
    NavigationRail {
        Column(modifier = Modifier.verticalScroll(scrollState)) {
            destinations.forEach { destination ->
                NavigationRailItem(
                    selected = destination == selected,
                    onClick = { onSelect(destination) },
                    icon = { NavigationIcon(destination.label) },
                    label = { Text(destination.label) }
                )
            }
        }
    }
}

@Composable
private fun NavigationIcon(label: String) {
    Box(
        modifier = Modifier.size(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label.take(1),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

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
