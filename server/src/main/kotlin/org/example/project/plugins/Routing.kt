package org.example.project.plugins

import io.ktor.server.application.*
import io.ktor.server.routing.*
import org.example.project.config.AppConfig
import org.example.project.realtime.ChatEventBus
import org.example.project.realtime.ConversationEventBus
import org.example.project.realtime.ErpEventBus
import org.example.project.realtime.NotificationEventBus
import org.example.project.repository.ChatRepository
import org.example.project.repository.ConversationRepository
import org.example.project.realtime.chatWebSocketRoutes
import org.example.project.realtime.conversationWebSocketRoutes
import org.example.project.realtime.erpEventWebSocketRoutes
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
    val projectPlanService: ProjectPlanService by inject()
    val taskService: TaskService by inject()
    val chatService: ChatService by inject()
    val notificationService: NotificationService by inject()
    val pushService: PushService by inject()
    val searchService: SearchService by inject()
    val wmsService: WmsService by inject()
    val nomenclatureCardService: NomenclatureCardService by inject()
    val receiptService: ReceiptService by inject()
    val receiptQcService: ReceiptQcService by inject()
    val wmsTransferService: WmsTransferService by inject()
    val wmsIssueService: WmsIssueService by inject()
    val wmsWriteOffService: WmsWriteOffService by inject()
    val wmsInventoryService: WmsInventoryService by inject()
    val productionService: ProductionService by inject()
    val productionExtrasService: ProductionExtrasService by inject()
    val supplierService: SupplierService by inject()
    val supplierClaimService: SupplierClaimService by inject()
    val supplierInvoiceService: org.example.project.service.SupplierInvoiceService by inject()
    val analyticsService: AnalyticsService by inject()
    val purchaseOrderService: PurchaseOrderService by inject()
    val attachmentService: AttachmentService by inject()
    val bomService: BomService by inject()
    val documentService: DocumentService by inject()
    val demandService: DemandService by inject()
    val approvalService: ApprovalService by inject()
    val chatEventBus: ChatEventBus by inject()
    val notificationEventBus: NotificationEventBus by inject()
    val conversationEventBus: ConversationEventBus by inject()
    val erpEventBus: ErpEventBus by inject()
    val chatRepository: ChatRepository by inject()
    val conversationRepository: ConversationRepository by inject()
    val conversationService: ConversationService by inject()
    val unifiedChatListService: UnifiedChatListService by inject()
    val auditService: AuditService by inject()
    val erpExtrasService: ErpExtrasService by inject()
    val assistantService: AssistantService by inject()
    val dsl: org.jooq.DSLContext by inject()
    val config: AppConfig by inject()

    routing {
        healthRoutes(dsl)
        auditRoutes(auditService)
        authRoutes(authService, sessionService, config)
        userRoutes(userService)
        departmentRoutes(departmentService)
        roleRoutes(roleService)
        permissionRoutes(permissionService)
        projectRoutes(projectService, taskService, demandService, documentService)
        projectPlanRoutes(projectPlanService)
        taskRoutes(taskService)
        chatRoutes(chatService)
        notificationRoutes(notificationService)
        pushRoutes(pushService)
        searchRoutes(searchService)
        chatWebSocketRoutes(chatService, chatRepository, sessionService, chatEventBus, config.cookieName, config.allowedOrigins)
        notificationWebSocketRoutes(notificationService, sessionService, notificationEventBus, config.cookieName, config.allowedOrigins)
        erpEventWebSocketRoutes(sessionService, erpEventBus, config.cookieName, config.allowedOrigins)
        conversationRoutes(conversationService)
        unifiedChatRoutes(unifiedChatListService)
        conversationWebSocketRoutes(conversationService, conversationRepository, sessionService, conversationEventBus, config.cookieName, config.allowedOrigins)
        wmsRoutes(wmsService)
        nomenclatureCardRoutes(nomenclatureCardService, documentService)
        receiptRoutes(receiptService, receiptQcService)
        wmsTransferRoutes(wmsTransferService)
        wmsIssueRoutes(wmsIssueService)
        wmsWriteOffRoutes(wmsWriteOffService)
        wmsInventoryRoutes(wmsInventoryService)
        productionRoutes(productionService)
        productionExtrasRoutes(productionExtrasService)
        supplierRoutes(supplierService)
        supplierClaimRoutes(supplierClaimService)
        supplierInvoiceRoutes(supplierInvoiceService)
        analyticsRoutes(analyticsService)
        erpExtrasRoutes(erpExtrasService)
        purchaseOrderRoutes(purchaseOrderService)
        attachmentRoutes(attachmentService)
        bomRoutes(bomService)
        documentRoutes(documentService)
        demandRoutes(demandService)
        approvalRoutes(approvalService)
        assistantRoutes(assistantService)
    }
}
