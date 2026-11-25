package org.example.project.smoke

import io.ktor.client.request.get
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import org.example.project.TestDatabase
import org.example.project.dto.*
import org.example.project.route.SetActiveRequest
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Smoke: реальные сценарии работы предприятия «Авиаагрегат-Н» через несколько вкладок.
 *
 * Каждый тест — рабочий день конкретных сотрудников с их собственными правами:
 * действие на одной вкладке обязано отразиться на других (остатки, счётчики,
 * уведомления, поиск, аудит). Сценарии используют только HTTP API, как веб-клиент.
 */
class SmokeRealScenariosTest {

    // ------------------------------------------------------------------
    // Сценарий 1. Снабжение → Поставщики → Заказы → Склад → Поиск → Аудит
    // ------------------------------------------------------------------
    @Test
    fun `procurement cycle from demand to stock is visible on every related tab`() = smokeApp {
        val c = company()
        val storekeeper = c.hire("sklad", "Кладовщик Иванов", perms(
            "wms:read", "wms:post", "wms:manage", "suppliers:read", "suppliers:receive", "demands:read_any"
        ))
        val engineer = c.hire("engineer", "Инженер Петров", perms(
            "demands:create", "demands:read_any", "demands:update", "suppliers:read", "suppliers:select", "wms:read"
        ))
        val buyer = c.hire("buyer", "Снабженец Сидорова", perms(
            "suppliers:read", "suppliers:create", "suppliers:update", "suppliers:offer", "suppliers:order",
            "demands:read_any", "wms:read", "users:read_any"
        ))
        val chief = c.hire("chief", "Нач. снабжения Орлов", perms(
            "suppliers:read", "suppliers:approve_order", "demands:read_any", "demands:approve", "wms:read"
        ))

        // Склад: склад, ячейка, номенклатура
        val wh = storekeeper.client.postOk<CreateWarehouseRequest, WarehouseDto>(
            "/api/wms/warehouses", CreateWarehouseRequest("WH-MAIN", "Основной склад"), HttpStatusCode.Created
        )
        val cell = storekeeper.client.postOk<CreateStorageLocationRequest, StorageLocationDto>(
            "/api/wms/locations",
            CreateStorageLocationRequest(wh.id, code = "A-01-01", name = "Стеллаж A, полка 1", kind = StorageLocationKind.CELL),
            HttpStatusCode.Created
        )
        val bolt = storekeeper.client.postOk<CreateNomenclatureRequest, NomenclatureDto>(
            "/api/wms/nomenclature",
            CreateNomenclatureRequest(code = "BOLT-M6x20", name = "Болт М6х20 оцинк.", type = NomenclatureType.PURCHASED_COMPONENT, unit = "шт"),
            HttpStatusCode.Created
        )

        // Снабжение: инженер создаёт потребность — по D-64 она сразу в статусе APPROVED
        val demand = engineer.client.postOk<CreateDemandRequest, DemandDetailDto>(
            "/api/demands",
            CreateDemandRequest(
                sourceType = DemandSourceType.MANUAL,
                requiredDate = LocalDate.now().plusDays(21).toString(),
                stockpileNote = "Пополнение склада крепежа",
                lines = listOf(DemandLineInput(1, bolt.id, "100", "шт"))
            ),
            HttpStatusCode.Created
        )
        assertEquals(DemandStatus.APPROVED, demand.header.status)

        // Поставщики: карточка, проверка, предложение (с обязательным счётом), выбор инициатором
        val supplier = buyer.client.postOk<CreateSupplierRequest, SupplierCardDto>(
            "/api/suppliers",
            CreateSupplierRequest(name = "ООО «КрепёжСнаб»", inn = "7701555123", idempotencyKey = newKey("sup")),
            HttpStatusCode.Created
        )
        val verified = c.admin.client.postJson(
            "/api/suppliers/${supplier.supplier.id}/verification",
            encode(SupplierVerificationRequest(SupplierVerificationStatus.VERIFIED, "Проверен СБ", supplier.supplier.version, newKey("ver")))
        )
        assertEquals(HttpStatusCode.OK, verified.status, verified.bodyAsText())
        val invoiceDoc = buyer.client.postOk<CreateDocumentRequest, DocumentDto>(
            "/api/documents", CreateDocumentRequest("Счёт КрепёжСнаб", DocumentType.INVOICE), HttpStatusCode.Created
        )
        val verResp = buyer.client.submitFormWithBinaryData(
            url = "/api/documents/${invoiceDoc.id}/versions",
            formData = formData {
                append("file", "%PDF-1.4 krepezh".toByteArray(Charsets.UTF_8), Headers.build {
                    append(HttpHeaders.ContentType, "application/pdf")
                    append(HttpHeaders.ContentDisposition, "filename=\"krepezh.pdf\"")
                })
            }
        )
        assertEquals(HttpStatusCode.Created, verResp.status, verResp.bodyAsText())
        val offer = buyer.client.postOk<CreateSupplierOfferRequest, SupplierOfferDto>(
            "/api/supplier-offers",
            CreateSupplierOfferRequest(
                demandId = demand.header.id,
                supplierId = supplier.supplier.id,
                compliance = SupplierOfferCompliance.FULL,
                documentsComplete = true,
                deliveryDays = 5,
                paymentTerms = "Постоплата 30 дней",
                validUntil = LocalDate.now().plusDays(30).toString(),
                lines = listOf(SupplierOfferLineInput(demand.lines.first().id, "100", "12.50", "20")),
                idempotencyKey = newKey("offer"),
                invoiceDocumentId = invoiceDoc.id
            ),
            HttpStatusCode.Created
        )
        val offerFull = buyer.client.getOk<SupplierOfferDto>("/api/supplier-offers/${offer.id}")
        engineer.client.postOk<SelectSupplierOfferRequest, SupplierOfferDto>(
            "/api/supplier-offers/${offer.id}/select",
            SelectSupplierOfferRequest("Лучшая цена и срок", offerFull.version, newKey("select"))
        )

        // Заказы поставщикам: создание → согласование → отправка
        val po = buyer.client.postOk<CreatePurchaseOrderRequest, PurchaseOrderDto>(
            "/api/purchase-orders",
            CreatePurchaseOrderRequest(
                offerId = offer.id,
                expectedAt = LocalDate.now().plusDays(7).toString(),
                paymentTerms = "Постоплата 30 дней",
                deliveryAddress = "Склад WH-MAIN",
                lines = listOf(CreatePurchaseOrderLineInput(offerFull.lines.first().id, "100")),
                idempotencyKey = newKey("po")
            ),
            HttpStatusCode.Created
        )
        val poSubmitted = buyer.client.postOk<PurchaseOrderActionRequest, PurchaseOrderDto>(
            "/api/purchase-orders/${po.id}/submit", PurchaseOrderActionRequest(po.version, idempotencyKey = newKey())
        )
        // Снабженец не может согласовать собственный заказ
        val buyerApprove = buyer.client.postJson(
            "/api/purchase-orders/${po.id}/approve", encode(PurchaseOrderActionRequest(poSubmitted.version, idempotencyKey = newKey()))
        )
        assertEquals(HttpStatusCode.Forbidden, buyerApprove.status, buyerApprove.bodyAsText())
        val poApproved = chief.client.postOk<PurchaseOrderActionRequest, PurchaseOrderDto>(
            "/api/purchase-orders/${po.id}/approve", PurchaseOrderActionRequest(poSubmitted.version, idempotencyKey = newKey())
        )
        val poSent = buyer.client.postOk<PurchaseOrderActionRequest, PurchaseOrderDto>(
            "/api/purchase-orders/${po.id}/send", PurchaseOrderActionRequest(poApproved.version, idempotencyKey = newKey())
        )

        // Склад: поставщик привёз 60 из 100 по УПД
        val receipt = storekeeper.client.postOk<CreateReceiptRequest, ReceiptDto>(
            "/api/wms/receipts",
            CreateReceiptRequest(
                sourceType = ReceiptSourceType.DELIVERY_NOTE,
                sourceDocumentNo = "УПД-1187",
                purchaseOrderId = po.id,
                warehouseId = wh.id,
                lines = listOf(
                    ReceiptLineInput(
                        lineNo = 1, nomenclatureId = bolt.id, expectedQuantity = "100", receivedQuantity = "60",
                        locationId = cell.id, purchaseOrderLineId = poSent.lines.first().id,
                        discrepancyReason = "Недопоставка, остаток во второй партии"
                    )
                )
            ),
            HttpStatusCode.Created
        )
        assertEquals(BigDecimal.ZERO, stockQty(storekeeper, wh.id, bolt.id), "До проведения остаток не меняется")
        storekeeper.client.postOk<PostReceiptRequest, ReceiptDto>("/api/wms/receipts/${receipt.id}/post", PostReceiptRequest(receipt.version))
        assertEquals(BigDecimal("60"), stockQty(storekeeper, wh.id, bolt.id))

        // Ожидаемые поставки показывают недопоставку 40
        val expected = storekeeper.client.getOk<List<ExpectedReceiptLineDto>>("/api/wms/expected-receipts?purchaseOrderId=${po.id}")
        assertEquals(0, BigDecimal(expected.first().notAcceptedQuantity).compareTo(BigDecimal("40")))

        // Движения склада содержат приход
        val movements = storekeeper.client.get("/api/wms/movements?nomenclatureId=${bolt.id}")
        assertEquals(HttpStatusCode.OK, movements.status)
        assertTrue(movements.bodyAsText().contains(bolt.id.toString()), "Движение прихода видно во вкладке «Склад → Движения»")

        // Глобальный поиск находит заказ по номеру у того, кто имеет права
        // Номенклатура находится по коду
        val byCode = storekeeper.client.getOk<SearchResponse>("/api/search?q=BOLT-M6")
        assertTrue(byCode.groups.any { g -> g.type == SearchResultType.NOMENCLATURE && g.items.isNotEmpty() }, "Поиск не нашёл номенклатуру по коду")
        // AUDIT-12: глобальный поиск не охватывает заказы, потребности и поставщиков
        val search = buyer.client.getOk<SearchResponse>("/api/search?q=${po.number}")
        report("AUDIT-12 глобальный поиск находит заказ поставщику по номеру", search.groups.any { it.items.isNotEmpty() }, "групп с результатами: ${search.groups.count { it.items.isNotEmpty() }}")

        // Пользователь без складских прав не получает номенклатуру через поиск
        val noBuyer = c.hire("guest", "Гость", emptyList())
        val guestSearch = noBuyer.client.getOk<SearchResponse>("/api/search?q=BOLT-M6")
        report("Поиск скрывает номенклатуру от пользователя без wms:read", guestSearch.groups.all { it.items.isEmpty() }, "групп: ${guestSearch.groups.map { it.type }}")

        // Аудит фиксирует ключевые операции
        val audit = c.admin.client.get("/api/audit?limit=200").bodyAsText()
        assertTrue(audit.contains("RECEIPT", ignoreCase = true), "Аудит не содержит проведения приёмки")
    }

