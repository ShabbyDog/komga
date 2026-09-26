# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Fork workflow (read this first)

This checkout is a **maintained fork** of `gotson/komga`. Upstream does not accept pull
requests, so our changes live here permanently.

| Remote | Points at | Use |
| --- | --- | --- |
| `upstream` | `gotson/komga` | Fetch only. Its push URL is intentionally invalid. |
| `origin` | `ShabbyDog/komga` | Where we push. |

**Every jar we build must be "the last upstream *release* + our changes" — never a
release plus unreleased upstream commits.** That is the whole shape of this workflow.

- **`ShabbyFork` is the build branch.** Its history is always linear: the newest upstream
  **release tag**, then our commits on top. Nothing else. It is what you build from.
- **We rebase onto release tags; we do not merge `upstream/master`.** Merging master would
  pull unreleased commits into the jar, which is exactly what we are avoiding. Rebasing
  rewrites history, so pushing uses `git push --force-with-lease origin ShabbyFork`.
- **`master` is a pristine mirror of `upstream/master`. Never commit to it.** It exists only
  to see what upstream is doing. A local `pre-commit` hook blocks commits on it
  (`git commit --no-verify` overrides). Advance it with `git fetch upstream master:master`.
- `git diff <release tag> ShabbyFork` should only ever show our own files.

### Moving onto a new upstream release

```bash
git release-check    # is there a newer release tag? what would be replayed?
git release-sync     # rebase ShabbyFork onto the newest release tag
```

Both are `scripts/sync-release.sh`. It fetches tags, picks the newest `X.Y.Z` tag by
creation date, reports which release we are built on, and rebases our commits onto the
new tag. It refuses to run on a dirty tree and always leaves a `backup/ShabbyFork-<stamp>`
branch before rewriting anything. Exit codes: `0` already newest, `1` a newer release
exists, `2` rebase stopped on conflicts, `3` error.

After it succeeds: `git push --force-with-lease origin ShabbyFork`.

The fork build number restarts at `build1` on the new release, because it is derived from the
`<version>-ShabbyFork-build<n>` tags of the version being built. Tag the first jar you build on
it, as always.

### Early warning about the next release

```bash
git upstream-check   # what has landed on upstream/master since our release
git upstream-sync    # same, plus fast-forwards the `master` mirror
```

`scripts/check-upstream.sh` is informational only — those commits are *not* in our jar.
Its value is the last section, which lists upstream changes that touch files we have
modified: that is advance notice that our patches will need work when the next release
ships.

### Building a jar

Build from `ShabbyFork` (confirm with `git release-check` first). Requires JDK 21+;
`org.gradle.java.home` is pinned in `~/.gradle/gradle.properties` because the system
`JAVA_HOME` is JDK 17.

```bash
cd komga-webui && npm ci && npm run build
cd ../next-ui   && npm ci && npm run build:with-i18n
cd .. && ./gradlew :komga:webuiCopyIndex :komga:nextuiCopyIndex :komga:bootJar
```

The runnable jar is `komga/build/libs/komga-<version>-ShabbyFork-build<n>.jar` (the
`ShabbyFork-build<n>` suffix is a `bootJar` archive classifier, set in `komga/build.gradle.kts`),
and it reports `v<version>-ShabbyFork-build<n>`, where the version comes from `gradle.properties`
at the release tag and the branch name from `gradle-git-properties`. Note the classifier means
the jar no longer sits at the path upstream's jreleaser config expects; we do not run jreleaser.

`<n>` is the fork build number, which separates several builds made against the same upstream
release. It is derived from the fork release tags for the current version: the highest existing
`<version>-ShabbyFork-build<n>` tag plus one, falling back to 1.

**Always tag a jar as soon as you build one you intend to keep.** Tagging is the only thing that
advances the number, so an untagged build hands the same `<n>` to the next one and two different
jars end up with the same name — exactly the confusion the number exists to prevent:

```bash
git tag -a 1.27.1-ShabbyFork-build1 -m 'ShabbyFork build 1 on Komga 1.27.1'
```

The tag pattern is scoped to the upstream version, so **a new upstream release starts again at
`build1`** on its first build. There is no counter to reset by hand: once `ShabbyFork` is rebased
onto, say, 1.28.0, no `1.28.0-ShabbyFork-build*` tag exists yet, so the next jar is
`komga-1.28.0-ShabbyFork-build1.jar`.

The number is also published through `/actuator/info` as `build.forkBuild` (added to `buildInfo`
in `komga/build.gradle.kts`), which is what both UIs render next to the version. The project
version itself is deliberately left bare, because it is what the updates screen compares against
upstream's release tags.

### Test on Windows and Linux before every build

