package ch.marty.finreader.data

import ch.marty.finreader.data.api.PendingTransactionPayload
import ch.marty.finreader.data.db.OutboxItem
import ch.marty.finreader.data.prefs.Settings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Decides which fix, if any, is attached to a payment on its way out.
 *
 * The capture happens twice: once when the notification arrives (instant, from
 * [LocationCapture.lastKnown]) and once just before posting, after the undo
 * window has given the phone a few more seconds to settle on something better.
 * Everything here is pure so the gates can be tested without a device.
 */
object OutboxLocation {

    private val json = Json { explicitNulls = false; encodeDefaults = true; ignoreUnknownKeys = true }

    /** How long the worker is willing to block a post waiting for a better fix. */
    const val REFINE_TIMEOUT_MS = 8_000L

    /**
     * True when the fix in hand is already worth posting, so the worker can
     * skip the wait entirely — the common case for a payment made outdoors.
     */
    fun isGoodEnough(fix: LocationCapture.Fix?, now: Long): Boolean {
        if (fix == null) return false
        val accuracy = fix.accuracyM ?: return false
        return accuracy <= GOOD_ENOUGH_M && now - fix.takenAt <= FRESH_MS
    }

    /**
     * The better of the two fixes, or null if neither passes the age and
     * accuracy gates. A fix that fails them is worse than no location at all:
     * the web app cannot tell "roughly here" from "here" once it is stored.
     */
    fun choose(
        stored: LocationCapture.Fix?,
        fresh: LocationCapture.Fix?,
        now: Long,
        settings: Settings,
    ): LocationCapture.Fix? = listOfNotNull(stored, fresh)
        .filter { now - it.takenAt <= settings.locationMaxAgeMillis }
        .filter { (it.accuracyM ?: Float.MAX_VALUE) <= settings.locationMaxAccuracyM }
        .minByOrNull { it.accuracyM ?: Float.MAX_VALUE }

    /** The fix an item is already carrying, if it carries one. */
    fun storedFix(item: OutboxItem): LocationCapture.Fix? {
        val latitude = item.latitude ?: return null
        val longitude = item.longitude ?: return null
        return LocationCapture.Fix(latitude, longitude, item.locationAccuracyM, item.locationAt)
    }

    /**
     * Puts the fix into the stored payload. Done here rather than at capture
     * time because the payload is frozen once written, and the location is the
     * one field that keeps improving until the moment it is sent.
     */
    fun inject(payloadJson: String, fix: LocationCapture.Fix?): String {
        val payload = runCatching {
            json.decodeFromString<PendingTransactionPayload>(payloadJson)
        }.getOrNull() ?: return payloadJson
        val updated = payload.copy(
            latitude = fix?.latitude,
            longitude = fix?.longitude,
            locationAccuracyM = fix?.accuracyM,
            locationSource = if (fix != null) "device" else null,
        )
        return json.encodeToString(updated)
    }

    /** Matches [LocationCapture]'s own bar: enough to place a merchant. */
    private const val GOOD_ENOUGH_M = 50f

    /** Younger than this and the phone has not meaningfully moved. */
    private const val FRESH_MS = 60_000L
}
