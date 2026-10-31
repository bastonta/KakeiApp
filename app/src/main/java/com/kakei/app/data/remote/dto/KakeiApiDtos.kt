package com.kakei.app.data.remote.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import java.math.BigDecimal

/**
 * Custom serializer representing arbitrary-precision BigDecimal values as JSON strings
 * (compatible with Kakei backend's `rust_decimal` with `serde-with-str`).
 */
object BigDecimalStringSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("BigDecimal", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        encoder.encodeString(value.toPlainString())
    }

    override fun deserialize(decoder: Decoder): BigDecimal {
        val raw = decoder.decodeString().trim()
        return if (raw.isEmpty()) BigDecimal.ZERO else BigDecimal(raw)
    }
}

typealias DecimalStr = @Serializable(with = BigDecimalStringSerializer::class) BigDecimal

// ============================================================================
// 0. System & Common Contracts (ProblemDetails, Pagination, Version)
// ============================================================================

@Serializable
data class VersionResponse(
    val version: String,
    @SerialName("build_time") val buildTime: String = "",
    val debug: Boolean = false
)

@Serializable
data class ProblemErrorItem(
    val code: String = "",
    val message: String = ""
)

@Serializable
data class ProblemDetails(
    val type: String = "",
    val title: String = "Error",
    val detail: String = "",
    val status: Int = 400,
    val traceId: String? = null,
    val errors: List<ProblemErrorItem> = emptyList()
) {
    fun readableMessage(): String {
        val firstErr = errors.firstOrNull()?.message
        return when {
            !firstErr.isNullOrBlank() -> firstErr
            detail.isNotBlank() -> detail
            else -> title
        }
    }
}

@Serializable
data class Pagination<T>(
    val offset: Int = 0,
    val limit: Int = 50,
    val more: Boolean = false,
    val items: List<T> = emptyList()
)

// ============================================================================
// 1. Identity (/api/identity/*)
// ============================================================================

@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class LoginResponse(
    val userId: String,
    val token: String,
    val need2fa: Boolean = false,
    val hasPasskeys: Boolean = false
)

@Serializable
data class Login2faRequest(
    val userId: String,
    val token: String,
    val code: String,
    val type: String = "code" // "code" | "recovery"
)

@Serializable
data class Login2faResponse(
    val userId: String,
    val token: String
)

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val name: String,
    val defaultCurrency: String? = "KZT"
)

@Serializable
data class RegisterResponse(
    val userId: String,
    val resendAfter: Long = 60
)

@Serializable
data class EmailConfirmRequest(
    val userId: String,
    val code: String
)

@Serializable
data class EmailConfirmResponse(
    val token: String
)

@Serializable
data class EmailConfirmResendRequest(
    val userId: String? = null,
    val email: String? = null
)

@Serializable
data class EmailConfirmResendResponse(
    val userId: String,
    val resendAfter: Long = 60
)

@Serializable
data class PasswordForgotRequest(
    val email: String
)

@Serializable
data class PasswordForgotResponse(
    val email: String,
    val resendAfter: Long = 60
)

@Serializable
data class PasswordResetValidationRequest(
    val email: String,
    val code: String
)

@Serializable
data class PasswordResetValidationResponse(
    val email: String,
    /** Short-lived token that authorises exactly one password-reset call. */
    val token: String,
    val hasPassword: Boolean = true,
    val hasPasskeys: Boolean = false
)

@Serializable
data class PasswordResetRequest(
    val email: String,
    val token: String,
    val password: String
)

@Serializable
data class TokenRefreshResponse(
    val token: String
)

// ============================================================================
// 2. Profile (/api/profile/*)
// ============================================================================

@Serializable
data class ProfileInfoResponse(
    val userId: String,
    val email: String,
    val name: String,
    val defaultCurrency: String = "KZT",
    val language: String = "ru",
    val twoFactorEnabled: Boolean = false,
    val emailConfirmed: Boolean = true,
    val hasPassword: Boolean = true,
    val passkeyCount: Long = 0
)

@Serializable
data class ProfileInfoUpdateRequest(
    val name: String,
    val defaultCurrency: String? = null,
    val language: String? = null
)

/** Market rate of one currency, relative to USD (`GET /profile/exchange-rates`). */
@Serializable
data class ExchangeRateDto(
    val currency: String,
    val unitsPerUsd: DecimalStr,
    val rateDate: String = "",
    val source: String = ""
)

