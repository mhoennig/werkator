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
  4. Start builds for due branches via `startBuild(branch, commit)` (async).
     The executor prepares a per-branch worktree itself (step 04 amendment) — the watcher must never check out or reset the primary worktree.
     Multiple branches may build concurrently (`builds.maxConcurrent`); the executor already serializes builds of the same branch, so the watcher only has to avoid enqueueing a branch that is already pending or running.
  5. Run repository retention pruning and artifact pruning; also remove worktrees under `.git/gittally/worktrees/` of branches no longer on origin (`git worktree remove` or delete + `worktreePrune`).
- Decide here (or defer with a note): when a new commit arrives for a branch whose build is still running, keep the current queue-behind behavior or cancel the running build and start fresh — this may become a per-branch config option.
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
