package org.example.project.service

import org.example.project.dto.*
import org.example.project.exception.ConflictException
import org.example.project.exception.ForbiddenException
import org.example.project.exception.NotFoundException
import org.example.project.exception.ValidationException
import org.example.project.generated.jooq.tables.DocumentVersions.DOCUMENT_VERSIONS
import org.example.project.generated.jooq.tables.Documents.DOCUMENTS
import org.example.project.generated.jooq.tables.Nomenclature.NOMENCLATURE
import org.example.project.generated.jooq.tables.PurchaseOrderLines.PURCHASE_ORDER_LINES
import org.example.project.generated.jooq.tables.PurchaseOrders.PURCHASE_ORDERS
import org.example.project.generated.jooq.tables.Receipts.RECEIPTS
import org.example.project.generated.jooq.tables.SupplierInvoiceHistory.SUPPLIER_INVOICE_HISTORY
import org.example.project.generated.jooq.tables.SupplierInvoiceLines.SUPPLIER_INVOICE_LINES
import org.example.project.generated.jooq.tables.SupplierInvoices.SUPPLIER_INVOICES
import org.example.project.generated.jooq.tables.Suppliers.SUPPLIERS
import org.example.project.generated.jooq.tables.records.SupplierInvoiceLinesRecord
import org.example.project.generated.jooq.tables.records.SupplierInvoicesRecord
import org.example.project.model.CurrentUser
import org.example.project.repository.DocumentRepository
import org.jooq.DSLContext
import org.jooq.TransactionalCallable
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.NoSuchFileException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Счёт поставщика (D-61, D-62).
 *
 * - Счёт ведёт снабжение (`invoices:manage`); без загруженного файла счёта нет:
 *   документ DMS типа INVOICE с хотя бы одной версией-файлом обязателен.
 * - Сверка с заказом поставщику возвращает замечания трёх уровней (ERROR,
 *   REQUIRES_REASON, WARNING); подтвердить можно только без ошибок, замечания
 *   REQUIRES_REASON принимаются с письменным обоснованием.
 * - Поступление на склад по сверенному счёту оформляет снабжение
 *   ([createReceipt]); склад вносит факт и проводит приёмку (ReceiptService).
 * - Все переходы проверяют версию, пишут историю и аудит.
 */
