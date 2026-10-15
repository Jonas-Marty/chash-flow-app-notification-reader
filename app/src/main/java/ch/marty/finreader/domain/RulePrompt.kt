package ch.marty.finreader.domain

import ch.marty.finreader.data.db.Rule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The text behind "Ask an AI": everything a chat model needs to write a rule
 * from real notifications, and the exact JSON to answer in, so the answer can
 * be pasted back into the editor.
 *
 * The samples are the captured text itself, not a screenshot of it. Whether an
 * apostrophe is ' or ’ and whether a space is a non-breaking one decides
 * whether a pattern matches, and neither survives a picture.
 */
object RulePrompt {

    /** More than this and the prompt is mostly repetition of the same notification. */
    const val MAX_SAMPLES = 10
    private const val MAX_SAMPLE_CHARS = 1000

    data class Sample(val id: Long, val input: NotificationInput)

    data class Request(
        val appLabel: String,
        val packageName: String,
        /** Most recent first, as the editor lists them. */
        val samples: List<Sample>,
        /** The notification the user started the rule from, if any. */
        val focusSampleId: Long?,
        val categoryNames: List<String>,
        /** The rule being edited; null for one that has not been saved yet. */
        val current: Rule?,
        /** Saved rules for the same app — the new one has to coexist with them. */
        val siblings: List<Rule>,
    )

    private val json = Json { prettyPrint = true }

    fun build(request: Request): String = buildString {
        val app = request.appLabel.ifBlank { request.packageName }
        appendLine("I use FinReader, an Android app that reads payment notifications and turns them into")
        appendLine("transactions for my budgeting web app. Each rule is a regex over one app's notifications.")
        appendLine(
            if (request.current != null) {
                "Improve my rule for notifications from \"$app\" so it handles the samples below."
            } else {
                "Write a rule for notifications from \"$app\" using the samples below."
            },
        )
        appendLine()

        appendLine("## How the app runs a rule")
        appendLine("- The text matched is the notification title, a newline, then the body (\"haystack\" below).")
        appendLine("- `text_pattern` is searched (find, not a full match) with Java regex on Android, which uses ICU:")
        appendLine("  case-insensitive and DOTALL are always on; do not add inline flags.")
        appendLine("- Named groups are `(?<name>…)`; names are ASCII letters and digits only, starting with a letter —")
        appendLine("  no underscores, and never Python's `(?P<name>…)`.")
        appendLine("- ICU rejects a bare `{` or `}`: escape every literal brace as `\\{` `\\}`. Quantifiers like `{2,60}` are fine.")
        appendLine("- The amount group is parsed with `number_format`: auto (the last separator is the decimal one),")
        appendLine("  swiss (1'234.50) or eu (1.234,50). Thousands separators may be ' or ’ (U+2019), so use a class")
        appendLine("  like `[\\d'’.,]` and start and end the amount on a digit. Make sure the group catches the amount")
        appendLine("  that was paid, not a balance or limit mentioned in the same text.")
        appendLine("- The currency group is upper-cased; without one, `default_currency` is used.")
        appendLine("- `title_pattern` (optional) must also be found in the title alone.")
        appendLine("- `exclude_patterns`: if any one of them is found in the haystack, the notification is skipped.")
        appendLine("  Use them for notifications of this app that look similar but are not payments.")
        appendLine("- Rules of one app are tried in order and the first one that matches wins.")
        appendLine("- Templates (`description_template`, `note_template`) use `{group}` placeholders: any named group of")
        appendLine("  the pattern, plus {amount} {currency} {title} {text} {app} {date}. A placeholder takes modifiers")
        append("  after a colon, applied left to right, e.g. {merchant:ad}. On \"${Template.DEMO_VALUE}\":")
        Template.MODIFIERS.forEach { append(" :${it.flag} → \"${it.example}\" (${it.meaning});") }
        appendLine()
        appendLine("  A template that renders empty leaves the field empty — don't put the app name or rule name in")
        appendLine("  the description, it should name who was paid.")
        appendLine()

        appendLine("## What a rule can create")
        appendLine("- `pending_transaction`: a transaction I confirm later. Fill `direction` (expense, income, or")
        appendLine("  from_pattern with `income_pattern` deciding income), the templates, `location`, `category` and")
        appendLine("  `external_source` (a short lowercase badge naming the source, e.g. \"twint\").")
        appendLine("  `location` is attach, never (online payments, subscriptions — the phone is not where the money")
        appendLine("  was spent) or unless_pattern with `location_exclude_pattern`.")
        appendLine("- `recurring_bill`: the amount fills in the next entry of a recurring bill (rent, phone, credit")
        appendLine("  card statement). Only the amount and the date are used; skip the booking fields.")
        appendLine("I pick the account and the recurring entry myself in the app — don't include them.")
        appendLine()

        if (request.categoryNames.isNotEmpty()) {
            appendLine("## My categories")
            appendLine("Use one of these names exactly for `category`, or null if none fits:")
            appendLine(request.categoryNames.joinToString(", "))
            appendLine()
        }

        appendLine("## Samples from \"$app\" (${request.packageName})")
        appendLine("Exact text as captured, JSON-encoded. Invisible or unusual spaces are written as \\uXXXX escapes;")
        appendLine("the real text contains the character, not the escape.")
        request.focusSampleId?.let { appendLine("Sample $it is the one I want this rule for.") }
        appendLine("Not every sample is a payment — tell the kinds apart and skip what is not money moving.")
        appendLine("```json")
        appendLine(samplesJson(request.samples))
        appendLine("```")
        appendLine()

        if (request.siblings.isNotEmpty()) {
            appendLine("## My other rules for this app")
            appendLine("They stay as they are. Don't overlap with them unless that is the point.")
            appendLine("```json")
            appendLine(json.encodeToString(JsonArray.serializer(), JsonArray(request.siblings.map(RuleExchange::export))))
            appendLine("```")
            appendLine()
        }

        request.current?.let {
            appendLine("## The rule as it is now")
            appendLine("```json")
            appendLine(json.encodeToString(JsonObject.serializer(), RuleExchange.export(it)))
            appendLine("```")
            appendLine()
        }

        appendLine("## Answer")
        appendLine("Check every pattern against every sample before answering. Then reply with one JSON block in")
        appendLine("exactly this shape and nothing after it. Use several entries in `rules` only when the samples")
        appendLine("need clearly different rules (e.g. card payments and a monthly statement).")
        appendLine("`expected_matches` lists every sample id the rule should match, with the amount it should read;")
        appendLine("samples not listed must not match. Keys you leave out stay as they are in the app.")
        appendLine("```json")
        appendLine(EXAMPLE)
        appendLine("```")
    }

