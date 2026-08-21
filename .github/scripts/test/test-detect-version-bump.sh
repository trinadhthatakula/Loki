#!/usr/bin/env bash
# Builds a throwaway git repo per case so the assertions are against real
# `git show` behaviour rather than a mock.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
script="$root/.github/scripts/detect-version-bump.sh"
assertions=0
failed=0

setup_repo() {
  d="$(mktemp -d)"
  git -C "$d" init -q
  git -C "$d" config user.email t@example.com
  git -C "$d" config user.name t
  printf 'org.gradle.jvmargs=-Xmx4g\nversionCode=%s\n' "$1" > "$d/gradle.properties"
  git -C "$d" add gradle.properties
  git -C "$d" commit -q -m first
  echo "$d"
}

bump_repo() {
  printf 'org.gradle.jvmargs=-Xmx4g\nversionCode=%s\n' "$2" > "$1/gradle.properties"
  git -C "$1" add gradle.properties
  git -C "$1" commit -q -m bump
}

expect() {
  local label="$1" haystack="$2" needle="$3"
  assertions=$((assertions + 1))
  if printf '%s' "$haystack" | grep -qx -- "$needle"; then
    echo "  ok: $label"
  else
    echo "  FAIL: $label - expected line '$needle' in:"
    printf '%s\n' "$haystack" | sed 's/^/       /'
    failed=1
  fi
}

# 1. An unchanged code is not a release.
d="$(setup_repo 10000)"; git -C "$d" commit -q --allow-empty -m noop
out="$(cd "$d" && bash "$script" 'HEAD^')"
expect "unchanged code -> changed=false" "$out" "changed=false"
expect "unchanged code still reports the code" "$out" "code=10000"
rm -rf "$d"

# 2. A bumped code is a release, and the derived name is right.
d="$(setup_repo 10000)"; bump_repo "$d" 10001
out="$(cd "$d" && bash "$script" 'HEAD^')"
expect "bumped code -> changed=true" "$out" "changed=true"
expect "bumped code reports the new code" "$out" "code=10001"
expect "patch bump derives the name" "$out" "name=1.0.1"
rm -rf "$d"

# 3. The two-digit-per-segment scheme, at each boundary it could get wrong.
#    10100 must be 1.1.0 and not 1.0.100; 10010 must be 1.0.10 and not 1.1.0.
d="$(setup_repo 10000)"; bump_repo "$d" 10100
out="$(cd "$d" && bash "$script" 'HEAD^')"
expect "minor bump derives x.1.0" "$out" "name=1.1.0"
rm -rf "$d"

d="$(setup_repo 10000)"; bump_repo "$d" 10010
out="$(cd "$d" && bash "$script" 'HEAD^')"
expect "two-digit patch derives x.y.10" "$out" "name=1.0.10"
rm -rf "$d"

d="$(setup_repo 10000)"; bump_repo "$d" 21234
out="$(cd "$d" && bash "$script" 'HEAD^')"
expect "every segment at once" "$out" "name=2.12.34"
rm -rf "$d"

# 4. Fails OPEN: an unreadable old value must not silently skip a release.
d="$(setup_repo 10000)"
out="$(cd "$d" && bash "$script" 'refs/heads/nonexistent')"
expect "unreadable old ref -> changed=true" "$out" "changed=true"
rm -rf "$d"

# 5. A JVM tweak with no version change is not a release, even though
#    gradle.properties itself changed.
d="$(setup_repo 10000)"
printf 'org.gradle.jvmargs=-Xmx8g\nversionCode=10000\n' > "$d/gradle.properties"
git -C "$d" add gradle.properties; git -C "$d" commit -q -m tune
out="$(cd "$d" && bash "$script" 'HEAD^')"
expect "jvmargs change alone -> changed=false" "$out" "changed=false"
rm -rf "$d"

# 6. An unreadable CURRENT value is a hard error, not a fail-open.
d="$(setup_repo 10000)"
printf 'org.gradle.jvmargs=-Xmx4g\n' > "$d/gradle.properties"
assertions=$((assertions + 1))
if (cd "$d" && bash "$script" 'HEAD') >/dev/null 2>&1; then
  echo "  FAIL: missing current versionCode should exit non-zero"
  failed=1
else
  echo "  ok: missing current versionCode exits non-zero"
fi
rm -rf "$d"

# 6b. A DECREASE is a hard error, not a publish. This is the case that cannot be
#     undone downstream: Android refuses a lower versionCode as an update, and a
#     published code cannot be reused, so the only place to stop it is before the
#     build.
d="$(setup_repo 10001)"; bump_repo "$d" 10000
assertions=$((assertions + 1))
if (cd "$d" && bash "$script" 'HEAD^') >/dev/null 2>&1; then
  echo "  FAIL: a versionCode decrease should exit non-zero"
  failed=1
else
  echo "  ok: versionCode decrease exits non-zero"
fi
rm -rf "$d"

# 6c. ...and it must not be reported as a release on the way out. A decrease that
#     exits non-zero but still prints changed=true would publish if any caller
#     ever read the output without checking the status.
d="$(setup_repo 10001)"; bump_repo "$d" 10000
out="$(cd "$d" && bash "$script" 'HEAD^' 2>/dev/null || true)"
assertions=$((assertions + 1))
if printf '%s' "$out" | grep -qx -- 'changed=true'; then
  echo "  FAIL: a versionCode decrease must not report changed=true"
  failed=1
else
  echo "  ok: versionCode decrease reports no bump"
fi
rm -rf "$d"

# 7. The real gradle.properties must be parseable by the anchored grep, and the
#    prose above the assignment must not be. That comment block mentions
#    `versionCode` on several lines, which is exactly what an unanchored match
#    would swallow before reaching the real one.
d="$(mktemp -d)"
git -C "$d" init -q
git -C "$d" config user.email t@example.com
git -C "$d" config user.name t
cp "$root/gradle.properties" "$d/gradle.properties"
git -C "$d" add gradle.properties
git -C "$d" commit -q -m real
out="$(cd "$d" && bash "$script" 'refs/heads/nonexistent')"
assertions=$((assertions + 1))
if printf '%s' "$out" | grep -qE '^code=[0-9]+$'; then
  echo "  ok: the repo's own gradle.properties parses to exactly one code"
else
  echo "  FAIL: could not parse the repo's own gradle.properties:"
  printf '%s\n' "$out" | sed 's/^/       /'
  failed=1
fi
rm -rf "$d"

# 8. The shell arithmetic here and calculateVersionName() in
#    app/build.gradle.kts are two implementations of one scheme: Gradle's names
#    the APK, this one names the tag, the GitHub release and the release-notes
#    directory. Change one and the release is labelled differently from the
#    binary inside it, with nothing failing. Pin the divisors structurally -
#    running Kotlin from a shell test is not worth the machinery, and the
#    numbers are the entire content of the function.
gradle_file="$root/app/build.gradle.kts"
for expr in 'code / 10000' 'code % 10000) / 100' 'code % 100'; do
  assertions=$((assertions + 1))
  if grep -qF -- "$expr" "$gradle_file"; then
    echo "  ok: build.gradle.kts still derives with '$expr'"
  else
    echo "  FAIL: '$expr' is gone from app/build.gradle.kts - the Kotlin and the"
    echo "        shell version-name arithmetic have diverged. Update BOTH, and"
    echo "        this test, together."
    failed=1
  fi
done

echo "  ${assertions} assertion(s)"
[ "$failed" -eq 0 ]
