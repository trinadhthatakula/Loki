# Review — anonymous source drop (`~/Downloads/Loki`)

Reviewed as an untrusted external PR. The drop is a **plain source tree with no git history**: 124
files, 34 Kotlin. Structural comparison against `dev`:

| | count |
|---|---|
| identical | 32 |
| modified | 26 |
| new in drop | 15 |
| present in `dev`, absent from drop | 5 |

It is based on an **older** Loki than `dev`, so every build file in it is a downgrade (see §7).

**Overall:** there is real, wanted work here — the theme system, the log viewer, the in-app file
explorer, the `FileObserver` live refresh and the Settings screen are all things Loki should have.
There is also a fake progress delay, a non-functional feature, a permission grant nobody asked for,
two path-traversal holes, a crash on API 28, and an OOM waiting for a large capture. Nothing here
should land as-is; most of it should land after rework.

Counts: **accept 6 · accept-with-fixes 11 · reject 14**.

---

## 1. Blockers — must be fixed before any of this ships

### 1.1 Saved logs move to external storage, where other apps can read them (CRITICAL)

`di/Modules.kt`, `services/LogcatService.kt`, `services/LokiDocumentsProvider.kt`,
`ui/settings/*`, `ui/crash/*` all move log storage from `filesDir` to
`getExternalFilesDir(null)/Loki`.

`minSdk` is **28** (see §10.8), and scoped storage only arrived in API 29. So on API 28 — the
floor, and still a supported level — any app holding `READ_EXTERNAL_STORAGE` can read
`Android/data/com.valhalla.loki/files/**`. Captured logcat carries auth tokens, URLs and third
parties' PII, and `AGENTS.md` rule 2 treats it as sensitive data.

Raising the floor narrowed this from four API levels to one; it did not fix it. "Only the oldest
supported release leaks the user's logs" is not an acceptable resting place, and the argument stops
depending on API levels at all the moment you note that `filesDir` costs nothing to keep.

`filesDir` is private on every API level.

**Resolution:** keep logs in `filesDir` and point the DocumentsProvider at `filesDir`. The
motivation for the move was to make logs reachable from a file manager — but exposing private
storage through a DocumentsProvider *is* the supported way to do that, so the provider gives them
the feature without the exposure. This costs the drop nothing and is the single most important
change to make.

Secondary: `getExternalFilesDir(null)` returns null when no volume is mounted. `Modules.kt` passes
that straight into `File(androidDataFolder, "Loki")` and `LokiDocumentsProvider` does
`context!!.getExternalFilesDir(null)`.

### 1.2 `LokiDocumentsProvider` — path traversal in `createDocument` and `renameDocument` (HIGH)

The `resolve()` guard is correct and well written — canonical path, `startsWith(root + separator)`,
`runCatching` fallback. The contributor clearly thought about this. But three call sites bypass it:

- `createDocument` resolves the *parent*, then builds `File(parent, displayName)` and creates it
  without re-validating. `displayName` comes from the calling app. `../../../x` escapes `rootDir`.
- `renameDocument` has the same gap via `File(file.parentFile, displayName)` — a rename can move a
  file out of the root.
- `isChildDocument` compares raw `absolutePath`, not canonical, so
  `("/root", "/root/../etc/passwd")` returns `true`.

**Fix:** route every constructed path back through `resolve()`, and reject a `displayName`
containing `File.separator` or `..` outright.

Also in that file: `Root.FLAG_SUPPORTS_SEARCH` is advertised with **no `querySearchDocuments`
override** (search silently fails); `Document.FLAG_SUPPORTS_WRITE` lets external apps modify saved
logs — read + delete is enough for a log reader; no `notifyChange` after create/delete/rename, so
the picker never refreshes; and `com.valhalla.loki.R.mipmap.launch` is fully qualified instead of
imported.

### 1.3 `AppListViewModel` — a fake progress bar that sleeps 10 ms per installed app (HIGH)

```kotlin
val allApps = userApps + systemApps          // already loaded, both lines above
allApps.forEachIndexed { index, _ ->
    _uiState.update { it.copy(loadingProgress = progress) }
    delay(10)                                 // <-- per app
}
```

The apps are already in memory. This loop does no work: it adds a deliberate **~3 s stall on a
300-app device** and emits 300 StateFlow updates, each recomposing the list. Pure regression.

**Reject the whole hunk.** Also in the same file: `//loadApps()` and `//viewModelScope.launch {`
scaffolding left commented around the live call, `// 0f to 1fff`, and `refreshApps()` wrapping
`loadApps()` in a second `viewModelScope.launch` when `loadApps()` already launches its own.

### 1.4 `FileObserver(File, Int)` is API 29+ — crashes on API 28 (HIGH)

