package ch.marty.finreader.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ch.marty.finreader.AppContainer
import ch.marty.finreader.data.LocationCapture
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
            val located = withLocation(container, item)
            when (val result = container.api.postPending(located.payloadJson)) {
                is ApiResult.Ok -> {
                    outbox.updateResult(
                        id = item.id,
                        state = if (result.value.deduplicated) OutboxState.DEDUPED else OutboxState.POSTED,
                        attempts = item.attempts + 1,
                        lastError = null,
                        remoteId = result.value.remoteId,
                    )
                    recordDelivery(container, item, located)
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

        refineLocations(container)

        // Piggy-backs on the periodic run so the accepted/rejected labels in the
        // inbox stay roughly current without a worker of their own. Costs
        // nothing when nothing has been posted.
        container.repository.refreshServerStatus()

        // Items still inside their undo window, and rows whose one refinement
        // is still ahead of them, both need their own wake-up.
        val nextWake = listOfNotNull(
            outbox.nextDueAfter(System.currentTimeMillis()),
            outbox.nextRefineAfter(System.currentTimeMillis()),
        ).minOrNull()
        nextWake?.let { next ->
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
     * What a payload left with, so the outcome can be recorded once the server
     * has answered. [note] is null only when this payment was never meant to
     * carry a location at all — there is nothing to explain in that case.
     */
    private data class Located(
        val payloadJson: String,
        val fix: LocationCapture.Fix?,
        val note: String?,
        /** Capture is on, but the platform would not answer. */
        val blocked: Boolean = false,
    )

    /**
     * Last chance to improve the fix taken when the notification arrived. The
     * undo window has just elapsed, so the phone has had a few seconds of
     * standing still at the till — often the difference between a cell-tower
     * guess and something that actually picks out the right branch.
     */
    private suspend fun withLocation(container: AppContainer, item: OutboxItem): Located {
        val settings = container.settings.current()
        // The rule's verdict was taken when the notification arrived and is not
        // revisited here; a fresher fix must not smuggle a location into a
        // payment that was meant to travel without one.
        if (!settings.captureLocation || !item.attachLocation) {
            return Located(OutboxLocation.inject(item.payloadJson, null), null, null)
        }

        container.locationCapture.unavailableReason()?.let { reason ->
            return Located(
                payloadJson = OutboxLocation.inject(item.payloadJson, null),
                fix = null,
                note = OutboxLocation.blockedNote(reason),
                blocked = true,
            )
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
        return Located(
            payloadJson = OutboxLocation.inject(item.payloadJson, fix),
            fix = fix,
            note = OutboxLocation.sentNote(fix, null),
        )
    }

    /**
     * Records what the server now holds, and books the one refinement attempt.
     * Nothing is booked when the platform refused to answer: ninety seconds
     * will not grant a permission.
     */
    private suspend fun recordDelivery(container: AppContainer, item: OutboxItem, located: Located) {
        val note = located.note ?: return
        val sentAccuracyM = located.fix?.accuracyM
        val refineAt = if (located.blocked) {
            0L
        } else {
            OutboxLocation.refineAt(item.createdAt, sentAccuracyM, System.currentTimeMillis())
        }
        container.db.outbox().updateLocationDelivery(
            id = item.id,
            sentAccuracyM = sentAccuracyM,
            refineAt = refineAt,
            note = note,
        )
    }

    /**
     * The second and final look at where a payment happened.
     *
     * The server replaces a pending row's location on a repeat POST with the
     * same `external_ref`, and only the location — a second, worse parse of the
     * notification cannot rewrite what the user is about to review. So the
     * phone is free to send the payment the instant it is captured and come
     * back with a better answer a minute later, which is the whole point: at a
     * till, the good fix arrives after the notification, not before it.
     *
     * One attempt per payment. A refinement is a nicety, and a phone that keeps
     * waking to re-take fixes is a location feature that gets switched off.
     */
    private suspend fun refineLocations(container: AppContainer) {
        val outbox = container.db.outbox()
        val now = System.currentTimeMillis()
        val due = outbox.dueForRefining(now)
        if (due.isEmpty()) return

        val settings = container.settings.current()
        // Switched off, or so late that a fresh fix would say where the phone
        // is now rather than where the money went.
        val (expired, live) = due.partition {
            !settings.captureLocation || OutboxLocation.isTooLateToRefine(it, now)
        }
        expired.forEach { outbox.updateLocationDelivery(it.id, it.locationSentAccuracyM, 0, it.locationNote) }
        if (live.isEmpty()) return

        // One reading serves every payment due in this pass. They are all from
        // the same few minutes, so a second would cost battery to say the same
        // thing — and two payments in one shop should agree on where they were.
        val fresh = container.locationCapture.fresh(OutboxLocation.REFINE_TIMEOUT_MS)

        for (item in live) {
            val candidate = OutboxLocation.choose(
                OutboxLocation.storedFix(item),
                fresh,
                System.currentTimeMillis(),
                settings,
            )
            if (candidate == null ||
                !OutboxLocation.isMeaningfulImprovement(item.locationSentAccuracyM, candidate.accuracyM)
            ) {
                outbox.updateLocationDelivery(item.id, item.locationSentAccuracyM, 0, item.locationNote)
                continue
            }

            when (val result = container.api.postPending(OutboxLocation.inject(item.payloadJson, candidate))) {
                is ApiResult.Ok -> {
                    val accepted = result.value.locationUpdated
                    outbox.updateLocation(
                        id = item.id,
                        latitude = candidate.latitude,
                        longitude = candidate.longitude,
                        accuracyM = candidate.accuracyM,
                        takenAt = candidate.takenAt,
                    )
                    outbox.updateLocationDelivery(
                        id = item.id,
                        sentAccuracyM = if (accepted) candidate.accuracyM else item.locationSentAccuracyM,
                        refineAt = 0,
                        note = OutboxLocation.refinedNote(candidate.accuracyM, accepted),
                    )
                }

                is ApiResult.ClientError -> outbox.updateLocationDelivery(
                    id = item.id,
                    sentAccuracyM = item.locationSentAccuracyM,
                    refineAt = 0,
                    note = "Location refinement refused: ${result.message}",
                )

                // Left booked rather than burned: another run inside the
                // deadline may still get it through, and past the deadline the
                // check at the top of the loop clears it.
                else -> Unit
            }
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 8
    }
}
