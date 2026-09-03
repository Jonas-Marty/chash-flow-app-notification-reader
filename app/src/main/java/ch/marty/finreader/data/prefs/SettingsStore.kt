package ch.marty.finreader.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

data class Settings(
    val baseUrl: String = "",
    val apiToken: String = "",
    val autoPostEnabled: Boolean = true,
    val feedbackNotifications: Boolean = true,
    val undoWindowSeconds: Int = 20,
    val retentionDays: Int = 30,
    val maxCapturedRows: Int = 1000,
    val lastCacheSync: Long = 0,
    /** Off by default: location is an extra permission and an extra risk. */
    val captureLocation: Boolean = false,
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && apiToken.isNotBlank()

    /**
     * A fix older than this is not where the payment happened. It matters for
     * the card notifications that arrive once you are already home, where the
     * last known location would otherwise be attached with confidence.
     */
    val locationMaxAgeMillis: Long get() = 10 * 60 * 1000L

    /** Beyond this the fix says "somewhere in this town" and is not worth storing. */
    val locationMaxAccuracyM: Float get() = 500f

    /** Undo is only offered while a feedback notification is actually shown. */
    val undoWindowMillis: Long
        get() = if (feedbackNotifications) undoWindowSeconds * 1000L else 0L
}

/**
 * The API token lives in [EncryptedSharedPreferences]; nothing here is included
 * in cloud backup (see `data_extraction_rules.xml`).
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val app = context.applicationContext
        val masterKey = MasterKey.Builder(app)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            app,
            "finreader_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun current(): Settings = Settings(
        baseUrl = prefs.getString(KEY_BASE_URL, "").orEmpty(),
        apiToken = prefs.getString(KEY_TOKEN, "").orEmpty(),
        autoPostEnabled = prefs.getBoolean(KEY_AUTO_POST, true),
        feedbackNotifications = prefs.getBoolean(KEY_FEEDBACK, true),
        undoWindowSeconds = prefs.getInt(KEY_UNDO_WINDOW, 20),
        retentionDays = prefs.getInt(KEY_RETENTION, 30),
        maxCapturedRows = prefs.getInt(KEY_MAX_ROWS, 1000),
        lastCacheSync = prefs.getLong(KEY_LAST_SYNC, 0),
        captureLocation = prefs.getBoolean(KEY_CAPTURE_LOCATION, false),
    )

    fun observe(): Flow<Settings> = callbackFlow {
        trySend(current())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(current()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun update(block: Settings.() -> Settings) {
        val next = current().block()
        prefs.edit()
            .putString(KEY_BASE_URL, normalizeBaseUrl(next.baseUrl))
            .putString(KEY_TOKEN, next.apiToken.trim())
            .putBoolean(KEY_AUTO_POST, next.autoPostEnabled)
            .putBoolean(KEY_FEEDBACK, next.feedbackNotifications)
            .putInt(KEY_UNDO_WINDOW, next.undoWindowSeconds.coerceIn(0, 300))
            .putInt(KEY_RETENTION, next.retentionDays.coerceIn(1, 365))
            .putInt(KEY_MAX_ROWS, next.maxCapturedRows.coerceIn(50, 20_000))
            .putLong(KEY_LAST_SYNC, next.lastCacheSync)
            .putBoolean(KEY_CAPTURE_LOCATION, next.captureLocation)
            .apply()
    }

    companion object {
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_TOKEN = "api_token"
        private const val KEY_AUTO_POST = "auto_post"
        private const val KEY_FEEDBACK = "feedback_notifications"
        private const val KEY_UNDO_WINDOW = "undo_window_seconds"
        private const val KEY_RETENTION = "retention_days"
        private const val KEY_MAX_ROWS = "max_captured_rows"
        private const val KEY_LAST_SYNC = "last_cache_sync"
        private const val KEY_CAPTURE_LOCATION = "capture_location"

        fun normalizeBaseUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return ""
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
            else "https://$trimmed"
        }
    }
}
