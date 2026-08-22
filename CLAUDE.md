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

If you touched a Markdown file — which includes moving or renaming any source file the docs
name:

```bash
.github/scripts/check-doc-links.sh      # internal links + heading anchors, offline
```

`static-analysis` runs it on every PR. It checks relative targets and `#fragments` only;
outbound URLs are swept weekly by `docs-link-check.yml`, which files an issue rather than
reddening a PR. Keeping the flaky half out of the PR gate is deliberate — see the header
comment in that workflow.

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

Single Gradle module, `:app`. `minSdk 28`, `targetSdk 36`, `compileSdk 37`, JVM target 21,
`applicationId com.valhalla.loki`.

`compileSdk` is 37 while `targetSdk` stays 36, and that gap is deliberate: the pinned Compose BOM
publishes AAR metadata demanding API 37, so at 36 the build died in `checkDebugAarMetadata` before
compiling a line. `compileSdk` only widens the compile-time API surface; raising `targetSdk` opts in
to new *runtime* behaviour and is a separate, testable change.

```text
com/valhalla/loki/
  di/Modules.kt      ← the single Koin module + initKoin()
  model/             ← PermissionManager, SelfPermissions (the pure grant rule),
                       SelfPermissionGrabber (the launch-time sweep), SelfGrantStore (its two
                       persisted markers), LogcatCapture, AppInfo, AppInfoGrabber, Packages,
                       SavedLogs, Preferences (the one DataStore), ThemeManager, DirectoryChanges,
                       LogLevel, Extensions
  services/          ← LogcatService (foreground service; the capture loop),
                       LokiDocumentsProvider (exposes logs/ to the system file picker)
  ui/
    navigation/      ← LokiRoute (the NavKey surface) + NavItem (the bottom-bar items)
    home/            ← HomeScreen + HomeViewModel  (the shell: bottom bar over one NavDisplay)
    appList/         ← AppListScreen + AppListViewModel
    saved/           ← SavedLogsScreen + SavedLogsViewModel, LogViewerScreen + LogViewerViewModel
    explorer/        ← LogsExplorerScreen + LogsExplorerViewModel  (browse/bulk-delete/zip-share)
    onboarding/      ← OnboardingScreen + OnboardingViewModel  (privilege setup lives here)
    settings/        ← SettingsScreen + SettingsViewModel
    theme/           ← Color, Theme, Type
    widgets/         ← Formatting (shared byte/count formatters)
```

Plain screen + ViewModel pairs, Compose throughout. **Not** Thor's Clean Architecture split — there
is no `domain/`, no `data/`, no use-case layer, no repository interfaces. Do not introduce one as a
drive-by refactor; if it is worth doing it is worth its own discussion.

## Navigation

**Navigation 3**, wired as Thor is. `ui/navigation/LokiRoute.kt` is a `@Serializable sealed interface`
over `NavKey`; `HomeScreen` holds one `rememberNavBackStack` **per tab**, a single
`entryProvider<NavKey>` describing every route once, one `rememberDecoratedNavEntries` per stack
(saveable-state-holder + ViewModel-store decorators), and one `NavDisplay`.

Two things about this that are easy to get wrong:

- `entry` is a **member** of `EntryProviderScope`, so it resolves through the receiver and must
  **not** be imported. `import androidx.navigation3.runtime.entry` does not resolve and the error it
  produces (`Unresolved reference 'entry'`) points at the call site, not the import. Only
  `entryProvider` is imported.
- Back is answered by three `BackHandler`s with **mutually exclusive** `enabled` flags, not one
  handler with a `when` inside. Only the innermost *enabled* handler fires, so disjointness is what
  guarantees exactly one runs.

This replaced a `HorizontalPager` in which the log viewer and the logs explorer were not routes at
all but early returns from a tab's composable, keyed off a `rememberSaveable` path. A `String?` in a
Bundle cannot express "the explorer, at this directory, with the viewer above it", and the pager
could swipe out from under an open capture. Do not reintroduce a pager for the tabs.

The bottom bar hides while a child route is open. A consequence, not an oversight: you cannot
tab-switch out of an open viewer or explorer, so the per-tab stacks pay off mainly across process
death — which they do handle, verified.

## Dependency injection

**Koin DSL, not Koin Annotations.** `di/Modules.kt` declares everything explicitly — `singleOf`,
`viewModelOf`, and a few spelled-out `viewModel { }` blocks. There is no component scan, no compiler
plugin, no KSP. A new ViewModel needs a line added by hand; annotating the class does nothing here.

