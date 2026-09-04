package ch.marty.finreader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import ch.marty.finreader.data.CaptureProcessor
import ch.marty.finreader.data.LocationCapture
import ch.marty.finreader.data.api.ApiResult
import ch.marty.finreader.data.api.FinanceApi
import ch.marty.finreader.data.db.AccountCache
import ch.marty.finreader.data.db.AppDatabase
import ch.marty.finreader.data.db.CapturedNotification
import ch.marty.finreader.data.db.CategoryCache
import ch.marty.finreader.data.api.PendingTransactionDto
import ch.marty.finreader.data.api.PendingTransactionPayload
import ch.marty.finreader.data.db.MatchState
import ch.marty.finreader.data.db.OutboxItem
import ch.marty.finreader.data.db.ServerStatus
import ch.marty.finreader.data.db.MonitoredApp
import ch.marty.finreader.data.db.OutboxState
import ch.marty.finreader.data.db.Rule
import ch.marty.finreader.data.prefs.SettingsStore
import ch.marty.finreader.work.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class InstalledApp(
    val packageName: String,
    val label: String,
    val monitored: Boolean,
)

/** Outcome of [Repository.rerun], phrased for the inbox message. */
sealed interface RerunResult {
    /** The rules ran; [state] is what the capture ended up as, null if it vanished. */
    data class Done(val state: MatchState?) : RerunResult

    data class Refused(val reason: String) : RerunResult
}

@Serializable
data class RuleExport(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val rules: List<Rule> = emptyList(),
    val monitoredPackages: List<String> = emptyList(),
)

