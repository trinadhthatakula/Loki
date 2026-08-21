<p align="center">
  <img src="app/src/main/launch-playstore.png" alt="Loki logo" height="512">
</p>

<h1 align="center">Loki — Logger</h1>

<p align="center">
  A logcat reader for Android that shows you what your apps are actually saying.
</p>

<p align="center">
  <a href="LICENSE"><img alt="License: GPL-3.0-or-later" src="https://img.shields.io/badge/license-GPL--3.0--or--later-blue.svg"></a>
  <a href="https://github.com/trinadhthatakula/Loki/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/trinadhthatakula/Loki?sort=semver"></a>
  <a href="https://github.com/trinadhthatakula/Loki/actions/workflows/pr-ci.yml"><img alt="CI" src="https://github.com/trinadhthatakula/Loki/actions/workflows/pr-ci.yml/badge.svg"></a>
  <img alt="minSdk 28" src="https://img.shields.io/badge/minSdk-28-brightgreen.svg">
</p>

- 100% Kotlin
- Material 3 design
- Jetpack Compose
- No ads, no trackers, no analytics

> ⚠️ **Early days.** Loki works, but it is young. Expect rough edges, and expect the occasional
> release that changes how something behaves.

## What does this do?

Ever wondered what goes on behind the scenes of your favourite apps? Loki lets you find out:

- **Initiate the spy work.** Pick any installed app and, with a tap, set Loki to work.
- **Track the trails.** Loki monitors and extracts that application's logs, catching every whisper
  and murmur until you tell it to stop.
- **Save the secrets.** When the mission's done, everything gathered is compiled into a text file.
- **Share the scoop.** Send the log file straight from the app.

It's like giving Loki a secret mission to peek into the digital diary of your apps.

## Will this work on my device?

Two things have to be true.

**Android 9.0 (API 28) or newer.** That's the floor. It used to be 7.0, and if you are on 7.x or
8.x an older release will still install — but new ones won't.

**Root or Shizuku.** This is the part worth understanding before you install. Reading *another*
application's logs needs `android.permission.READ_LOGS`, which Android classifies as
`signature|privileged` — meaning **no app can ask you for it and no user can grant it**. Google
closed that door in Android 4.1, deliberately, because a log stream is a firehose of other apps'
secrets. So Loki borrows the privilege instead:

| Path | What you need | Notes |
|---|---|---|
| **Root** | Magisk, KernelSU or APatch | Loki runs its capture through a root shell (libsu). |
| **Shizuku** | [Shizuku](https://shizuku.rikka.app/) running | No root needed. Pair over wireless debugging or ADB; survives until reboot. |
| Neither | — | Loki can only read its own logs. Not useful. |

Loki's onboarding walks through whichever you have. If you have neither, Shizuku is the easier one
to get.

## Install

| Channel | Status |
|---|---|
| [**GitHub Releases**](https://github.com/trinadhthatakula/Loki/releases/latest) | ✅ Live — `master` publishes the stable, `dev` publishes pre-releases |
| [**Obtainium**](https://obtainium.imranr.dev/) | ✅ Point it at this repository for automatic updates |
| **IzzyOnDroid / F-Droid** | 🚧 Listing metadata is ready; not yet submitted |
| **Google Play** | ❓ An open question — see below |

Every release carries a `.sha256` next to the APK. Check it.

### About Google Play

Loki is not on Play, and that is an open question rather than an oversight. An app whose core
function is reading other applications' log output sits in genuinely unsettled territory under
Play's policies — `READ_LOGS` is exactly the permission those policies are wary of, and the fact
that Loki reaches it through root or Shizuku rather than by asking doesn't obviously help. Building a
Play publishing pipeline before knowing the answer would be building the expensive half first, so
the release machinery deliberately stops at GitHub Releases.

If you know how Play treats this class of app, please say so in an issue — that is the conversation
that needs to happen first.

## A word about your logs

Logcat is not a tidy stream of an app's own thoughts. It carries access tokens, URLs, account
identifiers, file paths, and data belonging to other people who use your device.

Loki never sends a capture anywhere. Files stay in Loki's own storage until you share them
deliberately. But once you *do* share one — in a bug report, a chat, an issue on this repository —
it is out of your hands. **Read a log before you post it.**

## Will this project be discontinued in future?

No. The plan is to grow this into something closer to an app crash detector; for now it reads,
filters, saves and exports logs.

## What other features are you working on?

- Live monitoring for apps

## Building from source

JDK 21 (Zulu, matching CI). No signing keys or secrets needed — a fresh clone builds unsigned.

```bash
git clone https://github.com/trinadhthatakula/Loki.git
cd Loki
./gradlew assembleDebug
```

The full gate CI runs on every pull request:

```bash
./gradlew assembleDebug testDebugUnitTest :app:compileDebugAndroidTestKotlin \
          lintRelease assembleRelease --stacktrace
```

## Contributing

Contributions are welcome, including small ones.

- [**CONTRIBUTING.md**](CONTRIBUTING.md) — how to build, which branch to target, what CI checks
- [**docs/branching-and-releases.md**](docs/branching-and-releases.md) — the `dev` → `master` ladder
  and how a merge becomes a release
- [**CODE_OF_CONDUCT.md**](CODE_OF_CONDUCT.md) — how we treat each other
- [**AGENTS.md**](AGENTS.md) / [**CLAUDE.md**](CLAUDE.md) — the same rules, for AI agents

The short version: branch from `dev`, open your PR against `dev`, don't bump `versionCode`, and if
you touch the privileged surface (`model/SuCli.kt`, `model/PermissionManager.kt`,
`services/LogcatService.kt`) say which privilege mode you actually tested under.

## Security

**Please don't open a public issue for a security vulnerability.** Use GitHub's private advisory
form: [Report a vulnerability](https://github.com/trinadhthatakula/Loki/security/advisories/new).
Details, and what's in and out of scope, in [SECURITY.md](.github/SECURITY.md).

## Licence

**[GPL-3.0-or-later](LICENSE).** You may use, study, modify and redistribute Loki, provided your
distributed version also ships its complete corresponding source under the same terms.

The Loki **name and icon** are trademarks and are **not** covered by the GPL — a fork must rename
and re-icon. This is not a restriction on your code freedoms; it exists so that a user installing
something called "Loki" knows where it came from, which matters more than usual for an app that
reads other apps' logs. See [TRADEMARK.md](TRADEMARK.md).

Loki bundles two fonts under the **SIL Open Font License 1.1**, which is GPL-compatible. The OFL
requires the licence and the copyright notice to travel with the font, so each ships verbatim from
upstream:

- **Outfit** — Copyright 2021 The Outfit Project Authors
  ([upstream](https://github.com/Outfitio/Outfit-Fonts)) — [`licenses/OFL-1.1-Outfit.txt`](licenses/OFL-1.1-Outfit.txt)
- **Fira Code** — Copyright 2014-2020 The Fira Code Project Authors
  ([upstream](https://github.com/tonsky/FiraCode)) — [`licenses/OFL-1.1-FiraCode.txt`](licenses/OFL-1.1-FiraCode.txt)

## Mirrors

**GitHub is canonical** — issues, pull requests, releases and CI all live here. A
[Codeberg](https://codeberg.org) mirror is planned and will be read-only when it lands.

## Sibling project

**[Thor](https://github.com/trinadhthatakula/Thor)** — an app manager for Android by the same
maintainer, sharing this project's governance, release discipline and trademark policy.

---

<p align="center">
  Made with 💜 by <a href="https://github.com/trinadhthatakula">Trinadh Thatakula</a>
</p>
