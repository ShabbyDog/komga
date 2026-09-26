#!/usr/bin/env bash
# ShabbyFork: unattended "new upstream release -> tested jar -> GitHub release -> upgraded server".
#
#   bash scripts/auto-release.sh --preflight  # check the environment, change nothing
#   bash scripts/auto-release.sh --check      # is there a newer upstream release? change nothing
#   bash scripts/auto-release.sh --dry-run     # rebase, build and test, but do not tag, push or deploy
#   bash scripts/auto-release.sh --deploy-only # rehearse stop/backup/start/health on the installed jar
#   bash scripts/auto-release.sh               # the real thing
#
# Exit codes: 0 = nothing to do, or released and deployed
#             1 = stopped safely before touching anything that matters
#             2 = deploy failed and was rolled back
#             3 = error
#
# Komga runs its Flyway migrations on startup and Flyway has no undo, so starting a newer jar
# moves the database forward permanently. Rolling back therefore means restoring the database
# as well as the jar, which is why the service is stopped before the backup is taken: a copy of
# a live SQLite file is not guaranteed to be consistent.

set -uo pipefail

WORK=ShabbyFork
CONF="${KOMGA_AUTO_CONF:-$HOME/.config/komga-auto.conf}"
LOCK=/tmp/komga-auto-release.lock

# ---------------------------------------------------------------- logging

log() { printf '%s  %s\n' "$(date +'%Y-%m-%d %H:%M:%S')" "$*"; }
die() { log "ERROR: $*" >&2; exit 3; }

# ---------------------------------------------------------------- config

load_config() {
  # Defaults are only a starting point; the config file is what a real install goes by.
  KOMGA_SERVICE=komga.service
  KOMGA_JAR=/opt/komga/komga.jar
  KOMGA_CONFIG_DIR=/var/lib/komga
  KOMGA_URL=http://localhost:25600
  BACKUP_DIR=/var/backups/komga
  KEEP_BACKUPS=3
  GH_REPO=ShabbyDog/komga
  HEALTH_TIMEOUT=600
  ISSUE_TITLE='Automated release pipeline failed'

  # shellcheck source=/dev/null
  [ -f "$CONF" ] && . "$CONF"
}

# Komga usually runs as an ordinary user, so the pipeline does too and reaches for sudo only
# to start and stop the unit. Running the whole thing as root would leave root-owned files in
# the jar folder and build caches in root's home.
systemctl_cmd() {
  if [ "$(id -u)" -eq 0 ]; then
    systemctl "$@"
  else
    sudo -n systemctl "$@"
  fi
}

# ---------------------------------------------------------------- notification

# One open issue at a time: a failing run opens or comments on it, a good run closes it, so a
# recurring failure does not bury the notifications it is trying to deliver.
open_issue_number() {
  gh issue list --repo "$GH_REPO" --state open --search "\"$ISSUE_TITLE\" in:title" \
    --json number --jq '.[0].number' 2>/dev/null
}

notify_failure() {
  local body="$1" num
  log "NOTIFY: $body"
  command -v gh >/dev/null || return 0
  num=$(open_issue_number)
  if [ -n "${num:-}" ] && [ "$num" != "null" ]; then
    gh issue comment "$num" --repo "$GH_REPO" --body "$body" >/dev/null 2>&1 || true
  else
    gh issue create --repo "$GH_REPO" --title "$ISSUE_TITLE" --body "$body" >/dev/null 2>&1 || true
  fi
}

notify_success() {
  local body="$1" num
  log "$body"
  command -v gh >/dev/null || return 0
  num=$(open_issue_number)
  if [ -n "${num:-}" ] && [ "$num" != "null" ]; then
    gh issue close "$num" --repo "$GH_REPO" --comment "$body" >/dev/null 2>&1 || true
  fi
}

# ---------------------------------------------------------------- preflight