**Every jar is tested on both platforms before it ships.** This Windows machine can do both,
so there is no reason to skip Linux: production runs Linux, and the GitHub Actions that used
to cover it are disabled (see below). macOS is covered by neither.

```bash
# Windows
./gradlew :komga:test ktlintCheck

# Linux, from the same checkout, via WSL
git archive --format=tar HEAD | wsl -d Ubuntu -- bash -lc 'rm -rf ~/komga-linux && mkdir -p ~/komga-linux && tar x -C ~/komga-linux'
wsl -d Ubuntu -- bash -lc 'cd ~/komga-linux && sed -i "s/\r$//" gradlew && git init -q && git add -A && git -c user.email=t@t.local -c user.name=wsl commit -qm snapshot'
wsl -d Ubuntu -- bash -lc 'cd ~/komga-linux && JAVA_HOME=$HOME/jdk21 PATH=$HOME/jdk21/bin:$PATH ./gradlew :komga:test ktlintCheck --console=plain'
```

The WSL side needs JDK 21+. `~/jdk21` is a Temurin tarball extracted by hand — no root, no
apt. Three things that otherwise waste an hour:

- `gradlew` arrives with CRLF from the Windows checkout and dies with
  `bad interpreter: /bin/sh^M`. Strip it: `sed -i 's/\r$//' gradlew`.
- `git archive` carries no `.git`, and `gradle-git-properties` refuses to run without one.
  A throwaway `git init` plus one commit is enough.
- Build **inside the Linux filesystem, never `/mnt/c`**. The two platforms would share one
  `build/` directory, and inotify does not fire reliably on `/mnt/c`, so
  `LibraryFileWatcherTest` would report nonsense there.

### Automated releases on the Linux server

`scripts/auto-release.sh` runs the whole chain unattended, on the Linux box that also serves
production: check upstream, rebase, build, test, tag, publish a GitHub release, then upgrade
the local service. It is driven by a systemd timer (`scripts/komga-auto-release.timer.example`)
and configured by `~/.config/komga-auto.conf`, copied from `scripts/auto-release.conf.example`
and kept outside the repo so machine-specific paths never reach the fork diff.

```bash
bash scripts/auto-release.sh --preflight  # check the environment, change nothing
bash scripts/auto-release.sh --check      # is there a newer upstream release?
bash scripts/auto-release.sh --dry-run     # rebase, build and test, but do not publish or deploy
bash scripts/auto-release.sh --deploy-only # rehearse stop/backup/start/health on the installed jar
bash scripts/auto-release.sh               # the real thing
```

Rehearse both halves before trusting the timer. `--dry-run` builds and tests even when there is
nothing new upstream, so it exercises the build without waiting for a release. `--deploy-only`
runs the stop, backup, install, start and health check against the jar already installed, which
proves the deploy path and takes a real backup while changing nothing.

Exit codes: `0` nothing to do or fully succeeded, `1` stopped safely before anything mattered,
`2` deploy failed and was rolled back, `3` error. Failures open an issue on the fork and a
success closes it, so a recurring failure cannot bury its own notifications.

What it refuses to do:

- **Deploy if the rebase conflicted.** `sync-release.sh` leaves the rebase in progress for a
  human; the pipeline aborts it, leaves the `backup/` branch alone, and stops.
- **Deploy if the tests fail.** The release is not published either.
- **Upgrade without a way back.** Komga runs Flyway migrations on startup and Flyway has no
  undo, so a newer jar moves the database forward permanently. The service is stopped *before*
  the backup is taken (a copy of a live SQLite file is not guaranteed consistent), and a
  rollback restores the database as well as the jar.

Two things it cannot do, by construction:

- **It only tests Linux.** The dual-platform rule above still needs a Windows run, which is
  ours to do when we write a change; the pipeline's job is upstream rebases.
- **It cannot resolve conflicts or judge upstream's changes.** A release that needs either
  stops and waits.

### Upstream's GitHub Actions are disabled

The fork inherits upstream's workflows, and several of them talk to upstream's own
infrastructure. They are disabled **as repo state, not by editing the files** (`gh workflow
disable <id>`), so nothing is added to the fork diff and nothing conflicts on rebase.

Disabled: Discord announce release, Dispatch events (sends `repository_dispatch` to
`gotson/komga-website`), Update DockerHub description, Chromatic, Chromatic for pull
requests, Release, Test Komga, Test NextUI, Test WebUI, Validate NextUI i18n.

Still active, because they only touch this fork: Lock threads, Update Browserslist database.

```bash
gh workflow list --repo ShabbyDog/komga --all      # check state
gh workflow enable <id> --repo ShabbyDog/komga     # undo
```

**Re-check this list after each upstream release**: a workflow file that upstream adds
arrives enabled by default, and the disable only applies to workflows that already existed.

### Fork-only additions

