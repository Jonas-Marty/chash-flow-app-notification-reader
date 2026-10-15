package ch.marty.finreader.domain

import ch.marty.finreader.data.db.CategoryCache
import ch.marty.finreader.data.db.LocationMode
import ch.marty.finreader.data.db.NumberFormatStyle
import ch.marty.finreader.data.db.Rule
import ch.marty.finreader.data.db.RuleTarget
import ch.marty.finreader.data.db.TxTypeMode
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The JSON a rule travels in when it leaves the app — into a prompt for an AI
 * chat — and comes back as that chat's answer.
 *
 * Only what can be written from the notification text is in here. Account and
 * recurring-rule ids mean nothing outside this phone, so they are never
 * exported and never accepted: a model would only invent them. The category
 * travels by name, which is something a model can actually choose well.
 *
 * Reading is lenient about the wrapping (chat replies come in code fences, with
 * prose around them) and strict about the content: an unknown enum value is
 * reported, never guessed into a default.
 */
object RuleExchange {

    const val FORMAT_KEY = "finreader_rule"
    const val FORMAT_VERSION = 1

    private val json = Json { prettyPrint = true }

    /** One rule read back from a reply, already laid over the rule being edited. */
    data class Imported(
        val rule: Rule,
        /** The model's own account of why the patterns look the way they do. */
        val explanation: String?,
        /**
         * Sample id → the amount the rule should read from it. Samples absent
         * here should not match. Null when the reply made no claim at all.
         */
        val expectations: Map<Long, String?>?,
        /** Human labels of the fields the reply set. */
        val filled: List<String>,
        /** Things the user still has to look at before saving. */
        val warnings: List<String>,
    )

    sealed interface ParseResult {
        data class Ok(val rules: List<JsonObject>) : ParseResult
        data class Error(val message: String) : ParseResult
    }

    // ---- export -----------------------------------------------------------

    fun export(rule: Rule): JsonObject = buildJsonObject {
        put("name", rule.name)
        put("target", rule.target.wire())
        put("text_pattern", rule.textPattern)
        put("title_pattern", rule.titlePattern)
        put("exclude_patterns", buildJsonArray {
            RuleEngine.patternLines(rule.excludePattern).forEach { add(JsonPrimitive(it)) }
        })
        put("amount_group", rule.amountGroup)
        put("currency_group", rule.currencyGroup)
        put("merchant_group", rule.merchantGroup)
        put("default_currency", rule.defaultCurrency)
        put("number_format", rule.numberFormat.wire())
        if (rule.target == RuleTarget.PENDING_TRANSACTION) {
            put("direction", rule.txTypeMode.wire())
            put("income_pattern", rule.incomePattern)
            put("description_template", rule.descriptionTemplate)
            put("note_template", rule.noteTemplate)
            put("location", rule.locationMode.wire())
            put("location_exclude_pattern", rule.locationExcludePattern)
            put("category", rule.categoryName)
            put("external_source", rule.externalSource)
        }
    }

