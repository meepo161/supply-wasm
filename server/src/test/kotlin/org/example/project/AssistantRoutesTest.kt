package org.example.project

import com.typesafe.config.ConfigFactory
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.serializer
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.example.project.config.AssistantSettings
import org.example.project.dto.*
import org.example.project.exception.ValidationException
import org.example.project.model.CurrentUser
import org.example.project.repository.AssistantConversationRepository
import org.example.project.service.*
import java.io.ByteArrayOutputStream
import java.util.Base64
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.koin.ktor.ext.getKoin
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssistantRoutesTest {

    @Before
    fun reset() {
        TestDatabase.reset()
    }

    @Test
    fun `assistant endpoints require authentication and validate empty messages`() = testApp {
        val unauthClient = createClient { install(HttpCookies) }
        assertEquals(HttpStatusCode.Unauthorized, unauthClient.get("/api/assistant/status").status)
        val unauthChat = unauthClient.post("/api/assistant/chat") {
            contentType(ContentType.Application.Json)
            setBody(stringify(AssistantChatRequestDto(listOf(AssistantMessageDto(AssistantRole.USER, "Привет")))))
        }
        assertEquals(HttpStatusCode.Unauthorized, unauthChat.status)

        val admin = createClient { install(HttpCookies) }
        admin.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin", "admin123", "Admin User")))
        }
        admin.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("admin", "admin123")))
        }

        val statusResp = admin.get("/api/assistant/status")
        assertEquals(HttpStatusCode.OK, statusResp.status)
        val statusBody = statusResp.bodyAs<ApiResponse<AssistantStatusDto>>()
        assertTrue(statusBody.success)
        assertEquals("qwen3.5:9b", statusBody.data!!.model)

        val emptyChatResp = admin.post("/api/assistant/chat") {
            contentType(ContentType.Application.Json)
            setBody(stringify(AssistantChatRequestDto(emptyList())))
        }
        assertEquals(HttpStatusCode.BadRequest, emptyChatResp.status)
    }

    @Test
    fun `assistant service filters tools by permissions and completes tool round`() = testApp {
        val bootstrapClient = createClient { install(HttpCookies) }
        val bootResp = bootstrapClient.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin", "admin123", "Admin User")))
        }
        val bootUserId = bootResp.bodyAs<ApiResponse<LoginResponse>>().data!!.user.id

        val koin = application.getKoin()
        val searchService = koin.get<SearchService>()
        val taskService = koin.get<TaskService>()
        val wmsService = koin.get<WmsService>()
        val demandService = koin.get<DemandService>()
        val purchaseOrderService = koin.get<PurchaseOrderService>()
        val supplierService = koin.get<SupplierService>()
        val projectService = koin.get<ProjectService>()
        val productionService = koin.get<ProductionService>()
        val convRepo = koin.get<AssistantConversationRepository>()

        var seenTools: List<String> = emptyList()
        var callIndex = 0
        val fakeProvider = object : LlmProvider {
            override fun status() = AssistantStatusDto(available = true, model = "qwen3.5:9b")
            override fun chat(messages: List<LlmChatMessage>, tools: List<LlmToolDefinition>): LlmChatResult {
                callIndex++
                if (callIndex == 1) {
                    seenTools = tools.map { it.name }
                    return LlmChatResult(
                        content = "",
                        toolCalls = listOf(LlmToolCall("my_tasks", buildJsonObject {}))
                    )
                }
                return LlmChatResult(
                    content = "Открытых задач: 0.",
                    toolCalls = emptyList()
                )
            }
        }

        val service = AssistantService(
            settings = AssistantSettings(
                enabled = true,
                baseUrl = "http://127.0.0.1:11434",
                model = "qwen3.5:9b",
                timeoutSeconds = 10
            ),
            llmProvider = fakeProvider,
            searchService = searchService,
            taskService = taskService,
            wmsService = wmsService,
            demandService = demandService,
            purchaseOrderService = purchaseOrderService,
            supplierService = supplierService,
            projectService = projectService,
            productionService = productionService,
            conversationRepository = convRepo
        )

        val limitedUser = CurrentUser(
            userId = bootUserId,
            login = "operator",
            fullName = "Оператор",
            permissions = emptySet()
        )
        val response = service.chat(
            AssistantChatRequestDto(listOf(AssistantMessageDto(AssistantRole.USER, "Какие у меня задачи?"))),
            limitedUser
        )

        assertEquals("Открытых задач: 0.", response.reply)
        assertEquals("my_tasks", response.toolUsed)
        assertTrue(response.conversationId != null)

        val listed = service.listConversations(limitedUser)
        assertEquals(1, listed.size)
        val detail = service.getConversation(response.conversationId!!, limitedUser)
        assertEquals(2, detail.messages.size)
        service.deleteConversation(response.conversationId!!, limitedUser)
        assertEquals(0, service.listConversations(limitedUser).size)
        assertTrue("search_erp" in seenTools)
        assertTrue("my_tasks" in seenTools)
        assertFalse("wms_stock" in seenTools)
        assertFalse("demands_list" in seenTools)
        assertFalse("purchase_orders" in seenTools)
    }

    @Test
    fun `assistant file extractor parses md txt pdf and jpg attachments`() {
        val mdBytes = "# Счёт №123\nПоставщик: ООО ЭлектроТяга\nИтого: 15000 руб.".toByteArray(Charsets.UTF_8)
        val pdfBytes = ByteArrayOutputStream().use { out ->
            PDDocument().use { doc ->
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    cs.newLineAtOffset(50f, 700f)
                    cs.showText("Invoice #456 Total 9900 RUB")
                    cs.endText()
                }
                doc.save(out)
            }
            out.toByteArray()
        }
        val tinyPngBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg=="

        val extracted = AssistantFileExtractor.extract(
            listOf(
                AssistantUploadDto("note.md", "text/markdown", Base64.getEncoder().encodeToString(mdBytes)),
                AssistantUploadDto("invoice.pdf", "application/pdf", Base64.getEncoder().encodeToString(pdfBytes)),
                AssistantUploadDto("scan.jpg", "image/jpeg", tinyPngBase64)
            )
        )

        assertEquals(1, extracted.imagesBase64.size)
        assertTrue(extracted.textBlocks.any { it.contains("Счёт №123") && it.contains("15000") })
        assertTrue(extracted.textBlocks.any { it.contains("Invoice #456 Total 9900 RUB") })

        val bad = runCatching {
            AssistantFileExtractor.extract(
                listOf(AssistantUploadDto("archive.zip", "application/zip", tinyPngBase64))
            )
        }.exceptionOrNull()
        assertTrue(bad is ValidationException)
    }

    @Test
    fun `confirmDraft creates supplier nomenclature and demand via real services`() = testApp {
        val admin = createClient { install(HttpCookies) }
        admin.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin", "admin123", "Admin User")))
        }
        admin.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("admin", "admin123")))
        }

        val draft = AssistantDraftDto(
            draftId = "test-219658",
            title = "Счёт №219658 от ОАО СЗТТ",
            summary = "Новый поставщик и 2 позиции",
            invoiceNumber = "219658",
            invoiceDate = "2025-02-25",
            supplier = AssistantDraftSupplierDto(
                name = "ОАО СЗТТ",
                inn = "7707083893",
                kpp = "665801001"
            ),
            lines = listOf(
                AssistantDraftLineDto(
                    code = "10000185984",
                    name = "Топ-0,66-5-0,2S-120/5 У3",
                    unit = "шт",
                    quantity = "3",
                    unitPrice = "2220.00"
                ),
                AssistantDraftLineDto(
                    code = "10000068714",
                    name = "Топ-0,66-5-0,2S-30/5 У3",
                    unit = "шт",
                    quantity = "3",
                    unitPrice = "2270.00"
                )
            ),
            createDemand = true
        )

        val confirmResp = admin.post("/api/assistant/confirm-draft") {
            contentType(ContentType.Application.Json)
            setBody(stringify(AssistantConfirmDraftRequestDto(draft = draft)))
        }
        val confirmText = confirmResp.bodyAsText()
        assertEquals(HttpStatusCode.OK, confirmResp.status, "Body: $confirmText")
        val body = json.decodeFromString<ApiResponse<AssistantConfirmDraftResponseDto>>(confirmText)
        assertTrue(body.success)
        val data = body.data!!
        assertTrue(data.createdSupplierId != null)
        assertEquals(2, data.createdNomenclatureCount)
        assertTrue(!data.createdDemandNumber.isNullOrBlank())

        // Also verify universal action draft (CREATE_TASK)
        val taskDraft = AssistantDraftDto(
            draftId = "task-action-1",
            title = "Новая задача",
            summary = "Проверить поставку СЗТТ",
            createDemand = false,
            actions = listOf(
                AssistantActionDto(
                    kind = AssistantActionKind.CREATE_TASK,
                    label = "Создать задачу: Проверить поставку СЗТТ",
                    params = mapOf("title" to "Проверить поставку СЗТТ", "priority" to "HIGH")
                )
            )
        )
        val taskResp = admin.post("/api/assistant/confirm-draft") {
            contentType(ContentType.Application.Json)
            setBody(stringify(AssistantConfirmDraftRequestDto(draft = taskDraft)))
        }
        assertEquals(HttpStatusCode.OK, taskResp.status)
        val taskBody = json.decodeFromString<ApiResponse<AssistantConfirmDraftResponseDto>>(taskResp.bodyAsText())
        assertTrue(taskBody.success)
        assertTrue(taskBody.data!!.summary.contains("Создана задача"))
    }

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) {
        testApplication {
            environment {
                config = HoconApplicationConfig(
                    ConfigFactory.parseString(
                        """
                        ktor {
                          application {
                            modules = [ org.example.project.ApplicationKt.module ]
                          }
                        }
                        database {
                          url = "${TestDatabase.TEST_URL}"
                          user = "erp"
                          password = "erp"
                        }
                        session {
                          ttlMinutes = 60
                          cookieName = "erp_session"
                        }
                        security {
                          allowedOrigins = "http://localhost:8080,http://localhost:8081"
                          cookieSecure = false
                        }
                        storage {
                          path = "./.local/test-storage"
                        }
                        assistant {
                          enabled = false
                          baseUrl = "http://127.0.0.1:11434"
                          model = "qwen3.5:9b"
                          timeoutSeconds = 10
                        }
                        """.trimIndent()
                    )
                )
            }
            block()
        }
    }

    private suspend inline fun <reified T> HttpResponse.bodyAs(): T =
        json.decodeFromString(serializer<T>(), bodyAsText())

    private inline fun <reified T> stringify(value: T): String =
        json.encodeToString(serializer<T>(), value)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        @JvmStatic
        @BeforeClass
        fun setup() {
            TestDatabase.init()
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            TestDatabase.destroy()
        }
    }
}
