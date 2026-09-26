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

# Delivery is reported rather than swallowed: a notification channel that has quietly stopped
# working is worse than none, because the run log then looks like everything was fine.
notify_failure() {
  local body="$1" num out
  log "NOTIFY: $body"
  command -v gh >/dev/null || { log "notify: gh is not installed, so that was only logged"; return 0; }
  num=$(open_issue_number)
  if [ -n "${num:-}" ] && [ "$num" != "null" ]; then
    out=$(gh issue comment "$num" --repo "$GH_REPO" --body "$body" 2>&1) ||
      log "notify: could not comment on issue #$num: $out"
  else
    out=$(gh issue create --repo "$GH_REPO" --title "$ISSUE_TITLE" --body "$body" 2>&1) ||
      log "notify: could not open an issue ($out). Enable issues: gh repo edit $GH_REPO --enable-issues"
  fi
}

notify_success() {
  local body="$1" num out
  log "$body"
  command -v gh >/dev/null || return 0
  num=$(open_issue_number)
  if [ -n "${num:-}" ] && [ "$num" != "null" ]; then
    out=$(gh issue close "$num" --repo "$GH_REPO" --comment "$body" 2>&1) ||
      log "notify: could not close issue #$num: $out"
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
  need node "needed to build the frontends"; need npm; need systemctl; need java "a JVM to run Gradle"

  # This build declares no Java toolchain, so Gradle compiles with the JVM it runs on. What matters
  # is therefore not whether *some* javac is on PATH but whether the home Gradle picks has one --
  # otherwise the build dies much later with "Toolchain installation ... does not provide the
  # required capabilities: [JAVA_COMPILER]". Resolve that home the way Gradle does.
  local jhome="" v
  if [ -n "${JAVA_HOME:-}" ]; then
    jhome=$JAVA_HOME
  elif command -v java >/dev/null; then
    jhome=$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")
  fi
  if [ -z "$jhome" ]; then
    log "  MISSING jdk    (no JAVA_HOME set and no java on PATH)"
    ok=1
  elif [ ! -x "$jhome/bin/javac" ]; then
    log "  FAILED  jdk    $jhome has no bin/javac, so it is a JRE and Gradle cannot compile with it"
    if command -v javac >/dev/null; then
      log "                 javac on PATH is $(readlink -f "$(command -v javac)") -- a different"
      log "                 installation; point JAVA_HOME at a home that owns it"
    else
      log "                 install a JDK, e.g. openjdk-21-jdk-headless"
    fi
    ok=1
  else
    v=$("$jhome/bin/javac" -version 2>&1 | head -1)
    if printf '%s' "$v" | grep -qE ' (2[1-9]|[3-9][0-9])'; then
      log "  ok      jdk    $v  ($jhome)"
    else
      log "  TOO OLD jdk    $v  ($jhome)  (JDK 21+ required)"
      ok=1
    fi
  fi

  log "GitHub:"
  if gh auth status >/dev/null 2>&1; then log "  ok      gh authenticated"; else log "  FAILED  gh not authenticated"; ok=1; fi
  # Forks have their issue tracker off by default, and without it a failing unattended run has no
  # way to tell anyone. Not fatal to the pipeline, but it is the difference between a failure you
  # hear about and one you do not.
  if [ "$(gh repo view "$GH_REPO" --json hasIssuesEnabled --jq .hasIssuesEnabled 2>/dev/null)" = "true" ]; then
    log "  ok      issues enabled, so failures can be reported"
  else
    log "  WARNING issues are disabled on $GH_REPO - failures will only reach the run log."
    log "          Enable with: gh repo edit $GH_REPO --enable-issues"
  fi

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
  local wlog rc
  log "Building frontends ..."
  wlog=$(mktemp)
  ( cd komga-webui && npm ci --no-audit --no-fund && npm run build ) 2>&1 | tee "$wlog"
  rc=$?
  if [ "$rc" -ne 0 ]; then rm -f "$wlog"; return 1; fi
  # The legacy webui's type check has never completed on any machine we have: its worker exhausts
  # its heap and is killed, after which webpack prints DONE and exits 0 regardless. Raising the
  # limit moves the ceiling but not the outcome (4 GB and 8 GB both die), so this is reported on
  # every build rather than silently passing -- and not treated as a release blocker, because it
  # predates the pipeline and would otherwise block every release forever. See CLAUDE.md.
  if grep -q 'Issues checking service aborted' "$wlog"; then
    log "WARNING: webui bundle built WITHOUT type checking (checker ran out of heap, known issue)."
  fi
  rm -f "$wlog"
  ( cd next-ui && npm ci --no-audit --no-fund && npm run build:with-i18n ) || return 1

  # Never inherit a running daemon. It caches its probe of the JDK it was started with, so a daemon
  # from before a JDK was installed keeps insisting the home has no compiler, and the build fails on
  # a machine where javac is demonstrably present.
  ./gradlew --stop >/dev/null 2>&1 || true

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

  local dry_note=""
  [ "$mode" = "--dry-run" ] && dry_note=" during a \`--dry-run\` rehearsal"

  if ! build_and_test; then
    notify_failure "Build or tests failed on upstream **$version**${dry_note}. Nothing was released and production was not touched. If a rebase did happen, the pre-rebase state is on the \`backup/\` branch left by the sync script."
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
flock -n 9; lock_rc=$?
if [ "$lock_rc" -ne 0 ]; then
  # Exit 1 means another run holds the lock, which is a normal no-op. Anything else -- 127, flock
  # not installed, above all -- means the lock never worked, and treating that as "already running"
  # would report every unattended night as a quiet success while doing nothing at all.
  [ "$lock_rc" -eq 1 ] || die "flock failed (exit $lock_rc); cannot guarantee a single instance"
  echo "another run is in progress"
  exit 0
fi

main "${1:-run}"
