package org.example.project.ui.demand

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.dto.BomRevisionSummaryDto
import org.example.project.dto.DemandPriority
import org.example.project.dto.DemandSourceType
import org.example.project.dto.NomenclatureDto
import org.example.project.dto.ProductModelDto
import org.example.project.dto.ProjectDto
import org.example.project.ui.bom.localizedName
import org.example.project.ui.components.SearchableDropdown

@Composable
fun DemandSourceTypeDropdown(
    selected: DemandSourceType,
    onSelect: (DemandSourceType) -> Unit,
    modifier: Modifier = Modifier
) {
    SearchableDropdown(
        items = DemandSourceType.entries,
        selected = selected,
        onSelect = onSelect,
        label = "Основание",
        itemText = { it.localizedName() },
        modifier = modifier
    )
}

@Composable
fun DemandPriorityDropdown(
    selected: DemandPriority,
    onSelect: (DemandPriority) -> Unit,
    modifier: Modifier = Modifier
) {
    SearchableDropdown(
        items = DemandPriority.entries,
        selected = selected,
        onSelect = onSelect,
        label = "Приоритет",
        itemText = { it.localizedName() },
        modifier = modifier
    )
}

@Composable
fun ProjectDropdown(
    projects: List<ProjectDto>,
    selected: ProjectDto?,
    onSelect: (ProjectDto) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Проект"
) {
    SearchableDropdown(
        items = projects,
        selected = selected,
        onSelect = onSelect,
        label = label,
        itemText = { "${it.number} — ${it.name}" },
        modifier = modifier,
        emptyText = "Нет доступных проектов"
    )
}

@Composable
fun ProductModelDropdown(
    models: List<ProductModelDto>,
    selected: ProductModelDto?,
    onSelect: (ProductModelDto) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Модель изделия"
) {
    SearchableDropdown(
        items = models,
        selected = selected,
        onSelect = onSelect,
        label = label,
        itemText = { "${it.code} — ${it.name}" },
        modifier = modifier,
        emptyText = "Нет доступных моделей"
    )
}

@Composable
fun BomRevisionDropdown(
    revisions: List<BomRevisionSummaryDto>,
    selected: BomRevisionSummaryDto?,
    onSelect: (BomRevisionSummaryDto) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Ревизия BOM"
) {
    SearchableDropdown(
        items = revisions,
        selected = selected,
        onSelect = onSelect,
        label = label,
        itemText = { "${it.revisionNumber} (${it.status.localizedName()})" },
        modifier = modifier,
        emptyText = "Нет утверждённых или активных ревизий"
    )
}

@Composable
fun DemandNomenclatureDropdown(
    items: List<NomenclatureDto>,
    selected: NomenclatureDto?,
    onSelect: (NomenclatureDto) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Номенклатура"
) {
    SearchableDropdown(
        items = items.filterNot { it.archived },
        selected = selected,
        onSelect = onSelect,
        label = label,
        itemText = { "${it.code} — ${it.name}" },
        modifier = modifier,
        emptyText = "Нет доступной номенклатуры"
    )
}
