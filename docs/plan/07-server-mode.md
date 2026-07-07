# Step 07: Server Mode and HTTP API

Prerequisites: steps 04, 05, 06.
Read `README.md` and `00-legacy-analysis.md` first.

## Goal

Implement the `server` subcommand: a persistent web server exposing build state as JSON and serving artifacts.

## Design

Bootstrapping:

- `CLAUDE.md` notes the context starts with web type `none`; the `server` subcommand must run a web context.
  Preferred approach: `ServerCommand` launches a second `SpringApplication` with `WebApplicationType.SERVLET` and a `server` profile, then blocks until shutdown.
  Document the chosen mechanism in the code and, if it deviates, in an ADR.
- Add `spring-boot-starter-web` dependency.
- The watcher (step 06) is active only in the `server` profile.
- New config keys `server.port` and `server.bindAddress` (defaults 18080 / 0.0.0.0, as legacy).

JSON API (package `de.hoennig.gittally.server`), replacing the legacy `/control/*` endpoints:

- `GET /api/builds/latest` — latest build per branch.
- `GET /api/builds/history` — all builds, newest first.
- `GET /api/builds/current` — running build, its live status, and log tail (`?offset=` for incremental log fetch).
- `GET /api/status/{commit}` — effective status including Gitea lookup (replaces `/control/status`); must return an explicit error state on Gitea failure, never hang.
- `POST /api/builds/{branch}/restart`, `POST /api/builds/current/cancel`, `DELETE /api/builds/{artifactKey}` — guarded by a simple token like the legacy cancel token; wire into executor/watcher/repository.
- `GET /api/watcher` — watcher health (last poll, last error).
- Artifact serving: `GET /artifacts/{artifactKey}/**` streaming from the artifact store, with no-cache headers for html/json/log.

## Out of Scope

- HTML pages (step 08); JSON plus artifact files only.
- TLS/reverse proxy (documented in step 12).

## Tests

- `@WebMvcTest` slices with `@MockkBean` per controller (see `CLAUDE.md` conventions).
- Contract tests: JSON shapes, error states (Gitea down → explicit `unknown` status, HTTP 200), cancel token rejection.
- One `@SpringBootTest` on the server profile proving the context boots with watcher and web enabled.

## Acceptance Criteria

- `./gradlew ktlintFormat` then `./gradlew build` is green.
- `java -jar ... server` starts, `GET /api/builds/latest` answers, Ctrl-C shuts down cleanly (manual smoke test; document result in this file).