preflight() {
  local ok=0

  need() {
    if command -v "$1" >/dev/null; then
      log "  ok      $1"
    else
      log "  MISSING $1${2:+  ($2)}"
      ok=1
    fi
  }

  log "Tools:"
  need git; need curl; need flock; need gh "gh auth login"
  need node "needed to build the frontends"; need npm; need systemctl

  if command -v java >/dev/null; then
    local v
    v=$(java -version 2>&1 | head -1)
    if java -version 2>&1 | grep -qE '"(2[1-9]|[3-9][0-9])'; then
      log "  ok      java   $v"
    else
      log "  TOO OLD java   $v  (JDK 21+ required)"
      ok=1
    fi
  else
    log "  MISSING java  (JDK 21+)"
    ok=1
  fi

  log "GitHub:"
  if gh auth status >/dev/null 2>&1; then log "  ok      gh authenticated"; else log "  FAILED  gh not authenticated"; ok=1; fi

  log "Repository:"
  git rev-parse --show-toplevel >/dev/null 2>&1 || { log "  FAILED  not a git repository"; ok=1; }
  git remote get-url upstream >/dev/null 2>&1 || { log "  FAILED  no 'upstream' remote"; ok=1; }
  git remote get-url origin   >/dev/null 2>&1 || { log "  FAILED  no 'origin' remote"; ok=1; }
  git rev-parse --verify --quiet "$WORK" >/dev/null || { log "  FAILED  no '$WORK' branch"; ok=1; }

  log "Config ($CONF):"
  [ -f "$CONF" ] && log "  ok      present" || log "  note    absent, using defaults - copy scripts/auto-release.conf.example"

  log "Service and paths:"
  if systemctl cat "$KOMGA_SERVICE" >/dev/null 2>&1; then
    log "  ok      $KOMGA_SERVICE"
  else
    log "  FAILED  $KOMGA_SERVICE not found"; ok=1
  fi
  if [ "$(id -u)" -eq 0 ]; then
    log "  ok      running as root"
  elif sudo -n systemctl show "$KOMGA_SERVICE" >/dev/null 2>&1; then
    log "  ok      passwordless sudo systemctl"
  else
    log "  FAILED  cannot run 'sudo -n systemctl' - see the sudoers line in auto-release.conf.example"
    ok=1
  fi
  [ -f "$KOMGA_JAR" ] && log "  ok      jar      $KOMGA_JAR" || { log "  FAILED  jar not found: $KOMGA_JAR"; ok=1; }
  [ -w "$(dirname "$KOMGA_JAR")" ] && log "  ok      writable $(dirname "$KOMGA_JAR")" || { log "  FAILED  cannot write $(dirname "$KOMGA_JAR") - run as root?"; ok=1; }
  [ -f "$KOMGA_CONFIG_DIR/database.sqlite" ] && log "  ok      database $KOMGA_CONFIG_DIR/database.sqlite" || { log "  FAILED  no database.sqlite in $KOMGA_CONFIG_DIR"; ok=1; }

  mkdir -p "$BACKUP_DIR" 2>/dev/null
  [ -w "$BACKUP_DIR" ] && log "  ok      backups  $BACKUP_DIR" || { log "  FAILED  cannot write $BACKUP_DIR"; ok=1; }

  log "Health endpoint:"
  if curl -fsS -o /dev/null -m 10 "$KOMGA_URL/" 2>/dev/null; then
    log "  ok      $KOMGA_URL responding"
  else
    log "  note    $KOMGA_URL not responding (fine if Komga is stopped)"
  fi

  log "Disk:"
  local jar_dir
  jar_dir=$(dirname "$KOMGA_JAR")
  if [ -d "$jar_dir" ]; then
    log "  $(df -h "$jar_dir" | tail -1)"
  else
    log "  skipped, $jar_dir does not exist yet"
  fi

  if [ "$ok" -eq 0 ]; then log "Preflight passed."; else log "Preflight found problems above."; fi
  return "$ok"
}

