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
    fun `dashes let a multi-word value survive as a hashtag`() {
        assertEquals(
            "#twint #Coop-City",
            Template.render("#twint #{merchant:d}", mapOf("merchant" to "Coop City")),
        )
        assertEquals(
            "#Coop_City",
            Template.render("#{merchant:u}", mapOf("merchant" to "Coop  City")),
        )
        assertEquals(
            "#CoopCity",
            Template.render("#{merchant:c}", mapOf("merchant" to "coop city")),
        )
    }

    @Test
    fun `modifiers chain left to right`() {
        assertEquals(
            "#coop-city",
            Template.render("#{merchant:dl}", mapOf("merchant" to "Coop City")),
        )
        assertEquals(
            "#Cafe-Muller",
            Template.render("#{merchant:ad}", mapOf("merchant" to "Caf\u00e9 & M\u00fcller")),
        )
    }

    @Test
    fun `an unmodified placeholder is untouched and an unknown modifier is ignored`() {
        assertEquals("Coop City", Template.render("{merchant}", mapOf("merchant" to "Coop City")))
        assertEquals("Coop City", Template.render("{merchant:z}", mapOf("merchant" to "Coop City")))
        assertEquals("", Template.render("{missing:d}", emptyMap()))
    }

    /** Pins the table in docs/DESIGN.md so the documentation cannot drift. */
    @Test
    fun `each documented modifier does what the design doc claims`() {
        val merchant = mapOf("merchant" to "Caf\u00e9 & M\u00fcller")
        assertEquals("Caf\u00e9-&-M\u00fcller", Template.render("{merchant:d}", merchant))
        assertEquals("Caf\u00e9_&_M\u00fcller", Template.render("{merchant:u}", merchant))
        assertEquals("Caf\u00e9&M\u00fcller", Template.render("{merchant:c}", merchant))
        assertEquals("caf\u00e9 & m\u00fcller", Template.render("{merchant:l}", merchant))
        assertEquals("Cafe Muller", Template.render("{merchant:a}", merchant))
        assertEquals("Cafe-Muller", Template.render("{merchant:ad}", merchant))
    }

    @Test
    fun `lists the named groups of a pattern`() {
        assertEquals(
            listOf("currency", "amount", "merchant"),
            Template.namedGroupsOf("""(?<currency>CHF)\s(?<amount>[\d.]+) an (?<merchant>.+)"""),
        )
    }
}
