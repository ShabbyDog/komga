#!/usr/bin/env bash
# Keep the fork's work branch sitting exactly on an upstream RELEASE TAG, so every jar we build
# is "a released Komga + our changes" and never contains unreleased upstream commits.
#
# Releases are taken one at a time, in order: when upstream has shipped several since our base,
# this moves onto the *next* one, not the newest, so the fork gets a release for every upstream
# release and the two release lists line up. Run it again (or let the pipeline loop) to advance.
#
#   bash scripts/sync-release.sh --check    # report only, change nothing
#   bash scripts/sync-release.sh --pending  # print the releases still to take, oldest first
#   bash scripts/sync-release.sh            # rebase the work branch onto the next release
#
# Exit codes: 0 = already on the newest release | 1 = a newer release exists
#             2 = rebase stopped on conflicts   | 3 = error

set -uo pipefail

WORK=ShabbyFork
TAG_RE='^v?[0-9]+\.[0-9]+\.[0-9]+$'

cd "$(git rev-parse --show-toplevel)" || exit 3

CHECK=0 PENDING=0
case "${1:-}" in
  --check)   CHECK=1 ;;
  --pending) PENDING=1 ;;
  "")        ;;
  *) echo "usage: $0 [--check | --pending]" >&2; exit 3 ;;
esac

if ! git rev-parse --verify --quiet "$WORK" >/dev/null; then
  echo "error: branch '$WORK' does not exist" >&2
  exit 3
fi

[ "$PENDING" -eq 1 ] || echo "Fetching upstream tags ..."
git fetch upstream --prune --tags --quiet || { echo "error: fetch failed" >&2; exit 3; }

# Where our branch currently forks off upstream: with a linear
# "<tag> + our commits" history this is the release commit itself.
base=$(git merge-base "$WORK" upstream/master)
base_tag=$(git describe --tags --exact-match "$base" 2>/dev/null || echo "$(git rev-parse --short "$base") (not a release tag)")
ours=$(git rev-list --count --no-merges "$base..$WORK")

# The releases still to take: X.Y.Z tags that descend from our base, oldest first. Chosen by
# ancestry rather than by comparing versions, because upstream's older tags carry a "v" prefix
# and would sort wrongly; the version sort only orders what ancestry already selected.
pending=$(
  git tag -l | grep -E "$TAG_RE" | while read -r t; do
    c=$(git rev-parse "$t^{commit}")
    [ "$c" != "$base" ] && git merge-base --is-ancestor "$base" "$c" && printf '%s %s\n' "${t#v}" "$t"
  done | sort -V | cut -d' ' -f2
)

if [ "$PENDING" -eq 1 ]; then
  [ -n "$pending" ] && printf '%s\n' "$pending"
  exit 0
fi

latest_tag=$(printf '%s\n' "$pending" | tail -1)
next_tag=$(printf '%s\n' "$pending" | head -1)

echo
printf '  %-24s: %s\n' "$WORK is built on" "$base_tag"
printf '  %-24s: %s\n' "our commits on top" "$ours"

if [ -z "$pending" ]; then
  echo
  echo "Already on the latest release. Nothing to do."
  exit 0
fi

printf '  %-24s: %s\n' "latest upstream release" "$latest_tag ($(git log -1 --format=%cs "$latest_tag"))"
printf '  %-24s: %s\n' "releases to take (order)" "$(printf '%s\n' "$pending" | paste -sd' ')"
printf '  %-24s: %s\n' "next" "$next_tag"
echo
echo "Our commits to replay onto $next_tag:"
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

echo "Rebasing $WORK onto $next_tag ..."
if git rebase --onto "$next_tag" "$base" "$WORK"; then
  echo
  echo "Done. $WORK is now $next_tag + $ours commit(s)."
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
