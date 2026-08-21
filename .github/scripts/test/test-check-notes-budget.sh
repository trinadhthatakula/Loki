#!/usr/bin/env bash
# Fixtures are real directories under mktemp -d, and the script is run with that
# directory as $PWD, because the paths it builds ("release-notes/v...") are
# relative and a mock would not exercise them.
set -euo pipefail
script="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/check-notes-budget.sh"
assertions=0
failed=0

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# n copies of a character, as one line with no trailing newline - so the
# character count the script reports is exactly the number asked for.
repeat() {
  python3 -c "import sys; sys.stdout.write(sys.argv[1] * int(sys.argv[2]))" "$1" "$2"
}

# Each case gets its own version directory, so a leftover file cannot make a
# later case pass.
notes_dir() {
  # Two statements, not one `local a= b=$a`: local expands all its arguments
  # before performing any assignment, so $name would still be unbound.
  local d
  d="$work/release-notes/v$1"
  mkdir -p "$d"
  echo "$d"
}

ok() { assertions=$((assertions + 1)); echo "  ok: $1"; }
bad() { assertions=$((assertions + 1)); echo "  FAIL: $1"; failed=1; }

run() { (cd "$work" && bash "$script" "$@") 2>&1; }
run_status() { (cd "$work" && bash "$script" "$@") >/dev/null 2>&1; }

# 1. A missing version directory is an error, not a silent pass.
if run_status 9.9.9; then
  bad "a missing release-notes directory should exit non-zero"
else
  ok "a missing release-notes directory exits non-zero"
fi

# 2. Notes within budget pass.
d="$(notes_dir 1.0.1)"
printf '## Fixed\n- A thing\n' > "$d/github.md"
repeat x 400 > "$d/playstore.txt"
if run_status 1.0.1; then
  ok "notes within budget pass"
else
  bad "notes within budget should pass: $(run 1.0.1)"
fi

# 3. The reported character count is the file's, not the byte count. An emoji is
#    one character and four UTF-8 bytes; counting bytes would reject notes that
#    are actually inside the cap.
d="$(notes_dir 1.0.2)"
printf '## Fixed\n' > "$d/github.md"
repeat '🎉' 400 > "$d/playstore.txt"
out="$(run 1.0.2)"
if printf '%s' "$out" | grep -q 'playstore.txt: 400/500'; then
  ok "characters are counted, not bytes"
else
  bad "expected 'playstore.txt: 400/500' in: $out"
fi

# 4. Over the Play cap is an error.
d="$(notes_dir 1.0.3)"
printf '## Fixed\n' > "$d/github.md"
repeat x 501 > "$d/playstore.txt"
if run_status 1.0.3; then
  bad "a 501-character playstore.txt should exit non-zero"
else
  ok "a 501-character playstore.txt exits non-zero"
fi

# 5. Exactly at the cap is allowed - the caps are inclusive.
d="$(notes_dir 1.0.4)"
printf '## Fixed\n' > "$d/github.md"
repeat x 500 > "$d/playstore.txt"
if run_status 1.0.4; then
  ok "exactly 500 characters is within budget"
else
  bad "500 characters should be within budget: $(run 1.0.4)"
fi

# 6. Absent and unrequired is a warning: the dev rung falls back to the commit
#    log, so it must not be blocked on curated notes.
d="$(notes_dir 1.0.5)"
repeat x 100 > "$d/playstore.txt"
out="$(run 1.0.5)"
if run_status 1.0.5 && printf '%s' "$out" | grep -q 'no github.md'; then
  ok "absent github.md warns but passes when unrequired"
else
  bad "absent-and-unrequired github.md should warn and pass: $out"
fi

# 7. Absent and --require'd is an error. This is the gate that stops a master
#    push from publishing a release with a commit-log body.
if run_status --require github.md 1.0.5; then
  bad "--require github.md should fail when the file is absent"
else
  ok "--require github.md fails when the file is absent"
fi

# 8. --require=x is the same option as --require x.
if run_status --require=github.md 1.0.5; then
  bad "--require=github.md should fail when the file is absent"
else
  ok "--require=github.md fails when the file is absent"
fi

# 9. A required-and-absent file is reported once, not twice - once by the
#    presence loop and again by the per-file branch.
out="$(run --require github.md 1.0.5 || true)"
count="$(printf '%s\n' "$out" | grep -c 'no github.md' || true)"
if [ "$count" -eq 1 ]; then
  ok "a required-and-absent file is reported exactly once"
else
  bad "expected one 'no github.md' line, got $count: $out"
fi

# 10. Several --require flags all apply.
d="$(notes_dir 1.0.6)"
printf '## Fixed\n' > "$d/github.md"
if run_status --require github.md --require playstore.txt 1.0.6; then
  bad "two --requires should fail when the second file is absent"
else
  ok "every --require is checked, not just the first"
fi

# 11. An unknown option is a usage error (exit 2), distinct from a notes
#     failure - so a typo in the workflow is not read as bad notes.
(cd "$work" && bash "$script" --bogus 1.0.1) >/dev/null 2>&1 && status=0 || status=$?
assertions=$((assertions + 1))
if [ "$status" -eq 2 ]; then
  echo "  ok: an unknown option exits 2"
else
  echo "  FAIL: an unknown option should exit 2, got $status"
  failed=1
fi

# 12. The unprefixed directory name is the documented fallback.
mkdir -p "$work/release-notes/2.0.0"
printf '## New\n' > "$work/release-notes/2.0.0/github.md"
repeat x 10 > "$work/release-notes/2.0.0/playstore.txt"
if run_status 2.0.0; then
  ok "release-notes/<name> is found when release-notes/v<name> is absent"
else
  bad "the unprefixed fallback directory should be found: $(run 2.0.0)"
fi

echo "  ${assertions} assertion(s)"
[ "$failed" -eq 0 ]
