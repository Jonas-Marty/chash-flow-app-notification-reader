package ch.marty.finreader

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import ch.marty.finreader.data.api.ApiResult
import ch.marty.finreader.data.api.FinanceApi
import ch.marty.finreader.data.db.AccountCache
import ch.marty.finreader.data.db.AppDatabase
import ch.marty.finreader.data.db.CapturedNotification
import ch.marty.finreader.data.db.CategoryCache
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
}
