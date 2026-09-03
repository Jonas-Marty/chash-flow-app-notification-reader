package ch.marty.finreader.domain

import ch.marty.finreader.data.db.NumberFormatStyle
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Turns the amount fragment of a notification into integer cents.
 *
 * Handles the shapes that actually turn up on Swiss phones: `CHF 12.50`,
 * `1'234.50`, `EUR 12,50`, `1.234,56`, and the Swiss shorthand `CHF 12.-` /
 * `12.–` for whole francs.
 */
object AmountParser {

    private val GROUPING = charArrayOf('\'', '’', '‘', '`', ' ', ' ', ' ')
    private val KEEP = Regex("[^0-9.,'’‘`\\s]")

    fun parseToCents(raw: String, style: NumberFormatStyle = NumberFormatStyle.AUTO): Long? {
        val cleaned = clean(raw) ?: return null
        val normalized = when (style) {
            NumberFormatStyle.SWISS -> cleaned.replace(",", "")
            NumberFormatStyle.EU -> cleaned.replace(".", "").replace(',', '.')
            NumberFormatStyle.AUTO -> auto(cleaned)
        }
        val value = normalized.toBigDecimalOrNull() ?: return null
        if (value <= BigDecimal.ZERO) return null
        return value.setScale(2, RoundingMode.HALF_UP).movePointRight(2).toLong()
    }

    /** Strips currency words, symbols and grouping characters, keeping separators. */
    private fun clean(raw: String): String? {
        var s = KEEP.replace(raw, "")
        for (c in GROUPING) s = s.replace(c.toString(), "")
        // "CHF 12.-" and "12.–" mean twelve francs exactly; the dash is already gone.
        s = s.trimEnd('.', ',')
        s = s.trimStart('.', ',')
        return s.takeIf { it.isNotEmpty() && it.any(Char::isDigit) }
    }

    /**
     * Whichever of `.` and `,` appears last is the decimal separator. With only
     * one separator present, three trailing digits mean grouping (`1.234`),
     * anything else means decimals (`12.5`, `12.50`).
     */
    private fun auto(s: String): String {
        val lastDot = s.lastIndexOf('.')
        val lastComma = s.lastIndexOf(',')
        return when {
            lastDot >= 0 && lastComma >= 0 -> {
                if (lastDot > lastComma) s.replace(",", "")
                else s.replace(".", "").replace(',', '.')
            }
            lastComma >= 0 -> singleSeparator(s, lastComma, ',')
            lastDot >= 0 -> singleSeparator(s, lastDot, '.')
            else -> s
        }
    }

    private fun singleSeparator(s: String, index: Int, sep: Char): String {
        val trailing = s.length - index - 1
        val multiple = s.count { it == sep } > 1
        return if (multiple || trailing == 3) s.replace(sep.toString(), "")
        else s.replace(sep, '.')
    }

    /** Cents as the two-decimal string the web app's schema expects. */
    fun centsToPlainString(cents: Long): String =
        BigDecimal.valueOf(cents, 2).setScale(2, RoundingMode.HALF_UP).toPlainString()
}
