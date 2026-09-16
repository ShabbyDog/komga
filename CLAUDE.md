# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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
npm run i18n:extract   # extract messages into ./i18n (what Weblate consumes)
npm run i18n:compile   # compile ./i18n into ./src/i18n (what the app loads)
npm run storybook:dev
```

- API access goes through the Hey API generated client in `src/generated/openapi` (do not edit; regenerate with `openapi-ts` after `generateOpenApiDocs`) wrapped by Pinia Colada queries/mutations in `src/colada`.
- `src/pages` are file-based routes (each has a `<route>` block for layout/meta), `src/layouts` wrap them, `src/components` are pure UI. Components and common APIs are auto-imported (`components.d.ts`, `auto-imports.d.ts` are generated).
- i18n is FormatJS with **auto-generated message IDs** — never hard-code an ID. CI fails if `i18n:extract` produces a diff, so run it when you touch messages.
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

## Conventions

- Commits follow Conventional Commits — versioning, changelog, and releases are automated from them. Allowed types are listed in `conventionalcommit.json` (notably `i18n` and `deps` in addition to the usual set); the scope shows up in the generated changelog.
- Kotlin: 2-space indent, ktlint with trailing commas allowed, no max line length (see `.editorconfig`).
- `ERRORCODES.md` lists the `ERR_XXXX` codes returned by the API; add new ones there.
