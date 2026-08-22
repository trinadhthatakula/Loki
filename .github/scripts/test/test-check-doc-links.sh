#!/usr/bin/env bash
# check-doc-links.sh resolves everything relative to `git rev-parse
# --show-toplevel`, so each fixture is a real (empty, local) git repository
# under mktemp -d. That is also what exercises the default file list.
set -euo pipefail
script="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/check-doc-links.sh"
assertions=0
failed=0

ok() { assertions=$((assertions + 1)); echo "  ok: $1"; }
bad() { assertions=$((assertions + 1)); echo "  FAIL: $1"; failed=1; }

# -c rather than global config, so running the tests cannot touch the
# contributor's identity or hooks.
fixture() {
  local d
  d="$(mktemp -d)"
  git -C "$d" init -q
  git -C "$d" config user.email t@example.invalid
  git -C "$d" config user.name test
  echo "$d"
}

# `git ls-files` only sees staged paths, which is the point: the default file
# list is the tracked docs, not whatever happens to be lying around.
track() { git -C "$1" add -A; }

# 1. A link to a file that exists passes; one to a file that does not fails,
#    and the message names the file and the line.
d="$(fixture)"
printf '# Doc\n\n[here](other.md)\n[gone](missing.md)\n' > "$d/a.md"
printf '# Other\n' > "$d/other.md"
track "$d"
out="$(cd "$d" && bash "$script" 2>&1)" && rc=0 || rc=$?
if [ "$rc" -ne 0 ]; then
  ok "exits non-zero when a relative target is missing"
else
  bad "a missing target must fail the run"
fi
if printf '%s' "$out" | grep -q 'a\.md:4: broken link: missing\.md'; then
  ok "reports file:line and the target that broke"
else
  bad "expected 'a.md:4: broken link: missing.md', got: $out"
fi
if printf '%s' "$out" | grep -q 'other\.md'; then
  bad "other.md exists and must not be reported"
else
  ok "a target that exists is not reported"
fi
rm -rf "$d"

# 2. Anchors. The emoji case is the one worth pinning: GitHub deletes the
#    emoji's bytes but keeps the space after it, so the slug really does begin
#    with a hyphen. Getting this wrong makes every such link a false failure.
d="$(fixture)"
printf '# T\n\n[a](t.md#-the-privileged-surface)\n[b](t.md#plain-heading)\n[c](t.md#nope)\n[d](#t)\n' > "$d/a.md"
printf '# T\n\n## \xf0\x9f\x94\x90 The privileged surface\n\n## Plain heading\n' > "$d/t.md"
track "$d"
out="$(cd "$d" && bash "$script" 2>&1)" && rc=0 || rc=$?
if printf '%s' "$out" | grep -q 'a\.md:3'; then
  bad "#-the-privileged-surface is a real GitHub slug and must resolve: $out"
else
  ok "an emoji heading resolves as #-<rest>, matching GitHub"
fi
if printf '%s' "$out" | grep -q 'a\.md:4'; then
  bad "#plain-heading must resolve"
else
  ok "an ordinary heading resolves"
fi
if printf '%s' "$out" | grep -q 'a\.md:5: broken anchor: #nope'; then
  ok "an anchor with no matching heading is reported"
else
  bad "expected a broken-anchor report for #nope, got: $out"
fi
if printf '%s' "$out" | grep -q 'a\.md:6'; then
  bad "a bare #fragment must resolve against its own file"
else
  ok "a bare #fragment resolves against the file containing it"
fi
if [ "$rc" -ne 0 ]; then
  ok "the run fails overall"
else
  bad "the run should have failed"
fi
rm -rf "$d"

# 3. Things that look like links but are not. Every one of these was a false
#    positive at some point while writing the script.
d="$(fixture)"
# A quoted heredoc, so the fence, the backticks and the ${{ }} reach the
# fixture verbatim without the shell or shellcheck taking an interest.
cat > "$d/a.md" <<'FIXTURE'
# T

```markdown
[in a fence](made/up.md)
```

A `[code span](nope.md)` is not a link.

[ext](https://example.invalid/404)
[mail](mailto:someone@example.invalid)
[expr](${{ github.server_url }}/x)
[parens](other.md)
FIXTURE
printf '# Other\n' > "$d/other.md"
track "$d"
if out="$(cd "$d" && bash "$script" 2>&1)"; then
  ok "fences, code spans, external schemes and \${{ }} are all skipped"
else
  bad "nothing here should fail: $out"
fi
if printf '%s' "$out" | grep -q '2 external'; then
  ok "external links are counted, not checked"
else
  bad "expected 2 external links counted, got: $out"
fi
rm -rf "$d"

# 4. A repo with no Markdown at all must fail rather than report a green run of
#    zero checks - the same rule run-tests.sh applies to itself.
d="$(fixture)"
printf 'x\n' > "$d/not-a-doc.txt"
track "$d"
if (cd "$d" && bash "$script" >/dev/null 2>&1); then
  bad "a repo with no Markdown must not pass vacuously"
else
  ok "no Markdown to check is an error, not a pass"
fi
rm -rf "$d"

# 5. A directory target and a repo-root-absolute target both resolve. Both
#    appear in the real docs (fastlane/metadata/, /CLAUDE.md).
d="$(fixture)"
mkdir -p "$d/sub/deep"
printf '# T\n\n[dir](deep)\n[root](/top.md)\n[up](../top.md)\n' > "$d/sub/a.md"
printf '# Top\n' > "$d/top.md"
track "$d"
if out="$(cd "$d" && bash "$script" 2>&1)"; then
  ok "a directory, a /repo-root path and a ../ path all resolve"
else
  bad "expected all three to resolve: $out"
fi

# The other half of the same rule: a relative target is resolved against the
# file that contains it, NOT against the repo root. Writing `sub/deep` inside
# `sub/a.md` is the mistake this catches, and it is the mistake that was in
# this test file first.
printf '# T\n\n[wrong](sub/deep)\n' > "$d/sub/a.md"
track "$d"
if (cd "$d" && bash "$script" >/dev/null 2>&1); then
  bad "a repo-root-relative path written without a leading / must not resolve"
else
  ok "relative targets resolve against the containing file, not the repo root"
fi
rm -rf "$d"

# 6. The real repository. This is the assertion that keeps the docs honest, and
#    the reason the script is in pr-ci.yml at all.
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
if out="$(cd "$repo" && bash "$script" 2>&1)"; then
  ok "every internal link in this repository resolves"
else
  bad "this repository has broken internal links: $out"
fi

printf '  %d assertion(s)\n' "$assertions"
exit "$failed"
