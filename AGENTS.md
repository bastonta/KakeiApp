# Kakei Android Client Application — Project Rules & Guidelines

Welcome to **Kakei Android App** (家計), the native client tier of the Kakei personal and family fintech platform and financial operating system. KakeiApp provides an autonomous, offline-first mobile experience with bank SMS & push notification interception, instant overlay category pickers, 7 desktop widgets, encrypted local SQLCipher storage, bidirectional REST synchronization with the Kakei backend (including RFC 4918 / HTTP 207 Multi-Status Outbox), envelope budgeting with dynamic burn rates, and predictive cash flow forecasting.

This document serves as the single source of truth for architecture, conventions, and operational rules for both human developers and AI assistants working in this repository.

---

## 1. Project Architecture & Workspace Layout

KakeiApp is structured as a modern Android application built with **Kotlin 2.2+**, **Android Gradle Plugin (AGP) 9.4+**, **Jetpack Compose (BOM 2026.02+)**, and **Target SDK 37 (Min SDK 31)**. The codebase enforces a clean modular architecture:

```text
KakeiApp/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/kakei/app/
│   │   │   │   ├── KakeiApplication.kt               # Application entrypoint & WorkManager scheduler
│   │   │   │   ├── MainActivity.kt                   # Single-activity Jetpack Compose host
│   │   │   │   ├── automation/                       # Mobile automation & background capture tier
│   │   │   │   │   ├── BankCaptureEngine.kt          # SMS/Push regex parsers, merchant matcher & 5-min dedup
│   │   │   │   │   └── AndroidAutomationServices.kt   # Receivers, NotificationListener, Overlay, QS Tile, Widgets
│   │   │   │   ├── core/                             # Cross-cutting foundational modules
│   │   │   │   │   ├── i18n/AppStrings.kt            # Trilingual string localization (RU, KK, EN)
│   │   │   │   │   ├── security/SecurityHelpers.kt   # AndroidKeyStore, biometric auth, AES-256-GCM backup crypto
│   │   │   │   │   └── util/                         # HashDedupCalculator, CurrencyFormatter
│   │   │   │   ├── data/                             # Data persistence & network synchronization
│   │   │   │   │   ├── local/KakeiDatabase.kt        # SQLCipher encrypted SQLite kv_store & Outbox queue
│   │   │   │   │   ├── remote/                       # OkHttp REST client, DTOs & token handling
│   │   │   │   │   │   ├── KakeiApiClient.kt         # REST client communicating with Kakei Axum backend
│   │   │   │   │   │   └── dto/KakeiApiDtos.kt       # KotlinX Serializable DTOs matching backend models
│   │   │   │   │   └── repository/KakeiRepository.kt # Central offline-first repository & StateFlow emissions
│   │   │   │   ├── domain/calculators/               # Pure financial calculation engines
│   │   │   │   │   └── FinancialCalculators.kt       # Budgets, Cash Flow Forecast, Health Score, Imports/Exports
│   │   │   │   └── ui/                               # Jetpack Compose UI layer
│   │   │   │       ├── screens/                      # Compose screens (Auth, Home, Budgets, Lend/Save, Profile)
│   │   │   │       └── theme/                        # Material 3 colors, typography & dynamic theme provider
│   │   │   ├── res/                                  # Android resources (XML layouts, icons, strings, widgets)
│   │   │   └── AndroidManifest.xml                   # Application manifest, permissions, receivers & services
│   │   └── test/java/com/kakei/app/                  # Unit and integration test suite
│   │       └── UnitTest.kt                           # Regex parser, crypto, math & deduplication tests
│   └── build.gradle.kts                              # App-level build script & dependency declarations
├── gradle/
│   ├── libs.versions.toml                            # Gradle Version Catalog (single source of truth)
│   └── wrapper/                                      # Gradle wrapper binaries
├── .agents/                                          # Agent skills and automation workflows
│   └── skills/                                       # Reusable workflow skills
│       └── release/                                  # Standardized release & SemVer tagging skill
├── build.gradle.kts                                  # Root-level build configuration
├── settings.gradle.kts                               # Gradle settings & plugin repositories
├── gradle.properties                                 # JVM memory, AndroidX flags, configuration cache
├── CHANGELOG.md                                      # Keep a Changelog documentation
├── README.md                                         # Public project overview & developer documentation
└── AGENTS.md                                         # Project guidelines & AI assistant operational rules
```

---

## 2. Dependency Management (Gradle Version Catalog)

The project strictly manages all dependencies through the **Gradle Version Catalog**:

- **`gradle/libs.versions.toml` as Single Source of Truth**: All third-party libraries, AndroidX components, and Gradle plugins must be declared in `gradle/libs.versions.toml`.
- **Type-Safe Accessors**: In `app/build.gradle.kts`, reference dependencies using type-safe accessors (`libs.androidx.compose...`, `libs.sqlcipher.android`, etc.):
  ```kotlin
  dependencies {
      implementation(libs.androidx.compose.material3)
      implementation(libs.sqlcipher.android)
      implementation(libs.kotlinx.serialization.json)
  }
  ```
