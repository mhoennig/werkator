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
- `/current`: running builds with live log view (incremental fetch via the step 07 current-builds API), a cancel button per build, and a clear "no build running" state.
  Remember there can be several running builds (one per branch, up to `builds.maxConcurrent`); the view must list all of them, not assume a single one.
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

## Implementation Notes (2026-07-07)

Implemented as designed: Thymeleaf templates (`fragments`, `builds`, `current`, `artifact`) rendered by `UiController`, one hand-written `static/gittally.js`, one `static/gittally.css` (loosely ported legacy look incl. dark mode and the mobile card layout), and the legacy favicon.
Pages render the full state server-side and work without JavaScript; the script polls the JSON API (tables 10s, current builds and log tails 3s) and re-renders table bodies from data.
Every fetch runs with an 8s timeout; a failure flips the nav-row indicator to an explicit `error` badge and dims the stale table — there is no loading state at all, so no spinner can get stuck.
Polling pauses on `visibilitychange` and refreshes immediately when the tab becomes visible; running durations tick client-side from `data-started-at`.

Deviations and decisions:

- The tables show one `Started` column instead of the legacy `Commit Time` + `Status Time` pair, and client-side column sorting was not ported; the API delivers newest-first.
- Status badges show the repository status; the per-row Gitea lookup (`/control/status` per commit) was deliberately not ported — that fan-out caused the legacy stuck spinners. `GET /api/status/{commit}` remains available.
- The control token is embedded as a `<meta>` tag in every rendered page (legacy embedded its cancel token in the cancel form the same way); `gittally.js` sends it via `X-GitTally-Token` for restart/cancel/delete.
- The restart endpoint moved from `POST /api/builds/{branch}/restart` to `POST /api/builds/restart?branch=…` so branch names with slashes work (resolves the step 07 deviation note).
- Artifact links render whenever a result has an artifact key; the artifact index page itself explains a pruned/missing artifact directory instead of a per-row existence check.
- `/builds/{artifactKey}` renders logs (top-level files) and the topmost `reports/**/index.html` pages from the artifact store; nested index pages below an already-listed one are skipped like legacy. Raw directory browsing is not offered.
- The build command shown on the artifact page is the currently configured one — the command effective at build time is not persisted.
- On `/current`, a build that leaves the running list keeps its card, marked `finished` with a link to its result page; its initial server render shows an empty log (the script fetches from offset 0).
- JS builds all DOM via `createElement`/`textContent`, so re-rendered data cannot inject markup; server-side escaping is covered by a MockMvc test with a hostile branch name.
- New config key `server.impressumUrl` (empty hides the footer link); the footer version comes from Spring Boot `buildInfo()` (`BuildProperties`, build time excluded for repeatability) with a `dev` fallback.
- `UiFormats` (Kotlin) and `gittally.js` intentionally produce the same timestamp/duration display formats.

Manual smoke test (2026-07-07): scratch repository with a bare origin, `pollInterval: 5s`, and a 25s build command; server on port 18982, observed through a real browser tab.
After `git push`, the open Latest tab showed the new build without reload and its badge flipped `running` → `success` live (`pending` was too short to sample; the row itself appeared via polling).
`/current` showed the build card with streaming live log, ticking duration, and cancel button; on completion the card flipped to `finished` with a working link to the artifact page (status badge, build command, three log links, `reports/demo/index.html` served with no-cache headers).
Killing the server flipped the indicator to a red `error` badge (fetch failure in the tooltip) and dimmed the stale table — zero spinners (verified through a TCP proxy so the tab outlived the process); restarting the server brought `live` back.
The 375px viewport stacked rows as labeled cards, and the History view correctly offers delete but no restart.

Addendum (2026-07-07, after step 12): the legacy Branches view had not been ported; it was added later on request.
`/branches` (nav: Latest | Branches | History | Current | System) lists every branch with its latest build, or an `unknown` row when never built — main/master first, then flat names, then hierarchical names, like legacy.
Deviation: the listing enumerates origin branches instead of legacy's local branches, because the new watcher's branch universe is origin (local refs never move and origin-only branches do get built).
`POST /api/builds/restart` falls back to the branch's origin head when it has no recorded build, so the Branches view can trigger first builds like legacy.

Addendum (2026-07-07): the legacy per-page reload button (`⟳`, top right) was also re-added on request, next to the live indicator.
On polling pages it triggers an immediate data refresh via the page's poller; pages without a poller (artifact index) reload fully.

Addendum (2026-07-07): all links that leave the GitTally UI open in a new tab (`target="_blank" rel="noopener noreferrer"`).
This already held for Gitea branch/commit links and the footer; it was added for the artifact page's log and report links, whose targets have no navigation.
Links between GitTally pages (nav, artifact index) stay in the same tab.

Addendum (2026-08-10): the artifacts column carries the whole build-reachability logic, and the nav lost its `Current` entry.
The permanent `🔗` link is rendered on the build it resolves to — the branch's latest green build — on every build table, instead of on each row of a branch with any green build.
A `📡` link to `/current` appears while a build runs; `/current` itself stays a routable page, it just has no tab of its own anymore.