/** User-defined rate for a pair: [rate] target units per 1 [fromCurrency] unit. */
@Serializable
data class ExchangeRateOverrideDto(
    val fromCurrency: String,
    val toCurrency: String,
    val rate: DecimalStr,
    val updatedAt: String = ""
)

@Serializable
data class ExchangeRatesResponse(
    val baseCurrency: String = "",
    val rates: List<ExchangeRateDto> = emptyList(),
    val overrides: List<ExchangeRateOverrideDto> = emptyList()
)

@Serializable
data class UpsertExchangeRateOverrideRequest(
    val fromCurrency: String,
    val toCurrency: String,
    val rate: DecimalStr
)

@Serializable
data class PasswordChangeRequest(
    val oldPassword: String,
    val newPassword: String
)

@Serializable
data class TfaEnableResponse(
    val url: String,
    val secret: String
)

@Serializable
data class TfaConfirmRequest(
    val code: String
)

@Serializable
data class TfaConfirmResponse(
    val recoveryCodes: List<String> = emptyList()
)

/**
 * `DELETE /profile/account` requires re-authentication: either [password], or a passkey
 * assertion ([passkeyResponse]) for the ceremony from `POST /profile/account/delete-challenge`.
 */
@Serializable
data class AccountDeleteRequest(
    val password: String? = null,
    val ceremonyId: String? = null,
    val passkeyResponse: JsonElement? = null
)

@Serializable
data class TfaDisableRequest(
    val password: String
)

@Serializable
data class UserPasskeyItem(
    val id: String,
    val name: String,
    val createdAt: String,
    val lastUsedAt: String? = null
)

// ============================================================================
// 3. Accounts (/api/accounts/*)
// ============================================================================

@Serializable
data class AccountDto(
    val id: String,
    val userId: String = "local-user",
    val name: String,
    val accountType: String, // "CASH" | "CHECKING" | "CREDIT" | "DEBT" | "INVESTMENT"
    val currency: String,
    val initialBalance: DecimalStr = BigDecimal.ZERO,
    val currentBalance: DecimalStr = BigDecimal.ZERO,
    val isExcludedFromStats: Boolean = false,
    val isArchived: Boolean = false,
    val syncRevision: Long = 1L,
    val permissionLevel: String = "ADMIN",
    val createdAt: String = "",
    val updatedAt: String = ""
)

@Serializable
data class AccountsListResponse(
    val items: List<AccountDto> = emptyList()
)

@Serializable
data class CreateAccountRequest(
    val name: String,
    val accountType: String,
    val currency: String,
    val initialBalance: DecimalStr,
    val isExcludedFromStats: Boolean = false
)

@Serializable
data class AccountMutationItem(
    val id: String,
    val name: String? = null,
    val accountType: String? = null,
    val isExcludedFromStats: Boolean? = null,
    val isArchived: Boolean? = null,
    /** New opening balance; the server shifts the current balance by the same difference. */
    val initialBalance: DecimalStr? = null,
    val expectedSyncRevision: Long? = null
)

@Serializable
data class BatchUpdateAccountsRequest(
    val items: List<AccountMutationItem>
)

@Serializable
data class AccountBatchItemResult(
    val id: String,
    val status: Int,
    val account: AccountDto? = null,
    val serverRevision: Long? = null,
    val error: String? = null
)

@Serializable
data class BatchUpdateAccountsResponse(
    val results: List<AccountBatchItemResult> = emptyList()
)

// ============================================================================
// 4. Categories, Labels & Smart Rules (/api/categories/*)
// ============================================================================

@Serializable
data class CategoryDto(
    val id: String,
    val userId: String? = null,
    val parentId: String? = null,
    val name: String,
    val translations: Map<String, String> = emptyMap(),
    val iconKey: String = "tag",
    val colorHex: String = "#30A46C",
    val isSystem: Boolean = false,
    val createdAt: String = ""
) {
    fun localizedName(langCode: String): String =
        translations[langCode]?.takeIf { it.isNotBlank() } ?: name
}

@Serializable
data class CategoriesListResponse(
    val items: List<CategoryDto> = emptyList()
)

@Serializable
data class UpsertCategoryRequest(
    val parentId: String? = null,
    val name: String? = null,
    val translations: Map<String, String>? = null,
    val iconKey: String,
    val colorHex: String
)

@Serializable
data class LabelDto(
    val id: String,
    val name: String,
    val colorHex: String = "#6366F1",
    val createdAt: String = ""
)

