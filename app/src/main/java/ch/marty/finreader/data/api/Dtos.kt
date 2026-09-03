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
    val status: String? = null,
)

@Serializable
data class PendingListResponse(
    @SerialName("pending_transactions") val pendingTransactions: List<PendingTransactionDto> = emptyList(),
)