- **No Inline Version Duplication**: Never hardcode version strings inside `build.gradle.kts` files. When adding or upgrading a dependency, add it to `gradle/libs.versions.toml` first.

---

## 3. Architecture & Domain Conventions

### A. Offline-First Storage & SQLCipher Encryption (`KakeiDatabase`)

- **Encrypted Local Storage**: The local SQLite database is encrypted with **SQLCipher (AES-256)** via `SupportOpenHelperFactory`.
- **Passphrase Security**: The database passphrase is dynamically generated and stored in the hardware-backed **AndroidKeyStore** (`DatabaseKeyManager`). If SQLCipher binary libraries are unavailable during development/testing, it gracefully falls back to `FrameworkSQLiteOpenHelperFactory`.
- **Collection Key-Value Store**: The schema uses a partitioned key-value table (`kv_store`) indexed on `(collection, sort_key DESC)` storing serialized JSON records for rapid retrieval, range queries, and offline mutation queues (`SyncMutationEntity`).
- **Outbox Pattern**: Mutations performed while offline are queued in `SyncMutationEntity` and drained to the backend during synchronization.

### B. Transactional Ledger & Banking Parity Principles

1. **Modified Single-Entry with Counterflow Pairing**:
   - Every transaction belongs to an account and is typed as `EXPENSE`, `INCOME`, or `TRANSFER`.
   - Transfers between accounts create mirror records connected by `transfer_pair_id` so internal money transfers do not distort net spending/income totals.
2. **Banking Parity for Refunds**:
   - Refunds and chargebacks are recorded as `INCOME` entries rather than reversals/storno of previous expenses, maintaining 1:1 parity with bank statements.
3. **Transaction Splits**:
   - A single transaction can be split across multiple categories (`splits: List<SplitDto>`) while preserving the total nominal amount, payee, and receipt attachment.
4. **Clearing Lifecycle**:
   - Status transitions: `UNCLEARED` (pending bank authorization) ➔ `CLEARED` (posted/booked) ➔ `RECONCILED` (verified against official statement).
5. **Deduplication Hash**:
   - Incoming bank records and statement imports calculate a deterministic composite SHA-256 hash:
     $$\text{TxHash} = \text{SHA256}(\text{AccountId} \parallel \text{Amount} \parallel \text{Currency} \parallel \text{BookingDate} \parallel \text{Payee})$$
   - Implemented in `HashDedupCalculator.computeTransactionHash(...)`. Duplicate records are silently deduplicated.

### C. Mobile Automation & Autonomous Bank Capture Tier

1. **SMS & Push Notification Interception**:
   - `SmsReceiver` (`BroadcastReceiver`, priority 999) intercepts incoming banking SMS.
   - `BankNotificationListenerService` listens for system push notifications while explicitly ignoring messaging apps (`BankSmsPatterns.ignoredPackages`) to protect user privacy.
2. **5-Minute Deduplication Window**:
   - `NotificationDeduplicationHelper` deduplicates notifications received across both SMS and Push channels within a 5-minute sliding window.
3. **Smart Merchant Categorization**:
   - `SmartMerchantMatcher` evaluates deterministic user rules (`CounterpartyRuleDto`) using prefix, suffix, and contains matching, falling back to keyword heuristics or flagging the record as `needsReview`.
4. **Instant Category Overlay (`InstantPopupService`)**:
   - If enabled and granted `SYSTEM_ALERT_WINDOW`, pops up a lightweight, non-intrusive floating category picker immediately upon card transactions for instant 1-tap assignment.
5. **Quick Settings Tile (`QuickAddTileService`)**:
   - Allows users to open the rapid transaction creation sheet directly from the Android Quick Settings shade.
6. **7 Home Screen Desktop Widgets**:
   - `BudgetRingWidget`: Active budget envelope utilization ring.
   - `GoalsRingWidget`: Progress towards savings goals.
   - `TodaySpendWidget`: Real-time daily spend total.
   - `DebtBalanceWidget`: Summary of active lent and borrowed balances.
   - `QuickAddWidget`: Fast-access launcher for new transaction entries.
   - `SplitBillWidget`: Quick calculator for group bill splitting.
   - `NetWorthWidget`: Consolidated net worth across all active liquid accounts.
   - Widgets are updated via `KakeiWidgetUpdater` and automatically reset at midnight via `MidnightWidgetResetWorker`.

### D. Centralized Server Scheduling & Bidirectional Sync (HTTP 207)

- **Server-Side Generation**: Recurring scheduled payments are generated on the Kakei server backend. KakeiApp displays them as virtual projections until confirmed by the server.
- **Revision Control & Multi-Status (RFC 4918 / HTTP 207)**:
  - Every mutating record tracks `sync_revision` (`Long`).
  - Outbox mutations are batched and posted to `/api/v1/sync/batch`, returning an HTTP 207 Multi-Status response with individual item statuses.
  - Conflict resolution follows Last-Write-Wins based on server timestamp and higher revision.
- **Offline Autonomous Mode**: The app operates fully without an active backend connection, buffering mutations in SQLite.

### E. Financial Calculators & Domain Engines (`FinancialCalculators`)

