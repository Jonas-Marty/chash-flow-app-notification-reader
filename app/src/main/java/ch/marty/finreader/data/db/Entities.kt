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

/** Whether a payment matched by a rule may carry the phone's location. */
enum class LocationMode {
    /** Attach the fix, as long as Settings → Location is on. */
    ATTACH,

    /**
     * Never attach. An online payment happens wherever the phone happens to
     * be — usually at home — which is both the wrong place and one worth not
     * recording at all.
     */
    NEVER,

    /** Attach unless [Rule.locationExcludePattern] matches the notification. */
    UNLESS_PATTERN,
}

/** What a matched notification turns into in the web app. */
enum class RuleTarget {
    /** A pending transaction, confirmed on `/pending`. */
    PENDING_TRANSACTION,

    /**
     * A bill: its amount and the notification's date are proposed for the next
     * entry of a variable-amount recurring rule, which the user still posts by
     * hand. Nothing is created on `/pending`.
     */
    RECURRING_BILL,
}

enum class TxTypeMode {
    EXPENSE,
    INCOME,

    /** expense unless [Rule.incomePattern] matches the notification. */
    FROM_PATTERN,

    /**
     * Money moved between two of my own accounts, e.g. a cash withdrawal into
     * the wallet. Goes to [Rule.destinationAccountId]; no category.
     */
    TRANSFER,
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
    /** One regex per line; a line that matches skips the notification. */
    val excludePattern: String? = null,
    val amountGroup: String = "amount",
    val merchantGroup: String? = "merchant",
    val currencyGroup: String? = "currency",
    val numberFormat: NumberFormatStyle = NumberFormatStyle.AUTO,
    val defaultCurrency: String = "CHF",
    val txTypeMode: TxTypeMode = TxTypeMode.EXPENSE,
    val incomePattern: String? = null,
    /** Whether payments matched by this rule may carry the phone's location. */
    val locationMode: LocationMode = LocationMode.ATTACH,
    /** With [LocationMode.UNLESS_PATTERN]: a match here suppresses the fix. */
    val locationExcludePattern: String? = null,
    val target: RuleTarget = RuleTarget.PENDING_TRANSACTION,
    /** With [RuleTarget.RECURRING_BILL]: uuid of the recurring rule in the web app. */
    val recurringRuleId: String? = null,
    val recurringRuleName: String? = null,
    /** uuid of an account in the web app; unused for [RuleTarget.RECURRING_BILL]. */
    val sourceAccountId: String,
    val sourceAccountName: String? = null,
    val sourceAccountCurrency: String? = null,
    /**
     * With [TxTypeMode.TRANSFER]: uuid of the account the money goes to. Left
     * empty, the destination is picked on `/pending` when confirming.
     */
    val destinationAccountId: String? = null,
    val destinationAccountName: String? = null,
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

    /**
     * The web app understood it and said no for a reason retrying cannot fix —
     * a bill whose recurring entry was already posted or skipped. The user has
     * to act in the web app, so this is not offered for sending again.
     */
    REFUSED,
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
    /** The pending transaction's id, or the recurring occurrence's for a bill. */
    val remotePendingId: String? = null,
    /** Which endpoint [payloadJson] is for; taken from the rule when captured. */
    val target: RuleTarget = RuleTarget.PENDING_TRANSACTION,
    /** For a bill: the recurring rule it fills, and that entry's date once known. */
    val recurringRuleName: String? = null,
    val recurringDueOn: String? = null,
    /** What the web app has done with it since, null until first checked. */
    val serverStatus: ServerStatus? = null,
    /**
     * Set once the pending transaction is confirmed or the bill's entry posted;
     * links to /edit/<id>. Also set on a REFUSED bill, whose entry was posted
     * before the bill arrived.
     */
    val confirmedTransactionId: String? = null,
    val rejectReason: String? = null,
    val statusCheckedAt: Long = 0,
    /**
     * Where the payment happened, if location capture is on. Held on the item
     * rather than baked into [payloadJson] so the outbox worker can improve it
     * with a fresher fix right up to the moment it posts.
     */
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationAccuracyM: Float? = null,
    /** When the fix was taken — a stale one is not where the payment happened. */
    val locationAt: Long = 0,
    /**
     * What the rule decided about location for this notification, kept here so
     * the outbox worker cannot undo it by refining a fix on the way out. The
     * rule may well have been edited or deleted by then.
     */
    val attachLocation: Boolean = true,
    /**
     * The accuracy of the fix the server actually holds, null when it holds no
     * point. Distinct from [locationAccuracyM], which is the best fix the phone
     * has: between a post and a refinement the two differ, and the gap is
     * exactly what decides whether a second POST is worth making.
     */
    val locationSentAccuracyM: Float? = null,
    /**
     * When to make the one refinement attempt, or 0 for none. The server
     * improves a pending row's location on a repeat POST with the same
     * `external_ref`, so a fix that arrives after the payment was sent is not
     * wasted — see [ch.marty.finreader.data.OutboxLocation.refineAt].
     */
    val locationRefineAt: Long = 0,
    /**
     * Why this payment carries the location it carries, in words. Covers the
     * silent cases: a permission that is granted but not for the background, a
     * fix too vague to be worth storing, a refinement the server took or did
     * not take. Shown in the Inbox.
     */
    val locationNote: String? = null,
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

/** Mirror of `/api/public/recurring-rules`, for the rule editor's picker. */
@Entity(tableName = "recurring_rule_cache")
data class RecurringRuleCache(
    @PrimaryKey val id: String,
    val name: String,
    /** Variable amount — the only rules a bill may fill in. */
    val acceptsProposals: Boolean,
    val estimatedAmount: Double?,
    /** Date of the next entry still waiting to be posted, `yyyy-MM-dd`. */
    val nextDueOn: String?,
    val sortIndex: Int = 0,
)
