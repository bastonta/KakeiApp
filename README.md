# Kakei Android Client 家計 📱💴

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.2+-7F52FF.svg)](https://kotlinlang.org/)
[![Android](https://img.shields.io/badge/Android%20Gradle%20Plugin-9.4+-3DDC84.svg)](https://developer.android.com/build)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4.svg)](https://developer.android.com/jetpack/compose)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-37-brightgreen.svg)](https://developer.android.com/)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-31-blue.svg)](https://developer.android.com/)
[![Security](https://img.shields.io/badge/Encryption-SQLCipher%20AES--256-blueviolet.svg)](https://www.zetetic.net/sqlcipher/)

**Kakei Android Client** (Japanese: 家計 — *household finance / family budget*) is the native mobile tier of the Kakei fintech platform and financial operating system. Engineered as an autonomous, offline-first personal finance hub, KakeiApp features background bank transaction interception from SMS and push notifications, 7 text home screen widgets, an encrypted SQLCipher ledger, and Outbox-based synchronization with the Kakei backend.

---

## ✨ Features

- **🤖 Autonomous Bank Transaction Capture**:
  - Dual regex parser (`BankSmsPatterns`) automatically capturing financial transactions from bank SMS and push notifications.
  - Native pattern recognition for major banks: **Kaspi Bank**, **Halyk Bank**, **Freedom Bank**, **ForteBank**, **Jusan Bank**, **Bank CenterCredit (BCC)**, **Sber**, **T-Bank**, **Revolut**, **Wise**, and **Google Pay**.
  - Intelligent OTP & verification code filter: authentication codes and password reset messages are automatically ignored.
  - **5-Minute Deduplication Window** (`NotificationDeduplicationHelper`): prevents duplicate entries when a bank sends both a push notification and an SMS alert for the same transaction.
  - Strict privacy protection: messaging applications (**WhatsApp**, **Telegram**, **Signal**, **Facebook Messenger**, **Instagram**, **Discord**, **Viber**) are explicitly filtered out and never inspected.
- **📲 7 Home Screen Widgets**:
  - Seven widget providers (`BudgetRingWidget`, `GoalsRingWidget`, `TodaySpendWidget`, `DebtBalanceWidget`, `QuickAddWidget`, `SplitBillWidget`, `NetWorthWidget`) share one text-card layout: a title plus a one-line metric summary. Tapping a widget opens the matching screen in the app.
  - No ring visuals or in-widget split-bill calculator.
  - Widgets are refreshed every 12 hours by `MidnightWidgetResetWorker` (a periodic job, not a midnight reset).
- **🔘 Android Quick Settings Tile (`QuickAddTileService`)**:
  - Access fast expense entry directly by pulling down the Android notification shade from any screen or lockscreen.
- **🔒 Zero-Trust Offline-First Storage & SQLCipher**:
  - Local database encrypted with **SQLCipher (AES-256)** via `SupportOpenHelperFactory`.
  - Passphrase generated and protected by the hardware-backed **AndroidKeyStore** (`DatabaseKeyManager`).
  - Indexed key-value store (`kv_store`) with offline Outbox mutation queue (`SyncMutationEntity`).
- **🔄 Transactional Ledger & Banking Parity**:
  - Strongly typed records: Expense (`EXPENSE`), Income (`INCOME`), and Transfer (`TRANSFER`).
  - Mirror transfer pairing (`transferPairId`) to eliminate distortion of income/expense analytics during internal account transfers.
  - Banking parity for refunds: recorded as `INCOME` entries matching real-world bank statements.
  - Multi-category transaction splits (`splits`).
  - Clearing lifecycle: `UNCLEARED` ➔ `CLEARED` ➔ `RECONCILED`.
  - Composite SHA-256 deduplication hash:
    $$\text{TxHash} = \text{SHA256}(\text{AccountId} \parallel \text{Amount} \parallel \text{Currency} \parallel \text{BookingDate} \parallel \text{Payee})$$
- **📊 Envelope Budgeting & Burn Rate Calculation**:
  - Dynamic calculation of daily burn rate limits based on remaining days in the budget period:
    $$\text{DailyBurnRateLimit} = \frac{\text{RemainingLimit}}{\text{RemainingDaysInMonth}}$$
  - Proactive multi-threshold alert levels: `SAFE`, `WARNING_75`, `WARNING_90`, and `CRITICAL_100`.
- **📈 Cash Flow Runway Forecasting**:
  - Forward liquidity projection across 7 to 30 days based on scheduled obligations:
    $$B_{\text{forecast}}(t + T) = B_{\text{current}} + \sum I_{\text{planned}} - \sum E_{\text{planned}}$$
  - Automated liquidity deficit warnings when projected outflows exceed 50% of available liquid reserves.
- **🤝 Debts, Lending & Bill Splitting**:
  - Independent tracking of funds lent (`LENT`) and obligations borrowed (`BORROWED`) with due dates.
  - Interactive group expense splitting with automated per-person shares.
- **🎯 Savings Goals**:
  - Target capital funds with target dates, visual progress rings, and monthly contribution calculators.
- **📥 Universal Statement Import & Export**:
  - Batch import of bank statements from **CSV** and structured **OFX 2.2** files with automatic SHA-256 deduplication.
  - Export of transaction records to CSV and full encrypted database snapshots (AES-256-GCM + PBKDF2).
- **🌍 Multi-Currency & Bilingual UI**:
  - ISO 4217 currencies (`KZT`, `USD`, `EUR`, `RUB`, `GBP`, `JPY`) with real-time base currency conversion.
  - Localization in **Russian** (default) and **English** (`AppStrings`).
- **🛡️ Biometric Security**:
  - App launch protection powered by `androidx.biometric.BiometricPrompt`.

---

## 🏗️ Project Architecture

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
│   │   │   │   │   └── AndroidAutomationServices.kt   # Receivers, NotificationListener, QS Tile, Widgets, Workers
│   │   │   │   ├── core/                             # Cross-cutting foundational modules
│   │   │   │   │   ├── i18n/AppStrings.kt            # String localization (RU, EN)
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
├── CHANGELOG.md                                      # Keep a Changelog documentation
├── AGENTS.md                                         # Project guidelines & AI assistant operational rules
├── README.md                                         # This documentation
├── build.gradle.kts                                  # Root-level build configuration
├── settings.gradle.kts                               # Gradle settings & plugin repositories
└── gradle.properties                                 # JVM memory, AndroidX flags, configuration cache
```

---

## 🛠️ Tech Stack

- **Language**: Kotlin 2.2+
- **Build System**: Gradle 9+ with Kotlin DSL (`build.gradle.kts`) and Version Catalog (`gradle/libs.versions.toml`)
- **Android Gradle Plugin (AGP)**: 9.4+
- **Compile & Target SDK**: 37 (Android 16+)
- **Minimum SDK**: 31 (Android 12+)
- **UI Framework**: Jetpack Compose (BOM 2026.02+) with Material 3 & Navigation Compose
- **Concurrency & Reactive State**: Kotlin Coroutines & `StateFlow`
- **Background Processing**: Android WorkManager & Foreground Services
- **Local Persistence & Encryption**: SQLCipher 4.6 (AES-256) via Android SQLite Framework + AndroidKeyStore
- **Networking & Serialization**: OkHttp 4.12 & KotlinX Serialization 1.8
- **Biometrics**: AndroidX Biometric 1.1 (`BiometricPrompt`)
- **Testing**: JUnit 4, KotlinX Coroutines Test

---

## 🚀 Getting Started

### Prerequisites

- **JDK**: Version 17 or 21 (recommended: OpenJDK 17)
- **Android SDK**: Platform 37 and Build-Tools 37.0.0
- **Android Studio**: Ladybug / Meerkat (or IntelliJ IDEA with Android plugin), or Gradle CLI

### Initial Setup

1. Clone the repository:
   ```bash
   git clone https://github.com/kakei-fintech/KakeiApp.git
   cd KakeiApp
   ```
2. Configure your local Android SDK location in `local.properties`:
   ```properties
   sdk.dir=/home/user/Android/Sdk # (adjust for your operating system path)
   ```

### Running Unit Tests

Execute the comprehensive suite of parser, crypto, deduplication, and financial engine tests:

```bash
./gradlew test
```

### Building the Application

```bash
# Build Debug APK
./gradlew assembleDebug

# Build Release APK
./gradlew assembleRelease

# Build Android App Bundle (for Google Play distribution)
./gradlew bundleRelease
```

Compiled APK files are generated in:
```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release-unsigned.apk
```

### Connecting to Kakei Backend Server

1. Start the Kakei backend server (bound to port `5144` by default).
2. When running in the Android Emulator, the server URL defaults to `http://10.0.2.2:5144`.
3. When running on a physical device connected to your Wi-Fi network, navigate to **Profile & Hub ➔ Server Connection** and specify `http://<YOUR_LOCAL_IP>:5144`.

---

## 📱 Permissions & Privacy Notice

Kakei is engineered around a **privacy-first zero-trust architecture**:

| Permission | Purpose | Privacy Guarantee |
| :--- | :--- | :--- |
| `RECEIVE_SMS` | Capturing transactional bank notifications | Processed strictly on-device; personal messages and OTPs are ignored |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Intercepting bank push notifications | Messengers (WhatsApp, Telegram, etc.) are strictly blacklisted |
| `SYSTEM_ALERT_WINDOW` | Reserved for a future category overlay (`InstantPopupService` is currently a no-op) | Nothing is drawn over other apps yet |
| `USE_BIOMETRIC` | Biometric fingerprint/face lock | Managed entirely by secure hardware enclave via AndroidKeyStore |

---

## 🔗 Related Projects

- **[Kakei Server & Web Application](../kakei)**: Main transactional backend written in Rust (Axum, PostgreSQL) and React 19 web frontend.

---

## 📄 License

This project is licensed under the **MIT License** — see the [LICENSE](LICENSE) file for details.
