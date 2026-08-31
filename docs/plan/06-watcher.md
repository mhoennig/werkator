# Step 06: Watcher and Scheduling

Prerequisites: steps 01, 02, 04.
Read `README.md` and `00-legacy-analysis.md` first.

## Goal

Replace the legacy blocking main loop with a non-blocking, observable scheduler.

## Design

Create package `de.hoennig.werkator.watcher`:

- `Watcher` component with a fixed-delay poll cycle (Spring `@Scheduled` or a managed executor; enabled only in server/watch mode, not during CLI commands or tests).
- One poll cycle, never blocking on a build:
  1. `fetchOrigin()` (errors: log, publish watcher health state, retry next cycle — no internal retry-sleep loops like legacy `retry_origin_change_check`).
  2. Determine candidate branches: changed local branches, plus new origin branches within `watcher.newBranchMaxAge` (step 02 operations).
  3. Check auto-build time slots (below).
  4. Start builds for due branches via `startBuild(branch, commit)` (async).
     The executor prepares a per-branch worktree itself (step 04 amendment) — the watcher must never check out or reset the primary worktree.
     Multiple branches may build concurrently (`builds.maxConcurrent`); the executor already serializes builds of the same branch, so the watcher only has to avoid enqueueing a branch that is already pending or running.
  5. Run repository retention pruning and artifact pruning; also remove worktrees under `.git/werkator/worktrees/` of branches no longer on origin (`git worktree remove` or delete + `worktreePrune`).
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
Update `WerkatorConfig`, `InitCommand` templates, and `docs/configuration.md` together.

## Tests

- Poll cycle unit tests with MockK: branch selection precedence, skip-when-building, fetch failure resilience.
- Auto-build slot logic: slot matching, per-day dedup, state persistence.
- New-branch age filtering.
- Startup recovery: interrupted marking and re-enqueue (integration with steps 01/04 fakes).
- No test may sleep for real poll intervals; trigger cycles directly.

## Acceptance Criteria

- `./gradlew ktlintFormat` then `./gradlew build` is green.
- A test proves a poll cycle completes while a (fake) build is running.

## Execution Notes (done 2026-07-07)

Implemented as designed in `de.hoennig.werkator.watcher`; build green, 23 new tests
(`WatcherTest`, `AutoBuildStateTest`, plus new `DurationParserTest` and `GitServiceTest` cases).
Deviations and decisions:

- The poll loop is a managed single-thread `ScheduledExecutorService` with fixed delay, not Spring `@Scheduled`:
  scheduling annotations would run during CLI commands and tests.
  Nothing starts automatically; server/watch mode (step 07) must call `Watcher.start(workingDir)` explicitly,
  which runs the startup recovery and then polls immediately and on every `watcher.pollInterval`.
  `ApplicationContextTest` stays green and fast because constructing the beans schedules nothing.
- "Changed" is detected against the result repository (`latestFor(branch).commit != origin head`),
  gated by `hasNewCommits`, instead of resetting local refs like legacy:
  builds run detached in per-branch worktrees and never move local branch refs,
  so a legacy-style local-ref comparison would re-enqueue the same commit forever.
  Consequence: a failed build of a commit is not retried automatically (same as legacy) — step 10 adds the retry command.
- Queue-behind vs. cancel-on-new-commit: DEFERRED, keeping queue-behind semantics at watcher granularity.
  A branch whose latest build is PENDING or RUNNING is skipped; the new commit is picked up on a later cycle
  once the build finished. Making this per-branch configurable (cancel and rebuild instead) remains open,
  see also the step 04 amendment.
- Enqueue precedence per cycle: changed local branches, then recent new origin branches, then due auto-build slots;
  each branch at most once (an auto-build slot stays untriggered while its branch is pending/running and fires
  on a later cycle instead of being lost).
- Auto-build state lives in `.git/werkator/auto-builds.json` (`FileAutoBuildState`, replaces `auto-builds.tsv`);
  entries of past days are dropped on write. Slot matching (`AutoBuildSlots`) picks the latest slot at or before
  the current UTC time, like legacy `auto_build_check`. Only branches named in the `branches` config
  (other than `default`) can auto-build; `default.autoBuild.enabled` does not extend to unlisted branches.
- Startup recovery re-enqueues branches whose latest result is PENDING or INTERRUPTED and which still exist
  on origin, after a best-effort fetch (a failing fetch recovers from the last known origin state).
  A stale latest PENDING entry is marked INTERRUPTED before its replacement build is enqueued,
  because the executor queue does not survive a restart.
- Worktree cleanup deletes `.git/werkator/worktrees/<branchKey>` directories of branches gone from origin
  (never those of queued or running builds) and then calls `git worktree prune`.
- `DurationParser` was extended with `s`/`m` suffixes for `watcher.pollInterval` (it only knew `d`/`h`).
- Watcher health is exposed via `Watcher.state(): WatcherState` (running, last poll time, last fetch/poll error,
  queued branches) for the step 07 status endpoints; no events are published for it.
- A UTC `Clock` bean (`WatcherConfiguration`) makes slot matching and poll timestamps testable.
