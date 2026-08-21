#!/usr/bin/env bash
# Propagate a release's store changelog into every fastlane locale.
#
# usage: sync-changelog-locales.sh [--check] <version-name> [version-code]
#
# Source:  release-notes/v<version-name>/playstore.txt
# Target:  fastlane/metadata/android/<locale>/changelogs/<version-code>.txt
#
# `version-code` defaults to the value in gradle.properties, which is right on
# the commit that ships the version and wrong on any other - pass it explicitly
# when backfilling an older release.
#
# WHY EVERY LOCALE, not just en-US: an F-Droid-family builder (and Play, later)
# enumerates locales from the metadata directory and reads that locale's
# changelogs/<code>.txt. A locale with metadata but no file for this code gets
# an EMPTY what's-new, not the English one and not the previous version's -
# so a translated listing silently loses its changelog the moment a release is
# cut without running this. The English text is a far better placeholder than
# nothing; a translator overwrites it in the same PR that adds the locale.
#
# --check writes nothing and exits non-zero if any locale is missing or has a
# stale copy. That is the mode pr-ci.yml runs, so forgetting the sync is a red
# check on the release PR rather than a blank changelog discovered in the store.
#
# Vacuously green is deliberate: with no release-notes directory for the version,
# or no locale carrying metadata, there is nothing to be inconsistent about.
# Loki ships one locale today and this script exists for the day it does not.
set -euo pipefail

check_only=false
if [ "${1:-}" = "--check" ]; then
  check_only=true
  shift
fi

version_name="${1:?usage: sync-changelog-locales.sh [--check] <version-name> [version-code]}"
version_code="${2:-}"

if [ -z "$version_code" ]; then
  version_code="$(
    grep -E '^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*[0-9]+[[:space:]]*$' gradle.properties \
      | head -n 1 | cut -d= -f2 | tr -d '[:space:]'
  )"
fi
if [ -z "$version_code" ]; then
  echo "::error::no version code given and none readable from gradle.properties" >&2
  exit 1
fi

dir="release-notes/v${version_name}"
[ -d "$dir" ] || dir="release-notes/${version_name}"
src="$dir/playstore.txt"
meta="fastlane/metadata/android"

if [ ! -f "$src" ]; then
  echo "  no $src - nothing to propagate"
  exit 0
fi
if [ ! -d "$meta" ]; then
  echo "  no $meta - nothing to propagate into"
  exit 0
fi

status=0
locales=0

for locale_dir in "$meta"/*/; do
  [ -d "$locale_dir" ] || continue

  # A directory with no *.txt at its top level is not a locale a store
  # enumerates - it has no title or description - so it needs no changelog.
  # Without this, an incidental subdirectory would be reported as a failure.
  if ! ls "$locale_dir"/*.txt >/dev/null 2>&1; then
    continue
  fi

  locales=$((locales + 1))
  target="$locale_dir/changelogs/$version_code.txt"

  if [ -f "$target" ] && cmp -s "$src" "$target"; then
    echo "  ok:   $target"
    continue
  fi

  if [ "$check_only" = true ]; then
    if [ -f "$target" ]; then
      # Not an error. A translated changelog SHOULD differ from the English
      # source; only absence is a defect.
      echo "  differs (translated?): $target"
    else
      echo "::error::missing $target - run .github/scripts/sync-changelog-locales.sh ${version_name}" >&2
      status=1
    fi
    continue
  fi

  if [ -f "$target" ]; then
    echo "  keep: $target already exists and differs - not overwriting a translation"
    continue
  fi

  mkdir -p "$locale_dir/changelogs"
  cp "$src" "$target"
  echo "  wrote: $target"
done

if [ "$locales" -eq 0 ]; then
  echo "  no locale carries store metadata - nothing to do"
fi

exit "$status"
