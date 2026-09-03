package ch.marty.finreader.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ch.marty.finreader.R
import ch.marty.finreader.ui.MainActivity

class Notifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        system.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CAPTURED,
                context.getString(R.string.channel_feedback_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.channel_feedback_desc) },
        )
        system.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROBLEM,
                context.getString(R.string.channel_problem_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.channel_problem_desc) },
        )
    }

    fun showCaptured(
        outboxId: Long,
        amount: String,
        currency: String,
        description: String?,
        held: Boolean,
        undoable: Boolean,
    ) {
        val title = if (held) "Waiting to send · $currency $amount" else "Captured $currency $amount"
        val builder = NotificationCompat.Builder(context, CHANNEL_CAPTURED)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title)
            .setContentText(description.orEmpty())
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)

        if (undoable) {
            builder.addAction(
                android.R.drawable.ic_menu_revert,
                "Undo",
                action(FeedbackActionReceiver.ACTION_UNDO, outboxId),
            )
        }
        if (held) {
            builder.addAction(
                android.R.drawable.ic_menu_send,
                "Send now",
                action(FeedbackActionReceiver.ACTION_POST_NOW, outboxId),
            )
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Discard",
                action(FeedbackActionReceiver.ACTION_UNDO, outboxId),
            )
        }
        notify(capturedNotificationId(outboxId), builder.build())
    }

    fun showProblem(outboxId: Long, text: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_PROBLEM)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Transaction not sent")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
        notify(problemNotificationId(outboxId), builder.build())
    }

    fun cancelCaptured(outboxId: Long) = manager.cancel(capturedNotificationId(outboxId))

    private fun notify(id: Int, notification: android.app.Notification) {
        // POST_NOTIFICATIONS may be denied; feedback is a nicety, never a failure.
        runCatching { manager.notify(id, notification) }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun action(action: String, outboxId: Long): PendingIntent {
        val intent = Intent(context, FeedbackActionReceiver::class.java)
            .setAction(action)
            .putExtra(FeedbackActionReceiver.EXTRA_OUTBOX_ID, outboxId)
        return PendingIntent.getBroadcast(
            context,
            (action.hashCode() xor outboxId.toInt()),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL_CAPTURED = "captured"
        const val CHANNEL_PROBLEM = "problems"

        fun capturedNotificationId(outboxId: Long): Int = (outboxId % 100_000).toInt() + 1_000
        fun problemNotificationId(outboxId: Long): Int = (outboxId % 100_000).toInt() + 500_000
    }
}
