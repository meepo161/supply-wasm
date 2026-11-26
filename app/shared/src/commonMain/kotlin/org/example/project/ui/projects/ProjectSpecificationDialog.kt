package org.example.project.ui.projects

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.example.project.data.ApiResult
import org.example.project.data.ProjectRepository
import org.example.project.data.WmsRepository
import org.example.project.dto.DemandLineInput
import org.example.project.dto.HandoffProjectSpecRequest
import org.example.project.dto.NomenclatureDto
import org.example.project.dto.ProjectDto
import org.example.project.ui.demand.DemandNomenclatureDropdown
import org.example.project.ui.theme.ErpDialog
import org.example.project.ui.theme.TextButton
import org.koin.compose.koinInject

private data class SpecificationLine(val item: NomenclatureDto? = null, val quantity: String = "")

@Composable
fun ProjectSpecificationDialog(project: ProjectDto, onDismiss: () -> Unit, onSent: () -> Unit) {
    val wms: WmsRepository = koinInject()
    val projects: ProjectRepository = koinInject()
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<NomenclatureDto>>(emptyList()) }
    var lines by remember { mutableStateOf(listOf(SpecificationLine())) }
    var error by remember { mutableStateOf<org.example.project.dto.ApiError?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("project-spec-${project.id}-${kotlin.random.Random.nextLong().toString(16)}") }

    LaunchedEffect(project.id) {
        when (val result = wms.listNomenclature()) {
            is ApiResult.Success -> items = result.data
            is ApiResult.Error -> error = result.error
        }
        loading = false
    }
    fun updateLines(updated: List<SpecificationLine>) {
        lines = updated
        key = "project-spec-${project.id}-${kotlin.random.Random.nextLong().toString(16)}"
    }
    ErpDialog(
        title = "Спецификация проекта ${project.number}",
        subtitle = "Добавьте позиции и количество. После передачи снабжение увидит потребность.",
        confirmText = if (busy) "Передача…" else "Передать в снабжение",
        confirmEnabled = !loading && !busy,
        onDismiss = { if (!busy) onDismiss() },
        onConfirm = {
            if (lines.any { it.item == null || (it.quantity.replace(',', '.').toDoubleOrNull() ?: 0.0) <= 0.0 }) {
                error = org.example.project.dto.ApiError("VALIDATION_ERROR", "Выберите номенклатуру и укажите положительное количество для каждой строки.")
            } else {
                busy = true
                val request = HandoffProjectSpecRequest(
                    items = lines.mapIndexed { index, line ->
                        DemandLineInput(index + 1, line.item!!.id, line.quantity.replace(',', '.'), line.item.unit)
                    },
                    idempotencyKey = key
                )
                scope.launch {
                    when (val result = projects.handoffSpecification(project.id, request)) {
                        is ApiResult.Success -> onSent()
                        is ApiResult.Error -> { error = result.error; busy = false }
                    }
                }
            }
        }
    ) {
        error?.let { ErrorMessageCard(error = it) }
        if (loading) Text("Загрузка номенклатуры…")
        lines.forEachIndexed { index, line ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Позиция ${index + 1}", style = MaterialTheme.typography.titleSmall)
                DemandNomenclatureDropdown(items, line.item, { item ->
                    updateLines(lines.toMutableList().also { it[index] = line.copy(item = item) })
                }, Modifier.fillMaxWidth(), "Номенклатура *")
                OutlinedTextField(
                    value = line.quantity,
                    onValueChange = { value -> updateLines(lines.toMutableList().also { it[index] = line.copy(quantity = value) }) },
                    label = { Text("Количество * ${line.item?.unit.orEmpty()}") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                if (lines.size > 1) TextButton(onClick = { updateLines(lines.filterIndexed { i, _ -> i != index }) }) { Text("Удалить позицию") }
            }
        }
        TextButton(onClick = { updateLines(lines + SpecificationLine()) }) { Text("Добавить позицию") }
    }
}
