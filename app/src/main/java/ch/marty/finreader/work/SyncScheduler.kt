package ch.marty.finreader.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SyncScheduler {

    private const val OUTBOX_WORK = "outbox-sync"
    private const val OUTBOX_PERIODIC = "outbox-periodic"
    private const val PURGE_WORK = "purge"

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /**
     * Drains the outbox. [delayMillis] carries the undo window, so a queued
     * transaction is only sent once the undo action has expired.
     */
    fun kickOutbox(context: Context, delayMillis: Long = 0) {
        val request = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setConstraints(networkConstraint)
            .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // REPLACE keeps the delay honest; a worker killed mid-send is recovered
        // by OutboxWorker's stale-SENDING sweep on the next run.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(OUTBOX_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    /** Safety net: catches anything a replaced or crashed worker left behind. */
    fun ensureBackgroundWork(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            OUTBOX_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<OutboxWorker>(30, TimeUnit.MINUTES)
                .setConstraints(networkConstraint)
                .build(),
        )
        wm.enqueueUniquePeriodicWork(
            PURGE_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PurgeWorker>(1, TimeUnit.DAYS).build(),
        )
    }
}