`SavedLogsViewModel.startWatchingDirectory()` uses the `File` constructor, added in API 29.
`minSdk` 28 → `NoSuchMethodError` on launch for every user on Android 9. Raising the floor to 28
(§10.8) shrank the blast radius from four API levels to one, but a hard crash on a supported level
is still a crash: the `String` constructor is the fix, not the floor.

Two more problems with the same feature (the *idea* is good and worth keeping):

- It watches only the root, non-recursively. Logs are written to `<root>/<pkg>/<ts>.log`, so
  `CREATE` in a subdirectory **never fires** — it only notices new package directories.
- `delay(500)` inside `onEvent` launches a **new coroutine per event**, so 50 new files means 50
  concurrent full list reloads. That is not debouncing. Correct shape is one
  `MutableSharedFlow` + `.debounce(500)` collected once in `init`.

### 1.5 `LogViewerScreen` loads the entire log into memory (HIGH)

```kotlin
fullText = logFile.readText()                                     // ~2× file size (UTF-16)
allLines = fullText.split("\n").mapIndexed { i, ln -> i to ln }    // + a second full copy, boxed
```

Three to five times the file size in heap. Loki captures logcat, which is unbounded — a busy device
produces tens to hundreds of MB. This OOMs, and the app's whole purpose is producing the files that
kill it.

Same file: `out.writeText(...)` for "Save filtered logs" runs on the **main thread**; `performSearch`
does a full `.lowercase()` linear scan on the main thread; the split and filter after the IO read are
also on Main. And the filtered-output file is written into the saved-logs directory as
`*_filtered.txt`, where it then appears as a new "log".

**Fix:** read line-windowed (`useLines` + a paged buffer), cap what is held, move filter/search to
`Dispatchers.Default` with debounce, and write exports on IO.

### 1.6 `MANAGE_EXTERNAL_STORAGE` plumbing — dead, unguarded, and unwanted (HIGH)

`MainActivity.onActivityResult` checks `Environment.isExternalStorageManager()`:

- nothing ever launches `REQUEST_STORAGE_PERMISSION` — the constant is referenced only in the file
  that declares it;
- the permission is **not declared** in the manifest;
- `isExternalStorageManager()` is API 30+ with **no version guard** → `NoSuchMethodError` on 24–29
  if it ever were reached;
- All-files access is the single most scrutinised Android permission, and a log reader has no claim
  to it.

**Reject entirely**, along with the deprecated `onActivityResult` override.

---

## 2. Privileged surface

### 2.1 `LogcatService` uses a root shell to copy its own file (REJECT the root path)

```kotlin
Shell.cmd("mkdir -p \"$destDir\"").exec()
Shell.cmd("cp \"${tempLogFile.absolutePath}\" \"$destFile\" && chmod 664 \"$destFile\"").exec()
```

Both source and destination are the app's **own** directories. Plain Java `copyTo` already does this
and is already present as the fallback. Using a root shell for an operation the app is authorized to
perform is gratuitous privilege use, and it adds a shell-interpolation surface for nothing.
`chmod 664` on app-private external storage is meaningless.

The gate is also wrong: `isRootAvailable() || isShizukuAvailable()` guards a call to **libsu**, which
is root-only. On a Shizuku-but-not-rooted device this fires a `su` attempt that stalls and fails
before falling through.

Interpolation itself: `$destDir` and `$destFile` derive from `appToLog.packageName`, which comes from
`PackageManager`, and Android package names cannot contain `$`, backtick or backslash — so this is
not exploitable today. It still violates `AGENTS.md` rule 1, and the correct answer is to delete the
shell call rather than to escape it.

**Delete the root branch; keep the Java copy.** That removes the injection surface, the wrong gate
and the spurious root prompt in one edit.

Also in this file: `import android.os.Environment` unused; `stopLogging()` is dedented to 4 spaces
with a stray brace; the file loses its trailing newline; and

```kotlin
// === DO NOT DELETE TEMP FILE YET ===
// tempLogFile.delete()  ← COMMENT OUT THIS LINE
```

is an instruction to the reader left in shipped source. The stated intent (keep the temp file live
after stopping) is not achieved anyway — `stopSelf()` runs immediately, so `onDestroy` deletes it
seconds later.

**Accept:** `loki_temp_${appInfo.packageName}.log` — per-package temp files instead of one shared
`loki_temp_log.log` is a genuine fix for concurrent captures.

### 2.2 `PermissionManager.isRootAvailable()` called during composition

`SettingsScreen` lines 62–64 call `hasReadLogsPermission`, `isRootAvailable` and
`isShizukuAvailable` directly in the composable body. `isRootAvailable()` is
`ShellUtils.fastCmd("id -u")` — a **blocking shell round-trip on the main thread**, re-executed on
every recomposition.