class SupplierInvoiceService(
    private val receiptService: ReceiptService,
    private val documentRepository: DocumentRepository,
    private val fileStorage: FileStorage,
    private val auditService: AuditService,
    private val dsl: DSLContext
) {

    // -----------------------------------------------------------------------
    // Чтение
    // -----------------------------------------------------------------------

    fun list(
        status: SupplierInvoiceStatus?,
        supplierId: Long?,
        purchaseOrderId: Long?,
        currentUser: CurrentUser
    ): List<SupplierInvoiceDto> {
        requireRead(currentUser)
        var cond: org.jooq.Condition = DSL.trueCondition()
        if (status != null) cond = cond.and(SUPPLIER_INVOICES.STATUS.eq(status.name))
        if (supplierId != null) cond = cond.and(SUPPLIER_INVOICES.SUPPLIER_ID.eq(supplierId))
        if (purchaseOrderId != null) cond = cond.and(SUPPLIER_INVOICES.PURCHASE_ORDER_ID.eq(purchaseOrderId))
        return dsl.selectFrom(SUPPLIER_INVOICES)
            .where(cond)
            .orderBy(SUPPLIER_INVOICES.ID.desc())
            .fetch()
            .map { build(it, withLines = false) }
    }

    fun get(id: Long, currentUser: CurrentUser): SupplierInvoiceDto {
        requireRead(currentUser)
        return build(find(id), withLines = true)
    }

    fun history(id: Long, currentUser: CurrentUser): List<SupplierInvoiceHistoryDto> {
        requireRead(currentUser)
        find(id)
        return dsl.selectFrom(SUPPLIER_INVOICE_HISTORY)
            .where(SUPPLIER_INVOICE_HISTORY.INVOICE_ID.eq(id))
            .orderBy(SUPPLIER_INVOICE_HISTORY.ID.asc())
            .fetch { r ->
                SupplierInvoiceHistoryDto(
                    id = r.id!!,
                    action = r.action,
                    fromStatus = r.fromStatus,
                    toStatus = r.toStatus,
                    reason = r.reason,
                    changedBy = r.changedBy,
                    changedAt = r.changedAt?.toString()
                )
            }
    }

    // -----------------------------------------------------------------------
    // Регистрация и правка
    // -----------------------------------------------------------------------

    fun create(input: SupplierInvoiceInput, currentUser: CurrentUser): SupplierInvoiceDto {
        requireManage(currentUser)
        val prepared = prepare(input, currentUser)
        return dsl.transactionResult(TransactionalCallable {
            val id = try {
                insertHeader(prepared, currentUser)
            } catch (e: DataAccessException) {
                throw duplicateOr(e, prepared)
            }
            insertLines(id, prepared.lines)
            history(id, "CREATED", null, SupplierInvoiceStatus.DRAFT.name, null, currentUser)
            auditService.log(
                currentUser.userId, "SUPPLIER_INVOICE_CREATE", "SUPPLIER_INVOICE", id,
                mapOf("number" to prepared.number, "supplierId" to prepared.supplierId, "grossTotal" to prepared.grossTotal.toPlainString())
            )
            build(find(id), withLines = true)
        })
    }

    fun update(id: Long, request: UpdateSupplierInvoiceRequest, currentUser: CurrentUser): SupplierInvoiceDto {
        requireManage(currentUser)
        val prepared = prepare(request.invoice, currentUser)
        return dsl.transactionResult(TransactionalCallable {
            val invoice = lock(id)
            requireVersion(invoice, request.version)
            if (invoice.status != SupplierInvoiceStatus.DRAFT.name) {
                throw ConflictException("Изменять можно только счёт в статусе «Черновик»; верните счёт на доработку")
            }
            if (prepared.supplierId != invoice.supplierId) {
                throw ValidationException("Поставщика счёта изменить нельзя", mapOf("supplierId" to "immutable"))
            }
            try {
                dsl.update(SUPPLIER_INVOICES)
                    .set(SUPPLIER_INVOICES.NUMBER, prepared.number)
                    .set(SUPPLIER_INVOICES.INVOICE_DATE, prepared.invoiceDate)
                    .set(SUPPLIER_INVOICES.PURCHASE_ORDER_ID, prepared.purchaseOrderId)
                    .set(SUPPLIER_INVOICES.DOCUMENT_ID, prepared.documentId)
                    .set(SUPPLIER_INVOICES.SUPPLIER_INN, prepared.supplierInn)
                    .set(SUPPLIER_INVOICES.SUPPLIER_KPP, prepared.supplierKpp)
                    .set(SUPPLIER_INVOICES.CURRENCY, prepared.currency)
                    .set(SUPPLIER_INVOICES.PRICES_INCLUDE_VAT, prepared.pricesIncludeVat)
                    .set(SUPPLIER_INVOICES.NET_TOTAL, prepared.netTotal)
                    .set(SUPPLIER_INVOICES.VAT_TOTAL, prepared.vatTotal)
                    .set(SUPPLIER_INVOICES.GROSS_TOTAL, prepared.grossTotal)
                    .set(SUPPLIER_INVOICES.VALID_UNTIL, prepared.validUntil)
                    .set(SUPPLIER_INVOICES.PAYMENT_TERMS, prepared.paymentTerms)
                    .set(SUPPLIER_INVOICES.PREPAYMENT_PCT, prepared.prepaymentPct)
                    .set(SUPPLIER_INVOICES.LEAD_TIME_DAYS, prepared.leadTimeDays)
                    .set(SUPPLIER_INVOICES.LEAD_TIME_KIND, prepared.leadTimeKind)
                    .set(SUPPLIER_INVOICES.DELIVERY_TERMS, prepared.deliveryTerms)
                    .set(SUPPLIER_INVOICES.PAYMENT_PURPOSE, prepared.paymentPurpose)
                    .set(SUPPLIER_INVOICES.SUPPLIER_MANAGER, prepared.supplierManager)
                    .set(SUPPLIER_INVOICES.NOTE, prepared.note)
                    .set(SUPPLIER_INVOICES.UPDATED_BY, currentUser.userId)
                    .set(SUPPLIER_INVOICES.UPDATED_AT, OffsetDateTime.now())
                    .set(SUPPLIER_INVOICES.VERSION, SUPPLIER_INVOICES.VERSION.plus(1))
                    .where(SUPPLIER_INVOICES.ID.eq(id))
                    .execute()
            } catch (e: DataAccessException) {
                throw duplicateOr(e, prepared)
            }
            dsl.deleteFrom(SUPPLIER_INVOICE_LINES).where(SUPPLIER_INVOICE_LINES.INVOICE_ID.eq(id)).execute()
            insertLines(id, prepared.lines)
            history(id, "UPDATED", invoice.status, invoice.status, null, currentUser)
            auditService.log(currentUser.userId, "SUPPLIER_INVOICE_UPDATE", "SUPPLIER_INVOICE", id, mapOf("number" to prepared.number))
            build(find(id), withLines = true)
        })
    }

    // -----------------------------------------------------------------------
    // Сверка
    // -----------------------------------------------------------------------

    fun check(id: Long, currentUser: CurrentUser): SupplierInvoiceCheckDto {
        requireRead(currentUser)
        val invoice = find(id)
        return toCheckDto(id, computeIssues(invoice))
    }

    fun confirmCheck(id: Long, request: ConfirmSupplierInvoiceCheckRequest, currentUser: CurrentUser): SupplierInvoiceDto {
        requireManage(currentUser)
        return dsl.transactionResult(TransactionalCallable {
            val invoice = lock(id)
            requireVersion(invoice, request.version)
            if (invoice.status != SupplierInvoiceStatus.DRAFT.name) {
                throw ConflictException("Сверку можно подтвердить только для счёта в статусе «Черновик»")
            }
            val issues = computeIssues(invoice)
            val errors = issues.filter { it.severity == InvoiceIssueSeverity.ERROR }
            if (errors.isNotEmpty()) {
                throw ConflictException("Сверка не пройдена: " + errors.joinToString("; ") { it.message })
            }
            val reason = request.acceptReason?.trim()?.takeIf { it.isNotEmpty() }
            if (issues.any { it.severity == InvoiceIssueSeverity.REQUIRES_REASON } && reason == null) {
                throw ValidationException(
                    "Есть расхождения, которые принимаются только с обоснованием",
                    mapOf("acceptReason" to "required")
                )
            }
            dsl.update(SUPPLIER_INVOICES)
                .set(SUPPLIER_INVOICES.STATUS, SupplierInvoiceStatus.CHECKED.name)
                .set(SUPPLIER_INVOICES.ACCEPTED_DISCREPANCY_REASON, reason)
                .set(SUPPLIER_INVOICES.CHECKED_BY, currentUser.userId)
                .set(SUPPLIER_INVOICES.CHECKED_AT, OffsetDateTime.now())
                .set(SUPPLIER_INVOICES.UPDATED_BY, currentUser.userId)
                .set(SUPPLIER_INVOICES.UPDATED_AT, OffsetDateTime.now())
                .set(SUPPLIER_INVOICES.VERSION, SUPPLIER_INVOICES.VERSION.plus(1))
                .where(SUPPLIER_INVOICES.ID.eq(id))
                .execute()
            history(id, "CHECKED", invoice.status, SupplierInvoiceStatus.CHECKED.name, reason, currentUser)
            auditService.log(
                currentUser.userId, "SUPPLIER_INVOICE_CHECK", "SUPPLIER_INVOICE", id,
                mapOf("issues" to issues.map { it.code }, "acceptReason" to reason)
            )
            build(find(id), withLines = true)
        })
    }

    /** Вернуть сверенный счёт в черновик (пока по нему нет поступления на склад). */
    fun reopen(id: Long, request: SupplierInvoiceActionRequest, currentUser: CurrentUser): SupplierInvoiceDto {
        requireManage(currentUser)
        return dsl.transactionResult(TransactionalCallable {
            val invoice = lock(id)
            requireVersion(invoice, request.version)
            if (invoice.status != SupplierInvoiceStatus.CHECKED.name) {
                throw ConflictException("Вернуть в черновик можно только сверенный счёт")
            }
            if (activeReceipt(id) != null) {
                throw ConflictException("По счёту уже оформлено поступление на склад; сначала его нужно отменить")
            }
            dsl.update(SUPPLIER_INVOICES)
                .set(SUPPLIER_INVOICES.STATUS, SupplierInvoiceStatus.DRAFT.name)
                .setNull(SUPPLIER_INVOICES.CHECKED_BY)
                .setNull(SUPPLIER_INVOICES.CHECKED_AT)
                .setNull(SUPPLIER_INVOICES.ACCEPTED_DISCREPANCY_REASON)
                .set(SUPPLIER_INVOICES.UPDATED_BY, currentUser.userId)
                .set(SUPPLIER_INVOICES.UPDATED_AT, OffsetDateTime.now())
                .set(SUPPLIER_INVOICES.VERSION, SUPPLIER_INVOICES.VERSION.plus(1))
                .where(SUPPLIER_INVOICES.ID.eq(id))
                .execute()
            history(id, "REOPENED", invoice.status, SupplierInvoiceStatus.DRAFT.name, null, currentUser)
            auditService.log(currentUser.userId, "SUPPLIER_INVOICE_REOPEN", "SUPPLIER_INVOICE", id, emptyMap())
            build(find(id), withLines = true)
        })
    }

    fun cancel(id: Long, request: CancelSupplierInvoiceRequest, currentUser: CurrentUser): SupplierInvoiceDto {
        requireManage(currentUser)
        val reason = request.reason.trim()
        if (reason.isEmpty()) throw ValidationException("Укажите причину отмены счёта", mapOf("reason" to "required"))
        return dsl.transactionResult(TransactionalCallable {
            val invoice = lock(id)
            requireVersion(invoice, request.version)
            if (invoice.status == SupplierInvoiceStatus.CANCELLED.name) {
                throw ConflictException("Счёт уже отменён")
            }
            if (activeReceipt(id) != null) {
                throw ConflictException("По счёту оформлено поступление на склад; отменить счёт нельзя")
            }
            dsl.update(SUPPLIER_INVOICES)
                .set(SUPPLIER_INVOICES.STATUS, SupplierInvoiceStatus.CANCELLED.name)
                .set(SUPPLIER_INVOICES.CANCEL_REASON, reason)
                .set(SUPPLIER_INVOICES.UPDATED_BY, currentUser.userId)
                .set(SUPPLIER_INVOICES.UPDATED_AT, OffsetDateTime.now())
                .set(SUPPLIER_INVOICES.VERSION, SUPPLIER_INVOICES.VERSION.plus(1))
                .where(SUPPLIER_INVOICES.ID.eq(id))
                .execute()
            history(id, "CANCELLED", invoice.status, SupplierInvoiceStatus.CANCELLED.name, reason, currentUser)
            auditService.log(currentUser.userId, "SUPPLIER_INVOICE_CANCEL", "SUPPLIER_INVOICE", id, mapOf("reason" to reason))
            build(find(id), withLines = true)
        })
    }

    // -----------------------------------------------------------------------
    // Поступление на склад по счёту (оформляет снабжение)
    // -----------------------------------------------------------------------

    fun createReceipt(id: Long, request: CreateReceiptFromInvoiceRequest, currentUser: CurrentUser): ReceiptDto {
        requireManage(currentUser)
        return dsl.transactionResult(TransactionalCallable {
            val invoice = lock(id)
            requireVersion(invoice, request.version)
            if (invoice.status != SupplierInvoiceStatus.CHECKED.name) {
                throw ConflictException("Поступление оформляется только по сверенному счёту")
            }
            activeReceipt(id)?.let {
                throw ConflictException("По счёту уже оформлено поступление ${it.number}")
            }
            val lines = lines(id)
            val unmapped = lines.filter { it.nomenclatureId == null }
            if (unmapped.isNotEmpty()) {
                throw ConflictException("Не сопоставлена номенклатура в строках: ${unmapped.joinToString { it.lineNo.toString() }}")
            }
            val receipt = receiptService.createDraftForSupplierInvoice(
                ReceiptService.InvoiceReceiptDraft(
                    invoiceId = id,
                    invoiceNumber = invoice.number,
                    invoiceDate = invoice.invoiceDate,
                    purchaseOrderId = invoice.purchaseOrderId,
                    warehouseId = request.warehouseId,
                    expectedAt = request.expectedAt,
                    note = request.note?.trim()?.takeIf { it.isNotEmpty() },
                    lines = lines.map {
                        ReceiptService.InvoiceReceiptLine(
                            lineNo = it.lineNo,
                            nomenclatureId = it.nomenclatureId!!,
                            quantity = it.quantity,
                            purchaseOrderLineId = it.purchaseOrderLineId
                        )
                    }
                ),
                currentUser
            )
            dsl.update(SUPPLIER_INVOICES)
                .set(SUPPLIER_INVOICES.UPDATED_BY, currentUser.userId)
                .set(SUPPLIER_INVOICES.UPDATED_AT, OffsetDateTime.now())
                .set(SUPPLIER_INVOICES.VERSION, SUPPLIER_INVOICES.VERSION.plus(1))
                .where(SUPPLIER_INVOICES.ID.eq(id))
                .execute()
            history(id, "RECEIPT_CREATED", invoice.status, invoice.status, "Поступление ${receipt.number}", currentUser)
            auditService.log(
                currentUser.userId, "SUPPLIER_INVOICE_RECEIPT", "SUPPLIER_INVOICE", id,
                mapOf("receiptId" to receipt.id, "warehouseId" to request.warehouseId)
            )
            receipt
        })
    }

    // -----------------------------------------------------------------------
    // Файл счёта
    // -----------------------------------------------------------------------

    data class InvoiceFile(val fileName: String, val mimeType: String?, val content: ByteArray)

    /**
     * Файл счёта для снабжения и для склада, принимающего товар по этому счёту.
     * Скачивание журналируется так же, как в DMS.
     */
    fun downloadFile(id: Long, ipAddress: String?, userAgent: String?, currentUser: CurrentUser): InvoiceFile {
        val invoice = find(id)
        val canSee = canRead(currentUser) ||
            (currentUser.hasPermission("wms", "read") && activeReceipt(id) != null)
        if (!canSee) throw ForbiddenException("Missing permission invoices:read")
        val version = latestFileVersion(invoice.documentId)
            ?: throw NotFoundException("supplier_invoice_file", id)
        val storageKey = documentRepository.findFileStorageKey(version.fileId)
            ?: throw NotFoundException("supplier_invoice_file", id)
        val content = try {
            fileStorage.read(storageKey)
        } catch (e: NoSuchFileException) {
            throw NotFoundException("supplier_invoice_file", id)
        }
        dsl.transaction { _ ->
            documentRepository.logDownload(version.versionId, currentUser.userId, ipAddress, userAgent)
            auditService.log(
                currentUser.userId, "SUPPLIER_INVOICE_FILE_DOWNLOAD", "SUPPLIER_INVOICE", id,
                mapOf("documentVersionId" to version.versionId)
            )
        }
        return InvoiceFile(version.fileName, version.mimeType, content)
    }

    // -----------------------------------------------------------------------
    // Подготовка и проверки ввода
    // -----------------------------------------------------------------------

    private data class PreparedLine(
        val lineNo: Int,
        val supplierItemCode: String?,
        val supplierItemName: String,
        val nomenclatureId: Long?,
        val purchaseOrderLineId: Long?,
        val quantity: BigDecimal,
        val unit: String,
        val unitPrice: BigDecimal,
        val vatRate: BigDecimal?,
        val vatAmount: BigDecimal,
        val lineTotal: BigDecimal
    )

    private data class Prepared(
        val number: String,
        val invoiceDate: LocalDate,
        val supplierId: Long,
        val purchaseOrderId: Long?,
        val documentId: Long,
        val supplierInn: String?,
        val supplierKpp: String?,
        val currency: String,
        val pricesIncludeVat: Boolean,
        val netTotal: BigDecimal,
        val vatTotal: BigDecimal,
        val grossTotal: BigDecimal,
        val validUntil: LocalDate?,
        val paymentTerms: String?,
        val prepaymentPct: BigDecimal?,
        val leadTimeDays: Int?,
        val leadTimeKind: String?,
        val deliveryTerms: String?,
        val paymentPurpose: String?,
        val supplierManager: String?,
        val note: String?,
        val lines: List<PreparedLine>
    )

    private fun prepare(input: SupplierInvoiceInput, currentUser: CurrentUser): Prepared {
        val number = input.number.trim()
        if (number.isEmpty()) throw ValidationException("Укажите номер счёта", mapOf("number" to "required"))
        val invoiceDate = date(input.invoiceDate, "invoiceDate")
        val validUntil = input.validUntil?.takeIf { it.isNotBlank() }?.let { date(it, "validUntil") }
        if (validUntil != null && validUntil.isBefore(invoiceDate)) {
            throw ValidationException("Срок действия счёта раньше даты счёта", mapOf("validUntil" to "before_invoice_date"))
        }
        val supplier = dsl.selectFrom(SUPPLIERS).where(SUPPLIERS.ID.eq(input.supplierId)).fetchOne()
            ?: throw NotFoundException("supplier", input.supplierId)
        if (supplier.archived == true) {
            throw ConflictException("Поставщик ${supplier.name} в архиве")
        }
        requireInvoiceDocument(input.documentId, currentUser)

        val order = input.purchaseOrderId?.let { orderId ->
            val found = dsl.selectFrom(PURCHASE_ORDERS).where(PURCHASE_ORDERS.ID.eq(orderId)).fetchOne()
                ?: throw NotFoundException("purchase_order", orderId)
            if (found.supplierId != supplier.id) {
                throw ValidationException("Заказ ${found.number} оформлен у другого поставщика", mapOf("purchaseOrderId" to "other_supplier"))
            }
            found
        }
        val orderLines = order?.let {
            dsl.selectFrom(PURCHASE_ORDER_LINES).where(PURCHASE_ORDER_LINES.ORDER_ID.eq(it.id)).fetch()
        } ?: emptyList()

        if (input.lines.isEmpty()) throw ValidationException("В счёте должна быть хотя бы одна строка", mapOf("lines" to "required"))
        val lineNos = input.lines.map { it.lineNo }
        if (lineNos.distinct().size != lineNos.size) {
            throw ValidationException("Номера строк счёта повторяются", mapOf("lines" to "duplicate_line_no"))
        }
        val lines = input.lines.mapIndexed { index, line ->
            val name = line.supplierItemName.trim()
            if (name.isEmpty()) throw ValidationException("Укажите наименование в строке ${line.lineNo}", mapOf("lines[$index].supplierItemName" to "required"))
            val unit = line.unit.trim()
            if (unit.isEmpty()) throw ValidationException("Укажите единицу измерения в строке ${line.lineNo}", mapOf("lines[$index].unit" to "required"))
            val quantity = amount(line.quantity, "lines[$index].quantity", 4)
            if (quantity.signum() <= 0) throw ValidationException("Количество в строке ${line.lineNo} должно быть больше нуля", mapOf("lines[$index].quantity" to "positive"))
            var orderLineId = line.purchaseOrderLineId
            var nomenclatureId = line.nomenclatureId
            if (order != null) {
                if (orderLineId == null && nomenclatureId != null) {
                    orderLineId = orderLines.singleOrNull { it.nomenclatureId == nomenclatureId }?.id
                }
                if (orderLineId == null && orderLines.size == 1 && input.lines.size == 1) {
                    orderLineId = orderLines.first().id
                }
                if (orderLineId != null) {
                    val orderLine = orderLines.firstOrNull { it.id == orderLineId }
                        ?: throw ValidationException("Строка заказа не относится к заказу ${order.number}", mapOf("lines[$index].purchaseOrderLineId" to "not_in_order"))
                    if (nomenclatureId == null) nomenclatureId = orderLine.nomenclatureId
                }
            } else if (orderLineId != null) {
                throw ValidationException("Строка заказа указана без заказа", mapOf("lines[$index].purchaseOrderLineId" to "order_required"))
            }
            if (nomenclatureId != null) {
                dsl.fetchExists(NOMENCLATURE, NOMENCLATURE.ID.eq(nomenclatureId)).takeIf { it }
                    ?: throw NotFoundException("nomenclature", nomenclatureId)
            }
            PreparedLine(
                lineNo = line.lineNo,
                supplierItemCode = line.supplierItemCode?.trim()?.takeIf { it.isNotEmpty() },
                supplierItemName = name,
                nomenclatureId = nomenclatureId,
                purchaseOrderLineId = orderLineId,
                quantity = quantity,
                unit = unit,
                unitPrice = amount(line.unitPrice, "lines[$index].unitPrice", 4),
                vatRate = line.vatRate?.takeIf { it.isNotBlank() }?.let { amount(it, "lines[$index].vatRate", 2) },
                vatAmount = amount(line.vatAmount, "lines[$index].vatAmount", 2),
                lineTotal = amount(line.lineTotal, "lines[$index].lineTotal", 2)
            )
        }
        val currency = input.currency.trim().uppercase()
        if (!Regex("[A-Z]{3}").matches(currency)) throw ValidationException("Код валюты — три латинские буквы", mapOf("currency" to "invalid"))
        val prepayment = input.prepaymentPct?.takeIf { it.isNotBlank() }?.let { amount(it, "prepaymentPct", 2) }
        if (prepayment != null && prepayment > BigDecimal(100)) {
            throw ValidationException("Предоплата не может превышать 100 %", mapOf("prepaymentPct" to "range"))
        }
        if ((input.leadTimeDays ?: 0) < 0) {
            throw ValidationException("Срок изготовления не может быть отрицательным", mapOf("leadTimeDays" to "range"))
        }
        return Prepared(
            number = number,
            invoiceDate = invoiceDate,
            supplierId = supplier.id!!,
            purchaseOrderId = order?.id,
            documentId = input.documentId,
            supplierInn = input.supplierInn?.trim()?.takeIf { it.isNotEmpty() },
            supplierKpp = input.supplierKpp?.trim()?.takeIf { it.isNotEmpty() },
            currency = currency,
            pricesIncludeVat = input.pricesIncludeVat,
            netTotal = amount(input.netTotal, "netTotal", 2),
            vatTotal = amount(input.vatTotal, "vatTotal", 2),
            grossTotal = amount(input.grossTotal, "grossTotal", 2),
            validUntil = validUntil,
            paymentTerms = input.paymentTerms.clean(),
            prepaymentPct = prepayment,
            leadTimeDays = input.leadTimeDays,
            leadTimeKind = input.leadTimeKind?.name,
            deliveryTerms = input.deliveryTerms.clean(),
            paymentPurpose = input.paymentPurpose.clean(),
            supplierManager = input.supplierManager.clean(),
            note = input.note.clean(),
            lines = lines
        )
    }

    /** Файл счёта обязателен: документ типа INVOICE, доступный пользователю, с загруженной версией. */
    private fun requireInvoiceDocument(documentId: Long, currentUser: CurrentUser) {
        val document = documentRepository.findDocumentById(documentId)
            ?: throw ValidationException("Загрузите файл счёта", mapOf("documentId" to "not_found"))
        val readable = document.confidentiality == "PUBLIC" || document.confidentiality == "INTERNAL" ||
            document.ownerId == currentUser.userId || currentUser.hasPermission("documents", "read_any")
        if (!readable) throw ValidationException("Загрузите файл счёта", mapOf("documentId" to "not_found"))
        if (document.docType != DocumentType.INVOICE.name) {
            throw ValidationException("Документ должен иметь тип «Счёт»", mapOf("documentId" to "not_invoice"))
        }
        latestFileVersion(documentId)
            ?: throw ValidationException("К документу не загружен файл счёта", mapOf("documentId" to "no_file"))
    }

    private data class FileVersion(val versionId: Long, val fileId: Long, val fileName: String, val mimeType: String?)

    private fun latestFileVersion(documentId: Long): FileVersion? =
        dsl.selectFrom(DOCUMENT_VERSIONS)
            .where(DOCUMENT_VERSIONS.DOCUMENT_ID.eq(documentId))
            .and(DOCUMENT_VERSIONS.FILE_ID.isNotNull)
            .and(DOCUMENT_VERSIONS.STATUS.ne("CANCELLED"))
            .orderBy(DOCUMENT_VERSIONS.VERSION_NUMBER.desc())
            .limit(1)
            .fetchOne()
            ?.let { FileVersion(it.id!!, it.fileId!!, it.fileName, it.mimeType) }

    // -----------------------------------------------------------------------
    // Правила сверки
    // -----------------------------------------------------------------------

    private fun computeIssues(invoice: SupplierInvoicesRecord): List<SupplierInvoiceIssueDto> {
        val issues = mutableListOf<SupplierInvoiceIssueDto>()
        fun add(code: String, severity: InvoiceIssueSeverity, message: String, lineNo: Int? = null) {
            issues += SupplierInvoiceIssueDto(code, severity, lineNo, message)
        }
        val lines = lines(invoice.id!!)
        val supplier = dsl.selectFrom(SUPPLIERS).where(SUPPLIERS.ID.eq(invoice.supplierId)).fetchOne()!!
        if (supplier.archived == true) add("SUPPLIER_ARCHIVED", InvoiceIssueSeverity.ERROR, "Поставщик ${supplier.name} в архиве")

        // Реквизиты поставщика: ИНН/КПП в счёте против карточки.
        val cardInn = supplier.inn?.trim()?.takeIf { it.isNotEmpty() }
        when {
            invoice.supplierInn == null -> add("SUPPLIER_INN_MISSING", InvoiceIssueSeverity.WARNING, "ИНН поставщика из счёта не указан — сверка реквизитов не выполнена")
            cardInn == null -> add("SUPPLIER_CARD_INN_MISSING", InvoiceIssueSeverity.WARNING, "В карточке поставщика не заполнен ИНН")
            cardInn != invoice.supplierInn -> add("SUPPLIER_INN_MISMATCH", InvoiceIssueSeverity.ERROR, "ИНН в счёте (${invoice.supplierInn}) не совпадает с карточкой поставщика ($cardInn)")
        }
        val cardKpp = supplier.kpp?.trim()?.takeIf { it.isNotEmpty() }
        if (invoice.supplierKpp != null && cardKpp != null && cardKpp != invoice.supplierKpp) {
            add("SUPPLIER_KPP_MISMATCH", InvoiceIssueSeverity.REQUIRES_REASON, "КПП в счёте (${invoice.supplierKpp}) не совпадает с карточкой ($cardKpp)")
        }

        // Срок действия счёта.
        invoice.validUntil?.let { until ->
            val today = LocalDate.now()
            when {
                until.isBefore(today) -> add("EXPIRED", InvoiceIssueSeverity.ERROR, "Срок действия счёта истёк $until — запросите у поставщика новый счёт")
                businessDaysBetween(today, until) <= 2 -> add("EXPIRES_SOON", InvoiceIssueSeverity.WARNING, "Срок действия счёта истекает $until")
            }
        }

        // Пересчёт сумм по строкам (до копеек) и итогов.
        var net = BigDecimal.ZERO
        var vat = BigDecimal.ZERO
        var gross = BigDecimal.ZERO
        lines.forEach { line ->
            val amount = (line.quantity * line.unitPrice).setScale(2, RoundingMode.HALF_UP)
            val rate = line.vatRate ?: BigDecimal.ZERO
            val lineVat = if (invoice.pricesIncludeVat == true) {
                amount.multiply(rate).divide(BigDecimal(100).add(rate), 2, RoundingMode.HALF_UP)
            } else {
                amount.multiply(rate).divide(BigDecimal(100), 2, RoundingMode.HALF_UP)
            }
            if (amount.compareTo(line.lineTotal) != 0) {
                add("LINE_TOTAL_MISMATCH", InvoiceIssueSeverity.WARNING, "Сумма строки: в счёте ${line.lineTotal.money()}, расчёт ${amount.money()}", line.lineNo)
            }
            if (lineVat.compareTo(line.vatAmount) != 0) {
                add("LINE_VAT_MISMATCH", InvoiceIssueSeverity.WARNING, "НДС строки: в счёте ${line.vatAmount.money()}, расчёт ${lineVat.money()}", line.lineNo)
            }
            if (invoice.pricesIncludeVat == true) {
                gross += line.lineTotal; vat += line.vatAmount; net += line.lineTotal - line.vatAmount
            } else {
                net += line.lineTotal; vat += line.vatAmount; gross += line.lineTotal + line.vatAmount
            }
        }
        if (net.compareTo(invoice.netTotal) != 0 || vat.compareTo(invoice.vatTotal) != 0 || gross.compareTo(invoice.grossTotal) != 0) {
            add(
                "TOTALS_MISMATCH", InvoiceIssueSeverity.REQUIRES_REASON,
                "Итоги счёта (${invoice.netTotal.money()} / НДС ${invoice.vatTotal.money()} / ${invoice.grossTotal.money()}) " +
                    "не равны сумме строк (${net.money()} / НДС ${vat.money()} / ${gross.money()})"
            )
        }

        // Номенклатура и кратность поставки.
        lines.forEach { line ->
            val nomenclatureId = line.nomenclatureId
            if (nomenclatureId == null) {
                add("NOMENCLATURE_NOT_MAPPED", InvoiceIssueSeverity.ERROR, "Строка «${line.supplierItemName}» не сопоставлена с номенклатурой", line.lineNo)
            } else {
                val multiple = dsl.select(NOMENCLATURE.ORDER_MULTIPLE).from(NOMENCLATURE)
                    .where(NOMENCLATURE.ID.eq(nomenclatureId)).fetchOne(NOMENCLATURE.ORDER_MULTIPLE)
                if (multiple != null && multiple > BigDecimal.ONE && line.quantity.remainder(multiple).signum() != 0) {
                    add("ORDER_MULTIPLE", InvoiceIssueSeverity.WARNING, "Количество ${line.quantity.stripTrailingZeros().toPlainString()} не кратно ${multiple.stripTrailingZeros().toPlainString()}", line.lineNo)
                }
            }
        }

        // Сверка с заказом поставщику.
        val orderId = invoice.purchaseOrderId
        if (orderId == null) {
            add("NO_PURCHASE_ORDER", InvoiceIssueSeverity.WARNING, "Счёт не привязан к заказу поставщику — цены и количества не сверены")
        } else {
            val order = dsl.selectFrom(PURCHASE_ORDERS).where(PURCHASE_ORDERS.ID.eq(orderId)).fetchOne()!!
            if (order.status !in invoiceAllowedOrderStatuses) {
                add("ORDER_STATUS", InvoiceIssueSeverity.ERROR, "Заказ ${order.number} в статусе ${order.status}: счёт принимается по согласованному заказу")
            }
            if (order.supplierId != invoice.supplierId) {
                add("ORDER_SUPPLIER_MISMATCH", InvoiceIssueSeverity.ERROR, "Заказ ${order.number} оформлен у другого поставщика")
            }
            val orderLines = dsl.selectFrom(PURCHASE_ORDER_LINES).where(PURCHASE_ORDER_LINES.ORDER_ID.eq(orderId)).fetch().associateBy { it.id!! }
            lines.forEach { line ->
                val orderLine = line.purchaseOrderLineId?.let { orderLines[it] }
                if (orderLine == null) {
                    add("ORDER_LINE_REQUIRED", InvoiceIssueSeverity.ERROR, "Строка не сопоставлена со строкой заказа ${order.number}", line.lineNo)
                    return@forEach
                }
                if (line.nomenclatureId != null && line.nomenclatureId != orderLine.nomenclatureId) {
                    add("ORDER_LINE_NOMENCLATURE", InvoiceIssueSeverity.ERROR, "Номенклатура строки отличается от строки заказа", line.lineNo)
                }
                val otherInvoiced = dsl.select(DSL.coalesce(DSL.sum(SUPPLIER_INVOICE_LINES.QUANTITY), BigDecimal.ZERO))
                    .from(SUPPLIER_INVOICE_LINES)
                    .join(SUPPLIER_INVOICES).on(SUPPLIER_INVOICES.ID.eq(SUPPLIER_INVOICE_LINES.INVOICE_ID))
                    .where(SUPPLIER_INVOICE_LINES.PURCHASE_ORDER_LINE_ID.eq(orderLine.id))
                    .and(SUPPLIER_INVOICES.STATUS.ne(SupplierInvoiceStatus.CANCELLED.name))
                    .and(SUPPLIER_INVOICES.ID.ne(invoice.id))
                    .fetchOne(0, BigDecimal::class.java) ?: BigDecimal.ZERO
                val available = orderLine.quantity - otherInvoiced
                if (line.quantity > available) {
                    add(
                        "QUANTITY_EXCEEDS_ORDER", InvoiceIssueSeverity.ERROR,
                        "Количество ${line.quantity.qty()} больше остатка по заказу ${available.max(BigDecimal.ZERO).qty()} " +
                            "(заказано ${orderLine.quantity.qty()}, в других счетах ${otherInvoiced.qty()})",
                        line.lineNo
                    )
                }
                val rate = line.vatRate ?: BigDecimal.ZERO
                val netPrice = if (invoice.pricesIncludeVat == true) {
                    line.unitPrice.multiply(BigDecimal(100)).divide(BigDecimal(100).add(rate), 4, RoundingMode.HALF_UP)
                } else line.unitPrice
                if (netPrice.subtract(orderLine.unitPrice).abs() > PRICE_TOLERANCE) {
                    add("PRICE_MISMATCH", InvoiceIssueSeverity.REQUIRES_REASON, "Цена без НДС ${netPrice.money()} отличается от цены заказа ${orderLine.unitPrice.money()}", line.lineNo)
                }
                if ((line.vatRate ?: BigDecimal.ZERO).compareTo(orderLine.vatRate) != 0) {
                    add("VAT_RATE_MISMATCH", InvoiceIssueSeverity.REQUIRES_REASON, "Ставка НДС ${line.vatRate?.stripTrailingZeros()?.toPlainString() ?: "без НДС"} отличается от заказа (${orderLine.vatRate.stripTrailingZeros().toPlainString()} %)", line.lineNo)
                }
                if (!line.unit.equals(orderLine.unit, ignoreCase = true)) {
                    add("UNIT_MISMATCH", InvoiceIssueSeverity.WARNING, "Единица «${line.unit}» отличается от заказа («${orderLine.unit}»)", line.lineNo)
                }
            }
            // Сумма всех действующих счетов по заказу не превышает сумму заказа.
            val orderGross = orderLines.values.fold(BigDecimal.ZERO) { acc, l ->
                acc + (l.quantity * l.unitPrice).multiply(BigDecimal.ONE + l.vatRate.divide(BigDecimal(100)))
            }.setScale(2, RoundingMode.HALF_UP)
            val invoicedGross = dsl.select(DSL.coalesce(DSL.sum(SUPPLIER_INVOICES.GROSS_TOTAL), BigDecimal.ZERO))
                .from(SUPPLIER_INVOICES)
                .where(SUPPLIER_INVOICES.PURCHASE_ORDER_ID.eq(orderId))
                .and(SUPPLIER_INVOICES.STATUS.ne(SupplierInvoiceStatus.CANCELLED.name))
                .fetchOne(0, BigDecimal::class.java) ?: BigDecimal.ZERO
            if (invoicedGross > orderGross + BigDecimal("0.01")) {
                add("INVOICES_EXCEED_ORDER", InvoiceIssueSeverity.REQUIRES_REASON, "Сумма счетов по заказу ${invoicedGross.money()} больше суммы заказа ${orderGross.money()}")
            }
        }
        return issues
    }

    private fun toCheckDto(id: Long, issues: List<SupplierInvoiceIssueDto>) = SupplierInvoiceCheckDto(
        invoiceId = id,
        issues = issues,
        canConfirm = issues.none { it.severity == InvoiceIssueSeverity.ERROR },
        requiresReason = issues.any { it.severity == InvoiceIssueSeverity.REQUIRES_REASON }
    )

    // -----------------------------------------------------------------------
    // Хранение
    // -----------------------------------------------------------------------

    private fun insertHeader(p: Prepared, currentUser: CurrentUser): Long =
        dsl.insertInto(SUPPLIER_INVOICES)
            .set(SUPPLIER_INVOICES.NUMBER, p.number)
            .set(SUPPLIER_INVOICES.INVOICE_DATE, p.invoiceDate)
            .set(SUPPLIER_INVOICES.SUPPLIER_ID, p.supplierId)
            .set(SUPPLIER_INVOICES.PURCHASE_ORDER_ID, p.purchaseOrderId)
            .set(SUPPLIER_INVOICES.DOCUMENT_ID, p.documentId)
            .set(SUPPLIER_INVOICES.SUPPLIER_INN, p.supplierInn)
            .set(SUPPLIER_INVOICES.SUPPLIER_KPP, p.supplierKpp)
            .set(SUPPLIER_INVOICES.CURRENCY, p.currency)
            .set(SUPPLIER_INVOICES.PRICES_INCLUDE_VAT, p.pricesIncludeVat)
            .set(SUPPLIER_INVOICES.NET_TOTAL, p.netTotal)
            .set(SUPPLIER_INVOICES.VAT_TOTAL, p.vatTotal)
            .set(SUPPLIER_INVOICES.GROSS_TOTAL, p.grossTotal)
            .set(SUPPLIER_INVOICES.VALID_UNTIL, p.validUntil)
            .set(SUPPLIER_INVOICES.PAYMENT_TERMS, p.paymentTerms)
            .set(SUPPLIER_INVOICES.PREPAYMENT_PCT, p.prepaymentPct)
            .set(SUPPLIER_INVOICES.LEAD_TIME_DAYS, p.leadTimeDays)
            .set(SUPPLIER_INVOICES.LEAD_TIME_KIND, p.leadTimeKind)
            .set(SUPPLIER_INVOICES.DELIVERY_TERMS, p.deliveryTerms)
            .set(SUPPLIER_INVOICES.PAYMENT_PURPOSE, p.paymentPurpose)
            .set(SUPPLIER_INVOICES.SUPPLIER_MANAGER, p.supplierManager)
            .set(SUPPLIER_INVOICES.NOTE, p.note)
            .set(SUPPLIER_INVOICES.CREATED_BY, currentUser.userId)
            .returningResult(SUPPLIER_INVOICES.ID)
            .fetchOne()!!
            .get(SUPPLIER_INVOICES.ID)!!

    private fun insertLines(invoiceId: Long, lines: List<PreparedLine>) {
        lines.forEach { l ->
            dsl.insertInto(SUPPLIER_INVOICE_LINES)
                .set(SUPPLIER_INVOICE_LINES.INVOICE_ID, invoiceId)
                .set(SUPPLIER_INVOICE_LINES.LINE_NO, l.lineNo)
                .set(SUPPLIER_INVOICE_LINES.SUPPLIER_ITEM_CODE, l.supplierItemCode)
                .set(SUPPLIER_INVOICE_LINES.SUPPLIER_ITEM_NAME, l.supplierItemName)
                .set(SUPPLIER_INVOICE_LINES.NOMENCLATURE_ID, l.nomenclatureId)
                .set(SUPPLIER_INVOICE_LINES.PURCHASE_ORDER_LINE_ID, l.purchaseOrderLineId)
                .set(SUPPLIER_INVOICE_LINES.QUANTITY, l.quantity)
                .set(SUPPLIER_INVOICE_LINES.UNIT, l.unit)
                .set(SUPPLIER_INVOICE_LINES.UNIT_PRICE, l.unitPrice)
                .set(SUPPLIER_INVOICE_LINES.VAT_RATE, l.vatRate)
                .set(SUPPLIER_INVOICE_LINES.VAT_AMOUNT, l.vatAmount)
                .set(SUPPLIER_INVOICE_LINES.LINE_TOTAL, l.lineTotal)
                .execute()
        }
    }

    private fun history(id: Long, action: String, from: String?, to: String?, reason: String?, currentUser: CurrentUser) {
        dsl.insertInto(SUPPLIER_INVOICE_HISTORY)
            .set(SUPPLIER_INVOICE_HISTORY.INVOICE_ID, id)
            .set(SUPPLIER_INVOICE_HISTORY.ACTION, action)
            .set(SUPPLIER_INVOICE_HISTORY.FROM_STATUS, from)
            .set(SUPPLIER_INVOICE_HISTORY.TO_STATUS, to)
            .set(SUPPLIER_INVOICE_HISTORY.REASON, reason)
            .set(SUPPLIER_INVOICE_HISTORY.CHANGED_BY, currentUser.userId)
            .execute()
    }

    private fun find(id: Long): SupplierInvoicesRecord =
        dsl.selectFrom(SUPPLIER_INVOICES).where(SUPPLIER_INVOICES.ID.eq(id)).fetchOne()
            ?: throw NotFoundException("supplier_invoice", id)

    private fun lock(id: Long): SupplierInvoicesRecord =
        dsl.selectFrom(SUPPLIER_INVOICES).where(SUPPLIER_INVOICES.ID.eq(id)).forUpdate().fetchOne()
            ?: throw NotFoundException("supplier_invoice", id)

    private fun lines(id: Long): List<SupplierInvoiceLinesRecord> =
        dsl.selectFrom(SUPPLIER_INVOICE_LINES)
            .where(SUPPLIER_INVOICE_LINES.INVOICE_ID.eq(id))
            .orderBy(SUPPLIER_INVOICE_LINES.LINE_NO.asc())
            .fetch()

    private data class ActiveReceipt(val id: Long, val number: String, val status: String)

    private fun activeReceipt(invoiceId: Long): ActiveReceipt? =
        dsl.select(RECEIPTS.ID, RECEIPTS.NUMBER, RECEIPTS.STATUS)
            .from(RECEIPTS)
            .where(RECEIPTS.SUPPLIER_INVOICE_ID.eq(invoiceId))
            .and(RECEIPTS.STATUS.ne("CANCELLED"))
            .fetchOne { ActiveReceipt(it.get(RECEIPTS.ID)!!, it.get(RECEIPTS.NUMBER)!!, it.get(RECEIPTS.STATUS) ?: "DRAFT") }

    private fun requireVersion(invoice: SupplierInvoicesRecord, expected: Long) {
        if ((invoice.version ?: 0L) != expected) {
            throw ConflictException("Счёт изменён другим пользователем; обновите карточку")
        }
    }

    private fun duplicateOr(e: DataAccessException, p: Prepared): Exception =
        if (e.sqlState() == "23505" && e.message?.contains("uq_supplier_invoices_number") == true) {
            ConflictException("Счёт № ${p.number} от ${p.invoiceDate} этого поставщика уже зарегистрирован")
        } else e

    private fun build(r: SupplierInvoicesRecord, withLines: Boolean): SupplierInvoiceDto {
        val supplierName = dsl.select(SUPPLIERS.NAME).from(SUPPLIERS).where(SUPPLIERS.ID.eq(r.supplierId)).fetchOne(SUPPLIERS.NAME)
        val orderNumber = r.purchaseOrderId?.let {
            dsl.select(PURCHASE_ORDERS.NUMBER).from(PURCHASE_ORDERS).where(PURCHASE_ORDERS.ID.eq(it)).fetchOne(PURCHASE_ORDERS.NUMBER)
        }
        val documentNumber = dsl.select(DOCUMENTS.NUMBER).from(DOCUMENTS).where(DOCUMENTS.ID.eq(r.documentId)).fetchOne(DOCUMENTS.NUMBER)
        val receipt = activeReceipt(r.id!!)
        val lineDtos = if (!withLines) emptyList() else {
            val rows = lines(r.id!!)
            val nomenclature = rows.mapNotNull { it.nomenclatureId }.distinct().takeIf { it.isNotEmpty() }?.let { ids ->
                dsl.selectFrom(NOMENCLATURE).where(NOMENCLATURE.ID.`in`(ids)).fetch().associateBy { it.id!! }
            } ?: emptyMap()
            rows.map { l ->
                SupplierInvoiceLineDto(
                    id = l.id!!,
                    lineNo = l.lineNo,
                    supplierItemCode = l.supplierItemCode,
                    supplierItemName = l.supplierItemName,
                    nomenclatureId = l.nomenclatureId,
                    nomenclatureCode = l.nomenclatureId?.let { nomenclature[it]?.code },
                    nomenclatureName = l.nomenclatureId?.let { nomenclature[it]?.name },
                    purchaseOrderLineId = l.purchaseOrderLineId,
                    quantity = l.quantity.toPlainString(),
                    unit = l.unit,
                    unitPrice = l.unitPrice.toPlainString(),
                    vatRate = l.vatRate?.toPlainString(),
                    vatAmount = l.vatAmount.toPlainString(),
                    lineTotal = l.lineTotal.toPlainString()
                )
            }
        }
        return SupplierInvoiceDto(
            id = r.id!!,
            number = r.number,
            invoiceDate = r.invoiceDate.toString(),
            supplierId = r.supplierId,
            supplierName = supplierName,
            purchaseOrderId = r.purchaseOrderId,
            purchaseOrderNumber = orderNumber,
            documentId = r.documentId,
            documentNumber = documentNumber,
            fileName = latestFileVersion(r.documentId)?.fileName,
            supplierInn = r.supplierInn,
            supplierKpp = r.supplierKpp,
            currency = r.currency,
            pricesIncludeVat = r.pricesIncludeVat ?: false,
            netTotal = r.netTotal.toPlainString(),
            vatTotal = r.vatTotal.toPlainString(),
            grossTotal = r.grossTotal.toPlainString(),
            validUntil = r.validUntil?.toString(),
            expired = r.validUntil?.isBefore(LocalDate.now()) == true,
            paymentTerms = r.paymentTerms,
            prepaymentPct = r.prepaymentPct?.toPlainString(),
            leadTimeDays = r.leadTimeDays,
            leadTimeKind = r.leadTimeKind?.let { LeadTimeKind.valueOf(it) },
            deliveryTerms = r.deliveryTerms,
            paymentPurpose = r.paymentPurpose,
            supplierManager = r.supplierManager,
            note = r.note,
            status = SupplierInvoiceStatus.valueOf(r.status ?: "DRAFT"),
            acceptedDiscrepancyReason = r.acceptedDiscrepancyReason,
            checkedBy = r.checkedBy,
            checkedAt = r.checkedAt?.toString(),
            cancelReason = r.cancelReason,
            receiptId = receipt?.id,
            receiptNumber = receipt?.number,
            receiptStatus = receipt?.status?.let { ReceiptStatus.valueOf(it) },
            createdBy = r.createdBy,
            createdAt = r.createdAt?.toString(),
            version = r.version ?: 0,
            lines = lineDtos
        )
    }

    /**
     * Извлекает текст из PDF-счёта (Apache PDFBox) и разбирает реквизиты и позиции
     * с помощью [InvoiceTextParser], дополнительно подбирая поставщика по ИНН/КПП
     * (перенесено из supply2 InvoiceAutofillParser).
     */
    fun parsePdf(pdfBytes: ByteArray, currentUser: CurrentUser): SupplierInvoiceAutofillDto {
        requireManage(currentUser)
        if (pdfBytes.isEmpty()) {
            throw ValidationException("PDF-файл пуст", mapOf("file" to "empty"))
        }
        val extractedText = try {
            org.apache.pdfbox.Loader.loadPDF(pdfBytes).use { doc ->
                val stripper = org.apache.pdfbox.text.PDFTextStripper().apply {
                    sortByPosition = true
                }
                stripper.getText(doc)
            }
        } catch (e: Exception) {
            throw ValidationException("Не удалось прочитать PDF-файл: ${e.message ?: "повреждённый файл"}")
        }

        val parsed = InvoiceTextParser.parse(extractedText)
        val matchedSupplier = parsed.supplierInn?.let { inn ->
            val byInn = dsl.selectFrom(SUPPLIERS)
                .where(SUPPLIERS.INN.eq(inn))
                .fetch()
            when {
                byInn.isEmpty() -> null
                parsed.supplierKpp != null -> byInn.firstOrNull { it.kpp == parsed.supplierKpp } ?: byInn.first()
                else -> byInn.first()
            }
        }

        return SupplierInvoiceAutofillDto(
            number = parsed.number,
            invoiceDate = parsed.invoiceDate,
            validUntil = parsed.validUntil,
            supplierInn = parsed.supplierInn,
            supplierKpp = parsed.supplierKpp,
            suggestedSupplierId = matchedSupplier?.id,
            suggestedSupplierName = matchedSupplier?.name,
            paymentTerms = parsed.paymentTerms,
            prepaymentPct = parsed.prepaymentPct,
            leadTimeDays = parsed.leadTimeDays,
            leadTimeWorking = parsed.leadTimeWorking,
            deliveryTerms = parsed.deliveryTerms,
            supplierManager = parsed.supplierManager,
            pricesIncludeVat = parsed.pricesIncludeVat,
            extractedText = extractedText.take(12_000),
            lines = parsed.lines.map { line ->
                SupplierInvoiceAutofillLineDto(
                    lineNo = line.lineNo,
                    supplierItemCode = line.supplierItemCode,
                    supplierItemName = line.supplierItemName,
                    quantity = line.quantity,
                    unit = line.unit,
                    unitPrice = line.unitPrice,
                    vatRate = line.vatRate,
                    vatAmount = line.vatAmount,
                    lineTotal = line.lineTotal
                )
            }
        )
    }

    fun parseDocumentPdf(documentId: Long, currentUser: CurrentUser): SupplierInvoiceAutofillDto {
        requireManage(currentUser)
        val version = latestFileVersion(documentId)
            ?: throw NotFoundException("document_version", documentId)
        val storageKey = documentRepository.findFileStorageKey(version.fileId)
            ?: throw NotFoundException("document_file", documentId)

        val bytes = try {
            fileStorage.read(storageKey)
        } catch (_: NoSuchFileException) {
            throw NotFoundException("document_file", documentId)
        }
        return parsePdf(bytes, currentUser)
    }

    /**
     * Генерирует HTML-предпросмотр счёта поставщика для печати и просмотра в браузере
     * (перенесено из supply2 PreviewInvoiceUseCase).
     */
    fun previewHtml(id: Long, currentUser: CurrentUser): SupplierInvoicePreviewDto {
        val invoice = get(id, currentUser)
        val supplierName = dsl.select(SUPPLIERS.NAME)
            .from(SUPPLIERS)
            .where(SUPPLIERS.ID.eq(invoice.supplierId))
            .fetchOne(SUPPLIERS.NAME)
            ?: "Поставщик #${invoice.supplierId}"

        val statusLabel = when (invoice.status) {
            SupplierInvoiceStatus.DRAFT -> "Черновик"
            SupplierInvoiceStatus.CHECKED -> "Сверен"
            SupplierInvoiceStatus.CANCELLED -> "Отменён"
        }

        fun esc(v: String?): String = (v ?: "")
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

        val html = buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html lang=\"ru\"><head><meta charset=\"UTF-8\">")
            appendLine("<title>Счёт ${esc(invoice.number)}</title>")
            appendLine("<style>")
            appendLine("body{margin:0;padding:32px;background:#f7f3eb;color:#1e293b;font-family:'Segoe UI',Tahoma,sans-serif;}")
            appendLine(".sheet{max-width:960px;margin:0 auto;background:#fff;border:1px solid #e2e8f0;border-radius:12px;padding:28px;box-shadow:0 8px 24px rgba(15,23,42,.06);}")
            appendLine(".hdr{display:flex;justify-content:space-between;align-items:flex-start;gap:16px;border-bottom:2px solid #0f172a;padding-bottom:16px;margin-bottom:20px;}")
            appendLine("h1{margin:0;font-size:22px;} .badge{display:inline-block;padding:4px 10px;border-radius:999px;background:#e0f2fe;color:#0369a1;font-size:12px;font-weight:600;}")
            appendLine(".meta{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px 24px;margin-bottom:20px;font-size:14px;}")
            appendLine(".lbl{color:#64748b;font-size:12px;text-transform:uppercase;letter-spacing:.03em;}")
            appendLine("table{width:100%;border-collapse:collapse;font-size:14px;} th,td{border:1px solid #e2e8f0;padding:8px 10px;text-align:left;}")
            appendLine("th{background:#f8fafc;font-weight:600;} td.num,th.num{text-align:right;white-space:nowrap;}")
            appendLine(".totals{margin-top:16px;display:flex;justify-content:flex-end;} .totals table{width:auto;min-width:300px;}")
            appendLine("@media print{body{background:#fff;padding:0;}.sheet{border:none;box-shadow:none;}}")
            appendLine("</style></head><body><div class=\"sheet\">")
            appendLine("<div class=\"hdr\"><div><h1>Счёт поставщика № ${esc(invoice.number)}</h1>")
            appendLine("<div style=\"color:#475569;margin-top:4px;\">от ${esc(invoice.invoiceDate)}</div></div>")
            appendLine("<span class=\"badge\">$statusLabel</span></div>")
            appendLine("<div class=\"meta\">")
            appendLine("<div><div class=\"lbl\">Поставщик</div><div><strong>${esc(supplierName)}</strong></div></div>")
            appendLine("<div><div class=\"lbl\">ИНН / КПП</div><div>${esc(invoice.supplierInn ?: "—")} / ${esc(invoice.supplierKpp ?: "—")}</div></div>")
            invoice.paymentTerms?.let { appendLine("<div><div class=\"lbl\">Условия оплаты</div><div>${esc(it)}</div></div>") }
            invoice.deliveryTerms?.let { appendLine("<div><div class=\"lbl\">Условия поставки</div><div>${esc(it)}</div></div>") }
            invoice.validUntil?.let { appendLine("<div><div class=\"lbl\">Действителен до</div><div>${esc(it)}</div></div>") }
            invoice.supplierManager?.let { appendLine("<div><div class=\"lbl\">Менеджер</div><div>${esc(it)}</div></div>") }
            appendLine("</div>")
            appendLine("<table><thead><tr><th>№</th><th>Артикул</th><th>Наименование</th><th class=\"num\">Кол-во</th><th>Ед.</th><th class=\"num\">Цена</th><th class=\"num\">НДС</th><th class=\"num\">Сумма</th></tr></thead><tbody>")
            invoice.lines.forEach { line ->
                appendLine("<tr><td>${line.lineNo}</td><td>${esc(line.supplierItemCode)}</td><td>${esc(line.supplierItemName)}</td>")
                appendLine("<td class=\"num\">${esc(line.quantity)}</td><td>${esc(line.unit)}</td>")
                appendLine("<td class=\"num\">${esc(line.unitPrice)}</td><td class=\"num\">${esc(line.vatAmount)}</td><td class=\"num\">${esc(line.lineTotal)}</td></tr>")
            }
            appendLine("</tbody></table>")
            appendLine("<div class=\"totals\"><table>")
            appendLine("<tr><td>Итого без НДС</td><td class=\"num\">${esc(invoice.netTotal)} ${esc(invoice.currency)}</td></tr>")
            appendLine("<tr><td>НДС</td><td class=\"num\">${esc(invoice.vatTotal)} ${esc(invoice.currency)}</td></tr>")
            appendLine("<tr><th>Всего к оплате</th><th class=\"num\">${esc(invoice.grossTotal)} ${esc(invoice.currency)}</th></tr>")
            appendLine("</table></div>")
            invoice.note?.takeIf { it.isNotBlank() }?.let {
                appendLine("<p style=\"margin-top:16px;color:#475569;\"><strong>Примечание:</strong> ${esc(it)}</p>")
            }
            appendLine("</div></body></html>")
        }

        val safeNum = invoice.number.replace(Regex("""[^\w.-]+"""), "_")
        return SupplierInvoicePreviewDto(
            invoiceId = invoice.id,
            number = invoice.number,
            fileName = "invoice_preview_$safeNum.html",
            htmlContent = html
        )
    }

    // -----------------------------------------------------------------------
    // Права и разбор значений
    // -----------------------------------------------------------------------

    private fun canRead(currentUser: CurrentUser) =
        currentUser.hasPermission("invoices", "read") || currentUser.hasPermission("invoices", "manage")

    private fun requireRead(currentUser: CurrentUser) {
        if (!canRead(currentUser)) throw ForbiddenException("Missing permission invoices:read")
    }

    private fun requireManage(currentUser: CurrentUser) = currentUser.requirePermission("invoices", "manage")

    private fun date(value: String, field: String): LocalDate = try {
        LocalDate.parse(value.trim())
    } catch (e: DateTimeParseException) {
        throw ValidationException("Неверная дата, ожидается ГГГГ-ММ-ДД", mapOf(field to "invalid"))
    }

    private fun amount(value: String, field: String, scale: Int): BigDecimal {
        val parsed = value.trim().replace(" ", "").replace(',', '.').toBigDecimalOrNull()
            ?: throw ValidationException("Неверное число", mapOf(field to "invalid"))
        if (parsed.signum() < 0) throw ValidationException("Значение не может быть отрицательным", mapOf(field to "negative"))
        if (parsed.stripTrailingZeros().scale() > scale) {
            throw ValidationException("Слишком много знаков после запятой (не более $scale)", mapOf(field to "scale"))
        }
        return parsed.setScale(scale, RoundingMode.UNNECESSARY)
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private fun BigDecimal.money(): String = setScale(2, RoundingMode.HALF_UP).toPlainString()
    private fun BigDecimal.qty(): String = stripTrailingZeros().toPlainString()

    private fun businessDaysBetween(from: LocalDate, to: LocalDate): Int {
        var days = 0
        var d = from
        while (d.isBefore(to)) {
            d = d.plusDays(1)
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) days++
        }
        return days
    }

    companion object {
        /** Цена без НДС в счёте и в заказе сравнивается с точностью до копейки. */
        private val PRICE_TOLERANCE = BigDecimal("0.005")

        private val invoiceAllowedOrderStatuses = setOf("APPROVED", "SENT", "PARTIALLY_DELIVERED", "DELIVERED")
    }
}
