package ch.marty.finreader

import ch.marty.finreader.data.LocationCapture
import ch.marty.finreader.data.OutboxLocation
import ch.marty.finreader.data.api.PendingTransactionPayload
import ch.marty.finreader.data.prefs.Settings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxLocationTest {

    private val settings = Settings()
    private val now = 1_700_000_000_000L
    private val json = Json { explicitNulls = false; encodeDefaults = true; ignoreUnknownKeys = true }

    private fun fix(accuracy: Float?, ageMs: Long) =
        LocationCapture.Fix(47.1, 8.2, accuracy, now - ageMs)

    @Test
    fun `prefers the more accurate fix`() {
        val chosen = OutboxLocation.choose(fix(120f, 0), fix(18f, 0), now, settings)
        assertEquals(18f, chosen!!.accuracyM!!, 0f)
    }

    @Test
    fun `keeps the stored fix when the fresh one is worse`() {
        val chosen = OutboxLocation.choose(fix(20f, 0), fix(400f, 0), now, settings)
        assertEquals(20f, chosen!!.accuracyM!!, 0f)
    }

    @Test
    fun `drops a fix taken too long ago`() {
        // The card notification that arrives once you are already home.
        assertNull(OutboxLocation.choose(fix(10f, 60 * 60 * 1000), null, now, settings))
    }

    @Test
    fun `drops a fix that only says which town`() {
        assertNull(OutboxLocation.choose(fix(5_000f, 0), null, now, settings))
    }

    @Test
    fun `drops a fix of unknown accuracy`() {
        assertNull(OutboxLocation.choose(fix(null, 0), null, now, settings))
    }

    @Test
    fun `is good enough only for a recent and tight fix`() {
        assertTrue(OutboxLocation.isGoodEnough(fix(20f, 5_000), now))
        assertTrue(!OutboxLocation.isGoodEnough(fix(20f, 5 * 60 * 1000), now))
        assertTrue(!OutboxLocation.isGoodEnough(fix(200f, 0), now))
        assertTrue(!OutboxLocation.isGoodEnough(null, now))
    }

    @Test
    fun `injects the fix without disturbing the rest of the payload`() {
        val payload = PendingTransactionPayload(
            sourceAccountId = "acc",
            amount = "12.50",
            type = "expense",
            occurredOn = "2026-09-03",
            description = "Coffee",
            externalRef = "twint:abc",
        )
        val result = json.decodeFromString<PendingTransactionPayload>(
            OutboxLocation.inject(json.encodeToString(payload), fix(12f, 0)),
        )
        assertEquals("Coffee", result.description)
        assertEquals("twint:abc", result.externalRef)
        assertEquals(47.1, result.latitude!!, 1e-9)
        assertEquals(12f, result.locationAccuracyM!!, 0f)
        assertEquals("device", result.locationSource)
    }

    @Test
    fun `injecting no fix leaves the payload without a location`() {
        val payload = PendingTransactionPayload(
            sourceAccountId = "acc",
            amount = "1.00",
            type = "expense",
            occurredOn = "2026-09-03",
            latitude = 47.0,
            longitude = 8.0,
            locationSource = "device",
        )
        val result = json.decodeFromString<PendingTransactionPayload>(
            OutboxLocation.inject(json.encodeToString(payload), null),
        )
        assertNull(result.latitude)
        assertNull(result.longitude)
        assertNull(result.locationSource)
    }
}