    // ------------------------------------------------------------------
    // Сценарий 2. Смена кладовщика: резерв → выдача → возврат → остатки
    // ------------------------------------------------------------------
    @Test
    fun `storekeeper shift reservation issue and return keep balances consistent`() = smokeApp {
        val c = company()
        val keeper = c.hire("keeper", "Кладовщик Смирнов", perms("wms:read", "wms:post", "wms:manage", "wms:issue_without_reservation", "wms:inspect_returns"))
        val viewer = c.hire("viewer", "Мастер цеха (только просмотр)", perms("wms:read"))

        val wh = keeper.client.postOk<CreateWarehouseRequest, WarehouseDto>("/api/wms/warehouses", CreateWarehouseRequest("WH-2", "Цеховой склад"), HttpStatusCode.Created)
        val cell = keeper.client.postOk<CreateStorageLocationRequest, StorageLocationDto>(
            "/api/wms/locations", CreateStorageLocationRequest(wh.id, code = "B-01", name = "B-01", kind = StorageLocationKind.CELL), HttpStatusCode.Created
        )
        val quarantine = keeper.client.postOk<CreateStorageLocationRequest, StorageLocationDto>(
            "/api/wms/locations",
            CreateStorageLocationRequest(wh.id, code = "Q-01", name = "Карантин", kind = StorageLocationKind.CELL, purpose = StorageLocationPurpose.QUARANTINE),
            HttpStatusCode.Created
        )
        val wire = keeper.client.postOk<CreateNomenclatureRequest, NomenclatureDto>(
            "/api/wms/nomenclature", CreateNomenclatureRequest(code = "WIRE-MGTF", name = "Провод МГТФ 0.35", type = NomenclatureType.MATERIAL, unit = "м"), HttpStatusCode.Created
        )
        initialStock(keeper, wh.id, cell.id, wire.id, "500")
        assertEquals(BigDecimal("500"), stockQty(keeper, wh.id, wire.id))

        // Резерв 120 м под сборку
        val reservation = keeper.client.postOk<CreateReservationRequest, ReservationDto>(
            "/api/wms/reservations",
            CreateReservationRequest(reasonType = ReservationReasonType.OTHER, reasonNote = "Сборка жгута", nomenclatureId = wire.id, warehouseId = wh.id, locationId = cell.id, quantity = "120"),
            HttpStatusCode.Created
        )
        val afterReserve = stock(keeper, wh.id, wire.id)
        assertEquals(0, BigDecimal(afterReserve.reservedQty).compareTo(BigDecimal("120")))
        assertEquals(0, BigDecimal(afterReserve.availableQty).compareTo(BigDecimal("380")))

        // Мастер не может провести выдачу
        val masterIssue = viewer.client.postJson("/api/wms/issues", encode(issueRequest(wh.id, wire.id, "10", null)))
        assertEquals(HttpStatusCode.Forbidden, masterIssue.status)

        // Выдача по резерву: create → pick → issue → confirm
        var issue = keeper.client.postOk<CreateIssueOrderRequest, IssueOrderDto>("/api/wms/issues", issueRequest(wh.id, wire.id, "120", reservation.id), HttpStatusCode.Created)
        issue = keeper.client.postOk<PickIssueRequest, IssueOrderDto>(
            "/api/wms/issues/${issue.id}/picks",
            PickIssueRequest(issue.version, issue.lines.map { IssuePickInput(lineId = it.id, locationId = cell.id, quantity = it.requestedQuantity) }, idempotencyKey = newKey())
        )
        issue = keeper.client.postOk<IssueActionRequest, IssueOrderDto>("/api/wms/issues/${issue.id}/issue", IssueActionRequest(issue.version, newKey()))
        val confirmKey = newKey()
        issue = keeper.client.postOk<IssueActionRequest, IssueOrderDto>("/api/wms/issues/${issue.id}/confirm", IssueActionRequest(issue.version, confirmKey))
        // Повторное нажатие «Подтвердить» (двойной клик) не списывает второй раз
        keeper.client.postJson("/api/wms/issues/${issue.id}/confirm", encode(IssueActionRequest(issue.version - 1, confirmKey)))
        assertEquals(BigDecimal("380"), stockQty(keeper, wh.id, wire.id))
        val res = keeper.client.getOk<ReservationDto>("/api/wms/reservations/${reservation.id}")
        assertEquals(ReservationStatus.FULFILLED, res.status)

        // Возврат 20 м в карантин: остаток растёт, но доступным не становится
        val ret = keeper.client.postOk<CreateReturnOrderRequest, ReturnOrderDto>(
            "/api/wms/returns",
            CreateReturnOrderRequest(issueOrderId = issue.id, quarantineLocationId = quarantine.id, reason = "Остаток после сборки", lines = listOf(ReturnLineInput(issue.lines.first().id, "20")), idempotencyKey = newKey()),
            HttpStatusCode.Created
        )
        keeper.client.postOk<ReturnActionRequest, ReturnOrderDto>("/api/wms/returns/${ret.id}/receive", ReturnActionRequest(ret.version, newKey()))
        val balances = keeper.client.getOk<List<StockBalanceDto>>("/api/wms/stock?warehouseId=${wh.id}&nomenclatureId=${wire.id}")
        val physical = balances.fold(BigDecimal.ZERO) { a, b -> a + BigDecimal(b.physicalQty) }
        val available = balances.fold(BigDecimal.ZERO) { a, b -> a + BigDecimal(b.availableQty) }
        assertEquals(0, physical.compareTo(BigDecimal("400")))
        // AUDIT-11: строка карантина отдаёт availableQty>0, хотя резерв из карантина сервер запрещает
        report("AUDIT-11 карантин не входит в доступный остаток", available.compareTo(BigDecimal("380")) == 0, "available=$available")
        val reserveQuarantine = keeper.client.postJson(
            "/api/wms/reservations",
            encode(CreateReservationRequest(reasonType = ReservationReasonType.OTHER, nomenclatureId = wire.id, warehouseId = wh.id, locationId = quarantine.id, quantity = "1"))
        )
        assertEquals(HttpStatusCode.Conflict, reserveQuarantine.status, "Резерв из карантина должен быть запрещён")

        // Возврат больше выданного невозможен
        val over = keeper.client.postJson(
            "/api/wms/returns",
            encode(CreateReturnOrderRequest(issueOrderId = issue.id, quarantineLocationId = quarantine.id, reason = "Ошибка", lines = listOf(ReturnLineInput(issue.lines.first().id, "500")), idempotencyKey = newKey()))
        )
        assertTrue(over.status.value in 400..499, "Возврат 500 м при выданных 120: ${over.status}")

        // Просмотрщик видит остатки и движения, но не может создать резерв
        assertEquals(HttpStatusCode.OK, viewer.client.get("/api/wms/movements?nomenclatureId=${wire.id}").status)
        val viewerReserve = viewer.client.postJson(
            "/api/wms/reservations",
            encode(CreateReservationRequest(reasonType = ReservationReasonType.OTHER, nomenclatureId = wire.id, warehouseId = wh.id, locationId = cell.id, quantity = "1"))
        )
        assertEquals(HttpStatusCode.Forbidden, viewerReserve.status)
    }

