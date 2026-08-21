#!/usr/bin/env bash
# Did this push change versionCode?
#
# usage: detect-version-bump.sh <old-ref> [properties-path]
# prints: changed=true|false
#         code=<integer>
#         name=<x.y.z>
#
# Compares the PARSED value, not `git diff --quiet -- gradle.properties`: that
# file also carries Gradle daemon flags, R8 switches and memory settings, and a
# JVM tweak is not a release. HEAD^ on a PR merge commit is the previous branch
# tip (first parent), which is the comparison we want.
#
# Fails OPEN on an unreadable OLD value - report a release and let the publish
# step adjudicate, rather than silently skipping a real one. An unreadable
# CURRENT value is a hard error: there is nothing to publish and nothing to
# compare. A readable old value that is GREATER than the current one is also a
# hard error - see the comment on that check below.
#
# This script only decides whether a push was MEANT to publish. It is not a
# uniqueness check: the release step's own tag collision is the backstop for
# republishing a version that already shipped.
set -euo pipefail

old_ref="${1:?usage: detect-version-bump.sh <old-ref> [properties-path]}"
props="${2:-gradle.properties}"

# Anchored, and rejects comments. gradle.properties documents the scheme in a
# comment block directly above the real assignment - several of those lines
# contain the string `versionCode` - and an unanchored match would feed the
# prose into arithmetic. The `= <digits>` tail with nothing after it also means
# a future `initialVersionCode=...`-style key cannot collide.
read_code() {
  grep -E '^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*[0-9]+[[:space:]]*$' \
    | head -n 1 | cut -d= -f2 | tr -d '[:space:]'
}

new_code="$(read_code < "$props" || true)"
if [ -z "$new_code" ]; then
  echo "::error::versionCode not found in $props" >&2
  exit 1
fi

old_code="$(git show "${old_ref}:${props}" 2>/dev/null | read_code || true)"

# Two digits per segment: 10000 -> 1.0.0, 10001 -> 1.0.1, 10100 -> 1.1.0.
# This MUST match calculateVersionName() in app/build.gradle.kts, because that
# function names the APK and this one names the tag and the release-notes
# directory. test-detect-version-bump.sh reads the Kotlin and pins the pair.
name="$((new_code / 10000)).$(((new_code % 10000) / 100)).$((new_code % 100))"

# A DECREASE is never a release, and it is not the fail-open case either. Left to
# the branch below it would report changed=true and publish, and the resulting APK
# cannot be installed over the one already on a user's device: Android refuses a
# lower versionCode as an update, and a code that has shipped cannot be reused.
# That is unrecoverable from the publish side, so it is caught here, before the
# build. Only checked when the old value is readable - an unreadable one still
# fails open, for the reasons at the top of this file.
if [ -n "$old_code" ] && [ "$old_code" -gt "$new_code" ]; then
  echo "::error::versionCode went DOWN: $old_code -> $new_code in $props. Android will not install a lower versionCode over a higher one, and a code that has already been published cannot be reused. Restore $old_code, or pick a value above it." >&2
  exit 1
fi

if [ -n "$old_code" ] && [ "$old_code" = "$new_code" ]; then
  echo "::notice::versionCode unchanged at $new_code - building for verification only." >&2
  echo "changed=false"
else
  echo "::notice::versionCode ${old_code:-<unreadable>} -> $new_code - publishing." >&2
  echo "changed=true"
fi

echo "code=$new_code"
echo "name=$name"
