package ch.marty.finreader.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** An app whose notifications we are allowed to read. Nothing else is stored. */
@Entity(tableName = "monitored_app")
data class MonitoredApp(
    @PrimaryKey val packageName: String,
    val appLabel: String,
    val enabled: Boolean = true,
)

enum class MatchState {
    /** No rule matched — raw material for writing one. */
    UNMATCHED,

    /** A rule matched and an outbox item was created. */
    MATCHED,

    /** A rule's exclude pattern matched, deliberately skipped. */
    IGNORED,

    /** Same payment already captured moments ago. */
    DUPLICATE,

    /** A rule matched but the payload could not be built (bad regex, unparsable amount). */
    ERROR,
}

@Entity(
    tableName = "captured_notification",
    indices = [Index("postedAt"), Index("packageName"), Index("matchState")],
)
data class CapturedNotification(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appLabel: String,
    val notificationKey: String?,
    val postedAt: Long,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val matchState: MatchState = MatchState.UNMATCHED,
    val matchedRuleId: String? = null,
    val outboxId: Long? = null,
    /** Human-readable reason for IGNORED / ERROR / DUPLICATE. */
    val detail: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** What rules are matched against, and what ends up in `external_info`. */
    val haystack: String
        get() = listOfNotNull(
            title?.takeIf { it.isNotBlank() },
            (bigText ?: text)?.takeIf { it.isNotBlank() },
            subText?.takeIf { it.isNotBlank() && it != text },
        ).joinToString("\n")
}

enum class NumberFormatStyle {
    /** Guess from the string: the separator that appears last is the decimal one. */
    AUTO,

    /** 1'234.50 — apostrophe groups, dot decimal. */
    SWISS,

    /** 1.234,50 — dot groups, comma decimal. */
    EU,
}

enum class TxTypeMode {
    EXPENSE,
    INCOME,

    /** expense unless [Rule.incomePattern] matches the notification. */
    FROM_PATTERN,
}

@Serializable
@Entity(tableName = "rule", indices = [Index("packageName"), Index("enabled")])
data class Rule(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean = true,
    /** Lower runs first; the first matching rule wins. */
    val priority: Int = 100,
    val packageName: String,
    /** Optional regex the notification title must contain. */
    val titlePattern: String? = null,
    /** Regex with named groups, matched against the whole notification. */
    val textPattern: String,
    /** If this matches, the notification is deliberately skipped. */
    val excludePattern: String? = null,
    val amountGroup: String = "amount",
    val merchantGroup: String? = "merchant",
    val currencyGroup: String? = "currency",
    val numberFormat: NumberFormatStyle = NumberFormatStyle.AUTO,
    val defaultCurrency: String = "CHF",
    val txTypeMode: TxTypeMode = TxTypeMode.EXPENSE,
    val incomePattern: String? = null,
    /** uuid of an account in the web app. */
    val sourceAccountId: String,
    val sourceAccountName: String? = null,
    val sourceAccountCurrency: String? = null,
    val categoryId: String? = null,
    val categoryName: String? = null,
    /** Badge shown next to the pending transaction in the web app, e.g. "twint". */
    val externalSource: String,
    val descriptionTemplate: String = "{merchant}",
    val noteTemplate: String? = null,
    /** false → capture and queue, but wait for a manual tap before sending. */
    val autoPost: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** The fate of a posted transaction inside the web app. */
enum class ServerStatus {
    /** Sitting on /pending, waiting for a decision. */
    PENDING,

    /** Accepted; [OutboxItem.confirmedTransactionId] points at the transaction. */
    CONFIRMED,

    REJECTED,

    /** Posted once, but the row is no longer there — deleted in the web app. */
    GONE,
}

enum class OutboxState {
    /** Waiting to be sent (possibly inside the undo window). */
    QUEUED,

    /** Waiting for an explicit "post now" — rule or app has auto-post off. */
    HELD,

    SENDING,
    POSTED,

    /** The server already had this transaction. */
    DEDUPED,

    /** Transient failure, will be retried. */
    FAILED_RETRY,

    /** Rejected by the server or out of attempts — needs a human. */
    FAILED_PERMANENT,

    /** Undone before it was sent. */
    CANCELLED,
}

@Entity(
    tableName = "outbox_item",
    indices = [Index("state"), Index("externalRefBase"), Index("createdAt")],
)
data class OutboxItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val capturedId: Long?,
    val ruleId: String?,
    /** Idempotency key actually sent to the server. */
    val externalRef: String,
    /** Key without the repeat counter, used for local duplicate detection. */
    val externalRefBase: String,
    val payloadJson: String,
    val amountCents: Long,
    val currency: String,
    val description: String?,
    val state: OutboxState = OutboxState.QUEUED,
    val attempts: Int = 0,
    val lastError: String? = null,
    val remotePendingId: String? = null,
    /** What the web app has done with it since, null until first checked. */
    val serverStatus: ServerStatus? = null,
    /** Set once the pending transaction is confirmed; links to /edit/<id>. */
    val confirmedTransactionId: String? = null,
    val rejectReason: String? = null,
    val statusCheckedAt: Long = 0,
    /** Epoch millis before which this must not be sent — the undo window. */
    val notBefore: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "account_cache")
data class AccountCache(
    @PrimaryKey val id: String,
    val name: String,
    val type: String?,
    val currencyCode: String?,
    val currencySymbol: String?,
    val archived: Boolean = false,
    val sortIndex: Int = 0,
)

@Entity(tableName = "category_cache")
data class CategoryCache(
    @PrimaryKey val id: String,
    val name: String,
    val groupName: String?,
    val archived: Boolean = false,
    val sortIndex: Int = 0,
)
