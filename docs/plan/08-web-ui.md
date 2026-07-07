# Step 08: Web UI

Prerequisites: step 07.
Read `README.md` and `00-legacy-analysis.md` first.

## Goal

The browsable UI: build overview, history, current build with live log, per-build artifact index.
This step fixes the legacy stuck-loading-animation defect.

## Design

Server-rendered Thymeleaf templates plus a small, hand-written JavaScript file polling the step 07 JSON API.
No SPA framework, no build pipeline for the frontend.

Views (ported from legacy, see analysis for columns and behavior):

- `/` (Latest): latest build per branch — status badge, branch and commit with Gitea links and copy buttons, times, duration, artifact link, restart/delete actions.
- `/history`: all builds.
- `/current`: running build with live log view (incremental fetch via `/api/builds/current?offset=`), cancel button, and a clear "no build running" state.
- `/builds/{artifactKey}`: artifact index — build command, logs, links into archived report directories (rendered from the artifact store, not pre-generated HTML).
- Shared layout: view toggle nav, footer with version and optional impressum link, `prefers-color-scheme` support (port the legacy CSS look loosely, keep it simple).

Robust live updates (the actual bug fix):

- Poll JSON endpoints on an interval (10–15s) and re-render table bodies from data; never re-fetch and diff whole HTML pages.
- Every async fetch has a timeout and renders an explicit error/"unknown" badge on failure — no permanent spinners by construction.
- Pause polling when the tab is hidden (`visibilitychange`), resume and refresh immediately when visible.
- Running durations tick client-side from a `data-started-at` attribute.

## Out of Scope

- System metrics page (step 09).
- WebSocket/SSE push; plain polling first, extend later if wanted.

## Tests

- MockMvc view tests: templates render for empty state, running build, mixed history.
- Escaping test: branch names with HTML/JS metacharacters render safely (legacy had injection risks).
- JavaScript logic that is non-trivial (duration formatting, poll scheduling) should live in small pure functions; test via MockMvc-rendered attributes or keep trivially simple.

## Acceptance Criteria

- `./gradlew ktlintFormat` then `./gradlew build` is green.
- Manual smoke test with a real build: status flips pending → running → success in the open browser tab without a manual reload, and a killed server results in error badges, not spinners.
  Document the result in this file.