**Fix:** hoist to a ViewModel, or `produceState` on `Dispatchers.IO`. This is also the change that
makes the Odin migration pay off (§8).

### 2.3 `SuCli.kt`

Removes one `// --- CORRECTED LOGIC ---` comment. **Accept** — noise removal.

---

## 3. Manifest and resources

| Change | Verdict |
|---|---|
| `LokiDocumentsProvider` declaration — `exported="true"` + `grantUriPermissions="true"` + `permission="android.permission.MANAGE_DOCUMENTS"` + `DOCUMENTS_PROVIDER` intent-filter | **Accept.** Textbook AOSP pattern; `MANAGE_DOCUMENTS` is signature-level and held only by system DocumentsUI. |
| `CrashCopyReceiver` — `exported="false"` | **Accept.** |
| `<uses-permission android:name="android.permission.READ_MEDIA_VISUAL_USER_SELECTED" />` | **Reject.** That permission covers *visual media* (photos/video), despite the name reading like "user-selected folders". Useless to a text-log reader and it shows up in the store listing. |
| Collapsing existing multi-line attributes onto single lines | **Reject** — pure churn across the whole file, hides the real changes. |
| Trailing newline removed | **Reject.** |

`res/xml/provider_paths.xml`:

- `external-path` → `external-files-path` narrowing: **accept** (strictly tighter).
- `<cache-path name="log_shares" path="log_shares/" />`: **accept** — and it *is* used correctly
  (§5.2).
- commented-out XML block at EOF: **strip**.
- pre-existing `<root-path name="root" path="."/>`: **not theirs**, but it lets the FileProvider mint
  a URI for any path on the device. Remove it separately.

`res/values/themes.xml` + `res/values-night/themes.xml` — **reject**:

- `parent="Theme.Material3.DayNight.NoActionBar"` drags in `com.google.android.material:material`,
  the Material Components **views** library, into a Compose-only app (CLAUDE.md: "No XML layouts")
  purely for a splash-screen parent style;
- `colorPrimary` is declared **twice**;
- hardcoded `@color/white` window background and `windowLightStatusBar=true` now conflict with
  Theme.kt's new `isAppearanceLightStatusBars` `SideEffect` — the XML wins until first composition,
  then flips, so a dark in-app theme on a light system theme flickers;
- `android:statusBarColor` is a no-op at `targetSdk` 36 (edge-to-edge enforced);
- a large commented-out copy of the previous theme sits at EOF.

The one good idea in it — `windowSplashScreenBackground` — can be added to the existing theme
without any of the above. `colors.xml`'s new `gray`/`gray_dark`/`gray_light` exist only for this
file; reject with it (else `UnusedResources`).

`res/values/strings.xml`: `app_name` "Loki" → **"Loki Logcat"**. That is a launcher-label rebrand —
**owner's decision**, not a contributor's. Flagged, not applied.

`res/values-en/strings.xml`, `res/values-hi/strings.xml` — **reject**. Each contains exactly one
string (`app_name`). `stringResource` is used **zero times** in the entire drop; every UI string is
hardcoded English. `values-en` also shadows the default `values` on English devices for no reason,
and a one-string `values-hi` makes Android and F-Droid/IzzyOnDroid advertise Hindi support that does
not exist. Localisation is worth doing — as its own change that actually extracts the strings.

`res/drawable/shizuku.xml`: **accept** (used by Settings). `res/drawable/crash.xml`: reject with the
Crash Logs feature (§4).

---

## 4. Crash Logs — the feature does not work end to end (REJECT)

`Loki.kt` installs an uncaught-exception handler, builds a crash string, puts it in a notification
extra, and `exitProcess(2)`. **Nothing ever writes a crash log to disk.**

`CrashLogsScreen` then scans for crash files, and independently:

- it looks in `getExternalFilesDir(null).parentFile/Loki` = `Android/data/<pkg>/Loki`, while
  `LogcatService`, `Modules.kt` and the DocumentsProvider all use
  `getExternalFilesDir(null)/Loki` = `Android/data/<pkg>/files/Loki`. **The paths differ by one
  level**, so the screen reads a directory nothing creates;
- if they did line up, the classifier is `readText().lowercase().contains("error" | "exception" |
  "fatal" | "crash")` — **every** logcat capture of any length contains "error", so every saved log
  would be reported as a crash;
- it calls `readText()` on **every file in the directory** on screen entry — see §1.5;
- both buttons are stubs: `Toast("Opening …") // TODO` and `Toast("More options coming soon")`.

So: reject `ui/crash/CrashLogsScreen.kt`, the `NavItem("Crash Logs")` entry, `res/drawable/crash.xml`
and the tab-3 branch in `HomeScreen`.