class Repository(
    private val context: Context,
    val db: AppDatabase,
    private val settings: SettingsStore,
    private val api: FinanceApi,
    private val captureProcessor: CaptureProcessor,
    private val locationCapture: LocationCapture,
) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun observeSettings() = settings.observe()
    fun observeCaptured() = db.captured().observeRecent()
    fun observeRules() = db.rules().observeAll()
    fun observeMonitoredApps() = db.monitoredApps().observeAll()
    fun observeAccounts() = db.cache().observeAccounts()
    fun observeCategories() = db.cache().observeCategories()
    fun observeOutbox() = db.outbox().observeRecent()
    fun observeUnsentCount() = db.outbox().countUnsent()
    fun observeFailedCount() = db.outbox().countFailed()

    fun updateSettings(block: ch.marty.finreader.data.prefs.Settings.() -> ch.marty.finreader.data.prefs.Settings) =
        settings.update(block)

    fun isNotificationAccessGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun notificationAccessIntent(): Intent =
        Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")

    /**
     * Whether the app may *post* notifications — a different grant from reading
     * them. Without it the undo window still runs, but silently: nothing
     * appears and there is nothing to tap.
     */
    fun canPostNotifications(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** The per-app notification screen, the only route back after a denial. */
    fun notificationSettingsIntent(): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun hasLocationPermission(): Boolean = locationCapture.isPermitted()

    /**
     * Both the listener and the outbox worker read location from the
     * background, so the foreground grant alone gets us nothing. Android 11+
     * refuses to hand this out from a dialog — only from the app's own
     * settings page, which is what [appDetailsIntent] opens.
     */
    fun hasBackgroundLocationPermission(): Boolean = locationCapture.hasBackgroundPermission()

    fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))

    suspend fun installedApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val monitored = db.monitoredApps().enabled().map { it.packageName }.toSet()
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launcherIntent, 0)
            .asSequence()
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .map {
                InstalledApp(
                    packageName = it.packageName,
                    label = pm.getApplicationLabel(it).toString(),
                    monitored = it.packageName in monitored,
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    suspend fun setMonitored(packageName: String, label: String, enabled: Boolean) {
        db.monitoredApps().upsert(MonitoredApp(packageName, label, enabled))
    }

    suspend fun saveRule(rule: Rule) = db.rules().upsert(rule.copy(updatedAt = System.currentTimeMillis()))

    suspend fun deleteRule(id: String) = db.rules().delete(id)

    suspend fun ruleById(id: String): Rule? = db.rules().byId(id)

    suspend fun capturedById(id: Long): CapturedNotification? = db.captured().byId(id)

    suspend fun samplesFor(packageName: String): List<CapturedNotification> =
        db.captured().recentForPackage(packageName)

    suspend fun deleteCapture(id: Long) = db.captured().delete(id)

    /**
     * Withdraws whatever a capture produced last time and runs the rules
     * afresh: the pending transaction is deleted in the web app, the outbox
     * item locally, and the capture goes back through the rule engine. A
     * transaction the user has already accepted is left untouched.
     */
    suspend fun rerun(capturedId: Long): RerunResult {
        val capture = db.captured().byId(capturedId)
            ?: return RerunResult.Refused("That notification is no longer stored")
        val item = capture.outboxId?.let { db.outbox().byId(it) }
            ?: return RerunResult.Done(captureProcessor.reevaluate(capturedId))

        if (item.state == OutboxState.SENDING) {
            return RerunResult.Refused("It is being sent right now — try again in a moment")
        }
        if (item.serverStatus == ServerStatus.CONFIRMED) {
            return RerunResult.Refused(ALREADY_ACCEPTED)
        }

        // Anything that reached the server holds the (source, ref) pair the
        // next post would collide with, so it has to go first.
        val reachedServer = item.remotePendingId != null ||
            item.state == OutboxState.POSTED ||
            item.state == OutboxState.DEDUPED
        if (reachedServer) {
            val source = runCatching {
                json.decodeFromString<PendingTransactionPayload>(item.payloadJson).externalSource
            }.getOrNull()
            when (val deleted = api.deletePending(item.remotePendingId, source, item.externalRef)) {
                is ApiResult.Ok -> Unit

                is ApiResult.ClientError -> {
                    // Accepted in the web app between the last status check and now.
                    if (deleted.code == 409) {
                        db.outbox().updateServerStatus(
                            id = item.id,
                            status = ServerStatus.CONFIRMED,
                            transactionId = item.confirmedTransactionId,
                            rejectReason = item.rejectReason,
                            remoteId = null,
                        )
                        return RerunResult.Refused(ALREADY_ACCEPTED)
                    }
                    return RerunResult.Refused(describeDeleteFailure(deleted.message))
                }

                is ApiResult.ServerError ->
                    return RerunResult.Refused(describeDeleteFailure(deleted.message))

                is ApiResult.NetworkError ->
                    return RerunResult.Refused("No connection: ${deleted.message}")
            }
        }

        // Unlink before deleting so the capture never points at a missing row.
        db.captured().updateOutcome(capturedId, MatchState.UNMATCHED, null, null, null)
        db.outbox().deleteById(item.id)
        return RerunResult.Done(captureProcessor.reevaluate(capturedId))
    }

    /**
     * Asks the web app what happened to everything we posted: still on
     * `/pending`, accepted, rejected, or deleted there. Returns how many items
     * were checked.
     */
    suspend fun refreshServerStatus(): ApiResult<Int> {
        val posted = db.outbox().postedItems()
        if (posted.isEmpty()) return ApiResult.Ok(0)

        return when (val remote = api.fetchPendingByRefs(posted.map { it.externalRef })) {
            is ApiResult.Ok -> {
                val byRef = remote.value.rows.associateBy { it.externalRef }
                var checked = 0
                posted.forEach { item ->
                    val row = byRef[item.externalRef]
                    // A missing row only means "deleted" when the server proved
                    // it looked for that specific ref. Otherwise leave the last
                    // known status alone rather than inventing GONE.
                    if (row == null && !remote.value.filtered) return@forEach
                    db.outbox().updateServerStatus(
                        id = item.id,
                        status = serverStatusOf(row),
                        transactionId = row?.confirmedTransactionId,
                        rejectReason = row?.rejectReason,
                        remoteId = row?.id,
                    )
                    checked++
                }
                ApiResult.Ok(checked)
            }

            is ApiResult.ClientError -> remote
            is ApiResult.ServerError -> remote
            is ApiResult.NetworkError -> remote
        }
    }

    private fun serverStatusOf(row: PendingTransactionDto?): ServerStatus? = when (row?.status) {
        "pending" -> ServerStatus.PENDING
        "confirmed" -> ServerStatus.CONFIRMED
        "rejected" -> ServerStatus.REJECTED
        null -> ServerStatus.GONE
        else -> null
    }

    /** Deep link into the web app for a posted item. */
    fun webLinkFor(item: OutboxItem): String? {
        val base = settings.current().baseUrl.trimEnd('/').takeIf { it.isNotBlank() } ?: return null
        return when (item.serverStatus) {
            ServerStatus.CONFIRMED ->
                item.confirmedTransactionId?.let { "$base/edit/$it" } ?: "$base/pending"

            ServerStatus.REJECTED -> "$base/pending"
            else -> "$base/pending"
        }
    }

    /** Re-queues a failed or held item. */
    suspend fun sendNow(outboxId: Long) {
        db.outbox().updateState(outboxId, OutboxState.QUEUED, notBefore = 0)
        SyncScheduler.kickOutbox(context, 0)
    }

    suspend fun cancel(outboxId: Long) = db.outbox().updateState(outboxId, OutboxState.CANCELLED)

    suspend fun testConnection(): ApiResult<Int> = api.fetchPendingCount()

    /** Refreshes the account and category pickers from the web app. */
    suspend fun syncCatalog(): ApiResult<Int> {
        return when (val accounts = api.fetchAccounts()) {
            is ApiResult.Ok -> {
                db.cache().replaceAccounts(
                    accounts.value.mapIndexed { index, dto ->
                        AccountCache(
                            id = dto.id,
                            name = dto.name,
                            type = dto.type,
                            currencyCode = dto.currencyCode,
                            currencySymbol = dto.currencySymbol,
                            archived = dto.archived,
                            sortIndex = index,
                        )
                    },
                )
                when (val categories = api.fetchCategories()) {
                    is ApiResult.Ok -> {
                        val groupNames = categories.value.groups.associate { it.id to it.name }
                        db.cache().replaceCategories(
                            categories.value.categories.mapIndexed { index, dto ->
                                CategoryCache(
                                    id = dto.id,
                                    name = dto.name,
                                    groupName = dto.groupId?.let { groupNames[it] },
                                    archived = dto.archived,
                                    sortIndex = dto.sortOrder ?: index,
                                )
                            },
                        )
                        settings.update { copy(lastCacheSync = System.currentTimeMillis()) }
                        ApiResult.Ok(accounts.value.size)
                    }

                    is ApiResult.ClientError -> categories
                    is ApiResult.ServerError -> categories
                    is ApiResult.NetworkError -> categories
                }
            }

            is ApiResult.ClientError -> accounts
            is ApiResult.ServerError -> accounts
            is ApiResult.NetworkError -> accounts
        }
    }

    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        json.encodeToString(
            RuleExport(
                rules = db.rules().all(),
                monitoredPackages = db.monitoredApps().enabled().map { it.packageName },
            ),
        )
    }

    /** Replaces nothing: imported rules are added or overwrite by id. */
    suspend fun importJson(raw: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val parsed = json.decodeFromString<RuleExport>(raw)
            db.rules().insertAll(parsed.rules)
            parsed.rules.size
        }
    }

    /**
     * cash-flow answers a request it has no handler for by falling through to
     * its HTML router, which refuses anything that does not accept HTML. So
     * that particular message means the deployed web app is older than this
     * app and has no delete endpoint — nothing is wrong with the transaction,
     * and re-running will work once the server is redeployed.
     */
    private fun describeDeleteFailure(message: String): String =
        if (message.contains(HTML_ONLY, ignoreCase = true)) {
            "cash-flow has no delete endpoint yet — redeploy the web app, then try again"
        } else {
            "Could not remove it in cash-flow: $message"
        }

    private companion object {
        const val HTML_ONLY = "Only HTML requests are supported"
        const val ALREADY_ACCEPTED =
            "Already accepted in cash-flow — undo it there first if you want to re-run the rules"
    }
}