This is a real difference from Thor, where the scan finds annotated classes. Copying Thor's pattern
into Loki produces a binding that silently does not exist until it fails at runtime.

There is deliberately **no** unqualified `File` binding. There used to be a `single<File> { filesDir }`,
and it meant any later `File` dependency silently resolved to `filesDir` whatever it actually
wanted. The ViewModels that need a directory are spelled out and take `get<Context>().logsDir` or
`.shareCacheDir`; `LogViewerViewModel` takes its file as an injected *parameter*
(`parametersOf(file)`), one instance per file.

Anything holding preferences must be a `single`, and must go through the one delegate in
`model/Preferences.kt`. `preferencesDataStore(name = "settings")` may be instantiated **once per
process** — a second instance over the same file throws at runtime, not at compile time — so
`ThemeManager` and `SelfGrantStore` share `Context.settingsDataStore` and keep their own keys
private. Do not give a new owner its own store or its own file just to avoid the import.

## The privileged surface

`READ_LOGS` cannot be granted to a normal app by the user. Loki reaches it through a root shell
(**Odin**, `com.valhalla.superuser.ktx.ShellRepository`) or Shizuku (`rikka.shizuku`).
`model/PermissionManager.kt`, `model/SelfPermissionGrabber.kt`, `model/LogcatCapture.kt` and
`services/LogcatService.kt` therefore run with authority the app does not hold.

The shell is injected as the `ShellRepository` interface, bound to `RealShellRepository` in
`di/Modules.kt`. Take the interface, never construct a shell inline — that is what keeps the
privileged surface swappable in a test and countable by grep. `model/SuCli.kt` is **gone**; it was
the libsu wrapper.

### Self-granting READ_LOGS

The protection level is `signature|privileged|development` — note the third flag, which this file
used to omit — with `gids=[1007, 1096]`. Device-verified on API 36 and 37. Both halves matter:

- The `development` flag is the whole reason `pm grant` works at all. `PermissionManagerService`
  accepts `isRuntimePermission || bp.isDevelopment()`, so a privileged shell can hand Loki its own
  `READ_LOGS` without a signature match.
- The gids are why the grant **kills the entire appId**
  (`Killing …: permission grant or revoke changed gids`). A running process's supplementary group
  set cannot change, so the platform restarts rather than reconciles. This is expected, not a crash.

`SelfPermissionGrabber` sweeps once per launch that has a UI — `Loki.onCreate()` registers the
Shizuku listener and starts a sweep that does nothing until `MainActivity.onCreate()` calls
`onUiPresent()` — plus once per `recheck()`; concurrent sweeps are serialised by a mutex, not
prevented. The rule it applies lives in `model/SelfPermissions.kt`, which has **zero `android.*`
imports** so it can be tested on the JVM — `SelfPermissionsTest` is where the cases are written
down. Six things about it are easy to get wrong:

- **Two booleans, not Thor's one.** Thor's `planSelfGrant` gates on `isDangerous`, computed as
  `(protectionLevel and PROTECTION_MASK_BASE) == PROTECTION_DANGEROUS`. `READ_LOGS` is
  `2|16|32 = 50`, so that test asks `2 == 1` and structurally cannot ever grant it. Loki adds
  `isDevelopment`, and it must be a **flag** test —
  `(protectionFlags and PROTECTION_FLAG_DEVELOPMENT) != 0` — never a masked-base comparison.
- **Order is load-bearing.** Survivable grants (`gids=[]`, e.g. `POST_NOTIFICATIONS`) go first and
  fatal ones last, because the death aborts the rest of the sweep. Thor's suite actively pins the
  opposite (`theManifestsDeclarationOrderIsPreserved`) — it never needed this, so do not copy it.
- **The channels are asymmetric.** Root grants silently and arms a detached relaunch that survives
  the kill; Shizuku cannot, because `newProcess` dies with its client, so a relaunch armed there
  dies too. Shizuku therefore raises a dialog and the user reopens Loki by hand. The armed command
  is `nohup sh -c 'sleep N; pidof <pkg> >/dev/null 2>&1 || am start …' &`, and the `pidof` half is
  load-bearing: arming has to precede the grant, so nothing can call the command off afterwards, and
  a grant that *failed* would otherwise be followed two seconds later by Loki hauling itself to the
  foreground over whatever the user had moved on to. `pidof` finds our own process because the
  command runs as root, where `/proc` is not `hidepid`-restricted, and the package name doubles as
  the process name only because Loki declares no `android:process`.
