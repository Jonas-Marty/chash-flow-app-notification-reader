package ch.marty.finreader

import ch.marty.finreader.data.db.Rule
import ch.marty.finreader.data.db.TxTypeMode
import ch.marty.finreader.domain.MatchOutcome
import ch.marty.finreader.domain.NotificationInput
import ch.marty.finreader.domain.RuleEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {

    private fun rule(
        pattern: String = """(?<currency>CHF|EUR)\s*(?<amount>[\d'.,]+).*?an\s+(?<merchant>[^\n]+)""",
        block: Rule.() -> Rule = { this },
    ) = Rule(
        id = "r1",
        name = "Twint",
        packageName = "ch.twint.payment",
        textPattern = pattern,
        sourceAccountId = "acc-1",
        externalSource = "twint",
    ).block()

    private fun input(title: String?, body: String?, pkg: String = "ch.twint.payment") =
        NotificationInput(pkg, "Twint", title, body, 1_756_800_000_000)

    @Test
    fun `extracts amount merchant and description`() {
        val outcome = RuleEngine.evaluate(
            input("Zahlung erfolgreich", "CHF 12.50 an Coop Luzern"),
            listOf(rule()),
        )
        val matched = outcome as MatchOutcome.Matched
        assertEquals(1250L, matched.extraction.amountCents)
        assertEquals("CHF", matched.extraction.currency)
        assertEquals("Coop Luzern", matched.extraction.merchant)
        assertEquals("Coop Luzern", matched.extraction.description)
        assertEquals("expense", matched.extraction.type)
    }

    @Test
    fun `rules of another package are never considered`() {
        val outcome = RuleEngine.evaluate(
            input("Zahlung", "CHF 12.50 an Coop", pkg = "com.revolut.revolut"),
            listOf(rule()),
        )
        assertTrue(outcome is MatchOutcome.NoMatch)
    }

    @Test
    fun `exclude pattern wins over the match`() {
        val outcome = RuleEngine.evaluate(
            input("Zahlungsanfrage", "CHF 12.50 an Coop Luzern"),
            listOf(rule { copy(excludePattern = "Zahlungsanfrage") }),
        )
        assertTrue(outcome is MatchOutcome.Ignored)
    }

    @Test
    fun `direction can be driven by a pattern`() {
        val incomeRule = rule {
            copy(txTypeMode = TxTypeMode.FROM_PATTERN, incomePattern = "erhalten|Gutschrift")
        }
        val income = RuleEngine.evaluate(
            input("Geld erhalten", "CHF 20.00 an Jonas"),
            listOf(incomeRule),
        ) as MatchOutcome.Matched
        assertEquals("income", income.extraction.type)

        val expense = RuleEngine.evaluate(
            input("Bezahlt", "CHF 20.00 an Coop"),
            listOf(incomeRule),
        ) as MatchOutcome.Matched
        assertEquals("expense", expense.extraction.type)
    }

    @Test
    fun `lower priority rule wins`() {
        val specific = rule { copy(id = "specific", name = "specific", priority = 10) }
        val general = rule { copy(id = "general", name = "general", priority = 20) }
        val outcome = RuleEngine.evaluate(
            input("Zahlung", "CHF 12.50 an Coop"),
            listOf(general, specific),
        ) as MatchOutcome.Matched
        assertEquals("specific", outcome.rule.id)
    }

    @Test
    fun `an unparsable amount is reported rather than posted`() {
        val outcome = RuleEngine.evaluate(
            input("Zahlung", "CHF --- an Coop"),
            listOf(rule(pattern = """CHF\s*(?<amount>[^\s]+)""")),
        )
        assertTrue(outcome is MatchOutcome.Failed)
    }

    @Test
    fun `an invalid regex fails loudly instead of crashing`() {
        val outcome = RuleEngine.evaluate(input("x", "y"), listOf(rule(pattern = "(?<amount>[")))
        assertTrue(outcome is MatchOutcome.Failed)
    }

    @Test
    fun `revolut style notification can pick the settled chf amount`() {
        // Revolut shows both currencies; the rule chooses which one is posted.
        val revolut = Rule(
            id = "rev",
            name = "Revolut",
            packageName = "com.revolut.revolut",
            textPattern = """(?<origCurrency>€|EUR)\s?(?<origAmount>[\d'.,]+).*?CHF\s?(?<amount>[\d'.,]+)""",
            sourceAccountId = "acc-chf",
            externalSource = "revolut",
            currencyGroup = null,
            defaultCurrency = "CHF",
            descriptionTemplate = "{merchant}",
            noteTemplate = "Original: {origCurrency} {origAmount}",
            merchantGroup = null,
        )
        val outcome = RuleEngine.evaluate(
            NotificationInput(
                "com.revolut.revolut",
                "Revolut",
                "Payment at Lidl",
                "€12.50 spent · CHF 11.80",
                1_756_800_000_000,
            ),
            listOf(revolut),
        ) as MatchOutcome.Matched

        assertEquals(1180L, outcome.extraction.amountCents)
        assertEquals("CHF", outcome.extraction.currency)
        assertEquals("Original: € 12.50", outcome.extraction.note)
    }
}
