# Release notes

Curated notes for each Loki release live here, one directory per version:

```
release-notes/
  v1.0.0/
    github.md       ← the GitHub release body
    playstore.txt   ← the short store / F-Droid changelog
  v1.0.1/
    ...
```

The directory name is `v` + the **derived** `versionName`, not the `versionCode`. `versionCode=10000`
means `release-notes/v1.0.0/`. Two digits per segment; see
[`docs/branching-and-releases.md`](../docs/branching-and-releases.md) for the arithmetic and why it
differs from Thor's.

An un-prefixed directory (`release-notes/1.0.0/`) is tolerated by every script here, but `v` is the
convention — match it.

## The two files

| File | Consumed by | Hard cap | Required on |
|---|---|---|---|
| `github.md` | the GitHub release body, on both rungs | 125,000 characters | `master` |
| `playstore.txt` | `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt`, which IzzyOnDroid and F-Droid read | 500 characters | `master` |

There is deliberately **no `telegram.md`.** Thor has one because Thor has a channel; Loki does not,
so the whole caption-budget apparatus was left behind rather than ported. If Loki ever gets a
channel, add an input to `check-notes-budget.sh` — don't fork it.

The caps are real limits, not style guidance. 125,000 is GitHub's release-body maximum, and 500 is
the store changelog field. Exceeding either is a **failed publish**, which is why the check runs
before the build rather than after the tag is cut.

### `github.md`

Markdown, rendered on the release page. Write for someone deciding whether to update.

A shape that works:

```markdown
## Highlights

- The one or two things somebody would actually notice.

## Added
- ...

## Fixed
- Logs from a selected app stopped appearing after ~2 minutes under Shizuku (#42)

## Changed
- ...

## Internal
- Dependency bumps, CI, refactors. Fine to keep short.
```

Notes worth following:

- **Link the issue or PR** (`(#42)`) wherever there is one. It costs nothing and answers the next
  question.
- **Say if a change is privilege-mode-specific.** "Fixed under Shizuku" and "fixed" are different
  claims, and a user on root wants to know which they got.
- **Lead with impact, not mechanism.** "Capture no longer stops after two minutes" beats "refactored
  the service coroutine scope".
- Don't paste the commit log. The `dev` rung already falls back to one when notes are absent; the
  point of a curated file is to be better than that.

### `playstore.txt`

Plain text, no Markdown — F-Droid and IzzyOnDroid render it as-is. 500 characters, which is roughly
four short lines. It is a summary, not a truncation of `github.md`.

```
Fixes log capture stopping after a couple of minutes under Shizuku, and saved
logs no longer lose their app name after a restart.

New: filter by log level while capturing.
```

Emoji count as characters, not bytes — `check-notes-budget.sh` measures characters, because that is
how the services enforcing these caps measure them, so a "✨" costs 1 there and not 3.

That said, keep this file close to plain text and comfortably under budget rather than exactly at
it. The 500 is Play's limit; the F-Droid-family clients that read
`fastlane/.../changelogs/<code>.txt` do their own truncation, and a changelog written right up to
one service's cap is the one that gets cut off mid-sentence in another.

## Which rung needs what

| Rung | Notes | Behaviour when absent |
|---|---|---|
| `dev` → pre-release | optional | Falls back to the commit log since the last tag. |
| `master` → stable | **required** | Fails **before** the build. Nothing is published, no tag is cut. |

That asymmetry is the whole discipline: `dev` publishes often and a commit log is honest enough for a
pre-release, while `master` is the release users install and gets a body somebody wrote on purpose.

## Before opening a release PR

```bash
# 1. Check both files exist and fit their budgets. Run it exactly as master's rung will.
.github/scripts/check-notes-budget.sh --require github.md --require playstore.txt 1.0.1

# 2. Propagate playstore.txt into the fastlane tree for every locale that has metadata.
.github/scripts/sync-changelog-locales.sh 1.0.1

# 3. Confirm nothing is missing — this is what pr-ci.yml runs.
.github/scripts/sync-changelog-locales.sh --check 1.0.1
```

Note what step 2 does *not* do: it never overwrites an existing file. If a translator has already
written `fastlane/metadata/android/de-DE/changelogs/10001.txt`, their version stays. `--check` only
fails on **absence**, never on a translation differing from the English source — a translated
changelog is the goal, not a drift to be corrected.

The version argument is the `versionName` (`1.0.1`), with or without the `v`. The script reads the
matching `versionCode` from `gradle.properties` for the filename, or you can pass it explicitly as a
second argument when preparing notes ahead of the bump.

## Cadence

Loki has no fixed schedule. `dev` publishes a pre-release whenever a bumped `versionCode` lands;
`master` publishes a stable when a version is considered ready for people who are not following
development.

Unlike Thor, Loki does **not** distinguish "incremental dev release" from "cumulative major
release". Every stable stands on its own, and its `github.md` should describe everything since the
previous stable — not only what changed since the last pre-release.

## Common mistakes

| Symptom | Cause |
|---|---|
| Publish fails before building, on `master` | No `release-notes/v<name>/`, or `github.md`/`playstore.txt` missing from it. |
| `playstore.txt` rejected at 501 characters | It is a hard cap. 500 exactly passes. |
| Notes directory ignored | Named after the `versionCode` (`v10001`) instead of the `versionName` (`v1.0.1`). |
| `--check` fails in PR CI | `playstore.txt` exists but was never propagated — run `sync-changelog-locales.sh <name>`. |
| Stable release body is a commit log | Cannot happen. `require_notes: true` blocks the publish first; if you see one, the rung's inputs were changed. |
