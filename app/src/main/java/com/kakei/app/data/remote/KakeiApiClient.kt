package com.kakei.app.data.remote

import com.kakei.app.data.remote.dto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/** A request was refused locally because it would send credentials over plain http to a public host. */
class InsecureServerException(val host: String) :
    IOException("Refusing to send credentials over plain http to $host; use https")

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
    initialBaseUrl: String = "",
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
                if (cookie.name == REFRESH_COOKIE_NAME) persistRefreshCookie(url.host, cookie)
            }
            cookieStore[url.host] = existing
        }

        // The refresh token travels only to the token endpoints (refresh / revoke) and never
        // over plain http when the server marked it Secure. Its Path is checked by suffix
        // because the server issues it for `/api/identity/token` even when it is reached
        // through the `/v1/api` prefix.
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            return cookieStore[url.host]?.filter { cookie ->
                cookie.expiresAt > now &&
                    (!cookie.secure || url.isHttps) &&
                    if (cookie.name == REFRESH_COOKIE_NAME) {
                        url.encodedPath.contains(TOKEN_ENDPOINT_PREFIX)
                    } else {
                        cookie.matches(url)
                    }
            } ?: emptyList()
        }
    }

    init {
        restoreRefreshCookie()
    }

    private fun persistRefreshCookie(host: String, cookie: Cookie) {
        val storage = refreshCookieStorage ?: return
        // An expired/empty cookie is how the server revokes the session.
        val expired = cookie.expiresAt <= System.currentTimeMillis() || cookie.value.isBlank()
        storage.save(if (expired) null else "$host$COOKIE_HOST_SEPARATOR$cookie")
    }

    /**
     * Re-attaches the stored refresh cookie, but only to the host that issued it: a cookie
     * saved for one server is never handed to another one after the server URL changes.
     * Entries written by older builds carry no host and are bound to the current server once.
     */
    private fun restoreRefreshCookie() {
        val raw = refreshCookieStorage?.load() ?: return
        val url = baseUrl.toHttpUrlOrNull() ?: return
        val separator = raw.indexOf(COOKIE_HOST_SEPARATOR)
        val storedHost = if (separator > 0) raw.substring(0, separator) else null
        val serialized = if (separator > 0) raw.substring(separator + 1) else raw
        if (storedHost != null && !storedHost.equals(url.host, ignoreCase = true)) return
        val cookie = Cookie.parse(url, serialized) ?: return
        if (cookie.expiresAt <= System.currentTimeMillis()) {
            refreshCookieStorage.save(null)
            return
        }
        cookieStore[url.host] = listOf(cookie)
        if (storedHost == null) persistRefreshCookie(url.host, cookie)
    }

    /** True when a refresh token is on hand, i.e. a session can outlive the access JWT. */
    fun hasRefreshToken(): Boolean {
        val host = baseUrl.toHttpUrlOrNull()?.host ?: return false
        return cookieStore[host]?.any {
            it.name == REFRESH_COOKIE_NAME && it.expiresAt > System.currentTimeMillis()
        } == true
    }

    /** Scheme, host and port of the configured server, or null while no valid URL is set. */
    fun serverOrigin(): String? = originOf(baseUrl)

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
            if (!original.url.isHttps && !isPrivateHost(original.url.host)) {
                // Passwords, the JWT and the refresh cookie must not cross the internet in clear text.
                throw InsecureServerException(original.url.host)
            }
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
            return when (val outcome = tryRefreshTokenSync()) {
                is RefreshOutcome.Refreshed -> {
                    accessToken = outcome.token
                    onAccessTokenRefreshed?.invoke(outcome.token)
                    outcome.token
                }
                // Only the server saying "this refresh token is no good" ends the session.
                RefreshOutcome.Rejected -> {
                    expireSession()
                    null
                }
                // Network failure, timeout, 429 or 5xx: the session is still valid, the
                // caller gets its 401 back and the request is retried later.
                RefreshOutcome.Unavailable -> null
            }
        }
    }

    private sealed interface RefreshOutcome {
        data class Refreshed(val token: String) : RefreshOutcome
        data object Rejected : RefreshOutcome
        data object Unavailable : RefreshOutcome
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

    private fun tryRefreshTokenSync(): RefreshOutcome {
        return try {
            val url = "$baseUrl/identity/token/refresh"
            val req = Request.Builder()
                .url(url)
                .post("".toRequestBody("application/json".toMediaType()))
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful -> {
                        val token = json.decodeFromString<TokenRefreshResponse>(resp.body.string()).token
                        if (token.isBlank()) RefreshOutcome.Unavailable else RefreshOutcome.Refreshed(token)
                    }
                    // The server refuses a missing, expired or revoked refresh token with 400.
                    resp.code == 400 || resp.code == 401 || resp.code == 403 -> RefreshOutcome.Rejected
                    else -> RefreshOutcome.Unavailable
                }
            }
        } catch (_: Exception) {
            RefreshOutcome.Unavailable
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
                ?: throw IllegalArgumentException(
                    if (baseUrl == "/api") "Kakei server URL is not set" else "Invalid API URL: $baseUrl$path"
                )
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
                val rawBody = response.body.string()
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

    suspend fun passkeyLoginStart(req: PasskeyLoginStartRequest): Result<PasskeyCeremonyResponse> =
        requestJson("POST", "/identity/passkey-login-start", json.encodeToString(req))

    suspend fun passkeyLoginFinish(req: PasskeyLoginFinishRequest): Result<LoginResponse> =
        requestJson<LoginResponse>("POST", "/identity/passkey-login-finish", json.encodeToString(req)).onSuccess {
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

    /**
     * Sets the new password. The server answers like `/identity/login`: a session for the new
     * credentials (or a 2FA step when [LoginResponse.need2fa]) plus a fresh refresh cookie.
     */
    suspend fun resetPassword(req: PasswordResetRequest): Result<LoginResponse> =
        requestJson<LoginResponse>("POST", "/identity/password-reset", json.encodeToString(req)).onSuccess {
            if (!it.need2fa) accessToken = it.token
        }

    suspend fun revokeToken(): Result<Unit> =
        requestJson<Unit>("POST", "/identity/token/revoke").also { clearSession() }

    // 2. Profile
    suspend fun getProfileInfo(): Result<ProfileInfoResponse> =
        requestJson("GET", "/profile/info")

    suspend fun updateProfileInfo(req: ProfileInfoUpdateRequest): Result<Unit> =
        requestJson("PUT", "/profile/info", json.encodeToString(req))

    /** Erases the account on the server; the request carries the password (or a passkey assertion). */
    suspend fun deleteAccount(req: AccountDeleteRequest): Result<Unit> =
        requestJson("DELETE", "/profile/account", json.encodeToString(req))

    /**
     * Changes the password. Every other session ends; this one continues with the access token
     * returned here and the refresh cookie set by the same response.
     */
    suspend fun changePassword(req: PasswordChangeRequest): Result<LoginResponse> =
        requestJson<LoginResponse>("POST", "/profile/password-change", json.encodeToString(req)).onSuccess {
            accessToken = it.token
            onAccessTokenRefreshed?.invoke(it.token)
        }

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

    suspend fun passkeyRegisterStart(): Result<PasskeyCeremonyResponse> =
        requestJson("POST", "/profile/passkeys/register-start")

    suspend fun passkeyRegisterFinish(req: PasskeyRegisterFinishRequest): Result<UserPasskeyItem> =
        requestJson("POST", "/profile/passkeys/register-finish", json.encodeToString(req))

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

    suspend fun reorderAccounts(req: ReorderAccountsRequest): Result<Unit> =
        requestJson("PUT", "/accounts/order", json.encodeToString(req))

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

    /**
     * Posts the next occurrence now. [expectedDueDate] (`YYYY-MM-DD`) names the occurrence the
     * user was looking at: when the server has already moved past it (a repeated request), it
     * answers 409 `obligation_already_posted` instead of booking the following one as well.
     */
    suspend fun executeObligation(id: String, expectedDueDate: String? = null): Result<ExecuteObligationResponse> =
        requestJson(
            "POST",
            "/planning/obligations/$id/execute",
            queryParams = mapOf("expectedDueDate" to expectedDueDate)
        )

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
                if (!resp.isSuccessful) {
                    val problem = runCatching {
                        json.decodeFromString<ProblemDetails>(resp.body.string())
                    }.getOrNull()
                    throw ApiException(
                        statusCode = resp.code,
                        problem = problem,
                        message = problem?.readableMessage() ?: "Export failed: HTTP ${resp.code}"
                    )
                }
                resp.body.bytes()
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

        /** Separates the issuing host from the serialized cookie in [RefreshCookieStorage]. */
        private const val COOKIE_HOST_SEPARATOR = '\n'

        private const val TOKEN_ENDPOINT_PREFIX = "/identity/token/"

        /** `scheme://host:port` of a server URL, or null when it is not a valid http(s) URL. */
        fun originOf(rawUrl: String): String? {
            val url = rawUrl.trim().toHttpUrlOrNull() ?: return null
            return "${url.scheme}://${url.host}:${url.port}"
        }

        /** True for a server URL that would send credentials over plain http to a public host. */
        fun isInsecurePublicUrl(rawUrl: String): Boolean {
            val url = rawUrl.trim().toHttpUrlOrNull() ?: return false
            return !url.isHttps && !isPrivateHost(url.host)
        }

        /**
         * Hosts a self-hosted server may reasonably be reached at over plain http: the device
         * itself, the emulator host, private (RFC 1918 / unique-local) and link-local
         * addresses, and names that only resolve inside a local network.
         */
        fun isPrivateHost(host: String): Boolean {
            val h = host.trim().lowercase().removePrefix("[").removeSuffix("]")
            if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") ||
                h.endsWith(".lan") || h.endsWith(".home.arpa") || h.endsWith(".internal")
            ) return true
            if (':' in h) {
                // IPv6 literal: loopback, unique-local fc00::/7, link-local fe80::/10.
                return h == "::1" || h.startsWith("fc") || h.startsWith("fd") ||
                    h.startsWith("fe8") || h.startsWith("fe9") || h.startsWith("fea") || h.startsWith("feb")
            }
            val octets = h.split('.').map { it.toIntOrNull() ?: return !h.contains('.') }
            if (octets.size != 4 || octets.any { it !in 0..255 }) return false
            val (a, b) = octets
            return a == 10 || a == 127 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 169 && b == 254) ||
                (a == 100 && b in 64..127) // carrier-grade NAT, used by mesh VPNs
        }
    }
}
