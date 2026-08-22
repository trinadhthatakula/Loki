# Contributing to Loki

Thanks for your interest in Loki. Bug reports, fixes, features and translations are all welcome —
this guide covers how to get a change from your machine into a release.

Loki is the sibling project of [Thor](https://github.com/trinadhthatakula/Thor) and deliberately
shares its conventions. If you have contributed to Thor, almost everything here will already be
familiar; the differences are called out where they exist.

---

## 🌿 Branching & pull request workflow (mandatory)

All contributors and AI agents follow this workflow:

1. **Base branch**: always branch from `dev` (`git checkout dev && git pull origin dev`).
2. **Topic branch naming**:
   - `feat/<feature-name>` or `feature/<feature-name>` for new features
   - `fix/<bug-name>` for bug fixes
   - `i18n/<locale-code>` or `translate/<locale-code>` for translations
   - `docs/<doc-topic>` for documentation
   - `chore/<task-name>` for dependencies and maintenance
3. **Never commit directly to a protected branch.** Do not push to `dev` or `master`.
4. **Target `dev` in PRs.** Your pull request goes to **`dev`**; one opened against `master` will be
   asked to retarget. `master` receives exactly one kind of PR — a maintainer promoting `dev` as-is
   to cut a stable release — and that is not a contribution route, it is the release step.
5. **No version bumps.** Do not edit `versionCode` in `gradle.properties` in your PR. Releases are
   cut separately; see [`docs/branching-and-releases.md`](docs/branching-and-releases.md).

> 📖 **Which branch does what?** Loki uses a two-rung release ladder — `dev` → `master` — and your
> PR always targets `dev`. [`docs/branching-and-releases.md`](docs/branching-and-releases.md) has
> the full picture, including how a merged commit becomes a release and why you should not bump
> `versionCode` in a feature PR.

---

## 💻 Code contributions

### Tech stack

| Area | Choice |
|---|---|
| Language | Kotlin, 100% |
| UI | Jetpack Compose + Material 3 (expressive APIs opted in) |
| DI | [Koin](https://insert-koin.io) — plain module DSL in `app/src/main/java/com/valhalla/loki/di/Modules.kt`, not Koin Annotations |
| Navigation | Navigation 3 (`navigation3-runtime` / `-ui`), routes in `ui/navigation/LokiRoute.kt` |
| Components | Asgard UI (`com.trinadhthatakula:asgard`) — also what sets `minSdk 28` |
| Root | Odin (`com.trinadhthatakula:odin`), injected as `ShellRepository` |
| Shizuku | `dev.rikka.shizuku:api` + `:provider` |
| Serialization | `kotlinx.serialization` |
| JDK | **21** (Zulu, to match CI) |

Loki is a **single-module** project: `settings.gradle.kts` includes `:app` and nothing else. If a
change wants a second module, say so in the PR description — that is an architecture decision, not
a detail.

### Layout

```text
app/src/main/java/com/valhalla/loki/
├── Loki.kt              ← Application class, Koin start-up
├── MainActivity.kt
├── di/Modules.kt        ← the single Koin module
├── model/               ← AppInfo, AppInfoGrabber, SavedLogs, LogcatCapture,
│                          PermissionManager, SelfPermissions + SelfPermissionGrabber
│                          (the launch-time self-grant), …
├── services/            ← LogcatService (foreground service, dataSync), LokiDocumentsProvider
└── ui/
    ├── navigation/      ← LokiRoute (the NavKey surface) + NavItem (bottom-bar items)
    ├── home/            ← HomeScreen + HomeViewModel — the shell: bottom bar over one NavDisplay
    ├── appList/         ← AppListScreen + AppListViewModel
    ├── onboarding/      ← OnboardingScreen + OnboardingViewModel
    ├── saved/           ← SavedLogsScreen + SavedLogsViewModel, LogViewerScreen + ViewModel
    ├── explorer/        ← LogsExplorerScreen + LogsExplorerViewModel
    ├── settings/        ← SettingsScreen + SettingsViewModel
    ├── theme/           ← Color / Theme / Type
    └── widgets/         ← Formatting and other shared composables
```

One package per screen, each holding its `*Screen.kt` and its `*ViewModel.kt`. New screens follow
the same shape.

A new screen needs three things, none of them automatic: a `LokiRoute` entry, an `entry<…> { }` in
`HomeScreen`'s `entryProvider`, and a Koin line in `Modules.kt`. There is no component scan and no
route generation — annotating a class does nothing here.

### The privileged surface — read this before touching it

Loki reads **other applications'** logcat output. That needs `android.permission.READ_LOGS`, which
is `signature|privileged|development` and therefore **not grantable to a normal app** by the user.
Loki gets it one of two ways:

- **Root** — a shell via Odin, injected as `com.valhalla.superuser.ktx.ShellRepository`.
- **Shizuku** — an ADB-privileged process via `rikka.shizuku`.

Either one is also what `SelfPermissionGrabber` uses at launch to grant Loki the permission itself.
The `development` flag is why `pm grant` is allowed to; the permission's supplementary gids are why
the grant kills the app's process. [`CLAUDE.md`](CLAUDE.md#self-granting-read_logs) writes out the
consequences, including why the grants are issued in a specific order.

That makes every change in `model/PermissionManager.kt`, `model/SelfPermissionGrabber.kt`,
`model/LogcatCapture.kt` and `services/LogcatService.kt` security-relevant. A command assembled from
a package name that came from anywhere but `PackageManager` is a shell-injection surface. When you
change one of these:

- Say in the PR which privilege mode(s) you tested under — **Root**, **Shizuku**, or **neither**.
- Never interpolate unvalidated user input into a shell string.
- Take the `ShellRepository` interface from Koin rather than constructing a shell inline, so the
  privileged surface stays swappable in a test and countable by grep.
- Never assume a privileged command succeeded — check the exit code, and prefer re-reading what
  the platform reports (`checkSelfPermission` after a `pm grant`) over trusting that status.
- Treat a captured log as sensitive data. Logcat carries tokens, URLs and PII; nothing in Loki may
  send it anywhere the user did not choose.

### Useful build commands

```bash
./gradlew assembleDebug                       # debug APK
./gradlew testDebugUnitTest                   # unit tests
./gradlew :app:compileDebugAndroidTestKotlin  # compile instrumented tests (no device needed)
./gradlew lintRelease                         # Android Lint on the shipping variant
./gradlew assembleRelease                     # minified R8 build — the one that catches keep-rule breaks
./gradlew clean
```

Those five are exactly what [`pr-ci.yml`](.github/workflows/pr-ci.yml) runs. Run them before
opening the PR and CI holds no surprises.

`assembleRelease` is the one people skip and the one that matters most:
`android.enableR8.fullMode` and `android.r8.strictFullModeForKeepRules` are both on in
[`gradle.properties`](gradle.properties), so a reflective access that used to survive shrinking can
stop surviving it without any warning from a debug build.

### Versioning

`versionCode` in [`gradle.properties`](gradle.properties) is the only version number a human edits.
`versionName` is **derived** from it (`10000` → `1.0.0`) and must never be set by hand. Do not touch
either in a feature PR.

---

## 🌐 Translations

Loki is currently **English only** — there is no `values-<locale>/` directory yet, so being the
first translator is easy and genuinely valuable.

1. Create `app/src/main/res/values-<locale-code>/strings.xml` (e.g. `values-de` for German,
   `values-hi` for Hindi).
2. Copy every `<string>` from
   [`values/strings.xml`](app/src/main/res/values/strings.xml) and translate the text. **Every
   `name=` must survive** — the resource merger keys on `name`, and a missing one is a
   `MissingTranslation` lint finding.
3. Get the `<plurals>` categories right for your language: exactly the ones CLDR defines for it, no
   more and no fewer. A category your language does not have is a lint error too.
4. Leave every `%1$s`, `%1$d` and `\n` exactly as the English has them, and escape a literal
   apostrophe as `\'`.
5. Run `./gradlew lintRelease` before opening the PR.

**A green build is not a proofread.** Name-set parity, placeholder parity and CLDR coverage all
answer *"is the string present and well-formed?"*. Nothing in this repo can answer *"is it correct
in the language?"* — so read your diff in the language, not just in the diff view.

Store-listing metadata lives under [`fastlane/metadata/android/`](fastlane/metadata/android) and is
translated the same way: create a directory for your locale (`de-DE`, `hi-IN`, …) alongside
`en-US/` and add `title.txt` (≤ 30 chars), `short_description.txt` (≤ 80) and
`full_description.txt` (≤ 4000). Plain text — no markdown.

---

## 🚀 Submitting

1. **Fork** the repository.
2. **Branch** from `dev`, named per the conventions above.
3. **Commit** with clear messages. [Conventional Commits](https://www.conventionalcommits.org)
   prefixes (`feat:`, `fix:`, `docs:`, `chore:`) are used throughout the history — please match.
4. **Verify** locally with the five commands above.
5. **Open a pull request against `dev`** and fill in the template. The privilege-mode line is not
   decoration; for anything touching logcat capture it is the only way a reviewer knows what was
   actually exercised.

CI will run build, unit tests, instrumented-test compilation, lint, an R8 release build, CodeQL and
workflow/shell linting on your PR. `build-and-test` and `static-analysis` are both required checks —
neither can be waved through.

---

## 📜 Ground rules

- By contributing you agree your work is licensed under
  [**GPL-3.0-or-later**](LICENSE), the same as the rest of Loki.
- Participation is covered by the [Code of Conduct](CODE_OF_CONDUCT.md).
- Security issues do **not** go in a public issue — see [SECURITY.md](.github/SECURITY.md).
- The Loki **name and icon** are trademarks and are not covered by the GPL. A fork must rename and
  re-icon; see [TRADEMARK.md](TRADEMARK.md).

Thank you for helping build Loki 💖
