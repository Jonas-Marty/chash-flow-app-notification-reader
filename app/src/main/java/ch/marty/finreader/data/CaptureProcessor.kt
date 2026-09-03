package ch.marty.finreader.data

import android.content.Context
import ch.marty.finreader.data.api.PendingTransactionPayload
import ch.marty.finreader.data.db.AppDatabase
import ch.marty.finreader.data.db.CapturedNotification
import ch.marty.finreader.data.db.MatchState
import ch.marty.finreader.data.db.OutboxItem
import ch.marty.finreader.data.db.OutboxState
import ch.marty.finreader.data.prefs.SettingsStore
import ch.marty.finreader.domain.AmountParser
import ch.marty.finreader.domain.ExternalRef
import ch.marty.finreader.domain.MatchOutcome
import ch.marty.finreader.domain.NotificationInput
import ch.marty.finreader.domain.RuleEngine
import ch.marty.finreader.domain.toInput
import ch.marty.finreader.notify.Notifier
import ch.marty.finreader.work.SyncScheduler
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Turns an incoming notification into an outbox item.
 *
 * Everything here runs off the notification listener's thread and is the only
 * place that decides whether something is worth sending.
 */
class CaptureProcessor(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val notifier: Notifier,
) {

    private val json = Json { explicitNulls = false; encodeDefaults = true }

    suspend fun process(input: NotificationInput) {
        val monitored = db.monitoredApps().byPackage(input.packageName)
        if (monitored?.enabled != true) return
        if (input.title.isNullOrBlank() && input.body.isNullOrBlank()) return

        val captured = CapturedNotification(
            packageName = input.packageName,
            appLabel = monitored.appLabel,
            notificationKey = null,
            postedAt = input.postedAt,
            title = input.title,
            text = input.body,
            bigText = null,
            subText = null,
        )
        val capturedId = db.captured().insert(captured)
        evaluate(capturedId, input.copy(appLabel = monitored.appLabel))
    }

    /**
     * Runs today's rules against a capture stored earlier — the case where the
     * rule was written *after* the notification arrived.
     *
     * The capture must not still own an outbox item: withdrawing that one,
     * locally and on the server, is the caller's job, and skipping it would
     * post the same payment twice under different refs.
     */
    suspend fun reevaluate(capturedId: Long): MatchState? {
        val capture = db.captured().byId(capturedId) ?: return null
        evaluate(capturedId, capture.toInput())
        return db.captured().byId(capturedId)?.matchState
    }

    private suspend fun evaluate(capturedId: Long, input: NotificationInput) {
        val rules = db.rules().enabledForPackage(input.packageName)
        when (val outcome = RuleEngine.evaluate(input, rules)) {
            is MatchOutcome.NoMatch ->
                db.captured().updateOutcome(capturedId, MatchState.UNMATCHED, null, null, null)

            is MatchOutcome.Ignored ->
                db.captured().updateOutcome(
                    capturedId, MatchState.IGNORED, outcome.rule.id, null, outcome.reason,
                )

            is MatchOutcome.Failed ->
                db.captured().updateOutcome(
                    capturedId, MatchState.ERROR, outcome.rule.id, null, outcome.reason,
                )

            is MatchOutcome.Matched -> enqueue(capturedId, input, outcome)
        }
    }

    private suspend fun enqueue(
        capturedId: Long,
        input: NotificationInput,
        matched: MatchOutcome.Matched,
    ) {
        val (rule, extraction) = matched
        val config = settings.current()
        val now = System.currentTimeMillis()

        val base = ExternalRef.base(
            packageName = input.packageName,
            occurredOn = extraction.occurredOn,
            amountCents = extraction.amountCents,
            text = input.haystack,
        )
        val siblings = db.outbox().siblingsOf(base)
        val recent = siblings.firstOrNull { now - it.createdAt < DUPLICATE_WINDOW_MS }
        if (recent != null) {
            db.captured().updateOutcome(
                capturedId,
                MatchState.DUPLICATE,
                rule.id,
                recent.id,
                "Same payment captured ${(now - recent.createdAt) / 1000}s ago",
            )
            return
        }

        val payload = PendingTransactionPayload(
            sourceAccountId = rule.sourceAccountId,
            amount = AmountParser.centsToPlainString(extraction.amountCents),
            type = extraction.type,
            occurredOn = extraction.occurredOn,
            categoryId = rule.categoryId,
            description = extraction.description,
            note = buildNote(extraction.note, extraction.currency, rule.sourceAccountCurrency),
            externalSource = rule.externalSource.take(120),
            externalRef = ExternalRef.withSequence(base, siblings.size),
            externalInfo = input.haystack.take(2000),
        )

        val holdForTap = !config.autoPostEnabled || !rule.autoPost
        val undoWindow = if (holdForTap) 0L else config.undoWindowMillis
        val item = OutboxItem(
            capturedId = capturedId,
            ruleId = rule.id,
            externalRef = payload.externalRef.orEmpty(),
            externalRefBase = base,
            payloadJson = json.encodeToString(payload),
            amountCents = extraction.amountCents,
            currency = extraction.currency,
            description = extraction.description,
            state = if (holdForTap) OutboxState.HELD else OutboxState.QUEUED,
            notBefore = now + undoWindow,
        )
        val outboxId = db.outbox().insert(item)
        db.captured().updateOutcome(capturedId, MatchState.MATCHED, rule.id, outboxId, null)

        if (config.feedbackNotifications || holdForTap) {
            notifier.showCaptured(
                outboxId = outboxId,
                amount = AmountParser.centsToPlainString(extraction.amountCents),
                currency = extraction.currency,
                description = extraction.description,
                held = holdForTap,
                undoable = !holdForTap && undoWindow > 0,
            )
        }

        if (!holdForTap) SyncScheduler.kickOutbox(context, undoWindow)
    }

    /** A currency the target account does not use is worth recording in the note. */
    private fun buildNote(note: String?, currency: String, accountCurrency: String?): String? {
        val mismatch = accountCurrency != null &&
            currency.isNotBlank() &&
            !currency.equals(accountCurrency, ignoreCase = true)
        val currencyNote = if (mismatch) "Original currency: $currency" else null
        return listOfNotNull(note, currencyNote).joinToString("\n").takeIf { it.isNotBlank() }
    }

    private companion object {
        /**
         * Android re-posts a notification when it updates it; anything arriving
         * inside this window with an identical key is the same payment.
         */
        const val DUPLICATE_WINDOW_MS = 120_000L
    }
}