    // ------------------------------------------------------------------
    // Сценарий 3. Проект → Задача → Чат задачи → Уведомления → Сводка проекта
    // ------------------------------------------------------------------
    @Test
    fun `project task with chat notifications and summary for members only`() = smokeApp {
        val c = company()
        val pm = c.hire("pm", "Руководитель проекта Волков", perms("projects:create", "tasks:create", "tasks:assign_any"))
        val worker = c.hire("worker", "Конструктор Лебедев", emptyList())
        val qa = c.hire("qa", "Контролёр ОТК Зайцева", emptyList())
        val outsider = c.hire("outsider", "Сотрудник другого отдела", emptyList())

        val project = pm.client.postOk<CreateProjectRequest, ProjectDto>(
            "/api/projects",
            CreateProjectRequest(name = "Агрегат АН-24 модернизация", projectType = ProjectType.ORDER_DEVELOPMENT, ownerId = pm.user.id,
                plannedStartAt = "2026-11-01T00:00:00Z", plannedEndAt = "2027-06-30T00:00:00Z"),
            HttpStatusCode.Created
        )
        for (member in listOf(worker, qa)) {
            val r = pm.client.postJson("/api/projects/${project.id}/members", encode(AddProjectMemberRequest(member.user.id)))
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        }

        val task = pm.client.postOk<CreateTaskRequest, TaskDto>(
            "/api/tasks",
            CreateTaskRequest(
                title = "Выпустить КД на кронштейн", description = "Комплект КД по ЕСКД", area = TaskArea.PROJECT,
                type = TaskWorkType.REVIEWED, projectId = project.id, assigneeId = worker.user.id, reviewerId = qa.user.id,
                acceptanceCriteria = "Чертёж согласован ОТК", dueDate = "2026-12-20T00:00:00Z"
            ),
            HttpStatusCode.Created
        )

        // Исполнитель получил уведомление о назначении
        val notifications = worker.client.getOk<NotificationPageDto>("/api/notifications").notifications
        // AUDIT-10: уведомления создаются только для сообщений чата, но не для назначения задачи
        report("AUDIT-10 исполнитель получает уведомление о назначении", notifications.isNotEmpty(), "уведомлений: ${notifications.size}")

        // Исполнитель берёт задачу и пишет в чат задачи
        var t = worker.client.postOk<TaskCommandRequest, TaskDto>("/api/tasks/${task.id}/accept", TaskCommandRequest(task.version))
        t = worker.client.postOk<TaskCommandRequest, TaskDto>("/api/tasks/${task.id}/start", TaskCommandRequest(t.version))
        val msg = worker.client.postOk<SendChatMessageRequest, ChatMessageDto>(
            "/api/tasks/${task.id}/chat/messages", SendChatMessageRequest("Прошу уточнить материал кронштейна", newKey("msg")), HttpStatusCode.Created
        )
        assertEquals("Прошу уточнить материал кронштейна", msg.content)

        // Посторонний не видит ни задачу, ни её чат
        val foreignTask = outsider.client.get("/api/tasks/${task.id}")
        assertTrue(foreignTask.status == HttpStatusCode.Forbidden || foreignTask.status == HttpStatusCode.NotFound, "Чужая задача: ${foreignTask.status}")
        val foreignChat = outsider.client.get("/api/tasks/${task.id}/chat/messages")
        assertTrue(foreignChat.status.value in setOf(403, 404), "Чужой чат задачи: ${foreignChat.status}")
        val foreignWrite = outsider.client.postJson("/api/tasks/${task.id}/chat/messages", encode(SendChatMessageRequest("спам", newKey())))
        assertTrue(foreignWrite.status.value in setOf(403, 404), "Запись в чужой чат: ${foreignWrite.status}")

        // Результат → проверка ОТК → возврат на доработку → повторная сдача → приёмка
        t = worker.client.postOk<SubmitResultRequest, TaskDto>("/api/tasks/${task.id}/result", SubmitResultRequest("КД выпущена, рев. A", t.version))
        t = worker.client.postOk<TaskCommandRequest, TaskDto>("/api/tasks/${task.id}/send-to-review", TaskCommandRequest(t.version))
        // Исполнитель не может сам себе принять работу
        val selfReview = worker.client.postJson("/api/tasks/${task.id}/review", encode(ReviewTaskRequest(ReviewDecision.ACCEPT, null, t.version)))
        assertTrue(selfReview.status.value in 400..499, "Самоприёмка: ${selfReview.status}")
        t = qa.client.postOk<ReviewTaskRequest, TaskDto>("/api/tasks/${task.id}/review", ReviewTaskRequest(ReviewDecision.RETURN, "Нет допусков на отверстия", t.version))
        assertEquals(TaskStatus.REWORK, t.status)
        // Устаревшая версия (вкладка открыта давно) — конфликт, а не тихая перезапись
        val stale = worker.client.postJson("/api/tasks/${task.id}/start", encode(TaskCommandRequest(t.version - 1)))
        assertEquals(HttpStatusCode.Conflict, stale.status, stale.bodyAsText())

        // Сводка проекта считает задачу открытой
        val summary = pm.client.getOk<ProjectSummaryDto>("/api/projects/${project.id}/summary")
        assertEquals(1, summary.taskCount)
        assertEquals(1, summary.openTaskCount)
        // Посторонний не видит сводку проекта
        val foreignSummary = outsider.client.get("/api/projects/${project.id}/summary")
        assertTrue(foreignSummary.status.value in setOf(403, 404), "Сводка чужого проекта: ${foreignSummary.status}")
        // Сообщение чата видно контролёру ОТК
        val qaMessages = qa.client.get("/api/tasks/${task.id}/chat/messages")
        assertEquals(HttpStatusCode.OK, qaMessages.status)
        assertTrue(qaMessages.bodyAsText().contains("уточнить материал"))
    }

