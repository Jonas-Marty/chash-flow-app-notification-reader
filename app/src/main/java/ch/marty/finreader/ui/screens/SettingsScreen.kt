package ch.marty.finreader.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ch.marty.finreader.ui.MainViewModel

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsState()
    val busy by viewModel.busy.collectAsState()

    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var token by remember { mutableStateOf(settings.apiToken) }
    LaunchedEffect(settings.baseUrl, settings.apiToken) {
        if (baseUrl.isBlank()) baseUrl = settings.baseUrl
        if (token.isBlank()) token = settings.apiToken
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportTo) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importFrom) }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Web app", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL") },
            placeholder = { Text("https://cash-flow.example.ch") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("API token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Create the token in the web app under Settings → API tokens.",
            style = MaterialTheme.typography.labelSmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                viewModel.saveSettings { copy(baseUrl = baseUrl, apiToken = token) }
                viewModel.testConnection()
            }) { Text("Save & test") }
            OutlinedButton(onClick = { viewModel.syncCatalog() }) { Text("Refresh accounts & categories") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

        HorizontalDivider()
        Text("Behaviour", style = MaterialTheme.typography.titleMedium)

        SwitchRow(
            title = "Send automatically",
            subtitle = "Off: every matched transaction waits for a tap on its notification.",
            checked = settings.autoPostEnabled,
        ) { viewModel.saveSettings { copy(autoPostEnabled = it) } }

        SwitchRow(
            title = "Show a notification for each capture",
            subtitle = "Also provides the undo window before sending.",
            checked = settings.feedbackNotifications,
        ) { viewModel.saveSettings { copy(feedbackNotifications = it) } }

        NumberRow(
            label = "Undo window (seconds)",
            value = settings.undoWindowSeconds,
            enabled = settings.feedbackNotifications,
        ) { viewModel.saveSettings { copy(undoWindowSeconds = it) } }

        NumberRow(label = "Keep notifications for (days)", value = settings.retentionDays) {
            viewModel.saveSettings { copy(retentionDays = it) }
        }

        HorizontalDivider()
        Text("Backup", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { exportLauncher.launch("finreader-rules.json") }) {
                Text("Export rules")
            }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) {
                Text("Import rules")
            }
        }
        Text(
            "The export contains your rules and the list of monitored apps — not the API token.",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.labelSmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberRow(label: String, value: Int, enabled: Boolean = true, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it.filter(Char::isDigit)
            text.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
