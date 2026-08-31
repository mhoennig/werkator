> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR-documentation is not maintained along with new PRs — treat it as a snapshot, not as current documentation.

## The Problem

When the server is stopped (systemd SIGTERM) while a build is executing, the build process dies with the service.
`BuildExecutor.execute` then classified the shutdown-induced exit as FAILED, because only the `cancelled` flag was checked.
FAILED is terminal and not restartable, so the watcher's startup recovery did not re-enqueue the build.
Additionally, a red `failure` status was posted to Gitea for a commit that was never actually built to failure.
Observed on vm4006: a running Docker build died during a service restart and the commit silently stayed red.

## Non-Goals

- No change to explicit cancellation: a user-cancelled build stays CANCELLED and red.
- No handling of `kill -9`: an unclean kill still leaves a stale RUNNING result, which the existing `markStaleRunningAsInterrupted` startup recovery already covers.
- No draining of the Spring web layer or watcher — those already have their own shutdown hooks.

## The Scenarios

### Feature: builds interrupted by a server shutdown are recovered, not failed

#### Background

- INTERRUPTED and PENDING are restartable statuses; `Watcher.recoverOnStartup` re-enqueues the latest build of a branch in either status.
- FAILED is terminal and never re-enqueued.

#### Scenario#000.01: An executing build is recorded as INTERRUPTED on shutdown

So that a service restart never loses a build or marks its commit as failed.

- **Given** a build is executing
- **When** the application context closes (e.g. systemd SIGTERM)
- **Then** the build's process tree is terminated
  - **and** the result is persisted as INTERRUPTED, not FAILED, before the context finishes closing
  - **and** the startup recovery re-enqueues the branch on the next start

##### Verified by

- [BuildExecutorTest — "shutdown kills an executing build and records INTERRUPTED, not FAILED"](../../src/test/kotlin/de/hoennig/werkator/build/BuildExecutorTest.kt)

#### Scenario#000.02: A queued build stays PENDING over a shutdown

So that queued builds survive a restart the same way executing builds do.

- **Given** a build is queued behind an executing build
- **When** the application context closes
- **Then** the queued build starts no process and gets no status transition
  - **and** it stays PENDING for the startup recovery, which re-enqueues it after the restart

##### Verified by

- [BuildExecutorTest — "a build still queued at shutdown stays PENDING for the startup recovery"](../../src/test/kotlin/de/hoennig/werkator/build/BuildExecutorTest.kt)

#### Scenario#000.03: No failure status is posted to Gitea for an interrupted build

So that a commit does not turn red because of a service restart.

- **Given** a build transitions to INTERRUPTED
- **When** the status is published to Gitea
- **Then** the commit-status state is `pending` (description "build interrupted"), not `failure`
  - **and** the re-enqueued build posts `pending` again after the restart

##### Verified by

- [GiteaStateMappingTest](../../src/test/kotlin/de/hoennig/werkator/gitea/GiteaStateMappingTest.kt)
- [GiteaClientTest — "maps every build status to the documented Gitea state"](../../src/test/kotlin/de/hoennig/werkator/gitea/GiteaClientTest.kt)

## The Solution

`BuildExecutor` gets a `shuttingDown` flag set by a `ContextClosedEvent` listener (`shutdown()`).
The listener terminates the process trees of all executing builds and waits (bounded, 20s) until their workers have persisted the INTERRUPTED results — the event fires before bean destruction, so the repository and the Gitea client are still usable.
The final-status classification checks the flag: a non-zero exit or an exception during shutdown becomes INTERRUPTED instead of FAILED; explicit cancellation and a clean SUCCESS still win.
Workers that pick up a queued build during shutdown return without any transition, leaving it PENDING.
The command-start and between-commands gates also check the flag, so no new process is spawned (and orphaned) once shutdown began.
`GiteaStateMapping` now publishes INTERRUPTED as `pending`: an interrupted build is re-enqueued by the startup recovery, so red would be wrong.

The hard invariants hold: nothing is scheduled (the listener is a lifecycle callback), and the listener is a no-op in CLI runs because `build`/`retry` block until completion before the context closes.
On a Ctrl-C during a CLI build the same logic applies and correctly records INTERRUPTED.

## Additional Changes

- Documented the shutdown behavior in the [architecture skill](../../.claude/skills/architecture/SKILL.md).

## Follow-up PRs

- None planned.