    // ------------------------------------------------------------------
    // Сценарий 4. НСИ и BOM → Снабжение (из BOM) → Производство → Материалы
    // ------------------------------------------------------------------
    @Test
    fun `bom drives demand and production order materials`() = smokeApp {
        val c = company()
        val technologist = c.hire("tech", "Технолог Кузнецова", perms(
            "bom:read_any", "bom:create", "bom:update", "bom:approve", "bom:explode",
            "production:read", "production:manage", "projects:create", "projects:read", "projects:read_any",
            "demands:create", "demands:read_any", "wms:read", "wms:post", "wms:manage"
        ))
        val project = technologist.client.postOk<CreateProjectRequest, ProjectDto>(
            "/api/projects", CreateProjectRequest(name = "Серия насосов НШ-10", projectType = ProjectType.ORDER_DEVELOPMENT, ownerId = technologist.user.id), HttpStatusCode.Created
        )
        val product = technologist.client.postOk<CreateNomenclatureRequest, NomenclatureDto>(
            "/api/wms/nomenclature", CreateNomenclatureRequest(code = "NSH-10", name = "Насос НШ-10", type = NomenclatureType.FINISHED_PRODUCT, unit = "шт"), HttpStatusCode.Created
        )
        val housing = technologist.client.postOk<CreateNomenclatureRequest, NomenclatureDto>(
            "/api/wms/nomenclature", CreateNomenclatureRequest(code = "HSG-10", name = "Корпус насоса", type = NomenclatureType.PURCHASED_COMPONENT, unit = "шт"), HttpStatusCode.Created
        )
        val seal = technologist.client.postOk<CreateNomenclatureRequest, NomenclatureDto>(
            "/api/wms/nomenclature", CreateNomenclatureRequest(code = "SEAL-22", name = "Манжета 22х32", type = NomenclatureType.PURCHASED_COMPONENT, unit = "шт"), HttpStatusCode.Created
        )
        val model = technologist.client.postOk<CreateProductModelRequest, ProductModelDto>(
            "/api/bom/models", CreateProductModelRequest("NSH-10", "Насос шестерённый НШ-10"), HttpStatusCode.Created
        )
        val rev = technologist.client.postOk<CreateBomRevisionRequest, BomRevisionDto>(
            "/api/bom/models/${model.id}/revisions", CreateBomRevisionRequest("A", "Первичный выпуск"), HttpStatusCode.Created
        )
        technologist.client.postOk<CreateBomLineRequest, BomLineDto>(
            "/api/bom/revisions/${rev.id}/lines", CreateBomLineRequest(parentNomenclatureId = product.id, childNomenclatureId = housing.id, quantity = "1", unit = "шт"), HttpStatusCode.Created
        )
        technologist.client.postOk<CreateBomLineRequest, BomLineDto>(
            "/api/bom/revisions/${rev.id}/lines", CreateBomLineRequest(parentNomenclatureId = product.id, childNomenclatureId = seal.id, quantity = "2", unit = "шт"), HttpStatusCode.Created
        )
        val revNow = technologist.client.getOk<BomRevisionDto>("/api/bom/revisions/${rev.id}")
        val approvedRev = technologist.client.postOk<ApproveBomRevisionRequest, BomRevisionDto>("/api/bom/revisions/${rev.id}/approve", ApproveBomRevisionRequest(revNow.version))
        assertEquals(BomRevisionStatus.APPROVED, approvedRev.status)

        // Утверждённую ревизию нельзя молча править
        val editApproved = technologist.client.postJson(
            "/api/bom/revisions/${rev.id}/lines", encode(CreateBomLineRequest(parentNomenclatureId = product.id, childNomenclatureId = seal.id, quantity = "5", unit = "шт"))
        )
        assertTrue(editApproved.status.value in 400..499, "Правка утверждённой ревизии: ${editApproved.status}")

        // Where-used: манжета используется в насосе
        val whereUsed = technologist.client.get("/api/bom/nomenclature/${seal.id}/where-used")
        assertEquals(HttpStatusCode.OK, whereUsed.status)
        assertTrue(whereUsed.bodyAsText().contains("NSH-10"), "Where-used не показал изделие")

        // Снабжение: потребность из BOM на 5 насосов → манжет 10
        val fromBom = technologist.client.postOk<CreateDemandFromBomRequest, DemandDetailDto>(
            "/api/demands/from-bom",
            CreateDemandFromBomRequest(project.id, rev.id, "5", LocalDate.now().plusDays(30).toString(), idempotencyKey = newKey("bom")),
            HttpStatusCode.Created
        )
        val sealLine = fromBom.lines.first { it.nomenclatureId == seal.id }
        assertEquals(0, BigDecimal(sealLine.quantity).compareTo(BigDecimal("10")))
        // Потребность видна во вкладке проекта
        val projectDemands = technologist.client.get("/api/projects/${project.id}/demands").bodyAsText()
        assertTrue(projectDemands.contains(fromBom.header.number), "Потребность из BOM не видна в проекте")

        // Производство: заказ на 5 шт по утверждённой ревизии, материалы из BOM
        val order = technologist.client.postOk<CreateProductionOrderRequest, ProductionOrderDto>(
            "/api/production/orders",
            CreateProductionOrderRequest(projectId = project.id, productModelId = model.id, bomRevisionId = rev.id, plannedQuantity = 5, responsibleId = technologist.user.id),
            HttpStatusCode.Created
        )
        val materials = technologist.client.postOk<Unit, List<ProductionOrderMaterialDto>>("/api/production/orders/${order.id}/materials/init-from-bom", Unit)
        val sealMat = materials.first { it.nomenclatureId == seal.id }
        assertEquals(0, BigDecimal(sealMat.plannedQty).compareTo(BigDecimal("10")))

        // Склад: на складе 10 манжет, резерв под заказ и расход
        val wh = technologist.client.postOk<CreateWarehouseRequest, WarehouseDto>("/api/wms/warehouses", CreateWarehouseRequest("WH-PR", "Склад производства"), HttpStatusCode.Created)
        val cell = technologist.client.postOk<CreateStorageLocationRequest, StorageLocationDto>(
            "/api/wms/locations", CreateStorageLocationRequest(wh.id, code = "P-01", name = "P-01", kind = StorageLocationKind.CELL), HttpStatusCode.Created
        )
        initialStock(technologist, wh.id, cell.id, seal.id, "10")
        val allocated = technologist.client.postOk<AllocateProductionMaterialRequest, ProductionOrderMaterialDto>(
            "/api/production/orders/${order.id}/materials/${sealMat.id}/allocate", AllocateProductionMaterialRequest(wh.id, "10", sealMat.version)
        )
        assertEquals(0, BigDecimal(stock(technologist, wh.id, seal.id).reservedQty).compareTo(BigDecimal("10")))

        // Нельзя израсходовать больше, чем зарезервировано
        val overConsume = technologist.client.postJson(
            "/api/production/orders/${order.id}/materials/${sealMat.id}/consume", encode(ConsumeProductionMaterialRequest("11", version = allocated.version))
        )
        assertEquals(HttpStatusCode.Conflict, overConsume.status)

        technologist.client.postOk<ConsumeProductionMaterialRequest, ProductionOrderMaterialDto>(
            "/api/production/orders/${order.id}/materials/${sealMat.id}/consume", ConsumeProductionMaterialRequest("10", version = allocated.version)
        )
        // AUDIT-07: расход в производстве обязан уменьшить физический остаток и снять резерв
        val after = stock(technologist, wh.id, seal.id)
        report("AUDIT-07 расход материала списывает остаток", BigDecimal(after.physicalQty).compareTo(BigDecimal.ZERO) == 0,
            "physical=${after.physicalQty}, reserved=${after.reservedQty}")
    }

