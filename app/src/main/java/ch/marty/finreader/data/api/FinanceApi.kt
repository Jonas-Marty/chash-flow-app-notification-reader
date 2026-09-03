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
    }
}
