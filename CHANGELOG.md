# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.3.0] - 2026-10-31

### Added
- Passkey sign-in to Kakei Cloud via the Android Credential Manager (`POST /identity/passkey-login-start` / `-finish`): with an email the challenge is limited to that account's keys, without one the system sheet offers every Kakei passkey on the device.
- Profile → Passkeys: create a passkey on this device (`POST /profile/passkeys/register-start` / `-finish`) and remove registered ones.
- The account edit dialog has the "Exclude from Net Worth stats" checkbox; previously it could only be set when creating an account.

### Changed
- The sign-in screen no longer prefills a demo server URL (`10.0.2.2`), email or name; sign-in buttons stay disabled until a server URL is entered.
- After 2FA or email confirmation the account email is resolved from the server profile instead of saving a placeholder.

### Fixed
- The selected app language reverted on every sync because the change only updated local preferences. It is now queued as a profile update, the profile pull keeps local values while that update is pending, and a language picked on the sign-in screen is pushed to the server after login.

## [1.2.0] - 2026-10-31

### Added
- Separate Transactions page, opened from "All" or the search button on Home: search by payee/note, period presets (current month by default, previous month, 3 months, current year, all time) or a custom date range, and filters by type (expense / income / transfer), account, categories (a group includes its subcategories, splits match too), merchant and the review queue, with income/expense totals for the selection. Every record is its own lazy list item, so long periods scroll smoothly; Home now only shows the latest records.
- Long-press an account card on Home to rename it or set its current balance, either via a "Balance adjustment" income/expense record or directly on the account (shifts the opening balance, synced via `PATCH /accounts` `initialBalance`, no effect on statistics).
- Two-level category picker (record editor, capture card, review dialog, budgets, smart rules): top-level groups first, subcategories of the opened group in a second row with a "Whole group" chip.
- Icons for every category `iconKey` seeded by the backend, including the extended tree from backend migration 11 (Car, Digital Services, Family, Education, Gifts & Charity, Finance, Work & Business).

### Changed
- `SmartMerchantMatcher` routes keywords to the backend's system category ids (mirroring the server keyword table, with the record type deciding income vs expense rules) and matches keywords at word starts; local name heuristics remain as fallback.
- Analytics category donut rolls subcategories up into their top-level group.

### Fixed
- Expanding the ledger with "All" froze the app (ANR) on long histories: the whole history was composed in a single list item; each day is now its own lazy item.
- Budgets on a parent category counted only records filed directly under it; like the server, they now include the direct subcategories (also in the health score).
- New records, rules, planned payments and unrecognized bank captures no longer default to the alphabetically first category (now "AI & Cloud"); they stay uncategorized until the user picks one.

## [1.1.1] - 2026-10-30

### Fixed
- Release builds crashed on launch with `IllegalAccessError` in `androidx.startup.InitializationProvider`: the experimental R8 `packageScope` (androidx/kotlin/kotlinx only) produced a class extending a package-private `kotlin.text` class across packages. R8 now optimizes the whole app.

## [1.1.0] - 2026-10-30

### Added
- Biometric app lock: when enabled, `MainActivity` shows `AppLockScreen` and a `BiometricPrompt` (biometrics or device credential) on cold start and after the app has been in the background longer than the relock timeout.
- Configurable relock timeout in Profile under the biometric unlock toggle (Immediately, 30 sec, 1, 5, 15 or 30 min; default 1 min).
- Profile lists the passkeys registered on the Kakei account.

### Changed
- Toolchain and libraries upgraded: AGP 9.5.0, Kotlin 2.4.21, Compose BOM 2026.09.00, core-ktx 1.19.1, lifecycle 2.11.0, activity-compose 1.13.0, kotlinx-serialization 1.12.0, coroutines 1.11.0, WorkManager 2.12.0, sqlite-ktx 2.7.1, SQLCipher 4.20.0, OkHttp 5.5.1.
- The biometric lock toggle refuses to turn on when no biometric or screen lock is set up on the device.
- `SharedPreferences` writes use the KTX `edit { }` extension.

### Removed
- Unused repository actions (`refreshExchangeRates`, `toggleArchiveAccount`, `deleteCategory`, `createLabel`, `deleteLabel`, `deleteSmartRule`) and other dead helpers.

## [1.0.0] - 2026-10-30