    // ------------------------------------------------------------------
    // Сценарий 5. Администрирование: увольнение, смена прав, сброс пароля
    // ------------------------------------------------------------------
    @Test
    fun `admin offboarding and permission changes take effect immediately`() = smokeApp {
        val c = company()
        val hr = c.hire("hr", "Кадровик Морозова", perms("users:read_any", "users:activate", "users:reset_password", "users:update_any"))
        val leaver = c.hire("leaver", "Увольняемый Сотрудник", perms("wms:read"))
        assertEquals(HttpStatusCode.OK, leaver.client.get("/api/wms/warehouses").status)

        // Деактивация: действующая сессия перестаёт работать, повторный вход невозможен
        val profile = hr.client.getOk<UserDto>("/api/users/${leaver.user.id}")
        val deactivated = hr.client.putOk<SetActiveRequest, UserDto>("/api/users/${leaver.user.id}/active", SetActiveRequest(false, profile.version))
        assertEquals(false, deactivated.active)
        val oldSession = leaver.client.get("/api/wms/warehouses")
        assertEquals(HttpStatusCode.Unauthorized, oldSession.status, "Сессия уволенного сотрудника продолжает работать")
        val relogin = c.anonymous().postJson("/api/auth/login", encode(LoginRequest("leaver", "Passw0rd!")))
        assertTrue(relogin.status.value in setOf(401, 403), "Вход деактивированного: ${relogin.status}")

        // Кадровик не может выдать себе права администратора
        val escalate = hr.client.postJson("/api/roles", encode(CreateRoleRequest("HACK", "Hack", permissions = perms("*:*"))))
        assertEquals(HttpStatusCode.Forbidden, escalate.status)

        // Реактивация + сброс пароля: вход только с новым паролем
        val again = hr.client.putOk<SetActiveRequest, UserDto>("/api/users/${leaver.user.id}/active", SetActiveRequest(true, deactivated.version))
        val reset = hr.client.postJson("/api/users/${leaver.user.id}/reset-password", encode(AdminResetPasswordRequest("NewPassw0rd!", again.version)))
        assertTrue(reset.status.isSuccessCodeSafe(), "Сброс пароля: ${reset.status} ${reset.bodyAsText()}")
        val oldPwd = c.anonymous().postJson("/api/auth/login", encode(LoginRequest("leaver", "Passw0rd!")))
        assertEquals(HttpStatusCode.Unauthorized, oldPwd.status)
        c.login("leaver", "NewPassw0rd!")

        // Неверный пароль много раз подряд → блокировка (защита от подбора)
        val anon = c.anonymous()
        val statuses = (1..12).map { anon.postJson("/api/auth/login", encode(LoginRequest("hr", "wrong-$it"))).status.value }
        assertTrue(429 in statuses, "Нет ограничения попыток входа: $statuses")
    }

