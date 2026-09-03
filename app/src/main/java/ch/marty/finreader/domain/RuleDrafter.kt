package ch.marty.finreader.domain

import ch.marty.finreader.data.db.CapturedNotification
import ch.marty.finreader.data.db.Rule
import java.util.Locale
import java.util.UUID

/**
 * Seeds a rule from a captured notification so the editor starts from something
 * that already matches, instead of an empty regex field.
 */
object RuleDrafter {

    private val CURRENCY_BEFORE =
        Regex("""(?<currency>CHF|EUR|USD|GBP|Fr\.?)\s*(?<amount>-?\d[\d'’.,\s]*\d|\d)""", RegexOption.IGNORE_CASE)
    private val CURRENCY_AFTER =
        Regex("""(?<amount>-?\d[\d'’.,\s]*\d|\d)\s*(?<currency>CHF|EUR|USD|GBP)""", RegexOption.IGNORE_CASE)

    /** Words that typically precede the counterparty in payment notifications. */
    private val MERCHANT_LEAD =
        Regex("""\b(?:an|bei|von|für|at|to|from|for)\s+(?<merchant>[^\n,.;]{2,60})""", RegexOption.IGNORE_CASE)

    fun draft(capture: CapturedNotification, externalSource: String, accountId: String): Rule {
        val text = capture.haystack
        val currencyFirst = CURRENCY_BEFORE.containsMatchIn(text)
        val amountPart = if (currencyFirst) {
            """(?<currency>CHF|EUR|USD|GBP)\s*(?<amount>[\d'’.,]+)"""
        } else {
            """(?<amount>[\d'’.,]+)\s*(?<currency>CHF|EUR|USD|GBP)"""
        }
        val hasMerchant = MERCHANT_LEAD.containsMatchIn(text)
        val merchantPart = if (hasMerchant) {
            val lead = MERCHANT_LEAD.find(text)?.value?.substringBefore(' ')?.lowercase(Locale.ROOT) ?: "an"
            """.*?\b$lead\s+(?<merchant>[^\n,.;]{2,60})"""
        } else {
            ""
        }

        return Rule(
            id = UUID.randomUUID().toString(),
            name = capture.appLabel.ifBlank { capture.packageName },
            packageName = capture.packageName,
            textPattern = amountPart + merchantPart,
            sourceAccountId = accountId,
            externalSource = externalSource,
            descriptionTemplate = if (hasMerchant) "{merchant}" else "{title}",
            noteTemplate = null,
        )
    }
}
