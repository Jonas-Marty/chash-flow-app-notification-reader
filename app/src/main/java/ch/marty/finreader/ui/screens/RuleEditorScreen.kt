package ch.marty.finreader.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ch.marty.finreader.data.db.AccountCache
import ch.marty.finreader.data.db.CapturedNotification
import ch.marty.finreader.data.db.CategoryCache
import ch.marty.finreader.data.db.MonitoredApp
import ch.marty.finreader.data.db.NumberFormatStyle
import ch.marty.finreader.data.db.Rule
import ch.marty.finreader.data.db.TxTypeMode
import ch.marty.finreader.domain.AmountParser
import ch.marty.finreader.domain.MatchOutcome
import ch.marty.finreader.domain.RuleDrafter
import ch.marty.finreader.domain.RuleEngine
import ch.marty.finreader.domain.Template
import ch.marty.finreader.domain.toInput
import ch.marty.finreader.ui.MainViewModel
import ch.marty.finreader.util.CrashLog
import java.util.Locale
import java.util.UUID

@Composable
fun RuleEditorScreen(
    viewModel: MainViewModel,
    ruleId: String?,
    captureId: Long,
    onDone: () -> Unit,
) {
    val accounts by viewModel.accounts.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val monitoredApps by viewModel.monitoredApps.collectAsState()

    var rule by remember { mutableStateOf<Rule?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(ruleId, captureId, accounts) {
        if (rule != null) return@LaunchedEffect
        val fallbackAccount = accounts.firstOrNull()?.id.orEmpty()
        runCatching {
            when {
                ruleId != null -> viewModel.ruleById(ruleId)
                captureId > 0 -> viewModel.captureById(captureId)?.let { capture ->
                    RuleDrafter.draft(capture, externalSourceFor(capture.appLabel), fallbackAccount)
                }

                else -> Rule(
                    id = UUID.randomUUID().toString(),
                    name = "",
                    packageName = "",
                    textPattern = """(?<currency>CHF|EUR)\s*(?<amount>[\d'.,]+)""",
                    sourceAccountId = fallbackAccount,
                    externalSource = "",
                )
            }
        }.onSuccess { drafted ->
            if (drafted == null && ruleId == null && captureId > 0) {
                loadError = "That captured notification no longer exists."
            }
            rule = drafted
        }.onFailure { error ->
            CrashLog.record("Drafting a rule from capture $captureId", error)
            loadError = "Could not draft a rule: ${error.javaClass.simpleName}: ${error.message}"
        }
    }

    val current = rule
    when {
        loadError != null -> Text(
            loadError.orEmpty(),
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.error,
        )

        current == null -> Text("Loading…", modifier = Modifier.padding(16.dp))

        else -> RuleEditorContent(
            viewModel = viewModel,
            isNew = ruleId == null,
            current = current,
            onChange = { rule = it },
            accounts = accounts,
            categories = categories,
            monitoredApps = monitoredApps,
            onDone = onDone,
        )
    }
}

@Composable
private fun RuleEditorContent(
    viewModel: MainViewModel,
    isNew: Boolean,
    current: Rule,
    onChange: (Rule) -> Unit,
    accounts: List<AccountCache>,
    categories: List<CategoryCache>,
    monitoredApps: List<MonitoredApp>,
    onDone: () -> Unit,
) {
    var samples by remember { mutableStateOf<List<CapturedNotification>>(emptyList()) }
    var sampleIndex by remember { mutableStateOf(0) }

    LaunchedEffect(current.packageName) {
        samples = runCatching { viewModel.samplesFor(current.packageName) }.getOrDefault(emptyList())
        sampleIndex = 0
    }

    val sample = samples.getOrNull(sampleIndex)
    val outcome = sample?.let { runCatching { RuleEngine.apply(current, it.toInput()) }.getOrNull() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (isNew) "New rule" else "Edit rule",
                style = MaterialTheme.typography.titleLarge,
            )
            Row {
                TextButton(onClick = onDone) { Text("Cancel") }
                Button(
                    enabled = current.name.isNotBlank() &&
                        current.packageName.isNotBlank() &&
                        current.textPattern.isNotBlank() &&
                        current.sourceAccountId.isNotBlank(),
                    onClick = {
                        viewModel.saveRule(current)
                        onDone()
                    },
                ) { Text("Save") }
            }
        }

        Field("Name", current.name) { onChange(current.copy(name = it)) }

        AppPicker(current, monitoredApps, onChange)

        Field(
            "Source badge (external_source)",
            current.externalSource,
            help = "Shown as a badge next to the pending transaction in the web app.",
        ) { onChange(current.copy(externalSource = it)) }

        LabeledDropdown(
            label = "Account",
            options = accounts,
            selected = accounts.firstOrNull { it.id == current.sourceAccountId },
            optionLabel = { accountLabel(it) },
            onSelect = {
                onChange(current.copy(
                    sourceAccountId = it.id,
                    sourceAccountName = it.name,
                    sourceAccountCurrency = it.currencyCode,
                ))
            },
        )
        if (accounts.isEmpty()) {
            Text(
                "No accounts loaded yet — press \"Refresh accounts & categories\" in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        LabeledDropdown(
            label = "Category (optional)",
            options = listOf<CategoryCache?>(null) + categories,
            selected = categories.firstOrNull { it.id == current.categoryId },
            optionLabel = { it?.let { c -> c.groupName?.let { g -> "$g · ${c.name}" } ?: c.name } ?: "— none —" },
            onSelect = { onChange(current.copy(categoryId = it?.id, categoryName = it?.name)) },
        )

        HorizontalDivider()

        Field(
            "Match pattern",
            current.textPattern,
            monospace = true,
            help = "Regex with named groups, e.g. (?<amount>…). Matched against title + text.",
        ) { onChange(current.copy(textPattern = it)) }

        Field(
            "Title must contain (optional)",
            current.titlePattern.orEmpty(),
            monospace = true,
        ) { onChange(current.copy(titlePattern = it.ifBlank { null })) }

        Field(
            "Skip if this matches (optional)",
            current.excludePattern.orEmpty(),
            monospace = true,
            help = "e.g. a payment request rather than a completed payment.",
        ) { onChange(current.copy(excludePattern = it.ifBlank { null })) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Amount group", current.amountGroup, modifier = Modifier.weight(1f)) {
                onChange(current.copy(amountGroup = it))
            }
            Field("Merchant group", current.merchantGroup.orEmpty(), modifier = Modifier.weight(1f)) {
                onChange(current.copy(merchantGroup = it.ifBlank { null }))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Currency group", current.currencyGroup.orEmpty(), modifier = Modifier.weight(1f)) {
                onChange(current.copy(currencyGroup = it.ifBlank { null }))
            }
            Field("Default currency", current.defaultCurrency, modifier = Modifier.weight(1f)) {
                onChange(current.copy(defaultCurrency = it.uppercase(Locale.ROOT)))
            }
        }

        Text(
            "Groups available in the templates: " +
                (Template.namedGroupsOf(current.textPattern) + listOf("amount", "currency", "title", "text", "app", "date"))
                    .distinct().joinToString(", ") { "{$it}" },
            style = MaterialTheme.typography.labelSmall,
        )

        LabeledDropdown(
            label = "Number format",
            options = NumberFormatStyle.entries,
            selected = current.numberFormat,
            optionLabel = {
                when (it) {
                    NumberFormatStyle.AUTO -> "Auto"
                    NumberFormatStyle.SWISS -> "Swiss (1'234.50)"
                    NumberFormatStyle.EU -> "European (1.234,50)"
                }
            },
            onSelect = { onChange(current.copy(numberFormat = it)) },
        )

        LabeledDropdown(
            label = "Direction",
            options = TxTypeMode.entries,
            selected = current.txTypeMode,
            optionLabel = {
                when (it) {
                    TxTypeMode.EXPENSE -> "Always expense"
                    TxTypeMode.INCOME -> "Always income"
                    TxTypeMode.FROM_PATTERN -> "Income when a pattern matches"
                }
            },
            onSelect = { onChange(current.copy(txTypeMode = it)) },
        )

        if (current.txTypeMode == TxTypeMode.FROM_PATTERN) {
            Field(
                "Income pattern",
                current.incomePattern.orEmpty(),
                monospace = true,
                help = "e.g. erhalten|Gutschrift|received",
            ) { onChange(current.copy(incomePattern = it.ifBlank { null })) }
        }

        Field(
            "Description template",
            current.descriptionTemplate,
            help = "e.g. {merchant} — placeholders are filled from the groups above.",
        ) { onChange(current.copy(descriptionTemplate = it)) }

        Field(
            "Note template (optional)",
            current.noteTemplate.orEmpty(),
            help = "Useful for a second currency, e.g. Original: {origCurrency} {origAmount}",
        ) { onChange(current.copy(noteTemplate = it.ifBlank { null })) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Send automatically", style = MaterialTheme.typography.bodyLarge)
            Switch(checked = current.autoPost, onCheckedChange = { onChange(current.copy(autoPost = it)) })
        }

        HorizontalDivider()

        Text("Test against a captured notification", style = MaterialTheme.typography.titleMedium)
        if (samples.isEmpty()) {
            Text(
                "No captured notifications for this package yet.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            LabeledDropdown(
                label = "Sample",
                options = samples.indices.toList(),
                selected = sampleIndex,
                optionLabel = { index ->
                    val item = samples[index]
                    (item.title ?: item.text).orEmpty().take(50)
                },
                onSelect = { sampleIndex = it },
            )
            sample?.let {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        it.haystack,
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            PreviewResult(outcome, accounts.firstOrNull { it.id == current.sourceAccountId })
        }
    }
}

