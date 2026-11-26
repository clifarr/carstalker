package com.carstalker.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.carstalker.app.data.CustomPid
import com.carstalker.app.ui.theme.LocalStalkerColors
import com.carstalker.core.obd.FormulaParser
import com.carstalker.core.obd.PidCatalog
import java.util.Locale

/**
 * Creates or edits a user-defined Mode 01 parameter.
 *
 * [presetPidHex] starts a new PID with the code fixed, used when the picker offers a code the
 * car advertises but the catalogue does not define: the user supplies the meaning and formula.
 */
@Composable
fun CustomPidDialog(
    initial: CustomPid?,
    presetPidHex: String? = null,
    onDismiss: () -> Unit,
    onSave: (CustomPid) -> Unit,
) {
    val stalker = LocalStalkerColors.current
    val pidFixed = initial == null && presetPidHex != null
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var shortName by remember { mutableStateOf(initial?.shortName.orEmpty()) }
    var pidHex by remember {
        mutableStateOf((initial?.pidHex ?: presetPidHex)?.uppercase(Locale.US).orEmpty())
    }
    var unit by remember { mutableStateOf(initial?.unit.orEmpty()) }
    var min by remember { mutableStateOf(initial?.min?.toString() ?: "0") }
    var max by remember { mutableStateOf(initial?.max?.toString() ?: "100") }
    var decimals by remember { mutableStateOf(initial?.decimals?.toString() ?: "1") }
    var formula by remember { mutableStateOf(initial?.formula.orEmpty()) }

    val pidValue = pidHex.toIntOrNull(16)
    val pidValid = pidValue != null && pidValue in 1..255
    val formulaValid = formula.isNotBlank() && FormulaParser.isValid(formula)
    val minValue = min.toDoubleOrNull()
    val maxValue = max.toDoubleOrNull()
    val rangeValid = minValue != null && maxValue != null && maxValue > minValue
    val decimalsValid = (decimals.toIntOrNull() ?: -1) in 0..3
    val unitValid = unit.length <= 12
    val collision = pidValue != null && pidValue in 1..255 &&
        PidCatalog.find(pidValue) != null &&
        initial?.pidHex?.toIntOrNull(16) != pidValue
    val canSave = name.isNotBlank() && pidValid && formulaValid && rangeValid && decimalsValid && unitValid && !collision

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New custom PID" else "Edit custom PID") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (pidFixed) {
                        "Your car advertises PID $pidHex but CarStalker has no definition for it. " +
                            "Name it and enter its formula to read it. Mode 01 only; A, B, C and D are " +
                            "the first four response bytes."
                    } else {
                        "Mode 01 custom PID only. A, B, C and D are the first four response bytes. Extended or header-specific requests are not supported."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = stalker.muted,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = shortName,
                    onValueChange = { shortName = it.take(12) },
                    label = { Text("Short name (tile)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pidHex,
                    onValueChange = { input ->
                        pidHex = input.uppercase(Locale.US).filter { it in '0'..'9' || it in 'A'..'F' }.take(2)
                    },
                    label = { Text("PID (hex)") },
                    singleLine = true,
                    enabled = !pidFixed,
                    isError = (pidHex.isNotEmpty() && !pidValid) || collision,
                    supportingText = {
                        when {
                            pidFixed -> Text("Advertised by this car")
                            pidHex.isNotEmpty() && !pidValid -> Text("Enter 01–FF")
                            collision -> Text("This PID is already defined by a standard parameter")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = unit,
                    onValueChange = { unit = it.take(12) },
                    label = { Text("Unit") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = formula,
                    onValueChange = { formula = it.take(80) },
                    label = { Text("Formula") },
                    singleLine = true,
                    isError = formula.isNotEmpty() && !formulaValid,
                    supportingText = {
                        Text(
                            if (formula.isNotEmpty() && !formulaValid) {
                                "Use A, B, C, D, numbers, + - * / % and parentheses. Division by zero or a non-finite result is rejected."
                            } else {
                                "Example: (A*256+B)/4"
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = min,
                        onValueChange = { min = it },
                        label = { Text("Min") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = min.isNotBlank() && minValue == null,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = max,
                        onValueChange = { max = it },
                        label = { Text("Max") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = max.isNotBlank() && (maxValue == null || !rangeValid),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = decimals,
                        onValueChange = { decimals = it.filter(Char::isDigit).take(1) },
                        label = { Text("Dec") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = decimals.isNotBlank() && !decimalsValid,
                        modifier = Modifier.width(74.dp),
                    )
                }
                if (!rangeValid && min.isNotBlank() && max.isNotBlank()) {
                    Text("Max must be greater than min.", style = MaterialTheme.typography.labelSmall, color = stalker.danger)
                }
                if (!decimalsValid && decimals.isNotBlank()) {
                    Text("Decimals must be 0–3.", style = MaterialTheme.typography.labelSmall, color = stalker.danger)
                }
                if (!unitValid) {
                    Text("Unit must be 12 characters or fewer.", style = MaterialTheme.typography.labelSmall, color = stalker.danger)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val id = pidValue ?: return@Button
                    onSave(
                        CustomPid(
                            pidHex = pidHex.uppercase(Locale.US).padStart(2, '0'),
                            name = name.trim(),
                            shortName = shortName.trim().ifBlank { name.trim().take(10) },
                            unit = unit.trim(),
                            min = min.toDoubleOrNull() ?: 0.0,
                            max = max.toDoubleOrNull() ?: 100.0,
                            decimals = decimals.toIntOrNull()?.coerceIn(0, 3) ?: 1,
                            formula = formula.trim(),
                            enabled = initial?.enabled ?: true,
                        ),
                    )
                },
                enabled = canSave,
                colors = ButtonDefaults.buttonColors(
                    containerColor = stalker.accent,
                    contentColor = Color.Black,
                ),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
