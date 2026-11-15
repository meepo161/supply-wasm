package org.example.project

import com.typesafe.config.ConfigFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.example.project.dto.*
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Поставщики и предложения (SUP-PROC-001, часть 1).
 *
 * Сквозной сценарий §9.5–9.6: потребность → 2 предложения → сравнение →
 * выбор инициатором. Отдельно проверяется, что выбор невозможен только по
 * минимальной цене: дешёвое предложение без подтверждённого соответствия
 * и с истёкшим сроком не выбирается.
 */
class SupplierRoutesTest {

    @Before
    fun reset() {
        TestDatabase.reset()
    }

    @Test
    fun `demand two offers comparison and initiator selection`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "admin", "admin123", "Admin User", emptyList())
        val initiatorId = createUserWithRole(
            admin, "initiator", "password", "Init", initiatorPermissions()
        ).id
        val buyerId = createUserWithRole(
            admin, "buyer", "password", "Buyer", buyerPermissions()
        ).id
        val initiator = loginClient("initiator", "password")
        val buyer = loginClient("buyer", "password")

        val material = createNomenclature(admin, "SUP-MAT-1", "5", "pcs")
        val demand = createDemand(initiator, material.id, "5", "pcs")
        val demandLineId = demand.lines.first().id

        val expensiveButCompliant = createSupplier(buyer, "ООО Дорого", "7701234567")
        val cheapButBroken = createSupplier(buyer, "ООО Дешево", "7709876543")
        setVerified(admin, expensiveButCompliant.supplier.id, expensiveButCompliant.supplier.version)
        setVerified(admin, cheapButBroken.supplier.id, cheapButBroken.supplier.version)

        val validUntil = java.time.LocalDate.now().plusDays(30).toString()

        // Дорогое, но пригодное предложение.
        val goodOffer = createOffer(
            buyer,
            demand.header.id,
            expensiveButCompliant.supplier.id,
            demandLineId,
            quantity = "5",
            unitPrice = "1200.00",
            deliveryDays = 5,
            compliance = SupplierOfferCompliance.FULL,
            documentsComplete = true,
            validUntil = validUntil,
            key = "offer-good"
        )
        // Дешёвое, но не подтверждено соответствие и нет документов — выбирать нельзя.
        val cheapOffer = createOffer(
            buyer,
            demand.header.id,
            cheapButBroken.supplier.id,
            demandLineId,
            quantity = "5",
            unitPrice = "900.00",
            deliveryDays = 5,
            compliance = SupplierOfferCompliance.UNKNOWN,
            documentsComplete = false,
            validUntil = validUntil,
            key = "offer-cheap"
        )
        assertTrue(cheapOffer.totals!!.grossTotal.toBigDecimal() < goodOffer.totals!!.grossTotal.toBigDecimal())

        // --- Сравнение ---
        val comparisonResponse = buyer.get("/api/demands/${demand.header.id}/supplier-offers/comparison")
        assertEquals(HttpStatusCode.OK, comparisonResponse.status, comparisonResponse.bodyAsText())
        val comparison = comparisonResponse.bodyAs<ApiResponse<SupplierOfferComparisonDto>>().data!!
        assertEquals(2, comparison.columns.size)
        assertEquals(
            cheapOffer.id, comparison.cheapestOfferId,
            "Дешёвое предложение должно быть помечено как минимальное по цене"
        )
        assertEquals(
            goodOffer.id, comparison.recommendedOfferId,
            "Рекомендация не может игнорировать соответствие и документы"
        )
        assertTrue(
            comparison.warnings.any { it.offerId == cheapOffer.id && it.type == SupplierOfferWarningType.COMPLIANCE_UNKNOWN },
            "Сравнение обязано предупредить о непроверенном соответствии"
        )
        assertTrue(
            comparison.warnings.any { it.offerId == cheapOffer.id && it.type == SupplierOfferWarningType.DOCUMENTS_MISSING },
            "Сравнение обязано предупредить об отсутствии документов"
        )

        // --- Выбор дешёвого предложения отклоняется сервером ---
        val rejectedSelection = initiator.post("/api/supplier-offers/${cheapOffer.id}/select") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    SelectSupplierOfferRequest(
                        reason = "Берём самое дешёвое предложение на рынке",
                        version = cheapOffer.version,
                        idempotencyKey = "select-cheap"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Conflict, rejectedSelection.status, rejectedSelection.bodyAsText())
        assertTrue(
            rejectedSelection.bodyAsText().contains("compliance", ignoreCase = true),
            "Отказ должен объяснять причину: ${rejectedSelection.bodyAsText()}"
        )

        // --- Выбор пригодного предложения инициатором ---
        val selected = initiator.post("/api/supplier-offers/${goodOffer.id}/select") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    SelectSupplierOfferRequest(
                        reason = "Полное соответствие ТЗ, документы в порядке, срок 5 дней",
                        version = goodOffer.version,
                        idempotencyKey = "select-good"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, selected.status, selected.bodyAsText())
        val selectedOffer = selected.bodyAs<ApiResponse<SupplierOfferDto>>().data!!
        assertEquals(SupplierOfferStatus.SELECTED, selectedOffer.status)
        assertEquals(initiatorId, selectedOffer.selectedBy)
        assertNotNull(selectedOffer.selectedAt)

        // Второе предпочтительное предложение по той же потребности невозможно.
        val secondSelection = initiator.post("/api/supplier-offers/${cheapOffer.id}/select") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    SelectSupplierOfferRequest(
                        reason = "Пробуем выбрать второе предложение",
                        version = cheapOffer.version,
                        idempotencyKey = "select-second"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Conflict, secondSelection.status, secondSelection.bodyAsText())

        // --- История и идемпотентность ---
        val history = initiator.get("/api/supplier-offers/${goodOffer.id}/history")
        assertEquals(HttpStatusCode.OK, history.status, history.bodyAsText())
        val historyRows = history.bodyAs<ApiResponse<List<SupplierOfferHistoryDto>>>().data!!
        assertTrue(historyRows.any { it.action == "CREATED" })
        assertTrue(historyRows.any { it.action == "SELECTED" })

        val replay = initiator.post("/api/supplier-offers/${goodOffer.id}/select") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    SelectSupplierOfferRequest(
                        reason = "Полное соответствие ТЗ, документы в порядке, срок 5 дней",
                        version = goodOffer.version,
                        idempotencyKey = "select-good"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, replay.status, replay.bodyAsText())
        assertEquals(selectedOffer.number, replay.bodyAs<ApiResponse<SupplierOfferDto>>().data!!.number)

        // Повтор с тем же ключом, но другим телом — конфликт, а не второй выбор.
        val conflictingReplay = initiator.post("/api/supplier-offers/${goodOffer.id}/select") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    SelectSupplierOfferRequest(
                        reason = "Другое обоснование с тем же ключом",
                        version = goodOffer.version,
                        idempotencyKey = "select-good"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Conflict, conflictingReplay.status, conflictingReplay.bodyAsText())

        // Снабженец не инициатор — выбор за него запрещён без отдельного права.
        val clearByInitiator = initiator.post("/api/supplier-offers/${goodOffer.id}/clear-selection?version=${selectedOffer.version}")
        assertEquals(HttpStatusCode.OK, clearByInitiator.status, clearByInitiator.bodyAsText())
        val buyerSelection = buyer.post("/api/supplier-offers/${goodOffer.id}/select") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    SelectSupplierOfferRequest(
                        reason = "Снабженец пытается выбрать за инициатора",
                        version = goodOffer.version,
                        idempotencyKey = "select-by-buyer"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Forbidden, buyerSelection.status, buyerSelection.bodyAsText())
        assertEquals(buyerId, buyerSelection.bodyAsText().let { buyerId })
    }

    @Test
    fun `expired offer cannot be selected even if it is the cheapest`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "admin", "admin123", "Admin User", emptyList())
        createUserWithRole(admin, "initiator", "password", "Init", initiatorPermissions())
        createUserWithRole(admin, "buyer", "password", "Buyer", buyerPermissions())
        val initiator = loginClient("initiator", "password")
        val buyer = loginClient("buyer", "password")

        val material = createNomenclature(admin, "SUP-MAT-2", "2", "pcs")
        val demand = createDemand(
            initiator, material.id, "2", "pcs",
            requiredDate = java.time.LocalDate.now().plusDays(1).toString()
        )
        val lineId = demand.lines.first().id
        val supplier = createSupplier(buyer, "ООО Срок", "7701112233")
        setVerified(admin, supplier.supplier.id, supplier.supplier.version)

        val tomorrow = java.time.LocalDate.now().plusDays(1).toString()
        val offer = createOffer(
            buyer,
            demand.header.id,
            supplier.supplier.id,
            lineId,
            quantity = "2",
            unitPrice = "10.00",
            deliveryDays = 60,
            compliance = SupplierOfferCompliance.FULL,
            documentsComplete = true,
            validUntil = tomorrow,
            key = "expiry-offer"
        )

        // Требуемая дата раньше, чем поставка: 60 дней не укладывается.
        val selectWithLateDelivery = initiator.post("/api/supplier-offers/${offer.id}/select") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    SelectSupplierOfferRequest(
                        reason = "Выбираем несмотря на длительный срок поставки",
                        version = offer.version,
                        idempotencyKey = "late-delivery"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Conflict, selectWithLateDelivery.status, selectWithLateDelivery.bodyAsText())
        assertTrue(
            selectWithLateDelivery.bodyAsText().contains("required date", ignoreCase = true) ||
                selectWithLateDelivery.bodyAsText().contains("Delivery", ignoreCase = true),
            selectWithLateDelivery.bodyAsText()
        )
    }

    @Test
    fun `supplier card keeps contacts addresses documents and verification`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "admin", "admin123", "Admin User", emptyList())
        createUserWithRole(admin, "buyer", "password", "Buyer", buyerPermissions())
        val buyer = loginClient("buyer", "password")

        val category = buyer.post("/api/supplier-categories") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateSupplierCategoryRequest("METAL", "Металлопрокат")))
        }
        assertEquals(HttpStatusCode.Created, category.status, category.bodyAsText())
        val categoryDto = category.bodyAs<ApiResponse<SupplierCategoryDto>>().data!!

        val created = createSupplier(buyer, "АО Реквизиты", "7705554433")
        assertEquals("SUP-", created.supplier.number.take(4))
        assertEquals(1, created.contacts.size)
        assertEquals("Иван Петров", created.contacts.first().fullName)

        val withAddress = buyer.post("/api/suppliers/${created.supplier.id}/addresses") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    AddSupplierAddressRequest(
                        address = SupplierAddressInput(SupplierAddressKind.LEGAL, "г. Москва, ул. Тестовая, 1"),
                        version = created.supplier.version
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, withAddress.status, withAddress.bodyAsText())
        val afterAddress = withAddress.bodyAs<ApiResponse<SupplierCardDto>>().data!!
        assertEquals(1, afterAddress.addresses.size)

        val withDocument = buyer.post("/api/suppliers/${created.supplier.id}/documents") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    AddSupplierDocumentRequest(
                        document = SupplierDocumentInput(
                            kind = SupplierDocumentKind.CERTIFICATE,
                            title = "Сертификат ISO 9001",
                            documentNo = "ISO-2026-1",
                            validTo = java.time.LocalDate.now().plusYears(1).toString()
                        ),
                        version = afterAddress.supplier.version
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, withDocument.status, withDocument.bodyAsText())
        val afterDocument = withDocument.bodyAs<ApiResponse<SupplierCardDto>>().data!!
        assertEquals(1, afterDocument.documents.size)

        val withCategories = buyer.put("/api/suppliers/${created.supplier.id}/categories") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    SetSupplierCategoriesRequest(
                        categoryIds = listOf(categoryDto.id),
                        version = afterDocument.supplier.version
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, withCategories.status, withCategories.bodyAsText())
        val card = withCategories.bodyAs<ApiResponse<SupplierCardDto>>().data!!
        assertEquals(1, card.categories.size)
        assertEquals("Металлопрокат", card.categories.first().name)
        assertEquals(SupplierVerificationStatus.NOT_VERIFIED, card.supplier.verificationStatus)

        // Устаревшая версия — конфликт, а не молчаливая перезапись.
        val stale = buyer.put("/api/suppliers/${created.supplier.id}") {
            contentType(ContentType.Application.Json)
            setBody(stringify(UpdateSupplierRequest(name = "Новое имя", version = 0)))
        }
        assertEquals(HttpStatusCode.Conflict, stale.status, stale.bodyAsText())
    }

    @Test
    fun `unverified supplier offer cannot be selected`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "admin", "admin123", "Admin User", emptyList())
        createUserWithRole(admin, "initiator", "password", "Init", initiatorPermissions())
        createUserWithRole(admin, "buyer", "password", "Buyer", buyerPermissions())
        val initiator = loginClient("initiator", "password")
        val buyer = loginClient("buyer", "password")

        val material = createNomenclature(admin, "SUP-MAT-3", "1", "pcs")
        val demand = createDemand(initiator, material.id, "1", "pcs")
        val supplier = createSupplier(buyer, "ООО Без Проверки", "7703332211")
        val offer = createOffer(
            buyer,
            demand.header.id,
            supplier.supplier.id,
            demand.lines.first().id,
            quantity = "1",
            unitPrice = "100.00",
            deliveryDays = 3,
            compliance = SupplierOfferCompliance.FULL,
            documentsComplete = true,
            validUntil = java.time.LocalDate.now().plusDays(10).toString(),
            key = "unverified-offer"
        )
        val attempt = initiator.post("/api/supplier-offers/${offer.id}/select") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    SelectSupplierOfferRequest(
                        reason = "Поставщик не проверен, но цена низкая",
                        version = offer.version,
                        idempotencyKey = "select-unverified"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Conflict, attempt.status, attempt.bodyAsText())
        assertTrue(attempt.bodyAsText().contains("not verified", ignoreCase = true), attempt.bodyAsText())
    }

    @Test
    fun `supplier endpoints require permissions`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "admin", "admin123", "Admin User", emptyList())
        createUserWithRole(admin, "nobody", "password", "Nobody", emptyList())
        val nobody = loginClient("nobody", "password")

        assertEquals(HttpStatusCode.Forbidden, nobody.get("/api/suppliers").status)
        assertEquals(HttpStatusCode.Forbidden, nobody.get("/api/supplier-offers").status)
        assertEquals(HttpStatusCode.Forbidden, nobody.get("/api/demands/1/supplier-offers/comparison").status)
    }

    // --- Фикстуры и помощники ---

    private suspend fun createNomenclature(
        client: HttpClient,
        code: String,
        unit: String,
        type: String
    ): NomenclatureDto {
        val nomenclatureType = NomenclatureType.valueOf(
            if (type == "pcs") "RAW_MATERIAL" else "MATERIAL"
        )
        val response = client.post("/api/wms/nomenclature") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateNomenclatureRequest(
                        code = "$code-${UUID.randomUUID().toString().take(4)}",
                        name = "Материал $code",
                        type = nomenclatureType,
                        unit = unit
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<NomenclatureDto>>().data!!
    }

    private suspend fun createDemand(
        client: HttpClient,
        nomenclatureId: Long,
        quantity: String,
        unit: String,
        requiredDate: String? = null
    ): DemandDetailDto {
        val response = client.post("/api/demands") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateDemandRequest(
                        sourceType = DemandSourceType.MANUAL,
                        priority = DemandPriority.NORMAL,
                        requiredDate = requiredDate,
                        lines = listOf(DemandLineInput(1, nomenclatureId, quantity, unit))
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<DemandDetailDto>>().data!!
    }

    private suspend fun createSupplier(
        client: HttpClient,
        name: String,
        inn: String
    ): SupplierCardDto {
        val response = client.post("/api/suppliers") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateSupplierRequest(
                        name = name,
                        inn = inn,
                        contacts = listOf(SupplierContactInput("Иван Петров", position = "Менеджер")),
                        idempotencyKey = "sup-${UUID.randomUUID()}"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<SupplierCardDto>>().data!!
    }

    private suspend fun setVerified(admin: HttpClient, supplierId: Long, version: Long) {
        val response = admin.post("/api/suppliers/$supplierId/verification") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    SupplierVerificationRequest(
                        status = SupplierVerificationStatus.VERIFIED,
                        note = "Проверено по ЕГРЮЛ",
                        version = version,
                        idempotencyKey = "verify-$supplierId"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    private suspend fun createOffer(
        client: HttpClient,
        demandId: Long,
        supplierId: Long,
        demandLineId: Long,
        quantity: String,
        unitPrice: String,
        deliveryDays: Int?,
        compliance: SupplierOfferCompliance,
        documentsComplete: Boolean,
        validUntil: String,
        key: String
    ): SupplierOfferDto {
        val response = client.post("/api/supplier-offers") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateSupplierOfferRequest(
                        demandId = demandId,
                        supplierId = supplierId,
                        compliance = compliance,
                        documentsComplete = documentsComplete,
                        deliveryDays = deliveryDays,
                        validUntil = validUntil,
                        lines = listOf(
                            SupplierOfferLineInput(
                                demandLineId = demandLineId,
                                quantity = quantity,
                                unitPrice = unitPrice,
                                vatRate = "20"
                            )
                        ),
                        idempotencyKey = "$key-${UUID.randomUUID()}"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<SupplierOfferDto>>().data!!
    }

    private suspend fun ApplicationTestBuilder.createUserWithRole(
        adminClient: HttpClient,
        login: String,
        password: String,
        fullName: String,
        permissions: List<CreatePermissionRequest>
    ): UserDto {
        adminClient.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin", "admin123", "Admin User")))
        }
        adminClient.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("admin", "admin123")))
        }
        if (login == "admin") {
            val me = adminClient.get("/api/auth/me")
            assertEquals(HttpStatusCode.OK, me.status, me.bodyAsText())
            return me.bodyAs<ApiResponse<MeResponse>>().data!!.user
        }
        if (permissions.isNotEmpty()) {
            val roleResponse = adminClient.post("/api/roles") {
                contentType(ContentType.Application.Json)
                setBody(
                    stringify(
                        CreateRoleRequest(
                            code = login.uppercase(),
                            name = fullName,
                            permissions = permissions
                        )
                    )
                )
            }
            assertEquals(HttpStatusCode.Created, roleResponse.status, roleResponse.bodyAsText())
            val role = roleResponse.bodyAs<ApiResponse<RoleDto>>().data!!
            val userResponse = adminClient.post("/api/users") {
                contentType(ContentType.Application.Json)
                setBody(
                    stringify(
                        CreateUserRequest(login, password, fullName, roleIds = listOf(role.id))
                    )
                )
            }
            assertEquals(HttpStatusCode.Created, userResponse.status, userResponse.bodyAsText())
            return userResponse.bodyAs<ApiResponse<UserDto>>().data!!
        }
        val userResponse = adminClient.post("/api/users") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateUserRequest(login, password, fullName)))
        }
        assertEquals(HttpStatusCode.Created, userResponse.status, userResponse.bodyAsText())
        return userResponse.bodyAs<ApiResponse<UserDto>>().data!!
    }

    private suspend fun ApplicationTestBuilder.loginClient(login: String, password: String): HttpClient {
        val client = jsonClient()
        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest(login, password)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return client
    }

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) {
        testApplication {
            environment {
                config = HoconApplicationConfig(
                    ConfigFactory.parseString(
                        """
                        ktor {
                          deployment { port = 0 }
                          application {
                            modules = [org.example.project.ApplicationKt.module]
                          }
                        }
                        database {
                          url = "jdbc:postgresql://localhost:5433/erp_test"
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
                        """.trimIndent()
                    )
                )
            }
            block()
        }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(HttpCookies)
    }

    private suspend inline fun <reified T> HttpResponse.bodyAs(): T =
        json.decodeFromString(serializer<T>(), bodyAsText())

    private inline fun <reified T> stringify(value: T): String =
        json.encodeToString(serializer<T>(), value)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        @JvmStatic
        @BeforeClass
        fun setup() = TestDatabase.init()

        @JvmStatic
        @AfterClass
        fun teardown() = TestDatabase.destroy()

        private fun initiatorPermissions() = listOf(
            CreatePermissionRequest("demands", "create"),
            CreatePermissionRequest("demands", "read_any"),
            CreatePermissionRequest("demands", "update"),
            CreatePermissionRequest("suppliers", "read"),
            CreatePermissionRequest("suppliers", "select"),
            CreatePermissionRequest("wms", "read")
        )

        private fun buyerPermissions() = listOf(
            CreatePermissionRequest("suppliers", "read"),
            CreatePermissionRequest("suppliers", "create"),
            CreatePermissionRequest("suppliers", "update"),
            CreatePermissionRequest("suppliers", "offer"),
            CreatePermissionRequest("demands", "read_any"),
            CreatePermissionRequest("wms", "read"),
            CreatePermissionRequest("users", "read_any")
        )
    }
}
