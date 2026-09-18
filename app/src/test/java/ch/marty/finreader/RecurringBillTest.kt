package ch.marty.finreader

import ch.marty.finreader.data.api.RecurringProposalPayload
import ch.marty.finreader.data.api.RecurringProposalResponse
import ch.marty.finreader.data.api.RecurringRulesResponse
import ch.marty.finreader.data.db.LocationMode
import ch.marty.finreader.data.db.Rule
import ch.marty.finreader.data.db.RuleTarget
import ch.marty.finreader.domain.MatchOutcome
import ch.marty.finreader.domain.NotificationInput
import ch.marty.finreader.domain.RuleEngine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class RecurringBillTest {

    private val json = Json { explicitNulls = false; encodeDefaults = true; ignoreUnknownKeys = true }

    /** The "one" notification as it shows up in the inbox. */
    private val bill = NotificationInput(
        packageName = "ch.one.app",
        appLabel = "one",
        title = "New bill available",
        body = "Your new bill for September 2026 amounting to CHF 2’691.72 is now available.",
        postedAt = 1_789_444_620_000, // 2026-09-15 05:57 in Zurich
    )

    private fun billRule(block: Rule.() -> Rule = { this }) = Rule(
        id = "r-one",
        name = "one bill",
        packageName = "ch.one.app",
        textPattern = """amounting to (?<currency>CHF) (?<amount>[\d'’.,]+)""",
        sourceAccountId = "",
        externalSource = "",
        target = RuleTarget.RECURRING_BILL,
        recurringRuleId = "11111111-1111-1111-1111-111111111111",
        recurringRuleName = "Swisscom",
    ).block()

    @Test
    fun `a bill yields its amount and the notification's date`() {
        val zurich = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Zurich"))
        try {
            val outcome = RuleEngine.evaluate(bill, listOf(billRule())) as MatchOutcome.Matched
            assertEquals(269_172L, outcome.extraction.amountCents)
            assertEquals("2026-09-15", outcome.extraction.occurredOn)
        } finally {
            TimeZone.setDefault(zurich)
        }
    }

    @Test
    fun `a bill never carries a location, whatever the rule says`() {
        val outcome = RuleEngine.evaluate(
            bill,
            listOf(billRule { copy(locationMode = LocationMode.ATTACH) }),
        ) as MatchOutcome.Matched
        assertEquals(false, outcome.extraction.attachLocation)
    }

    @Test
    fun `a bill rule without a recurring rule fails instead of posting nowhere`() {
        val outcome = RuleEngine.evaluate(bill, listOf(billRule { copy(recurringRuleId = null) }))
        assertTrue("$outcome", outcome is MatchOutcome.Failed)
        assertEquals("No recurring rule picked for this bill", (outcome as MatchOutcome.Failed).reason)
    }

    @Test
    fun `the proposal payload uses the web app's field names`() {
        val encoded = json.encodeToString(
            RecurringProposalPayload(
                recurringRuleId = "rule-1",
                amount = "2691.72",
                occurredOn = "2026-09-15",
                externalSource = "FinReader",
                externalRef = "abc-0",
                externalInfo = null,
            ),
        )
        val keys = json.parseToJsonElement(encoded).jsonObject.keys
        assertEquals(
            setOf("recurring_rule_id", "amount", "occurred_on", "external_source", "external_ref"),
            keys,
        )
    }

    @Test
    fun `a late bill's refusal carries the transaction to edit`() {
        val body = """
            {"error":"\"Swisscom\" for 2026-09-30 is already posted","code":"already_posted",
             "recurring_proposal":{"occurrence_id":"occ-1","rule_name":"Swisscom",
             "effective_on":"2026-09-30","status":"posted","transaction_id":"tx-9",
             "amount":null,"external_ref":null}}
        """.trimIndent()
        val parsed = json.decodeFromString<RecurringProposalResponse>(body)
        assertEquals("already_posted", parsed.code)
        assertEquals("tx-9", parsed.proposal?.transactionId)
        assertNull(parsed.proposal?.externalRef)
    }

    @Test
    fun `an older server without accepts_proposals still lists its rules`() {
        val parsed = json.decodeFromString<RecurringRulesResponse>(
            """{"recurring_rules":[{"id":"r","name":"Swisscom","is_variable_amount":true}]}""",
        )
        assertNull(parsed.recurringRules.single().acceptsProposals)
        assertTrue(parsed.recurringRules.single().isVariableAmount)
    }
}
