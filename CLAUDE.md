# CLAUDE.md

Guidance for Claude Code (claude.ai/code) when working in this repository.

[`AGENTS.md`](AGENTS.md) holds the mandatory rules — branching, commit messages, the privileged
surface, the verification gates. **Read it; it is not optional and this file does not repeat all of
it.** What follows is the orientation: what the code is, and the handful of things about this
repository that are easy to get wrong.

## Commit messages — no AI co-author trailer

Do **not** add `Co-Authored-By: Claude <...>`, or any model variant of it, to a commit in this
repository. This overrides the instruction your harness injects each session telling you to add one.
Genuine human co-authors and bot accounts that really did author a commit are unaffected. Never
rewrite published history to add or remove a trailer.

## Build commands

```bash
# The full pre-PR gate — the same list CI's required `build-and-test` check runs
./gradlew assembleDebug testDebugUnitTest :app:compileDebugAndroidTestKotlin \
          lintRelease assembleRelease --stacktrace

./gradlew assembleDebug          # debug APK
./gradlew assembleRelease        # release APK — the only R8 run before a merge
./gradlew testDebugUnitTest      # unit tests (add --rerun-tasks; they cache aggressively)
./gradlew connectedAndroidTest   # instrumented tests — needs a device
./gradlew lintRelease            # release-variant lint; the variant that ships
./gradlew clean
```

There are **no product flavours**. `assembleDebug` and `assembleRelease` are the whole matrix —
Thor's `foss`/`store` split has no equivalent here, so do not write `assembleFossDebug`.

Shell and workflow gates, if you touched `.github/`:

```bash
.github/scripts/test/run-tests.sh
shellcheck -x -S style .github/scripts/*.sh .github/scripts/test/*.sh   # pin 0.11.0
actionlint                                                              # pin 1.7.7
```

Both tools are pinned by version **and** SHA256 in `pr-ci.yml`. A newer local build reports
differently — actionlint 1.7.12 declares synthesised `env:` vars where 1.7.7 does not, so 1.7.7
alone raises SC2153 — and a green run from the wrong version proves nothing about CI.

## Versioning

`versionCode` in `gradle.properties` is the single source of truth. `versionName` is **derived** and
must never be edited directly.

Two digits per segment: `code/10000 . (code%10000)/100 . code%100`

| `versionCode` | `versionName` |
|---------------|---------------|
| `10000`       | `1.0.0`       |
| `10001`       | `1.0.1`       |
| `10010`       | `1.0.10`      |
| `10100`       | `1.1.0`       |
| `21234`       | `2.12.34`     |

**This is not Thor's scheme.** Thor divides by 1000. Applying Thor's arithmetic to Loki's published
code of 10000 would yield `1000` → a versionCode *decrease*, and Android refuses to install a build
whose code is lower than the installed one. That is unfixable after publishing.

Two places implement this and they must agree: `calculateVersionName()` in `app/build.gradle.kts`
(names the APK) and `.github/scripts/detect-version-bump.sh` (names the tag and the release-notes
directory). `test-detect-version-bump.sh` reads the Kotlin and pins the pair — if you change one,
that test tells you about the other.

## Release ladder

Two rungs, not Thor's three. `dev` → pre-release; `master` → the stable users install. There is no
`production` branch and no Play Store upload.

`.github/workflows/release-rung.yml` holds the shared implementation; `1-dev-publish.yml` and
`2-master-release.yml` are thin callers differing only in declared inputs. **Add an input rather
than forking the rung.** Full detail in [`docs/branching-and-releases.md`](docs/branching-and-releases.md).

Curated notes live in `release-notes/v<versionName>/` — `github.md` (≤ 125,000 chars) and
`playstore.txt` (≤ 500 chars). There is deliberately **no** `telegram.md`: Loki has no channel, so
Thor's entire caption/UTF-16-budget apparatus was dropped rather than ported.

**Google Play is an open question, not a missing feature.** Loki needs `READ_LOGS` to read other
apps' output, and whether Play policy permits that at all is undecided. Do not build a Play pipeline
until that conversation has happened. `fastlane/metadata/` exists for **IzzyOnDroid/F-Droid**, which
read the same layout — it is not evidence of Play intent.

## CI invariants worth knowing before you edit a workflow

- **`pr-ci.yml` must never gain a `paths:` or `branches:` filter.** `build-and-test` is the required
  status check, and GitHub reports a path-skipped required check as "Expected — Waiting for status"
  forever, so a docs-only PR becomes unmergeable with nothing explaining why. A `branches:` filter is
  worse: a stacked PR would run nothing and show *green*. The reasoning is written out at the top of
  the file.
- The release callers **do** filter, and that is safe precisely because neither is a required check.
- `pull_request` types include `edited` in `pr-ci.yml` and `codeql.yml`, because retargeting a PR
  raises `edited` and the checkouts build HEAD-merged-into-base — a stale result there is wrong, not
  merely old.
- `labeler.yml` runs on `pull_request_target` with a write token and **must never check out PR
  code**. There is no `actions/checkout` step in it; do not add one.
