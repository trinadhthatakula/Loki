# 🪜 Branching and releases

How Loki's branches fit together, and how a commit travels from a pull request to a published APK.

**Read this if** you are opening a PR, wondering which branch to target, or about to cut a release.
For *writing* the release notes themselves, see
[`release-notes/README.md`](../release-notes/README.md) — this document covers the routing, that one
covers the content.

Loki's model is [Thor's](https://github.com/trinadhthatakula/Thor/blob/master/docs/branching-and-releases.md)
with one rung removed. Thor has three branches because it has three Google Play tracks to feed;
Loki has no Play listing yet, so it has two. The shared implementation is written so a third rung is
an added caller, not a rewrite — see *[Adding a rung](#adding-a-rung)*.

---

## The two branches

Loki has exactly two permanent branches. Everything else is a short-lived topic branch.

| Branch | What it is | Who merges into it |
|---|---|---|
| `dev` | Integration branch. All work lands here first. | Anyone, via PR |
| `master` | What the public is running. The default branch. | Maintainer, via PR from `dev` |

```text
  feature/x ─┐
  fix/y ─────┼──▶ dev ──────────▶ master
  translate/z┘     │                 │
                   ▼                 ▼
            GitHub pre-release   GitHub release
         v<name>-dev-<run>-<try>    v<name>
                                (Latest — what
                                 Obtainium and
                                 IzzyOnDroid see)
```

**Never delete `master`.** It is the default branch and the release lane.

---

## If you are contributing

1. Branch from `dev`. Name it `feature/<name>`, `fix/<name>` or `translate/<locale>`.
2. Open your PR **against `dev`**. Never against `master`.
3. Do **not** bump `versionCode` in your PR. Releases are cut separately — see below.
4. CI will run build, unit tests, instrumented-test compilation, lint, an R8 release build, CodeQL
   and the workflow/shell static-analysis gates on your PR.

That is the whole contributor story. The rest of this document is about what happens after your
work is merged.

---

## The release ladder

A release climbs two rungs, one per branch. **A rung is identified by the branch it runs on** —
there is no arithmetic on the version number anywhere in the routing.

| Merge | Workflow | GitHub | Notes required |
|---|---|---|---|
| `<topic>` → `dev` | [`1-dev-publish.yml`](../.github/workflows/1-dev-publish.yml) | pre-release `v<name>-dev-<run>-<attempt>` | ❌ falls back to the commit log |
| `dev` → `master` | [`2-master-release.yml`](../.github/workflows/2-master-release.yml) | **release** `v<name>` (Latest) | ✅ hard requirement |

Both are thin callers of one shared implementation,
[`release-rung.yml`](../.github/workflows/release-rung.yml). They differ only in the inputs they
declare. **If a rung needs to behave differently, add an input — do not fork the workflow.**

### Rung 1 — `dev`

Builds a release APK from the merged tree and publishes it as a GitHub **pre-release** tagged
`v<name>-dev-<run-number>-<attempt>`. The run number and attempt, not the version code, are what
make the tag unique: the run number changes per push, and the attempt changes per re-run — so
retrying a publish that failed *after* cutting its tag mints a new pre-release instead of
overwriting the one already there.

The APK is **signed when the four signing secrets are configured, and unsigned when they are not**
— see [*Signing*](#signing) below. That is deliberate, so the ladder can be exercised before a
keystore exists, and an unsigned release says so in its own body.

Notes are optional here. Without a `release-notes/v<name>/github.md` the release body falls back to
the commit log since the last tag. Curated notes are written once, for the stable.

### Rung 2 — `master`

Builds from `master` and mints the **only** release that is not marked pre-release. The tag carries
no suffix, because that is the tag Obtainium and IzzyOnDroid resolve.

Notes are **required** on this rung and the commit-log fallback is removed entirely. A `master` push
with a missing or oversized notes file fails **before** it builds anything — see *When something
goes wrong*.

### What "same version, two releases" means

Each rung builds its APK from **its own commit**, and AGP embeds that commit in
`META-INF/version-control-info.textproto`. So the `-dev` pre-release and the stable release for one
version carry **different bytes** even though they are the same version.

This is expected. It is not a signing problem and not a reproducibility problem: a rebuilder clones
at the tag, and each tag is pinned to the commit that produced it via `target_commitish`.

---

## Version numbers

A single integer in [`gradle.properties`](../gradle.properties) drives everything:

```properties
versionCode=10000
```

`versionName` is **derived** — `10000` → `1.0.0` — and must never be hand-edited. The arithmetic is
`code/10000 . (code%10000)/100 . code%100`, so two digits per segment:

| `versionCode` | `versionName` |
|---|---|
| `10000` | `1.0.0` |
| `10001` | `1.0.1` |
| `10100` | `1.1.0` |
| `20000` | `2.0.0` |

Two digits per segment and not Thor's one, because Loki's first published APK already carried code
`10000`. Emitting `1000` for `1.0.0` under Thor's `code/1000` scheme would be a versionCode
**decrease**, which Android refuses to install over an existing build.

The formula lives in **two** places that must agree —
[`app/build.gradle.kts`](../app/build.gradle.kts)'s `calculateVersionName()` and
[`detect-version-bump.sh`](../.github/scripts/detect-version-bump.sh) — because Gradle owns the APK
and the shell script owns the tag. `test-detect-version-bump.sh` pins them together, so changing one
without the other is a red check rather than a mislabelled release.

**Bumping `versionCode` is what makes a push a release.** A push to `dev` that does not change it
still builds and verifies, but publishes nothing. On `master` an unchanged version is a hard error,
because that would mean re-releasing a version that already shipped.

Bump the version in its own `chore(release)` commit, never mixed into a feature PR.

**It may only ever go up.** `detect-version-bump.sh` fails the run outright on a decrease rather
than publishing it, because that is the one version mistake nothing downstream can fix: Android
refuses a lower `versionCode` as an update, and a code that has already shipped cannot be reused.

---

## Signing

Four values, and they are **one credential set** — all four or none:

| | |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | the keystore itself, base64-encoded |
| `KEYSTORE_PASSWORD` | |
| `KEY_ALIAS` | |
| `KEY_PASSWORD` | |

Locally they come from a git-ignored `jks.properties` at the repo root (`keyAlias`, `keyPassword`,
`storePassword`, `storeFile`); in CI they come from repository secrets, and the rung decodes the
keystore into `app/release.jks` for the length of the job.

**None of them is required to build.** With no credentials anywhere,
[`app/build.gradle.kts`](../app/build.gradle.kts) assigns no `signingConfig` and the release APK
comes out **unsigned** — which is the correct result for anyone rebuilding from source, and the
state this repository is in until the four secrets are added. Both rungs still run and still
publish; the release body says, in as many words, that the APK is unsigned and will not install.

**A partial set is a hard error, not a fall back to unsigned.** Three secrets set and one blank
fails the rung before the build, and a `jks.properties` missing a key fails Gradle at
configuration. The alternative is worse than either: an APK that came out unsigned while the
release body claimed it was signed.

> `${{ secrets.X }}` expands to the **empty string** for a secret that does not exist, so "set but
> blank" and "absent" are the same thing to a workflow. Everything here treats blank as absent for
> that reason.

---

## Release notes

Curated notes live in `release-notes/v<name>/` and are **required on the `master` rung only**. Below
it they are optional and the GitHub body falls back to the commit log.

| File | Consumed by | Limit |
|---|---|---|
| `github.md` | the GitHub release body | 125,000 characters — the GitHub API's cap on a release body |
| `playstore.txt` | F-Droid / IzzyOnDroid changelog; Play later | 500 characters |

Check before you push, not after. For a `dev` push, where notes are optional, sizes are all there is
to check:

```bash
.github/scripts/check-notes-budget.sh 1.0.1
```

For a **stable** release, pass the same `--require` flags the `master` rung passes, so that a
missing file fails on your machine instead of in the release run:

```bash
.github/scripts/check-notes-budget.sh --require github.md --require playstore.txt 1.0.1
```

That gate runs pre-flight in CI, *before* the build, so an oversized or missing file costs you a
failed check rather than a half-finished release.

`playstore.txt` also has to reach
`fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt` for **every** locale, not just
`en-US` — that directory is what an F-Droid-family builder reads, and a locale missing a file for
this code gets no changelog at all rather than the previous one. One command does it:

```bash
.github/scripts/sync-changelog-locales.sh 1.0.1
```

It never overwrites an existing file, so running it after a translator has written a real changelog
is safe. `--check` is the same walk with no writes, and that is what CI runs — so forgetting the
sync is a red check on the release PR rather than a blank changelog found in the store.

---

## Mid-cycle bug fixes

**There is no hotfix bypass, and that is deliberate.**

A fix lands on `dev` with a *new* `versionCode`, superseding the previous candidate, and climbs the
ladder normally. A fast lane straight to `master` would mean a stable release whose tree never
passed through the integration branch — which is the entire thing `dev` exists to prevent.

A fix that genuinely cannot wait for the ladder is a fix that should ship as a new version, which is
what this does. The ladder is two merges long; it is not slow.

---

## After a release: the back-merge

`master` picks up a merge commit that `dev` does not have. Level `dev` back up before starting the
next cycle, or the next topic branch forks from a tree missing the release:

```bash
git checkout dev && git pull
git merge --no-ff origin/master -m "Merge branch 'master' into dev"
git push origin dev
git rev-list --count origin/master ^dev   # expect 0
```

This push goes directly to `dev`, which the `DevRules` ruleset permits through a repository-role
bypass. It is the one and only exception to "never push directly to `dev`".

---

## Where builds end up

| Channel | Source | Notes |
|---|---|---|
| GitHub Releases | every rung | only the `master` rung is not a pre-release |
| Obtainium | `/releases/latest/` | resolves to the `master` rung's release |
| IzzyOnDroid | the `v<name>` tag | rebuilt from source, verified against the tag — **not yet submitted**, see below |
| Google Play | — | **not yet available**, see below |

### Google Play — an open question, not an oversight

Loki reads other applications' logcat output, which needs `android.permission.READ_LOGS`. Play's
policy on apps whose core function is reading other apps' log output is, at best, unsettled, and
this is not the sort of thing to discover after building the whole publishing pipeline.

So the ladder deliberately stops at GitHub. `release-rung.yml` carries no fastlane, no Play service
account and no track inputs. That is a decision to revisit, not a gap to fill — see
*[Adding a rung](#adding-a-rung)* for what it would take, and open a discussion before starting.

### IzzyOnDroid

The metadata IzzyOnDroid needs already exists under
[`fastlane/metadata/android/`](../fastlane/metadata/android), and the stable rung tags releases the
way its rebuilder expects. Submission itself is a manual step against IzzyOnDroid's own repo and has
not been done yet.

Two things have to be true first, and neither is today: the four signing secrets have to be
configured, because an unsigned APK is not a candidate for anything (see [*Signing*](#signing)), and
the listing metadata has to describe the revision being submitted. Land listing changes **before**
tagging — a store page and an APK that disagree about what the app does is the one failure mode
nobody notices from inside the repository.

---

## Adding a rung

The two callers are thin on purpose. To add a third branch (say `production`, or a Play track):

1. Add the branch and a ruleset for it (mirror `DevRules`).
2. Copy `2-master-release.yml` to `3-<branch>-<verb>.yml`, change the `branches:` filter, the
   `concurrency.group`, and the inputs.
3. If the new rung needs behaviour the current inputs cannot express, **add an input** to
   `release-rung.yml` and give the existing callers the value that preserves their behaviour.

Do not fork `release-rung.yml`. Thor's three rungs share one file for exactly this reason: the
alternative is three copies of the notes gate and the tag-pinning logic, drifting apart one fix at a
time.

---

## When something goes wrong

| Symptom | Cause | Fix |
|---|---|---|
| `master` rung fails before building | `require_notes` — `release-notes/v<name>/` is missing a required file | Add the missing file, push again. Nothing was published. |
| `versionCode is still N` on the `master` rung | Unchanged version — nothing new to release | Land a `chore(release)` bump first. |
| A `dev` rung publishes nothing | `versionCode` unchanged since the last release | Expected. Bump it if you meant to release. |
| The release APK is unsigned | `KEY_ALIAS` and friends are not set as repository secrets | Add the four signing secrets. Until then the workflow still succeeds and publishes an **unsigned** APK — deliberate, so the ladder works before the keystore exists. |
| `Release ... failed_on_unmatched_files` | The build produced no APK where the release step looked | Read the Gradle log above it; the release step is not the failure. |
| Checks sit `queued` for hours, no logs | Usually the account's included Actions minutes are exhausted — **not** a hung job | Check billing first. A quota block looks identical to a stuck runner from the PR page. |
| Every job fails in `Set up job` with `Failed to resolve action download info` | A GitHub Actions incident — this is before checkout, so no repository code ran | Nothing to fix. Confirm at `githubstatus.com`, re-run when it clears. |

---

## Verifying locally

```bash
.github/scripts/test/run-tests.sh                     # shell script tests
find .github/scripts -name '*.sh' -print0 | xargs -0 shellcheck -x -S style
./gradlew testDebugUnitTest --rerun-tasks
```

For `actionlint` and `shellcheck`, use **the versions CI pins**, not whatever your package manager
installed. Both tools change their findings across versions — actionlint 1.7.7 raises an SC2153 that
1.7.12 does not, and shellcheck renamed SC2317 to SC2329 in 0.10 — so a newer local build is a green
run that proves nothing about the job. Both pins, with their checksums and a fetch command, are at
the install steps in [`pr-ci.yml`](../.github/workflows/pr-ci.yml).

---

## File map

| Path | Role |
|---|---|
| `.github/workflows/release-rung.yml` | the shared rung implementation |
| `.github/workflows/1-dev-publish.yml` | `dev` rung caller — pre-release |
| `.github/workflows/2-master-release.yml` | `master` rung caller — stable release |
| `.github/workflows/manual-build.yml` | build an APK on demand, publish nothing |
| `.github/scripts/detect-version-bump.sh` | decides whether a push publishes |
| `.github/scripts/check-notes-budget.sh` | pre-flight notes presence and size gate |
| `.github/scripts/sync-changelog-locales.sh` | propagates `playstore.txt` to every fastlane locale |
| `release-notes/README.md` | how to write the notes |
