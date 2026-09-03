package ch.marty.finreader

import ch.marty.finreader.domain.ExternalRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ExternalRefTest {

    @Test
    fun `same payment yields the same key regardless of whitespace or case`() {
        val a = ExternalRef.base("ch.twint.payment", "2026-09-03", 1250, "CHF 12.50 an Coop")
        val b = ExternalRef.base("ch.twint.payment", "2026-09-03", 1250, "chf  12.50\nan   coop")
        assertEquals(a, b)
    }

    @Test
    fun `a different day or amount is a different payment`() {
        val base = ExternalRef.base("ch.twint.payment", "2026-09-03", 1250, "CHF 12.50 an Coop")
        assertNotEquals(base, ExternalRef.base("ch.twint.payment", "2026-09-04", 1250, "CHF 12.50 an Coop"))
        assertNotEquals(base, ExternalRef.base("ch.twint.payment", "2026-09-03", 1350, "CHF 13.50 an Coop"))
    }

    @Test
    fun `repeats of the same payment get their own key`() {
        val base = ExternalRef.base("ch.twint.payment", "2026-09-03", 1250, "CHF 12.50 an Coop")
        assertEquals(base, ExternalRef.withSequence(base, 0))
        assertEquals("$base-1", ExternalRef.withSequence(base, 1))
        assertNotEquals(ExternalRef.withSequence(base, 1), ExternalRef.withSequence(base, 2))
    }

    @Test
    fun `key fits the api column`() {
        val ref = ExternalRef.withSequence(
            ExternalRef.base("ch.twint.payment", "2026-09-03", 1250, "CHF 12.50 an Coop"),
            3,
        )
        assert(ref.length <= 200)
    }
}
