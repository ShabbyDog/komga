#!/usr/bin/env bash
# ShabbyFork: the two questions auto-release.sh asks Claude, run headless with the claude CLI.
#
#   bash scripts/claude-assist.sh overlap <base-tag> <next-tag>   # does upstream now ship one of our features?
#   bash scripts/claude-assist.sh resolve <base-tag> <next-tag>   # resolve the rebase conflict in progress
#
# Prints Claude's structured verdict as JSON on stdout and nothing else; progress goes to stderr.
# Exit codes: 0 = verdict printed | 3 = Claude unavailable, failed, or returned nothing usable.
#
# Claude advises and edits; it never acts. It runs in dontAsk mode with an explicit tool list, so
# anything not listed is refused rather than prompted for: it can read the repository and run
# read-only git commands, and for `resolve` it may also edit files. It cannot commit, continue the
# rebase, push, or reach GitHub -- auto-release.sh checks what it did and does all of that itself,
# and nothing it produces ships without passing the full build and tests.
#
# User settings and hooks are not loaded (--setting-sources project), so nothing configured for
# interactive use can widen what an unattended run may do. Every run's full JSON transcript is
# kept in CLAUDE_LOG_DIR for audit.

set -uo pipefail

WORK=${WORK:-ShabbyFork}
CLAUDE_BIN=${CLAUDE_BIN:-claude}
CLAUDE_MODEL=${CLAUDE_MODEL:-claude-opus-5-5}
CLAUDE_TIMEOUT=${CLAUDE_TIMEOUT:-1800}
CLAUDE_LOG_DIR=${CLAUDE_LOG_DIR:-$HOME/.local/state/komga-auto-release}

cd "$(git rev-parse --show-toplevel)" || exit 3

READ_TOOLS=(Read Glob Grep "Bash(git log:*)" "Bash(git show:*)" "Bash(git diff:*)" "Bash(git status:*)")
# Belt and braces: dontAsk already refuses these, but naming them keeps that true even if the
# allow list above is ever loosened by mistake.
DENY_TOOLS=("Bash(git push:*)" "Bash(git commit:*)" "Bash(git rebase:*)" "Bash(git reset:*)"
  "Bash(git checkout:*)" "Bash(gh:*)" "Bash(curl:*)" WebFetch WebSearch)

# The pipeline runs on the claude.ai subscription and must never bill API tokens instead. Any of
# these makes the CLI switch to pay-per-token billing, so their presence is a refusal, not a
# preference; and the CLI's own report of how it is authenticated has to agree.
BILLING_VARS=(ANTHROPIC_API_KEY ANTHROPIC_AUTH_TOKEN CLAUDE_CODE_USE_BEDROCK CLAUDE_CODE_USE_VERTEX CLAUDE_CODE_USE_FOUNDRY)

subscription_only() {
  local v auth
  for v in "${BILLING_VARS[@]}"; do
    if [ -n "${!v:-}" ]; then
      echo "claude-assist: refusing to run: $v is set, which would bill API tokens instead of the subscription" >&2
      return 3
    fi
  done
  auth=$("$CLAUDE_BIN" auth status 2>/dev/null | jq -r '"\(.authMethod)/\(.apiProvider)"' 2>/dev/null)
  if [ "$auth" != "claude.ai/firstParty" ]; then
    echo "claude-assist: refusing to run: claude is authenticated as '${auth:-unknown}', not a claude.ai subscription" >&2
    return 3
  fi
}

# run_claude <kind> <schema> <prompt> <allowed tool>...
run_claude() {
  local kind="$1" schema="$2" prompt="$3" out log rc
  shift 3
  subscription_only || return 3
  mkdir -p "$CLAUDE_LOG_DIR" || return 3
  log="$CLAUDE_LOG_DIR/$(date +%Y%m%d-%H%M%S)-$kind.json"
  echo "claude-assist: asking Claude ($CLAUDE_MODEL) for '$kind'; transcript: $log" >&2

  timeout "$CLAUDE_TIMEOUT" "$CLAUDE_BIN" -p "$prompt" \
    --model "$CLAUDE_MODEL" \
    --permission-mode dontAsk \
    --allowedTools "$@" \
    --disallowedTools "${DENY_TOOLS[@]}" \
    --setting-sources project \
    --strict-mcp-config \
    --no-session-persistence \
    --output-format json \
    --json-schema "$schema" \
    < /dev/null > "$log" 2> "$log.stderr"
  rc=$?
  if [ "$rc" -ne 0 ]; then
    echo "claude-assist: claude exited $rc ($( [ "$rc" -eq 124 ] && echo "timed out after ${CLAUDE_TIMEOUT}s" || tail -1 "$log.stderr"))" >&2
    return 3
  fi
  if [ "$(jq -r '.is_error' "$log" 2>/dev/null)" != "false" ]; then
    echo "claude-assist: Claude reported an error: $(jq -r '.result // .subtype' "$log" 2>/dev/null | head -c 300)" >&2
    return 3
  fi
  out=$(jq -ce '.structured_output // empty' "$log" 2>/dev/null) || { echo "claude-assist: no structured verdict in $log" >&2; return 3; }
  printf '%s\n' "$out"
}

