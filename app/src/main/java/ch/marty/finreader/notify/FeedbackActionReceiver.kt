package ch.marty.finreader.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ch.marty.finreader.AppContainer
import ch.marty.finreader.data.db.MatchState
import ch.marty.finreader.data.db.OutboxState
import ch.marty.finreader.work.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Handles the Undo / Send now actions on the feedback notification. */
class FeedbackActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val outboxId = intent.getLongExtra(EXTRA_OUTBOX_ID, -1L)
        if (outboxId <= 0) return
        val action = intent.action ?: return
        val pending = goAsync()
        val container = AppContainer.get(context)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = container.db
                val item = db.outbox().byId(outboxId) ?: return@launch
                when (action) {
                    ACTION_UNDO -> when (item.state) {
                        OutboxState.QUEUED, OutboxState.HELD, OutboxState.FAILED_RETRY -> {
                            db.outbox().updateState(outboxId, OutboxState.CANCELLED)
                            item.capturedId?.let {
                                db.captured().updateOutcome(
                                    it, MatchState.IGNORED, item.ruleId, outboxId, "Undone on the phone",
                                )
                            }
                            container.notifier.cancelCaptured(outboxId)
                        }

                        else -> container.notifier.showProblem(
                            outboxId,
                            "Already sent to the web app — reject it on the Pending page instead.",
                        )
                    }

                    ACTION_POST_NOW -> {
                        db.outbox().updateState(outboxId, OutboxState.QUEUED, notBefore = 0)
                        container.notifier.cancelCaptured(outboxId)
                        SyncScheduler.kickOutbox(context, 0)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_UNDO = "ch.marty.finreader.UNDO"
        const val ACTION_POST_NOW = "ch.marty.finreader.POST_NOW"
        const val EXTRA_OUTBOX_ID = "outbox_id"
    }
}