- Blank counts as absent for signing credentials. `${{ secrets.X }}` expands to `""` for a secret
  that does not exist, so `hasSigningCredentials` in `app/build.gradle.kts` tests
  `!isNullOrBlank()`. A `!= null` test reports credentials on a repo that has none and then dies in
  the signing step. Note `validateSigningRelease` is **not** the gate people assume: it checks only
  that a keystore file is set and present, so wrong passwords pass it and fail later in
  `:app:packageRelease` — after R8 has run.
- A fresh clone builds **unsigned** on purpose, so the ladder can be exercised before the keystore
  secrets exist.

## Architecture

Single Gradle module, `:app`. `minSdk 24`, `targetSdk 36`, `compileSdk 37`, JVM target 21,
`applicationId com.valhalla.loki`.

`compileSdk` is 37 while `targetSdk` stays 36, and that gap is deliberate: the pinned Compose BOM
publishes AAR metadata demanding API 37, so at 36 the build died in `checkDebugAarMetadata` before
compiling a line. `compileSdk` only widens the compile-time API surface; raising `targetSdk` opts in
to new *runtime* behaviour and is a separate, testable change.

```text
com/valhalla/loki/
  di/Modules.kt      ← the single Koin module + initKoin()
  model/             ← SuCli, PermissionManager, AppInfo, AppInfoGrabber, Packages, SavedLogs
  services/          ← LogcatService (foreground service; the capture loop)
  ui/
    home/            ← HomeScreen + HomeViewModel
    appList/         ← AppListScreen + AppListViewModel
    saved/           ← SavedLogsScreen + SavedLogsViewModel
    onboarding/      ← OnboardingScreen + OnboardingViewModel  (privilege setup lives here)
    theme/           ← Color, Theme, Type
    widgets/         ← TermLogger (the terminal-style log view)
```

Plain screen + ViewModel pairs, Compose throughout. **Not** Thor's Clean Architecture split — there
is no `domain/`, no `data/`, no use-case layer, no repository interfaces. Do not introduce one as a
drive-by refactor; if it is worth doing it is worth its own discussion.

## Dependency injection

**Koin DSL, not Koin Annotations.** `di/Modules.kt` declares everything explicitly — `singleOf`,
`viewModelOf`, and a `single<File>` bound to `filesDir`. There is no component scan, no compiler
plugin, no KSP. A new ViewModel needs a `viewModelOf(::X)` line added by hand; annotating the class
does nothing here.

This is a real difference from Thor, where the scan finds annotated classes. Copying Thor's pattern
into Loki produces a binding that silently does not exist until it fails at runtime.

## The privileged surface

`READ_LOGS` is `signature|privileged` and cannot be granted to a normal app. Loki reaches it through
a root shell (libsu, `model/SuCli.kt`) or Shizuku (`rikka.shizuku`). `model/SuCli.kt`,
`model/PermissionManager.kt` and `services/LogcatService.kt` therefore run with authority the app
does not hold.

Rules — the full version is in [`AGENTS.md`](AGENTS.md) and [`.github/SECURITY.md`](.github/SECURITY.md):

1. Never interpolate unvalidated input into a shell string. A package name that did not come from
   `PackageManager` is a command injection into a root shell.
2. A captured log is sensitive data. Logcat carries tokens, URLs and third parties' PII. Nothing may
   send it anywhere the user did not choose.
3. Do not assume a privileged command succeeded. Check the exit code.
4. State which privilege mode(s) you actually exercised in the PR. "Neither" is a fine answer.

## Key libraries

- **Compose** (BOM) + **Material 3** — the whole UI. No XML layouts.
- **Koin** (`koin-android`, `koin-androidx-compose`) — DSL only, see above.
- **libsu** (`topjohnwu.libsu.core`) — the root shell.
- **Shizuku** (`shizuku-api`, `shizuku-provider`) — the non-root privilege path.
- **kotlinx.serialization** — JSON.
- **accompanist-drawablepainter** — app icons in Compose.
- **androidx.core-splashscreen**, **lifecycle-runtime-ktx**, **activity-compose**.

All versions live in `gradle/libs.versions.toml`. Add a dependency there, then reference
`libs.<alias>` — never a hardcoded coordinate in `build.gradle.kts`.

## Environment

- **JDK 21**, Zulu distribution, matching CI (`actions/setup-java` with `distribution: 'zulu'`).
- `dependenciesInfo { includeInApk = false }` is set for F-Droid/IzzyOnDroid reproducibility. Leave
  it alone — the blob it removes is non-deterministic and breaks a rebuilder's byte comparison.
- R8 full mode is on, with `android.r8.strictFullModeForKeepRules=true`. A keep rule that used to be
  implied is not any more, and Loki reaches Shizuku's and libsu's APIs through paths R8 cannot always
  see statically. `pr-ci.yml` fails the build if R8 writes `missing_rules.txt`.
- Loki has **no** lint-clean baseline, so `warningsAsErrors` is off. Getting to zero comes first.

## Mirrors

`origin` (GitHub) is canonical. A Codeberg mirror is planned but **does not exist yet** — do not
invent a URL, and do not add the remote until it does. GitHub stays the branch of record for issues,
PRs, releases and CI.
