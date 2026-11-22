package org.example.project

import org.example.project.ui.supplier.InvoiceForm
import org.example.project.ui.supplier.InvoiceLineForm
import org.example.project.ui.supplier.SupplierInvoiceViewModel.Companion.money
import org.example.project.ui.supplier.SupplierInvoiceViewModel.Companion.recalc
import org.example.project.ui.supplier.SupplierInvoiceViewModel.Companion.withTotals
import kotlin.test.Test
import kotlin.test.assertEquals

/** Подсказки сумм в форме счёта поставщика (D-61) на примере счёта ОАО «СЗТТ» № 219658. */
class SupplierInvoiceFormTest {

    private fun line(no: Int, qty: String, price: String, rate: String = "20") = InvoiceLineForm(
        lineNo = no, supplierItemName = "Трансформатор $no", nomenclatureId = no.toLong(), purchaseOrderLineId = null,
        nomenclatureLabel = "", quantity = qty, unit = "шт", unitPrice = price, vatRate = rate
    )

    @Test
    fun sampleInvoiceTotalsMatchThePrintedDocument() {
        val lines = listOf(line(1, "3", "2220"), line(2, "3", "2270"), line(3, "3", "2180"), line(4, "1", "3350"))
            .map { recalc(it, pricesIncludeVat = false) }
        assertEquals("6660.00", lines[0].lineTotal)
        assertEquals("1332.00", lines[0].vatAmount)
        assertEquals("670.00", lines[3].vatAmount)
        val form = withTotals(InvoiceForm(supplierId = 1, supplierName = "ОАО «СЗТТ»", purchaseOrderId = null, purchaseOrderNumber = null, lines = lines))
        assertEquals("23360.00", form.netTotal)
        assertEquals("4672.00", form.vatTotal)
        assertEquals("28032.00", form.grossTotal)
    }

    @Test
    fun pricesIncludingVatExtractTaxFromTheAmount() {
        val l = recalc(line(1, "1", "120"), pricesIncludeVat = true)
        assertEquals("120.00", l.lineTotal)
        assertEquals("20.00", l.vatAmount)
        val form = withTotals(InvoiceForm(supplierId = 1, supplierName = "", purchaseOrderId = null, purchaseOrderNumber = null, pricesIncludeVat = true, lines = listOf(l)))
        assertEquals("100.00", form.netTotal)
        assertEquals("120.00", form.grossTotal)
    }

    @Test
    fun withoutVatAndCommaDecimalsAreHandled() {
        val l = recalc(line(1, "2,5", "10,10", rate = ""), pricesIncludeVat = false)
        assertEquals("25.25", l.lineTotal)
        assertEquals("0.00", l.vatAmount)
        assertEquals("0.05", money(0.049))
    }
}
