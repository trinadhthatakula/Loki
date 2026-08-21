#!/usr/bin/env bash
# Pre-flight presence-and-size gate for release notes.
#
# usage: check-notes-budget.sh [--require <file>]... <version-name>
#
# Runs BEFORE anything is built or published. That placement is the whole point:
# every consumer of these files discovers a problem too late to help.
#
#   - The GitHub release body is capped at 125000 characters by the API. Over
#     that, `Create GitHub Release` fails after the APK has been built and the
#     tag has been pushed, leaving a tag with no release attached to it.
#   - `playstore.txt` is what an F-Droid-family builder shows as the what's-new
#     for this version, and it is the file a Play listing would eventually take.
#     Play caps it at 500 characters. Nothing truncates it for you; over budget
#     is a rejected submission or a blank changelog.
#
# --require names a file the calling rung actually CONSUMES, which turns a
# missing one from a warning into a pre-flight error. release-rung.yml passes
# the list when require_notes is true. It is a list rather than a --strict flag
# because WHICH files a rung reads depends on the rung: the dev rung falls back
# to the commit log and never reads github.md, and a gate on a file nothing
# reads can only ever fire falsely. Options must precede the positional
# arguments.
#
# Characters, not bytes. One emoji is a single character and four UTF-8 bytes,
# and both caps above are counted in characters by the service that enforces
# them - so `wc -c` would reject notes that are actually within budget.
set -euo pipefail

required=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --require)
      if [ "$#" -lt 2 ]; then
        echo "::error::--require needs a filename" >&2
        exit 2
      fi
      required+=("$2")
      shift 2
      ;;
    --require=*)
      required+=("${1#--require=}")
      shift
      ;;
    --)
      shift
      break
      ;;
    -*)
      echo "::error::unknown option: $1" >&2
      exit 2
      ;;
    *)
      break
      ;;
  esac
done

version_name="${1:?usage: check-notes-budget.sh [--require <file>]... <version-name>}"

GITHUB_CAP=125000
PLAYSTORE_CAP=500

# ${#required[@]} is safe on an empty array under set -u; "${required[@]}" is
# not, on the bash 3.2 that ships with macOS. Hence the guard rather than a
# bare expansion.
is_required() {
  local needle="$1" f
  if [ "${#required[@]}" -eq 0 ]; then
    return 1
  fi
  for f in "${required[@]}"; do
    if [ "$f" = "$needle" ]; then
      return 0
    fi
  done
  return 1
}

# `v1.0.1` is the convention; the bare `1.0.1` fallback exists so a hand-created
# directory that forgot the prefix is found rather than reported as missing.
dir="release-notes/v${version_name}"
[ -d "$dir" ] || dir="release-notes/${version_name}"
if [ ! -d "$dir" ]; then
  echo "::error::no release-notes directory for v${version_name}" >&2
  exit 1
fi

status=0

# Presence first, before any budget arithmetic. A rung that declares a file
# required has no fallback for it, so absence has to fail here - ahead of the
# build, the tag and the release - rather than degrade into a commit-log dump.
if [ "${#required[@]}" -gt 0 ]; then
  for f in "${required[@]}"; do
    if [ ! -f "$dir/$f" ]; then
      echo "::error::no $f in $dir. This rung consumes it and requires curated notes, so there is nothing to fall back to." >&2
      status=1
    fi
  done
fi

count_chars() {
  python3 -c "
import sys
print(len(open(sys.argv[1], encoding='utf-8').read()))
" "$1"
}

github="$dir/github.md"
if [ -f "$github" ]; then
  chars="$(count_chars "$github")"
  if [ "$chars" -gt "$GITHUB_CAP" ]; then
    echo "::error::github.md is ${chars} characters, the release-body cap is ${GITHUB_CAP}. The release would fail AFTER the tag is pushed." >&2
    status=1
  else
    echo "  github.md: ${chars}/${GITHUB_CAP} characters"
  fi
elif ! is_required github.md; then
  # Not an error when unrequired: the dev rung falls back to the commit log.
  echo "::warning::no github.md in $dir - the release body will fall back to the commit log" >&2
fi

playstore="$dir/playstore.txt"
if [ -f "$playstore" ]; then
  chars="$(count_chars "$playstore")"
  if [ "$chars" -gt "$PLAYSTORE_CAP" ]; then
    echo "::error::playstore.txt is ${chars} characters, cap is ${PLAYSTORE_CAP}." >&2
    status=1
  else
    echo "  playstore.txt: ${chars}/${PLAYSTORE_CAP} characters"
  fi
elif ! is_required playstore.txt; then
  # A warning rather than an error while there is no store listing to feed. It
  # becomes --require'd the moment a rung consumes it.
  echo "::warning::no playstore.txt in $dir - no store changelog will be written for this version" >&2
fi

exit "$status"
