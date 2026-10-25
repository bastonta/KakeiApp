# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Security
- Auto Backup is disabled and the encrypted database, Keystore-wrapped key and session tokens are excluded from cloud backup and device transfer.
- The hard-coded SQLCipher fallback passphrase is gone. If the wrapped key cannot be unwrapped (data restored onto another device), the unreadable local replica is discarded and a fresh key is generated instead of crash-looping.
- Access JWT and refresh cookie are stored in `SecurePrefs` (AES-256-GCM, hardware-backed Keystore) instead of plaintext SharedPreferences.

### Fixed
- Refresh token survives process restarts; token refresh is single-flight; when the refresh fails the session is cleared and the sign-in screen is shown instead of silently failing every request.
- Outbox drain inspects per-item HTTP 207 statuses: applied items are deleted, server-rejected items are recorded in a visible "sync errors" list, network failures keep the entry and stop the drain so ordering is preserved.
- Every local write (accounts, categories, labels, rules, budgets, obligations, lending, savings, group sharing, profile, review, obligation execution) now goes through the Outbox instead of fire-and-forget API calls, so offline changes are no longer lost.
- Server pulls merge into the local replica (entities with pending mutations are preserved) instead of wiping collections; the ledger pull walks every page instead of the first 300 records.
- Records are created with a client id that the server adopts, so queued updates/deletes reference an id the server knows; client-created entities are remapped to server ids across all local references once acknowledged.
- Demo seed data is created only in explicit offline mode, never for signed-in users; bank SMS can no longer be routed into a fabricated account.
- Deleting a transfer removes both legs locally and reverses both balances using the leg direction.
- Executing an obligation while signed in no longer posts a local record in addition to the server-posted one.

### Added
- Standardized project guidelines and AI assistant rules in `AGENTS.md`.
- Comprehensive project documentation in `README.md`.
- Standardized release automation skill in `.agents/skills/release`.

## [1.0.0] - 2026-10-18

### Added
- Initial implementation of the offline-first Kakei native Android client.
- SQLCipher AES-256 encrypted local SQLite database with AndroidKeyStore key management (`KakeiDatabase`).
- Autonomous bank transaction capture: regex parser for SMS and Push notifications (`BankSmsPatterns`) supporting Kaspi, Halyk, Freedom, Forte, Jusan, BCC, Sber, T-Bank, Revolut, Wise, and Google Pay.
- 5-minute sliding notification deduplication across SMS and Push channels (`NotificationDeduplicationHelper`).
- Deterministic merchant rule matcher with longest-prefix priority (`SmartMerchantMatcher`).
- Instant category overlay window (`InstantPopupService`) with `SYSTEM_ALERT_WINDOW`.
- Quick Settings tile (`QuickAddTileService`) for instant transaction entry.
- 7 interactive home screen desktop widgets (`BudgetRingWidget`, `GoalsRingWidget`, `TodaySpendWidget`, `DebtBalanceWidget`, `QuickAddWidget`, `SplitBillWidget`, `NetWorthWidget`).
- Periodic background workers via WorkManager: `MidnightWidgetResetWorker`, `NotificationListenerWatchdogWorker`, and `SyncWorker`.
- Modified single-entry ledger supporting `EXPENSE`, `INCOME`, `TRANSFER` with counterflow pairing (`transferPairId`), splits (`splits`), and clearing status lifecycle (`UNCLEARED`, `CLEARED`, `RECONCILED`).
- Composite SHA-256 transaction deduplication hash calculation (`HashDedupCalculator`).
- Envelope budgeting engine with dynamic daily burn rates and multi-threshold alerts (`WARNING_75`, `WARNING_90`, `CRITICAL_100`).
- Predictive cash flow runway forecasting for 7 to 30 days horizon with liquidity deficit warnings.
- Financial Health Score (0-100) and No-Spend Streak calculations.
- Universal bank statement import gateway supporting CSV and structured OFX 2.2 formats.
- Encrypted full-database snapshot exports using AES-256-GCM and PBKDF2 (`BackupCryptoHelper`).
- Trilingual localization supporting English, Russian, and Kazakh (`AppStrings`).
- Biometric authentication gate using `androidx.biometric.BiometricPrompt`.
