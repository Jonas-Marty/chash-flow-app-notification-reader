package ch.marty.finreader.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.marty.finreader.AppContainer
import ch.marty.finreader.InstalledApp
import ch.marty.finreader.RerunResult
import ch.marty.finreader.data.api.ApiResult
import ch.marty.finreader.data.db.CapturedNotification
import ch.marty.finreader.data.db.MatchState
import ch.marty.finreader.data.db.MonitoredApp
import ch.marty.finreader.data.db.OutboxItem
import ch.marty.finreader.data.db.Rule
import ch.marty.finreader.data.prefs.Settings
import ch.marty.finreader.util.CrashLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val container = AppContainer.get(app)
    private val repo = container.repository

    val settings: StateFlow<Settings> =
        repo.observeSettings().stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    val captured: StateFlow<List<CapturedNotification>> =
        repo.observeCaptured().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val outboxById: StateFlow<Map<Long, OutboxItem>> =
        repo.observeOutbox().map { list -> list.associateBy { it.id } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Drives the app picker in the rule editor; the Apps screen keeps it filled. */
    val monitoredApps: StateFlow<List<MonitoredApp>> =
        repo.observeMonitoredApps().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val rules: StateFlow<List<Rule>> =
        repo.observeRules().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val accounts = repo.observeAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories = repo.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val unsentCount = repo.observeUnsentCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val failedCount = repo.observeFailedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps

    private val _notificationAccess = MutableStateFlow(repo.isNotificationAccessGranted())
    val notificationAccess: StateFlow<Boolean> = _notificationAccess

    /** Posting notifications, not reading them — a separate grant. */
    private val _notificationsAllowed = MutableStateFlow(repo.canPostNotifications())
    val notificationsAllowed: StateFlow<Boolean> = _notificationsAllowed

    /** Foreground grant; without it nothing is read at all. */
    private val _locationAllowed = MutableStateFlow(repo.hasLocationPermission())
    val locationAllowed: StateFlow<Boolean> = _locationAllowed

    /** "Allow all the time"; without it only a foreground read would work. */
    private val _backgroundLocationAllowed = MutableStateFlow(repo.hasBackgroundLocationPermission())
    val backgroundLocationAllowed: StateFlow<Boolean> = _backgroundLocationAllowed

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _diagnostics = MutableStateFlow<String?>(null)
    val diagnostics: StateFlow<String?> = _diagnostics

    fun loadDiagnostics() = viewModelScope.launch {
        _diagnostics.value = withContext(Dispatchers.IO) { CrashLog.report() }
    }

    fun hideDiagnostics() {
        _diagnostics.value = null
    }

    fun clearDiagnostics() = viewModelScope.launch {
        withContext(Dispatchers.IO) { CrashLog.clear() }
        _diagnostics.value = null
        _message.value = "Crash log cleared"
    }

    /** Cheap enough to re-read all of them whenever the app comes forward. */
    fun refreshPermissions() {
        _notificationAccess.value = repo.isNotificationAccessGranted()
        _notificationsAllowed.value = repo.canPostNotifications()
        _locationAllowed.value = repo.hasLocationPermission()
        _backgroundLocationAllowed.value = repo.hasBackgroundLocationPermission()
    }

    fun notificationAccessIntent() = repo.notificationAccessIntent()

    fun notificationSettingsIntent() = repo.notificationSettingsIntent()

    fun appDetailsIntent() = repo.appDetailsIntent()

    fun consumeMessage() {
        _message.value = null
    }

    fun loadInstalledApps() = viewModelScope.launch {
        _busy.value = true
        _installedApps.value = repo.installedApps()
        _busy.value = false
    }

    fun setMonitored(app: InstalledApp, enabled: Boolean) = viewModelScope.launch {
        repo.setMonitored(app.packageName, app.label, enabled)
        _installedApps.value = _installedApps.value.map {
            if (it.packageName == app.packageName) it.copy(monitored = enabled) else it
        }
    }

    fun saveSettings(block: Settings.() -> Settings) {
        repo.updateSettings(block)
    }

    fun testConnection() = viewModelScope.launch {
        _busy.value = true
        _message.value = when (val result = repo.testConnection()) {
            is ApiResult.Ok -> "Connected — ${result.value} transaction(s) waiting for confirmation"
            is ApiResult.ClientError -> "Failed: ${result.message}"
            is ApiResult.ServerError -> "Server error: ${result.message}"
            is ApiResult.NetworkError -> "No connection: ${result.message}"
        }
        _busy.value = false
    }

    fun syncCatalog() = viewModelScope.launch {
        _busy.value = true
        _message.value = when (val result = repo.syncCatalog()) {
            is ApiResult.Ok -> "Loaded ${result.value} account(s) and the category list"
            is ApiResult.ClientError -> "Failed: ${result.message}"
            is ApiResult.ServerError -> "Server error: ${result.message}"
            is ApiResult.NetworkError -> "No connection: ${result.message}"
        }
        _busy.value = false
    }

    fun sendNow(outboxId: Long) = viewModelScope.launch {
        repo.sendNow(outboxId)
        _message.value = "Queued for sending"
    }

    fun cancel(outboxId: Long) = viewModelScope.launch {
        repo.cancel(outboxId)
        _message.value = "Cancelled"
    }

    fun deleteCapture(id: Long) = viewModelScope.launch { repo.deleteCapture(id) }

    /**
     * Runs the rules again, undoing an earlier post if there was one. Covers
     * both the never-matched case and the "wrong rule won" case.
     */
    fun rerun(capturedId: Long) = viewModelScope.launch {
        _busy.value = true
        _message.value = when (val result = repo.rerun(capturedId)) {
            is RerunResult.Refused -> result.reason
            is RerunResult.Done -> describe(result.state)
        }
        _busy.value = false
    }

    private fun describe(state: MatchState?): String = when (state) {
        MatchState.MATCHED -> "Matched — see the amount on the card"
        MatchState.UNMATCHED -> "Still no rule matches this notification"
        MatchState.IGNORED -> "A rule matched but skipped it on purpose"
        MatchState.ERROR -> "A rule matched but could not build the transaction"
        MatchState.DUPLICATE -> "Treated as a duplicate of a recent payment"
        null -> "Nothing to run the rules against"
    }

    fun refreshServerStatus() = viewModelScope.launch {
        _busy.value = true
        _message.value = when (val result = repo.refreshServerStatus()) {
            is ApiResult.Ok ->
                if (result.value == 0) "Nothing waiting on a decision in cash-flow"
                else "Checked ${result.value} transaction(s) in cash-flow"

            is ApiResult.ClientError -> "Failed: ${result.message}"
            is ApiResult.ServerError -> "Server error: ${result.message}"
            is ApiResult.NetworkError -> "No connection: ${result.message}"
        }
        _busy.value = false
    }

    fun webLinkFor(item: OutboxItem): String? = repo.webLinkFor(item)

    suspend fun ruleById(id: String): Rule? = repo.ruleById(id)

    suspend fun captureById(id: Long): CapturedNotification? = repo.capturedById(id)

    suspend fun samplesFor(packageName: String): List<CapturedNotification> = repo.samplesFor(packageName)

    fun saveRule(rule: Rule) = viewModelScope.launch {
        repo.saveRule(rule)
        _message.value = "Rule saved"
    }

    fun deleteRule(id: String) = viewModelScope.launch { repo.deleteRule(id) }

    fun toggleRule(rule: Rule, enabled: Boolean) = viewModelScope.launch {
        repo.saveRule(rule.copy(enabled = enabled))
    }

    fun exportTo(uri: Uri) = viewModelScope.launch {
        val payload = repo.exportJson()
        val result = runCatching {
            getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                it.write(payload.toByteArray())
            } ?: error("Could not open the file")
        }
        _message.value = result.fold({ "Rules exported" }, { "Export failed: ${it.message}" })
    }

    fun importFrom(uri: Uri) = viewModelScope.launch {
        val raw = runCatching {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                it.readBytes().decodeToString()
            } ?: error("Could not open the file")
        }
        _message.value = raw.fold(
            { text -> repo.importJson(text).fold({ "Imported $it rule(s)" }, { "Import failed: ${it.message}" }) },
            { "Import failed: ${it.message}" },
        )
    }
}
