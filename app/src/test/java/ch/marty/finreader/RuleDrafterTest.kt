package ch.marty.finreader

import ch.marty.finreader.data.db.CapturedNotification
import ch.marty.finreader.domain.MatchOutcome
import ch.marty.finreader.domain.RuleDrafter
import ch.marty.finreader.domain.RuleEngine
import ch.marty.finreader.domain.Template
import ch.marty.finreader.domain.toInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real notifications seen on the phone, so drafting never regresses on them. */
class RuleDrafterTest {

    private fun capture(pkg: String, label: String, title: String, text: String) =
        CapturedNotification(
            id = 1,
            packageName = pkg,
            appLabel = label,
            notificationKey = "0|$pkg|1|null|10000",
            postedAt = 1_756_893_180_000,
            title = title,
            text = text,
            bigText = null,
            subText = null,
        )

    private val raiffeisenTwint = capture(
        pkg = "com.raiffeisen.twint",
        label = "Raiffeisen TWINT",
        title = "✅ Payment successful",
        text = "The payment of CHF 10.00 to Ziemer Skyview was successful",
    )

    @Test
    fun `drafts a rule that matches the notification it came from`() {
        val rule = RuleDrafter.draft(raiffeisenTwint, "raiffeisentwint", "acc-1")
        val outcome = RuleEngine.apply(rule, raiffeisenTwint.toInput())

        assertTrue("drafted rule did not match its own sample: $outcome", outcome is MatchOutcome.Matched)
        val extraction = (outcome as MatchOutcome.Matched).extraction
        assertEquals(1000L, extraction.amountCents)
        assertEquals("CHF", extraction.currency)
        assertEquals("expense", extraction.type)
    }

    @Test
    fun `the drafted pattern is a valid regex whose groups are all addressable`() {
        val rule = RuleDrafter.draft(raiffeisenTwint, "raiffeisentwint", "acc-1")
        val regex = RuleEngine.compile(rule.textPattern)
        assertTrue("drafted pattern does not compile: ${rule.textPattern}", regex != null)

        // The editor lists these; every one must be resolvable without throwing.
        val groups = Template.namedGroupsOf(rule.textPattern)
        assertTrue("expected an amount group in ${rule.textPattern}", "amount" in groups)
        val match = regex!!.find(raiffeisenTwint.haystack)
        assertTrue(match != null)
        groups.forEach { name -> match!!.groups[name] }
    }

    @Test
    fun `drafting survives a notification with no merchant lead word`() {
        val terse = capture("com.example.bank", "Bank", "Card payment", "CHF 42.00")
        val rule = RuleDrafter.draft(terse, "bank", "acc-1")
        val outcome = RuleEngine.apply(rule, terse.toInput())
        assertTrue("$outcome", outcome is MatchOutcome.Matched)
        assertEquals(4200L, (outcome as MatchOutcome.Matched).extraction.amountCents)
    }

    @Test
    fun `drafting survives an empty account list`() {
        val rule = RuleDrafter.draft(raiffeisenTwint, "raiffeisentwint", accountId = "")
        assertEquals("", rule.sourceAccountId)
        assertTrue(RuleEngine.apply(rule, raiffeisenTwint.toInput()) is MatchOutcome.Matched)
    }
}