The crash **notification** works and the idea is good. The honest version is a follow-up: write the
trace to `filesDir/crashes/<ts>.log` in the handler, then give the screen something real to read.
That is a rewrite, not an integration, and it should be its own change.

### 4.1 `CrashCopyReceiver` (accept with fixes)

Correctly `exported="false"` and action-guarded. Three fixes:

- the crash text travels as an **Intent extra through a `PendingIntent`** — Binder transactions cap
  around 1 MB, so a large trace throws `TransactionTooLargeException` *while handling a crash*. Pass
  a file path once §4's follow-up exists;
- no `ClipDescription.EXTRA_IS_SENSITIVE`, so Android 13+ renders a clipboard **preview** of content
  that may contain tokens;
- Android 13+ already toasts on copy, so the manual `Toast` double-reports.

---

## 5. UI — accept with fixes

### 5.1 `ui/theme/*` — the strongest work in the drop (accept)

`Theme.kt` +125, `Color.kt` 10→86, `Type.kt` 33→107: a full explicit Material 3 light/dark scheme,
AMOLED override, `MaterialExpressiveTheme` + `MotionScheme.expressive()`, and a status-bar-appearance
`SideEffect`. Verified: all 58 colour identifiers `Theme.kt` references are defined in `Color.kt`,
`AppTypography` is defined in `Type.kt`, and `ExperimentalMaterial3ExpressiveApi` is **already**
opted in globally at `app/build.gradle.kts:110` — so this compiles as a set.

Fixes:

- **`amoledMode: Boolean = true`** → default `false`. As written, every existing user gets pure
  black on upgrade.
- `dynamicColor` default flips `true` → `false`. Defensible (brand identity, and there is now a
  toggle) but it *is* a visible change for every user on Android 12+. Calling it out.
- **`MainActivity` calls `LokiTheme(darkTheme = darkTheme)` only** — `dynamicColor` and `amoledMode`
  are never passed. So two of the three switches in the Settings dialog persist values that nothing
  reads. Wire them.
- AMOLED blacks `background`/`surface`/`surfaceVariant` but leaves `surfaceContainer*` grey, so
  cards stay lighter than the background.
- `LocalDarkTheme` is defined and provided but **never consumed**, and its KDoc describes "Lottie raw
  files under res/raw-night" — Loki has no Lottie and no `raw-night`. A Thor artifact. Drop it, or
  keep it with an accurate comment.

### 5.2 `ui/settings/SettingsScreen.kt` (accept with fixes)

Good: uses `koinInject()` for both `ThemeManager` and the logs `File` — correct DI, matches project
style; permission-status card; ADB-command copy; theme dialog. This is the feature Loki was missing.

Fixes:

- **`"© 2026 Loki • All Rights Reserved"`** in the About dialog **directly contradicts the
  repository's licence.** Must be corrected before this ships.
- "Made with ❤️ in INDIA / by NS" — a hardcoded personal credit. The contributor asked not to be
  identified, so this is contradictory on its face, and attribution in shipped UI is the owner's
  call. Flagged for decision.
- theme writes go to **DataStore *and* SharedPreferences *and* `ThemePreference.applyTheme`** — three
  writes, two stores that can disagree. Collapse to DataStore (§5.6).
- `PermissionManager` calls in the composable body — §2.2.
- hardcoded `Color(0xFF4CAF50)` / `Color(0xFFF44336)` bypass the theme and will read wrong in
  dark/AMOLED. Use `colorScheme.error` / add semantic colours to `Color.kt`.
- `deleteRecursively()` + `mkdirs()` on the main thread, return value ignored, and the toast claims
  success unconditionally. Also races a capture in progress.
- `openWithSystemFileManager` is guesswork: `rawPath.split("/storage/emulated/0/")[1]` breaks for a
  secondary user (`/storage/emulated/10/…`) and falls back to a hardcoded `primary:Android/data/…`
  document id — which **Android 11+ ExternalStorageProvider refuses to open** for `Android/data`.
  Its fallback then launches `ACTION_OPEN_DOCUMENT_TREE` with `startActivity` (not for-result), so
  the user picks a folder and nothing happens.
- `openWithOtherFileManager` calls `FileProvider.getUriForFile` on a **directory** (FileProvider
  cannot serve one) with MIME `"resource/folder"` (not a real type), then builds a
  `DocumentsContract.buildRootUri` whose root id is an **absolute filesystem path** handed to
  arbitrary apps. `ACTION_VIEW` on a root URI is not a documented launch contract.
  **Recommendation:** keep only the in-app explorer, or reduce the external route to one
  `ACTION_OPEN_DOCUMENT_TREE` with `EXTRA_INITIAL_URI` pointing at Loki's own DocumentsProvider.
- unused imports (`Environment`, `MainActivity`, `horizontalScroll`, `rememberScrollState`),
  `navigationIcon = {}`, `Column()`, and the App Version dialog duplicating the list item beside it.

