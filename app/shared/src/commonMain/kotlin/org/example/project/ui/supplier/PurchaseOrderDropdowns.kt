package org.example.project.ui.supplier

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.example.project.dto.PurchaseOrderStatus
import org.example.project.dto.SupplierOfferDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchaseOrderStatusFilterDropdown(
    selected: PurchaseOrderStatus?,
    onSelect: (PurchaseOrderStatus?) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Статус заказа"
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
            PurchaseOrderStatus.entries.forEach { status ->
                DropdownMenuItem(
                    text = { Text(status.localizedName()) },
                    onClick = { onSelect(status); expanded = false }
                )
            }
        }
    }
}

/** Выбор основания заказа: только предложения в статусе «выбрано». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectedOfferDropdown(
    offers: List<SupplierOfferDto>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Предложение"
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = offers.firstOrNull { it.id == selectedId }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selected?.let { "${it.number} — ${it.supplierName ?: "поставщик #${it.supplierId}"}" }
                ?: if (offers.isEmpty()) "Нет выбранных предложений" else "Не выбрано",
            onValueChange = { },
            readOnly = true,
            enabled = offers.isNotEmpty(),
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            offers.forEach { offer ->
                DropdownMenuItem(
                    text = {
                        Text("${offer.number} — ${offer.supplierName ?: "поставщик #${offer.supplierId}"}")
                    },
                    onClick = { onSelect(offer.id); expanded = false }
                )
            }
        }
    }
}

/** Ответственный за заказ — из реального списка пользователей. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderResponsibleDropdown(
    users: List<org.example.project.dto.UserDto>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = users.firstOrNull { it.id == selectedId }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selected?.fullName ?: "Не выбран",
            onValueChange = { },
            readOnly = true,
            label = { Text("Ответственный") },
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
