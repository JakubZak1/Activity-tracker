package pl.edu.activitytracker.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import pl.edu.activitytracker.storage.SettingsUiState
import java.util.Locale

@Composable
fun SettingsScreen(
    paddingValues: PaddingValues,
    settings: SettingsUiState,
    onWeightChanged: (Double) -> Unit,
    onDeviceNameChanged: (String) -> Unit,
    onUseMockChanged: (Boolean) -> Unit,
    onResetSession: () -> Unit,
    onConnect: () -> Unit,
) {
    var weightText by remember { mutableStateOf(settings.weightKg.toString()) }
    var deviceName by remember { mutableStateOf(settings.deviceName) }

    LaunchedEffect(settings.weightKg) {
        val formatted = String.format(Locale.US, "%.1f", settings.weightKg)
        if (weightText != formatted) {
            weightText = formatted
        }
    }

    LaunchedEffect(settings.deviceName) {
        if (deviceName != settings.deviceName) {
            deviceName = settings.deviceName
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                OutlinedTextField(
                    value = weightText,
                    onValueChange = { value ->
                        weightText = value
                        value.replace(',', '.').toDoubleOrNull()?.let(onWeightChanged)
                    },
                    label = { Text("Weight kg") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = { value ->
                        deviceName = value
                        onDeviceNameChanged(value)
                    },
                    label = { Text("Device name filter") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Mock data source", style = MaterialTheme.typography.titleMedium)
                    }
                    Switch(
                        checked = settings.useMockSource,
                        onCheckedChange = onUseMockChanged,
                        enabled = false,
                    )
                }

                Button(onClick = onConnect) {
                    Icon(Icons.Default.Bluetooth, contentDescription = null)
                    Text("Connect mock")
                }
            }
        }

        OutlinedButton(onClick = onResetSession) {
            Icon(Icons.Default.Delete, contentDescription = null)
            Text("Reset session")
        }
    }
}