### 5.3 `ui/settings/LogsExplorerScreen.kt` (accept with fixes)

A capable file browser: sort modes, search, multi-select, `rememberSaveable` for `currentDir`, a
`BackHandler` that unwinds selection → parent → close, and zip-and-share. The share path is
**correct** — it writes into `cacheDir/log_shares`, exactly matching the new `<cache-path>` entry,
and clears only that subdirectory.

Fixes:

- `remember(currentDir, search, sortMode, selectedFiles.size)` calls **`listFiles()` inside
  composition on the main thread**, re-running on every selection tap. StrictMode disk-read
  violation and per-tap jank. Move to a ViewModel.
- `Thread { … }.start()` for zip/share — raw thread in a coroutines codebase, and `startActivity` is
  called from it (hence the `FLAG_ACTIVITY_NEW_TASK`). Use `Dispatchers.IO` + main for the launch.
- `selectedFiles.forEach { it.deleteRecursively() }` on the main thread, result ignored, toast
  unconditional.
- single-file share uses `type = "*/*"` — should be `text/plain` so text viewers appear.
  (`SavedLogsScreen` already uses `text/plain` for the same action; they disagree.)
- delete dialog puts Cancel **and** Delete inside `confirmButton` with `dismissButton = null`;
  `properties = DialogProperties()` passes the default for nothing.
- `addToZip` recurses with no symlink or depth guard.
- `FileSaver` restores `currentDir` from a saved absolute path with no root check.

### 5.4 `ui/saved/LogViewerScreen.kt` (accept with fixes)

Nice UX — drag line-selection with edge auto-scroll, search with result stepping, level filters,
save-filtered. Its data layer is the problem (§1.5). Additionally:

- copy-to-clipboard ships the whole filtered log with no `EXTRA_IS_SENSITIVE`;
- `Icons.Default.ArrowBack` is deprecated in favour of `AutoMirrored` and is wrong under RTL;
- the auto-scroll loop hand-rolls frames with `delay(16L)`; `withFrameNanos` is the primitive.

### 5.5 `ui/saved/SavedLogsScreen.kt` / `SavedLogsViewModel.kt` (accept with fixes)

`FileObserver` live refresh — good idea, three defects in §1.4.

- **"Open with External" adds `FLAG_GRANT_WRITE_URI_PERMISSION` to a *view* intent** — granting
  arbitrary third-party apps **write** access to the user's saved log. Read-only.
- `application/octet-stream` fallback forces a download-style handler; `text/plain` is better.
- per-item `packageManager.getApplicationIcon()` inside `remember` — PackageManager disk I/O on the
  main thread for every row in a `LazyColumn`. The project already has `AppInfoGrabber` and
  `accompanist-drawablepainter`; icons belong in the ViewModel.
- `catch (e: Exception) { null }` swallowing.
- delete does nothing visible on failure.
- `extension in listOf("log","txt")` allocates a list per file — use `setOf`.
- constructor param renamed `filesDir` → `lokiDir` and the `logs/` subdirectory dropped. Coupled to
  §1.1; the clean version is a dedicated Koin binding for the logs directory rather than reusing the
  bare `filesDir` binding.
- `refreshLogs()` is a public alias for the already-public `loadSavedLogs()`, called by nothing.

### 5.6 `ThemeManager` vs `ThemePreference` — keep one

`data/ThemeManager.kt` (DataStore) is **correct**: `private val Context.dataStore by
preferencesDataStore(name = "settings")` at top level is the right pattern, one instance per process.
**Accept** — with `amoledFlow` defaulting to `false`, not `true`, and registered as
`singleOf(::ThemeManager)` to match `Modules.kt` style.

> Paths in this section are the **anonymous drop's** layout, not Loki's. Loki has no `data/` package
> (see `CLAUDE.md` — there is deliberately no Clean Architecture split here), so on integration this
> file landed at `app/src/main/java/com/valhalla/loki/model/ThemeManager.kt`.

`ThemePreference.kt` — **reject**. It stores the same setting a second time in SharedPreferences
under different values ("light"/"dark"/"system" vs "Light Mode"/…), and calls
`AppCompatDelegate.setDefaultNightMode`, which does **nothing** here: `MainActivity` is a
`ComponentActivity`, not `AppCompatActivity`, and the theme is resolved by `isSystemInDarkTheme()` in
Compose. It also pulls in `androidx.appcompat`. Pure liability.

`Loki.userThemeSelection` — **reject**. A `companion object var` holding UI state on the Application,
written from inside a composable body (an unguarded side effect during composition). `ThemeManager`
already exposes it as a Flow.

### 5.7 `ui/home/HomeScreen.kt` (accept with fixes)

