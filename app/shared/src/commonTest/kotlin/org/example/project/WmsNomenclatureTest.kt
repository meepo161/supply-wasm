package org.example.project

import kotlin.test.Test
import kotlin.test.assertEquals
import org.example.project.dto.NomenclatureBarcodeDto
import org.example.project.dto.NomenclatureDto
import org.example.project.dto.NomenclatureType
import org.example.project.ui.wms.filterNomenclature

class WmsNomenclatureTest {

    private val items = listOf(
        item(1, "MAT-001", "Стальной лист", "ГОСТ 19903", "4607025390017"),
        item(2, "BRG-002", "Подшипник", "Для узла вращения", "4607025390024")
    )

    @Test
    fun `blank query keeps all items`() {
        assertEquals(items, filterNomenclature(items, "  "))
    }

    @Test
    fun `search matches code name description and barcode ignoring case`() {
        assertEquals(listOf(items[0]), filterNomenclature(items, "mat-001"))
        assertEquals(listOf(items[1]), filterNomenclature(items, "ПОДШИПНИК"))
        assertEquals(listOf(items[0]), filterNomenclature(items, "19903"))
        assertEquals(listOf(items[1]), filterNomenclature(items, "390024"))
    }

    @Test
    fun `unknown query returns empty list`() {
        assertEquals(emptyList(), filterNomenclature(items, "отсутствует"))
    }

    private fun item(id: Long, code: String, name: String, description: String, barcode: String) =
        NomenclatureDto(
            id = id,
            code = code,
            name = name,
            description = description,
            type = NomenclatureType.MATERIAL,
            unit = "шт",
            createdBy = 1,
            barcodes = listOf(NomenclatureBarcodeDto(id, barcode))
        )
}
