package ch.marty.finreader.data

import ch.marty.finreader.data.api.PendingTransactionPayload
import ch.marty.finreader.data.db.OutboxItem
import ch.marty.finreader.data.prefs.Settings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * Decides which fix, if any, is attached to a payment on its way out.
 *
 * The capture happens up to three times: once when the notification arrives
 * (instant, from [LocationCapture.lastKnown]), once just before posting, after
 * the undo window has given the phone a few more seconds to settle on something
 * better, and — because the server improves a pending row's location on a
 * repeat POST with the same `external_ref` — once more a minute or so later,
 * while the user is plausibly still standing in the same shop.
 *
 * Everything here is pure so the gates can be tested without a device.
 */
object OutboxLocation {

    private val json = Json { explicitNulls = false; encodeDefaults = true; ignoreUnknownKeys = true }

    /** How long the worker is willing to block a post waiting for a better fix. */
    const val REFINE_TIMEOUT_MS = 8_000L

    /**
     * When the one refinement attempt is made, measured from the notification
     * rather than from the post: what it is really waiting for is the phone to
     * stop moving and the WiFi scan to come back, which started at the till.
     */
    const val REFINE_AFTER_MS = 90_000L

    /**
     * After this, a refinement is refused outright. Not a tidiness rule — a
     * fix taken an hour later is where the phone is *now*, and posting it as
     * where the payment happened would be worse than sending nothing.
     */
    const val REFINE_DEADLINE_MS = 180_000L

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
     * When to make the single refinement attempt for a row just posted with
     * [sentAccuracyM], or 0 for none.
     *
     * A fix that is already tight is left alone: from 25 m the server's own bar
     * would demand 15 m or better to count, which is a GPS-outdoors number, and
     * the merchant is pinned at that range anyway. Waking the phone for it is
     * the kind of battery cost that gets a location feature switched off.
     */
    fun refineAt(capturedAt: Long, sentAccuracyM: Float?, now: Long): Long {
        if (sentAccuracyM != null && sentAccuracyM <= REFINE_WORTH_M) return 0
        val deadline = capturedAt + REFINE_DEADLINE_MS
        if (now >= deadline) return 0
        return (capturedAt + REFINE_AFTER_MS).coerceIn(now, deadline)
    }

    /** Past the deadline the phone has moved on and so has the user. */
    fun isTooLateToRefine(item: OutboxItem, now: Long): Boolean =
        now > item.createdAt + REFINE_DEADLINE_MS

    /**
     * Mirrors the server's own bar for replacing a stored point: at least 10 m
     * tighter *and* at least a quarter tighter. Applying it here too means a
     * redelivery that simply re-takes a fix and gets normal GPS jitter never
     * becomes a request.
     */
    fun isMeaningfulImprovement(sentAccuracyM: Float?, candidateAccuracyM: Float?): Boolean {
        // An accuracy the server is not told is not an improvement either way.
        if (candidateAccuracyM == null) return false
        // Nothing stored to improve on. Whether a repeat POST fills in a point
        // the row never had is not something the contract states; one request
        // finds out, and a "no" costs a deduplicated 200.
        if (sentAccuracyM == null) return true
        return sentAccuracyM - candidateAccuracyM >= MIN_GAIN_M &&
            candidateAccuracyM <= sentAccuracyM * (1f - MIN_GAIN_FRACTION)
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

    /** Why no fix was even attempted, for the Inbox to show straight away. */
    fun blockedNote(reason: String): String = "No location: $reason."

    /** What the Inbox says about the location a payment left with. */
    fun sentNote(fix: LocationCapture.Fix?, unavailable: String?): String = when {
        unavailable != null -> blockedNote(unavailable)
        fix == null -> "No location: no fix accurate enough to be worth storing."
        else -> "Location sent, accurate to ${metres(fix.accuracyM)}."
    }

    /** And what it says once the one refinement attempt has been made. */
    fun refinedNote(accuracyM: Float?, accepted: Boolean): String = if (accepted) {
        "Location refined to ${metres(accuracyM)}."
    } else {
        "Offered a ${metres(accuracyM)} fix; the server kept the original."
    }

    private fun metres(accuracyM: Float?): String =
        accuracyM?.let { "${it.roundToInt()} m" } ?: "an unknown accuracy"

    /** Matches [LocationCapture]'s own bar: enough to place a merchant. */
    private const val GOOD_ENOUGH_M = 50f

    /** Tighter than this and a second attempt cannot clear the server's bar. */
    private const val REFINE_WORTH_M = 25f

    /** Younger than this and the phone has not meaningfully moved. */
    private const val FRESH_MS = 60_000L

    /** The server's own thresholds for accepting a better point. */
    private const val MIN_GAIN_M = 10f
    private const val MIN_GAIN_FRACTION = 0.25f
}
