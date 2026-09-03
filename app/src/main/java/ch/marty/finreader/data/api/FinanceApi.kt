package ch.marty.finreader.data.api

import ch.marty.finreader.data.prefs.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    /** 4xx — retrying will not help. */
    data class ClientError(val code: Int, val message: String) : ApiResult<Nothing>

    /** 5xx — worth retrying later. */
    data class ServerError(val code: Int, val message: String) : ApiResult<Nothing>

    /** No connection, timeout, TLS problem — worth retrying later. */
    data class NetworkError(val message: String) : ApiResult<Nothing>
}

data class PostOutcome(val remoteId: String?, val deduplicated: Boolean)

/**
 * @param filtered whether the server honoured the `external_ref` filter. When
 *   it did, a ref missing from [rows] really was deleted; when it did not, the
 *   rows are just the most recent page and absence means nothing.
 */
data class PendingLookup(val rows: List<PendingTransactionDto>, val filtered: Boolean)

class FinanceApi(private val settings: SettingsStore) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    suspend fun fetchAccounts(): ApiResult<List<AccountDto>> =
        get("/api/public/accounts") { json.decodeFromString<AccountsResponse>(it).accounts }

    suspend fun fetchCategories(): ApiResult<CategoriesResponse> =
        get("/api/public/categories") { json.decodeFromString<CategoriesResponse>(it) }

    /** Used by the connection test: cheap, authenticated, and read-only. */
    suspend fun fetchPendingCount(): ApiResult<Int> =
        get("/api/public/pending-transactions?status=pending") {
            json.decodeFromString<PendingListResponse>(it).pendingTransactions.size
        }

    /**
     * Looks up what the web app has done with the given transactions.
     *
     * The refs go up as a filter, but the caller must still match the response
     * by `external_ref`: a server that does not know the parameter ignores it
     * and answers with the most recent rows instead. Refs are hex with an
     * optional `-N`, so they need no escaping.
     */
    suspend fun fetchPendingByRefs(refs: List<String>): ApiResult<PendingLookup> {
        val found = mutableListOf<PendingTransactionDto>()
        var filtered = true
        for (chunk in refs.chunked(REF_CHUNK)) {
            val wanted = chunk.toSet()
            val query = chunk.joinToString(",")
            when (val page = get("/api/public/pending-transactions?external_ref=$query") {
                json.decodeFromString<PendingListResponse>(it).pendingTransactions
            }) {
                is ApiResult.Ok -> {
                    // Anything outside the requested set proves the server did
                    // not apply the filter, so absence proves nothing.
                    if (page.value.any { it.externalRef !in wanted }) filtered = false
                    found += page.value
                }

                is ApiResult.ClientError -> return page
                is ApiResult.ServerError -> return page
                is ApiResult.NetworkError -> return page
            }
        }
        return ApiResult.Ok(PendingLookup(found, filtered))
    }

    /**
     * Removes a pending transaction from the web app so a re-run can post a
     * fresh one under the same `external_ref`.
     *
     * A 404 counts as success: the row is gone, which is all the caller wanted.
     * A 409 is passed through — the web app refuses to delete a transaction the
     * user has already accepted.
     *
     * @return true when this call did the deleting, false when it was already gone.
     */
    suspend fun deletePending(
        remoteId: String?,
        externalSource: String?,
        externalRef: String?,
    ): ApiResult<Boolean> = withContext(Dispatchers.IO) {
        val config = settings.current()
        if (!config.isConfigured) {
            return@withContext ApiResult.ClientError(0, "Server URL or API token missing")
        }
        val query = when {
            !remoteId.isNullOrBlank() -> "id=${encode(remoteId)}"
            !externalSource.isNullOrBlank() && !externalRef.isNullOrBlank() ->
                "external_source=${encode(externalSource)}&external_ref=${encode(externalRef)}"

            else -> return@withContext ApiResult.ClientError(
                0,
                "Nothing to identify the pending transaction with",
            )
        }
        val request = Request.Builder()
            .url(config.baseUrl + "/api/public/pending-transactions?" + query)
            .addHeader("Authorization", "Bearer ${config.apiToken}")
            .addHeader("Accept", "application/json")
            .delete()
            .build()
        when (val result = execute(request) { true }) {
            is ApiResult.ClientError -> if (result.code == 404) ApiResult.Ok(false) else result
            else -> result
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    suspend fun postPending(payloadJson: String): ApiResult<PostOutcome> =
        withContext(Dispatchers.IO) {
            val config = settings.current()
            if (!config.isConfigured) {
                return@withContext ApiResult.ClientError(0, "Server URL or API token missing")
            }
            val request = Request.Builder()
                .url(config.baseUrl + "/api/public/pending-transactions")
                .addHeader("Authorization", "Bearer ${config.apiToken}")
                .addHeader("Accept", "application/json")
                .post(payloadJson.toRequestBody(JSON_MEDIA))
                .build()
            execute(request) { body ->
                val parsed = json.decodeFromString<PendingTransactionResponse>(body)
                PostOutcome(parsed.pendingTransaction?.id, parsed.deduplicated)
            }
        }

    private suspend fun <T> get(path: String, parse: (String) -> T): ApiResult<T> =
        withContext(Dispatchers.IO) {
            val config = settings.current()
            if (!config.isConfigured) {
                return@withContext ApiResult.ClientError(0, "Server URL or API token missing")
            }
            val request = Request.Builder()
                .url(config.baseUrl + path)
                .addHeader("Authorization", "Bearer ${config.apiToken}")
                .addHeader("Accept", "application/json")
                .get()
                .build()
            execute(request, parse)
        }

    private fun <T> execute(request: Request, parse: (String) -> T): ApiResult<T> = try {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when {
                response.isSuccessful -> runCatching { ApiResult.Ok(parse(body)) }
                    .getOrElse { ApiResult.ClientError(response.code, "Unexpected response: ${it.message}") }

                response.code == 401 -> ApiResult.ClientError(401, "Unauthorized — check the API token")
                response.code in 400..499 -> ApiResult.ClientError(response.code, describe(response.code, body))
                else -> ApiResult.ServerError(response.code, describe(response.code, body))
            }
        }
    } catch (e: IOException) {
        ApiResult.NetworkError(e.message ?: e.javaClass.simpleName)
    } catch (e: IllegalArgumentException) {
        ApiResult.ClientError(0, "Invalid server URL: ${e.message}")
    }

    private fun describe(code: Int, body: String): String {
        val detail = runCatching {
            json.decodeFromString<PendingTransactionResponse>(body).error
        }.getOrNull()
        return detail?.let { "HTTP $code: $it" } ?: "HTTP $code: ${body.take(180)}"
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** Keeps the query string well clear of any server's URL length limit. */
        const val REF_CHUNK = 40
    }
}
