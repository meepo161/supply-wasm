package org.example.project.ui.production

import org.example.project.ui.bom.localizedName
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import org.example.project.ui.theme.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.example.project.dto.BomRevisionSummaryDto
import org.example.project.dto.ProductModelDto
import org.example.project.dto.ProductionOrderStatus
import org.example.project.dto.ProductionStageType
import org.example.project.dto.ProjectDto
import org.example.project.dto.UserDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductionStatusDropdown(
    selected: ProductionOrderStatus?,
    onSelect: (ProductionOrderStatus?) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Статус"
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected?.localizedName() ?: "Все",
            onValueChange = { },
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Все") },
                onClick = {
                    onSelect(null)
                    expanded = false
                }
            )
            ProductionOrderStatus.entries.forEach { status ->
                DropdownMenuItem(
                    text = { Text(status.localizedName()) },
                    onClick = {
                        onSelect(status)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDropdown(
    projects: List<ProjectDto>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Проект",
    allowEmpty: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = projects.firstOrNull { it.id == selectedId }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected?.let { "${it.number} · ${it.name}" } ?: if (allowEmpty) "Все" else "Не выбран",
            onValueChange = { },
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (allowEmpty) {
                DropdownMenuItem(
                    text = { Text("Все") },
                    onClick = {
                        onSelect(null)
                        expanded = false
                    }
                )
            }
            projects.forEach { project ->
                DropdownMenuItem(
                    text = { Text("${project.number} · ${project.name}") },
                    onClick = {
                        onSelect(project.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductModelDropdown(
    models: List<ProductModelDto>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Изделие"
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = models.firstOrNull { it.id == selectedId }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected?.let { "${it.code} · ${it.name}" } ?: "Не выбрано",
            onValueChange = { },
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = { Text("${model.code} · ${model.name}") },
                    onClick = {
                        onSelect(model.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BomRevisionDropdown(
    revisions: List<BomRevisionSummaryDto>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Ревизия BOM"
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = revisions.firstOrNull { it.id == selectedId }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected?.let { "${it.revisionNumber} · ${it.status.localizedName()}" } ?: "Не выбрана",
            onValueChange = { },
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (revisions.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("Нет утверждённых ревизий") },
                    onClick = { expanded = false }
                )
            }
            revisions.forEach { revision ->
                DropdownMenuItem(
                    text = { Text("${revision.revisionNumber} · ${revision.status.localizedName()}") },
                    onClick = {
                        onSelect(revision.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResponsibleDropdown(
    users: List<UserDto>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Ответственный"
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = users.firstOrNull { it.id == selectedId }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected?.fullName ?: "Не выбран",
            onValueChange = { },
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            users.forEach { user ->
                DropdownMenuItem(
                    text = { Text(user.fullName) },
                    onClick = {
                        onSelect(user.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StageTypeDropdown(
    selected: ProductionStageType?,
    onSelect: (ProductionStageType?) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Тип этапа"
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected?.localizedName() ?: "Выберите тип",
            onValueChange = { },
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ProductionStageType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type.localizedName()) },
                    onClick = {
                        onSelect(type)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PriorityDropdown(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Приоритет"
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(
        "LOW" to "Низкий",
        "NORMAL" to "Обычный",
        "HIGH" to "Высокий",
        "CRITICAL" to "Критический"
    )
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second ?: "Обычный",
            onValueChange = { },
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, title) ->
                DropdownMenuItem(
                    text = { Text(title) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    }
                )
            }
        }
    }
}
