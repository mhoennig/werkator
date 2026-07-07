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
