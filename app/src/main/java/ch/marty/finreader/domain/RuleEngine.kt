package ch.marty.finreader.domain

import ch.marty.finreader.data.db.LocationMode
import ch.marty.finreader.data.db.NumberFormatStyle
import ch.marty.finreader.data.db.Rule
import ch.marty.finreader.data.db.RuleTarget
import ch.marty.finreader.data.db.TxTypeMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The parts of a notification a rule is matched against. */
data class NotificationInput(
    val packageName: String,
    val appLabel: String,
    val title: String?,
    val body: String?,
    val postedAt: Long,
) {
    val haystack: String
        get() = listOfNotNull(
            title?.takeIf { it.isNotBlank() },
            body?.takeIf { it.isNotBlank() },
        ).joinToString("\n")
}

data class Extraction(
    val amountCents: Long,
    val amountRaw: String,
    val currency: String,
    val merchant: String?,
    val type: String,
    /** Empty when the rule's template renders to nothing; never guessed at. */
    val description: String?,
    val note: String?,
    val occurredOn: String,
    /** Whether this payment may carry the phone's location, see [Rule.locationMode]. */
    val attachLocation: Boolean = true,
)

sealed interface MatchOutcome {
    data class Matched(val rule: Rule, val extraction: Extraction) : MatchOutcome
    data class Ignored(val rule: Rule, val reason: String) : MatchOutcome
    /** A rule matched but the payload could not be built. */
    data class Failed(val rule: Rule, val reason: String) : MatchOutcome
    data object NoMatch : MatchOutcome
}

object RuleEngine {

    private const val MAX_DESCRIPTION = 500
    private const val MAX_NOTE = 2000

    fun evaluate(input: NotificationInput, rules: List<Rule>): MatchOutcome {
        val candidates = rules
            .filter { it.enabled && it.packageName == input.packageName }
            .sortedWith(compareBy({ it.priority }, { it.name.lowercase(Locale.ROOT) }))

        for (rule in candidates) {
            when (val outcome = apply(rule, input)) {
                is MatchOutcome.NoMatch -> continue
                else -> return outcome
            }
        }
        return MatchOutcome.NoMatch
    }

    /** Runs a single rule; exposed so the rule editor can preview against a sample. */
    fun apply(rule: Rule, input: NotificationInput): MatchOutcome {
        val haystack = input.haystack

        rule.titlePattern?.takeIf { it.isNotBlank() }?.let { pattern ->
            val regex = compile(pattern) ?: return MatchOutcome.Failed(rule, "Invalid title pattern")
            if (!regex.containsMatchIn(input.title.orEmpty())) return MatchOutcome.NoMatch
        }

        for (pattern in patternLines(rule.excludePattern)) {
            val regex = compile(pattern)
                ?: return MatchOutcome.Failed(rule, "Invalid exclude pattern \"$pattern\"")
            if (regex.containsMatchIn(haystack)) {
                return MatchOutcome.Ignored(rule, "Excluded by \"$pattern\"")
            }
        }

        val textRegex = compile(rule.textPattern)
            ?: return MatchOutcome.Failed(rule, "Invalid text pattern")
        val match = textRegex.find(haystack) ?: return MatchOutcome.NoMatch

        if (rule.target == RuleTarget.RECURRING_BILL && rule.recurringRuleId.isNullOrBlank()) {
            return MatchOutcome.Failed(rule, "No recurring rule picked for this bill")
        }

        val amountRaw = match.namedGroup(rule.amountGroup)
            ?: return MatchOutcome.Failed(rule, "Pattern has no group \"${rule.amountGroup}\"")
        val cents = AmountParser.parseToCents(amountRaw, rule.numberFormat)
            ?: return MatchOutcome.Failed(rule, "Could not read an amount from \"$amountRaw\"")

        val currency = rule.currencyGroup
            ?.let { match.namedGroup(it) }
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() }
            ?: rule.defaultCurrency

        val merchant = rule.merchantGroup
            ?.let { match.namedGroup(it) }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        val type = when (rule.txTypeMode) {
            TxTypeMode.EXPENSE -> "expense"
            TxTypeMode.INCOME -> "income"
            TxTypeMode.FROM_PATTERN -> {
                val pattern = rule.incomePattern.orEmpty()
                val regex = pattern.takeIf { it.isNotBlank() }?.let { compile(it) }
                    ?: return MatchOutcome.Failed(rule, "Income pattern missing or invalid")
                if (regex.containsMatchIn(haystack)) "income" else "expense"
            }
        }

        // A bill arrives wherever the phone is, which says nothing about the
        // payment — and a proposal has nowhere to put a location anyway.
        val attachLocation = if (rule.target == RuleTarget.RECURRING_BILL) false else when (rule.locationMode) {
            LocationMode.ATTACH -> true
            LocationMode.NEVER -> false
            // A pattern that is missing or does not compile suppresses the fix
            // rather than attaching one: a typo here must not quietly start
            // recording where the phone was.
            LocationMode.UNLESS_PATTERN -> {
                val regex = rule.locationExcludePattern
                    ?.takeIf { it.isNotBlank() }
                    ?.let { compile(it) }
                regex != null && !regex.containsMatchIn(haystack)
            }
        }

        val occurredOn = localDate(input.postedAt)
        val values = buildMap {
            put("merchant", merchant)
            put("amount", AmountParser.centsToPlainString(cents))
            put("currency", currency)
            put("title", input.title)
            put("text", input.body)
            put("app", input.appLabel)
            put("date", occurredOn)
            put("rule", rule.name)
            // Every named group of the rule's own pattern is addressable too.
            for (name in Template.namedGroupsOf(rule.textPattern)) {
                put(name, match.namedGroup(name))
            }
        }

        // A template that renders to nothing leaves the description empty.
        // The notification says where a payment happened and what it cost, not
        // what was bought, so a merchant or rule name substituted in here would
        // read like a description someone actually wrote.
        val description = Template.render(rule.descriptionTemplate, values)
            .takeIf { it.isNotBlank() }
            ?.take(MAX_DESCRIPTION)
        val note = rule.noteTemplate
            ?.takeIf { it.isNotBlank() }
            ?.let { Template.render(it, values) }
            ?.takeIf { it.isNotBlank() }
            ?.take(MAX_NOTE)

        return MatchOutcome.Matched(
            rule,
            Extraction(
                amountCents = cents,
                amountRaw = amountRaw,
                currency = currency,
                merchant = merchant,
                type = type,
                description = description,
                note = note,
                occurredOn = occurredOn,
                attachLocation = attachLocation,
            ),
        )
    }

    /**
     * A pattern field holds one regex per line, so a rule can list the
     * merchants it does not want without folding them into one alternation
     * where a stray `|` silently excludes everything. Blank lines are dropped
     * for the same reason: an empty regex matches every notification.
     */
    fun patternLines(field: String?): List<String> = field
        ?.lineSequence()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toList()
        .orEmpty()

    fun compile(pattern: String): Regex? = runCatching {
        Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    }.getOrNull()

    /** `occurred_on` is the phone's local date at the moment of the notification. */
    fun localDate(epochMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { this.timeZone = timeZone }
            .format(Date(epochMillis))

    private fun MatchResult.namedGroup(name: String): String? =
        runCatching { groups[name]?.value }.getOrNull()
}
