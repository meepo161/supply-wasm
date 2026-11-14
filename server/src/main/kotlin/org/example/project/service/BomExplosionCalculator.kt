package org.example.project.service

import org.example.project.exception.ValidationException
import org.example.project.repository.BomRepository
import java.math.BigDecimal
import java.math.RoundingMode

private val HUNDRED = BigDecimal("100")

class BomExplosionCalculator(private val bomRepository: BomRepository) {

    data class ExplosionLine(
        val lineId: Long,
        val parentLineId: Long?,
        val level: Int,
        val nomenclatureId: Long,
        val unit: String,
        val quantityPerParent: BigDecimal,
        val parentQuantity: BigDecimal,
        val requiredQuantity: BigDecimal,
        val consumptionRate: BigDecimal?,
        val lossPct: BigDecimal
    )

    data class ExplosionResult(
        val lines: List<ExplosionLine>,
        val totalRequired: Map<Long, BigDecimal>
    )

    fun validate(revisionId: Long) {
        val lines = bomRepository.listBomLinesByRevision(revisionId)
        validateNoCycles(lines)
        if (lines.any { it.lossPct.compareTo(HUNDRED) == 0 }) {
            throw ValidationException("loss_pct cannot be 100%", mapOf("lossPct" to "forbidden_100"))
        }
    }

    fun explode(revisionId: Long, releaseQuantity: BigDecimal): ExplosionResult {
        val lines = bomRepository.listBomLinesByRevision(revisionId)
        validateNoCycles(lines)
        if (lines.any { it.lossPct.compareTo(HUNDRED) == 0 }) {
            throw ValidationException("loss_pct cannot be 100%", mapOf("lossPct" to "forbidden_100"))
        }

        val roots = buildForest(lines)
        val resultLines = mutableListOf<ExplosionLine>()
        val totals = mutableMapOf<Long, BigDecimal>()

        for (root in roots) {
            explodeNode(root, releaseQuantity, resultLines, totals)
        }
        return ExplosionResult(resultLines, totals)
    }

    private fun validateNoCycles(lines: List<org.example.project.generated.jooq.tables.records.BomLinesRecord>) {
        val byId = lines.associateBy { it.id }
        for (line in lines) {
            val visited = mutableSetOf<Long>()
            var current: Long? = line.parentLineId
            while (current != null) {
                if (!visited.add(current)) {
                    throw ValidationException("Cycle detected in BOM hierarchy", mapOf("lines" to "cycle"))
                }
                current = byId[current]?.parentLineId
            }
        }

        val children = lines
            .filter { it.substituteOfLineId == null }
            .groupBy({ it.parentNomenclatureId }, { it.childNomenclatureId })
        val completed = mutableSetOf<Long>()

        fun visit(nomenclatureId: Long, path: MutableSet<Long>) {
            if (nomenclatureId in completed) return
            if (!path.add(nomenclatureId)) {
                throw ValidationException("Cycle detected in BOM nomenclature", mapOf("lines" to "cycle"))
            }
            children[nomenclatureId].orEmpty().forEach { visit(it, path) }
            path.remove(nomenclatureId)
            completed.add(nomenclatureId)
        }

        val nomenclatureIds = children.keys + children.values.flatten().toSet()
        nomenclatureIds.forEach { visit(it, mutableSetOf()) }
    }

    private fun buildForest(
        lines: List<org.example.project.generated.jooq.tables.records.BomLinesRecord>
    ): List<BomRepository.BomLineNode> {
        val nodes = lines.associate { it.id to BomRepository.BomLineNode(it) }
        val roots = mutableListOf<BomRepository.BomLineNode>()
        for (line in lines) {
            val node = nodes[line.id]!!
            val parentId = line.parentLineId
            if (parentId == null) {
                roots.add(node)
            } else {
                nodes[parentId]?.children?.add(node)
            }
        }
        return roots
    }

    private fun explodeNode(
        node: BomRepository.BomLineNode,
        parentQty: BigDecimal,
        lines: MutableList<ExplosionLine>,
        totals: MutableMap<Long, BigDecimal>
    ) {
        val line = node.line
        if (line.substituteOfLineId != null) return
        val factor = line.quantity.multiply(line.consumptionRate ?: BigDecimal.ONE)
        val loss = line.lossPct
        if (loss.compareTo(HUNDRED) == 0) {
            throw ValidationException("loss_pct cannot be 100%", mapOf("lossPct" to "forbidden_100"))
        }
        val required = parentQty
            .multiply(factor)
            .multiply(HUNDRED)
            .divide(HUNDRED.subtract(loss), 10, RoundingMode.HALF_UP)
            .stripTrailingZeros()
        lines.add(
            ExplosionLine(
                lineId = line.id,
                parentLineId = line.parentLineId,
                level = line.level,
                nomenclatureId = line.childNomenclatureId,
                unit = line.unit,
                quantityPerParent = factor,
                parentQuantity = parentQty,
                requiredQuantity = required,
                consumptionRate = line.consumptionRate,
                lossPct = line.lossPct
            )
        )
        totals.merge(line.childNomenclatureId, required, BigDecimal::add)
        for (child in node.children) {
            explodeNode(child, required, lines, totals)
        }
    }
}
