---
name: release
description: >-
  Automates and guides the standardized release process across Kakei ecosystem projects: determining SemVer,
  updating package and app versions (clean release without -dev in git), maintaining CHANGELOG.md,
  creating single release commits, generating annotated Git tags (vX.Y.Z), and managing Debug vs Release build metadata.
---

# Release & Version Tagging Skill

This skill provides a standardized, repeatable procedure for preparing, versioning, documenting, committing, and tagging releases across projects in the Kakei ecosystem (**Kakei Android App**, **Kakei Web & Server**).

---

## 1. Release Architecture & Versioning Principles

### A. Variant 1: Clean Release without `-dev` in Git

All projects in the Kakei ecosystem follow **Variant 1 (Clean Release without `-dev` in Git)**:
- **No `-dev` in Git**: Configuration files (`app/build.gradle.kts`, `package.json`, `Cargo.toml`) track the clean, current release version (e.g. `1.0.0`). We do **not** add `-dev` (e.g. `1.0.1-dev`) after a release.
- **Why**:
  1. According to [Semantic Versioning 2.0.0](https://semver.org/), pre-release versions are ordered *below* their normal counterpart (`1.0.0-dev` < `1.0.0` < `1.0.1-dev` < `1.0.1`). Adding `-dev` after a release causes confusion or requires predicting whether the next release will be patch, minor, or major.
  2. One single release commit (`chore(release): vX.Y.Z`) per release keeps git history clean without secondary "bump to dev" noise.
  3. Mobile build pipelines (Android Gradle `versionCode` and `versionName`) require clean semantic versions without arbitrary hyphenated suffixes.
- **Single Release Step**: A release is created in one commit updating the version files and `CHANGELOG.md`, followed immediately by an annotated git tag (`vX.Y.Z`). No follow-up commit to add `-dev` is made.

### B. Differentiating Debug vs. Release Builds

Debug and development builds are identified **dynamically at runtime / compile time**, never by polluting tracked configuration files:
- **Kakei Android Client**:
  - Android Gradle uses `BuildConfig.DEBUG` to toggle debug diagnostics, mock data, and test environments.
  - Release APKs/Bundles are generated with `optimization.enable = true` and release signing configurations.
- **Kakei Server & Web Application**:
  - `GET /version` returns `{ version, build_time, debug: bool }` where `debug` is derived from `cfg!(debug_assertions)`.
  - Server startup logs indicate whether running in `mode: debug` or `mode: release`.
  - Web client displays a `Debug` badge if `import.meta.env.DEV` is active or if the version string contains `dev`/`dirty`.

---

## 2. Release Workflow Overview

```
1. Inspect Git History ──► 2. Determine SemVer ──► 3. Update Versions & CHANGELOG ──► 4. Verify Builds ──► 5. Commit & Tag
```

---

## 3. Detailed Step-by-Step Procedure

### Step 1: Inspect Git History & Unreleased Commits

Find the latest existing tag and inspect all commits since that tag:

```bash
# Find latest tag
git describe --tags --abbrev=0

# View commits since the latest tag
git log $(git describe --tags --abbrev=0)..HEAD --oneline

# If no tags exist, view all commits
git log --oneline
```

### Step 2: Determine Next Semantic Version

Follow [Semantic Versioning 2.0.0](https://semver.org/):

| Change Type | Conventional Commit Prefix | Version Bump | Example |
| :--- | :--- | :--- | :--- |
| **Breaking Change** | `feat!:`, `fix!:`, `BREAKING CHANGE:` | **MAJOR** (`X.0.0`) | `1.0.0` ➔ `2.0.0` |
| **New Features** | `feat:`, `feat(scope):` | **MINOR** (`x.Y.0`) | `1.0.0` ➔ `1.1.0` |
| **Bug Fixes / Chores** | `fix:`, `perf:`, `refactor:`, `style:` | **PATCH** (`x.y.Z`) | `1.0.0` ➔ `1.0.1` |

> [!NOTE]
> For Android applications, every release must also increment `versionCode` by 1 (`versionCode = <previous_code + 1>`).

### Step 3: Update Project Version Files & `CHANGELOG.md`

Update all version files to the exact, clean target version `X.Y.Z`:

#### For Kakei Android Client:
- `app/build.gradle.kts`:
  ```kotlin
  versionCode = <incremented_code>
  versionName = "X.Y.Z"
  ```
- `CHANGELOG.md`: Move entries from `## [Unreleased]` into `## [X.Y.Z] - YYYY-MM-DD`.

#### Standard `CHANGELOG.md` Format:
```markdown
## [Unreleased]

## [X.Y.Z] - YYYY-MM-DD

### Added
- Feature description...

### Fixed
- Bug fix description...
```

### Step 4: Verification Builds

Ensure all tests and compilation checks pass before committing:

```bash
# Kakei Android Client:
./gradlew test
./gradlew check
```

### Step 5: Create Release Commit & Git Tag

Create a single atomic release commit and annotated git tag:

```bash
# 1. Stage modified version files and CHANGELOG.md
git add CHANGELOG.md app/build.gradle.kts

# 2. Create release commit
git commit -m "chore(release): vX.Y.Z"

# 3. Create annotated git tag
git tag -a vX.Y.Z -m "chore(release): vX.Y.Z"
```

> [!IMPORTANT]
> Always pass `-a` and `-m "<message>"` when creating git tags to prevent git from opening interactive editor prompts in non-interactive terminals.

### Step 6: Push Release to Remote

```bash
git push origin <current-branch>
git push origin vX.Y.Z
# Or push both simultaneously:
git push origin <current-branch> --tags
```

### Step 7: Post-Release State

- **Do NOT bump version to `-dev`**: The repository stays on clean version `X.Y.Z`.
- Development continues normally. Runtime checks (`BuildConfig.DEBUG`) ensure development builds are explicitly marked as `(Debug)`.
