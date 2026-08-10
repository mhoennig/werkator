> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR-documentation is not maintained along with new PRs — treat it as a snapshot, not as current documentation.

## The Problem

The legacy host (vm2176) had a `docker-prune.timer` that cleaned up Docker every night at 02:00, before the nightly runs.
Without it, unused images and stopped containers accumulate on the build host until the disk fills up.
The rewrite did not port this timer, and it had to be installed by hand.

## Non-Goals

- No volume pruning: the legacy `docker system prune -af --volumes` would delete GitTally's per-repository Gradle cache volumes every night; the new cleanup keeps volumes.
- No configurable schedule; 02:00 host time is hardcoded like on the legacy host.
- No pruning from within the GitTally server process — the cleanup stays a plain systemd timer.

## The Scenarios

### Feature: nightly Docker cleanup installed with the service

#### Background

- The legacy unit (vm2176, Hostsharing-internal) ran `docker system prune -af --volumes` at 02:00 with `Persistent=true`.
- GitTally's Docker builds maintain a per-repository Gradle cache volume that must survive a cleanup.

#### Scenario#000.01: `init --systemd` generates the cleanup units alongside the service unit

So that the cleanup is part of every GitTally installation instead of a manual step.

- **Given** a repository initialized with `init --systemd`
- **When** the generation finishes
- **Then** `.git/gittally/gittally-docker-prune.service` and `.git/gittally/gittally-docker-prune.timer` exist
  - **and** the printed install commands link and enable the timer together with the service

##### Verified by

- [InitCommandTest — "--systemd also generates the nightly Docker cleanup timer"](../../src/test/kotlin/de/hoennig/gittally/commands/InitCommandTest.kt)

#### Scenario#000.02: The cleanup prunes containers and images but never volumes

So that nightly builds start from fresh images while the Gradle caches survive.

- **Given** the generated `gittally-docker-prune.service`
- **When** the timer fires at 02:00 host time
- **Then** it runs `docker system prune -af` (stopped containers, unused images, networks, dangling build cache)
  - **and** it passes no `--volumes` flag
  - **and** on hosts without a `docker` CLI the run is skipped, not failed

##### Verified by

- [SystemdServiceFilesTest — "prune service cleans containers and images but never volumes"](../../src/test/kotlin/de/hoennig/gittally/commands/SystemdServiceFilesTest.kt)
- [SystemdServiceFilesTest — "prune timer fires nightly at 02:00 and catches up after downtime"](../../src/test/kotlin/de/hoennig/gittally/commands/SystemdServiceFilesTest.kt)

## The Solution

`SystemdServiceFiles` gets `pruneServiceContent()`/`pruneTimerContent()` next to the existing unit generation, and `createSystemdFiles` writes both files and extends the printed install commands.
The unit names are host-global (`gittally-docker-prune.*`, no repository name): Docker is a host-wide resource, so several GitTally instances on one host share one timer — every `init --systemd` regenerates the same content and the symlinks coincide.
Running containers and their images are never pruned by Docker, so an in-flight build is safe even if it overlaps 02:00.
Documented in `docs/deployment.md` (section "Nightly Docker Cleanup").

## Additional Changes

- None.

## Follow-up PRs

- None planned.