### Added
- Initial implementation of the offline-first Kakei native Android client.
- SQLCipher AES-256 encrypted local SQLite database with AndroidKeyStore key management (`KakeiDatabase`).
- Autonomous bank transaction capture: regex parser for SMS and Push notifications (`BankSmsPatterns`) supporting Kaspi, Halyk, Freedom, Forte, Jusan, BCC, Sber, T-Bank, Revolut, Wise, and Google Pay.
- 5-minute sliding notification deduplication across SMS and Push channels (`NotificationDeduplicationHelper`).
- Deterministic merchant rule matcher with longest-prefix priority (`SmartMerchantMatcher`).
- `InstantPopupService` declared with `SYSTEM_ALERT_WINDOW` as a placeholder (stops itself immediately; no overlay is shown).
- Quick Settings tile (`QuickAddTileService`) for instant transaction entry.
- 7 home screen widget providers (`BudgetRingWidget`, `GoalsRingWidget`, `TodaySpendWidget`, `DebtBalanceWidget`, `QuickAddWidget`, `SplitBillWidget`, `NetWorthWidget`) sharing one text-card layout.
- Periodic background workers via WorkManager: `MidnightWidgetResetWorker` (every 12 h), `NotificationListenerWatchdogWorker` (every 15 min), and `SyncWorker` (every 30 min, no network constraint).
- Modified single-entry ledger supporting `EXPENSE`, `INCOME`, `TRANSFER` with counterflow pairing (`transferPairId`), splits (`splits`), and clearing status lifecycle (`UNCLEARED`, `CLEARED`, `RECONCILED`).
- Composite SHA-256 transaction deduplication hash calculation (`HashDedupCalculator`).
- Envelope budgeting engine with dynamic daily burn rates and multi-threshold alerts (`WARNING_75`, `WARNING_90`, `CRITICAL_100`).
- Predictive cash flow runway forecasting for 7 to 30 days horizon with liquidity deficit warnings.
- Financial Health Score (0-100) and No-Spend Streak calculations.
- Universal bank statement import gateway supporting CSV and structured OFX 2.2 formats.
- Encrypted full-database snapshot exports using AES-256-GCM and PBKDF2 (`BackupCryptoHelper`).
- Localization in Russian and English (`AppStrings`).
- Biometric authentication gate using `androidx.biometric.BiometricPrompt`.
- Kakei 2.0 redesign: dark/light theme tokens, type scale and shapes, shared design-system components (buttons, chips, segmented control, rings, progress bars, settings and transaction rows), bottom navigation with four tabs around a raised quick-add button, redesigned Auth, Home, quick-add calculator sheet, Analytics, Debts, Savings and Profile screens, new adaptive app icon, branded splash and widget background.
- GitHub Actions release workflow (`release.yml`): on `v*` tags it runs the unit tests, builds a release APK signed from repository secrets and publishes it to a GitHub release with notes from `CHANGELOG.md`.
- Release signing config in `app/build.gradle.kts`, read from an untracked `keystore.properties`.
- Auto-capture settings: runtime SMS / notification permission requests, shortcuts to notification-listener and overlay settings with live status, default capture account, pending-capture review.
- Exchange-rate settings: effective rates and manual per-pair overrides (set / reset).
- Standardized project guidelines and AI assistant rules in `AGENTS.md`.
- Comprehensive project documentation in `README.md`.
- Standardized release automation skill in `.agents/skills/release`.

### Changed
- `keepRules/rules.keep` documents why release R8 is safe (optimization scoped to androidx/kotlin/kotlinx, consumer rules from SQLCipher and kotlinx.serialization) and adds explicit keep rules for SQLCipher JNI and the app's generated serializers.
- Documentation (`README.md`, `AGENTS.md`, this changelog) corrected to match the code: there is no `/api/v1/sync/batch` endpoint and no Last-Write-Wins (the Outbox sends one entry per request to the regular endpoints and treats `409` as a revision conflict), `InstantPopupService` shows no overlay, the 7 widgets are identical text cards, `MidnightWidgetResetWorker` runs every 12 hours, `SyncWorker` has no network constraint, and the UI is localized in Russian and English only.

### Removed
- Unused dependencies `navigation-compose`, `datastore-preferences` and `okhttp logging-interceptor`.

