package ch.marty.finreader.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ch.marty.finreader.AppContainer
import java.util.concurrent.TimeUnit

/** Keeps the local notification log bounded, in time and in rows. */
class PurgeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = AppContainer.get(applicationContext)
        val settings = container.settings.current()
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(settings.retentionDays.toLong())

        container.db.captured().purgeOlderThan(cutoff)
        container.db.captured().trimTo(settings.maxCapturedRows)
        container.db.outbox().purgeOlderThan(cutoff)
        return Result.success()
    }
}
