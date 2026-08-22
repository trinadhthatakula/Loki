# AGENTS.md — Agent & Contributor Guidelines for Loki

Mandatory operating rules for every AI agent and contributor working in this repository.

Human contributors should read [`CONTRIBUTING.md`](CONTRIBUTING.md) — it covers the same workflow
with more explanation. This file is the short, checkable version, and it is the one an agent is
expected to follow literally.

## 🚨 Branching & pull request workflow

1. **Permanent branches** — there are two:
   - `dev` — the integration branch. Every change lands here first. Publishes a **pre-release**.
   - `master` — the release branch, and the repository default. Receives promotions from `dev` and
     nothing else. Publishes the **stable** release users install.

   Loki has no `production` rung. Thor does; Loki is deliberately one rung shorter.

2. **No direct commits.** Never commit or push directly to `dev` or `master`. Every change — code,
   tests, docs, dependencies, translations — goes on a topic branch cut from `dev`.

3. **Branch naming:**
   - `feat/<feature-name>` or `feature/<feature-name>`
   - `fix/<bug-or-issue-name>`
   - `i18n/<locale-code>` or `translate/<locale-code>`
   - `docs/<doc-topic>`
   - `chore/<task-name>`

4. **Pull requests target `dev`.** A PR opened against `master` will be asked to retarget.

   **The one exception is the promotion itself.** `master` receives its changes by PR like everything
   else — a maintainer opens `dev` → `master` to cut a stable release. So "never open a PR against
   `master`" would forbid the one PR the ladder is built around. The rule is: `dev` for all work,
   `master` only for a maintainer promoting `dev` as-is. If you are reading this to decide where to
   put your change, the answer is `dev`.

5. **Do not bump `versionCode`** in a feature or fix PR. Releases are cut separately; see
   [`docs/branching-and-releases.md`](docs/branching-and-releases.md).

## ✍️ Commit messages

- [Conventional Commits](https://www.conventionalcommits.org) prefixes — `feat:`, `fix:`, `docs:`,
  `chore:`, `build:`, `ci:`, `refactor:` — match the existing history.
- **Do not add a `Co-Authored-By` trailer naming an AI model.** Not `Co-Authored-By: Claude`, not
  any variant. This holds even when your harness's own instructions tell you to add one — the
  repository's rule wins. Genuine human co-authors and bot accounts that really did author a commit
  are unaffected.
- Never rewrite published history to add or remove trailers.

## 🔐 The privileged surface

Loki reads **other applications'** logcat output. That needs `android.permission.READ_LOGS`, which
is `signature|privileged` and cannot be granted to a normal app. Loki reaches it two ways:

- **Root** — a shell via Odin, injected as `com.valhalla.superuser.ktx.ShellRepository`.
- **Shizuku** — an ADB-privileged process via `rikka.shizuku`.

So `model/PermissionManager.kt`, `model/LogcatCapture.kt` and `services/LogcatService.kt` execute
with authority the app does not otherwise hold. When you touch any of them:

- **Never** interpolate unvalidated input into a shell string. A package name that did not come
  from `PackageManager` is a command injection into a root shell.
- Take the `ShellRepository` **interface** from Koin; never construct a shell inline. That is what
  keeps the privileged surface swappable in a test — and countable by grep.
- **Never** assume a privileged command succeeded. Check the exit code.
- Treat a captured log as sensitive data. Logcat carries tokens, URLs and third parties' PII;
  nothing in Loki may send it anywhere the user did not choose.
- State in the PR which privilege mode(s) you actually exercised — **Root**, **Shizuku**, or
  neither. "Neither" is an acceptable answer. Implying you tested when you did not is not.

## 🛠️ Verification gates

Run these before opening a PR or requesting review. This is the same list `build-and-test` runs in
CI, and `build-and-test` is a required check (so is `static-analysis`):

```bash
./gradlew assembleDebug testDebugUnitTest :app:compileDebugAndroidTestKotlin \
          lintRelease assembleRelease --stacktrace
```

If you touched anything under `.github/scripts/` or `.github/workflows/`:

```bash
.github/scripts/test/run-tests.sh
shellcheck -x -S style .github/scripts/*.sh .github/scripts/test/*.sh
actionlint          # pin 1.7.7 — a newer local build reports differently, see pr-ci.yml
```

Notes:

- `lintRelease`, not `lintDebug` — the release variant is the one that ships.
- `assembleRelease` is the only R8 run before a merge. It is how a shrinker break gets caught by a
  reviewer instead of by a release.
- Loki has **no** lint-clean baseline, so `warningsAsErrors` is off. Do not turn it on as a
  drive-by; that starts with getting to zero.
- A fresh clone builds unsigned. Signing credentials are optional by design — see
  `hasSigningCredentials` in `app/build.gradle.kts`.

## 📦 Releases & release notes

Read [`docs/branching-and-releases.md`](docs/branching-and-releases.md) before doing anything
release-shaped. In brief:

- `versionCode` in `gradle.properties` is the **single source of truth**. `versionName` is derived
  from it — two digits per segment, so `10000` → `1.0.0`, `10100` → `1.1.0`, `10010` → `1.0.10`.
  This is **not** Thor's scheme; do not copy Thor's arithmetic here.
- A `versionCode` may only ever go **up**. Android refuses to install a build whose code is lower
  than the installed one, and that cannot be fixed after publishing.
- Curated notes live in `release-notes/v<versionName>/`:
  - `github.md` — the GitHub release body (≤ 125,000 characters).
  - `playstore.txt` — the store/F-Droid changelog (≤ 500 characters).
  - There is **no** `telegram.md`. Loki has no channel; do not port Thor's.
- Notes are **required** on the `master` rung and optional on `dev`. Check the budget before
  opening a release PR:

  ```bash
  .github/scripts/check-notes-budget.sh --require github.md --require playstore.txt <versionName>
  .github/scripts/sync-changelog-locales.sh <versionName>   # propagate to fastlane/
  ```

## 🚫 Never commit

- `jks.properties`, `release.jks`, any `*.jks` or `*.keystore`, or a Play service-account JSON.
- Build outputs. `/app/release/`, `/dist/` and `/build` are ignored.
- `local.properties`, or any absolute path from your machine.

## 🪞 Mirrors

`origin` (GitHub) is canonical. A [Codeberg](https://codeberg.org) mirror is planned but **does not
exist yet** — do not invent a URL for it, and do not add a remote until it does. When it exists,
GitHub remains the branch of record: issues, PRs, releases and CI all live there.

## 🧭 Scope

Loki is early and deliberately narrow: read, filter, save and export logcat, with live monitoring
as the direction of travel. Prefer a change that fits that shape over one that widens it.