    // ------------------------------------------------------------------
    // Сценарий 6. Сотрудник без особых прав: что он видит на каждой вкладке
    // ------------------------------------------------------------------
    @Test
    fun `ordinary employee sees only own data across tabs`() = smokeApp {
        val c = company()
        val pm = c.hire("pm2", "РП Белов", perms("projects:create", "tasks:create", "tasks:assign_any", "documents:create", "documents:read"))
        val employee = c.hire("emp", "Рабочий Ковалёв", emptyList())
        val project = pm.client.postOk<CreateProjectRequest, ProjectDto>(
            "/api/projects", CreateProjectRequest(name = "Закрытый проект ОКР", projectType = ProjectType.ORDER_DEVELOPMENT, ownerId = pm.user.id), HttpStatusCode.Created
        )
        pm.client.postOk<CreateTaskRequest, TaskDto>(
            "/api/tasks",
            CreateTaskRequest(title = "Секретная задача ОКР", area = TaskArea.PROJECT, type = TaskWorkType.GENERAL, projectId = project.id, assigneeId = pm.user.id),
            HttpStatusCode.Created
        )
        val tasks = employee.client.get("/api/tasks").bodyAsText()
        assertTrue(!tasks.contains("Секретная задача ОКР"), "Список задач раскрыл чужую проектную задачу")
        val projects = employee.client.get("/api/projects").bodyAsText()
        assertTrue(!projects.contains("Закрытый проект ОКР"), "Список проектов раскрыл чужой проект")
        val search = employee.client.getOk<SearchResponse>("/api/search?q=ОКР")
        assertTrue(search.groups.all { it.items.isEmpty() }, "Поиск раскрыл чужие объекты")
        val calendar = employee.client.get("/api/analytics/calendar").bodyAsText()
        assertTrue(!calendar.contains("Закрытый проект ОКР") && !calendar.contains("Секретная задача ОКР"), "Календарь раскрыл чужие объекты")
        val summary = employee.client.getOk<AnalyticsSummaryDto>("/api/analytics/summary")
        assertEquals(0, summary.totalProjects, "Сводка аналитики показывает чужие проекты")
        // Закрытые разделы меню действительно закрыты на сервере
        for (path in listOf("/api/wms/stock", "/api/suppliers", "/api/purchase-orders", "/api/production/orders", "/api/bom/models", "/api/audit", "/api/users")) {
            val s = employee.client.get(path).status
            assertEquals(HttpStatusCode.Forbidden, s, "$path доступен сотруднику без прав")
        }
    }

