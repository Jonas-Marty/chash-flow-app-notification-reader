package ch.marty.finreader.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.marty.finreader.AppContainer
import ch.marty.finreader.InstalledApp
import ch.marty.finreader.data.api.ApiResult
import ch.marty.finreader.data.db.CapturedNotification
import ch.marty.finreader.data.db.OutboxItem
import ch.marty.finreader.data.db.Rule
import ch.marty.finreader.data.prefs.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    fun refreshNotificationAccess() {
        _notificationAccess.value = repo.isNotificationAccessGranted()
    }

    fun notificationAccessIntent() = repo.notificationAccessIntent()

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