# ---------------------------------------------------------------- pipeline steps

rebase_onto_latest() {
  local rc
  bash scripts/sync-release.sh --check; rc=$?
  case "$rc" in
    0) log "Already on the latest upstream release; nothing to do."; return 10 ;;
    1) log "A newer upstream release exists; continuing." ;;
    *) die "release check failed (exit $rc)" ;;
  esac

  git checkout --quiet "$WORK" || die "cannot check out $WORK"

  bash scripts/sync-release.sh; rc=$?
  case "$rc" in
    0) log "Rebased onto the new release." ;;
    2)
      # sync-release leaves the rebase in progress on purpose, for a human to resolve.
      git rebase --abort 2>/dev/null
      notify_failure "Rebase onto the new upstream release hit conflicts, so the pipeline stopped before building. The branch is untouched and a backup branch was left behind. Resolve by hand with \`git release-sync\`."
      return 11
      ;;
    *) die "rebase failed (exit $rc)" ;;
  esac
}

build_and_test() {
  log "Building frontends ..."
  ( cd komga-webui && npm ci --no-audit --no-fund && npm run build ) || return 1
  ( cd next-ui && npm ci --no-audit --no-fund && npm run build:with-i18n ) || return 1

  log "Building jar ..."
  ./gradlew :komga:webuiCopyIndex :komga:nextuiCopyIndex :komga:bootJar --console=plain || return 1

  log "Testing ..."
  ./gradlew :komga:test ktlintCheck --console=plain || return 1
}

# The build number lives in the jar's name, and it comes from the tags that already exist, so the
# jar has to be built before the tag is created or the number would always be one ahead.
find_built_jar() {
  local version jar
  version=$(grep '^version' gradle.properties | cut -d= -f2 | tr -d '[:space:]')
  jar=$(ls -1 "komga/build/libs/komga-$version-ShabbyFork-build"*.jar 2>/dev/null | head -1)
  [ -n "$jar" ] || return 1
  printf '%s' "$jar"
}

