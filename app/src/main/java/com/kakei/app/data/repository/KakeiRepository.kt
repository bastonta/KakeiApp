package com.kakei.app.data.repository

import android.content.Context
import com.kakei.app.BuildConfig
import com.kakei.app.automation.BankSmsPatterns
import com.kakei.app.automation.KakeiWidgetUpdater
import com.kakei.app.automation.NotificationDeduplicationHelper
import com.kakei.app.automation.SmartMerchantMatcher
import com.kakei.app.core.i18n.AppLanguage
import com.kakei.app.core.security.BackupCryptoHelper
import com.kakei.app.core.security.SecurePrefs
import com.kakei.app.core.util.ConvertedTotal
import com.kakei.app.core.util.CurrencyFormatter
import com.kakei.app.core.util.ExchangeRateBook
import com.kakei.app.core.util.HashDedupCalculator
import com.kakei.app.data.local.KakeiDatabase
import com.kakei.app.data.local.PendingCaptureEntity
import com.kakei.app.data.local.SyncErrorEntity
import com.kakei.app.data.local.SyncMutationEntity
import com.kakei.app.data.remote.ApiException
import com.kakei.app.data.remote.KakeiApiClient
import com.kakei.app.data.remote.RefreshCookieStorage
import com.kakei.app.data.remote.dto.*
import com.kakei.app.domain.calculators.FinancialCalculators
import com.kakei.app.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

data class AppSettingsState(
    val isAuthenticated: Boolean = false,
    val isOfflineAutonomousMode: Boolean = false,
    val pending2faUserId: String? = null,
    val pending2faToken: String? = null,
    val pendingEmailConfirmUserId: String? = null,
    val serverBaseUrl: String = "http://10.0.2.2:5144",
    val serverVersion: String? = null,
    val profile: ProfileInfoResponse = ProfileInfoResponse(
        userId = "local-user",
        email = "offline@kakei.local",
        name = "Kakei User",
        defaultCurrency = "KZT",
        language = "ru",
        twoFactorEnabled = false,
        emailConfirmed = true
    ),
    val language: AppLanguage = AppLanguage.RU,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val biometricLockEnabled: Boolean = false,
    val smsCaptureEnabled: Boolean = true,
    val pushCaptureEnabled: Boolean = true,
    val instantPopupEnabled: Boolean = true,
    /** Account that captured bank alerts are filed into when no sender mapping matches. */
    val captureDefaultAccountId: String? = null,
    /** Alert sender (SMS address / app package) → account id, set when the user files a pending capture. */
    val captureSenderAccounts: Map<String, String> = emptyMap(),
    val syncInProgress: Boolean = false,
    val lastSyncTimestamp: String? = null,
    val statusMessage: String? = null
)

@Serializable
data class EncryptedSnapshotPayload(
    val exportedAt: String,
    val baseCurrency: String,
    val accounts: List<AccountDto>,
    val categories: List<CategoryDto>,
    val labels: List<LabelDto>,
    val rules: List<CounterpartyRuleDto>,
    val transactions: List<TransactionDto>,
    val budgets: List<BudgetEnvelopeDto>,
    val obligations: List<PlannedObligationDto>,
    val lending: List<LendingRecordDto>,
    val savings: List<SavingsGoalDto>
)

/** Outbox payload for group-sharing mutations (member creation / removal). */
@Serializable
data class GroupMemberMutationPayload(
    val accountId: String,
    val memberEmail: String? = null,
    val memberUserId: String? = null,
    val permissionLevel: String? = null
)

/** Persists the refresh cookie in the Keystore-encrypted [SecurePrefs]. */
private class SecurePrefsCookieStorage(private val securePrefs: SecurePrefs) : RefreshCookieStorage {
    override fun load(): String? = securePrefs.getString(SecurePrefs.KEY_REFRESH_COOKIE)
    override fun save(serializedCookie: String?) = securePrefs.putString(SecurePrefs.KEY_REFRESH_COOKIE, serializedCookie)
}

/**
 * Central Offline-First Repository coordinating:
 * - Encrypted SQLCipher persistence (`KakeiDatabase`)
 * - Bidirectional REST synchronization with `KakeiApiClient` (including HTTP 207 Multi-Status Outbox)
 * - Automatic SMS & Push notification ingestion (`BankSmsPatterns` + 5-min deduplication + Smart Rules)
 * - Real-time recalculation of Net Worth, Budgets, Cash Flow Forecast, Health Score, Streaks, and Heatmaps.
 */