    /** A complete reply, the same shape the prompt asks for. */
    fun exportDocument(rules: List<Rule>): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put(FORMAT_KEY, FORMAT_VERSION)
            put("rules", JsonArray(rules.map(::export)))
        },
    )

    // ---- import -----------------------------------------------------------

    /**
     * Finds the rules in whatever was pasted: the full document, a bare rule
     * object, or an array of them, with or without fences and chatter around it.
     */
    fun parse(text: String): ParseResult {
        if (text.isBlank()) return ParseResult.Error("Nothing pasted.")
        val element = jsonCandidates(text)
            .firstNotNullOfOrNull { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
            ?: return ParseResult.Error("No JSON found in the pasted text.")

        val rules = when (element) {
            is JsonArray -> element.filterIsInstance<JsonObject>()
            is JsonObject -> when (val nested = element["rules"]) {
                is JsonArray -> nested.filterIsInstance<JsonObject>()
                is JsonObject -> listOf(nested)
                else -> listOf(element)
            }
            else -> emptyList()
        }
        (element as? JsonObject)?.get(FORMAT_KEY)?.let { version ->
            val number = (version as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            if (number != null && number > FORMAT_VERSION) {
                return ParseResult.Error("Format version $number is newer than this app understands ($FORMAT_VERSION).")
            }
        }
        val usable = rules.filter { it.containsKey("text_pattern") }
        return if (usable.isEmpty()) {
            ParseResult.Error("The JSON has no rule with a \"text_pattern\".")
        } else {
            ParseResult.Ok(usable)
        }
    }

    /**
     * Lays [spec] over [base]. A key that is absent keeps the base's value; a
     * key that is null or empty clears an optional field. Send automatically is
     * always switched off: nothing a chat wrote posts on its own before it has
     * been seen to match a real notification.
     */
    fun apply(spec: JsonObject, base: Rule, categories: List<CategoryCache>): Imported {
        val filled = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var rule = base

        fun text(key: String): String? = (spec[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
        fun has(key: String) = spec.containsKey(key)

        fun optional(key: String, label: String, set: (Rule, String?) -> Rule) {
            if (!has(key)) return
            rule = set(rule, text(key)?.takeIf { it.isNotBlank() })
            filled += label
        }

        fun required(key: String, label: String, set: (Rule, String) -> Rule) {
            val value = text(key)?.takeIf { it.isNotBlank() } ?: return
            rule = set(rule, value)
            filled += label
        }

        fun <E : Enum<E>> choice(key: String, label: String, entries: List<E>, wire: (E) -> String, set: (Rule, E) -> Rule) {
            val value = text(key) ?: return
            val match = entries.firstOrNull { wire(it) == value.trim().lowercase(Locale.ROOT) }
            if (match == null) {
                warnings += "$label: \"$value\" is not one of ${entries.joinToString { wire(it) }} — left as it was."
            } else {
                rule = set(rule, match)
                filled += label
            }
        }

        required("name", "Name") { r, v -> r.copy(name = v) }
        choice("target", "Creates", RuleTarget.entries, { it.wire() }) { r, v -> r.copy(target = v) }
        required("text_pattern", "Match pattern") { r, v -> r.copy(textPattern = v) }
        optional("title_pattern", "Title must contain") { r, v -> r.copy(titlePattern = v) }
        if (has("exclude_patterns")) {
            val lines = when (val value = spec["exclude_patterns"]) {
                is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                is JsonPrimitive -> listOfNotNull(value.contentOrNull)
                else -> emptyList()
            }.flatMap { RuleEngine.patternLines(it) }
            rule = rule.copy(excludePattern = lines.joinToString("\n").ifBlank { null })
            filled += "Skip patterns"
        }
        required("amount_group", "Amount group") { r, v -> r.copy(amountGroup = v) }
        optional("currency_group", "Currency group") { r, v -> r.copy(currencyGroup = v) }
        optional("merchant_group", "Merchant group") { r, v -> r.copy(merchantGroup = v) }
        required("default_currency", "Default currency") { r, v -> r.copy(defaultCurrency = v.trim().uppercase(Locale.ROOT)) }
        choice("number_format", "Number format", NumberFormatStyle.entries, { it.wire() }) { r, v -> r.copy(numberFormat = v) }
        choice("direction", "Direction", TxTypeMode.entries, { it.wire() }) { r, v -> r.copy(txTypeMode = v) }
        optional("income_pattern", "Income pattern") { r, v -> r.copy(incomePattern = v) }
        if (has("description_template")) {
            rule = rule.copy(descriptionTemplate = text("description_template").orEmpty())
            filled += "Description template"
        }
        optional("note_template", "Note template") { r, v -> r.copy(noteTemplate = v) }
        choice("location", "Location", LocationMode.entries, { it.wire() }) { r, v -> r.copy(locationMode = v) }
        optional("location_exclude_pattern", "No-location pattern") { r, v -> r.copy(locationExcludePattern = v) }
        required("external_source", "Source badge") { r, v -> r.copy(externalSource = v.trim()) }

        if (has("category")) {
            val wanted = text("category")?.trim().orEmpty()
            if (wanted.isEmpty()) {
                rule = rule.copy(categoryId = null, categoryName = null)
                filled += "Category"
            } else {
                val category = findCategory(wanted, categories)
                if (category == null) {
                    warnings += "Category \"$wanted\" is not one of yours — pick one."
                } else {
                    rule = rule.copy(categoryId = category.id, categoryName = category.name)
                    filled += "Category"
                }
            }
        }

        // What the reply could not have known.
        if (rule.target == RuleTarget.RECURRING_BILL) {
            if (rule.recurringRuleId.isNullOrBlank()) warnings += "Pick the recurring rule the bill is for."
        } else if (rule.sourceAccountId.isBlank()) {
            warnings += "Pick the account."
        }
        warnings += checkPatterns(rule)

        if (base.autoPost) warnings += "Send automatically is off — turn it back on once the rule has matched a real notification."
        rule = rule.copy(autoPost = false)

        return Imported(
            rule = rule,
            explanation = text("explanation")?.takeIf { it.isNotBlank() },
            expectations = expectationsOf(spec["expected_matches"]),
            filled = filled,
            warnings = warnings,
        )
    }

    /** Mistakes that otherwise only show up as "No match" in the tester. */
    private fun checkPatterns(rule: Rule): List<String> = buildList {
        if (RuleEngine.compile(rule.textPattern) == null) add("The match pattern does not compile.")
        val groups = Template.namedGroupsOf(rule.textPattern)
        if (rule.amountGroup !in groups) add("The match pattern has no (?<${rule.amountGroup}>…) group.")
        rule.currencyGroup?.let { if (it !in groups) add("No (?<$it>…) group in the pattern — $it falls back to ${rule.defaultCurrency}.") }
        rule.merchantGroup?.let { if (it !in groups) add("No (?<$it>…) group in the pattern — {$it} renders empty.") }
        rule.titlePattern?.let { if (RuleEngine.compile(it) == null) add("The title pattern does not compile.") }
        RuleEngine.patternLines(rule.excludePattern)
            .filter { RuleEngine.compile(it) == null }
            .forEach { add("Skip pattern \"$it\" does not compile.") }
    }

    private fun expectationsOf(element: JsonElement?): Map<Long, String?>? {
        val array = element as? JsonArray ?: return null
        return buildMap {
            for (item in array) {
                when (item) {
                    is JsonPrimitive -> item.contentOrNull?.toLongOrNull()?.let { put(it, null) }
                    is JsonObject -> {
                        val id = (item["sample"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: continue
                        put(id, (item["amount"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull)
                    }
                    else -> Unit
                }
            }
        }
    }

    /** By name, ignoring case; "Group · Name" and "Group / Name" are accepted too. */
    private fun findCategory(wanted: String, categories: List<CategoryCache>): CategoryCache? {
        val key = wanted.lowercase(Locale.ROOT)
        val live = categories.filterNot { it.archived }
        return live.firstOrNull { it.name.lowercase(Locale.ROOT) == key }
            ?: live.firstOrNull { c ->
                c.groupName != null && listOf(" · ", " / ", "/", " > ").any { sep ->
                    "${c.groupName}$sep${c.name}".lowercase(Locale.ROOT) == key
                }
            }
    }

    /**
     * Places the JSON may start: inside a ```json fence first, then from each
     * opening bracket to its partner. Brackets inside strings are skipped, so a
     * pattern like `[\d'.,]+` does not end the scan early.
     */
    private fun jsonCandidates(text: String): Sequence<String> = sequence {
        Regex("""```(?:json)?\s*\n([\s\S]*?)```""").findAll(text).forEach { yield(it.groupValues[1].trim()) }
        var start = text.indexOfFirst { it == '{' || it == '[' }
        while (start >= 0) {
            balancedEnd(text, start)?.let { end -> yield(text.substring(start, end + 1)) }
            start = text.indexOfAny(charArrayOf('{', '['), start + 1)
        }
    }

    private fun balancedEnd(text: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' || c == '[' -> depth++
                c == '}' || c == ']' -> if (--depth == 0) return i
            }
        }
        return null
    }

    // ---- wire names -------------------------------------------------------

    fun RuleTarget.wire() = name.lowercase(Locale.ROOT)
    fun NumberFormatStyle.wire() = name.lowercase(Locale.ROOT)
    fun TxTypeMode.wire() = name.lowercase(Locale.ROOT)
    fun LocationMode.wire() = name.lowercase(Locale.ROOT)
}