    /** The samples, with every character a reader could not see spelled out. */
    fun samplesJson(samples: List<Sample>): String {
        val array = JsonArray(
            samples.take(MAX_SAMPLES).map { sample ->
                buildJsonObject {
                    put("id", sample.id)
                    put("title", sample.input.title)
                    put("haystack", sample.input.haystack.take(MAX_SAMPLE_CHARS))
                }
            },
        )
        return escapeInvisible(json.encodeToString(JsonArray.serializer(), array))
    }

    /**
     * Spaces other than U+0020 and format characters (zero-width joiners,
     * direction marks) look like nothing or like a plain space; inside a JSON
     * string a `\uXXXX` escape means the same character and is visible.
     */
    fun escapeInvisible(text: String): String = buildString {
        for (c in text) {
            val invisible = c != ' ' && c != '\n' && (
                Character.isSpaceChar(c) || Character.getType(c) == Character.FORMAT.toInt()
                )
            if (invisible) append("\\u%04x".format(c.code)) else append(c)
        }
    }

    private val EXAMPLE = """
{
  "${RuleExchange.FORMAT_KEY}": ${RuleExchange.FORMAT_VERSION},
  "rules": [
    {
      "name": "Card payments",
      "target": "pending_transaction",
      "text_pattern": "(?<currency>CHF|EUR)\\s*(?<amount>\\d[\\d'’.,]*\\d).*?bei\\s+(?<merchant>[^\\n,.]{2,60})",
      "title_pattern": null,
      "exclude_patterns": ["Zahlungseingang"],
      "amount_group": "amount",
      "currency_group": "currency",
      "merchant_group": "merchant",
      "default_currency": "CHF",
      "number_format": "auto",
      "direction": "expense",
      "income_pattern": null,
      "description_template": "{merchant}",
      "note_template": null,
      "location": "attach",
      "location_exclude_pattern": null,
      "category": null,
      "external_source": "card",
      "expected_matches": [{ "sample": 101, "amount": "12.50" }],
      "explanation": "One or two sentences: what the pattern anchors on and what it deliberately skips."
    }
  ]
}
""".trim()
}
