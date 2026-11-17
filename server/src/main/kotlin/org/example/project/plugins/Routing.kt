package org.example.project.plugins

import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.example.project.config.AppConfig
import org.example.project.realtime.ChatEventBus
import org.example.project.realtime.ConversationEventBus
import org.example.project.realtime.NotificationEventBus
import org.example.project.repository.ChatRepository
import org.example.project.repository.ConversationRepository
import org.example.project.realtime.chatWebSocketRoutes
import org.example.project.realtime.conversationWebSocketRoutes
import org.example.project.realtime.notificationWebSocketRoutes
import org.example.project.route.*
import org.example.project.service.*
import org.koin.ktor.ext.inject

fun Application.configureRouting() {
    val authService: AuthService by inject()
    val sessionService: SessionService by inject()
    val userService: UserService by inject()
    val departmentService: DepartmentService by inject()
    val roleService: RoleService by inject()
    val permissionService: PermissionService by inject()
    val projectService: ProjectService by inject()
    val taskService: TaskService by inject()
    val chatService: ChatService by inject()
    val notificationService: NotificationService by inject()
    val searchService: SearchService by inject()
    val wmsService: WmsService by inject()
    val wmsTransferService: WmsTransferService by inject()
    val wmsIssueService: WmsIssueService by inject()
    val wmsWriteOffService: WmsWriteOffService by inject()
    val wmsInventoryService: WmsInventoryService by inject()
    val productionService: ProductionService by inject()
    val attachmentService: AttachmentService by inject()
    val bomService: BomService by inject()
    val documentService: DocumentService by inject()
    val demandService: DemandService by inject()
    val approvalService: ApprovalService by inject()
    val chatEventBus: ChatEventBus by inject()
    val notificationEventBus: NotificationEventBus by inject()
    val conversationEventBus: ConversationEventBus by inject()
    val chatRepository: ChatRepository by inject()
    val conversationRepository: ConversationRepository by inject()
    val conversationService: ConversationService by inject()
    val config: AppConfig by inject()

    routing {
        authRoutes(authService, sessionService, config)
        userRoutes(userService)
        departmentRoutes(departmentService)
        roleRoutes(roleService)
        permissionRoutes(permissionService)
        projectRoutes(projectService)
        taskRoutes(taskService)
        chatRoutes(chatService)
        notificationRoutes(notificationService)
        searchRoutes(searchService)
        chatWebSocketRoutes(chatService, chatRepository, sessionService, chatEventBus, config.cookieName, config.allowedOrigins)
        notificationWebSocketRoutes(notificationService, sessionService, notificationEventBus, config.cookieName, config.allowedOrigins)
        conversationRoutes(conversationService)
        conversationWebSocketRoutes(conversationService, conversationRepository, sessionService, conversationEventBus, config.cookieName, config.allowedOrigins)
        wmsRoutes(wmsService)
        wmsTransferRoutes(wmsTransferService)
        wmsIssueRoutes(wmsIssueService)
        wmsWriteOffRoutes(wmsWriteOffService)
        wmsInventoryRoutes(wmsInventoryService)
        productionRoutes(productionService)
        attachmentRoutes(attachmentService)
        bomRoutes(bomService)
        documentRoutes(documentService)
        demandRoutes(demandService)
        approvalRoutes(approvalService)
    }
}