- **`SettingsScreen(modifier = modifier)` drops `paddingValues`** — every other page applies it, so
  Settings draws under the navigation bar. Settings compensates with a magic
  `contentPadding = PaddingValues(bottom = 90.dp)`. Fix the padding, delete the magic number.
- exit-dialog rewrite — **reject.** It sets `title = null`, leaves `confirmButton = { }` empty with a
  comment, and moves two equally-weighted filled `Button`s into the `text` slot, centred with a
  hardcoded 38.dp gap, in reversed Yes/No order. AlertDialog's slots exist for focus order, TalkBack
  semantics and Material button placement. Keep the original.
- `BackHandler(enabled = pagerState.currentPage != 0 || uiState.showExitDialog.not())` mixes two
  concerns, and its body drives the pager directly *and* sets nav index while a `LaunchedEffect`
  already animates on nav index. Simplify to `viewModel.setNavIndex(0)`.
- `val colorScheme = MaterialTheme.colorScheme` assigned and never used (plus its import).
- `SavedLogActions.ViewLogInApp` branch is an empty comment; `SavedLogsScreen` renders the viewer
  itself, so the action is redundant.
- NavigationBar rounding/shadow: accept, cosmetic.
- tab indices must be renumbered once Crash Logs is dropped.

### 5.8 `ui/settings/ExplorerChooserDialog.kt` (accept)

Small and clean. Minor: inconsistent `onDismiss()` ordering between branches, "(Recommended)" baked
into a label, three `TextButton`s where a radio list would be more Material.

### 5.9 `MainActivity.kt`

- `POST_NOTIFICATIONS` requested unconditionally in `onCreate` — a permission prompt on first launch
  before the user has done anything. Tie it to starting a capture (which is what actually needs the
  foreground-service notification). Also uses the deprecated `requestPermissions` /
  `onRequestPermissionsResult` pair rather than the Activity Result API, in a project that already
  depends on `activity-compose`.
- `requestShizukuPermissionFromSettings()` — a near-duplicate of `requestShizuku()`, **public,
  called by nothing**. Reject; Settings already takes an `onRequestShizuku` callback, which is the
  right design.
- `Shizuku.addBinderReceivedListener` and friends are added on every invocation and never removed —
  listeners accumulate. Pre-existing, worsened by the duplicate.
- removing the commented-out `OnboardingScreen` block: **accept** (it was already dead) — but it
  leaves `canGoForward` computed and unused, and makes `ui/onboarding/` permanently unreachable while
  `Modules.kt` still registers its ViewModel. Decide: restore the gate or delete the screen. Not
  resolved here.
- `ThemePreference.applyTheme(this)` before `super.onCreate()` — rejected with §5.6.
- `ThemeManager(this)` constructed by hand while `Modules.kt` registers it — inject it.
- no trailing newline.

---

## 6. Reject outright

| File | Reason |
|---|---|
| `utils/FileActions.kt` | **The entire 55-line file is commented out** inside `/* … */` and referenced by nothing. Even enabled it is broken: MIME type written `"*-/*"` (invalid) in two places, `deleteFiles` calls `deleteRecursively()` on arbitrary paths, and `copyFiles`/`moveFiles` are toast-only stubs ("Copy feature ready (hook SimpleStorage)"). |
| `ui/crash/CrashLogsScreen.kt` + `res/drawable/crash.xml` + `NavItem("Crash Logs")` | §4 — non-functional end to end. |
| `ThemePreference.kt` | §5.6 — duplicate store, no-op mechanism, drags in appcompat. |
| `res/values/themes.xml`, `res/values-night/themes.xml`, `colors.xml` additions | §3. |
| `res/values-en/strings.xml`, `res/values-hi/strings.xml` | §3 — empty localisation shells. |
| `MANAGE_EXTERNAL_STORAGE` plumbing in `MainActivity` | §1.6. |
| `READ_MEDIA_VISUAL_USER_SELECTED` | §3 — wrong permission class. |
| Manifest reformatting churn | §3. |
| `AppListViewModel` progress loop | §1.3. |
| `LogcatService` root-copy branch | §2.1. |
| Exit-dialog rewrite in `HomeScreen` | §5.7. |
| `Loki.userThemeSelection` | §5.6. |
| All five build files | §7. |

---

## 7. Build files — all rejects, but they name real dependency needs

The drop predates `dev`, so `app/build.gradle.kts` (+43/−154), `gradle.properties` (+11/−69),
`gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties` and `settings.gradle.kts`
would **downgrade** the toolchain — reverting Kotlin 2.4.10, AGP 9.3.1, Compose BOM 2026.08.00,
`compileSdk` 37, R8 full mode with `strictFullModeForKeepRules`, the signing config and the Gradle
toolchain work from `7718dcb`. Take none of them.

