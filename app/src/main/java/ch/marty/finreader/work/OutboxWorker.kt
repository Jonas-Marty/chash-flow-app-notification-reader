package ch.marty.finreader.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ch.marty.finreader.AppContainer
import ch.marty.finreader.data.api.ApiResult
import ch.marty.finreader.data.db.OutboxState
import java.util.concurrent.TimeUnit

/**
 * Sends everything in the outbox that is due. Failures are classified so that a
 * rejected payload is not retried forever while a flaky connection is.
 */
class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = AppContainer.get(applicationContext)
        val outbox = container.db.outbox()
        val now = System.currentTimeMillis()

        outbox.recoverStaleSending(now - TimeUnit.MINUTES.toMillis(5))

        val due = outbox.dueForSending(now)
        var shouldRetry = false

        for (item in due) {
            outbox.updateState(item.id, OutboxState.SENDING, notBefore = item.notBefore)
            when (val result = container.api.postPending(item.payloadJson)) {
                is ApiResult.Ok -> {
                    outbox.updateResult(
                        id = item.id,
                        state = if (result.value.deduplicated) OutboxState.DEDUPED else OutboxState.POSTED,
                        attempts = item.attempts + 1,
                        lastError = null,
                        remoteId = result.value.remoteId,
                    )
                    container.notifier.cancelCaptured(item.id)
                }

                is ApiResult.ClientError -> {
                    outbox.updateResult(
                        item.id, OutboxState.FAILED_PERMANENT, item.attempts + 1, result.message, null,
                    )
                    container.notifier.showProblem(item.id, "${item.description.orEmpty()} — ${result.message}")
                }

                is ApiResult.ServerError, is ApiResult.NetworkError -> {
                    val message = when (result) {
                        is ApiResult.ServerError -> result.message
                        is ApiResult.NetworkError -> result.message
                        else -> "unknown"
                    }
                    val attempts = item.attempts + 1
                    if (attempts >= MAX_ATTEMPTS) {
                        outbox.updateResult(item.id, OutboxState.FAILED_PERMANENT, attempts, message, null)
                        container.notifier.showProblem(
                            item.id,
                            "${item.description.orEmpty()} — gave up after $attempts attempts ($message)",
                        )
                    } else {
                        outbox.updateResult(item.id, OutboxState.FAILED_RETRY, attempts, message, null)
                        shouldRetry = true
                    }
                }
            }
        }

        // Items still inside their undo window need their own wake-up.
        outbox.nextDueAfter(System.currentTimeMillis())?.let { next ->
            val delay = next - System.currentTimeMillis()
            if (delay > 0 && !shouldRetry) SyncScheduler.kickOutbox(applicationContext, delay)
        }

        return if (shouldRetry) Result.retry() else Result.success()
    }

    private companion object {
        const val MAX_ATTEMPTS = 8
    }
}
