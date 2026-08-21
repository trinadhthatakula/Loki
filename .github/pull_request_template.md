<!--
Thanks for contributing to Loki.

Two things before you fill this in:
  • PRs target `dev`, not `master`. If GitHub picked `master` for you, change the base.
  • Don't bump `versionCode` in gradle.properties. Releases are cut separately —
    see docs/branching-and-releases.md.
-->

## What & why

<!-- What does this change, and what problem does it solve? Link the issue: "Fixes #123". -->

## Changes

<!-- The short list. One line per meaningful change; skip the file-by-file tour. -->

-

## Testing

<!--
How did you verify this? "It compiles" is not testing. If you couldn't test something,
say so plainly — an untested change that says it's untested is reviewable; one that
implies otherwise is not.
-->

- [ ] Builds on JDK 21: `./gradlew assembleDebug testDebugUnitTest :app:compileDebugAndroidTestKotlin lintRelease assembleRelease`
- [ ] Ran on a device / emulator

**Privilege mode(s) actually exercised:** <!-- Root / Shizuku / neither / not applicable -->

<!--
This line is not decoration, and "not applicable" is a fine answer for a docs or UI
change. It matters because Loki reads other apps' logcat through android.permission.
READ_LOGS, which is signature|privileged — so it gets there either through a root
shell (model/SuCli.kt) or through Shizuku (rikka.shizuku), and the two paths behave
differently. For anything touching model/SuCli.kt, model/PermissionManager.kt or
services/, this is the only way a reviewer knows what was really run.
-->

## Screenshots / recordings

<!-- For any user-visible change. Before/after if you're changing something that exists. -->

## Checklist

- [ ] Scoped to the stated purpose — no unrelated edits, no drive-by reformatting
- [ ] Targets `dev`
- [ ] No `versionCode` change in `gradle.properties`
- [ ] No secrets, keystores, `jks.properties` or local paths committed
- [ ] Docs updated if this changes how Loki is used or built
- [ ] If this touches the privileged surface: no unvalidated input reaches a shell string, and
      nothing sends a captured log anywhere the user did not choose