@Serializable
data class LabelsListResponse(
    val items: List<LabelDto> = emptyList()
)

@Serializable
data class CreateLabelRequest(
    val name: String,
    val colorHex: String? = "#6366F1"
)

@Serializable
data class CounterpartyRuleDto(
    val id: String,
    val counterpartyPattern: String,
    val matchType: String = "CONTAINS", // "EXACT" | "PREFIX" | "CONTAINS"
    val categoryId: String,
    val updatedAt: String = ""
)

@Serializable
data class RulesListResponse(
    val items: List<CounterpartyRuleDto> = emptyList()
)

@Serializable
data class CreateRuleRequest(
    val counterpartyPattern: String,
    val matchType: String? = "CONTAINS",
    val categoryId: String
)

// ============================================================================
// 5. Transactions / Records (/api/records/*)
// ============================================================================

@Serializable
data class SplitDto(
    val id: String = "",
    val categoryId: String? = null,
    val amount: DecimalStr,
    val note: String? = null
)

@Serializable
data class SplitItemInput(
    val categoryId: String? = null,
    val amount: DecimalStr,
    val note: String? = null
)

@Serializable
data class TransactionDto(
    val id: String,
    val accountId: String,
    val categoryId: String? = null,
    val type: String, // "EXPENSE" | "INCOME" | "TRANSFER"
    val amount: DecimalStr,
    val currency: String,
    val convertedAmount: DecimalStr = BigDecimal.ZERO,
    val bookingDate: String,
    val payee: String? = null,
    val note: String? = null,
    val status: String = "CLEARED", // "UNCLEARED" | "CLEARED" | "RECONCILED"
    val needsReview: Boolean = false,
    val transferPairId: String? = null,
    val transferTargetAccountId: String? = null,
    /** true on the mirror (credit) leg of a transfer */
    val isTransferCredit: Boolean = false,
    val externalId: String? = null,
    val hashDedup: String? = null,
    val syncRevision: Long = 1L,
    val splits: List<SplitDto> = emptyList(),
    val labelIds: List<String> = emptyList(),
    val createdAt: String = "",
    val updatedAt: String = ""
)

@Serializable
data class CreateRecordItem(
    /** Client-generated UUID used as the server record id (idempotent create). */
    val clientId: String? = null,
    val accountId: String,
    val categoryId: String? = null,
    val type: String,
    val amount: DecimalStr,
    val bookingDate: String? = null,
    val payee: String? = null,
    val note: String? = null,
    val status: String? = "CLEARED",
    val transferTargetAccountId: String? = null,
    /** Amount credited to the transfer target account, in its currency (wins over [exchangeRate]). */
    val targetAmount: DecimalStr? = null,
    /** Target units per 1 source unit for cross-currency transfers. */
    val exchangeRate: DecimalStr? = null,
    val externalId: String? = null,
    val splits: List<SplitItemInput> = emptyList(),
    val labelIds: List<String> = emptyList(),
    val skipIfDuplicateHash: Boolean = true
)

@Serializable
data class BatchCreateRecordsRequest(
    val items: List<CreateRecordItem>
)

@Serializable
data class RecordBatchCreateItemResult(
    val index: Int,
    val clientId: String? = null,
    val status: Int,
    val record: TransactionDto? = null,
    val duplicateHash: String? = null,
    val error: String? = null
)

@Serializable
data class BatchCreateRecordsResponse(
    val results: List<RecordBatchCreateItemResult> = emptyList()
)

@Serializable
data class UpdateRecordItem(
    val id: String,
    val categoryId: String? = null,
    val amount: DecimalStr? = null,
    val bookingDate: String? = null,
    val payee: String? = null,
    val note: String? = null,
    val status: String? = null,
    val needsReview: Boolean? = null,
    val splits: List<SplitItemInput>? = null,
    val labelIds: List<String>? = null,
    /**
     * Transfer legs only (`PATCH /records`): new amount of the *paired* leg, in that leg's
     * currency (editing the debit leg → the credited amount). When omitted while [amount]
     * changes, the server keeps the pair's previous implied rate.
     */
    val targetAmount: DecimalStr? = null,
    val expectedSyncRevision: Long? = null
)

@Serializable
data class BatchUpdateRecordsRequest(
    val items: List<UpdateRecordItem>
)

@Serializable
data class BatchDeleteRecordsRequest(
    val ids: List<String>
)

