package org.example.project

import com.typesafe.config.ConfigFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
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
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Карточка номенклатуры (NSI-001, D-63): реальные HTTP-вызовы и миграции PostgreSQL. */
class NomenclatureCardRoutesTest {

    @Before
    fun reset() {
        TestDatabase.reset()
    }

    @Test
    fun `card summarises stock by single rule and suggests demand`() = testApp {
        val keeper = setupKeeper(extra = listOf(CreatePermissionRequest("demands", "create")))
        val warehouse = createWarehouse(keeper, "WH-1")
        val storage = createLocation(keeper, warehouse.id, "A-01", StorageLocationPurpose.STORAGE)
        val quarantine = createLocation(keeper, warehouse.id, "Q-01", StorageLocationPurpose.QUARANTINE)
        val defect = createLocation(keeper, warehouse.id, "D-01", StorageLocationPurpose.DEFECT)
        val item = createNomenclature(keeper, "MAT-1")
        postInitialStock(keeper, warehouse.id, storage.id, item.id, "10")
        postInitialStock(keeper, warehouse.id, quarantine.id, item.id, "3")
        postInitialStock(keeper, warehouse.id, defect.id, item.id, "2")
        reserve(keeper, warehouse.id, storage.id, item.id, "4")

        val card = getCard(keeper, item.id)
        updateCard(
            keeper, item.id,
            baseUpdate(card).copy(
                minStock = "5", safetyStock = "20", leadTimeDays = 7, minOrderQty = "10", orderMultiple = "5"
            )
        )
        val updated = getCard(keeper, item.id)

        assertQty("15", updated.stock.physicalQty)
        assertQty("6", updated.stock.availableQty)
        assertQty("4", updated.stock.reservedQty)
        assertQty("3", updated.stock.quarantineQty)
        assertQty("2", updated.stock.defectQty)
        assertQty("0", updated.stock.inTransitQty)
        assertQty("0", updated.stock.expectedQty)
        assertTrue(updated.stock.belowSafetyStock)
        assertFalse(updated.stock.belowMinStock)
        assertEquals(1, updated.stock.byWarehouse.size)
        assertEquals(1, updated.counts.activeReservations)
        assertEquals(3, updated.counts.movements)

        // Цель — страховой запас 20; дефицит 20 − 6 = 14; кратность 5 → 15; не меньше партии 10.
        val suggestion = assertNotNull(updated.demandSuggestion)
        assertQty("20", suggestion.targetQty)
        assertQty("14", suggestion.deficitQty)
        assertQty("15", suggestion.suggestedQty)
        assertEquals(LocalDate.now().plusDays(7).toString(), suggestion.requiredDate)
        assertTrue(suggestion.openDemands.isEmpty())

        // Ручная потребность из карточки (D-63) не считается поставкой, но
        // показывается среди открытых, чтобы не создать дубль.
        val demand = keeper.post("/api/demands") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateDemandRequest(
                sourceType = DemandSourceType.MANUAL,
                idempotencyKey = "card-test-1",
                lines = listOf(DemandLineInput(1, item.id, suggestion.suggestedQty, "pcs"))
            )))
        }
        assertEquals(HttpStatusCode.Created, demand.status, demand.bodyAsText())
        val afterDemand = assertNotNull(getCard(keeper, item.id).demandSuggestion)
        assertQty("15", afterDemand.suggestedQty)
        assertEquals(1, afterDemand.openDemands.size)
        assertTrue(afterDemand.openDemands.single().endsWith("15 pcs"), afterDemand.openDemands.single())

        // Минимальная партия, не кратная заказу: сначала до партии 16, затем до кратности 5 → 20.
        val current = getCard(keeper, item.id)
        updateCard(keeper, item.id, baseUpdate(current).copy(minOrderQty = "16", orderMultiple = "5"))
        assertQty("20", assertNotNull(getCard(keeper, item.id).demandSuggestion).suggestedQty)

        // Остатки по строкам: карантин и брак не дают доступного количества.
        val quarantineRow = updated.stockRows.single { it.locationCode == "Q-01" }
        assertQty("0", quarantineRow.availableQty)
        assertQty("3", quarantineRow.physicalQty)
    }

    @Test
    fun `update card saves attributes warehouses category and product link with audit`() = testApp {
        val keeper = setupKeeper(extra = listOf(CreatePermissionRequest("bom", "create"), CreatePermissionRequest("bom", "read_any")))
        val warehouse = createWarehouse(keeper, "WH-1")
        val item = createNomenclature(keeper, "MAT-1")
        val other = createNomenclature(keeper, "MAT-2")
        val parent = createCategory(keeper, "EL", "Электроника", null)
        val category = createCategory(keeper, "EL-CON", "Разъёмы", parent.id)
        assertEquals("Электроника / Разъёмы", category.path)
        val model = createProductModel(keeper, "PM-1")

        val card = getCard(keeper, item.id)
        val result = updateCard(
            keeper, item.id,
            baseUpdate(card).copy(
                name = "Разъём РС-10",
                categoryId = category.id,
                article = "ART-77",
                manufacturer = "Завод",
                manufacturerModel = "РС-10ТВ",
                weightKg = "0,25",
                lengthMm = "40",
                widthMm = "20",
                heightMm = "10",
                storageConditions = "Сухое помещение",
                productModelId = model.id,
                attributes = listOf(
                    NomenclatureAttributeInput("Контактов", "10"),
                    NomenclatureAttributeInput("Ток", "5", "А"),
                    NomenclatureAttributeInput("", "")
                ),
                allowedWarehouseIds = listOf(warehouse.id)
            )
        )
        assertEquals("Разъём РС-10", result.item.name)
        assertEquals("Электроника / Разъёмы", result.category?.path)
        assertEquals("ART-77", result.item.article)
        assertQty("0.25", result.item.weightKg!!)
        assertEquals(listOf("Контактов", "Ток"), result.attributes.map { it.name })
        assertEquals("А", result.attributes[1].unit)
        assertEquals(listOf("WH-1"), result.allowedWarehouses.map { it.code })
        assertEquals(model.id, result.productModel?.id)
        assertEquals(card.item.version + 1, result.item.version)

        // Устаревшая версия — 409, данные не перезаписываются.
        val stale = keeper.put("/api/wms/nomenclature/${item.id}") {
            contentType(ContentType.Application.Json)
            setBody(stringify(baseUpdate(card).copy(name = "Чужая правка")))
        }
        assertEquals(HttpStatusCode.Conflict, stale.status, stale.bodyAsText())
        assertEquals("Разъём РС-10", getCard(keeper, item.id).item.name)

        // Одна модель изделия — одна позиция.
        val otherCard = getCard(keeper, other.id)
        val linkConflict = keeper.put("/api/wms/nomenclature/${other.id}") {
            contentType(ContentType.Application.Json)
            setBody(stringify(baseUpdate(otherCard).copy(productModelId = model.id)))
        }
        assertEquals(HttpStatusCode.Conflict, linkConflict.status, linkConflict.bodyAsText())

        // Устаревшая версия важнее остальных ошибок формы: клиент видит именно конфликт версии.
        val staleWithBadValue = keeper.put("/api/wms/nomenclature/${item.id}") {
            contentType(ContentType.Application.Json)
            setBody(stringify(baseUpdate(card).copy(article = "A".repeat(200))))
        }
        assertEquals(HttpStatusCode.Conflict, staleWithBadValue.status)
        assertTrue(staleWithBadValue.bodyAsText().contains("другой пользователь"), staleWithBadValue.bodyAsText())

        // Длина и точность проверяются сервисом (400), а не падают в БД (500).
        val tooLong = keeper.put("/api/wms/nomenclature/${other.id}") {
            contentType(ContentType.Application.Json)
            setBody(stringify(baseUpdate(otherCard).copy(article = "A".repeat(200))))
        }
        assertEquals(HttpStatusCode.BadRequest, tooLong.status, tooLong.bodyAsText())
        val tooBig = keeper.put("/api/wms/nomenclature/${other.id}") {
            contentType(ContentType.Application.Json)
            setBody(stringify(baseUpdate(otherCard).copy(weightKg = "1e30")))
        }
        assertEquals(HttpStatusCode.BadRequest, tooBig.status, tooBig.bodyAsText())

        // Повтор характеристики отклоняется.
        val duplicate = keeper.put("/api/wms/nomenclature/${other.id}") {
            contentType(ContentType.Application.Json)
            setBody(stringify(baseUpdate(otherCard).copy(attributes = listOf(
                NomenclatureAttributeInput("Цвет", "чёрный"),
                NomenclatureAttributeInput("цвет", "белый")
            ))))
        }
        assertEquals(HttpStatusCode.BadRequest, duplicate.status, duplicate.bodyAsText())

        val history = getList<NomenclatureHistoryEntryDto>(keeper, "/api/wms/nomenclature/${item.id}/history")
        val update = history.first { it.action == "UPDATE" }
        assertEquals("Карточка изменена", update.title)
        assertTrue(update.details!!.contains("характеристики"), update.details)
        assertTrue(update.details!!.contains("связь с изделием"), update.details)
        assertTrue(history.any { it.action == "CREATE" && it.title == "Позиция создана" })
    }

    @Test
    fun `categories reject cycles duplicates and archived parents`() = testApp {
        val keeper = setupKeeper()
        val root = createCategory(keeper, "A", "Корень", null)
        val child = createCategory(keeper, "A-1", "Потомок", root.id)

        val duplicate = keeper.post("/api/wms/nomenclature-categories") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateNomenclatureCategoryRequest("A", "Ещё")))
        }
        assertEquals(HttpStatusCode.Conflict, duplicate.status)

        val cycle = keeper.put("/api/wms/nomenclature-categories/${root.id}") {
            contentType(ContentType.Application.Json)
            setBody(stringify(UpdateNomenclatureCategoryRequest("Корень", parentId = child.id, version = root.version)))
        }
        assertEquals(HttpStatusCode.BadRequest, cycle.status, cycle.bodyAsText())

        val archive = keeper.put("/api/wms/nomenclature-categories/${child.id}") {
            contentType(ContentType.Application.Json)
            setBody(stringify(UpdateNomenclatureCategoryRequest("Потомок", parentId = root.id, archived = true, version = child.version)))
        }
        assertEquals(HttpStatusCode.OK, archive.status, archive.bodyAsText())

        val underArchived = keeper.post("/api/wms/nomenclature-categories") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateNomenclatureCategoryRequest("A-1-1", "Внук", child.id)))
        }
        assertEquals(HttpStatusCode.BadRequest, underArchived.status)

        val list = getList<NomenclatureCategoryDto>(keeper, "/api/wms/nomenclature-categories")
        assertEquals(listOf("Корень", "Корень / Потомок"), list.map { it.path })
    }

    @Test
    fun `usage lists blockers and archive explains them`() = testApp {
        val keeper = setupKeeper()
        val warehouse = createWarehouse(keeper, "WH-1")
        val storage = createLocation(keeper, warehouse.id, "A-01", StorageLocationPurpose.STORAGE)
        val item = createNomenclature(keeper, "MAT-1")
        postInitialStock(keeper, warehouse.id, storage.id, item.id, "5")
        reserve(keeper, warehouse.id, storage.id, item.id, "1")

        val usage = getOne<NomenclatureUsageDto>(keeper, "/api/wms/nomenclature/${item.id}/usage")
        assertEquals(listOf("есть остаток на складе", "есть активные резервы"), usage.archiveBlockers)

        val archive = keeper.post("/api/wms/nomenclature/${item.id}/archive")
        assertEquals(HttpStatusCode.Conflict, archive.status)
        assertTrue(archive.bodyAsText().contains("есть остаток на складе"), archive.bodyAsText())

        val free = createNomenclature(keeper, "MAT-FREE")
        val freeUsage = getOne<NomenclatureUsageDto>(keeper, "/api/wms/nomenclature/${free.id}/usage")
        assertTrue(freeUsage.archiveBlockers.isEmpty())
        assertEquals(HttpStatusCode.OK, keeper.post("/api/wms/nomenclature/${free.id}/archive").status)
        // Архивная позиция не предлагает потребность и не изменяется через карточку.
        val archived = getCard(keeper, free.id)
        assertNull(archived.demandSuggestion)
        val editArchived = keeper.put("/api/wms/nomenclature/${free.id}") {
            contentType(ContentType.Application.Json)
            setBody(stringify(baseUpdate(archived).copy(name = "Правка архива")))
        }
        assertEquals(HttpStatusCode.Conflict, editArchived.status, editArchived.bodyAsText())
    }

    @Test
    fun `movements are paginated filtered and feed stock history`() = testApp {
        val keeper = setupKeeper()
        val warehouse = createWarehouse(keeper, "WH-1")
        val storage = createLocation(keeper, warehouse.id, "A-01", StorageLocationPurpose.STORAGE)
        val item = createNomenclature(keeper, "MAT-1")
        repeat(3) { postInitialStock(keeper, warehouse.id, storage.id, item.id, "2") }

        val first = getOne<NomenclatureMovementsPageDto>(keeper, "/api/wms/nomenclature/${item.id}/movements?limit=2")
        assertEquals(2, first.items.size)
        val before = assertNotNull(first.nextBefore)
        val second = getOne<NomenclatureMovementsPageDto>(keeper, "/api/wms/nomenclature/${item.id}/movements?limit=2&before=$before")
        assertEquals(1, second.items.size)
        assertNull(second.nextBefore)
        assertTrue(second.items.single().id < first.items.last().id)

        val issues = getOne<NomenclatureMovementsPageDto>(keeper, "/api/wms/nomenclature/${item.id}/movements?type=ISSUE")
        assertTrue(issues.items.isEmpty())
        val badType = keeper.get("/api/wms/nomenclature/${item.id}/movements?type=NOPE")
        assertEquals(HttpStatusCode.BadRequest, badType.status)
        val badDate = keeper.get("/api/wms/nomenclature/${item.id}/movements?from=31.12.2026")
        assertEquals(HttpStatusCode.BadRequest, badDate.status)

        val history = getList<NomenclatureStockPointDto>(keeper, "/api/wms/nomenclature/${item.id}/stock-history?days=30")
        assertEquals(30, history.size)
        assertEquals(LocalDate.now().toString(), history.last().date)
        assertQty("6", history.last().physicalQty)
        assertQty("0", history.first().physicalQty)
    }

    @Test
    fun `analogs keep approval flag reject duplicates and can be removed`() = testApp {
        val keeper = setupKeeper()
        val item = createNomenclature(keeper, "MAT-1")
        val analog = createNomenclature(keeper, "MAT-2")

        val created = keeper.post("/api/wms/nomenclature/${item.id}/analogs") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateNomenclatureAnalogRequest(analog.id, "2", "Только для ремонта", requiresApproval = true)))
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val analogs = created.bodyAs<ApiResponse<List<NomenclatureAnalogDto>>>().data!!
        assertTrue(analogs.single().requiresApproval)
        assertEquals("Только для ремонта", analogs.single().applicabilityCondition)

        val duplicate = keeper.post("/api/wms/nomenclature/${item.id}/analogs") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateNomenclatureAnalogRequest(analog.id)))
        }
        assertEquals(HttpStatusCode.Conflict, duplicate.status)

        val analogCard = getCard(keeper, analog.id)
        assertEquals(listOf("MAT-1"), analogCard.analogOf.map { it.analogCode })

        val deleted = keeper.delete("/api/wms/nomenclature/${item.id}/analogs/${analogs.single().id}")
        assertEquals(HttpStatusCode.OK, deleted.status)
        assertTrue(getCard(keeper, item.id).analogs.isEmpty())

        val conv = keeper.post("/api/wms/nomenclature/${item.id}/unit-conversions") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateUnitConversionRequest("box", "pcs", "12")))
        }
        assertEquals(HttpStatusCode.Created, conv.status, conv.bodyAsText())
        val convDuplicate = keeper.post("/api/wms/nomenclature/${item.id}/unit-conversions") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateUnitConversionRequest("box", "pcs", "10")))
        }
        assertEquals(HttpStatusCode.Conflict, convDuplicate.status)
        val convId = conv.bodyAs<ApiResponse<NomenclatureUnitConversionDto>>().data!!.id
        assertEquals(HttpStatusCode.OK, keeper.delete("/api/wms/nomenclature/${item.id}/unit-conversions/$convId").status)

        val history = getList<NomenclatureHistoryEntryDto>(keeper, "/api/wms/nomenclature/${item.id}/history")
        assertTrue(history.any { it.title == "Добавлен аналог" && it.details == "MAT-2 · требует согласования" })
        assertTrue(history.any { it.title == "Добавлен пересчёт единиц" && it.details == "1 box = 12 pcs" })
    }

    @Test
    fun `reader sees every tab but cannot change the card`() = testApp {
        val keeper = setupKeeper()
        val admin = jsonClient()
        createUserWithRole(admin, "reader", "password", "Reader", listOf(CreatePermissionRequest("wms", "read")))
        createUserWithRole(admin, "nobody", "password", "Nobody", emptyList())
        val reader = loginClient("reader", "password")
        val nobody = loginClient("nobody", "password")
        val item = createNomenclature(keeper, "MAT-1")
        val card = getCard(keeper, item.id)

        listOf("card", "usage", "history", "reservations", "movements", "stock-history", "documents", "attachments").forEach { tab ->
            val response = reader.get("/api/wms/nomenclature/${item.id}/$tab")
            assertEquals(HttpStatusCode.OK, response.status, "$tab: ${response.bodyAsText()}")
            assertEquals(HttpStatusCode.Forbidden, nobody.get("/api/wms/nomenclature/${item.id}/$tab").status, tab)
        }
        assertEquals(HttpStatusCode.NotFound, reader.get("/api/wms/nomenclature/999999/card").status)
        assertEquals(HttpStatusCode.NotFound, reader.get("/api/wms/nomenclature/999999/documents").status)

        val denied = reader.put("/api/wms/nomenclature/${item.id}") {
            contentType(ContentType.Application.Json)
            setBody(stringify(baseUpdate(card).copy(name = "Нельзя")))
        }
        assertEquals(HttpStatusCode.Forbidden, denied.status)
        val uploadDenied = reader.submitFormWithBinaryData(
            url = "/api/wms/nomenclature/${item.id}/attachments",
            formData = formData {
                append("file", "x".toByteArray(), Headers.build {
                    append(HttpHeaders.ContentType, "text/plain")
                    append(HttpHeaders.ContentDisposition, "filename=\"a.txt\"")
                })
            }
        )
        assertEquals(HttpStatusCode.Forbidden, uploadDenied.status)
    }

    @Test
    fun `keeper uploads a photo and it is counted on the card`() = testApp {
        val keeper = setupKeeper()
        val item = createNomenclature(keeper, "MAT-1")
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val upload = keeper.submitFormWithBinaryData(
            url = "/api/wms/nomenclature/${item.id}/attachments",
            formData = formData {
                append("file", png, Headers.build {
                    append(HttpHeaders.ContentType, "image/png")
                    append(HttpHeaders.ContentDisposition, "filename=\"photo.png\"")
                })
            }
        )
        assertEquals(HttpStatusCode.Created, upload.status, upload.bodyAsText())
        val attachments = getList<AttachmentDto>(keeper, "/api/wms/nomenclature/${item.id}/attachments")
        assertEquals(listOf("photo.png"), attachments.map { it.fileName })
        assertEquals(AttachmentEntityType.NOMENCLATURE, attachments.single().entityType)
        assertEquals(1, getCard(keeper, item.id).counts.attachments)
    }

    // --- Вспомогательное ---

    private fun baseUpdate(card: NomenclatureCardDto) = UpdateNomenclatureRequest(
        version = card.item.version,
        name = card.item.name,
        description = card.item.description,
        categoryId = card.item.categoryId,
        article = card.item.article,
        manufacturer = card.item.manufacturer,
        manufacturerModel = card.item.manufacturerModel,
        weightKg = card.item.weightKg,
        lengthMm = card.item.lengthMm,
        widthMm = card.item.widthMm,
        heightMm = card.item.heightMm,
        storageConditions = card.item.storageConditions,
        productModelId = card.item.productModelId,
        minStock = card.item.minStock,
        safetyStock = card.item.safetyStock,
        leadTimeDays = card.item.leadTimeDays,
        minOrderQty = card.item.minOrderQty,
        orderMultiple = card.item.orderMultiple,
        attributes = card.attributes.map { NomenclatureAttributeInput(it.name, it.value, it.unit) },
        allowedWarehouseIds = card.allowedWarehouses.map { it.id }
    )

    private suspend fun ApplicationTestBuilder.setupKeeper(extra: List<CreatePermissionRequest> = emptyList()): HttpClient {
        val admin = jsonClient()
        createUserWithRole(
            admin, "keeper", "password", "Keeper User",
            listOf(
                CreatePermissionRequest("wms", "manage"),
                CreatePermissionRequest("wms", "post"),
                CreatePermissionRequest("wms", "read")
            ) + extra
        )
        return loginClient("keeper", "password")
    }

    private suspend fun getCard(client: HttpClient, id: Long): NomenclatureCardDto =
        getOne(client, "/api/wms/nomenclature/$id/card")

    private suspend fun updateCard(client: HttpClient, id: Long, request: UpdateNomenclatureRequest): NomenclatureCardDto {
        val response = client.put("/api/wms/nomenclature/$id") {
            contentType(ContentType.Application.Json)
            setBody(stringify(request))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<NomenclatureCardDto>>().data!!
    }

    private suspend inline fun <reified T> getOne(client: HttpClient, url: String): T {
        val response = client.get(url)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<T>>().data!!
    }

    private suspend inline fun <reified T> getList(client: HttpClient, url: String): List<T> = getOne(client, url)

    private suspend fun createCategory(client: HttpClient, code: String, name: String, parentId: Long?): NomenclatureCategoryDto {
        val response = client.post("/api/wms/nomenclature-categories") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateNomenclatureCategoryRequest(code, name, parentId)))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<NomenclatureCategoryDto>>().data!!
    }

    private suspend fun createProductModel(client: HttpClient, code: String): ProductModelDto {
        val response = client.post("/api/bom/models") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateProductModelRequest(code, "Model $code")))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<ProductModelDto>>().data!!
    }

    private suspend fun ApplicationTestBuilder.createUserWithRole(
        adminClient: HttpClient,
        login: String,
        password: String,
        fullName: String,
        permissions: List<CreatePermissionRequest>
    ): UserDto {
        val bootstrapResponse = adminClient.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin", "admin123", "Admin User")))
        }
        if (bootstrapResponse.status != HttpStatusCode.Created && bootstrapResponse.status != HttpStatusCode.Unauthorized) {
            assertEquals(HttpStatusCode.Created, bootstrapResponse.status, bootstrapResponse.bodyAsText())
        }
        val loginResponse = adminClient.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("admin", "admin123")))
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status, loginResponse.bodyAsText())
        val roleResponse = adminClient.post("/api/roles") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateRoleRequest(login.uppercase(), fullName, permissions = permissions)))
        }
        assertEquals(HttpStatusCode.Created, roleResponse.status, roleResponse.bodyAsText())
        val role = roleResponse.bodyAs<ApiResponse<RoleDto>>().data!!
        val userResponse = adminClient.post("/api/users") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateUserRequest(login, password, fullName, roleIds = listOf(role.id))))
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
        assertEquals(HttpStatusCode.OK, response.status)
        return client
    }

    private suspend fun createWarehouse(client: HttpClient, code: String): WarehouseDto {
        val response = client.post("/api/wms/warehouses") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateWarehouseRequest(code, "Warehouse $code")))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<WarehouseDto>>().data!!
    }

    private suspend fun createLocation(
        client: HttpClient,
        warehouseId: Long,
        code: String,
        purpose: StorageLocationPurpose
    ): StorageLocationDto {
        val response = client.post("/api/wms/locations") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateStorageLocationRequest(
                warehouseId = warehouseId,
                code = code,
                name = "Location $code",
                kind = StorageLocationKind.CELL,
                purpose = purpose
            )))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<StorageLocationDto>>().data!!
    }

    private suspend fun createNomenclature(client: HttpClient, code: String): NomenclatureDto {
        val response = client.post("/api/wms/nomenclature") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateNomenclatureRequest(code = code, name = "Nomenclature $code", type = NomenclatureType.MATERIAL, unit = "pcs")))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<NomenclatureDto>>().data!!
    }

    private suspend fun postInitialStock(client: HttpClient, warehouseId: Long, locationId: Long, nomenclatureId: Long, quantity: String) {
        val create = client.post("/api/wms/receipts") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateReceiptRequest(
                sourceType = ReceiptSourceType.INITIAL_STOCK,
                warehouseId = warehouseId,
                lines = listOf(ReceiptLineInput(lineNo = 1, nomenclatureId = nomenclatureId, expectedQuantity = quantity, locationId = locationId))
            )))
        }
        assertEquals(HttpStatusCode.Created, create.status, create.bodyAsText())
        val receipt = create.bodyAs<ApiResponse<ReceiptDto>>().data!!
        val post = client.post("/api/wms/receipts/${receipt.id}/post") {
            contentType(ContentType.Application.Json)
            setBody(stringify(PostReceiptRequest(receipt.version)))
        }
        assertEquals(HttpStatusCode.OK, post.status, post.bodyAsText())
    }

    private suspend fun reserve(client: HttpClient, warehouseId: Long, locationId: Long, nomenclatureId: Long, quantity: String) {
        val response = client.post("/api/wms/reservations") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateReservationRequest(
                reasonType = ReservationReasonType.OTHER,
                reasonNote = "Test",
                nomenclatureId = nomenclatureId,
                warehouseId = warehouseId,
                locationId = locationId,
                quantity = quantity
            )))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    }

    private fun assertQty(expected: String, actual: String) {
        assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(actual)), "expected $expected but was $actual")
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
                        """.trimIndent()
                    )
                )
            }
            block()
        }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(HttpCookies) }

    private suspend inline fun <reified T> HttpResponse.bodyAs(): T = json.decodeFromString(serializer<T>(), bodyAsText())

    private inline fun <reified T> stringify(value: T): String = json.encodeToString(serializer<T>(), value)

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