    // ------------------------------------------------------------------
    // Подтверждение дефектов аудита на живом API (см. отчёт аудита)
    // ------------------------------------------------------------------
    @Test
    fun `audit findings on extras endpoints are reproducible`() = smokeApp {
        val c = company()
        val pm = c.hire("pm3", "РП Гусев", perms("projects:create", "tasks:create", "tasks:assign_any"))
        val clerk = c.hire("clerk", "Делопроизводитель", perms("tasks:read", "tasks:update_any", "projects:read", "projects:update"))
        val project = pm.client.postOk<CreateProjectRequest, ProjectDto>(
            "/api/projects", CreateProjectRequest(name = "Проект с ограниченным доступом", projectType = ProjectType.ORDER_DEVELOPMENT, ownerId = pm.user.id), HttpStatusCode.Created
        )
        val t1 = pm.client.postOk<CreateTaskRequest, TaskDto>(
            "/api/tasks", CreateTaskRequest(title = "Закрытая задача А", area = TaskArea.PROJECT, type = TaskWorkType.GENERAL, projectId = project.id, assigneeId = pm.user.id), HttpStatusCode.Created
        )
        val t2 = pm.client.postOk<CreateTaskRequest, TaskDto>(
            "/api/tasks", CreateTaskRequest(title = "Закрытая задача Б", area = TaskArea.PROJECT, type = TaskWorkType.GENERAL, projectId = project.id, assigneeId = pm.user.id), HttpStatusCode.Created
        )
        assertNotEquals(HttpStatusCode.OK, clerk.client.get("/api/tasks/${t1.id}").status, "Предусловие: задача недоступна делопроизводителю")

        // AUDIT-03: зависимости задачи создаются и читаются без проверки доступа к задаче
        val dep = clerk.client.postJson("/api/tasks/dependencies", encode(CreateTaskDependencyRequest(t1.id, t2.id)))
        report("AUDIT-03 зависимость к недоступной задаче запрещена", dep.status.value in 400..499, "${dep.status}")
        val deps = clerk.client.get("/api/tasks/${t1.id}/dependencies")
        report("AUDIT-03 зависимости недоступной задачи скрыты", deps.status.value in 400..499 || !deps.bodyAsText().contains("Закрытая задача"), "${deps.status}")

        // AUDIT-02: массовое закрытие минует машину состояний и права на конкретные задачи
        val bulk = clerk.client.postJson("/api/tasks/bulk-action", encode(BulkTaskActionRequest(listOf(t1.id, t2.id), "CLOSE")))
        val after = pm.client.getOk<TaskDto>("/api/tasks/${t1.id}")
        report("AUDIT-02 массовое закрытие соблюдает переходы статусов", after.status != TaskStatus.CLOSED, "bulk=${bulk.status}, статус=${after.status}")

        // AUDIT-04: запрос на изменение чужого проекта и неверная дата
        val badDate = clerk.client.postJson("/api/projects/${project.id}/change-requests", encode(CreateProjectChangeRequest("Сдвиг", "Нет комплектующих", "31.12.2026")))
        report("AUDIT-04 неверная дата запроса изменения → 400", badDate.status == HttpStatusCode.BadRequest, "${badDate.status}")
        val missingProject = clerk.client.postJson("/api/projects/987654/change-requests", encode(CreateProjectChangeRequest("Сдвиг", "Причина")))
        report("AUDIT-04 запрос изменения несуществующего проекта → 404", missingProject.status == HttpStatusCode.NotFound, "${missingProject.status}")

        // AUDIT-05: делегирование на несуществующего пользователя и в прошлое
        val ghost = pm.client.postJson("/api/delegations", encode(CreateDelegationRequest(987654, "2027-01-01T00:00:00Z")))
        report("AUDIT-05 делегирование несуществующему → 4xx", ghost.status.value in 400..499, "${ghost.status}")
        val past = pm.client.postJson("/api/delegations", encode(CreateDelegationRequest(clerk.user.id, "2020-01-01T00:00:00Z")))
        report("AUDIT-05 делегирование в прошлое → 400", past.status == HttpStatusCode.BadRequest, "${past.status}")

        // AUDIT-06: ссылка задачи на несуществующую задачу/объект
        val link = c.admin.client.postJson("/api/tasks/987654/links", encode(CreateTaskObjectLinkRequest("DEMAND", 1)))
        report("AUDIT-06 связь для несуществующей задачи → 404", link.status == HttpStatusCode.NotFound, "${link.status}")

        // AUDIT-08: импорт номенклатуры из CSV без типа подставляет COMPONENT, которого нет в справочнике
        val csv = c.admin.client.postJson("/api/analytics/import/nomenclature", encode(CsvImportRequest("code;name\nGAYKA-M6;Гайка М6")))
        report("AUDIT-08 CSV-импорт строки без типа", csv.status == HttpStatusCode.OK && csv.bodyAsText().contains("\"importedCount\":1"), "${csv.status} ${csv.bodyAsText().take(120)}")
        val csvComma = c.admin.client.postJson("/api/analytics/import/nomenclature", encode(CsvImportRequest("code;name;type;unit\nBOLT-1;Болт М6х20, оцинк.;MATERIAL;шт")))
        val boltName = c.admin.client.get("/api/wms/nomenclature?search=BOLT-1").bodyAsText()
        report("AUDIT-08 CSV-импорт сохраняет запятую в наименовании", boltName.contains("Болт М6х20, оцинк."), "${csvComma.status}")

        reportAll()
    }

