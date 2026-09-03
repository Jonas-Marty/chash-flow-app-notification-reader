package ch.marty.finreader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Android 14+ backs `java.util.regex` with ICU, which is stricter than the JVM
 * these tests run on: a bare `{` or `}` that the desktop happily treats as a
 * literal is a `PatternSyntaxException` on the phone. No unit test can catch
 * that by running the pattern, so this one reads the sources instead.
 */
class RegexPortabilityTest {

    private val regexLiteral = Regex("""Regex\(\s*(\"{3}([\s\S]*?)\"{3}|"((?:\\.|[^"\\])*)")""")
    private val quantifier = Regex("""\{\d+(,\d*)?\}""")

    @Test
    fun `every hard-coded pattern is ICU-safe`() {
        val sources = sourceRoot().walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("found no sources to scan", sources.isNotEmpty())

        val offenders = mutableListOf<String>()
        for (file in sources) {
            for (match in regexLiteral.findAll(file.readText())) {
                val pattern = match.groupValues[2].ifEmpty { match.groupValues[3] }
                unescapedBrace(pattern)?.let { brace ->
                    offenders += "${file.name}: unescaped '$brace' in /$pattern/"
                }
            }
        }
        if (offenders.isNotEmpty()) {
            fail("ICU rejects these on Android; escape the brace:\n" + offenders.joinToString("\n"))
        }
    }

    /** The offending brace, or null when every one is escaped or a quantifier. */
    private fun unescapedBrace(pattern: String): Char? {
        val quantifierRanges = quantifier.findAll(pattern).map { it.range }.toList()
        var index = 0
        var inClass = false
        while (index < pattern.length) {
            val char = pattern[index]
            when {
                // \p{L}, \P{Mn}, \x{1F600} — braced escapes ICU accepts as they are.
                char == '\\' -> index += bracedEscapeLength(pattern, index)
                char == '[' -> inClass = true
                char == ']' -> inClass = false
                // Braces are literal inside a character class, and ICU accepts them there.
                inClass -> Unit
                char == '{' || char == '}' ->
                    if (quantifierRanges.none { index in it }) return char
            }
            index++
        }
        return null
    }

    /** Characters to skip for the escape at [start], the backslash included. */
    private fun bracedEscapeLength(pattern: String, start: Int): Int {
        val letter = pattern.getOrNull(start + 1) ?: return 1
        if (!letter.isLetter() || pattern.getOrNull(start + 2) != '{') return 1
        val close = pattern.indexOf('}', start + 3)
        return if (close < 0) 1 else close - start
    }

    @Test
    fun `the scanner actually rejects the bug it exists for`() {
        assertTrue(unescapedBrace("""\{([A-Za-z]*)}""") == '}')
        assertTrue(unescapedBrace("""\{([A-Za-z]*)\}""") == null)
        assertTrue(unescapedBrace("""\s{2,}""") == null)
        assertTrue(unescapedBrace("""[^\n,.;]{2,60}""") == null)
        assertTrue(unescapedBrace("""[^\p{L}\p{N}\s_-]""") == null)
        assertTrue(unescapedBrace("""\p{Mn}+""") == null)
        assertTrue(unescapedBrace("""\p{L}}""") == '}')
    }

    private fun sourceRoot(): File =
        sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull { it.isDirectory }
            ?: error("cannot locate the main sources from ${File(".").absolutePath}")
}