What they legitimately need, to be declared **in `libs.versions.toml` at our versions** and
referenced as `libs.*` (never a hardcoded coordinate — CLAUDE.md):

| Dependency | For | Verdict |
|---|---|---|
| `androidx.datastore:datastore-preferences` | `ThemeManager` | **Add** |
| `kotlinx-coroutines-core` / `-android` | already transitive; Odin `api()`s coroutines-android. Thor declares it explicitly to keep its own pin — do the same | **Add (explicit pin)** |
| `androidx.lifecycle:lifecycle-viewmodel-ktx` | ViewModels | **Add** |
| `androidx.compose.material:material-icons-extended` | `Icons.Filled.*` in Settings | **Add via `libs`, knowingly.** R8 full mode strips unused icons so release impact is small; debug/build-time cost is real. Alternative: use Loki's own drawables for the handful needed. |
| `com.google.android.material:material` | only the rejected XML theme | **Reject** |
| `com.anggrayudi:storage:1.5.5` (SimpleStorage) | only the fully-commented-out `FileActions.kt` | **Reject** — an entire third-party storage library for zero live callers, added as a hardcoded coordinate |
| `maven("https://jitpack.io")` in `settings.gradle.kts` | libsu | **Reject** — unnecessary once Odin comes from Maven Central |
| `androidx.appcompat` | only `ThemePreference` | **Reject** |

---

## 8. libsu → Odin

Independent of the drop, and the mechanism for §2.1/§2.2. Thor is the reference:
`com.trinadhthatakula:odin:1.0.0`, published on Maven Central.

```toml
# gradle/libs.versions.toml
odin = "1.0.0"
odin = { module = "com.trinadhthatakula:odin", version.ref = "odin" }
```

```kotlin
// app/build.gradle.kts — replaces implementation(libs.topjohnwu.libsu.core)
implementation(libs.odin)
```

Three call sites hold every libsu reference in the tree:

| Site | Today | Odin |
|---|---|---|
| `PermissionManager.isRootAvailable()` | `ShellUtils.fastCmd("id -u") == "0"` — blocking, unbounded | `shell.isRootGranted()` — bounded suspend, never hangs or throws |
| `SuCli.kt:184` | `fastCmd(shell, "pidof $packageName")` | `shell.exec("pidof …")` → `ShellResult` with `isSuccess` / `code` / `stdout` / `stderr`, so rule 3 ("check the exit code") becomes checkable instead of inferred |
| `SuCli.kt` logcat capture | `ProcessBuilder` / libsu streaming | `Shell.cmd("logcat …").asFlow(): Flow<ShellLine>` with `line.isError` / `line.text` — a natural fit for the capture loop and for `LogcatService`'s StateFlow |

Odin also ships `com.valhalla.superuser.utils.escapeForShell`, the primitive Thor uses at every
interpolation site — the right tool for `AGENTS.md` rule 1 wherever a shell string must still be
built.

Koin binds `RealShellRepository` → `ShellRepository` in `di/Modules.kt`, as Thor does, which makes
the shell injectable and lets the privilege checks move off the main thread (§2.2) instead of being
`object`-level blocking calls.

`settings.gradle.kts` can optionally carry Thor's `-PodinDir=…` `dependencySubstitution` for
cross-repo development. Shizuku is untouched — Odin replaces libsu only.

---

## 9. Suggested integration order

Each step is independently reviewable and buildable. Steps 1–2 are prerequisites; 3 onwards are the
contributor's features, cleaned.

1. **libsu → Odin** (§8) — no behaviour change, smallest diff, unblocks the rest.
2. **Dependency additions** (§7) — datastore, coroutines pin, lifecycle-viewmodel, icons-extended.
3. **Theme system** (§5.1, §5.6) — `Color.kt` / `Type.kt` / `Theme.kt` + `ThemeManager`, AMOLED
   default `false`, `dynamicColor`/`amoledMode` actually wired through `MainActivity`. Drop
   `ThemePreference` and `Loki.userThemeSelection`.
4. **Settings screen** (§5.2) — licence line corrected, privilege checks off the main thread,
   external file-manager launchers reduced to the one supported route, attribution decision applied.
5. **DocumentsProvider** (§1.2) — traversal fixes, pointed at `filesDir` (§1.1), `FLAG_SUPPORTS_SEARCH`
   dropped or implemented, write flag removed, `notifyChange` added.
6. **Saved-logs improvements** (§5.5) — `FileObserver` with the API-24 constructor, recursive
   watching, real debounce; write flag removed from the view intent; icons off the main thread.
7. **Log viewer** (§5.4, §1.5) — windowed reading, filter/search off the main thread, sensitive-clip
   flag.
8. **In-app file explorer** (§5.3) — `listFiles()` out of composition, coroutines instead of `Thread`,
   `text/plain`.