    // ------------------------------------------------------------------

    private val findings = mutableListOf<String>()

    /** Фиксирует ожидаемое корректное поведение; расхождения печатаются в отчёт аудита. */
    private fun report(name: String, ok: Boolean, actual: String) {
        val line = "${if (ok) "OK  " else "FAIL"} $name — факт: $actual"
        println("SMOKE-FINDING $line")
        if (!ok) findings += line
    }

    private fun reportAll() {
        java.io.File("build/smoke-reports").mkdirs()
        java.io.File("build/smoke-reports/audit-findings.txt").writeText(findings.joinToString("\n"))
    }

    private fun HttpStatusCode.isSuccessCodeSafe() = value in 200..299

    private suspend fun stock(actor: Actor, warehouseId: Long, nomenclatureId: Long): StockBalanceDto {
        val list = actor.client.getOk<List<StockBalanceDto>>("/api/wms/stock?warehouseId=$warehouseId&nomenclatureId=$nomenclatureId")
        return list.first()
    }

    private suspend fun stockQty(actor: Actor, warehouseId: Long, nomenclatureId: Long): BigDecimal {
        val list = actor.client.getOk<List<StockBalanceDto>>("/api/wms/stock?warehouseId=$warehouseId&nomenclatureId=$nomenclatureId")
        val sum = list.fold(BigDecimal.ZERO) { acc, b -> acc + BigDecimal(b.physicalQty) }
        // Нормализуем к целому масштабу, чтобы сравнение через assertEquals не зависело от DECIMAL(19,4)
        return if (sum.signum() == 0) BigDecimal.ZERO else sum.setScale(0, java.math.RoundingMode.UNNECESSARY)
    }

    private suspend fun initialStock(actor: Actor, warehouseId: Long, locationId: Long, nomenclatureId: Long, qty: String) {
        val r = actor.client.postOk<CreateReceiptRequest, ReceiptDto>(
            "/api/wms/receipts",
            CreateReceiptRequest(
                sourceType = ReceiptSourceType.INITIAL_STOCK, warehouseId = warehouseId,
                lines = listOf(ReceiptLineInput(lineNo = 1, nomenclatureId = nomenclatureId, expectedQuantity = qty, locationId = locationId))
            ),
            HttpStatusCode.Created
        )
        actor.client.postOk<PostReceiptRequest, ReceiptDto>("/api/wms/receipts/${r.id}/post", PostReceiptRequest(r.version))
    }

    private fun issueRequest(warehouseId: Long, nomenclatureId: Long, qty: String, reservationId: Long?) = CreateIssueOrderRequest(
        warehouseId = warehouseId,
        reasonType = ReservationReasonType.OTHER,
        lines = listOf(IssueLineInput(lineNo = 1, nomenclatureId = nomenclatureId, requestedQuantity = qty, reservationId = reservationId)),
        idempotencyKey = newKey("issue")
    )

    @Before
    fun reset() = TestDatabase.reset()

    companion object {
        @JvmStatic
        @BeforeClass
        fun setup() = TestDatabase.init()

        @JvmStatic
        @AfterClass
        fun teardown() = TestDatabase.destroy()
    }
}
