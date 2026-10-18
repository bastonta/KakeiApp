# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0] - YYYY-MM-DD

### Added
- Initial release of Kakei Android native mobile client.
- Offline-first architecture backed by SQLCipher AES-256 encrypted SQLite storage (`KakeiDatabase`).
- Autonomous bank transaction capture: regex parser for SMS and Push notifications (`BankSmsPatterns`).
- 5-minute notification deduplication across SMS and Push channels (`NotificationDeduplicationHelper`).
- Deterministic merchant rule classifier (`SmartMerchantMatcher`).
- Instant category overlay window (`InstantPopupService`) with `SYSTEM_ALERT_WINDOW`.
- Quick Settings tile (`QuickAddTileService`) for fast 1-tap transaction entry.
- 7 interactive home screen desktop widgets (`BudgetRingWidget`, `GoalsRingWidget`, `TodaySpendWidget`, `DebtBalanceWidget`, `QuickAddWidget`, `SplitBillWidget`, `NetWorthWidget`).
- Midnight widget metric reset worker (`MidnightWidgetResetWorker`) and notification watchdog (`NotificationListenerWatchdogWorker`).
- Centralized server scheduling integration: virtual planned transaction projections and liquidity horizon forecast (7-30 days).
- Modified single-entry ledger with banking parity, counterflow transfer pairing, splits, and clearing lifecycle.
- SHA-256 composite deduplication hash for statement imports and bank alerts (`HashDedupCalculator`).
- Envelope budgeting engine with dynamic daily burn rates and 75/90/100% threshold alerts.
- Statement import gateway supporting CSV and structured OFX 2.2 files.
- Export of transaction CSV and encrypted full-database snapshots (AES-256-GCM + PBKDF2).
- Trilingual localization (English, Russian, Kazakh) in `AppStrings`.
- Biometric authentication gate (`androidx.biometric`).

### Changed

### Deprecated

### Removed

### Fixed

### Security
