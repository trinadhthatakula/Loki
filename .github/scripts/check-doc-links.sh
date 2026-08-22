#!/usr/bin/env bash
# Verify every *internal* Markdown link in the repository resolves.
#
# usage: check-doc-links.sh [file.md ...]        (default: every tracked *.md)
#
# Checks two things, both entirely offline:
#
#   1. A relative link target names a file or directory that exists in the
#      working tree.
#   2. A `#fragment` names a heading that exists in the file it points into.
#
# External links (http, https, mailto, tel) are deliberately NOT checked here.
# They rot on somebody else's schedule, and a PR that goes red because another
# host had a bad minute teaches contributors to ignore red. Those are swept
# weekly by docs-link-check.yml, which files an issue instead.
#
# WHY THIS EXISTS: the docs name source files - `model/PermissionManager.kt`,
# `services/LogcatService.kt` - and cross-reference each other. Both drift as
# the code moves, and a stale path is worse than no path: it sends a reader,
# or a security reporter following .github/SECURITY.md, to a file that is not
# there. Nothing else in CI reads a Markdown link.
#
# The output is `file:line: message`, which is the format GitHub's log viewer
# and most editors turn into a jump target.
set -euo pipefail

# Resolved so the awk programs below can be found however the script is invoked,
# and so every path printed is repo-relative regardless of the caller's cwd.
repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

if [ "$#" -gt 0 ]; then
  files=("$@")
else
  # A read loop rather than `mapfile`, which is bash 4+: macOS still ships 3.2
  # and a contributor has to be able to run this before pushing. A glob that
  # matches nothing is the classic silent pass, so the count is asserted below
  # rather than trusted.
  files=()
  while IFS= read -r f; do
    files+=("$f")
  done < <(git ls-files '*.md' '*.markdown')
fi

if [ "${#files[@]}" -eq 0 ]; then
  echo "::error::no Markdown files to check - a run that checks nothing is not a pass" >&2
  exit 1
fi

# --- awk program: extract link targets -------------------------------------
#
# Emits one `line<TAB>target` per internal link. Deliberately conservative:
# anything it cannot parse with confidence is skipped rather than reported, so
# this script's failures are worth acting on.
read -r -d '' extract_links <<'AWK' || true
function strip_code_spans(s,   out, p, q) {
  # A link inside `backticks` is not a link. Dropping spans first is cheaper
  # than teaching the scanner below about them.
  out = ""
  while ((p = index(s, "`")) > 0) {
    out = out substr(s, 1, p - 1)
    s = substr(s, p + 1)
    q = index(s, "`")
    if (q == 0) { s = ""; break }   # unterminated - drop the rest of the line
    s = substr(s, q + 1)
  }
  return out s
}

# Fenced blocks hold example Markdown and directory trees, neither of which is
# a link. Tracked with a counter-free toggle because nesting is not a thing.
/^[[:space:]]*(```|~~~)/ { in_fence = !in_fence; next }
in_fence { next }

{
  line = strip_code_spans($0)

  # Reference definitions:  [label]: target "optional title"
  if (match(line, /^[[:space:]]{0,3}\[[^]]+\][[:space:]]*:[[:space:]]*[^[:space:]]+/)) {
    t = substr(line, RSTART, RLENGTH)
    sub(/^[[:space:]]{0,3}\[[^]]+\][[:space:]]*:[[:space:]]*/, "", t)
    print NR "\t" t
  }

  # Inline links:  [text](target)  and  [text](<target with spaces>)
  s = line
  while ((p = index(s, "](")) > 0) {
    s = substr(s, p + 2)

    if (substr(s, 1, 1) == "<") {
      q = index(s, ">")
      if (q == 0) continue
      print NR "\t" substr(s, 2, q - 2)
      s = substr(s, q + 1)
      continue
    }

    # Balanced-paren scan, because a target may legitimately contain them -
    # Wikipedia URLs and the like. Taking the first ")" would truncate those
    # into a false failure.
    depth = 1
    target = ""
    for (i = 1; i <= length(s); i++) {
      c = substr(s, i, 1)
      if (c == "(") depth++
      else if (c == ")") { depth--; if (depth == 0) break }
      target = target c
    }
    if (depth != 0) continue    # unterminated - not a link we can judge

    # A title after the target:  [t](path "Title")
    sub(/[[:space:]]+["'"'"'(].*$/, "", target)
    print NR "\t" target
    s = substr(s, i + 1)
  }
}
AWK

# --- awk program: extract heading anchors ----------------------------------
#
# Reproduces GitHub's slug algorithm: render the text, downcase, delete
# everything that is not a letter, digit, space, hyphen or underscore, then
# spaces to hyphens, then `-1`, `-2` for repeats.
#
# The deletion step is what makes `## 🔐 The privileged surface` resolve as
# `#-the-privileged-surface` - the emoji's bytes are dropped but the space
# after it is not, so the slug really does start with a hyphen. That is
# GitHub's behaviour, not an artefact of doing this in awk.
read -r -d '' extract_anchors <<'AWK' || true
/^[[:space:]]*(```|~~~)/ { in_fence = !in_fence; next }
in_fence { next }

# Explicit anchors win outright - a heading is not the only jump target.
{
  s = $0
  while (match(s, /<a[[:space:]][^>]*(name|id)[[:space:]]*=[[:space:]]*"[^"]+"/)) {
    frag = substr(s, RSTART, RLENGTH)
    sub(/^.*"/, "", frag)
    s = substr(s, RSTART + RLENGTH)
    match(frag, /[^"]+/)
    print substr(frag, RSTART, RLENGTH)
  }
  s = $0
  while (match(s, /(name|id)[[:space:]]*=[[:space:]]*"[^"]+"/)) {
    frag = substr(s, RSTART, RLENGTH)
    s = substr(s, RSTART + RLENGTH)
    sub(/^[^"]*"/, "", frag)
    sub(/".*$/, "", frag)
    if (frag != "") print frag
  }
}

/^[[:space:]]{0,3}#{1,6}[[:space:]]/ {
  h = $0
  sub(/^[[:space:]]{0,3}#+[[:space:]]+/, "", h)
  sub(/[[:space:]]+#+[[:space:]]*$/, "", h)     # closed ATX:  ## Title ##

  # Render inline constructs the way a browser would see them, so the slug is
  # built from the visible text. Link text survives; the URL does not.
  while (match(h, /\[[^]]*\]\([^)]*\)/)) {
    whole = substr(h, RSTART, RLENGTH)
    text = whole
    sub(/^\[/, "", text)
    sub(/\].*$/, "", text)
    h = substr(h, 1, RSTART - 1) text substr(h, RSTART + RLENGTH)
  }
  gsub(/`|\*\*|\*|~~/, "", h)

  h = tolower(h)
  gsub(/[^a-z0-9 _-]/, "", h)
  gsub(/ /, "-", h)

  seen[h]++
  if (seen[h] == 1) print h
  else print h "-" (seen[h] - 1)
}
AWK

