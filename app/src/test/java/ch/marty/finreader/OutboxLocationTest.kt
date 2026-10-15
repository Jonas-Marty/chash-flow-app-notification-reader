package ch.marty.finreader

import ch.marty.finreader.data.LocationCapture
import ch.marty.finreader.data.OutboxLocation
import ch.marty.finreader.data.api.PendingTransactionPayload
import ch.marty.finreader.data.db.OutboxItem
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

    private fun outboxItem(createdAt: Long) = OutboxItem(
        capturedId = 1,
        ruleId = "rule",
        externalRef = "twint:abc",
        externalRefBase = "twint:abc",
        payloadJson = "{}",
        amountCents = 1250,
        currency = "CHF",
        description = "Coffee",
        createdAt = createdAt,
    )

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
    fun `books a refinement when the fix that went out was vague`() {
        assertTrue(OutboxLocation.refineAt(now, 300f, now) > now)
    }

    @Test
    fun `books a refinement when nothing went out at all`() {
        assertTrue(OutboxLocation.refineAt(now, null, now) > now)
    }

    @Test
    fun `books nothing when the fix that went out is already tight`() {
        assertEquals(0L, OutboxLocation.refineAt(now, 12f, now))
    }

    @Test
    fun `books nothing once the payment is too old to place`() {
        val late = now + OutboxLocation.REFINE_DEADLINE_MS + 1
        assertEquals(0L, OutboxLocation.refineAt(now, 300f, late))
    }

    @Test
    fun `a refinement due in the past is taken now rather than dropped`() {
        // The post itself was delayed — offline, or a long undo window.
        val afterTheIdealMoment = now + OutboxLocation.REFINE_AFTER_MS + 10_000
        assertEquals(
            afterTheIdealMoment,
            OutboxLocation.refineAt(now, 300f, afterTheIdealMoment),
        )
    }

    @Test
    fun `refuses to refine a payment the phone has since walked away from`() {
        val item = outboxItem(createdAt = now)
        assertTrue(!OutboxLocation.isTooLateToRefine(item, now + 60_000))
        assertTrue(OutboxLocation.isTooLateToRefine(item, now + 60 * 60 * 1000))
    }

    @Test
    fun `improvement matches the server's own bar`() {
        // The server replaces a point only for at least 10 m and a quarter.
        assertTrue(OutboxLocation.isMeaningfulImprovement(500f, 30f))
        assertTrue(!OutboxLocation.isMeaningfulImprovement(45f, 44f))
        // A quarter tighter but under 10 m of actual gain.
        assertTrue(!OutboxLocation.isMeaningfulImprovement(20f, 14f))
        // 10 m of gain but not a quarter.
        assertTrue(!OutboxLocation.isMeaningfulImprovement(100f, 90f))
    }

    @Test
    fun `an unstated accuracy is never an improvement`() {
        assertTrue(!OutboxLocation.isMeaningfulImprovement(500f, null))
        assertTrue(!OutboxLocation.isMeaningfulImprovement(null, null))
    }

    @Test
    fun `a point is worth offering when the server holds none`() {
        assertTrue(OutboxLocation.isMeaningfulImprovement(null, 80f))
    }

    @Test
    fun `says why no location was attached`() {
        assertEquals(
            "No location: location permission is not granted.",
            OutboxLocation.sentNote(null, "location permission is not granted"),
        )
        assertEquals(
            "No location: no fix accurate enough to be worth storing.",
            OutboxLocation.sentNote(null, null),
        )
        assertEquals("Location sent, accurate to 80 m.", OutboxLocation.sentNote(fix(80f, 0), null))
        assertEquals("Location refined to 35 m.", OutboxLocation.refinedNote(35f, accepted = true))
        assertEquals(
            "Offered a 35 m fix; the server kept the original.",
            OutboxLocation.refinedNote(35f, accepted = false),
        )
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
