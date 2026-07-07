# Step 03: Gitea Client

Prerequisites: step 01 (for `BuildStatus`).
Read `README.md` and `00-legacy-analysis.md` first.

## Goal

A tested client for the Gitea commit-status API.

## Design

Create package `de.hoennig.gittally.gitea`:

- `GiteaClient` using Spring's `RestClient`.
- `publishStatus(sha, state, description, targetUrl)` → `POST /api/v1/repos/{owner}/{repo}/statuses/{sha}` with header `Authorization: token <git.token>`; body fields `state`, `context`, `description`, `target_url`.
- `readStatus(sha)` → `GET /api/v1/repos/{owner}/{repo}/commits/{sha}/statuses?sort=recentupdate`; pick the newest entry matching `gitea.statusContext`.
- `resolveUsername()` → `GET /api/v1/user` (used as fallback for `git.account`).
- State mapping in both directions (`BuildStatus` ↔ Gitea `success|failure|pending|error`), as in the legacy analysis.
- `isEnabled()` — true only when `gitea.baseUrl`, `gitea.owner`, `gitea.repo`, and `git.token` are configured.
  All callers must treat a disabled or failing client as non-fatal (log and continue); the legacy behaved the same but failed silently.

Configuration comes from `GitTallyConfig` (`gitea.*`, `git.token`).

## Out of Scope

- No callers yet; the build executor (step 04) wires status publishing.
- No webhook receiving; GitTally remains poll-based.

## Tests

WireMock (already a test dependency, see `WireMockSmokeTest`):

- Publish: correct URL, auth header, JSON body per status.
- Read: filtering by context, newest-first, empty result, malformed JSON → error status, HTTP 4xx/5xx → non-fatal error result.
- State-mapping unit tests.

## Acceptance Criteria

- `./gradlew ktlintFormat` then `./gradlew build` is green.
- No call path throws when Gitea is unconfigured or down.

## Execution Notes (2026-07-07)

Implemented as designed; deviations and decisions:

- Added `org.springframework:spring-web` as a dependency; `RestClient` lives there and `spring-boot-starter` alone does not provide it.
- `publishStatus` takes a `BuildStatus` and maps it internally instead of a raw Gitea state string.
  The forward mapping never produces `error` because the `BuildStatus` enum is exhaustive; legacy emitted `error` only for unknown status strings.
- `readStatus` returns a sealed `GiteaStatusResult` (`Found`/`None`/`Disabled`/`Error`) so callers get explicit non-fatal error states instead of exceptions.
- `resolveUsername` only requires `gitea.baseUrl` and `git.token`; legacy gated it on the full status-enabled check including owner/repo, which the `/api/v1/user` endpoint does not need.
- Responses are read as strings and parsed with a dedicated Jackson `ObjectMapper` instead of RestClient message converters, keeping malformed-JSON handling explicit and independent of converter auto-detection.
- The legacy "Build status deleted" description marker is not ported; it belongs to the result-delete feature of later steps.
- No config changes were needed: `gitea.*` and `git.token` already exist in `GitTallyConfig`, the `init` templates, and `docs/configuration.md`.
