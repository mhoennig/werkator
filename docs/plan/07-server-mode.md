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
- `GET /api/builds/current` — the list of running builds (there can be several, one per branch, up to `builds.maxConcurrent`), each with live status and log tail (`?offset=` for incremental log fetch, addressed by artifact key).
- `GET /api/status/{commit}` — effective status including Gitea lookup (replaces `/control/status`); must return an explicit error state on Gitea failure, never hang.
- `POST /api/builds/{branch}/restart`, `POST /api/builds/{artifactKey}/cancel` (cancel takes the artifact key because multiple builds can run concurrently), `DELETE /api/builds/{artifactKey}` — guarded by a simple token like the legacy cancel token; wire into executor/watcher/repository.
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

## Implementation Notes (2026-07-07)

Bootstrapping was implemented as designed, with two additions.
`application-server.yml` switches `spring.main.web-application-type` to `servlet`, because `spring.main.*` properties override the programmatic `SpringApplicationBuilder.web(...)` setting.
`CliRunner` is excluded from the `server` profile so the second context does not run picocli again.
After Ctrl-C, `ServerCommand` parks the command thread while the JVM shuts down; otherwise `main()` races the shutdown hooks and `SpringApplication.exit` prints a stack trace for the already-closed CLI context.

Deviations and decisions:

- The live log tail is a sibling endpoint: `GET /api/builds/current` lists the running builds (with `logSize`), and `GET /api/builds/current/{artifactKey}/log?offset=` fetches the log incrementally, addressed by artifact key as required. Responses are capped at 1 MiB per chunk.
- The control token needs no config key. It is generated on first use and persisted to `.git/gittally/control-token` (mode 600); operators can write their own token there, deleting the file rotates it. Requests pass it via the `X-GitTally-Token` header or a `token` parameter; mismatch answers 403 like legacy.
- `DELETE /api/builds/{artifactKey}` removes the result and then calls `ArtifactStore.prune(history)`, so no new store interface method was needed.
- `GET /api/status/{commit}` also accepts abbreviated hashes (7–40 hex like legacy) and resolves them against the local history. The `GiteaClient` (step 03) gained 10s connect/read timeouts so the endpoint can never hang; a Gitea failure yields HTTP 200 with `status: unknown` (or the local status) plus `giteaError`.
- `POST /api/builds/{branch}/restart` rebuilds the branch's last recorded commit. Branch names containing `/` would need an encoded slash, which Tomcat rejects by default — revisit in step 08 if the UI needs restart for such branches. (Resolved in step 08: the endpoint moved to `POST /api/builds/restart?branch=…`.)
- Spring Boot 4 moved `@WebMvcTest` into the new `spring-boot-starter-webmvc-test` test module (added as test dependency).
- The server-profile `@SpringBootTest` mocks the `Watcher` bean, so booting the test never fetches origin or enqueues builds; watcher wiring is proven by verifying `start()` was called.

Manual smoke test (2026-07-07): in a scratch repository, `java -jar build/libs/gittally-0.1.0-SNAPSHOT.jar server` started on the configured port 18981.
`GET /api/builds/latest` answered `[]` with HTTP 200, `GET /api/watcher` exposed the failing fetch of the origin-less repo as `lastFetchError`, `GET /api/status/<sha>` answered `unknown` with HTTP 200, and cancel without token answered 403.
SIGINT (Ctrl-C) shut the process down cleanly in about 2 seconds: port closed, no exceptions in the log, exit code 130.
