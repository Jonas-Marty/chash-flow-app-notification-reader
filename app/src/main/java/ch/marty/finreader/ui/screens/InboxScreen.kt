package ch.marty.finreader.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ch.marty.finreader.data.db.CapturedNotification
import ch.marty.finreader.data.db.MatchState
import ch.marty.finreader.data.db.OutboxItem
import ch.marty.finreader.data.db.OutboxState
import ch.marty.finreader.domain.AmountParser
import ch.marty.finreader.ui.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun InboxScreen(viewModel: MainViewModel, onCreateRule: (Long) -> Unit) {
    val captured by viewModel.captured.collectAsState()
    val outbox by viewModel.outboxById.collectAsState()
    val access by viewModel.notificationAccess.collectAsState()
    val settings by viewModel.settings.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            StatusCard(
                notificationAccess = access,
                configured = settings.isConfigured,
                onGrant = { viewModel.refreshNotificationAccess() },
                viewModel = viewModel,
            )
        }

        if (captured.isEmpty()) {
            item {
                Text(
                    "Nothing captured yet. Enable the apps you want to read under Apps, " +
                        "then make a payment — the notification shows up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }

        items(captured, key = { it.id }) { capture ->
            CaptureCard(
                capture = capture,
                outboxItem = capture.outboxId?.let { outbox[it] },
                onCreateRule = { onCreateRule(capture.id) },
                onRematch = { viewModel.rematch(capture.id) },
                onSendNow = { id -> viewModel.sendNow(id) },
                onCancel = { id -> viewModel.cancel(id) },
                onDelete = { viewModel.deleteCapture(capture.id) },
            )
        }
    }
}

@Composable
private fun StatusCard(
    notificationAccess: Boolean,
    configured: Boolean,
    onGrant: () -> Unit,
    viewModel: MainViewModel,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val unsent by viewModel.unsentCount.collectAsState()
    val failed by viewModel.failedCount.collectAsState()

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Status", style = MaterialTheme.typography.titleMedium)
            Text(
                if (notificationAccess) "Notification access granted"
                else "Notification access NOT granted — nothing can be read",
                style = MaterialTheme.typography.bodyMedium,
                color = if (notificationAccess) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.error,
            )
            Text(
                if (configured) "Server configured" else "Server URL / token missing — see Settings",
                style = MaterialTheme.typography.bodyMedium,
                color = if (configured) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.error,
            )
            Text("$unsent waiting to send · $failed failed", style = MaterialTheme.typography.bodySmall)

            if (!notificationAccess) {
                TextButton(onClick = {
                    context.startActivity(viewModel.notificationAccessIntent())
                    onGrant()
                }) {
                    Text("Grant notification access")
                }
            }
        }
    }
}

@Composable
private fun CaptureCard(
    capture: CapturedNotification,
    outboxItem: OutboxItem?,
    onCreateRule: () -> Unit,
    onRematch: () -> Unit,
    onSendNow: (Long) -> Unit,
    onCancel: (Long) -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(capture.appLabel, style = MaterialTheme.typography.labelLarge)
                Text(timestamp(capture.postedAt), style = MaterialTheme.typography.labelSmall)
            }

            capture.title?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            capture.text?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip(stateLabel(capture, outboxItem))
                outboxItem?.let {
                    StatusChip("${it.currency} ${AmountParser.centsToPlainString(it.amountCents)}")
                }
            }

            capture.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            outboxItem?.lastError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            HorizontalDivider()

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (capture.matchState != MatchState.MATCHED) {
                    TextButton(onClick = onCreateRule) { Text("Create rule") }
                }
                // A rule written after the fact is the normal case while the
                // rule set is still being built up.
                if (capture.matchState != MatchState.MATCHED && capture.outboxId == null) {
                    TextButton(onClick = onRematch) { Text("Try rules again") }
                }
                outboxItem?.let { item ->
                    if (item.state in setOf(
                            OutboxState.HELD,
                            OutboxState.FAILED_PERMANENT,
                            OutboxState.FAILED_RETRY,
                            OutboxState.CANCELLED,
                        )
                    ) {
                        TextButton(onClick = { onSendNow(item.id) }) { Text("Send now") }
                    }
                    if (item.state in setOf(OutboxState.QUEUED, OutboxState.HELD, OutboxState.FAILED_RETRY)) {
                        TextButton(onClick = { onCancel(item.id) }) { Text("Cancel") }
                    }
                }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

/** A label, not a control — the states here are read-only. */
@Composable
private fun StatusChip(label: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

private fun stateLabel(capture: CapturedNotification, item: OutboxItem?): String = when {
    item != null -> when (item.state) {
        OutboxState.QUEUED -> "queued"
        OutboxState.HELD -> "waiting for tap"
        OutboxState.SENDING -> "sending"
        OutboxState.POSTED -> "posted"
        OutboxState.DEDUPED -> "already on server"
        OutboxState.FAILED_RETRY -> "retrying (${item.attempts})"
        OutboxState.FAILED_PERMANENT -> "failed"
        OutboxState.CANCELLED -> "cancelled"
    }

    else -> when (capture.matchState) {
        MatchState.UNMATCHED -> "no rule"
        MatchState.MATCHED -> "matched"
        MatchState.IGNORED -> "ignored"
        MatchState.DUPLICATE -> "duplicate"
        MatchState.ERROR -> "rule error"
    }
}

private fun timestamp(epochMillis: Long): String =
    SimpleDateFormat("dd.MM. HH:mm", Locale.getDefault()).format(Date(epochMillis))
