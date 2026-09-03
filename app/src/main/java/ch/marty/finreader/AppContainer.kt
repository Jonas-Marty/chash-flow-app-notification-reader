package ch.marty.finreader

import android.content.Context
import ch.marty.finreader.data.CaptureProcessor
import ch.marty.finreader.data.api.FinanceApi
import ch.marty.finreader.data.db.AppDatabase
import ch.marty.finreader.data.prefs.SettingsStore
import ch.marty.finreader.notify.Notifier

/**
 * Hand-rolled dependency holder. Reachable from workers, receivers and the
 * notification listener without going through [android.app.Application].
 */
class AppContainer private constructor(context: Context) {

    private val appContext = context.applicationContext

    val db: AppDatabase by lazy { AppDatabase.get(appContext) }
    val settings: SettingsStore by lazy { SettingsStore(appContext) }
    val api: FinanceApi by lazy { FinanceApi(settings) }
    val notifier: Notifier by lazy { Notifier(appContext) }
    val repository: Repository by lazy { Repository(appContext, db, settings, api) }
    val captureProcessor: CaptureProcessor by lazy {
        CaptureProcessor(appContext, db, settings, notifier)
    }

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun get(context: Context): AppContainer = instance ?: synchronized(this) {
            instance ?: AppContainer(context).also { instance = it }
        }
    }
}