@Composable
private fun PreviewResult(outcome: MatchOutcome?, account: AccountCache?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (outcome) {
                null -> Text("—")
                is MatchOutcome.NoMatch -> Text(
                    "No match",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )

                is MatchOutcome.Ignored -> Text("Skipped: ${outcome.reason}")
                is MatchOutcome.Failed -> Text(
                    outcome.reason,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )

                is MatchOutcome.Matched -> {
                    val e = outcome.extraction
                    Text("Would post:", style = MaterialTheme.typography.labelLarge)
                    Text(
                        buildString {
                            appendLine("amount:      ${AmountParser.centsToPlainString(e.amountCents)} (${e.currency})")
                            appendLine("type:        ${e.type}")
                            appendLine("date:        ${e.occurredOn}")
                            appendLine("description: ${e.description}")
                            e.note?.let { appendLine("note:        $it") }
                            append("account:     ${account?.let { accountLabel(it) } ?: "—"}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
    help: String? = null,
    onChange: (String) -> Unit,
) {
    Column(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = !monospace,
            textStyle = if (monospace) {
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            modifier = Modifier.fillMaxWidth(),
        )
        help?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/**
 * The package name is an exact-match filter, not a pattern, so a typo here
 * disables the rule silently. Offering only real monitored apps removes the
 * possibility.
 */
@Composable
private fun AppPicker(
    current: Rule,
    monitoredApps: List<MonitoredApp>,
    onChange: (Rule) -> Unit,
) {
    val options = remember(monitoredApps, current.packageName) {
        val enabled = monitoredApps.filter { it.enabled }
        when {
            current.packageName.isBlank() -> enabled
            enabled.any { it.packageName == current.packageName } -> enabled
            // Keep a rule's own app selectable even after monitoring was turned
            // off for it, otherwise editing the rule would quietly reassign it.
            else -> enabled + (
                monitoredApps.firstOrNull { it.packageName == current.packageName }
                    ?: MonitoredApp(current.packageName, current.packageName, enabled = false)
                ).copy(enabled = false)
        }
    }
    val selected = options.firstOrNull { it.packageName == current.packageName }

    LabeledDropdown(
        label = "App",
        options = options,
        selected = selected,
        optionLabel = { it.appLabel.ifBlank { it.packageName } + if (it.enabled) "" else " (not monitored)" },
        onSelect = { onChange(current.copy(packageName = it.packageName)) },
    )
    when {
        current.packageName.isBlank() -> Text(
            if (options.isEmpty()) {
                "No apps enabled yet — turn one on under Apps first."
            } else {
                "Which app's notifications this rule reads."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )

        selected?.enabled == false -> Text(
            "${current.packageName} — notifications from this app are not being read, " +
                "so the rule will never fire. Enable it under Apps.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )

        else -> Text(
            current.packageName,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

private fun accountLabel(account: AccountCache): String =
    account.name + (account.currencyCode?.let { " ($it)" } ?: "")

private fun externalSourceFor(appLabel: String): String =
    appLabel.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "").take(20)