@Serializable
data class RecordBatchMutateItemResult(
    val id: String,
    val status: Int,
    val record: TransactionDto? = null,
    val serverRevision: Long? = null,
    val error: String? = null
)

@Serializable
data class BatchMutateRecordsResponse(
    val results: List<RecordBatchMutateItemResult> = emptyList()
)

@Serializable
data class ConfirmReviewRequest(
    val categoryId: String,
    val createSmartRule: Boolean = true
)

// ============================================================================
// 6. Budgets (/api/budgets/*)
// ============================================================================

@Serializable
data class BudgetEnvelopeDto(
    val id: String,
    val userId: String = "local-user",
    val name: String,
    val limitAmount: DecimalStr,
    val spentAmount: DecimalStr = BigDecimal.ZERO,
    val remainingAmount: DecimalStr = BigDecimal.ZERO,
    val usagePercentage: DecimalStr = BigDecimal.ZERO,
    val dailyBurnRateLimit: DecimalStr = BigDecimal.ZERO,
    val remainingDaysInPeriod: Long = 15,
    val currency: String,
    val periodType: String = "MONTHLY", // "WEEKLY" | "MONTHLY" | "YEARLY" | "ONE_OFF"
    val periodStart: String = "",
    val periodEnd: String = "",
    val startDate: String = "",
    val endDate: String? = null,
    val notifyThreshold: Int = 80,
    val alertLevel: String = "NORMAL", // "NORMAL" | "WARNING_75" | "WARNING_90" | "EXCEEDED_100"
    val thresholdTriggered: Boolean = false,
    val categoryIds: List<String> = emptyList(),
    val createdAt: String = "",
    val updatedAt: String = ""
)

@Serializable
data class BudgetsListResponse(
    val items: List<BudgetEnvelopeDto> = emptyList()
)

@Serializable
data class UpsertBudgetRequest(
    val name: String,
    val limitAmount: DecimalStr,
    val currency: String,
    val periodType: String,
    val startDate: String? = null,
    val endDate: String? = null,
    val notifyThreshold: Int? = 80,
    val categoryIds: List<String> = emptyList()
)

// ============================================================================
// 7. Planning & Cash Flow Forecast (/api/planning/*)
// ============================================================================

@Serializable
data class PlannedObligationDto(
    val id: String,
    val userId: String = "local-user",
    val accountId: String,
    val targetAccountId: String? = null,
    val categoryId: String? = null,
    val title: String,
    val type: String = "EXPENSE", // "EXPENSE" | "INCOME" | "TRANSFER"
    val amount: DecimalStr,
    val currency: String,
    val frequency: String = "MONTHLY", // "DAILY" | "WEEKLY" | "MONTHLY" | "YEARLY" | "ONE_OFF"
    val nextDueDate: String,
    val endDate: String? = null,
    val payee: String? = null,
    val note: String? = null,
    val autoPost: Boolean = true,
    val isActive: Boolean = true,
    val lastPostedAt: String? = null,
    val createdAt: String = "",
    val updatedAt: String = ""
)

@Serializable
data class ObligationsListResponse(
    val items: List<PlannedObligationDto> = emptyList()
)

@Serializable
data class UpsertObligationRequest(
    val accountId: String,
    val targetAccountId: String? = null,
    val categoryId: String? = null,
    val title: String,
    val type: String,
    val amount: DecimalStr,
    val currency: String,
    val frequency: String,
    val nextDueDate: String,
    val endDate: String? = null,
    val payee: String? = null,
    val note: String? = null,
    val autoPost: Boolean? = true,
    val isActive: Boolean? = true
)

@Serializable
data class ExecuteObligationResponse(
    val obligation: PlannedObligationDto,
    val postedRecord: TransactionDto
)

@Serializable
data class VirtualProjectionDto(
    val obligationId: String,
    val title: String,
    val accountId: String,
    val type: String,
    val amount: DecimalStr,
    val convertedAmount: DecimalStr,
    val currency: String,
    val projectedDate: String
)

@Serializable
data class DailyForecastPointDto(
    val date: String,
    val plannedIncome: DecimalStr,
    val plannedExpense: DecimalStr,
    val projectedBalance: DecimalStr
)

