> **WARNING:** This document describes only the change applied in this PR.
> It may already be outdated once the next PR is merged.
> Historic PR-documentation is not maintained along with new PRs — treat it as a snapshot, not as current documentation.

## The Problem

Retention is count-based only: `artifacts.retentionPerBranch` keeps the newest N builds per branch, no matter how old they are.
The legacy script also supported an age limit (`GITTALLY_ARTIFACT_BUILD_RETENTION_PER_BRANCH=3d`), which the rewrite dropped — the migration doc declared the age suffix unsupported.
Without an age limit there is no way to bound how long stale build logs and reports stay on disk, e.g. on branches that build rarely.

## Non-Goals

- No either/or single config value like legacy (`3` *or* `3d`); count and age are separate keys that can be combined.
- No age pruning of a branch's newest build — a dormant branch keeps its last status and artifacts.
- No change to the existing pruning of branches deleted from origin.
- No size-based (bytes) retention.

## The Scenarios

### Feature: age-based build retention combinable with count-based retention

#### Background

- `artifacts.retentionMaxAge` takes the shared duration format (`s`/`m`/`h`/`d` suffix, like `watcher.newBranchMaxAge`); empty (the default) means no age limit.
- Pruning runs in the watcher poll cycle; artifact directories follow the surviving build results.

#### Scenario#000.01: Builds older than retentionMaxAge are pruned

So that stale logs and reports do not stay on disk indefinitely.

- **Given** `artifacts.retentionMaxAge: 30d`
  - **and** a branch with builds within the retention count, some older than 30 days
- **When** the watcher prunes results and artifacts
- **Then** the builds older than 30 days are dropped together with their artifacts
  - **and** the younger builds are kept.

##### Verified by

- [FileBuildResultRepositoryTest](../../src/test/kotlin/de/hoennig/gittally/build/FileBuildResultRepositoryTest.kt)
- [WatcherTest](../../src/test/kotlin/de/hoennig/gittally/watcher/WatcherTest.kt)

#### Scenario#000.02: Count and age combine as independent caps

So that one config can bound disk usage by count and staleness by age at the same time — which legacy could not.

- **Given** `artifacts.retentionPerBranch: 2` and `artifacts.retentionMaxAge: 30d`
- **When** the watcher prunes
- **Then** a build is kept only while it is among the branch's newest 2 builds **and** younger than 30 days
  - **and** a build violating either limit is dropped.

##### Verified by

- [FileBuildResultRepositoryTest](../../src/test/kotlin/de/hoennig/gittally/build/FileBuildResultRepositoryTest.kt)

#### Scenario#000.03: A branch's newest build is never age-pruned

So that a dormant branch keeps its last build status visible, matching the legacy age-retention behavior.

- **Given** a branch whose newest build is older than `retentionMaxAge`
- **When** the watcher prunes
- **Then** that newest build is kept regardless of its age.

##### Verified by

- [FileBuildResultRepositoryTest](../../src/test/kotlin/de/hoennig/gittally/build/FileBuildResultRepositoryTest.kt)

#### Scenario#000.04: keepLatestGreen shields the latest green build from the age limit

So that the permanent `/branches/<branch-key>/…` artifact links stay valid while newer builds fail, even under an age limit.

- **Given** `artifacts.keepLatestGreen: true` (the default)
  - **and** a branch whose latest green build is older than `retentionMaxAge`, followed by newer failed builds
- **When** the watcher prunes
- **Then** the latest green build and its artifacts are kept.

##### Verified by

- [FileBuildResultRepositoryTest](../../src/test/kotlin/de/hoennig/gittally/build/FileBuildResultRepositoryTest.kt)

#### Scenario#000.05: The default keeps existing behavior unchanged

So that existing installations are unaffected by the new key.

- **Given** `artifacts.retentionMaxAge` is unset or empty
- **When** the watcher prunes
- **Then** only the count-based retention applies, exactly as before this PR.

##### Verified by

- [FileBuildResultRepositoryTest](../../src/test/kotlin/de/hoennig/gittally/build/FileBuildResultRepositoryTest.kt) (pre-existing count-only prune tests)

## The Solution

- New config key `artifacts.retentionMaxAge`, parsed with the existing `DurationParser`; all three config sync points are updated (`GitTallyConfig`, the `init` templates, `docs/configuration.md`).
- `BuildResultRepository.prune` takes an optional `retentionCutoff: Instant?`; entries started before the cutoff are dropped, except each branch's newest entry and — with `keepLatestGreen` — the newest green entry.
- The `Watcher` computes the cutoff from its injected `Clock` on every poll cycle, so the repository stays clock-free and deterministic in tests, and config changes apply without restart like the other retention keys.
- Artifact directories need no separate handling: the artifact store already prunes by the surviving build results.

## Open Questions

- An invalid `retentionMaxAge` value (e.g. `30x`) surfaces as a poll-cycle error in the log and the watcher state, not as a startup failure — consistent with how `watcher.newBranchMaxAge` is handled.

## Additional Changes

- `docs/migration-from-legacy.md` now maps a legacy age value of `GITTALLY_ARTIFACT_BUILD_RETENTION_PER_BRANCH` to `artifacts.retentionMaxAge` instead of declaring the age suffix unsupported.
