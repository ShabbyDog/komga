#!/usr/bin/env bash
# Early warning about what upstream has landed since the release we build on.
#
# We build from release tags (see sync-release.sh), so these commits are NOT in
# our jar yet -- this is a preview of the next release, and a check that our own
# patches will still apply when it ships. Nothing here is touched or merged.
#
#   bash scripts/check-upstream.sh          # report only
#   bash scripts/check-upstream.sh --sync   # also fast-forward the local `master` mirror
#
# Exit codes: 0 = up to date | 1 = updates available, merges clean | 2 = updates available, conflicts

set -uo pipefail

MIRROR=master                 # local pristine mirror of upstream; never commit here
WORK=ShabbyFork               # our long-lived work branch
UPSTREAM_REF=upstream/master

SYNC=0
[ "${1:-}" = "--sync" ] && SYNC=1

cd "$(git rev-parse --show-toplevel)" || exit 3

if ! git rev-parse --verify --quiet "$WORK" >/dev/null; then
  echo "error: branch '$WORK' does not exist" >&2
  exit 3
fi

echo "Fetching $UPSTREAM_REF ..."
git fetch upstream --prune --quiet || { echo "error: fetch failed" >&2; exit 3; }

new_count=$(git rev-list --count "$MIRROR..$UPSTREAM_REF")
behind=$(git rev-list --count "$WORK..$UPSTREAM_REF")
ours=$(git rev-list --count "$UPSTREAM_REF..$WORK")

echo
echo "  upstream tip : $(git log -1 --format='%h %s' "$UPSTREAM_REF")"
echo "  $MIRROR mirror : $new_count commit(s) behind upstream"
echo "  $WORK branch  : $behind commit(s) behind upstream, $ours commit(s) of our own"

if [ "$behind" -eq 0 ]; then
  echo
  echo "Up to date with upstream. Nothing to do."
  exit 0
fi

echo
echo "New upstream commits ($behind):"
git log --oneline --no-decorate "$WORK..$UPSTREAM_REF" | head -40
[ "$behind" -gt 40 ] && echo "  ... and $((behind - 40)) more"

# Dry-run the merge in memory: does not touch the index or working tree.
echo
conflicts=$(git merge-tree --write-tree --name-only --no-messages "$WORK" "$UPSTREAM_REF" 2>/dev/null | tail -n +2)
status=$?

if [ $status -eq 0 ]; then
  echo "Conflict preview: CLEAN -- our commits still apply on top of upstream."
  rc=1
else
  echo "Conflict preview: our patches CONFLICT in the following files:"
  echo "$conflicts" | sed 's/^/  /'
  rc=2
fi

# Which of our own files does this upstream range touch? Those are the real risk.
echo
echo "Upstream changes overlapping files we modified:"
overlap=$(comm -12 \
  <(git diff --name-only "$(git merge-base "$UPSTREAM_REF" "$WORK")" "$WORK" | sort -u) \
  <(git diff --name-only "$WORK...$UPSTREAM_REF" | sort -u))
if [ -n "$overlap" ]; then echo "$overlap" | sed 's/^/  /'; else echo "  (none)"; fi

if [ "$SYNC" -eq 1 ]; then
  echo
  echo "Fast-forwarding '$MIRROR' mirror ..."
  git fetch upstream "master:$MIRROR" && echo "  $MIRROR is now at $(git log -1 --format='%h' "$MIRROR")"
fi

echo
echo "These land in our build only once upstream tags a release."
echo "To move onto the newest release:  bash scripts/sync-release.sh"
exit $rc