### Fixed
- Russian UI no longer mixes in English: analytics, planning, lending, goals and profile labels, dialog fields, account types, clearing statuses, budget periods/alerts and sync/status snackbars are localized (`AppStrings.pick` and code-label helpers).
- Home: the debug `rev #N` label is gone from account cards, the account name no longer pushes the type label off the card, and transaction cards drop the empty delete row and no longer repeat the category when there is no payee.
- Truncated buttons ("Разделить счёт", "Синхронизировать", "Выйти из аккаунта", theme "Системная") now fit.
- Budgets, planned payments, debts and goals show an empty-state hint instead of a blank screen.
- Exchange rates are shown with two decimals (four below 1) and the same separators as amounts (`CurrencyFormatter.formatRate`).
- Split bill: shares are allocated in the currency's minor units and add up to the total exactly (100.00 / 3 → 33.34 + 33.33 + 33.33); lending amounts use the currency's own precision (JPY 0, KWD 3) instead of always 2 decimals.
- Local store: rows whose payload no longer decodes are reported (`KakeiDatabase` log + `undecodableRowCount`) and kept on disk instead of being swallowed silently; the schema has a version constant and an ordered migration registry instead of an empty `onUpgrade`.
- Profile diagnostics no longer probe system services on the main thread during composition; the check runs on `Dispatchers.IO` and refreshes when the screen resumes.
- `deleteAccount` sends the re-authentication body (`password`, or `ceremonyId` + `passkeyResponse`) now required by `DELETE /profile/account`.
- Refresh token survives process restarts; token refresh is single-flight; when the refresh fails the session is cleared and the sign-in screen is shown instead of silently failing every request.
- Outbox drain inspects per-item HTTP 207 statuses: applied items are deleted, server-rejected items are recorded in a visible "sync errors" list, network failures keep the entry and stop the drain so ordering is preserved.
- Every local write (accounts, categories, labels, rules, budgets, obligations, lending, savings, group sharing, profile, review, obligation execution) now goes through the Outbox instead of fire-and-forget API calls, so offline changes are no longer lost.
- Server pulls merge into the local replica (entities with pending mutations are preserved) instead of wiping collections; the ledger pull walks every page instead of the first 300 records.
- Records are created with a client id that the server adopts, so queued updates/deletes reference an id the server knows; client-created entities are remapped to server ids across all local references once acknowledged.
- Executing an obligation while signed in no longer posts a local record in addition to the server-posted one.
- Dedup hash now uses the server's canonical v2 format (UTC second-precision booking date, HALF_EVEN 4-dp amount); creating a record whose hash already exists on the same account returns the existing one, so re-imports and re-delivered alerts no longer duplicate.
- Hardcoded FX table removed: rates come from `GET /profile/exchange-rates` and are cached locally; amounts without a known rate are shown in their own currency instead of being converted with rate 1. Cross-currency transfers credit the target exactly the received amount the user entered (or the effective rate) and send `targetAmount` to the server; saving is blocked when no rate is known.
- Deleting a transfer deletes both legs in one transaction, reverting each leg on its own account in its own currency, and queues a single server delete.
- No demo data is created on install or in offline mode (debug builds can load it explicitly); untouched demo rows from older builds are removed on upgrade. Bank alerts are filed only into the account mapped to the sender or the chosen default capture account, otherwise they wait in a "needs an account" list; records the server rejects with 404 are parked there instead of being dropped.
- Focused text fields stay visible above the keyboard on the auth screen and in tab content (edge-to-edge mode disabled `adjustResize`).
- The All Accounts card on Home matches the size of the account cards.

### Security
- Auto Backup is disabled and the encrypted database, Keystore-wrapped key and session tokens are excluded from cloud backup and device transfer.
- The hard-coded SQLCipher fallback passphrase is gone. If the wrapped key cannot be unwrapped (data restored onto another device), the unreadable local replica is discarded and a fresh key is generated instead of crash-looping.
- Access JWT and refresh cookie are stored in `SecurePrefs` (AES-256-GCM, hardware-backed Keystore) instead of plaintext SharedPreferences.
- Database key: transient Keystore failures are retried; the key prefers StrongBox and falls back to the TEE Keystore. A permanently invalidated key triggers a controlled reset (local DB + wrapped key wiped, resync from server); databases created by old builds with the constant fallback passphrase are re-keyed in place. A Keystore that stays unavailable runs the session on an in-memory database without touching the file.
- `READ_SMS` permission removed (only `RECEIVE_SMS` is needed).
