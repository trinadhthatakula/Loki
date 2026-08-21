#!/usr/bin/env bash
# Real directory trees under mktemp -d, run with that directory as $PWD, because
# every path the script touches is relative.
set -euo pipefail
script="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/sync-changelog-locales.sh"
assertions=0
failed=0

ok() { assertions=$((assertions + 1)); echo "  ok: $1"; }
bad() { assertions=$((assertions + 1)); echo "  FAIL: $1"; failed=1; }

# A fresh fixture per case: locales carry a title.txt because that is what marks
# a directory as a locale a store enumerates.
fixture() {
  local d
  d="$(mktemp -d)"
  mkdir -p "$d/release-notes/v1.0.1"
  printf 'Fixed a thing.\n' > "$d/release-notes/v1.0.1/playstore.txt"
  printf 'versionCode=10001\n' > "$d/gradle.properties"
  local locale
  for locale in "$@"; do
    mkdir -p "$d/fastlane/metadata/android/$locale"
    printf 'Loki\n' > "$d/fastlane/metadata/android/$locale/title.txt"
  done
  echo "$d"
}

# 1. Writes the changelog for every locale, deriving the code from
#    gradle.properties when it is not passed.
d="$(fixture en-US de-DE)"
(cd "$d" && bash "$script" 1.0.1) >/dev/null
if [ -f "$d/fastlane/metadata/android/en-US/changelogs/10001.txt" ] \
  && [ -f "$d/fastlane/metadata/android/de-DE/changelogs/10001.txt" ]; then
  ok "writes <code>.txt for every locale, code read from gradle.properties"
else
  bad "expected changelogs/10001.txt in both locales"
fi
if cmp -s "$d/release-notes/v1.0.1/playstore.txt" \
  "$d/fastlane/metadata/android/de-DE/changelogs/10001.txt"; then
  ok "the written file is a copy of playstore.txt"
else
  bad "the written file should match playstore.txt byte for byte"
fi
rm -rf "$d"

# 2. An explicit code wins over gradle.properties - the backfill case.
d="$(fixture en-US)"
(cd "$d" && bash "$script" 1.0.1 10000) >/dev/null
if [ -f "$d/fastlane/metadata/android/en-US/changelogs/10000.txt" ] \
  && [ ! -f "$d/fastlane/metadata/android/en-US/changelogs/10001.txt" ]; then
  ok "an explicit version code overrides gradle.properties"
else
  bad "the explicit code 10000 should be the only file written"
fi
rm -rf "$d"

# 3. Never clobbers a translation. This is the property that makes the script
#    safe to re-run, which is the only reason it can be a documented habit.
d="$(fixture en-US de-DE)"
mkdir -p "$d/fastlane/metadata/android/de-DE/changelogs"
printf 'Ein Fehler behoben.\n' > "$d/fastlane/metadata/android/de-DE/changelogs/10001.txt"
(cd "$d" && bash "$script" 1.0.1) >/dev/null
if grep -q 'Ein Fehler' "$d/fastlane/metadata/android/de-DE/changelogs/10001.txt"; then
  ok "an existing translated changelog is left alone"
else
  bad "a translated changelog must not be overwritten"
fi
rm -rf "$d"

# 4. --check fails on a missing locale and writes nothing while doing it.
d="$(fixture en-US de-DE)"
mkdir -p "$d/fastlane/metadata/android/en-US/changelogs"
cp "$d/release-notes/v1.0.1/playstore.txt" \
  "$d/fastlane/metadata/android/en-US/changelogs/10001.txt"
if (cd "$d" && bash "$script" --check 1.0.1) >/dev/null 2>&1; then
  bad "--check should fail when a locale has no changelog for the code"
else
  ok "--check fails when a locale has no changelog for the code"
fi
if [ ! -e "$d/fastlane/metadata/android/de-DE/changelogs" ]; then
  ok "--check writes nothing"
else
  bad "--check must not create files"
fi
rm -rf "$d"

# 5. --check passes when every locale has a file, translated or not. A German
#    changelog SHOULD differ from the English source; only absence is a defect.
d="$(fixture en-US de-DE)"
for locale in en-US de-DE; do
  mkdir -p "$d/fastlane/metadata/android/$locale/changelogs"
done
cp "$d/release-notes/v1.0.1/playstore.txt" \
  "$d/fastlane/metadata/android/en-US/changelogs/10001.txt"
printf 'Ein Fehler behoben.\n' > "$d/fastlane/metadata/android/de-DE/changelogs/10001.txt"
if (cd "$d" && bash "$script" --check 1.0.1) >/dev/null 2>&1; then
  ok "--check passes when a locale's changelog is present but translated"
else
  bad "--check should not require the translation to match the English"
fi
rm -rf "$d"

# 6. A directory with no top-level *.txt is not a locale a store enumerates, so
#    it needs no changelog. Without this, an incidental subdirectory would fail
#    --check on every PR.
d="$(fixture en-US)"
mkdir -p "$d/fastlane/metadata/android/images"
mkdir -p "$d/fastlane/metadata/android/en-US/changelogs"
cp "$d/release-notes/v1.0.1/playstore.txt" \
  "$d/fastlane/metadata/android/en-US/changelogs/10001.txt"
if (cd "$d" && bash "$script" --check 1.0.1) >/dev/null 2>&1; then
  ok "a metadata-less directory is ignored"
else
  bad "a directory with no top-level *.txt should not be treated as a locale"
fi
rm -rf "$d"

# 7. Vacuously green, both ways: no notes for the version, and no metadata tree
#    at all. Neither is an inconsistency, and Loki has releases predating this
#    machinery.
d="$(fixture en-US)"
if (cd "$d" && bash "$script" --check 9.9.9) >/dev/null 2>&1; then
  ok "a version with no playstore.txt is not a failure"
else
  bad "a version with no notes should exit 0"
fi
rm -rf "$d"

d="$(fixture)"
rm -rf "$d/fastlane"
if (cd "$d" && bash "$script" --check 1.0.1) >/dev/null 2>&1; then
  ok "no fastlane tree is not a failure"
else
  bad "a missing fastlane tree should exit 0"
fi
rm -rf "$d"

# 8. No code anywhere is a hard error rather than a file named ".txt".
d="$(fixture en-US)"
: > "$d/gradle.properties"
if (cd "$d" && bash "$script" 1.0.1) >/dev/null 2>&1; then
  bad "an unreadable version code should exit non-zero"
else
  ok "an unreadable version code exits non-zero"
fi
rm -rf "$d"

echo "  ${assertions} assertion(s)"
[ "$failed" -eq 0 ]
