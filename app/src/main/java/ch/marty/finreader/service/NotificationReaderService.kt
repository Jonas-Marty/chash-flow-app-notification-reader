package ch.marty.finreader.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import ch.marty.finreader.AppContainer
import ch.marty.finreader.domain.NotificationInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The only component that ever sees notification content. Anything from a
 * package the user has not enabled is dropped here, before it reaches storage.
 */
class NotificationReaderService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        if (sbn.isOngoing) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString("\n") { it.toString() }
        val body = bigText ?: lines ?: text
        if (title.isNullOrBlank() && body.isNullOrBlank()) return

        val input = NotificationInput(
            packageName = sbn.packageName,
            appLabel = sbn.packageName,
            title = title,
            body = body,
            postedAt = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis(),
        )

        val container = AppContainer.get(applicationContext)
        scope.launch {
            runCatching { container.captureProcessor.process(input) }
        }
    }

    override fun onListenerConnected() {
        AppContainer.get(applicationContext).notifier.ensureChannels()
    }
}
