package ch.marty.finreader.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Where a payment happened, from the platform's own [LocationManager].
 *
 * Deliberately not Google Play Services: a sideloaded personal APK has no
 * reason to pull in that dependency, and the fused provider's advantage
 * (better indoor fixes) is small next to what actually limits us — that a
 * payment notification arrives while the phone is indoors at a till, where
 * GPS rarely fixes at all and the answer comes from WiFi and cell towers.
 *
 * So the accuracy to expect is tens of metres, not single digits. That is
 * enough to pick the right branch out of a list of known merchant locations,
 * which is what it is for; it is not enough to identify a shop on its own.
 */
class LocationCapture(private val context: Context) {

    /** False when neither location permission has been granted. */
    fun isPermitted(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    /**
     * Required to read location from the notification listener and the outbox
     * worker, both of which are background. Android 11+ refuses to grant it
     * from a dialog, so the UI sends the user to system settings.
     */
    fun hasBackgroundPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    /** Instant and free, but possibly hours old and somewhere else entirely. */
    fun lastKnown(): Fix? {
        if (!isPermitted()) return null
        val manager = manager() ?: return null
        return providers(manager)
            .mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }
            .map { it.toFix() }
            .bestOf()
    }

    /**
     * Waits up to [timeoutMs] for a fresh fix, returning the best one seen, or
     * null if nothing arrived. Every provider is asked at once: indoors the
     * network provider usually answers in a second or two while GPS never
     * does, and outdoors the reverse gives a far better result.
     */
    suspend fun fresh(timeoutMs: Long): Fix? {
        if (!isPermitted()) return null
        val manager = manager() ?: return null
        val providers = providers(manager)
        if (providers.isEmpty()) return null

        // Held outside the timeout so a fix that arrived just too late to beat
        // the accuracy bar is still returned instead of being thrown away.
        val best = AtomicReference<Fix?>(null)
        val listeners = mutableListOf<LocationListener>()

        fun stop() {
            synchronized(listeners) {
                listeners.forEach { runCatching { manager.removeUpdates(it) } }
                listeners.clear()
            }
        }

        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    providers.forEach { provider ->
                        val listener = LocationListener { location ->
                            val winner = best.updateAndGet { current ->
                                listOfNotNull(current, location.toFix()).bestOf()
                            }
                            // Good enough to place a merchant; waiting longer
                            // for a tighter fix costs more than it is worth.
                            if ((winner?.accuracyM ?: Float.MAX_VALUE) <= GOOD_ENOUGH_M) {
                                if (continuation.isActive) continuation.resume(Unit)
                            }
                        }
                        synchronized(listeners) { listeners += listener }
                        runCatching {
                            manager.requestLocationUpdates(
                                provider,
                                0L,
                                0f,
                                listener,
                                Looper.getMainLooper(),
                            )
                        }
                    }
                    continuation.invokeOnCancellation { stop() }
                }
            }
        } finally {
            stop()
        }
        return best.get()
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun manager(): LocationManager? =
        ContextCompat.getSystemService(context, LocationManager::class.java)

    private fun providers(manager: LocationManager): List<String> =
        runCatching { manager.getProviders(true) }.getOrDefault(emptyList())
            .filter { it != LocationManager.PASSIVE_PROVIDER }

    /** A fix worth storing: a point, how wrong it may be, and when it was taken. */
    data class Fix(
        val latitude: Double,
        val longitude: Double,
        val accuracyM: Float?,
        val takenAt: Long,
    )

    private companion object {
        const val GOOD_ENOUGH_M = 50f

        fun Location.toFix() = Fix(
            latitude = latitude,
            longitude = longitude,
            accuracyM = if (hasAccuracy()) accuracy else null,
            takenAt = time,
        )

        /** Most accurate wins; an unknown accuracy loses to any known one. */
        fun List<Fix>.bestOf(): Fix? =
            minByOrNull { it.accuracyM ?: Float.MAX_VALUE }
    }
}