- **Do not trust the exit code for "the permission landed."** Each grant is confirmed by re-reading
  `checkSelfPermission`. The plan is built from `PackageManager` alone before any privilege is
  probed, so a launch with nothing to grant never runs `su`.
- **The capture is claimed, not asked about.** `sweep()` still bails early on
  `LogcatCapture.isCapturing`, but that read is stale long before the grant — the root probe alone
  can take Odin's ten-second timeout, and on the Shizuku path a human has been reading a dialog in
  between. `grantFatal` therefore calls `LogcatCapture.blockForPrivilegedGrant()`, which takes the
  same monitor `start()` holds, so exactly one of the two wins and a refused claim abandons the
  grant. Do not "simplify" that back into a second `isCapturing` read; a check-then-act cannot be
  repaired by checking again.
- **A headless process may not spend privilege.** `Application.onCreate` runs on *every* process
  start, and Loki exports `LokiDocumentsProvider` with a `DOCUMENTS_PROVIDER` filter — so DocumentsUI
  starts Loki whenever any app opens a file picker, and the sticky Shizuku callback re-enters the
  sweep there too. `sweep()` therefore returns immediately unless `uiPresent` is set, which only
  `MainActivity.onCreate()` does. Without it, a rooted device would get a root-manager prompt over
  somebody else's picker, then the fatal grant, then `am start` putting Loki in the foreground on top
  of whatever the user was doing. The `pidof` guard above cannot cover that case: the process really
  did die, so there is nothing for `pidof` to find. That guard answers "did the grant fail?"; the
  flag answers "was there a UI to come back to?" The gate is inside `sweep()`, not at the `start()`
  call site, because the binder listener calls `refresh()` directly.

Scope is decided by the device, not a hardcoded list: every permission Loki declares that
`pm grant` can actually change. Adding one to the manifest needs no code edit. `POST_NOTIFICATIONS`
consequently never shows its system prompt.

**A grant is taken once, not once per launch.** `SelfGrantStore` persists the survivable permissions
a sweep has confirmed, and `planSelfGrant` skips a `dangerous` name it finds there — so turning
Loki's notifications off in Settings now sticks instead of lasting until the next cold start. The
exemption is asymmetric on purpose: `development` permissions are never consulted against that set,
because `READ_LOGS` has no switch anywhere in Settings and so its absence cannot be a user's choice.
There is still no `pm revoke` counterpart.

**The `su` probe is paid for once per install, not once per launch.** `READ_LOGS` is permanently
ungranted on a phone with no privilege, so the plan is permanently non-empty and `resolveChannel`
used to spawn `su` — and, on some root managers, raise a prompt — on every single cold start.
`SelfGrantStore.rootUnavailable` remembers the answer; `SelfPermissionGrabber.recheck()`, wired to
Settings' "Tap to re-check" row, is how someone who roots their phone later gets the probe back.
Shizuku is still checked every sweep, because `checkSelfPermission()` costs nothing. Note this fixes
the *sweep* only: `AppListViewModel` and `SettingsViewModel` still probe root when they need the
answer for their own UI.

`PermissionManager.isRootAvailable()` closes a cached **non-root** shell before re-probing. Odin's
`MainShell` falls back to `exec("sh")` when `su` throws and caches that as the main shell, and
`cached`'s getter only clears the slot when `status < 0` — a live non-root shell is `0`. Without
the close, one failed probe pins root unavailable for the whole process and every "tap to re-check"
is a silent no-op.

### Capture precedence: root before READ_LOGS

`LogcatCapture` probes root **first** and only falls back to in-process `READ_LOGS`, at the cost of
a `su` spawn on the common path. Holding `READ_LOGS` is what subjects a client to
`com.android.systemui/.logcat.LogAccessDialogActivity`: `logd`'s `clientIsExemptedFromUserConsent`
exempts uid < 10000, so a `su` shell at uid 0 is never asked and an in-process reader always is.
`LogcatManagerService` then raises the dialog only for a requester in `PROCESS_STATE_TOP` and
silently declines anything else — and a foreground service is not `TOP`. A declined *streaming*
reader is not killed: logd revokes the privilege and leaves it attached, so the symptom to expect is
a capture sitting there filling nothing, not one that exits non-zero and says why.

⚠️ **All of that is read from the platform's sources, not observed on a device.** Settling it needs
root *and* `READ_LOGS` on one phone, which nobody working on this has had. The same caveat is
written on `LogcatCapture`'s class KDoc, in more detail; keep the two in step. If the prediction is
wrong the ordering is merely unnecessary rather than harmful, which is why it stands — but do not
flip it to preferring `READ_LOGS` on the grounds that the app now holds it, and do not restate the
prediction as a measurement.

