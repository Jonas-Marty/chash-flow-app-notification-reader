package ch.marty.finreader.domain

import java.text.Normalizer
import java.util.Locale

/**
 * `{placeholder}` substitution for description and note templates. Unknown
 * placeholders collapse to an empty string rather than staying visible in the
 * transaction description.
 *
 * A placeholder may carry modifiers after a colon — `{merchant:d}` — which are
 * applied left to right. They exist mainly for hashtags, where a value with a
 * space in it (`#Coop City`) silently becomes the wrong tag.
 */
object Template {

    /**
     * Every brace is escaped on purpose. Android 14+ backs `java.util.regex`
     * with ICU, which rejects a bare `}` as a syntax error where the JVM the
     * unit tests run on quietly treats it as a literal — so an unescaped one
     * compiles green on the desktop and throws on the phone.
     */
    private val PLACEHOLDER = Regex("""\{([A-Za-z][A-Za-z0-9_]*)(?::([A-Za-z]+))?\}""")

    private val WHITESPACE = Regex("""\s+""")

    /** Everything a tag has no business containing; `-` and `_` survive. */
    private val PUNCTUATION = Regex("""[^\p{L}\p{N}\s_-]""")

    private val COMBINING_MARKS = Regex("""\p{Mn}+""")

    /**
     * A merchant name that every modifier visibly changes. Most real ones are
     * plain letters and a space, where `a` has nothing to strip and looks
     * broken next to `d`.
     */
    const val DEMO_VALUE = "Café & Müller"

    /** One modifier, with a worked example the editor shows next to it. */
    data class ModifierHelp(val flag: Char, val meaning: String) {
        /** Produced by [render] itself, so the help can never drift from it. */
        val example: String get() = render("{v:$flag}", mapOf("v" to DEMO_VALUE))
    }

    /** The modifiers, in the order the help text lists them. */
    val MODIFIERS: List<ModifierHelp> = listOf(
        ModifierHelp('d', "spaces to dashes"),
        ModifierHelp('u', "spaces to underscores"),
        ModifierHelp('c', "CamelCase, no spaces"),
        ModifierHelp('l', "lowercase"),
        ModifierHelp('a', "drop punctuation and accents"),
    )

    fun render(template: String, values: Map<String, String?>): String =
        PLACEHOLDER.replace(template) { match ->
            val value = values[match.groupValues[1]]?.trim().orEmpty()
            applyModifiers(value, match.groupValues[2])
        }.replace(Regex("""\s{2,}"""), " ").trim()

    /**
     * Unknown modifier letters are ignored rather than failing the rule: the
     * editor shows a live preview, so a typo is visible long before anything
     * is posted.
     */
    private fun applyModifiers(value: String, modifiers: String): String =
        modifiers.fold(value) { text, modifier ->
            when (modifier) {
                'd' -> text.squeeze().replace(WHITESPACE, "-")
                'u' -> text.squeeze().replace(WHITESPACE, "_")
                'c' -> text.squeeze().split(WHITESPACE).joinToString("") { word ->
                    word.replaceFirstChar { it.titlecase(Locale.ROOT) }
                }

                'l' -> text.lowercase(Locale.ROOT)
                'a' -> Normalizer.normalize(text, Normalizer.Form.NFD)
                    .replace(COMBINING_MARKS, "")
                    .replace(PUNCTUATION, "")
                    .squeeze()

                else -> text
            }
        }

    private fun String.squeeze(): String = replace(WHITESPACE, " ").trim()

    /** Named groups declared in a regex, so the rule editor can list them. */
    fun namedGroupsOf(pattern: String): List<String> =
        Regex("""\(\?<([A-Za-z][A-Za-z0-9]*)>""").findAll(pattern).map { it.groupValues[1] }.toList()
}