class KakeiRepository(
    private val appContext: Context,
    val database: KakeiDatabase,
    val apiClient: KakeiApiClient = KakeiApiClient(
        refreshCookieStorage = SecurePrefsCookieStorage(SecurePrefs(appContext))
    )
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = appContext.getSharedPreferences("kakei_app_prefs", Context.MODE_PRIVATE)
    private val securePrefs = SecurePrefs(appContext)
    private val dedupHelper = NotificationDeduplicationHelper()
    private val syncMutex = Mutex()

    private val _settingsState = MutableStateFlow(loadInitialSettings())
    val settingsState: StateFlow<AppSettingsState> = _settingsState.asStateFlow()

    private val _accounts = MutableStateFlow<List<AccountDto>>(emptyList())
    val accounts: StateFlow<List<AccountDto>> = _accounts.asStateFlow()

    private val _categories = MutableStateFlow<List<CategoryDto>>(emptyList())
    val categories: StateFlow<List<CategoryDto>> = _categories.asStateFlow()

    private val _labels = MutableStateFlow<List<LabelDto>>(emptyList())
    val labels: StateFlow<List<LabelDto>> = _labels.asStateFlow()

    private val _rules = MutableStateFlow<List<CounterpartyRuleDto>>(emptyList())
    val rules: StateFlow<List<CounterpartyRuleDto>> = _rules.asStateFlow()

    private val _transactions = MutableStateFlow<List<TransactionDto>>(emptyList())
    val transactions: StateFlow<List<TransactionDto>> = _transactions.asStateFlow()

    private val _budgets = MutableStateFlow<List<BudgetEnvelopeDto>>(emptyList())
    val budgets: StateFlow<List<BudgetEnvelopeDto>> = _budgets.asStateFlow()

    private val _obligations = MutableStateFlow<List<PlannedObligationDto>>(emptyList())
    val obligations: StateFlow<List<PlannedObligationDto>> = _obligations.asStateFlow()

    private val _forecast = MutableStateFlow(
        CashFlowForecastDto(
            baseCurrency = "KZT",
            currentLiquidBalance = BigDecimal.ZERO,
            plannedIncomeTotal = BigDecimal.ZERO,
            plannedExpenseTotal = BigDecimal.ZERO,
            forecastedEndBalance = BigDecimal.ZERO
        )
    )
    val forecast: StateFlow<CashFlowForecastDto> = _forecast.asStateFlow()

    private val _lendingResponse = MutableStateFlow(LendingListResponse())
    val lendingResponse: StateFlow<LendingListResponse> = _lendingResponse.asStateFlow()

    private val _savingsGoals = MutableStateFlow<List<SavingsGoalDto>>(emptyList())
    val savingsGoals: StateFlow<List<SavingsGoalDto>> = _savingsGoals.asStateFlow()

    private val _groupMembers = MutableStateFlow<List<GroupMemberDto>>(emptyList())
    val groupMembers: StateFlow<List<GroupMemberDto>> = _groupMembers.asStateFlow()

    private val _passkeys = MutableStateFlow<List<UserPasskeyItem>>(emptyList())
    val passkeys: StateFlow<List<UserPasskeyItem>> = _passkeys.asStateFlow()

    private val _pendingMutations = MutableStateFlow<List<SyncMutationEntity>>(emptyList())
    val pendingMutations: StateFlow<List<SyncMutationEntity>> = _pendingMutations.asStateFlow()

    private val _syncErrors = MutableStateFlow<List<SyncErrorEntity>>(emptyList())
    /** Mutations the server rejected (per-item 4xx in HTTP 207 responses). */
    val syncErrors: StateFlow<List<SyncErrorEntity>> = _syncErrors.asStateFlow()

    private val _healthScore = MutableStateFlow(
        FinancialHealthScoreDto(
            overallScore = 85,
            grade = "A",
            savingsRatePercentage = BigDecimal("22.0"),
            savingsRateScore = 88,
            budgetCompliancePercentage = BigDecimal("100.0"),
            budgetComplianceScore = 100,
            debtToIncomePercentage = BigDecimal("0.0"),
            debtToIncomeScore = 100,
            periodIncome = BigDecimal.ZERO,
            periodExpense = BigDecimal.ZERO,
            totalOutstandingDebt = BigDecimal.ZERO,
            currency = "KZT"
        )
    )
    val healthScore: StateFlow<FinancialHealthScoreDto> = _healthScore.asStateFlow()

    private val _streaks = MutableStateFlow(
        NoSpendStreakDto(currentStreakDays = 0, longestStreakDays = 0, totalNoSpendDaysLast30 = 0)
    )
    val streaks: StateFlow<NoSpendStreakDto> = _streaks.asStateFlow()

    private val _heatmaps = MutableStateFlow(
        SpendingHeatmapsDto(year = LocalDate.now().year, month = LocalDate.now().monthValue, currency = "KZT")
    )
    val heatmaps: StateFlow<SpendingHeatmapsDto> = _heatmaps.asStateFlow()

    private val _insights = MutableStateFlow(
        InsightsSummaryDto(
            currency = "KZT",
            currentMonthSpent = BigDecimal.ZERO,
            previousMonthSpent = BigDecimal.ZERO,
            monthOverMonthDeltaPercentage = BigDecimal.ZERO,
            monthEndProjectedSpend = BigDecimal.ZERO,
            smallSpendsTotal = BigDecimal.ZERO,
            smallSpendsCount = 0,
            weekdaySpendTotal = BigDecimal.ZERO,
            weekendSpendTotal = BigDecimal.ZERO
        )
    )
    val insights: StateFlow<InsightsSummaryDto> = _insights.asStateFlow()

    private val _exchangeRates = MutableStateFlow(ExchangeRatesResponse())
    /** Last `GET /profile/exchange-rates` answer (market rates + the user's overrides). */
    val exchangeRates: StateFlow<ExchangeRatesResponse> = _exchangeRates.asStateFlow()

    private val _rateBook = MutableStateFlow(ExchangeRateBook.EMPTY)
    /** Effective rates used for every conversion; never contains invented rates. */
    val rateBook: StateFlow<ExchangeRateBook> = _rateBook.asStateFlow()

    private val _pendingCaptures = MutableStateFlow<List<PendingCaptureEntity>>(emptyList())
    /** Parsed bank alerts waiting for the user to choose an account. */
    val pendingCaptures: StateFlow<List<PendingCaptureEntity>> = _pendingCaptures.asStateFlow()

    private val _instantCapturedRecord = MutableStateFlow<TransactionDto?>(null)
    val instantCapturedRecord: StateFlow<TransactionDto?> = _instantCapturedRecord.asStateFlow()

    init {
        apiClient.updateBaseUrl(_settingsState.value.serverBaseUrl)
        apiClient.accessToken = loadAccessToken()
        // A persisted refresh cookie alone is enough to keep the session alive.
        if (!_settingsState.value.isAuthenticated && apiClient.hasRefreshToken()) {
            _settingsState.value = _settingsState.value.copy(isAuthenticated = true)
        }
        apiClient.onAccessTokenRefreshed = { token ->
            securePrefs.putString(SecurePrefs.KEY_ACCESS_TOKEN, token)
        }
        apiClient.onSessionExpired = { handleSessionExpired() }
        // Demo data is never created automatically (see loadDemoDataForDevelopment);
        // installs that received it from older builds get the untouched rows removed.
        removeUntouchedLegacyDemoSeed()
        reloadFromLocalDatabase()
        reportDatabaseOpenState()
        if (_settingsState.value.isAuthenticated) {
            scheduleSync()
        }
    }

    /** Reads the access token from encrypted storage, migrating the legacy plaintext entry once. */
    private fun loadAccessToken(): String? {
        securePrefs.getString(SecurePrefs.KEY_ACCESS_TOKEN)?.let { return it }
        val legacy = prefs.getString("jwt_token", null)
        if (!legacy.isNullOrBlank()) {
            securePrefs.putString(SecurePrefs.KEY_ACCESS_TOKEN, legacy)
            prefs.edit().remove("jwt_token").apply()
        }
        return legacy
    }

    private fun handleSessionExpired() {
        securePrefs.remove(SecurePrefs.KEY_ACCESS_TOKEN)
        securePrefs.remove(SecurePrefs.KEY_REFRESH_COOKIE)
        _settingsState.value = _settingsState.value.copy(
            isAuthenticated = false,
            syncInProgress = false,
            statusMessage = "Session expired. Please sign in again; pending changes are kept in the outbox."
        )
    }

    private fun scheduleSync() {
        if (_settingsState.value.isAuthenticated) {
            scope.launch { synchronizeWithServer() }
        }
    }

    private fun loadInitialSettings(): AppSettingsState {
        val token = loadAccessToken()
        val offline = prefs.getBoolean("offline_mode", false)
        val serverUrl = prefs.getString("server_url", "http://10.0.2.2:5144") ?: "http://10.0.2.2:5144"
        val lang = AppLanguage.fromCode(prefs.getString("lang_code", "ru"))
        val theme = runCatching {
            ThemeMode.valueOf(prefs.getString("theme_mode", "DARK") ?: "DARK")
        }.getOrDefault(ThemeMode.DARK)
        val defaultCurrency = prefs.getString("default_currency", "KZT") ?: "KZT"
        val userName = prefs.getString("user_name", "Kakei User") ?: "Kakei User"
        val userEmail = prefs.getString("user_email", "user@kakei.app") ?: "user@kakei.app"

        return AppSettingsState(
            isAuthenticated = !token.isNullOrBlank() || apiClient.hasRefreshToken(),
            isOfflineAutonomousMode = offline,
            serverBaseUrl = serverUrl,
            language = lang,
            themeMode = theme,
            biometricLockEnabled = prefs.getBoolean("biometric_lock", false),
            smsCaptureEnabled = prefs.getBoolean("sms_capture", true),
            pushCaptureEnabled = prefs.getBoolean("push_capture", true),
            instantPopupEnabled = prefs.getBoolean("instant_popup", true),
            captureDefaultAccountId = prefs.getString("capture_default_account", null),
            captureSenderAccounts = runCatching {
                database.json.decodeFromString<Map<String, String>>(prefs.getString("capture_sender_accounts", null) ?: "{}")
            }.getOrDefault(emptyMap()),
            lastSyncTimestamp = prefs.getString("last_sync", null),
            profile = ProfileInfoResponse(
                userId = prefs.getString("user_id", "local-user") ?: "local-user",
                email = userEmail,
                name = userName,
                defaultCurrency = defaultCurrency,
                language = lang.code,
                twoFactorEnabled = prefs.getBoolean("two_factor", false),
                emailConfirmed = true
            )
        )
    }

    fun clearStatusMessage() {
        _settingsState.value = _settingsState.value.copy(statusMessage = null)
    }

    private fun postMessage(msg: String) {
        _settingsState.value = _settingsState.value.copy(statusMessage = msg)
    }

    fun setLanguage(language: AppLanguage) {
        prefs.edit().putString("lang_code", language.code).apply()
        _settingsState.value = _settingsState.value.copy(
            language = language,
            profile = _settingsState.value.profile.copy(language = language.code)
        )
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString("theme_mode", mode.name).apply()
        _settingsState.value = _settingsState.value.copy(themeMode = mode)
    }

    fun setServerBaseUrl(url: String) {
        val clean = url.trim().ifBlank { "http://10.0.2.2:5144" }
        prefs.edit().putString("server_url", clean).apply()
        apiClient.updateBaseUrl(clean)
        _settingsState.value = _settingsState.value.copy(serverBaseUrl = clean)
        scope.launch {
            apiClient.getVersion().onSuccess { ver ->
                _settingsState.value = _settingsState.value.copy(
                    serverVersion = "${ver.version} (${if (ver.debug) "debug" else "release"})",
                    statusMessage = "Connected to Kakei Server v${ver.version}"
                )
            }.onFailure { err ->
                postMessage("Server check failed: ${err.message}")
            }
        }
    }

    fun updateAutomationToggles(
        smsEnabled: Boolean? = null,
        pushEnabled: Boolean? = null,
        instantPopup: Boolean? = null,
        biometricEnabled: Boolean? = null
    ) {
        val current = _settingsState.value
        val nextSms = smsEnabled ?: current.smsCaptureEnabled
        val nextPush = pushEnabled ?: current.pushCaptureEnabled
        val nextPopup = instantPopup ?: current.instantPopupEnabled
        val nextBio = biometricEnabled ?: current.biometricLockEnabled
        prefs.edit()
            .putBoolean("sms_capture", nextSms)
            .putBoolean("push_capture", nextPush)
            .putBoolean("instant_popup", nextPopup)
            .putBoolean("biometric_lock", nextBio)
            .apply()
        _settingsState.value = current.copy(
            smsCaptureEnabled = nextSms,
            pushCaptureEnabled = nextPush,
            instantPopupEnabled = nextPopup,
            biometricLockEnabled = nextBio
        )
    }

    // ========================================================================
    // Authentication & Autonomous Offline Mode
    // ========================================================================

    fun enterOfflineAutonomousMode() {
        prefs.edit().putBoolean("offline_mode", true).apply()
        reloadFromLocalDatabase()
        _settingsState.value = _settingsState.value.copy(
            isOfflineAutonomousMode = true,
            statusMessage = "Offline-First SQLCipher mode active"
        )
    }

    fun signIn(email: String, password: String) {
        scope.launch {
            _settingsState.value = _settingsState.value.copy(syncInProgress = true)
            apiClient.login(LoginRequest(email.trim(), password)).onSuccess { resp ->
                if (resp.need2fa) {
                    _settingsState.value = _settingsState.value.copy(
                        syncInProgress = false,
                        pending2faUserId = resp.userId,
                        pending2faToken = resp.token,
                        statusMessage = "Enter 2FA code"
                    )
                } else {
                    completeLoginSession(resp.userId, email.trim(), resp.token)
                }
            }.onFailure { err ->
                _settingsState.value = _settingsState.value.copy(
                    syncInProgress = false,
                    statusMessage = "Sign-in error: ${err.message}"
                )
            }
        }
    }

    fun verify2fa(code: String, isRecovery: Boolean = false) {
        val userId = _settingsState.value.pending2faUserId ?: return
        val tempToken = _settingsState.value.pending2faToken ?: return
        scope.launch {
            _settingsState.value = _settingsState.value.copy(syncInProgress = true)
            apiClient.login2fa(
                Login2faRequest(
                    userId = userId,
                    token = tempToken,
                    code = code.trim(),
                    type = if (isRecovery) "recovery" else "code"
                )
            ).onSuccess { resp ->
                completeLoginSession(resp.userId, _settingsState.value.profile.email, resp.token)
            }.onFailure { err ->
                _settingsState.value = _settingsState.value.copy(
                    syncInProgress = false,
                    statusMessage = "2FA verification error: ${err.message}"
                )
            }
        }
    }

    fun registerAccount(name: String, email: String, password: String, defaultCurrency: String) {
        scope.launch {
            _settingsState.value = _settingsState.value.copy(syncInProgress = true)
            apiClient.register(
                RegisterRequest(
                    email = email.trim(),
                    password = password,
                    name = name.trim(),
                    defaultCurrency = defaultCurrency
                )
            ).onSuccess { resp ->
                prefs.edit()
                    .putString("user_name", name.trim())
                    .putString("user_email", email.trim())
                    .putString("default_currency", defaultCurrency)
                    .apply()
                _settingsState.value = _settingsState.value.copy(
                    syncInProgress = false,
                    pendingEmailConfirmUserId = resp.userId,
                    statusMessage = "Verification code sent to $email"
                )
            }.onFailure { err ->
                _settingsState.value = _settingsState.value.copy(
                    syncInProgress = false,
                    statusMessage = "Registration error: ${err.message}"
                )
            }
        }
    }

    fun confirmRegistrationEmail(code: String) {
        val userId = _settingsState.value.pendingEmailConfirmUserId ?: return
        scope.launch {
            apiClient.confirmEmail(EmailConfirmRequest(userId = userId, code = code.trim()))
                .onSuccess { resp ->
                    completeLoginSession(userId, _settingsState.value.profile.email, resp.token)
                }.onFailure { err ->
                    postMessage("Email confirmation failed: ${err.message}")
                }
        }
    }

    private suspend fun completeLoginSession(userId: String, email: String, token: String) {
        securePrefs.putString(SecurePrefs.KEY_ACCESS_TOKEN, token)
        prefs.edit()
            .remove("jwt_token")
            .putString("user_id", userId)
            .putString("user_email", email)
            .putBoolean("offline_mode", false)
            .apply()
        apiClient.accessToken = token
        _settingsState.value = _settingsState.value.copy(
            isAuthenticated = true,
            isOfflineAutonomousMode = false,
            pending2faUserId = null,
            pending2faToken = null,
            pendingEmailConfirmUserId = null,
            syncInProgress = false
        )
        synchronizeWithServer()
    }

    fun logout() {
        scope.launch {
            runCatching { apiClient.revokeToken() }
            apiClient.clearSession()
            securePrefs.remove(SecurePrefs.KEY_ACCESS_TOKEN)
            securePrefs.remove(SecurePrefs.KEY_REFRESH_COOKIE)
            prefs.edit()
                .remove("jwt_token")
                .putBoolean("offline_mode", false)
                .apply()
            _settingsState.value = _settingsState.value.copy(
                isAuthenticated = false,
                isOfflineAutonomousMode = false,
                statusMessage = "Signed out"
            )
        }
    }

    fun updateProfile(name: String, defaultCurrency: String, language: AppLanguage) {
        prefs.edit()
            .putString("user_name", name.trim())
            .putString("default_currency", defaultCurrency)
            .putString("lang_code", language.code)
            .apply()
        val updatedProfile = _settingsState.value.profile.copy(
            name = name.trim(),
            defaultCurrency = defaultCurrency,
            language = language.code
        )
        _settingsState.value = _settingsState.value.copy(
            profile = updatedProfile,
            language = language,
            statusMessage = "Profile updated"
        )
        recomputeDerivedAnalytics()
        enqueueMutation(
            entityType = "PROFILE",
            entityId = updatedProfile.userId,
            operation = "UPDATE",
            payloadJson = database.json.encodeToString(
                ProfileInfoUpdateRequest(
                    name = name.trim(),
                    defaultCurrency = defaultCurrency,
                    language = language.code
                )
            )
        )
    }

    // ========================================================================
    // Bidirectional Synchronization with Kakei Server (HTTP 207 Multi-Status)
    // ========================================================================

    private enum class Outcome { APPLIED, REJECTED, RETRY, UNAUTHORIZED }

    private data class MutationResult(
        val outcome: Outcome,
        val status: Int = 0,
        val error: String? = null
    )

    private fun applied() = MutationResult(Outcome.APPLIED)

    private fun rejected(status: Int, error: String?) =
        MutationResult(Outcome.REJECTED, status, error ?: "HTTP $status")

    private fun failure(t: Throwable): MutationResult = when {
        t is ApiException && t.statusCode == 401 -> MutationResult(Outcome.UNAUTHORIZED, 401, t.message)
        t is ApiException && t.statusCode in 400..499 -> rejected(t.statusCode, t.message)
        else -> MutationResult(Outcome.RETRY, (t as? ApiException)?.statusCode ?: 0, t.message)
    }

    private inline fun <reified T> putDto(collection: String, id: String, sortKey: String, dto: T) {
        database.putItem(collection, id, sortKey, database.json.encodeToString(dto))
    }

    /**
     * Queues a mutation in the Outbox. Every local write goes through here so that an
     * offline change survives until the server has acknowledged it.
     */
    private fun enqueueMutation(
        entityType: String,
        entityId: String,
        operation: String,
        payloadJson: String,
        expectedSyncRevision: Long? = null
    ) {
        val mut = SyncMutationEntity(
            id = UUID.randomUUID().toString(),
            entityType = entityType,
            entityId = entityId,
            operation = operation,
            payloadJson = payloadJson,
            expectedSyncRevision = expectedSyncRevision,
            createdAt = Instant.now().toString()
        )
        // Sort key = enqueue time so the drain is strictly FIFO.
        putDto(KakeiDatabase.COL_MUTATIONS, mut.id, mut.createdAt, mut)
        _pendingMutations.value = database.listItems(KakeiDatabase.COL_MUTATIONS, descending = false)
        scheduleSync()
    }

    /** Local id of a client-created entity has been replaced by the server-assigned one. */
    private inline fun <reified T> adoptServerEntity(
        collection: String,
        localId: String,
        serverId: String,
        sortKey: String,
        dto: T
    ) {
        database.replaceIdReferences(localId, serverId)
        putDto(collection, serverId, sortKey, dto)
    }

    private fun recordSyncError(mut: SyncMutationEntity, result: MutationResult) {
        val err = SyncErrorEntity(
            id = UUID.randomUUID().toString(),
            entityType = mut.entityType,
            operation = mut.operation,
            entityId = mut.entityId,
            status = result.status,
            error = result.error ?: "Rejected by server",
            createdAt = Instant.now().toString()
        )
        putDto(KakeiDatabase.COL_SYNC_ERRORS, err.id, err.createdAt, err)
    }

    fun dismissSyncErrors() {
        database.clearCollection(KakeiDatabase.COL_SYNC_ERRORS)
        _syncErrors.value = emptyList()
    }

    /** Sends one Outbox entry and maps the server answer (incl. HTTP 207 item statuses). */
    private suspend fun processMutation(mut: SyncMutationEntity): MutationResult {
        val j = database.json
        return when (mut.entityType to mut.operation) {
            // ---- Ledger records: batch endpoints with per-item status ----
            "RECORD" to "CREATE" -> {
                val item = j.decodeFromString<CreateRecordItem>(mut.payloadJson).copy(clientId = mut.entityId)
                apiClient.batchCreateRecords(BatchCreateRecordsRequest(listOf(item))).fold(
                    onSuccess = { resp ->
                        val r = resp.results.firstOrNull() ?: return rejected(0, "Empty batch response")
                        when (r.status) {
                            200, 201 -> {
                                r.record?.let { putDto(KakeiDatabase.COL_TRANSACTIONS, it.id, it.bookingDate, it) }
                                applied()
                            }
                            // Equivalent record already exists on the server (dedup hash).
                            409 -> if (r.duplicateHash != null) applied() else rejected(r.status, r.error)
                            // The account does not exist on the server (e.g. a local-only demo
                            // account): keep the transaction for the user to re-file instead of
                            // letting the next pull silently drop it.
                            404 -> {
                                parkRejectedRecordCreate(item, r.error)
                                rejected(r.status, r.error)
                            }
                            422 -> rejected(r.status, describeRecordError(r.error))
                            else -> rejected(r.status, r.error)
                        }
                    },
                    onFailure = { failure(it) }
                )
            }
            "RECORD" to "UPDATE" -> {
                val item = j.decodeFromString<UpdateRecordItem>(mut.payloadJson)
                apiClient.batchUpdateRecords(BatchUpdateRecordsRequest(listOf(item))).fold(
                    onSuccess = { resp ->
                        val r = resp.results.firstOrNull() ?: return rejected(0, "Empty batch response")
                        when (r.status) {
                            200 -> {
                                r.record?.let { putDto(KakeiDatabase.COL_TRANSACTIONS, it.id, it.bookingDate, it) }
                                applied()
                            }
                            // Revision conflict: the server copy wins and is pulled right after.
                            409 -> rejected(r.status, r.error ?: "Sync revision conflict")
                            422 -> rejected(r.status, describeRecordError(r.error))
                            else -> rejected(r.status, r.error)
                        }
                    },
                    onFailure = { failure(it) }
                )
            }
            "RECORD" to "DELETE" -> {
                apiClient.batchDeleteRecords(BatchDeleteRecordsRequest(listOf(mut.entityId))).fold(
                    onSuccess = { resp ->
                        val r = resp.results.firstOrNull() ?: return rejected(0, "Empty batch response")
                        when (r.status) {
                            200, 404 -> applied() // already gone is fine
                            else -> rejected(r.status, r.error)
                        }
                    },
                    onFailure = { failure(it) }
                )
            }
            "RECORD" to "REVIEW" -> {
                val req = j.decodeFromString<ConfirmReviewRequest>(mut.payloadJson)
                apiClient.confirmRecordReview(mut.entityId, req).fold(
                    onSuccess = { tx -> putDto(KakeiDatabase.COL_TRANSACTIONS, tx.id, tx.bookingDate, tx); applied() },
                    onFailure = { failure(it) }
                )
            }

            // ---- Accounts ----
            "ACCOUNT" to "CREATE" -> {
                val req = j.decodeFromString<CreateAccountRequest>(mut.payloadJson)
                apiClient.createAccount(req).fold(
                    onSuccess = { acc -> adoptServerEntity(KakeiDatabase.COL_ACCOUNTS, mut.entityId, acc.id, acc.name, acc); applied() },
                    onFailure = { failure(it) }
                )
            }
            "ACCOUNT" to "UPDATE" -> {
                val item = j.decodeFromString<AccountMutationItem>(mut.payloadJson)
                apiClient.batchUpdateAccounts(BatchUpdateAccountsRequest(listOf(item))).fold(
                    onSuccess = { resp ->
                        val r = resp.results.firstOrNull() ?: return rejected(0, "Empty batch response")
                        when (r.status) {
                            200 -> {
                                r.account?.let { putDto(KakeiDatabase.COL_ACCOUNTS, it.id, it.name, it) }
                                applied()
                            }
                            409 -> rejected(r.status, r.error ?: "Sync revision conflict")
                            else -> rejected(r.status, r.error)
                        }
                    },
                    onFailure = { failure(it) }
                )
            }
            "ACCOUNT" to "DELETE" -> simpleDelete { apiClient.deleteAccountById(mut.entityId) }

            // ---- Categories, labels, rules ----
            "CATEGORY" to "CREATE" -> {
                val req = j.decodeFromString<UpsertCategoryRequest>(mut.payloadJson)
                apiClient.createCategory(req).fold(
                    onSuccess = { c -> adoptServerEntity(KakeiDatabase.COL_CATEGORIES, mut.entityId, c.id, c.name, c); applied() },
                    onFailure = { failure(it) }
                )
            }
            "CATEGORY" to "DELETE" -> simpleDelete { apiClient.deleteCategory(mut.entityId) }
            "LABEL" to "CREATE" -> {
                val req = j.decodeFromString<CreateLabelRequest>(mut.payloadJson)
                apiClient.createLabel(req).fold(
                    onSuccess = { l -> adoptServerEntity(KakeiDatabase.COL_LABELS, mut.entityId, l.id, l.name, l); applied() },
                    onFailure = { failure(it) }
                )
            }
            "LABEL" to "DELETE" -> simpleDelete { apiClient.deleteLabel(mut.entityId) }
            "RULE" to "CREATE" -> {
                val req = j.decodeFromString<CreateRuleRequest>(mut.payloadJson)
                apiClient.createRule(req).fold(
                    onSuccess = { r -> adoptServerEntity(KakeiDatabase.COL_RULES, mut.entityId, r.id, r.counterpartyPattern, r); applied() },
                    onFailure = { failure(it) }
                )
            }
            "RULE" to "DELETE" -> simpleDelete { apiClient.deleteRule(mut.entityId) }

            // ---- Budgets ----
            "BUDGET" to "CREATE" -> {
                val req = j.decodeFromString<UpsertBudgetRequest>(mut.payloadJson)
                apiClient.createBudget(req).fold(
                    onSuccess = { b -> adoptServerEntity(KakeiDatabase.COL_BUDGETS, mut.entityId, b.id, b.name, b); applied() },
                    onFailure = { failure(it) }
                )
            }
            "BUDGET" to "DELETE" -> simpleDelete { apiClient.deleteBudget(mut.entityId) }

            // ---- Planning ----
            "OBLIGATION" to "CREATE" -> {
                val req = j.decodeFromString<UpsertObligationRequest>(mut.payloadJson)
                apiClient.createObligation(req).fold(
                    onSuccess = { o -> adoptServerEntity(KakeiDatabase.COL_OBLIGATIONS, mut.entityId, o.id, o.nextDueDate, o); applied() },
                    onFailure = { failure(it) }
                )
            }
            "OBLIGATION" to "EXECUTE" -> {
                apiClient.executeObligation(mut.entityId).fold(
                    onSuccess = { resp ->
                        putDto(KakeiDatabase.COL_OBLIGATIONS, resp.obligation.id, resp.obligation.nextDueDate, resp.obligation)
                        putDto(KakeiDatabase.COL_TRANSACTIONS, resp.postedRecord.id, resp.postedRecord.bookingDate, resp.postedRecord)
                        applied()
                    },
                    onFailure = { failure(it) }
                )
            }
            "OBLIGATION" to "DELETE" -> simpleDelete { apiClient.deleteObligation(mut.entityId) }

            // ---- Lending ----
            "LENDING" to "CREATE" -> {
                val req = j.decodeFromString<CreateLendingRequest>(mut.payloadJson)
                apiClient.createLending(req).fold(
                    onSuccess = { l -> adoptServerEntity(KakeiDatabase.COL_LENDING, mut.entityId, l.id, l.createdAt, l); applied() },
                    onFailure = { failure(it) }
                )
            }
            "LENDING" to "REPAYMENT" -> {
                val req = j.decodeFromString<CreateRepaymentRequest>(mut.payloadJson)
                apiClient.createRepayment(mut.entityId, req).fold(
                    onSuccess = { l -> putDto(KakeiDatabase.COL_LENDING, l.id, l.createdAt, l); applied() },
                    onFailure = { failure(it) }
                )
            }
            "LENDING" to "FORGIVE" -> {
                apiClient.forgiveLending(mut.entityId).fold(
                    onSuccess = { l -> putDto(KakeiDatabase.COL_LENDING, l.id, l.createdAt, l); applied() },
                    onFailure = { failure(it) }
                )
            }
            "LENDING" to "SETTLE_ALL" -> {
                val req = j.decodeFromString<SettleAllRequest>(mut.payloadJson)
                apiClient.settleAll(req).fold(onSuccess = { applied() }, onFailure = { failure(it) })
            }

            // ---- Savings ----
            "SAVINGS" to "CREATE" -> {
                val req = j.decodeFromString<UpsertSavingsGoalRequest>(mut.payloadJson)
                apiClient.createSavingsGoal(req).fold(
                    onSuccess = { g -> adoptServerEntity(KakeiDatabase.COL_SAVINGS, mut.entityId, g.id, g.title, g); applied() },
                    onFailure = { failure(it) }
                )
            }
            "SAVINGS" to "CONTRIBUTION" -> {
                val req = j.decodeFromString<AddContributionRequest>(mut.payloadJson)
                apiClient.addSavingsContribution(mut.entityId, req).fold(
                    onSuccess = { g -> putDto(KakeiDatabase.COL_SAVINGS, g.id, g.title, g); applied() },
                    onFailure = { failure(it) }
                )
            }
            "SAVINGS" to "DELETE" -> simpleDelete { apiClient.deleteSavingsGoal(mut.entityId) }

            // ---- Group sharing ----
            "GROUP_MEMBER" to "CREATE" -> {
                val p = j.decodeFromString<GroupMemberMutationPayload>(mut.payloadJson)
                apiClient.upsertGroupMember(
                    p.accountId,
                    UpsertGroupMemberRequest(memberEmail = p.memberEmail, permissionLevel = p.permissionLevel ?: "READ_ONLY")
                ).fold(
                    onSuccess = { m -> adoptServerEntity(KakeiDatabase.COL_GROUP_MEMBERS, mut.entityId, m.id, m.createdAt, m); applied() },
                    onFailure = { failure(it) }
                )
            }
            "GROUP_MEMBER" to "DELETE" -> {
                val p = j.decodeFromString<GroupMemberMutationPayload>(mut.payloadJson)
                val memberUserId = p.memberUserId ?: return rejected(400, "Member id unknown")
                simpleDelete { apiClient.removeGroupMember(p.accountId, memberUserId) }
            }

            // ---- Profile ----
            "PROFILE" to "UPDATE" -> {
                val req = j.decodeFromString<ProfileInfoUpdateRequest>(mut.payloadJson)
                apiClient.updateProfileInfo(req).fold(onSuccess = { applied() }, onFailure = { failure(it) })
            }

            else -> rejected(0, "Unknown mutation ${mut.entityType}/${mut.operation}")
        }
    }

    private fun describeRecordError(error: String?): String? =
        if (error?.contains(FX_RATE_UNAVAILABLE) == true) {
            "No exchange rate known for this transfer; enter the received amount or set a rate and add it again"
        } else error

    /** Moves a record the server refused (unknown account) into the pending-capture review list. */
    private fun parkRejectedRecordCreate(item: CreateRecordItem, error: String?) {
        if (item.type == "TRANSFER") return // a transfer cannot be re-filed without its target; the sync error stays visible
        val currency = database.getItem<AccountDto>(KakeiDatabase.COL_ACCOUNTS, item.accountId)?.currency
            ?: _settingsState.value.profile.defaultCurrency
        val now = Instant.now().toString()
        val capture = PendingCaptureEntity(
            id = UUID.randomUUID().toString(),
            sourceChannel = "SYNC",
            rawText = listOfNotNull(item.payee, item.note, error).joinToString(" · "),
            operationType = item.type,
            amount = item.amount,
            currency = currency,
            merchant = item.payee,
            bookingDate = item.bookingDate ?: now,
            reason = "SERVER_REJECTED",
            createdAt = now
        )
        putDto(KakeiDatabase.COL_PENDING_CAPTURES, capture.id, capture.createdAt, capture)
    }

    private suspend fun simpleDelete(call: suspend () -> Result<Unit>): MutationResult =
        call().fold(
            onSuccess = { applied() },
            onFailure = { t ->
                if (t is ApiException && t.statusCode == 404) applied() else failure(t)
            }
        )

    /**
     * Drains the Outbox in FIFO order. Returns false when the drain must stop early
     * (network failure keeps ordering intact for the remaining entries; 401 ends the session).
     */
    private suspend fun drainOutbox(): Pair<Boolean, Int> {
        var rejectedCount = 0
        val queued = database.listItems<SyncMutationEntity>(KakeiDatabase.COL_MUTATIONS, descending = false)
        for (mut in queued) {
            val result = runCatching { processMutation(mut) }.getOrElse { failure(it) }
            when (result.outcome) {
                Outcome.APPLIED -> database.deleteItem(KakeiDatabase.COL_MUTATIONS, mut.id)
                Outcome.REJECTED -> {
                    rejectedCount++
                    recordSyncError(mut, result)
                    database.deleteItem(KakeiDatabase.COL_MUTATIONS, mut.id)
                }
                Outcome.RETRY -> {
                    val updated = mut.copy(attempts = mut.attempts + 1, lastError = result.error)
                    putDto(KakeiDatabase.COL_MUTATIONS, updated.id, updated.createdAt, updated)
                    return false to rejectedCount
                }
                Outcome.UNAUTHORIZED -> return false to rejectedCount
            }
        }
        return true to rejectedCount
    }

    suspend fun synchronizeWithServer() {
        if (!_settingsState.value.isAuthenticated) {
            recomputeDerivedAnalytics()
            return
        }
        if (!syncMutex.tryLock()) return // a sync is already running
        try {
            _settingsState.value = _settingsState.value.copy(syncInProgress = true)

            // 1. Flush Outbox mutations (per-item HTTP 207 statuses decide the fate of each entry)
            val (drained, rejectedCount) = drainOutbox()
            if (!_settingsState.value.isAuthenticated) return // session expired during the drain

            // Entities that still have queued mutations are local-only and must survive the pull.
            val remaining = database.listItems<SyncMutationEntity>(KakeiDatabase.COL_MUTATIONS, descending = false)
            val protectedIds = remaining.map { it.entityId }.toSet()
            var pullFailures = 0

            // 2. Pull fresh state and merge it over the local replica
            apiClient.getVersion().onSuccess { ver ->
                _settingsState.value = _settingsState.value.copy(
                    serverVersion = "${ver.version} (${if (ver.debug) "debug" else "release"})"
                )
            }

            apiClient.getProfileInfo().onSuccess { prof ->
                _settingsState.value = _settingsState.value.copy(
                    profile = prof,
                    language = AppLanguage.fromCode(prof.language)
                )
            }.onFailure { pullFailures++ }

            val j = database.json
            apiClient.listAccounts().onSuccess { resp ->
                database.mergeCollection(
                    KakeiDatabase.COL_ACCOUNTS,
                    resp.items.map { Triple(it.id, it.name, j.encodeToString(it)) },
                    protectedIds
                )
            }.onFailure { pullFailures++ }

            apiClient.listCategories().onSuccess { resp ->
                if (resp.items.isNotEmpty()) {
                    database.mergeCollection(
                        KakeiDatabase.COL_CATEGORIES,
                        resp.items.map { Triple(it.id, it.name, j.encodeToString(it)) },
                        protectedIds
                    )
                }
            }.onFailure { pullFailures++ }

            apiClient.listLabels().onSuccess { resp ->
                database.mergeCollection(
                    KakeiDatabase.COL_LABELS,
                    resp.items.map { Triple(it.id, it.name, j.encodeToString(it)) },
                    protectedIds
                )
            }.onFailure { pullFailures++ }

            apiClient.listRules().onSuccess { resp ->
                database.mergeCollection(
                    KakeiDatabase.COL_RULES,
                    resp.items.map { Triple(it.id, it.counterpartyPattern, j.encodeToString(it)) },
                    protectedIds
                )
            }.onFailure { pullFailures++ }

            // Records: walk every page so the merge sees the complete server ledger.
            val allRecords = mutableListOf<TransactionDto>()
            var offset = 0
            var recordsOk = true
            while (true) {
                val page = apiClient.listRecords(offset = offset, limit = RECORDS_PAGE_SIZE).getOrElse {
                    recordsOk = false
                    null
                } ?: break
                allRecords += page.items
                if (!page.more || page.items.isEmpty() || allRecords.size >= MAX_PULLED_RECORDS) break
                offset += page.items.size
            }
            if (recordsOk) {
                database.mergeCollection(
                    KakeiDatabase.COL_TRANSACTIONS,
                    allRecords.map { Triple(it.id, it.bookingDate, j.encodeToString(it)) },
                    protectedIds
                )
            } else {
                pullFailures++
            }

            apiClient.listBudgets().onSuccess { resp ->
                database.mergeCollection(
                    KakeiDatabase.COL_BUDGETS,
                    resp.items.map { Triple(it.id, it.name, j.encodeToString(it)) },
                    protectedIds
                )
            }.onFailure { pullFailures++ }

            apiClient.listObligations().onSuccess { resp ->
                database.mergeCollection(
                    KakeiDatabase.COL_OBLIGATIONS,
                    resp.items.map { Triple(it.id, it.nextDueDate, j.encodeToString(it)) },
                    protectedIds
                )
            }.onFailure { pullFailures++ }

            apiClient.listLending().onSuccess { resp ->
                database.mergeCollection(
                    KakeiDatabase.COL_LENDING,
                    resp.items.map { Triple(it.id, it.createdAt, j.encodeToString(it)) },
                    protectedIds
                )
            }.onFailure { pullFailures++ }

            apiClient.listSavingsGoals().onSuccess { resp ->
                database.mergeCollection(
                    KakeiDatabase.COL_SAVINGS,
                    resp.items.map { Triple(it.id, it.title, j.encodeToString(it)) },
                    protectedIds
                )
            }.onFailure { pullFailures++ }

            // Group members: shares granted to me plus members of my own accounts.
            val members = mutableListOf<GroupMemberDto>()
            var membersOk = true
            apiClient.listSharedWithMe().onSuccess { members += it.items }.onFailure { membersOk = false }
            val ownAccounts = database.listItems<AccountDto>(KakeiDatabase.COL_ACCOUNTS, descending = false)
                .filter { it.permissionLevel == "ADMIN" && it.userId == _settingsState.value.profile.userId }
            for (acc in ownAccounts) {
                apiClient.listGroupMembers(acc.id).onSuccess { members += it.items }.onFailure { membersOk = false }
            }
            if (membersOk) {
                database.mergeCollection(
                    KakeiDatabase.COL_GROUP_MEMBERS,
                    members.distinctBy { it.id }.map { Triple(it.id, it.createdAt, j.encodeToString(it)) },
                    protectedIds
                )
            } else {
                pullFailures++
            }

            apiClient.listPasskeys().onSuccess { list ->
                _passkeys.value = list
            }

            apiClient.getExchangeRates().onSuccess { storeExchangeRates(it) }.onFailure { pullFailures++ }

            val nowIso = Instant.now().toString()
            prefs.edit().putString("last_sync", nowIso).apply()
            reloadFromLocalDatabase()

            // Override analytics with server values if available
            apiClient.getHealthScore().onSuccess { _healthScore.value = it }
            apiClient.getStreaks().onSuccess { _streaks.value = it }
            apiClient.getHeatmaps(LocalDate.now().year, LocalDate.now().monthValue).onSuccess { _heatmaps.value = it }
            apiClient.getInsights().onSuccess { _insights.value = it }
            apiClient.getForecast(30).onSuccess { _forecast.value = it }

            if (!_settingsState.value.isAuthenticated) return
            val pendingLeft = database.listIds(KakeiDatabase.COL_MUTATIONS).size
            val summary = buildString {
                append(if (drained && pullFailures == 0) "Synchronized with Kakei Server" else "Sync finished with issues")
                if (rejectedCount > 0) append(" · $rejectedCount change(s) rejected by server")
                if (pendingLeft > 0) append(" · $pendingLeft change(s) still pending")
                if (pullFailures > 0) append(" · $pullFailures pull error(s)")
            }
            _settingsState.value = _settingsState.value.copy(
                syncInProgress = false,
                lastSyncTimestamp = nowIso,
                statusMessage = summary
            )
        } finally {
            if (_settingsState.value.syncInProgress) {
                _settingsState.value = _settingsState.value.copy(syncInProgress = false)
            }
            syncMutex.unlock()
        }
    }

    // ========================================================================
    // Local Database Reload & Real-Time Analytics Engine
    // ========================================================================

    fun reloadFromLocalDatabase() {
        val accs = database.listItems<AccountDto>(KakeiDatabase.COL_ACCOUNTS, descending = false)
        val cats = database.listItems<CategoryDto>(KakeiDatabase.COL_CATEGORIES, descending = false)
        val lbls = database.listItems<LabelDto>(KakeiDatabase.COL_LABELS, descending = false)
        val rls = database.listItems<CounterpartyRuleDto>(KakeiDatabase.COL_RULES, descending = false)
        val txs = database.listItems<TransactionDto>(KakeiDatabase.COL_TRANSACTIONS, descending = true)
        val rawBudgets = database.listItems<BudgetEnvelopeDto>(KakeiDatabase.COL_BUDGETS, descending = false)
        val obs = database.listItems<PlannedObligationDto>(KakeiDatabase.COL_OBLIGATIONS, descending = false)
        val lendItems = database.listItems<LendingRecordDto>(KakeiDatabase.COL_LENDING, descending = true)
        val savItems = database.listItems<SavingsGoalDto>(KakeiDatabase.COL_SAVINGS, descending = false)
        val grpItems = database.listItems<GroupMemberDto>(KakeiDatabase.COL_GROUP_MEMBERS, descending = false)
        val muts = database.listItems<SyncMutationEntity>(KakeiDatabase.COL_MUTATIONS, descending = false)
        val errs = database.listItems<SyncErrorEntity>(KakeiDatabase.COL_SYNC_ERRORS, descending = true)

        _accounts.value = accs
        _categories.value = cats
        _labels.value = lbls
        _rules.value = rls
        _transactions.value = txs
        _obligations.value = obs
        _savingsGoals.value = savItems
        _groupMembers.value = grpItems
        _pendingMutations.value = muts
        _syncErrors.value = errs
        _pendingCaptures.value = database.listItems(KakeiDatabase.COL_PENDING_CAPTURES, descending = true)
        database.getItem<ExchangeRatesResponse>(KakeiDatabase.COL_EXCHANGE_RATES, EXCHANGE_RATES_ID)?.let {
            _exchangeRates.value = it
            _rateBook.value = ExchangeRateBook.from(it)
        }

        recomputeDerivedAnalytics()
        KakeiWidgetUpdater.refreshAllWidgets(appContext)
    }

    private fun recomputeDerivedAnalytics() {
        val baseCurrency = _settingsState.value.profile.defaultCurrency
        val rates = _rateBook.value
        val accs = _accounts.value
        val txs = _transactions.value
        val rawBudgets = database.listItems<BudgetEnvelopeDto>(KakeiDatabase.COL_BUDGETS, descending = false)
        val obs = _obligations.value
        val lendItems = database.listItems<LendingRecordDto>(KakeiDatabase.COL_LENDING, descending = true)

        val evaluatedBudgets = FinancialCalculators.evaluateBudgets(rawBudgets, txs, rates = rates)
        _budgets.value = evaluatedBudgets

        // Debts in a currency without a known rate are left out of the base-currency totals
        // (each record still shows its own amount and currency).
        val owedToMe = ConvertedTotal.of(
            lendItems.filter { it.type == "LENT" && !it.isSettled && !it.isForgiven }.map { it.outstandingAmount to it.currency },
            baseCurrency,
            rates
        ).total.setScale(2, RoundingMode.HALF_UP)

        val owedByMe = ConvertedTotal.of(
            lendItems.filter { it.type == "BORROWED" && !it.isSettled && !it.isForgiven }.map { it.outstandingAmount to it.currency },
            baseCurrency,
            rates
        ).total.setScale(2, RoundingMode.HALF_UP)

        _lendingResponse.value = LendingListResponse(
            baseCurrency = baseCurrency,
            totalOwedToMe = owedToMe,
            totalOwedByMe = owedByMe,
            netPosition = (owedToMe - owedByMe).setScale(2, RoundingMode.HALF_UP),
            items = lendItems
        )

        _forecast.value = FinancialCalculators.calculateCashFlowForecast(accs, obs, baseCurrency, rates = rates)
        _healthScore.value = FinancialCalculators.calculateHealthScore(txs, evaluatedBudgets, lendItems, baseCurrency, rates = rates)
        _streaks.value = FinancialCalculators.calculateNoSpendStreak(txs)
        _heatmaps.value = FinancialCalculators.calculateHeatmaps(txs, baseCurrency, rates = rates)
        _insights.value = FinancialCalculators.calculateInsights(txs, baseCurrency, rates = rates)
    }

    /** Net worth in the base currency plus the balances that could not be converted. */
    fun netWorth(): ConvertedTotal = ConvertedTotal.of(
        _accounts.value.filter { !it.isArchived && !it.isExcludedFromStats }.map { it.currentBalance to it.currency },
        _settingsState.value.profile.defaultCurrency,
        _rateBook.value
    )

    // ========================================================================
    // Exchange Rates (server-provided, user overrides)
    // ========================================================================

    private fun storeExchangeRates(resp: ExchangeRatesResponse) {
        putDto(KakeiDatabase.COL_EXCHANGE_RATES, EXCHANGE_RATES_ID, EXCHANGE_RATES_ID, resp)
        _exchangeRates.value = resp
        _rateBook.value = ExchangeRateBook.from(resp)
        recomputeDerivedAnalytics()
    }

    fun refreshExchangeRates() {
        if (!_settingsState.value.isAuthenticated) return
        scope.launch {
            apiClient.getExchangeRates()
                .onSuccess { storeExchangeRates(it) }
                .onFailure { postMessage("Exchange rates refresh failed: ${it.message}") }
        }
    }

    /** Sets a manual rate: [rate] units of [toCurrency] per 1 [fromCurrency]. Requires a connection. */
    fun setExchangeRateOverride(fromCurrency: String, toCurrency: String, rate: BigDecimal) {
        val from = fromCurrency.trim().uppercase(Locale.ROOT)
        val to = toCurrency.trim().uppercase(Locale.ROOT)
        if (from == to || rate <= BigDecimal.ZERO) return
        if (!_settingsState.value.isAuthenticated) {
            postMessage("Sign in to Kakei Server to set exchange rates")
            return
        }
        scope.launch {
            apiClient.upsertExchangeRateOverride(
                UpsertExchangeRateOverrideRequest(fromCurrency = from, toCurrency = to, rate = rate.stripTrailingZeros())
            ).onSuccess { saved ->
                val current = _exchangeRates.value
                storeExchangeRates(
                    current.copy(
                        overrides = current.overrides.filterNot {
                            it.fromCurrency.equals(from, true) && it.toCurrency.equals(to, true)
                        } + saved
                    )
                )
                postMessage("Rate saved: 1 $from = ${saved.rate.stripTrailingZeros().toPlainString()} $to")
            }.onFailure { postMessage("Could not save rate: ${it.message}") }
        }
    }

    fun resetExchangeRateOverride(fromCurrency: String, toCurrency: String) {
        if (!_settingsState.value.isAuthenticated) {
            postMessage("Sign in to Kakei Server to change exchange rates")
            return
        }
        scope.launch {
            apiClient.deleteExchangeRateOverride(fromCurrency, toCurrency).onSuccess {
                val current = _exchangeRates.value
                storeExchangeRates(
                    current.copy(
                        overrides = current.overrides.filterNot {
                            it.fromCurrency.equals(fromCurrency, true) && it.toCurrency.equals(toCurrency, true)
                        }
                    )
                )
            }.onFailure { postMessage("Could not reset rate: ${it.message}") }
        }
    }

    // ========================================================================
    // 3. Accounts Operations
    // ========================================================================

    fun createAccount(
        name: String,
        accountType: String,
        currency: String,
        initialBalance: BigDecimal,
        isExcludedFromStats: Boolean = false
    ) {
        val now = Instant.now().toString()
        val localAccount = AccountDto(
            id = UUID.randomUUID().toString(),
            userId = _settingsState.value.profile.userId,
            name = name.trim(),
            accountType = accountType,
            currency = currency.uppercase(Locale.ROOT),
            initialBalance = initialBalance.setScale(4, RoundingMode.HALF_UP),
            currentBalance = initialBalance.setScale(4, RoundingMode.HALF_UP),
            isExcludedFromStats = isExcludedFromStats,
            isArchived = false,
            syncRevision = 1L,
            permissionLevel = "ADMIN",
            createdAt = now,
            updatedAt = now
        )
        database.putItem(
            KakeiDatabase.COL_ACCOUNTS,
            localAccount.id,
            localAccount.name,
            database.json.encodeToString(localAccount)
        )
        reloadFromLocalDatabase()
        enqueueMutation(
            entityType = "ACCOUNT",
            entityId = localAccount.id,
            operation = "CREATE",
            payloadJson = database.json.encodeToString(
                CreateAccountRequest(
                    name = localAccount.name,
                    accountType = localAccount.accountType,
                    currency = localAccount.currency,
                    initialBalance = localAccount.initialBalance,
                    isExcludedFromStats = localAccount.isExcludedFromStats
                )
            )
        )
    }

    fun toggleArchiveAccount(account: AccountDto) {
        val updated = account.copy(
            isArchived = !account.isArchived,
            syncRevision = account.syncRevision + 1,
            updatedAt = Instant.now().toString()
        )
        database.putItem(KakeiDatabase.COL_ACCOUNTS, updated.id, updated.name, database.json.encodeToString(updated))
        val mutationItem = AccountMutationItem(
            id = account.id,
            isArchived = updated.isArchived,
            expectedSyncRevision = account.syncRevision
        )
        enqueueOrSendAccountMutation(mutationItem)
        reloadFromLocalDatabase()
    }

    fun deleteAccount(accountId: String) {
        database.deleteItem(KakeiDatabase.COL_ACCOUNTS, accountId)
        reloadFromLocalDatabase()
        enqueueMutation("ACCOUNT", accountId, "DELETE", "{}")
    }

    private fun enqueueOrSendAccountMutation(item: AccountMutationItem) {
        enqueueMutation(
            entityType = "ACCOUNT",
            entityId = item.id,
            operation = "UPDATE",
            payloadJson = database.json.encodeToString(item),
            expectedSyncRevision = item.expectedSyncRevision
        )
    }

    // ========================================================================
    // 4. Categories, Labels & Smart Merchant Rules
    // ========================================================================

    fun createCategory(
        name: String,
        parentId: String?,
        iconKey: String,
        colorHex: String
    ) {
        val cat = CategoryDto(
            id = UUID.randomUUID().toString(),
            userId = _settingsState.value.profile.userId,
            parentId = parentId,
            name = name.trim(),
            translations = mapOf("ru" to name.trim(), "en" to name.trim()),
            iconKey = iconKey,
            colorHex = colorHex,
            isSystem = false,
            createdAt = Instant.now().toString()
        )
        database.putItem(KakeiDatabase.COL_CATEGORIES, cat.id, cat.name, database.json.encodeToString(cat))
        reloadFromLocalDatabase()
        enqueueMutation(
            entityType = "CATEGORY",
            entityId = cat.id,
            operation = "CREATE",
            payloadJson = database.json.encodeToString(
                UpsertCategoryRequest(
                    parentId = parentId,
                    name = cat.name,
                    translations = cat.translations,
                    iconKey = iconKey,
                    colorHex = colorHex
                )
            )
        )
    }

    fun deleteCategory(id: String) {
        database.deleteItem(KakeiDatabase.COL_CATEGORIES, id)
        reloadFromLocalDatabase()
        enqueueMutation("CATEGORY", id, "DELETE", "{}")
    }

    fun createLabel(name: String, colorHex: String) {
        val lbl = LabelDto(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            colorHex = colorHex,
            createdAt = Instant.now().toString()
        )
        database.putItem(KakeiDatabase.COL_LABELS, lbl.id, lbl.name, database.json.encodeToString(lbl))
        reloadFromLocalDatabase()
        enqueueMutation(
            "LABEL", lbl.id, "CREATE",
            database.json.encodeToString(CreateLabelRequest(name = lbl.name, colorHex = colorHex))
        )
    }

    fun deleteLabel(id: String) {
        database.deleteItem(KakeiDatabase.COL_LABELS, id)
        reloadFromLocalDatabase()
        enqueueMutation("LABEL", id, "DELETE", "{}")
    }

    fun createSmartRule(pattern: String, matchType: String, categoryId: String) {
        val rule = CounterpartyRuleDto(
            id = UUID.randomUUID().toString(),
            counterpartyPattern = pattern.trim(),
            matchType = matchType,
            categoryId = categoryId,
            updatedAt = Instant.now().toString()
        )
        database.putItem(KakeiDatabase.COL_RULES, rule.id, rule.counterpartyPattern, database.json.encodeToString(rule))
        reloadFromLocalDatabase()
        enqueueMutation(
            "RULE", rule.id, "CREATE",
            database.json.encodeToString(
                CreateRuleRequest(
                    counterpartyPattern = rule.counterpartyPattern,
                    matchType = matchType,
                    categoryId = categoryId
                )
            )
        )
    }

    fun deleteSmartRule(id: String) {
        database.deleteItem(KakeiDatabase.COL_RULES, id)
        reloadFromLocalDatabase()
        enqueueMutation("RULE", id, "DELETE", "{}")
    }

    // ========================================================================
    // 5. Transactions / Ledger Records (Splits, Counterflow Transfers, Review)
    // ========================================================================

    /** Outcome of [createRecordDetailed]. */
    data class RecordCreateResult(
        val record: TransactionDto?,
        /** True when an identical record (same dedup hash on the same account) already existed. */
        val duplicate: Boolean = false,
        /** Set when nothing was created and the user has to act (e.g. missing exchange rate). */
        val error: String? = null
    )

    fun createRecord(
        accountId: String,
        type: String,
        amount: BigDecimal,
        categoryId: String?,
        payee: String?,
        note: String?,
        status: String = "CLEARED",
        transferTargetAccountId: String? = null,
        splits: List<SplitDto> = emptyList(),
        labelIds: List<String> = emptyList(),
        needsReview: Boolean = false,
        bookingDateIso: String = Instant.now().toString(),
        targetAmount: BigDecimal? = null
    ): TransactionDto? = createRecordDetailed(
        accountId, type, amount, categoryId, payee, note, status, transferTargetAccountId,
        splits, labelIds, needsReview, bookingDateIso, targetAmount
    ).also { result -> result.error?.let { postMessage(it) } }.record

    /**
     * Creates a record locally and queues it for the server.
     *
     * - Dedup: when a local record on the same account already carries the same canonical
     *   dedup hash (see [HashDedupCalculator]) the existing record is returned and nothing
     *   is created, so re-importing a statement or a re-delivered bank alert is idempotent.
     * - Transfers: the target account is credited exactly [targetAmount] (the amount the
     *   bank actually credited, in the target currency). Without it, a cross-currency
     *   transfer uses the effective server rate; if no rate is known it is refused.
     */
    fun createRecordDetailed(
        accountId: String,
        type: String,
        amount: BigDecimal,
        categoryId: String?,
        payee: String?,
        note: String?,
        status: String = "CLEARED",
        transferTargetAccountId: String? = null,
        splits: List<SplitDto> = emptyList(),
        labelIds: List<String> = emptyList(),
        needsReview: Boolean = false,
        bookingDateIso: String = Instant.now().toString(),
        targetAmount: BigDecimal? = null
    ): RecordCreateResult {
        val account = _accounts.value.firstOrNull { it.id == accountId }
            ?: return RecordCreateResult(null, error = "Account not found")
        val baseCurrency = _settingsState.value.profile.defaultCurrency
        val rates = _rateBook.value
        val normalizedAmount = amount.abs().setScale(4, RoundingMode.HALF_UP)
        val cleanPayee = payee?.takeIf { it.isNotBlank() }
        val dedupHash = HashDedupCalculator.computeTransactionHash(
            accountId = accountId,
            amount = normalizedAmount,
            currency = account.currency,
            bookingDateIso = bookingDateIso,
            payee = cleanPayee
        )
        _transactions.value.firstOrNull {
            it.accountId == accountId && !it.isTransferCredit && it.hashDedup == dedupHash
        }?.let { return RecordCreateResult(it, duplicate = true) }

        val isTransfer = type == "TRANSFER" && !transferTargetAccountId.isNullOrBlank()
        val targetAcc = if (isTransfer) {
            _accounts.value.firstOrNull { it.id == transferTargetAccountId }
                ?: return RecordCreateResult(null, error = "Transfer target account not found")
        } else null
        val crossCurrency = targetAcc != null && !targetAcc.currency.equals(account.currency, ignoreCase = true)
        val creditAmount: BigDecimal? = when {
            targetAcc == null -> null
            !crossCurrency -> normalizedAmount
            targetAmount != null && targetAmount.signum() > 0 -> targetAmount.abs().setScale(4, RoundingMode.HALF_UP)
            else -> rates.convert(normalizedAmount, account.currency, targetAcc.currency)
                ?: return RecordCreateResult(
                    null,
                    error = "No ${account.currency}→${targetAcc.currency} exchange rate known: enter the amount received"
                )
        }

        val pairId = if (targetAcc != null) UUID.randomUUID().toString() else null

        val record = TransactionDto(
            id = UUID.randomUUID().toString(),
            accountId = accountId,
            categoryId = categoryId,
            type = type,
            amount = normalizedAmount,
            currency = account.currency,
            // Informational only (the server sends the authoritative value); zero when no rate is known.
            convertedAmount = CurrencyFormatter.convert(normalizedAmount, account.currency, baseCurrency, rates)
                ?: BigDecimal.ZERO,
            bookingDate = bookingDateIso,
            payee = cleanPayee,
            note = note?.takeIf { it.isNotBlank() },
            status = status,
            needsReview = needsReview,
            transferPairId = pairId,
            transferTargetAccountId = transferTargetAccountId,
            hashDedup = dedupHash,
            syncRevision = 1L,
            splits = splits,
            labelIds = labelIds,
            createdAt = bookingDateIso,
            updatedAt = bookingDateIso
        )

        database.runInTransaction {
            putDto(KakeiDatabase.COL_TRANSACTIONS, record.id, record.bookingDate, record)

            // Update source account balance
            val deltaSource = when (type) {
                "INCOME" -> normalizedAmount
                "EXPENSE", "TRANSFER" -> normalizedAmount.negate()
                else -> BigDecimal.ZERO
            }
            val updatedSource = account.copy(
                currentBalance = (account.currentBalance + deltaSource).setScale(4, RoundingMode.HALF_UP),
                updatedAt = bookingDateIso
            )
            putDto(KakeiDatabase.COL_ACCOUNTS, updatedSource.id, updatedSource.name, updatedSource)

            // TRANSFER: counterflow credit leg on the target account, exactly creditAmount.
            if (targetAcc != null && creditAmount != null && pairId != null) {
                val mirrorTx = record.copy(
                    id = UUID.randomUUID().toString(),
                    accountId = targetAcc.id,
                    type = "TRANSFER",
                    isTransferCredit = true,
                    amount = creditAmount,
                    currency = targetAcc.currency,
                    convertedAmount = CurrencyFormatter.convert(creditAmount, targetAcc.currency, baseCurrency, rates)
                        ?: BigDecimal.ZERO,
                    payee = "Transfer from ${account.name}",
                    transferPairId = pairId,
                    transferTargetAccountId = account.id,
                    hashDedup = null
                )
                putDto(KakeiDatabase.COL_TRANSACTIONS, mirrorTx.id, mirrorTx.bookingDate, mirrorTx)
                // Re-read: the target may be the same row we just wrote for the source.
                val freshTarget = database.getItem<AccountDto>(KakeiDatabase.COL_ACCOUNTS, targetAcc.id) ?: targetAcc
                val updatedTarget = freshTarget.copy(
                    currentBalance = (freshTarget.currentBalance + creditAmount).setScale(4, RoundingMode.HALF_UP),
                    updatedAt = bookingDateIso
                )
                putDto(KakeiDatabase.COL_ACCOUNTS, updatedTarget.id, updatedTarget.name, updatedTarget)
            }
        }

        // Queue Outbox mutation; the local id doubles as the server id (idempotent create)
        val createReqItem = CreateRecordItem(
            clientId = record.id,
            accountId = accountId,
            categoryId = categoryId,
            type = type,
            amount = normalizedAmount,
            bookingDate = bookingDateIso,
            payee = record.payee,
            note = record.note,
            status = status,
            transferTargetAccountId = transferTargetAccountId,
            // Always state the credited amount for cross-currency transfers so the server
            // books exactly what was credited locally (it would otherwise apply its own rate).
            targetAmount = if (crossCurrency) creditAmount else null,
            splits = splits.map { SplitItemInput(it.categoryId, it.amount, it.note) },
            labelIds = labelIds,
            skipIfDuplicateHash = true
        )
        reloadFromLocalDatabase()
        enqueueMutation(
            entityType = "RECORD",
            entityId = record.id,
            operation = "CREATE",
            payloadJson = database.json.encodeToString(createReqItem),
            expectedSyncRevision = 1L
        )
        return RecordCreateResult(record)
    }

    fun confirmReview(record: TransactionDto, categoryId: String, createRule: Boolean) {
        val updated = record.copy(
            categoryId = categoryId,
            needsReview = false,
            syncRevision = record.syncRevision + 1,
            updatedAt = Instant.now().toString()
        )
        database.putItem(
            KakeiDatabase.COL_TRANSACTIONS,
            updated.id,
            updated.bookingDate,
            database.json.encodeToString(updated)
        )
        if (_instantCapturedRecord.value?.id == record.id) {
            _instantCapturedRecord.value = null
        }
        if (createRule && !record.payee.isNullOrBlank()) {
            createSmartRule(
                pattern = record.payee,
                matchType = "CONTAINS",
                categoryId = categoryId
            )
        } else {
            reloadFromLocalDatabase()
        }
        enqueueMutation(
            "RECORD", record.id, "REVIEW",
            database.json.encodeToString(ConfirmReviewRequest(categoryId = categoryId, createSmartRule = createRule))
        )
    }

    fun dismissInstantCapturedBanner() {
        _instantCapturedRecord.value = null
    }

    /** Signed effect a record has on its own account balance (mirrors the server rule). */
    private fun balanceEffect(record: TransactionDto): BigDecimal = when (record.type) {
        "INCOME" -> record.amount
        "EXPENSE" -> record.amount.negate()
        "TRANSFER" -> if (record.isTransferCredit) record.amount else record.amount.negate()
        else -> BigDecimal.ZERO
    }

    /**
     * Deletes a record. A transfer is removed as a whole: both legs (same transferPairId)
     * are deleted and each leg's own effect is reverted on its own account, in its own
     * currency, inside one transaction. The server deletes both legs when either is
     * deleted, so exactly one DELETE is queued.
     */
    fun deleteRecord(record: TransactionDto) {
        val legs = if (record.transferPairId != null) {
            database.listItems<TransactionDto>(KakeiDatabase.COL_TRANSACTIONS)
                .filter { it.transferPairId == record.transferPairId }
                .ifEmpty { listOf(record) }
        } else listOf(record)

        database.runInTransaction {
            for (leg in legs) {
                database.deleteItem(KakeiDatabase.COL_TRANSACTIONS, leg.id)
                // Fresh read per leg: both legs may sit on the same account.
                val account = database.getItem<AccountDto>(KakeiDatabase.COL_ACCOUNTS, leg.accountId) ?: continue
                val updatedAcc = account.copy(
                    currentBalance = (account.currentBalance - balanceEffect(leg)).setScale(4, RoundingMode.HALF_UP)
                )
                putDto(KakeiDatabase.COL_ACCOUNTS, updatedAcc.id, updatedAcc.name, updatedAcc)
            }
        }
        reloadFromLocalDatabase()

        // Delete through the debit leg: its id is the client id the server stored, while a
        // locally generated credit leg id is unknown to the server until the next pull.
        val serverLeg = legs.firstOrNull { !it.isTransferCredit } ?: record
        enqueueMutation(
            entityType = "RECORD",
            entityId = serverLeg.id,
            operation = "DELETE",
            payloadJson = "{}",
            expectedSyncRevision = serverLeg.syncRevision
        )
    }

    // ========================================================================
    // 6. Budgets Operations
    // ========================================================================

    fun createBudget(
        name: String,
        limitAmount: BigDecimal,
        currency: String,
        periodType: String,
        notifyThreshold: Int = 80,
        categoryIds: List<String> = emptyList()
    ) {
        val today = LocalDate.now()
        val nowIso = Instant.now().toString()
        val budget = BudgetEnvelopeDto(
            id = UUID.randomUUID().toString(),
            userId = _settingsState.value.profile.userId,
            name = name.trim(),
            limitAmount = limitAmount.setScale(2, RoundingMode.HALF_UP),
            currency = currency,
            periodType = periodType,
            periodStart = today.withDayOfMonth(1).toString(),
            periodEnd = today.withDayOfMonth(today.lengthOfMonth()).toString(),
            startDate = today.withDayOfMonth(1).toString(),
            notifyThreshold = notifyThreshold,
            categoryIds = categoryIds,
            createdAt = nowIso,
            updatedAt = nowIso
        )
        database.putItem(KakeiDatabase.COL_BUDGETS, budget.id, budget.name, database.json.encodeToString(budget))
        reloadFromLocalDatabase()
        enqueueMutation(
            "BUDGET", budget.id, "CREATE",
            database.json.encodeToString(
                UpsertBudgetRequest(
                    name = budget.name,
                    limitAmount = budget.limitAmount,
                    currency = budget.currency,
                    periodType = budget.periodType,
                    startDate = budget.startDate,
                    notifyThreshold = notifyThreshold,
                    categoryIds = categoryIds
                )
            )
        )
    }

    fun deleteBudget(id: String) {
        database.deleteItem(KakeiDatabase.COL_BUDGETS, id)
        reloadFromLocalDatabase()
        enqueueMutation("BUDGET", id, "DELETE", "{}")
    }

    // ========================================================================
    // 7. Planning & Recurring Obligations
    // ========================================================================

    fun createObligation(
        title: String,
        accountId: String,
        categoryId: String?,
        type: String,
        amount: BigDecimal,
        currency: String,
        frequency: String,
        nextDueDate: String,
        payee: String?,
        autoPost: Boolean = true
    ) {
        val nowIso = Instant.now().toString()
        val ob = PlannedObligationDto(
            id = UUID.randomUUID().toString(),
            userId = _settingsState.value.profile.userId,
            accountId = accountId,
            categoryId = categoryId,
            title = title.trim(),
            type = type,
            amount = amount.setScale(2, RoundingMode.HALF_UP),
            currency = currency,
            frequency = frequency,
            nextDueDate = nextDueDate,
            payee = payee?.takeIf { it.isNotBlank() } ?: title.trim(),
            autoPost = autoPost,
            isActive = true,
            createdAt = nowIso,
            updatedAt = nowIso
        )
        database.putItem(KakeiDatabase.COL_OBLIGATIONS, ob.id, ob.nextDueDate, database.json.encodeToString(ob))
        reloadFromLocalDatabase()
        enqueueMutation(
            "OBLIGATION", ob.id, "CREATE",
            database.json.encodeToString(
                UpsertObligationRequest(
                    accountId = accountId,
                    categoryId = categoryId,
                    title = ob.title,
                    type = type,
                    amount = ob.amount,
                    currency = currency,
                    frequency = frequency,
                    nextDueDate = nextDueDate,
                    payee = ob.payee,
                    autoPost = autoPost,
                    isActive = true
                )
            )
        )
    }

    fun executeObligationNow(obligation: PlannedObligationDto) {
        if (_settingsState.value.isAuthenticated) {
            // The server posts the ledger record and advances the schedule itself; posting a
            // local record as well would duplicate it. The response is applied on sync.
            enqueueMutation("OBLIGATION", obligation.id, "EXECUTE", "{}")
            return
        }
        // Offline autonomous mode: post locally & advance nextDueDate
        createRecord(
            accountId = obligation.accountId,
            type = obligation.type,
            amount = obligation.amount,
            categoryId = obligation.categoryId,
            payee = obligation.payee ?: obligation.title,
            note = "Scheduled Obligation: ${obligation.title}"
        )
        val currentDue = runCatching { LocalDate.parse(obligation.nextDueDate.take(10)) }
            .getOrDefault(LocalDate.now())
        val nextDue = when (obligation.frequency.uppercase(Locale.ROOT)) {
            "DAILY" -> currentDue.plusDays(1)
            "WEEKLY" -> currentDue.plusWeeks(1)
            "MONTHLY" -> currentDue.plusMonths(1)
            "YEARLY" -> currentDue.plusYears(1)
            else -> currentDue
        }
        val updatedOb = obligation.copy(
            nextDueDate = nextDue.toString(),
            isActive = obligation.frequency != "ONE_OFF",
            lastPostedAt = Instant.now().toString()
        )
        database.putItem(
            KakeiDatabase.COL_OBLIGATIONS,
            updatedOb.id,
            updatedOb.nextDueDate,
            database.json.encodeToString(updatedOb)
        )
        reloadFromLocalDatabase()
    }

    fun deleteObligation(id: String) {
        database.deleteItem(KakeiDatabase.COL_OBLIGATIONS, id)
        reloadFromLocalDatabase()
        enqueueMutation("OBLIGATION", id, "DELETE", "{}")
    }

    // ========================================================================
    // 8. Lending, Repayments, Debt Forgiveness, Split Expense & Settle All
    // ========================================================================

    fun createLendingRecord(
        personName: String,
        type: String, // "LENT" | "BORROWED"
        principalAmount: BigDecimal,
        currency: String,
        dueDate: String?,
        note: String?
    ) {
        val nowIso = Instant.now().toString()
        val cleanAmount = principalAmount.setScale(FinancialCalculators.currencyScale(currency), RoundingMode.HALF_UP)
        val record = LendingRecordDto(
            id = UUID.randomUUID().toString(),
            userId = _settingsState.value.profile.userId,
            personName = personName.trim(),
            type = type,
            principalAmount = cleanAmount,
            settledAmount = BigDecimal.ZERO,
            outstandingAmount = cleanAmount,
            currency = currency,
            dueDate = dueDate,
            isSettled = false,
            isForgiven = false,
            note = note?.takeIf { it.isNotBlank() },
            repayments = emptyList(),
            createdAt = nowIso,
            updatedAt = nowIso
        )
        database.putItem(KakeiDatabase.COL_LENDING, record.id, record.createdAt, database.json.encodeToString(record))
        reloadFromLocalDatabase()
        enqueueMutation(
            "LENDING", record.id, "CREATE",
            database.json.encodeToString(
                CreateLendingRequest(
                    personName = record.personName,
                    type = type,
                    principalAmount = cleanAmount,
                    currency = currency,
                    dueDate = dueDate,
                    note = record.note
                )
            )
        )
    }

    fun recordRepayment(lending: LendingRecordDto, amount: BigDecimal, note: String?) {
        val repayAmount = amount.min(lending.outstandingAmount).setScale(2, RoundingMode.HALF_UP)
        if (repayAmount <= BigDecimal.ZERO) return
        val newSettled = (lending.settledAmount + repayAmount).setScale(2, RoundingMode.HALF_UP)
        val newOutstanding = (lending.principalAmount - newSettled).coerceAtLeast(BigDecimal.ZERO)
        val repayment = LendingRepaymentDto(
            id = UUID.randomUUID().toString(),
            lendingRecordId = lending.id,
            amount = repayAmount,
            repaymentDate = LocalDate.now().toString(),
            note = note?.takeIf { it.isNotBlank() },
            createdAt = Instant.now().toString()
        )
        val updated = lending.copy(
            settledAmount = newSettled,
            outstandingAmount = newOutstanding,
            isSettled = newOutstanding.compareTo(BigDecimal.ZERO) == 0,
            repayments = lending.repayments + repayment,
            updatedAt = Instant.now().toString()
        )
        database.putItem(KakeiDatabase.COL_LENDING, updated.id, updated.createdAt, database.json.encodeToString(updated))
        reloadFromLocalDatabase()
        enqueueMutation(
            "LENDING", lending.id, "REPAYMENT",
            database.json.encodeToString(
                CreateRepaymentRequest(
                    amount = repayAmount,
                    repaymentDate = repayment.repaymentDate,
                    note = repayment.note
                )
            )
        )
    }

    fun forgiveDebt(lending: LendingRecordDto) {
        val updated = lending.copy(
            outstandingAmount = BigDecimal.ZERO,
            isSettled = true,
            isForgiven = true,
            updatedAt = Instant.now().toString()
        )
        database.putItem(KakeiDatabase.COL_LENDING, updated.id, updated.createdAt, database.json.encodeToString(updated))
        reloadFromLocalDatabase()
        enqueueMutation("LENDING", lending.id, "FORGIVE", "{}")
    }

    fun splitExpenseAcrossParticipants(
        totalAmount: BigDecimal,
        currency: String,
        participantNames: List<String>,
        includeSelf: Boolean,
        note: String?
    ) {
        val cleanNames = participantNames.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleanNames.isEmpty()) return
        val parts = cleanNames.size + if (includeSelf) 1 else 0
        // Shares add up to the total exactly; the user's own share (if any) is the last one.
        val shares = FinancialCalculators.splitEvenly(totalAmount, parts, FinancialCalculators.currencyScale(currency))

        for ((person, share) in cleanNames.zip(shares)) {
            createLendingRecord(
                personName = person,
                type = "LENT",
                principalAmount = share,
                currency = currency,
                dueDate = LocalDate.now().plusDays(14).toString(),
                note = note ?: "Split bill (${CurrencyFormatter.format(totalAmount, currency)})"
            )
        }
        val owed = shares.take(cleanNames.size)
        val shareText = if (owed.distinct().size == 1) {
            "${CurrencyFormatter.format(owed.first(), currency)} each"
        } else {
            "${CurrencyFormatter.format(owed.min(), currency)}–${CurrencyFormatter.format(owed.max(), currency)} each"
        }
        postMessage("Split bill created for ${cleanNames.size} participants ($shareText)")
    }

    fun settleAllForPerson(personName: String) {
        val records = _lendingResponse.value.items.filter {
            it.personName.equals(personName.trim(), ignoreCase = true) && !it.isSettled && !it.isForgiven
        }
        for (rec in records) {
            val settled = rec.copy(
                settledAmount = rec.principalAmount,
                outstandingAmount = BigDecimal.ZERO,
                isSettled = true,
                updatedAt = Instant.now().toString()
            )
            database.putItem(KakeiDatabase.COL_LENDING, settled.id, settled.createdAt, database.json.encodeToString(settled))
        }
        reloadFromLocalDatabase()
        postMessage("Settled ${records.size} records with $personName")
        if (records.isNotEmpty()) {
            enqueueMutation(
                "LENDING", personName.trim(), "SETTLE_ALL",
                database.json.encodeToString(SettleAllRequest(personName = personName.trim()))
            )
        }
    }

    // ========================================================================
    // 9. Savings Goals (Piggybanks & Quick Steppers)
    // ========================================================================

    fun createSavingsGoal(
        title: String,
        targetAmount: BigDecimal,
        currency: String,
        deadlineDate: String?,
        colorHex: String = "#30A46C"
    ) {
        val cleanTarget = targetAmount.setScale(2, RoundingMode.HALF_UP)
        val pacing = computeGoalPacing(cleanTarget, BigDecimal.ZERO, deadlineDate)
        val nowIso = Instant.now().toString()
        val goal = SavingsGoalDto(
            id = UUID.randomUUID().toString(),
            userId = _settingsState.value.profile.userId,
            title = title.trim(),
            targetAmount = cleanTarget,
            currentAmount = BigDecimal.ZERO,
            remainingAmount = cleanTarget,
            progressPercentage = BigDecimal.ZERO,
            requiredDailyContribution = pacing.first,
            requiredMonthlyContribution = pacing.second,
            currency = currency,
            deadlineDate = deadlineDate,
            iconKey = "piggy-bank",
            colorHex = colorHex,
            contributions = emptyList(),
            createdAt = nowIso,
            updatedAt = nowIso
        )
        database.putItem(KakeiDatabase.COL_SAVINGS, goal.id, goal.title, database.json.encodeToString(goal))
        reloadFromLocalDatabase()
        enqueueMutation(
            "SAVINGS", goal.id, "CREATE",
            database.json.encodeToString(
                UpsertSavingsGoalRequest(
                    title = goal.title,
                    targetAmount = cleanTarget,
                    currency = currency,
                    deadlineDate = deadlineDate,
                    colorHex = colorHex
                )
            )
        )
    }

    fun addSavingsContribution(goal: SavingsGoalDto, amount: BigDecimal) {
        val addAmt = amount.setScale(2, RoundingMode.HALF_UP)
        if (addAmt <= BigDecimal.ZERO) return
        val newCurrent = (goal.currentAmount + addAmt).setScale(2, RoundingMode.HALF_UP)
        val newRemaining = (goal.targetAmount - newCurrent).coerceAtLeast(BigDecimal.ZERO)
        val pct = if (goal.targetAmount > BigDecimal.ZERO) {
            newCurrent.multiply(BigDecimal("100")).divide(goal.targetAmount, 1, RoundingMode.HALF_UP)
        } else BigDecimal.ZERO
        val pacing = computeGoalPacing(goal.targetAmount, newCurrent, goal.deadlineDate)
        val contrib = SavingsContributionDto(
            id = UUID.randomUUID().toString(),
            goalId = goal.id,
            amount = addAmt,
            contributionDate = LocalDate.now().toString(),
            createdAt = Instant.now().toString()
        )
        val updated = goal.copy(
            currentAmount = newCurrent,
            remainingAmount = newRemaining,
            progressPercentage = pct,
            requiredDailyContribution = pacing.first,
            requiredMonthlyContribution = pacing.second,
            contributions = goal.contributions + contrib,
            updatedAt = Instant.now().toString()
        )
        database.putItem(KakeiDatabase.COL_SAVINGS, updated.id, updated.title, database.json.encodeToString(updated))
        reloadFromLocalDatabase()
        enqueueMutation(
            "SAVINGS", goal.id, "CONTRIBUTION",
            database.json.encodeToString(AddContributionRequest(amount = addAmt, contributionDate = contrib.contributionDate))
        )
    }

    fun deleteSavingsGoal(id: String) {
        database.deleteItem(KakeiDatabase.COL_SAVINGS, id)
        reloadFromLocalDatabase()
        enqueueMutation("SAVINGS", id, "DELETE", "{}")
    }

    private fun computeGoalPacing(
        target: BigDecimal,
        current: BigDecimal,
        deadlineIso: String?
    ): Pair<BigDecimal?, BigDecimal?> {
        val rem = (target - current).coerceAtLeast(BigDecimal.ZERO)
        if (rem <= BigDecimal.ZERO || deadlineIso.isNullOrBlank()) return BigDecimal.ZERO to BigDecimal.ZERO
        val deadline = runCatching { LocalDate.parse(deadlineIso.take(10)) }.getOrNull() ?: return null to null
        val days = maxOf(1L, java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), deadline))
        val daily = rem.divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP)
        val monthly = daily.multiply(BigDecimal("30")).setScale(2, RoundingMode.HALF_UP)
        return daily to monthly
    }

    // ========================================================================
    // 10. Exchange Import / Export & Encrypted Backups
    // ========================================================================

    fun importStatementContent(
        accountId: String,
        format: String,
        delimiter: String,
        content: String,
        mapping: ColumnMapping = ColumnMapping()
    ): ImportStatementResponse {
        val acc = _accounts.value.firstOrNull { it.id == accountId } ?: _accounts.value.firstOrNull()
            ?: return ImportStatementResponse()
        val existingHashes = _transactions.value.mapNotNull { it.hashDedup }.toSet()
        val req = ImportStatementRequest(
            accountId = acc.id,
            format = format,
            delimiter = delimiter,
            hasHeader = true,
            mapping = mapping,
            content = content
        )
        val (summary, items) = FinancialCalculators.parseStatementImport(acc, existingHashes, req)
        for (item in items) {
            val match = SmartMerchantMatcher.classify(item.payee, item.note, _rules.value, _categories.value)
            createRecord(
                accountId = item.accountId,
                type = item.type,
                amount = item.amount,
                categoryId = match.categoryId,
                payee = item.payee,
                note = item.note,
                needsReview = match.needsReview,
                bookingDateIso = item.bookingDate ?: Instant.now().toString()
            )
        }
        // Imported rows reach the server as individual RECORD CREATE outbox entries
        // (idempotent by client id and dedup hash); posting the raw statement as well
        // would import everything twice.
        postMessage("Imported ${summary.importedCount} records (${summary.duplicateSkippedCount} duplicates skipped)")
        return summary
    }

    fun exportCsvString(): String {
        val accMap = _accounts.value.associateBy { it.id }
        val catMap = _categories.value.associateBy { it.id }
        return FinancialCalculators.exportTransactionsToCsv(_transactions.value, accMap, catMap)
    }

    fun createEncryptedBackupString(passphrase: String): String {
        val snapshot = EncryptedSnapshotPayload(
            exportedAt = Instant.now().toString(),
            baseCurrency = _settingsState.value.profile.defaultCurrency,
            accounts = _accounts.value,
            categories = _categories.value,
            labels = _labels.value,
            rules = _rules.value,
            transactions = _transactions.value,
            budgets = _budgets.value,
            obligations = _obligations.value,
            lending = _lendingResponse.value.items,
            savings = _savingsGoals.value
        )
        val rawJson = database.json.encodeToString(snapshot)
        return BackupCryptoHelper.encryptBackup(rawJson, passphrase)
    }

    fun restoreEncryptedBackupString(encryptedBase64: String, passphrase: String): Boolean {
        return runCatching {
            val plain = BackupCryptoHelper.decryptBackup(encryptedBase64, passphrase)
            val snapshot = database.json.decodeFromString<EncryptedSnapshotPayload>(plain)
            database.clearAll()
            snapshot.accounts.forEach { database.putItem(KakeiDatabase.COL_ACCOUNTS, it.id, it.name, database.json.encodeToString(it)) }
            snapshot.categories.forEach { database.putItem(KakeiDatabase.COL_CATEGORIES, it.id, it.name, database.json.encodeToString(it)) }
            snapshot.labels.forEach { database.putItem(KakeiDatabase.COL_LABELS, it.id, it.name, database.json.encodeToString(it)) }
            snapshot.rules.forEach { database.putItem(KakeiDatabase.COL_RULES, it.id, it.counterpartyPattern, database.json.encodeToString(it)) }
            snapshot.transactions.forEach { database.putItem(KakeiDatabase.COL_TRANSACTIONS, it.id, it.bookingDate, database.json.encodeToString(it)) }
            snapshot.budgets.forEach { database.putItem(KakeiDatabase.COL_BUDGETS, it.id, it.name, database.json.encodeToString(it)) }
            snapshot.obligations.forEach { database.putItem(KakeiDatabase.COL_OBLIGATIONS, it.id, it.nextDueDate, database.json.encodeToString(it)) }
            snapshot.lending.forEach { database.putItem(KakeiDatabase.COL_LENDING, it.id, it.createdAt, database.json.encodeToString(it)) }
            snapshot.savings.forEach { database.putItem(KakeiDatabase.COL_SAVINGS, it.id, it.title, database.json.encodeToString(it)) }
            reloadFromLocalDatabase()
            postMessage("Restored encrypted backup (${snapshot.transactions.size} records)")
            true
        }.getOrElse {
            postMessage("Backup restore failed: ${it.message}")
            false
        }
    }

    // ========================================================================
    // 11. Family Group Sharing
    // ========================================================================

    fun shareAccountWithMember(accountId: String, memberEmail: String, permissionLevel: String) {
        val acc = _accounts.value.firstOrNull { it.id == accountId } ?: return
        val member = GroupMemberDto(
            id = UUID.randomUUID().toString(),
            ownerId = _settingsState.value.profile.userId,
            memberUserId = "pending-${UUID.randomUUID()}",
            memberName = memberEmail.substringBefore("@"),
            memberEmail = memberEmail.trim(),
            accountId = acc.id,
            accountName = acc.name,
            permissionLevel = permissionLevel,
            createdAt = Instant.now().toString()
        )
        database.putItem(KakeiDatabase.COL_GROUP_MEMBERS, member.id, member.createdAt, database.json.encodeToString(member))
        reloadFromLocalDatabase()
        enqueueMutation(
            "GROUP_MEMBER", member.id, "CREATE",
            database.json.encodeToString(
                GroupMemberMutationPayload(
                    accountId = acc.id,
                    memberEmail = memberEmail.trim(),
                    permissionLevel = permissionLevel
                )
            )
        )
    }

    fun removeGroupMember(member: GroupMemberDto) {
        database.deleteItem(KakeiDatabase.COL_GROUP_MEMBERS, member.id)
        reloadFromLocalDatabase()
        // The member's real user id is only known once the share was created on the server;
        // a locally pending share still carries a placeholder and is simply dropped.
        val pendingLocally = _pendingMutations.value.any { it.entityType == "GROUP_MEMBER" && it.entityId == member.id }
        if (pendingLocally) {
            _pendingMutations.value
                .filter { it.entityType == "GROUP_MEMBER" && it.entityId == member.id }
                .forEach { database.deleteItem(KakeiDatabase.COL_MUTATIONS, it.id) }
            reloadFromLocalDatabase()
            return
        }
        enqueueMutation(
            "GROUP_MEMBER", member.id, "DELETE",
            database.json.encodeToString(
                GroupMemberMutationPayload(accountId = member.accountId, memberUserId = member.memberUserId)
            )
        )
    }

    // ========================================================================
    // 12. Native SMS / Push Ingestion Pipeline & Widget Metrics
    // ========================================================================

    /**
     * Parses a bank SMS / push and books it. The target account comes only from the
     * user's own configuration: the account mapped to this [sender], else the default
     * capture account. Without one (or when the alert's currency differs from that
     * account's) the alert is parked in [pendingCaptures] for the user to file; it is
     * never booked against a guessed or non-existent account.
     */
    fun ingestRawBankAlert(
        rawText: String,
        sourceChannel: String = "SMS",
        sender: String? = null
    ): TransactionDto? {
        val defaultCurrency = _settingsState.value.profile.defaultCurrency
        val parsed = BankSmsPatterns.parse(rawText, sourceChannel, defaultCurrency) ?: return null
        if (!dedupHelper.shouldProcess(parsed)) return null

        val settings = _settingsState.value
        val usable = _accounts.value.filter { !it.isArchived && it.permissionLevel != "READ_ONLY" }.associateBy { it.id }
        val senderKey = sender?.trim()?.takeIf { it.isNotEmpty() }
        val targetAccount = senderKey?.let { settings.captureSenderAccounts[it] }?.let { usable[it] }
            ?: settings.captureDefaultAccountId?.let { usable[it] }

        val bookingDate = Instant.now().toString()
        val reason = when {
            targetAccount == null -> "NO_ACCOUNT"
            !targetAccount.currency.equals(parsed.currency, ignoreCase = true) -> "CURRENCY_MISMATCH"
            else -> null
        }
        if (targetAccount == null || reason != null) {
            val capture = PendingCaptureEntity(
                id = UUID.randomUUID().toString(),
                sourceChannel = sourceChannel,
                sender = senderKey,
                rawText = parsed.rawMessage,
                operationType = parsed.operationType,
                amount = parsed.amount,
                currency = parsed.currency.uppercase(Locale.ROOT),
                merchant = parsed.merchant.takeIf { it.isNotBlank() },
                bookingDate = bookingDate,
                reason = reason ?: "NO_ACCOUNT",
                createdAt = bookingDate
            )
            putDto(KakeiDatabase.COL_PENDING_CAPTURES, capture.id, capture.createdAt, capture)
            _pendingCaptures.value = database.listItems(KakeiDatabase.COL_PENDING_CAPTURES, descending = true)
            return null
        }

        val classification = SmartMerchantMatcher.classify(
            payee = parsed.merchant,
            note = parsed.rawMessage,
            rules = _rules.value,
            categories = _categories.value
        )

        val result = createRecordDetailed(
            accountId = targetAccount.id,
            type = parsed.operationType,
            amount = parsed.amount,
            categoryId = classification.categoryId,
            payee = parsed.merchant,
            note = "[Auto-$sourceChannel] ${parsed.rawMessage}",
            status = "CLEARED",
            needsReview = classification.needsReview,
            bookingDateIso = bookingDate
        )

        val created = result.record?.takeIf { !result.duplicate }
        if (created != null && _settingsState.value.instantPopupEnabled) {
            _instantCapturedRecord.value = created
        }
        return created
    }

    /** Books a pending capture into [accountId]; optionally remembers the account for its sender. */
    fun filePendingCapture(capture: PendingCaptureEntity, accountId: String, rememberSender: Boolean) {
        val account = _accounts.value.firstOrNull { it.id == accountId } ?: return
        if (!account.currency.equals(capture.currency, ignoreCase = true)) {
            postMessage("${account.name} is in ${account.currency}, the alert is in ${capture.currency}")
            return
        }
        val classification = SmartMerchantMatcher.classify(
            payee = capture.merchant,
            note = capture.rawText,
            rules = _rules.value,
            categories = _categories.value
        )
        val result = createRecordDetailed(
            accountId = account.id,
            type = capture.operationType,
            amount = capture.amount,
            categoryId = classification.categoryId,
            payee = capture.merchant,
            note = "[Auto-${capture.sourceChannel}] ${capture.rawText}",
            needsReview = classification.needsReview,
            bookingDateIso = capture.bookingDate
        )
        if (result.record == null) {
            result.error?.let { postMessage(it) }
            return
        }
        if (rememberSender && !capture.sender.isNullOrBlank()) {
            setCaptureSenderAccount(capture.sender, account.id)
        }
        dismissPendingCapture(capture.id)
    }

    fun dismissPendingCapture(id: String) {
        database.deleteItem(KakeiDatabase.COL_PENDING_CAPTURES, id)
        _pendingCaptures.value = database.listItems(KakeiDatabase.COL_PENDING_CAPTURES, descending = true)
    }

    fun setDefaultCaptureAccount(accountId: String?) {
        prefs.edit().putString("capture_default_account", accountId).apply()
        _settingsState.value = _settingsState.value.copy(captureDefaultAccountId = accountId)
    }

    fun setCaptureSenderAccount(sender: String, accountId: String?) {
        val map = _settingsState.value.captureSenderAccounts.toMutableMap()
        if (accountId == null) map.remove(sender) else map[sender] = accountId
        prefs.edit().putString("capture_sender_accounts", database.json.encodeToString(map.toMap())).apply()
        _settingsState.value = _settingsState.value.copy(captureSenderAccounts = map.toMap())
    }

    fun getWidgetMetricSummary(widgetAction: String): String {
        val baseCurr = _settingsState.value.profile.defaultCurrency
        return when (widgetAction) {
            "NET_WORTH" -> formatConvertedTotal(netWorth())
            "TODAY_SPEND" -> {
                val today = LocalDate.now().toString()
                val spent = ConvertedTotal.of(
                    _transactions.value
                        .filter { it.type == "EXPENSE" && it.bookingDate.startsWith(today) }
                        .map { it.amount to it.currency },
                    baseCurr,
                    _rateBook.value
                )
                formatConvertedTotal(spent)
            }
            "BUDGET_RING" -> {
                val first = _budgets.value.firstOrNull()
                if (first != null) "${first.usagePercentage}% · ${first.name}" else "No active budget"
            }
            "GOALS_RING" -> {
                val first = _savingsGoals.value.firstOrNull()
                if (first != null) "${first.progressPercentage}% · ${first.title}" else "No active goal"
            }
            "DEBT_BALANCE" -> {
                CurrencyFormatter.format(_lendingResponse.value.netPosition, baseCurr, showPlus = true)
            }
            "SPLIT_BILL" -> "Tap to split bill"
            else -> "Tap to add record"
        }
    }

    /** "1 000 ₸ + 20 $" — amounts without a known rate stay in their own currency. */
    private fun formatConvertedTotal(total: ConvertedTotal): String = buildString {
        append(CurrencyFormatter.format(total.total, total.currency))
        total.unconverted.forEach { (cur, amt) -> append(" + ").append(CurrencyFormatter.format(amt, cur)) }
    }

    companion object {
        private const val RECORDS_PAGE_SIZE = 500
        private const val EXCHANGE_RATES_ID = "current"
        private const val FX_RATE_UNAVAILABLE = "fx_rate_unavailable"
        private const val PREF_DEMO_SEED_CLEANED = "demo_seed_cleanup_v1"
        private const val MAX_PULLED_RECORDS = 20_000
    }

    // ========================================================================
    // Demo Data (developer previews only)
    // ========================================================================

    private data class DemoSeed(
        val categories: List<CategoryDto>,
        val labels: List<LabelDto>,
        val rules: List<CounterpartyRuleDto>,
        val accounts: List<AccountDto>,
        val transactions: List<TransactionDto>,
        val budgets: List<BudgetEnvelopeDto>,
        val obligations: List<PlannedObligationDto>,
        val lending: List<LendingRecordDto>,
        val savings: List<SavingsGoalDto>
    )

    /** Demo data is only available in debug builds. */
    val canLoadDemoData: Boolean
        get() = BuildConfig.DEBUG && !_settingsState.value.isAuthenticated

    /**
     * Fills an empty offline replica with demo rows. Debug builds only and only on an
     * explicit developer action (never automatically, never for a signed-in user): the
     * demo ids do not exist on any server, so records booked against them are rejected.
     */
    fun loadDemoDataForDevelopment() {
        if (!canLoadDemoData) return
        if (database.listIds(KakeiDatabase.COL_ACCOUNTS).isNotEmpty()) {
            postMessage("Demo data is only loaded into an empty offline replica")
            return
        }
        val seed = buildDemoSeed(Instant.now().toString(), LocalDate.now())
        database.runInTransaction {
            seed.categories.forEach { putDto(KakeiDatabase.COL_CATEGORIES, it.id, it.name, it) }
            seed.labels.forEach { putDto(KakeiDatabase.COL_LABELS, it.id, it.name, it) }
            seed.rules.forEach { putDto(KakeiDatabase.COL_RULES, it.id, it.counterpartyPattern, it) }
            seed.accounts.forEach { putDto(KakeiDatabase.COL_ACCOUNTS, it.id, it.name, it) }
            seed.transactions.forEach { putDto(KakeiDatabase.COL_TRANSACTIONS, it.id, it.bookingDate, it) }
            seed.budgets.forEach { putDto(KakeiDatabase.COL_BUDGETS, it.id, it.name, it) }
            seed.obligations.forEach { putDto(KakeiDatabase.COL_OBLIGATIONS, it.id, it.nextDueDate, it) }
            seed.lending.forEach { putDto(KakeiDatabase.COL_LENDING, it.id, it.createdAt, it) }
            seed.savings.forEach { putDto(KakeiDatabase.COL_SAVINGS, it.id, it.title, it) }
        }
        reloadFromLocalDatabase()
    }

    /**
     * Older builds wrote the demo seed into every install. Remove those rows on upgrade,
     * but only rows that are provably untouched: same id and same content as the seed
     * (timestamps and install-relative dates ignored), not referenced by anything the
     * user created, and not targeted by a queued mutation. Seed ids are not UUIDs, so they
     * never exist on the server. Anything the user edited or built upon is kept.
     */
    private fun removeUntouchedLegacyDemoSeed() {
        if (prefs.getBoolean(PREF_DEMO_SEED_CLEANED, false)) return
        val seed = buildDemoSeed("", LocalDate.now())
        val queued = database.listItems<SyncMutationEntity>(KakeiDatabase.COL_MUTATIONS, descending = false)
        fun isQueued(id: String) = queued.any { it.entityId == id || it.payloadJson.contains("\"$id\"") }

        database.runInTransaction {
            val txs = database.listItems<TransactionDto>(KakeiDatabase.COL_TRANSACTIONS)
            val seedTx = seed.transactions.associateBy { it.id }
            val removedTx = txs.filter { tx ->
                val ref = seedTx[tx.id] ?: return@filter false
                tx.copy(bookingDate = ref.bookingDate, createdAt = ref.createdAt, updatedAt = ref.updatedAt) == ref && !isQueued(tx.id)
            }.map { it.id }.toSet()
            removedTx.forEach { database.deleteItem(KakeiDatabase.COL_TRANSACTIONS, it) }
            val remainingTx = txs.filter { it.id !in removedTx }

            val seedOb = seed.obligations.associateBy { it.id }
            val obs = database.listItems<PlannedObligationDto>(KakeiDatabase.COL_OBLIGATIONS)
            val removedOb = obs.filter { ob ->
                val ref = seedOb[ob.id] ?: return@filter false
                ob.copy(nextDueDate = ref.nextDueDate, createdAt = ref.createdAt, updatedAt = ref.updatedAt) == ref && !isQueued(ob.id)
            }.map { it.id }.toSet()
            removedOb.forEach { database.deleteItem(KakeiDatabase.COL_OBLIGATIONS, it) }
            val remainingOb = obs.filter { it.id !in removedOb }

            val seedBudgets = seed.budgets.associateBy { it.id }
            val budgets = database.listItems<BudgetEnvelopeDto>(KakeiDatabase.COL_BUDGETS)
            val removedBudgets = budgets.filter { b ->
                val ref = seedBudgets[b.id] ?: return@filter false
                b.copy(createdAt = ref.createdAt, updatedAt = ref.updatedAt) == ref && !isQueued(b.id)
            }.map { it.id }.toSet()
            removedBudgets.forEach { database.deleteItem(KakeiDatabase.COL_BUDGETS, it) }
            val remainingBudgets = budgets.filter { it.id !in removedBudgets }

            val seedLend = seed.lending.associateBy { it.id }
            database.listItems<LendingRecordDto>(KakeiDatabase.COL_LENDING).filter { l ->
                val ref = seedLend[l.id] ?: return@filter false
                l.copy(
                    dueDate = ref.dueDate,
                    createdAt = ref.createdAt,
                    updatedAt = ref.updatedAt,
                    repayments = l.repayments.map { rp ->
                        ref.repayments.firstOrNull { it.id == rp.id }
                            ?.let { rp.copy(repaymentDate = it.repaymentDate, createdAt = it.createdAt) } ?: rp
                    }
                ) == ref && !isQueued(l.id)
            }.forEach { database.deleteItem(KakeiDatabase.COL_LENDING, it.id) }

            val seedGoals = seed.savings.associateBy { it.id }
            database.listItems<SavingsGoalDto>(KakeiDatabase.COL_SAVINGS).filter { g ->
                val ref = seedGoals[g.id] ?: return@filter false
                g.copy(deadlineDate = ref.deadlineDate, createdAt = ref.createdAt, updatedAt = ref.updatedAt) == ref && !isQueued(g.id)
            }.forEach { database.deleteItem(KakeiDatabase.COL_SAVINGS, it.id) }

            val seedRules = seed.rules.associateBy { it.id }
            val rules = database.listItems<CounterpartyRuleDto>(KakeiDatabase.COL_RULES)
            val removedRules = rules.filter { r ->
                val ref = seedRules[r.id] ?: return@filter false
                r.copy(updatedAt = ref.updatedAt) == ref && !isQueued(r.id)
            }.map { it.id }.toSet()
            removedRules.forEach { database.deleteItem(KakeiDatabase.COL_RULES, it) }
            val remainingRules = rules.filter { it.id !in removedRules }

            val seedAccounts = seed.accounts.associateBy { it.id }
            database.listItems<AccountDto>(KakeiDatabase.COL_ACCOUNTS).filter { acc ->
                val ref = seedAccounts[acc.id] ?: return@filter false
                acc.copy(createdAt = ref.createdAt, updatedAt = ref.updatedAt) == ref &&
                    remainingTx.none { it.accountId == acc.id || it.transferTargetAccountId == acc.id } &&
                    remainingOb.none { it.accountId == acc.id } &&
                    !isQueued(acc.id)
            }.forEach { database.deleteItem(KakeiDatabase.COL_ACCOUNTS, it.id) }

            val seedLabels = seed.labels.associateBy { it.id }
            database.listItems<LabelDto>(KakeiDatabase.COL_LABELS).filter { l ->
                val ref = seedLabels[l.id] ?: return@filter false
                l.copy(createdAt = ref.createdAt) == ref &&
                    remainingTx.none { l.id in it.labelIds } && !isQueued(l.id)
            }.forEach { database.deleteItem(KakeiDatabase.COL_LABELS, it.id) }

            val seedCats = seed.categories.associateBy { it.id }
            database.listItems<CategoryDto>(KakeiDatabase.COL_CATEGORIES).filter { c ->
                val ref = seedCats[c.id] ?: return@filter false
                c.copy(createdAt = ref.createdAt) == ref &&
                    remainingTx.none { tx -> tx.categoryId == c.id || tx.splits.any { it.categoryId == c.id } } &&
                    remainingRules.none { it.categoryId == c.id } &&
                    remainingBudgets.none { c.id in it.categoryIds } &&
                    remainingOb.none { it.categoryId == c.id } &&
                    !isQueued(c.id)
            }.forEach { database.deleteItem(KakeiDatabase.COL_CATEGORIES, it.id) }
        }
        prefs.edit().putBoolean(PREF_DEMO_SEED_CLEANED, true).apply()
    }

    private fun reportDatabaseOpenState() {
        when (database.openState) {
            KakeiDatabase.OpenState.OK -> Unit
            KakeiDatabase.OpenState.MIGRATED_FROM_LEGACY_KEY ->
                postMessage("Local database re-encrypted with a hardware-backed key")
            KakeiDatabase.OpenState.RECOVERED_AFTER_KEY_LOSS ->
                postMessage("Local encrypted data could not be unlocked and was reset; it will be restored from Kakei Server after sign-in")
            KakeiDatabase.OpenState.KEYSTORE_UNAVAILABLE ->
                postMessage("Secure storage is temporarily unavailable: changes made now are not kept after restart. Please restart the app later.")
        }
    }

    private fun buildDemoSeed(now: String, today: LocalDate): DemoSeed {

        val defaultCategories = listOf(
            CategoryDto(
                id = "cat-groceries",
                name = "Продукты и супермаркеты",
                translations = mapOf("ru" to "Продукты и супермаркеты", "en" to "Groceries"),
                iconKey = "shopping-cart",
                colorHex = "#30A46C",
                isSystem = true,
                createdAt = now
            ),
            CategoryDto(
                id = "cat-dining",
                name = "Кафе и рестораны",
                translations = mapOf("ru" to "Кафе и рестораны", "en" to "Dining & Coffee"),
                iconKey = "coffee",
                colorHex = "#F59E0B",
                isSystem = true,
                createdAt = now
            ),
            CategoryDto(
                id = "cat-transport",
                name = "Транспорт и такси",
                translations = mapOf("ru" to "Транспорт и такси", "en" to "Transport & Taxi"),
                iconKey = "car",
                colorHex = "#06B6D4",
                isSystem = true,
                createdAt = now
            ),
            CategoryDto(
                id = "cat-housing",
                name = "Жильё и ЖКХ",
                translations = mapOf("ru" to "Жильё и ЖКХ", "en" to "Housing & Utilities"),
                iconKey = "home",
                colorHex = "#6366F1",
                isSystem = true,
                createdAt = now
            ),
            CategoryDto(
                id = "cat-subscriptions",
                name = "Подписки и цифровые сервисы",
                translations = mapOf("ru" to "Подписки и сервисы", "en" to "Subscriptions"),
                iconKey = "tv",
                colorHex = "#A855F7",
                isSystem = true,
                createdAt = now
            ),
            CategoryDto(
                id = "cat-salary",
                name = "Зарплата и доход",
                translations = mapOf("ru" to "Зарплата и доход", "en" to "Salary & Income"),
                iconKey = "wallet",
                colorHex = "#3DD68C",
                isSystem = true,
                createdAt = now
            )
        )

        val defaultLabels = listOf(
            LabelDto(id = "lbl-family", name = "Семья / Family", colorHex = "#30A46C", createdAt = now),
            LabelDto(id = "lbl-work", name = "Работа / Work", colorHex = "#6366F1", createdAt = now),
            LabelDto(id = "lbl-travel", name = "Отпуск / Travel", colorHex = "#F59E0B", createdAt = now)
        )

        val defaultRules = listOf(
            CounterpartyRuleDto(
                id = "rule-magnum",
                counterpartyPattern = "Magnum",
                matchType = "CONTAINS",
                categoryId = "cat-groceries",
                updatedAt = now
            ),
            CounterpartyRuleDto(
                id = "rule-starbucks",
                counterpartyPattern = "Starbucks",
                matchType = "PREFIX",
                categoryId = "cat-dining",
                updatedAt = now
            ),
            CounterpartyRuleDto(
                id = "rule-yandex",
                counterpartyPattern = "Yandex Go",
                matchType = "CONTAINS",
                categoryId = "cat-transport",
                updatedAt = now
            )
        )

        val defaultAccounts = listOf(
            AccountDto(
                id = "acc-kaspi",
                name = "Kaspi Gold",
                accountType = "CHECKING",
                currency = "KZT",
                initialBalance = BigDecimal("450000.0000"),
                currentBalance = BigDecimal("612400.0000"),
                permissionLevel = "ADMIN",
                createdAt = now,
                updatedAt = now
            ),
            AccountDto(
                id = "acc-freedom-usd",
                name = "Freedom Multi USD",
                accountType = "INVESTMENT",
                currency = "USD",
                initialBalance = BigDecimal("2400.0000"),
                currentBalance = BigDecimal("2400.0000"),
                permissionLevel = "ADMIN",
                createdAt = now,
                updatedAt = now
            ),
            AccountDto(
                id = "acc-cash",
                name = "Наличные / Cash Wallet",
                accountType = "CASH",
                currency = "KZT",
                initialBalance = BigDecimal("65000.0000"),
                currentBalance = BigDecimal("58500.0000"),
                permissionLevel = "ADMIN",
                createdAt = now,
                updatedAt = now
            )
        )

        val seedTransactions = listOf(
            TransactionDto(
                id = "tx-seed-1",
                accountId = "acc-kaspi",
                categoryId = "cat-salary",
                type = "INCOME",
                amount = BigDecimal("850000.0000"),
                currency = "KZT",
                convertedAmount = BigDecimal("850000.0000"),
                bookingDate = "${today.minusDays(5)}T10:00:00Z",
                payee = "Tech Corp Payroll",
                note = "Monthly salary",
                status = "RECONCILED",
                labelIds = listOf("lbl-work"),
                createdAt = now,
                updatedAt = now
            ),
            TransactionDto(
                id = "tx-seed-2",
                accountId = "acc-kaspi",
                categoryId = "cat-groceries",
                type = "EXPENSE",
                amount = BigDecimal("24600.0000"),
                currency = "KZT",
                convertedAmount = BigDecimal("24600.0000"),
                bookingDate = "${today.minusDays(2)}T18:30:00Z",
                payee = "Magnum Cash & Carry",
                note = "Weekly family groceries",
                status = "CLEARED",
                labelIds = listOf("lbl-family"),
                createdAt = now,
                updatedAt = now
            ),
            TransactionDto(
                id = "tx-seed-3",
                accountId = "acc-kaspi",
                categoryId = "cat-dining",
                type = "EXPENSE",
                amount = BigDecimal("4200.0000"),
                currency = "KZT",
                convertedAmount = BigDecimal("4200.0000"),
                bookingDate = "${today.minusDays(1)}T09:15:00Z",
                payee = "Starbucks Coffee",
                note = "Auto-captured via Push Notification",
                status = "CLEARED",
                needsReview = true,
                createdAt = now,
                updatedAt = now
            ),
            TransactionDto(
                id = "tx-seed-4",
                accountId = "acc-cash",
                categoryId = "cat-transport",
                type = "EXPENSE",
                amount = BigDecimal("6500.0000"),
                currency = "KZT",
                convertedAmount = BigDecimal("6500.0000"),
                bookingDate = "${today}T11:20:00Z",
                payee = "Yandex Go",
                note = "Airport transfer",
                status = "CLEARED",
                createdAt = now,
                updatedAt = now
            )
        )

        val seedBudgets = listOf(
            BudgetEnvelopeDto(
                id = "bdg-food",
                name = "Питание и Продукты",
                limitAmount = BigDecimal("180000.00"),
                currency = "KZT",
                periodType = "MONTHLY",
                notifyThreshold = 80,
                categoryIds = listOf("cat-groceries", "cat-dining"),
                createdAt = now,
                updatedAt = now
            ),
            BudgetEnvelopeDto(
                id = "bdg-transport",
                name = "Транспорт и Такси",
                limitAmount = BigDecimal("50000.00"),
                currency = "KZT",
                periodType = "MONTHLY",
                notifyThreshold = 80,
                categoryIds = listOf("cat-transport"),
                createdAt = now,
                updatedAt = now
            )
        )

        val seedObligations = listOf(
            PlannedObligationDto(
                id = "ob-rent",
                accountId = "acc-kaspi",
                categoryId = "cat-housing",
                title = "Аренда и Комм. услуги",
                type = "EXPENSE",
                amount = BigDecimal("165000.00"),
                currency = "KZT",
                frequency = "MONTHLY",
                nextDueDate = today.plusDays(6).toString(),
                payee = "Алсеко / ЖКХ",
                autoPost = true,
                isActive = true,
                createdAt = now,
                updatedAt = now
            ),
            PlannedObligationDto(
                id = "ob-cloud",
                accountId = "acc-kaspi",
                categoryId = "cat-subscriptions",
                title = "Семейные подписки (YouTube + iCloud)",
                type = "EXPENSE",
                amount = BigDecimal("7900.00"),
                currency = "KZT",
                frequency = "MONTHLY",
                nextDueDate = today.plusDays(12).toString(),
                payee = "Google / Apple",
                autoPost = true,
                isActive = true,
                createdAt = now,
                updatedAt = now
            )
        )

        val seedLending = listOf(
            LendingRecordDto(
                id = "lend-1",
                personName = "Тимур",
                type = "LENT",
                principalAmount = BigDecimal("45000.00"),
                settledAmount = BigDecimal("15000.00"),
                outstandingAmount = BigDecimal("30000.00"),
                currency = "KZT",
                dueDate = today.plusDays(10).toString(),
                note = "Совместный ужин и билеты",
                repayments = listOf(
                    LendingRepaymentDto(
                        id = "rep-1",
                        lendingRecordId = "lend-1",
                        amount = BigDecimal("15000.00"),
                        repaymentDate = today.minusDays(2).toString(),
                        note = "Перевод на Kaspi",
                        createdAt = now
                    )
                ),
                createdAt = now,
                updatedAt = now
            ),
            LendingRecordDto(
                id = "lend-2",
                personName = "Алия",
                type = "BORROWED",
                principalAmount = BigDecimal("12000.00"),
                settledAmount = BigDecimal.ZERO,
                outstandingAmount = BigDecimal("12000.00"),
                currency = "KZT",
                dueDate = today.plusDays(5).toString(),
                note = "Подарок коллеге",
                createdAt = now,
                updatedAt = now
            )
        )

        val seedSavings = listOf(
            SavingsGoalDto(
                id = "goal-emergency",
                title = "Резервный фонд (Подушка безопасности)",
                targetAmount = BigDecimal("2000000.00"),
                currentAmount = BigDecimal("1150000.00"),
                remainingAmount = BigDecimal("850000.00"),
                progressPercentage = BigDecimal("57.5"),
                requiredDailyContribution = BigDecimal("7083.33"),
                requiredMonthlyContribution = BigDecimal("212500.00"),
                currency = "KZT",
                deadlineDate = today.plusDays(120).toString(),
                iconKey = "shield",
                colorHex = "#30A46C",
                createdAt = now,
                updatedAt = now
            ),
            SavingsGoalDto(
                id = "goal-trip",
                title = "Поездка в Токио (Япония)",
                targetAmount = BigDecimal("1200000.00"),
                currentAmount = BigDecimal("480000.00"),
                remainingAmount = BigDecimal("720000.00"),
                progressPercentage = BigDecimal("40.0"),
                requiredDailyContribution = BigDecimal("4800.00"),
                requiredMonthlyContribution = BigDecimal("144000.00"),
                currency = "KZT",
                deadlineDate = today.plusDays(150).toString(),
                iconKey = "plane",
                colorHex = "#6366F1",
                createdAt = now,
                updatedAt = now
            )
        )

        return DemoSeed(
            categories = defaultCategories,
            labels = defaultLabels,
            rules = defaultRules,
            accounts = defaultAccounts,
            transactions = seedTransactions,
            budgets = seedBudgets,
            obligations = seedObligations,
            lending = seedLending,
            savings = seedSavings
        )
    }
}