- **Envelope Budget Burn Rate**:
  - Computes spent amount, remaining amount, percentage, and dynamic daily burn rate:
    $$\text{DailyBurnRateLimit} = \frac{\text{RemainingLimit}}{\text{RemainingDaysInMonth}}$$
  - Evaluates multi-threshold alert levels: `SAFE`, `WARNING_75`, `WARNING_90`, `CRITICAL_100`.
- **Cash Flow Runway Forecast**:
  - Calculates 7-day to 30-day liquidity forecasts based on current liquid balances and upcoming recurring obligations:
    $$B_{\text{forecast}}(t + T) = B_{\text{current}} + \sum I_{\text{planned}} - \sum E_{\text{planned}}$$
  - Triggers `liquidityDeficitAlert` when upcoming commitments exceed 50% of liquid assets.
- **Financial Health Score & Streaks**:
  - Multi-factor index (0–100) assessing savings rate, debt ratio, budget discipline, and liquidity runway.
  - Tracks consecutive `No-Spend Streak` days excluding mandatory bills.
- **Statement Import & Export**:
  - Parses bank statements in CSV and structured OFX 2.2 formats with automatic SHA-256 transaction deduplication.
  - Exports encrypted full-database JSON backups and transaction CSV files.

### F. Security, Biometrics & Backup Cryptography

- **Biometric Lock**: Optional biometric authentication gate (`androidx.biometric.BiometricPrompt`) protecting app launch.
- **Encrypted Backup Files**: Cloud and local file backups are encrypted via `BackupCryptoHelper` using **AES-256-GCM** with keys derived via **PBKDF2WithHmacSHA256** (100,000 iterations, 16-byte random salt, 12-byte random IV).

---

## 4. Essential CLI Commands

Run all commands from the repository root:

### Build & Run Tests

```bash
# Run all unit tests (domain calculators, parsers, crypto, dedup)
./gradlew test

# Run tests with stacktrace on failure
./gradlew test --stacktrace

# Run specific unit test class
./gradlew test --tests "com.kakei.app.UnitTest"
```

### Build APK & Bundles

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Build release Android App Bundle (AAB)
./gradlew bundleRelease
```

### Code Verification & Linting

```bash
# Run Android lint
./gradlew lintDebug

# Run Gradle check
./gradlew check
```

---

## 5. Environment & Configuration

- **`local.properties`**: Contains the path to your Android SDK installation:
  ```properties
  sdk.dir=/home/user/Android/Sdk
  ```
- **Backend Server URL**: Configured dynamically in the app under Settings (`AppSettingsState.serverBaseUrl`), defaulting to:
  - `http://10.0.2.2:5144` for Android Emulator connecting to host machine Kakei server.
  - `http://<LAN_IP>:5144` for physical testing devices over Wi-Fi.
- **Debug vs Release**:
  - `BuildConfig.DEBUG` is used dynamically across the codebase to toggle verbose logging, test environments, and development bypasses. Never pollute tracked configuration files with temporary debug flags.

---

## 6. AI Assistant Operating Guidelines

When developing or modifying code in this repository:

1. **Layer Discipline**: Strictly observe boundaries between `ui/`, `domain/`, `data/`, and `automation/`.
   - UI Composables must never interact directly with `KakeiDatabase` or `KakeiApiClient`; always route actions through `KakeiRepository`.
   - Automation services (`SmsReceiver`, `BankNotificationListenerService`) delegate parsing to `BankSmsPatterns` and persistence to `KakeiRepository`.
2. **Financial Precision**:
   - Always use `java.math.BigDecimal` for financial calculations and monetary amounts. **Never** use floating-point types (`Float` or `Double`) for money amounts.
3. **Mandatory Post-Edit Verification**:
   - Always run `./gradlew test` after any Kotlin code edits to verify that unit tests pass.
   - Run `./gradlew check` or `./gradlew assembleDebug` to confirm build integrity.
4. **Preserve Documentation & Comments**: Keep existing docstrings and comments intact unless directly invalidated by your changes.
5. **English-Only Documentation & Comments**: All code comments, docstrings, commit messages, architectural designs, and markdown documentation (`README.md`, `AGENTS.md`, `CHANGELOG.md`) must be written strictly in **English**. Never write code comments or repository documentation in Russian or any other language.
6. **Git Commit Style**: Use Conventional Commits with relevant scopes:
   - `feat(automation): ...`, `feat(ui): ...`, `fix(data): ...`, `refactor(domain): ...`, `chore(release): ...`.
7. **Releases & Versioning**: Follow Keep a Changelog in `CHANGELOG.md` and use the `.agents/skills/release` skill when preparing releases and cutting tags (`vX.Y.Z`).
   - Follow **Variant 1 (Clean Release without `-dev` in Git)**: commit exact clean versions in `app/build.gradle.kts` (`versionName = "X.Y.Z"`, increment `versionCode`), tag the release commit (`vX.Y.Z`), and do not make subsequent commits adding `-dev`.
   - Differentiate debug/development builds dynamically at runtime via `BuildConfig.DEBUG`.