# --- helpers ---------------------------------------------------------------

anchor_cache="$(mktemp -d)"
trap 'rm -rf "$anchor_cache"' EXIT

# Anchors are looked up once per target file, not once per link, because
# AGENTS.md alone is linked from five places.
anchors_for() {
  local path="$1" key
  key="$(printf '%s' "$path" | tr '/' '_')"
  if [ ! -f "$anchor_cache/$key" ]; then
    LC_ALL=C awk "$extract_anchors" "$path" > "$anchor_cache/$key"
  fi
  cat "$anchor_cache/$key"
}

status=0
checked=0
skipped_external=0

for file in "${files[@]}"; do
  [ -f "$file" ] || continue
  dir="$(dirname "$file")"

  while IFS=$'\t' read -r lineno target; do
    [ -n "$target" ] || continue

    # SC2016 is disabled for the whole statement rather than the one branch that
    # needs it, because a shellcheck directive in front of a single case item is
    # itself an error (SC1124). The single quotes below are the point: `${{` is a
    # literal pattern to match in the *document* and must not expand here.
    # shellcheck disable=SC2016
    case "$target" in
      # Not ours to check. mailto and tel have nothing to resolve against;
      # http(s) is the weekly job's business.
      http://* | https://* | mailto:* | tel:* | ftp://* | //*)
        skipped_external=$((skipped_external + 1))
        continue
        ;;
      # A GitHub Actions expression or an HTML-comment placeholder is a
      # template, not a path.
      *'${{'* | *'<!--'* | '{'*)
        continue
        ;;
    esac

    # %20 is the only escape that shows up in practice, and leaving it encoded
    # would make a legitimate "My File.md" look missing.
    target="${target//%20/ }"

    frag=""
    path="$target"
    case "$target" in
      *'#'*)
        path="${target%%#*}"
        frag="${target#*#}"
        ;;
    esac

    if [ -z "$path" ]; then
      # A bare "#fragment" points inside the file that contains it.
      resolved="$file"
    elif [ "${path#/}" != "$path" ]; then
      # A leading slash means repo root on GitHub, not filesystem root.
      resolved="${path#/}"
    else
      resolved="$dir/$path"
    fi
    # dirname of a root-level file is ".", and "./AGENTS.md" in an error message
    # reads like a different file from the "AGENTS.md" the link actually says.
    resolved="${resolved#./}"

    checked=$((checked + 1))

    if [ ! -e "$resolved" ]; then
      echo "$file:$lineno: broken link: $target (no such file: $resolved)" >&2
      status=1
      continue
    fi

    [ -n "$frag" ] || continue
    # Only Markdown has headings to point at. A "#L42" into a .kt file is a
    # GitHub line anchor and there is nothing local to check.
    case "$resolved" in
      *.md | *.markdown) ;;
      *) continue ;;
    esac

    if ! anchors_for "$resolved" | grep -Fxq -- "$frag"; then
      echo "$file:$lineno: broken anchor: #$frag (not a heading in $resolved)" >&2
      status=1
    fi
  done < <(LC_ALL=C awk "$extract_links" "$file")
done

printf '  %d file(s), %d internal link(s) checked, %d external link(s) left to the weekly sweep\n' \
  "${#files[@]}" "$checked" "$skipped_external"

if [ "$status" -ne 0 ]; then
  echo "::error::broken internal documentation links - see the lines above" >&2
fi

exit "$status"
