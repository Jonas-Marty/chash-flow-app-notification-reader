package ch.marty.finreader.domain

/**
 * `{placeholder}` substitution for description and note templates. Unknown
 * placeholders collapse to an empty string rather than staying visible in the
 * transaction description.
 */
object Template {

    /**
     * Both braces are escaped on purpose. Android 14+ backs `java.util.regex`
     * with ICU, which rejects a bare `}` as a syntax error where the JVM the
     * unit tests run on quietly treats it as a literal — so an unescaped one
     * compiles green on the desktop and throws on the phone.
     */
    private val PLACEHOLDER = Regex("""\{([A-Za-z][A-Za-z0-9_]*)\}""")

    fun render(template: String, values: Map<String, String?>): String =
        PLACEHOLDER.replace(template) { m ->
            values[m.groupValues[1]]?.trim().orEmpty()
        }.replace(Regex("\\s{2,}"), " ").trim()

    /** Named groups declared in a regex, so the rule editor can list them. */
    fun namedGroupsOf(pattern: String): List<String> =
        Regex("\\(\\?<([A-Za-z][A-Za-z0-9]*)>").findAll(pattern).map { it.groupValues[1] }.toList()
}
