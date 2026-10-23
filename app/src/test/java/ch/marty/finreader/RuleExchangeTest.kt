package ch.marty.finreader

import ch.marty.finreader.data.db.CategoryCache
import ch.marty.finreader.data.db.LocationMode
import ch.marty.finreader.data.db.Rule
import ch.marty.finreader.data.db.RuleTarget
import ch.marty.finreader.data.db.TxTypeMode
import ch.marty.finreader.domain.MatchOutcome
import ch.marty.finreader.domain.NotificationInput
import ch.marty.finreader.domain.RuleEngine
import ch.marty.finreader.domain.RuleExchange
import ch.marty.finreader.domain.RulePrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleExchangeTest {

    private val payment = NotificationInput(
        packageName = "ch.viseca.tcmobile",
        appLabel = "TC Mobile",
        title = "TC Mobile",
        body = "Aufgrund eines Zahlungseingangs von 245.70 CHF beträgt der verfügbare Betrag " +
            "auf Ihrer Kreditkarte \"3361\" neu 4'795.35 CHF.",
        postedAt = 1_790_000_000_000,
    )

    private val base = Rule(
        id = "r1",
        name = "TC Mobile",
        packageName = "ch.viseca.tcmobile",
        textPattern = "x",
        sourceAccountId = "acc-1",
        externalSource = "tcmobile",
    )

    private val categories = listOf(
        CategoryCache("c1", "Groceries", "Household"),
        CategoryCache("c2", "Fees", null),
    )

    /** How a chat actually answers: prose, a fence, and backslashes JSON-escaped. */
    private val reply = """
        Here is a rule for the payment notification:

        ```json
        {
          "finreader_rule": 1,
          "rules": [
            {
              "name": "Credit card repayment",
              "target": "recurring_bill",
              "text_pattern": "Zahlungseingangs\\s+von\\s+(?<amount>\\d[\\d'’.,]*\\d)\\s*(?<currency>[A-Z]{3})",
              "exclude_patterns": [],
              "merchant_group": null,
              "number_format": "auto",
              "expected_matches": [{ "sample": 7, "amount": "245.70" }],
              "explanation": "Anchors on Zahlungseingangs so the balance [4'795.35] is never read."
            }
          ]
        }
        ```

        Let me know if you need anything else!
    """.trimIndent()

    @Test
    fun `a fenced reply with prose around it becomes a rule that reads the paid amount`() {
        val parsed = RuleExchange.parse(reply) as RuleExchange.ParseResult.Ok
        val imported = RuleExchange.apply(parsed.rules.single(), base, categories)

        assertEquals(RuleTarget.RECURRING_BILL, imported.rule.target)
        assertNull(imported.rule.merchantGroup)
        assertEquals(mapOf(7L to "245.70"), imported.expectations)
        assertTrue(imported.warnings.any { "recurring rule" in it })

        val outcome = RuleEngine.apply(
            imported.rule.copy(recurringRuleId = "rr-1"),
            payment,
        ) as MatchOutcome.Matched
        assertEquals(24_570L, outcome.extraction.amountCents)
    }

    @Test
    fun `an import never leaves Send automatically on`() {
        val parsed = RuleExchange.parse(reply) as RuleExchange.ParseResult.Ok
        val imported = RuleExchange.apply(parsed.rules.single(), base.copy(autoPost = true), categories)
        assertFalse(imported.rule.autoPost)
    }

    @Test
    fun `absent keys keep the rule's values, null clears them`() {
        val parsed = RuleExchange.parse(
            """{"text_pattern": "(?<amount>\\d+)", "title_pattern": null}""",
        ) as RuleExchange.ParseResult.Ok
        val imported = RuleExchange.apply(
            parsed.rules.single(),
            base.copy(titlePattern = "TC", noteTemplate = "#card", locationMode = LocationMode.NEVER),
            categories,
        )
        assertNull(imported.rule.titlePattern)
        assertEquals("#card", imported.rule.noteTemplate)
        assertEquals(LocationMode.NEVER, imported.rule.locationMode)
        assertEquals("acc-1", imported.rule.sourceAccountId)
    }

    @Test
    fun `an unknown enum value is reported, not guessed`() {
        val parsed = RuleExchange.parse(
            """{"text_pattern": "(?<amount>\\d+)", "location": "sometimes"}""",
        ) as RuleExchange.ParseResult.Ok
        val imported = RuleExchange.apply(parsed.rules.single(), base, categories)
        assertEquals(LocationMode.ATTACH, imported.rule.locationMode)
        assertTrue(imported.warnings.any { "sometimes" in it })
    }

    @Test
    fun `categories are matched by name, an unknown one is left to the user`() {
        fun categoryOf(name: String) = RuleExchange.apply(
            (RuleExchange.parse("""{"text_pattern": "(?<amount>\\d+)", "category": "$name"}""")
                as RuleExchange.ParseResult.Ok).rules.single(),
            base,
            categories,
        )
        assertEquals("c1", categoryOf("groceries").rule.categoryId)
        assertEquals("c1", categoryOf("Household · Groceries").rule.categoryId)
        val unknown = categoryOf("Restaurants")
        assertNull(unknown.rule.categoryId)
        assertTrue(unknown.warnings.any { "Restaurants" in it })
    }

    @Test
    fun `a pattern without the amount group is flagged`() {
        val parsed = RuleExchange.parse("""[{"text_pattern": "(?<total>\\d+)"}]""") as RuleExchange.ParseResult.Ok
        val imported = RuleExchange.apply(parsed.rules.single(), base, categories)
        assertTrue(imported.warnings.any { "(?<amount>" in it })
    }

    @Test
    fun `text without a rule is an error, not an empty import`() {
        assertTrue(RuleExchange.parse("Sorry, I can't help with that.") is RuleExchange.ParseResult.Error)
        assertTrue(RuleExchange.parse("""{"name": "x"}""") is RuleExchange.ParseResult.Error)
        assertTrue(RuleExchange.parse("""{"finreader_rule": 9, "rules": [{"text_pattern": "x"}]}""") is RuleExchange.ParseResult.Error)
    }

    @Test
    fun `an exported rule reads back as the same rule`() {
        val rule = base.copy(
            textPattern = """(?<currency>CHF)\s*(?<amount>[\d'’.,]+).*?bei\s+(?<merchant>[^\n]+)""",
            excludePattern = "Zahlungseingang\nLimite",
            noteTemplate = "#{merchant:d}",
            locationMode = LocationMode.UNLESS_PATTERN,
            locationExcludePattern = "online",
            categoryId = "c2",
            categoryName = "Fees",
            autoPost = false,
        )
        val parsed = RuleExchange.parse(RuleExchange.exportDocument(listOf(rule))) as RuleExchange.ParseResult.Ok
        val back = RuleExchange.apply(parsed.rules.single(), base.copy(autoPost = false), categories)
        assertEquals(rule, back.rule)
    }

    @Test
    fun `the prompt spells out characters a reader cannot see`() {
        val withNbsp = payment.copy(body = "Betrag CHF 245.70")
        val json = RulePrompt.samplesJson(listOf(RulePrompt.Sample(7, withNbsp)))
        assertTrue(json, "CHF\\u00a0245.70" in json)
        assertFalse(json.contains(' '))
    }

    @Test
    fun `the prompt carries the samples, the categories and the answer format`() {
        val prompt = RulePrompt.build(
            RulePrompt.Request(
                appLabel = "TC Mobile",
                packageName = "ch.viseca.tcmobile",
                samples = listOf(RulePrompt.Sample(7, payment)),
                focusSampleId = 7,
                categoryNames = listOf("Groceries", "Fees"),
                current = null,
                siblings = emptyList(),
            ),
        )
        assertTrue("Zahlungseingangs von 245.70 CHF" in prompt)
        assertTrue("Groceries, Fees" in prompt)
        assertTrue("\"finreader_rule\": 1" in prompt)
        // The example answer in the prompt must itself be importable.
        val example = prompt.substringAfterLast("## Answer")
        assertTrue(RuleExchange.parse(example) is RuleExchange.ParseResult.Ok)
    }

    @Test
    fun `a cash withdrawal imports as a transfer that still needs its destination`() {
        val spec = (RuleExchange.parse(
            """
            {"finreader_rule": 1, "rules": [{
              "name": "debiX+ cash withdrawal",
              "target": "pending_transaction",
              "text_pattern": "(?<amount>\\d[\\d'’.,]*\\d)\\s*(?<currency>CHF|EUR)\\s*,[^,\\n]*,\\s*(?<merchant>[^,\\n]+?)\\s+\\d{1,2}\\.\\d{1,2}\\.\\d{2,4}",
              "title_pattern": "Cash withdrawal",
              "direction": "transfer",
              "category": null,
              "external_source": "debix"
            }]}
            """.trimIndent(),
        ) as RuleExchange.ParseResult.Ok).rules.single()
        val imported = RuleExchange.apply(spec, base, categories)

        assertEquals(TxTypeMode.TRANSFER, imported.rule.txTypeMode)
        assertTrue(imported.warnings.any { "transfer goes to" in it })

        val outcome = RuleEngine.apply(
            imported.rule.copy(destinationAccountId = "acc-cash"),
            NotificationInput(
                "com.sixgroup.debixplus", "debiX+", "Cash withdrawal",
                "100.00 CHF, VISA Debit ****7880, Raiffeisen 22.10.2026 22:11:18", 1_790_000_000_000,
            ),
        ) as MatchOutcome.Matched
        assertEquals("transfer", outcome.extraction.type)
        assertEquals(10000L, outcome.extraction.amountCents)
        assertEquals("Raiffeisen", outcome.extraction.merchant)
    }
}