overlap() {
  local base="$1" next="$2" schema prompt
  schema='{"type":"object","additionalProperties":false,"required":["superseded","partial","summary"],"properties":{
    "superseded":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["commits","feature","upstream_evidence"],"properties":{
      "commits":{"type":"array","items":{"type":"string"},"description":"Full SHAs of every fork commit belonging to this feature, including later fixes to it"},
      "feature":{"type":"string"},"upstream_evidence":{"type":"string","description":"Upstream commits/files that now provide it"}}}},
    "partial":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["commits","feature","upstream_evidence","what_upstream_lacks"],"properties":{
      "commits":{"type":"array","items":{"type":"string"}},"feature":{"type":"string"},
      "upstream_evidence":{"type":"string"},"what_upstream_lacks":{"type":"string"}}}},
    "summary":{"type":"string"}}}'

  prompt=$(cat <<EOF
You are reviewing a fork of Komga before it is rebased onto a new upstream release. Work read-only.

The fork branch \`$WORK\` is upstream release \`$base\` plus the fork's own commits (\`$base..$WORK\`).
It is about to move onto upstream release \`$next\`. The upstream changes being taken are \`$base..$next\`.

Question: does \`$next\` now provide, itself, any feature that one of the fork's commits added? The
fork's policy is that **upstream wins**: a fork feature that upstream now fully provides is dropped
in favour of upstream's implementation.

How to work:
- You are already in the repository root. Run git commands directly (no \`cd\`, no pipes or \`&&\`):
  only plain \`git log\`, \`git show\`, \`git diff\` and \`git status\` are permitted.
- Start from FORK_CHANGELOG.md (the fork's features) and \`git log --reverse --format='%H %s%n%b' $base..$WORK\`.
- Then read what upstream changed: \`git log --no-merges --format='%h %s' $base..$next\`, \`git show $next:CHANGELOG.md\`
  (the top section is the release being taken), and \`git show\` / \`git diff $base $next -- <path>\` for anything relevant.
- Compare behaviour, not names. Upstream solving the same user problem in a different way counts.
  Upstream touching the same files, or something merely related, does not.

Classify each fork feature that upstream overlaps:
- "superseded": upstream \`$next\` fully provides what the fork feature does, so the fork commits are
  redundant and can be dropped without users losing anything. List EVERY commit of that feature,
  including later fixes or follow-ups to it, so dropping them leaves nothing dangling.
- "partial": upstream provides part of it, but dropping the fork commits would lose something.
  Say exactly what upstream lacks.
Be conservative: if you are not confident a feature is fully superseded, it is "partial". Never list
commits whose subject has the "(fork)" scope (tooling, docs, build plumbing); they are not features.
If nothing overlaps, return empty lists. "summary" is one or two sentences for the release notes.
EOF
)
  run_claude overlap "$schema" "$prompt" "${READ_TOOLS[@]}"
}

resolve() {
  local base="$1" next="$2" stopped files schema prompt
  stopped=$(git rev-parse --verify --quiet REBASE_HEAD) || { echo "claude-assist: no rebase is stopped" >&2; return 3; }
  files=$(git diff --name-only --diff-filter=U)
  [ -n "$files" ] || { echo "claude-assist: no conflicted files" >&2; return 3; }

  schema='{"type":"object","additionalProperties":false,"required":["resolved","safe","summary"],"properties":{
    "resolved":{"type":"boolean","description":"Every conflict marker removed and each file left in its intended final state"},
    "safe":{"type":"boolean","description":"The resolution is mechanical and keeps the intent of both sides"},
    "summary":{"type":"string","description":"What each conflict was and how it was resolved, for the release notes"},
    "reason_if_unsafe":{"type":"string"}}}'

  prompt=$(cat <<EOF
A rebase of the Komga fork branch \`$WORK\` from upstream release \`$base\` onto \`$next\` has stopped on conflicts.
Resolve them only if doing so is safe and mechanical; otherwise leave the files alone and say why.

Stopped while applying fork commit $stopped (see \`git show $stopped\`).
Conflicted files:
$(printf '%s\n' "$files" | sed 's/^/- /')

How to work:
- You are already in the repository root. Run git commands directly (no \`cd\`, no pipes or \`&&\`):
  only plain \`git log\`, \`git show\`, \`git diff\` and \`git status\` are permitted.
- \`git show $stopped\` is what the fork commit intends. \`git log --format='%h %s' $base..$next -- <file>\`
  and \`git diff $base $next -- <file>\` show what upstream changed in the same place.
- Edit ONLY the conflicted files listed above, with the Edit tool. Remove every conflict marker.
- Keep the intent of both sides: upstream's change and the fork commit's change both survive.
- Where both sides change the very same thing (a dependency version, a constant, a signature),
  upstream wins, and the fork's edit is re-applied on top of upstream's version if it still makes sense.
- Do not add new behaviour, refactor, or "improve" anything. Do not touch tests.

It is SAFE only when the conflict is mechanical: adjacent or overlapping edits whose combination is
obvious (version bumps, imports, neighbouring lines, a renamed symbol to follow). It is NOT safe when
resolving it requires a design decision, when upstream rewrote or removed the code the fork commit
modifies, when the two sides' behaviour genuinely contradicts, or when you are unsure. In that case set
safe=false, explain in reason_if_unsafe, and you may leave the files as they are.
The result will be built and the full test suite run before anything ships.
EOF
)
  run_claude resolve "$schema" "$prompt" "${READ_TOOLS[@]}" Edit
}

case "${1:-}" in
  overlap) [ $# -eq 3 ] || { echo "usage: $0 overlap <base-tag> <next-tag>" >&2; exit 3; }; overlap "$2" "$3" ;;
  resolve) [ $# -eq 3 ] || { echo "usage: $0 resolve <base-tag> <next-tag>" >&2; exit 3; }; resolve "$2" "$3" ;;
  *) echo "usage: $0 overlap <base-tag> <next-tag> | resolve <base-tag> <next-tag>" >&2; exit 3 ;;
esac
