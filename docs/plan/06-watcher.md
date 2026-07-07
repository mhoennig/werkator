# Step 06: Watcher and Scheduling

Prerequisites: steps 01, 02, 04.
Read `README.md` and `00-legacy-analysis.md` first.

## Goal

Replace the legacy blocking main loop with a non-blocking, observable scheduler.

## Design

Create package `de.hoennig.gittally.watcher`:

- `Watcher` component with a fixed-delay poll cycle (Spring `@Scheduled` or a managed executor; enabled only in server/watch mode, not during CLI commands or tests).
- One poll cycle, never blocking on a build:
  1. `fetchOrigin()` (errors: log, publish watcher health state, retry next cycle — no internal retry-sleep loops like legacy `retry_origin_change_check`).
  2. Determine candidate branches: changed local branches, plus new origin branches within `watcher.newBranchMaxAge` (step 02 operations).
  3. Check auto-build time slots (below).
  4. If the executor is idle, dequeue the next branch: checkout, reset to origin, `startBuild` (async).
  5. Run repository retention pruning and artifact pruning.
- Startup sequence (port of legacy recovery): mark stale running builds interrupted, then enqueue restartable branches.
- Auto-builds: per-branch `autoBuild.enabled` + `times` (UTC HH:MM) from the merged `branches` config.
  Persist "already triggered for slot/day" state via a small JSON file next to the build results (replaces `auto-builds.tsv`).
- Expose watcher state (last poll time, last fetch error, queue) for the UI/status endpoints.

## Out of Scope

- HTTP endpoints (step 07); expose state via a service bean only.
- Retry-failed-builds command (step 10 triggers it through the same queue).

## Config

New key `watcher.pollInterval` (e.g. `10s`, default matching legacy cadence).
Update `GitTallyConfig`, `InitCommand` templates, and `docs/configuration.md` together.

## Tests

- Poll cycle unit tests with MockK: branch selection precedence, skip-when-building, fetch failure resilience.
- Auto-build slot logic: slot matching, per-day dedup, state persistence.
- New-branch age filtering.
- Startup recovery: interrupted marking and re-enqueue (integration with steps 01/04 fakes).
- No test may sleep for real poll intervals; trigger cycles directly.

## Acceptance Criteria

- `./gradlew ktlintFormat` then `./gradlew build` is green.
- A test proves a poll cycle completes while a (fake) build is running.
