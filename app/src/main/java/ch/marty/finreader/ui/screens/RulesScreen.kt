package ch.marty.finreader.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ch.marty.finreader.ui.MainViewModel

@Composable
fun RulesScreen(viewModel: MainViewModel, onEdit: (String) -> Unit, onCreate: () -> Unit) {
    val rules by viewModel.rules.collectAsState()

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onCreate) {
                Icon(Icons.Filled.Add, contentDescription = "New rule")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (rules.isEmpty()) {
                item {
                    Text(
                        "No rules yet. Capture a few notifications first, then use " +
                            "\"Create rule\" on one of them in the Inbox — the pattern is pre-filled from the sample.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(rules, key = { it.id }) { rule ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onEdit(rule.id) },
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(rule.name, style = MaterialTheme.typography.titleMedium)
                            Switch(
                                checked = rule.enabled,
                                onCheckedChange = { viewModel.toggleRule(rule, it) },
                            )
                        }
                        Text(rule.packageName, style = MaterialTheme.typography.labelSmall)
                        Text(
                            rule.textPattern,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            "→ ${rule.sourceAccountName ?: rule.sourceAccountId}" +
                                (rule.categoryName?.let { " · $it" } ?: "") +
                                if (rule.autoPost) "" else " · manual",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Row {
                            TextButton(onClick = { onEdit(rule.id) }) { Text("Edit") }
                            TextButton(onClick = { viewModel.deleteRule(rule.id) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}
