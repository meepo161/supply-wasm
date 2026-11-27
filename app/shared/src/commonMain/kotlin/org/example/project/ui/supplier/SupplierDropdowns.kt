package org.example.project.ui.supplier

import org.example.project.ui.demand.localizedName
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import org.example.project.ui.theme.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.example.project.dto.DemandHeaderDto
import org.example.project.dto.SupplierDto
import org.example.project.dto.SupplierOfferCompliance
import org.example.project.dto.SupplierVerificationStatus
import org.example.project.dto.UserDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupplierStatusDropdown(
    selected: SupplierVerificationStatus?,
    onSelect: (SupplierVerificationStatus?) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Статус проверки"
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selected?.localizedName() ?: "Все",
            onValueChange = { },
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Все") },
                onClick = { onSelect(null); expanded = false }
            )
            SupplierVerificationStatus.entries.forEach { status ->
                DropdownMenuItem(
                    text = { Text(status.localizedName()) },
                    onClick = { onSelect(status); expanded = false }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupplierDropdown(
    suppliers: List<SupplierDto>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Поставщик",
    onlyVerified: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    val options = if (onlyVerified) {
        suppliers.filter { it.verificationStatus == SupplierVerificationStatus.VERIFIED && !it.archived }
    } else {
        suppliers.filter { !it.archived }
    }
    val selected = options.firstOrNull { it.id == selectedId }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selected?.let { "${it.number} · ${it.name}" } ?: "Не выбран",
            onValueChange = { },
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (options.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("Нет проверенных поставщиков") },
                    onClick = { expanded = false }
                )
            }
            options.forEach { supplier ->
                DropdownMenuItem(
                    text = { Text("${supplier.number} · ${supplier.name}") },
                    onClick = { onSelect(supplier.id); expanded = false }
                )
            }
        }
    }
}

/** Подпись потребности в списках выбора: номер, статус и что именно нужно (номенклатура). */
private fun DemandHeaderDto.pickerLabel(): String = buildString {
    append(number)
    append(" · ")
    append(status.localizedName())
    itemsSummary?.let { append(" · ").append(it) }
    quantitySummary?.let { append(" · ").append(it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemandFilterDropdown(
    demands: List<DemandHeaderDto>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Потребность",
    allowAll: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = demands.firstOrNull { it.id == selectedId }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selected?.pickerLabel() ?: if (allowAll) "Все потребности" else "Не выбрана",
            onValueChange = { },
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (allowAll) {
                DropdownMenuItem(
                    text = { Text("Все потребности") },
                    onClick = { onSelect(null); expanded = false }
                )
            }
            demands.forEach { demand ->
                DropdownMenuItem(
                    text = { Text(demand.pickerLabel()) },
                    onClick = { onSelect(demand.id); expanded = false }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComplianceDropdown(
    selected: SupplierOfferCompliance,
    onSelect: (SupplierOfferCompliance) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Соответствие ТЗ"
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selected.localizedName(),
            onValueChange = { },
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SupplierOfferCompliance.entries.forEach { value ->
                DropdownMenuItem(
                    text = { Text(value.localizedName()) },
                    onClick = { onSelect(value); expanded = false }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResponsibleUserDropdown(
    users: List<UserDto>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Ответственный"
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = users.firstOrNull { it.id == selectedId }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selected?.fullName ?: "Не выбран",
            onValueChange = { },
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            users.forEach { user ->
                DropdownMenuItem(
                    text = { Text(user.fullName) },
                    onClick = { onSelect(user.id); expanded = false }
                )
            }
        }
    }
}