- `FORK_CHANGELOG.md` — the fork's changelog, rendered above the upstream releases on the
  updates screen in both UIs. Single source of truth; edit this file, nothing else. Each entry
  carries an `*Added in <version>-ShabbyFork-build<n>.*` line under its heading, recording the
  build it first shipped in; add one when you add an entry.
- `scripts/` — the sync and drift scripts above, plus `auto-release.sh` (below) and
  `.gitattributes` pinning them to LF.
- `next-ui/.gitattributes` — pins generated files to LF so builds do not dirty the tree.
- `next-ui/src/utils/i18n/locale-messages.ts` — loads translations via Vite's glob import
  instead of `vite-plugin-dir2json`, whose Windows paths break `vite build`. Without this
  `npm run build:with-i18n` cannot produce a bundle on Windows. Re-check this on each
  upstream release: 1.27.0 reshaped the loading to be lazy and the patch had to follow.

## Projects

Komga is a media server for comics/mangas/BDs/eBooks. Four projects, only two are Gradle modules (`settings.gradle` includes `komga` and `komga-tray`; the frontends are built with npm and copied into the backend's resources):

- `komga` — Spring Boot / Kotlin backend. Hosts the REST/OPDS/Kobo APIs and serves the frontends' static assets.
- `komga-webui` — legacy Vue 2 + Vuetify 2 + Vuex frontend (Vue CLI/webpack). Served at `/`.
- `next-ui` — new Vue 3 + Vuetify 4 + Pinia Colada frontend (Vite). Served at `/next`.
- `komga-tray` — Compose Desktop tray wrapper around `:komga`, packaged with Conveyor.

Each project has its own `README.md` with detail; `next-ui/AGENTS.md` documents that project's conventions.

## Backend (`komga`)

### Commands

Run from the repo root (`./gradlew` on POSIX, `gradlew.bat` on Windows; this repo is checked out on Windows).

```bash
./gradlew build                              # compile + test + ktlint
./gradlew :komga:test                        # backend tests only
./gradlew :komga:test --tests "org.gotson.komga.domain.service.SeriesLifecycleTest"
./gradlew :komga:test --tests "*.SeriesLifecycleTest.some test name"
./gradlew ktlintCheck / ktlintFormat         # lint / autoformat (ktlint 1.8, applied to all projects)
./gradlew :komga:benchmark                   # JMH benchmarks (separate `benchmark` sourceSet)
./gradlew :komga:generateOpenApiDocs         # regenerates komga/docs/openapi.json (boots the app)
```

Tests run with `spring.profiles.active=test`. Java 21+ is required to build; bytecode targets JVM 17.

### Running locally

`bootRun` must be given profiles — see `komga/README.md`. Typical:

```bash
./gradlew :komga:bootRun --args='--spring.profiles.active=dev,noclaim'
```

`dev` binds port 8080 (not the production 25600), uses an in-memory DB, enables CORS for the frontend dev servers, and writes config to `./config-dir`. `localdb` persists the DB in `./localdb`. `noclaim` seeds `admin@example.org`/`admin` and `user@example.org`/`user` when combined with `dev`. Frontend dev servers talk to `localhost:8080`, so the backend **must** run with `dev` or requests are blocked by CORS.

### Architecture

Hexagonal/DDD layering under `org.gotson.komga`, enforced by ArchUnit tests in `komga/src/test/kotlin/org/gotson/komga/architecture/` — these fail the build, so respect them:

- `domain/model` — pure domain classes. Must not depend on `infrastructure`, `interfaces`, `domain.persistence`, or `domain.service`.
- `domain/persistence` — repository interfaces.
- `domain/service` — business logic. Classes here (and in `application.service`) must **not** be named `*Service`/`*Manager`; the convention is `*Lifecycle` (`BookLifecycle`, `SeriesLifecycle`, …), `*Analyzer`, `*Importer`, `*Converter`.
- `application/tasks` — async work queue. `TaskEmitter` submits a `Task` to `TasksRepository`, `TaskProcessor` (a thread pool sized by settings) picks it up and dispatches to `TaskHandler`.
- `infrastructure/*` — jOOQ DAOs implementing the domain repositories, security (session/API key/OAuth2), Lucene search, image/media containers (divina, epub, pdf), metadata providers (comicrack, epub, mylar, barcode).
- `interfaces/*` — inbound adapters: `api/rest`, `api/opds/{v1,v2}`, `api/kobo`, `api/kosync`, `sse`, `mvc`. Classes named `*Controller` must live under `interfaces`, and `@RestController`/`@Controller` classes must be suffixed `Controller`. **Interface slices must not depend on each other** (e.g. `api/rest` cannot import from `api/opds`).

### Database & jOOQ (important build wiring)

Two SQLite databases: `main` (`database.sqlite`) and `tasks` (`tasks.sqlite`), each with its own Flyway migrations and its own generated jOOQ package (`org.gotson.komga.jooq.main` / `.tasks`).

Migrations live in `komga/src/flyway/` — SQL in `resources/db/migration/sqlite` (+ `resources/tasks/migration/sqlite`), and Kotlin migrations in `kotlin/db/migration/sqlite`. Filenames are `V<yyyyMMddHHmmss>__description`.

The jOOQ classes are **generated from the migrations**: `generateJooq`/`generateTasksJooq` first run `flywayMigrateMain`/`flywayMigrateTasks` against a throwaway SQLite file under `build/generated/flyway/`. `compileKotlin` and the ktlint tasks depend on this, so after adding a migration just build — the DSL regenerates. Generated sources (`build/generated-src/jooq/`) are excluded from ktlint, as is `**/db/migration/**`.

### Serving the frontends

The frontends are not built by Gradle. The release flow is: `npm run build` in `komga-webui`, `npm run build:with-i18n` in `next-ui`, then `./gradlew :komga:webuiCopyIndex :komga:nextuiCopyIndex :komga:bootJar`.

`webuiCopyIndex`/`nextuiCopyIndex` copy `dist/` into `komga/src/main/resources/public/` and rewrite `index.html`, duplicating `src`/`href`/`content` attributes as Thymeleaf `th:` variants so the servlet context path can be injected at runtime (next-ui's becomes `index-next.html`). `IndexController` serves `index` at `/` and `index-next` at `/next`, injecting `baseUrl`. Don't hand-edit anything in `resources/public/` — it's build output.

## `next-ui`

```bash
npm run dev            # Vite dev server on :3000; calls the API at localhost:8080 (VITE_KOMGA_API_URL in .env.development)
npm run test:unit      # vitest, `unit` project
npm run test:storybook # vitest, `storybook` project (needs playwright)
npm run lint:fix / prettier:fix / type-check
npm run openapi-ts     # regenerate the typed API client from ../komga/docs/openapi.json
npm run formatjs:extract  # extract messages into ./i18n (what Weblate consumes)
npm run i18n:compile      # compile ./i18n into ./src/i18n (what the app loads)
npm run storybook:dev
```

- API access goes through the Hey API generated client in `src/generated/openapi` (do not edit; regenerate with `openapi-ts` after `generateOpenApiDocs`) wrapped by Pinia Colada queries/mutations in `src/colada`.
- `src/pages` are file-based routes (each has a `<route>` block for layout/meta), `src/layouts` wrap them, `src/components` are pure UI. Components and common APIs are auto-imported (`components.d.ts`, `auto-imports.d.ts` are generated).
- i18n is FormatJS with **auto-generated message IDs** — never hard-code an ID. Write the message with only `description` and `defaultMessage`; `npm run lint:fix` fills in the `id` via the `formatjs/enforce-id` ESLint rule. Then run `npm run formatjs:extract` (there is no `i18n:extract` script), because CI fails if extracting produces a diff.
- Icons come from UnoCSS's icon preset (MDI/Tabler via Iconify), not a font.
- MSW mocks the Komga API for tests and Storybook.
- Vite does not type-check; run `type-check` separately.

## `komga-webui` (legacy)

```bash
npm run serve      # dev server on :8081
npm run build
npm run test:unit  # jest
npm run lint
```

Prefer `next-ui` for new frontend features; touch `komga-webui` only for fixes to the legacy UI.

**The type check does not run, and has not for a long time.** `vue-cli-service build` hands
type checking to a worker that `fork-ts-checker-webpack-plugin` forks with
`--max-old-space-size=<memoryLimit>`; that worker exhausts its heap and is killed, webpack then
prints `DONE` and exits 0, so the bundle ships with nothing checked. Measured on 2026-09-26, on
Windows and on the Linux server alike: it dies at ~4 GB by default, and raising `memoryLimit`
to 8192 in `vue.config.js` only moves the ceiling -- it dies at ~8.2 GB instead. `NODE_OPTIONS`
is not a lever: the plugin's flag is on the worker's command line and beats the environment.
`tsconfig.json` is unremarkable (`src` only, `node_modules` excluded), so the appetite is the
type graph itself. Nothing in the fork changes this; `auto-release.sh` reports it on every build
and does not block the release on it, since it has never once passed.

## Conventions

- Commits follow Conventional Commits — versioning, changelog, and releases are automated from them. Allowed types are listed in `conventionalcommit.json` (notably `i18n` and `deps` in addition to the usual set); the scope shows up in the generated changelog.
- Kotlin: 2-space indent, ktlint with trailing commas allowed, no max line length (see `.editorconfig`).
- `ERRORCODES.md` lists the `ERR_XXXX` codes returned by the API; add new ones there.
