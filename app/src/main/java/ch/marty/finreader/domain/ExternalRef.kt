package ch.marty.finreader.domain

import java.security.MessageDigest
import java.util.Locale

/**
 * Idempotency key for `/api/public/pending-transactions`.
 *
 * The base key is derived from what makes a payment unique on a given day. A
 * repeat of the same payment later the same day gets a counter appended so it
 * is not swallowed by the server's deduplication, while a notification the
 * system re-posts moments later maps onto the identical key and is dropped.
 */
object ExternalRef {

    private val WHITESPACE = Regex("\\s+")

    fun normalizeText(text: String): String =
        WHITESPACE.replace(text, " ").trim().lowercase(Locale.ROOT)

    fun base(packageName: String, occurredOn: String, amountCents: Long, text: String): String {
        val payload = "$packageName|$occurredOn|$amountCents|${normalizeText(text)}"
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    fun withSequence(base: String, sequence: Int): String =
        if (sequence <= 0) base else "$base-$sequence"
}
