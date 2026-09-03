package ch.marty.finreader

import android.app.Application
import ch.marty.finreader.util.CrashLog
import ch.marty.finreader.work.SyncScheduler

class FinReaderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        val container = AppContainer.get(this)
        container.notifier.ensureChannels()
        SyncScheduler.ensureBackgroundWork(this)
    }
}
