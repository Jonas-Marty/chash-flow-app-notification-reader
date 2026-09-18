package ch.marty.finreader.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AccountsResponse(val accounts: List<AccountDto> = emptyList())

@Serializable
data class AccountDto(
    val id: String,
    val name: String,
    val type: String? = null,
    val archived: Boolean = false,
    @SerialName("currency_code") val currencyCode: String? = null,
    @SerialName("currency_symbol") val currencySymbol: String? = null,
)

@Serializable
data class CategoriesResponse(
    val categories: List<CategoryDto> = emptyList(),
    val groups: List<CategoryGroupDto> = emptyList(),
)

@Serializable
data class CategoryDto(
    val id: String,
    val name: String,
    @SerialName("group_id") val groupId: String? = null,
    val archived: Boolean = false,
    @SerialName("sort_order") val sortOrder: Int? = null,
)

@Serializable
data class CategoryGroupDto(
    val id: String,
    val name: String,
    val kind: String? = null,
    val archived: Boolean = false,
    @SerialName("sort_order") val sortOrder: Int? = null,
)

/** Mirrors the request body accepted by `/api/public/pending-transactions`. */
@Serializable
data class PendingTransactionPayload(
    @SerialName("source_account_id") val sourceAccountId: String,
    val amount: String,
    val type: String,
    @SerialName("occurred_on") val occurredOn: String,
    @SerialName("category_id") val categoryId: String? = null,
    val description: String? = null,
    val note: String? = null,
    @SerialName("external_source") val externalSource: String? = null,
    @SerialName("external_ref") val externalRef: String? = null,
    @SerialName("external_info") val externalInfo: String? = null,
    /** Sent only as a pair; the server rejects one without the other. */
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("location_accuracy_m") val locationAccuracyM: Float? = null,
    @SerialName("location_source") val locationSource: String? = null,
)

@Serializable
data class PendingTransactionResponse(
    @SerialName("pending_transaction") val pendingTransaction: PendingTransactionDto? = null,
    val deduplicated: Boolean = false,
    val error: String? = null,
)

@Serializable
data class PendingTransactionDto(
    val id: String,
    val amount: Double? = null,
    val description: String? = null,
    /** "pending", "confirmed" or "rejected". */
    val status: String? = null,
    @SerialName("external_source") val externalSource: String? = null,
    @SerialName("external_ref") val externalRef: String? = null,
    @SerialName("confirmed_transaction_id") val confirmedTransactionId: String? = null,
    @SerialName("confirmed_at") val confirmedAt: String? = null,
    @SerialName("rejected_at") val rejectedAt: String? = null,
    @SerialName("reject_reason") val rejectReason: String? = null,
)

@Serializable
data class PendingListResponse(
    @SerialName("pending_transactions") val pendingTransactions: List<PendingTransactionDto> = emptyList(),
)

@Serializable
data class RecurringRulesResponse(
    @SerialName("recurring_rules") val recurringRules: List<RecurringRuleDto> = emptyList(),
)

@Serializable
data class RecurringRuleDto(
    val id: String,
    val name: String,
    @SerialName("estimated_amount") val estimatedAmount: Double? = null,
    @SerialName("is_variable_amount") val isVariableAmount: Boolean = false,
    /** Absent on a server that predates bill proposals; derived from the amount flag then. */
    @SerialName("accepts_proposals") val acceptsProposals: Boolean? = null,
    @SerialName("next_pending_occurrence") val nextPendingOccurrence: OccurrenceDateDto? = null,
)

@Serializable
data class OccurrenceDateDto(
    @SerialName("due_on") val dueOn: String? = null,
    @SerialName("effective_on") val effectiveOn: String? = null,
)

/** Mirrors the request body accepted by `/api/public/recurring-proposals`. */
@Serializable
data class RecurringProposalPayload(
    @SerialName("recurring_rule_id") val recurringRuleId: String,
    val amount: String,
    /** The notification's date; the web app pre-fills the transaction date with it. */
    @SerialName("occurred_on") val occurredOn: String,
    /** Shown to the user in the web app as who provided the numbers. */
    @SerialName("external_source") val externalSource: String,
    @SerialName("external_ref") val externalRef: String,
    @SerialName("external_info") val externalInfo: String? = null,
)

@Serializable
data class RecurringProposalResponse(
    @SerialName("recurring_proposal") val proposal: RecurringProposalDto? = null,
    val deduplicated: Boolean = false,
    val error: String? = null,
    /** `already_posted`, `skipped`, `no_occurrence`, `fixed_amount`, … */
    val code: String? = null,
)

@Serializable
data class RecurringProposalDto(
    @SerialName("occurrence_id") val occurrenceId: String,
    @SerialName("rule_name") val ruleName: String? = null,
    @SerialName("effective_on") val effectiveOn: String? = null,
    /** The occurrence's status: "pending", "posted" or "skipped". */
    val status: String? = null,
    @SerialName("transaction_id") val transactionId: String? = null,
    @SerialName("external_ref") val externalRef: String? = null,
)

@Serializable
data class RecurringProposalListResponse(
    @SerialName("recurring_proposals") val proposals: List<RecurringProposalDto> = emptyList(),
)
