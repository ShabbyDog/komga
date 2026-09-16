#!/usr/bin/env bash
# Keep the fork's work branch sitting exactly on the latest upstream RELEASE TAG,
# so every jar we build is "last released Komga + our changes" and never contains
# unreleased upstream commits.
#
#   bash scripts/sync-release.sh --check    # report only, change nothing
#   bash scripts/sync-release.sh            # rebase the work branch onto the newest tag
#
# Exit codes: 0 = already on the newest release | 1 = a newer release exists
#             2 = rebase stopped on conflicts   | 3 = error

set -uo pipefail

WORK=ShabbyFork
TAG_RE='^v?[0-9]+\.[0-9]+\.[0-9]+$'

cd "$(git rev-parse --show-toplevel)" || exit 3

CHECK=0
[ "${1:-}" = "--check" ] && CHECK=1

if ! git rev-parse --verify --quiet "$WORK" >/dev/null; then
  echo "error: branch '$WORK' does not exist" >&2
  exit 3
fi

echo "Fetching upstream tags ..."
git fetch upstream --prune --tags --quiet || { echo "error: fetch failed" >&2; exit 3; }

# Newest release tag by creation date, ignoring anything that is not X.Y.Z.
latest_tag=$(
  git for-each-ref --sort=-creatordate --format='%(refname:short)' refs/tags |
    grep -E "$TAG_RE" | head -1
)
if [ -z "$latest_tag" ]; then
  echo "error: no release tag matching $TAG_RE found" >&2
  exit 3
fi

# Where our branch currently forks off upstream: with a linear
# "<tag> + our commits" history this is the release commit itself.
base=$(git merge-base "$WORK" upstream/master)
base_tag=$(git describe --tags --exact-match "$base" 2>/dev/null || echo "$(git rev-parse --short "$base") (not a release tag)")
ours=$(git rev-list --count --no-merges "$base..$WORK")

echo
printf '  %-24s: %s
' "latest upstream release" "$latest_tag ($(git log -1 --format=%cs "$latest_tag"))"
printf '  %-24s: %s
' "$WORK is built on" "$base_tag"
printf '  %-24s: %s
' "our commits on top" "$ours"

if [ "$base" = "$(git rev-parse "$latest_tag^{commit}")" ]; then
  echo
  echo "Already on the latest release. Nothing to do."
  exit 0
fi

echo
echo "Our commits to replay onto $latest_tag:"
git log --no-merges --reverse --format='  %h %s' "$base..$WORK"

if [ "$CHECK" -eq 1 ]; then
  echo
  echo "Check only; nothing changed. Run without --check to rebase."
  exit 1
fi

# Content-based, not `git status --porcelain`: frontend builds rewrite generated
# files with identical content, which leaves them stat-dirty on Windows and would
# otherwise block the rebase. Untracked files are harmless to a rebase.
if ! git diff --quiet || ! git diff --cached --quiet; then
  echo
  echo "error: there are uncommitted changes; commit or stash first" >&2
  git diff --stat HEAD | tail -5 >&2
  exit 3
fi

backup="backup/$WORK-$(date +%Y%m%d-%H%M%S)"
git branch "$backup" "$WORK"
echo
echo "Backup of the current branch: $backup"

echo "Rebasing $WORK onto $latest_tag ..."
if git rebase --onto "$latest_tag" "$base" "$WORK"; then
  echo
  echo "Done. $WORK is now $latest_tag + $ours commit(s)."
  echo "The jar will report version $(grep '^version' gradle.properties | cut -d= -f2)."
  echo "Push with:  git push --force-with-lease origin $WORK"
  exit 0
else
  echo
  echo "Rebase stopped on conflicts. Resolve, then 'git rebase --continue'." >&2
  echo "To back out entirely:  git rebase --abort" >&2
  echo "The branch as it was is kept at $backup" >&2
  exit 2
fi