@Serializable
data class CashFlowForecastDto(
    val baseCurrency: String,
    val currentLiquidBalance: DecimalStr,
    val plannedIncomeTotal: DecimalStr,
    val plannedExpenseTotal: DecimalStr,
    val forecastedEndBalance: DecimalStr,
    val horizonDays: Long = 30,
    val obligationToLiquidityRatio: DecimalStr = BigDecimal.ZERO,
    val liquidityDeficitAlert: Boolean = false,
    val projections: List<VirtualProjectionDto> = emptyList(),
    val dailyTimeline: List<DailyForecastPointDto> = emptyList()
)

// ============================================================================
// 8. Lending, Split Expense & Settle All (/api/lending/*)
// ============================================================================

@Serializable
data class LendingRepaymentDto(
    val id: String,
    val lendingRecordId: String,
    val amount: DecimalStr,
    val repaymentDate: String,
    val note: String? = null,
    val linkedTransactionId: String? = null,
    val createdAt: String = ""
)

@Serializable
data class LendingRecordDto(
    val id: String,
    val userId: String = "local-user",
    val personName: String,
    val type: String, // "LENT" | "BORROWED"
    val principalAmount: DecimalStr,
    val settledAmount: DecimalStr = BigDecimal.ZERO,
    val outstandingAmount: DecimalStr = BigDecimal.ZERO,
    val currency: String,
    val dueDate: String? = null,
    val isSettled: Boolean = false,
    val isForgiven: Boolean = false,
    val note: String? = null,
    val linkedTransactionId: String? = null,
    val repayments: List<LendingRepaymentDto> = emptyList(),
    val createdAt: String = "",
    val updatedAt: String = ""
)

@Serializable
data class LendingListResponse(
    val baseCurrency: String = "KZT",
    val totalOwedToMe: DecimalStr = BigDecimal.ZERO,
    val totalOwedByMe: DecimalStr = BigDecimal.ZERO,
    val netPosition: DecimalStr = BigDecimal.ZERO,
    val items: List<LendingRecordDto> = emptyList()
)

@Serializable
data class CreateLendingRequest(
    val personName: String,
    val type: String,
    val principalAmount: DecimalStr,
    val currency: String,
    val dueDate: String? = null,
    val note: String? = null,
    val linkedTransactionId: String? = null
)

@Serializable
data class CreateRepaymentRequest(
    val amount: DecimalStr,
    val repaymentDate: String? = null,
    val note: String? = null,
    val linkedTransactionId: String? = null
)

@Serializable
data class SplitParticipantInput(
    val personName: String,
    val shareAmount: DecimalStr? = null
)

@Serializable
data class SplitExpenseRequest(
    val totalAmount: DecimalStr,
    val currency: String,
    val includeSelf: Boolean? = true,
    val dueDate: String? = null,
    val note: String? = null,
    val linkedTransactionId: String? = null,
    val participants: List<SplitParticipantInput>
)

@Serializable
data class SplitExpenseResponse(
    val createdReceivables: List<LendingRecordDto> = emptyList()
)

@Serializable
data class SettleAllRequest(
    val personName: String
)

@Serializable
data class SettleAllSummaryDto(
    val personName: String,
    val currency: String,
    val totalLentSettled: DecimalStr,
    val totalBorrowedSettled: DecimalStr,
    val netTransferAmount: DecimalStr,
    val netDirection: String,
    val settledRecordIds: List<String> = emptyList()
)

// ============================================================================
// 9. Savings Goals (/api/savings/*)
// ============================================================================

@Serializable
data class SavingsContributionDto(
    val id: String,
    val goalId: String,
    val amount: DecimalStr,
    val contributionDate: String,
    val linkedTransactionId: String? = null,
    val createdAt: String = ""
)

@Serializable
data class SavingsGoalDto(
    val id: String,
    val userId: String = "local-user",
    val title: String,
    val targetAmount: DecimalStr,
    val currentAmount: DecimalStr = BigDecimal.ZERO,
    val remainingAmount: DecimalStr = BigDecimal.ZERO,
    val progressPercentage: DecimalStr = BigDecimal.ZERO,
    val requiredMonthlyContribution: DecimalStr? = null,
    val requiredDailyContribution: DecimalStr? = null,
    val currency: String,
    val deadlineDate: String? = null,
    val iconKey: String = "piggy-bank",
    val colorHex: String = "#30A46C",
    val contributions: List<SavingsContributionDto> = emptyList(),
    val createdAt: String = "",
    val updatedAt: String = ""
)

@Serializable
data class SavingsListResponse(
    val items: List<SavingsGoalDto> = emptyList()
)

