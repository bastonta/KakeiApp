package com.kakei.app.data.remote

import com.kakei.app.data.remote.dto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ApiException(
    val statusCode: Int,
    val problem: ProblemDetails?,
    override val message: String
) : IOException(message)

/**
 * Persists the `refresh_token` cookie between process restarts. Backed by the
 * Keystore-encrypted `SecurePrefs` in production.
 */
interface RefreshCookieStorage {
    fun load(): String?
    fun save(serializedCookie: String?)
}

/**
 * Full-featured HTTP client for Kakei REST API (`/api` prefix) supporting:
 * - Dynamic Base URL configuration
 * - Bearer JWT authentication with single-flight refresh on 401
 * - Persistent (encrypted) CookieJar for `refresh_token` rotation
 * - RFC 7807 / 9457 ProblemDetails error parsing
 * - HTTP 207 Multi-Status batch responses
 */
class KakeiApiClient(
    initialBaseUrl: String = "http://10.0.2.2:5144",
    initialToken: String? = null,
    private val refreshCookieStorage: RefreshCookieStorage? = null
) {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        coerceInputValues = true
    }

    @Volatile
    var baseUrl: String = normalizeBaseUrl(initialBaseUrl)
        private set

    @Volatile
    var accessToken: String? = initialToken

    /**
     * Invoked (at most once per expired session) when a request got 401 and the
     * refresh token could not be used. The owner must drop the session state.
     */
    @Volatile
    var onSessionExpired: (() -> Unit)? = null

    /** Called whenever a new access token is obtained through refresh. */
    @Volatile
    var onAccessTokenRefreshed: ((String) -> Unit)? = null

    private val refreshLock = ReentrantLock()

    private val cookieStore = ConcurrentHashMap<String, List<Cookie>>()

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val existing = cookieStore[url.host]?.toMutableList() ?: mutableListOf()
            for (cookie in cookies) {
                existing.removeAll { it.name == cookie.name }
                existing.add(cookie)
                if (cookie.name == REFRESH_COOKIE_NAME) persistRefreshCookie(cookie)
            }
            cookieStore[url.host] = existing
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookieStore[url.host]?.filter { it.expiresAt > System.currentTimeMillis() }
                ?: emptyList()
        }
    }

    init {
        restoreRefreshCookie()
    }

    private fun persistRefreshCookie(cookie: Cookie) {
        val storage = refreshCookieStorage ?: return
        // An expired/empty cookie is how the server revokes the session.
        val expired = cookie.expiresAt <= System.currentTimeMillis() || cookie.value.isBlank()
        storage.save(if (expired) null else cookie.toString())
    }

    private fun restoreRefreshCookie() {
        val raw = refreshCookieStorage?.load() ?: return
        val url = baseUrl.toHttpUrlOrNull() ?: return
        val cookie = Cookie.parse(url, raw) ?: return
        if (cookie.expiresAt <= System.currentTimeMillis()) {
            refreshCookieStorage.save(null)
            return
        }
        cookieStore[url.host] = listOf(cookie)
    }

    /** True when a refresh token is on hand, i.e. a session can outlive the access JWT. */
    fun hasRefreshToken(): Boolean {
        val host = baseUrl.toHttpUrlOrNull()?.host ?: return false
        return cookieStore[host]?.any {
            it.name == REFRESH_COOKIE_NAME && it.expiresAt > System.currentTimeMillis()
        } == true
    }

    /** Forgets every session credential (used on sign-out and expiry). */
    fun clearSession() {
        accessToken = null
        cookieStore.clear()
        refreshCookieStorage?.save(null)
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val original = chain.request()
            val tokenUsed = accessToken
            val builder = original.newBuilder()
                .header("Accept", "application/json")
            val isRefreshCall = original.url.encodedPath.endsWith("/identity/token/refresh")
            if (!tokenUsed.isNullOrBlank() && !isRefreshCall && original.header("Authorization") == null) {
                builder.header("Authorization", "Bearer $tokenUsed")
            }
            val response = chain.proceed(builder.build())
            if (response.code != 401 || original.url.encodedPath.contains("/identity/")) {
                return@addInterceptor response
            }

            // The first response stays open until we know whether a retry happens, so a
            // failed refresh still hands the caller a readable 401 body.
            val refreshed = refreshAccessTokenSingleFlight(tokenUsed)
            if (refreshed.isNullOrBlank()) {
                return@addInterceptor response
            }
            response.close()
            val retryReq = original.newBuilder()
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $refreshed")
                .build()
            chain.proceed(retryReq)
        }
        .build()

    /**
     * Refreshes the access token once even when many requests hit 401 concurrently:
     * callers that arrive while a refresh is in flight wait for it and reuse the result.
     */
    private fun refreshAccessTokenSingleFlight(tokenThatFailed: String?): String? {
        refreshLock.withLock {
            val current = accessToken
            if (!current.isNullOrBlank() && current != tokenThatFailed) {
                return current // another thread already refreshed
            }
            if (!hasRefreshToken()) {
                expireSession()
                return null
            }
            val refreshed = tryRefreshTokenSync()
            if (refreshed.isNullOrBlank()) {
                expireSession()
                return null
            }
            accessToken = refreshed
            onAccessTokenRefreshed?.invoke(refreshed)
            return refreshed
        }
    }

    private fun expireSession() {
        if (accessToken == null && !hasRefreshToken()) return
        clearSession()
        onSessionExpired?.invoke()
    }

    fun updateBaseUrl(newUrl: String) {
        baseUrl = normalizeBaseUrl(newUrl)
        // The stored cookie is bound to the server host; re-attach it for the new base URL.
        restoreRefreshCookie()
    }

    private fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim().removeSuffix("/")
        return when {
            trimmed.endsWith("/api") -> trimmed
            trimmed.endsWith("/v1/api") -> trimmed
            else -> "$trimmed/api"
        }
    }

    private fun tryRefreshTokenSync(): String? {
        return try {
            val url = "$baseUrl/identity/token/refresh"
            val req = Request.Builder()
                .url(url)
                .post("".toRequestBody("application/json".toMediaType()))
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    json.decodeFromString<TokenRefreshResponse>(body).token
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend inline fun <reified T> requestJson(
        method: String,
        path: String,
        bodyJson: String? = null,
        queryParams: Map<String, String?> = emptyMap()
    ): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            val fullUrlBuilder = "$baseUrl$path".toHttpUrlOrNull()?.newBuilder()
                ?: throw IllegalArgumentException("Invalid API URL: $baseUrl$path")
            queryParams.forEach { (k, v) ->
                if (!v.isNullOrBlank()) {
                    fullUrlBuilder.addQueryParameter(k, v)
                }
            }
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val reqBody = bodyJson?.toRequestBody(mediaType)
                ?: if (method == "POST" || method == "PUT" || method == "PATCH") {
                    "{}".toRequestBody(mediaType)
                } else null

            val request = Request.Builder()
                .url(fullUrlBuilder.build())
                .method(method, reqBody)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (response.code in 200..299) {
                    when {
                        T::class == Unit::class -> {
                            @Suppress("UNCHECKED_CAST")
                            Unit as T
                        }
                        rawBody.isBlank() -> throw ApiException(
                            statusCode = response.code,
                            problem = null,
                            message = "Empty response body for $method $path"
                        )
                        else -> json.decodeFromString<T>(rawBody)
                    }
                } else {
                    val problem = runCatching {
                        json.decodeFromString<ProblemDetails>(rawBody)
                    }.getOrNull()
                    throw ApiException(
                        statusCode = response.code,
                        problem = problem,
                        message = problem?.readableMessage() ?: "HTTP ${response.code}"
                    )
                }
            }
        }
    }

    // 0. System
    suspend fun getVersion(): Result<VersionResponse> =
        requestJson("GET", "/version")

    // 1. Identity
    suspend fun login(req: LoginRequest): Result<LoginResponse> =
        requestJson<LoginResponse>("POST", "/identity/login", json.encodeToString(req)).onSuccess {
            if (!it.need2fa) accessToken = it.token
        }

    suspend fun login2fa(req: Login2faRequest): Result<Login2faResponse> =
        requestJson<Login2faResponse>("POST", "/identity/login-2fa", json.encodeToString(req)).onSuccess {
            accessToken = it.token
        }

    suspend fun register(req: RegisterRequest): Result<RegisterResponse> =
        requestJson("POST", "/identity/register", json.encodeToString(req))

    suspend fun confirmEmail(req: EmailConfirmRequest): Result<EmailConfirmResponse> =
        requestJson<EmailConfirmResponse>("POST", "/identity/email-confirm", json.encodeToString(req)).onSuccess {
            accessToken = it.token
        }

    suspend fun resendEmailConfirm(req: EmailConfirmResendRequest): Result<EmailConfirmResendResponse> =
        requestJson("POST", "/identity/email-confirm-resend", json.encodeToString(req))

    suspend fun forgotPassword(req: PasswordForgotRequest): Result<PasswordForgotResponse> =
        requestJson("POST", "/identity/password-forgot", json.encodeToString(req))

    suspend fun validatePasswordReset(req: PasswordResetValidationRequest): Result<PasswordResetValidationResponse> =
        requestJson("POST", "/identity/password-reset-validation", json.encodeToString(req))

    suspend fun resetPassword(req: PasswordResetRequest): Result<Unit> =
        requestJson("POST", "/identity/password-reset", json.encodeToString(req))

    suspend fun revokeToken(): Result<Unit> =
        requestJson<Unit>("POST", "/identity/token/revoke").also { clearSession() }

    // 2. Profile
    suspend fun getProfileInfo(): Result<ProfileInfoResponse> =
        requestJson("GET", "/profile/info")

    suspend fun updateProfileInfo(req: ProfileInfoUpdateRequest): Result<Unit> =
        requestJson("PUT", "/profile/info", json.encodeToString(req))

    suspend fun deleteAccount(req: AccountDeleteRequest): Result<Unit> =
        requestJson("DELETE", "/profile/account", json.encodeToString(req))

    suspend fun changePassword(req: PasswordChangeRequest): Result<Unit> =
        requestJson("POST", "/profile/password-change", json.encodeToString(req))

    suspend fun enable2fa(): Result<TfaEnableResponse> =
        requestJson("POST", "/profile/2fa-enable")

    suspend fun confirm2fa(req: TfaConfirmRequest): Result<TfaConfirmResponse> =
        requestJson("POST", "/profile/2fa-confirm", json.encodeToString(req))

    suspend fun disable2fa(req: TfaDisableRequest): Result<Unit> =
        requestJson("POST", "/profile/2fa-disable", json.encodeToString(req))

    suspend fun getExchangeRates(): Result<ExchangeRatesResponse> =
        requestJson("GET", "/profile/exchange-rates")

    suspend fun upsertExchangeRateOverride(req: UpsertExchangeRateOverrideRequest): Result<ExchangeRateOverrideDto> =
        requestJson("PUT", "/profile/exchange-rates/overrides", json.encodeToString(req))

    suspend fun deleteExchangeRateOverride(fromCurrency: String, toCurrency: String): Result<Unit> =
        requestJson("DELETE", "/profile/exchange-rates/overrides/$fromCurrency/$toCurrency")

    suspend fun listPasskeys(): Result<List<UserPasskeyItem>> =
        requestJson("GET", "/profile/passkeys")

    suspend fun deletePasskey(id: String): Result<Unit> =
        requestJson("DELETE", "/profile/passkeys/$id")

    // 3. Accounts
    suspend fun listAccounts(isArchived: Boolean? = null): Result<AccountsListResponse> =
        requestJson(
            "GET",
            "/accounts",
            queryParams = mapOf("isArchived" to isArchived?.toString())
        )

    suspend fun createAccount(req: CreateAccountRequest): Result<AccountDto> =
        requestJson("POST", "/accounts", json.encodeToString(req))

    suspend fun batchUpdateAccounts(req: BatchUpdateAccountsRequest): Result<BatchUpdateAccountsResponse> =
        requestJson("PATCH", "/accounts", json.encodeToString(req))

    suspend fun deleteAccountById(id: String): Result<Unit> =
        requestJson("DELETE", "/accounts/$id")

    // 4. Categories, Labels, Smart Rules
    suspend fun listCategories(): Result<CategoriesListResponse> =
        requestJson("GET", "/categories")

    suspend fun createCategory(req: UpsertCategoryRequest): Result<CategoryDto> =
        requestJson("POST", "/categories", json.encodeToString(req))

    suspend fun updateCategory(id: String, req: UpsertCategoryRequest): Result<CategoryDto> =
        requestJson("PUT", "/categories/$id", json.encodeToString(req))

    suspend fun deleteCategory(id: String): Result<Unit> =
        requestJson("DELETE", "/categories/$id")

    suspend fun listLabels(): Result<LabelsListResponse> =
        requestJson("GET", "/categories/labels")

    suspend fun createLabel(req: CreateLabelRequest): Result<LabelDto> =
        requestJson("POST", "/categories/labels", json.encodeToString(req))

    suspend fun deleteLabel(id: String): Result<Unit> =
        requestJson("DELETE", "/categories/labels/$id")

    suspend fun listRules(): Result<RulesListResponse> =
        requestJson("GET", "/categories/rules")

    suspend fun createRule(req: CreateRuleRequest): Result<CounterpartyRuleDto> =
        requestJson("POST", "/categories/rules", json.encodeToString(req))

    suspend fun deleteRule(id: String): Result<Unit> =
        requestJson("DELETE", "/categories/rules/$id")

    // 5. Records (Transactions)
    suspend fun listRecords(
        accountId: String? = null,
        categoryId: String? = null,
        payeeQuery: String? = null,
        minSyncRevision: Long? = null,
        offset: Int = 0,
        limit: Int = 200
    ): Result<Pagination<TransactionDto>> =
        requestJson(
            "GET",
            "/records",
            queryParams = mapOf(
                "accountId" to accountId,
                "categoryId" to categoryId,
                "payeeQuery" to payeeQuery,
                "minSyncRevision" to minSyncRevision?.toString(),
                "offset" to offset.toString(),
                "limit" to limit.toString()
            )
        )

    suspend fun batchCreateRecords(req: BatchCreateRecordsRequest): Result<BatchCreateRecordsResponse> =
        requestJson("POST", "/records", json.encodeToString(req))

    suspend fun batchUpdateRecords(req: BatchUpdateRecordsRequest): Result<BatchMutateRecordsResponse> =
        requestJson("PATCH", "/records", json.encodeToString(req))

    suspend fun batchDeleteRecords(req: BatchDeleteRecordsRequest): Result<BatchMutateRecordsResponse> =
        requestJson("DELETE", "/records", json.encodeToString(req))

    suspend fun confirmRecordReview(id: String, req: ConfirmReviewRequest): Result<TransactionDto> =
        requestJson("POST", "/records/$id/review", json.encodeToString(req))

    // 6. Budgets
    suspend fun listBudgets(period: String? = null): Result<BudgetsListResponse> =
        requestJson("GET", "/budgets", queryParams = mapOf("period" to period))

    suspend fun createBudget(req: UpsertBudgetRequest): Result<BudgetEnvelopeDto> =
        requestJson("POST", "/budgets", json.encodeToString(req))

    suspend fun updateBudget(id: String, req: UpsertBudgetRequest): Result<BudgetEnvelopeDto> =
        requestJson("PUT", "/budgets/$id", json.encodeToString(req))

    suspend fun deleteBudget(id: String): Result<Unit> =
        requestJson("DELETE", "/budgets/$id")

    // 7. Planning & Forecast
    suspend fun listObligations(activeOnly: Boolean? = null): Result<ObligationsListResponse> =
        requestJson(
            "GET",
            "/planning/obligations",
            queryParams = mapOf("activeOnly" to activeOnly?.toString())
        )

    suspend fun createObligation(req: UpsertObligationRequest): Result<PlannedObligationDto> =
        requestJson("POST", "/planning/obligations", json.encodeToString(req))

    suspend fun updateObligation(id: String, req: UpsertObligationRequest): Result<PlannedObligationDto> =
        requestJson("PUT", "/planning/obligations/$id", json.encodeToString(req))

    suspend fun deleteObligation(id: String): Result<Unit> =
        requestJson("DELETE", "/planning/obligations/$id")

    suspend fun executeObligation(id: String): Result<ExecuteObligationResponse> =
        requestJson("POST", "/planning/obligations/$id/execute")

    suspend fun getForecast(horizonDays: Long = 30): Result<CashFlowForecastDto> =
        requestJson("GET", "/planning/forecast", queryParams = mapOf("horizonDays" to horizonDays.toString()))

    // 8. Lending
    suspend fun listLending(isSettled: Boolean? = null, lendingType: String? = null): Result<LendingListResponse> =
        requestJson(
            "GET",
            "/lending",
            queryParams = mapOf(
                "isSettled" to isSettled?.toString(),
                "lendingType" to lendingType
            )
        )

    suspend fun createLending(req: CreateLendingRequest): Result<LendingRecordDto> =
        requestJson("POST", "/lending", json.encodeToString(req))

    suspend fun createRepayment(id: String, req: CreateRepaymentRequest): Result<LendingRecordDto> =
        requestJson("POST", "/lending/$id/repayments", json.encodeToString(req))

    suspend fun forgiveLending(id: String): Result<LendingRecordDto> =
        requestJson("POST", "/lending/$id/forgive")

    suspend fun splitExpense(req: SplitExpenseRequest): Result<SplitExpenseResponse> =
        requestJson("POST", "/lending/split-expense", json.encodeToString(req))

    suspend fun settleAll(req: SettleAllRequest): Result<SettleAllSummaryDto> =
        requestJson("POST", "/lending/settle-all", json.encodeToString(req))

    // 9. Savings Goals
    suspend fun listSavingsGoals(): Result<SavingsListResponse> =
        requestJson("GET", "/savings")

    suspend fun createSavingsGoal(req: UpsertSavingsGoalRequest): Result<SavingsGoalDto> =
        requestJson("POST", "/savings", json.encodeToString(req))

    suspend fun updateSavingsGoal(id: String, req: UpsertSavingsGoalRequest): Result<SavingsGoalDto> =
        requestJson("PUT", "/savings/$id", json.encodeToString(req))

    suspend fun deleteSavingsGoal(id: String): Result<Unit> =
        requestJson("DELETE", "/savings/$id")

    suspend fun addSavingsContribution(id: String, req: AddContributionRequest): Result<SavingsGoalDto> =
        requestJson("POST", "/savings/$id/contributions", json.encodeToString(req))

    // 10. Exchange Import & Export
    suspend fun importStatement(req: ImportStatementRequest): Result<ImportStatementResponse> =
        requestJson("POST", "/exchange/import", json.encodeToString(req))

    /** [startDate]/[endDate] are sent as ISO 8601 instants (e.g. 2026-01-31T00:00:00Z). */
    suspend fun exportRecordsRaw(
        format: String = "csv",
        startDate: java.time.Instant? = null,
        endDate: java.time.Instant? = null,
        accountId: String? = null
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val urlBuilder = "$baseUrl/exchange/export".toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("format", format)
            startDate?.let { urlBuilder.addQueryParameter("startDate", it.toString()) }
            endDate?.let { urlBuilder.addQueryParameter("endDate", it.toString()) }
            if (!accountId.isNullOrBlank()) urlBuilder.addQueryParameter("accountId", accountId)
            val req = Request.Builder().url(urlBuilder.build()).get().build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Export failed: HTTP ${resp.code}")
                resp.body?.bytes() ?: ByteArray(0)
            }
        }
    }

    // 11. Group Sharing
    suspend fun listGroupMembers(accountId: String): Result<GroupMembersListResponse> =
        requestJson("GET", "/groups/accounts/$accountId/members")

    suspend fun upsertGroupMember(accountId: String, req: UpsertGroupMemberRequest): Result<GroupMemberDto> =
        requestJson("POST", "/groups/accounts/$accountId/members", json.encodeToString(req))

    suspend fun removeGroupMember(accountId: String, memberUserId: String): Result<Unit> =
        requestJson("DELETE", "/groups/accounts/$accountId/members/$memberUserId")

    suspend fun listSharedWithMe(): Result<SharedWithMeResponse> =
        requestJson("GET", "/groups/shared-with-me")

    // 12. Analytics
    suspend fun getHealthScore(periodDays: Long = 30): Result<FinancialHealthScoreDto> =
        requestJson("GET", "/analytics/health-score", queryParams = mapOf("periodDays" to periodDays.toString()))

    suspend fun getHeatmaps(year: Int, month: Int? = null): Result<SpendingHeatmapsDto> =
        requestJson(
            "GET",
            "/analytics/heatmaps",
            queryParams = mapOf("year" to year.toString(), "month" to month?.toString())
        )

    suspend fun getStreaks(): Result<NoSpendStreakDto> =
        requestJson("GET", "/analytics/streaks")

    suspend fun getInsights(): Result<InsightsSummaryDto> =
        requestJson("GET", "/analytics/insights")

    companion object {
        const val REFRESH_COOKIE_NAME = "refresh_token"
    }
}
