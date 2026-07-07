# Step 04: Build Executor

Prerequisites: steps 01, 02, 03.
Read `README.md` and `00-legacy-analysis.md` first.

## Goal

Asynchronous build execution with log capture, cancellation, and immediately visible status transitions.
This step fixes the legacy defect that nothing could observe status changes while a build ran.

## Design

Create package `de.hoennig.gittally.build` (extends step 01):

- `BuildExecutor` service; one build at a time (a `ReentrantLock` or single-thread executor replaces the legacy flock file).
- `startBuild(branch, commit)` runs asynchronously and returns immediately; expose `currentBuild(): RunningBuild?`.
- Execution sequence per build:
  1. Record `PENDING`, then `RUNNING` via `BuildResultRepository`; publish each transition to Gitea (non-fatal on failure).
  2. Run the branch's `cleanCommand`, then `buildCommand` (from the merged `branches` config) via `ProcessBuilder` with the branch name in the environment as `branch`.
  3. Stream stdout/stderr to the configured log files in a working/staging directory, plus a combined live log file.
  4. On exit: record `SUCCESS`/`FAILED`, publish status, hand the staging directory to the artifact store (step 05 interface; use a stub interface now if 05 is not done).
- Cancellation: `cancel()` flag checked by a monitor; destroy the process tree (`ProcessHandle.descendants()`, TERM-wait-KILL like legacy `terminate_process_tree`); record `CANCELLED`.
- Status transitions must be readable at any time via the repository — no in-memory-only state.

Emit a Spring `ApplicationEvent` on every status transition so the UI (step 08) can later push updates without polling internals.

## Out of Scope

- Docker execution (step 11); native `ProcessBuilder` only, but keep a `BuildRunner` interface so Docker can plug in.
- Scheduling and branch selection (step 06).
- Artifact index HTML (step 05/08).

## Config

Uses existing `branches.<name>.buildCommand/cleanCommand/stdoutLog/stderrLog`.
Consider `builds.timeout` only if trivial; otherwise defer.

## Tests

- Fake commands (`sh -c 'echo ok'`, failing command, sleeping command) in temp dirs.
- Status sequence assertions: pending → running → success/failed/cancelled, each persisted before/after execution.
- Cancellation kills a sleeping process tree and records `CANCELLED`.
- Log files contain captured output; live log grows during the build (poll in test).
- Gitea publishing mocked with MockK; a Gitea failure must not fail the build.

## Acceptance Criteria

- `./gradlew ktlintFormat` then `./gradlew build` is green.
- While a test build sleeps, the repository reports `RUNNING` — proven by a test.

## Execution Notes (done 2026-07-07)

Implemented as designed in `de.hoennig.gittally.build`; build green, 18 new tests
(`BuildExecutorTest`, `ProcessBuildRunnerTest`, `ArtifactKeysTest`, plus two new `FileBuildResultRepositoryTest` cases).
Deviations and decisions:

- One-build-at-a-time uses a single-thread worker executor; a second `startBuild` queues and stays `PENDING` until the first finishes.
  `PENDING` is persisted synchronously in `startBuild`, so a queued build is immediately visible.
- `BuildResultRepository` gained `updateByArtifactKey(...)` (extends step 01) so transitions always hit the exact entry, even when a newer `PENDING` entry of the same branch was queued meanwhile.
- `cancel()` sets the flag and destroys the process tree directly (TERM, 2s wait, KILL via `ProcessHandle.descendants()`); no separate monitor thread.
  The flag is checked before each command and after `waitFor`, so a cancel between clean and build commands still records `CANCELLED`.
- Artifact key naming (`ArtifactKeys`) was needed here because `BuildResult` requires a key; it follows the legacy scheme (sanitized name + 12-char SHA-256 prefix + sanitized ISO timestamp + hash) using the UTC `Instant`, not local time.
  Step 05 should reuse it rather than re-implement.
- `ArtifactStore` is an interface in the `build` package with a logging `NoOpArtifactStore` placeholder; step 05 replaces the placeholder and implements the real store in `de.hoennig.gittally.artifacts`.
- The combined live log is `build.log` inside the per-build staging directory (a temp dir exposed via `RunningBuild.stagingDir`/`liveLogFile`); output is flushed per read chunk so the log grows while the build runs.
- Commands run via `bash -c` with the branch name in the environment as `branch`, like legacy `run_build_command`; a failing `cleanCommand` fails the build without running `buildCommand`.
- `BuildResultRepository` is wired as a Spring bean (`BuildConfiguration`) at `.git/gittally/build-results.json` relative to the working directory, matching how `ConfigLoader` resolves the override file; `git rev-parse --git-path` style worktree resolution can come later if needed.
- Gitea `target_url` is not published yet; the artifact page URL scheme only exists from step 07 on.
- `builds.timeout` was deferred (not trivial alongside cancellation semantics); no config keys were added or changed.

## Amendment: Concurrent Builds and Per-Branch Worktrees (2026-07-07)

Refactored on request, superseding parts of the notes above:

- Builds now run concurrently up to the new config key `builds.maxConcurrent` (default 1), enforced by a global semaphore sized on first use (changing it requires a restart).
- At most one build per branch at a time, enforced by one serial worker per branch; a second build of the same branch queues as `PENDING` and runs afterwards ("finish, then next").
  Whether a new commit should instead cancel the branch's running build is a later, possibly configurable decision (see step 06).
- Each branch builds in its own reusable git worktree at `.git/gittally/worktrees/<branchKey>` (`BranchWorkspaces`/`GitWorktreeWorkspaces`), checked out detached at the requested commit — the primary checkout is never touched.
  Reuse keeps incremental build caches; `cleanCommand` decides how much of them survives.
  `GitService` gained `worktreeAdd`, `worktreePrune`, and `checkoutDetached` for this.
- API change: `currentBuild()` became `currentBuilds(): List<RunningBuild>`, and `cancel()` became `cancel(artifactKey)`; a queued build can be cancelled too and is recorded `CANCELLED` when its worker picks it up.
- The Gitea `PENDING` status is now published synchronously in `startBuild`, so queued builds are visible in Gitea while they wait for a slot.
- Branch config is still loaded from the primary repository directory, not from the branch's checked-out `.gittally.yml`; honoring the branch's own committed config would be a separate decision.
