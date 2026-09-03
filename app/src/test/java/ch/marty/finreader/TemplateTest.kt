package ch.marty.finreader

import ch.marty.finreader.domain.Template
import org.junit.Assert.assertEquals
import org.junit.Test

class TemplateTest {

    @Test
    fun `fills placeholders and drops unknown ones`() {
        assertEquals(
            "Coop Luzern",
            Template.render("{merchant}", mapOf("merchant" to "Coop Luzern")),
        )
        assertEquals(
            "Twint Coop",
            Template.render("Twint {merchant} {missing}", mapOf("merchant" to "Coop")),
        )
    }

    @Test
    fun `collapses the gaps left by empty placeholders`() {
        assertEquals("a b", Template.render("a {gone} b", emptyMap()))
    }

    @Test
    fun `lists the named groups of a pattern`() {
        assertEquals(
            listOf("currency", "amount", "merchant"),
            Template.namedGroupsOf("""(?<currency>CHF)\s(?<amount>[\d.]+) an (?<merchant>.+)"""),
        )
    }
}