@Serializable
data class UpsertSavingsGoalRequest(
    val title: String,
    val targetAmount: DecimalStr,
    val currency: String,
    val deadlineDate: String? = null,
    val iconKey: String? = "piggy-bank",
    val colorHex: String? = "#30A46C"
)

@Serializable
data class AddContributionRequest(
    val amount: DecimalStr,
    val contributionDate: String? = null,
    val linkedTransactionId: String? = null
)

// ============================================================================
// 10. Exchange Import & Export (/api/exchange/*)
// ============================================================================

@Serializable
data class ColumnMapping(
    val dateColumn: Int? = 0,
    val amountColumn: Int? = 1,
    val payeeColumn: Int? = 2,
    val noteColumn: Int? = 3,
    val externalIdColumn: Int? = null
)

@Serializable
data class ImportStatementRequest(
    val accountId: String,
    val format: String? = "csv", // "csv" | "ofx" | "tsv"
    val delimiter: String? = ",",
    val hasHeader: Boolean? = true,
    val mapping: ColumnMapping? = null,
    val content: String
)

@Serializable
data class ImportStatementResponse(
    val totalParsed: Int = 0,
    val importedCount: Int = 0,
    val duplicateSkippedCount: Int = 0,
    val failedCount: Int = 0
)

// ============================================================================
// 11. Group Sharing (/api/groups/*)
// ============================================================================

@Serializable
data class GroupMemberDto(
    val id: String,
    val ownerId: String = "local-user",
    val memberUserId: String,
    val memberName: String,
    val memberEmail: String,
    val accountId: String,
    val accountName: String,
    val permissionLevel: String, // "ADMIN" | "TRACK_READ" | "READ_ONLY" | "NONE"
    val createdAt: String = ""
)

@Serializable
data class GroupMembersListResponse(
    val items: List<GroupMemberDto> = emptyList()
)

@Serializable
data class UpsertGroupMemberRequest(
    val memberUserId: String? = null,
    val memberEmail: String? = null,
    val permissionLevel: String
)

@Serializable
data class SharedWithMeResponse(
    val items: List<GroupMemberDto> = emptyList()
)

// ============================================================================
// 12. Analytics (/api/analytics/*)
// ============================================================================

@Serializable
data class FinancialHealthScoreDto(
    val overallScore: Int,
    val grade: String,
    val savingsRatePercentage: DecimalStr,
    val savingsRateScore: Int,
    val budgetCompliancePercentage: DecimalStr,
    val budgetComplianceScore: Int,
    val debtToIncomePercentage: DecimalStr,
    val debtToIncomeScore: Int,
    val periodIncome: DecimalStr,
    val periodExpense: DecimalStr,
    val totalOutstandingDebt: DecimalStr,
    val currency: String,
    val recommendations: List<String> = emptyList()
)

@Serializable
data class HeatmapDayCellDto(
    val date: String,
    val totalSpent: DecimalStr,
    val transactionCount: Long,
    val intensityLevel: Int // 0..4
)

@Serializable
data class HeatmapMonthSummaryDto(
    val month: Int, // 1..12
    val totalSpent: DecimalStr,
    val transactionCount: Long
)

@Serializable
data class SpendingHeatmapsDto(
    val year: Int,
    val month: Int? = null,
    val currency: String,
    val calendarDays: List<HeatmapDayCellDto> = emptyList(),
    val monthlySummaries: List<HeatmapMonthSummaryDto> = emptyList()
)

@Serializable
data class NoSpendStreakDto(
    val currentStreakDays: Long,
    val longestStreakDays: Long,
    val totalNoSpendDaysLast30: Long,
    val lastSpendDate: String? = null
)

@Serializable
data class TopPayeeEntryDto(
    val payee: String,
    val totalSpent: DecimalStr,
    val transactionCount: Long
)

@Serializable
data class InsightsSummaryDto(
    val currency: String,
    val currentMonthSpent: DecimalStr,
    val previousMonthSpent: DecimalStr,
    val monthOverMonthDeltaPercentage: DecimalStr,
    val monthEndProjectedSpend: DecimalStr,
    val smallSpendsTotal: DecimalStr,
    val smallSpendsCount: Long,
    val weekdaySpendTotal: DecimalStr,
    val weekendSpendTotal: DecimalStr,
    val topPayees: List<TopPayeeEntryDto> = emptyList()
)