Root being *present but unusable* falls through to `READ_LOGS` rather than ending the capture — the
`&&` in the mode `when`, not a nested `if`. Three ways to reach it: the `su` probe succeeded but
`logcat` under it failed, the shell died between probe and use, or a stop arrived mid-setup. That
hole arrived with the root-first reorder and is easy to reintroduce.

Rules — the full version is in [`AGENTS.md`](AGENTS.md) and [`.github/SECURITY.md`](.github/SECURITY.md):

1. Never interpolate unvalidated input into a shell string. A package name that did not come from
   `PackageManager` is a command injection into a root shell.
2. A captured log is sensitive data. Logcat carries tokens, URLs and third parties' PII. Nothing may
   send it anywhere the user did not choose.
3. Do not assume a privileged command succeeded. Check the exit code.
4. State which privilege mode(s) you actually exercised in the PR. "Neither" is a fine answer.

## Key libraries

- **Compose** (BOM) + **Material 3** — the whole UI. No XML layouts.
- **Asgard UI** (`com.trinadhthatakula:asgard`, `com.valhalla.asgard.components`) — the shared
  component library extracted from Thor's design system: `AsgardHeader`, `AsgardListRow`,
  `AsgardSearchBar`, `AsgardEmptyState`, `AsgardSectionCard`, `AsgardSettingRow`, and friends.
  **This is what sets `minSdk` at 28.** Depend on the KMP root module, not `asgard-android`.
- **Koin** (`koin-android`, `koin-androidx-compose`) — DSL only, see above.
- **Odin** (`com.trinadhthatakula:odin`, namespace `com.valhalla.superuser`) — the root shell.
  Kotlin-first and coroutine-based; it replaced libsu 6.0.0. It exposes coroutines through `api()`,
  so `gradle/libs.versions.toml` pins kotlinx-coroutines explicitly rather than letting Odin decide.
- **Shizuku** (`shizuku-api`, `shizuku-provider`) — the non-root privilege path.
- **Navigation 3** (`navigation3-runtime`, `navigation3-ui`, `lifecycle-viewmodel-navigation3`) —
  see the Navigation section. The lifecycle artifact tracks the *lifecycle* version, not nav3's.
- **kotlinx.serialization** — JSON, and the routes.
- **material-icons-extended** — declared in `:app` on purpose; Asgard hand-rolls its icons and does
  not pass this down transitively.
- **accompanist-drawablepainter** — app icons in Compose.
- **androidx.core-splashscreen**, **lifecycle-runtime-ktx**, **activity-compose**.

There is no `lifecycle-runtime-compose`, so **`collectAsStateWithLifecycle` is unavailable**. The
project uses `androidx.compose.runtime.collectAsState()` throughout; copying a Thor snippet that
calls the lifecycle-aware variant will not compile.

All versions live in `gradle/libs.versions.toml`. Add a dependency there, then reference
`libs.<alias>` — never a hardcoded coordinate in `build.gradle.kts`.

## Environment

- **JDK 21**, Zulu distribution, matching CI (`actions/setup-java` with `distribution: 'zulu'`).
- `dependenciesInfo { includeInApk = false }` is set for F-Droid/IzzyOnDroid reproducibility. Leave
  it alone — the blob it removes is non-deterministic and breaks a rebuilder's byte comparison.
- R8 full mode is on, with `android.r8.strictFullModeForKeepRules=true`. A keep rule that used to be
  implied is not any more, and Loki reaches Shizuku's and Odin's APIs through paths R8 cannot always
  see statically. `pr-ci.yml` fails the build if R8 writes `missing_rules.txt`.
- Project-wide opt-ins live in `app/build.gradle.kts`: `ExperimentalMaterial3Api`,
  `ExperimentalMaterial3ExpressiveApi` (this is what makes `MaterialTheme.motionScheme` usable),
  `kotlin.time.ExperimentalTime`, `kotlin.RequiresOptIn`. **Not** opted in, so annotate locally:
  `ExperimentalLayoutApi`, `ExperimentalCoroutinesApi`, `FlowPreview`.
- Loki has **no** lint-clean baseline, so `warningsAsErrors` is off. Getting to zero comes first.

## Mirrors

`origin` (GitHub) is canonical. A Codeberg mirror is planned but **does not exist yet** — do not
invent a URL, and do not add the remote until it does. GitHub stays the branch of record for issues,
PRs, releases and CI.
