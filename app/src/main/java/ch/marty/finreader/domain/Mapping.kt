package ch.marty.finreader.domain

import ch.marty.finreader.data.db.CapturedNotification

/** The stored capture, re-shaped for the rule engine (used by the rule tester). */
fun CapturedNotification.toInput(): NotificationInput = NotificationInput(
    packageName = packageName,
    appLabel = appLabel,
    title = title,
    body = bigText ?: text,
    postedAt = postedAt,
)