9. **Navigation** — the drop adds four screens with no navigation: nested booleans, early `return`s
   from composables and full-screen overlays. This is the weakest structural part and the piece most
   worth doing deliberately. **Decided: AndroidX Navigation 3**, wired as Thor wires it (§10.4).

Follow-ups deliberately **not** in the above: crash-log-to-disk (§4), real string extraction and
localisation (§3), onboarding restore-or-delete (§5.9), removing the pre-existing
`<root-path>` from `provider_paths.xml` (§3).

## 10. Decisions — answered

All five questions this review raised have been decided by the owner, plus three UI decisions that
came out of the same conversation. They are binding on the integration; where a decision contradicts
something the drop does, the decision wins.

### 10.1 `app_name` stays "Loki" — **rename rejected**

The drop renames the launcher label to "Loki Logcat". Keep `"Loki"`. Rationale given: keep it
simple. The `<string name="app_name">` change is dropped; nothing else in the manifest depends on it.

### 10.2 Contributor credits — **out of the UI chrome, into a Settings note**

Every shipped-UI attribution the drop adds ("Made with ❤️ in INDIA", "by NS", and variants) is
removed. In its place, Settings carries a small acknowledgements entry thanking an anonymous
contributor.

This is not a slight — it is what the contributor asked for. They shared the source specifically on
the condition of not being identified, so the credit has to be real without being a name. Do not
invent a handle, initials, or a link, and do not reintroduce the strings anywhere else (splash,
about dialog, home screen footer).

### 10.3 Licence — **GPL-3.0-or-later, and the contradiction goes**

The project is GPL-3.0-**or-later**. The drop ships `© 2026 Loki • All Rights Reserved` in the About
surface, which is a direct contradiction of the licence the code is distributed under and is the kind
of string that causes real trouble for downstream packagers (F-Droid and IzzyOnDroid both read
licence metadata). It is deleted, not reworded around. Any other text asserting reserved rights goes
with it.

### 10.4 Navigation — **AndroidX Navigation 3**, following Thor

Step 9 uses Navigation 3, wired the same way Thor wires it, rather than `navigation-compose` or a
hand-rolled sealed nav state. Consistency with the sibling app is the deciding factor: the two
projects share governance, CI shape and now a root-shell library, and a porter moving between them
should not have to learn two navigation models.

This retires the drop's approach entirely — nested booleans, early `return`s from composables and
full-screen overlays are not navigation, and every one of them is replaced.

### 10.5 Onboarding — **unchanged, and still a follow-up**

Restoring or deleting the privilege gate stays out of this integration, as §9's follow-up list
already says. The gate remains commented out in `MainActivity`, together with the `canGoForward`
root probe, and `ui/onboarding/` keeps its Koin registration. Deciding it properly means deciding
what a first run should look like once Settings can report privilege state, which is a conversation
for after these nine steps land.

### 10.6 `dynamicColor` defaults to **`false`**

The drop's flip from `true` to `false` is accepted. Loki's own palette is what users should see on
first launch; Material You remains available as an explicit toggle in Settings. AMOLED mode also
defaults to `false` (§5.1).

### 10.7 Bottom sheets are the default surface

Thor leans heavily on bottom sheets and Loki should read the same way. Transient and
option-picking surfaces become `ModalBottomSheet` rather than dialogs or full destinations —
concretely: the explorer/directory chooser the drop implements as a dialog, log-viewer options
(filter, level, share), and saved-log item actions.

This is a presentation decision, not licence to make everything a sheet. A sheet is wrong for
anything that needs to survive rotation as a first-class location, anything the back stack should
own, and anything holding a long scrollable body — those stay real destinations under §10.4.

### 10.8 Asgard UI where it fits

The owner's Asgard UI library may be used where it genuinely replaces hand-rolled UI. "If needed" is
the operative phrase: it is not a mandate to adopt it screen-wide, and every component taken from it
has to clear the same bar as any new dependency (R8 full mode with no `missing_rules.txt`, no
Material 3 version conflict).

**This one moved Loki's `minSdk`.** Asgard 2.0.0 declares 28, and depending on a library above your
own floor fails `checkDebugAarMetadata` outright. Given the choice between lowering Asgard's floor
(publishing 2.0.1) and raising Loki's, the owner chose to raise Loki's — so the floor is **28**, not
24, from the commit that added Asgard onward, and Android 7.0/7.1/8.0/8.1 are no longer supported.
Every API-range claim elsewhere in this document has been restated against 28; the underlying
findings did not change, only how many API levels they affect.

### Scope note

Two items remain deliberately outside this integration and are **not** decisions pending: the
crash-log feature (§4, rejected as non-functional end to end) and real string extraction plus
localisation (§3). Both are recorded in §9's follow-up list.
