package ch.marty.finreader.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ch.marty.finreader.AppContainer
import ch.marty.finreader.data.OutboxLocation
import ch.marty.finreader.data.api.ApiResult
import ch.marty.finreader.data.api.ProposalOutcome
import ch.marty.finreader.data.db.OutboxItem
import ch.marty.finreader.data.db.OutboxState
import ch.marty.finreader.data.db.RuleTarget
import ch.marty.finreader.data.db.ServerStatus
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
            if (item.target == RuleTarget.RECURRING_BILL) {
                if (sendBill(container, item)) shouldRetry = true
                continue
            }
            val payload = withLocation(container, item)
            when (val result = container.api.postPending(payload)) {
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

                is ApiResult.ServerError -> if (failTransiently(container, item, result.message)) shouldRetry = true
                is ApiResult.NetworkError -> if (failTransiently(container, item, result.message)) shouldRetry = true
            }
        }

        // Piggy-backs on the periodic run so the accepted/rejected labels in the
        // inbox stay roughly current without a worker of their own. Costs
        // nothing when nothing has been posted.
        container.repository.refreshServerStatus()

        // Items still inside their undo window need their own wake-up.
        outbox.nextDueAfter(System.currentTimeMillis())?.let { next ->
            val delay = next - System.currentTimeMillis()
            if (delay > 0 && !shouldRetry) SyncScheduler.kickOutbox(applicationContext, delay)
        }

        return if (shouldRetry) Result.retry() else Result.success()
    }

    /**
     * Sends a bill proposal. Returns true when it is worth retrying later.
     *
     * An entry that was already posted is a verdict, not a failure: the bill
     * came late, and the user has to correct that transaction in the web app.
     * The item is REFUSED with the transaction's id so the inbox can open it.
     */
    private suspend fun sendBill(container: AppContainer, item: OutboxItem): Boolean {
        val outbox = container.db.outbox()
        val label = "Bill for ${item.recurringRuleName ?: "a recurring entry"}"
        when (val result = container.api.postProposal(item.payloadJson)) {
            is ApiResult.Ok -> when (val outcome = result.value) {
                is ProposalOutcome.Stored -> {
                    val proposal = outcome.proposal
                    outbox.updateResult(
                        id = item.id,
                        state = if (outcome.deduplicated) OutboxState.DEDUPED else OutboxState.POSTED,
                        attempts = item.attempts + 1,
                        lastError = null,
                        remoteId = proposal.occurrenceId,
                    )
                    outbox.updateRecurringEntry(item.id, proposal.ruleName, proposal.effectiveOn, proposal.transactionId)
                    outbox.updateServerStatus(
                        id = item.id,
                        status = if (proposal.status == "posted") ServerStatus.CONFIRMED else ServerStatus.PENDING,
                        transactionId = proposal.transactionId,
                        rejectReason = null,
                        remoteId = proposal.occurrenceId,
                    )
                    container.notifier.cancelCaptured(item.id)
                }

                is ProposalOutcome.Refused -> {
                    val proposal = outcome.proposal
                    val message = if (outcome.code == "already_posted") {
                        "Already posted in cash-flow before this bill arrived — open the entry and edit the amount by hand"
                    } else {
                        "cash-flow did not take it: ${outcome.message}"
                    }
                    outbox.updateResult(item.id, OutboxState.REFUSED, item.attempts + 1, message, null)
                    outbox.updateRecurringEntry(item.id, proposal?.ruleName, proposal?.effectiveOn, proposal?.transactionId)
                    container.notifier.showProblem(item.id, "$label — $message")
                }
            }

            is ApiResult.ClientError -> {
                outbox.updateResult(item.id, OutboxState.FAILED_PERMANENT, item.attempts + 1, result.message, null)
                container.notifier.showProblem(item.id, "$label — ${result.message}")
            }

            is ApiResult.ServerError -> return failTransiently(container, item, result.message)
            is ApiResult.NetworkError -> return failTransiently(container, item, result.message)
        }
        return false
    }

    /** Counts an attempt that may work later; true when it should be retried. */
    private suspend fun failTransiently(container: AppContainer, item: OutboxItem, message: String): Boolean {
        val outbox = container.db.outbox()
        val attempts = item.attempts + 1
        if (attempts >= MAX_ATTEMPTS) {
            outbox.updateResult(item.id, OutboxState.FAILED_PERMANENT, attempts, message, null)
            container.notifier.showProblem(
                item.id,
                "${(item.recurringRuleName ?: item.description).orEmpty()} — gave up after $attempts attempts ($message)",
            )
            return false
        }
        outbox.updateResult(item.id, OutboxState.FAILED_RETRY, attempts, message, null)
        return true
    }

    /**
     * Last chance to improve the fix taken when the notification arrived. The
     * undo window has just elapsed, so the phone has had a few seconds of
     * standing still at the till — often the difference between a cell-tower
     * guess and something that actually picks out the right branch.
     */
    private suspend fun withLocation(container: AppContainer, item: OutboxItem): String {
        val settings = container.settings.current()
        // The rule's verdict was taken when the notification arrived and is not
        // revisited here; a fresher fix must not smuggle a location into a
        // payment that was meant to travel without one.
        if (!settings.captureLocation || !item.attachLocation) {
            return OutboxLocation.inject(item.payloadJson, null)
        }

        val stored = OutboxLocation.storedFix(item)
        val fresh = if (OutboxLocation.isGoodEnough(stored, System.currentTimeMillis())) {
            null
        } else {
            container.locationCapture.fresh(OutboxLocation.REFINE_TIMEOUT_MS)
        }
        val fix = OutboxLocation.choose(stored, fresh, System.currentTimeMillis(), settings)

        if (fix != null && fix != stored) {
            container.db.outbox().updateLocation(
                id = item.id,
                latitude = fix.latitude,
                longitude = fix.longitude,
                accuracyM = fix.accuracyM,
                takenAt = fix.takenAt,
            )
        }
        return OutboxLocation.inject(item.payloadJson, fix)
    }

    private companion object {
        const val MAX_ATTEMPTS = 8
    }
}
