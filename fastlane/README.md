# fastlane/ — store listing metadata

**This directory does not mean fastlane is wired up.** There is no `Fastfile`, no `Gemfile`, no Ruby
anywhere in this repository, and no workflow invokes fastlane. Nothing here is executed.

What it is: a **directory layout**. The `fastlane/metadata/android/` convention was popularised by
fastlane's `supply` plugin, and **F-Droid and IzzyOnDroid adopted it** as the way a repository
declares its own listing. Both read these files straight out of the git tree — but *which revision*
of the tree differs between them, which is the next section and the only thing here that can bite
you. So the tree exists for them, not for Play — see the note on Google Play in
[`docs/branching-and-releases.md`](../docs/branching-and-releases.md) for why Loki has no Play
pipeline at all.

If Loki ever does publish to Play, this metadata is already in the shape `supply` expects. That is a
happy coincidence of the two tools sharing a layout, not a plan in progress.

## Land listing changes before you tag

The two consumers read **different revisions**, because they get the APK in different ways, and the
consequence of a late edit is opposite in each.

| | Where the metadata is read from | A listing edit landing *after* the tag |
|---|---|---|
| **F-Droid** | the same commit/tag it built the APK from — it builds from source, so the listing is pinned to the built revision | **invisible** until the next release cuts a new tag |
| **IzzyOnDroid** | the default branch as it stands when the index is next refreshed — it does not build, it takes the published APK | **goes live immediately**, describing an APK nobody can download yet |

So the same mistake fails in two directions. On F-Droid, fixing a typo in
`full_description.txt` means shipping a release to carry it. On IzzyOnDroid, merging copy about a
feature that is finished but unreleased publishes a promise the current APK does not keep.

Two rules, and the first is just the second's consequence:

1. **Edit the listing, then bump and tag** — in that order, in the same PR where practical. This is
   the one sequence both services agree on.
2. **Never merge forward-looking listing copy to the default branch.** It is safe against F-Droid
   and wrong on IzzyOnDroid, and nothing in CI can catch it, because "this sentence describes next
   month's build" is not a property a script can check.

A description that promises a feature the shipped APK does not have is a bug report you will never
see, because the person reading the store page is not the person who can file it.

Treat the table as current behaviour rather than a contract — it is how these services work today,
not something they have promised, and a distribution channel is free to change how it indexes. The
two rules above hold either way, which is why they are the part worth remembering.

The one file none of this applies to is `changelogs/<versionCode>.txt`: it is keyed to the version
code, so a client showing it has already matched it to a specific build and it cannot describe the
wrong release — see below.

## Layout

```text
fastlane/metadata/android/
  en-US/
    title.txt                ← app name, ≤ 50 characters
    short_description.txt    ← one line, ≤ 80 characters
    full_description.txt     ← the listing body, ≤ 4000 characters
    changelogs/
      10000.txt              ← per-release changelog, named by versionCode
    images/
      icon.png               ← 512×512
      featureGraphic.png     ← 1024×500
      phoneScreenshots/
        1.png, 2.png, ...
  <locale>/
    ...same structure
```

Locale directories use the BCP-47-ish codes F-Droid accepts — `en-US`, `de-DE`, `fr-FR`, `hi-IN`,
`te-IN`. A locale directory needs at least one top-level `.txt` to be recognised as a real locale by
`.github/scripts/sync-changelog-locales.sh`; a directory holding only images is skipped.

## Changelogs are generated, not hand-written here

Do **not** author `changelogs/<versionCode>.txt` by hand. The source of truth is
`release-notes/v<versionName>/playstore.txt`, and this script propagates it:

```bash
.github/scripts/sync-changelog-locales.sh 1.0.1
```

It copies `playstore.txt` into `changelogs/<versionCode>.txt` for every locale that has metadata,
and it **never overwrites an existing file**. That is the important property: a translator's
`de-DE/changelogs/10001.txt` survives, because a translated changelog is the goal, not drift to be
corrected. `--check` (which `pr-ci.yml` runs) fails only on a *missing* changelog, never on one that
differs from the English source.

The filename is the `versionCode`, not the `versionName` — `1.0.1` → `10001.txt`. F-Droid matches
changelogs to builds by code.

See [`release-notes/README.md`](../release-notes/README.md) for what goes in `playstore.txt` and the
500-character budget it has to fit.

## Translating a listing

1. Create `fastlane/metadata/android/<locale>/`.
2. Translate `title.txt` (usually leave "Loki" alone — it is a trademark, see
   [`TRADEMARK.md`](../TRADEMARK.md)), `short_description.txt` and `full_description.txt`.
3. Respect the character caps. They are store limits, not suggestions.
4. Run `.github/scripts/sync-changelog-locales.sh <versionName>` to seed the changelogs directory,
   then translate the file it wrote.
5. Open the PR against `dev` on a branch named `i18n/<locale>`.

`full_description.txt` accepts a small amount of HTML — `<b>`, `<i>`, `<u>`, and `•` for bullets.
Markdown is **not** rendered. Keep the tags that are already in the English file.
