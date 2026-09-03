package ch.marty.finreader

import ch.marty.finreader.data.db.NumberFormatStyle
import ch.marty.finreader.domain.AmountParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmountParserTest {

    @Test
    fun `reads plain swiss amounts`() {
        assertEquals(1250L, AmountParser.parseToCents("CHF 12.50"))
        assertEquals(1250L, AmountParser.parseToCents("12.50"))
        assertEquals(500L, AmountParser.parseToCents("Fr. 5.00"))
    }

    @Test
    fun `reads apostrophe grouping`() {
        assertEquals(123450L, AmountParser.parseToCents("1'234.50"))
        assertEquals(123450L, AmountParser.parseToCents("1’234.50"))
        assertEquals(1234567L, AmountParser.parseToCents("CHF 12'345.67"))
    }

    @Test
    fun `reads european decimals`() {
        assertEquals(1250L, AmountParser.parseToCents("EUR 12,50"))
        assertEquals(123456L, AmountParser.parseToCents("1.234,56"))
        assertEquals(123456L, AmountParser.parseToCents("1.234,56", NumberFormatStyle.EU))
    }

    @Test
    fun `treats three trailing digits as grouping`() {
        assertEquals(123400L, AmountParser.parseToCents("1.234"))
        assertEquals(123400L, AmountParser.parseToCents("1,234"))
    }

    @Test
    fun `handles the swiss whole-franc shorthand`() {
        assertEquals(1200L, AmountParser.parseToCents("CHF 12.-"))
        assertEquals(1200L, AmountParser.parseToCents("12.–"))
    }

    @Test
    fun `ignores the sign because direction comes from the rule`() {
        assertEquals(500L, AmountParser.parseToCents("-CHF 5.00"))
    }

    @Test
    fun `rejects anything without a usable amount`() {
        assertNull(AmountParser.parseToCents("no digits here"))
        assertNull(AmountParser.parseToCents("CHF 0.00"))
        assertNull(AmountParser.parseToCents(""))
    }

    @Test
    fun `forced styles win over the heuristic`() {
        assertEquals(123450L, AmountParser.parseToCents("1'234.50", NumberFormatStyle.SWISS))
        assertEquals(1250L, AmountParser.parseToCents("12,50", NumberFormatStyle.EU))
    }

    @Test
    fun `formats cents the way the api expects`() {
        assertEquals("12.50", AmountParser.centsToPlainString(1250))
        assertEquals("0.05", AmountParser.centsToPlainString(5))
        assertEquals("1234.00", AmountParser.centsToPlainString(123400))
    }
}
