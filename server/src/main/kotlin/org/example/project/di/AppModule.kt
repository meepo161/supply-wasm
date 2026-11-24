package org.example.project.di

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.example.project.config.AppConfig
import org.example.project.realtime.ChatEventBus
import org.example.project.realtime.ConversationEventBus
import org.example.project.realtime.NotificationEventBus
import org.example.project.repository.*
import org.example.project.service.*
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DataSourceConnectionProvider
import org.jooq.impl.DefaultConfiguration
import org.jooq.impl.ThreadLocalTransactionProvider
import org.koin.dsl.module
import javax.sql.DataSource

fun appModule(config: AppConfig, pushTransport: PushTransport? = null) = module {
    single { config }

    single<DataSource> {
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = config.dbUrl
            username = config.dbUser
            password = config.dbPassword
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 10
            minimumIdle = 2
        })
    }

    single<DSLContext> {
        val configuration = DefaultConfiguration()
            .set(SQLDialect.POSTGRES)
            .set(ThreadLocalTransactionProvider(DataSourceConnectionProvider(get<DataSource>()), true))
        DSL.using(configuration)
    }

    single { PasswordHasher() }
    single { AuditRepository(get()) }
    single { AuditService(get()) }

    single { SessionRepository(get()) }
    single { PermissionRepository(get()) }
    single { UserRolesRepository(get()) }

    single { SessionService(get(), get(), get(), config) }

    single { UserRepository(get()) }
    single { DepartmentRepository(get()) }
    single { RoleRepository(get()) }
    single { ProjectRepository(get()) }
    single { ProjectHistoryRepository(get()) }
    single { ProjectPlanRepository(get()) }
    single { ChatRepository(get()) }
    single { TaskRepository(get()) }
    single { NotificationRepository(get()) }
    single { SearchRepository(get()) }
    single { WmsRepository(get()) }
    single { WmsTransferRepository(get()) }
    single { WmsIssueRepository(get()) }
    single { AttachmentRepository(get()) }
    single { BomRepository(get()) }
    single<FileStorage> { LocalFileStorage(config.storagePath) }
    single { LoginThrottleService(get()) }
    single { MessageRateLimiter() }

    single { AuthService(get(), get(), get(), get(), get(), get(), get(), get()) }
    single { UserService(get(), get(), get(), get(), get()) }
    single { DepartmentService(get(), get()) }
    single { RoleService(get(), get(), get()) }
    single { PermissionService(get(), get(), get()) }
    single {
        ProjectService(
            projectRepository = get(),
            userRepository = get(),
            taskRepository = get(),
            demandRepository = get(),
            documentRepository = get(),
            historyRepository = get(),
            auditService = get(),
            dsl = get()
        )
    }
    single {
        ProjectPlanService(
            planRepository = get(),
            projectRepository = get(),
            auditService = get(),
            dsl = get()
        )
    }
    single { TaskService(get(), get(), get(), get(), get(), get()) }
    single { NotificationService(get()) }
    single<PushSubscriptionStore> { PushSubscriptionRepository(get()) }
    single<PushTransport> { pushTransport ?: WebPushTransport(config.push) }
    single { PushService(get(), get(), config.push) }
    single { ChatEventBus() }
    single { NotificationEventBus() }
    single { ConversationRepository(get()) }
    single { ConversationEventBus() }
    single { ConversationService(get(), get(), get(), get(), get(), get(), get()) }
    single { ChatService(get(), get(), get(), get(), get(), get(), get()) }
    single { UnifiedChatListService(get(), get(), get(), get(), get()) }
    single { SearchService(get(), get()) }
    single { WmsService(get(), get(), get()) }
    single { org.example.project.repository.NomenclatureCardRepository(get()) }
    single { NomenclatureCardService(get(), get(), get(), get()) }
    single { ReceiptService(get(), get(), get(), get()) }
    single { ReceiptQcService(get(), get()) }
    single { WmsTransferService(get(), get(), get()) }
    single { WmsIssueService(get(), get(), get()) }
    single { WmsWriteOffRepository(get()) }
    single { WmsWriteOffService(get(), get(), get()) }
    single { WriteOffApprovalTarget(get()) }
    single { WmsInventoryRepository(get()) }
    single { WmsInventoryService(get(), get(), get()) }
    single { ProductionRepository(get()) }
    single { ProductionService(get(), get(), get()) }
    single { ProductionExtrasService(get(), get(), get()) }
    single { SupplierRepository(get()) }
    single { SupplierService(get(), get(), get(), get()) }
    single { SupplierClaimService(get(), get()) }
    single { org.example.project.service.SupplierInvoiceService(get(), get(), get(), get(), get()) }
    single { AnalyticsService(get(), get()) }
    single { PurchaseOrderRepository(get()) }
    single { PurchaseOrderService(get(), get(), get(), get()) }
    single { AttachmentService(get(), get(), get(), get(), get(), get(), get()) }
    single { BomService(get(), get(), get()) }
    single { DocumentRepository(get()) }
    single { DocumentAccess(get(), get()) }
    single { DocumentService(get(), get(), get(), get(), get(), get()) }
    single { DemandRepository(get()) }
    single { ApprovalRepository(get()) }
    single { DemandApprovalTarget(get(), get(), get()) }
    single { PurchaseOrderApprovalTarget(get(), get()) }
    single { DocumentApprovalTarget(get(), get()) }
    single { ProductionOrderApprovalTarget(get(), get()) }
    single<ApprovalGuard> { ApprovalRouteGuard(get()) }
    single {
        ApprovalService(
            get(),
            listOf<ApprovalTargetPort>(
                get<DemandApprovalTarget>(),
                get<WriteOffApprovalTarget>(),
                get<PurchaseOrderApprovalTarget>(),
                get<DocumentApprovalTarget>(),
                get<ProductionOrderApprovalTarget>()
            ),
            get(), get(), get(), get()
        )
    }
    single { DemandService(get(), get(), get(), get(), get(), get()) }
    single { ErpExtrasService(get(), get(), get()) }
}