publish_release() {
  local jar="$1" tag="$2" version="$3" upstream_notes
  upstream_notes="https://github.com/gotson/komga/releases/tag/$version"

  git push --force-with-lease origin "$WORK" || return 1
  git tag -a "$tag" -m "ShabbyFork build on Komga $version" || return 1
  git push origin "$tag" || return 1

  gh release create "$tag" --repo "$GH_REPO" --title "$tag" --notes "$(cat <<EOF
Built automatically from upstream **Komga $version** with the ShabbyFork patches on top. No
unreleased upstream code is included: this is exactly the $version release plus our changes.

The server reports its version as \`v$tag\`.

Upstream's own release notes: $upstream_notes

See [FORK_CHANGELOG.md](https://github.com/$GH_REPO/blob/$WORK/FORK_CHANGELOG.md) for what this
fork adds, including which build each change first shipped in.
EOF
)" "$jar" || return 1
}

# ---------------------------------------------------------------- deploy

# The databases run in WAL mode, so `database.sqlite` is only part of the story. A clean
# shutdown checkpoints the -wal away, but a stop that times out and gets killed leaves
# committed transactions in it. Worse, restoring a database while a newer -wal is still lying
# next to it lets SQLite replay that foreign WAL onto it, so the sidecars have to travel with
# the database in both directions.
backup_sqlite() {
  local dir="$1" name="$2" side
  [ -f "$KOMGA_CONFIG_DIR/$name" ] || return 0
  cp -p "$KOMGA_CONFIG_DIR/$name" "$dir/" || return 1
  for side in -wal -shm; do
    [ -f "$KOMGA_CONFIG_DIR/$name$side" ] && { cp -p "$KOMGA_CONFIG_DIR/$name$side" "$dir/" || return 1; }
  done
  return 0
}

restore_sqlite() {
  local dir="$1" name="$2" side
  [ -f "$dir/$name" ] || return 0
  rm -f "$KOMGA_CONFIG_DIR/$name-wal" "$KOMGA_CONFIG_DIR/$name-shm"
  cp -p "$dir/$name" "$KOMGA_CONFIG_DIR/$name" || return 1
  for side in -wal -shm; do
    [ -f "$dir/$name$side" ] && { cp -p "$dir/$name$side" "$KOMGA_CONFIG_DIR/$name$side" || return 1; }
  done
  return 0
}

deploy() {
  local jar="$1" stamp backup
  stamp=$(date +%Y%m%d-%H%M%S)
  backup="$BACKUP_DIR/$stamp"
  mkdir -p "$backup" || return 1

  log "Stopping $KOMGA_SERVICE ..."
  systemctl_cmd stop "$KOMGA_SERVICE" || return 1

  # A clean stop checkpoints the WAL into the database and removes the sidecars, so normally
  # there is nothing but database.sqlite to copy. A sidecar still being here means the stop was
  # cut short by its systemd timeout, which is the one case where copying the database alone
  # would produce a backup that is not just stale but unreadable.
  if [ -f "$KOMGA_CONFIG_DIR/database.sqlite-wal" ] || [ -f "$KOMGA_CONFIG_DIR/tasks.sqlite-wal" ]; then
    log "NOTE: a -wal is still present after stopping $KOMGA_SERVICE, so the shutdown was not clean."
    log "      Backing the sidecars up with their databases. Consider raising TimeoutStopSec."
  fi

  # With the service down the SQLite files are quiescent, so a plain copy is consistent.
  log "Backing up jar and databases to $backup ..."
  cp -p "$KOMGA_JAR" "$backup/" || return 1
  backup_sqlite "$backup" database.sqlite || return 1
  backup_sqlite "$backup" tasks.sqlite || return 1

  # Keep the versioned jar alongside the live one, so the jar folder stays a history of what
  # has run and a manual rollback is just a copy. Installing the jar that is already in place is
  # a no-op rather than an error, which is what lets --deploy-only rehearse this path harmlessly.
  if [ "$(readlink -f "$jar")" = "$(readlink -f "$KOMGA_JAR")" ]; then
    log "Installing: $(basename "$jar") is already the jar in place, nothing to copy."
  else
    log "Installing $(basename "$jar") ..."
    cp -p "$jar" "$(dirname "$KOMGA_JAR")/" || return 1
    cp -p "$jar" "$KOMGA_JAR" || return 1
  fi

  log "Starting $KOMGA_SERVICE ..."
  systemctl_cmd start "$KOMGA_SERVICE" || { rollback "$backup"; return 2; }

  if wait_healthy; then
    log "Healthy."
    prune_backups
    return 0
  fi

  log "Did not come up healthy within ${HEALTH_TIMEOUT}s; rolling back."
  rollback "$backup"
  return 2
}

# Migrations on a large library can take a while, so this waits rather than probing once.
wait_healthy() {
  local deadline=$((SECONDS + HEALTH_TIMEOUT))
  while [ "$SECONDS" -lt "$deadline" ]; do
    if curl -fsS -o /dev/null -m 10 "$KOMGA_URL/" 2>/dev/null; then return 0; fi
    sleep 10
  done
  return 1
}

rollback() {
  local backup="$1"
  log "ROLLBACK from $backup"
  systemctl_cmd stop "$KOMGA_SERVICE" 2>/dev/null
  cp -p "$backup/$(basename "$KOMGA_JAR")" "$KOMGA_JAR" || log "rollback: could not restore jar"
  restore_sqlite "$backup" database.sqlite || log "rollback: could not restore database.sqlite"
  restore_sqlite "$backup" tasks.sqlite || log "rollback: could not restore tasks.sqlite"
  systemctl_cmd start "$KOMGA_SERVICE" 2>/dev/null
  if wait_healthy; then log "Rolled back and healthy again."; else log "ROLLED BACK BUT STILL UNHEALTHY - needs a human."; fi
}

prune_backups() {
  local n
  n=$(ls -1d "$BACKUP_DIR"/*/ 2>/dev/null | wc -l)
  [ "$n" -gt "$KEEP_BACKUPS" ] || return 0
  ls -1d "$BACKUP_DIR"/*/ | sort | head -n "$((n - KEEP_BACKUPS))" | while read -r d; do
    log "Pruning old backup $d"
    rm -rf "$d"
  done
}

# ---------------------------------------------------------------- main

main() {
  local mode="${1:-run}" rc jar tag version

  load_config
  cd "$(git rev-parse --show-toplevel)" || die "not in a git repository"

  case "$mode" in
    --preflight) preflight; exit $? ;;
    --check)     bash scripts/sync-release.sh --check; exit $? ;;
  esac

  preflight || die "preflight failed; fix the above first"

  # Exercises the riskiest and least testable part of the pipeline against the jar that is
  # already installed: a real stop, a real backup, a real start and a real health check, with
  # nothing actually changed. Worth running once before trusting the timer.
  if [ "$mode" = "--deploy-only" ]; then
    log "Deploy rehearsal using the installed jar: $KOMGA_JAR"
    deploy "$KOMGA_JAR"; rc=$?
    case "$rc" in
      0) log "Deploy path is sound: stop, backup, install, start and health check all passed."; exit 0 ;;
      2) notify_failure "A deploy rehearsal (\`--deploy-only\`) failed its health check and was rolled back. The jar was unchanged, so this points at the service or the health check rather than at a build."; exit 2 ;;
      *) die "deploy rehearsal failed before it changed anything" ;;
    esac
  fi

  rebase_onto_latest; rc=$?
  case "$rc" in
    0)  ;;
    10)
      # A dry run is a rehearsal, so it builds and tests even when there is nothing new to take.
      if [ "$mode" = "--dry-run" ]; then
        log "Nothing new upstream; rehearsing the build and tests anyway."
      else
        exit 0
      fi
      ;;
    11) exit 1 ;;
    *)  exit 3 ;;
  esac

  version=$(grep '^version' gradle.properties | cut -d= -f2 | tr -d '[:space:]')

  if ! build_and_test; then
    notify_failure "Build or tests failed after rebasing onto upstream **$version**. Nothing was released and production was not touched. The rebased branch is on \`$WORK\` locally, and the pre-rebase state is on the \`backup/\` branch left by the sync script."
    exit 1
  fi

  jar=$(find_built_jar) || { notify_failure "The build reported success but no jar matching komga-$version-ShabbyFork-build*.jar was found."; exit 3; }
  tag="$(basename "$jar" .jar | sed "s/^komga-//")"
  log "Built $jar (tag $tag)"

  if [ "$mode" = "--dry-run" ]; then
    log "Dry run: stopping before tag, push, release and deploy."
    exit 0
  fi

  if ! publish_release "$jar" "$tag" "$version"; then
    notify_failure "Built and tested **$tag**, but publishing the GitHub release failed. Production was not touched."
    exit 1
  fi
  log "Released $tag"

  deploy "$jar"; rc=$?
  case "$rc" in
    0) notify_success "Released **$tag** (upstream $version) and upgraded production. Health check passed." ; exit 0 ;;
    2) notify_failure "Released **$tag**, but production did not come up healthy and was rolled back to the previous jar **and database**. The release itself is fine; the server is back on the old build."; exit 2 ;;
    *) notify_failure "Released **$tag**, but the deploy step failed before it could swap anything. Production should be untouched - check \`systemctl status $KOMGA_SERVICE\`."; exit 3 ;;
  esac
}

exec 9>"$LOCK" || die "cannot open lock file $LOCK"
flock -n 9 || { echo "another run is in progress"; exit 0; }

main "${1:-run}"
