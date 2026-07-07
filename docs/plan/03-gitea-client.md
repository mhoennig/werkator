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
